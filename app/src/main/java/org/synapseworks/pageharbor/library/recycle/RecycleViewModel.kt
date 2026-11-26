package org.synapseworks.pageharbor.library.recycle

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.synapseworks.pageharbor.library.LibraryRepository

internal data class RecycleConfirmation(val id:String?=null,val revision:Long=0,val watermark:Long=0,val count:Long=1)
internal data class RecycleUiState(val open:Boolean=false,val page:RecyclePage=RecyclePage(),val loading:Boolean=false,
    val working:Boolean=false,val confirmation:RecycleConfirmation?=null,val error:RecycleFailure?=null,val notice:String?=null)
@OptIn(ExperimentalCoroutinesApi::class)
internal class RecycleViewModel(app:Application):AndroidViewModel(app){
    private val repository=LibraryRepository(app)
    private val mutable=MutableStateFlow(RecycleUiState())
    val state=mutable.asStateFlow()
    private val offset=MutableStateFlow<Int?>(null)
    private var active=true;private var epoch=0L;private var job:Job?=null
    init{viewModelScope.launch{offset.flatMapLatest{o->if(o==null)flowOf(null)else repository.observeRecyclePage(o).map{it as RecyclePage?}.catch{mutable.update{it.copy(error=RecycleFailure.UNAVAILABLE,loading=false)};emit(null)}}.collect{page->
        if(page!=null&&offset.value==page.offset){
            if(page.offset>0&&page.offset>=page.count){offset.value=(((page.count-1).coerceAtLeast(0)/30)*30).toInt()}
            else mutable.update{it.copy(page=page,loading=false)}
        }
    }}}
    fun setActive(value:Boolean){active=value;if(!value)close()}
    fun open(){if(!active||mutable.value.open||mutable.value.working)return;epoch++;mutable.value=RecycleUiState(open=true,loading=true);offset.value=0;retryCleanup()}
    fun close(){epoch++;job?.cancel();job=null;offset.value=null;mutable.value=RecycleUiState()}
    private fun canAct()=active&&mutable.value.open&&!mutable.value.working
    fun previous(){if(canAct()&&mutable.value.page.offset>=30){mutable.update{it.copy(loading=true)};offset.value=mutable.value.page.offset-30}}
    fun next(){val p=mutable.value.page;if(canAct()&&p.offset.toLong()+p.items.size<p.count&&p.offset<Int.MAX_VALUE-60){mutable.update{it.copy(loading=true)};offset.value=p.offset+30}}
    fun askPurge(id:String,revision:Long){if(canAct())mutable.update{it.copy(confirmation=RecycleConfirmation(id,revision))}}
    fun dismiss(){if(canAct())mutable.update{it.copy(confirmation=null)}}
    private fun work(block:suspend()->Unit){if(!canAct())return;val token=epoch;mutable.update{it.copy(working=true,error=null,notice=null)}
        job=viewModelScope.launch{try{block()}catch(e:CancellationException){throw e}catch(_:Exception){if(epoch==token)mutable.update{it.copy(error=RecycleFailure.UNAVAILABLE)}}finally{if(epoch==token)mutable.update{it.copy(working=false)}}}}
    private fun result(r:RecycleResult,success:String){when(r){is RecycleResult.Done->mutable.update{it.copy(confirmation=null,notice=if(r.cleanupPending)"Removed from the bin. Private file cleanup is pending; retry cleanup or reopen RME."else success)};is RecycleResult.Failed->mutable.update{it.copy(confirmation=null,error=r.reason)}}}
    fun restore(id:String,revision:Long)=work{val token=epoch;val r=withContext(Dispatchers.IO){repository.restoreFromRecycle(id,revision)};if(token==epoch&&active)result(r,"Document restored.")}
    fun askEmpty()=work{val token=epoch;val preview=withContext(Dispatchers.IO){repository.recyclePreview()};if(token==epoch&&active)mutable.update{it.copy(confirmation=RecycleConfirmation(watermark=preview.watermark,count=preview.count))}}
    fun confirm(){val c=mutable.value.confirmation?:return;work{val token=epoch
        if(c.id!=null){val r=withContext(Dispatchers.IO){repository.permanentlyDelete(c.id,c.revision)};if(token==epoch&&active)result(r,"Document permanently deleted.")}
        else{val r=withContext(Dispatchers.IO){repository.emptyRecycleBin(c.watermark)};if(token==epoch&&active)mutable.update{it.copy(confirmation=null,notice="${r.deleted} permanently deleted; ${r.failed} could not be deleted; ${r.cleanupPending} await file cleanup.")}}
    }}
    fun retryCleanup()=work{val token=epoch;val r=withContext(Dispatchers.IO){repository.recoverPendingDocumentDeletions()};if(token==epoch&&active)mutable.update{it.copy(notice=if(r.stillPending>0||r.databaseUnavailable||r.filesystemScanUnavailable)"Some private-file cleanup could not complete. Items in the bin remain protected; retry later."else"Private-file cleanup checked.")}}
}
