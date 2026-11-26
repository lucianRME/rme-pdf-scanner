package org.synapseworks.pageharbor

import android.net.Uri
import kotlinx.coroutines.*
import org.synapseworks.pageharbor.document.history.*
import org.synapseworks.pageharbor.image.ManualImageAdjustments
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.synapseworks.pageharbor.document.batch.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import org.synapseworks.pageharbor.document.PageExportState
import org.synapseworks.pageharbor.document.PdfSaveState
import org.synapseworks.pageharbor.document.PdfShareState
import org.synapseworks.pageharbor.document.searchablepdf.SearchablePdfSaveState
import org.synapseworks.pageharbor.document.importing.DocumentImportError
import org.synapseworks.pageharbor.document.importing.DocumentImportOrigin
import org.synapseworks.pageharbor.document.importing.DocumentImportPreparationResult
import org.synapseworks.pageharbor.document.importing.DocumentImportUiState
import org.synapseworks.pageharbor.document.session.AcquiredDocumentPage
import org.synapseworks.pageharbor.document.session.AcquiredResource
import org.synapseworks.pageharbor.document.session.DEFAULT_MAX_DOCUMENT_PAGES
import org.synapseworks.pageharbor.document.session.DEFAULT_MAX_IMPORTED_DOCUMENT_PAGES
import org.synapseworks.pageharbor.document.session.DocumentAcquisitionCoordinator
import org.synapseworks.pageharbor.document.session.DocumentAcquisitionError
import org.synapseworks.pageharbor.document.session.DocumentAcquisitionInput
import org.synapseworks.pageharbor.document.session.DocumentAcquisitionMode
import org.synapseworks.pageharbor.document.session.DocumentAcquisitionResult
import org.synapseworks.pageharbor.document.session.DocumentAcquisitionToken
import org.synapseworks.pageharbor.document.session.DocumentImageMetadata
import org.synapseworks.pageharbor.document.session.DocumentPage
import org.synapseworks.pageharbor.document.session.DocumentPageId
import org.synapseworks.pageharbor.document.session.DocumentResourceOwnership
import org.synapseworks.pageharbor.document.session.DocumentSession
import org.synapseworks.pageharbor.document.session.DocumentSourceCategory
import org.synapseworks.pageharbor.document.session.LibraryDocumentReference
import org.synapseworks.pageharbor.document.session.PendingResourceRegistrationResult
import org.synapseworks.pageharbor.document.session.toAndroidUri
import org.synapseworks.pageharbor.image.DocumentFilter
import org.synapseworks.pageharbor.ocr.OcrUiState
import org.synapseworks.pageharbor.ocr.OcrScript
import org.synapseworks.pageharbor.scanner.ScannerSpikeState
import org.synapseworks.pageharbor.scanner.createScannerResultSummary
import org.synapseworks.pageharbor.ui.PageHarborScreen

/** Collection representability only; the app no longer imposes a 20-page document ceiling. */
internal const val MAX_DOCUMENT_PAGES = DEFAULT_MAX_DOCUMENT_PAGES

/** Whether the external scanner is acquiring a first document or pages for the active document. */
internal enum class ScannerRequestMode {
    INITIAL_SCAN,
    ADD_PAGES,
}

private enum class DocumentAcquisitionKind {
    SCANNER,
    CONTINUOUS,
    FILE_IMPORT,
    INBOUND_SHARE,
}

private data class ActiveDocumentAcquisition(
    val kind: DocumentAcquisitionKind,
    val mode: DocumentAcquisitionMode,
    val token: DocumentAcquisitionToken,
)

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
    // Stale-work guards and history frames compare the accepted session by identity.
    private var acceptedDocumentSession: DocumentSession by mutableStateOf(DocumentSession(), androidx.compose.runtime.referentialEqualityPolicy())
    var documentSession:DocumentSession
        get()=acceptedDocumentSession
        private set(value){acceptedDocumentSession=value;refreshLibraryReaderPins()}
    private val mutableDocumentRevision = MutableStateFlow(0L)
    internal val documentRevisionChanges: StateFlow<Long> = mutableDocumentRevision.asStateFlow()
    internal val documentRevision: Long
        get() = mutableDocumentRevision.value
    val batchEditor = SelectedPageBatchEditor(viewModelScope,
        current = { BatchDocumentCapture(documentRevision, documentSession) },
        commit = { expected, updated -> commitBatchEdit(expected, updated) })

    internal fun commitBatchEdit(expected: BatchDocumentCapture, updated: DocumentSession): Boolean {
        if (!historyMutationAllowed() || documentRevision != expected.revision || documentSession !== expected.session) return false
        return replaceDocumentSession(updated,HistoryAction.BATCH)
    }

    internal fun applyBookSplit(expected:DocumentSession,revision:Long,replacements:List<org.synapseworks.pageharbor.document.booklayout.BookPageReplacement>):Boolean {
        if(!historyMutationAllowed()||documentRevision!=revision||documentSession!==expected||activeAcquisition!=null)return false
        val updated=org.synapseworks.pageharbor.document.booklayout.bookSplitSession(expected,replacements)?:return false
        if(updated===expected)return true
        if(!replaceDocumentSession(updated,HistoryAction.BOOK_SPLIT))return false
        if(activeDocumentSessionLeases.isEmpty())acquisitionCoordinator.releaseDetachedSessions(listOf(expected),updated)
        else deferredReleaseSessions+=expected
        invalidatePageOrderDependentState()
        scannerState=createScannerResultSummary(updated.pages.size,null)
        return true
    }

    internal fun applyObjectRemoval(expected:DocumentSession,revision:Long,output:org.synapseworks.pageharbor.document.objectremoval.RemovalPrepared,ownerToken:String=documentOwnerToken,action:HistoryAction=HistoryAction.OBJECT_REMOVAL):Boolean {
        if(action !in setOf(HistoryAction.OBJECT_REMOVAL,HistoryAction.FINGER_REMOVAL))return false
        if(!historyMutationAllowed()||documentRevision!=revision||documentSession!==expected||activeAcquisition!=null||ownerToken!=documentOwnerToken)return false
        val publication=org.synapseworks.pageharbor.document.objectremoval.removalPublication(expected,output)?:return false
        val retainedBytes=output.sourceBytes*(if(output.original.source.ownership==DocumentResourceOwnership.RME_OWNED_TEMPORARY)2 else 1)
        if(retainedBytes>history.limits.temporaryBytes)return false
        val admission=DocumentHistory(history.limits).apply{reset(expected);record(expected,publication.after,action,publication.before)}
        if(admission.undoCount!=1)return false
        if(!replaceDocumentSession(publication.after,action,publication.before))return false
        invalidatePageOrderDependentState();scannerState=createScannerResultSummary(publication.after.pages.size,null)
        return true
    }

    internal fun applyCurvature(expected:DocumentSession,revision:Long,replacement:org.synapseworks.pageharbor.document.curvature.CurvatureReplacement):Boolean =
        applyPageGeometry(expected, revision, replacement.original, replacement.derived, replacement.original.filter, HistoryAction.CURVATURE)

    internal fun applyPageGeometry(
        expected: DocumentSession,
        revision: Long,
        page: DocumentPage,
        derived: org.synapseworks.pageharbor.document.booklayout.BookDerivedPage,
        filter: DocumentFilter,
        action: HistoryAction,
    ): Boolean {
        if(!historyMutationAllowed()||documentRevision!=revision||documentSession!==expected||activeAcquisition!=null)return false
        val updated=org.synapseworks.pageharbor.document.curvature.replacePageGeometry(expected,page,derived,filter)?:return false
        if (action == HistoryAction.CROP) {
            val admission = DocumentHistory(history.limits).apply {
                reset(expected)
                record(expected, updated, action)
            }
            if (admission.undoCount != 1) return false
        }
        if(!replaceDocumentSession(updated,action))return false
        if(activeDocumentSessionLeases.isEmpty())acquisitionCoordinator.releaseDetachedSessions(listOf(expected),updated)
        else deferredReleaseSessions+=expected
        invalidatePageOrderDependentState()
        scannerState=createScannerResultSummary(updated.pages.size,null)
        return true
    }

    internal fun applyCollage(expected:DocumentSession,revision:Long,output:org.synapseworks.pageharbor.document.collage.CollageOutput,ownerToken:String=documentOwnerToken):Boolean {
        if(!historyMutationAllowed()||documentRevision!=revision||documentSession!==expected||activeAcquisition!=null)return false
        if(ownerToken!=documentOwnerToken)return false
        val updated=org.synapseworks.pageharbor.document.collage.collageSession(expected,output)?:return false
        val admission=DocumentHistory().apply{reset(expected);record(expected,updated,HistoryAction.COLLAGE)}
        if(admission.undoCount!=1)return false
        if(!replaceDocumentSession(updated,HistoryAction.COLLAGE))return false
        invalidatePageOrderDependentState()
        scannerState=createScannerResultSummary(updated.pages.size,null)
        return true
    }

    /** Opaque in-memory owner identity; a restored UI cannot bind to a new process/session owner. */
    internal val documentOwnerToken: String = java.util.UUID.randomUUID().toString()

    fun applyReviewedShadowGlare(expected: DocumentPage, expectedRevision: Long, filter: DocumentFilter): Boolean {
        if (filter != DocumentFilter.ORIGINAL && filter !in setOf(DocumentFilter.SHADOW_GLARE_LOW_V1,
                DocumentFilter.SHADOW_GLARE_BALANCED_V1, DocumentFilter.SHADOW_GLARE_STRONG_V1)) return false
        if (documentRevision != expectedRevision || documentSession.pages.firstOrNull { it.id == expected.id } !== expected) return false
        if (expected.filter == filter) return true
        return setPageFilter(expected.id.value, filter)
    }

    fun applyReviewedMoire(expected: DocumentPage, expectedRevision: Long, filter: DocumentFilter, expectedOwner: String): Boolean {
        if(filter!=DocumentFilter.ORIGINAL&&filter !in setOf(DocumentFilter.MOIRE_LOW_V1,DocumentFilter.MOIRE_BALANCED_V1,DocumentFilter.MOIRE_STRONG_V1))return false
        if(documentOwnerToken!=expectedOwner||documentRevision!=expectedRevision||documentSession.pages.firstOrNull{it.id==expected.id}!==expected)return false
        if(expected.filter==filter)return true
        return setPageFilter(expected.id.value,filter)
    }

    fun applyReviewedCreaseStain(expected: DocumentPage, expectedRevision: Long, filter: DocumentFilter, expectedOwner: String): Boolean {
        if(filter!=DocumentFilter.ORIGINAL&&filter !in setOf(DocumentFilter.CREASE_STAIN_LOW_V1,DocumentFilter.CREASE_STAIN_BALANCED_V1,DocumentFilter.CREASE_STAIN_STRONG_V1))return false
        if(documentOwnerToken!=expectedOwner||documentRevision!=expectedRevision||documentSession.pages.firstOrNull{it.id==expected.id}!==expected)return false
        if(expected.filter==filter)return true
        return setPageFilter(expected.id.value,filter)
    }

    fun applyReviewedBleedThrough(expected: DocumentPage, expectedRevision: Long, filter: DocumentFilter, expectedOwner: String): Boolean {
        if(filter!=DocumentFilter.ORIGINAL&&filter !in setOf(DocumentFilter.BLEED_THROUGH_LOW_V1,DocumentFilter.BLEED_THROUGH_BALANCED_V1,DocumentFilter.BLEED_THROUGH_STRONG_V1))return false
        if(documentOwnerToken!=expectedOwner||documentRevision!=expectedRevision||documentSession.pages.firstOrNull{it.id==expected.id}!==expected)return false
        if(expected.filter==filter)return true
        return setPageFilter(expected.id.value,filter)
    }

    fun applyReviewedWhitening(expected: DocumentPage, expectedRevision: Long, filter: DocumentFilter): Boolean {
        if (filter != DocumentFilter.ORIGINAL && filter !in setOf(DocumentFilter.PAPER_WHITE_NEUTRAL_LOW_V1,
                DocumentFilter.PAPER_WHITE_NEUTRAL_BALANCED_V1,DocumentFilter.PAPER_WHITE_NEUTRAL_STRONG_V1,
                DocumentFilter.PAPER_WHITE_COLOUR_LOW_V1,DocumentFilter.PAPER_WHITE_COLOUR_BALANCED_V1,
                DocumentFilter.PAPER_WHITE_COLOUR_STRONG_V1)) return false
        if (documentRevision != expectedRevision || documentSession.pages.firstOrNull { it.id == expected.id } !== expected) return false
        if (expected.filter == filter) return true
        return setPageFilter(expected.id.value, filter)
    }

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
    var lastOcrScript: OcrScript? by mutableStateOf(null)
    var ocrSelectedPageIndex: Int by mutableIntStateOf(0)
    var openedPageRequestId: Long by mutableLongStateOf(0L)
        private set
    var openedPagePersistentId: String? by mutableStateOf(null)
        private set
    var searchablePdfSaveState: SearchablePdfSaveState by mutableStateOf(SearchablePdfSaveState.Idle)
    var lastAcquisitionError: DocumentAcquisitionError? by mutableStateOf(null)
        private set
    var importUiState: DocumentImportUiState by mutableStateOf(DocumentImportUiState.Idle)
        private set
    private var activeAcquisition: ActiveDocumentAcquisition? = null
    private var nextDocumentSessionLeaseId = 0L
    private val activeDocumentSessionLeases = mutableMapOf<Long, DocumentSession>()
    private val activeDocumentSessionLeaseCount = MutableStateFlow(0)
    private val deferredReleaseSessions = mutableListOf<DocumentSession>()
    private var history=DocumentHistory()
    private var historyResources:HistoryResources?=null
    private var historyAuditJob:Job?=null
    private var historyNavigationJob:Job?=null
    private var historyEpoch=0L
    private var historyActive=true
    private var historyDisposed=false
    private var historyMain:CoroutineDispatcher=Dispatchers.Main.immediate
    internal var historyState:HistoryUiState by mutableStateOf(HistoryUiState());private set

    internal fun configureHistory(access:HistorySourceAccess,limits:HistoryLimits=HistoryLimits(),main:CoroutineDispatcher=Dispatchers.Main.immediate) {
        if(historyResources!=null)return
        historyMain=main;history=DocumentHistory(limits);history.reset(documentSession)
        val runtime=HistoryResources(access,limits,acquisitionCoordinator::cleanupImmediately);historyResources=runtime
        acquisitionCoordinator.cleanupDelegate=runtime::cleanup
        batchEditor.undoCommitted={expected,done->
            if(history.undoAction!=HistoryAction.BATCH||documentSession!==expected.session||documentRevision!=expected.revision)false
            else navigateHistory(true,done)
        }
        batchEditor.cancelCommitted=::cancelHistoryNavigation
        batchEditor.externalUndoAvailable={history.undoAction==HistoryAction.BATCH&&history.undoCount>0&&!historyState.busy}
        historyChanged()
    }
    internal fun setHistoryActive(active:Boolean) {
        historyActive=active
        if(!active){historyEpoch++;historyNavigationJob?.cancel();historyNavigationJob=null;historyAuditJob?.cancel();historyAuditJob=null;publishHistory(false)}
        else if(historyResources!=null)historyChanged()
    }
    internal fun historyReady()=historyResources==null||(!historyState.busy&&historyActive)
    private fun historyMutationAllowed()=historyResources==null||(historyActive&&historyNavigationJob?.isActive!=true&&activeAcquisition==null)
    private fun refreshLibraryReaderPins(){
        org.synapseworks.pageharbor.library.recycle.LibrarySourceReaders.retain(this,history.sessions()+documentSession+activeDocumentSessionLeases.values)
    }
    private fun historyPins() {
        refreshLibraryReaderPins()
        historyResources?.retain(history.sessions()+documentSession+activeDocumentSessionLeases.values,acquisitionCoordinator.pendingHistoryResources())
    }
    private fun publishHistory(busy:Boolean,error:Boolean=false,bytes:Long=historyState.retainedBytes) {
        if(historyResources==null)return
        historyState=HistoryUiState(history.undoCount,history.redoCount,history.undoAction,history.redoAction,busy,history.dirty,history.limited,error,bytes)
    }
    private fun historyChanged(retired:List<DocumentSession> = emptyList()) {
        val runtime=historyResources?:return
        historyPins();runtime.cleanup(DocumentHistory.resources(retired),emptyList())
        if(historyDisposed){if(activeDocumentSessionLeases.isEmpty())runtime.close();return}
        historyAuditJob?.cancel();val token=++historyEpoch
        val sessions=history.sessions()
        // Empty history has no sources to admit or account; cleanup remains on the IO worker.
        if(DocumentHistory.resources(sessions).isEmpty()){historyAuditJob=null;publishHistory(false,bytes=0L);return}
        publishHistory(true)
        val states=sessions+activeDocumentSessionLeases.values
        historyAuditJob=viewModelScope.launch(historyMain){
            try{val audit=runtime.audit(states)
                if(token!=historyEpoch)return@launch
                val protected=listOf(documentSession)+activeDocumentSessionLeases.values
                val discarded=history.evictToByteLimit{runtime.extraBytes(it,protected,audit)}
                historyPins();runtime.cleanup(DocumentHistory.resources(discarded),emptyList())
                publishHistory(false,!audit.available,runtime.extraBytes(history.sessions(),protected,audit))
            }catch(e:CancellationException){throw e}catch(_:Exception){if(token==historyEpoch)publishHistory(false,true)}
        }
    }
    private fun cancelHistoryNavigation(){historyEpoch++;historyNavigationJob?.cancel();historyNavigationJob=null;publishHistory(false)}
    internal fun undoDocument()=navigateHistory(true)
    internal fun redoDocument()=navigateHistory(false)
    private fun navigateHistory(undo:Boolean,done:(Boolean)->Unit={}):Boolean {
        val runtime=historyResources?:return false
        if(!historyMutationAllowed()||historyState.busy)return false
        val move=history.move(undo)?:return false
        val revision=documentRevision;val token=++historyEpoch;publishHistory(true)
        historyNavigationJob=viewModelScope.launch(historyMain){
            var accepted=false
            try{
                val valid=runtime.validate(move.target)
                if(token!=historyEpoch||!historyActive)return@launch
                if(revision!=documentRevision||documentSession!==move.expected){publishHistory(false,true);return@launch}
                if(valid&&history.accept(move)){
                    documentSession=move.target;advanceDocumentRevision();batchEditor.onDocumentChanged();invalidatePageOrderDependentState()
                    scannerState=createScannerResultSummary(documentSession.pages.size,null)
                    accepted=true
                    historyPins();publishHistory(false)
                }else publishHistory(false,true)
            }catch(e:CancellationException){throw e}catch(_:Exception){if(token==historyEpoch)publishHistory(false,true)}
            finally{if(token==historyEpoch){historyNavigationJob=null;done(accepted);if(accepted)historyChanged()}else done(false)}
        };return true
    }
    internal fun disposeHistoryForTest(){onCleared()}
    internal suspend fun awaitHistoryIdle() = withTimeout(30_000) {
        while(true){
            val (audit,navigation)=withContext(historyMain){historyAuditJob to historyNavigationJob}
            audit?.join();navigation?.join();historyResources?.awaitIdle()
            if(withContext(historyMain){audit===historyAuditJob&&navigation===historyNavigationJob&&historyAuditJob?.isActive!=true&&historyNavigationJob?.isActive!=true&&!historyState.busy})break
        }
    }


    internal fun beginScannerRequest(): ScannerRequestMode? {
        if(!historyReady())return null
        if (activeAcquisition != null) return null
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
        activeAcquisition = ActiveDocumentAcquisition(
            kind = DocumentAcquisitionKind.SCANNER,
            mode = acquisitionMode,
            token = token,
        )
        lastAcquisitionError = null
        importUiState = DocumentImportUiState.Idle
        if (requestMode == ScannerRequestMode.INITIAL_SCAN) {
            scannerState = ScannerSpikeState.Preparing
        }
        return requestMode
    }

    internal fun beginContinuousRequest(): DocumentAcquisitionToken? {
        if(!historyReady())return null
        if(activeAcquisition!=null||remainingPageCapacity()==0)return null
        // A legacy PDF-only result has no reviewable image pages to append without losing content.
        if(documentPages.isEmpty()&&documentSession.directPdfSource!=null)return null
        val mode=if(documentPages.isEmpty())DocumentAcquisitionMode.REPLACE else DocumentAcquisitionMode.APPEND
        val token=acquisitionCoordinator.begin(mode)?:return null
        activeAcquisition=ActiveDocumentAcquisition(DocumentAcquisitionKind.CONTINUOUS,mode,token)
        return token
    }

    internal fun cancelContinuousRequest(token:DocumentAcquisitionToken) {
        if(activeAcquisition?.let{it.kind==DocumentAcquisitionKind.CONTINUOUS&&it.token==token}!=true)return
        acquisitionCoordinator.cancel(token,documentSession);activeAcquisition=null;historyPins()
    }

    internal fun completeContinuousRequest(token:DocumentAcquisitionToken,revision:Long,input:DocumentAcquisitionInput):Boolean {
        val request=activeAcquisition?.takeIf{it.kind==DocumentAcquisitionKind.CONTINUOUS&&it.token==token}?:return false
        if(revision!=documentRevision){cancelContinuousRequest(token);return false}
        val previous=documentSession
        val defer=request.mode==DocumentAcquisitionMode.REPLACE&&activeDocumentSessionLeases.isNotEmpty()
        val result=acquisitionCoordinator.complete(token,previous,input,deferReplacedSessionCleanup=defer)
        activeAcquisition=null
        if(result !is DocumentAcquisitionResult.Success){historyPins();return false}
        if(defer)deferredReleaseSessions+=previous
        replaceDocumentSession(result.session,if(request.mode==DocumentAcquisitionMode.APPEND)HistoryAction.APPEND else null)
        scannerState=createScannerResultSummary(result.session.pages.size,null)
        lastAcquisitionError=null;ocrUiState=OcrUiState.Idle;ocrSelectedPageIndex=0
        resetTransientState();screen=PageHarborScreen.ScanResult;return true
    }

    internal fun remainingPageCapacity(): Int =
        (acquisitionCoordinator.limits.maxPages - activePageCount()).coerceAtLeast(0)

    internal fun remainingImportPageCapacity(): Int =
        (DEFAULT_MAX_IMPORTED_DOCUMENT_PAGES - activePageCount()).coerceAtLeast(0)

    fun cancelScannerRequest() {
        val request = activeAcquisition?.takeIf { it.kind == DocumentAcquisitionKind.SCANNER }
            ?: return
        acquisitionCoordinator.cancel(request.token, documentSession)
        activeAcquisition = null
        historyPins()
        lastAcquisitionError = null
        when (request.mode.toScannerRequestMode()) {
            ScannerRequestMode.INITIAL_SCAN -> scannerState = ScannerSpikeState.Cancelled
            ScannerRequestMode.ADD_PAGES -> screen = PageHarborScreen.ScanResult
        }
    }

    /** Registers adapter-owned staging files immediately so lifecycle invalidation can release them. */
    internal fun registerPendingAcquisitionResources(
        resources: List<AcquiredResource>,
    ): PendingResourceRegistrationResult {
        val token = activeAcquisition?.token
            ?: return PendingResourceRegistrationResult.StaleToken
        val result=acquisitionCoordinator.registerPendingResources(token, documentSession, resources)
        historyPins()
        return result
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
        if (documentPages.isNotEmpty()) {
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
        if (activeAcquisition != null) return
        val token = acquisitionCoordinator.begin(DocumentAcquisitionMode.REPLACE) ?: return
        activeAcquisition = ActiveDocumentAcquisition(
            DocumentAcquisitionKind.SCANNER,
            DocumentAcquisitionMode.REPLACE,
            token,
        )
        completeScannerReferences(scannerState, scannedPdfReference, scannedPageReferences)
    }

    fun beginImportRequest(origin: DocumentImportOrigin): Boolean {
        if(!historyReady())return false
        if (activeAcquisition != null) {
            if (!activeAcquisition.isImport()) {
                importUiState = DocumentImportUiState.Error(DocumentImportError.BUSY)
            }
            return false
        }
        if (remainingImportPageCapacity() == 0) {
            importUiState = DocumentImportUiState.Error(DocumentImportError.PAGE_LIMIT_EXCEEDED)
            return false
        }
        val mode = if (documentPages.isEmpty()) {
            DocumentAcquisitionMode.REPLACE
        } else {
            DocumentAcquisitionMode.APPEND
        }
        val token = acquisitionCoordinator.begin(
            mode = mode,
            maximumPageCount = DEFAULT_MAX_IMPORTED_DOCUMENT_PAGES,
        ) ?: run {
            importUiState = DocumentImportUiState.Error(DocumentImportError.BUSY)
            return false
        }
        activeAcquisition = ActiveDocumentAcquisition(
            kind = when (origin) {
                DocumentImportOrigin.PICKER -> DocumentAcquisitionKind.FILE_IMPORT
                DocumentImportOrigin.INBOUND_SHARE -> DocumentAcquisitionKind.INBOUND_SHARE
            },
            mode = mode,
            token = token,
        )
        lastAcquisitionError = null
        importUiState = when (origin) {
            DocumentImportOrigin.PICKER -> DocumentImportUiState.Selecting
            DocumentImportOrigin.INBOUND_SHARE -> DocumentImportUiState.Processing(0, 0, 0)
        }
        return true
    }

    fun beginImportProcessing(totalItems: Int) {
        if (!activeAcquisition.isImport()) return
        importUiState = DocumentImportUiState.Processing(
            completedItems = 0,
            totalItems = totalItems.coerceAtLeast(0),
            preparedPages = 0,
        )
    }

    fun updateImportProgress(completedItems: Int, totalItems: Int, preparedPages: Int) {
        if (!activeAcquisition.isImport()) return
        importUiState = DocumentImportUiState.Processing(
            completedItems = completedItems.coerceAtLeast(0),
            totalItems = totalItems.coerceAtLeast(0),
            preparedPages = preparedPages.coerceAtLeast(0),
        )
    }

    fun completeImportRequest(result: DocumentImportPreparationResult.Success) {
        val request = activeAcquisition?.takeIf { it.isImport() }
            ?: run {
                importUiState = DocumentImportUiState.Error(DocumentImportError.INTERRUPTED)
                return
            }
        activeAcquisition = null
        val previousSession = documentSession
        val deferReplacedSessionCleanup =
            request.mode == DocumentAcquisitionMode.REPLACE && activeDocumentSessionLeases.isNotEmpty()
        when (
            val completion = acquisitionCoordinator.complete(
                token = request.token,
                currentSession = previousSession,
                input = result.input,
                deferReplacedSessionCleanup = deferReplacedSessionCleanup,
            )
        ) {
            is DocumentAcquisitionResult.Success -> {
                if (deferReplacedSessionCleanup) deferredReleaseSessions += previousSession
                replaceDocumentSession(completion.session,if(request.mode==DocumentAcquisitionMode.APPEND)HistoryAction.APPEND else null)
                scannerState = createScannerResultSummary(
                    jpegPageCount = completion.session.pages.size,
                    pdfPageCount = null,
                )
                lastAcquisitionError = null
                ocrUiState = OcrUiState.Idle
                ocrSelectedPageIndex = 0
                resetTransientState()
                importUiState = DocumentImportUiState.Completed(
                    importedPages = result.input.pages.size,
                    skippedItems = result.skippedItems,
                    appended = request.mode == DocumentAcquisitionMode.APPEND,
                )
                screen = PageHarborScreen.ScanResult
            }

            DocumentAcquisitionResult.Cancelled -> {
                historyPins()
                importUiState = DocumentImportUiState.Cancelled
            }

            is DocumentAcquisitionResult.Failure -> {
                historyPins()
                lastAcquisitionError = completion.reason
                importUiState = DocumentImportUiState.Error(completion.reason.toImportError())
            }
        }
    }

    fun cancelImportRequest() {
        val request = activeAcquisition?.takeIf { it.isImport() }
            ?: return
        acquisitionCoordinator.cancel(request.token, documentSession)
        activeAcquisition = null
        lastAcquisitionError = null
        importUiState = DocumentImportUiState.Cancelled
        historyPins()
    }

    fun failImportRequest(reason: DocumentImportError) {
        val request = activeAcquisition?.takeIf { it.isImport() }
        if (request != null) {
            acquisitionCoordinator.fail(
                request.token,
                documentSession,
                reason = reason.toAcquisitionError(),
            )
            activeAcquisition = null
        }
        lastAcquisitionError = reason.toAcquisitionError()
        importUiState = DocumentImportUiState.Error(reason)
        historyPins()
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

    /** Replaces the active work with one durable library document without deleting library files. */
    fun openLibraryDocument(session: DocumentSession, preferredPageId: String? = null): Boolean {
        val reservation=org.synapseworks.pageharbor.library.recycle.LibrarySourceReaders.reserve(session)?:return false
        try { return openPinnedLibraryDocument(session,preferredPageId) }
        finally { org.synapseworks.pageharbor.library.recycle.LibrarySourceReaders.release(reservation) }
    }

    private fun openPinnedLibraryDocument(session:DocumentSession,preferredPageId:String?):Boolean {
        if (session.pages.isEmpty() || session.libraryDocument == null || activeAcquisition != null) {
            return false
        }
        val previous = documentSession
        if (!replaceDocumentSession(session)) return false
        if (activeDocumentSessionLeases.isEmpty()) {
            acquisitionCoordinator.releaseDetachedSessions(
                sessions = listOf(previous),
                preservingSession = session,
            )
        } else {
            deferredReleaseSessions += previous
        }
        scannerState = createScannerResultSummary(session.pages.size, null)
        lastAcquisitionError = null
        importUiState = DocumentImportUiState.Idle
        ocrUiState = OcrUiState.Idle
        val resolvedPageIndex = preferredPageId?.let { stableId ->
            session.pages.indexOfFirst { page -> page.persistentId == stableId }.takeIf { it >= 0 }
        }
        openedPagePersistentId = resolvedPageIndex?.let { session.pages[it].persistentId }
        openedPageRequestId += 1L
        ocrSelectedPageIndex = resolvedPageIndex ?: 0
        resetTransientState()
        screen = PageHarborScreen.ScanResult
        return true
    }

    fun updateLibraryReference(title: String, folderId: String?): Boolean {
        val current = documentSession.libraryDocument ?: return false
        val updated = current.copy(title = title, folderId = folderId)
        if (updated == current) return false
        if(historyResources!=null){val retired=history.rebaseLibrary(updated);documentSession=history.present;historyChanged(retired)}
        else documentSession = documentSession.copy(libraryDocument = updated)
        batchEditor.onDocumentChanged()
        return true
    }

    fun clearScan() {
        batchEditor.close()
        activeAcquisition?.let { request ->
            acquisitionCoordinator.interrupt(request.token, documentSession)
        }
        activeAcquisition = null
        val detachedSession = documentSession
        documentSession = DocumentSession()
        if(historyResources!=null){historyEpoch++;historyNavigationJob?.cancel();historyNavigationJob=null;historyResources?.resetAdmission();historyChanged(history.reset(documentSession))}
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
        importUiState = DocumentImportUiState.Idle
        ocrUiState = OcrUiState.Idle
        ocrSelectedPageIndex = 0
        resetTransientState()
    }

    /** Keeps RME-owned sources alive until one Activity-owned document operation finishes. */
    internal fun acquireDocumentSessionLease(
        releaseObserverForTest: (() -> Unit)? = null,
    ): DocumentSessionLease? {
        if (documentSession.pages.isEmpty()) return null
        val reservation=org.synapseworks.pageharbor.library.recycle.LibrarySourceReaders.reserve(documentSession)?:return null
        try {
        val lease = DocumentSessionLease(
            id = ++nextDocumentSessionLeaseId,
            session = documentSession,
            releaseObserverForTest = releaseObserverForTest,
        )
        activeDocumentSessionLeases[lease.id] = lease.session
        historyPins()
        activeDocumentSessionLeaseCount.value = activeDocumentSessionLeases.size
        return lease
        } finally { org.synapseworks.pageharbor.library.recycle.LibrarySourceReaders.release(reservation) }
    }

    internal fun releaseDocumentSessionLease(lease: DocumentSessionLease) {
        if (activeDocumentSessionLeases.remove(lease.id) == null) return
        activeDocumentSessionLeaseCount.value = activeDocumentSessionLeases.size
        refreshLibraryReaderPins()
        releaseDeferredSessionsWhenSafe()
        if(historyResources!=null)historyChanged()
        lease.releaseObserverForTest?.invoke()
    }

    /** Prevents deletion of RME-owned library sources until every active reader has exited. */
    internal suspend fun awaitDocumentSessionLeasesReleased() {
        activeDocumentSessionLeaseCount.first { count -> count == 0 }
    }

    internal fun hasActiveDocumentSessionLeases(): Boolean =
        activeDocumentSessionLeases.isNotEmpty()

    /** Active work is Activity-owned and is cancelled by the Activity; completed data remains. */
    fun resetTransientStateForRecreation() {
        activeAcquisition?.takeIf { it.isImport() }?.let { request ->
            acquisitionCoordinator.interrupt(request.token, documentSession)
            activeAcquisition = null
            importUiState = DocumentImportUiState.Idle
        }
        if (ocrUiState == OcrUiState.Recognizing) {
            ocrUiState = OcrUiState.Idle
        }
        resetTransientState()
        screen = when {
            screen == PageHarborScreen.Home -> PageHarborScreen.Home
            screen == PageHarborScreen.OcrResult && ocrUiState is OcrUiState.Success -> {
                PageHarborScreen.OcrResult
            }

            scannerState is ScannerSpikeState.ResultSummary -> PageHarborScreen.ScanResult
            else -> PageHarborScreen.Home
        }
    }

    fun setPageFilter(pageId: Long, filter: DocumentFilter): Boolean {
        if(!historyMutationAllowed())return false
        val updated = documentSession.setFilter(DocumentPageId(pageId), filter) ?: return false
        return replaceDocumentSession(updated,HistoryAction.FILTER)
    }

    fun setPageAdjustments(pageId: Long, adjustments: ManualImageAdjustments, expected: DocumentPage? = null): Boolean {
        if(!historyMutationAllowed())return false
        if (expected != null && documentSession.pages.firstOrNull { it.id.value == pageId } != expected) return false
        val updated = documentSession.setAdjustments(DocumentPageId(pageId), adjustments) ?: return false
        return replaceDocumentSession(updated,HistoryAction.ADJUST)
    }

    internal fun applyAnnotations(expected:DocumentPage,revision:Long,owner:String,layer:org.synapseworks.pageharbor.document.annotation.AnnotationLayer):Boolean {
        if(owner!=documentOwnerToken||!historyMutationAllowed()||documentRevision!=revision||documentSession.pages.firstOrNull{it.id==expected.id}!==expected)return false
        val updated=documentSession.copy(pages=documentSession.pages.map{if(it===expected)it.copy(annotations=layer)else it})
        if(!replaceDocumentSession(updated,HistoryAction.ANNOTATE))return false
        invalidatePageOrderDependentState()
        return true
    }

    fun rotatePageClockwise(pageId: Long): Boolean {
        if(!historyMutationAllowed())return false
        val updated = documentSession.rotateClockwise(DocumentPageId(pageId)) ?: return false
        if (!replaceDocumentSession(updated,HistoryAction.ROTATE)) return false
        invalidatePageOrderDependentState()
        return true
    }

    fun reorderPages(pageIds: List<Long>): Boolean {
        if(!historyMutationAllowed())return false
        val updated = documentSession.reorder(pageIds.map(::DocumentPageId)) ?: return false
        if (!replaceDocumentSession(updated,HistoryAction.REORDER)) return false
        invalidatePageOrderDependentState()
        return true
    }

    fun movePage(pageId: Long, offset: Int): Boolean {
        if(!historyMutationAllowed())return false
        val updated = documentSession.move(DocumentPageId(pageId), offset) ?: return false
        if (!replaceDocumentSession(updated,HistoryAction.REORDER)) return false
        invalidatePageOrderDependentState()
        return true
    }

    fun removePage(pageId: Long): Boolean {
        if(!historyMutationAllowed())return false
        val previousSession = documentSession
        if (previousSession.pages.size <= 1) return false
        val updated = previousSession.remove(DocumentPageId(pageId)) ?: return false
        if (!replaceDocumentSession(updated,HistoryAction.DELETE)) return false
        if (activeDocumentSessionLeases.isEmpty()) {
            acquisitionCoordinator.releaseDetachedSessions(
                sessions = listOf(previousSession),
                preservingSession = updated,
            )
        } else {
            deferredReleaseSessions += previousSession
        }
        invalidatePageOrderDependentState()
        if (updated.pages.isEmpty()) {
            scannerState = ScannerSpikeState.Idle
            importUiState = DocumentImportUiState.Idle
            screen = PageHarborScreen.Home
        } else {
            scannerState = createScannerResultSummary(updated.pages.size, null)
        }
        return true
    }

    override fun onCleared() {
        batchEditor.close()
        activeAcquisition?.let { request ->
            acquisitionCoordinator.interrupt(request.token, documentSession)
        }
        activeAcquisition = null
        deferredReleaseSessions += documentSession
        documentSession = DocumentSession()
        if(historyResources!=null){historyDisposed=true;historyEpoch++;historyAuditJob?.cancel();historyNavigationJob?.cancel();historyResources?.resetAdmission();historyChanged(history.reset(documentSession))}
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
        val request = activeAcquisition?.takeIf { it.kind == DocumentAcquisitionKind.SCANNER }
        if (request == null) {
            lastAcquisitionError = DocumentAcquisitionError.INTERRUPTED
            return
        }
        val previousSummary = this.scannerState as? ScannerSpikeState.ResultSummary
        activeAcquisition = null
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
            request.mode == DocumentAcquisitionMode.REPLACE && activeDocumentSessionLeases.isNotEmpty()
        when (
            val result = acquisitionCoordinator.complete(
                token = request.token,
                currentSession = previousSession,
                input = input,
                deferReplacedSessionCleanup = deferReplacedSessionCleanup,
            )
        ) {
            is DocumentAcquisitionResult.Success -> {
                if (deferReplacedSessionCleanup) deferredReleaseSessions += previousSession
                replaceDocumentSession(result.session,if(request.mode==DocumentAcquisitionMode.APPEND)HistoryAction.APPEND else null)
                lastAcquisitionError = null
                this.scannerState = when (request.mode.toScannerRequestMode()) {
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

            DocumentAcquisitionResult.Cancelled -> applyScannerFailure(
                request.mode.toScannerRequestMode(),
                null,
            )
            is DocumentAcquisitionResult.Failure -> applyScannerFailure(
                request.mode.toScannerRequestMode(),
                result.reason,
            )
        }
    }

    private fun failActiveScannerRequest(reason: DocumentAcquisitionError) {
        val request = activeAcquisition?.takeIf { it.kind == DocumentAcquisitionKind.SCANNER }
            ?: return
        acquisitionCoordinator.fail(request.token, documentSession, reason = reason)
        activeAcquisition = null
        applyScannerFailure(request.mode.toScannerRequestMode(), reason)
    }

    private fun applyScannerFailure(mode: ScannerRequestMode, error: DocumentAcquisitionError?) {
        historyPins()
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

    private fun replaceDocumentSession(updated: DocumentSession,action:HistoryAction?=null,frozenBefore:DocumentSession?=null): Boolean {
        if (updated == documentSession && (action!=null || historyResources==null)) return false
        val before=documentSession
        documentSession = updated
        if(historyResources!=null){
            historyEpoch++;historyNavigationJob?.cancel();historyNavigationJob=null
            val retired=if(action==null){batchEditor.close();historyResources?.resetAdmission();history.reset(updated)}else history.record(before,updated,action,frozenBefore)
            historyChanged(retired)
        }
        advanceDocumentRevision()
        batchEditor.onDocumentChanged()
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

    private fun ActiveDocumentAcquisition?.isImport(): Boolean =
        this != null && kind in setOf(DocumentAcquisitionKind.FILE_IMPORT,DocumentAcquisitionKind.INBOUND_SHARE)

    private fun DocumentAcquisitionMode.toScannerRequestMode(): ScannerRequestMode =
        when (this) {
            DocumentAcquisitionMode.REPLACE -> ScannerRequestMode.INITIAL_SCAN
            DocumentAcquisitionMode.APPEND -> ScannerRequestMode.ADD_PAGES
        }

    private fun DocumentImportError.toAcquisitionError(): DocumentAcquisitionError = when (this) {
        DocumentImportError.EMPTY_INPUT -> DocumentAcquisitionError.EMPTY_INPUT
        DocumentImportError.UNSUPPORTED_TYPE -> DocumentAcquisitionError.UNSUPPORTED_CONTENT_TYPE
        DocumentImportError.PAGE_LIMIT_EXCEEDED -> DocumentAcquisitionError.PAGE_LIMIT_EXCEEDED
        DocumentImportError.SOURCE_TOO_LARGE -> DocumentAcquisitionError.SOURCE_TOO_LARGE
        DocumentImportError.INTERRUPTED, DocumentImportError.BUSY ->
            DocumentAcquisitionError.INTERRUPTED
        DocumentImportError.UNREADABLE_SOURCE,
        DocumentImportError.INVALID_IMAGE,
        DocumentImportError.PDF_UNREADABLE,
        DocumentImportError.TEMPORARY_FILE_FAILED,
        DocumentImportError.INSUFFICIENT_STORAGE,
        DocumentImportError.INSUFFICIENT_MEMORY,
        -> DocumentAcquisitionError.SOURCE_UNAVAILABLE
    }

    private fun DocumentAcquisitionError.toImportError(): DocumentImportError = when (this) {
        DocumentAcquisitionError.EMPTY_INPUT -> DocumentImportError.EMPTY_INPUT
        DocumentAcquisitionError.UNSUPPORTED_CONTENT_TYPE -> DocumentImportError.UNSUPPORTED_TYPE
        DocumentAcquisitionError.PAGE_LIMIT_EXCEEDED -> DocumentImportError.PAGE_LIMIT_EXCEEDED
        DocumentAcquisitionError.SOURCE_TOO_LARGE,
        DocumentAcquisitionError.IMAGE_DIMENSIONS_EXCEEDED,
        -> DocumentImportError.SOURCE_TOO_LARGE
        DocumentAcquisitionError.INTERRUPTED -> DocumentImportError.INTERRUPTED
        DocumentAcquisitionError.INVALID_REFERENCE,
        DocumentAcquisitionError.INVALID_OWNERSHIP,
        DocumentAcquisitionError.SOURCE_UNAVAILABLE,
        DocumentAcquisitionError.INVALID_IMAGE_METADATA,
        -> DocumentImportError.UNREADABLE_SOURCE
    }
}
