package org.synapseworks.pageharbor.ui.portability

/** User-selected guidance only; no scanner-specific private integration is implied. */
enum class ScannerMigrationSource(
    val displayName: String,
    val guidance: String,
) {
    CAMSCANNER(
        displayName = "CamScanner",
        guidance = "In CamScanner, select the documents, choose Share or Export, then choose RME " +
            "from Android's share sheet. If that version only saves files, export them and use " +
            "Select files here.",
    ),
    ADOBE_SCAN(
        displayName = "Adobe Scan / Acrobat",
        guidance = "In Adobe Scan or Acrobat, choose Share or Send a copy, then choose RME from " +
            "Android's share sheet. You can also save standard PDF or image files and select them here.",
    ),
    GENIUS_SCAN(
        displayName = "Genius Scan",
        guidance = "In Genius Scan, export the selected documents as PDFs or images and choose RME " +
            "from Android's share sheet. You can also save them and select them here.",
    ),
    OTHER(
        displayName = "Other scanner",
        guidance = "Use the scanner app's Share, Export, or Send a copy action and choose RME from " +
            "Android's share sheet. If needed, save standard PDF or image files and select them here.",
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
