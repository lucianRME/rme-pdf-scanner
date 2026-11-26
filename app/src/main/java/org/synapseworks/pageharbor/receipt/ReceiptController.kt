package org.synapseworks.pageharbor.receipt

import java.util.UUID

internal enum class ReceiptPhase { CAMERA, PAUSED, PICKER, CAPTURING, OCR, EDIT, REVIEW }
internal class ReceiptToken(val owner: String, val epoch: Long)
internal data class ReceiptState(val owner: String, val epoch: Long, val phase: ReceiptPhase = ReceiptPhase.CAMERA,
    val extraction: ReceiptExtraction? = null, val draft: ReceiptDraft = ReceiptDraft(), val rules: ReceiptRules = ReceiptRules(),
    val reviewed: Map<ReceiptField,String>? = null, val problem: String? = null) {
    override fun toString() = "ReceiptState(<redacted>)"
}

/** Pure ownership/epoch gate. No source URI or draft survives process death. */
internal class ReceiptController {
    var state: ReceiptState? = null; private set
    private var epoch = 0L
    private var active = true
    private var picker: ReceiptToken? = null
    @Synchronized fun setActive(value: Boolean) { active = value }
    @Synchronized fun open() { if(active && state == null) state = ReceiptState(UUID.randomUUID().toString(), ++epoch) }
    @Synchronized fun close() { epoch++; state = null }
    @Synchronized fun matches(t: ReceiptToken, phase: ReceiptPhase? = null): Boolean = active && state?.let {
        it.owner==t.owner && it.epoch==t.epoch && (phase==null || it.phase==phase)
    } == true
    @Synchronized fun reset() { val s = state ?: return; if(active) state = ReceiptState(s.owner, ++epoch) }
    @Synchronized fun background(recreating: Boolean) {
        active = false; val s = state ?: return
        if(s.phase==ReceiptPhase.PICKER) return
        if(recreating && s.phase in setOf(ReceiptPhase.EDIT, ReceiptPhase.REVIEW)) return
        state = ReceiptState(s.owner, ++epoch, ReceiptPhase.PAUSED)
    }
    @Synchronized fun lock(pendingInput: Boolean = false) {
        active = false
        if(state?.phase!=ReceiptPhase.PICKER && !(pendingInput && state?.phase==ReceiptPhase.OCR)) close()
    }
    @Synchronized fun pause(owner: String, generation: Long, message: String) {
        if(!matches(ReceiptToken(owner,generation)) || state?.phase !in setOf(ReceiptPhase.CAMERA,ReceiptPhase.CAPTURING)) return
        state = ReceiptState(owner,++epoch,ReceiptPhase.PAUSED,problem=message)
    }
    @Synchronized fun capture(owner: String, generation: Long): ReceiptToken? {
        val t = ReceiptToken(owner,generation); if(!matches(t,ReceiptPhase.CAMERA)) return null
        state = state!!.copy(phase=ReceiptPhase.CAPTURING); return t
    }
    @Synchronized fun captured(t: ReceiptToken): Boolean {
        if(!matches(t,ReceiptPhase.CAPTURING)) return false
        state = state!!.copy(phase=ReceiptPhase.OCR); return true
    }
    @Synchronized fun beginPicker(owner: String, generation: Long): Boolean {
        val s = state ?: return false
        if(!matches(ReceiptToken(owner,generation)) || s.phase in setOf(ReceiptPhase.CAPTURING,ReceiptPhase.OCR,ReceiptPhase.PICKER)) return false
        if(picker!=null) { state=s.copy(problem="An earlier image selection is still pending. Return from it first."); return false }
        picker=ReceiptToken(owner,++epoch); state=ReceiptState(owner,epoch,ReceiptPhase.PICKER); return true
    }
    @Synchronized fun picked(hasImage: Boolean): ReceiptToken? {
        val t=picker ?: return null; picker=null; val s=state ?: return null
        if(s.owner!=t.owner || s.epoch!=t.epoch || s.phase!=ReceiptPhase.PICKER) return null
        state=s.copy(phase=if(hasImage) ReceiptPhase.OCR else ReceiptPhase.PAUSED)
        return if(hasImage) t else null
    }
    @Synchronized fun complete(t: ReceiptToken, extraction: ReceiptExtraction?) {
        if(!matches(t,ReceiptPhase.OCR)) return
        state=if(extraction!=null) state!!.copy(phase=ReceiptPhase.EDIT,extraction=extraction,draft=extraction.draft())
        else state!!.copy(phase=ReceiptPhase.PAUSED,problem="Receipt recognition could not finish within supported limits. Retry or enter fields manually.")
    }
    @Synchronized fun manual() { val s=state ?: return
        if(active && s.phase in setOf(ReceiptPhase.CAMERA,ReceiptPhase.PAUSED)) state=s.copy(epoch=++epoch,phase=ReceiptPhase.EDIT,problem=null)
    }
    @Synchronized fun edit(owner: String, generation: Long, field: ReceiptField, value: String) {
        if(matches(ReceiptToken(owner,generation),ReceiptPhase.EDIT)) state=state!!.copy(draft=state!!.draft.edit(field,value),problem=null)
    }
    @Synchronized fun rules(owner: String, generation: Long, rules: ReceiptRules) {
        if(!matches(ReceiptToken(owner,generation),ReceiptPhase.EDIT)) return
        val s=state!!; val next=s.extraction?.let { ReceiptParser.reparse(it,rules) }
        val proposed=next?.draft()?.values.orEmpty()
        val values=ReceiptField.entries.associateWith { if(it in s.draft.corrected) s.draft.values[it].orEmpty() else proposed[it].orEmpty() }
        state=s.copy(rules=rules,extraction=next,draft=s.draft.copy(values=values),problem=null)
    }
    @Synchronized fun review(owner: String, generation: Long) {
        if(!matches(ReceiptToken(owner,generation),ReceiptPhase.EDIT)) return
        val s=state!!; val normalized=s.draft.normalized(s.rules)
        state=if(normalized!=null) s.copy(phase=ReceiptPhase.REVIEW,reviewed=normalized,problem=null)
        else s.copy(problem="Correct or clear invalid fields. Use a supported date, currency and exact amount format.")
    }
    @Synchronized fun correct() { if(active && state?.phase==ReceiptPhase.REVIEW) state=state!!.copy(phase=ReceiptPhase.EDIT,reviewed=null) }
}
