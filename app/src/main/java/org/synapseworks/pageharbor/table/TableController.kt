package org.synapseworks.pageharbor.table

import java.util.UUID

internal enum class TablePhase { CAMERA,PAUSED,IMAGE,CAPTURING,OCR,EDIT,REVIEW,DESTINATION,WAITING,SAVING }
internal class TableToken(val owner:String,val epoch:Long)
internal data class TableState(val owner:String,val epoch:Long,val phase:TablePhase=TablePhase.CAMERA,
    val extraction:TableExtraction?=null,val grid:TableGrid=TableGrid.blank(),val review:TableReview?=null,
    val confirmed:Boolean=false,val layoutChanged:Boolean=false,val problem:String?=null) {
    override fun toString()="TableState(<redacted>)"
}
/** Opaque pending reservations survive invalidation only to drain their original callbacks. */
internal class TableController {
    var state:TableState?=null;private set
    private var active=true;private var epoch=0L
    private var image:TableToken?=null;private var destination:TableSaveToken?=null;private var export:TableExport?=null
    @Synchronized fun setActive(value:Boolean){active=value}
    @Synchronized fun open(){if(active&&state==null)state=TableState(UUID.randomUUID().toString(),++epoch)}
    @Synchronized fun close(){epoch++;state=null;export=null}
    @Synchronized fun matches(token:TableToken,phase:TablePhase?=null)=active&&state?.let{it.owner==token.owner&&it.epoch==token.epoch&&(phase==null||it.phase==phase)}==true
    @Synchronized fun reset(){val s=state?:return;if(active){state=TableState(s.owner,++epoch);export=null}}
    @Synchronized fun background(recreating:Boolean){
        active=false;val s=state?:return
        if(s.phase in setOf(TablePhase.IMAGE,TablePhase.DESTINATION,TablePhase.WAITING))return
        if(recreating&&s.phase in setOf(TablePhase.EDIT,TablePhase.REVIEW))return
        val writing=s.phase==TablePhase.SAVING;export=null
        state=if(recreating&&writing)s.copy(epoch=++epoch,phase=TablePhase.EDIT,review=null,confirmed=false,problem="Writing was interrupted. The destination may contain partial CSV. Review before retrying.")
            else TableState(s.owner,++epoch,TablePhase.PAUSED,problem=if(writing)"Writing was interrupted. Check the destination for partial CSV."else null)
    }
    @Synchronized fun lock(pendingInput:Boolean=false){active=false
        if(state?.phase!=TablePhase.IMAGE&&!(pendingInput&&state?.phase==TablePhase.OCR))close()
    }
    @Synchronized fun pause(owner:String,generation:Long,message:String){
        if(!matches(TableToken(owner,generation))||state?.phase !in setOf(TablePhase.CAMERA,TablePhase.CAPTURING))return
        state=TableState(owner,++epoch,TablePhase.PAUSED,problem=message)
    }
    @Synchronized fun manual(){val s=state?:return;if(active&&s.phase in setOf(TablePhase.CAMERA,TablePhase.PAUSED))state=s.copy(epoch=++epoch,phase=TablePhase.EDIT,problem=null)}
    @Synchronized fun capture(owner:String,generation:Long):TableToken?{val t=TableToken(owner,generation);if(!matches(t,TablePhase.CAMERA))return null;state=state!!.copy(phase=TablePhase.CAPTURING);return t}
    @Synchronized fun captured(t:TableToken):Boolean{if(!matches(t,TablePhase.CAPTURING))return false;state=state!!.copy(phase=TablePhase.OCR);return true}
    @Synchronized fun beginPicker(owner:String,generation:Long):Boolean{
        val s=state?:return false
        if(!matches(TableToken(owner,generation))||s.phase !in setOf(TablePhase.CAMERA,TablePhase.PAUSED,TablePhase.EDIT,TablePhase.REVIEW))return false
        if(image!=null){state=s.copy(problem="An earlier image selection is still pending. Return from it first.");return false}
        image=TableToken(owner,++epoch);state=TableState(owner,epoch,TablePhase.IMAGE);export=null;return true
    }
    @Synchronized fun picked(hasImage:Boolean):TableToken?{
        val t=image?:return null;image=null;val s=state?:return null
        if(s.owner!=t.owner||s.epoch!=t.epoch||s.phase!=TablePhase.IMAGE)return null
        state=s.copy(phase=if(hasImage)TablePhase.OCR else TablePhase.PAUSED);return if(hasImage)t else null
    }
    @Synchronized fun complete(t:TableToken,result:TableExtraction?){if(!matches(t,TablePhase.OCR))return
        state=if(result!=null)state!!.copy(phase=TablePhase.EDIT,extraction=result,grid=result.grid)
            else state!!.copy(phase=TablePhase.PAUSED,problem="Table recognition could not finish within supported limits. Retry a smaller table or enter cells manually.")
    }
    @Synchronized fun edit(owner:String,generation:Long,id:String,value:String){if(matches(TableToken(owner,generation),TablePhase.EDIT))state=state!!.copy(grid=state!!.grid.edit(id,value),problem=null)}
    @Synchronized fun layout(owner:String,generation:Long,update:(TableGrid)->TableGrid){if(matches(TableToken(owner,generation),TablePhase.EDIT))state=state!!.copy(epoch=++epoch,grid=update(state!!.grid),layoutChanged=true,problem=null)}
    @Synchronized fun review(owner:String,generation:Long){if(!matches(TableToken(owner,generation),TablePhase.EDIT))return
        val s=state!!;val frozen=TableReview.create(s.grid)
        state=if(frozen!=null)s.copy(phase=TablePhase.REVIEW,review=frozen,confirmed=false,problem=null)
            else s.copy(problem="Correct over-limit cells or unsupported control/format characters before review.")
    }
    @Synchronized fun correct(){val s=state?:return;if(active&&s.phase==TablePhase.REVIEW){state=s.copy(phase=TablePhase.EDIT,review=null,confirmed=false);export=null}}
    @Synchronized fun confirm(owner:String,generation:Long,id:String,value:Boolean){if(matches(TableToken(owner,generation),TablePhase.REVIEW)&&state?.review?.id==id)state=state!!.copy(confirmed=value)}
    @Synchronized fun beginSave(owner:String,generation:Long,review:String):Boolean{
        val s=state?:return false
        if(!matches(TableToken(owner,generation),TablePhase.REVIEW)||s.review?.id!=review||!s.confirmed)return false
        if(destination!=null){state=s.copy(problem="An earlier Save dialog is still pending. Return from it before retrying.");return false}
        val token=TableSaveToken(owner,generation,review);destination=token;export=s.review.export(token)
        state=s.copy(phase=TablePhase.DESTINATION,problem=null);return true
    }
    @Synchronized fun destination(hasUri:Boolean):TableSaveToken?{
        val token=destination?:return null;destination=null;val s=state?:return null
        if(s.owner!=token.owner||s.epoch!=token.epoch||s.review?.id!=token.review||s.phase!=TablePhase.DESTINATION||export?.token?.id!=token.id)return null
        if(!hasUri){export=null;state=s.copy(phase=TablePhase.REVIEW,confirmed=false,problem="Save canceled. No write started.");return null}
        state=s.copy(phase=TablePhase.WAITING);return token
    }
    @Synchronized fun startWrite(t:TableSaveToken):TableExport?{
        val s=state?:return null
        if(!active||s.phase!=TablePhase.WAITING||s.owner!=t.owner||s.epoch!=t.epoch||s.review?.id!=t.review||export?.token?.id!=t.id)return null
        state=s.copy(phase=TablePhase.SAVING);return export
    }
    @Synchronized fun canWrite(t:TableSaveToken)=active&&state?.let{it.owner==t.owner&&it.epoch==t.epoch&&it.review?.id==t.review&&it.phase==TablePhase.SAVING&&export?.token?.id==t.id}==true
    @Synchronized fun saved(t:TableSaveToken,success:Boolean){if(!canWrite(t))return
        export=null;state=state!!.copy(phase=TablePhase.REVIEW,confirmed=false,problem=if(success)"CSV saved."else "CSV could not be saved. The destination may contain partial CSV; inspect or delete it in Files, then retry.")
    }
    @Synchronized fun rejectedDestination(){destination(false);if(state?.phase==TablePhase.REVIEW)state=state!!.copy(problem="Unsupported destination. Choose a content provider through Android Save.")}
    @Synchronized fun pickerFailed(){destination(false);if(state?.phase==TablePhase.REVIEW)state=state!!.copy(problem="Save dialog could not open. Retry or choose another file provider.")}
    @Synchronized fun cancelExport(){val s=state?:return
        if(s.phase !in setOf(TablePhase.DESTINATION,TablePhase.WAITING,TablePhase.SAVING))return
        state=s.copy(epoch=++epoch,phase=TablePhase.EDIT,review=null,confirmed=false,problem="Export canceled. If writing had begun, the destination may contain partial CSV.");export=null
    }
}
