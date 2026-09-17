package org.synapseworks.pageharbor.library

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.synapseworks.pageharbor.document.session.DocumentSession
import org.synapseworks.pageharbor.ocr.OcrResult

enum class LibraryActionSuccess {
    SAVED,
    RENAMED,
    MOVED,
    DELETED,
    FOLDER_CREATED,
    FOLDER_RENAMED,
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
    val folders: List<LibraryFolder> = emptyList(),
    val query: String = "",
    val selectedFolderId: String? = null,
    val sortOrder: LibrarySortOrder = LibrarySortOrder.MODIFIED_DESC,
    val actionState: LibraryActionState = LibraryActionState.Idle,
)

private data class LibraryControls(
    val query: String,
    val folderId: String?,
    val sortOrder: LibrarySortOrder,
)

private data class LibraryContent(
    val documents: List<LibraryDocumentSummary>,
    val folders: List<LibraryFolder>,
)

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = LibraryRepository(application)
    private val query = MutableStateFlow("")
    private val selectedFolderId = MutableStateFlow<String?>(null)
    private val sortOrder = MutableStateFlow(LibrarySortOrder.MODIFIED_DESC)
    private val actionState = MutableStateFlow<LibraryActionState>(LibraryActionState.Idle)
    private val nextEventId = AtomicLong(0L)

    private val documents = combine(query, selectedFolderId, sortOrder) { query, folderId, sort ->
        Triple(query, folderId, sort)
    }.flatMapLatest { (query, folderId, sort) ->
        if (query.isBlank()) {
            repository.observeDocuments(folderId, sort)
        } else {
            repository.observeSearch(query) ?: flowOf(emptyList())
        }
    }

    private val content = combine(documents, repository.observeFolders(), ::LibraryContent)
    private val controls = combine(query, selectedFolderId, sortOrder, ::LibraryControls)

    val uiState = combine(content, controls, actionState) { content, controls, action ->
        LibraryUiState(
            documents = content.documents,
            folders = content.folders,
            query = controls.query,
            selectedFolderId = controls.folderId,
            sortOrder = controls.sortOrder,
            actionState = action,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LibraryUiState(),
    )

    fun updateQuery(value: String) {
        query.value = value.take(160)
    }

    fun selectFolder(folderId: String?) {
        selectedFolderId.value = folderId
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
    ): LibraryResult<SavedLibraryDocument> {
        if (!beginSuspendingAction()) {
            return LibraryResult.Failure(LibraryError.OPERATION_INTERRUPTED)
        }
        return try {
            val result = withContext(Dispatchers.IO) {
                repository.saveSession(session, title, ocrResult = ocrResult)
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

    fun createFolder(name: String) = launchAction(
        LibraryActionSuccess.FOLDER_CREATED,
    ) { repository.createFolder(name) }

    fun renameFolder(folderId: String, name: String) = launchAction(
        LibraryActionSuccess.FOLDER_RENAMED,
    ) { repository.renameFolder(folderId, name) }

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
