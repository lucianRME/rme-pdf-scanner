package org.synapseworks.pageharbor.document.collage

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
import org.synapseworks.pageharbor.document.booklayout.BookPreviewImage
import org.synapseworks.pageharbor.document.session.*

internal data class CollageState(val owner:String,val sourceIds:List<Long>,val draft:CollageDraft,
    val previews:List<BookPreviewImage> = emptyList(),val working:Boolean=true,val preparing:Boolean=false,val failed:Boolean=false)
internal class CollageViewModel(app:Application):AndroidViewModel(app) {
    private class Owner(val session:DocumentSession,val revision:Long,val documentOwner:String,val release:()->Unit) {
        val id=UUID.randomUUID().toString();var work:CollageWorkspace?=null;var ended=false;var keep:Set<String> = emptySet();val released=AtomicBoolean()
    }
    private val processor=CollageProcessor(app);private val files=Mutex();private val cleanup=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var owner:Owner?=null;private var generation=0L;private var task:Job?=null
    var state:CollageState? by mutableStateOf(null);private set
    internal var beforePublishForTest:(()->Unit)?=null
    fun open(session:DocumentSession,revision:Long,documentOwner:String,release:()->Unit):Boolean {
        if(owner!=null||session.pages.isEmpty()||session.pages.size>=500)return false
        val o=Owner(session,revision,documentOwner,release);owner=o
        state=CollageState(o.id,session.pages.map{it.id.value},CollageDraft(session.pages.take(4).map{CollageTile(UUID.randomUUID().toString(),it.id.value)}))
        preview();return true
    }
    private fun current(o:Owner,token:Long)=owner===o&&generation==token&&!o.ended
    private fun runWork(preparing:Boolean=false,block:suspend(Owner,Long)->Unit) {
        val o=owner?:return;task?.cancel();val token=++generation
        state=state?.copy(working=true,preparing=preparing,previews=emptyList(),failed=false)
        task=viewModelScope.launch{
            try{block(o,token)}catch(e:CancellationException){throw e}
            catch(_:OutOfMemoryError){if(current(o,token))state=state?.copy(working=false,preparing=false,failed=true)}
            catch(_:Exception){if(current(o,token))state=state?.copy(working=false,preparing=false,failed=true)}
            finally{if(o.ended)withContext(NonCancellable){finish(o)}}
        }
    }
    private fun change(block:(CollageDraft)->CollageDraft){val s=state?:return;if(s.preparing)return;state=s.copy(draft=block(s.draft));preview()}
    private fun preview()=runWork{o,token->
        delay(180);val draft=state?.draft?:return@runWork
        if(draft.tiles.isEmpty()){if(current(o,token))state=state?.copy(working=false);return@runWork}
        val images=files.withLock{
            val work=o.work?:withContext(NonCancellable){processor.begin().also{o.work=it}}
            currentCoroutineContext().ensureActive()
            processor.preview(work,draft,o.session)
        }
        if(current(o,token))state=state?.copy(previews=images,working=false)
    }
    fun settings(value:CollageSettings)=change{it.withSettings(value)}
    fun add(pageId:Long){val s=state?:return;if(pageId !in s.sourceIds||s.draft.tiles.size>=MAX_COLLAGE_TILES)return
        change{it.withTiles(it.tiles+CollageTile(UUID.randomUUID().toString(),pageId))}}
    fun remove(id:String)=change{it.withTiles(it.tiles.filterNot{t->t.id==id})}
    fun move(id:String,delta:Int)=change{d->val list=d.tiles.toMutableList();val i=list.indexOfFirst{it.id==id};val target=i+delta
        if(i>=0&&target in list.indices){val t=list.removeAt(i);list.add(target,t)};d.withTiles(list)}
    fun tile(value:CollageTile)=change{d->d.withTiles(d.tiles.map{if(it.id==value.id&&it.pageId==value.pageId)value else it})}
    fun reset(){val o=owner?:return;change{CollageDraft(o.session.pages.take(4).map{CollageTile(UUID.randomUUID().toString(),it.id.value)})}}
    fun retry(){if(state?.preparing!=true)preview()}
    fun apply(publish:(DocumentSession,Long,String,CollageOutput)->Boolean){
        val s=state?:return;if(s.working||s.failed||s.draft.tiles.isEmpty())return;val draft=s.draft
        runWork(true){o,token->
            val output=files.withLock{processor.prepare(requireNotNull(o.work),draft,o.session)}
            if(!current(o,token))return@runWork
            beforePublishForTest?.invoke();if(!current(o,token))return@runWork
            files.withLock{processor.verify(requireNotNull(o.work),o.session.pages.filter{p->draft.tiles.any{it.pageId==p.id.value}})}
            if(!current(o,token))return@runWork
            // End before advancing the revision, so the revision observer cannot cancel accepted bytes.
            owner=null;state=null;task=null;o.ended=true
            if(publish(o.session,o.revision,o.documentOwner,output))o.keep=output.pages.map{File(requireNotNull(it.source.ownedTemporaryFile).path).name}.toSet()
        }
    }
    fun onDocumentRevisionChanged(revision:Long){if(owner?.revision?.let{it!=revision}==true)close()}
    fun close(){val o=owner?:return;owner=null;generation++;state=null;o.ended=true;val pending=task;task=null;pending?.cancel()
        cleanup.launch{pending?.join();finish(o)}}
    private suspend fun finish(o:Owner){if(!o.released.compareAndSet(false,true))return
        try{files.withLock{o.work?.let{runCatching{processor.finish(it,o.keep)}}}}
        finally{withContext(NonCancellable+Dispatchers.Main.immediate){o.release()}}}
    override fun onCleared(){close();super.onCleared()}
}
