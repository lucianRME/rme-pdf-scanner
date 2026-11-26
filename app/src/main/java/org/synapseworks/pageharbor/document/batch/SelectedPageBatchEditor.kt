package org.synapseworks.pageharbor.document.batch

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.synapseworks.pageharbor.document.session.*

internal data class BatchDocumentCapture(val revision: Long, val session: DocumentSession)
enum class BatchEditOutcome { DRAFT, APPLIED, NO_CHANGES, CANCELLED, FAILED, STALE, UNDONE }
data class BatchEditState(
    val captureId: Long,
    val pages: List<DocumentPage>,
    val selectedIds: Set<Long> = emptySet(),
    val edit: BatchPageEdit = BatchPageEdit(),
    val previewId: Long? = null,
    val busy: Boolean = false,
    val outcome: BatchEditOutcome = BatchEditOutcome.DRAFT,
    val changedPages: Int = 0,
    val canUndo: Boolean = false,
)

/** Retained by the session ViewModel; drafts are deliberately not persisted across process death. */
class SelectedPageBatchEditor internal constructor(
    private val scope: CoroutineScope,
    private val current: () -> BatchDocumentCapture,
    private val commit: (BatchDocumentCapture, DocumentSession) -> Boolean,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val prepare: (DocumentSession, Set<Long>, BatchPageEdit, () -> Unit, (Int) -> Unit) -> PreparedBatchEdit = ::prepareBatchEdit,
) {
    var state: BatchEditState? by mutableStateOf(null)
        private set
    private val mutableProgress = MutableStateFlow(0L to 0)
    val progress = mutableProgress.asStateFlow()
    private var capture: BatchDocumentCapture? = null
    private var job: Job? = null
    private var epoch = 0L
    private var publishing = false
    private data class Undo(val before: DocumentSession, val expected: BatchDocumentCapture)
    private var undo: Undo? = null
    internal var undoCommitted:((BatchDocumentCapture,(Boolean)->Unit)->Boolean)?=null
    internal var cancelCommitted:(()->Unit)?=null
    internal var externalUndoAvailable:(()->Boolean)?=null
    internal val canUndoCommitted get()=state?.canUndo==true&&(externalUndoAvailable?.invoke()!=false)


    fun open() {
        close()
        val captured = current()
        capture = captured
        state = BatchEditState(++epoch, captured.session.pages)
    }
    fun close() {
        if(publishing)cancelCommitted?.invoke()
        publishing=false
        ++epoch; mutableProgress.value = epoch to 0; job?.cancel(); job = null; undo = null; capture = null; state = null
    }
    private fun matches(expected: BatchDocumentCapture): Boolean {
        val now = current()
        return now.revision == expected.revision && now.session === expected.session
    }
    /** Called synchronously by the document owner for every effective session mutation. */
    internal fun onDocumentChanged() {
        if (publishing || state == null) return
        ++epoch; mutableProgress.value = epoch to 0; job?.cancel(); job = null; undo = null
        state = state?.copy(selectedIds = emptySet(), previewId = null, busy = false,
            outcome = BatchEditOutcome.STALE, canUndo = false)
    }
    private fun editable(): BatchEditState? {
        val s = state ?: return null
        if (s.busy || s.outcome == BatchEditOutcome.STALE) return null
        if (capture?.let(::matches) != true) { onDocumentChanged(); return null }
        return s
    }
    fun select(ids: Set<Long>) {
        val s = editable() ?: return
        if (s.pages.count { it.id.value in ids } != ids.size) return
        state = s.copy(selectedIds = ids.toSet(), previewId = s.previewId?.takeIf { it in ids }
            ?: s.pages.firstOrNull { it.id.value in ids }?.id?.value)
    }
    fun selectAll() { editable()?.let { select(it.pages.map { p -> p.id.value }.toSet()) } }
    fun selectRange(text: String): Boolean {
        val s = editable() ?: return false
        val ids = batchRangeIds(text, s.pages) ?: return false
        select(ids); return true
    }
    fun preview(id: Long) { editable()?.let { if (id in it.selectedIds) state = it.copy(previewId = id) } }
    fun setEdit(edit: BatchPageEdit) { editable()?.let { state = it.copy(edit = edit, outcome = BatchEditOutcome.DRAFT) } }
    fun cancel() {
        val s = state ?: return
        if (!s.busy) return
        if(publishing)cancelCommitted?.invoke()
        publishing=false
        ++epoch; mutableProgress.value = epoch to 0; job?.cancel(); job = null
        state = s.copy(busy = false, outcome = BatchEditOutcome.CANCELLED)
    }
    fun apply() {
        val s = editable() ?: return
        if (s.selectedIds.isEmpty()) return
        val expected = capture ?: return
        val token = ++epoch
        mutableProgress.value = token to 0
        state = s.copy(busy = true, outcome = BatchEditOutcome.DRAFT)
        job = scope.launch {
            try {
                val prepared = withContext(dispatcher) {
                    val context = currentCoroutineContext()
                    prepare(expected.session, s.selectedIds, s.edit, { context.ensureActive() },
                        { completed -> mutableProgress.update { if (it.first == token) token to completed else it } })
                }
                ensureActive()
                if (token != epoch) return@launch
                if (!matches(expected)) { onDocumentChanged(); return@launch }
                if (prepared.changedPages == 0) {
                    state = s.copy(outcome = BatchEditOutcome.NO_CHANGES, canUndo = undo != null)
                } else {
                    publishing = true
                    val committed = try { commit(expected, prepared.session) } finally { publishing = false }
                    if (!committed) { onDocumentChanged(); return@launch }
                    val after = current()
                    capture = after
                    undo = Undo(expected.session, after)
                    state = s.copy(pages = after.session.pages, edit = BatchPageEdit(),
                        outcome = BatchEditOutcome.APPLIED, changedPages = prepared.changedPages, canUndo = true)
                }
            } catch (_: CancellationException) {
                if (token == epoch) state = s.copy(outcome = BatchEditOutcome.CANCELLED)
            } catch (_: Exception) {
                if (token == epoch) state = s.copy(outcome = BatchEditOutcome.FAILED)
            } catch (_: OutOfMemoryError) {
                if (token == epoch) state = s.copy(outcome = BatchEditOutcome.FAILED)
            }
        }
    }
    /** Delegates to the matching newest general entry when installed; standalone users keep one guarded batch Undo. */
    fun undo() {
        val s = editable() ?: return
        val previous = undo ?: return
        if (!matches(previous.expected)) { onDocumentChanged(); return }
        val external=undoCommitted
        if(external!=null){
            val token=++epoch;publishing=true;state=s.copy(busy=true)
            val started=external(previous.expected){success->
                if(token==epoch){
                    publishing=false
                    if(success){capture=current();undo=null;state=s.copy(pages=current().session.pages,edit=BatchPageEdit(),outcome=BatchEditOutcome.UNDONE,canUndo=false,busy=false)}
                    else state=s.copy(outcome=BatchEditOutcome.FAILED,busy=false)
                }
            }
            if(!started){publishing=false;undo=null;state=s.copy(canUndo=false,busy=false)}
            return
        }
        publishing = true
        val committed = try { commit(previous.expected, previous.before) } finally { publishing = false }
        if (!committed) { onDocumentChanged(); return }
        capture = current(); undo = null
        state = s.copy(pages = current().session.pages, edit = BatchPageEdit(),
            outcome = BatchEditOutcome.UNDONE, canUndo = false)
    }
}
