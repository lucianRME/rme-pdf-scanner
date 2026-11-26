package org.synapseworks.pageharbor.document.signature

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Retained memory-only drawing corrections and template management, outside document history. */
class SignatureWorkspace(private val scope: CoroutineScope) {
    data class Draft(val token: String, val templates: List<SignatureTemplate> = emptyList(), val strokes: List<SignatureStroke> = emptyList(),
        val ratio: Float = 3f, val strokeWidth: Float = .012f, val name: String = "", val selected: String? = null, val busy: Boolean = false, val error: String? = null)
    var state by mutableStateOf<Draft?>(null); private set
    private var job: Job? = null
    private var store: SignatureTemplateStore? = null
    fun open(store: SignatureTemplateStore) {
        cancel(); this.store = store; state = Draft(UUID.randomUUID().toString()); work { store.list() }
    }
    fun cancel() { job?.cancel(); job = null; state = null; store = null }
    private fun work(operation: suspend () -> List<SignatureTemplate>) {
        val s = state ?: return; if (s.busy) return
        state = s.copy(busy = true,error = null)
        job = scope.launch {
            try { val result = operation(); state?.takeIf { it.token == s.token }?.let { state = it.copy(templates = result,busy = false) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { state?.takeIf { it.token == s.token }?.let { state = it.copy(busy = false,error = "Signature library unavailable or limit reached. No change was accepted.") } }
        }
    }
    fun name(value: String) { val s = state ?: return; if (!s.busy) state = s.copy(name = value,error = null) }
    fun strokes(value: List<SignatureStroke>, ratio: Float) {
        val s = state ?: return; if (s.busy) return
        try {
            if (value.isNotEmpty()) HandwrittenSignature(value,s.strokeWidth,ratio)
            state = s.copy(strokes = value.toList(),ratio = ratio,error = null)
        } catch (_: IllegalArgumentException) { error("Drawing exceeds the handwriting limit. The stroke was rejected.") }
    }
    fun select(template: SignatureTemplate) { val s = state ?: return; if (!s.busy) state = s.copy(strokes = template.ink.strokes,ratio = template.ink.aspectRatio,strokeWidth = template.ink.strokeWidthFraction,name = template.name,selected = template.id,error = null) }
    fun reset() { val s = state ?: return; if (!s.busy) state = s.copy(strokes = emptyList(),selected = null,name = "",ratio = 3f,strokeWidth = .012f,error = null) }
    fun undoStroke() { val s = state ?: return; if (!s.busy) state = s.copy(strokes = s.strokes.dropLast(1),error = null) }
    fun error(message: String) { state = state?.copy(error = message) }
    fun ink(): HandwrittenSignature? = state?.let { runCatching { HandwrittenSignature(it.strokes,it.strokeWidth,it.ratio) }.getOrNull() }
    fun save(replace: Boolean) {
        val s = state ?: return; val storage = store ?: return; if (s.busy) return
        val ink = ink() ?: return error("Draw at least one stroke before saving.")
        val id = if (replace) s.selected ?: return else null
        try { SignatureTemplate(id ?: UUID.randomUUID().toString(),s.name,ink) }
        catch (_: IllegalArgumentException) { error("Enter a name of 1–80 visible characters."); return }
        work { storage.save(s.name,ink,id) }
    }
    fun delete() { val s = state ?: return; val storage = store ?: return; val id = s.selected ?: return
        if (s.busy) return
        work { storage.delete(id) }
        state = state?.copy(selected = null)
    }
    fun rename() { val s = state ?: return; val storage = store ?: return; val saved = s.templates.firstOrNull { it.id == s.selected } ?: return
        if (s.busy) return
        try { SignatureTemplate(saved.id,s.name,saved.ink) } catch (_: IllegalArgumentException) { error("Enter a name of 1–80 visible characters."); return }
        work { storage.save(s.name,saved.ink,saved.id) }
    }
}
