package org.synapseworks.pageharbor.backup.engine

import java.io.File
import java.io.InputStream
import org.synapseworks.pageharbor.backup.format.BackupArchiveReader
import org.synapseworks.pageharbor.backup.format.BackupFormatLimits
import org.synapseworks.pageharbor.backup.format.BackupManifest
import org.synapseworks.pageharbor.backup.restore.TemporaryBackupVerificationStaging

/** Narrow verification boundary; production uses an operation-owned disk index. */
fun interface BackupArchiveVerifier {
    fun verify(
        source: InputStream,
        limits: BackupFormatLimits,
        checkCancellation: () -> Unit,
        scratchDirectory: File,
    ): BackupManifest
}

object IndexedBackupArchiveVerifier : BackupArchiveVerifier {
    override fun verify(
        source: InputStream,
        limits: BackupFormatLimits,
        checkCancellation: () -> Unit,
        scratchDirectory: File,
    ): BackupManifest = TemporaryBackupVerificationStaging.create(scratchDirectory).use { temporary ->
        BackupArchiveReader.readAndVerify(
            source = source,
            staging = temporary.sink,
            limits = limits,
            checkCancellation = checkCancellation,
        ).manifest
    }
}
