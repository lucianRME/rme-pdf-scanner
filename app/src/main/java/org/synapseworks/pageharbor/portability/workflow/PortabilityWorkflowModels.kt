package org.synapseworks.pageharbor.portability.workflow

import org.synapseworks.pageharbor.backup.restore.RestoreMergePolicy
import org.synapseworks.pageharbor.backup.restore.RestorePreview
import org.synapseworks.pageharbor.review.ReviewMilestone

@JvmInline
value class PortabilityPickerRequestId(val value: Long)

enum class PortabilityBackupProtection {
    PLAIN,
    ENCRYPTED,
}

enum class PortabilityWorkflowKind {
    BACKUP,
    RESTORE,
    WHOLE_LIBRARY_EXPORT,
}

data class PortabilityBackupSummary(
    val folderCount: Int,
    val documentCount: Int,
    val pageCount: Int,
    val contentBytes: Long,
    val artifactBytes: Long,
    val libraryRevision: Long,
) {
    init {
        require(folderCount >= 0)
        require(documentCount >= 0)
        require(pageCount >= 0)
        require(contentBytes >= 0L)
        require(artifactBytes > 0L)
        require(libraryRevision >= 0L)
    }
}

data class VerifiedBackupCheckpoint(
    val timestampMillis: Long,
    val libraryRevision: Long,
) {
    init {
        require(timestampMillis > 0L)
        require(libraryRevision >= 0L)
    }
}

data class PortabilityWorkflowState(
    val operation: PortabilityOperationStatus = PortabilityOperationStatus.Idle,
    val lastVerifiedBackup: VerifiedBackupCheckpoint? = null,
)

sealed interface PortabilityOperationStatus {
    data object Idle : PortabilityOperationStatus

    data class CreatingBackup(
        val protection: PortabilityBackupProtection,
    ) : PortabilityOperationStatus

    data class AwaitingBackupDestination(
        val requestId: PortabilityPickerRequestId,
        val protection: PortabilityBackupProtection,
        val summary: PortabilityBackupSummary,
    ) : PortabilityOperationStatus

    data class PublishingBackup(
        val protection: PortabilityBackupProtection,
        val summary: PortabilityBackupSummary,
    ) : PortabilityOperationStatus

    data class BackupVerified(
        val protection: PortabilityBackupProtection,
        val summary: PortabilityBackupSummary,
    ) : PortabilityOperationStatus

    data class AwaitingRestoreSource(
        val requestId: PortabilityPickerRequestId,
    ) : PortabilityOperationStatus

    data object InspectingRestore : PortabilityOperationStatus

    data class AwaitingRestorePassword(
        val previousFailure: PortabilityWorkflowFailure? = null,
    ) : PortabilityOperationStatus

    data object PreparingRestore : PortabilityOperationStatus

    data class RestoreReady(
        val preview: RestorePreview,
        /** True only when the active Room library contained at least one document at preparation. */
        val existingLibraryHasContent: Boolean,
        val mergePolicy: RestoreMergePolicy = RestoreMergePolicy.MERGE_SKIP_EXACT,
    ) : PortabilityOperationStatus

    data class Restoring(
        val preview: RestorePreview,
        val mergePolicy: RestoreMergePolicy,
        val completedDocuments: Int,
        val totalDocuments: Int,
    ) : PortabilityOperationStatus

    data class RestoreCompleted(
        val importedDocumentCount: Int,
        val skippedExactDocumentCount: Int,
    ) : PortabilityOperationStatus

    data class AwaitingExportTree(
        val requestId: PortabilityPickerRequestId,
    ) : PortabilityOperationStatus

    data class Exporting(
        val completedDocuments: Int,
        val totalDocuments: Int,
    ) : PortabilityOperationStatus

    data class ExportCompleted(
        val exportedDocumentCount: Int,
        val failedDocumentCount: Int,
    ) : PortabilityOperationStatus

    data class Cancelled(
        val workflow: PortabilityWorkflowKind,
    ) : PortabilityOperationStatus

    data class Failed(
        val workflow: PortabilityWorkflowKind,
        val reason: PortabilityWorkflowFailure,
    ) : PortabilityOperationStatus
}

enum class PortabilityWorkflowFailure {
    INVALID_PASSWORD,
    BACKUP_SNAPSHOT_UNAVAILABLE,
    BACKUP_TEMPORARY_STORAGE_UNAVAILABLE,
    BACKUP_CREATION_FAILED,
    BACKUP_DESTINATION_UNAVAILABLE,
    BACKUP_DESTINATION_VERIFICATION_FAILED,
    RESTORE_SOURCE_UNAVAILABLE,
    RESTORE_INPUT_UNSUPPORTED,
    RESTORE_WRONG_PASSWORD_OR_DAMAGED,
    RESTORE_TEMPORARY_STORAGE_UNAVAILABLE,
    RESTORE_INVALID_OR_CORRUPT,
    RESTORE_LIBRARY_UNAVAILABLE,
    RESTORE_FAILED,
    EXPORT_DESTINATION_UNAVAILABLE,
    EXPORT_FAILED,
    PICKER_REQUEST_UNAVAILABLE,
}

sealed interface PortabilityPickerRequest {
    val requestId: PortabilityPickerRequestId

    data class CreateBackupDocument(
        override val requestId: PortabilityPickerRequestId,
        val suggestedFileName: String,
        val mimeType: String,
        val protection: PortabilityBackupProtection,
    ) : PortabilityPickerRequest

    data class OpenRestoreDocument(
        override val requestId: PortabilityPickerRequestId,
        val mimeType: String = "*/*",
    ) : PortabilityPickerRequest

    data class OpenWholeLibraryExportTree(
        override val requestId: PortabilityPickerRequestId,
    ) : PortabilityPickerRequest
}

sealed interface PortabilityWorkflowEvent {
    /** Emitted only after the corresponding operation has reached a verified success state. */
    data class VerifiedReviewMilestone(
        val milestone: ReviewMilestone,
    ) : PortabilityWorkflowEvent
}

/** Opaque provider reference. It is operation-local and is never persisted by this workflow. */
@JvmInline
internal value class PortabilitySafReference(val value: String)
