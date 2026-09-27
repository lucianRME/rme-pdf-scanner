package org.synapseworks.pageharbor.library

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.synapseworks.pageharbor.document.session.DocumentSession
import org.synapseworks.pageharbor.ocr.OcrResult
import org.synapseworks.pageharbor.ocr.OcrScript

enum class LibraryActionSuccess {
    SAVED,
    RENAMED,
    MOVED,
    DELETED,
    FOLDER_CREATED,
    FOLDER_RENAMED,
    FOLDER_MOVED,
    FOLDER_DELETED,
    MERGED,
    EXTRACTED,
    SPLIT,
    OCR_INDEXED,
}

sealed interface LibraryActionState {
    data object Idle : LibraryActionState
    data object Working : LibraryActionState
    data class Succeeded(
        val eventId: Long,
        val action: LibraryActionSuccess,
        val warning: LibraryWarning? = null,
    ) : LibraryActionState

    data class Failed(val eventId: Long, val error: LibraryError) : LibraryActionState
}

data class LibraryUiState(
    val documents: List<LibraryDocumentSummary> = emptyList(),
    val recentDocuments: List<LibraryDocumentSummary> = emptyList(),
    val folders: List<LibraryFolder> = emptyList(),
    val folderBreadcrumb: List<LibraryFolder> = emptyList(),
    val query: String = "",
    val selectedFolderId: String? = null,
    val sortOrder: LibrarySortOrder = LibrarySortOrder.MODIFIED_DESC,
    val searchState: LibrarySearchState = LibrarySearchState.Idle,
    val smartNameStates: Map<String, SmartNameUiState> = emptyMap(),
    val actionState: LibraryActionState = LibraryActionState.Idle,
)

private data class LibraryControls(
    val query: String,
    val folderId: String?,
    val sortOrder: LibrarySortOrder,
)

private data class LibraryContent(
    val documents: List<LibraryDocumentSummary>,
    val recentDocuments: List<LibraryDocumentSummary>,
    val folders: List<LibraryFolder>,
)

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = LibraryRepository(application)
    private val query = MutableStateFlow("")
    private val selectedFolderId = MutableStateFlow<String?>(null)
    private val sortOrder = MutableStateFlow(LibrarySortOrder.MODIFIED_DESC)
    private val searchLimit = MutableStateFlow(LIBRARY_SEARCH_INITIAL_LIMIT)
    private val actionState = MutableStateFlow<LibraryActionState>(LibraryActionState.Idle)
    private val smartNameStates = MutableStateFlow<Map<String, SmartNameUiState>>(emptyMap())
    private val nextEventId = AtomicLong(0L)
    private val nextSmartNameRequestId = AtomicLong(0L)
    private val smartNameRequestIds = mutableMapOf<String, Long>()

    private val documents = combine(selectedFolderId, sortOrder) { folderId, sort ->
        folderId to sort
    }.flatMapLatest { (folderId, sort) -> repository.observeDocuments(folderId, sort) }

    private val searchState = librarySearchStateFlow(query, searchLimit) { value, limit ->
        repository.observeSearchHits(value, limit = limit) ?: flowOf(emptyList())
    }

    private val recentDocuments = repository.observeDocuments(
        folderId = null,
        sortOrder = LibrarySortOrder.MODIFIED_DESC,
    )
    private val content = combine(
        documents,
        recentDocuments,
        repository.observeFolders(),
        ::LibraryContent,
    )
    private val controls = combine(query, selectedFolderId, sortOrder, ::LibraryControls)

    val uiState = combine(content, controls, searchState, smartNameStates, actionState) {
            content, controls, search, smartNames, action ->
        LibraryUiState(
            documents = content.documents,
            recentDocuments = content.recentDocuments,
            folders = content.folders,
            folderBreadcrumb = content.folders.breadcrumbTo(controls.folderId),
            query = controls.query,
            selectedFolderId = controls.folderId,
            sortOrder = controls.sortOrder,
            searchState = search,
            smartNameStates = smartNames,
            actionState = action,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LibraryUiState(),
    )

    fun updateQuery(value: String) {
        val updated = value.take(160)
        if (updated != query.value) searchLimit.value = LIBRARY_SEARCH_INITIAL_LIMIT
        query.value = updated
    }

    fun loadMoreSearchResults() {
        if (query.value.isUsefulLibrarySearchQuery()) {
            searchLimit.value = (searchLimit.value + LIBRARY_SEARCH_INITIAL_LIMIT)
                .coerceAtMost(LIBRARY_SEARCH_MAX_LIMIT)
        }
    }

    fun requestSmartName(documentId: String) {
        val requestId = nextSmartNameRequestId.incrementAndGet()
        smartNameRequestIds[documentId] = requestId
        smartNameStates.update { it + (documentId to SmartNameUiState.Loading) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { repository.suggestDocumentName(documentId) }
            if (smartNameRequestIds[documentId] != requestId) return@launch
            val state = when (result) {
                is LibraryResult.Success -> result.value?.let(SmartNameUiState::Available)
                    ?: SmartNameUiState.Unavailable
                is LibraryResult.Failure -> SmartNameUiState.Unavailable
            }
            smartNameStates.update { it + (documentId to state) }
        }
    }

    fun selectFolder(folderId: String?) {
        selectedFolderId.value = folderId
        searchLimit.value = LIBRARY_SEARCH_INITIAL_LIMIT
        query.value = ""
    }

    fun updateSortOrder(value: LibrarySortOrder) {
        sortOrder.value = value
    }

    fun consumeActionState() {
        actionState.value = LibraryActionState.Idle
    }

    suspend fun openDocument(documentId: String): LibraryResult<OpenedLibraryDocument> {
        val result = withContext(Dispatchers.IO) { repository.openDocument(documentId) }
        if (result is LibraryResult.Failure) {
            actionState.value = LibraryActionState.Failed(
                nextEventId.incrementAndGet(),
                result.reason,
            )
        }
        return result
    }

    suspend fun saveSession(
        session: DocumentSession,
        title: String,
        ocrResult: OcrResult?,
        ocrScript: OcrScript? = null,
    ): LibraryResult<SavedLibraryDocument> {
        if (!beginSuspendingAction()) {
            return LibraryResult.Failure(LibraryError.OPERATION_INTERRUPTED)
        }
        return try {
            val result = withContext(Dispatchers.IO) {
                repository.saveSession(
                    session,
                    title,
                    ocrResult = ocrResult,
                    ocrScript = ocrScript,
                )
            }
            publish(LibraryActionSuccess.SAVED, result)
        } catch (error: CancellationException) {
            actionState.value = LibraryActionState.Idle
            throw error
        }
    }

    suspend fun indexOcr(
        documentId: String,
        pageIds: List<String?>,
        result: OcrResult,
    ): LibraryResult<Unit> {
        if (!beginSuspendingAction()) {
            return LibraryResult.Failure(LibraryError.OPERATION_INTERRUPTED)
        }
        return try {
            val indexed = withContext(Dispatchers.IO) {
                repository.indexOcr(documentId, pageIds, result)
            }
            publish(LibraryActionSuccess.OCR_INDEXED, indexed, announceSuccess = false)
        } catch (error: CancellationException) {
            actionState.value = LibraryActionState.Idle
            throw error
        }
    }

    suspend fun captureOcrPageSnapshots(
        documentId: String,
        orderedPageIds: List<String>,
    ): List<LibraryOcrPageSnapshot> = withContext(Dispatchers.IO) {
        repository.captureOcrPageSnapshots(documentId, orderedPageIds)
    }

    suspend fun indexOcr(
        documentId: String,
        outcomes: List<LibraryOcrPageOutcomeDraft>,
    ): LibraryOcrCommitResult = withContext(Dispatchers.IO) {
        repository.indexOcr(documentId, outcomes)
    }

    suspend fun extractPages(
        documentId: String,
        pageIds: Set<String>,
        title: String,
        removeFromOriginal: Boolean,
    ): LibraryResult<SavedLibraryDocument> {
        if (!beginSuspendingAction()) {
            return LibraryResult.Failure(LibraryError.OPERATION_INTERRUPTED)
        }
        return try {
            val result = withContext(Dispatchers.IO) {
                repository.extractPages(documentId, pageIds, title, removeFromOriginal)
            }
            publish(
                if (removeFromOriginal) LibraryActionSuccess.SPLIT else LibraryActionSuccess.EXTRACTED,
                result,
            )
        } catch (error: CancellationException) {
            actionState.value = LibraryActionState.Idle
            throw error
        }
    }

    fun renameDocument(documentId: String, title: String) = launchAction(
        LibraryActionSuccess.RENAMED,
    ) { repository.renameDocument(documentId, title) }

    fun renameDocument(documentId: String, title: String, onSuccess: () -> Unit) = launchAction(
        LibraryActionSuccess.RENAMED,
        onSuccess,
    ) { repository.renameDocument(documentId, title) }

    fun moveDocument(documentId: String, folderId: String?) = launchAction(
        LibraryActionSuccess.MOVED,
    ) { repository.moveDocument(documentId, folderId) }

    fun moveDocument(documentId: String, folderId: String?, onSuccess: () -> Unit) = launchAction(
        LibraryActionSuccess.MOVED,
        onSuccess,
    ) { repository.moveDocument(documentId, folderId) }

    fun deleteDocument(documentId: String) = launchAction(
        LibraryActionSuccess.DELETED,
    ) { repository.deleteDocument(documentId) }

    fun createFolder(name: String, parentFolderId: String? = selectedFolderId.value) = launchAction(
        LibraryActionSuccess.FOLDER_CREATED,
    ) { repository.createFolder(name, parentFolderId) }

    fun renameFolder(folderId: String, name: String) = launchAction(
        LibraryActionSuccess.FOLDER_RENAMED,
    ) { repository.renameFolder(folderId, name) }

    fun moveFolder(folderId: String, parentFolderId: String?) = launchAction(
        LibraryActionSuccess.FOLDER_MOVED,
    ) { repository.moveFolder(folderId, parentFolderId) }

    fun deleteFolder(folderId: String) = launchAction(
        LibraryActionSuccess.FOLDER_DELETED,
    ) { repository.deleteFolder(folderId) }

    fun deleteFolder(folderId: String, onSuccess: () -> Unit) = launchAction(
        LibraryActionSuccess.FOLDER_DELETED,
        onSuccess,
    ) { repository.deleteFolder(folderId) }

    fun mergeDocuments(documentIds: List<String>, title: String) = launchAction(
        LibraryActionSuccess.MERGED,
    ) { repository.mergeDocuments(documentIds, title) }

    fun thumbnailUri(relativePath: String?): Uri? = repository.thumbnailUri(relativePath)

    suspend fun cleanupDocumentRevisions(documentId: String) {
        withContext(Dispatchers.IO) { repository.cleanupDocumentRevisions(documentId) }
    }

    private fun beginSuspendingAction(): Boolean {
        if (actionState.value == LibraryActionState.Working) return false
        actionState.value = LibraryActionState.Working
        return true
    }

    private fun <T> launchAction(
        success: LibraryActionSuccess,
        onSuccess: (() -> Unit)? = null,
        block: suspend () -> LibraryResult<T>,
    ) {
        if (actionState.value == LibraryActionState.Working) return
        actionState.value = LibraryActionState.Working
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { block() }
            publish(success, result)
            if (result is LibraryResult.Success) onSuccess?.invoke()
        }
    }

    private fun <T> publish(
        success: LibraryActionSuccess,
        result: LibraryResult<T>,
        announceSuccess: Boolean = true,
    ): LibraryResult<T> {
        actionState.value = when (result) {
            is LibraryResult.Success -> if (announceSuccess) {
                LibraryActionState.Succeeded(nextEventId.incrementAndGet(), success, result.warning)
            } else {
                LibraryActionState.Idle
            }
            is LibraryResult.Failure -> LibraryActionState.Failed(
                nextEventId.incrementAndGet(),
                result.reason,
            )
        }
        return result
    }
}

private const val SEARCH_DEBOUNCE_MILLIS = 250L

@OptIn(ExperimentalCoroutinesApi::class)
internal fun librarySearchStateFlow(
    query: Flow<String>,
    limit: Flow<Int>,
    debounceMillis: Long = SEARCH_DEBOUNCE_MILLIS,
    search: (query: String, limit: Int) -> Flow<List<LibrarySearchHit>>,
): Flow<LibrarySearchState> = combine(query, limit) { value, requestedLimit ->
    value.trim() to requestedLimit
}.flatMapLatest { (value, requestedLimit) ->
    flow {
        when {
            value.isBlank() -> emit(LibrarySearchState.Idle)
            !value.isUsefulLibrarySearchQuery() -> emit(LibrarySearchState.TooShort(value))
            else -> {
                emit(LibrarySearchState.Loading(value))
                delay(debounceMillis)
                search(value, requestedLimit + 1).collect { hits ->
                    val hasMoreResults = hits.size > requestedLimit
                    emit(
                        LibrarySearchState.Results(
                            query = value,
                            hits = hits.take(requestedLimit),
                            canLoadMore = hasMoreResults &&
                                requestedLimit < LIBRARY_SEARCH_MAX_LIMIT,
                            hasMoreResults = hasMoreResults,
                        ),
                    )
                }
            }
        }
    }.catch {
        emit(LibrarySearchState.Results(value, emptyList(), canLoadMore = false))
    }
}

internal fun List<LibraryFolder>.breadcrumbTo(folderId: String?): List<LibraryFolder> {
    if (folderId == null) return emptyList()
    val foldersById = associateBy(LibraryFolder::id)
    val reversed = mutableListOf<LibraryFolder>()
    val visited = mutableSetOf<String>()
    var currentId: String? = folderId
    while (currentId != null && visited.add(currentId)) {
        val folder = foldersById[currentId] ?: break
        reversed += folder
        currentId = folder.parentFolderId
    }
    return reversed.asReversed()
}
