package org.synapseworks.pageharbor

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.synapseworks.pageharbor.document.PageExportState
import org.synapseworks.pageharbor.document.PdfSaveState
import org.synapseworks.pageharbor.document.PdfShareState
import org.synapseworks.pageharbor.document.searchablepdf.SearchablePdfSaveState
import org.synapseworks.pageharbor.document.session.AcquiredDocumentPage
import org.synapseworks.pageharbor.document.session.AcquiredResource
import org.synapseworks.pageharbor.document.session.DEFAULT_MAX_DOCUMENT_PAGES
import org.synapseworks.pageharbor.document.session.DocumentAcquisitionCoordinator
import org.synapseworks.pageharbor.document.session.DocumentAcquisitionError
import org.synapseworks.pageharbor.document.session.DocumentAcquisitionInput
import org.synapseworks.pageharbor.document.session.DocumentAcquisitionMode
import org.synapseworks.pageharbor.document.session.DocumentAcquisitionResult
import org.synapseworks.pageharbor.document.session.DocumentAcquisitionToken
import org.synapseworks.pageharbor.document.session.DocumentImageMetadata
import org.synapseworks.pageharbor.document.session.DocumentPage
import org.synapseworks.pageharbor.document.session.DocumentPageId
import org.synapseworks.pageharbor.document.session.DocumentSession
import org.synapseworks.pageharbor.document.session.DocumentSourceCategory
import org.synapseworks.pageharbor.document.session.PendingResourceRegistrationResult
import org.synapseworks.pageharbor.document.session.toAndroidUri
import org.synapseworks.pageharbor.image.DocumentFilter
import org.synapseworks.pageharbor.ocr.OcrUiState
import org.synapseworks.pageharbor.scanner.ScannerSpikeState
import org.synapseworks.pageharbor.scanner.createScannerResultSummary
import org.synapseworks.pageharbor.ui.PageHarborScreen

/** The shared active-document limit used by scan and every future acquisition source. */
internal const val MAX_DOCUMENT_PAGES = DEFAULT_MAX_DOCUMENT_PAGES

/** Whether the external scanner is acquiring a first document or pages for the active document. */
internal enum class ScannerRequestMode {
    INITIAL_SCAN,
    ADD_PAGES,
}

internal data class DocumentSessionLease(
    val id: Long,
    val session: DocumentSession,
    internal val releaseObserverForTest: (() -> Unit)? = null,
)

/**
 * Retains only the current in-memory document session while Android recreates MainActivity.
 * It deliberately has no saved-state handle: process-death recovery is unsupported.
 */
class PageHarborSessionViewModel internal constructor(
    private val acquisitionCoordinator: DocumentAcquisitionCoordinator,
) : ViewModel() {
    constructor() : this(DocumentAcquisitionCoordinator())

    var screen: PageHarborScreen by mutableStateOf(PageHarborScreen.Home)
    var scannerState: ScannerSpikeState by mutableStateOf(ScannerSpikeState.Idle)
    var documentSession: DocumentSession by mutableStateOf(DocumentSession())
        private set
    private val mutableDocumentRevision = MutableStateFlow(0L)
    internal val documentRevisionChanges: StateFlow<Long> = mutableDocumentRevision.asStateFlow()
    internal val documentRevision: Long
        get() = mutableDocumentRevision.value
    val documentPages: List<DocumentPage>
        get() = documentSession.pages
    val scannedPageUris: List<Uri>
        get() = documentPages.map { page -> page.source.toAndroidUri() }
    val scannedPdfUri: Uri?
        get() = documentSession.directPdfSource?.toAndroidUri()
    var pdfSaveState: PdfSaveState by mutableStateOf(PdfSaveState.Idle)
    var pdfShareState: PdfShareState by mutableStateOf(PdfShareState.Idle)
    var pageExportState: PageExportState by mutableStateOf(PageExportState.Idle)
    var ocrUiState: OcrUiState by mutableStateOf(OcrUiState.Idle)
    var ocrSelectedPageIndex: Int by mutableStateOf(0)
    var searchablePdfSaveState: SearchablePdfSaveState by mutableStateOf(SearchablePdfSaveState.Idle)
    var lastAcquisitionError: DocumentAcquisitionError? by mutableStateOf(null)
        private set
    private var activeScannerRequest: Pair<ScannerRequestMode, DocumentAcquisitionToken>? = null
    private var nextDocumentSessionLeaseId = 0L
    private val activeDocumentSessionLeases = mutableMapOf<Long, DocumentSession>()
    private val deferredReleaseSessions = mutableListOf<DocumentSession>()

    internal fun beginScannerRequest(): ScannerRequestMode? {
        if (activeScannerRequest != null) return null
        if (scannerState is ScannerSpikeState.ResultSummary && remainingPageCapacity() == 0) {
            return null
        }
        val requestMode = if (scannerState is ScannerSpikeState.ResultSummary) {
            ScannerRequestMode.ADD_PAGES
        } else {
            ScannerRequestMode.INITIAL_SCAN
        }
        val acquisitionMode = when (requestMode) {
            ScannerRequestMode.INITIAL_SCAN -> DocumentAcquisitionMode.REPLACE
            ScannerRequestMode.ADD_PAGES -> DocumentAcquisitionMode.APPEND
        }
        val token = acquisitionCoordinator.begin(acquisitionMode) ?: return null
        activeScannerRequest = requestMode to token
        lastAcquisitionError = null
        if (requestMode == ScannerRequestMode.INITIAL_SCAN) {
            scannerState = ScannerSpikeState.Preparing
        }
        return requestMode
    }

    internal fun remainingPageCapacity(): Int =
        (acquisitionCoordinator.limits.maxPages - activePageCount()).coerceAtLeast(0)

    fun cancelScannerRequest() {
        val request = activeScannerRequest ?: return
        acquisitionCoordinator.cancel(request.second, documentSession)
        activeScannerRequest = null
        lastAcquisitionError = null
        when (request.first) {
            ScannerRequestMode.INITIAL_SCAN -> scannerState = ScannerSpikeState.Cancelled
            ScannerRequestMode.ADD_PAGES -> screen = PageHarborScreen.ScanResult
        }
    }

    /** Registers adapter-owned staging files immediately so lifecycle invalidation can release them. */
    internal fun registerPendingAcquisitionResources(
        resources: List<AcquiredResource>,
    ): PendingResourceRegistrationResult {
        val token = activeScannerRequest?.second
            ?: return PendingResourceRegistrationResult.StaleToken
        return acquisitionCoordinator.registerPendingResources(token, documentSession, resources)
    }

    fun failScannerRequest() {
        failActiveScannerRequest(DocumentAcquisitionError.SOURCE_UNAVAILABLE)
    }

    fun completeScannerRequestWithoutResult() {
        failActiveScannerRequest(DocumentAcquisitionError.EMPTY_INPUT)
    }

    /** Existing scanner output enters the shared boundary used by every future acquisition source. */
    fun completeScannerRequest(
        scannerState: ScannerSpikeState.ResultSummary,
        scannedPdfUri: Uri?,
        scannedPageUris: List<Uri>,
        scannedPageMetadata: List<DocumentImageMetadata> =
            List(scannedPageUris.size) { DocumentImageMetadata() },
    ) {
        require(scannedPageMetadata.size == scannedPageUris.size)
        completeScannerReferences(
            scannerState = scannerState,
            scannedPdfReference = scannedPdfUri?.toString(),
            scannedPageReferences = scannedPageUris.map(Uri::toString),
            scannedPageMetadata = scannedPageMetadata,
        )
    }

    fun returnToScanResult() {
        if (scannerState is ScannerSpikeState.ResultSummary) {
            screen = PageHarborScreen.ScanResult
        }
    }

    /** Restore/test seam that still delegates to the shared acquisition coordinator. */
    fun replaceScan(
        scannerState: ScannerSpikeState.ResultSummary,
        scannedPdfUri: Uri?,
        scannedPageUris: List<Uri>,
    ) {
        replaceScannerReferencesForTest(
            scannerState = scannerState,
            scannedPdfReference = scannedPdfUri?.toString(),
            scannedPageReferences = scannedPageUris.map(Uri::toString),
        )
    }

    internal fun replaceScannerReferencesForTest(
        scannerState: ScannerSpikeState.ResultSummary,
        scannedPdfReference: String? = null,
        scannedPageReferences: List<String>,
    ) {
        if (activeScannerRequest != null) return
        val token = acquisitionCoordinator.begin(DocumentAcquisitionMode.REPLACE) ?: return
        activeScannerRequest = ScannerRequestMode.INITIAL_SCAN to token
        completeScannerReferences(scannerState, scannedPdfReference, scannedPageReferences)
    }

    internal fun completeScannerReferencesForTest(
        scannerState: ScannerSpikeState.ResultSummary,
        scannedPdfReference: String? = null,
        scannedPageReferences: List<String>,
        scannedPageMetadata: List<DocumentImageMetadata> =
            List(scannedPageReferences.size) { DocumentImageMetadata() },
    ) {
        completeScannerReferences(
            scannerState,
            scannedPdfReference,
            scannedPageReferences,
            scannedPageMetadata,
        )
    }

    internal fun installDocumentSessionForTest(session: DocumentSession) {
        replaceDocumentSession(session)
        scannerState = createScannerResultSummary(
            jpegPageCount = session.pages.size,
            pdfPageCount = null,
        )
        screen = PageHarborScreen.ScanResult
    }

    fun clearScan() {
        activeScannerRequest?.let { (_, token) ->
            acquisitionCoordinator.interrupt(token, documentSession)
        }
        activeScannerRequest = null
        val detachedSession = documentSession
        documentSession = DocumentSession()
        advanceDocumentRevision()
        if (activeDocumentSessionLeases.isEmpty()) {
            acquisitionCoordinator.releaseDetachedSessions(
                sessions = listOf(detachedSession),
                preservingSession = documentSession,
            )
        } else {
            deferredReleaseSessions += detachedSession
        }
        screen = PageHarborScreen.Home
        scannerState = ScannerSpikeState.Idle
        lastAcquisitionError = null
        ocrUiState = OcrUiState.Idle
        ocrSelectedPageIndex = 0
        resetTransientState()
    }

    /** Keeps RME-owned sources alive until one Activity-owned document operation finishes. */
    internal fun acquireDocumentSessionLease(
        releaseObserverForTest: (() -> Unit)? = null,
    ): DocumentSessionLease? {
        if (documentSession.pages.isEmpty()) return null
        val lease = DocumentSessionLease(
            id = ++nextDocumentSessionLeaseId,
            session = documentSession,
            releaseObserverForTest = releaseObserverForTest,
        )
        activeDocumentSessionLeases[lease.id] = lease.session
        return lease
    }

    internal fun releaseDocumentSessionLease(lease: DocumentSessionLease) {
        if (activeDocumentSessionLeases.remove(lease.id) == null) return
        releaseDeferredSessionsWhenSafe()
        lease.releaseObserverForTest?.invoke()
    }

    /** Active work is Activity-owned and is cancelled by the Activity; completed data remains. */
    fun resetTransientStateForRecreation() {
        if (ocrUiState == OcrUiState.Recognizing) {
            ocrUiState = OcrUiState.Idle
        }
        resetTransientState()
        screen = when {
            screen == PageHarborScreen.OcrResult && ocrUiState is OcrUiState.Success -> {
                PageHarborScreen.OcrResult
            }

            scannerState is ScannerSpikeState.ResultSummary -> PageHarborScreen.ScanResult
            else -> PageHarborScreen.Home
        }
    }

    fun setPageFilter(pageId: Long, filter: DocumentFilter): Boolean {
        val updated = documentSession.setFilter(DocumentPageId(pageId), filter) ?: return false
        return replaceDocumentSession(updated)
    }

    fun rotatePageClockwise(pageId: Long): Boolean {
        val updated = documentSession.rotateClockwise(DocumentPageId(pageId)) ?: return false
        if (!replaceDocumentSession(updated)) return false
        invalidatePageOrderDependentState()
        return true
    }

    fun reorderPages(pageIds: List<Long>): Boolean {
        val updated = documentSession.reorder(pageIds.map(::DocumentPageId)) ?: return false
        if (!replaceDocumentSession(updated)) return false
        invalidatePageOrderDependentState()
        return true
    }

    override fun onCleared() {
        activeScannerRequest?.let { (_, token) ->
            acquisitionCoordinator.interrupt(token, documentSession)
        }
        activeScannerRequest = null
        deferredReleaseSessions += documentSession
        documentSession = DocumentSession()
        releaseDeferredSessionsWhenSafe()
        super.onCleared()
    }

    private fun releaseDeferredSessionsWhenSafe() {
        if (activeDocumentSessionLeases.isNotEmpty() || deferredReleaseSessions.isEmpty()) return
        val sessions = deferredReleaseSessions.toList()
        deferredReleaseSessions.clear()
        acquisitionCoordinator.releaseDetachedSessions(
            sessions = sessions,
            preservingSession = documentSession,
        )
    }

    private fun completeScannerReferences(
        scannerState: ScannerSpikeState.ResultSummary,
        scannedPdfReference: String?,
        scannedPageReferences: List<String>,
        scannedPageMetadata: List<DocumentImageMetadata> =
            List(scannedPageReferences.size) { DocumentImageMetadata() },
    ) {
        require(scannedPageMetadata.size == scannedPageReferences.size)
        val request = activeScannerRequest
        if (request == null) {
            lastAcquisitionError = DocumentAcquisitionError.INTERRUPTED
            return
        }
        val previousSummary = this.scannerState as? ScannerSpikeState.ResultSummary
        activeScannerRequest = null
        val input = DocumentAcquisitionInput(
            pages = scannedPageReferences.mapIndexed { index, reference ->
                AcquiredDocumentPage(
                    resource = AcquiredResource(reference),
                    sourceCategory = DocumentSourceCategory.SCAN,
                    contentType = "image/jpeg",
                    imageMetadata = scannedPageMetadata[index],
                )
            },
            directPdfSource = scannedPdfReference?.let(::AcquiredResource),
        )
        val previousSession = documentSession
        val deferReplacedSessionCleanup =
            request.first == ScannerRequestMode.INITIAL_SCAN && activeDocumentSessionLeases.isNotEmpty()
        when (
            val result = acquisitionCoordinator.complete(
                token = request.second,
                currentSession = previousSession,
                input = input,
                deferReplacedSessionCleanup = deferReplacedSessionCleanup,
            )
        ) {
            is DocumentAcquisitionResult.Success -> {
                if (deferReplacedSessionCleanup) deferredReleaseSessions += previousSession
                replaceDocumentSession(result.session)
                lastAcquisitionError = null
                this.scannerState = when (request.first) {
                    ScannerRequestMode.INITIAL_SCAN -> scannerState
                    ScannerRequestMode.ADD_PAGES -> createScannerResultSummary(
                        jpegPageCount = result.session.pages.size,
                        pdfPageCount = previousSummary?.pdfPageCount,
                    )
                }
                ocrUiState = OcrUiState.Idle
                ocrSelectedPageIndex = 0
                resetTransientState()
                screen = PageHarborScreen.ScanResult
            }

            DocumentAcquisitionResult.Cancelled -> applyScannerFailure(request.first, null)
            is DocumentAcquisitionResult.Failure -> applyScannerFailure(request.first, result.reason)
        }
    }

    private fun failActiveScannerRequest(reason: DocumentAcquisitionError) {
        val request = activeScannerRequest ?: return
        acquisitionCoordinator.fail(request.second, documentSession, reason = reason)
        activeScannerRequest = null
        applyScannerFailure(request.first, reason)
    }

    private fun applyScannerFailure(mode: ScannerRequestMode, error: DocumentAcquisitionError?) {
        lastAcquisitionError = error
        when (mode) {
            ScannerRequestMode.INITIAL_SCAN -> scannerState = ScannerSpikeState.Error
            ScannerRequestMode.ADD_PAGES -> screen = PageHarborScreen.ScanResult
        }
    }

    private fun invalidatePageOrderDependentState() {
        ocrUiState = OcrUiState.Idle
        ocrSelectedPageIndex = 0
        resetTransientState()
    }

    private fun replaceDocumentSession(updated: DocumentSession): Boolean {
        if (updated == documentSession) return false
        documentSession = updated
        advanceDocumentRevision()
        return true
    }

    private fun advanceDocumentRevision() {
        mutableDocumentRevision.update { revision -> revision + 1L }
    }

    private fun resetTransientState() {
        pdfSaveState = PdfSaveState.Idle
        pdfShareState = PdfShareState.Idle
        pageExportState = PageExportState.Idle
        searchablePdfSaveState = SearchablePdfSaveState.Idle
    }

    private fun activePageCount(): Int = maxOf(
        documentSession.pages.size,
        (scannerState as? ScannerSpikeState.ResultSummary)?.jpegPageCount ?: 0,
    )
}
