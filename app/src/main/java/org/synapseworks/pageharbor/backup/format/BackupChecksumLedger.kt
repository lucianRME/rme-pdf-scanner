package org.synapseworks.pageharbor.backup.format

import java.io.ByteArrayOutputStream
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

    fun write(
        checksums: Collection<BackupChecksum>,
        destination: OutputStream,
        limits: BackupFormatLimits = BackupFormatLimits(),
    ) {
        if (checksums.size > limits.maximumEntryCount - 1) {
            limitExceeded("The checksum ledger has too many records.")
        }
        val sorted = checksums.sortedBy(BackupChecksum::path)
        val registry = BackupPathRegistry()
        var totalBytes = 0L
        sorted.forEach { checksum ->
            validateChecksum(checksum)
            registry.add(checksum.path)
            if (checksum.path == RME_BACKUP_CHECKSUMS_PATH) invalidLedger("The ledger cannot list itself.")
            val bytes = (
                checksum.sha256 + "  " + checksum.byteLength + "  " + checksum.path + "\n"
                ).toByteArray(StandardCharsets.UTF_8)
            totalBytes = checkedAdd(totalBytes, bytes.size.toLong())
            if (totalBytes > limits.maximumLedgerBytes) limitExceeded("The checksum ledger is too large.")
            destination.write(bytes)
        }
    }

    fun read(
        source: InputStream,
        limits: BackupFormatLimits = BackupFormatLimits(),
    ): List<BackupChecksum> {
        val bytes = readBounded(source, limits.maximumLedgerBytes)
        val text = decodeStrictUtf8(bytes)
        if (text.isEmpty()) invalidLedger("The checksum ledger is empty.")
        val lines = text.split('\n')
        val result = ArrayList<BackupChecksum>(lines.size)
        val registry = BackupPathRegistry()
        var previousPath: String? = null
        lines.forEachIndexed { index, rawLine ->
            if (index == lines.lastIndex && rawLine.isEmpty()) return@forEachIndexed
            if (rawLine.isEmpty() || rawLine.endsWith('\r')) {
                invalidLedger("The checksum ledger contains a malformed line.")
            }
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
            registry.add(checksum.path)
            if (previousPath != null && requireNotNull(previousPath) >= checksum.path) {
                invalidLedger("The checksum ledger is not strictly sorted by path.")
            }
            previousPath = checksum.path
            result += checksum
            if (result.size > limits.maximumEntryCount - 1) {
                limitExceeded("The checksum ledger has too many records.")
            }
        }
        if (result.isEmpty()) invalidLedger("The checksum ledger is empty.")
        return result.toList()
    }

    private fun validateChecksum(checksum: BackupChecksum) {
        if (!checksum.sha256.matches(Regex("[0-9a-f]{64}")) || checksum.byteLength < 0) {
            invalidLedger("The checksum ledger contains an invalid digest or byte length.")
        }
        BackupPathValidator.requireSupportedEntryPath(checksum.path)
    }

    private fun readBounded(source: InputStream, maximumBytes: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(maximumBytes, 8 * 1024))
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val count = source.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            if (total > maximumBytes - count) limitExceeded("The checksum ledger is too large.")
            output.write(buffer, 0, count)
            total += count
        }
        return output.toByteArray()
    }

    private fun invalidLedger(message: String): Nothing = throw backupFailure(
        BackupFormatFailure.INVALID_LEDGER,
        message,
    )
}
