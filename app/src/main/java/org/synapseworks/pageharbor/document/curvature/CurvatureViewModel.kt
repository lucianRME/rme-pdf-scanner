package org.synapseworks.pageharbor.document.curvature

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
import org.synapseworks.pageharbor.document.session.*
import org.synapseworks.pageharbor.document.crop.PageCrop

internal enum class CurvatureWork { ANALYZING, PREVIEW, PREPARING }
internal data class CurvatureState(val owner:String,val review:CurvatureReview?=null,val preview:CurvaturePreview?=null,
    val work:CurvatureWork?=CurvatureWork.ANALYZING,val done:Int=0,val total:Int=0,val failed:Boolean=false) {
    val blocked get()=work==CurvatureWork.ANALYZING||work==CurvatureWork.PREPARING
}
internal class CurvatureViewModel(app:Application):AndroidViewModel(app) {
    private class Owner(val session:DocumentSession,val page:DocumentPage,val revision:Long,val release:()->Unit) {
        val id=UUID.randomUUID().toString();var workspace:File?=null;var ended=false;var keep:Set<String> = emptySet();val released=AtomicBoolean()
    }
    private val processor=CurvatureProcessor(app);private val files=Mutex();private val cleanup=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var owner:Owner?=null;private var generation=0L;private var task:Job?=null
    var state:CurvatureState? by mutableStateOf(null);private set
    internal var beforePublishForTest:(()->Unit)?=null
    fun open(session:DocumentSession,revision:Long,pageIndex:Int,release:()->Unit):Boolean {
        if(owner!=null||pageIndex !in session.pages.indices)return false
        val o=Owner(session,session.pages[pageIndex],revision,release);owner=o;state=CurvatureState(o.id)
        runWork(CurvatureWork.ANALYZING){active,token->
            val work=files.withLock{processor.begin().also{active.workspace=it}}
            val result=files.withLock{processor.analyze(work,active.page)}
            if(!current(active,token))return@runWork
            val review=result.review(active.page);state=state?.copy(review=review,work=CurvatureWork.PREVIEW)
            val preview=if(result.canProcess)files.withLock{processor.preview(work,review)}else null
            if(current(active,token))state=state?.copy(preview=preview,work=null)
        };return true
    }
    private fun current(o:Owner,token:Long)=owner===o&&generation==token&&!o.ended
    private fun runWork(work:CurvatureWork,block:suspend(Owner,Long)->Unit) {
        val o=owner?:return;task?.cancel();val token=++generation
        state=state?.copy(work=work,preview=null,failed=false,done=0,total=0)
        task=viewModelScope.launch{
            try{block(o,token)}catch(e:CancellationException){throw e}
            catch(_:OutOfMemoryError){if(current(o,token))state=state?.copy(work=null,failed=true)}
            catch(_:Exception){if(current(o,token))state=state?.copy(work=null,failed=true)}
            finally{if(o.ended)withContext(NonCancellable){finish(o)}}
        }
    }
    private fun change(block:(CurvatureReview)->CurvatureReview) {
        val s=state?:return;val review=s.review?:return;if(s.blocked||!review.initial.canProcess)return
        state=s.copy(review=block(review));preview()
    }
    private fun preview()=runWork(CurvatureWork.PREVIEW){o,token->
        delay(100);val review=state?.review?:return@runWork
        val result=files.withLock{processor.preview(requireNotNull(o.workspace),review)}
        if(current(o,token))state=state?.copy(preview=result,work=null)
    }
    fun setEnabled(enabled:Boolean)=change{it.copy(enabled=enabled,field=it.field?:CurvatureField.identity(),manual=enabled)}
    fun setAnchor(index:Int,value:Float){if(index !in 0..4||!value.isFinite()||value !in -.14f.. .14f)return
        change{r->val f=r.field?:CurvatureField.identity()
            r.copy(field=f.withAnchor(index,value),enabled=true,manual=true)}}
    fun setTaper(value:Float){if(!value.isFinite()||value !in -.16f.. .16f)return
        change{r->val f=r.field?:CurvatureField.identity();r.copy(field=CurvatureField.manual(List(5){f.offset(it/4f)},value).copy(horizontalStretch=f.horizontalStretch),enabled=true,manual=true)}}

    fun setHorizontalStretch(value: Float) {
        if (value !in -.40f.. .40f) return
        change { review ->
            val field = review.field ?: CurvatureField.identity()
            review.copy(field = field.copy(horizontalStretch = value), enabled = true, manual = true)
        }
    }

    private fun CurvatureAnalysis.review(
        page: DocumentPage,
        straighten: Boolean = false,
    ): CurvatureReview {
        val active = if (straighten) straightenedEstimate else estimate
        return CurvatureReview(
            page, this, field = active?.field, enabled = active?.status == CurvatureStatus.PROPOSED,
            straighten = straighten,
        )
    }

    fun setStraighten(value: Boolean) = change { review ->
        if (value && review.perspective == null) review
        else {
            val estimate = if (value) review.rectifiedEstimate else review.initial.estimate
            review.copy(straighten = value, field = estimate?.field,
                enabled = estimate?.status == CurvatureStatus.PROPOSED, manual = false)
        }
    }

    fun updateCorners(crop: PageCrop) {
        val before = state?.takeIf { it.work == null && !it.failed }?.review ?: return
        if (!before.initial.canProcess) return
        val perspective = crop.takeUnless(PageCrop::boundsCoverFrame)
        if (perspective == before.perspective) {
            setStraighten(perspective != null)
            return
        }
        runWork(CurvatureWork.ANALYZING) { active, token ->
            val work = requireNotNull(active.workspace)
            val estimate = if (perspective != null) {
                files.withLock { processor.estimateReviewedCorners(work, before, perspective) }
            } else before.initial.estimate
            if (!current(active, token)) return@runWork
            val review = before.copy(
                perspective = perspective,
                rectifiedEstimate = estimate.takeIf { perspective != null },
                straighten = perspective != null,
                field = estimate.field,
                enabled = estimate.status == CurvatureStatus.PROPOSED,
                manual = false,
            )
            val updated = files.withLock { processor.preview(work, review) }
            if (current(active, token)) state = state?.copy(review = review, preview = updated, work = null)
        }
    }
    fun reset()=change{it.initial.review(it.page)}
    fun apply(publish:(DocumentSession,Long,CurvatureReplacement)->Boolean) {
        val s=state?:return;val review=s.review?:return;if(s.work!=null||s.failed)return
        if(!review.hasEdit){close();return}
        runWork(CurvatureWork.PREPARING){o,token->
            val result=files.withLock{processor.prepare(requireNotNull(o.workspace),review){done,total->
                withContext(Dispatchers.Main.immediate){if(current(o,token))state=state?.copy(done=done,total=total)}}}
            if(!current(o,token))return@runWork
            beforePublishForTest?.invoke();if(!current(o,token))return@runWork
            owner=null;state=null;task=null;o.ended=true
            if(publish(o.session,o.revision,result))o.keep=setOf(File(requireNotNull(result.derived.source.ownedTemporaryFile).path).name)
        }
    }
    fun onDocumentRevisionChanged(revision:Long){if(owner?.revision?.let{it!=revision}==true)close()}
    fun close(){val o=owner?:return;owner=null;generation++;state=null;o.ended=true;val pending=task;task=null;pending?.cancel()
        cleanup.launch{pending?.join();finish(o)}}
    private suspend fun finish(o:Owner){if(!o.released.compareAndSet(false,true))return
        try{files.withLock{o.workspace?.let{runCatching{processor.finish(it,o.keep)}}}}
        finally{withContext(NonCancellable+Dispatchers.Main.immediate){o.release()}}}
    override fun onCleared(){close();super.onCleared()}
}
