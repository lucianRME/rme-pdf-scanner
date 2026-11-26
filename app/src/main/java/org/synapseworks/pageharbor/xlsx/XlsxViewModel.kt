package org.synapseworks.pageharbor.xlsx

import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.*
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.synapseworks.pageharbor.receipt.*
import org.synapseworks.pageharbor.table.*
import org.synapseworks.pageharbor.workflow.*

internal data class XlsxPrepared(val file:File,val digest:Pair<String,Long>,val book:XlsxBook,val revision:Long,
    val owner:String,val guards:List<WorkflowSourceGuard>) {override fun toString()="XlsxPrepared(<redacted>)"}
internal class XlsxViewModel(application:Application):AndroidViewModel(application) {
    companion object {private val work=Mutex();private val owners=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()}
    val activityOwner=UUID.randomUUID().toString()
    private val base=File(application.noBackupFilesDir,"xlsx-v1")
    private val root=File(base,activityOwner)
    private val inputs=mutableMapOf<Int,XlsxInput>()
    var visible by mutableStateOf(false);private set
    var collecting by mutableStateOf(false);private set
    var book by mutableStateOf(XlsxBook());private set
    var prepared by mutableStateOf<XlsxPrepared?>(null);private set
    var name by mutableStateOf("Workbook.xlsx");private set
    var busy by mutableStateOf(false);private set
    var progress by mutableStateOf(0);private set
    var message by mutableStateOf<String?>(null);private set
    var picker by mutableStateOf(false);private set
    var reviewed by mutableStateOf(false);private set
    var savedDestination:Uri?=null;private set
    @Volatile private var epoch=0L
    @Volatile private var active=true
    private var revision=0L
    private var job:Job?=null
    private data class Reservation(val epoch:Long,val prepared:XlsxPrepared,val name:String){override fun toString()="Reservation(<redacted>)"}
    private var reservation:Reservation?=null
    init {
        owners+=activityOwner
        base.listFiles()?.filter{it.isDirectory&&it.name !in owners&&System.currentTimeMillis()-it.lastModified()>86_400_000L}?.take(16)?.forEach{it.deleteRecursively()}
        File(application.cacheDir,"shared-pdfs").listFiles()?.filter{it.isDirectory&&it.name.startsWith("xlsx-")&&System.currentTimeMillis()-it.lastModified()>86_400_000L}?.take(16)?.forEach{it.deleteRecursively()}
    }
    private fun addInput(input:XlsxInput):Int? {
        if(!active||busy||picker)return null
        if(!input.exportable||inputs.size>=16){visible=true;collecting=false;message="Workbook source declined: unverified image identity or source/work limit. Original extraction remains available.";return null}
        val n=(1..16).first{it !in inputs}
        inputs[n]=input;return n
    }
    fun openEmpty(){if(!busy&&!picker&&active){visible=true;collecting=false}}
    fun table(state:TableState,input:XlsxInput) {
        if(book.tables.size>=4){message="At most four tables per workbook.";return}
        if(state.phase!=TablePhase.REVIEW||!state.confirmed)return
        val n=addInput(input)?:return
        mutate(book.copy(tables=book.tables+XlsxCapture.table(state,n,input.kind)));visible=true;collecting=false
    }
    fun receipt(state:ReceiptState,input:XlsxInput) {
        if(book.expenses.size>=12){message="At most twelve receipts per workbook.";return}
        if(state.phase!=ReceiptPhase.REVIEW)return
        val n=addInput(input)?:return
        mutate(book.copy(expenses=book.expenses+XlsxCapture.receipt(state,n,input.kind)));visible=true;collecting=false
    }
    private fun mutate(value:XlsxBook) {
        if(busy||picker||!active)return
        epoch++;revision++;prepared?.file?.delete();prepared=null;reviewed=false;savedDestination=null;message=null;book=value.owned()
    }
    fun update(value:XlsxBook){mutate(value)}
    fun table(index:Int,value:XlsxTable){if(index !in book.tables.indices)return;mutate(book.copy(tables=book.tables.toMutableList().apply{set(index,value.copy(types=value.types.filterKeys{key->value.grid.rows.flatten().any{it.id==key}},confirmed=false))}))}
    fun expense(index:Int,value:XlsxExpense){if(index !in book.expenses.indices)return;mutate(book.copy(expenses=book.expenses.toMutableList().apply{set(index,value.copy(confirmed=false))}))}
    fun confirmTable(index:Int,value:Boolean){if(index !in book.tables.indices)return;mutate(book.copy(tables=book.tables.toMutableList().apply{set(index,get(index).copy(confirmed=value))}))}
    fun confirmExpense(index:Int,value:Boolean){if(index !in book.expenses.indices)return;mutate(book.copy(expenses=book.expenses.toMutableList().apply{set(index,get(index).copy(confirmed=value))}))}
    fun removeTable(index:Int){val t=book.tables.getOrNull(index)?:return;if(busy||picker)return;inputs.remove(t.source.number);mutate(book.copy(tables=book.tables.filterIndexed{i,_->i!=index}))}
    fun removeExpense(index:Int){val e=book.expenses.getOrNull(index)?:return;if(busy||picker)return;inputs.remove(e.source.number);mutate(book.copy(expenses=book.expenses.filterIndexed{i,_->i!=index}))}
    fun collect(action:()->Unit){if(!active||busy||picker)return;collecting=true;visible=false;action()}
    fun resume(){if(collecting){collecting=false;visible=true}}
    fun rename(value:String){if(busy||picker||!active)return;prepared?.file?.delete();prepared=null;name=value.take(120);reviewed=false;epoch++;revision++}
    fun nameValid()=runCatching{require(name.endsWith(".xlsx"));WorkflowNaming.verifyExported(name,"xlsx")}.isSuccess
    fun review(value:Boolean){if(!busy&&!picker&&active)reviewed=value}
    fun generate() {
        if(!active||busy||picker||!reviewed||book.problem()!=null||!nameValid())return
        val frozen=book.owned();val currentInputs=inputs.toMap();val token=++epoch;val currentRevision=revision
        busy=true;progress=0;message=null;prepared?.file?.delete();prepared=null
        job=viewModelScope.launch {
            var output:File?=null
            try {
                val result=withContext(Dispatchers.IO){work.withLock{
                    val check:()->Unit={ensureActive();check(active&&epoch==token)}
                    check()
                    val dirs=base.listFiles().orEmpty();require(dirs.size<32||root.isDirectory&&dirs.size==32)
                    var used=0L;for(d in dirs){require(d.canonicalFile.parentFile==base.canonicalFile);val files=d.listFiles().orEmpty();require(files.size<=4);used+=files.sumOf{it.length()}}
                    require(used+XLSX_MAX<=32L*1024*1024&&getApplication<Application>().noBackupFilesDir.usableSpace>=32L*1024*1024)
                    require(root.mkdirs()||root.isDirectory)
                    for(input in currentInputs.values)if(input.guard!=null){
                        val now=XlsxInput.image(getApplication(),Uri.parse(input.guard.uri),check)
                        require(now==input)
                    }
                    val f=File(root,"workbook-${UUID.randomUUID()}.xlsx");output=f
                    XlsxWriter.write(frozen,f,check){n->viewModelScope.launch{if(epoch==token)progress=n}}
                    val guards=currentInputs.values.mapNotNull{it.guard}+workflowSourceGuard(getApplication(),Uri.fromFile(f))
                    XlsxPrepared(f,workflowDigest(f.inputStream(),XLSX_MAX,check),frozen,currentRevision,activityOwner,guards)
                }}
                ensureActive();check(active&&epoch==token);prepared=result;output=null;message="Reviewed workbook generated. Save a separate copy or Share; inspect it in a spreadsheet application."
            }catch(_:CancellationException){}catch(_:OutOfMemoryError){message="Workbook exceeded available memory. Nothing accepted."}
            catch(_:Exception){message="Workbook declined: unsupported value, fit, source change or storage limit. Originals unchanged."}
            finally{withContext(NonCancellable+Dispatchers.IO){output?.delete()};if(token==epoch)busy=false}
        }
    }
    fun beginSave():String? {
        if(reservation!=null){message="Finish or cancel the outstanding Android Save first.";return null}
        val p=prepared?:return null;if(!active||busy||picker||!nameValid())return null
        reservation=Reservation(epoch,p,name);picker=true;return name
    }
    fun destination(uri:Uri?) {
        val pending=reservation?:return;reservation=null
        if(!picker||pending.epoch!=epoch||prepared!==pending.prepared||!active)return
        picker=false
        if(uri==null){message="Save cancelled. Reviewed workbook retained; source unchanged.";return}
        val token=epoch;busy=true
        job=viewModelScope.launch {
            try {
                withContext(Dispatchers.IO){work.withLock{writeXlsx(getApplication(),pending.prepared.file,uri,pending.name,pending.prepared.digest,pending.prepared.guards){ensureActive();check(active&&epoch==token)}}}
                ensureActive();check(active&&epoch==token);savedDestination=uri;message="Separate XLSX saved and bytes verified. Original sources unchanged."
            }catch(_:CancellationException){}catch(_:Exception){message="Save failed or was interrupted. Inspect any user-owned partial file before retrying; it was not deleted or overwritten."}
            finally{if(epoch==token)busy=false}
        }
    }
    fun share(launch:(Intent)->Unit) {
        val p=prepared?:return;if(!active||busy||picker||!nameValid())return
        val token=epoch;val frozenName=name;busy=true
        job=viewModelScope.launch {
            var directory:File?=null;var handed=false
            try {
                val file=withContext(Dispatchers.IO){work.withLock{
                    val check:()->Unit={ensureActive();check(active&&epoch==token)}
                    val base=File(getApplication<Application>().cacheDir,"shared-pdfs").apply{mkdirs()}
                    require(base.listFiles().orEmpty().count{it.name.startsWith("xlsx-")}<4)
                    val dir=File(base,"xlsx-${UUID.randomUUID()}").apply{check(mkdir())};directory=dir
                    val f=File(dir,frozenName);require(workflowDigest(p.file.inputStream(),XLSX_MAX,check)==p.digest)
                    p.file.inputStream().use{input->f.outputStream().use{out->val buffer=ByteArray(65536);while(true){check();val n=input.read(buffer);if(n<0)break;out.write(buffer,0,n)}}};check();f
                }}
                ensureActive();check(active&&epoch==token)
                val app=getApplication<Application>();val uri=FileProvider.getUriForFile(app,app.packageName+".fileprovider",file)
                launch(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type=XLSX_MIME;putExtra(Intent.EXTRA_STREAM,uri);clipData=ClipData.newRawUri("XLSX",uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},"Share reviewed workbook"));handed=true
                message="Shared reviewed XLSX. The receiving app controls its copy."
            }catch(_:CancellationException){}catch(_:Exception){message="Share unavailable. No original changed."}
            finally{if(!handed)directory?.deleteRecursively();if(epoch==token)busy=false}
        }
    }
    fun foreground(unlocked:Boolean){active=unlocked}
    fun background(recreating:Boolean,awaitingInput:Boolean=false){if(!recreating&&(picker||collecting&&awaitingInput)){active=false;return};close()}
    fun cancelWork(){if(!active)return;epoch++;job?.cancel();busy=false;message="Cancelled. Any external partial file remains under your control."}
    fun close() {
        val files=root.listFiles().orEmpty().toList();active=false;epoch++;val previous=job;previous?.cancel();visible=false;collecting=false;picker=false;busy=false;prepared=null;book=XlsxBook();inputs.clear();reviewed=false;savedDestination=null;name="Workbook.xlsx"
        viewModelScope.launch(NonCancellable+Dispatchers.IO){previous?.join();files.forEach{it.delete()};root.delete()}
    }
    override fun onCleared(){close();owners-=activityOwner;super.onCleared()}
}
