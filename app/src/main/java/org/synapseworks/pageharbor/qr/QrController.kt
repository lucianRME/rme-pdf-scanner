package org.synapseworks.pageharbor.qr

import java.util.UUID

internal enum class QrPhase { CAMERA, PAUSED, PICKER, IMPORTING, RESULT }
internal enum class QrProblem { PERMISSION, CAMERA, IMAGE, NO_CODE, HANDOFF, PICKER_PENDING }
internal class QrTicket(val owner: String, val generation: Long, val sequence: Long)
internal data class QrState(val owner: String, val generation: Long, val phase: QrPhase = QrPhase.CAMERA,
    val result: QrPayload? = null, val problem: QrProblem? = null, val linkReview: Boolean = false, val copied: Boolean = false)

/** Synchronization covers worker eligibility only; the ViewModel publishes UI state on Main. */
internal class QrController {
    var state: QrState? = null; private set
    private var active = true
    private var generation = 0L
    private var sequence = 0L
    private var frame: QrTicket? = null
    private var picker: QrTicket? = null
    @Synchronized fun open(): Boolean {
        if (!active || state != null) return false
        state = QrState(UUID.randomUUID().toString(),++generation); return true
    }
    @Synchronized fun active(value: Boolean) { active = value }
    @Synchronized fun close() { generation++; frame=null; state=null }
    @Synchronized fun background(recreating: Boolean) {
        active=false; frame=null
        val s=state ?: return
        if (s.phase==QrPhase.PICKER && picker?.owner==s.owner && picker?.generation==s.generation) return
        state=if(recreating && s.phase==QrPhase.RESULT) s.copy(linkReview=false)
            else if(recreating && s.phase==QrPhase.CAMERA) s.copy(generation=++generation)
            else s.copy(generation=++generation,phase=QrPhase.PAUSED,result=null,linkReview=false,copied=false)
    }
    @Synchronized fun rescan() {
        val s=state ?: return; if(!active)return
        frame=null; state=s.copy(generation=++generation,phase=QrPhase.CAMERA,result=null,problem=null,linkReview=false,copied=false)
    }
    @Synchronized fun pause(problem: QrProblem?=null) {
        val s=state ?: return; frame=null
        state=s.copy(generation=++generation,phase=QrPhase.PAUSED,result=null,problem=problem,linkReview=false,copied=false)
    }
    @Synchronized fun frame(owner: String?=null, epoch: Long?=null): QrTicket? {
        val s=state ?: return null
        if(!active || s.phase!=QrPhase.CAMERA || frame!=null || (owner!=null && owner!=s.owner) || (epoch!=null && epoch!=s.generation))return null
        return QrTicket(s.owner,s.generation,++sequence).also{frame=it}
    }
    @Synchronized fun cameraAction(owner:String,epoch:Long)=active && state?.let{
        it.owner==owner && it.generation==epoch && it.phase==QrPhase.CAMERA
    }==true
    @Synchronized fun currentFrame(t: QrTicket) = active && frame===t &&
        state?.let{it.owner==t.owner && it.generation==t.generation && it.phase==QrPhase.CAMERA}==true
    @Synchronized fun completeFrame(t: QrTicket, decoded: QrDecode) {
        val accept=currentFrame(t); if(frame===t)frame=null
        if(accept && decoded is QrDecode.Found)state=state!!.copy(phase=QrPhase.RESULT,result=decoded.payload,problem=null)
    }
    @Synchronized fun beginPicker(owner: String?=null, epoch: Long?=null): QrTicket? {
        val s=state ?: return null
        if(!active || s.phase==QrPhase.IMPORTING || (owner!=null && owner!=s.owner) || (epoch!=null && epoch!=s.generation))return null
        if(picker!=null){state=s.copy(problem=QrProblem.PICKER_PENDING);return null}
        frame=null
        val t=QrTicket(s.owner,++generation,++sequence);picker=t
        state=s.copy(generation=t.generation,phase=QrPhase.PICKER,result=null,linkReview=false,problem=null,copied=false)
        return t
    }
    @Synchronized fun receivePicker(hasImage: Boolean): QrTicket? {
        val t=picker ?: return null;picker=null
        val s=state ?: return null
        if(s.owner!=t.owner || s.generation!=t.generation || s.phase!=QrPhase.PICKER)return null
        state=s.copy(phase=if(hasImage)QrPhase.IMPORTING else QrPhase.PAUSED,problem=null)
        return t.takeIf{hasImage}
    }
    @Synchronized fun currentImport(t: QrTicket) = active && state?.let {
        it.owner==t.owner && it.generation==t.generation && it.phase==QrPhase.IMPORTING
    }==true
    @Synchronized fun completeImport(t: QrTicket, decoded: QrDecode) {
        if(!currentImport(t))return
        state=if(decoded is QrDecode.Found)state!!.copy(phase=QrPhase.RESULT,result=decoded.payload,problem=null)
            else state!!.copy(phase=QrPhase.PAUSED,problem=if(decoded==QrDecode.NoCode)QrProblem.NO_CODE else QrProblem.IMAGE)
    }
    @Synchronized fun result(owner: String, epoch: Long): QrPayload? = state?.takeIf {
        active && it.owner==owner && it.generation==epoch && it.phase==QrPhase.RESULT
    }?.result
    @Synchronized fun reviewLink(owner: String, epoch: Long) {
        if(result(owner,epoch)?.destination!=null)state=state!!.copy(linkReview=true)
    }
    @Synchronized fun dismissLink(){state=state?.copy(linkReview=false)}
    @Synchronized fun destination(owner: String, epoch: Long): QrDestination? =
        result(owner,epoch)?.takeIf{state?.linkReview==true}?.destination
    @Synchronized fun copied(owner: String, epoch: Long){if(result(owner,epoch)!=null)state=state!!.copy(copied=true)}
    @Synchronized fun handoffFailed(){if(state?.phase==QrPhase.RESULT)state=state!!.copy(problem=QrProblem.HANDOFF,linkReview=false)}
}
