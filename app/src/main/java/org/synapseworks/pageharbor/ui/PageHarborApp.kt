package org.synapseworks.pageharbor.ui

import androidx.activity.compose.BackHandler
import androidx.compose.material3.SnackbarHostState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import android.net.Uri
import android.os.SystemClock
import org.synapseworks.pageharbor.BuildConfig
import org.synapseworks.pageharbor.R
import org.synapseworks.pageharbor.document.PageExportResult
import org.synapseworks.pageharbor.document.importing.DocumentImportError
import org.synapseworks.pageharbor.document.importing.DocumentImportUiState
import org.synapseworks.pageharbor.document.PageExportState
import org.synapseworks.pageharbor.document.PdfExportResult
import org.synapseworks.pageharbor.document.PdfSaveState
import org.synapseworks.pageharbor.document.PdfShareError
import org.synapseworks.pageharbor.document.PdfShareState
import org.synapseworks.pageharbor.document.searchablepdf.SearchablePdfSaveError
import org.synapseworks.pageharbor.document.searchablepdf.SearchablePdfSaveState
import org.synapseworks.pageharbor.document.session.DocumentPage
import org.synapseworks.pageharbor.document.session.LibraryDocumentReference
import org.synapseworks.pageharbor.scanner.ScannerSpikeState
import org.synapseworks.pageharbor.image.DocumentFilter
import org.synapseworks.pageharbor.ocr.OcrUiState
import org.synapseworks.pageharbor.library.LibrarySortOrder
import org.synapseworks.pageharbor.library.LibraryUiState
import org.synapseworks.pageharbor.security.AppLockAuthenticatedChangeResult
import org.synapseworks.pageharbor.security.AppLockAuthenticationAvailability
import org.synapseworks.pageharbor.security.AppLockSetupResult
import org.synapseworks.pageharbor.security.AppLockState
import org.synapseworks.pageharbor.security.AutoLockTimeout
import org.synapseworks.pageharbor.ui.home.LibraryHomeScreen
import org.synapseworks.pageharbor.ui.home.LibraryDestination
import org.synapseworks.pageharbor.ui.home.OcrResultScreen
import org.synapseworks.pageharbor.ui.home.ScanResultScreen
import org.synapseworks.pageharbor.ui.portability.BackupEncryptionUiState
import org.synapseworks.pageharbor.ui.portability.BackupReminderScreen
import org.synapseworks.pageharbor.ui.portability.BackupRestoreScreen
import org.synapseworks.pageharbor.ui.portability.MigrationCompletionScreen
import org.synapseworks.pageharbor.ui.portability.MigrationIssuesScreen
import org.synapseworks.pageharbor.ui.portability.MigrationPreviewScreen
import org.synapseworks.pageharbor.ui.portability.MigrationProgressScreen
import org.synapseworks.pageharbor.ui.portability.MoveFromScannerScreen
import org.synapseworks.pageharbor.ui.portability.MoveToNewPhoneScreen
import org.synapseworks.pageharbor.ui.portability.PortabilityCallbacks
import org.synapseworks.pageharbor.ui.portability.PortabilityOperationScreen
import org.synapseworks.pageharbor.ui.portability.PortabilityWorkflowState
import org.synapseworks.pageharbor.ui.portability.RestorePasswordScreen
import org.synapseworks.pageharbor.ui.portability.RestorePreviewScreen
import org.synapseworks.pageharbor.ui.security.AppLockSettingsScreen
import org.synapseworks.pageharbor.ui.theme.PageHarborTheme

@Composable
fun PageHarborApp(
    screen: PageHarborScreen = PageHarborScreen.Home,
    onScreenChange: (PageHarborScreen) -> Unit = {},
    autoNavigateToScanResult: Boolean = true,
    scannerSpikeState: ScannerSpikeState = ScannerSpikeState.Idle,
    pdfSaveState: PdfSaveState = PdfSaveState.Idle,
    pdfShareState: PdfShareState = PdfShareState.Idle,
    pageExportState: PageExportState = PageExportState.Idle,
    ocrUiState: OcrUiState = OcrUiState.Idle,
    ocrSelectedPageIndex: Int = 0,
    scannedPageUris: List<Uri> = emptyList(),
    documentPages: List<DocumentPage> = emptyList(),
    libraryDocument: LibraryDocumentReference? = null,
    importUiState: DocumentImportUiState = DocumentImportUiState.Idle,
    libraryUiState: LibraryUiState = LibraryUiState(),
    libraryThumbnailUri: (String?) -> Uri? = { null },
    onLibraryQueryChange: (String) -> Unit = {},
    onLibraryFolderSelected: (String?) -> Unit = {},
    onLibrarySortOrderChange: (LibrarySortOrder) -> Unit = {},
    onOpenLibraryDocument: (String) -> Unit = {},
    onRenameLibraryDocument: (String, String) -> Unit = { _, _ -> },
    onMoveLibraryDocument: (String, String?) -> Unit = { _, _ -> },
    onDeleteLibraryDocument: (String) -> Unit = {},
    onMergeLibraryDocuments: (List<String>, String) -> Unit = { _, _ -> },
    onCreateLibraryFolder: (String) -> Unit = {},
    onRenameLibraryFolder: (String, String) -> Unit = { _, _ -> },
    onDeleteLibraryFolder: (String) -> Unit = {},
    onConsumeLibraryAction: () -> Unit = {},
    onPageFilterChange: (Long, DocumentFilter) -> Unit = { _, _ -> },
    onPageRotate: (Long) -> Unit = {},
    onPageMove: (Long, Int) -> Unit = { _, _ -> },
    onPageRemove: (Long) -> Unit = {},
    onSaveToLibrary: (String) -> Unit = {},
    onExtractLibraryPages: (Set<String>, String, Boolean) -> Unit = { _, _, _ -> },
    onOcrSelectedPageChange: (Int) -> Unit = {},
    searchablePdfSaveState: SearchablePdfSaveState = SearchablePdfSaveState.Idle,
    onScanDocument: () -> Unit = {},
    onImportFiles: () -> Unit = {},
    onCancelImport: () -> Unit = {},
    onSavePdf: () -> Unit = {},
    onSaveSearchablePdf: () -> Unit = {},
    onSharePdf: () -> Unit = {},
    onExportPages: () -> Unit = {},
    onRecognizeText: () -> Unit = {},
    onClearRecognizedText: () -> Unit = {},
    onCopyRecognizedText: ((String) -> Unit)? = null,
    onViewSourceCode: () -> Unit = {},
    onRateRme: () -> Unit = {},
    onSuggestFeature: () -> Unit = {},
    onShareRme: () -> Unit = {},
    appLockState: AppLockState? = null,
    appLockAuthenticationAvailability: AppLockAuthenticationAvailability =
        AppLockAuthenticationAvailability.UNSUPPORTED,
    onSetupAppLock: (AutoLockTimeout) -> AppLockSetupResult =
        { AppLockSetupResult.StorageUnavailable },
    onAppLockTimeoutChange: (AutoLockTimeout) -> AppLockAuthenticatedChangeResult =
        { AppLockAuthenticatedChangeResult.STORAGE_UNAVAILABLE },
    onDisableAppLock: () -> AppLockAuthenticatedChangeResult =
        { AppLockAuthenticatedChangeResult.STORAGE_UNAVAILABLE },
    onLockAppNow: () -> Unit = {},
    onOpenDeviceSecuritySettings: () -> Unit = {},
    portabilityState: PortabilityWorkflowState = PortabilityWorkflowState.Hidden,
    portabilityCallbacks: PortabilityCallbacks = PortabilityCallbacks(),
    onReviewUiBusyChanged: (Boolean) -> Unit = {},
    documentsDestinationRequestId: Long = 0L,
    onClearScanResult: () -> Unit = {},
    onExitApp: () -> Unit = {},
    exitBackClock: () -> Long = SystemClock::elapsedRealtime,
    exitBackTimeoutMillis: Long = DoubleBackExitController.DEFAULT_TIMEOUT_MILLIS,
    darkTheme: Boolean = isSystemInDarkTheme(),
) {
    PageHarborTheme(darkTheme = darkTheme) {
        var currentScreen by remember { mutableStateOf(screen) }
        val snackbarHostState = remember { SnackbarHostState() }
        val coroutineScope = rememberCoroutineScope()
        val context = LocalContext.current
        val copiedMessage = stringResource(R.string.ocr_copied_message)
        var showPrivacyInfo by remember { mutableStateOf(false) }
        var showAbout by remember { mutableStateOf(false) }
        var showAppLockSettings by remember { mutableStateOf(false) }
        var libraryTransientUiBusy by remember { mutableStateOf(false) }
        var libraryDestinationIndex by rememberSaveable {
            mutableIntStateOf(LibraryDestination.Home.ordinal)
        }
        val libraryDestination = LibraryDestination.entries[libraryDestinationIndex]
        var backupEncryption by remember { mutableStateOf(BackupEncryptionUiState()) }
        val scanCancelledMessage = stringResource(R.string.home_scan_cancelled)
        val scannerErrorMessage = stringResource(R.string.home_scanner_error)
        val pdfSourceMissingMessage = stringResource(R.string.pdf_save_source_missing)
        val sourceTooLargeMessage = stringResource(R.string.document_source_too_large)
        val pdfDestinationUnavailableMessage = stringResource(R.string.pdf_save_destination_unavailable)
        val pdfWriteFailedMessage = stringResource(R.string.pdf_save_failed)
        val pdfSavedMessage = stringResource(R.string.pdf_save_success)
        val searchablePdfNoPagesMessage = stringResource(R.string.searchable_pdf_error_no_pages)
        val searchablePdfPreparationFailedMessage = stringResource(R.string.searchable_pdf_error_preparation_failed)
        val searchablePdfDestinationUnavailableMessage =
            stringResource(R.string.searchable_pdf_error_destination_unavailable)
        val searchablePdfWriteFailedMessage = stringResource(R.string.searchable_pdf_error_write_failed)
        val searchablePdfSavedMessage = stringResource(R.string.searchable_pdf_save_success)
        val searchablePdfCancelledMessage = stringResource(R.string.searchable_pdf_cancelled)
        val pdfShareNoPdfMessage = stringResource(R.string.pdf_share_no_pdf)
        val pdfShareTargetUnavailableMessage = stringResource(R.string.pdf_share_target_unavailable)
        val pdfShareInvalidUriMessage = stringResource(R.string.pdf_share_invalid_uri)
        val pdfShareFailedMessage = stringResource(R.string.pdf_share_failed)
        val pageExportSourceMissingMessage = stringResource(R.string.page_export_source_missing)
        val pageExportDestinationUnavailableMessage =
            stringResource(R.string.page_export_destination_unavailable)
        val pageExportFailedMessage = stringResource(R.string.page_export_failed)
        val pageExportCompletedMessage = stringResource(R.string.page_export_success)
        val pageExportCancelledMessage = stringResource(R.string.page_export_cancelled)
        val ocrNoPagesMessage = stringResource(R.string.ocr_error_no_pages)
        val ocrAllPagesFailedMessage = stringResource(R.string.ocr_error_all_pages_failed)
        val ocrUnexpectedErrorMessage = stringResource(R.string.ocr_error_unexpected)
        val importCancelledMessage = stringResource(R.string.import_cancelled)
        val importUnsupportedMessage = stringResource(R.string.import_error_unsupported)
        val importUnreadableMessage = stringResource(R.string.import_error_unreadable)
        val importInvalidImageMessage = stringResource(R.string.import_error_invalid_image)
        val importPdfUnreadableMessage = stringResource(R.string.import_error_pdf_unreadable)
        val importLimitMessage = stringResource(R.string.import_error_page_limit)
        val importTooLargeMessage = stringResource(R.string.import_error_too_large)
        val importTemporaryFileMessage = stringResource(R.string.import_error_temporary_file)
        val importBusyMessage = stringResource(R.string.import_error_busy)
        val importInterruptedMessage = stringResource(R.string.import_error_interrupted)
        val exitBackHintMessage = stringResource(R.string.exit_back_hint)
        val exitController = remember(exitBackClock, exitBackTimeoutMillis) {
            DoubleBackExitController(exitBackClock, exitBackTimeoutMillis)
        }

        suspend fun showTransientFeedback(message: String) {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
        }

        fun navigateTo(target: PageHarborScreen) {
            exitController.reset()
            currentScreen = target
            onScreenChange(target)
        }

        fun handleTopLevelBack() {
            when (exitController.onBack()) {
                DoubleBackExitController.Result.ShowHint -> coroutineScope.launch {
                    showTransientFeedback(exitBackHintMessage)
                }

                DoubleBackExitController.Result.Exit -> onExitApp()
            }
        }

        LaunchedEffect(screen) {
            currentScreen = screen
        }

        LaunchedEffect(
            showPrivacyInfo,
            showAbout,
            showAppLockSettings,
            libraryTransientUiBusy,
        ) {
            val isBusy = showPrivacyInfo || showAbout || showAppLockSettings ||
                libraryTransientUiBusy
            onReviewUiBusyChanged(isBusy)
            if (isBusy) exitController.reset()
        }

        LaunchedEffect(portabilityState) {
            if (portabilityState !is PortabilityWorkflowState.BackupRestore) {
                backupEncryption = BackupEncryptionUiState()
            }
        }

        BackHandler(enabled = showAppLockSettings) { showAppLockSettings = false }
        BackHandler(enabled = portabilityState !is PortabilityWorkflowState.Hidden) {
            portabilityCallbacks.onBack()
        }

        BackHandler(
            enabled = currentScreen == PageHarborScreen.OcrResult &&
                ocrUiState is OcrUiState.Success,
        ) {
            navigateTo(PageHarborScreen.ScanResult)
        }

        BackHandler(
            enabled = currentScreen == PageHarborScreen.ScanResult &&
                scannerSpikeState is ScannerSpikeState.ResultSummary,
        ) {
            navigateTo(PageHarborScreen.Home)
        }

        when {
        portabilityState is PortabilityWorkflowState.MigrationSource -> MoveFromScannerScreen(
            selectedSource = portabilityState.selectedSource,
            actionsEnabled = portabilityState.actionsEnabled,
            onSourceSelected = portabilityCallbacks.onMigrationSourceSelected,
            onSelectFiles = portabilityCallbacks.onSelectMigrationFiles,
            onSelectFolder = portabilityCallbacks.onSelectMigrationFolder,
            onBack = portabilityCallbacks.onBack,
        )
        portabilityState is PortabilityWorkflowState.MigrationPreview -> MigrationPreviewScreen(
            preview = portabilityState.preview,
            importEnabled = portabilityState.importEnabled,
            showAllReviewItems = portabilityState.showAllReviewItems,
            onReview = portabilityCallbacks.onReviewMigration,
            onDuplicateDecision = portabilityCallbacks.onMigrationDuplicateDecision,
            onImport = portabilityCallbacks.onImportMigration,
            onCancel = portabilityCallbacks.onCancelMigration,
            onBack = portabilityCallbacks.onBack,
        )
        portabilityState is PortabilityWorkflowState.MigrationProgress -> MigrationProgressScreen(
            progress = portabilityState.progress,
            onCancelSafely = portabilityCallbacks.onCancelMigration,
            onBack = portabilityCallbacks.onBack,
        )
        portabilityState is PortabilityWorkflowState.MigrationComplete -> {
            if (portabilityState.showIssues) {
                MigrationIssuesScreen(
                    issues = portabilityState.report.issues,
                    onRetryFailedItems = portabilityCallbacks.onRetryMigration,
                    onDone = portabilityCallbacks.onBack,
                    onBack = portabilityCallbacks.onViewMigrationIssues,
                )
            } else {
                MigrationCompletionScreen(
                    report = portabilityState.report,
                    onViewDocuments = portabilityCallbacks.onViewMigratedDocuments,
                    onViewIssues = portabilityCallbacks.onViewMigrationIssues,
                    onRetryFailedItems = portabilityCallbacks.onRetryMigration,
                    onDone = portabilityCallbacks.onBack,
                    onBack = portabilityCallbacks.onBack,
                )
            }
        }
        portabilityState is PortabilityWorkflowState.BackupRestore -> BackupRestoreScreen(
            status = portabilityState.status,
            encryption = backupEncryption,
            actionsEnabled = portabilityState.actionsEnabled,
            onEncryptionEnabledChange = { enabled ->
                backupEncryption = if (enabled) {
                    backupEncryption.copy(enabled = true, validationMessage = null)
                } else {
                    BackupEncryptionUiState()
                }
            },
            onPasswordChange = { value ->
                backupEncryption = backupEncryption.copy(password = value, validationMessage = null)
            },
            onConfirmationChange = { value ->
                backupEncryption = backupEncryption.copy(
                    confirmation = value,
                    validationMessage = null,
                )
            },
            onBackupNow = {
                val password = backupEncryption.password.takeIf { backupEncryption.enabled }
                    ?.toCharArray()
                try {
                    portabilityCallbacks.onCreateBackup(backupEncryption.enabled, password)
                } finally {
                    password?.fill('\u0000')
                    backupEncryption = BackupEncryptionUiState()
                }
            },
            onRestoreBackup = portabilityCallbacks.onSelectRestoreBackup,
            onExportLibrary = portabilityCallbacks.onExportLibrary,
            onBack = portabilityCallbacks.onBack,
        )
        portabilityState is PortabilityWorkflowState.BackupReminder -> BackupReminderScreen(
            documentCount = portabilityState.documentCount,
            pageCount = portabilityState.pageCount,
            onBackupNow = portabilityCallbacks.onBackupReminderNow,
            onNotNow = portabilityCallbacks.onBackupReminderNotNow,
            onBack = portabilityCallbacks.onBack,
        )
        portabilityState is PortabilityWorkflowState.RestorePassword -> RestorePasswordScreen(
            validationMessage = portabilityState.validationMessage,
            actionsEnabled = portabilityState.actionsEnabled,
            onSubmit = portabilityCallbacks.onRestorePassword,
            onCancel = portabilityCallbacks.onCancelRestore,
            onBack = portabilityCallbacks.onBack,
        )
        portabilityState is PortabilityWorkflowState.RestorePreview -> RestorePreviewScreen(
            preview = portabilityState.preview,
            duplicateChoice = portabilityState.duplicateChoice,
            onDuplicateChoiceChange = portabilityCallbacks.onRestoreDuplicateChoice,
            onRestore = portabilityCallbacks.onRestore,
            onCancel = portabilityCallbacks.onCancelRestore,
            onBack = portabilityCallbacks.onBack,
        )
        portabilityState is PortabilityWorkflowState.NewPhone -> MoveToNewPhoneScreen(
            actionsEnabled = portabilityState.actionsEnabled,
            onCreateBackup = portabilityCallbacks.onOpenBackupRestore,
            onRestoreBackup = portabilityCallbacks.onSelectRestoreBackup,
            onBack = portabilityCallbacks.onBack,
        )
        portabilityState is PortabilityWorkflowState.Operation -> PortabilityOperationScreen(
            state = portabilityState,
            onBack = portabilityCallbacks.onBack,
            onPrimaryAction = portabilityCallbacks.onOperationPrimaryAction,
        )
        showAppLockSettings && appLockState != null -> AppLockSettingsScreen(
            state = appLockState,
            authenticationAvailability = appLockAuthenticationAvailability,
            onBack = { showAppLockSettings = false },
            onSetup = onSetupAppLock,
            onTimeoutChange = onAppLockTimeoutChange,
            onDisable = onDisableAppLock,
            onLockNow = {
                showAppLockSettings = false
                onLockAppNow()
            },
            onOpenDeviceSecuritySettings = onOpenDeviceSecuritySettings,
        )
        currentScreen == PageHarborScreen.OcrResult && ocrUiState is OcrUiState.Success -> {
            OcrResultScreen(
                result = ocrUiState.result,
                pageUris = scannedPageUris,
                pageMetadata = documentPages.map(DocumentPage::imageMetadata),
                selectedPageIndex = ocrSelectedPageIndex,
                onSelectedPageChange = onOcrSelectedPageChange,
                snackbarHostState = snackbarHostState,
                onBack = { navigateTo(PageHarborScreen.ScanResult) },
                onRecognizeAgain = {
                    navigateTo(PageHarborScreen.ScanResult)
                    onRecognizeText()
                },
                onClearRecognizedText = {
                    navigateTo(PageHarborScreen.ScanResult)
                    onClearRecognizedText()
                },
                onCopyText = { text ->
                    val copied = if (onCopyRecognizedText != null) {
                        onCopyRecognizedText(text)
                        true
                    } else {
                        copyPlainTextToClipboard(
                            context = context,
                            label = context.getString(R.string.ocr_result_heading),
                            text = text,
                        )
                    }
                    if (copied) {
                        coroutineScope.launch { showTransientFeedback(copiedMessage) }
                    }
                },
            )
        }
        currentScreen == PageHarborScreen.ScanResult &&
            scannerSpikeState is ScannerSpikeState.ResultSummary -> ScanResultScreen(
            result = scannerSpikeState,
            snackbarHostState = snackbarHostState,
            pdfSaveState = pdfSaveState,
            pdfShareState = pdfShareState,
            pageExportState = pageExportState,
            ocrUiState = ocrUiState,
            searchablePdfSaveState = searchablePdfSaveState,
            documentPages = documentPages,
            libraryDocument = libraryDocument,
            libraryFolders = libraryUiState.folders,
            libraryActionState = libraryUiState.actionState,
            importUiState = importUiState,
            onPageFilterChange = onPageFilterChange,
            onPageRotate = onPageRotate,
            onPageMove = onPageMove,
            onPageRemove = onPageRemove,
            onSaveToLibrary = onSaveToLibrary,
            onExtractLibraryPages = onExtractLibraryPages,
            onRenameLibraryDocument = onRenameLibraryDocument,
            onMoveLibraryDocument = onMoveLibraryDocument,
            onDeleteLibraryDocument = onDeleteLibraryDocument,
            onConsumeLibraryAction = onConsumeLibraryAction,
            onBack = { navigateTo(PageHarborScreen.Home) },
            onSavePdf = onSavePdf,
            onSaveSearchablePdf = onSaveSearchablePdf,
            onSharePdf = onSharePdf,
            onExportPages = onExportPages,
            onRecognizeText = onRecognizeText,
            onViewRecognizedText = { navigateTo(PageHarborScreen.OcrResult) },
            onScanAgain = onScanDocument,
            onImportFiles = onImportFiles,
            onCancelImport = onCancelImport,
            onDiscard = { navigateTo(PageHarborScreen.Home); onClearScanResult() },
        )
        else -> LibraryHomeScreen(
            snackbarHostState = snackbarHostState,
            libraryUiState = libraryUiState,
            scannerSpikeState = scannerSpikeState,
            hasActiveSession = documentPages.isNotEmpty(),
            importUiState = importUiState,
            showBuildDetails = BuildConfig.SHOW_BUILD_DETAILS,
            buildTypeLabel = BuildConfig.BUILD_TYPE_LABEL,
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            gitRevision = BuildConfig.GIT_REVISION,
            showPrivacyInfo = showPrivacyInfo,
            showAbout = showAbout,
            thumbnailUri = libraryThumbnailUri,
            onQueryChange = onLibraryQueryChange,
            onFolderSelected = onLibraryFolderSelected,
            onSortOrderChange = onLibrarySortOrderChange,
            onOpenDocument = onOpenLibraryDocument,
            onRenameDocument = onRenameLibraryDocument,
            onMoveDocument = onMoveLibraryDocument,
            onDeleteDocument = onDeleteLibraryDocument,
            onMergeDocuments = onMergeLibraryDocuments,
            onCreateFolder = onCreateLibraryFolder,
            onRenameFolder = onRenameLibraryFolder,
            onDeleteFolder = onDeleteLibraryFolder,
            onConsumeLibraryAction = onConsumeLibraryAction,
            onScanDocument = {
                onScanDocument()
            },
            onImportFiles = onImportFiles,
            onCancelImport = onCancelImport,
            onViewScanResult = { navigateTo(PageHarborScreen.ScanResult) },
            onPrivacyInfo = {
                showPrivacyInfo = true
            },
            onDismissPrivacyInfo = {
                showPrivacyInfo = false
            },
            onAbout = {
                showAbout = true
            },
            onDismissAbout = {
                showAbout = false
            },
            onViewSourceCode = onViewSourceCode,
            onRateRme = onRateRme,
            onSuggestFeature = onSuggestFeature,
            onShareRme = onShareRme,
            onAppLock = { showAppLockSettings = true },
            onMoveFromScanner = portabilityCallbacks.onOpenMigration,
            onBackupRestore = portabilityCallbacks.onOpenBackupRestore,
            onMoveToNewPhone = portabilityCallbacks.onOpenNewPhone,
            onTopLevelBack = ::handleTopLevelBack,
            onTopLevelBackSequenceReset = exitController::reset,
            onTransientUiBusyChange = { libraryTransientUiBusy = it },
            documentsDestinationRequestId = documentsDestinationRequestId,
            selectedDestination = libraryDestination,
            onDestinationSelected = { libraryDestinationIndex = it.ordinal },
        )
        }

        LaunchedEffect(scannerSpikeState) {
            when (scannerSpikeState) {
                is ScannerSpikeState.ResultSummary -> {
                    if (autoNavigateToScanResult) navigateTo(PageHarborScreen.ScanResult)
                }
                ScannerSpikeState.Cancelled -> {
                    showTransientFeedback(scanCancelledMessage)
                }

                ScannerSpikeState.Error -> {
                    showTransientFeedback(scannerErrorMessage)
                }

                ScannerSpikeState.Idle,
                ScannerSpikeState.Preparing,
                -> Unit
            }
        }

        LaunchedEffect(importUiState) {
            val message = when (importUiState) {
                is DocumentImportUiState.Completed -> {
                    navigateTo(PageHarborScreen.ScanResult)
                    when {
                        importUiState.skippedItems > 0 -> context.getString(
                            R.string.import_completed_partial,
                            importUiState.importedPages,
                            importUiState.skippedItems,
                        )
                        importUiState.importedPages == 1 ->
                            context.getString(R.string.import_completed_one)
                        else -> context.getString(
                            R.string.import_completed_many,
                            importUiState.importedPages,
                        )
                    }
                }

                DocumentImportUiState.Cancelled -> importCancelledMessage
                is DocumentImportUiState.Error -> when (importUiState.reason) {
                    DocumentImportError.BUSY -> importBusyMessage
                    DocumentImportError.EMPTY_INPUT -> importUnreadableMessage
                    DocumentImportError.UNSUPPORTED_TYPE -> importUnsupportedMessage
                    DocumentImportError.UNREADABLE_SOURCE -> importUnreadableMessage
                    DocumentImportError.INVALID_IMAGE -> importInvalidImageMessage
                    DocumentImportError.PDF_UNREADABLE -> importPdfUnreadableMessage
                    DocumentImportError.PAGE_LIMIT_EXCEEDED -> importLimitMessage
                    DocumentImportError.SOURCE_TOO_LARGE -> importTooLargeMessage
                    DocumentImportError.TEMPORARY_FILE_FAILED -> importTemporaryFileMessage
                    DocumentImportError.INTERRUPTED -> importInterruptedMessage
                }

                DocumentImportUiState.Idle,
                DocumentImportUiState.Selecting,
                is DocumentImportUiState.Processing,
                -> null
            }
            if (message != null) showTransientFeedback(message)
        }

        LaunchedEffect(pdfSaveState) {
            val message = when (pdfSaveState) {
                is PdfSaveState.Error -> when (pdfSaveState.result) {
                    PdfExportResult.SourceMissing -> pdfSourceMissingMessage
                    PdfExportResult.SourceTooLarge -> sourceTooLargeMessage
                    PdfExportResult.DestinationUnavailable -> pdfDestinationUnavailableMessage
                    PdfExportResult.WriteFailed -> pdfWriteFailedMessage
                    PdfExportResult.Success -> null
                }

                PdfSaveState.Saved -> pdfSavedMessage

                PdfSaveState.Idle,
                PdfSaveState.ChoosingDestination,
                PdfSaveState.Saving,
                -> null
            }

            if (message != null) {
                showTransientFeedback(message)
            }
        }

        LaunchedEffect(pdfShareState) {
            val message = when (pdfShareState) {
                is PdfShareState.Error -> when (pdfShareState.result) {
                    PdfShareError.NoPdfAvailable -> pdfShareNoPdfMessage
                    PdfShareError.SourceTooLarge -> sourceTooLargeMessage
                    PdfShareError.ShareTargetUnavailable -> pdfShareTargetUnavailableMessage
                    PdfShareError.InvalidUri -> pdfShareInvalidUriMessage
                    PdfShareError.UnexpectedFailure -> pdfShareFailedMessage
                }

                PdfShareState.Idle,
                PdfShareState.Preparing,
                -> null
            }

            if (message != null) {
                showTransientFeedback(message)
            }
        }

        LaunchedEffect(searchablePdfSaveState) {
            val message = when (searchablePdfSaveState) {
                is SearchablePdfSaveState.Error -> when (searchablePdfSaveState.reason) {
                    SearchablePdfSaveError.NO_PAGES -> searchablePdfNoPagesMessage
                    SearchablePdfSaveError.SOURCE_TOO_LARGE -> sourceTooLargeMessage
                    SearchablePdfSaveError.PREPARATION_FAILED -> searchablePdfPreparationFailedMessage
                    SearchablePdfSaveError.DESTINATION_UNAVAILABLE -> searchablePdfDestinationUnavailableMessage
                    SearchablePdfSaveError.WRITE_FAILED -> searchablePdfWriteFailedMessage
                }

                SearchablePdfSaveState.Saved -> searchablePdfSavedMessage
                SearchablePdfSaveState.Cancelled -> searchablePdfCancelledMessage

                SearchablePdfSaveState.Idle,
                SearchablePdfSaveState.Preparing,
                SearchablePdfSaveState.Recognizing,
                SearchablePdfSaveState.Generating,
                SearchablePdfSaveState.ChoosingDestination,
                SearchablePdfSaveState.Saving,
                -> null
            }

            if (message != null) {
                showTransientFeedback(message)
            }
        }

        LaunchedEffect(pageExportState) {
            val message = when (pageExportState) {
                is PageExportState.Error -> when (pageExportState.result) {
                    PageExportResult.SourceMissing -> pageExportSourceMissingMessage
                    PageExportResult.SourceTooLarge -> pageExportFailedMessage
                    PageExportResult.DestinationUnavailable ->
                        pageExportDestinationUnavailableMessage
                    PageExportResult.WriteFailed -> pageExportFailedMessage
                    PageExportResult.Success -> null
                }

                is PageExportState.Completed -> pageExportCompletedMessage
                is PageExportState.Cancelled -> pageExportCancelledMessage

                PageExportState.Idle,
                is PageExportState.ChoosingDestination,
                is PageExportState.Exporting,
                -> null
            }

            if (message != null) {
                showTransientFeedback(message)
            }
        }

        LaunchedEffect(ocrUiState) {
            val message = when (ocrUiState) {
                is OcrUiState.Error -> when (ocrUiState.reason) {
                    org.synapseworks.pageharbor.ocr.OcrUiError.NO_PAGES -> ocrNoPagesMessage
                    org.synapseworks.pageharbor.ocr.OcrUiError.ALL_PAGES_FAILED -> {
                        ocrAllPagesFailedMessage
                    }

                    org.synapseworks.pageharbor.ocr.OcrUiError.UNEXPECTED_FAILURE -> {
                        ocrUnexpectedErrorMessage
                    }
                }

                OcrUiState.Idle,
                OcrUiState.Recognizing,
                is OcrUiState.Success,
                -> null
            }

            if (message != null) {
                showTransientFeedback(message)
            }
        }
    }
}
