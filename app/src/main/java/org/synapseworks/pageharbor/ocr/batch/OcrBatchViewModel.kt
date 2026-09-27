package org.synapseworks.pageharbor.ocr.batch

import android.app.Application
import java.io.FileNotFoundException
import java.util.UUID
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.synapseworks.pageharbor.document.session.DocumentPage
import org.synapseworks.pageharbor.document.session.toAndroidUri
import org.synapseworks.pageharbor.library.LibraryRepository
import org.synapseworks.pageharbor.library.LibraryResult
import org.synapseworks.pageharbor.library.OcrBatchJobEntity
import org.synapseworks.pageharbor.library.OcrBatchJobState
import org.synapseworks.pageharbor.library.OcrBatchTarget
import org.synapseworks.pageharbor.ocr.MultilingualOcrRuntime
import org.synapseworks.pageharbor.ocr.OcrFailureReason
import org.synapseworks.pageharbor.ocr.OcrModelState
import org.synapseworks.pageharbor.ocr.OcrPage
import org.synapseworks.pageharbor.ocr.OcrPageRecognitionEngine
import org.synapseworks.pageharbor.ocr.OcrPageRecognitionOutcome
import org.synapseworks.pageharbor.ocr.OcrScript
import org.synapseworks.pageharbor.ocr.OcrScriptSelection
import org.synapseworks.pageharbor.ocr.persistence.OcrPagePersistenceMapping
import org.synapseworks.pageharbor.ocr.persistence.ScriptedOcrSessionRunner

enum class OcrBatchMode { MISSING_ONLY, RERUN }

enum class OcrBatchSetupError { MODEL_UNAVAILABLE, EMPTY_SELECTION, PLANNING_FAILED }

data class OcrBatchProgress(
    val documentTotal: Int,
    val completedDocuments: Int,
    val pageTotal: Int,
    val completedPages: Int,
    val skippedPages: Int,
    val failedPages: Int,
    val currentDocumentTitle: String?,
)

data class OcrBatchSummary(
    val documentTotal: Int,
    val pageTotal: Int,
    val completedPages: Int,
    val skippedPages: Int,
    val failedPages: Int,
    val cancelled: Boolean,
    val retryableItems: Int,
) {
    val hasRetryableWork: Boolean get() = retryableItems > 0
}

sealed interface OcrBatchUiState {
    data object Hidden : OcrBatchUiState
    data class Setup(
        val documentIds: List<String>,
        val mode: OcrBatchMode = OcrBatchMode.MISSING_ONLY,
        val error: OcrBatchSetupError? = null,
    ) : OcrBatchUiState
    data class Planning(val documentTotal: Int) : OcrBatchUiState
    data class Running(val jobId: String, val progress: OcrBatchProgress) : OcrBatchUiState
    data class Finished(val jobId: String, val summary: OcrBatchSummary) : OcrBatchUiState
}

/**
 * Durable, sequential library OCR. The database owns all job truth; this view model owns only the
 * active coroutine and reconstructs unfinished work after process/repository recreation.
 */
class OcrBatchViewModel internal constructor(
    application: Application,
    private val repository: LibraryRepository,
    recognitionEngine: OcrPageRecognitionEngine,
    private val modelState: suspend (OcrScript) -> OcrModelState,
    private val nowMillis: () -> Long,
    private val newId: () -> String,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(
        application = application,
        repository = LibraryRepository(application),
        recognitionEngine = MultilingualOcrRuntime.get(application).recognitionEngine,
        modelState = MultilingualOcrRuntime.get(application).modelInstaller::stateFor,
        nowMillis = System::currentTimeMillis,
        newId = { UUID.randomUUID().toString() },
    )

    private val runner = ScriptedOcrSessionRunner(recognitionEngine)
    private val mutableState = MutableStateFlow<OcrBatchUiState>(OcrBatchUiState.Hidden)
    val state: StateFlow<OcrBatchUiState> = mutableState.asStateFlow()
    private var execution: Job? = null

    init {
        execution = viewModelScope.launch { reconstructDurableWork() }
    }

    fun openSetup(documentIds: List<String>) {
        if (execution?.isActive == true && mutableState.value !is OcrBatchUiState.Hidden) return
        val stableIds = documentIds.filter(String::isNotBlank).distinct()
        mutableState.value = OcrBatchUiState.Setup(
            documentIds = stableIds,
            error = if (stableIds.isEmpty()) OcrBatchSetupError.EMPTY_SELECTION else null,
        )
    }

    fun setMode(mode: OcrBatchMode) {
        val setup = mutableState.value as? OcrBatchUiState.Setup ?: return
        mutableState.value = setup.copy(mode = mode, error = null)
    }

    fun dismiss() {
        if (mutableState.value is OcrBatchUiState.Running) return
        mutableState.value = OcrBatchUiState.Hidden
    }

    fun start(
        requestedSelection: OcrScriptSelection,
        resolvedScript: OcrScript,
        localeRecommendation: OcrScript,
    ) {
        val setup = mutableState.value as? OcrBatchUiState.Setup ?: return
        if (setup.documentIds.isEmpty()) return
        val previous = execution
        execution = viewModelScope.launch {
            previous?.join()
            if (mutableState.value !is OcrBatchUiState.Setup) return@launch
            val available = withContext(Dispatchers.IO) {
                isBatchModelReady(resolvedScript, modelState(resolvedScript))
            }
            if (!available) {
                mutableState.value = setup.copy(error = OcrBatchSetupError.MODEL_UNAVAILABLE)
                return@launch
            }
            mutableState.value = OcrBatchUiState.Planning(setup.documentIds.size)
            val jobId = withContext(Dispatchers.IO) {
                createJob(
                    documentIds = setup.documentIds,
                    mode = setup.mode,
                    requestedSelection = requestedSelection,
                    resolvedScript = resolvedScript,
                    localeRecommendation = localeRecommendation,
                )
            }
            if (jobId == null) {
                mutableState.value = setup.copy(error = OcrBatchSetupError.PLANNING_FAILED)
                return@launch
            }
            execute(jobId)
        }
    }

    fun cancel() {
        val running = mutableState.value as? OcrBatchUiState.Running ?: return
        viewModelScope.launch(Dispatchers.IO) {
            repository.cancelOcrBatchJob(running.jobId)
            execution?.cancel()
            publish(running.jobId)
        }
    }

    fun retryFailed() {
        val finished = mutableState.value as? OcrBatchUiState.Finished ?: return
        if (!finished.summary.hasRetryableWork) return
        val previous = execution
        execution = viewModelScope.launch {
            previous?.join()
            withContext(Dispatchers.IO) {
                while (true) {
                    val retryable = repository.retryableOcrBatchItems(
                        finished.jobId,
                        RETRY_CHUNK_SIZE,
                    )
                    if (retryable.isEmpty()) break
                    val refreshed = retryable.count {
                        repository.refreshOcrBatchItemForRetry(it.jobId, it.itemId)
                    }
                    if (refreshed == 0) break
                    if (retryable.size < RETRY_CHUNK_SIZE) break
                }
            }
            execute(finished.jobId)
        }
    }

    private suspend fun reconstructDurableWork() = withContext(Dispatchers.IO) {
        repository.markInterruptedOcrBatchJobs()
        val recoverable = repository.latestRecoverableOcrBatchJob() ?: return@withContext
        if (recoverable.state == OcrBatchJobState.INTERRUPTED.name ||
            recoverable.state == OcrBatchJobState.PROCESSING.name
        ) {
            repository.recoverInterruptedOcrBatchJob(recoverable.jobId)
        }
        execute(recoverable.jobId)
    }

    private suspend fun createJob(
        documentIds: List<String>,
        mode: OcrBatchMode,
        requestedSelection: OcrScriptSelection,
        resolvedScript: OcrScript,
        localeRecommendation: OcrScript,
    ): String? {
        val timestamp = nowMillis()
        val jobId = newId()
        val job = OcrBatchJobEntity(
            jobId = jobId,
            selectionPolicy = mode.name,
            requestedScriptSelection = requestedSelection.stableId,
            localeRecommendationSnapshot = localeRecommendation.stableId,
            state = OcrBatchJobState.READY.name,
            totalItemCount = 0,
            completedItemCount = 0,
            failedItemCount = 0,
            skippedItemCount = 0,
            createdAtMillis = timestamp,
            updatedAtMillis = timestamp,
            runGeneration = 1,
            cancelRequested = false,
            targetPopulationComplete = false,
            terminalErrorCode = null,
        )
        if (!repository.beginOcrBatchTargetPopulation(job)) return null
        var ordinal = 0
        val chunk = ArrayList<OcrBatchTarget>(TARGET_CHUNK_SIZE)
        try {
            documentIds.forEach { documentId ->
                val snapshots = repository.captureOcrPageSnapshots(documentId)
                snapshots.forEach { snapshot ->
                    chunk += OcrBatchTarget(
                        itemId = newId(),
                        documentId = documentId,
                        pageId = snapshot.pageId,
                        ordinal = ordinal++,
                        requestedScriptSelection = requestedSelection.stableId,
                        resolvedScript = resolvedScript.stableId,
                    )
                    if (chunk.size == TARGET_CHUNK_SIZE) {
                        if (!repository.appendOcrBatchTargets(jobId, chunk.toList())) return null
                        chunk.clear()
                    }
                }
            }
            if (chunk.isNotEmpty() && !repository.appendOcrBatchTargets(jobId, chunk)) return null
            if (!repository.finishOcrBatchTargetPopulation(jobId)) return null
            return jobId
        } finally {
            val stored = repository.ocrBatchJob(jobId)
            if (stored?.targetPopulationComplete == false) {
                repository.discardIncompleteOcrBatchTargetPopulation(jobId)
            }
        }
    }

    private suspend fun execute(jobId: String) {
        var openedDocumentId: String? = null
        var openedPages: Map<String?, DocumentPage> = emptyMap()
        try {
            while (true) {
                val job = withContext(Dispatchers.IO) { repository.ocrBatchJob(jobId) } ?: break
                if (job.cancelRequested || job.state == OcrBatchJobState.CANCELLED.name) break
                val item = withContext(Dispatchers.IO) { repository.nextOcrBatchItem(jobId) } ?: break
                publish(jobId, item.documentId)
                val claim = withContext(Dispatchers.IO) {
                    repository.claimOcrBatchItem(jobId, item.itemId, job.runGeneration, newId())
                } ?: continue

                if (job.selectionPolicy == OcrBatchMode.MISSING_ONLY.name) {
                    val review = withContext(Dispatchers.IO) {
                        repository.ocrReviewPage(claim.documentId, claim.pageId)
                    }
                    if (!shouldRecognizeBatchPage(OcrBatchMode.MISSING_ONLY, review)) {
                        withContext(Dispatchers.IO) {
                            repository.skipOcrBatchItem(claim, ERROR_CURRENT_OCR)
                        }
                        continue
                    }
                }

                if (openedDocumentId != claim.documentId) {
                    openedPages = withContext(Dispatchers.IO) {
                        val opened = repository.openDocument(claim.documentId)
                            as? LibraryResult.Success
                        opened?.value?.session?.pages?.associateBy(DocumentPage::persistentId)
                            ?: emptyMap()
                    }
                    openedDocumentId = claim.documentId
                }
                val page = openedPages[claim.pageId]
                if (page == null) {
                    withContext(Dispatchers.IO) {
                        repository.failOcrBatchItem(claim, ERROR_SOURCE_MISSING)
                    }
                    continue
                }
                recognizeClaim(claim.resolvedScript, claim, page)
            }
        } catch (error: CancellationException) {
            throw error
        } finally {
            publish(jobId)
        }
    }

    private suspend fun recognizeClaim(
        scriptId: String,
        claim: org.synapseworks.pageharbor.library.OcrBatchClaim,
        page: DocumentPage,
    ) {
        val script = OcrScript.fromStableId(scriptId)
        if (script == null) {
            withContext(Dispatchers.IO) { repository.failOcrBatchItem(claim, ERROR_SCRIPT) }
            return
        }
        try {
            val recognized = withContext(Dispatchers.IO) {
                runner.recognizePage(
                    page = OcrPage(
                        rotationDegrees = page.rotation.degrees,
                        imageMetadata = page.imageMetadata,
                    ) {
                        getApplication<Application>().contentResolver.openInputStream(
                            page.source.toAndroidUri(),
                        ) ?: throw FileNotFoundException()
                    },
                    script = script,
                    pagePosition = claim.expected.pagePosition,
                    savedSnapshot = claim.expected,
                    recognizedAtMillis = nowMillis(),
                )
            }
            val artifact = (recognized.persistence as? OcrPagePersistenceMapping.Ready)
                ?.outcome?.artifact
            if (artifact != null) {
                withContext(Dispatchers.IO) { repository.completeOcrBatchItem(claim, artifact) }
            } else {
                val code = (recognized.outcome as? OcrPageRecognitionOutcome.Failure)
                    ?.reason?.toSafeCode() ?: ERROR_RECOGNITION
                withContext(Dispatchers.IO) { repository.failOcrBatchItem(claim, code) }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            withContext(Dispatchers.IO) { repository.failOcrBatchItem(claim, ERROR_RECOGNITION) }
        }
    }

    private suspend fun publish(jobId: String, currentDocumentId: String? = null) {
        val job = withContext(Dispatchers.IO) { repository.ocrBatchJob(jobId) } ?: return
        val progressSnapshot = withContext(Dispatchers.IO) {
            repository.ocrBatchProgress(jobId)
        }
        val summary = OcrBatchSummary(
            documentTotal = progressSnapshot.documentTotal,
            pageTotal = job.totalItemCount,
            completedPages = job.completedItemCount,
            skippedPages = job.skippedItemCount,
            failedPages = job.failedItemCount,
            cancelled = job.state == OcrBatchJobState.CANCELLED.name,
            retryableItems = progressSnapshot.retryableItems,
        )
        mutableState.value = if (job.state in setOf(
                OcrBatchJobState.COMPLETED.name,
                OcrBatchJobState.CANCELLED.name,
                OcrBatchJobState.FAILED.name,
            )
        ) {
            OcrBatchUiState.Finished(jobId, summary)
        } else {
            OcrBatchUiState.Running(
                jobId = jobId,
                progress = OcrBatchProgress(
                    documentTotal = progressSnapshot.documentTotal,
                    completedDocuments = progressSnapshot.completedDocuments,
                    pageTotal = job.totalItemCount,
                    completedPages = job.completedItemCount,
                    skippedPages = job.skippedItemCount,
                    failedPages = job.failedItemCount,
                    currentDocumentTitle = currentDocumentId?.let {
                        withContext(Dispatchers.IO) { repository.documentTitle(it) }
                    },
                ),
            )
        }
    }

    private fun OcrFailureReason.toSafeCode(): String = when (this) {
        OcrFailureReason.MODEL_UNAVAILABLE,
        OcrFailureReason.GOOGLE_PLAY_SERVICES_UNAVAILABLE,
        OcrFailureReason.SCRIPT_UNSUPPORTED,
        -> ERROR_MODEL
        OcrFailureReason.STALE_INPUT -> ERROR_STALE_INPUT
        OcrFailureReason.IMAGE_UNREADABLE -> ERROR_SOURCE_MISSING
        OcrFailureReason.PERSISTENCE_FAILED -> ERROR_PERSISTENCE
        OcrFailureReason.RECOGNITION_FAILED,
        OcrFailureReason.CANCELLED,
        -> ERROR_RECOGNITION
    }

    companion object {
        private const val TARGET_CHUNK_SIZE = 100
        private const val RETRY_CHUNK_SIZE = 100
        private const val ERROR_CURRENT_OCR = "CURRENT_OCR"
        private const val ERROR_STALE_INPUT = "STALE_INPUT"
        private const val ERROR_SOURCE_MISSING = "SOURCE_MISSING"
        private const val ERROR_MODEL = "MODEL_UNAVAILABLE"
        private const val ERROR_SCRIPT = "SCRIPT_UNSUPPORTED"
        private const val ERROR_PERSISTENCE = "PERSISTENCE_FAILED"
        private const val ERROR_RECOGNITION = "RECOGNITION_FAILED"
    }
}
