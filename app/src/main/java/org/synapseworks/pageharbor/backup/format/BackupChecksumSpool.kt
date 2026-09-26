package org.synapseworks.pageharbor.backup.format

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.PriorityQueue

/** Operation-owned external sorter for checksum records. */
internal class BackupChecksumSpool(
    private val limits: BackupFormatLimits,
    private val cancellationCheck: () -> Unit = {},
    private val root: File,
    private val cleanup: (File) -> Boolean = ::deleteChecksumDirectory,
) : AutoCloseable {
    private val inputFile = File(root, "records.bin")
    private val output: DataOutputStream
    private var recordCount = 0
    private var inputClosed = false
    private var closed = false

    init {
        output = try {
            DataOutputStream(BufferedOutputStream(FileOutputStream(inputFile, false)))
        } catch (failure: Throwable) {
            if (!deleteChecksumDirectory(root)) {
                failure.addSuppressed(IOException("The checksum spool could not be removed."))
            }
            throw failure
        }
    }

    fun append(checksum: BackupChecksum) {
        check(!inputClosed && !closed)
        cancellationCheck()
        validateSpoolChecksum(checksum)
        if (recordCount >= limits.maximumEntryCount - 1) {
            limitExceeded("The checksum ledger has too many records.")
        }
        writeRecord(output, checksum)
        recordCount += 1
    }

    fun writeLedger(destination: java.io.OutputStream) {
        check(!closed)
        finishInput()
        if (recordCount == 0) {
            throw backupFailure(BackupFormatFailure.INVALID_LEDGER, "The checksum ledger is empty.")
        }
        validateCanonicalUniqueness()
        val runs = sortedRuns(SortOrder.PATH)
        try {
            BackupChecksumLedger.writeSorted(destination, limits) { accept ->
                mergeRuns(runs, SortOrder.PATH, accept)
            }
        } finally {
            runs.forEach { it.delete() }
        }
    }

    override fun close() {
        if (closed) return
        var failure: Throwable? = null
        try {
            finishInput()
        } catch (closeFailure: Throwable) {
            failure = closeFailure
        }
        var cleaned = false
        repeat(CLEANUP_ATTEMPTS) {
            if (!cleaned) {
                cleaned = try {
                    cleanup(root)
                } catch (_: SecurityException) {
                    false
                }
            }
        }
        if (!cleaned) {
            val cleanupFailure = IOException("The checksum spool could not be removed.")
            if (failure == null) failure = cleanupFailure else failure.addSuppressed(cleanupFailure)
        } else {
            closed = true
        }
        failure?.let { throw it }
    }

    private fun validateCanonicalUniqueness() {
        val runs = sortedRuns(SortOrder.CANONICAL)
        var previousCanonicalPath: String? = null
        try {
            mergeRuns(runs, SortOrder.CANONICAL) { checksum ->
                val canonical = BackupPathValidator.canonicalCollisionKey(checksum.path)
                if (canonical == previousCanonicalPath) {
                    throw backupFailure(
                        BackupFormatFailure.DUPLICATE_ENTRY,
                        "The backup contains duplicate or canonically colliding entry names.",
                    )
                }
                previousCanonicalPath = canonical
            }
        } finally {
            runs.forEach { it.delete() }
        }
    }

    private fun sortedRuns(order: SortOrder): List<File> {
        var generation = 0
        var runCount = 0
        DataInputStream(BufferedInputStream(FileInputStream(inputFile))).use { input ->
            while (true) {
                cancellationCheck()
                val records = ArrayList<SpoolRecord>(SORT_CHUNK_RECORDS)
                while (records.size < SORT_CHUNK_RECORDS) {
                    val checksum = readRecord(input) ?: break
                    records += SpoolRecord(checksum)
                }
                if (records.isEmpty()) return@use
                records.sortWith(order.comparator)
                writeRun(runFile(generation, runCount), records)
                runCount += 1
                if (records.size < SORT_CHUNK_RECORDS) return@use
            }
        }
        while (runCount > MAXIMUM_MERGE_FAN_IN) {
            val nextGeneration = generation + 1
            var nextRunCount = 0
            try {
                var groupStart = 0
                while (groupStart < runCount) {
                    cancellationCheck()
                    val groupEnd = minOf(groupStart + MAXIMUM_MERGE_FAN_IN, runCount)
                    val group = ArrayList<File>(groupEnd - groupStart)
                    for (index in groupStart until groupEnd) group += runFile(generation, index)
                    val merged = runFile(nextGeneration, nextRunCount)
                    DataOutputStream(BufferedOutputStream(FileOutputStream(merged, false))).use { destination ->
                        mergeRuns(group, order) { checksum -> writeRecord(destination, checksum) }
                    }
                    group.forEach { it.delete() }
                    nextRunCount += 1
                    groupStart = groupEnd
                }
            } catch (failure: Throwable) {
                throw failure
            }
            generation = nextGeneration
            runCount = nextRunCount
        }
        return List(runCount) { index -> runFile(generation, index) }
    }

    private fun mergeRuns(
        runs: List<File>,
        order: SortOrder,
        accept: (BackupChecksum) -> Unit,
    ) {
        val cursors = ArrayList<RunCursor>(runs.size)
        try {
            val queue = PriorityQueue<RunCursor> { left, right ->
                order.comparator.compare(requireNotNull(left.current), requireNotNull(right.current))
            }
            runs.forEachIndexed { index, file ->
                val cursor = RunCursor(index, file)
                cursors += cursor
                if (cursor.advance()) queue += cursor
            }
            while (queue.isNotEmpty()) {
                cancellationCheck()
                val cursor = queue.remove()
                accept(requireNotNull(cursor.current).checksum)
                if (cursor.advance()) queue += cursor
            }
        } finally {
            cursors.forEach { runCatching(it::close) }
        }
    }

    private fun writeRun(file: File, records: List<SpoolRecord>) {
        try {
            DataOutputStream(BufferedOutputStream(FileOutputStream(file, false))).use { destination ->
                records.forEach { record -> writeRecord(destination, record.checksum) }
            }
        } catch (failure: Throwable) {
            file.delete()
            throw failure
        }
    }

    private fun runFile(generation: Int, index: Int): File =
        File(root, "run-$generation-${index.toString().padStart(8, '0')}.bin")

    private fun finishInput() {
        if (!inputClosed) {
            output.close()
            inputClosed = true
        }
    }

    private enum class SortOrder(val comparator: Comparator<SpoolRecord>) {
        PATH(compareBy { it.checksum.path }),
        CANONICAL(compareBy<SpoolRecord> { it.canonicalPath }.thenBy { it.checksum.path }),
    }

    private data class SpoolRecord(val checksum: BackupChecksum) {
        val canonicalPath: String by lazy(LazyThreadSafetyMode.NONE) {
            BackupPathValidator.canonicalCollisionKey(checksum.path)
        }
    }

    private class RunCursor(
        val ordinal: Int,
        file: File,
    ) : AutoCloseable {
        private val input = DataInputStream(BufferedInputStream(FileInputStream(file)))
        var current: SpoolRecord? = null
            private set

        fun advance(): Boolean {
            current = readRecord(input)?.let(::SpoolRecord)
            return current != null
        }

        override fun close() = input.close()
    }
}

internal fun createPrivateChecksumDirectory(parent: File): File {
    if ((!parent.exists() && !parent.mkdirs()) || !parent.isDirectory) {
        throw IOException("The checksum spool workspace is unavailable.")
    }
    val marker = File.createTempFile(".rme-checksums-", ".tmp", parent)
    if (!marker.delete() || !marker.mkdir()) throw IOException("The checksum spool is unavailable.")
    marker.setReadable(false, false)
    marker.setWritable(false, false)
    marker.setExecutable(false, false)
    marker.setReadable(true, true)
    marker.setWritable(true, true)
    marker.setExecutable(true, true)
    return marker
}

private fun deleteChecksumDirectory(root: File): Boolean = try {
    !root.exists() || root.deleteRecursively()
} catch (_: SecurityException) {
    false
}

private fun writeRecord(destination: DataOutputStream, checksum: BackupChecksum) {
    val path = checksum.path.toByteArray(Charsets.UTF_8)
    if (path.size > MAXIMUM_SPOOL_PATH_BYTES) {
        throw backupFailure(BackupFormatFailure.INVALID_PATH, "A checksum path is too large.")
    }
    destination.writeInt(path.size)
    destination.write(path)
    destination.writeLong(checksum.byteLength)
    val digest = checksum.sha256.toByteArray(Charsets.US_ASCII)
    destination.write(digest)
}

private fun readRecord(source: DataInputStream): BackupChecksum? {
    val pathLength = try {
        source.readInt()
    } catch (_: EOFException) {
        return null
    }
    if (pathLength !in 1..MAXIMUM_SPOOL_PATH_BYTES) throw IOException("The checksum spool is invalid.")
    val path = ByteArray(pathLength).also(source::readFully).toString(Charsets.UTF_8)
    val byteLength = source.readLong()
    val digest = ByteArray(SHA_256_CHARACTERS).also(source::readFully).toString(Charsets.US_ASCII)
    return BackupChecksum(digest, byteLength, path)
}

private fun validateSpoolChecksum(checksum: BackupChecksum) {
    if (!checksum.sha256.matches(SHA_256_PATTERN) || checksum.byteLength < 0L) {
        throw backupFailure(
            BackupFormatFailure.INVALID_LEDGER,
            "The checksum ledger contains an invalid digest or byte length.",
        )
    }
    BackupPathValidator.requireSupportedEntryPath(checksum.path)
    if (checksum.path == RME_BACKUP_CHECKSUMS_PATH) {
        throw backupFailure(BackupFormatFailure.INVALID_LEDGER, "The ledger cannot list itself.")
    }
}

private val SHA_256_PATTERN = Regex("[0-9a-f]{64}")
private const val SHA_256_CHARACTERS = 64
private const val MAXIMUM_SPOOL_PATH_BYTES = 4 * 1024
private const val SORT_CHUNK_RECORDS = 4_096
private const val MAXIMUM_MERGE_FAN_IN = 32
private const val CLEANUP_ATTEMPTS = 3
