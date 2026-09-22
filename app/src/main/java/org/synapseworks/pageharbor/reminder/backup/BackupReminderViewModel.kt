package org.synapseworks.pageharbor.reminder.backup

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.synapseworks.pageharbor.library.LibraryDatabase
import org.synapseworks.pageharbor.library.LibraryOperationCoordinator
import java.util.concurrent.atomic.AtomicLong

data class BackupReminderPresentation(
    val documentCount: Int,
    val pageCount: Int,
)

/**
 * Session owner for the local-only backup reminder. Only aggregate counts and the library revision
 * cross this boundary; document titles, paths, URIs, and content are never persisted here.
 */
class BackupReminderViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = LibraryDatabase.get(application).libraryDao()
    private val coordinator = BackupReminderCoordinator(
        SharedPreferencesBackupReminderStateStore(application),
    )
    private val presentationGate = BackupReminderPresentationGate(coordinator)
    private val refreshRequests = Channel<BackupReminderRefreshRequest>(Channel.UNLIMITED)
    private val nextRefreshRequestId = AtomicLong(0L)
    private val refreshOrder = BackupReminderRefreshOrder()
    private val mutableLibrarySnapshot = MutableStateFlow<BackupReminderLibrarySnapshot?>(null)
    private val mutablePresentation = MutableStateFlow<BackupReminderPresentation?>(null)

    val librarySnapshot: StateFlow<BackupReminderLibrarySnapshot?> =
        mutableLibrarySnapshot.asStateFlow()
    val presentation: StateFlow<BackupReminderPresentation?> =
        mutablePresentation.asStateFlow()

    init {
        viewModelScope.launch {
            for (request in refreshRequests) processRefresh(request)
        }
        enqueueRefresh(recordMutation = true, meaningful = false)
    }

    fun recordSuccessfulLibraryMutation(meaningful: Boolean = true) {
        enqueueRefresh(recordMutation = true, meaningful = meaningful)
    }

    fun refreshEligibility() {
        enqueueRefresh(recordMutation = false, meaningful = false)
    }

    /** Called only after the reminder surface has actually entered the unlocked UI composition. */
    fun markPresented() {
        if (!presentationGate.markPresented()) mutablePresentation.value = null
    }

    fun dismiss() {
        presentationGate.dismiss()
        mutablePresentation.value = null
    }

    fun notNow() {
        presentationGate.dismiss()
        mutablePresentation.value = null
        refreshRequests.trySend(
            BackupReminderRefreshRequest.NotNow(nextRefreshRequestId.incrementAndGet()),
        )
    }

    override fun onCleared() {
        refreshRequests.close()
        super.onCleared()
    }

    private fun enqueueRefresh(recordMutation: Boolean, meaningful: Boolean) {
        refreshRequests.trySend(
            BackupReminderRefreshRequest.Refresh(
                requestId = nextRefreshRequestId.incrementAndGet(),
                recordMutation = recordMutation,
                meaningful = meaningful,
            ),
        )
    }

    private suspend fun processRefresh(request: BackupReminderRefreshRequest) {
        if (!refreshOrder.shouldStart(request.requestId)) return
        val snapshot = readSnapshot() ?: return
        if (!refreshOrder.shouldApply(request.requestId, snapshot.revision)) return
        mutableLibrarySnapshot.value = snapshot
        when (request) {
            is BackupReminderRefreshRequest.Refresh -> {
                if (request.recordMutation) {
                    coordinator.recordLibraryMutation(
                        outcome = BackupReminderMutationOutcome.SUCCESS,
                        library = snapshot,
                        meaningful = request.meaningful,
                    )
                }
                when (presentationGate.evaluate(snapshot)) {
                    BackupReminderPresentationDecision.SHOW -> {
                        mutablePresentation.value = BackupReminderPresentation(
                            documentCount = snapshot.documentCount,
                            pageCount = snapshot.pageCount,
                        )
                    }
                    BackupReminderPresentationDecision.KEEP -> Unit
                    BackupReminderPresentationDecision.HIDE -> mutablePresentation.value = null
                }
            }
            is BackupReminderRefreshRequest.NotNow -> {
                coordinator.markNotNow(snapshot)
                presentationGate.dismiss()
                mutablePresentation.value = null
            }
        }
    }

    private suspend fun readSnapshot(): BackupReminderLibrarySnapshot? = withContext(Dispatchers.IO) {
        try {
            LibraryOperationCoordinator.gate.withStableSnapshot {
                var afterRowId = 0L
                var documentCount = 0
                var pageCount = 0L
                while (true) {
                    val page = dao.activeDocumentsPage(afterRowId, QUERY_PAGE_SIZE)
                    if (page.isEmpty()) break
                    documentCount += page.size
                    page.forEach { document ->
                        pageCount += document.pageCount.toLong()
                        afterRowId = maxOf(afterRowId, document.rowId)
                    }
                    if (page.size < QUERY_PAGE_SIZE) break
                }
                BackupReminderLibrarySnapshot(
                    documentCount = documentCount,
                    pageCount = pageCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    revision = dao.metadata()?.libraryRevision ?: 0L,
                )
            }
        } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        const val QUERY_PAGE_SIZE = 256
    }
}

private sealed interface BackupReminderRefreshRequest {
    val requestId: Long

    data class Refresh(
        override val requestId: Long,
        val recordMutation: Boolean,
        val meaningful: Boolean,
    ) : BackupReminderRefreshRequest

    data class NotNow(
        override val requestId: Long,
    ) : BackupReminderRefreshRequest
}

internal class BackupReminderRefreshOrder {
    private var latestStartedRequestId = 0L
    private var lastAppliedRevision = BackupReminderState.NO_REVISION

    fun shouldStart(requestId: Long): Boolean {
        if (requestId <= latestStartedRequestId) return false
        latestStartedRequestId = requestId
        return true
    }

    fun shouldApply(requestId: Long, revision: Long): Boolean {
        if (requestId != latestStartedRequestId) return false
        if (
            lastAppliedRevision != BackupReminderState.NO_REVISION &&
            revision < lastAppliedRevision
        ) {
            return false
        }
        lastAppliedRevision = revision
        return true
    }
}
