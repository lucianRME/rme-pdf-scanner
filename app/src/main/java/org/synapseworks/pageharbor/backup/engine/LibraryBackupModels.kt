package org.synapseworks.pageharbor.backup.engine

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import org.synapseworks.pageharbor.backup.format.BackupManifest

enum class LibraryBackupArtifactState {
    VERIFIED,
}

class VerifiedLibraryBackupArtifact internal constructor(
    val file: File,
    val manifest: BackupManifest,
    val sizeBytes: Long,
    val storageEstimate: LibraryBackupStorageEstimate,
    private val deleteArtifact: (File) -> Boolean,
) : AutoCloseable {
    val state: LibraryBackupArtifactState = LibraryBackupArtifactState.VERIFIED
    private val closed = AtomicBoolean(false)

    val isClosed: Boolean
        get() = closed.get()

    override fun close() {
        if (closed.compareAndSet(false, true) && !deleteArtifact(file)) {
            closed.set(false)
        }
    }
}

sealed interface LibraryBackupCreationResult {
    data class Verified(
        val artifact: VerifiedLibraryBackupArtifact,
    ) : LibraryBackupCreationResult

    data class Failed(
        val failure: LibraryBackupCreationFailure,
        val storageEstimate: LibraryBackupStorageEstimate? = null,
        val cleanupSucceeded: Boolean = true,
    ) : LibraryBackupCreationResult
}

enum class LibraryBackupCreationFailure {
    SNAPSHOT_UNAVAILABLE,
    INSUFFICIENT_TEMPORARY_STORAGE,
    TEMPORARY_FILE_UNAVAILABLE,
    ARCHIVE_WRITE_FAILED,
    REOPEN_VERIFICATION_FAILED,
}

data class LibraryBackupStorageEstimate(
    val contentBytes: Long,
    val metadataUpperBoundBytes: Long,
    val zipEntryCount: Long,
    val temporaryArchiveUpperBoundBytes: Long,
    val availableTemporaryBytes: Long?,
)

fun interface LibraryBackupStoragePreflight {
    fun canCreate(estimate: LibraryBackupStorageEstimate): Boolean
}

object DefaultLibraryBackupStoragePreflight : LibraryBackupStoragePreflight {
    override fun canCreate(estimate: LibraryBackupStorageEstimate): Boolean =
        estimate.availableTemporaryBytes?.let { it >= estimate.temporaryArchiveUpperBoundBytes } ?: true
}

fun interface LibraryBackupClock {
    fun nowEpochMillis(): Long
}

fun interface LibraryBackupIdSource {
    fun newBackupId(): String
}
