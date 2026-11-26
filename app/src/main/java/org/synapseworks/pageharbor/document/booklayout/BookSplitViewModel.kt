package org.synapseworks.pageharbor.document.booklayout

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

internal enum class BookWork { ANALYZING, PREVIEW, PREPARING }
internal data class BookSplitState(val owner:String,val sourceCount:Int,val reviews:List<BookReviewedPage> = emptyList(),
    val index:Int=0,val order:BookReadingOrder=BookReadingOrder.LEFT_TO_RIGHT,val work:BookWork?=null,
    val done:Int=0,val total:Int=0,val preview:BookSplitPreview?=null,val failed:Boolean=false,
    val capturedSpread: Boolean = false) {
    val current:BookReviewedPage? get()=reviews.getOrNull(index)
    val blocked:Boolean get()=work==BookWork.ANALYZING||work==BookWork.PREPARING
}

/** Retains no Activity/camera/bitmap. Background/recreation/lock closes this review explicitly.
 * Files and session lease remain owned until every canceled worker settles, even after onCleared.
 */
internal class BookSplitViewModel(app:Application):AndroidViewModel(app) {
    private class Owner(val session:DocumentSession,val revision:Long,val capturedSpread: Boolean,val release:()->Unit) {
        val id=UUID.randomUUID().toString();var workspace:File?=null;var ended=false;var keep:Set<String> = emptySet();val released=AtomicBoolean()
    }
    private val processor=BookSplitProcessor(app)
    private val files=Mutex()
    private val cleanup=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var owner:Owner?=null;private var generation=0L;private var task:Job?=null
    var state:BookSplitState? by mutableStateOf(null);private set
    internal var beforePublishForTest:(()->Unit)?=null
    fun open(session:DocumentSession,revision:Long,pageIndex:Int,capturedSpread: Boolean = false,release:()->Unit):Boolean {
        if(owner!=null||session.pages.isEmpty())return false
        if (capturedSpread && pageIndex !in session.pages.indices) return false
        val o=Owner(session,revision,capturedSpread,release);owner=o
        state=BookSplitState(o.id,if(capturedSpread) 1 else session.pages.size,capturedSpread=capturedSpread)
        analyze(o,listOf(session.pages[pageIndex.coerceIn(session.pages.indices)]));return true
    }
    private fun current(o:Owner,token:Long)=owner===o&&generation==token&&!o.ended
    private fun runWork(work:BookWork,block:suspend(Owner,Long)->Unit) {
        val o=owner?:return;task?.cancel();val token=++generation
        state=state?.copy(work=work,preview=null,failed=false,done=0)
        task=viewModelScope.launch {
            try{block(o,token)}
            catch(e:CancellationException){throw e}
            catch(_:OutOfMemoryError){if(current(o,token))state=state?.copy(work=null,preview=null,failed=true)}
            catch(_:Exception){if(current(o,token))state=state?.copy(work=null,preview=null,failed=true)}
            finally{if(o.ended)withContext(NonCancellable){finish(o)}}
        }
    }
    private suspend fun workspace(o:Owner):File=files.withLock{o.workspace?:processor.begin().also{o.workspace=it}}
    private fun BookReviewedPage.proposeCapturedSplit(captured: Boolean): BookReviewedPage =
        if (captured && analysis.canSplit && !split) copy(split = true, line = line ?: BookSplitLine(.5f, .5f)) else this

    private fun analyze(o:Owner,pages:List<DocumentPage>) {
        state=state?.copy(reviews=emptyList(),index=0,total=pages.size)
        runWork(BookWork.ANALYZING){active,token->
            check(active===o);val work=workspace(o);val reviews=mutableListOf<BookReviewedPage>()
            for((index,page)in pages.withIndex()){
                val result=files.withLock{processor.analyze(work,page)}
                if(!current(o,token))return@runWork
                reviews+=BookReviewedPage(page,result).proposeCapturedSplit(o.capturedSpread)
                state=state?.copy(reviews=reviews.toList(),index=0,done=index+1,total=pages.size)
            }
            if(current(o,token)){
                state=state?.copy(work=BookWork.PREVIEW)
                val s=state!!;val review=s.current
                val preview=if(review?.analysis?.canSplit==true)files.withLock{processor.preview(work,review,s.order)}else null
                if(current(o,token))state=state?.copy(preview=preview,work=null)
            }
        }
    }
    fun reviewAll(){val o=owner?:return;if(o.capturedSpread||state?.blocked==true)return;analyze(o,o.session.pages)}
    private fun preview() {
        runWork(BookWork.PREVIEW){o,token->
            delay(100);val s=state?:return@runWork;val review=s.current?:return@runWork
            val result=if(review.analysis.canSplit)files.withLock{processor.preview(requireNotNull(o.workspace),review,s.order)}else null
            if(current(o,token))state=state?.copy(preview=result,work=null)
        }
    }
    fun select(index:Int){val s=state?:return;if(s.blocked||index !in s.reviews.indices)return;state=s.copy(index=index);preview()}
    private fun change(transform:(BookReviewedPage)->BookReviewedPage) {
        val s=state?:return;if(s.blocked)return;val selected=s.current?:return
        state=s.copy(reviews=s.reviews.mapIndexed{i,page->if(i==s.index)transform(selected)else page});preview()
    }
    fun setLine(top:Float,bottom:Float)=change{it.copy(line=BookSplitLine(top,bottom),split=true,manual=true)}
    fun setTop(top:Float)=change{it.copy(line=BookSplitLine(top,it.line?.bottom?:.5f),split=true,manual=true)}
    fun setBottom(bottom:Float)=change{it.copy(line=BookSplitLine(it.line?.top?:.5f,bottom),split=true,manual=true)}
    fun setSplit(split:Boolean)=change{if(!it.analysis.canSplit)it else it.copy(split=split,line=it.line?:BookSplitLine(.5f,.5f),manual=split)}
    fun setOrder(order:BookReadingOrder){val s=state?:return;if(s.blocked)return;state=s.copy(order=order);preview()}
    fun reset()=change{BookReviewedPage(it.page,it.initial).proposeCapturedSplit(owner?.capturedSpread == true)}
    fun rotate() {
        val s=state?:return;if(s.blocked)return;val selected=s.current?:return;val index=s.index;val turns=(selected.turns+1)%4
        runWork(BookWork.ANALYZING){o,token->
            val result=files.withLock{processor.analyze(requireNotNull(o.workspace),selected.page,turns,selected.initial.digest)}
            if(!current(o,token))return@runWork
            val review=selected.copy(analysis=result,turns=turns,line=result.detection.line,split=result.detection.status==BookDetectionStatus.CONFIDENT,manual=false).proposeCapturedSplit(o.capturedSpread)
            state=state?.copy(reviews=state!!.reviews.mapIndexed{i,page->if(i==index)review else page},work=BookWork.PREVIEW)
            val p=if(result.canSplit)files.withLock{processor.preview(requireNotNull(o.workspace),review,s.order)}else null
            if(current(o,token))state=state?.copy(preview=p,work=null)
        }
    }
    fun apply(publish:(DocumentSession,Long,List<BookPageReplacement>)->Boolean) {
        val s=state?:return;if(s.work!=null||s.failed)return
        if(s.reviews.none{it.split}){close();return}
        runWork(BookWork.PREPARING){o,token->
            val prepared=files.withLock{processor.prepare(requireNotNull(o.workspace),s.reviews,s.order){done,total->
                withContext(Dispatchers.Main.immediate){if(current(o,token))state=state?.copy(done=done,total=total)}
            }}
            if(!current(o,token))return@runWork
            beforePublishForTest?.invoke()
            if(!current(o,token))return@runWork
            // Detach before synchronous revision observers can cancel/delete published files.
            owner=null;state=null;task=null;o.ended=true
            if(publish(o.session,o.revision,prepared))o.keep=prepared.flatMap{it.halves}.map{File(requireNotNull(it.source.ownedTemporaryFile).path).name}.toSet()
        }
    }
    fun onDocumentRevisionChanged(revision:Long){if(owner?.revision?.let{it!=revision}==true)close()}
    fun close() {
        val o=owner?:return;owner=null;generation++;state=null;o.ended=true;val pending=task;task=null;pending?.cancel()
        cleanup.launch{pending?.join();finish(o)}
    }
    private suspend fun finish(o:Owner) {
        if(!o.released.compareAndSet(false,true))return
        try{files.withLock{o.workspace?.let{runCatching{processor.finish(it,o.keep)}}}}
        finally{withContext(NonCancellable+Dispatchers.Main.immediate){o.release()}}
    }
    override fun onCleared(){close();super.onCleared()}
}
