package org.synapseworks.pageharbor.forms

internal enum class FormPhase { OPEN,PICKING,READING,EDIT,REVIEW,PREPARING,READY,DESTINATION,WAITING,SAVING }
internal data class FormToken(val owner:String,val epoch:Long,val review:String?=null,val request:String=java.util.UUID.randomUUID().toString())
internal data class FormState(val owner:String,val epoch:Long,val phase:FormPhase=FormPhase.OPEN,val model:FormModel?=null,
    val values:Map<String,List<String>> = emptyMap(),val review:FormReview?=null,val confirmed:Boolean=false,val message:String?=null){override fun toString()="FormState(<redacted>)"}
internal class FormController {
    var state:FormState?=null;private set
    private var active=true;private var epoch=0L;private var input:FormToken?=null;private var destination:FormToken?=null
    @Synchronized fun open(){if(active&&state==null)state=FormState(java.util.UUID.randomUUID().toString(),++epoch)}
    @Synchronized fun active(value:Boolean){active=value}
    @Synchronized fun matches(t:FormToken,phase:FormPhase?=null)=active&&state?.let{it.owner==t.owner&&it.epoch==t.epoch&&(t.review==null||it.review?.id==t.review)&&(phase==null||it.phase==phase)}==true
    @Synchronized fun close(){state=null;epoch++}
    @Synchronized fun beginInput(owner:String,generation:Long):Boolean {
        val s=state?:return false;if(!matches(FormToken(owner,generation))||s.phase !in setOf(FormPhase.OPEN,FormPhase.EDIT,FormPhase.REVIEW,FormPhase.READY))return false
        if(input!=null){state=s.copy(message="An earlier file selection is still pending. Return from it first.");return false}
        input=FormToken(owner,++epoch);state=FormState(owner,epoch,FormPhase.PICKING);return true
    }
    @Synchronized fun picked(hasSource:Boolean):FormToken? {
        val t=input?:return null;input=null;val s=state?:return null
        if(s.owner!=t.owner||s.epoch!=t.epoch||s.phase!=FormPhase.PICKING)return null
        state=s.copy(phase=if(hasSource)FormPhase.READING else FormPhase.OPEN)
        return if(hasSource)t else null
    }
    @Synchronized fun loaded(t:FormToken,model:FormModel?,failure:FormFailure?=null){if(!matches(t,FormPhase.READING))return
        state=if(model==null)FormState(t.owner,t.epoch,message=(failure?:FormFailure.MALFORMED).message)else state!!.copy(phase=FormPhase.EDIT,model=model,values=model.initial(),message=null)
    }
    @Synchronized fun edit(owner:String,generation:Long,id:String,values:List<String>){
        val s=state?:return;if(!matches(FormToken(owner,generation),FormPhase.EDIT))return
        val field=s.model!!.fields.singleOrNull{it.id==id}?:return
        if(field.readOnly||values.size>FormLimits.OPTIONS||values.any{it.length>FormLimits.VALUE})return
        if(field.kind==FormKind.RADIO&&field.noToggleOff&&s.values[id]!=listOf("Off")&&values==listOf("Off"))return
        val next=s.values+(id to values.toList());if(next.values.flatten().sumOf{it.length}>FormLimits.TOTAL){state=s.copy(message="The total form text limit is reached. Shorten a value before adding more text.");return}
        state=s.copy(values=next,message=null)
    }
    @Synchronized fun review(owner:String,generation:Long){if(!matches(FormToken(owner,generation),FormPhase.EDIT))return
        val s=state!!;val review=FormReview.create(s.model!!,s.values)
        state=if(review==null)s.copy(message="Correct unsupported characters, field lengths or choices before reviewing.")else s.copy(phase=FormPhase.REVIEW,review=review,confirmed=false,message=null)
    }
    @Synchronized fun correct(){val s=state?:return;if(active&&s.phase in setOf(FormPhase.REVIEW,FormPhase.READY))state=s.copy(epoch=++epoch,phase=FormPhase.EDIT,review=null,confirmed=false,message=null)}
    @Synchronized fun confirm(owner:String,generation:Long,id:String,value:Boolean){val s=state?:return;if(matches(FormToken(owner,generation,id))&&s.phase in setOf(FormPhase.REVIEW,FormPhase.READY))state=s.copy(confirmed=value)}
    @Synchronized fun prepare(owner:String,generation:Long,id:String):FormToken? {val s=state?:return null
        if(!matches(FormToken(owner,generation,id),FormPhase.REVIEW)||!s.confirmed)return null
        state=s.copy(phase=FormPhase.PREPARING,message=null);return FormToken(owner,generation,id)
    }
    @Synchronized fun prepared(t:FormToken,failure:FormFailure?){if(!matches(t,FormPhase.PREPARING))return
        state=state!!.copy(phase=if(failure==null)FormPhase.READY else FormPhase.REVIEW,confirmed=failure==null,message=failure?.message)
    }
    @Synchronized fun beginSave(owner:String,generation:Long,id:String):Boolean {
        val s=state?:return false;if(!matches(FormToken(owner,generation,id),FormPhase.READY)||!s.confirmed)return false
        if(destination!=null){state=s.copy(message="An earlier Save dialog is still pending. Return from it first.");return false}
        destination=FormToken(owner,generation,id);state=s.copy(phase=FormPhase.DESTINATION,message=null);return true
    }
    @Synchronized fun destination(hasUri:Boolean):FormToken? {
        val t=destination?:return null;destination=null;val s=state?:return null
        if(s.owner!=t.owner||s.epoch!=t.epoch||s.review?.id!=t.review||s.phase!=FormPhase.DESTINATION)return null
        state=s.copy(phase=if(hasUri)FormPhase.WAITING else FormPhase.READY,confirmed=hasUri,message=if(hasUri)null else"Save cancelled. No write started.");return if(hasUri)t else null
    }
    @Synchronized fun startWrite(t:FormToken):Boolean{if(!matches(t,FormPhase.WAITING))return false;state=state!!.copy(phase=FormPhase.SAVING);return true}
    @Synchronized fun saved(t:FormToken,failure:FormFailure?){if(!matches(t,FormPhase.SAVING))return;state=state!!.copy(phase=FormPhase.READY,confirmed=false,message=failure?.message?:"PDF copy saved.")}
    @Synchronized fun cancelWork(){val s=state?:return;if(s.phase in setOf(FormPhase.DESTINATION,FormPhase.WAITING,FormPhase.SAVING,FormPhase.PREPARING))state=s.copy(epoch=++epoch,phase=if(s.phase==FormPhase.PREPARING)FormPhase.REVIEW else FormPhase.READY,confirmed=false,message="Operation cancelled. If writing had begun, inspect the separate destination for partial PDF.")}
    /** True means source/content may stay in the live ViewModel; lock always clears separately. */
    @Synchronized fun background(recreating:Boolean):Boolean {
        active=false;val s=state?:return false
        if(s.phase in setOf(FormPhase.PICKING,FormPhase.DESTINATION,FormPhase.WAITING))return true
        if(recreating&&s.phase in setOf(FormPhase.EDIT,FormPhase.REVIEW,FormPhase.READY))return true
        if(recreating&&s.phase in setOf(FormPhase.PREPARING,FormPhase.SAVING)){cancelWork();return true}
        state=FormState(s.owner,++epoch,message="The transient draft was cleared after leaving the app. Reopen the original PDF.");return false
    }
    @Synchronized fun lock(pendingInput:Boolean=false){active=false;if(state?.phase!=FormPhase.PICKING&&!(pendingInput&&state?.phase==FormPhase.READING))close()}
}
