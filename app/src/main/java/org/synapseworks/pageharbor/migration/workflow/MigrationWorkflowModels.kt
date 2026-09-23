package org.synapseworks.pageharbor.migration.workflow

import org.synapseworks.pageharbor.migration.MigrationBatchProgress

/** Scanner choices are guidance hints only; no vendor-specific storage path is assumed. */
enum class MigrationSourceApp {
    CAMSCANNER,
    ADOBE_SCAN,
    GENIUS_SCAN,
    OTHER,
}

enum class MigrationSourceRoute {
    MULTIPLE_FILES,
    DOCUMENT_TREE,
    ANDROID_SHARE,
}

data class MigrationSourceSelection(
    val app: MigrationSourceApp,
    val route: MigrationSourceRoute? = null,
)

@JvmInline
value class MigrationPickerRequestId(val value: Long)

@JvmInline
value class MigrationDuplicateSelectionId(val value: Long)

data class MigrationStorageSummary(
    val requiredBytesLowerBound: Long,
    val unknownSourceCount: Int,
    val safetyMarginBytes: Long,
) {
    init {
        require(requiredBytesLowerBound >= 0L)
        require(unknownSourceCount >= 0)
        require(safetyMarginBytes >= 0L)
    }

    val isComplete: Boolean
        get() = unknownSourceCount == 0
}

data class MigrationPossibleDuplicate(
    val selectionId: MigrationDuplicateSelectionId,
    val suggestedTitle: String,
    val relativeFolderPath: List<String>,
    val pageCount: Int,
    val importAnyway: Boolean,
    val outcome: MigrationPossibleDuplicateOutcome = MigrationPossibleDuplicateOutcome.PENDING,
)

enum class MigrationPossibleDuplicateOutcome {
    PENDING,
    REVIEW_REQUIRED,
    IMPORTED,
}

/** Sanitized, user-reviewable preview. It deliberately contains no provider references. */
data class MigrationPreviewSummary(
    val source: MigrationSourceSelection,
    val documentCount: Int,
    val pageCount: Long,
    val folderCount: Int,
    val exactDuplicateCount: Int,
    val possibleDuplicateCount: Int,
    val differentDocumentCount: Int,
    val unsupportedSourceCount: Int,
    val unreadableSourceCount: Int,
    val requestedSourceCount: Int,
    val inspectedSourceCount: Int,
    val skippedTreeEntryCount: Int,
    val unreadableTreeDirectoryCount: Int,
    val treeWasTruncated: Boolean,
    val storage: MigrationStorageSummary,
    val possibleDuplicates: List<MigrationPossibleDuplicate>,
) {
    init {
        require(documentCount >= 0)
        require(pageCount >= 0L)
        require(folderCount >= 0)
        require(exactDuplicateCount >= 0)
        require(possibleDuplicateCount >= 0)
        require(differentDocumentCount >= 0)
        require(unsupportedSourceCount >= 0)
        require(unreadableSourceCount >= 0)
        require(requestedSourceCount >= 0)
        require(inspectedSourceCount >= 0)
        require(skippedTreeEntryCount >= 0)
        require(unreadableTreeDirectoryCount >= 0)
        require(possibleDuplicateCount == possibleDuplicates.size)
    }
}

enum class MigrationPreparationPhase {
    DISCOVERING_TREE,
    INSPECTING_SOURCES,
    ANALYZING_DUPLICATES,
}

data class MigrationPreparationProgress(
    val phase: MigrationPreparationPhase,
    val completedItems: Int,
    val totalItems: Int?,
    val visitedFolders: Int = 0,
) {
    init {
        require(completedItems >= 0)
        require(totalItems == null || totalItems >= 0)
        require(visitedFolders >= 0)
    }
}

data class MigrationImportProgress(
    val completedDocuments: Int,
    val totalDocuments: Int,
    val isCopyingDocument: Boolean,
    val currentDocumentBytesCopied: Long,
    val currentDocumentTotalBytes: Long?,
) {
    init {
        require(completedDocuments >= 0)
        require(totalDocuments >= 0)
        require(currentDocumentBytesCopied >= 0L)
        require(currentDocumentTotalBytes == null || currentDocumentTotalBytes >= 0L)
    }
}

internal fun MigrationBatchProgress.toWorkflowProgress() = MigrationImportProgress(
    completedDocuments = completedDocuments,
    totalDocuments = totalDocuments,
    isCopyingDocument = currentDocumentId != null,
    currentDocumentBytesCopied = currentDocumentBytesCopied,
    currentDocumentTotalBytes = currentDocumentTotalBytes,
)

enum class MigrationIssueReason {
    UNSUPPORTED_SOURCE,
    UNREADABLE_SOURCE,
    SOURCE_UNAVAILABLE,
    SOURCE_TOO_LARGE,
    INSUFFICIENT_STORAGE,
    WRITE_FAILED,
    INVALID_DOCUMENT,
    INTERRUPTED,
    POSSIBLE_DUPLICATE_REVIEW_REQUIRED,
    CANCELLED,
    NOT_ATTEMPTED,
}

data class MigrationCompletionIssue(
    val itemName: String?,
    val relativeFolderPath: List<String>,
    val reason: MigrationIssueReason,
    val retryable: Boolean,
)

data class MigrationCompletionSummary(
    val requestedDocumentCount: Int,
    val publishedDocumentCount: Int,
    val exactDuplicateSkippedCount: Int,
    val possibleDuplicateImportedCount: Int,
    val possibleDuplicateReviewRequiredCount: Int,
    val failedDocumentCount: Int,
    val cancelledDocumentCount: Int,
    val unprocessedDocumentCount: Int,
    val unsupportedSourceCount: Int,
    val unreadableSourceCount: Int,
    val wasCancelled: Boolean,
    val canRetryFailures: Boolean,
    val canRetryIncomplete: Boolean,
    val issues: List<MigrationCompletionIssue>,
) {
    val completedWithoutIssues: Boolean
        get() = !wasCancelled &&
            possibleDuplicateReviewRequiredCount == 0 &&
            failedDocumentCount == 0 &&
            cancelledDocumentCount == 0 &&
            unprocessedDocumentCount == 0 &&
            unsupportedSourceCount == 0 &&
            unreadableSourceCount == 0
}

enum class MigrationWorkflowFailureReason {
    SOURCE_UNAVAILABLE,
    PREVIEW_FAILED,
    PREVIEW_INCOMPLETE,
    INSUFFICIENT_STORAGE,
    IMPORT_FAILED,
    PICKER_REQUEST_UNAVAILABLE,
}

data class MigrationWorkflowFailure(
    val reason: MigrationWorkflowFailureReason,
    val affectedDocumentCount: Int = 0,
    val requiredBytesLowerBound: Long? = null,
    val availableBytes: Long? = null,
) {
    init {
        require(affectedDocumentCount >= 0)
        require(requiredBytesLowerBound == null || requiredBytesLowerBound >= 0L)
        require(availableBytes == null || availableBytes >= 0L)
    }
}

data class MigrationWorkflowState(
    val operation: MigrationOperationStatus = MigrationOperationStatus.Idle,
)

sealed interface MigrationOperationStatus {
    data object Idle : MigrationOperationStatus

    data class SourceSelected(
        val source: MigrationSourceSelection,
    ) : MigrationOperationStatus

    data class AwaitingMultipleFiles(
        val requestId: MigrationPickerRequestId,
        val source: MigrationSourceSelection,
    ) : MigrationOperationStatus

    data class AwaitingDocumentTree(
        val requestId: MigrationPickerRequestId,
        val source: MigrationSourceSelection,
    ) : MigrationOperationStatus

    data class Preparing(
        val source: MigrationSourceSelection,
        val progress: MigrationPreparationProgress,
    ) : MigrationOperationStatus

    data class PreviewReady(
        val preview: MigrationPreviewSummary,
    ) : MigrationOperationStatus

    data class Importing(
        val preview: MigrationPreviewSummary,
        val progress: MigrationImportProgress,
    ) : MigrationOperationStatus

    data class Cancelling(
        val source: MigrationSourceSelection,
        val preview: MigrationPreviewSummary?,
    ) : MigrationOperationStatus

    data class Completed(
        val preview: MigrationPreviewSummary,
        val completion: MigrationCompletionSummary,
    ) : MigrationOperationStatus

    data class Cancelled(
        val source: MigrationSourceSelection,
        val preview: MigrationPreviewSummary? = null,
    ) : MigrationOperationStatus

    data class Failed(
        val source: MigrationSourceSelection,
        val failure: MigrationWorkflowFailure,
        val preview: MigrationPreviewSummary? = null,
        val canRetry: Boolean,
    ) : MigrationOperationStatus
}

sealed interface MigrationPickerRequest {
    val requestId: MigrationPickerRequestId

    data class OpenMultipleDocuments(
        override val requestId: MigrationPickerRequestId,
        val mimeTypes: List<String> = SUPPORTED_MIGRATION_MIME_TYPES,
    ) : MigrationPickerRequest

    data class OpenDocumentTree(
        override val requestId: MigrationPickerRequestId,
    ) : MigrationPickerRequest
}

sealed interface MigrationWorkflowEvent {
    /** Emitted only for a fully completed migration that published at least one document. */
    data object VerifiedMigrationCompleted : MigrationWorkflowEvent
}

val SUPPORTED_MIGRATION_MIME_TYPES: List<String> = listOf(
    "application/pdf",
    "image/jpeg",
    "image/png",
    "image/webp",
)
