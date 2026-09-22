package org.synapseworks.pageharbor.ui.portability

/** User-selected guidance only; no scanner-specific private integration is implied. */
enum class ScannerMigrationSource(
    val displayName: String,
    val guidance: String,
) {
    CAMSCANNER(
        displayName = "CamScanner",
        guidance = "Export or share your PDFs/images from CamScanner, then select them here.",
    ),
    ADOBE_SCAN(
        displayName = "Adobe Scan / Acrobat",
        guidance = "Export or share your PDFs/images from Adobe Scan or Acrobat, then select them here.",
    ),
    GENIUS_SCAN(
        displayName = "Genius Scan",
        guidance = "Export or share your PDFs/images from Genius Scan, then select them here.",
    ),
    OTHER(
        displayName = "Other scanner",
        guidance = "Export or share your PDFs/images from your scanner, then select them here.",
    ),
}

data class MigrationPreviewUiModel(
    val source: ScannerMigrationSource,
    val documentCount: Int,
    val pageCount: Int,
    val folderCount: Int,
    val exactDuplicateCount: Int,
    val possibleDuplicateCount: Int,
    val unsupportedFileCount: Int,
    val estimatedStorage: String,
    val ambiguousGroups: List<MigrationGroupUiModel> = emptyList(),
) {
    init {
        require(
            listOf(
                documentCount,
                pageCount,
                folderCount,
                exactDuplicateCount,
                possibleDuplicateCount,
                unsupportedFileCount,
            ).all { it >= 0 },
        ) { "Migration preview counts must not be negative" }
    }
}

data class MigrationGroupUiModel(
    val title: String,
    val detail: String,
    val duplicateSelectionId: Long? = null,
    val importAnyway: Boolean = false,
)

data class MigrationProgressUiModel(
    val completedDocuments: Int,
    val totalDocuments: Int,
    val currentDocument: String?,
    val currentStage: String,
    val skippedItems: Int,
    val failedItems: Int,
    val cancellationRequested: Boolean = false,
) {
    init {
        require(completedDocuments >= 0)
        require(totalDocuments >= 0)
        require(completedDocuments <= totalDocuments || totalDocuments == 0)
        require(skippedItems >= 0)
        require(failedItems >= 0)
    }

    val progress: Float
        get() = if (totalDocuments == 0) 0f else completedDocuments.toFloat() / totalDocuments
}

data class MigrationCompletionUiModel(
    val importedDocuments: Int,
    val duplicatesSkipped: Int,
    val failedItems: Int,
    val issues: List<MigrationIssueUiModel> = emptyList(),
) {
    init {
        require(importedDocuments >= 0)
        require(duplicatesSkipped >= 0)
        require(failedItems >= 0)
    }
}

data class MigrationIssueUiModel(
    val itemLabel: String,
    val reason: String,
    val retryable: Boolean,
)

sealed interface BackupVerificationStatusUiModel {
    data object NeverBackedUp : BackupVerificationStatusUiModel

    data class Verified(
        val lastVerified: String,
        val libraryChangedSince: Boolean,
    ) : BackupVerificationStatusUiModel

    data class InProgress(
        val stage: String,
    ) : BackupVerificationStatusUiModel

    data class Failed(
        val safeReason: String,
    ) : BackupVerificationStatusUiModel
}

/** Password values are transient UI input and must never be persisted, logged, or saved in state. */
data class BackupEncryptionUiState(
    val enabled: Boolean = false,
    val password: String = "",
    val confirmation: String = "",
    val validationMessage: String? = null,
) {
    val canCreateBackup: Boolean
        get() = !enabled || (password.isNotEmpty() && password == confirmation)

    override fun toString(): String =
        "BackupEncryptionUiState(enabled=$enabled, password=<redacted>, " +
            "confirmation=<redacted>, validationMessagePresent=${validationMessage != null})"
}

data class RestorePreviewUiModel(
    val documentCount: Int,
    val pageCount: Int,
    val folderCount: Int,
    val exactDuplicateCount: Int,
    val possibleDuplicateCount: Int,
    val estimatedStorage: String,
    val createdAt: String,
    val existingLibraryHasContent: Boolean,
    val fullyVerified: Boolean,
) {
    init {
        require(
            listOf(
                documentCount,
                pageCount,
                folderCount,
                exactDuplicateCount,
                possibleDuplicateCount,
            ).all { it >= 0 },
        ) { "Restore preview counts must not be negative" }
    }
}

enum class RestoreDuplicateChoice(val displayName: String, val supportingText: String) {
    SKIP_EXACT(
        displayName = "Merge and skip exact duplicates",
        supportingText = "Content confirmed as identical is not imported again.",
    ),
    IMPORT_ANYWAY(
        displayName = "Merge and import duplicates anyway",
        supportingText = "Import every document, including content confirmed as identical.",
    ),
}
