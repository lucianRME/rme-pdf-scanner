package org.synapseworks.pageharbor

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.synapseworks.pageharbor.document.PageExportResult
import org.synapseworks.pageharbor.document.PageExportState
import org.synapseworks.pageharbor.document.PageJpegExportPlan
import org.synapseworks.pageharbor.document.importing.DocumentImportOrigin
import org.synapseworks.pageharbor.document.importing.DocumentImportPreparationResult
import org.synapseworks.pageharbor.document.importing.DocumentImportProcessor
import org.synapseworks.pageharbor.document.importing.DocumentImportProgressListener
import org.synapseworks.pageharbor.document.importing.InboundShareInput
import org.synapseworks.pageharbor.document.importing.SUPPORTED_IMPORT_MIME_TYPES
import org.synapseworks.pageharbor.document.importing.deleteStaleDocumentImports
import org.synapseworks.pageharbor.document.importing.extractInboundShareInput
import org.synapseworks.pageharbor.document.DocumentOperationTracker
import org.synapseworks.pageharbor.document.DocumentOperationToken
import org.synapseworks.pageharbor.document.NormalPdfExportPlan
import org.synapseworks.pageharbor.document.NormalPdfRecompositionResult
import org.synapseworks.pageharbor.document.PdfExportResult
import org.synapseworks.pageharbor.document.PdfSaveState
import org.synapseworks.pageharbor.document.PdfShareError
import org.synapseworks.pageharbor.document.PdfShareIntentResult
import org.synapseworks.pageharbor.document.PdfSharePreparationResult
import org.synapseworks.pageharbor.document.PdfShareState
import org.synapseworks.pageharbor.document.copyPageToDestination
import org.synapseworks.pageharbor.document.canExportNormalPdf
import org.synapseworks.pageharbor.document.copyPdfToDestination
import org.synapseworks.pageharbor.document.createPdfShareIntent
import org.synapseworks.pageharbor.document.deleteStaleSharedPdfs
import org.synapseworks.pageharbor.document.discardPreparedPdfShare
import org.synapseworks.pageharbor.document.deleteNormalPdfRecomposition
import org.synapseworks.pageharbor.document.deleteStaleNormalPdfs
import org.synapseworks.pageharbor.document.normalPdfExportPlan
import org.synapseworks.pageharbor.document.pageExportStateAfterCancellation
import org.synapseworks.pageharbor.document.pageExportStateAfterSuccess
import org.synapseworks.pageharbor.document.pageJpegExportPlan
import org.synapseworks.pageharbor.document.preparePdfForSharing
import org.synapseworks.pageharbor.document.recomposeNormalPdf
import org.synapseworks.pageharbor.document.startPageExport
import org.synapseworks.pageharbor.document.writeFilteredJpegToDestination
import org.synapseworks.pageharbor.document.searchablepdf.LocalSearchablePdfExportCoordinator
import org.synapseworks.pageharbor.document.searchablepdf.SearchablePdfExportCoordinator
import org.synapseworks.pageharbor.document.searchablepdf.SearchablePdfExportError
import org.synapseworks.pageharbor.document.searchablepdf.SearchablePdfExportProgressListener
import org.synapseworks.pageharbor.document.searchablepdf.SearchablePdfExportRequest
import org.synapseworks.pageharbor.document.searchablepdf.SearchablePdfVisualPage
import org.synapseworks.pageharbor.document.searchablepdf.SearchablePdfExportResult
import org.synapseworks.pageharbor.document.searchablepdf.SearchablePdfOperationTracker
import org.synapseworks.pageharbor.document.searchablepdf.SearchablePdfPreparedExport
import org.synapseworks.pageharbor.document.searchablepdf.SearchablePdfSaveError
import org.synapseworks.pageharbor.document.searchablepdf.SearchablePdfSaveState
import org.synapseworks.pageharbor.document.searchablepdf.deleteStaleSearchablePdfs
import org.synapseworks.pageharbor.document.searchablepdf.isInProgress
import org.synapseworks.pageharbor.document.searchablepdf.searchablePdfSaveStateForProgress
import org.synapseworks.pageharbor.document.session.DocumentPage
import org.synapseworks.pageharbor.document.session.DocumentSession
import org.synapseworks.pageharbor.document.session.readDocumentImageMetadata
import org.synapseworks.pageharbor.document.session.toAndroidUri
import org.synapseworks.pageharbor.backup.restore.RestoreMergePolicy
import org.synapseworks.pageharbor.scanner.ScannerSpikeState
import org.synapseworks.pageharbor.scanner.createScannerResultSummary
import org.synapseworks.pageharbor.ui.PageHarborApp
import org.synapseworks.pageharbor.ui.PageHarborScreen
import org.synapseworks.pageharbor.ocr.MlKitOcrEngine
import org.synapseworks.pageharbor.ocr.OcrEngine
import org.synapseworks.pageharbor.ocr.OcrOperationTracker
import org.synapseworks.pageharbor.ocr.OcrPage
import org.synapseworks.pageharbor.ocr.OcrUiError
import org.synapseworks.pageharbor.ocr.OcrUiState
import org.synapseworks.pageharbor.ocr.canStartOcr
import org.synapseworks.pageharbor.ocr.clearedOcrState
import org.synapseworks.pageharbor.ocr.ocrStateAfterResult
import org.synapseworks.pageharbor.library.LibraryResult
import org.synapseworks.pageharbor.library.LibraryActionState
import org.synapseworks.pageharbor.library.LibraryViewModel
import org.synapseworks.pageharbor.migration.workflow.MigrationDuplicateSelectionId
import org.synapseworks.pageharbor.migration.workflow.MigrationIssueReason
import org.synapseworks.pageharbor.migration.workflow.MigrationOperationStatus
import org.synapseworks.pageharbor.migration.workflow.MigrationPickerRequest
import org.synapseworks.pageharbor.migration.workflow.MigrationPreparationPhase
import org.synapseworks.pageharbor.migration.workflow.MigrationSourceApp
import org.synapseworks.pageharbor.migration.workflow.MigrationWorkflowEvent
import org.synapseworks.pageharbor.migration.workflow.MigrationWorkflowFailureReason
import org.synapseworks.pageharbor.migration.workflow.MigrationWorkflowState as EngineMigrationWorkflowState
import org.synapseworks.pageharbor.migration.workflow.MigrationWorkflowViewModel
import org.synapseworks.pageharbor.portability.workflow.PortabilityBackupProtection
import org.synapseworks.pageharbor.portability.workflow.PortabilityOperationStatus
import org.synapseworks.pageharbor.portability.workflow.PortabilityPickerRequest
import org.synapseworks.pageharbor.portability.workflow.PortabilityWorkflowEvent
import org.synapseworks.pageharbor.portability.workflow.PortabilityWorkflowFailure
import org.synapseworks.pageharbor.portability.workflow.PortabilityWorkflowKind
import org.synapseworks.pageharbor.portability.workflow.PortabilityWorkflowState as EnginePortabilityWorkflowState
import org.synapseworks.pageharbor.portability.workflow.PortabilityWorkflowViewModel
import org.synapseworks.pageharbor.reminder.backup.BackupReminderLibrarySnapshot
import org.synapseworks.pageharbor.reminder.backup.BackupReminderPresentation
import org.synapseworks.pageharbor.reminder.backup.BackupReminderViewModel
import org.synapseworks.pageharbor.review.AutomaticReviewEligibility
import org.synapseworks.pageharbor.review.GooglePlayReviewLauncher
import org.synapseworks.pageharbor.review.ReviewAttemptRunner
import org.synapseworks.pageharbor.review.ReviewEligibilityViewModel
import org.synapseworks.pageharbor.review.ReviewMilestone
import org.synapseworks.pageharbor.review.ReviewMilestoneOutcome
import org.synapseworks.pageharbor.security.AndroidXAppLockAuthenticationController
import org.synapseworks.pageharbor.security.AppLockAccessDecision
import org.synapseworks.pageharbor.security.AppLockAuthenticationAvailability
import org.synapseworks.pageharbor.security.AppLockPhase
import org.synapseworks.pageharbor.security.AppLockProtectedEntryPoint
import org.synapseworks.pageharbor.security.AppLockViewModel
import org.synapseworks.pageharbor.security.PendingProtectedAction
import org.synapseworks.pageharbor.security.ProtectedActionQueueViewModel
import org.synapseworks.pageharbor.ui.security.AppLockScreen
import org.synapseworks.pageharbor.ui.portability.BackupVerificationStatusUiModel
import org.synapseworks.pageharbor.ui.portability.MigrationCompletionUiModel
import org.synapseworks.pageharbor.ui.portability.MigrationGroupUiModel
import org.synapseworks.pageharbor.ui.portability.MigrationIssueUiModel
import org.synapseworks.pageharbor.ui.portability.MigrationPreviewUiModel
import org.synapseworks.pageharbor.ui.portability.MigrationProgressUiModel
import org.synapseworks.pageharbor.ui.portability.PortabilityCallbacks
import org.synapseworks.pageharbor.ui.portability.PortabilityWorkflowState as UiPortabilityWorkflowState
import org.synapseworks.pageharbor.ui.portability.RestoreDuplicateChoice
import org.synapseworks.pageharbor.ui.portability.RestorePreviewUiModel
import org.synapseworks.pageharbor.ui.portability.ScannerMigrationSource

private enum class PortabilityRoute {
    NONE,
    MIGRATION,
    BACKUP_RESTORE,
    NEW_PHONE,
}

private const val STATE_PORTABILITY_ROUTE = "portability_route"
private const val STATE_MIGRATION_SOURCE = "migration_source"
private const val STATE_MIGRATION_REVIEW_EXPANDED = "migration_review_expanded"
private const val STATE_MIGRATION_SHOW_ISSUES = "migration_show_issues"
private const val STATE_RESTORE_DUPLICATE_CHOICE = "restore_duplicate_choice"
private const val STATE_NEW_PHONE_WORKFLOW_ARMED = "new_phone_workflow_armed"

class MainActivity : FragmentActivity() {
    private val session: PageHarborSessionViewModel by viewModels()
    private val library: LibraryViewModel by viewModels()
    private val reviewEligibility: ReviewEligibilityViewModel by viewModels()
    private val appLock: AppLockViewModel by viewModels()
    private val portability: PortabilityWorkflowViewModel by viewModels()
    private val migration: MigrationWorkflowViewModel by viewModels()
    private val backupReminder: BackupReminderViewModel by viewModels()
    private val protectedActions: ProtectedActionQueueViewModel by viewModels()
    private val playReviewLauncher by lazy { GooglePlayReviewLauncher() }
    private val appLockAuthenticationController by lazy {
        AndroidXAppLockAuthenticationController(
            activity = this,
            promptTitle = getString(R.string.app_lock_locked_title),
        )
    }
    private var portabilityRoute by mutableStateOf(PortabilityRoute.NONE)
    private var selectedMigrationSource by mutableStateOf(ScannerMigrationSource.OTHER)
    private var migrationReviewExpanded by mutableStateOf(false)
    private var migrationShowIssues by mutableStateOf(false)
    private var restoreDuplicateChoice by mutableStateOf(RestoreDuplicateChoice.SKIP_EXACT)
    private var newPhoneWorkflowArmed = false
    private var composeUiBusy = false
    private var documentsDestinationRequestId by mutableLongStateOf(0L)
    private var scannerSpikeState: ScannerSpikeState
        get() = session.scannerState
        set(value) { session.scannerState = value }
    private var pdfSaveState: PdfSaveState
        get() = session.pdfSaveState
        set(value) { session.pdfSaveState = value }
    private var pdfShareState: PdfShareState
        get() = session.pdfShareState
        set(value) { session.pdfShareState = value }
    private var pageExportState: PageExportState
        get() = session.pageExportState
        set(value) { session.pageExportState = value }
    private var ocrUiState: OcrUiState
        get() = session.ocrUiState
        set(value) { session.ocrUiState = value }
    private var ocrSelectedPageIndex: Int
        get() = session.ocrSelectedPageIndex
        set(value) { session.ocrSelectedPageIndex = value }
    private var searchablePdfSaveState: SearchablePdfSaveState
        get() = session.searchablePdfSaveState
        set(value) { session.searchablePdfSaveState = value }
    private val scannedPageUris: List<Uri>
        get() = session.scannedPageUris
    private var ocrEngine: OcrEngine = MlKitOcrEngine()
    private var ocrJob: Job? = null
    private val ocrOperationTracker = OcrOperationTracker()
    private var searchablePdfExportCoordinatorForCurrentActivity: SearchablePdfExportCoordinator? = null
    private val searchablePdfExportCoordinator: SearchablePdfExportCoordinator
        get() = searchablePdfExportCoordinatorForCurrentActivity
            ?: LocalSearchablePdfExportCoordinator(this, ocrEngine).also {
                searchablePdfExportCoordinatorForCurrentActivity = it
            }
    private var searchablePdfPreparedExport: SearchablePdfPreparedExport.Ready? = null
    private var searchablePdfExportJob: Job? = null
    private val searchablePdfOperationTracker = SearchablePdfOperationTracker()
    private var searchablePdfDestinationLauncherOverride: ((String) -> Unit)? = null
    private var ocrTerminalStateObserverForTest: (() -> Unit)? = null
    private var documentSessionLeaseReleaseObserverForTest: (() -> Unit)? = null
    private val normalPdfSaveOperationTracker = DocumentOperationTracker()
    private val pdfShareOperationTracker = DocumentOperationTracker()
    private val pageExportOperationTracker = DocumentOperationTracker()
    private var normalPdfSaveJob: Job? = null
    private var pdfShareJob: Job? = null
    private var pageExportJob: Job? = null
    private var pendingNormalPdfDestinationToken: DocumentOperationToken? = null
    private var pendingPageDestinationToken: DocumentOperationToken? = null
    private var normalPdfWriteOverride:
        (suspend (NormalPdfExportPlan, Uri) -> PdfExportResult)? = null
    private var pdfSharePreparationOverride:
        (suspend (NormalPdfExportPlan) -> PdfSharePreparationResult)? = null
    private var pageExportOverride:
        (suspend (DocumentPage, Uri) -> PageExportResult)? = null
    private var normalPdfDestinationLauncherOverride: ((String) -> Unit)? = null
    private var pageDestinationLauncherOverride: ((String) -> Unit)? = null
    private var pdfShareLauncherOverride: ((Uri) -> Unit)? = null
    private var importJob: Job? = null
    private val importProcessor by lazy {
        DocumentImportProcessor(this) { resource ->
            session.registerPendingAcquisitionResources(listOf(resource)) ==
                org.synapseworks.pageharbor.document.session.PendingResourceRegistrationResult.Registered
        }
    }

    private val scanLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        runWhenUnlocked(
            AppLockProtectedEntryPoint.ACTIVITY_RESULT_CALLBACK,
            PendingProtectedAction.ScannerResult(result.resultCode, result.data?.let(::Intent)),
        )
    }

    private fun handleScannerResult(result: androidx.activity.result.ActivityResult) {
        if (result.resultCode == Activity.RESULT_CANCELED) {
            session.cancelScannerRequest()
            return
        }

        if (result.resultCode != Activity.RESULT_OK) {
            session.failScannerRequest()
            return
        }

        runCatching {
            val scannerResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            val jpegPageCount = scannerResult?.pages?.size ?: 0
            val pdfPageCount = scannerResult?.pdf?.pageCount
            if (scannerResult == null || (jpegPageCount == 0 && pdfPageCount == null)) {
                session.completeScannerRequestWithoutResult()
            } else {
                val pageUris = scannerResult.pages.orEmpty().map { page -> page.imageUri }
                clearRecognizedText()
                clearSearchablePdfSave()
                session.completeScannerRequest(
                    scannerState = createScannerResultSummary(
                        jpegPageCount = jpegPageCount,
                        pdfPageCount = pdfPageCount,
                    ),
                    scannedPdfUri = scannerResult.pdf?.uri,
                    scannedPageUris = pageUris,
                    scannedPageMetadata = pageUris.map(contentResolver::readDocumentImageMetadata),
                )
            }
        }.onFailure {
            session.failScannerRequest()
        }
    }

    private val openDocumentsLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        runWhenUnlocked(
            AppLockProtectedEntryPoint.ACTIVITY_RESULT_CALLBACK,
            PendingProtectedAction.ImportDocuments(uris),
        )
    }

    private val portabilityPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val request = protectedActions.consumePortabilityPickerRequest()
            ?: return@registerForActivityResult
        val uri = result.data?.data.takeIf { result.resultCode == Activity.RESULT_OK }
        runWhenUnlocked(
            AppLockProtectedEntryPoint.ACTIVITY_RESULT_CALLBACK,
            PendingProtectedAction.PortabilityPickerResult(request, uri),
        )
    }

    private val migrationPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val request = protectedActions.consumeMigrationPickerRequest()
            ?: return@registerForActivityResult
        runWhenUnlocked(
            AppLockProtectedEntryPoint.ACTIVITY_RESULT_CALLBACK,
            PendingProtectedAction.MigrationPickerResult(
                request = request,
                resultCode = result.resultCode,
                data = result.data?.let(::Intent),
            ),
        )
    }

    private val createPdfDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri ->
        runWhenUnlocked(
            AppLockProtectedEntryPoint.ACTIVITY_RESULT_CALLBACK,
            PendingProtectedAction.NormalPdfDestination(uri),
        )
    }

    private val createPageDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("image/jpeg"),
    ) { uri ->
        runWhenUnlocked(
            AppLockProtectedEntryPoint.ACTIVITY_RESULT_CALLBACK,
            PendingProtectedAction.PageDestination(uri),
        )
    }

    private val createSearchablePdfDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
    ) { destinationUri ->
        runWhenUnlocked(
            AppLockProtectedEntryPoint.ACTIVITY_RESULT_CALLBACK,
            PendingProtectedAction.SearchablePdfDestination(destinationUri),
        )
    }

    private fun handleSearchablePdfDestinationResult(destinationUri: Uri?) {
        val preparedExport = searchablePdfPreparedExport
        if (destinationUri == null) {
            preparedExport?.let(searchablePdfExportCoordinator::discardPreparedExport)
            searchablePdfPreparedExport = null
            searchablePdfSaveState = if (preparedExport == null) {
                SearchablePdfSaveState.Idle
            } else {
                SearchablePdfSaveState.Cancelled
            }
            return
        }
        // A result delivered after discard, scan replacement, or recreation owns no export.
        if (preparedExport == null) return

        searchablePdfSaveState = SearchablePdfSaveState.Saving
        searchablePdfExportJob = lifecycleScope.launch {
            val result = searchablePdfExportCoordinator.writePreparedExport(preparedExport, destinationUri)
            searchablePdfPreparedExport = null
            searchablePdfSaveState = when (result) {
                SearchablePdfExportResult.Success -> {
                    recordReviewMilestone(ReviewMilestone.SEARCHABLE_PDF_EXPORTED)
                    SearchablePdfSaveState.Saved
                }
                is SearchablePdfExportResult.Failure -> SearchablePdfSaveState.Error(
                    when (result.reason) {
                        SearchablePdfExportError.PREPARED_EXPORT_UNAVAILABLE ->
                            SearchablePdfSaveError.PREPARATION_FAILED

                        SearchablePdfExportError.DESTINATION_UNAVAILABLE ->
                            SearchablePdfSaveError.DESTINATION_UNAVAILABLE

                        SearchablePdfExportError.WRITE_FAILED -> SearchablePdfSaveError.WRITE_FAILED
                    },
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restoreActivityUiState(savedInstanceState)
        reviewEligibility.recordAppSession()
        enableEdgeToEdge()
        var observedDocumentRevision = session.documentRevision
        lifecycleScope.launch {
            session.documentRevisionChanges.collect { revision ->
                if (revision == observedDocumentRevision) return@collect
                observedDocumentRevision = revision
                clearStaleNormalDocumentOperations(revision)
            }
        }
        lifecycleScope.launch(Dispatchers.IO) {
            deleteStaleSharedPdfs(cacheDir)
            deleteStaleSearchablePdfs(cacheDir)
            deleteStaleNormalPdfs(cacheDir)
            if (session.documentPages.isEmpty()) {
                deleteStaleDocumentImports(cacheDir)
            }
        }
        lifecycleScope.launch {
            appLock.state.collect { lockState ->
                if (lockState.phase != AppLockPhase.LOCKED) {
                    deliverPendingProtectedActions()
                }
            }
        }
        lifecycleScope.launch {
            portability.pickerRequests.collect { request ->
                runWhenUnlocked(
                    AppLockProtectedEntryPoint.APP_CONTENT,
                    PendingProtectedAction.LaunchPortabilityPicker(request),
                )
            }
        }
        lifecycleScope.launch {
            migration.pickerRequests.collect { request ->
                runWhenUnlocked(
                    AppLockProtectedEntryPoint.APP_CONTENT,
                    PendingProtectedAction.LaunchMigrationPicker(request),
                )
            }
        }
        lifecycleScope.launch {
            portability.events.collect { event ->
                when (event) {
                    is PortabilityWorkflowEvent.VerifiedReviewMilestone -> {
                        recordReviewMilestone(event.milestone)
                        if (event.milestone == ReviewMilestone.RESTORE_COMPLETED) {
                            backupReminder.recordSuccessfulLibraryMutation()
                        } else {
                            backupReminder.refreshEligibility()
                        }
                        if (
                            newPhoneWorkflowArmed &&
                            event.milestone in setOf(
                                ReviewMilestone.BACKUP_VERIFIED,
                                ReviewMilestone.RESTORE_COMPLETED,
                            )
                        ) {
                            newPhoneWorkflowArmed = false
                            recordReviewMilestone(ReviewMilestone.NEW_PHONE_TRANSFER_VERIFIED)
                        }
                    }
                }
            }
        }
        lifecycleScope.launch {
            migration.events.collect { event ->
                when (event) {
                    MigrationWorkflowEvent.VerifiedMigrationCompleted -> {
                        recordReviewMilestone(ReviewMilestone.MIGRATION_COMPLETED)
                        backupReminder.recordSuccessfulLibraryMutation()
                    }
                }
            }
        }
        var observedLibraryActionEventId = 0L
        lifecycleScope.launch {
            library.uiState.collect { state ->
                val action = state.actionState as? LibraryActionState.Succeeded
                    ?: return@collect
                if (action.eventId <= observedLibraryActionEventId) return@collect
                observedLibraryActionEventId = action.eventId
                backupReminder.recordSuccessfulLibraryMutation(
                    meaningful = action.action !=
                        org.synapseworks.pageharbor.library.LibraryActionSuccess.OCR_INDEXED,
                )
            }
        }
        setContent {
            val libraryUiState by library.uiState.collectAsState()
            val appLockState by appLock.state.collectAsState()
            val portabilityEngineState by portability.state.collectAsState()
            val migrationEngineState by migration.state.collectAsState()
            val backupReminderPresentation by backupReminder.presentation.collectAsState()
            val reminderLibrarySnapshot by backupReminder.librarySnapshot.collectAsState()
            if (appLockState.phase == AppLockPhase.LOCKED) {
                AppLockScreen(
                    state = appLockState,
                    authenticationAvailability = appLockAuthenticationController.availability(),
                    onUnlock = {
                        appLock.authenticate(appLockAuthenticationController)
                    },
                    onOpenDeviceSecuritySettings = ::openDeviceSecuritySettings,
                    onExit = ::finish,
                )
            } else {
                val portabilityUiState = buildPortabilityUiState(
                    engineState = portabilityEngineState,
                    migrationState = migrationEngineState,
                    reminder = backupReminderPresentation,
                    reminderLibrarySnapshot = reminderLibrarySnapshot,
                )
                LaunchedEffect(portabilityUiState) {
                    if (portabilityUiState is UiPortabilityWorkflowState.BackupReminder) {
                        backupReminder.markPresented()
                    }
                }
                PageHarborApp(
                    screen = session.screen,
                    onScreenChange = { target ->
                        if (target == PageHarborScreen.ScanResult) {
                            session.returnToScanResult()
                        } else {
                            session.screen = target
                        }
                        if (target == PageHarborScreen.Home) {
                            lifecycleScope.launch { attemptInAppReviewIfSafe() }
                        }
                    },
                    autoNavigateToScanResult = false,
                    scannerSpikeState = scannerSpikeState,
                    pdfSaveState = pdfSaveState,
                    pdfShareState = pdfShareState,
                    pageExportState = pageExportState,
                    ocrUiState = ocrUiState,
                    ocrSelectedPageIndex = ocrSelectedPageIndex,
                    scannedPageUris = scannedPageUris,
                    documentPages = session.documentPages,
                    libraryDocument = session.documentSession.libraryDocument,
                    importUiState = session.importUiState,
                    libraryUiState = libraryUiState,
                    libraryThumbnailUri = library::thumbnailUri,
                    onLibraryQueryChange = library::updateQuery,
                    onLibraryFolderSelected = library::selectFolder,
                    onLibrarySortOrderChange = library::updateSortOrder,
                    onOpenLibraryDocument = ::openLibraryDocument,
                    onRenameLibraryDocument = ::renameLibraryDocument,
                    onMoveLibraryDocument = ::moveLibraryDocument,
                    onDeleteLibraryDocument = ::deleteLibraryDocument,
                    onMergeLibraryDocuments = library::mergeDocuments,
                    onCreateLibraryFolder = library::createFolder,
                    onRenameLibraryFolder = library::renameFolder,
                    onDeleteLibraryFolder = ::deleteLibraryFolder,
                    onConsumeLibraryAction = library::consumeActionState,
                    onOcrSelectedPageChange = { ocrSelectedPageIndex = it },
                    onPageFilterChange = session::setPageFilter,
                    onPageRotate = session::rotatePageClockwise,
                    onPageMove = session::movePage,
                    onPageRemove = session::removePage,
                    onSaveToLibrary = ::saveCurrentDocumentToLibrary,
                    onExtractLibraryPages = ::extractLibraryPages,
                    searchablePdfSaveState = searchablePdfSaveState,
                    onScanDocument = ::launchDocumentScanner,
                    onImportFiles = ::launchFileImport,
                    onCancelImport = ::cancelImport,
                    onSavePdf = ::choosePdfDestination,
                    onSaveSearchablePdf = ::saveSearchablePdf,
                    onSharePdf = ::sharePdf,
                    onExportPages = ::exportPages,
                    onRecognizeText = ::recognizeText,
                    onClearRecognizedText = ::clearRecognizedText,
                    onViewSourceCode = ::openSourceCode,
                    onRateRme = ::openPlayListing,
                    onSuggestFeature = ::suggestFeature,
                    onShareRme = ::shareRme,
                    appLockState = appLockState,
                    appLockAuthenticationAvailability = appLockAuthenticationController.availability(),
                    onSetupAppLock = { timeout ->
                        appLock.setup(timeout, appLockAuthenticationController.availability())
                    },
                    onAppLockTimeoutChange = appLock::setAutoLockTimeoutAfterAuthentication,
                    onDisableAppLock = appLock::disableAfterAuthentication,
                    onLockAppNow = appLock::lockNow,
                    onOpenDeviceSecuritySettings = ::openDeviceSecuritySettings,
                    portabilityState = portabilityUiState,
                    portabilityCallbacks = portabilityCallbacks(),
                    onReviewUiBusyChanged = { composeUiBusy = it },
                    documentsDestinationRequestId = documentsDestinationRequestId,
                    onClearScanResult = {
                        clearRecognizedText()
                        clearSearchablePdfSave()
                        clearNormalDocumentOperations()
                        session.clearScan()
                    },
                    onExitApp = ::finish,
                )
            }
        }
        handleInboundIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_PORTABILITY_ROUTE, portabilityRoute.name)
        outState.putString(STATE_MIGRATION_SOURCE, selectedMigrationSource.name)
        outState.putBoolean(STATE_MIGRATION_REVIEW_EXPANDED, migrationReviewExpanded)
        outState.putBoolean(STATE_MIGRATION_SHOW_ISSUES, migrationShowIssues)
        outState.putString(STATE_RESTORE_DUPLICATE_CHOICE, restoreDuplicateChoice.name)
        outState.putBoolean(STATE_NEW_PHONE_WORKFLOW_ARMED, newPhoneWorkflowArmed)
        super.onSaveInstanceState(outState)
    }

    private fun restoreActivityUiState(savedState: Bundle?) {
        if (savedState == null) return
        portabilityRoute = savedState.getString(STATE_PORTABILITY_ROUTE)
            ?.let { value -> enumValues<PortabilityRoute>().firstOrNull { it.name == value } }
            ?: PortabilityRoute.NONE
        selectedMigrationSource = savedState.getString(STATE_MIGRATION_SOURCE)
            ?.let { value -> enumValues<ScannerMigrationSource>().firstOrNull { it.name == value } }
            ?: ScannerMigrationSource.OTHER
        migrationReviewExpanded = savedState.getBoolean(STATE_MIGRATION_REVIEW_EXPANDED)
        migrationShowIssues = savedState.getBoolean(STATE_MIGRATION_SHOW_ISSUES)
        restoreDuplicateChoice = savedState.getString(STATE_RESTORE_DUPLICATE_CHOICE)
            ?.let { value -> enumValues<RestoreDuplicateChoice>().firstOrNull { it.name == value } }
            ?: RestoreDuplicateChoice.SKIP_EXACT
        newPhoneWorkflowArmed = savedState.getBoolean(STATE_NEW_PHONE_WORKFLOW_ARMED)
    }

    override fun onStart() {
        super.onStart()
        appLock.onAppForegrounded(SystemClock.elapsedRealtime())
        backupReminder.refreshEligibility()
    }

    override fun onStop() {
        appLockAuthenticationController.cancel()
        appLock.onAppBackgrounded(SystemClock.elapsedRealtime())
        super.onStop()
    }

    private fun launchPortabilityPicker(request: PortabilityPickerRequest) {
        val intent = when (request) {
            is PortabilityPickerRequest.CreateBackupDocument -> Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = request.mimeType
                putExtra(Intent.EXTRA_TITLE, request.suggestedFileName)
            }
            is PortabilityPickerRequest.OpenRestoreDocument -> Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = request.mimeType
                putExtra(
                    Intent.EXTRA_MIME_TYPES,
                    arrayOf("application/zip", "application/octet-stream"),
                )
            }
            is PortabilityPickerRequest.OpenWholeLibraryExportTree ->
                Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
                    )
                }
        }
        try {
            protectedActions.markPortabilityPickerLaunched(request)
            portabilityPickerLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            protectedActions.consumePortabilityPickerRequest()
            when (request) {
                is PortabilityPickerRequest.CreateBackupDocument ->
                    portability.onBackupDestinationResult(request.requestId, null)
                is PortabilityPickerRequest.OpenRestoreDocument ->
                    portability.onRestoreSourceResult(request.requestId, null)
                is PortabilityPickerRequest.OpenWholeLibraryExportTree ->
                    portability.onWholeLibraryExportTreeResult(request.requestId, null)
            }
        }
    }

    private fun launchMigrationPicker(request: MigrationPickerRequest) {
        val intent = when (request) {
            is MigrationPickerRequest.OpenMultipleDocuments -> Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                putExtra(Intent.EXTRA_MIME_TYPES, request.mimeTypes.toTypedArray())
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            is MigrationPickerRequest.OpenDocumentTree -> Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
                )
            }
        }
        try {
            protectedActions.markMigrationPickerLaunched(request)
            migrationPickerLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            protectedActions.consumeMigrationPickerRequest()
            when (request) {
                is MigrationPickerRequest.OpenMultipleDocuments -> migration.onMultipleFilesResult(
                    requestId = request.requestId,
                    files = null,
                    grantFlags = 0,
                )
                is MigrationPickerRequest.OpenDocumentTree -> migration.onDocumentTreeResult(
                    requestId = request.requestId,
                    tree = null,
                    grantFlags = 0,
                )
            }
        }
    }

    private fun buildPortabilityUiState(
        engineState: EnginePortabilityWorkflowState,
        migrationState: EngineMigrationWorkflowState,
        reminder: BackupReminderPresentation?,
        reminderLibrarySnapshot: BackupReminderLibrarySnapshot?,
    ): UiPortabilityWorkflowState = if (
        portabilityRoute == PortabilityRoute.MIGRATION ||
        migrationState.operation !is MigrationOperationStatus.Idle
    ) {
        buildMigrationUiState(migrationState)
    } else when (val operation = engineState.operation) {
        PortabilityOperationStatus.Idle -> when {
            portabilityRoute == PortabilityRoute.MIGRATION ->
                UiPortabilityWorkflowState.MigrationSource(selectedMigrationSource)
            portabilityRoute == PortabilityRoute.BACKUP_RESTORE ->
                UiPortabilityWorkflowState.BackupRestore(
                    backupStatus(engineState, reminderLibrarySnapshot),
                )
            portabilityRoute == PortabilityRoute.NEW_PHONE ->
                UiPortabilityWorkflowState.NewPhone()
            reminder != null && session.screen == PageHarborScreen.Home ->
                UiPortabilityWorkflowState.BackupReminder(
                    documentCount = reminder.documentCount,
                    pageCount = reminder.pageCount,
                )
            else -> UiPortabilityWorkflowState.Hidden
        }
        is PortabilityOperationStatus.CreatingBackup -> UiPortabilityWorkflowState.Operation(
            title = "Back up RME",
            heading = "Preparing backup",
            detail = "RME is creating and verifying a complete local backup before you choose where to save it.",
            inProgress = true,
        )
        is PortabilityOperationStatus.AwaitingBackupDestination -> UiPortabilityWorkflowState.Operation(
            title = "Back up RME",
            heading = "Choose a destination",
            detail = "Use Android's system picker to choose where the verified backup will be saved.",
            inProgress = false,
        )
        is PortabilityOperationStatus.PublishingBackup -> UiPortabilityWorkflowState.Operation(
            title = "Back up RME",
            heading = "Saving and verifying backup",
            detail = "RME is writing the backup, reopening it, and checking the complete destination copy.",
            inProgress = true,
        )
        is PortabilityOperationStatus.BackupVerified -> UiPortabilityWorkflowState.Operation(
            title = "Back up RME",
            heading = "Backup verified successfully",
            detail = "The destination copy was reopened and verified. Keep it somewhere you control.",
            inProgress = false,
            primaryActionLabel = "Done",
        )
        is PortabilityOperationStatus.AwaitingRestoreSource -> UiPortabilityWorkflowState.Operation(
            title = "Restore RME backup",
            heading = "Choose a backup",
            detail = "Use Android's system picker to select an RME backup file.",
            inProgress = false,
        )
        PortabilityOperationStatus.InspectingRestore -> UiPortabilityWorkflowState.Operation(
            title = "Restore RME backup",
            heading = "Inspecting backup",
            detail = "RME is checking the backup type before reading its contents.",
            inProgress = true,
        )
        is PortabilityOperationStatus.AwaitingRestorePassword ->
            UiPortabilityWorkflowState.RestorePassword(
                validationMessage = operation.previousFailure?.safePortabilityFailureMessage(),
            )
        PortabilityOperationStatus.PreparingRestore -> UiPortabilityWorkflowState.Operation(
            title = "Restore RME backup",
            heading = "Verifying backup",
            detail = "RME is staging and validating every item before showing the restore preview.",
            inProgress = true,
        )
        is PortabilityOperationStatus.RestoreReady -> {
            val preview = operation.preview
            UiPortabilityWorkflowState.RestorePreview(
                preview = RestorePreviewUiModel(
                    documentCount = preview.documentCount,
                    pageCount = preview.pageCount,
                    folderCount = preview.folderCount,
                    exactDuplicateCount = preview.documents.count {
                        it.duplicateKind == org.synapseworks.pageharbor.library.duplicate.DuplicateKind.EXACT
                    },
                    possibleDuplicateCount = preview.documents.count {
                        it.duplicateKind == org.synapseworks.pageharbor.library.duplicate.DuplicateKind.POSSIBLE
                    },
                    estimatedStorage = formatByteCount(preview.contentByteLength),
                    createdAt = DateFormat.getDateInstance(DateFormat.MEDIUM)
                        .format(Date(preview.createdAtEpochMillis)),
                    existingLibraryHasContent = operation.existingLibraryHasContent,
                    fullyVerified = true,
                ),
                duplicateChoice = restoreDuplicateChoice,
            )
        }
        is PortabilityOperationStatus.Restoring -> UiPortabilityWorkflowState.Operation(
            title = "Restore RME backup",
            heading = "Restoring ${operation.completedDocuments} of ${operation.totalDocuments}",
            detail = "Existing documents remain untouched until the staged restore activates safely.",
            inProgress = true,
        )
        is PortabilityOperationStatus.RestoreCompleted -> UiPortabilityWorkflowState.Operation(
            title = "Restore RME backup",
            heading = "Restore complete",
            detail = "${operation.importedDocumentCount} documents restored; " +
                "${operation.skippedExactDocumentCount} exact duplicates skipped.",
            inProgress = false,
            primaryActionLabel = "View documents",
        )
        is PortabilityOperationStatus.AwaitingExportTree -> UiPortabilityWorkflowState.Operation(
            title = "Export library",
            heading = "Choose an export folder",
            detail = "Use Android's system picker to choose where ordinary PDFs and folders will be created.",
            inProgress = false,
        )
        is PortabilityOperationStatus.Exporting -> UiPortabilityWorkflowState.Operation(
            title = "Export library",
            heading = "Exporting ${operation.completedDocuments} of ${operation.totalDocuments}",
            detail = "RME is creating ordinary, human-readable PDF files and folders.",
            inProgress = true,
        )
        is PortabilityOperationStatus.ExportCompleted -> UiPortabilityWorkflowState.Operation(
            title = "Export library",
            heading = "Library export complete",
            detail = "${operation.exportedDocumentCount} documents exported; " +
                "${operation.failedDocumentCount} failed.",
            inProgress = false,
            isError = operation.failedDocumentCount > 0,
            primaryActionLabel = "Done",
        )
        is PortabilityOperationStatus.Cancelled -> UiPortabilityWorkflowState.Operation(
            title = operation.workflow.displayTitle(),
            heading = "Operation cancelled",
            detail = "No half-published document or unverified backup was accepted.",
            inProgress = false,
            primaryActionLabel = "Done",
        )
        is PortabilityOperationStatus.Failed -> UiPortabilityWorkflowState.Operation(
            title = operation.workflow.displayTitle(),
            heading = "Could not complete operation",
            detail = operation.reason.safePortabilityFailureMessage(),
            inProgress = false,
            isError = true,
            primaryActionLabel = "Done",
        )
    }

    private fun buildMigrationUiState(
        state: EngineMigrationWorkflowState,
    ): UiPortabilityWorkflowState = when (val operation = state.operation) {
        MigrationOperationStatus.Idle -> UiPortabilityWorkflowState.MigrationSource(
            selectedSource = selectedMigrationSource,
        )
        is MigrationOperationStatus.SourceSelected -> UiPortabilityWorkflowState.MigrationSource(
            selectedSource = operation.source.app.toUiMigrationSource(),
        )
        is MigrationOperationStatus.AwaitingMultipleFiles,
        is MigrationOperationStatus.AwaitingDocumentTree,
        -> UiPortabilityWorkflowState.MigrationSource(
            selectedSource = selectedMigrationSource,
            actionsEnabled = false,
        )
        is MigrationOperationStatus.Preparing -> UiPortabilityWorkflowState.Operation(
            title = "Move to RME",
            heading = when (operation.progress.phase) {
                MigrationPreparationPhase.DISCOVERING_TREE -> "Discovering documents"
                MigrationPreparationPhase.INSPECTING_SOURCES -> "Inspecting selected files"
                MigrationPreparationPhase.ANALYZING_DUPLICATES -> "Checking for duplicates"
            },
            detail = buildString {
                append(operation.progress.completedItems)
                operation.progress.totalItems?.let { total -> append(" of $total") }
                append(" items inspected. Nothing has been added to your library yet.")
            },
            inProgress = true,
        )
        is MigrationOperationStatus.PreviewReady -> UiPortabilityWorkflowState.MigrationPreview(
            preview = operation.preview.toUiMigrationPreview(),
            importEnabled = operation.preview.documentCount > 0,
            showAllReviewItems = migrationReviewExpanded,
        )
        is MigrationOperationStatus.Importing -> UiPortabilityWorkflowState.MigrationProgress(
            MigrationProgressUiModel(
                completedDocuments = operation.progress.completedDocuments,
                totalDocuments = operation.progress.totalDocuments,
                currentDocument = null,
                currentStage = if (operation.progress.isCopyingDocument) {
                    "Copying and validating current document"
                } else {
                    "Publishing completed documents"
                },
                skippedItems = operation.preview.exactDuplicateCount,
                failedItems = 0,
            ),
        )
        is MigrationOperationStatus.Cancelling -> UiPortabilityWorkflowState.MigrationProgress(
            MigrationProgressUiModel(
                completedDocuments = 0,
                totalDocuments = operation.preview?.documentCount ?: 0,
                currentDocument = null,
                currentStage = "Stopping at an atomic document boundary",
                skippedItems = operation.preview?.exactDuplicateCount ?: 0,
                failedItems = 0,
                cancellationRequested = true,
            ),
        )
        is MigrationOperationStatus.Completed -> UiPortabilityWorkflowState.MigrationComplete(
            report = operation.completion.toUiMigrationCompletion(),
            showIssues = migrationShowIssues,
        )
        is MigrationOperationStatus.Cancelled -> UiPortabilityWorkflowState.Operation(
            title = "Move to RME",
            heading = "Migration cancelled safely",
            detail = "Completed documents remain available and no half-published document was left behind.",
            inProgress = false,
            primaryActionLabel = "Done",
        )
        is MigrationOperationStatus.Failed -> UiPortabilityWorkflowState.Operation(
            title = "Move to RME",
            heading = "Migration needs attention",
            detail = operation.failure.safeMigrationFailureMessage(),
            inProgress = false,
            isError = true,
            primaryActionLabel = if (operation.canRetry) "Retry" else "Done",
        )
    }

    private fun org.synapseworks.pageharbor.migration.workflow.MigrationPreviewSummary
        .toUiMigrationPreview(): MigrationPreviewUiModel {
        val estimatedBytes = if (storage.requiredBytesLowerBound > Long.MAX_VALUE - storage.safetyMarginBytes) {
            Long.MAX_VALUE
        } else {
            storage.requiredBytesLowerBound + storage.safetyMarginBytes
        }
        val estimate = buildString {
            append(formatByteCount(estimatedBytes))
            if (!storage.isComplete) append(" + unknown provider sizes")
        }
        return MigrationPreviewUiModel(
            source = source.app.toUiMigrationSource(),
            documentCount = documentCount,
            pageCount = pageCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            folderCount = folderCount,
            exactDuplicateCount = exactDuplicateCount,
            possibleDuplicateCount = possibleDuplicateCount,
            unsupportedFileCount = unsupportedSourceCount + unreadableSourceCount,
            estimatedStorage = estimate,
            ambiguousGroups = possibleDuplicates.map { duplicate ->
                MigrationGroupUiModel(
                    title = duplicate.suggestedTitle,
                    detail = buildString {
                        if (duplicate.relativeFolderPath.isNotEmpty()) {
                            append(duplicate.relativeFolderPath.joinToString(" / "))
                            append(" · ")
                        }
                        append("${duplicate.pageCount} pages · possible duplicate")
                    },
                    duplicateSelectionId = duplicate.selectionId.value,
                    importAnyway = duplicate.importAnyway,
                )
            },
        )
    }

    private fun org.synapseworks.pageharbor.migration.workflow.MigrationCompletionSummary
        .toUiMigrationCompletion(): MigrationCompletionUiModel = MigrationCompletionUiModel(
        importedDocuments = publishedDocumentCount,
        duplicatesSkipped = exactDuplicateSkippedCount,
        failedItems = failedDocumentCount + possibleDuplicateReviewRequiredCount +
            cancelledDocumentCount + unprocessedDocumentCount + unsupportedSourceCount +
            unreadableSourceCount,
        issues = issues.map { issue ->
            MigrationIssueUiModel(
                itemLabel = issue.itemName ?: issue.relativeFolderPath.lastOrNull() ?: "Selected item",
                reason = issue.reason.safeMigrationIssueMessage(),
                retryable = issue.retryable,
            )
        },
    )

    private fun MigrationIssueReason.safeMigrationIssueMessage(): String = when (this) {
        MigrationIssueReason.UNSUPPORTED_SOURCE -> "Unsupported file type"
        MigrationIssueReason.UNREADABLE_SOURCE -> "The selected file could not be read"
        MigrationIssueReason.SOURCE_UNAVAILABLE -> "The source is no longer available"
        MigrationIssueReason.SOURCE_TOO_LARGE -> "The source exceeds the migration safety limit"
        MigrationIssueReason.INSUFFICIENT_STORAGE -> "Not enough usable storage"
        MigrationIssueReason.WRITE_FAILED -> "The document could not be published safely"
        MigrationIssueReason.INVALID_DOCUMENT -> "The document failed validation"
        MigrationIssueReason.INTERRUPTED -> "The operation was interrupted"
        MigrationIssueReason.POSSIBLE_DUPLICATE_REVIEW_REQUIRED ->
            "Possible duplicate; choose Import anyway to include it"
        MigrationIssueReason.CANCELLED -> "Cancelled at a safe boundary"
        MigrationIssueReason.NOT_ATTEMPTED -> "Not attempted"
    }

    private fun org.synapseworks.pageharbor.migration.workflow.MigrationWorkflowFailure
        .safeMigrationFailureMessage(): String = when (reason) {
        MigrationWorkflowFailureReason.SOURCE_UNAVAILABLE ->
            "The selected files or folder are no longer available."
        MigrationWorkflowFailureReason.PREVIEW_FAILED ->
            "RME could not safely inspect the selected sources."
        MigrationWorkflowFailureReason.PREVIEW_INCOMPLETE ->
            "The preview changed before import. Review the refreshed results and try again."
        MigrationWorkflowFailureReason.INSUFFICIENT_STORAGE -> buildString {
            append("There is not enough usable storage to import safely.")
            requiredBytesLowerBound?.let { required -> append(" Required: ${formatByteCount(required)}.") }
            availableBytes?.let { available -> append(" Available: ${formatByteCount(available)}.") }
        }
        MigrationWorkflowFailureReason.IMPORT_FAILED ->
            "The migration could not complete safely. Published documents remain intact."
        MigrationWorkflowFailureReason.PICKER_REQUEST_UNAVAILABLE ->
            "Android's system file picker is unavailable."
    }

    private fun MigrationSourceApp.toUiMigrationSource(): ScannerMigrationSource = when (this) {
        MigrationSourceApp.CAMSCANNER -> ScannerMigrationSource.CAMSCANNER
        MigrationSourceApp.ADOBE_SCAN -> ScannerMigrationSource.ADOBE_SCAN
        MigrationSourceApp.GENIUS_SCAN -> ScannerMigrationSource.GENIUS_SCAN
        MigrationSourceApp.OTHER -> ScannerMigrationSource.OTHER
    }

    private fun ScannerMigrationSource.toEngineMigrationSource(): MigrationSourceApp = when (this) {
        ScannerMigrationSource.CAMSCANNER -> MigrationSourceApp.CAMSCANNER
        ScannerMigrationSource.ADOBE_SCAN -> MigrationSourceApp.ADOBE_SCAN
        ScannerMigrationSource.GENIUS_SCAN -> MigrationSourceApp.GENIUS_SCAN
        ScannerMigrationSource.OTHER -> MigrationSourceApp.OTHER
    }

    private fun backupStatus(
        state: EnginePortabilityWorkflowState,
        snapshot: BackupReminderLibrarySnapshot?,
    ): BackupVerificationStatusUiModel {
        val checkpoint = state.lastVerifiedBackup ?: return BackupVerificationStatusUiModel.NeverBackedUp
        return BackupVerificationStatusUiModel.Verified(
            lastVerified = DateFormat.getDateInstance(DateFormat.MEDIUM)
                .format(Date(checkpoint.timestampMillis)),
            libraryChangedSince = snapshot?.revision?.let { it != checkpoint.libraryRevision } ?: false,
        )
    }

    private fun portabilityCallbacks(): PortabilityCallbacks = PortabilityCallbacks(
        onOpenMigration = {
            portability.dismissResult()
            portabilityRoute = PortabilityRoute.MIGRATION
            migrationReviewExpanded = false
            migrationShowIssues = false
            migration.dismissResult()
            migration.selectSource(selectedMigrationSource.toEngineMigrationSource())
        },
        onOpenBackupRestore = {
            if (portabilityRoute == PortabilityRoute.NEW_PHONE) newPhoneWorkflowArmed = true
            portability.dismissResult()
            portabilityRoute = PortabilityRoute.BACKUP_RESTORE
        },
        onOpenNewPhone = {
            portability.dismissResult()
            portabilityRoute = PortabilityRoute.NEW_PHONE
        },
        onBack = ::closePortabilitySurface,
        onMigrationSourceSelected = { source ->
            selectedMigrationSource = source
            migration.selectSource(source.toEngineMigrationSource())
        },
        onSelectMigrationFiles = migration::requestMultipleFiles,
        onSelectMigrationFolder = migration::requestDocumentTree,
        onReviewMigration = { migrationReviewExpanded = !migrationReviewExpanded },
        onMigrationDuplicateDecision = { selectionId, importAnyway ->
            migration.setImportAnyway(
                MigrationDuplicateSelectionId(selectionId),
                importAnyway,
            )
        },
        onImportMigration = migration::importDocuments,
        onCancelMigration = {
            if (!migration.cancelCurrentOperation()) closePortabilitySurface()
        },
        onViewMigratedDocuments = {
            migration.dismissResult()
            portabilityRoute = PortabilityRoute.NONE
            library.selectFolder(null)
            documentsDestinationRequestId++
            attemptInAppReviewIfSafe()
        },
        onViewMigrationIssues = { migrationShowIssues = !migrationShowIssues },
        onRetryMigration = {
            migrationShowIssues = false
            val status = migration.state.value.operation
            when (status) {
                is MigrationOperationStatus.Completed -> {
                    if (!migration.retryFailures()) migration.retryIncomplete()
                }
                is MigrationOperationStatus.Failed -> {
                    if (status.preview == null) {
                        migration.retryPreparation()
                    } else if (!migration.retryIncomplete()) {
                        migration.retryFailures()
                    }
                }
                else -> Unit
            }
        },
        onCreateBackup = { encrypted, password ->
            portabilityRoute = PortabilityRoute.BACKUP_RESTORE
            portability.createBackup(
                protection = if (encrypted) {
                    PortabilityBackupProtection.ENCRYPTED
                } else {
                    PortabilityBackupProtection.PLAIN
                },
                password = password,
            )
        },
        onSelectRestoreBackup = {
            if (portabilityRoute == PortabilityRoute.NEW_PHONE) newPhoneWorkflowArmed = true
            portabilityRoute = PortabilityRoute.BACKUP_RESTORE
            restoreDuplicateChoice = RestoreDuplicateChoice.SKIP_EXACT
            portability.requestRestoreSource()
        },
        onExportLibrary = {
            portabilityRoute = PortabilityRoute.BACKUP_RESTORE
            portability.requestWholeLibraryExport()
        },
        onRestorePassword = portability::submitRestorePassword,
        onRestoreDuplicateChoice = { restoreDuplicateChoice = it },
        onRestore = {
            portability.restore(
                if (restoreDuplicateChoice == RestoreDuplicateChoice.IMPORT_ANYWAY) {
                    RestoreMergePolicy.MERGE_IMPORT_ANYWAY
                } else {
                    RestoreMergePolicy.MERGE_SKIP_EXACT
                },
            )
        },
        onCancelRestore = {
            if (!portability.cancelCurrentOperation()) portability.dismissResult()
        },
        onOperationPrimaryAction = operationAction@{
            if (portabilityRoute == PortabilityRoute.MIGRATION) {
                val status = migration.state.value.operation
                if (status is MigrationOperationStatus.Failed && status.canRetry) {
                    if (status.preview == null) {
                        migration.retryPreparation()
                    } else if (!migration.retryIncomplete()) {
                        migration.retryFailures()
                    }
                } else {
                    migration.dismissResult()
                    portabilityRoute = PortabilityRoute.NONE
                    attemptInAppReviewIfSafe()
                }
                return@operationAction
            }
            val completedRestore = portability.state.value.operation is
                PortabilityOperationStatus.RestoreCompleted
            portability.dismissResult()
            portabilityRoute = if (completedRestore) {
                library.selectFolder(null)
                documentsDestinationRequestId++
                PortabilityRoute.NONE
            } else {
                PortabilityRoute.BACKUP_RESTORE
            }
            backupReminder.refreshEligibility()
            attemptInAppReviewIfSafe()
        },
        onBackupReminderNow = {
            backupReminder.dismiss()
            portabilityRoute = PortabilityRoute.BACKUP_RESTORE
        },
        onBackupReminderNotNow = backupReminder::notNow,
    )

    private fun closePortabilitySurface() {
        if (portabilityRoute == PortabilityRoute.MIGRATION) {
            if (migrationReviewExpanded) {
                migrationReviewExpanded = false
                return
            }
            if (migrationShowIssues) {
                migrationShowIssues = false
                return
            }
            val status = migration.state.value.operation
            when (status) {
                MigrationOperationStatus.Idle,
                is MigrationOperationStatus.SourceSelected,
                is MigrationOperationStatus.PreviewReady,
                is MigrationOperationStatus.Completed,
                is MigrationOperationStatus.Cancelled,
                is MigrationOperationStatus.Failed,
                -> migration.dismissResult()
                else -> migration.cancelCurrentOperation()
            }
            migrationReviewExpanded = false
            migrationShowIssues = false
        }
        val operation = portability.state.value.operation
        when (operation) {
            PortabilityOperationStatus.Idle -> Unit
            is PortabilityOperationStatus.BackupVerified,
            is PortabilityOperationStatus.RestoreCompleted,
            is PortabilityOperationStatus.ExportCompleted,
            is PortabilityOperationStatus.Cancelled,
            is PortabilityOperationStatus.Failed,
            -> portability.dismissResult()
            else -> portability.cancelCurrentOperation()
        }
        backupReminder.dismiss()
        portabilityRoute = PortabilityRoute.NONE
        newPhoneWorkflowArmed = false
        attemptInAppReviewIfSafe()
    }

    private fun formatByteCount(bytes: Long): String {
        if (bytes < 1_024L) return "$bytes B"
        val units = arrayOf("KiB", "MiB", "GiB", "TiB")
        var value = bytes.toDouble()
        var unitIndex = -1
        do {
            value /= 1_024.0
            unitIndex++
        } while (value >= 1_024.0 && unitIndex < units.lastIndex)
        return String.format(java.util.Locale.ROOT, "%.1f %s", value, units[unitIndex])
    }

    private fun PortabilityWorkflowFailure.safePortabilityFailureMessage(): String = when (this) {
        PortabilityWorkflowFailure.INVALID_PASSWORD -> "Enter a backup password and try again."
        PortabilityWorkflowFailure.BACKUP_SNAPSHOT_UNAVAILABLE ->
            "The library could not be read safely for backup."
        PortabilityWorkflowFailure.BACKUP_TEMPORARY_STORAGE_UNAVAILABLE ->
            "There is not enough usable temporary storage to create this backup safely."
        PortabilityWorkflowFailure.BACKUP_CREATION_FAILED ->
            "The backup could not be created and verified."
        PortabilityWorkflowFailure.BACKUP_DESTINATION_UNAVAILABLE ->
            "The selected destination could not be opened."
        PortabilityWorkflowFailure.BACKUP_DESTINATION_VERIFICATION_FAILED ->
            "The saved destination copy could not be reopened and verified."
        PortabilityWorkflowFailure.RESTORE_SOURCE_UNAVAILABLE ->
            "The selected backup could not be opened."
        PortabilityWorkflowFailure.RESTORE_INPUT_UNSUPPORTED ->
            "This is not a supported RME backup."
        PortabilityWorkflowFailure.RESTORE_WRONG_PASSWORD_OR_DAMAGED ->
            "The password is wrong or the encrypted backup is damaged."
        PortabilityWorkflowFailure.RESTORE_TEMPORARY_STORAGE_UNAVAILABLE ->
            "There is not enough usable temporary storage to verify and stage this restore."
        PortabilityWorkflowFailure.RESTORE_INVALID_OR_CORRUPT ->
            "The backup is invalid, incomplete, or corrupt. Your library was not changed."
        PortabilityWorkflowFailure.RESTORE_LIBRARY_UNAVAILABLE,
        PortabilityWorkflowFailure.RESTORE_FAILED,
        -> "The restore could not be completed safely. Your existing library was not replaced."
        PortabilityWorkflowFailure.EXPORT_DESTINATION_UNAVAILABLE ->
            "The selected export folder could not be opened."
        PortabilityWorkflowFailure.EXPORT_FAILED -> "The library export could not be completed."
        PortabilityWorkflowFailure.PICKER_REQUEST_UNAVAILABLE ->
            "Android's system file picker is unavailable."
    }

    private fun PortabilityWorkflowKind.displayTitle(): String = when (this) {
        PortabilityWorkflowKind.BACKUP -> "Back up RME"
        PortabilityWorkflowKind.RESTORE -> "Restore RME backup"
        PortabilityWorkflowKind.WHOLE_LIBRARY_EXPORT -> "Export library"
    }

    private fun openLibraryDocument(documentId: String) {
        lifecycleScope.launch {
            when (val opened = library.openDocument(documentId)) {
                is LibraryResult.Success -> {
                    clearRecognizedText()
                    clearSearchablePdfSave()
                    clearNormalDocumentOperations()
                    if (session.openLibraryDocument(opened.value.session) &&
                        !session.hasActiveDocumentSessionLeases()
                    ) {
                        library.cleanupDocumentRevisions(documentId)
                    }
                }
                is LibraryResult.Failure -> Unit
            }
        }
    }

    private fun deleteLibraryDocument(documentId: String) {
        if (session.documentSession.libraryDocument?.documentId == documentId) {
            clearRecognizedText()
            clearSearchablePdfSave()
            clearNormalDocumentOperations()
            session.clearScan()
        }
        library.deleteDocument(documentId)
    }

    private fun renameLibraryDocument(documentId: String, title: String) {
        library.renameDocument(documentId, title) {
            val current = session.documentSession.libraryDocument
            if (current?.documentId == documentId) {
                session.updateLibraryReference(
                    title = title.trim().replace(Regex("\\s+"), " ").take(120),
                    folderId = current.folderId,
                )
            }
        }
    }

    private fun moveLibraryDocument(documentId: String, folderId: String?) {
        library.moveDocument(documentId, folderId) {
            val current = session.documentSession.libraryDocument
            if (current?.documentId == documentId) {
                session.updateLibraryReference(current.title, folderId)
            }
        }
    }

    private fun deleteLibraryFolder(folderId: String) {
        library.deleteFolder(folderId) {
            val current = session.documentSession.libraryDocument
            if (current?.folderId == folderId) {
                session.updateLibraryReference(current.title, null)
            }
        }
    }

    private fun saveCurrentDocumentToLibrary(title: String) {
        val lease = session.acquireDocumentSessionLease(documentSessionLeaseReleaseObserverForTest)
            ?: return
        val recognized = (ocrUiState as? OcrUiState.Success)?.result
        lifecycleScope.launch {
            var openedDocumentId: String? = null
            try {
                when (val saved = library.saveSession(lease.session, title, recognized)) {
                    is LibraryResult.Success -> {
                        recordReviewMilestone(ReviewMilestone.SAVED_TO_RME)
                        when (val opened = library.openDocument(saved.value.id)) {
                            is LibraryResult.Success -> {
                                clearRecognizedText()
                                clearSearchablePdfSave()
                                clearNormalDocumentOperations()
                                if (session.openLibraryDocument(opened.value.session)) {
                                    openedDocumentId = saved.value.id
                                }
                            }
                            is LibraryResult.Failure -> Unit
                        }
                    }
                    is LibraryResult.Failure -> Unit
                }
            } finally {
                session.releaseDocumentSessionLease(lease)
                val documentId = openedDocumentId
                if (documentId != null && !session.hasActiveDocumentSessionLeases()) {
                    library.cleanupDocumentRevisions(documentId)
                }
            }
        }
    }

    private fun attemptInAppReviewIfSafe() {
        if (isFinishing || isDestroyed) return
        val eligibility = automaticReviewEligibility()
        if (!eligibility.permitsAttempt) return
        if (!reviewEligibility.isEligible(BuildConfig.VERSION_NAME, eligibility)) return
        ReviewAttemptRunner(
            markAttempt = reviewEligibility::markReviewAttempt,
            launch = {
                playReviewLauncher.launch(this) {
                    automaticReviewEligibility().permitsAttempt
                }
            },
        ).run(BuildConfig.VERSION_NAME)
    }

    private fun recordReviewMilestone(milestone: ReviewMilestone) {
        val shouldAttempt = runCatching {
            reviewEligibility.recordMilestone(
                milestone = milestone,
                outcome = ReviewMilestoneOutcome.SUCCESS,
                currentVersion = BuildConfig.VERSION_NAME,
                eligibility = automaticReviewEligibility(),
            )
        }.getOrDefault(false)
        if (shouldAttempt) attemptInAppReviewIfSafe()
    }

    private fun automaticReviewEligibility(): AutomaticReviewEligibility =
        AutomaticReviewEligibility(
            isAppResumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
            isAppUnlocked = appLock.state.value.phase != AppLockPhase.LOCKED,
            hasActiveUserFlow = session.screen != PageHarborScreen.Home ||
                session.hasActiveDocumentSessionLeases() ||
                portabilityRoute != PortabilityRoute.NONE ||
                portability.state.value.operation !is PortabilityOperationStatus.Idle ||
                migration.state.value.operation !is MigrationOperationStatus.Idle ||
                library.uiState.value.actionState !is LibraryActionState.Idle ||
                composeUiBusy ||
                backupReminder.presentation.value != null,
        )

    private fun extractLibraryPages(
        pageIds: Set<String>,
        title: String,
        removeFromOriginal: Boolean,
    ) {
        val lease = session.acquireDocumentSessionLease(documentSessionLeaseReleaseObserverForTest)
            ?: return
        val originalId = lease.session.libraryDocument?.documentId ?: run {
            session.releaseDocumentSessionLease(lease)
            return
        }
        lifecycleScope.launch {
            var originalReplaced = false
            try {
                when (
                    library.extractPages(
                        documentId = originalId,
                        pageIds = pageIds,
                        title = title,
                        removeFromOriginal = removeFromOriginal,
                    )
                ) {
                    is LibraryResult.Success -> if (removeFromOriginal) {
                        when (val opened = library.openDocument(originalId)) {
                            is LibraryResult.Success -> {
                                clearRecognizedText()
                                clearSearchablePdfSave()
                                clearNormalDocumentOperations()
                                originalReplaced = session.openLibraryDocument(opened.value.session)
                            }
                            is LibraryResult.Failure -> Unit
                        }
                    }
                    is LibraryResult.Failure -> Unit
                }
            } finally {
                session.releaseDocumentSessionLease(lease)
                if (originalReplaced && !session.hasActiveDocumentSessionLeases()) {
                    library.cleanupDocumentRevisions(originalId)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleInboundIntent(intent)
    }

    private fun openDeviceSecuritySettings() {
        startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
    }

    private fun runWhenUnlocked(
        entryPoint: AppLockProtectedEntryPoint,
        action: PendingProtectedAction,
    ) {
        if (appLock.gate(entryPoint).decision == AppLockAccessDecision.ALLOW) {
            dispatchProtectedAction(action)
        } else {
            protectedActions.enqueue(action)
        }
    }

    private fun deliverPendingProtectedActions() {
        if (appLock.state.value.phase == AppLockPhase.LOCKED) return
        while (true) {
            val action = protectedActions.poll() ?: return
            dispatchProtectedAction(action)
            if (appLock.state.value.phase == AppLockPhase.LOCKED) return
        }
    }

    private fun dispatchProtectedAction(action: PendingProtectedAction) {
        when (action) {
            is PendingProtectedAction.ScannerResult -> handleScannerResult(
                androidx.activity.result.ActivityResult(action.resultCode, action.data),
            )
            is PendingProtectedAction.ImportDocuments -> {
                if (action.uris.isEmpty()) {
                    session.cancelImportRequest()
                } else {
                    processImportedUris(
                        uris = action.uris,
                        imageSourceCategory =
                            org.synapseworks.pageharbor.document.session.DocumentSourceCategory.SELECTED_IMAGE,
                    )
                }
            }
            is PendingProtectedAction.PortabilityPickerResult -> when (val request = action.request) {
                is PortabilityPickerRequest.CreateBackupDocument ->
                    portability.onBackupDestinationResult(request.requestId, action.uri)
                is PortabilityPickerRequest.OpenRestoreDocument ->
                    portability.onRestoreSourceResult(request.requestId, action.uri)
                is PortabilityPickerRequest.OpenWholeLibraryExportTree ->
                    portability.onWholeLibraryExportTreeResult(request.requestId, action.uri)
            }
            is PendingProtectedAction.MigrationPickerResult -> {
                val accepted = action.resultCode == Activity.RESULT_OK
                val grantFlags = action.data?.flags ?: 0
                when (val request = action.request) {
                    is MigrationPickerRequest.OpenMultipleDocuments -> {
                        val uris = if (accepted) action.data.selectedContentUris() else null
                        val declaredTypes = uris.orEmpty().associateWith { uri ->
                            runCatching { contentResolver.getType(uri) }.getOrNull()
                        }
                        migration.onMultipleFilesResult(
                            requestId = request.requestId,
                            files = uris,
                            grantFlags = grantFlags,
                            declaredContentTypes = declaredTypes,
                        )
                    }
                    is MigrationPickerRequest.OpenDocumentTree -> migration.onDocumentTreeResult(
                        requestId = request.requestId,
                        tree = action.data?.data.takeIf { accepted },
                        grantFlags = grantFlags,
                    )
                }
            }
            is PendingProtectedAction.NormalPdfDestination ->
                handleNormalPdfDestinationResult(action.uri)
            is PendingProtectedAction.PageDestination -> handlePageDestinationResult(action.uri)
            is PendingProtectedAction.SearchablePdfDestination ->
                handleSearchablePdfDestinationResult(action.uri)
            is PendingProtectedAction.LaunchPortabilityPicker ->
                launchPortabilityPicker(action.request)
            is PendingProtectedAction.LaunchMigrationPicker -> launchMigrationPicker(action.request)
            is PendingProtectedAction.InboundIntent -> handleInboundIntentUnlocked(action.intent)
        }
    }

    private fun launchDocumentScanner() {
        if (session.beginScannerRequest() == null) return
        clearNormalDocumentOperations()
        val remainingPageCapacity = session.remainingPageCapacity()

        val options = GmsDocumentScannerOptions.Builder()
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_BASE_WITH_FILTER)
            .setResultFormats(
                GmsDocumentScannerOptions.RESULT_FORMAT_JPEG,
                GmsDocumentScannerOptions.RESULT_FORMAT_PDF,
            )
            .setGalleryImportAllowed(true)
            .setPageLimit(remainingPageCapacity)
            .build()

        GmsDocumentScanning.getClient(options)
            .getStartScanIntent(this)
            .addOnSuccessListener { intentSender ->
                val request = IntentSenderRequest.Builder(intentSender).build()
                scanLauncher.launch(request)
            }
            .addOnFailureListener {
                session.failScannerRequest()
            }
    }

    private fun launchFileImport() {
        if (!session.beginImportRequest(DocumentImportOrigin.PICKER)) return
        try {
            openDocumentsLauncher.launch(SUPPORTED_IMPORT_MIME_TYPES)
        } catch (_: ActivityNotFoundException) {
            session.failImportRequest(
                org.synapseworks.pageharbor.document.importing.DocumentImportError.UNREADABLE_SOURCE,
            )
        }
    }

    private fun handleInboundIntent(intent: Intent) {
        val deferredIntent = Intent(intent)
        runWhenUnlocked(
            AppLockProtectedEntryPoint.INBOUND_SHARE,
            PendingProtectedAction.InboundIntent(
                intent = deferredIntent,
                fingerprint = deferredIntent.inboundShareFingerprint(),
            ),
        )
    }

    private fun handleInboundIntentUnlocked(intent: Intent) {
        when (val input = extractInboundShareInput(intent)) {
            InboundShareInput.NotShareIntent -> Unit
            is InboundShareInput.Failure -> {
                consumeInboundActivityIntent()
                session.failImportRequest(input.reason)
            }
            is InboundShareInput.Ready -> {
                consumeInboundActivityIntent()
                portability.dismissResult()
                portabilityRoute = PortabilityRoute.MIGRATION
                selectedMigrationSource = ScannerMigrationSource.OTHER
                migrationReviewExpanded = false
                migrationShowIssues = false
                migration.dismissResult()
                migration.startInboundShare(input.resources, MigrationSourceApp.OTHER)
            }
        }
    }

    private fun Intent.inboundShareFingerprint(): String = when (
        val input = extractInboundShareInput(this)
    ) {
        is InboundShareInput.Ready -> buildString {
            append(action)
            append('|')
            append(type)
            input.resources.forEach { resource ->
                append('|')
                append(resource.uri)
                append('|')
                append(resource.declaredContentType)
                append('|')
                append(resource.grantFlags)
            }
        }
        is InboundShareInput.Failure -> "$action|$type|${input.reason}"
        InboundShareInput.NotShareIntent -> "$action|$type|$data"
    }

    private fun consumeInboundActivityIntent() {
        setIntent(Intent(this, MainActivity::class.java))
    }

    private fun processImportedUris(
        uris: List<Uri>,
        imageSourceCategory: org.synapseworks.pageharbor.document.session.DocumentSourceCategory,
    ) {
        importJob?.cancel()
        session.beginImportProcessing(uris.size)
        val capacity = session.remainingImportPageCapacity()
        importJob = lifecycleScope.launch {
            val result = try {
                importProcessor.prepare(
                    uris = uris,
                    imageSourceCategory = imageSourceCategory,
                    pageCapacity = capacity,
                    progressListener = DocumentImportProgressListener { completed, total, pages ->
                        runOnUiThread {
                            session.updateImportProgress(completed, total, pages)
                        }
                    },
                )
            } catch (_: CancellationException) {
                return@launch
            } catch (_: RuntimeException) {
                session.failImportRequest(
                    org.synapseworks.pageharbor.document.importing.DocumentImportError.UNREADABLE_SOURCE,
                )
                importJob = null
                return@launch
            }
            when (result) {
                is DocumentImportPreparationResult.Success -> {
                    clearRecognizedText()
                    clearSearchablePdfSave()
                    clearNormalDocumentOperations()
                    session.completeImportRequest(result)
                }

                is DocumentImportPreparationResult.Failure -> {
                    session.failImportRequest(result.reason)
                }
            }
            importJob = null
        }
    }

    private fun cancelImport() {
        importJob?.cancel()
        importJob = null
        session.cancelImportRequest()
    }

    private fun recognizeText() {
        if (!canStartOcr(ocrUiState)) return

        val lease = session.acquireDocumentSessionLease(documentSessionLeaseReleaseObserverForTest) ?: run {
            ocrUiState = OcrUiState.Error(OcrUiError.NO_PAGES)
            return
        }
        val pages = lease.session.pages.map { page ->
            OcrPage(
                rotationDegrees = page.rotation.degrees,
                imageMetadata = page.imageMetadata,
            ) {
                contentResolver.openInputStream(page.source.toAndroidUri())
                    ?: throw FileNotFoundException()
            }
        }
        if (pages.isEmpty()) {
            session.releaseDocumentSessionLease(lease)
            ocrUiState = OcrUiState.Error(OcrUiError.NO_PAGES)
            return
        }

        ocrUiState = OcrUiState.Recognizing
        val operationId = ocrOperationTracker.begin()
        ocrJob = lifecycleScope.launch {
            try {
                val result = try {
                    withContext(Dispatchers.IO) {
                        ocrEngine.recognize(pages)
                    }
                } catch (_: kotlinx.coroutines.CancellationException) {
                    return@launch
                } catch (_: Exception) {
                    if (ocrOperationTracker.claimCompletion(operationId) ==
                        OcrOperationTracker.CompletionClaim.CLAIMED
                    ) {
                        ocrUiState = OcrUiState.Error(OcrUiError.UNEXPECTED_FAILURE)
                        ocrTerminalStateObserverForTest?.invoke()
                    }
                    return@launch
                }
                if (ocrOperationTracker.claimCompletion(operationId) !=
                    OcrOperationTracker.CompletionClaim.CLAIMED
                ) {
                    return@launch
                }
                ocrUiState = ocrStateAfterResult(result)
                ocrSelectedPageIndex = 0
                lease.session.libraryDocument?.let { saved ->
                    library.indexOcr(
                        documentId = saved.documentId,
                        pageIds = lease.session.pages.map(DocumentPage::persistentId),
                        result = result,
                    )
                }
                ocrTerminalStateObserverForTest?.invoke()
            } finally {
                session.releaseDocumentSessionLease(lease)
            }
        }
    }

    private fun clearRecognizedText() {
        ocrOperationTracker.invalidate()
        ocrJob?.cancel()
        ocrJob = null
        ocrUiState = clearedOcrState()
        ocrSelectedPageIndex = 0
    }

    private fun choosePdfDestination() {
        if (pdfSaveState == PdfSaveState.ChoosingDestination || pdfSaveState == PdfSaveState.Saving) {
            return
        }

        if (!canExportNormalPdf(session.documentSession)) {
            pdfSaveState = PdfSaveState.Error(PdfExportResult.SourceMissing)
            return
        }
        val plan = normalPdfExportPlan(session.documentSession)
        if (plan is NormalPdfExportPlan.DirectScannerPdf && plan.source == null) {
            pdfSaveState = PdfSaveState.Error(PdfExportResult.SourceMissing)
            return
        }

        val operationId = normalPdfSaveOperationTracker.begin(session.documentRevision)
        pendingNormalPdfDestinationToken = operationId
        pdfSaveState = PdfSaveState.ChoosingDestination
        try {
            val filename = getString(R.string.pdf_default_filename)
            normalPdfDestinationLauncherOverride?.invoke(filename)
                ?: createPdfDocumentLauncher.launch(filename)
        } catch (_: ActivityNotFoundException) {
            pendingNormalPdfDestinationToken = null
            if (normalPdfSaveOperationTracker.finishForCurrentDocument(operationId)) {
                pdfSaveState = PdfSaveState.Error(PdfExportResult.DestinationUnavailable)
            }
        } catch (_: RuntimeException) {
            pendingNormalPdfDestinationToken = null
            if (normalPdfSaveOperationTracker.finishForCurrentDocument(operationId)) {
                pdfSaveState = PdfSaveState.Error(PdfExportResult.DestinationUnavailable)
            }
        }
    }

    private fun handleNormalPdfDestinationResult(destinationUri: Uri?) {
        val operationId = pendingNormalPdfDestinationToken ?: return
        pendingNormalPdfDestinationToken = null
        if (!normalPdfSaveOperationTracker.isCurrentForCurrentDocument(operationId)) return
        if (destinationUri == null) {
            if (normalPdfSaveOperationTracker.finishForCurrentDocument(operationId)) {
                pdfSaveState = PdfSaveState.Idle
            }
            return
        }
        savePdfToDestination(destinationUri, operationId)
    }

    private fun saveSearchablePdf() {
        if (searchablePdfSaveState.isInProgress()) return
        val lease = session.acquireDocumentSessionLease(documentSessionLeaseReleaseObserverForTest)
        if (lease == null) {
            searchablePdfSaveState = SearchablePdfSaveState.Error(SearchablePdfSaveError.NO_PAGES)
            return
        }
        val documentPages = lease.session.pages

        clearSearchablePdfSave()
        val operationId = searchablePdfOperationTracker.begin()
        searchablePdfSaveState = SearchablePdfSaveState.Preparing
        val existingOcrResult = (ocrUiState as? OcrUiState.Success)?.result
        searchablePdfExportJob = lifecycleScope.launch {
            try {
                val preparedExport = searchablePdfExportCoordinator.prepare(
                    SearchablePdfExportRequest(
                        pageUris = documentPages.map { page -> page.source.toAndroidUri() },
                        visualPages = documentPages.map { page ->
                            SearchablePdfVisualPage(
                                pageId = page.id.value,
                                originalUri = page.source.toAndroidUri(),
                                filter = page.filter,
                                rotation = page.rotation,
                                contentType = page.contentType,
                                imageMetadata = page.imageMetadata,
                            )
                        },
                        ocrResult = existingOcrResult,
                        progressListener = SearchablePdfExportProgressListener { progress ->
                            runOnUiThread {
                                if (searchablePdfOperationTracker.acceptsProgress(operationId)) {
                                    searchablePdfSaveState = searchablePdfSaveStateForProgress(progress)
                                }
                            }
                        },
                    ),
                )
                when (searchablePdfOperationTracker.claimCompletion(operationId)) {
                    SearchablePdfOperationTracker.CompletionClaim.SUPERSEDED -> {
                        if (preparedExport is SearchablePdfPreparedExport.Ready) {
                            searchablePdfExportCoordinator.discardPreparedExport(preparedExport)
                        }
                        return@launch
                    }

                    SearchablePdfOperationTracker.CompletionClaim.DUPLICATE -> return@launch
                    SearchablePdfOperationTracker.CompletionClaim.CLAIMED -> Unit
                }
                when (preparedExport) {
                    is SearchablePdfPreparedExport.Ready -> {
                        searchablePdfPreparedExport = preparedExport
                        searchablePdfSaveState = SearchablePdfSaveState.ChoosingDestination
                        try {
                            val filename = preparedExport.filenameSuggestion.filename
                            val launcher = searchablePdfDestinationLauncherOverride
                            if (launcher != null) {
                                launcher(filename)
                            } else {
                                createSearchablePdfDocumentLauncher.launch(filename)
                            }
                        } catch (_: ActivityNotFoundException) {
                            searchablePdfExportCoordinator.discardPreparedExport(preparedExport)
                            searchablePdfPreparedExport = null
                            searchablePdfSaveState = SearchablePdfSaveState.Error(
                                SearchablePdfSaveError.DESTINATION_UNAVAILABLE,
                            )
                        } catch (_: RuntimeException) {
                            searchablePdfExportCoordinator.discardPreparedExport(preparedExport)
                            searchablePdfPreparedExport = null
                            searchablePdfSaveState = SearchablePdfSaveState.Error(
                                SearchablePdfSaveError.DESTINATION_UNAVAILABLE,
                            )
                        }
                    }

                    is SearchablePdfPreparedExport.Failure -> {
                        searchablePdfSaveState = SearchablePdfSaveState.Error(
                            when (preparedExport.reason) {
                                org.synapseworks.pageharbor.document.searchablepdf.SearchablePdfPreparationError.NO_PAGES ->
                                    SearchablePdfSaveError.NO_PAGES

                                org.synapseworks.pageharbor.document.searchablepdf.SearchablePdfPreparationError.SOURCE_TOO_LARGE ->
                                    SearchablePdfSaveError.SOURCE_TOO_LARGE

                                org.synapseworks.pageharbor.document.searchablepdf.SearchablePdfPreparationError.OCR_FAILED,
                                org.synapseworks.pageharbor.document.searchablepdf.SearchablePdfPreparationError.OCR_RESULT_MISMATCH,
                                org.synapseworks.pageharbor.document.searchablepdf.SearchablePdfPreparationError.TEMPORARY_STORAGE_UNAVAILABLE,
                                org.synapseworks.pageharbor.document.searchablepdf.SearchablePdfPreparationError.GENERATION_FAILED,
                                -> SearchablePdfSaveError.PREPARATION_FAILED
                            },
                        )
                    }
                }
            } catch (_: CancellationException) {
                // The invalidating action owns user-visible state.
            } catch (_: Exception) {
                if (searchablePdfOperationTracker.claimCompletion(operationId) ==
                    SearchablePdfOperationTracker.CompletionClaim.CLAIMED
                ) {
                    searchablePdfSaveState = SearchablePdfSaveState.Error(
                        SearchablePdfSaveError.PREPARATION_FAILED,
                    )
                }
            } finally {
                session.releaseDocumentSessionLease(lease)
            }
        }
    }

    private fun clearSearchablePdfSave() {
        searchablePdfOperationTracker.invalidate()
        searchablePdfExportJob?.cancel()
        searchablePdfExportJob = null
        searchablePdfPreparedExport?.let(searchablePdfExportCoordinator::discardPreparedExport)
        searchablePdfPreparedExport = null
        searchablePdfSaveState = SearchablePdfSaveState.Idle
    }

    private fun sharePdf() {
        if (pdfShareState == PdfShareState.Preparing) return

        if (!canExportNormalPdf(session.documentSession)) {
            pdfShareState = PdfShareState.Error(PdfShareError.NoPdfAvailable)
            return
        }

        val lease = session.acquireDocumentSessionLease(documentSessionLeaseReleaseObserverForTest) ?: run {
            pdfShareState = PdfShareState.Error(PdfShareError.NoPdfAvailable)
            return
        }
        val plan = normalPdfExportPlan(lease.session)
        val operationId = pdfShareOperationTracker.begin(session.documentRevision)
        pdfShareState = PdfShareState.Preparing
        pdfShareJob = lifecycleScope.launch {
            val preparedOwner = AtomicReference<PdfSharePreparationResult.Ready?>()
            var handedOff = false
            try {
                val preparationResult = withContext(Dispatchers.IO) {
                    val result = pdfSharePreparationOverride?.invoke(plan)
                        ?: prepareNormalPdfForSharing(plan)
                    if (result is PdfSharePreparationResult.Ready) preparedOwner.set(result)
                    if (!pdfShareOperationTracker.isCurrentForCurrentDocument(operationId)) {
                        preparedOwner.getAndSet(null)?.let(::discardPreparedPdfShare)
                    }
                    result
                }
                if (!pdfShareOperationTracker.isCurrentForCurrentDocument(operationId)) return@launch
                when (preparationResult) {
                    is PdfSharePreparationResult.Ready -> {
                        handedOff = launchPdfShare(preparationResult, operationId)
                        if (handedOff) preparedOwner.set(null)
                    }

                    PdfSharePreparationResult.SourceMissing -> if (
                        pdfShareOperationTracker.finishForCurrentDocument(operationId)
                    ) {
                        pdfShareState = PdfShareState.Error(PdfShareError.NoPdfAvailable)
                    }

                    PdfSharePreparationResult.SourceTooLarge -> if (
                        pdfShareOperationTracker.finishForCurrentDocument(operationId)
                    ) {
                        pdfShareState = PdfShareState.Error(PdfShareError.SourceTooLarge)
                    }

                    PdfSharePreparationResult.Failed -> if (
                        pdfShareOperationTracker.finishForCurrentDocument(operationId)
                    ) {
                        pdfShareState = PdfShareState.Error(PdfShareError.UnexpectedFailure)
                    }
                }
            } catch (_: CancellationException) {
                // Invalidation owns user-visible state; the finally block owns private cleanup.
            } finally {
                if (!handedOff) {
                    preparedOwner.getAndSet(null)?.let(::discardPreparedPdfShare)
                }
                session.releaseDocumentSessionLease(lease)
            }
        }
    }

    private fun launchPdfShare(
        preparation: PdfSharePreparationResult.Ready,
        operationId: DocumentOperationToken,
    ): Boolean {
        if (!pdfShareOperationTracker.isCurrentForCurrentDocument(operationId)) return false
        when (val result = createPdfShareIntent(preparation.uri)) {
            is PdfShareIntentResult.Success -> {
                try {
                    pdfShareLauncherOverride?.invoke(preparation.uri) ?: run {
                        val chooser = Intent.createChooser(
                            result.intent,
                            getString(R.string.pdf_share_chooser_title),
                        )
                        startActivity(chooser)
                    }
                    if (!pdfShareOperationTracker.finishForCurrentDocument(operationId)) return false
                    pdfShareState = PdfShareState.Idle
                    return true
                } catch (_: ActivityNotFoundException) {
                    if (pdfShareOperationTracker.finishForCurrentDocument(operationId)) {
                        pdfShareState = PdfShareState.Error(PdfShareError.ShareTargetUnavailable)
                    }
                } catch (_: SecurityException) {
                    if (pdfShareOperationTracker.finishForCurrentDocument(operationId)) {
                        pdfShareState = PdfShareState.Error(PdfShareError.UnexpectedFailure)
                    }
                } catch (_: IllegalArgumentException) {
                    if (pdfShareOperationTracker.finishForCurrentDocument(operationId)) {
                        pdfShareState = PdfShareState.Error(PdfShareError.UnexpectedFailure)
                    }
                } catch (_: RuntimeException) {
                    if (pdfShareOperationTracker.finishForCurrentDocument(operationId)) {
                        pdfShareState = PdfShareState.Error(PdfShareError.UnexpectedFailure)
                    }
                }
            }

            PdfShareIntentResult.NoPdfAvailable -> {
                if (pdfShareOperationTracker.finishForCurrentDocument(operationId)) {
                    pdfShareState = PdfShareState.Error(PdfShareError.NoPdfAvailable)
                }
            }

            PdfShareIntentResult.InvalidUri -> {
                if (pdfShareOperationTracker.finishForCurrentDocument(operationId)) {
                    pdfShareState = PdfShareState.Error(PdfShareError.InvalidUri)
                }
            }
        }
        return false
    }

    private fun exportPages() {
        if (pageExportState is PageExportState.ChoosingDestination ||
            pageExportState is PageExportState.Exporting
        ) {
            return
        }

        pageExportState = startPageExport(session.documentPages.size)
        val initialState = pageExportState as? PageExportState.ChoosingDestination ?: return
        val operationId = pageExportOperationTracker.begin(session.documentRevision)
        launchPageDestination(initialState, operationId)
    }

    private fun launchPageDestination(
        state: PageExportState.ChoosingDestination,
        operationId: DocumentOperationToken,
    ) {
        if (!pageExportOperationTracker.isCurrentForCurrentDocument(operationId)) return
        pendingPageDestinationToken = operationId
        try {
            val filename = getString(R.string.page_export_default_filename, state.pageNumber)
            pageDestinationLauncherOverride?.invoke(filename)
                ?: createPageDocumentLauncher.launch(filename)
        } catch (_: ActivityNotFoundException) {
            pendingPageDestinationToken = null
            if (pageExportOperationTracker.finishForCurrentDocument(operationId)) {
                pageExportState = PageExportState.Error(PageExportResult.DestinationUnavailable)
            }
        } catch (_: RuntimeException) {
            pendingPageDestinationToken = null
            if (pageExportOperationTracker.finishForCurrentDocument(operationId)) {
                pageExportState = PageExportState.Error(PageExportResult.DestinationUnavailable)
            }
        }
    }

    private fun handlePageDestinationResult(destinationUri: Uri?) {
        val operationId = pendingPageDestinationToken ?: return
        pendingPageDestinationToken = null
        if (!pageExportOperationTracker.isCurrentForCurrentDocument(operationId)) return
        val currentState = pageExportState as? PageExportState.ChoosingDestination ?: return
        if (destinationUri == null) {
            if (pageExportOperationTracker.finishForCurrentDocument(operationId)) {
                pageExportState = pageExportStateAfterCancellation(currentState.pageNumber)
            }
            return
        }
        exportPageToDestination(currentState, destinationUri, operationId)
    }

    private fun exportPageToDestination(
        state: PageExportState.ChoosingDestination,
        destinationUri: Uri,
        operationId: DocumentOperationToken,
    ) {
        if (!pageExportOperationTracker.isCurrentForCurrentDocument(operationId)) return
        val lease = session.acquireDocumentSessionLease(documentSessionLeaseReleaseObserverForTest) ?: run {
            if (pageExportOperationTracker.finishForCurrentDocument(operationId)) {
                pageExportState = PageExportState.Error(PageExportResult.SourceMissing)
            }
            return
        }
        val page = lease.session.pages.getOrNull(state.pageNumber - 1)
        if (page == null) {
            session.releaseDocumentSessionLease(lease)
            if (pageExportOperationTracker.finishForCurrentDocument(operationId)) {
                pageExportState = PageExportState.Error(PageExportResult.SourceMissing)
            }
            return
        }

        pageExportState = PageExportState.Exporting(
            pageNumber = state.pageNumber,
            pageCount = state.pageCount,
        )
        pageExportJob = lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    pageExportOverride?.invoke(page, destinationUri)
                        ?: exportScannedPage(page, destinationUri)
                }
                if (!pageExportOperationTracker.isCurrentForCurrentDocument(operationId)) return@launch
                if (result != PageExportResult.Success) {
                    if (pageExportOperationTracker.finishForCurrentDocument(operationId)) {
                        pageExportState = PageExportState.Error(result)
                    }
                    return@launch
                }

                pageExportState = pageExportStateAfterSuccess(
                    pageNumber = state.pageNumber,
                    pageCount = state.pageCount,
                )
                val next = pageExportState as? PageExportState.ChoosingDestination
                if (next == null) {
                    pageExportOperationTracker.finishForCurrentDocument(operationId)
                } else {
                    launchPageDestination(next, operationId)
                }
            } catch (_: CancellationException) {
                // A stale operation owns no UI state or subsequent picker.
            } finally {
                session.releaseDocumentSessionLease(lease)
            }
        }
    }

    private fun savePdfToDestination(destinationUri: Uri, operationId: DocumentOperationToken) {
        if (!normalPdfSaveOperationTracker.isCurrentForCurrentDocument(operationId)) return
        val lease = session.acquireDocumentSessionLease(documentSessionLeaseReleaseObserverForTest) ?: run {
            if (normalPdfSaveOperationTracker.finishForCurrentDocument(operationId)) {
                pdfSaveState = PdfSaveState.Error(PdfExportResult.SourceMissing)
            }
            return
        }
        val plan = normalPdfExportPlan(lease.session)
        if (plan is NormalPdfExportPlan.DirectScannerPdf && plan.source == null) {
            session.releaseDocumentSessionLease(lease)
            if (normalPdfSaveOperationTracker.finishForCurrentDocument(operationId)) {
                pdfSaveState = PdfSaveState.Error(PdfExportResult.SourceMissing)
            }
            return
        }

        pdfSaveState = PdfSaveState.Saving
        normalPdfSaveJob = lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    normalPdfWriteOverride?.invoke(plan, destinationUri)
                        ?: writeNormalPdfToDestination(plan, destinationUri)
                }
                if (!normalPdfSaveOperationTracker.finishForCurrentDocument(operationId)) return@launch
                pdfSaveState = when (result) {
                    PdfExportResult.Success -> {
                        recordReviewMilestone(ReviewMilestone.PDF_EXPORTED)
                        PdfSaveState.Saved
                    }
                    PdfExportResult.SourceMissing,
                    PdfExportResult.SourceTooLarge,
                    PdfExportResult.DestinationUnavailable,
                    PdfExportResult.WriteFailed,
                    -> PdfSaveState.Error(result)
                }
            } catch (_: CancellationException) {
                // The invalidating action already restored the correct retryable state.
            } finally {
                session.releaseDocumentSessionLease(lease)
            }
        }
    }

    private fun clearNormalDocumentOperations() {
        normalPdfSaveOperationTracker.invalidate()
        pdfShareOperationTracker.invalidate()
        pageExportOperationTracker.invalidate()
        pendingNormalPdfDestinationToken = null
        pendingPageDestinationToken = null
        normalPdfSaveJob?.cancel()
        pdfShareJob?.cancel()
        pageExportJob?.cancel()
        normalPdfSaveJob = null
        pdfShareJob = null
        pageExportJob = null
        pdfSaveState = PdfSaveState.Idle
        pdfShareState = PdfShareState.Idle
        pageExportState = PageExportState.Idle
    }

    private fun clearStaleNormalDocumentOperations(documentRevision: Long) {
        if (normalPdfSaveOperationTracker.invalidateIfDocumentRevisionChanged(documentRevision)) {
            pendingNormalPdfDestinationToken = null
            normalPdfSaveJob?.cancel()
            normalPdfSaveJob = null
            pdfSaveState = PdfSaveState.Idle
        }
        if (pdfShareOperationTracker.invalidateIfDocumentRevisionChanged(documentRevision)) {
            pdfShareJob?.cancel()
            pdfShareJob = null
            pdfShareState = PdfShareState.Idle
        }
        if (pageExportOperationTracker.invalidateIfDocumentRevisionChanged(documentRevision)) {
            pendingPageDestinationToken = null
            pageExportJob?.cancel()
            pageExportJob = null
            pageExportState = PageExportState.Idle
        }
    }

    private fun DocumentOperationTracker.isCurrentForCurrentDocument(
        operation: DocumentOperationToken,
    ): Boolean = isCurrent(operation, session.documentRevision)

    private fun DocumentOperationTracker.finishForCurrentDocument(
        operation: DocumentOperationToken,
    ): Boolean = finish(operation, session.documentRevision)

    private suspend fun writeNormalPdfToDestination(
        plan: NormalPdfExportPlan,
        destinationUri: Uri,
    ): PdfExportResult = when (plan) {
        is NormalPdfExportPlan.DirectScannerPdf -> {
            val source = plan.source ?: return PdfExportResult.SourceMissing
            copyScannedPdf(source.toAndroidUri(), destinationUri)
        }

        is NormalPdfExportPlan.RecomposeFromPages -> when (
            val recomposed = recomposeNormalPdf(this@MainActivity, plan.pages)
        ) {
            is NormalPdfRecompositionResult.Ready -> try {
                copyRecomposedPdf(recomposed.file, destinationUri)
            } finally {
                deleteNormalPdfRecomposition(recomposed.file)
            }

            NormalPdfRecompositionResult.SourceMissing -> PdfExportResult.SourceMissing
            NormalPdfRecompositionResult.SourceTooLarge -> PdfExportResult.SourceTooLarge
            NormalPdfRecompositionResult.Failed -> PdfExportResult.WriteFailed
        }
    }

    private suspend fun prepareNormalPdfForSharing(
        plan: NormalPdfExportPlan,
    ): PdfSharePreparationResult = when (plan) {
        is NormalPdfExportPlan.DirectScannerPdf -> {
            preparePdfForSharing(this@MainActivity, plan.source?.toAndroidUri())
        }

        is NormalPdfExportPlan.RecomposeFromPages -> when (
            val recomposed = recomposeNormalPdf(this@MainActivity, plan.pages)
        ) {
            is NormalPdfRecompositionResult.Ready -> try {
                preparePdfForSharing(this@MainActivity, Uri.fromFile(recomposed.file))
            } finally {
                deleteNormalPdfRecomposition(recomposed.file)
            }

            NormalPdfRecompositionResult.SourceMissing -> PdfSharePreparationResult.SourceMissing
            NormalPdfRecompositionResult.SourceTooLarge -> PdfSharePreparationResult.SourceTooLarge
            NormalPdfRecompositionResult.Failed -> PdfSharePreparationResult.Failed
        }
    }

    private fun copyScannedPdf(sourceUri: Uri, destinationUri: Uri): PdfExportResult {
        val source = try {
            contentResolver.openInputStream(sourceUri)
        } catch (_: FileNotFoundException) {
            null
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            return PdfExportResult.SourceMissing
        } catch (_: IllegalArgumentException) {
            return PdfExportResult.SourceMissing
        }

        val destination = try {
            contentResolver.openOutputStream(destinationUri)
        } catch (_: FileNotFoundException) {
            source.closeSafely()
            return PdfExportResult.DestinationUnavailable
        } catch (_: IOException) {
            source.closeSafely()
            return PdfExportResult.DestinationUnavailable
        } catch (_: SecurityException) {
            source.closeSafely()
            return PdfExportResult.DestinationUnavailable
        } catch (_: IllegalArgumentException) {
            source.closeSafely()
            return PdfExportResult.DestinationUnavailable
        }

        return copyPdfToDestination(source, destination)
    }

    private fun copyRecomposedPdf(sourceFile: java.io.File, destinationUri: Uri): PdfExportResult {
        val source = try {
            sourceFile.inputStream()
        } catch (_: FileNotFoundException) {
            null
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
        val destination = try {
            contentResolver.openOutputStream(destinationUri)
        } catch (_: FileNotFoundException) {
            source.closeSafely()
            return PdfExportResult.DestinationUnavailable
        } catch (_: IOException) {
            source.closeSafely()
            return PdfExportResult.DestinationUnavailable
        } catch (_: SecurityException) {
            source.closeSafely()
            return PdfExportResult.DestinationUnavailable
        } catch (_: IllegalArgumentException) {
            source.closeSafely()
            return PdfExportResult.DestinationUnavailable
        }
        return copyPdfToDestination(source, destination)
    }

    private fun copyScannedPage(
        sourceUri: Uri,
        destinationUri: Uri,
        imageMetadata: org.synapseworks.pageharbor.document.session.DocumentImageMetadata,
    ): PageExportResult {
        val source = try {
            contentResolver.openInputStream(sourceUri)
        } catch (_: FileNotFoundException) {
            null
        } catch (_: IOException) {
            return PageExportResult.SourceMissing
        } catch (_: SecurityException) {
            return PageExportResult.SourceMissing
        } catch (_: IllegalArgumentException) {
            return PageExportResult.SourceMissing
        }

        val destination = try {
            contentResolver.openOutputStream(destinationUri)
        } catch (_: FileNotFoundException) {
            source.closeSafely()
            return PageExportResult.DestinationUnavailable
        } catch (_: IOException) {
            source.closeSafely()
            return PageExportResult.DestinationUnavailable
        } catch (_: SecurityException) {
            source.closeSafely()
            return PageExportResult.DestinationUnavailable
        } catch (_: IllegalArgumentException) {
            source.closeSafely()
            return PageExportResult.DestinationUnavailable
        }

        return copyPageToDestination(source, destination, imageMetadata)
    }

    private fun exportScannedPage(page: DocumentPage, destinationUri: Uri): PageExportResult =
        when (val plan = pageJpegExportPlan(page)) {
            is PageJpegExportPlan.DirectCopy -> {
                copyScannedPage(page.source.toAndroidUri(), destinationUri, page.imageMetadata)
            }

            is PageJpegExportPlan.Filtered -> {
                writeFilteredScannedPage(
                    sourceUri = page.source.toAndroidUri(),
                    destinationUri = destinationUri,
                    filter = plan.filter,
                    rotation = plan.rotation,
                    imageMetadata = page.imageMetadata,
                )
            }
        }

    private fun writeFilteredScannedPage(
        sourceUri: Uri,
        destinationUri: Uri,
        filter: org.synapseworks.pageharbor.image.DocumentFilter,
        rotation: org.synapseworks.pageharbor.document.session.DocumentPageRotation,
        imageMetadata: org.synapseworks.pageharbor.document.session.DocumentImageMetadata,
    ): PageExportResult {
        val destination = try {
            contentResolver.openOutputStream(destinationUri)
        } catch (_: FileNotFoundException) {
            return PageExportResult.DestinationUnavailable
        } catch (_: IOException) {
            return PageExportResult.DestinationUnavailable
        } catch (_: SecurityException) {
            return PageExportResult.DestinationUnavailable
        } catch (_: IllegalArgumentException) {
            return PageExportResult.DestinationUnavailable
        }

        return writeFilteredJpegToDestination(
            openSource = {
                try {
                    contentResolver.openInputStream(sourceUri)
                } catch (_: FileNotFoundException) {
                    null
                } catch (_: IOException) {
                    null
                } catch (_: SecurityException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                }
            },
            destination = destination,
            filter = filter,
            rotation = rotation,
            imageMetadata = imageMetadata,
        )
    }

    private fun InputStream?.closeSafely() {
        try {
            this?.close()
        } catch (_: IOException) {
            // Nothing user-actionable, and paths or document details must not be logged.
        }
    }

    override fun onDestroy() {
        importJob?.cancel()
        importJob = null
        ocrJob?.cancel()
        ocrJob = null
        clearSearchablePdfSave()
        clearNormalDocumentOperations()
        session.resetTransientStateForRecreation()
        super.onDestroy()
    }

    internal fun restoreCompletedSessionForTest(
        summary: ScannerSpikeState.ResultSummary,
        ocrResult: org.synapseworks.pageharbor.ocr.OcrResult? = null,
        screen: PageHarborScreen = PageHarborScreen.ScanResult,
        selectedOcrPageIndex: Int = 0,
        searchablePdfSaveState: SearchablePdfSaveState = SearchablePdfSaveState.Idle,
        pageUris: List<Uri> = emptyList(),
    ) {
        clearNormalDocumentOperations()
        if (session.documentPages.isNotEmpty()) session.clearScan()
        val restoredPageUris = pageUris.ifEmpty {
            List(summary.jpegPageCount) { index ->
                Uri.Builder()
                    .scheme("content")
                    .authority("${packageName}.test")
                    .appendPath("restored-session")
                    .appendPath(index.toString())
                    .build()
            }
        }
        session.replaceScan(summary, scannedPdfUri = null, scannedPageUris = restoredPageUris)
        session.ocrUiState = ocrResult?.let(OcrUiState::Success) ?: OcrUiState.Idle
        session.screen = screen
        session.ocrSelectedPageIndex = selectedOcrPageIndex
        session.searchablePdfSaveState = searchablePdfSaveState
    }

    /**
     * Instrumentation-only dependency seam. Production always retains the local ML Kit/PDFBox
     * implementations and the Android SAF launcher; no runtime setting or manifest entry can use
     * this seam. R8 removes these internal callers from production when they are unused.
     */
    internal fun replaceOperationsForTest(
        ocrEngine: OcrEngine = this.ocrEngine,
        searchablePdfExportCoordinator: SearchablePdfExportCoordinator =
            this.searchablePdfExportCoordinator,
        onSearchablePdfDestinationRequested: ((String) -> Unit)? =
            searchablePdfDestinationLauncherOverride,
        onOcrTerminalState: (() -> Unit)? = ocrTerminalStateObserverForTest,
    ) {
        this.ocrEngine = ocrEngine
        searchablePdfExportCoordinatorForCurrentActivity = searchablePdfExportCoordinator
        searchablePdfDestinationLauncherOverride = onSearchablePdfDestinationRequested
        ocrTerminalStateObserverForTest = onOcrTerminalState
    }

    internal fun recognizeTextForTest() = recognizeText()

    internal fun saveSearchablePdfForTest() = saveSearchablePdf()

    internal fun chooseNormalPdfDestinationForTest() = choosePdfDestination()

    internal fun deliverNormalPdfDestinationForTest(uri: Uri?) =
        handleNormalPdfDestinationResult(uri)

    internal fun sharePdfForTest() = sharePdf()

    internal fun exportPagesForTest() = exportPages()

    internal fun deliverPageDestinationForTest(uri: Uri?) = handlePageDestinationResult(uri)

    internal fun sessionScreenForTest(): PageHarborScreen = session.screen

    internal fun sessionSummaryForTest(): ScannerSpikeState = session.scannerState

    internal fun selectedOcrPageForTest(): Int = session.ocrSelectedPageIndex

    internal fun searchablePdfStateForTest(): SearchablePdfSaveState = session.searchablePdfSaveState

    internal fun ocrStateForTest(): OcrUiState = session.ocrUiState

    internal fun normalPdfStateForTest(): PdfSaveState = session.pdfSaveState

    internal fun pdfShareStateForTest(): PdfShareState = session.pdfShareState

    internal fun pageExportStateForTest(): PageExportState = session.pageExportState

    internal fun documentRevisionForTest(): Long = session.documentRevision

    internal fun setFirstPageFilterForTest(
        filter: org.synapseworks.pageharbor.image.DocumentFilter,
    ): Boolean = session.documentPages.firstOrNull()?.let { page ->
        session.setPageFilter(page.id.value, filter)
    } ?: false

    internal fun rotateFirstPageForTest(): Boolean = session.documentPages.firstOrNull()?.let { page ->
        session.rotatePageClockwise(page.id.value)
    } ?: false

    internal fun reorderPagesForTest(pageIds: List<Long>): Boolean = session.reorderPages(pageIds)

    internal fun installDocumentSessionForTest(documentSession: DocumentSession) {
        clearRecognizedText()
        clearSearchablePdfSave()
        clearNormalDocumentOperations()
        if (session.documentPages.isNotEmpty()) session.clearScan()
        session.installDocumentSessionForTest(documentSession)
    }

    internal fun observeDocumentSessionLeaseReleaseForTest(observer: () -> Unit) {
        documentSessionLeaseReleaseObserverForTest = observer
    }

    internal fun replaceNormalOperationsForTest(
        writeNormalPdf: (suspend (NormalPdfExportPlan, Uri) -> PdfExportResult)? =
            normalPdfWriteOverride,
        preparePdfShare: (suspend (NormalPdfExportPlan) -> PdfSharePreparationResult)? =
            pdfSharePreparationOverride,
        exportPage: (suspend (DocumentPage, Uri) -> PageExportResult)? = pageExportOverride,
        onNormalPdfDestinationRequested: ((String) -> Unit)? =
            normalPdfDestinationLauncherOverride,
        onPageDestinationRequested: ((String) -> Unit)? = pageDestinationLauncherOverride,
        onPdfShareRequested: ((Uri) -> Unit)? = pdfShareLauncherOverride,
    ) {
        normalPdfWriteOverride = writeNormalPdf
        pdfSharePreparationOverride = preparePdfShare
        pageExportOverride = exportPage
        normalPdfDestinationLauncherOverride = onNormalPdfDestinationRequested
        pageDestinationLauncherOverride = onPageDestinationRequested
        pdfShareLauncherOverride = onPdfShareRequested
    }

    internal fun discardForTest() {
        clearRecognizedText()
        clearSearchablePdfSave()
        clearNormalDocumentOperations()
        session.clearScan()
    }

    private fun openSourceCode() {
        val sourceIntent = Intent(
            Intent.ACTION_VIEW,
            getString(R.string.source_code_url).toUri(),
        )

        try {
            startActivity(sourceIntent)
        } catch (_: ActivityNotFoundException) {
            // No browser is available. Keep the app stable and avoid logging local state.
        }
    }

    private fun openPlayListing() {
        val marketIntent = Intent(
            Intent.ACTION_VIEW,
            getString(R.string.rme_market_url).toUri(),
        )
        val webIntent = Intent(
            Intent.ACTION_VIEW,
            getString(R.string.rme_play_url).toUri(),
        )
        try {
            startActivity(marketIntent)
        } catch (_: ActivityNotFoundException) {
            try {
                startActivity(webIntent)
            } catch (_: ActivityNotFoundException) {
                // No compatible store or browser is installed. Keep local app state untouched.
            }
        }
    }

    private fun suggestFeature() {
        val supportAddress = getString(R.string.rme_feature_email)
        val subject = getString(R.string.rme_feature_subject)
        val emailIntent = Intent(
            Intent.ACTION_SENDTO,
            "mailto:$supportAddress?subject=${Uri.encode(subject)}".toUri(),
        )
        try {
            startActivity(emailIntent)
        } catch (_: ActivityNotFoundException) {
            val fallback = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_EMAIL, arrayOf(supportAddress))
                putExtra(Intent.EXTRA_SUBJECT, subject)
            }
            try {
                startActivity(Intent.createChooser(fallback, subject))
            } catch (_: ActivityNotFoundException) {
                // No email or share target is installed. Keep local app state untouched.
            }
        }
    }

    private fun shareRme() {
        val message = getString(
            R.string.rme_share_message,
            getString(R.string.rme_play_url),
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, message)
        }
        try {
            startActivity(
                Intent.createChooser(
                    shareIntent,
                    getString(R.string.rme_share_chooser_title),
                ),
            )
        } catch (_: ActivityNotFoundException) {
            // No share target is installed. Keep local app state untouched.
        }
    }
}

private fun Intent?.selectedContentUris(): List<Uri> {
    if (this == null) return emptyList()
    val selected = linkedMapOf<String, Uri>()
    data?.let { uri -> selected[uri.toString()] = uri }
    clipData?.let { clips ->
        repeat(clips.itemCount) { index ->
            clips.getItemAt(index).uri?.let { uri -> selected[uri.toString()] = uri }
        }
    }
    return selected.values.toList()
}
