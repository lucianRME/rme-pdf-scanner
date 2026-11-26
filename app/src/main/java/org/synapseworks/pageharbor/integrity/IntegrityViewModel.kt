package org.synapseworks.pageharbor.integrity
import android.app.Application
import android.content.*
import android.net.Uri
import android.os.*
import android.provider.DocumentsContract.Document as D
import androidx.compose.runtime.*
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID
import org.synapseworks.pageharbor.workflow.*

class IntegrityViewModel(app:Application):AndroidViewModel(app){
 val owner=UUID.randomUUID().toString()
 var interactionToken by mutableStateOf(0L);private set
 var visible by mutableStateOf(false);private set
 var busy by mutableStateOf(false);private set
 var picker by mutableStateOf(false);private set
 var message by mutableStateOf("");private set
 var report by mutableStateOf("");private set
 var byteEqual by mutableStateOf<Boolean?>(null);private set
 var reviewed by mutableStateOf(false)
 var draftRevision by mutableStateOf(0L);private set
 var page by mutableStateOf(0);private set
 internal var baseline by mutableStateOf<IntegrityInput?>(null);private set
 internal var comparison by mutableStateOf<IntegrityInput?>(null);private set
 internal var left by mutableStateOf<IntegrityDocument?>(null);private set
 internal var right by mutableStateOf<IntegrityDocument?>(null);private set
 internal val differences get()=if(left!=null&&right!=null)IntegrityRules.compare(left!!,right!!)else emptyList()
 private val store=IntegrityStore(app)
 private var work:IntegrityWork?=null
 private var current:()->Boolean={false}
 @Volatile private var epoch=0L
 @Volatile private var active=true
 private var job:Job?=null
 private var guards=emptyList<WorkflowSourceGuard>()
 private class Pick(val baseline:Boolean,val ticket:String=UUID.randomUUID().toString())
 private var pick:Pick?=null
 val inputTicket get()=pick?.ticket
 private class Export(val bytes:ByteArray,val revision:Long,val ticket:String=UUID.randomUUID().toString()){val name="Integrity-report.txt";val mime="text/plain"}
 private var frozen:Export?=null
 val saveTicket get()=frozen?.ticket
 fun interact(token:Long,action:()->Unit){if(token==interactionToken&&visible&&active&&current())action()}
 private fun valid(token:Long)=visible&&active&&token==epoch&&current()
 fun open(isCurrent:()->Boolean){if(visible)return;interactionToken++;visible=true;active=true;current=isCurrent;val token=++epoch;busy=true;message="Preparing private workspace; earlier cleanup may still be finishing."
  job=viewModelScope.launch{var created:IntegrityWork?=null;try{created=store.begin();check(valid(token));work=created;created=null;message="Compare two PDFs for changes. Each can contain up to 4 pages and be up to 8 MiB."}catch(_:Exception){if(token==epoch)message="Workspace unavailable. Free at least160MiB or wait for earlier cleanup, then Close/reopen."}finally{created?.let(store::finish);if(token==epoch)busy=false}}
 }
 fun beginPick(baseline:Boolean):String?{if(!visible||!active||busy||picker||work==null||!current())return null;pick=Pick(baseline);picker=true;reviewed=false;return pick!!.ticket}
 fun selected(ticket:String?,uri:Uri?){val selected=pick?:return;if(!picker||ticket==null||ticket!=selected.ticket)return;pick=null;picker=false;if(uri==null){message="Selection cancelled; previous captured input retained.";return};val w=work?:return;val token=++epoch;if(!valid(token))return;busy=true
  job=viewModelScope.launch{var result:IntegrityInput?=null;try{result=store.capture(w,uri){check(valid(token))};check(valid(token));if(selected.baseline){baseline?.file?.delete();baseline=result}else{comparison?.file?.delete();comparison=result};result=null;left=null;right=null;byteEqual=null;report="";reviewed=false;draftRevision++;page=0;guards=listOfNotNull(baseline?.guard,comparison?.guard);message="Immutable PDF snapshot captured. Select both inputs, then Inspect."}catch(_:Exception){if(token==epoch)message="Input refused: use local regular, unencrypted PDF1.x, at most8MiB/4 pages. Source unavailable, changed, oversized or unsupported."}finally{result?.file?.delete();if(token==epoch)busy=false}}
 }
 fun inspect(){val a=baseline?:return;val b=comparison?:return;val w=work?:return;if(busy||picker||!active||!current())return;val token=++epoch;busy=true;reviewed=false;report="";left=null;right=null;byteEqual=null;draftRevision++
  job=viewModelScope.launch{var exact:Boolean?=null;try{val pair=withContext(Dispatchers.IO){val deadline=SystemClock.elapsedRealtime()+20000;val checkWork={check(valid(token));check(SystemClock.elapsedRealtime()<deadline)};com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(getApplication<Application>());exact=IntegrityRules.bytesEqual(a.file,b.file,checkWork);w.directory.listFiles().orEmpty().filter{it.name.startsWith("baseline-")||it.name.startsWith("comparison-")}.forEach{it.delete()};val x=IntegrityEngine.inspect(a.file,w.directory,"baseline",checkWork);val y=IntegrityEngine.inspect(b.file,w.directory,"comparison",checkWork);checkWork();x to y};check(valid(token));left=pair.first;right=pair.second;byteEqual=exact;report=IntegrityEngine.report(pair.first,pair.second,requireNotNull(exact));page=0;message="Inspection ready. Read separate byte, native-text, raster and signature results; no genuine/fake verdict."}catch(_:Exception){if(valid(token)){byteEqual=exact;report="RME integrity: HASH-ONLY REPORT\nBaseline SHA-256: ${a.sha256}\nComparison SHA-256: ${b.sha256}\nByte equality: ${if(exact==null)"NOT_CHECKED"else if(exact==true)"BYTE_IDENTICAL"else"DIFFERENT_BYTES"}\nPDF comparison/signatures: NOT_CHECKED; parser/resource/encryption/format limit. No absence or authenticity claim.\nContent Credentials NOT_CHECKED/NOT_IMPLEMENTED. Trust/timestamp/revocation NOT_CHECKED; no remote fetch.\n";message="PDF inspection refused. A clearly labeled hash-only report is available; choose smaller supported PDFs."}}catch(_:OutOfMemoryError){if(token==epoch)message="Memory limit: Close and choose smaller PDFs; no inspection success."}finally{if(token==epoch)busy=false}}
 }
 fun move(n:Int){if(busy||picker)return;page=(page+n).coerceIn(0,(differences.size-1).coerceAtLeast(0))}
 private suspend fun verifyInputs(token:Long){for(input in listOfNotNull(baseline,comparison))store.verify(input){check(valid(token))}}
 fun beginSave():String?{if(!visible||!active||busy||picker||!reviewed||report.isBlank()||!current())return null;val bytes=report.toByteArray(Charsets.UTF_8);require(bytes.size<=65536);frozen=Export(bytes,draftRevision);picker=true;return frozen!!.name}
 fun destination(ticket:String?,uri:Uri?){if(!picker||ticket==null||ticket!=frozen?.ticket)return;val value=frozen!!;frozen=null;picker=false;if(uri==null){message="Save cancelled.";return};val token=epoch;if(!valid(token))return;busy=true
  job=viewModelScope.launch{try{withContext(Dispatchers.IO){verifyInputs(token);write(uri,value){check(valid(token)&&draftRevision==value.revision)};verifyInputs(token)};if(valid(token))message="Separate reviewed report saved and bytes verified. Inputs unchanged."}catch(_:Exception){if(token==epoch)message="Save refused/interrupted: inputs changed or destination unsafe. Inspect any user-owned partial file."}finally{if(token==epoch)busy=false}}
 }
 private fun write(uri:Uri,e:Export,check:()->Unit){val app=getApplication<Application>();val r=app.contentResolver;require(uri.scheme=="content");check();r.query(uri,arrayOf(D.COLUMN_DISPLAY_NAME,D.COLUMN_SIZE,D.COLUMN_MIME_TYPE),null,null,null)?.use{c->require(c.moveToFirst()&&c.getString(0)==e.name&&!c.isNull(1)&&c.getLong(1)==0L&&c.getString(2)==e.mime)}?:error("DESTINATION")
  val fd=requireNotNull(r.openFileDescriptor(uri,"rw"));try{require(fd.statSize==0L&&workflowDifferentSources(app,fd,uri,guards));check()}catch(t:Throwable){fd.close();throw t};ParcelFileDescriptor.AutoCloseOutputStream(fd).use{check();it.write(e.bytes);it.flush();check()};require(r.openInputStream(uri)?.use{input->val b=ByteArray(4096);var at=0;while(true){check();val n=input.read(b);if(n<0)break;require(n>0&&at+n<=e.bytes.size);for(i in 0 until n)require(b[i]==e.bytes[at+i]);at+=n};at==e.bytes.size}==true);check()
 }
 fun share(launch:(Intent)->Unit){if(!visible||!active||busy||picker||!reviewed||!current()||report.isBlank())return;val e=Export(report.toByteArray(Charsets.UTF_8),draftRevision);val token=epoch;busy=true
  job=viewModelScope.launch{var dir:File?=null;var handed=false;try{val f=withContext(Dispatchers.IO){verifyInputs(token);check(valid(token));val root=File(getApplication<Application>().cacheDir,"shared-pdfs").apply{mkdirs()};root.listFiles().orEmpty().filter{it.name.startsWith("integrity-")&&System.currentTimeMillis()-it.lastModified()>86400000}.take(16).forEach{it.deleteRecursively()};require(root.listFiles().orEmpty().count{it.name.startsWith("integrity-")}<4);dir=File(root,"integrity-${UUID.randomUUID()}").apply{check(mkdir())};File(dir,e.name).apply{writeBytes(e.bytes)}};check(valid(token)&&draftRevision==e.revision);val app=getApplication<Application>();val uri=FileProvider.getUriForFile(app,app.packageName+".fileprovider",f);launch(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(e.mime).putExtra(Intent.EXTRA_STREAM,uri).apply{clipData=ClipData.newRawUri("Integrity report",uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},"Share integrity report"));handed=true;message="Shared reviewed artifact; recipient controls its copy."}catch(_:Exception){if(token==epoch)message="Share unavailable; close old exports or retry later."}finally{if(!handed)dir?.deleteRecursively();if(token==epoch)busy=false}}
 }

 fun changed(){if(visible&&!current())close()}
 fun foreground(unlocked:Boolean){active=unlocked}
 fun background(recreating:Boolean){if(picker&&!recreating){active=false;return};close()}
 fun close(){interactionToken++;epoch++;val old=job;old?.cancel();val w=work;work=null;old?.invokeOnCompletion{w?.let(store::finish)}?:w?.let(store::finish);visible=false;active=false;busy=false;picker=false;baseline=null;comparison=null;left=null;right=null;guards=emptyList();byteEqual=null;report="";frozen=null;pick=null;reviewed=false;page=0;current={false};job=null;message=""}
 override fun onCleared(){close();super.onCleared()}
}
