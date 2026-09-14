package org.synapseworks.pageharbor

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
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

class MainActivity : ComponentActivity() {
    private val session: PageHarborSessionViewModel by viewModels()
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
        if (result.resultCode == Activity.RESULT_CANCELED) {
            session.cancelScannerRequest()
            return@registerForActivityResult
        }

        if (result.resultCode != Activity.RESULT_OK) {
            session.failScannerRequest()
            return@registerForActivityResult
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
        if (uris.isEmpty()) {
            session.cancelImportRequest()
        } else {
            processImportedUris(
                uris = uris,
                imageSourceCategory = org.synapseworks.pageharbor.document.session.DocumentSourceCategory.SELECTED_IMAGE,
            )
        }
    }

    private val createPdfDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
        ::handleNormalPdfDestinationResult,
    )

    private val createPageDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("image/jpeg"),
        ::handlePageDestinationResult,
    )

    private val createSearchablePdfDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
    ) { destinationUri ->
        val preparedExport = searchablePdfPreparedExport
        if (destinationUri == null) {
            preparedExport?.let(searchablePdfExportCoordinator::discardPreparedExport)
            searchablePdfPreparedExport = null
            searchablePdfSaveState = if (preparedExport == null) {
                SearchablePdfSaveState.Idle
            } else {
                SearchablePdfSaveState.Cancelled
            }
            return@registerForActivityResult
        }
        // A result delivered after discard, scan replacement, or recreation owns no export.
        if (preparedExport == null) return@registerForActivityResult

        searchablePdfSaveState = SearchablePdfSaveState.Saving
        searchablePdfExportJob = lifecycleScope.launch {
            val result = searchablePdfExportCoordinator.writePreparedExport(preparedExport, destinationUri)
            searchablePdfPreparedExport = null
            searchablePdfSaveState = when (result) {
                SearchablePdfExportResult.Success -> SearchablePdfSaveState.Saved
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
        setContent {
            PageHarborApp(
                screen = session.screen,
                onScreenChange = { target ->
                    if (target == PageHarborScreen.ScanResult) {
                        session.returnToScanResult()
                    } else {
                        session.screen = target
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
                importUiState = session.importUiState,
                onOcrSelectedPageChange = { ocrSelectedPageIndex = it },
                onPageFilterChange = session::setPageFilter,
                onPageRotate = session::rotatePageClockwise,
                onPageMove = session::movePage,
                onPageRemove = session::removePage,
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
                onClearScanResult = {
                    clearRecognizedText()
                    clearSearchablePdfSave()
                    clearNormalDocumentOperations()
                    session.clearScan()
                },
            )
        }
        if (savedInstanceState == null) handleInboundIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleInboundIntent(intent)
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
        when (val input = extractInboundShareInput(intent)) {
            InboundShareInput.NotShareIntent -> Unit
            is InboundShareInput.Failure -> session.failImportRequest(input.reason)
            is InboundShareInput.Ready -> {
                if (!session.beginImportRequest(DocumentImportOrigin.INBOUND_SHARE)) return
                processImportedUris(
                    uris = input.uris,
                    imageSourceCategory =
                        org.synapseworks.pageharbor.document.session.DocumentSourceCategory.INBOUND_SHARE,
                )
            }
        }
    }

    private fun processImportedUris(
        uris: List<Uri>,
        imageSourceCategory: org.synapseworks.pageharbor.document.session.DocumentSourceCategory,
    ) {
        importJob?.cancel()
        session.beginImportProcessing(uris.size)
        val capacity = session.remainingPageCapacity()
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
                    PdfExportResult.Success -> PdfSaveState.Saved
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
            Uri.parse(getString(R.string.source_code_url)),
        )

        try {
            startActivity(sourceIntent)
        } catch (_: ActivityNotFoundException) {
            // No browser is available. Keep the app stable and avoid logging local state.
        }
    }
}
