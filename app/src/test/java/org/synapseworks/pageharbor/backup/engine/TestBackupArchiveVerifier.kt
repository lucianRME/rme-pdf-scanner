package org.synapseworks.pageharbor.backup.engine

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import org.synapseworks.pageharbor.backup.format.BackupChecksum
import org.synapseworks.pageharbor.backup.format.BackupChecksumLedger
import org.synapseworks.pageharbor.backup.format.BackupFormatLimits
import org.synapseworks.pageharbor.backup.format.BackupJsonCodec
import org.synapseworks.pageharbor.backup.format.BackupManifest
import org.synapseworks.pageharbor.backup.format.RME_BACKUP_CHECKSUMS_PATH
import org.synapseworks.pageharbor.backup.format.RME_BACKUP_MANIFEST_PATH

/** Small-fixture JVM verifier; production uses [IndexedBackupArchiveVerifier]. */
internal object TestBackupArchiveVerifier : BackupArchiveVerifier {
    override fun verify(
        source: InputStream,
        limits: BackupFormatLimits,
        checkCancellation: () -> Unit,
        scratchDirectory: File,
    ): BackupManifest {
        val observed = LinkedHashMap<String, BackupChecksum>()
        val seen = HashSet<String>()
        var manifest: BackupManifest? = null
        var ledger: List<BackupChecksum>? = null
        ZipInputStream(source).use { zip ->
            val buffer = ByteArray(8 * 1024)
            while (true) {
                checkCancellation()
                val entry = zip.nextEntry ?: break
                check(seen.add(entry.name))
                val captured = if (entry.name == RME_BACKUP_MANIFEST_PATH ||
                    entry.name == RME_BACKUP_CHECKSUMS_PATH
                ) {
                    ByteArrayOutputStream()
                } else {
                    null
                }
                val digest = MessageDigest.getInstance("SHA-256")
                var byteLength = 0L
                while (true) {
                    checkCancellation()
                    val count = zip.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    digest.update(buffer, 0, count)
                    captured?.write(buffer, 0, count)
                    byteLength += count
                }
                if (entry.name == RME_BACKUP_MANIFEST_PATH) {
                    manifest = BackupJsonCodec.readManifest(
                        ByteArrayInputStream(requireNotNull(captured).toByteArray()),
                        limits,
                    )
                } else if (entry.name == RME_BACKUP_CHECKSUMS_PATH) {
                    ledger = buildList {
                        BackupChecksumLedger.read(
                            ByteArrayInputStream(requireNotNull(captured).toByteArray()),
                            limits,
                            ::add,
                        )
                    }
                }
                if (entry.name != RME_BACKUP_CHECKSUMS_PATH) {
                    observed[entry.name] = BackupChecksum(
                        digest.digest().toLowerHex(),
                        byteLength,
                        entry.name,
                    )
                }
            }
        }
        check(requireNotNull(ledger) == observed.values.sortedBy(BackupChecksum::path))
        return requireNotNull(manifest)
    }
}

private fun ByteArray.toLowerHex(): String = joinToString(separator = "") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}
