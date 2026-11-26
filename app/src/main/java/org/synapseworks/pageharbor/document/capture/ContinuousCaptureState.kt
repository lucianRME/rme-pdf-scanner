package org.synapseworks.pageharbor.document.capture

import java.io.File
import java.util.UUID
import org.synapseworks.pageharbor.document.session.DocumentImageMetadata

internal data class CapturedPage(val file:File,val metadata:DocumentImageMetadata)
internal enum class CaptureMode { DOCUMENT, BATCH, BOOK }
internal enum class CapturePhase { CAMERA, REVIEW }
internal enum class CaptureProblem { PERMISSION, CAMERA, STORAGE, IMAGE, STALE }
internal class CaptureTicket(val owner:String,val generation:Long,val sequence:Long, val singlePage: Boolean = false)
internal data class ContinuousCaptureState(val owner:String,val revision:Long,
    val pages:List<CapturedPage> = emptyList(),val phase:CapturePhase=CapturePhase.CAMERA,
    val paused:Boolean=false,val busy:Boolean=false,val finishing:Boolean=false,val problem:CaptureProblem?=null,
    val replacing:CapturedPage?=null, val mode: CaptureMode = CaptureMode.BATCH) {
    val singlePage: Boolean get() = mode != CaptureMode.BATCH
    val canSelectMode: Boolean get() = phase == CapturePhase.CAMERA && pages.isEmpty() &&
        replacing == null && !busy && !finishing
}
internal enum class CaptureDisposition { ACCEPT, DISCARD, IGNORE }

/** Main-thread state owner; no camera, file I/O, bitmap, Activity or saved-state reference. */
internal class ContinuousCaptureController {
    var state:ContinuousCaptureState?=null; private set
    private var pending:CaptureTicket?=null
    private var generation=0L
    private var sequence=0L
    val canOpen:Boolean get()=state==null&&pending==null
    fun open(revision:Long, singlePage: Boolean = false):Boolean {
        if(!canOpen)return false
        val mode = if (singlePage) CaptureMode.DOCUMENT else CaptureMode.BATCH
        state=ContinuousCaptureState(UUID.randomUUID().toString(),revision, mode = mode);return true
    }
    fun selectMode(mode: CaptureMode) {
        val current = state ?: return
        if (current.canSelectMode && current.mode != mode) state = current.copy(mode = mode)
    }
    fun request():CaptureTicket? {
        val s=state?:return null
        if(s.phase!=CapturePhase.CAMERA||s.paused||s.finishing||pending!=null)return null
        return CaptureTicket(s.owner,generation,++sequence, s.singlePage).also{pending=it;state=s.copy(busy=true,problem=null)}
    }
    fun complete(ticket:CaptureTicket,page:CapturedPage?):CaptureDisposition {
        if(pending!==ticket)return CaptureDisposition.IGNORE
        pending=null
        val s=state
        if(s==null||s.owner!=ticket.owner)return CaptureDisposition.DISCARD
        val accept=ticket.generation==generation&&page!=null
        val replaced = s.replacing
        val pages = when {
            !accept -> s.pages
            replaced == null -> s.pages + requireNotNull(page)
            else -> s.pages.map { if (it === replaced) requireNotNull(page) else it }
        }
        val review = s.finishing || (accept && (replaced != null || s.singlePage))
        state = s.copy(
            pages = pages,
            busy = false,
            phase = if (review) CapturePhase.REVIEW else s.phase,
            replacing = if (review) null else replaced,
            problem = if (accept) null else if (ticket.generation != generation) s.problem else CaptureProblem.IMAGE,
        )
        return if(accept)CaptureDisposition.ACCEPT else CaptureDisposition.DISCARD
    }
    fun finish() {
        val current = state ?: return
        state = if (pending == null) {
            current.copy(finishing = true, phase = CapturePhase.REVIEW, replacing = null)
        } else current.copy(finishing = true)
    }
    fun pause(problem:CaptureProblem?=null) {generation++;state=state?.copy(paused=true,problem=problem)}
    fun resume() {state?.takeIf{it.phase==CapturePhase.CAMERA&&!it.finishing}?.let{state=it.copy(paused=false,problem=null)}}
    fun retake() = returnToCamera(null)

    fun replace(index: Int) {
        state?.pages?.getOrNull(index)?.let(::returnToCamera)
    }

    private fun returnToCamera(page: CapturedPage?) {
        val current = state?.takeIf { it.phase == CapturePhase.REVIEW && !it.busy } ?: return
        generation++
        state = current.copy(
            phase = CapturePhase.CAMERA,
            finishing = false,
            paused = true,
            problem = null,
            replacing = page,
        )
    }

    fun move(index: Int, offset: Int): Boolean =
        (offset == -1 || offset == 1) && moveTo(index, index + offset)

    fun moveTo(index: Int, destination: Int): Boolean {
        val current = state?.takeIf { it.phase == CapturePhase.REVIEW && !it.busy } ?: return false
        if (index == destination || index !in current.pages.indices || destination !in current.pages.indices) return false
        val pages = current.pages.toMutableList()
        pages.add(destination, pages.removeAt(index))
        state = current.copy(pages = pages)
        return true
    }
    /** Pending callback retains deletion ownership until it settles; reopening is blocked meanwhile. */
    fun cancel():List<CapturedPage> {val pages=state?.pages.orEmpty();generation++;state=null;return pages}
    fun release():List<CapturedPage> {
        val s=requireNotNull(state);check(s.phase==CapturePhase.REVIEW&&!s.busy)
        state=null;return s.pages
    }
}
