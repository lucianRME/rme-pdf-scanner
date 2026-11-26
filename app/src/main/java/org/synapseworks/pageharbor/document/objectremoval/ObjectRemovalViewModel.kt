package org.synapseworks.pageharbor.document.objectremoval

import android.app.Application
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.synapseworks.pageharbor.document.annotation.AnnotationPoint
import org.synapseworks.pageharbor.document.session.*

internal enum class RemovalMode { BRUSH, FINGER }
internal enum class RemovalWork { OPENING, MASK, PREVIEW, APPLYING }
internal enum class RemovalView { MASK, BEFORE, AFTER }
internal data class RemovalState(val page:DocumentPage,val owner:String,val analysis:RemovalAnalysis?=null,val mask:InpaintMask?=null,
    val mode:RemovalMode=RemovalMode.BRUSH,val detection:org.synapseworks.pageharbor.document.finger.FingerDetection?=null,val baseMask:InpaintMask?=null,
    val strokes:List<RemovalStroke> = emptyList(),val radius:Int=14,val erase:Boolean=false,val view:RemovalView=RemovalView.MASK,
    val prepared:RemovalPrepared?=null,val reviewedDigest:String?=null,val status:RemovalStatus?=null,val work:RemovalWork?=RemovalWork.OPENING,val failed:Boolean=false)
internal class ObjectRemovalViewModel(app:Application):AndroidViewModel(app) {
    private class Owner(val session:DocumentSession,val revision:Long,val page:DocumentPage,val token:String,val release:()->Unit){
        val id=UUID.randomUUID().toString();var workspace:File?=null;var ended=false;var keep:Set<String> = emptySet();val released=AtomicBoolean()
    }
    private val processor=ObjectRemovalProcessor(app);private val files=Mutex();private val cleanup=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var owner:Owner?=null;private var generation=0L;private var task:Job?=null
    var state:RemovalState? by mutableStateOf(null);private set
    internal var beforePublishForTest:(()->Unit)?=null
    fun open(session:DocumentSession,revision:Long,pageIndex:Int,ownerToken:String,mode:RemovalMode=RemovalMode.BRUSH,release:()->Unit):Boolean {
        if(owner!=null||pageIndex !in session.pages.indices||!session.pages[pageIndex].contentType.startsWith("image/"))return false
        val o=Owner(session,revision,session.pages[pageIndex],ownerToken,release);owner=o;state=RemovalState(o.page,o.id,mode=mode)
        runWork(RemovalWork.OPENING){active,token->
            val pair=files.withLock{val work=processor.begin().also{active.workspace=it};val analysis=processor.open(work,active.page)
                analysis to if(mode==RemovalMode.FINGER)processor.detect(work,active.page,analysis)else null}
            if(current(active,token)){val a=pair.first;val detection=pair.second;state=state?.copy(analysis=a,detection=detection,baseMask=detection?.mask,mask=detection?.mask?:InpaintMask.paint(a.width,a.height,emptyList()),work=null)}
        };return true
    }
    private fun current(o:Owner,token:Long)=owner===o&&generation==token&&!o.ended
    private fun runWork(work:RemovalWork,block:suspend(Owner,Long)->Unit){
        val o=owner?:return;task?.cancel();val token=++generation;state=state?.copy(work=work,failed=false)
        task=viewModelScope.launch{try{block(o,token)}catch(e:CancellationException){throw e}
            catch(_:OutOfMemoryError){failed(o,token)}
            catch(_:Exception){failed(o,token)}
            finally{if(o.ended)withContext(NonCancellable){finish(o)}}}
    }
    private suspend fun failed(o:Owner,token:Long){
        withContext(NonCancellable){files.withLock{if(current(o,token))o.workspace?.let{processor.discard(it)}}}
        if(current(o,token))state=state?.copy(work=null,failed=true,prepared=null,reviewedDigest=null)
    }
    fun rejectStroke(){state=state?.copy(failed=true)}
    fun markReviewed(prepared:RemovalPrepared){val s=state?:return;if(s.work==null&&s.view==RemovalView.AFTER&&s.prepared===prepared)state=s.copy(reviewedDigest=prepared.resultDigest)}
    fun radius(value:Int){if(value in 2..32)state=state?.copy(radius=value)}
    fun erase(value:Boolean){state=state?.copy(erase=value,view=RemovalView.MASK)}
    fun view(value:RemovalView){if(value!=RemovalView.AFTER||state?.prepared!=null)state=state?.copy(view=value)}
    fun stroke(points:List<AnnotationPoint>){val s=state?:return;if(s.analysis==null||s.work==RemovalWork.OPENING||s.work==RemovalWork.APPLYING)return
        if(points.size !in 1..2048||s.strokes.size>=128||s.strokes.sumOf{it.points.size}+points.size>16384){state=s.copy(failed=true);return}
        change(s.strokes+RemovalStroke(points.toList(),s.radius,s.erase))}
    fun undo(){state?.let{if(it.strokes.isNotEmpty())change(it.strokes.dropLast(1))}}
    fun clear(){if(state?.mode==RemovalMode.FINGER)rejectProposals()else change(emptyList())}
    fun rejectProposals(){val s=state?:return;if(s.work==RemovalWork.OPENING||s.work==RemovalWork.APPLYING)return;state=s.copy(baseMask=null);change(emptyList())}
    fun restoreProposals(){val s=state?:return;if(s.work==RemovalWork.OPENING||s.work==RemovalWork.APPLYING)return;state=s.copy(baseMask=s.detection?.mask);change(emptyList())}
    private fun change(strokes:List<RemovalStroke>){
        val s=state?:return;val analysis=s.analysis?:return;if(s.work==RemovalWork.OPENING||s.work==RemovalWork.APPLYING)return
        state=s.copy(strokes=strokes,prepared=null,reviewedDigest=null,status=null,view=RemovalView.MASK,mask=null)
        runWork(RemovalWork.MASK){o,token->val mask=files.withLock{processor.discard(requireNotNull(o.workspace));withContext(Dispatchers.Default){val job=coroutineContext;InpaintMask.paint(analysis.width,analysis.height,strokes,s.baseMask){job.ensureActive()}}}
            if(current(o,token))state=state?.copy(mask=mask,work=null)}
    }
    fun preview(){val s=state?:return;val analysis=s.analysis?:return;val mask=s.mask?:return;if(s.work!=null)return
        state=s.copy(prepared=null,reviewedDigest=null,status=null)
        runWork(RemovalWork.PREVIEW){o,token->val result=files.withLock{processor.preview(requireNotNull(o.workspace),o.page,analysis,mask,s.mode==RemovalMode.FINGER)}
            if(current(o,token))state=state?.copy(prepared=result.prepared,status=result.status,view=if(result.prepared!=null)RemovalView.AFTER else RemovalView.MASK,work=null)}
    }
    fun apply(publish:(DocumentSession,Long,RemovalPrepared,String)->Boolean){
        val s=state?:return;val prepared=s.prepared?:return;val analysis=s.analysis?:return;if(s.work!=null||s.failed||s.reviewedDigest!=prepared.resultDigest)return
        runWork(RemovalWork.APPLYING){o,token->
            files.withLock{processor.verify(requireNotNull(o.workspace),analysis,prepared)}
            if(!current(o,token))return@runWork;beforePublishForTest?.invoke();if(!current(o,token))return@runWork
            owner=null;state=null;task=null;o.ended=true
            if(publish(o.session,o.revision,prepared,o.token))o.keep=listOf(prepared.snapshot,prepared.result.source).map{File(requireNotNull(it.ownedTemporaryFile).path).name}.toSet()
        }
    }
    fun onDocumentRevisionChanged(revision:Long){if(owner?.revision?.let{it!=revision}==true)close()}
    fun close(){val o=owner?:return;owner=null;generation++;state=null;o.ended=true;val pending=task;task=null;pending?.cancel();cleanup.launch{pending?.join();finish(o)}}
    private suspend fun finish(o:Owner){if(!o.released.compareAndSet(false,true))return
        try{files.withLock{o.workspace?.let{processor.finish(it,o.keep)}}}finally{withContext(NonCancellable+Dispatchers.Main.immediate){o.release()}}}
    override fun onCleared(){close();super.onCleared()}
}
