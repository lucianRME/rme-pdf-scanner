package org.synapseworks.pageharbor.backup.format

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

data class BackupChecksum(
    val sha256: String,
    val byteLength: Long,
    val path: String,
)

object BackupChecksumLedger {
    private val ledgerLine = Regex("([0-9a-f]{64})  (0|[1-9][0-9]*)  (.+)")

    /** Compatibility overload; production archive writing uses the disk-backed sorter directly. */
    fun write(
        checksums: Collection<BackupChecksum>,
        destination: OutputStream,
        scratchDirectory: File,
        limits: BackupFormatLimits = BackupFormatLimits(),
    ) {
        BackupChecksumSpool(
            limits = limits,
            root = createPrivateChecksumDirectory(scratchDirectory),
        ).use { spool ->
            checksums.forEach(spool::append)
            spool.writeLedger(destination)
        }
    }

    internal fun writeSorted(
        destination: OutputStream,
        limits: BackupFormatLimits,
        produce: ((BackupChecksum) -> Unit) -> Unit,
    ) {
        var count = 0
        var totalBytes = 0L
        var previousPath: String? = null
        produce { checksum ->
            validateChecksum(checksum)
            if (checksum.path == RME_BACKUP_CHECKSUMS_PATH) invalidLedger("The ledger cannot list itself.")
            if (previousPath != null && requireNotNull(previousPath) >= checksum.path) {
                invalidLedger("The checksum ledger is not strictly sorted by path.")
            }
            if (count >= limits.maximumEntryCount - 1) {
                limitExceeded("The checksum ledger has too many records.")
            }
            val bytes = (
                checksum.sha256 + "  " + checksum.byteLength + "  " + checksum.path + "\n"
                ).toByteArray(StandardCharsets.UTF_8)
            totalBytes = checkedAdd(totalBytes, bytes.size.toLong())
            if (totalBytes > limits.maximumLedgerBytes) limitExceeded("The checksum ledger is too large.")
            destination.write(bytes)
            previousPath = checksum.path
            count += 1
        }
        if (count == 0) invalidLedger("The checksum ledger is empty.")
    }

    /** Streams a strictly sorted ledger without retaining its bytes, decoded text, or records. */
    fun read(
        source: InputStream,
        limits: BackupFormatLimits = BackupFormatLimits(),
        accept: (BackupChecksum) -> Unit,
    ): Int {
        val line = ByteArrayOutputStream(INITIAL_LINE_BYTES)
        val buffer = ByteArray(READ_BUFFER_BYTES)
        var totalBytes = 0L
        var count = 0
        var previousPath: String? = null

        fun consumeLine() {
            if (line.size() == 0) invalidLedger("The checksum ledger contains a malformed line.")
            val rawLine = decodeStrictUtf8(line.toByteArray())
            line.reset()
            if (rawLine.endsWith('\r')) invalidLedger("The checksum ledger contains a malformed line.")
            val match = ledgerLine.matchEntire(rawLine)
                ?: invalidLedger("The checksum ledger contains a malformed line.")
            val checksum = BackupChecksum(
                sha256 = match.groupValues[1],
                byteLength = match.groupValues[2].toLongOrNull()
                    ?: invalidLedger("A checksum byte length is out of range."),
                path = match.groupValues[3],
            )
            validateChecksum(checksum)
            if (checksum.path == RME_BACKUP_CHECKSUMS_PATH) invalidLedger("The ledger cannot list itself.")
            if (previousPath != null && requireNotNull(previousPath) >= checksum.path) {
                invalidLedger("The checksum ledger is not strictly sorted by path.")
            }
            if (count >= limits.maximumEntryCount - 1) {
                limitExceeded("The checksum ledger has too many records.")
            }
            previousPath = checksum.path
            count += 1
            accept(checksum)
        }

        while (true) {
            val read = source.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            totalBytes = checkedAdd(totalBytes, read.toLong())
            if (totalBytes > limits.maximumLedgerBytes) limitExceeded("The checksum ledger is too large.")
            for (index in 0 until read) {
                val value = buffer[index].toInt() and 0xff
                if (value == '\n'.code) {
                    consumeLine()
                } else {
                    if (line.size() >= MAXIMUM_LEDGER_LINE_BYTES) {
                        invalidLedger("The checksum ledger contains an oversized line.")
                    }
                    line.write(value)
                }
            }
        }
        if (line.size() > 0) consumeLine()
        if (count == 0) invalidLedger("The checksum ledger is empty.")
        return count
    }

    private fun validateChecksum(checksum: BackupChecksum) {
        if (!checksum.sha256.matches(Regex("[0-9a-f]{64}")) || checksum.byteLength < 0) {
            invalidLedger("The checksum ledger contains an invalid digest or byte length.")
        }
        BackupPathValidator.requireSupportedEntryPath(checksum.path)
    }

    private fun invalidLedger(message: String): Nothing = throw backupFailure(
        BackupFormatFailure.INVALID_LEDGER,
        message,
    )
}

private const val INITIAL_LINE_BYTES = 256
private const val READ_BUFFER_BYTES = 8 * 1024
private const val MAXIMUM_LEDGER_LINE_BYTES = 4 * 1024
