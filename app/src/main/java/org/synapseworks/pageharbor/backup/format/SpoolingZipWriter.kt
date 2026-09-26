package org.synapseworks.pageharbor.backup.format

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Streaming ZIP writer whose central directory is operation-owned disk state.
 *
 * [java.util.zip.ZipOutputStream] retains one object per entry until finish. This writer retains one
 * active entry and a fixed deflate/copy buffer; completed central-directory records go directly to
 * [centralDirectoryFile]. The destination remains owned by the caller.
 */
internal class SpoolingZipWriter(
    private val destination: OutputStream,
    private val centralDirectoryFile: File,
    private val cancellationCheck: () -> Unit = {},
    private val zip64EndEntryThreshold: Long = ZIP16_SENTINEL,
    private val zip64EndValueThreshold: Long = ZIP32_SENTINEL,
    private val zip64EntryValueThreshold: Long = ZIP32_SENTINEL,
    private val cleanupCentralDirectory: (File) -> Boolean = ::deleteCentralDirectoryFile,
) : OutputStream(), AutoCloseable {
    private val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
    private val deflateBuffer = ByteArray(DEFLATE_BUFFER_BYTES)
    private var centralOutput: BufferedOutputStream? = null
    private var active: ActiveEntry? = null
    private var archiveOffset = 0L
    private var entryCount = 0L
    private var finished = false
    private var closed = false
    private var resourcesClosed = false

    init {
        require(zip64EndEntryThreshold in 1..ZIP16_SENTINEL)
        require(zip64EndValueThreshold in 1..ZIP32_SENTINEL)
        require(zip64EntryValueThreshold in 1..ZIP32_SENTINEL)
        try {
            centralDirectoryFile.parentFile?.let { parent ->
                if ((!parent.exists() && !parent.mkdirs()) || !parent.isDirectory) {
                    throw IOException("The ZIP central-directory workspace is unavailable.")
                }
            }
            centralOutput = BufferedOutputStream(FileOutputStream(centralDirectoryFile, false))
            makeOwnerOnly(centralDirectoryFile)
        } catch (failure: Throwable) {
            val deleted = try {
                !centralDirectoryFile.exists() || centralDirectoryFile.delete()
            } catch (_: SecurityException) {
                false
            }
            if (!deleted) {
                failure.addSuppressed(IOException("The ZIP central-directory spool could not be removed."))
            }
            deflater.end()
            throw failure
        }
    }

    fun putNextEntry(path: String, maximumUncompressedBytes: Long) {
        checkOpen()
        check(!finished) { "The ZIP archive is already finished." }
        check(active == null) { "The previous ZIP entry is still open." }
        require(maximumUncompressedBytes >= 0L)
        cancellationCheck()
        val name = path.toByteArray(StandardCharsets.UTF_8)
        if (name.isEmpty() || name.size > ZIP16_MAXIMUM) {
            throw IOException("A ZIP entry name is too large.")
        }
        val localOffset = archiveOffset
        val reserveZip64Version = maximumUncompressedBytes >= ZIP32_SENTINEL
        writeArchiveInt(LOCAL_FILE_HEADER_SIGNATURE)
        // The final sizes are not known on this non-seekable stream. Keep the local header in the
        // conventional data-descriptor form. A ZIP64 local extra containing placeholder zeroes is
        // interpreted as an exact zero length by sequential readers such as ZipInputStream.
        writeArchiveShort(if (reserveZip64Version) ZIP64_VERSION else ZIP_VERSION)
        writeArchiveShort(GENERAL_PURPOSE_FLAGS)
        writeArchiveShort(DEFLATE_METHOD)
        writeArchiveShort(DOS_TIME)
        writeArchiveShort(DOS_DATE)
        writeArchiveInt(0)
        writeArchiveInt(0)
        writeArchiveInt(0)
        writeArchiveShort(name.size)
        writeArchiveShort(0)
        writeArchive(name, 0, name.size)
        deflater.reset()
        active = ActiveEntry(
            name = name,
            localHeaderOffset = localOffset,
            maximumUncompressedBytes = maximumUncompressedBytes,
        )
    }

    override fun write(value: Int) {
        val single = byteArrayOf(value.toByte())
        write(single, 0, 1)
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        checkOpen()
        val entry = active ?: throw IOException("No ZIP entry is open.")
        if (offset < 0 || length < 0 || offset > buffer.size - length) {
            throw IndexOutOfBoundsException()
        }
        if (length == 0) return
        cancellationCheck()
        val newLength = addExact(entry.uncompressedBytes, length.toLong())
        if (newLength > entry.maximumUncompressedBytes) {
            throw IOException("A ZIP entry exceeds its declared maximum size.")
        }
        entry.crc.update(buffer, offset, length)
        entry.uncompressedBytes = newLength
        deflater.setInput(buffer, offset, length)
        while (!deflater.needsInput()) drainDeflater(entry, finishing = false)
    }

    fun closeEntry() {
        checkOpen()
        val entry = active ?: throw IOException("No ZIP entry is open.")
        deflater.finish()
        while (!deflater.finished()) drainDeflater(entry, finishing = true)
        val zip64Size = entry.compressedBytes >= zip64EntryValueThreshold ||
            entry.uncompressedBytes >= zip64EntryValueThreshold
        val zip64Descriptor = entry.compressedBytes >= ZIP32_SENTINEL ||
            entry.uncompressedBytes >= ZIP32_SENTINEL
        if (zip64Descriptor) {
            writeArchiveInt(DATA_DESCRIPTOR_SIGNATURE)
            writeArchiveInt(entry.crc.value)
            writeArchiveLong(entry.compressedBytes)
            writeArchiveLong(entry.uncompressedBytes)
        } else {
            writeArchiveInt(DATA_DESCRIPTOR_SIGNATURE)
            writeArchiveInt(entry.crc.value)
            writeArchiveInt(entry.compressedBytes)
            writeArchiveInt(entry.uncompressedBytes)
        }
        cancellationCheck()
        writeCentralDirectoryRecord(entry, zip64Size)
        entryCount = addExact(entryCount, 1L)
        active = null
    }

    fun finish() {
        checkOpen()
        check(!finished) { "The ZIP archive is already finished." }
        check(active == null) { "A ZIP entry is still open." }
        cancellationCheck()
        closeCentralOutput()
        val centralOffset = archiveOffset
        val centralSize = centralDirectoryFile.length()
        FileInputStream(centralDirectoryFile).use { raw ->
            val input = BufferedInputStream(raw)
            val buffer = ByteArray(CENTRAL_COPY_BUFFER_BYTES)
            while (true) {
                cancellationCheck()
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) writeArchive(buffer, 0, count)
            }
        }
        val needsZip64End = entryCount >= zip64EndEntryThreshold ||
            centralSize >= zip64EndValueThreshold || centralOffset >= zip64EndValueThreshold
        if (needsZip64End) writeZip64End(centralOffset, centralSize)
        writeClassicEnd(centralOffset, centralSize, needsZip64End)
        destination.flush()
        finished = true
        deleteCentralDirectoryOrThrow()
    }

    override fun flush() = destination.flush()

    override fun close() {
        if (closed) return
        var failure: Throwable? = null
        if (!resourcesClosed) {
            resourcesClosed = true
            try {
                closeCentralOutput()
            } catch (closeFailure: Throwable) {
                failure = closeFailure
            }
            try {
                deflater.end()
            } catch (endFailure: Throwable) {
                if (failure == null) failure = endFailure else failure.addSuppressed(endFailure)
            }
        }
        if (!deleteCentralDirectory()) {
            val cleanupFailure = IOException("The ZIP central-directory spool could not be removed.")
            if (failure == null) failure = cleanupFailure else failure.addSuppressed(cleanupFailure)
        } else {
            closed = true
        }
        failure?.let { throw it }
    }

    private fun drainDeflater(entry: ActiveEntry, finishing: Boolean) {
        cancellationCheck()
        val count = deflater.deflate(deflateBuffer)
        if (count <= 0) {
            if (finishing || (!deflater.finished() && !deflater.needsInput())) {
                throw IOException("The ZIP deflater stopped making progress.")
            }
            return
        }
        writeArchive(deflateBuffer, 0, count)
        entry.compressedBytes = addExact(entry.compressedBytes, count.toLong())
    }

    private fun writeCentralDirectoryRecord(entry: ActiveEntry, zip64Size: Boolean) {
        val output = requireNotNull(centralOutput)
        val includeUncompressed = zip64Size
        val includeCompressed = zip64Size
        val includeOffset = entry.localHeaderOffset >= ZIP32_SENTINEL
        val zip64 = includeUncompressed || includeCompressed || includeOffset
        val extraPayloadBytes =
            (if (includeUncompressed) Long.SIZE_BYTES else 0) +
                (if (includeCompressed) Long.SIZE_BYTES else 0) +
                (if (includeOffset) Long.SIZE_BYTES else 0)
        val extraBytes = if (zip64) ZIP_EXTRA_HEADER_BYTES + extraPayloadBytes else 0
        val version = if (zip64) ZIP64_VERSION else ZIP_VERSION
        output.writeIntLe(CENTRAL_DIRECTORY_HEADER_SIGNATURE)
        output.writeShortLe(version)
        output.writeShortLe(version)
        output.writeShortLe(GENERAL_PURPOSE_FLAGS)
        output.writeShortLe(DEFLATE_METHOD)
        output.writeShortLe(DOS_TIME)
        output.writeShortLe(DOS_DATE)
        output.writeIntLe(entry.crc.value)
        output.writeIntLe(if (includeCompressed) ZIP32_SENTINEL else entry.compressedBytes)
        output.writeIntLe(if (includeUncompressed) ZIP32_SENTINEL else entry.uncompressedBytes)
        output.writeShortLe(entry.name.size)
        output.writeShortLe(extraBytes)
        output.writeShortLe(0)
        output.writeShortLe(0)
        output.writeShortLe(0)
        output.writeIntLe(0)
        output.writeIntLe(if (includeOffset) ZIP32_SENTINEL else entry.localHeaderOffset)
        output.write(entry.name)
        if (zip64) {
            output.writeShortLe(ZIP64_EXTRA_ID)
            output.writeShortLe(extraPayloadBytes)
            if (includeUncompressed) output.writeLongLe(entry.uncompressedBytes)
            if (includeCompressed) output.writeLongLe(entry.compressedBytes)
            if (includeOffset) output.writeLongLe(entry.localHeaderOffset)
        }
    }

    private fun writeZip64End(centralOffset: Long, centralSize: Long) {
        val zip64EndOffset = archiveOffset
        writeArchiveInt(ZIP64_END_SIGNATURE)
        writeArchiveLong(ZIP64_END_PAYLOAD_BYTES)
        writeArchiveShort(ZIP64_VERSION)
        writeArchiveShort(ZIP64_VERSION)
        writeArchiveInt(0)
        writeArchiveInt(0)
        writeArchiveLong(entryCount)
        writeArchiveLong(entryCount)
        writeArchiveLong(centralSize)
        writeArchiveLong(centralOffset)
        writeArchiveInt(ZIP64_LOCATOR_SIGNATURE)
        writeArchiveInt(0)
        writeArchiveLong(zip64EndOffset)
        writeArchiveInt(1)
    }

    private fun writeClassicEnd(centralOffset: Long, centralSize: Long, zip64: Boolean) {
        writeArchiveInt(END_OF_CENTRAL_DIRECTORY_SIGNATURE)
        writeArchiveShort(0)
        writeArchiveShort(0)
        writeArchiveShort(if (zip64) ZIP16_SENTINEL else entryCount)
        writeArchiveShort(if (zip64) ZIP16_SENTINEL else entryCount)
        writeArchiveInt(if (zip64) ZIP32_SENTINEL else centralSize)
        writeArchiveInt(if (zip64) ZIP32_SENTINEL else centralOffset)
        writeArchiveShort(0)
    }

    private fun writeArchiveShort(value: Int) {
        destination.write(value and 0xff)
        destination.write((value ushr 8) and 0xff)
        archiveOffset = addExact(archiveOffset, Short.SIZE_BYTES.toLong())
    }

    private fun writeArchiveShort(value: Long) = writeArchiveShort(value.toInt())

    private fun writeArchiveInt(value: Int) = writeArchiveInt(value.toLong() and UINT32_MASK)

    private fun writeArchiveInt(value: Long) {
        repeat(Int.SIZE_BYTES) { shift -> destination.write(((value ushr (shift * 8)) and 0xff).toInt()) }
        archiveOffset = addExact(archiveOffset, Int.SIZE_BYTES.toLong())
    }

    private fun writeArchiveLong(value: Long) {
        repeat(Long.SIZE_BYTES) { shift -> destination.write(((value ushr (shift * 8)) and 0xff).toInt()) }
        archiveOffset = addExact(archiveOffset, Long.SIZE_BYTES.toLong())
    }

    private fun writeArchive(buffer: ByteArray, offset: Int, length: Int) {
        destination.write(buffer, offset, length)
        archiveOffset = addExact(archiveOffset, length.toLong())
    }

    private fun closeCentralOutput() {
        centralOutput?.let { output ->
            centralOutput = null
            output.close()
        }
    }

    private fun deleteCentralDirectoryOrThrow() {
        if (!deleteCentralDirectory()) {
            throw IOException("The ZIP central-directory spool could not be removed.")
        }
    }

    private fun deleteCentralDirectory(): Boolean {
        repeat(CLEANUP_ATTEMPTS) {
            if (cleanupCentralDirectory(centralDirectoryFile)) return true
        }
        return false
    }

    private fun checkOpen() = check(!closed) { "The ZIP writer is closed." }

    private data class ActiveEntry(
        val name: ByteArray,
        val localHeaderOffset: Long,
        val maximumUncompressedBytes: Long,
        val crc: CRC32 = CRC32(),
        var uncompressedBytes: Long = 0L,
        var compressedBytes: Long = 0L,
    )
}

internal fun createPrivateCentralDirectoryFile(parent: File): File {
    if ((!parent.exists() && !parent.mkdirs()) || !parent.isDirectory) {
        throw IOException("The ZIP central-directory workspace is unavailable.")
    }
    return File.createTempFile(".rme-zip-central-", ".bin", parent).also(::makeOwnerOnly)
}

private fun makeOwnerOnly(file: File) {
    file.setReadable(false, false)
    file.setWritable(false, false)
    file.setExecutable(false, false)
    file.setReadable(true, true)
    file.setWritable(true, true)
}

private fun deleteCentralDirectoryFile(file: File): Boolean = try {
    !file.exists() || file.delete()
} catch (_: SecurityException) {
    false
}

private fun OutputStream.writeShortLe(value: Int) {
    write(value and 0xff)
    write((value ushr 8) and 0xff)
}

private fun OutputStream.writeIntLe(value: Int) = writeIntLe(value.toLong() and UINT32_MASK)

private fun OutputStream.writeIntLe(value: Long) {
    repeat(Int.SIZE_BYTES) { shift -> write(((value ushr (shift * 8)) and 0xff).toInt()) }
}

private fun OutputStream.writeLongLe(value: Long) {
    repeat(Long.SIZE_BYTES) { shift -> write(((value ushr (shift * 8)) and 0xff).toInt()) }
}

private fun addExact(left: Long, right: Long): Long = try {
    Math.addExact(left, right)
} catch (failure: ArithmeticException) {
    throw IOException("A ZIP byte count overflowed.", failure)
}

private const val LOCAL_FILE_HEADER_SIGNATURE = 0x04034b50
private const val DATA_DESCRIPTOR_SIGNATURE = 0x08074b50
private const val CENTRAL_DIRECTORY_HEADER_SIGNATURE = 0x02014b50
private const val END_OF_CENTRAL_DIRECTORY_SIGNATURE = 0x06054b50
private const val ZIP64_END_SIGNATURE = 0x06064b50
private const val ZIP64_LOCATOR_SIGNATURE = 0x07064b50
private const val ZIP64_EXTRA_ID = 0x0001
private const val ZIP_VERSION = 20
private const val ZIP64_VERSION = 45
private const val GENERAL_PURPOSE_FLAGS = 0x0808
private const val DEFLATE_METHOD = 8
private const val DOS_TIME = 0
private const val DOS_DATE = 0x0021
private const val ZIP16_MAXIMUM = 0xffff
private const val ZIP16_SENTINEL = 0xffffL
private const val ZIP32_SENTINEL = 0xffff_ffffL
private const val UINT32_MASK = 0xffff_ffffL
private const val ZIP_EXTRA_HEADER_BYTES = 4
private const val ZIP64_END_PAYLOAD_BYTES = 44L
private const val DEFLATE_BUFFER_BYTES = 32 * 1024
private const val CENTRAL_COPY_BUFFER_BYTES = 32 * 1024
private const val CLEANUP_ATTEMPTS = 3
