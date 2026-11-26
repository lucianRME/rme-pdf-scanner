package org.synapseworks.pageharbor.businesscard

import java.util.UUID

internal enum class CardPhase { CAMERA, PAUSED, PICKER, CAPTURING, OCR, EDIT, REVIEW, EDITOR, RETURNED }
internal class CardToken(val owner: String, val epoch: Long)
internal data class CardState(val owner: String, val epoch: Long, val phase: CardPhase = CardPhase.CAMERA,
    val suggestions: ContactSuggestions? = null, val draft: ContactDraft = ContactDraft(), val problem: String? = null) {
    override fun toString() = "CardState(<redacted>)"
}

internal class CardController {
    var state: CardState? = null; private set
    private var epoch = 0L
    private var active = true
    private var picker: CardToken? = null
    private var editor: CardToken? = null
    @Synchronized fun setActive(value: Boolean) { active = value }
    @Synchronized fun open() { if(active && state == null) state = CardState(UUID.randomUUID().toString(), ++epoch) }
    @Synchronized fun close() { epoch++; state = null }
    @Synchronized fun matches(token: CardToken, phase: CardPhase? = null): Boolean = active && state?.let {
        it.owner == token.owner && it.epoch == token.epoch && (phase == null || phase == it.phase)
    } == true
    @Synchronized fun pause(owner: String, generation: Long, message: String? = null) {
        if(!matches(CardToken(owner, generation)) || state?.phase !in setOf(CardPhase.CAMERA, CardPhase.CAPTURING)) return
        state = state!!.copy(epoch = ++epoch, phase = CardPhase.PAUSED, problem = message)
    }
    @Synchronized fun reset() { val s = state ?: return; if(active) state = CardState(s.owner, ++epoch) }
    @Synchronized fun background(recreating: Boolean) {
        active = false
        val s = state ?: return
        if(s.phase in setOf(CardPhase.PICKER, CardPhase.EDITOR)) return
        if(recreating && s.phase in setOf(CardPhase.EDIT, CardPhase.REVIEW, CardPhase.RETURNED)) return
        state = CardState(s.owner, ++epoch, CardPhase.PAUSED)
    }
    @Synchronized fun lock(keepPendingInput: Boolean = false) {
        active = false
        if(state?.phase !in setOf(CardPhase.PICKER, CardPhase.EDITOR) && !(keepPendingInput && state?.phase == CardPhase.OCR)) close()
    }
    @Synchronized fun capture(owner: String, generation: Long): CardToken? {
        val t = CardToken(owner, generation); if(!matches(t, CardPhase.CAMERA)) return null
        state = state!!.copy(phase = CardPhase.CAPTURING); return t
    }
    @Synchronized fun captured(token: CardToken): Boolean {
        if(!matches(token, CardPhase.CAPTURING)) return false
        state = state!!.copy(phase = CardPhase.OCR); return true
    }
    @Synchronized fun beginPicker(owner: String, generation: Long): Boolean {
        val s = state ?: return false
        if(!matches(CardToken(owner, generation)) || s.phase in setOf(CardPhase.CAPTURING, CardPhase.OCR, CardPhase.EDITOR)) return false
        if(picker != null) { state = s.copy(problem = "An earlier image selection is still pending. Return from that picker first."); return false }
        picker = CardToken(owner, ++epoch)
        state = CardState(owner, epoch, CardPhase.PICKER); return true
    }
    @Synchronized fun picked(hasImage: Boolean): CardToken? {
        val t = picker ?: return null; picker = null
        val s = state ?: return null
        if(s.owner != t.owner || s.epoch != t.epoch || s.phase != CardPhase.PICKER) return null
        state = s.copy(phase = if(hasImage) CardPhase.OCR else CardPhase.PAUSED)
        return if(hasImage) t else null
    }
    @Synchronized fun complete(token: CardToken, result: ContactSuggestions?) {
        if(!matches(token, CardPhase.OCR)) return
        state = if(result != null) state!!.copy(phase = CardPhase.EDIT, suggestions = result, draft = result.draft)
        else state!!.copy(phase = CardPhase.PAUSED, problem = "The card could not be recognized within supported limits. Retry or enter fields manually.")
    }
    @Synchronized fun manual() { val s = state ?: return; if(active && s.phase in setOf(CardPhase.PAUSED, CardPhase.CAMERA)) state = s.copy(epoch = ++epoch, phase = CardPhase.EDIT, problem = null) }
    @Synchronized fun edit(owner: String, generation: Long, update: (ContactDraft) -> ContactDraft) {
        if(matches(CardToken(owner, generation), CardPhase.EDIT)) state = state!!.copy(draft = update(state!!.draft), problem = null)
    }
    @Synchronized fun review(owner: String, generation: Long) {
        if(!matches(CardToken(owner, generation), CardPhase.EDIT)) return
        state = if(state!!.draft.valid()) state!!.copy(phase = CardPhase.REVIEW, draft = state!!.draft.cleaned(), problem = null)
            else state!!.copy(problem = "Enter at least one field and remove unsupported controls or over-limit values before review.")
    }
    @Synchronized fun correct() { if(active && state?.phase == CardPhase.REVIEW) state = state!!.copy(phase = CardPhase.EDIT) }
    @Synchronized fun handoff(owner: String, generation: Long): ContactDraft? {
        val t = CardToken(owner, generation)
        if(!matches(t, CardPhase.REVIEW) || editor != null || !state!!.draft.valid()) return null
        editor = t
        val draft = state!!.draft
        // Android owns the reviewed snapshot after explicit launch. Drop RME's draft immediately.
        state = CardState(owner, generation, CardPhase.EDITOR)
        return draft
    }
    @Synchronized fun editorReturned() {
        val t = editor ?: return; editor = null
        val s = state ?: return
        if(s.owner == t.owner && s.epoch == t.epoch && s.phase == CardPhase.EDITOR)
            state = CardState(s.owner, ++epoch, CardPhase.RETURNED, problem = "The contact editor returned. RME has not verified a saved contact; canceling does not create one through RME.")
    }
    @Synchronized fun editorFailed() {
        editor = null
        if(state?.phase == CardPhase.EDITOR) state = state!!.copy(phase = CardPhase.RETURNED,
            problem = "No contact editor could be opened. No contact was written by RME. Scan again or use your Contacts app.")
    }
}
