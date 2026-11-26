package org.synapseworks.pageharbor

import org.synapseworks.pageharbor.ocr.persistence.toEffectiveOcrRequestOrNull

import android.app.Activity
import org.synapseworks.pageharbor.image.ManualImageAdjustments
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import org.synapseworks.pageharbor.ui.theme.setPageHarborContent
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.synapseworks.pageharbor.document.PageExportResult
import org.synapseworks.pageharbor.document.PageExportState
import org.synapseworks.pageharbor.document.PageJpegExportPlan
import org.synapseworks.pageharbor.document.crop.ImportCropViewModel
import org.synapseworks.pageharbor.document.capture.CaptureMode
import org.synapseworks.pageharbor.document.crop.supportsImportCrop
import org.synapseworks.pageharbor.document.importing.DocumentImportOrigin
import org.synapseworks.pageharbor.document.importing.isSinglePhotoShare
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
import org.synapseworks.pageharbor.document.protectedpdf.PasswordPdfExportViewModel
import org.synapseworks.pageharbor.ui.protectedpdf.PasswordPdfExportDialog
import org.synapseworks.pageharbor.document.localtools.LocalPdfToolsViewModel
import org.synapseworks.pageharbor.ui.localtools.LocalPdfToolsDialog
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
import org.synapseworks.pageharbor.document.cardcopy.CardCopyAcquisition
import org.synapseworks.pageharbor.document.cardcopy.CardCopyFailure
import org.synapseworks.pageharbor.document.cardcopy.CardCopySide
import org.synapseworks.pageharbor.document.cardcopy.CardCopyWorkflowViewModel
import org.synapseworks.pageharbor.document.cardcopy.CardCopyWorkflowState
import org.synapseworks.pageharbor.document.cardcopy.cardCopyFilename
import org.synapseworks.pageharbor.document.cardcopy.cardCopyShareUri
import org.synapseworks.pageharbor.document.cardcopy.deleteStaleCardCopyFiles
import org.synapseworks.pageharbor.document.redaction.deleteStaleSecureRedactedPdfs
import org.synapseworks.pageharbor.document.redaction.deleteStaleRedactedPdfVerifierFiles
import org.synapseworks.pageharbor.document.redaction.SecureRedactedPdfPage
import org.synapseworks.pageharbor.document.redaction.SecureRedactedOcrProvenance
import org.synapseworks.pageharbor.document.redaction.secureRedactionSha256
import org.synapseworks.pageharbor.document.sharesafe.ShareSafeCopyWorkflowState
import org.synapseworks.pageharbor.document.sharesafe.ShareSafeCopyWorkflowViewModel
import org.synapseworks.pageharbor.document.sharesafe.ShareSafeDocumentInput
import org.synapseworks.pageharbor.document.sharesafe.ShareSafeDocumentPage
import org.synapseworks.pageharbor.document.sharesafe.ShareSafeRecipe
import org.synapseworks.pageharbor.document.sharesafe.deleteStaleShareSafeFiles
import org.synapseworks.pageharbor.document.sharesafe.shareSafeFilename
import org.synapseworks.pageharbor.document.searchablepdf.LocalSearchablePdfExportCoordinator
import org.synapseworks.pageharbor.document.searchablepdf.EffectiveOcrPageProvider
import org.synapseworks.pageharbor.document.searchablepdf.EffectiveOcrPageProvision
import org.synapseworks.pageharbor.document.searchablepdf.EffectiveOcrPageRequest
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
import org.synapseworks.pageharbor.document.targetsize.TARGET_SIZE_MAX_STALE_AGE_MILLIS
import org.synapseworks.pageharbor.document.targetsize.TargetSizeBytes
import org.synapseworks.pageharbor.document.targetsize.TargetSizeSourceSummary
import org.synapseworks.pageharbor.document.targetsize.TargetSizeWorkflowInput
import org.synapseworks.pageharbor.document.targetsize.TargetSizeWorkflowViewModel
import org.synapseworks.pageharbor.document.targetsize.TargetSizeWorkflowState
import org.synapseworks.pageharbor.document.targetsize.deleteStaleTargetSizePdfs
import org.synapseworks.pageharbor.document.targetsize.targetSizeFilename
import org.synapseworks.pageharbor.document.targetsize.targetSizePdfRequest
import org.synapseworks.pageharbor.document.targetsize.targetSizeShareUri
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
import org.synapseworks.pageharbor.ocr.MultilingualOcrRuntime
import org.synapseworks.pageharbor.ocr.OcrEngine
import org.synapseworks.pageharbor.ocr.OcrDurablePageCurrentness
import org.synapseworks.pageharbor.ocr.OcrPageAddress
import org.synapseworks.pageharbor.ocr.OcrRecognitionCurrentness
import org.synapseworks.pageharbor.ocr.OcrLocaleScriptMapper
import org.synapseworks.pageharbor.ocr.OcrModelState
import org.synapseworks.pageharbor.ocr.OcrOperationTracker
import org.synapseworks.pageharbor.ocr.OcrOperationSelection
import org.synapseworks.pageharbor.ocr.OcrOperationSelectionResolver
import org.synapseworks.pageharbor.ocr.OcrPage
import org.synapseworks.pageharbor.ocr.OcrPageResult
import org.synapseworks.pageharbor.ocr.OcrResult
import org.synapseworks.pageharbor.ocr.OcrScript
import org.synapseworks.pageharbor.ocr.OcrScriptSelection
import org.synapseworks.pageharbor.ocr.ScriptedOcrEngine
import org.synapseworks.pageharbor.ocr.SharedPreferencesOcrLanguagePreferenceStore
import org.synapseworks.pageharbor.ocr.resolve
import org.synapseworks.pageharbor.ocr.review.OcrReviewRerunScope
import org.synapseworks.pageharbor.ocr.review.OcrReviewViewModel
import org.synapseworks.pageharbor.ocr.batch.OcrBatchViewModel
import org.synapseworks.pageharbor.ocr.OcrUiError
import org.synapseworks.pageharbor.ocr.OcrUiState
import org.synapseworks.pageharbor.ocr.canStartOcr
import org.synapseworks.pageharbor.ocr.clearedOcrState
import org.synapseworks.pageharbor.ocr.ocrStateAfterResult
import org.synapseworks.pageharbor.ocr.persistence.BundledLatinOcrMappingResult
import org.synapseworks.pageharbor.ocr.persistence.BundledLatinOcrResultMapper
import org.synapseworks.pageharbor.ocr.persistence.ScriptedOcrSessionRunner
import org.synapseworks.pageharbor.library.LibraryResult
import org.synapseworks.pageharbor.library.LibraryActionState
import org.synapseworks.pageharbor.library.LibraryOcrCommitResult
import org.synapseworks.pageharbor.library.LibraryOcrPageSnapshot
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
private const val STATE_INBOUND_MIGRATION_FLOW = "inbound_migration_flow"
private const val STATE_INBOUND_MIGRATION_RESTORE_EXISTING_TASK =
    "inbound_migration_restore_existing_task"

class MainActivity : FragmentActivity() {
    private companion object {
        val m7ProcessStartupCleanupClaimed = AtomicBoolean(false)
        val targetSizeProcessStartupCleanupClaimed = AtomicBoolean(false)
    }

    private val session: PageHarborSessionViewModel by viewModels()
    private val library: LibraryViewModel by viewModels()
    private val ocrReview: OcrReviewViewModel by viewModels()
    private val ocrBatch: OcrBatchViewModel by viewModels()
    private val reviewEligibility: ReviewEligibilityViewModel by viewModels()
    private val xlsx: org.synapseworks.pageharbor.xlsx.XlsxViewModel by viewModels()
    private lateinit var xlsxSave: androidx.activity.result.ActivityResultLauncher<String>
    private val pptx: org.synapseworks.pageharbor.pptx.PptxViewModel by viewModels()
    private lateinit var pptxSave: androidx.activity.result.ActivityResultLauncher<String>
    private val integrity: org.synapseworks.pageharbor.integrity.IntegrityViewModel by viewModels()
    private val integrityInputs=mutableMapOf<String,androidx.activity.result.ActivityResultLauncher<Array<String>>>()
    private val integritySaves=mutableMapOf<String,androidx.activity.result.ActivityResultLauncher<String>>()
    private val photo: org.synapseworks.pageharbor.photo.PhotoViewModel by viewModels()
    private val photoSaves=mutableMapOf<String,androidx.activity.result.ActivityResultLauncher<String>>()
    private val assistance: org.synapseworks.pageharbor.assistance.AssistanceViewModel by viewModels()
    private val assistanceSaves=mutableMapOf<String,androidx.activity.result.ActivityResultLauncher<String>>()
    private val translation: org.synapseworks.pageharbor.translation.TranslationViewModel by viewModels()
    private val translationSaves=mutableMapOf<String,androidx.activity.result.ActivityResultLauncher<String>>()
    private val docx: org.synapseworks.pageharbor.docx.DocxViewModel by viewModels()
    private lateinit var docxSave: androidx.activity.result.ActivityResultLauncher<String>
    private val workflows: org.synapseworks.pageharbor.workflow.WorkflowViewModel by viewModels()
    private lateinit var workflowTree: androidx.activity.result.ActivityResultLauncher<Uri?>
    private lateinit var workflowSave: androidx.activity.result.ActivityResultLauncher<String>
    private lateinit var workflowDocxSave: androidx.activity.result.ActivityResultLauncher<String>
    private lateinit var workflowPptxSave: androidx.activity.result.ActivityResultLauncher<String>
    private lateinit var workflowXlsxSave: androidx.activity.result.ActivityResultLauncher<String>
    private val appLock: AppLockViewModel by viewModels()
    private val portability: PortabilityWorkflowViewModel by viewModels()
    private val migration: MigrationWorkflowViewModel by viewModels()
    private val backupReminder: BackupReminderViewModel by viewModels()
    private val protectedActions: ProtectedActionQueueViewModel by viewModels()
    private val targetSizeWorkflow: TargetSizeWorkflowViewModel by viewModels()
    private val cardCopyWorkflow: CardCopyWorkflowViewModel by viewModels()
    private val shareSafeWorkflow: ShareSafeCopyWorkflowViewModel by viewModels()
    private val passwordPdfExport: PasswordPdfExportViewModel by viewModels()
    private val localPdfTools: LocalPdfToolsViewModel by viewModels()
    private var continuousUseJob: Job? = null
    private var continuousUseOwner: String? = null
    private val bookSplit: org.synapseworks.pageharbor.document.booklayout.BookSplitViewModel by viewModels()
    internal val pdfText: org.synapseworks.pageharbor.pdftext.TextViewModel by viewModels()
    private lateinit var textInputLauncher: androidx.activity.result.ActivityResultLauncher<String>
    private lateinit var textSaveLauncher: androidx.activity.result.ActivityResultLauncher<String>
    internal val forms: org.synapseworks.pageharbor.forms.FormViewModel by viewModels()
    private lateinit var formInputLauncher: androidx.activity.result.ActivityResultLauncher<String>
    private lateinit var formSaveLauncher: androidx.activity.result.ActivityResultLauncher<String>
    internal val table: org.synapseworks.pageharbor.table.TableViewModel by viewModels()
    internal val receipt: org.synapseworks.pageharbor.receipt.ReceiptViewModel by viewModels()
    internal val businessCard: org.synapseworks.pageharbor.businesscard.CardViewModel by viewModels()
    private lateinit var cardImageLauncher: androidx.activity.result.ActivityResultLauncher<String>
    private lateinit var tableImageLauncher: androidx.activity.result.ActivityResultLauncher<String>
    private lateinit var tablePermissionLauncher: androidx.activity.result.ActivityResultLauncher<String>
    private lateinit var tableDestinationLauncher: androidx.activity.result.ActivityResultLauncher<String>
    private lateinit var receiptImageLauncher: androidx.activity.result.ActivityResultLauncher<String>
    private lateinit var cardPermissionLauncher: androidx.activity.result.ActivityResultLauncher<String>
    private lateinit var receiptPermissionLauncher: androidx.activity.result.ActivityResultLauncher<String>
    private lateinit var cardEditorLauncher: androidx.activity.result.ActivityResultLauncher<Intent>
    private lateinit var qrImageLauncher: androidx.activity.result.ActivityResultLauncher<String>
    private lateinit var qrPermissionLauncher: androidx.activity.result.ActivityResultLauncher<String>
    internal val qr: org.synapseworks.pageharbor.qr.QrViewModel by viewModels()
    internal val recycle: org.synapseworks.pageharbor.library.recycle.RecycleViewModel by viewModels()
    internal val organization: org.synapseworks.pageharbor.library.organization.LibraryOrganizationViewModel by viewModels()
    internal val collage: org.synapseworks.pageharbor.document.collage.CollageViewModel by viewModels()
    private val curvature: org.synapseworks.pageharbor.document.curvature.CurvatureViewModel by viewModels()
    private val importCrop: ImportCropViewModel by viewModels()
    internal val objectRemoval: org.synapseworks.pageharbor.document.objectremoval.ObjectRemovalViewModel by viewModels()
    internal val annotationEditor: org.synapseworks.pageharbor.document.annotation.AnnotationEditorViewModel by viewModels()
    private val continuousCapture: org.synapseworks.pageharbor.document.capture.ContinuousCaptureViewModel by viewModels()
    internal var continuousBackendForTest: ((java.io.File,(Boolean)->Unit)->Unit)? by mutableStateOf(null)
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
    private var inboundMigrationFlow by mutableStateOf(false)
    private var inboundMigrationRestoreExistingTask = false
    private var inboundIntentArrivedViaNewIntent = false
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
    private val multilingualOcrRuntime by lazy { MultilingualOcrRuntime.get(applicationContext) }
    private val ocrLanguagePreferences by lazy {
        SharedPreferencesOcrLanguagePreferenceStore(applicationContext)
    }
    private var ocrDefaultSelection by mutableStateOf<OcrScriptSelection>(
        OcrScriptSelection.Automatic,
    )
    private var ocrModelStates by mutableStateOf<Map<OcrScript, OcrModelState>>(emptyMap())
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
    internal var sourceStartupCleanupForTest: Job? = null; private set
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
    private var inboundPhotoJob: Job? = null
    private val privateShareAuthorities: Set<String> by lazy {
        @Suppress("DEPRECATION")
        val providers = packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.GET_PROVIDERS).providers
        providers.orEmpty().flatMap { it.authority.orEmpty().split(';') }.toSet()
    }
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

    private val createTargetSizePdfDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
    ) { destinationUri ->
        runWhenUnlocked(
            AppLockProtectedEntryPoint.ACTIVITY_RESULT_CALLBACK,
            PendingProtectedAction.TargetSizePdfDestination(destinationUri),
        )
    }

    private val cardCopyScanLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        runWhenUnlocked(
            AppLockProtectedEntryPoint.ACTIVITY_RESULT_CALLBACK,
            PendingProtectedAction.CardCopyScannerResult(
                result.resultCode,
                result.data?.let(::Intent),
            ),
        )
    }

    private val cardCopyImageLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        runWhenUnlocked(
            AppLockProtectedEntryPoint.ACTIVITY_RESULT_CALLBACK,
            PendingProtectedAction.CardCopyImageResult(uri),
        )
    }

    private val createCardCopyPdfLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri ->
        runWhenUnlocked(
            AppLockProtectedEntryPoint.ACTIVITY_RESULT_CALLBACK,
            PendingProtectedAction.CardCopyPdfDestination(uri),
        )
    }

    private val createShareSafePdfLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri ->
        runWhenUnlocked(
            AppLockProtectedEntryPoint.ACTIVITY_RESULT_CALLBACK,
            PendingProtectedAction.ShareSafePdfDestination(uri),
        )
    }

    private val createLocalToolsPdfLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri ->
        val requestId = protectedActions.consumeLocalToolsSavePickerRequest()
            ?: return@registerForActivityResult
        runWhenUnlocked(
            AppLockProtectedEntryPoint.ACTIVITY_RESULT_CALLBACK,
            PendingProtectedAction.LocalToolsPdfDestination(uri, requestId),
        )
    }

    private val createPasswordPdfLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri ->
        val requestId = protectedActions.consumePasswordPdfSavePickerRequest()
            ?: return@registerForActivityResult
        runWhenUnlocked(
            AppLockProtectedEntryPoint.ACTIVITY_RESULT_CALLBACK,
            PendingProtectedAction.PasswordPdfDestination(uri, requestId),
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

    // M35: append the registration after existing launchers; no saved-state draft.
    private var officeRecipeTicket: org.synapseworks.pageharbor.workflow.OfficeRecipeTicket? = null
    private val officeRecipeLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val ticket = officeRecipeTicket; officeRecipeTicket = null
        org.synapseworks.pageharbor.office.OfficeRecipeOffers.endRequest(ticket?.request)
        val id = result.data?.getStringExtra(org.synapseworks.pageharbor.office.OfficeRecipeOffers.RESULT)
        if (ticket == null || result.resultCode != android.app.Activity.RESULT_OK || id == null ||
            result.data?.getStringExtra(org.synapseworks.pageharbor.office.OfficeRecipeOffers.REQUEST) != ticket.request) {
            org.synapseworks.pageharbor.office.OfficeRecipeOffers.discard(id)
        } else lifecycleScope.launch {
            try {
                kotlinx.coroutines.withTimeout(10000) {
                    while (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) kotlinx.coroutines.delay(16)
                }
                val current = { lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) && appLock.state.value.phase != AppLockPhase.LOCKED }
                workflows.foreground(current())
                workflows.acceptOffice(ticket, id, current)
            } catch (_: kotlinx.coroutines.CancellationException) { } finally { org.synapseworks.pageharbor.office.OfficeRecipeOffers.discard(id) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        updateProtectedWindow(appLock.state.value.config.enabled)
        // Activity-owned registration survives QR composition removal by app lock. A new
        // ViewModel after process death gets different keys, so old results cannot target it.
        docxSave=activityResultRegistry.register("docx-save:"+docx.activityOwner,this,ActivityResultContracts.CreateDocument(org.synapseworks.pageharbor.docx.DOCX_MIME)){uri->docx.foreground(appLock.state.value.phase!=AppLockPhase.LOCKED);if(appLock.state.value.phase==AppLockPhase.LOCKED){docx.close();docx.destination(uri)}else docx.destination(uri)}
        pptxSave=activityResultRegistry.register("pptx-save:"+pptx.activityOwner,this,ActivityResultContracts.CreateDocument(org.synapseworks.pageharbor.pptx.PPTX_MIME)){uri->pptx.foreground(appLock.state.value.phase!=AppLockPhase.LOCKED);if(appLock.state.value.phase==AppLockPhase.LOCKED)pptx.close();pptx.destination(uri)}
        xlsxSave=activityResultRegistry.register("xlsx-save:"+xlsx.activityOwner,this,ActivityResultContracts.CreateDocument(org.synapseworks.pageharbor.xlsx.XLSX_MIME)){uri->xlsx.foreground(appLock.state.value.phase!=AppLockPhase.LOCKED);if(appLock.state.value.phase==AppLockPhase.LOCKED)xlsx.close();xlsx.destination(uri)}
        workflowTree=activityResultRegistry.register("workflow-tree:"+workflows.activityOwner,this,ActivityResultContracts.OpenDocumentTree(),workflows::treeReturned)
        workflowSave=activityResultRegistry.register("workflow-save:"+workflows.activityOwner,this,ActivityResultContracts.CreateDocument("application/pdf"),workflows::manualResult)
        workflowDocxSave=activityResultRegistry.register("workflow-docx-save:"+workflows.activityOwner,this,ActivityResultContracts.CreateDocument(org.synapseworks.pageharbor.docx.DOCX_MIME),workflows::manualResult)
        workflowPptxSave=activityResultRegistry.register("workflow-pptx-save:"+workflows.activityOwner,this,ActivityResultContracts.CreateDocument(org.synapseworks.pageharbor.pptx.PPTX_MIME),workflows::manualResult)
        workflowXlsxSave=activityResultRegistry.register("workflow-xlsx-save:"+workflows.activityOwner,this,ActivityResultContracts.CreateDocument(org.synapseworks.pageharbor.xlsx.XLSX_MIME),workflows::manualResult)
        textInputLauncher=activityResultRegistry.register("text-input:"+pdfText.activityResultOwner,this,ActivityResultContracts.GetContent(),pdfText::picked)
        textSaveLauncher=activityResultRegistry.register("text-pdf:"+pdfText.activityResultOwner,this,ActivityResultContracts.CreateDocument("application/pdf")){uri->val gate=appLock;pdfText.destination(uri){gate.state.value.phase!=AppLockPhase.LOCKED}}
        formInputLauncher=activityResultRegistry.register("form-input:"+forms.activityResultOwner,this,ActivityResultContracts.GetContent(),forms::picked)
        formSaveLauncher=activityResultRegistry.register("form-pdf:"+forms.activityResultOwner,this,ActivityResultContracts.CreateDocument("application/pdf")){uri->val gate=appLock;forms.destination(uri){gate.state.value.phase!=AppLockPhase.LOCKED}}
        tableImageLauncher=activityResultRegistry.register("table-image:"+table.activityResultOwner,this,ActivityResultContracts.GetContent(),table::picked)
        tablePermissionLauncher=activityResultRegistry.register("table-camera:"+table.activityResultOwner,this,ActivityResultContracts.RequestPermission()){table.permissionResult(org.synapseworks.pageharbor.document.capture.CameraAccess.allowed(this))}
        tableDestinationLauncher=activityResultRegistry.register("table-csv:"+table.activityResultOwner,this,ActivityResultContracts.CreateDocument("text/csv")){uri->val gate=appLock;table.destination(uri){gate.state.value.phase!=AppLockPhase.LOCKED}}
        receiptImageLauncher=activityResultRegistry.register("receipt-image:"+receipt.activityResultOwner,this,ActivityResultContracts.GetContent(),receipt::picked)
        receiptPermissionLauncher=activityResultRegistry.register("receipt-camera:"+receipt.activityResultOwner,this,ActivityResultContracts.RequestPermission()){ receipt.permissionResult(org.synapseworks.pageharbor.document.capture.CameraAccess.allowed(this)) }
        cardImageLauncher=activityResultRegistry.register("card-image:"+businessCard.activityResultOwner,this,ActivityResultContracts.GetContent(),businessCard::picked)
        cardPermissionLauncher=activityResultRegistry.register("card-camera:"+businessCard.activityResultOwner,this,ActivityResultContracts.RequestPermission()){
            businessCard.permissionResult(org.synapseworks.pageharbor.document.capture.CameraAccess.allowed(this))
        }
        cardEditorLauncher=activityResultRegistry.register("card-editor:"+businessCard.activityResultOwner,this,ActivityResultContracts.StartActivityForResult()){
            businessCard.editorReturned()
        }
        qrImageLauncher=activityResultRegistry.register("qr-image:"+qr.activityResultOwner,this,ActivityResultContracts.GetContent(),qr::receivePickedImage)
        qrPermissionLauncher=activityResultRegistry.register("qr-camera:"+qr.activityResultOwner,this,ActivityResultContracts.RequestPermission()){
            qr.permissionResult(org.synapseworks.pageharbor.document.capture.CameraAccess.allowed(this))
        }
        session.configureHistory(org.synapseworks.pageharbor.document.history.AndroidHistorySourceAccess(applicationContext))
        ocrDefaultSelection = ocrLanguagePreferences.read()
        lifecycleScope.launch {
            multilingualOcrRuntime.modelInstaller.states.collect { states ->
                ocrModelStates = states
            }
        }
        restoreActivityUiState(savedInstanceState)
        reviewEligibility.recordAppSession()
        enableEdgeToEdge()
        var observedDocumentRevision = session.documentRevision
        val m7CleanupCutoffMillis = System.currentTimeMillis()
        val deleteM7ProcessOrphans = m7ProcessStartupCleanupClaimed.compareAndSet(false, true)
        val targetSizeCleanupCutoffMillis = System.currentTimeMillis()
        val deleteTargetSizeProcessOrphans =
            targetSizeProcessStartupCleanupClaimed.compareAndSet(false, true)
        lifecycleScope.launch {
            session.documentRevisionChanges.collect { revision ->
                if (revision == observedDocumentRevision) return@collect
                observedDocumentRevision = revision
                clearStaleOcrAndSearchablePdfOperations(revision)
                clearStaleNormalDocumentOperations(revision)
                if(continuousCapture.state?.revision?.let{it!=revision}==true)cancelContinuousCapture()
                bookSplit.onDocumentRevisionChanged(revision)
                curvature.onDocumentRevisionChanged(revision)
                importCrop.onDocumentRevisionChanged(revision)
                objectRemoval.onDocumentRevisionChanged(revision)
                collage.onDocumentRevisionChanged(revision)
            }
        }
        sourceStartupCleanupForTest = lifecycleScope.launch(Dispatchers.IO) {
            deleteStaleSharedPdfs(cacheDir)
            deleteStaleSearchablePdfs(cacheDir)
            deleteStaleNormalPdfs(cacheDir)
            deleteStaleTargetSizePdfs(
                cacheDirectory = cacheDir,
                nowMillis = targetSizeCleanupCutoffMillis,
                minimumAgeMillis = if (deleteTargetSizeProcessOrphans) {
                    0L
                } else {
                    TARGET_SIZE_MAX_STALE_AGE_MILLIS
                },
            )
            if (deleteM7ProcessOrphans) {
                deleteStaleCardCopyFiles(cacheDir, m7CleanupCutoffMillis, minimumAgeMillis = 0L)
                deleteStaleSecureRedactedPdfs(
                    cacheDir,
                    m7CleanupCutoffMillis,
                    minimumAgeMillis = 0L,
                )
                deleteStaleRedactedPdfVerifierFiles(
                    cacheDir,
                    m7CleanupCutoffMillis,
                    minimumAgeMillis = 0L,
                )
                deleteStaleShareSafeFiles(cacheDir, m7CleanupCutoffMillis, minimumAgeMillis = 0L)
            } else {
                deleteStaleCardCopyFiles(cacheDir)
                deleteStaleSecureRedactedPdfs(cacheDir)
                deleteStaleRedactedPdfVerifierFiles(cacheDir)
                deleteStaleShareSafeFiles(cacheDir)
            }
            if (session.documentPages.isEmpty() && !session.hasActiveDocumentSessionLeases()) {
                deleteStaleDocumentImports(cacheDir)
            }
        }
        lifecycleScope.launch {
            appLock.state.collect { lockState ->
                updateProtectedWindow(lockState.config.enabled)
                workflows.foreground(lockState.phase!=AppLockPhase.LOCKED && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))
                if(lockState.phase==AppLockPhase.LOCKED){integrity.close();photo.close();assistance.close();translation.close();pptx.close();xlsx.close();docx.close();workflows.background(true);pdfText.lock();forms.lock();table.lock();receipt.lock();businessCard.lock();qr.lock();continuousCapture.pause();bookSplit.close();curvature.close();importCrop.close();objectRemoval.close();collage.close();annotationEditor.cancel()}
                session.setHistoryActive(lockState.phase!=AppLockPhase.LOCKED&&lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))
                if(lockState.phase==AppLockPhase.LOCKED){organization.setActive(false);recycle.setActive(false)}
                else if(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)){organization.setActive(true);recycle.setActive(true);qr.setActive(true);businessCard.setActive(true);receipt.setActive(true);table.setActive(true);forms.setActive(true);pdfText.setActive(true)}
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
        setPageHarborContent {
            val libraryUiState by library.uiState.collectAsState()
            val recycleUiState by recycle.state.collectAsState()
            val organizationUiState by organization.uiState.collectAsState()
            val ocrReviewState by ocrReview.state.collectAsState()
            val ocrBatchState by ocrBatch.state.collectAsState()
            val appLockState by appLock.state.collectAsState()
            val portabilityEngineState by portability.state.collectAsState()
            val migrationEngineState by migration.state.collectAsState()
            val backupReminderPresentation by backupReminder.presentation.collectAsState()
            val reminderLibrarySnapshot by backupReminder.librarySnapshot.collectAsState()
            val portabilityUiState = buildPortabilityUiState(
                engineState = portabilityEngineState,
                migrationState = migrationEngineState,
                reminder = backupReminderPresentation,
                reminderLibrarySnapshot = reminderLibrarySnapshot,
            )
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
            } else if(pdfText.state!=null) {
                org.synapseworks.pageharbor.pdftext.TextScreen(pdfText,
                    choose={owner,epoch->if(pdfText.beginInput(owner,epoch))try{textInputLauncher.launch("application/pdf")}catch(_:Exception){pdfText.picked(null)}},
                    save={owner,epoch,review->if(pdfText.beginSave(owner,epoch,review))try{textSaveLauncher.launch("edited-text.pdf")}catch(_:Exception){pdfText.pickerFailed()}})
            } else if(forms.state!=null) {
                org.synapseworks.pageharbor.forms.FormScreen(forms,
                    choose={owner,epoch->if(forms.beginInput(owner,epoch))try{formInputLauncher.launch("application/pdf")}catch(_:Exception){forms.picked(null)}},
                    save={owner,epoch,review->if(forms.beginSave(owner,epoch,review))try{formSaveLauncher.launch("filled-form.pdf")}catch(_:Exception){forms.pickerFailed()}})
            } else if(xlsx.visible) {
                org.synapseworks.pageharbor.xlsx.XlsxScreen(xlsx,
                    save={xlsx.beginSave()?.let{name->try{xlsxSave.launch(name)}catch(_:Exception){xlsx.destination(null)}}},
                    share={xlsx.share{startActivity(it)}},
                    addTable={xlsx.collect{receipt.close();table.close();table.setActive(true);table.open()}},
                    addReceipt={xlsx.collect{table.close();receipt.close();receipt.setActive(true);receipt.open()}},
                    queue=if(workflows.draft?.preset==org.synapseworks.pageharbor.workflow.WorkflowPreset.XLSX)({val p=xlsx.prepared;if(p!=null)workflows.acceptXlsx(p,{xlsx.visible&&xlsx.prepared===p&&appLock.state.value.phase!=AppLockPhase.LOCKED}){xlsx.close();table.close();receipt.close()}})else null)
            } else if(table.state!=null) {
                org.synapseworks.pageharbor.ui.home.TableScreen(table,
                    chooseImage={owner,epoch->if(table.beginPicker(owner,epoch))try{tableImageLauncher.launch("image/*")}catch(_:Exception){table.picked(null)}},
                    allowCamera={owner,epoch->if(table.beginPermission(owner,epoch))try{tablePermissionLauncher.launch(android.Manifest.permission.CAMERA)}catch(_:Exception){table.permissionResult(false)}},
                    save={owner,epoch,review->if(table.beginSave(owner,epoch,review))try{tableDestinationLauncher.launch("table.csv")}catch(_:Exception){table.pickerFailed()}},
                    workbook={xlsx.foreground(true);table.state?.let{xlsx.table(it,table.workbookInput)}},
                    dismiss={table.close();xlsx.resume()})
            } else if(receipt.state!=null) {
                org.synapseworks.pageharbor.ui.home.ReceiptScreen(receipt,
                    chooseImage={owner,epoch->if(receipt.beginPicker(owner,epoch))try { receiptImageLauncher.launch("image/*") } catch(_: Exception) { receipt.picked(null) }},
                    allowCamera={owner,epoch->if(receipt.beginPermission(owner,epoch))try { receiptPermissionLauncher.launch(android.Manifest.permission.CAMERA) } catch(_: Exception) { receipt.permissionResult(false) }},
                    workbook={xlsx.foreground(true);receipt.state?.let{xlsx.receipt(it,receipt.workbookInput)}},
                    dismiss={receipt.close();xlsx.resume()})
            } else if(businessCard.state!=null) {
                org.synapseworks.pageharbor.ui.home.BusinessCardScreen(businessCard,
                    chooseImage={owner,epoch->if(businessCard.beginPicker(owner,epoch))cardImageLauncher.launch("image/*")},
                    allowCamera={owner,epoch->if(businessCard.beginPermission(owner,epoch))cardPermissionLauncher.launch(android.Manifest.permission.CAMERA)},
                    openEditor={owner,epoch->businessCard.handoff(owner,epoch){cardEditorLauncher.launch(it)}})
            } else if(qr.state!=null) {
                org.synapseworks.pageharbor.ui.home.QrScreen(qr,
                    onChooseImage={owner,epoch->if(qr.beginPicker(owner,epoch))qrImageLauncher.launch("image/*")},
                    onAllowCamera={owner,epoch->if(qr.beginPermission(owner,epoch))qrPermissionLauncher.launch(android.Manifest.permission.CAMERA)})
            } else if(pptx.visible) {
                org.synapseworks.pageharbor.pptx.PptxScreen(pptx,
                    save={pptx.beginSave()?.let{name->try{pptxSave.launch(name)}catch(_:Exception){pptx.destination(null)}}},
                    share={pptx.share{startActivity(it)}},
                    queue=if(workflows.draft?.preset==org.synapseworks.pageharbor.workflow.WorkflowPreset.PPTX)({val p=pptx.prepared;if(p!=null)workflows.acceptPptx(p,{pptx.visible&&pptx.prepared===p&&appLock.state.value.phase!=AppLockPhase.LOCKED}){pptx.close()}})else null)
            } else if(integrity.visible) {
                androidx.compose.runtime.LaunchedEffect(session.documentRevision,session.documentOwnerToken){integrity.changed()}
                org.synapseworks.pageharbor.integrity.IntegrityScreen(integrity,pick=::launchIntegrityInput,save=::launchIntegritySave,share={integrity.share{startActivity(it)}})
            } else if(photo.visible) {
                androidx.compose.runtime.LaunchedEffect(session.documentRevision,session.documentOwnerToken){photo.changed()}
                org.synapseworks.pageharbor.photo.PhotoScreen(photo,save=::launchPhotoSave,share={photo.share{startActivity(it)}})
            } else if(assistance.visible) {
                androidx.compose.runtime.LaunchedEffect(session.documentRevision,session.documentOwnerToken){assistance.changed()}
                org.synapseworks.pageharbor.assistance.AssistanceScreen(assistance,save=::launchAssistanceSave,share={pdf->assistance.share(pdf){startActivity(it)}},ocr={assistance.close();recognizeText()})
            } else if(translation.visible) {
                androidx.compose.runtime.LaunchedEffect(session.documentRevision,session.documentOwnerToken){translation.changed()}
                org.synapseworks.pageharbor.translation.TranslationScreen(translation,save={launchTranslationSave(false)},share={translation.share{startActivity(it)}},pdfSave={launchTranslationSave(true)},pdfShare={translation.sharePdf{startActivity(it)}},ocr={translation.close();recognizeText()})
            } else if(docx.visible) {
                org.synapseworks.pageharbor.docx.DocxScreen(docx,
                    save={docx.beginSave()?.let{name->try{docxSave.launch(name)}catch(_:Exception){docx.destination(null)}}},
                    share={docx.share{startActivity(it)}},
                    queue={val p=docx.prepared;val c=docx.capture;if(p!=null&&c!=null)workflows.acceptDocx(p,c,current={docx.visible&&docx.prepared===p&&docx.capture===c&&appLock.state.value.phase!=AppLockPhase.LOCKED}){docx.close()}})
            } else if(workflows.visible) {
                org.synapseworks.pageharbor.workflow.WorkflowScreen(workflows,canCapture=session.documentPages.isNotEmpty()||workflows.draft?.preset==org.synapseworks.pageharbor.workflow.WorkflowPreset.XLSX,
                    onCapture=::captureWorkflowDocument,
                    onSuggest={val document=session.documentSession;val revision=session.documentRevision;lifecycleScope.launch { val ocr=workflowOcr(document);if(workflows.visible && session.documentSession===document && session.documentRevision==revision)workflows.titleSuggestion(ocr,revision) }},
                    onTree={if(workflows.beginTree())try{workflowTree.launch(android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents","primary:Download"))}catch(_:Exception){workflows.treeReturned(null)}},
                    onManual={id->workflows.manual(id)?.let { name->try{if(name.endsWith(".docx"))workflowDocxSave.launch(name)else if(name.endsWith(".xlsx"))workflowXlsxSave.launch(name)else if(name.endsWith(".pptx"))workflowPptxSave.launch(name)else workflowSave.launch(name)}catch(_:Exception){workflows.manualResult(null)} }})
            } else if(recycleUiState.open) {
                org.synapseworks.pageharbor.ui.home.RecycleScreen(recycle)
            } else if(organizationUiState.request.open) {
                org.synapseworks.pageharbor.ui.home.LibraryOrganizationScreen(organization,onOpen=::openLibraryDocument)
            } else if(collage.state!=null) {
                org.synapseworks.pageharbor.ui.home.CollageScreen(collage,onApply=::applyCollage)
            } else if(objectRemoval.state!=null) {
                org.synapseworks.pageharbor.ui.home.ObjectRemovalScreen(objectRemoval,onApply=::applyObjectRemoval)
            } else if(importCrop.state!=null) {
                org.synapseworks.pageharbor.ui.home.ImportCropScreen(importCrop,onApply=::applyImportCrop)
            } else if(curvature.state!=null) {
                org.synapseworks.pageharbor.ui.home.CurvatureScreen(curvature,onApply=::applyCurvature)
            } else if(bookSplit.state!=null) {
                org.synapseworks.pageharbor.ui.home.BookSplitScreen(bookSplit,onApply=::applyBookSplit)
            } else if(continuousCapture.state!=null) {
                org.synapseworks.pageharbor.ui.home.ContinuousCaptureScreen(continuousCapture,
                    onCancel=::cancelContinuousCapture,onUse=::useContinuousPages,
                    onCardCopy=::openCardCopyFromCamera,backendOverride=continuousBackendForTest)
            } else {
                LaunchedEffect(portabilityUiState) {
                    if (portabilityUiState is UiPortabilityWorkflowState.BackupReminder) {
                        backupReminder.markPresented()
                    }
                }
                LaunchedEffect(migrationEngineState.operation, inboundMigrationFlow) {
                    if (migrationEngineState.operation is MigrationOperationStatus.Cancelled) {
                        if (inboundMigrationFlow) {
                            finishOrRestoreInboundMigration()
                        }
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
                    openedPageRequestId = session.openedPageRequestId,
                    openedPagePersistentId = session.openedPagePersistentId,
                    importUiState = session.importUiState,
                    libraryUiState = libraryUiState,
                    libraryHasDocuments = reminderLibrarySnapshot?.documentCount?.let { it > 0 }
                        ?: libraryUiState.recentDocuments.isNotEmpty(),
                    libraryThumbnailUri = library::thumbnailUri,
                    onOpenQrScanner = ::openQrScanner,
                    onOpenBusinessCard = ::openBusinessCard,
                    onOpenReceipt = ::openReceipt,
                    onOpenTable = ::openTable,
                    onOpenForms = ::openForms,
                    onOpenText = ::openPdfText,
                    onOpenPptx = ::openPptx,
                    onOpenIntegrity = ::openIntegrity,
                    onOpenPhoto = ::openPhoto,
                    onOpenAssistance = ::openAssistance,
                    onOpenTranslation = ::openTranslation,
                    onOpenDocx = ::openDocx,
                    onOpenWorkflows = { if(appLock.state.value.phase!=AppLockPhase.LOCKED)workflows.open() },
                    onOpenRecycleBin = { if(appLock.state.value.phase!=AppLockPhase.LOCKED)recycle.open() },
                    onOpenLibraryOrganization = { openLibraryOrganization(libraryUiState.selectedFolderId,libraryUiState.query,libraryUiState.sortOrder) },
                    onLibraryQueryChange = library::updateQuery,
                    onLibraryFolderSelected = library::selectFolder,
                    onLibrarySortOrderChange = library::updateSortOrder,
                    onOpenLibraryDocument = ::openLibraryDocument,
                    onLoadMoreLibrarySearchResults = library::loadMoreSearchResults,
                    onRequestSmartName = library::requestSmartName,
                    onRenameLibraryDocument = ::renameLibraryDocument,
                    onMoveLibraryDocument = ::moveLibraryDocument,
                    onDeleteLibraryDocument = ::deleteLibraryDocument,
                    onMergeLibraryDocuments = library::mergeDocuments,
                    ocrBatchState = ocrBatchState,
                    onOpenOcrBatch = ocrBatch::openSetup,
                    onOcrBatchModeChange = ocrBatch::setMode,
                    onStartOcrBatch = { selection, useAsDefault ->
                        val requested = when (selection) {
                            OcrOperationSelection.UseDefault -> ocrDefaultSelection
                            is OcrOperationSelection.Override ->
                                OcrScriptSelection.Explicit(selection.script)
                        }
                        val recommendation = currentOcrRecommendation()
                        ocrBatch.start(
                            requestedSelection = requested,
                            resolvedScript = resolveOcrOperation(selection, useAsDefault),
                            localeRecommendation = recommendation.script,
                        )
                    },
                    onCancelOcrBatch = ocrBatch::cancel,
                    onRetryOcrBatch = ocrBatch::retryFailed,
                    onDismissOcrBatch = ocrBatch::dismiss,
                    onCreateLibraryFolder = library::createFolder,
                    onRenameLibraryFolder = library::renameFolder,
                    onDeleteLibraryFolder = ::deleteLibraryFolder,
                    onConsumeLibraryAction = library::consumeActionState,
                    onOcrSelectedPageChange = { ocrSelectedPageIndex = it },
                    onPageFilterChange = session::setPageFilter,
                    onPageAdjustmentsChange = { page, settings -> session.setPageAdjustments(page.id.value, settings, page) },
                    batchEditor = session.batchEditor,
                    documentRevision = session.documentRevision,
                    documentOwnerToken = session.documentOwnerToken,
                    onReviewedShadowGlare = { page, filter, revision -> session.applyReviewedShadowGlare(page,revision,filter) },
                    onReviewedWhitening = { page, filter, revision -> session.applyReviewedWhitening(page,revision,filter) },
                    onReviewedBleedThrough = { page, filter, revision, owner -> session.applyReviewedBleedThrough(page,revision,filter,owner) },
                    onReviewedCreaseStain = { page, filter, revision, owner -> session.applyReviewedCreaseStain(page,revision,filter,owner) },
                    onReviewedMoire = { page, filter, revision, owner -> session.applyReviewedMoire(page,revision,filter,owner) },
                    onBookSplit = { openBookSplit(it) },
                    onCurvature = ::openCurvature,
                    onImportCrop = { index -> openImportCrop(index, automatic = false) },
                    onObjectRemoval = ::openObjectRemoval,
                    onFingerRemoval = ::openFingerRemoval,
                    onCollage = ::openCollage,
                    annotationEditor = annotationEditor,
                    onOpenAnnotations = ::openAnnotations,
                    onAnnotationsApply = { page,revision,owner,layer -> appLock.state.value.phase!=AppLockPhase.LOCKED && session.applyAnnotations(page,revision,owner,layer) },
                    historyState = session.historyState,
                    onDocumentUndo = { navigateDocumentHistory(true) },
                    onDocumentRedo = { navigateDocumentHistory(false) },
                    onPageRotate = session::rotatePageClockwise,
                    onPageMove = session::movePage,
                    onPageRemove = session::removePage,
                    onSaveToLibrary = ::saveCurrentDocumentToLibrary,
                    onExtractLibraryPages = ::extractLibraryPages,
                    searchablePdfSaveState = searchablePdfSaveState,
                    onScanDocument = ::launchDocumentScanner,
                    onManualScan = { openContinuousCapture(singlePage = true) },
                    onImportFiles = ::launchFileImport,
                    onCancelImport = ::cancelImport,
                    onSavePdf = ::choosePdfDestination,
                    onSaveSearchablePdf = ::saveSearchablePdf,
                    onSaveSearchablePdfWithLanguage = { selection, useAsDefault ->
                        saveSearchablePdf(resolveOcrOperation(selection, useAsDefault))
                    },
                    onSharePdf = ::sharePdf,
                    targetSizeWorkflowState = targetSizeWorkflow.state,
                    onOpenFitUploadLimit = ::openFitUploadLimit,
                    onFitLimitPresetSelected = targetSizeWorkflow::selectPreset,
                    onFitLimitCustomSelected = { targetSizeWorkflow.selectCustom() },
                    onFitLimitCustomTextChanged = { value ->
                        targetSizeWorkflow.updateCustomText(value)
                    },
                    onFitLimitCustomUnitChanged = { unit ->
                        targetSizeWorkflow.updateCustomUnit(unit)
                    },
                    onFitLimitPrepare = targetSizeWorkflow::prepare,
                    onFitLimitCancel = targetSizeWorkflow::cancelPreparation,
                    onFitLimitUseAlternative = targetSizeWorkflow::acceptSmallestRecommended,
                    onFitLimitChange = targetSizeWorkflow::changeLimit,
                    onFitLimitSave = ::chooseTargetSizeDestination,
                    onFitLimitShare = ::shareTargetSizePdf,
                    onFitLimitRetry = targetSizeWorkflow::retry,
                    onFitLimitClose = targetSizeWorkflow::close,
                    cardCopyWorkflowState = cardCopyWorkflow.state,
                    onOpenCardCopy = cardCopyWorkflow::open,
                    onCardCopyScan = cardCopyWorkflow::retake,
                    onCardCopyCapture = cardCopyWorkflow::captureSide,
                    onCancelCardCamera = cardCopyWorkflow::cancelCameraCapture,
                    onCardCopyImport = { side -> launchCardCopyImport(side) },
                    onCardCopyContinueReview = cardCopyWorkflow::continueToReview,
                    onCardCopyRotate = cardCopyWorkflow::rotate,
                    onCardCopyRemove = cardCopyWorkflow::remove,
                    onCardCopySwap = cardCopyWorkflow::swap,
                    onCardCopyContinueLayout = cardCopyWorkflow::continueToLayout,
                    onCardCopyPageSize = cardCopyWorkflow::updatePageSize,
                    onCardCopyLayout = cardCopyWorkflow::updateLayout,
                    onCardCopySpacing = cardCopyWorkflow::updateSpacing,
                    onCardCopyAlignment = cardCopyWorkflow::updateAlignment,
                    onCardCopyShowPreview = cardCopyWorkflow::showPreview,
                    onCardCopyPrepare = cardCopyWorkflow::prepare,
                    onCardCopySave = ::chooseCardCopyDestination,
                    onCardCopyShare = ::shareCardCopy,
                    onCardCopyRetry = cardCopyWorkflow::retryPreparation,
                    onCardCopyBack = ::backFromCardCopy,
                    onCardCopyOpenRedaction = { side ->
                        cardCopyWorkflow.openRedactionEditor(side)
                    },
                    onCardCopyAddRedaction = cardCopyWorkflow::addRedactionFromDisplay,
                    onCardCopySelectRedaction = cardCopyWorkflow::selectRedaction,
                    onCardCopyUpdateRedaction = cardCopyWorkflow::updateRedactionFromDisplay,
                    onCardCopyDeleteRedaction = cardCopyWorkflow::deleteSelectedRedaction,
                    onCardCopyUndoRedaction = cardCopyWorkflow::undoRedactionEdit,
                    onCardCopyRedoRedaction = cardCopyWorkflow::redoRedactionEdit,
                    onCardCopyApplyRedactions = cardCopyWorkflow::applyRedactions,
                    shareSafeWorkflowState = shareSafeWorkflow.state,
                    onOpenShareSafeCopy = ::openShareSafeCopy,
                    onPasswordPdfExport = ::openPasswordPdfExport,
                    onLocalPdfTools = ::openLocalPdfTools,
                    onShareSafeRecipe = { recipe ->
                        shareSafeWorkflow.selectRecipe(recipe)
                        if (recipe == ShareSafeRecipe.SHARE_ID_COPY) {
                            cardCopyWorkflow.openForShareSafe()
                        }
                    },
                    onShareSafePage = shareSafeWorkflow::selectPage,
                    onShareSafeAddRedaction = shareSafeWorkflow::addRedaction,
                    onShareSafeSelectRedaction = shareSafeWorkflow::selectRedaction,
                    onShareSafeUpdateRedaction = shareSafeWorkflow::updateSelectedRedaction,
                    onShareSafeDeleteRedaction = shareSafeWorkflow::deleteSelectedRedaction,
                    onShareSafeWatermarkEnabled = shareSafeWorkflow::setWatermarkEnabled,
                    onShareSafePurpose = shareSafeWorkflow::updatePurpose,
                    onShareSafeDateEnabled = { enabled ->
                        shareSafeWorkflow.setDateEnabled(enabled)
                    },
                    onShareSafeDate = shareSafeWorkflow::updateDate,
                    onShareSafePlacement = shareSafeWorkflow::updatePlacement,
                    onShareSafeOpacity = shareSafeWorkflow::updateOpacity,
                    onShareSafeTargetEnabled = shareSafeWorkflow::setTargetSizeEnabled,
                    onShareSafeTargetBytes = shareSafeWorkflow::updateTargetBytes,
                    onShareSafeNext = shareSafeWorkflow::nextStep,
                    onShareSafePrepare = shareSafeWorkflow::prepare,
                    onShareSafeSave = ::chooseShareSafeDestination,
                    onShareSafeShare = ::shareShareSafeCopy,
                    onShareSafeRetry = shareSafeWorkflow::retry,
                    onShareSafeBack = shareSafeWorkflow::back,
                    onCardCopyContinueShareSafe = ::continueCardCopyToShareSafe,
                    onExportPages = ::exportPages,
                    onRecognizeText = ::recognizeText,
                    onRecognizeTextWithLanguage = { selection, useAsDefault ->
                        recognizeText(resolveOcrOperation(selection, useAsDefault))
                    },
                    onClearRecognizedText = ::clearRecognizedText,
                    ocrReviewState = ocrReviewState,
                    onOpenOcrReview = {
                        session.documentSession.libraryDocument?.let { document ->
                            ocrReview.open(
                                documentId = document.documentId,
                                title = document.title,
                                preferredPageId = session.documentPages
                                    .getOrNull(ocrSelectedPageIndex)
                                    ?.persistentId,
                            )
                        }
                    },
                    onCloseOcrReview = ocrReview::close,
                    onOcrReviewBeginEdit = ocrReview::beginEdit,
                    onOcrReviewDraftChange = ocrReview::updateDraft,
                    onOcrReviewSave = ocrReview::saveCorrection,
                    onOcrReviewCancelEdit = ocrReview::cancelEdit,
                    onOcrReviewRevert = ocrReview::revertCorrection,
                    onOcrReviewPageSelected = ocrReview::selectPage,
                    onOcrReviewRerunPage = { selection, useAsDefault ->
                        ocrReview.rerunOcr(
                            OcrReviewRerunScope.PAGE,
                            resolveOcrOperation(selection, useAsDefault),
                        )
                    },
                    onOcrReviewRerunDocument = { selection, useAsDefault ->
                        ocrReview.rerunOcr(
                            OcrReviewRerunScope.DOCUMENT,
                            resolveOcrOperation(selection, useAsDefault),
                        )
                    },
                    onOcrReviewCancelRerun = ocrReview::cancelRerun,
                    onViewSourceCode = ::openSourceCode,
                    onRateRme = ::openPlayListing,
                    onSuggestFeature = ::suggestFeature,
                    onShareRme = ::shareRme,
                    ocrDefaultSelection = ocrDefaultSelection,
                    ocrRecommendation = currentOcrRecommendation(),
                    ocrModelStates = ocrModelStates,
                    onOcrDefaultSelectionChanged = ::updateOcrDefaultSelection,
                    onOcrInstallRequested = ::requestOcrModelInstall,
                    onOcrModelRefreshRequested = ::refreshOcrModelStates,
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
                LocalPdfToolsDialog(
                    state = localPdfTools.state,
                    onDismiss = localPdfTools::close,
                    onOptionsChanged = localPdfTools::discardOutput,
                    onPrepare = localPdfTools::prepare,
                    onSave = {
                        localPdfTools.createSaveRequest()?.let { requestId ->
                            try {
                                if (protectedActions.markLocalToolsSavePickerLaunched(requestId)) {
                                    createLocalToolsPdfLauncher.launch(localPdfTools.state.suggestedName)
                                } else {
                                    localPdfTools.save(null, requestId)
                                    localPdfTools.showMessage("Finish or cancel the previous save dialog first.")
                                }
                            } catch (_: ActivityNotFoundException) {
                                protectedActions.consumeLocalToolsSavePickerRequest()
                                localPdfTools.save(null, requestId)
                                localPdfTools.showMessage("No system file picker is available.")
                            }
                        }
                    },
                    onShare = { handOffLocalPdfToolCopy(preview = false) },
                    onPreview = { handOffLocalPdfToolCopy(preview = true) },
                    onIdCopy = { localPdfTools.close(); cardCopyWorkflow.open() },
                )
                PasswordPdfExportDialog(
                    state = passwordPdfExport.state,
                    onDismiss = passwordPdfExport::close,
                    onOptionsChanged = passwordPdfExport::discardOutput,
                    onPrepare = passwordPdfExport::prepare,
                    onSave = ::choosePasswordPdfDestination,
                    onShare = ::sharePasswordPdf,
                )
            }
        }
        handleInboundIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("password_pdf_save_request", protectedActions.passwordPdfSavePickerRequestForRecreation())
        outState.putString("local_tools_save_request", protectedActions.localToolsSavePickerRequestForRecreation())
        outState.putString(STATE_PORTABILITY_ROUTE, portabilityRoute.name)
        outState.putString(STATE_MIGRATION_SOURCE, selectedMigrationSource.name)
        outState.putBoolean(STATE_MIGRATION_REVIEW_EXPANDED, migrationReviewExpanded)
        outState.putBoolean(STATE_MIGRATION_SHOW_ISSUES, migrationShowIssues)
        outState.putString(STATE_RESTORE_DUPLICATE_CHOICE, restoreDuplicateChoice.name)
        outState.putBoolean(STATE_NEW_PHONE_WORKFLOW_ARMED, newPhoneWorkflowArmed)
        outState.putBoolean(STATE_INBOUND_MIGRATION_FLOW, inboundMigrationFlow)
        outState.putBoolean(
            STATE_INBOUND_MIGRATION_RESTORE_EXISTING_TASK,
            inboundMigrationRestoreExistingTask,
        )
        super.onSaveInstanceState(outState)
    }

    private fun restoreActivityUiState(savedState: Bundle?) {
        if (savedState == null) return
        protectedActions.restorePasswordPdfSavePickerRequest(savedState.getString("password_pdf_save_request"))
        protectedActions.restoreLocalToolsSavePickerRequest(savedState.getString("local_tools_save_request"))
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
        inboundMigrationFlow = savedState.getBoolean(STATE_INBOUND_MIGRATION_FLOW)
        inboundMigrationRestoreExistingTask = savedState.getBoolean(
            STATE_INBOUND_MIGRATION_RESTORE_EXISTING_TASK,
        )
    }

    private fun updateProtectedWindow(appLockEnabled: Boolean) {
        if (appLockEnabled) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onStart() {
        super.onStart()
        appLock.onAppForegrounded(SystemClock.elapsedRealtime())
        session.setHistoryActive(appLock.state.value.phase!=AppLockPhase.LOCKED)
        organization.setActive(appLock.state.value.phase!=AppLockPhase.LOCKED)
        recycle.setActive(appLock.state.value.phase!=AppLockPhase.LOCKED)
        qr.setActive(appLock.state.value.phase!=AppLockPhase.LOCKED)
        businessCard.setActive(appLock.state.value.phase!=AppLockPhase.LOCKED)
        receipt.setActive(appLock.state.value.phase!=AppLockPhase.LOCKED)
        table.setActive(appLock.state.value.phase!=AppLockPhase.LOCKED)
        forms.setActive(appLock.state.value.phase!=AppLockPhase.LOCKED)
        pdfText.setActive(appLock.state.value.phase!=AppLockPhase.LOCKED)
        workflows.foreground(appLock.state.value.phase!=AppLockPhase.LOCKED)
        xlsx.foreground(appLock.state.value.phase!=AppLockPhase.LOCKED)
        pptx.foreground(appLock.state.value.phase!=AppLockPhase.LOCKED)
        integrity.foreground(appLock.state.value.phase!=AppLockPhase.LOCKED)
        photo.foreground(appLock.state.value.phase!=AppLockPhase.LOCKED)
        assistance.foreground(appLock.state.value.phase!=AppLockPhase.LOCKED)
        translation.foreground(appLock.state.value.phase!=AppLockPhase.LOCKED)
        docx.foreground(appLock.state.value.phase!=AppLockPhase.LOCKED)
        backupReminder.refreshEligibility()
    }

    override fun onStop() {
        xlsx.background(isChangingConfigurations,table.state?.phase==org.synapseworks.pageharbor.table.TablePhase.IMAGE||receipt.state?.phase==org.synapseworks.pageharbor.receipt.ReceiptPhase.PICKER)
        pptx.background(isChangingConfigurations)
        integrity.background(isChangingConfigurations)
        photo.background(isChangingConfigurations)
        assistance.background(isChangingConfigurations)
        translation.background(isChangingConfigurations)
        docx.background(isChangingConfigurations)
        workflows.background()
        businessCard.background(isChangingConfigurations)
        receipt.background(isChangingConfigurations)
        table.background(isChangingConfigurations)
        forms.background(isChangingConfigurations)
        pdfText.background(isChangingConfigurations)
        qr.background(isChangingConfigurations)
        session.setHistoryActive(false)
        if(!isChangingConfigurations){annotationEditor.cancel();collage.close();organization.setActive(false);recycle.setActive(false)}
        bookSplit.close()
        curvature.close()
        if (!isChangingConfigurations) importCrop.close()
        objectRemoval.close()
        continuousCapture.pause()
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
                    exactDuplicateCount = preview.exactDuplicateCount,
                    possibleDuplicateCount = preview.possibleDuplicateCount,
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
                currentStage = if (operation.progress.isPreparingSearch) {
                    "Preparing search: ${operation.progress.processedSearchPages} of " +
                        "${operation.progress.totalSearchPages} pages"
                } else if (operation.progress.isCopyingDocument) {
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
        is MigrationOperationStatus.Cancelled -> UiPortabilityWorkflowState.MigrationSource(
            selectedSource = operation.source.app.toUiMigrationSource(),
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
        val sourceSize = buildString {
            append(formatByteCount(storage.knownSourceBytes))
            if (storage.unknownSourceCount > 0) append(" + unknown provider sizes")
        }
        val libraryEstimate = buildString {
            append(formatByteCount(storage.estimatedPersistentBytes))
            if (!storage.isComplete) append(" + unknown document sizes")
        }
        return MigrationPreviewUiModel(
            source = source.app.toUiMigrationSource(),
            documentCount = documentCount,
            pageCount = pageCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            folderCount = folderCount,
            exactDuplicateCount = exactDuplicateCount,
            possibleDuplicateCount = possibleDuplicateCount,
            unsupportedFileCount = unsupportedSourceCount + unreadableSourceCount,
            sourceSize = sourceSize,
            estimatedLibraryStorage = libraryEstimate,
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
        searchReadyDocuments = searchReadyDocumentCount,
        partiallySearchReadyDocuments = partiallySearchReadyDocumentCount,
        failedSearchPreparationDocuments = failedSearchPreparationDocumentCount,
        searchPreparationCancelled = searchPreparationCancelled,
    )

    private fun MigrationIssueReason.safeMigrationIssueMessage(): String = when (this) {
        MigrationIssueReason.UNSUPPORTED_SOURCE -> "Unsupported file type"
        MigrationIssueReason.UNREADABLE_SOURCE -> "The selected file could not be read"
        MigrationIssueReason.SOURCE_UNAVAILABLE -> "The source is no longer available"
        MigrationIssueReason.SOURCE_CHANGED -> "The source changed and must be reviewed again"
        MigrationIssueReason.SOURCE_TOO_LARGE -> "The source exceeds the migration safety limit"
        MigrationIssueReason.INSUFFICIENT_STORAGE -> "Not enough usable storage"
        MigrationIssueReason.VALIDATION_FAILED -> "The source failed validation"
        MigrationIssueReason.STAGING_FAILED -> "The document could not be staged safely"
        MigrationIssueReason.PUBLICATION_FAILED -> "The document could not be published safely"
        MigrationIssueReason.WRITE_FAILED -> "The document could not be published safely"
        MigrationIssueReason.INVALID_DOCUMENT -> "The document failed validation"
        MigrationIssueReason.INTERRUPTED -> "The operation was interrupted"
        MigrationIssueReason.POSSIBLE_DUPLICATE_REVIEW_REQUIRED ->
            "Possible duplicate; choose Import anyway to include it"
        MigrationIssueReason.EXCLUDED_BY_USER -> "Excluded from migration"
        MigrationIssueReason.AMBIGUOUS_REVIEW_UNRESOLVED ->
            "Ambiguous grouping requires review"
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
        MigrationSourceApp.MICROSOFT_LENS -> ScannerMigrationSource.MICROSOFT_LENS
        MigrationSourceApp.OTHER -> ScannerMigrationSource.OTHER
    }

    private fun ScannerMigrationSource.toEngineMigrationSource(): MigrationSourceApp = when (this) {
        ScannerMigrationSource.CAMSCANNER -> MigrationSourceApp.CAMSCANNER
        ScannerMigrationSource.ADOBE_SCAN -> MigrationSourceApp.ADOBE_SCAN
        ScannerMigrationSource.GENIUS_SCAN -> MigrationSourceApp.GENIUS_SCAN
        ScannerMigrationSource.MICROSOFT_LENS -> MigrationSourceApp.MICROSOFT_LENS
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
            inboundMigrationFlow = false
            inboundMigrationRestoreExistingTask = false
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
            if (!migration.cancelCurrentOperation() &&
                migration.state.value.operation is MigrationOperationStatus.PreviewReady
            ) {
                if (inboundMigrationFlow) {
                    migration.dismissResult()
                    finishOrRestoreInboundMigration()
                } else {
                    migration.selectSource(selectedMigrationSource.toEngineMigrationSource())
                }
            }
        },
        onViewMigratedDocuments = {
            migration.dismissResult()
            inboundMigrationFlow = false
            inboundMigrationRestoreExistingTask = false
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
                -> {
                    migration.dismissResult()
                    portabilityRoute = PortabilityRoute.NONE
                }
                is MigrationOperationStatus.PreviewReady -> {
                    migration.dismissResult()
                    if (inboundMigrationFlow) finishOrRestoreInboundMigration()
                    return
                }
                is MigrationOperationStatus.Cancelled,
                is MigrationOperationStatus.Failed,
                -> {
                    migration.dismissResult()
                    if (inboundMigrationFlow) finishOrRestoreInboundMigration()
                    return
                }
                is MigrationOperationStatus.Completed -> {
                    migration.dismissResult()
                    inboundMigrationFlow = false
                    inboundMigrationRestoreExistingTask = false
                    portabilityRoute = PortabilityRoute.NONE
                }
                is MigrationOperationStatus.Preparing,
                is MigrationOperationStatus.AwaitingMultipleFiles,
                is MigrationOperationStatus.AwaitingDocumentTree,
                is MigrationOperationStatus.Importing,
                is MigrationOperationStatus.Cancelling,
                -> {
                    migration.cancelCurrentOperation()
                    return
                }
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
        PortabilityWorkflowFailure.RESTORE_DELETED_DOCUMENT_CONFLICT -> "This archive contains a document deleted here. Restore it from Recycle bin first. Purged known identities stay blocked; existing documents were not replaced."
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

    private fun openLibraryDocument(documentId: String, pageId: String? = null) {
        lifecycleScope.launch {
            when (val opened = library.openDocument(documentId)) {
                is LibraryResult.Success -> {
                    clearRecognizedText()
                    clearSearchablePdfSave()
                    clearNormalDocumentOperations()
                    if (session.openLibraryDocument(opened.value.session, pageId) &&
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
            library.deleteDocument(documentId)
            return
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
                when (
                    val saved = library.saveSession(
                        lease.session,
                        title,
                        recognized,
                        session.lastOcrScript,
                    )
                ) {
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
        inboundIntentArrivedViaNewIntent = true
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
            is PendingProtectedAction.TargetSizePdfDestination ->
                targetSizeWorkflow.onSaveDestination(action.uri)
            is PendingProtectedAction.CardCopyScannerResult ->
                handleCardCopyScannerResult(action.resultCode, action.data)
            is PendingProtectedAction.CardCopyImageResult ->
                cardCopyWorkflow.onAcquisitionResult(action.uri)
            is PendingProtectedAction.CardCopyPdfDestination ->
                cardCopyWorkflow.onSaveDestination(action.uri)
            is PendingProtectedAction.ShareSafePdfDestination ->
                shareSafeWorkflow.onSaveDestination(action.uri)
            is PendingProtectedAction.PasswordPdfDestination ->
                passwordPdfExport.save(action.uri, action.requestId) {
                    appLock.state.value.phase != AppLockPhase.LOCKED
                }
            is PendingProtectedAction.LocalToolsPdfDestination ->
                localPdfTools.save(action.uri, action.requestId)
            is PendingProtectedAction.LaunchPortabilityPicker ->
                launchPortabilityPicker(action.request)
            is PendingProtectedAction.LaunchMigrationPicker -> launchMigrationPicker(action.request)
            is PendingProtectedAction.InboundIntent -> handleInboundIntentUnlocked(action.intent)
        }
    }

    internal fun openPdfText() {
        if(appLock.state.value.phase==AppLockPhase.LOCKED||forms.state!=null||table.state!=null||receipt.state!=null||businessCard.state!=null||qr.state!=null||continuousCapture.state!=null||bookSplit.state!=null||curvature.state!=null||objectRemoval.state!=null||collage.state!=null||annotationEditor.state!=null||session.batchEditor.state!=null)return
        pdfText.open()
    }

    internal fun openForms() {
        if(appLock.state.value.phase==AppLockPhase.LOCKED||pdfText.state!=null||table.state!=null||receipt.state!=null||businessCard.state!=null||qr.state!=null||continuousCapture.state!=null||bookSplit.state!=null||curvature.state!=null||objectRemoval.state!=null||collage.state!=null||annotationEditor.state!=null||session.batchEditor.state!=null)return
        forms.open()
    }

    internal fun openTable() {
        if(appLock.state.value.phase==AppLockPhase.LOCKED||pdfText.state!=null||forms.state!=null||receipt.state!=null||businessCard.state!=null||qr.state!=null||continuousCapture.state!=null||bookSplit.state!=null||curvature.state!=null||objectRemoval.state!=null||collage.state!=null||annotationEditor.state!=null||session.batchEditor.state!=null)return
        table.open()
    }

    internal fun openReceipt() {
        if(appLock.state.value.phase==AppLockPhase.LOCKED||pdfText.state!=null||forms.state!=null||table.state!=null||businessCard.state!=null||qr.state!=null||continuousCapture.state!=null||bookSplit.state!=null||curvature.state!=null||objectRemoval.state!=null||collage.state!=null||annotationEditor.state!=null||session.batchEditor.state!=null)return
        receipt.open()
    }

    internal fun openBusinessCard() {
        if(appLock.state.value.phase==AppLockPhase.LOCKED||pdfText.state!=null||forms.state!=null||table.state!=null||receipt.state!=null||qr.state!=null||continuousCapture.state!=null||bookSplit.state!=null||curvature.state!=null||objectRemoval.state!=null||collage.state!=null||annotationEditor.state!=null||session.batchEditor.state!=null)return
        businessCard.open()
    }

    internal fun openQrScanner() {
        if(appLock.state.value.phase==AppLockPhase.LOCKED||pdfText.state!=null||forms.state!=null||table.state!=null||receipt.state!=null||businessCard.state!=null||continuousCapture.state!=null||bookSplit.state!=null||curvature.state!=null||objectRemoval.state!=null||collage.state!=null||annotationEditor.state!=null||session.batchEditor.state!=null)return
        qr.open()
    }

    internal fun openContinuousCapture(singlePage: Boolean = false) {
        if(appLock.state.value.phase==AppLockPhase.LOCKED||pdfText.state!=null||forms.state!=null||table.state!=null||receipt.state!=null||businessCard.state!=null||qr.state!=null||!continuousCapture.canOpen)return
        val token=session.beginContinuousRequest()?:run {
            if(session.documentPages.isEmpty()&&session.documentSession.directPdfSource!=null)
                android.widget.Toast.makeText(this,R.string.continuous_pdf_only,android.widget.Toast.LENGTH_LONG).show()
            return
        }
        if(!continuousCapture.open(token,session.documentRevision,singlePage))session.cancelContinuousRequest(token)
    }
    internal fun openCardCopyFromCamera(cameraOwner: String) {
        val capture = continuousCapture.state ?: return
        if (capture.owner != cameraOwner || !capture.canSelectMode ||
            appLock.state.value.phase == AppLockPhase.LOCKED || cardCopyWorkflow.state != CardCopyWorkflowState.Hidden) return
        // End only this empty camera acquisition. The current document remains the return destination.
        cancelContinuousCapture()
        cardCopyWorkflow.open()
    }

    internal fun cancelContinuousCapture() {
        continuousUseJob?.cancel();continuousUseJob=null;continuousUseOwner=null
        continuousCapture.request?.let(session::cancelContinuousRequest)
        continuousCapture.cancel()
    }
    internal fun useContinuousPages() {
        if(continuousUseJob?.isActive==true)return
        val capture=continuousCapture.state?:return
        val token=continuousCapture.request?:return
        continuousUseOwner=capture.owner
        continuousUseJob=lifecycleScope.launch {
            try {
                val input=withContext(Dispatchers.IO){val context=kotlinx.coroutines.currentCoroutineContext();runCatching{continuousCapture.input{context.ensureActive()}}.getOrNull()}
                if(input==null){
                    if(continuousCapture.state===capture){cancelContinuousCapture();android.widget.Toast.makeText(this@MainActivity,R.string.continuous_capture_lost,android.widget.Toast.LENGTH_SHORT).show()}
                    return@launch
                }
                if(continuousCapture.state!==capture||appLock.state.value.phase==AppLockPhase.LOCKED)return@launch
                if(!continuousCapture.transfer{session.completeContinuousRequest(token,capture.revision,input)}) {
                    cancelContinuousCapture()
                    android.widget.Toast.makeText(this@MainActivity,R.string.continuous_stale,android.widget.Toast.LENGTH_SHORT).show()
                } else if (capture.singlePage) {
                    openCapturedPageReviewWhenReady(capture.mode)
                }
            } finally {if(continuousUseOwner==capture.owner){continuousUseJob=null;continuousUseOwner=null}}
        }
    }

    private suspend fun openCapturedPageReviewWhenReady(mode: CaptureMode) {
        val published = session.documentSession
        val revision = session.documentRevision
        val page = published.pages.lastOrNull() ?: return
        // Publishing newly owned files starts an asynchronous history audit. Retain this explicit
        // review request until it admits that exact page, or until its UI owner is no longer active.
        val stillCurrent = {
            session.documentSession === published && session.documentRevision == revision &&
                session.screen == PageHarborScreen.ScanResult && session.documentPages.lastOrNull() === page &&
                appLock.state.value.phase != AppLockPhase.LOCKED &&
                lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        combine(
            snapshotFlow { session.historyReady() || session.documentSession !== published || session.screen != PageHarborScreen.ScanResult },
            session.documentRevisionChanges, lifecycle.currentStateFlow, appLock.state,
        ) { readyOrChanged, currentRevision, lifecycleState, lock ->
            readyOrChanged || currentRevision != revision || !lifecycleState.isAtLeast(Lifecycle.State.STARTED) ||
                lock.phase == AppLockPhase.LOCKED
        }.first { it }
        if (stillCurrent()) {
            if (mode == CaptureMode.BOOK) {
                openBookSplit(published.pages.lastIndex, capturedSpread = true)
            } else {
                // Explicit review stays open when automatic boundary detection has no proposal.
                openImportCrop(published.pages.lastIndex, automatic = false)
            }
        }
    }

    internal fun openLibraryOrganization(folderId:String?=null,query:String="",sort:org.synapseworks.pageharbor.library.LibrarySortOrder=org.synapseworks.pageharbor.library.LibrarySortOrder.MODIFIED_DESC) {
        if(appLock.state.value.phase==AppLockPhase.LOCKED||collage.state!=null||annotationEditor.state!=null||curvature.state!=null||objectRemoval.state!=null||bookSplit.state!=null||continuousCapture.state!=null)return
        organization.open(folderId,query,sort)
    }

    internal fun openCollage() {
        if(!session.historyReady()||appLock.state.value.phase==AppLockPhase.LOCKED||continuousCapture.state!=null||bookSplit.state!=null||curvature.state!=null||objectRemoval.state!=null||collage.state!=null||annotationEditor.state!=null||session.batchEditor.state!=null)return
        val document=session
        val lease=document.acquireDocumentSessionLease(documentSessionLeaseReleaseObserverForTest)?:return
        if(!collage.open(lease.session,document.documentRevision,document.documentOwnerToken){document.releaseDocumentSessionLease(lease)})document.releaseDocumentSessionLease(lease)
    }
    internal fun applyCollage() {
        collage.apply{expected,revision,owner,output->
            appLock.state.value.phase!=AppLockPhase.LOCKED&&session.applyCollage(expected,revision,output,owner)
        }
    }

    internal fun openAnnotations(page:org.synapseworks.pageharbor.document.session.DocumentPage) {
        if(!session.historyReady()||appLock.state.value.phase==AppLockPhase.LOCKED||continuousCapture.state!=null||bookSplit.state!=null||curvature.state!=null||objectRemoval.state!=null||collage.state!=null||session.batchEditor.state!=null)return
        val document=session
        if(document.documentSession.pages.firstOrNull{it.id==page.id}!==page)return
        val lease=document.acquireDocumentSessionLease()?:return
        annotationEditor.open(page,document.documentRevision,document.documentOwnerToken){document.releaseDocumentSessionLease(lease)}
    }

    internal fun openBookSplit(pageIndex:Int, capturedSpread: Boolean = false) {
        if(!session.historyReady())return
        if(session.documentSession.pages.getOrNull(pageIndex)?.annotations?.isEmpty==false){android.widget.Toast.makeText(this,"Remove annotations before changing page geometry.",android.widget.Toast.LENGTH_LONG).show();return}
        if(appLock.state.value.phase==AppLockPhase.LOCKED||continuousCapture.state!=null||bookSplit.state!=null||curvature.state!=null||objectRemoval.state!=null||collage.state!=null||annotationEditor.state!=null)return
        val document=session
        val lease=document.acquireDocumentSessionLease(documentSessionLeaseReleaseObserverForTest)?:return
        if(!bookSplit.open(lease.session,document.documentRevision,pageIndex,capturedSpread){document.releaseDocumentSessionLease(lease)})document.releaseDocumentSessionLease(lease)
    }
    internal fun applyBookSplit() {
        bookSplit.apply{expected,revision,replacements->
            appLock.state.value.phase!=AppLockPhase.LOCKED&&session.applyBookSplit(expected,revision,replacements)
        }
    }

    internal fun openObjectRemoval(pageIndex:Int)=openRemoval(pageIndex,org.synapseworks.pageharbor.document.objectremoval.RemovalMode.BRUSH)
    internal fun openFingerRemoval(pageIndex:Int)=openRemoval(pageIndex,org.synapseworks.pageharbor.document.objectremoval.RemovalMode.FINGER)
    private fun openRemoval(pageIndex:Int,mode:org.synapseworks.pageharbor.document.objectremoval.RemovalMode) {
        if(!session.historyReady()||appLock.state.value.phase==AppLockPhase.LOCKED||continuousCapture.state!=null||bookSplit.state!=null||curvature.state!=null||objectRemoval.state!=null||collage.state!=null||annotationEditor.state!=null||session.batchEditor.state!=null)return
        val document=session
        val lease=document.acquireDocumentSessionLease(documentSessionLeaseReleaseObserverForTest)?:return
        if(!objectRemoval.open(lease.session,document.documentRevision,pageIndex,document.documentOwnerToken,mode){document.releaseDocumentSessionLease(lease)})document.releaseDocumentSessionLease(lease)
    }
    internal fun applyObjectRemoval() {
        val action=if(objectRemoval.state?.mode==org.synapseworks.pageharbor.document.objectremoval.RemovalMode.FINGER)org.synapseworks.pageharbor.document.history.HistoryAction.FINGER_REMOVAL else org.synapseworks.pageharbor.document.history.HistoryAction.OBJECT_REMOVAL
        objectRemoval.apply{expected,revision,output,owner->
            appLock.state.value.phase!=AppLockPhase.LOCKED&&session.applyObjectRemoval(expected,revision,output,owner,action)
        }
    }

    internal fun openCurvature(pageIndex:Int) {
        if(!session.historyReady())return
        if(session.documentSession.pages.getOrNull(pageIndex)?.annotations?.isEmpty==false){android.widget.Toast.makeText(this,"Remove annotations before changing page geometry.",android.widget.Toast.LENGTH_LONG).show();return}
        if(appLock.state.value.phase==AppLockPhase.LOCKED||continuousCapture.state!=null||bookSplit.state!=null||curvature.state!=null||objectRemoval.state!=null||collage.state!=null||annotationEditor.state!=null)return
        val document=session
        val lease=document.acquireDocumentSessionLease(documentSessionLeaseReleaseObserverForTest)?:return
        if(!curvature.open(lease.session,document.documentRevision,pageIndex){document.releaseDocumentSessionLease(lease)})document.releaseDocumentSessionLease(lease)
    }
    internal fun applyCurvature() {
        curvature.apply{expected,revision,replacement->
            appLock.state.value.phase!=AppLockPhase.LOCKED&&session.applyCurvature(expected,revision,replacement)
        }
    }

    private fun openImportCrop(pageIndex: Int, automatic: Boolean) {
        if (!session.historyReady() || appLock.state.value.phase == AppLockPhase.LOCKED ||
            importCrop.state != null || continuousCapture.state != null || bookSplit.state != null ||
            curvature.state != null || objectRemoval.state != null || collage.state != null ||
            annotationEditor.state != null || session.batchEditor.state != null) return
        if (session.documentPages.getOrNull(pageIndex)?.supportsImportCrop() != true) return
        val document = session
        val lease = document.acquireDocumentSessionLease(documentSessionLeaseReleaseObserverForTest) ?: return
        if (!importCrop.open(lease.session, document.documentRevision, pageIndex, automatic) {
                document.releaseDocumentSessionLease(lease)
            }) document.releaseDocumentSessionLease(lease)
    }

    private fun applyImportCrop() {
        importCrop.apply { expected, revision, page, derived, filter ->
            appLock.state.value.phase != AppLockPhase.LOCKED &&
                lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) &&
                session.applyPageGeometry(expected, revision, page, derived, filter,
                    org.synapseworks.pageharbor.document.history.HistoryAction.CROP)
        }
    }

    internal fun navigateDocumentHistory(undo:Boolean):Boolean {
        if(appLock.state.value.phase==AppLockPhase.LOCKED||!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)||
            continuousCapture.state!=null||bookSplit.state!=null||curvature.state!=null||importCrop.state!=null||objectRemoval.state!=null||collage.state!=null||annotationEditor.state!=null||session.batchEditor.state!=null)return false
        return if(undo)session.undoDocument()else session.redoDocument()
    }

    private fun launchDocumentScanner() {
        if (session.beginScannerRequest() == null) return
        clearNormalDocumentOperations()
        val options = GmsDocumentScannerOptions.Builder()
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_BASE_WITH_FILTER)
            .setResultFormats(
                GmsDocumentScannerOptions.RESULT_FORMAT_JPEG,
                GmsDocumentScannerOptions.RESULT_FORMAT_PDF,
            )
            .setGalleryImportAllowed(true)
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
        inboundPhotoJob?.cancel()
        when (val input = extractInboundShareInput(intent, privateShareAuthorities)) {
            InboundShareInput.NotShareIntent -> Unit
            is InboundShareInput.Failure -> {
                consumeInboundActivityIntent()
                inboundIntentArrivedViaNewIntent = false
                session.failImportRequest(input.reason)
            }
            is InboundShareInput.Ready -> {
                if (input.isSinglePhotoShare(intent.action)) {
                    importSharedPhotoWhenReady(intent, input)
                    return
                }
                consumeInboundActivityIntent()
                importCrop.close()
                inboundMigrationFlow = true
                inboundMigrationRestoreExistingTask = inboundIntentArrivedViaNewIntent
                inboundIntentArrivedViaNewIntent = false
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

    private fun importSharedPhotoWhenReady(intent: Intent, input: InboundShareInput.Ready) {
        inboundPhotoJob = lifecycleScope.launch {
            lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.STARTED) }
            snapshotFlow { session.historyReady() || appLock.state.value.phase == AppLockPhase.LOCKED }.first { it }
            if (appLock.gate(AppLockProtectedEntryPoint.INBOUND_SHARE).decision != AppLockAccessDecision.ALLOW) {
                protectedActions.enqueue(PendingProtectedAction.InboundIntent(intent, intent.inboundShareFingerprint()))
                return@launch
            }
            consumeInboundActivityIntent()
            inboundIntentArrivedViaNewIntent = false
            if (session.beginImportRequest(DocumentImportOrigin.INBOUND_SHARE)) {
                importCrop.close()
                processImportedUris(
                    input.uris,
                    org.synapseworks.pageharbor.document.session.DocumentSourceCategory.INBOUND_SHARE,
                )
            }
        }
    }

    private fun finishOrRestoreInboundMigration() {
        val restoreExistingTask = inboundMigrationRestoreExistingTask
        inboundMigrationFlow = false
        inboundMigrationRestoreExistingTask = false
        portabilityRoute = PortabilityRoute.NONE
        migrationReviewExpanded = false
        migrationShowIssues = false
        if (!restoreExistingTask) finish()
    }

    private fun Intent.inboundShareFingerprint(): String = when (
        val input = extractInboundShareInput(this, privateShareAuthorities)
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
                    val completed = session.importUiState as? org.synapseworks.pageharbor.document.importing.DocumentImportUiState.Completed
                    if (completed?.appended == false && session.documentPages.singleOrNull()?.supportsImportCrop() == true) {
                        val importedRevision = session.documentRevision
                        snapshotFlow { session.historyReady() || session.documentRevision != importedRevision }.first { it }
                        if (session.documentRevision == importedRevision && session.screen == PageHarborScreen.ScanResult) {
                            openImportCrop(0, automatic = true)
                        }
                    }
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

    private fun currentOcrRecommendation() = OcrLocaleScriptMapper.recommend(
        resources.configuration.locales.get(0),
    )

    private fun updateOcrDefaultSelection(selection: OcrScriptSelection) {
        ocrLanguagePreferences.write(selection)
        ocrDefaultSelection = selection
    }

    private fun resolveOcrOperation(
        operationSelection: OcrOperationSelection,
        useAsDefault: Boolean,
    ): OcrScript {
        val resolved = OcrOperationSelectionResolver.resolve(
            operationSelection = operationSelection,
            defaultSelection = ocrDefaultSelection,
            recommendation = currentOcrRecommendation(),
            useAsDefault = useAsDefault,
        )
        resolved.updatedDefault?.let(::updateOcrDefaultSelection)
        return resolved.script
    }

    private fun requestOcrModelInstall(script: OcrScript) {
        if (script == OcrScript.LATIN) return
        lifecycleScope.launch { multilingualOcrRuntime.modelInstaller.requestInstall(script) }
    }

    private fun refreshOcrModelStates() {
        lifecycleScope.launch {
            OcrScript.entries.filterNot { it == OcrScript.LATIN }.forEach { script ->
                multilingualOcrRuntime.modelInstaller.stateFor(script)
            }
        }
    }

    private fun recognizeText() = recognizeText(
        ocrDefaultSelection.resolve(currentOcrRecommendation()),
    )

    private fun recognizeText(script: OcrScript) {
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

        val documentRevision = session.documentRevision
        val savedDocument = lease.session.libraryDocument
        val savedDocumentId = savedDocument?.documentId
        val persistentPageIds = lease.session.pages.map(DocumentPage::persistentId)
        ocrUiState = OcrUiState.Recognizing
        val operationId = ocrOperationTracker.begin(documentRevision)
        ocrJob = lifecycleScope.launch {
            try {
                val capturedSnapshots = if (savedDocumentId != null) {
                    val orderedPageIds = persistentPageIds.filterNotNull()
                    if (orderedPageIds.size != pages.size) {
                        publishOcrUnexpectedFailure(operationId)
                        return@launch
                    }
                    val snapshots = library.captureOcrPageSnapshots(
                        documentId = savedDocumentId,
                        orderedPageIds = orderedPageIds,
                    )
                    if (!ocrOperationTracker.isCurrent(operationId, session.documentRevision)) {
                        return@launch
                    }
                    if (snapshots.size != pages.size) {
                        publishOcrUnexpectedFailure(operationId)
                        return@launch
                    }
                    snapshots
                } else {
                    null
                }

                val scriptedResult = withContext(Dispatchers.IO) {
                    if (script == OcrScript.LATIN) {
                        null
                    } else {
                        ScriptedOcrSessionRunner(
                            engine = multilingualOcrRuntime.recognitionEngine,
                        ).recognize(
                            pages = pages,
                            script = script,
                            sessionDocumentRevision = documentRevision,
                            savedSnapshots = capturedSnapshots,
                            recognizedAtMillis = System.currentTimeMillis(),
                        )
                    }
                }
                val result = scriptedResult?.result ?: withContext(Dispatchers.IO) {
                    ocrEngine.recognize(pages)
                }
                if (!ocrOperationTracker.isCurrent(operationId, session.documentRevision)) {
                    return@launch
                }

                if (capturedSnapshots != null && script == OcrScript.LATIN) {
                    val mapping = BundledLatinOcrResultMapper.map(
                        result = result,
                        orderedSnapshots = capturedSnapshots,
                        recognizedAtMillis = System.currentTimeMillis(),
                    )
                    if (mapping !is BundledLatinOcrMappingResult.Success) {
                        publishOcrUnexpectedFailure(operationId)
                        return@launch
                    }
                    if (!ocrOperationTracker.isCurrent(operationId, session.documentRevision)) {
                        return@launch
                    }
                    val commit = library.indexOcr(
                        documentId = requireNotNull(savedDocumentId),
                        outcomes = mapping.outcomes,
                    )
                    if (commit != LibraryOcrCommitResult.APPLIED) {
                        publishOcrUnexpectedFailure(operationId)
                        return@launch
                    }
                } else if (capturedSnapshots != null) {
                    val outcomes = scriptedResult?.persistenceOutcomes
                    if (outcomes == null) {
                        // Missing/failed optional support must leave the previous durable OCR intact.
                        if (result.pages.all { it.error != null }) {
                            if (ocrOperationTracker.claimCompletion(
                                    operationId,
                                    session.documentRevision,
                                ) == OcrOperationTracker.CompletionClaim.CLAIMED
                            ) {
                                ocrUiState = ocrStateAfterResult(result)
                                ocrTerminalStateObserverForTest?.invoke()
                            }
                            return@launch
                        }
                        publishOcrUnexpectedFailure(operationId)
                        return@launch
                    }
                    val commit = library.indexOcr(
                        documentId = requireNotNull(savedDocumentId),
                        outcomes = outcomes,
                    )
                    if (commit != LibraryOcrCommitResult.APPLIED) {
                        publishOcrUnexpectedFailure(operationId)
                        return@launch
                    }
                }

                if (ocrOperationTracker.claimCompletion(
                        operationId,
                        session.documentRevision,
                    ) != OcrOperationTracker.CompletionClaim.CLAIMED
                ) {
                    return@launch
                }
                ocrUiState = ocrStateAfterResult(result)
                if (ocrUiState is OcrUiState.Success) {
                    session.lastOcrScript = script
                    presentCompletedOcrResult(
                        savedDocumentId = savedDocumentId,
                        savedDocumentTitle = savedDocument?.title,
                        preferredPageId = persistentPageIds.firstOrNull(),
                    )
                }
                ocrSelectedPageIndex = 0
                ocrTerminalStateObserverForTest?.invoke()
            } catch (_: CancellationException) {
                return@launch
            } catch (_: Exception) {
                publishOcrUnexpectedFailure(operationId)
            } finally {
                session.releaseDocumentSessionLease(lease)
            }
        }
    }

    private fun presentCompletedOcrResult(
        savedDocumentId: String?,
        savedDocumentTitle: String?,
        preferredPageId: String?,
    ) {
        if (savedDocumentId != null && savedDocumentTitle != null) {
            ocrReview.open(
                documentId = savedDocumentId,
                title = savedDocumentTitle,
                preferredPageId = preferredPageId,
            )
        } else {
            session.screen = PageHarborScreen.OcrResult
        }
    }

    private fun publishOcrUnexpectedFailure(operationId: Long) {
        if (
            ocrOperationTracker.claimCompletion(operationId, session.documentRevision) ==
            OcrOperationTracker.CompletionClaim.CLAIMED
        ) {
            ocrUiState = OcrUiState.Error(OcrUiError.UNEXPECTED_FAILURE)
            ocrTerminalStateObserverForTest?.invoke()
        }
    }

    private fun clearRecognizedText() {
        ocrOperationTracker.invalidate()
        ocrJob?.cancel()
        ocrJob = null
        ocrUiState = clearedOcrState()
        session.lastOcrScript = null
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

    private fun saveSearchablePdf() = saveSearchablePdf(
        ocrDefaultSelection.resolve(currentOcrRecommendation()),
    )

    private fun saveSearchablePdf(script: OcrScript) {
        if (searchablePdfSaveState.isInProgress()) return
        val lease = session.acquireDocumentSessionLease(documentSessionLeaseReleaseObserverForTest)
        if (lease == null) {
            searchablePdfSaveState = SearchablePdfSaveState.Error(SearchablePdfSaveError.NO_PAGES)
            return
        }
        val documentPages = lease.session.pages

        clearSearchablePdfSave()
        val documentRevision = session.documentRevision
        val operationId = searchablePdfOperationTracker.begin(documentRevision)
        searchablePdfSaveState = SearchablePdfSaveState.Preparing
        val existingOcrResult = (ocrUiState as? OcrUiState.Success)?.result
            ?.takeIf { session.lastOcrScript == script }
        searchablePdfExportJob = lifecycleScope.launch {
            try {
                val persistedOcr = capturePersistedSearchablePdfOcr(lease.session)
                val preparedExport = searchablePdfExportCoordinator.prepare(
                    SearchablePdfExportRequest(
                        pageUris = documentPages.map { page -> page.source.toAndroidUri() },
                        visualPages = documentPages.map { page ->
                            SearchablePdfVisualPage(
                                pageId = page.id.value,
                                originalUri = page.source.toAndroidUri(),
                                filter = page.filter,
                                adjustments = page.adjustments,
                                annotations = page.annotations,
                                paperSize = page.paperSize,
                                rotation = page.rotation,
                                contentType = page.contentType,
                                imageMetadata = page.imageMetadata,
                            )
                        },
                        ocrResult = if (persistedOcr == null) existingOcrResult else null,
                        ocrEngineOverride = if (script == OcrScript.LATIN) {
                            null
                        } else {
                            ScriptedOcrEngine(multilingualOcrRuntime.recognitionEngine, script)
                        },
                        progressListener = SearchablePdfExportProgressListener { progress ->
                            runOnUiThread {
                                if (
                                    searchablePdfOperationTracker.acceptsProgress(
                                        operationId,
                                        session.documentRevision,
                                    )
                                ) {
                                    searchablePdfSaveState = searchablePdfSaveStateForProgress(progress)
                                }
                            }
                        },
                        effectiveOcrPageProvider = persistedOcr?.provider,
                        effectiveOcrPageRequests = persistedOcr?.requests.orEmpty(),
                    ),
                )
                when (
                    searchablePdfOperationTracker.claimCompletion(
                        operationId,
                        session.documentRevision,
                    )
                ) {
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
                if (searchablePdfOperationTracker.claimCompletion(
                        operationId,
                        session.documentRevision,
                    ) ==
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

    /**
     * Uses durable effective OCR whenever any page has persisted OCR. A partially recognized
     * document deliberately enters the provider path and fails safely instead of replacing a
     * corrected page with freshly recognized raw text merely to complete an export.
     */
    private suspend fun capturePersistedSearchablePdfOcr(
        documentSession: DocumentSession,
    ): PersistedSearchablePdfOcr? {
        val documentId = documentSession.libraryDocument?.documentId ?: return null
        val pageIds = documentSession.pages.map(DocumentPage::persistentId)
        if (pageIds.any { it == null }) return null
        val stablePageIds = pageIds.filterNotNull()
        val snapshots = library.captureOcrPageSnapshots(documentId, stablePageIds)
        if (snapshots.size != stablePageIds.size || snapshots.none { it.activeArtifactRevision != null }) {
            return null
        }
        val effectivePages = library.captureEffectiveOcrPages(documentId, stablePageIds)
        if (effectivePages.size != stablePageIds.size) return null
        val requests = snapshots.mapIndexed { index, snapshot ->
            effectivePages[index]?.toEffectiveOcrRequestOrNull() ?: snapshot.toUnavailableOcrRequest()
        }
        return PersistedSearchablePdfOcr(
            provider = EffectiveOcrPageProvider(library::provideEffectiveOcrPage),
            requests = requests,
        )
    }

    private fun clearSearchablePdfSave() {
        searchablePdfOperationTracker.invalidate()
        searchablePdfExportJob?.cancel()
        searchablePdfExportJob = null
        searchablePdfPreparedExport?.let(searchablePdfExportCoordinator::discardPreparedExport)
        searchablePdfPreparedExport = null
        searchablePdfSaveState = SearchablePdfSaveState.Idle
    }

    private fun openFitUploadLimit() {
        if (targetSizeWorkflow.state != TargetSizeWorkflowState.Hidden) return
        val lease = session.acquireDocumentSessionLease(documentSessionLeaseReleaseObserverForTest)
        if (lease == null || lease.session.pages.isEmpty()) {
            lease?.let(session::releaseDocumentSessionLease)
            targetSizeWorkflow.failOpening()
            return
        }
        targetSizeWorkflow.beginOpening()
        lifecycleScope.launch {
            try {
                val documentSession = lease.session
                val effectiveOcr = captureTargetSizeEffectiveOcr(documentSession)
                val directSource = (
                    normalPdfExportPlan(documentSession) as? NormalPdfExportPlan.DirectScannerPdf
                    )?.source
                val directUri = directSource?.toAndroidUri()
                val directBytes = directUri?.let(::contentLength)
                val pageSourceBytes = documentSession.pages
                    .map { it.imageMetadata.sourceByteCount }
                    .takeIf { sizes -> sizes.all { it != null } }
                    ?.sumOf { requireNotNull(it) }
                targetSizeWorkflow.open(
                    TargetSizeWorkflowInput(
                        summary = TargetSizeSourceSummary(
                            title = documentSession.libraryDocument?.title
                                ?: getString(R.string.document_result_title),
                            pageCount = documentSession.pages.size,
                            sourceBytes = directBytes ?: pageSourceBytes,
                        ),
                        request = targetSizePdfRequest(
                            session = documentSession,
                            targetBytes = TargetSizeBytes.MB_1,
                            effectiveOcr = effectiveOcr,
                        ),
                        safeOriginalPdfUri = directUri.takeIf { directBytes != null },
                        safeOriginalPdfBytes = directBytes,
                    ),
                )
            } catch (_: CancellationException) {
                targetSizeWorkflow.failOpening()
            } catch (_: Exception) {
                targetSizeWorkflow.failOpening()
            } finally {
                session.releaseDocumentSessionLease(lease)
            }
        }
    }

    private fun openShareSafeCopy() {
        if (shareSafeWorkflow.state != ShareSafeCopyWorkflowState.Hidden) return
        val lease = session.acquireDocumentSessionLease(documentSessionLeaseReleaseObserverForTest)
        if (lease == null || lease.session.pages.isEmpty()) {
            lease?.let(session::releaseDocumentSessionLease)
            shareSafeWorkflow.open(null)
            return
        }
        lifecycleScope.launch {
            try {
                val documentSession = lease.session
                val ocrCapture = captureShareSafeEffectiveOcr(documentSession)
                val effectiveOcr = ocrCapture.result
                    ?.pages?.associateBy(OcrPageResult::pageIndex).orEmpty()
                val pages = withContext(Dispatchers.IO) {
                    documentSession.pages.mapIndexed { index, page ->
                        val uri = page.source.toAndroidUri()
                        val sourceHash = secureRedactionSha256 {
                            try {
                                contentResolver.openInputStream(uri)
                            } catch (_: Exception) {
                                null
                            }
                        } ?: throw IOException()
                        ShareSafeDocumentPage(
                            source = SecureRedactedPdfPage(
                                pageId = page.persistentId ?: "session-page-${page.id.value}",
                                openSource = {
                                    try {
                                        contentResolver.openInputStream(uri)
                                    } catch (_: Exception) {
                                        null
                                    }
                                },
                                imageMetadata = page.imageMetadata,
                                expectedSourceSha256 = sourceHash,
                                regions = emptyList(),
                                filter = page.filter,
                                adjustments = page.adjustments,
                                annotations = page.annotations,
                                paperSize = page.paperSize,
                                rotation = page.rotation,
                                effectiveOcr = effectiveOcr[index],
                                ocrProvenance = effectiveOcr[index]?.layout
                                    ?.takeIf { ocrCapture.sourceBound }
                                    ?.let { layout ->
                                        SecureRedactedOcrProvenance(
                                            recognizedSourceSha256 = sourceHash,
                                            effectiveWidthPx = layout.imageWidthPx,
                                            effectiveHeightPx = layout.imageHeightPx,
                                            rotation = page.rotation,
                                        )
                                    },
                            ),
                            previewUri = uri,
                        )
                    }
                }
                shareSafeWorkflow.open(
                    ShareSafeDocumentInput(
                        title = documentSession.libraryDocument?.title
                            ?: getString(R.string.document_result_title),
                        pages = pages,
                        sourceGeneration = 0L,
                    ),
                )
            } catch (_: CancellationException) {
                shareSafeWorkflow.open(null)
            } catch (_: Exception) {
                shareSafeWorkflow.open(null)
            } finally {
                session.releaseDocumentSessionLease(lease)
            }
        }
    }

    private fun continueCardCopyToShareSafe() {
        val prepared = cardCopyWorkflow.takePreparedForShareSafe() ?: return
        if (!shareSafeWorkflow.acceptCardCopy(prepared)) {
            prepared.workingDirectory.deleteRecursively()
            shareSafeWorkflow.close()
        }
    }

    private fun backFromCardCopy() {
        cardCopyWorkflow.back()
        if (cardCopyWorkflow.state == CardCopyWorkflowState.Hidden &&
            shareSafeWorkflow.state == ShareSafeCopyWorkflowState.CardCopyHandoff
        ) {
            shareSafeWorkflow.back()
        }
    }

    private fun chooseShareSafeDestination() {
        val current = shareSafeWorkflow.state as? ShareSafeCopyWorkflowState.Prepared ?: return
        if (!shareSafeWorkflow.markSavePickerOpen()) return
        try {
            createShareSafePdfLauncher.launch(shareSafeFilename(current.title))
        } catch (_: ActivityNotFoundException) {
            shareSafeWorkflow.onSaveDestination(null)
        } catch (_: RuntimeException) {
            shareSafeWorkflow.onSaveDestination(null)
        }
    }

    private fun shareShareSafeCopy() {
        lifecycleScope.launch {
            val uri = shareSafeWorkflow.verifiedShareUri()
            if (uri == null) {
                shareSafeWorkflow.markShareResult(false)
                return@launch
            }
            when (val share = createPdfShareIntent(uri)) {
                is PdfShareIntentResult.Success -> try {
                    startActivity(
                        Intent.createChooser(
                            share.intent,
                            getString(R.string.pdf_share_chooser_title),
                        ),
                    )
                    shareSafeWorkflow.markShareResult(true)
                } catch (_: Exception) {
                    shareSafeWorkflow.markShareResult(false)
                }
                PdfShareIntentResult.InvalidUri,
                PdfShareIntentResult.NoPdfAvailable,
                -> shareSafeWorkflow.markShareResult(false)
            }
        }
    }

    private fun launchCardCopyImport(side: CardCopySide) {
        if (!cardCopyWorkflow.beginAcquisition(side, CardCopyAcquisition.IMPORT)) return
        try {
            cardCopyImageLauncher.launch(arrayOf("image/jpeg", "image/png", "image/webp"))
        } catch (_: ActivityNotFoundException) {
            cardCopyWorkflow.onAcquisitionFailure()
        } catch (_: RuntimeException) {
            cardCopyWorkflow.onAcquisitionFailure()
        }
    }

    private fun handleCardCopyScannerResult(resultCode: Int, data: Intent?) {
        if (resultCode == Activity.RESULT_CANCELED) {
            cardCopyWorkflow.onAcquisitionResult(null)
            return
        }
        if (resultCode != Activity.RESULT_OK) {
            cardCopyWorkflow.onAcquisitionFailure()
            return
        }
        val uri = runCatching {
            GmsDocumentScanningResult.fromActivityResultIntent(data)?.pages?.singleOrNull()?.imageUri
        }.getOrNull()
        if (uri == null) cardCopyWorkflow.onAcquisitionFailure()
        else cardCopyWorkflow.onAcquisitionResult(uri)
    }

    private fun chooseCardCopyDestination() {
        if (cardCopyWorkflow.state !is CardCopyWorkflowState.Prepared) return
        if (!cardCopyWorkflow.markSavePickerOpen()) return
        try {
            createCardCopyPdfLauncher.launch(cardCopyFilename())
        } catch (_: ActivityNotFoundException) {
            cardCopyWorkflow.onSaveDestination(null)
        } catch (_: RuntimeException) {
            cardCopyWorkflow.onSaveDestination(null)
        }
    }

    private fun shareCardCopy() {
        val prepared = cardCopyWorkflow.preparedForShare() ?: run {
            cardCopyWorkflow.markShareFailed()
            return
        }
        val uri = cardCopyShareUri(this, prepared) ?: run {
            cardCopyWorkflow.markShareFailed()
            return
        }
        when (val share = createPdfShareIntent(uri)) {
            is PdfShareIntentResult.Success -> try {
                startActivity(Intent.createChooser(share.intent, getString(R.string.pdf_share_chooser_title)))
                cardCopyWorkflow.markShareInvoked()
            } catch (_: RuntimeException) {
                cardCopyWorkflow.markShareFailed()
            }
            PdfShareIntentResult.InvalidUri,
            PdfShareIntentResult.NoPdfAvailable,
            -> cardCopyWorkflow.markShareFailed()
        }
    }

    private data class ShareSafeOcrCapture(
        val result: OcrResult?,
        val sourceBound: Boolean,
    )

    private suspend fun captureTargetSizeEffectiveOcr(
        documentSession: DocumentSession,
    ): OcrResult? {
        val persisted = capturePersistedSearchablePdfOcr(documentSession)
            ?: return (ocrUiState as? OcrUiState.Success)?.result
        val pages = mutableListOf<OcrPageResult>()
        persisted.requests.forEachIndexed { pageIndex, request ->
            when (val provision = persisted.provider.provide(request)) {
                is EffectiveOcrPageProvision.Available -> pages += OcrPageResult(
                    pageIndex = pageIndex,
                    text = provision.page.effectiveText,
                    layout = provision.page.layout,
                )
                is EffectiveOcrPageProvision.CorrectedTextOnly -> pages += OcrPageResult(
                    pageIndex = pageIndex,
                    text = provision.effectiveText,
                    layout = null,
                )
                EffectiveOcrPageProvision.Stale,
                is EffectiveOcrPageProvision.Unavailable,
                -> Unit
            }
        }
        return pages.takeIf(List<OcrPageResult>::isNotEmpty)?.let(::OcrResult)
    }

    private suspend fun captureShareSafeEffectiveOcr(
        documentSession: DocumentSession,
    ): ShareSafeOcrCapture {
        val persisted = capturePersistedSearchablePdfOcr(documentSession)
            ?: return ShareSafeOcrCapture(
                result = (ocrUiState as? OcrUiState.Success)?.result,
                sourceBound = false,
            )
        val pages = mutableListOf<OcrPageResult>()
        persisted.requests.forEachIndexed { pageIndex, request ->
            when (val provision = persisted.provider.provide(request)) {
                is EffectiveOcrPageProvision.Available -> pages += OcrPageResult(
                    pageIndex = pageIndex,
                    text = provision.page.effectiveText,
                    layout = provision.page.layout,
                )
                is EffectiveOcrPageProvision.CorrectedTextOnly -> pages += OcrPageResult(
                    pageIndex = pageIndex,
                    text = provision.effectiveText,
                    layout = null,
                )
                EffectiveOcrPageProvision.Stale,
                is EffectiveOcrPageProvision.Unavailable,
                -> Unit
            }
        }
        return ShareSafeOcrCapture(
            result = pages.takeIf(List<OcrPageResult>::isNotEmpty)?.let(::OcrResult),
            sourceBound = true,
        )
    }

    private fun contentLength(uri: Uri): Long? = try {
        contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
            descriptor.length.takeIf { it >= 0L }
        }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun chooseTargetSizeDestination() {
        val prepared = targetSizeWorkflow.state as? TargetSizeWorkflowState.Prepared ?: return
        targetSizeWorkflow.markSavePickerOpen()
        try {
            createTargetSizePdfDocumentLauncher.launch(
                targetSizeFilename(prepared.input.summary.title),
            )
        } catch (_: ActivityNotFoundException) {
            targetSizeWorkflow.onSaveDestination(null)
        } catch (_: RuntimeException) {
            targetSizeWorkflow.onSaveDestination(null)
        }
    }

    private fun shareTargetSizePdf() {
        val prepared = targetSizeWorkflow.preparedForShare() ?: run {
            targetSizeWorkflow.markShareFailed()
            return
        }
        val uri = targetSizeShareUri(this, prepared) ?: run {
            targetSizeWorkflow.markShareFailed()
            return
        }
        when (val share = createPdfShareIntent(uri)) {
            is PdfShareIntentResult.Success -> try {
                startActivity(
                    Intent.createChooser(
                        share.intent,
                        getString(R.string.pdf_share_chooser_title),
                    ),
                )
                targetSizeWorkflow.markShareInvoked()
            } catch (_: ActivityNotFoundException) {
                targetSizeWorkflow.markShareFailed()
            } catch (_: SecurityException) {
                targetSizeWorkflow.markShareFailed()
            } catch (_: RuntimeException) {
                targetSizeWorkflow.markShareFailed()
            }
            PdfShareIntentResult.NoPdfAvailable,
            PdfShareIntentResult.InvalidUri,
            -> targetSizeWorkflow.markShareFailed()
        }
    }

    private fun openLocalPdfTools() {
        if (localPdfTools.state.visible) return
        val lease = session.acquireDocumentSessionLease(documentSessionLeaseReleaseObserverForTest) ?: return
        val plan = normalPdfExportPlan(lease.session)
        lifecycleScope.launch {
            try {
                localPdfTools.open(plan)
            } finally {
                session.releaseDocumentSessionLease(lease)
            }
        }
    }

    private fun handOffLocalPdfToolCopy(preview: Boolean) {
        val output = localPdfTools.beginHandoff() ?: return
        lifecycleScope.launch {
            val preparedOwner = AtomicReference<PdfSharePreparationResult.Ready?>()
            var handedOff = false
            var message: String? = null
            try {
                val prepared = withContext(Dispatchers.IO) {
                    preparePdfForSharing(this@MainActivity, Uri.fromFile(output)).also {
                        if (it is PdfSharePreparationResult.Ready) preparedOwner.set(it)
                    }
                }
                if (appLock.state.value.phase == AppLockPhase.LOCKED) {
                    message = "Unlock RME, then choose Preview or Share again."
                    return@launch
                }
                if (prepared !is PdfSharePreparationResult.Ready) {
                    message = "The prepared PDF could not be opened."
                    return@launch
                }
                val intent = if (preview) {
                    Intent(Intent.ACTION_VIEW).setDataAndType(prepared.uri, "application/pdf")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        .apply { clipData = android.content.ClipData.newRawUri("PDF", prepared.uri) }
                } else {
                    val result = createPdfShareIntent(prepared.uri)
                    if (result !is PdfShareIntentResult.Success) return@launch
                    Intent.createChooser(result.intent, getString(R.string.pdf_share_chooser_title))
                }
                startActivity(intent)
                handedOff = true
                preparedOwner.set(null)
            } catch (_: CancellationException) {
                // The private hand-off copy is released below if Android never received it.
            } catch (_: ActivityNotFoundException) {
                message = "No application is available for this PDF."
            } catch (_: Exception) {
                message = "The PDF could not be opened."
            } finally {
                if (!handedOff) preparedOwner.getAndSet(null)?.let(::discardPreparedPdfShare)
                localPdfTools.finishHandoff(output, message)
            }
        }
    }

    private suspend fun workflowOcr(document:DocumentSession):OcrResult? = try { captureTargetSizeEffectiveOcr(document) } catch(cancelled:kotlinx.coroutines.CancellationException) { throw cancelled } catch(_:Exception) { null }

    private fun openPptx() {
        if(appLock.state.value.phase==AppLockPhase.LOCKED||pptx.visible||!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))return
        val document=session;val revision=document.documentRevision;val owner=document.documentOwnerToken
        val lease=document.acquireDocumentSessionLease()?:return
        pptx.foreground(true)
        pptx.open(lease.session,revision,owner,
            current={document.documentRevision==revision&&document.documentOwnerToken==owner&&appLock.state.value.phase!=AppLockPhase.LOCKED&&lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)},
            release={document.releaseDocumentSessionLease(lease)},ocrProvider={workflowOcr(lease.session)})
    }

    private fun openIntegrity(){
        if(appLock.state.value.phase==AppLockPhase.LOCKED||integrity.visible)return
        val revision=session.documentRevision;val owner=session.documentOwnerToken
        integrity.open{session.documentRevision==revision&&session.documentOwnerToken==owner&&appLock.state.value.phase!=AppLockPhase.LOCKED}
    }
    private fun launchIntegrityInput(baseline:Boolean){
        val ticket=integrity.beginPick(baseline)?:return
        val launcher=activityResultRegistry.register("integrity-input:"+integrity.owner+":"+ticket,ActivityResultContracts.OpenDocument()){uri->
            val prior=integrityInputs.remove(ticket);integrity.foreground(appLock.state.value.phase!=AppLockPhase.LOCKED)
            if(appLock.state.value.phase==AppLockPhase.LOCKED)integrity.close()
            integrity.selected(ticket,uri);prior?.unregister()
        };integrityInputs[ticket]=launcher
        try{launcher.launch(arrayOf("application/pdf"))}catch(_:Exception){integrityInputs.remove(ticket)?.unregister();integrity.selected(ticket,null)}
    }
    private fun launchIntegritySave(){
        val name=integrity.beginSave()?:return;val ticket=integrity.saveTicket?:return
        val launcher=activityResultRegistry.register("integrity-export:"+integrity.owner+":"+ticket,ActivityResultContracts.CreateDocument("text/plain")){uri->
            val prior=integritySaves.remove(ticket);integrity.foreground(appLock.state.value.phase!=AppLockPhase.LOCKED)
            if(appLock.state.value.phase==AppLockPhase.LOCKED)integrity.close()
            integrity.destination(ticket,uri);prior?.unregister()
        };integritySaves[ticket]=launcher
        try{launcher.launch(name)}catch(_:Exception){integritySaves.remove(ticket)?.unregister();integrity.destination(ticket,null)}
    }
    private fun openPhoto(index:Int){
        if(appLock.state.value.phase==AppLockPhase.LOCKED||photo.visible)return
        val document=session;val revision=document.documentRevision;val owner=document.documentOwnerToken
        val lease=document.acquireDocumentSessionLease()?:return
        val page=lease.session.pages.getOrNull(index)?:run{document.releaseDocumentSessionLease(lease);return}
        photo.open(page,lease.session.originalPdfSources+lease.session.pages.map{it.source},
            {document.documentRevision==revision&&document.documentOwnerToken==owner&&document.documentSession.pages.getOrNull(index)===page&&appLock.state.value.phase!=AppLockPhase.LOCKED},
            {document.releaseDocumentSessionLease(lease)})
    }
    private fun launchPhotoSave(){
        val name=photo.beginSave()?:return;val ticket=photo.saveTicket?:return
        val launcher=activityResultRegistry.register("photo-export:"+photo.owner+":"+ticket,ActivityResultContracts.CreateDocument("image/png")){uri->
            val prior=photoSaves.remove(ticket);photo.foreground(appLock.state.value.phase!=AppLockPhase.LOCKED)
            if(appLock.state.value.phase==AppLockPhase.LOCKED)photo.close()
            photo.destination(ticket,uri);prior?.unregister()
        };photoSaves[ticket]=launcher
        try{launcher.launch(name)}catch(_:Exception){photoSaves.remove(ticket)?.unregister();photo.destination(ticket,null)}
    }
    private fun openAssistance(){
        if(appLock.state.value.phase==AppLockPhase.LOCKED||assistance.visible)return
        val document=session;val revision=document.documentRevision;val owner=document.documentOwnerToken
        val lease=document.acquireDocumentSessionLease()?:return
        assistance.open(lease.session,revision,{document.documentRevision==revision&&document.documentOwnerToken==owner&&appLock.state.value.phase!=AppLockPhase.LOCKED},{document.releaseDocumentSessionLease(lease)}){workflowOcr(lease.session)}
    }
    private fun launchAssistanceSave(pdf:Boolean){
        val name=assistance.beginSave(pdf)?:return;val ticket=assistance.saveTicket?:return
        val launcher=activityResultRegistry.register("assistance-export:"+assistance.owner+":"+ticket,ActivityResultContracts.CreateDocument(if(pdf)"application/pdf"else"text/plain")){uri->
            val prior=assistanceSaves.remove(ticket);assistance.foreground(appLock.state.value.phase!=AppLockPhase.LOCKED)
            if(appLock.state.value.phase==AppLockPhase.LOCKED)assistance.close()
            assistance.destination(ticket,uri);prior?.unregister()
        };assistanceSaves[ticket]=launcher
        try{launcher.launch(name)}catch(_:Exception){assistanceSaves.remove(ticket)?.unregister();assistance.destination(ticket,null)}
    }
    private fun launchTranslationSave(pdf:Boolean){
        val name=(if(pdf)translation.beginPdfSave()else translation.beginSave())?:return
        val ticket=translation.saveTicket?:return
        val launcher=activityResultRegistry.register("translation-export:"+translation.owner+":"+ticket,ActivityResultContracts.CreateDocument(if(pdf)"application/pdf" else "text/plain")){uri->
            val prior=translationSaves.remove(ticket)
            translation.foreground(appLock.state.value.phase!=AppLockPhase.LOCKED)
            if(appLock.state.value.phase==AppLockPhase.LOCKED)translation.close()
            translation.destination(ticket,uri);prior?.unregister()
        }
        translationSaves[ticket]=launcher
        try{launcher.launch(name)}catch(_:Exception){translationSaves.remove(ticket)?.unregister();translation.destination(ticket,null)}
    }

    private fun openTranslation(){
        if(appLock.state.value.phase==AppLockPhase.LOCKED || translation.visible)return
        val document=session;val revision=document.documentRevision;val owner=document.documentOwnerToken
        val lease=document.acquireDocumentSessionLease()?:return
        translation.open(lease.session,revision,{document.documentRevision==revision&&document.documentOwnerToken==owner&&appLock.state.value.phase!=AppLockPhase.LOCKED},{document.releaseDocumentSessionLease(lease)}){workflowOcr(lease.session)}
    }

    private fun openDocx() = openDocx(false)

    private fun openDocx(fromRecipe:Boolean) {
        if(appLock.state.value.phase==AppLockPhase.LOCKED || docx.visible || !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))return
        val document=session;val revision=document.documentRevision;val owner=document.documentOwnerToken
        val ticket=if(fromRecipe)workflows.beginDocx(revision,owner)?:return else null
        val lease=document.acquireDocumentSessionLease()?:return
        docx.foreground(true)
        docx.open(lease.session,null,revision,owner,
            current={document.documentRevision==revision && document.documentOwnerToken==owner && appLock.state.value.phase!=AppLockPhase.LOCKED && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)},
            release={document.releaseDocumentSessionLease(lease)},recipeName=ticket?.name,
            ocrProvider={workflowOcr(lease.session)})
    }

    private fun captureWorkflowDocument(password:CharArray) {
        // M35 captures a separate reviewed Office PDF, never the current library document.
        if(workflows.draft?.preset==org.synapseworks.pageharbor.workflow.WorkflowPreset.OFFICE) {
            password.fill('\u0000')
            if(appLock.state.value.phase==AppLockPhase.LOCKED)return
            val ticket=workflows.beginOffice()?:return
            officeRecipeTicket=ticket
            try { officeRecipeLauncher.launch(Intent(this,org.synapseworks.pageharbor.office.OfficeImportActivity::class.java).putExtra(org.synapseworks.pageharbor.office.OfficeRecipeOffers.REQUEST,ticket.request)) }
            catch(_:Exception){officeRecipeTicket=null}
            return
        }
        if(workflows.draft?.preset==org.synapseworks.pageharbor.workflow.WorkflowPreset.PPTX){password.fill('\u0000');if(appLock.state.value.phase!=AppLockPhase.LOCKED)openPptx();return}
        if(workflows.draft?.preset==org.synapseworks.pageharbor.workflow.WorkflowPreset.XLSX){password.fill('\u0000');if(appLock.state.value.phase!=AppLockPhase.LOCKED){xlsx.foreground(true);xlsx.openEmpty()};return}
        if(workflows.draft?.preset==org.synapseworks.pageharbor.workflow.WorkflowPreset.DOCX){password.fill('\u0000');openDocx(true);return}
        if(appLock.state.value.phase==AppLockPhase.LOCKED){password.fill('\u0000');return}
        val document=session;val revision=document.documentRevision;val owner=document.documentOwnerToken
        val lease=document.acquireDocumentSessionLease()?:run{password.fill('\u0000');workflows.noDocument();return}
        lifecycleScope.launch {
            var handed=false
            try {
                val ocr=workflowOcr(lease.session)
                workflows.capture(lease.session,revision,owner,ocr,password,
                    current={document.documentRevision==revision && document.documentOwnerToken==owner && appLock.state.value.phase!=AppLockPhase.LOCKED},
                    release={document.releaseDocumentSessionLease(lease)})
                handed=true
            } finally { password.fill('\u0000');if(!handed)document.releaseDocumentSessionLease(lease) }
        }
    }

    private fun openPasswordPdfExport() {
        if (passwordPdfExport.state.visible || session.documentPages.isEmpty() ||
            appLock.state.value.phase == AppLockPhase.LOCKED) return
        val lease = session.acquireDocumentSessionLease(documentSessionLeaseReleaseObserverForTest) ?: return
        val plan = normalPdfExportPlan(lease.session)
        lifecycleScope.launch {
            try { passwordPdfExport.open(plan) }
            finally { session.releaseDocumentSessionLease(lease) }
        }
    }

    private fun choosePasswordPdfDestination() {
        if (appLock.state.value.phase == AppLockPhase.LOCKED) return
        val request = passwordPdfExport.createSaveRequest() ?: return
        try {
            if (protectedActions.markPasswordPdfSavePickerLaunched(request)) {
                createPasswordPdfLauncher.launch("RME-protected.pdf")
            } else {
                passwordPdfExport.save(null, request) { false }
                passwordPdfExport.showMessage(R.string.protect_previous_picker)
            }
        } catch (_: ActivityNotFoundException) {
            protectedActions.consumePasswordPdfSavePickerRequest()
            passwordPdfExport.save(null, request) { false }
            passwordPdfExport.showMessage(R.string.protect_no_picker)
        }
    }

    private fun sharePasswordPdf() {
        if (appLock.state.value.phase == AppLockPhase.LOCKED) return
        val handoff = passwordPdfExport.beginHandoff() ?: return
        lifecycleScope.launch {
            val preparedOwner = AtomicReference<PdfSharePreparationResult.Ready?>()
            var handedOff = false
            var message: Int? = null
            try {
                val prepared = withContext(Dispatchers.IO) {
                    preparePdfForSharing(this@MainActivity, Uri.fromFile(handoff.output)).also {
                        if (it is PdfSharePreparationResult.Ready) preparedOwner.set(it)
                    }
                }
                if (!passwordPdfExport.isCurrent(handoff)) return@launch
                if (appLock.state.value.phase == AppLockPhase.LOCKED) {
                    message = R.string.protect_unlock_share
                    return@launch
                }
                if (prepared !is PdfSharePreparationResult.Ready) {
                    message = R.string.protect_share_failure
                    return@launch
                }
                val intent = createPdfShareIntent(prepared.uri)
                if (intent !is PdfShareIntentResult.Success) return@launch
                startActivity(Intent.createChooser(intent.intent, getString(R.string.pdf_share_chooser_title)))
                handedOff = true
                preparedOwner.set(null)
            } catch (_: CancellationException) {
                // The undelivered private handoff copy is released below.
            } catch (_: Exception) {
                message = R.string.protect_share_failure
            } finally {
                if (!handedOff) preparedOwner.getAndSet(null)?.let(::discardPreparedPdfShare)
                passwordPdfExport.finishHandoff(handoff, message)
            }
        }
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

    private fun clearStaleOcrAndSearchablePdfOperations(documentRevision: Long) {
        if (ocrOperationTracker.invalidateIfDocumentRevisionChanged(documentRevision)) {
            ocrJob?.cancel()
            ocrJob = null
            ocrUiState = clearedOcrState()
            ocrSelectedPageIndex = 0
        }
        if (searchablePdfOperationTracker.invalidateIfDocumentRevisionChanged(documentRevision)) {
            searchablePdfExportJob?.cancel()
            searchablePdfExportJob = null
            searchablePdfPreparedExport?.let(searchablePdfExportCoordinator::discardPreparedExport)
            searchablePdfPreparedExport = null
            searchablePdfSaveState = SearchablePdfSaveState.Idle
        }
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
                    adjustments = plan.adjustments,
                    rotation = plan.rotation,
                    imageMetadata = page.imageMetadata,
                    annotations = plan.annotations,
                )
            }
        }

    private fun writeFilteredScannedPage(
        sourceUri: Uri,
        destinationUri: Uri,
        filter: org.synapseworks.pageharbor.image.DocumentFilter,
        rotation: org.synapseworks.pageharbor.document.session.DocumentPageRotation,
        imageMetadata: org.synapseworks.pageharbor.document.session.DocumentImageMetadata,
        adjustments: ManualImageAdjustments,
        annotations: org.synapseworks.pageharbor.document.annotation.AnnotationLayer,
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
            adjustments = adjustments,
            annotations = annotations,
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
        integrityInputs.values.toList().forEach{it.unregister()};integrityInputs.clear()
        integritySaves.values.toList().forEach{it.unregister()};integritySaves.clear()
        photoSaves.values.toList().forEach{it.unregister()};photoSaves.clear()
        assistanceSaves.values.toList().forEach{it.unregister()};assistanceSaves.clear()
        translationSaves.values.toList().forEach{it.unregister()};translationSaves.clear()
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
        session.lastOcrScript = if (ocrResult != null) OcrScript.LATIN else null
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

    internal fun recognizeTextForTest(script: OcrScript) = recognizeText(script)

    internal fun saveSearchablePdfForTest() = saveSearchablePdf()

    internal fun chooseNormalPdfDestinationForTest() = choosePdfDestination()

    internal fun deliverNormalPdfDestinationForTest(uri: Uri?) =
        handleNormalPdfDestinationResult(uri)

    internal fun sharePdfForTest() = sharePdf()

    internal fun deleteLibraryDocumentForTest(documentId: String) = deleteLibraryDocument(documentId)

    internal fun exportPagesForTest() = exportPages()

    internal fun deliverPageDestinationForTest(uri: Uri?) = handlePageDestinationResult(uri)

    internal fun sessionScreenForTest(): PageHarborScreen = session.screen

    internal fun sessionSummaryForTest(): ScannerSpikeState = session.scannerState

    internal fun selectedOcrPageForTest(): Int = session.ocrSelectedPageIndex

    internal fun searchablePdfStateForTest(): SearchablePdfSaveState = session.searchablePdfSaveState

    internal fun ocrStateForTest(): OcrUiState = session.ocrUiState

    internal fun ocrReviewStateForTest():
        org.synapseworks.pageharbor.ocr.review.OcrReviewUiState = ocrReview.state.value

    internal fun closeOcrReviewForTest() = ocrReview.close()

    internal fun normalPdfStateForTest(): PdfSaveState = session.pdfSaveState

    internal fun pdfShareStateForTest(): PdfShareState = session.pdfShareState

    internal fun pageExportStateForTest(): PageExportState = session.pageExportState

    internal fun openTargetSizeWorkflowForTest(input: TargetSizeWorkflowInput) {
        targetSizeWorkflow.open(input)
    }

    internal fun targetSizeWorkflowStateForTest(): TargetSizeWorkflowState =
        targetSizeWorkflow.state

    internal fun cardCopyWorkflowForTest(): CardCopyWorkflowViewModel = cardCopyWorkflow

    internal fun cardCopyWorkflowStateForTest(): CardCopyWorkflowState = cardCopyWorkflow.state

    internal fun deliverTargetSizeDestinationForTest(uri: Uri?) =
        targetSizeWorkflow.onSaveDestination(uri)

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

private data class PersistedSearchablePdfOcr(
    val provider: EffectiveOcrPageProvider,
    val requests: List<EffectiveOcrPageRequest>,
)

private fun LibraryOcrPageSnapshot.toUnavailableOcrRequest(): EffectiveOcrPageRequest =
    EffectiveOcrPageRequest(
        address = OcrPageAddress(documentId, pageId),
        expectedCurrentness = OcrRecognitionCurrentness(
            inputFingerprintVersion = 1,
            inputFingerprint = "unavailable:$pageId",
            durable = OcrDurablePageCurrentness(
                documentContentRevision = documentContentRevision,
                pageVisualRevision = pageVisualRevision,
                ocrStateRevision = ocrStateRevision,
                activeArtifactRevision = activeArtifactRevision,
            ),
        ),
    )

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
