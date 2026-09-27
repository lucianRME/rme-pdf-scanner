package org.synapseworks.pageharbor.ocr.review

import android.app.Application
import java.io.FileNotFoundException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import org.synapseworks.pageharbor.document.session.DocumentPage
import org.synapseworks.pageharbor.document.session.toAndroidUri
import org.synapseworks.pageharbor.library.LibraryOcrCommitResult
import org.synapseworks.pageharbor.library.LibraryOcrCorrectionAlignment
import org.synapseworks.pageharbor.library.LibraryOcrCorrectionDraft
import org.synapseworks.pageharbor.library.LibraryOcrReviewPage
import org.synapseworks.pageharbor.library.LibraryRepository
import org.synapseworks.pageharbor.library.LibraryResult
import org.synapseworks.pageharbor.ocr.MultilingualOcrRuntime
import org.synapseworks.pageharbor.ocr.OcrPage
import org.synapseworks.pageharbor.ocr.OcrPageRecognitionEngine
import org.synapseworks.pageharbor.ocr.OcrScript

enum class OcrReviewLoadState { IDLE, LOADING, READY, DOCUMENT_MISSING, PAGE_MISSING, FAILED }

enum class OcrReviewNotice {
    CORRECTION_SAVED,
    CORRECTION_REVERTED,
    RECOGNITION_REFRESHED,
    RECOGNITION_REFRESHED_CORRECTION_PRESERVED,
    RECOGNITION_PARTIAL,
    RECOGNITION_FAILED,
    RECOGNITION_CANCELLED,
    STALE_PAGE,
}

sealed interface OcrReviewOperationState {
    data object Idle : OcrReviewOperationState
    data class Running(val progress: DocumentOcrRerunProgress) : OcrReviewOperationState
    data class Finished(val result: DocumentOcrRerunResult) : OcrReviewOperationState
    data object Cancelled : OcrReviewOperationState
}

data class OcrReviewUiState(
    val active: Boolean = false,
    val documentId: String? = null,
    val documentTitle: String = "",
    val pageIds: List<String> = emptyList(),
    val selectedPageId: String? = null,
    val selectedPageIndex: Int = 0,
    val page: LibraryOcrReviewPage? = null,
    val loadState: OcrReviewLoadState = OcrReviewLoadState.IDLE,
    val editing: Boolean = false,
    val draftText: String = "",
    val operation: OcrReviewOperationState = OcrReviewOperationState.Idle,
    val notice: OcrReviewNotice? = null,
) {
    val hasUnsavedChanges: Boolean
        get() = editing && draftText != page?.effectiveText.orEmpty()

    val operationRunning: Boolean
        get() = operation is OcrReviewOperationState.Running
}

enum class OcrReviewRerunScope { PAGE, DOCUMENT }

/**
 * Saved-document OCR review state. Only one page is materialized at a time; the document-wide
 * operation keeps at most the bounded page descriptors and one decoded page in flight.
 */
class OcrReviewViewModel internal constructor(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
    private val repository: LibraryRepository,
    private val recognitionEngine: OcrPageRecognitionEngine,
    private val nowMillis: () -> Long,
) : AndroidViewModel(application) {
    constructor(application: Application, savedStateHandle: SavedStateHandle) : this(
        application = application,
        savedStateHandle = savedStateHandle,
        repository = LibraryRepository(application),
        recognitionEngine = MultilingualOcrRuntime.get(application).recognitionEngine,
        nowMillis = System::currentTimeMillis,
    )

    private val mutableState = MutableStateFlow(restoredState())
    val state: StateFlow<OcrReviewUiState> = mutableState.asStateFlow()
    private var loadJob: Job? = null
    private var mutationJob: Job? = null
    private var rerunJob: Job? = null

    init {
        if (mutableState.value.active) refreshDocument(mutableState.value.selectedPageId)
    }

    fun open(documentId: String, title: String, preferredPageId: String? = null) {
        require(documentId.isNotBlank())
        mutableState.value = OcrReviewUiState(
            active = true,
            documentId = documentId,
            documentTitle = title,
            selectedPageId = preferredPageId,
            loadState = OcrReviewLoadState.LOADING,
        )
        persistState()
        refreshDocument(preferredPageId)
    }

    fun close() {
        loadJob?.cancel()
        mutationJob?.cancel()
        rerunJob?.cancel()
        mutableState.value = OcrReviewUiState()
        listOf(
            KEY_ACTIVE,
            KEY_DOCUMENT_ID,
            KEY_DOCUMENT_TITLE,
            KEY_PAGE_ID,
            KEY_EDITING,
            KEY_DRAFT,
        ).forEach { key -> savedStateHandle.remove<Any?>(key) }
    }

    fun beginEdit() {
        val current = mutableState.value
        if (current.page?.rawText == null || current.operationRunning) return
        update(
            current.copy(
                editing = true,
                draftText = current.page.effectiveText.orEmpty(),
                notice = null,
            ),
        )
    }

    fun updateDraft(value: String) {
        val current = mutableState.value
        if (!current.editing) return
        update(current.copy(draftText = value, notice = null))
    }

    fun cancelEdit() {
        val current = mutableState.value
        update(
            current.copy(
                editing = false,
                draftText = current.page?.effectiveText.orEmpty(),
                notice = null,
            ),
        )
    }

    fun selectPage(pageId: String) {
        val current = mutableState.value
        if (current.hasUnsavedChanges || current.operationRunning || pageId == current.selectedPageId) {
            return
        }
        refreshDocument(pageId)
    }

    fun saveCorrection() {
        val current = mutableState.value
        val documentId = current.documentId ?: return
        val page = current.page ?: return
        if (!current.editing || current.operationRunning) return
        val draftText = current.draftText
        val draftLines = draftText.split('\n')
        val correction = if (page.rawLines.isNotEmpty() && draftLines.size == page.rawLines.size) {
            LibraryOcrCorrectionDraft(
                correctedText = draftText,
                alignment = LibraryOcrCorrectionAlignment.LINE_ALIGNED,
                correctedLines = draftLines,
            )
        } else {
            LibraryOcrCorrectionDraft(
                correctedText = draftText,
                alignment = LibraryOcrCorrectionAlignment.FREEFORM,
            )
        }
        mutationJob?.cancel()
        mutationJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                repository.saveOcrCorrection(page.snapshot, correction)
            }
            when (result) {
                LibraryOcrCommitResult.APPLIED -> {
                    loadSelectedPage(
                        documentId = documentId,
                        pageId = page.snapshot.pageId,
                        editing = false,
                        draftText = draftText,
                        notice = OcrReviewNotice.CORRECTION_SAVED,
                    )
                }
                LibraryOcrCommitResult.STALE -> {
                    loadSelectedPage(
                        documentId = documentId,
                        pageId = page.snapshot.pageId,
                        editing = true,
                        draftText = draftText,
                        notice = OcrReviewNotice.STALE_PAGE,
                    )
                }
                LibraryOcrCommitResult.NOT_FOUND -> markMissing(page.snapshot.pageId)
            }
        }
    }

    fun revertCorrection() {
        val current = mutableState.value
        val documentId = current.documentId ?: return
        val page = current.page ?: return
        if (!page.hasCorrection || current.editing || current.operationRunning) return
        mutationJob?.cancel()
        mutationJob = viewModelScope.launch {
            when (withContext(Dispatchers.IO) {
                repository.revertOcrCorrection(page.snapshot)
            }) {
                LibraryOcrCommitResult.APPLIED -> loadSelectedPage(
                    documentId = documentId,
                    pageId = page.snapshot.pageId,
                    editing = false,
                    draftText = "",
                    notice = OcrReviewNotice.CORRECTION_REVERTED,
                )
                LibraryOcrCommitResult.STALE -> loadSelectedPage(
                    documentId = documentId,
                    pageId = page.snapshot.pageId,
                    editing = false,
                    draftText = "",
                    notice = OcrReviewNotice.STALE_PAGE,
                )
                LibraryOcrCommitResult.NOT_FOUND -> markMissing(page.snapshot.pageId)
            }
        }
    }

    fun rerunOcr(scope: OcrReviewRerunScope, script: OcrScript) {
        val current = mutableState.value
        val documentId = current.documentId ?: return
        if (current.hasUnsavedChanges || current.operationRunning) return
        rerunJob?.cancel()
        rerunJob = viewModelScope.launch {
            val pageIds = when (scope) {
                OcrReviewRerunScope.PAGE -> listOfNotNull(mutableState.value.selectedPageId)
                OcrReviewRerunScope.DOCUMENT -> withContext(Dispatchers.IO) {
                    repository.captureOcrPageSnapshots(documentId).map { it.pageId }
                }
            }
            if (pageIds.isEmpty()) {
                markMissing(mutableState.value.selectedPageId)
                return@launch
            }
            mutableState.value = mutableState.value.copy(
                operation = OcrReviewOperationState.Running(
                    DocumentOcrRerunProgress(0, pageIds.size, 0, 0),
                ),
                notice = null,
            )
            try {
                val targets = withContext(Dispatchers.IO) {
                    createTargets(documentId, pageIds)
                }
                if (targets.size != pageIds.size) {
                    mutableState.value = mutableState.value.copy(
                        operation = OcrReviewOperationState.Finished(
                            DocumentOcrRerunResult(
                                pageIds.map { pageId ->
                                    DocumentOcrRerunPageResult(
                                        pageId = pageId,
                                        succeeded = false,
                                        failure = DocumentOcrRerunFailure.DELETED,
                                    )
                                },
                            ),
                        ),
                        notice = OcrReviewNotice.RECOGNITION_FAILED,
                    )
                    refreshDocument(mutableState.value.selectedPageId)
                    return@launch
                }
                val coordinator = DocumentOcrRerunCoordinator(
                    engine = recognitionEngine,
                    committer = OcrArtifactCommitter { expected, artifact ->
                        repository.commitOcrArtifact(expected, artifact)
                    },
                    nowMillis = nowMillis,
                )
                val result = withContext(Dispatchers.IO) {
                    coordinator.run(targets, script) { progress ->
                        mutableState.value = mutableState.value.copy(
                            operation = OcrReviewOperationState.Running(progress),
                        )
                    }
                }
                mutableState.value = mutableState.value.copy(
                    operation = OcrReviewOperationState.Finished(result),
                    notice = when {
                        result.succeededPages == 0 -> OcrReviewNotice.RECOGNITION_FAILED
                        result.failedPages > 0 -> OcrReviewNotice.RECOGNITION_PARTIAL
                        result.preservedCorrections > 0 ->
                            OcrReviewNotice.RECOGNITION_REFRESHED_CORRECTION_PRESERVED
                        else -> OcrReviewNotice.RECOGNITION_REFRESHED
                    },
                )
                refreshDocument(mutableState.value.selectedPageId)
            } catch (_: CancellationException) {
                mutableState.value = mutableState.value.copy(
                    operation = OcrReviewOperationState.Cancelled,
                    notice = OcrReviewNotice.RECOGNITION_CANCELLED,
                )
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(
                    operation = OcrReviewOperationState.Finished(
                        DocumentOcrRerunResult(
                            pageIds.map { pageId ->
                                DocumentOcrRerunPageResult(
                                    pageId = pageId,
                                    succeeded = false,
                                    failure = DocumentOcrRerunFailure.RECOGNITION_FAILED,
                                )
                            },
                        ),
                    ),
                    notice = OcrReviewNotice.RECOGNITION_FAILED,
                )
            }
        }
    }

    fun cancelRerun() {
        rerunJob?.cancel()
    }

    fun consumeNotice() {
        mutableState.value = mutableState.value.copy(notice = null)
    }

    private suspend fun createTargets(
        documentId: String,
        pageIds: List<String>,
    ): List<DocumentOcrRerunTarget> {
        val opened = repository.openDocument(documentId) as? LibraryResult.Success ?: return emptyList()
        val pagesById = opened.value.session.pages.associateBy(DocumentPage::persistentId)
        return pageIds.mapNotNull { pageId ->
            val page = pagesById[pageId] ?: return@mapNotNull null
            val snapshot = repository.captureOcrPageSnapshot(documentId, pageId)
                ?: return@mapNotNull null
            val review = repository.ocrReviewPage(documentId, pageId)
                ?: return@mapNotNull null
            DocumentOcrRerunTarget(
                snapshot = snapshot,
                source = OcrPage(
                    rotationDegrees = page.rotation.degrees,
                    imageMetadata = page.imageMetadata,
                ) {
                    getApplication<Application>().contentResolver.openInputStream(
                        page.source.toAndroidUri(),
                    ) ?: throw FileNotFoundException()
                },
                hadCorrection = review.hasCorrection,
            )
        }
    }

    private fun refreshDocument(preferredPageId: String?) {
        val documentId = mutableState.value.documentId ?: return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            mutableState.value = mutableState.value.copy(loadState = OcrReviewLoadState.LOADING)
            try {
                val snapshots = withContext(Dispatchers.IO) {
                    repository.captureOcrPageSnapshots(documentId)
                }
                if (snapshots.isEmpty()) {
                    mutableState.value = mutableState.value.copy(
                        pageIds = emptyList(),
                        page = null,
                        loadState = OcrReviewLoadState.DOCUMENT_MISSING,
                    )
                    return@launch
                }
                val pageIds = snapshots.map { it.pageId }
                val selected = preferredPageId?.takeIf(pageIds::contains)
                    ?: mutableState.value.selectedPageId?.takeIf(pageIds::contains)
                    ?: pageIds.first()
                mutableState.value = mutableState.value.copy(
                    pageIds = pageIds,
                    selectedPageId = selected,
                    selectedPageIndex = pageIds.indexOf(selected),
                )
                loadSelectedPage(
                    documentId = documentId,
                    pageId = selected,
                    editing = mutableState.value.editing,
                    draftText = mutableState.value.draftText,
                    notice = mutableState.value.notice,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(loadState = OcrReviewLoadState.FAILED)
            }
        }
    }

    private suspend fun loadSelectedPage(
        documentId: String,
        pageId: String,
        editing: Boolean,
        draftText: String,
        notice: OcrReviewNotice?,
    ) {
        val page = withContext(Dispatchers.IO) { repository.ocrReviewPage(documentId, pageId) }
        if (page == null) {
            markMissing(pageId)
            return
        }
        val current = mutableState.value
        update(
            current.copy(
                selectedPageId = pageId,
                selectedPageIndex = current.pageIds.indexOf(pageId).coerceAtLeast(0),
                page = page,
                loadState = OcrReviewLoadState.READY,
                editing = editing,
                draftText = if (editing) draftText else page.effectiveText.orEmpty(),
                notice = notice,
            ),
        )
    }

    private fun markMissing(pageId: String?) {
        val current = mutableState.value
        mutableState.value = current.copy(
            page = null,
            loadState = if (current.pageIds.size <= 1) {
                OcrReviewLoadState.DOCUMENT_MISSING
            } else {
                OcrReviewLoadState.PAGE_MISSING
            },
            selectedPageId = pageId,
            notice = OcrReviewNotice.STALE_PAGE,
        )
    }

    private fun update(value: OcrReviewUiState) {
        mutableState.value = value
        persistState()
    }

    private fun persistState() {
        val current = mutableState.value
        savedStateHandle[KEY_ACTIVE] = current.active
        savedStateHandle[KEY_DOCUMENT_ID] = current.documentId
        savedStateHandle[KEY_DOCUMENT_TITLE] = current.documentTitle
        savedStateHandle[KEY_PAGE_ID] = current.selectedPageId
        savedStateHandle[KEY_EDITING] = current.editing
        savedStateHandle[KEY_DRAFT] = current.draftText
    }

    private fun restoredState(): OcrReviewUiState {
        val active = savedStateHandle[KEY_ACTIVE] ?: false
        return OcrReviewUiState(
            active = active,
            documentId = savedStateHandle[KEY_DOCUMENT_ID],
            documentTitle = savedStateHandle[KEY_DOCUMENT_TITLE] ?: "",
            selectedPageId = savedStateHandle[KEY_PAGE_ID],
            loadState = if (active) OcrReviewLoadState.LOADING else OcrReviewLoadState.IDLE,
            editing = savedStateHandle[KEY_EDITING] ?: false,
            draftText = savedStateHandle[KEY_DRAFT] ?: "",
        )
    }

    private companion object {
        const val KEY_ACTIVE = "ocr_review_active"
        const val KEY_DOCUMENT_ID = "ocr_review_document_id"
        const val KEY_DOCUMENT_TITLE = "ocr_review_document_title"
        const val KEY_PAGE_ID = "ocr_review_page_id"
        const val KEY_EDITING = "ocr_review_editing"
        const val KEY_DRAFT = "ocr_review_draft"
    }
}
