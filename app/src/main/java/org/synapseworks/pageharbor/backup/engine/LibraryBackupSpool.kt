package org.synapseworks.pageharbor.backup.engine

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.Collections
import kotlin.collections.AbstractList
import org.synapseworks.pageharbor.backup.format.BackupOcrManifest
import org.synapseworks.pageharbor.backup.format.BackupOcrLineChunkDescriptor
import org.synapseworks.pageharbor.backup.format.BackupSummary
import org.synapseworks.pageharbor.backup.format.PreparedBackupArchiveSource
import org.synapseworks.pageharbor.backup.format.PreparedBackupAsset
import org.synapseworks.pageharbor.backup.format.PreparedBackupAssetSource
import org.synapseworks.pageharbor.backup.format.RME_BACKUP_OCR_LINES_DIRECTORY

internal class FilePreparedBackupArchiveSource(
    private val spoolRoot: File,
    override val preparedSummary: BackupSummary,
    override val preparedOcrManifest: BackupOcrManifest,
    private val pageAssetIndex: File,
    private val sourceAssetIndex: File,
) : PreparedBackupArchiveSource, CancellationAwarePreparedBackupSource {
    @Volatile
    private var cancellationCheck: () -> Unit = {}

    override fun installCancellationCheck(check: () -> Unit) {
        cancellationCheck = check
    }

    override fun checkCancellation() = cancellationCheck()

    override fun openPreparedEntry(path: String): InputStream {
        val entriesRoot = File(spoolRoot, ENTRIES_DIRECTORY).canonicalFile
        val file = File(entriesRoot, path).canonicalFile
        if (!file.toPath().startsWith(entriesRoot.toPath()) || !file.isFile) {
            throw IOException("A prepared backup metadata entry is unavailable.")
        }
        return FileInputStream(file)
    }

    override fun preparedPageAssets(): PreparedBackupAssetSource = assetSequence(pageAssetIndex)

    override fun preparedSourceAssets(): PreparedBackupAssetSource = assetSequence(sourceAssetIndex)

    private fun assetSequence(index: File): PreparedBackupAssetSource =
        BackupAssetIndexSequence(index, ::checkCancellation)

    internal companion object {
        const val ENTRIES_DIRECTORY = "entries"
    }
}

private class BackupAssetIndexSequence(
    private val file: File,
    private val cancellationCheck: () -> Unit,
) : PreparedBackupAssetSource {
    private var activeIterator: BackupAssetIndexIterator? = null

    override fun iterator(): Iterator<PreparedBackupAsset> {
        check(activeIterator == null) { "A prepared backup asset index can only be consumed once." }
        return BackupAssetIndexIterator(file, cancellationCheck).also { activeIterator = it }
    }

    override fun close() {
        activeIterator?.close()
        activeIterator = null
    }
}

internal class BackupAssetIndexWriter(file: File) : AutoCloseable {
    private val output = DataOutputStream(BufferedOutputStream(FileOutputStream(file, false)))
    private var closed = false

    fun append(asset: PreparedBackupAsset, sourceFile: File) {
        check(!closed)
        writeString(output, asset.path)
        writeString(output, sourceFile.absolutePath)
        writeString(output, asset.mimeType)
        writeString(output, asset.sha256)
        output.writeLong(asset.byteLength)
        output.writeInt(asset.width ?: ABSENT_DIMENSION)
        output.writeInt(asset.height ?: ABSENT_DIMENSION)
    }

    override fun close() {
        if (!closed) {
            closed = true
            output.close()
        }
    }
}

internal class BackupLineChunkIndex(
    private val file: File,
) : AutoCloseable {
    private val output = DataOutputStream(BufferedOutputStream(FileOutputStream(file, false)))
    var count: Int = 0
        private set
    private var closed = false

    fun append(recordCount: Int, byteLength: Long) {
        check(!closed)
        output.writeInt(recordCount)
        output.writeLong(byteLength)
        count += 1
    }

    fun asList(): List<BackupOcrLineChunkDescriptor> {
        close()
        return DiskBackedLineChunkList(file, count)
    }

    override fun close() {
        if (!closed) {
            closed = true
            output.close()
        }
    }
}

/** List contract required by the format model, backed by a fixed-width operation-owned disk index. */
private class DiskBackedLineChunkList(
    private val file: File,
    override val size: Int,
) : AbstractList<BackupOcrLineChunkDescriptor>() {
    override fun get(index: Int): BackupOcrLineChunkDescriptor {
        checkElementIndex(index, size)
        val values = RandomAccessFile(file, "r").use { input ->
            input.seek(index.toLong() * LINE_CHUNK_INDEX_RECORD_BYTES)
            input.readInt() to input.readLong()
        }
        return BackupOcrLineChunkDescriptor(
            path = "$RME_BACKUP_OCR_LINES_DIRECTORY/${index.toString().padStart(6, '0')}.jsonl",
            recordCount = values.first,
            byteLength = values.second,
        )
    }
}

private class BackupAssetIndexIterator(
    file: File,
    private val cancellationCheck: () -> Unit,
) : Iterator<PreparedBackupAsset>, AutoCloseable {
    private val input = DataInputStream(BufferedInputStream(FileInputStream(file)))
    private var next: PreparedBackupAsset? = null
    private var finished = false

    override fun hasNext(): Boolean {
        if (next == null && !finished) next = readNext()
        return next != null
    }

    override fun next(): PreparedBackupAsset {
        if (!hasNext()) throw NoSuchElementException()
        return requireNotNull(next).also { next = null }
    }

    private fun readNext(): PreparedBackupAsset? {
        val path = try {
            cancellationCheck()
            readString(input)
        } catch (_: EOFException) {
            finished = true
            input.close()
            return null
        } catch (failure: Throwable) {
            finished = true
            runCatching(input::close)
            throw failure
        }
        return try {
            val sourcePath = readString(input)
            val mimeType = readString(input)
            val sha256 = readString(input)
            val byteLength = input.readLong()
            val width = input.readInt().takeUnless { it == ABSENT_DIMENSION }
            val height = input.readInt().takeUnless { it == ABSENT_DIMENSION }
            PreparedBackupAsset(
                path = path,
                mimeType = mimeType,
                sha256 = sha256,
                byteLength = byteLength,
                width = width,
                height = height,
                openStream = { FileInputStream(sourcePath) },
            )
        } catch (failure: Throwable) {
            finished = true
            runCatching(input::close)
            throw failure
        }
    }

    override fun close() {
        if (!finished) {
            finished = true
            input.close()
        }
    }
}

private fun writeString(output: DataOutputStream, value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    require(bytes.size <= MAXIMUM_INDEX_STRING_BYTES)
    output.writeInt(bytes.size)
    output.write(bytes)
}

private fun readString(input: DataInputStream): String {
    val size = input.readInt()
    if (size !in 0..MAXIMUM_INDEX_STRING_BYTES) throw IOException("A backup asset index is invalid.")
    val bytes = ByteArray(size)
    input.readFully(bytes)
    return bytes.toString(Charsets.UTF_8)
}

internal fun createPrivateSpoolDirectory(parent: File): File {
    if ((!parent.exists() && !parent.mkdirs()) || !parent.isDirectory) {
        throw IOException("The private backup spool is unavailable.")
    }
    recoverStaleBackupSpools(parent)
    val marker = File.createTempFile(".rme-backup-spool-", ".tmp", parent)
    if (!marker.delete() || !marker.mkdir()) throw IOException("The private backup spool is unavailable.")
    marker.setReadable(false, false)
    marker.setWritable(false, false)
    marker.setExecutable(false, false)
    marker.setReadable(true, true)
    marker.setWritable(true, true)
    marker.setExecutable(true, true)
    ActiveBackupSpools.register(marker)
    return marker
}

internal fun deleteSpoolDirectory(parent: File, spool: File): Boolean {
    val deleted = deleteDirectBackupSpool(parent, spool)
    if (deleted) ActiveBackupSpools.unregister(spool)
    return deleted
}

/** Removes only recognized, direct-child snapshot spools left by a dead process. */
internal fun recoverStaleBackupSpools(parent: File) {
    if (!parent.exists()) return
    if (!parent.isDirectory) throw IOException("The private backup spool is unavailable.")
    val children = parent.listFiles() ?: throw IOException("The private backup spool cannot be inspected.")
    children.forEach { child ->
        if (!BACKUP_SPOOL_NAME.matches(child.name) || !child.isDirectory ||
            Files.isSymbolicLink(child.toPath()) || ActiveBackupSpools.contains(child)
        ) {
            return@forEach
        }
        var deleted = false
        repeat(SPOOL_CLEANUP_ATTEMPTS) {
            if (!deleted) deleted = deleteDirectBackupSpool(parent, child)
        }
        if (!deleted) throw IOException("A stale private backup snapshot spool could not be removed.")
    }
}

private fun deleteDirectBackupSpool(parent: File, spool: File): Boolean = try {
    val canonicalParent = parent.canonicalFile
    val canonicalSpool = spool.canonicalFile
    canonicalSpool.parentFile == canonicalParent &&
        !Files.isSymbolicLink(spool.toPath()) &&
        (!canonicalSpool.exists() || canonicalSpool.deleteRecursively())
} catch (_: IOException) {
    false
} catch (_: SecurityException) {
    false
}

private object ActiveBackupSpools {
    private val paths = Collections.synchronizedSet(mutableSetOf<String>())

    fun register(spool: File) {
        paths += spool.canonicalPath
    }

    fun unregister(spool: File) {
        runCatching { paths -= spool.canonicalPath }
    }

    fun contains(spool: File): Boolean = runCatching { spool.canonicalPath in paths }.getOrDefault(true)
}

private const val ABSENT_DIMENSION = -1
private const val MAXIMUM_INDEX_STRING_BYTES = 16 * 1024
private const val LINE_CHUNK_INDEX_RECORD_BYTES = 12L
private const val SPOOL_CLEANUP_ATTEMPTS = 3
private val BACKUP_SPOOL_NAME = Regex("\\.rme-backup-spool-[0-9]+\\.tmp")

private fun checkElementIndex(index: Int, size: Int) {
    if (index !in 0 until size) throw IndexOutOfBoundsException("index=$index, size=$size")
}
