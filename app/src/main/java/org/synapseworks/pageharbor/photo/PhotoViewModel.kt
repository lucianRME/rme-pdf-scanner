package org.synapseworks.pageharbor.photo
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
import org.synapseworks.pageharbor.document.session.*
import org.synapseworks.pageharbor.workflow.*

class PhotoViewModel(app:Application):AndroidViewModel(app){
 val owner=UUID.randomUUID().toString()
 var interactionToken by mutableStateOf(0L);private set
 var visible by mutableStateOf(false);private set
 var busy by mutableStateOf(false);private set
 var message by mutableStateOf("");private set
 internal var recipe by mutableStateOf(PhotoRecipe());private set
 internal var direction by mutableStateOf(ScratchDirection.VERTICAL);private set
 internal var dabs by mutableStateOf<List<PhotoDab>>(emptyList());private set
 var erase by mutableStateOf(false)
 var brushRadius by mutableStateOf(0)
 var painting by mutableStateOf(false)
 var before by mutableStateOf(true)
 var reviewed by mutableStateOf(false)
 var picker by mutableStateOf(false);private set
 var ready by mutableStateOf(false);private set
 var draftRevision by mutableStateOf(0L);private set
 internal var capture by mutableStateOf<PhotoCapture?>(null);private set
 internal var result by mutableStateOf<IntArray?>(null);private set
 private val processor=PhotoProcessor(app)
 private var page:DocumentPage?=null
 private var release:(()->Unit)?=null
 private var current:()->Boolean={false}
 @Volatile private var epoch=0L
 @Volatile private var active=true
 private var job:Job?=null
 private var guards=emptyList<WorkflowSourceGuard>()
 private var output:ByteArray?=null
 private class Export(val bytes:ByteArray,val revision:Long,val ticket:String=UUID.randomUUID().toString()){val name="Restored-photo.png";val mime="image/png"}
 private var frozen:Export?=null
 val saveTicket get()=frozen?.ticket
 fun interact(token:Long,action:()->Unit){if(token==interactionToken&&visible&&active&&current())action()}
 private fun valid(token:Long)=visible&&active&&token==epoch&&current()
 internal fun open(p:DocumentPage,sources:List<DocumentResource>,isCurrent:()->Boolean,releaseSource:()->Unit){if(visible){releaseSource();return};interactionToken++;visible=true;active=true;current=isCurrent;page=p;release=releaseSource;val token=++epoch;busy=true;message="Capturing bounded current appearance; waiting for earlier cleanup if needed…"
  job=viewModelScope.launch{var c:PhotoCapture?=null;try{val pair=withContext(Dispatchers.IO){val g=sources.distinctBy{it.reference}.also{require(it.size in 1..40)}.map{workflowSourceGuard(getApplication(),it.toAndroidUri())};c=processor.capture(p){check(valid(token))};c!! to g};check(valid(token));capture=pair.first;c=null;guards=pair.second;message="Preview the corrections, then save a separate PNG."}catch(_:CancellationException){}catch(_:OutOfMemoryError){if(token==epoch)message="Insufficient memory; close restoration and use a smaller image."}catch(_:Exception){if(token==epoch)message="Unavailable: use opaque 8-bit sRGB JPEG/PNG on Android 14+, at most 4096 px/12 MP/32 MiB. HDR, wide gamut, alpha or changed sources are declined."}finally{c?.let{processor.finish(it)};if(token==epoch)busy=false}}
 }
 internal fun settings(r:PhotoRecipe){if(busy||picker)return;recipe=r;invalidate()}
 internal fun orientation(d:ScratchDirection){if(busy||picker)return;direction=d;invalidate()}
 internal fun add(points:List<PhotoDab>){if(busy||picker)return;if(points.size>512||dabs.size+points.size>4096){strokeRefused();return};if(strokes.size>=32){message="Limit: 32 strokes; Undo or Clear mask.";return};strokes=strokes+points.size;dabs=dabs+points;invalidate()}
 fun strokeRefused(){message="Stroke exceeds 512 points or the total 4096-point budget; entire new stroke declined. Shorten it or Clear mask."}
 private var strokes=emptyList<Int>()
 fun undo(){if(busy||picker||strokes.isEmpty())return;dabs=dabs.dropLast(strokes.last());strokes=strokes.dropLast(1);invalidate()}
 fun clearMask(){if(busy||picker)return;strokes=emptyList();dabs=emptyList();invalidate()}
 fun reset(){if(busy||picker)return;recipe=PhotoRecipe();direction=ScratchDirection.VERTICAL;clearMask();invalidate();before=true}
 private fun invalidate(){output=null;result=null;ready=false;reviewed=false;before=true;draftRevision++;message="Preview again after changes. Scratch-only preserves every unmasked pixel before PNG encoding."}
 fun preview(){val c=capture?:return;if(busy||picker||!current())return;val r=recipe;val d=direction;val points=dabs.toList();val token=++epoch;busy=true;ready=false;reviewed=false;before=true;result=null;output=null
  job=viewModelScope.launch{try{val pair=withContext(Dispatchers.Default){val deadline=SystemClock.elapsedRealtime()+8000;val checkWork={check(valid(token));check(SystemClock.elapsedRealtime()<deadline)};val mask=PhotoEngine.mask(c.width,c.height,points,checkWork);val out=PhotoEngine.process(c.width,c.height,c.pixels,r,mask,d,checkWork);out to processor.png(c.width,c.height,out,checkWork)};check(valid(token));result=pair.first;output=pair.second;ready=true;before=false;message="Preview ready. Compare Before/After, then explicitly review to enable separate PNG export."}catch(_:CancellationException){}catch(_:OutOfMemoryError){if(token==epoch)message="Insufficient memory; Reset or use a smaller image."}catch(_:Exception){if(token==epoch)message="Preview declined: mask too wide/large, missing or inconsistent two-sided evidence, changed source or 8-second limit. Correct mask, Reset, or keep original."}finally{if(token==epoch)busy=false}}
 }
 private suspend fun verifySource(token:Long){processor.verify(requireNotNull(page),requireNotNull(capture)){check(valid(token))}}
 fun beginSave():String?{if(!visible||!active||busy||picker||!ready||!reviewed||!current())return null;val bytes=output?:return null;frozen=Export(bytes.copyOf(),draftRevision);picker=true;return frozen!!.name}
 fun destination(ticket:String?,uri:Uri?){if(!picker||ticket==null||ticket!=frozen?.ticket)return;val value=frozen!!;frozen=null;picker=false;if(uri==null){message="Save cancelled.";return};val token=epoch;if(!valid(token))return;busy=true
  job=viewModelScope.launch{try{withContext(Dispatchers.IO){verifySource(token);write(uri,value){check(valid(token)&&draftRevision==value.revision)};verifySource(token)};if(valid(token))message="Separate reviewed PNG saved and bytes verified. Source unchanged."}catch(_:Exception){if(token==epoch)message="Save refused or interrupted; inspect any user-owned partial file. No overwrite was requested."}finally{if(token==epoch)busy=false}}
 }
 private fun write(uri:Uri,e:Export,check:()->Unit){val app=getApplication<Application>();val r=app.contentResolver;require(uri.scheme=="content");check();r.query(uri,arrayOf(D.COLUMN_DISPLAY_NAME,D.COLUMN_SIZE,D.COLUMN_MIME_TYPE),null,null,null)?.use{c->require(c.moveToFirst()&&c.getString(0)==e.name&&!c.isNull(1)&&c.getLong(1)==0L&&c.getString(2)==e.mime)}?:error("DESTINATION")
  val fd=requireNotNull(r.openFileDescriptor(uri,"rw"));try{require(fd.statSize==0L&&workflowDifferentSources(app,fd,uri,guards));check()}catch(t:Throwable){fd.close();throw t};ParcelFileDescriptor.AutoCloseOutputStream(fd).use{check();it.write(e.bytes);it.flush();check()};require(r.openInputStream(uri)?.use{input->val b=ByteArray(4096);var at=0;while(true){check();val n=input.read(b);if(n<0)break;require(n>0&&at+n<=e.bytes.size);for(i in 0 until n)require(b[i]==e.bytes[at+i]);at+=n};at==e.bytes.size}==true);check()
 }
 fun share(launch:(Intent)->Unit){if(!visible||!active||busy||picker||!reviewed||!current())return;val bytes=output?:return;val e=Export(bytes.copyOf(),draftRevision);val token=epoch;busy=true
  job=viewModelScope.launch{var dir:File?=null;var handed=false;try{val f=withContext(Dispatchers.IO){verifySource(token);check(valid(token));val root=File(getApplication<Application>().cacheDir,"shared-pdfs").apply{mkdirs()};root.listFiles().orEmpty().filter{it.name.startsWith("photo-")&&System.currentTimeMillis()-it.lastModified()>86400000}.take(16).forEach{it.deleteRecursively()};require(root.listFiles().orEmpty().count{it.name.startsWith("photo-")}<4&&root.usableSpace>=80L*1024*1024);dir=File(root,"photo-${UUID.randomUUID()}").apply{check(mkdir())};File(dir,e.name).apply{writeBytes(e.bytes)}};check(valid(token)&&draftRevision==e.revision);val app=getApplication<Application>();val uri=FileProvider.getUriForFile(app,app.packageName+".fileprovider",f);launch(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(e.mime).putExtra(Intent.EXTRA_STREAM,uri).apply{clipData=ClipData.newRawUri("Reviewed photo",uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},"Share restored photo"));handed=true;message="Shared reviewed artifact; recipient controls its copy."}catch(_:Exception){if(token==epoch)message="Share unavailable; close old exports or retry later."}finally{if(!handed)dir?.deleteRecursively();if(token==epoch)busy=false}}
 }

 fun changed(){if(visible&&!current())close()}
 fun foreground(unlocked:Boolean){active=unlocked}
 fun background(recreating:Boolean){if(picker&&!recreating){active=false;return};close()}
 fun close(){interactionToken++;epoch++;val old=job;old?.cancel();val c=capture;capture=null;val done=release;release=null;old?.invokeOnCompletion{c?.let(processor::finish);done?.invoke()}?:run{c?.let(processor::finish);done?.invoke()};visible=false;active=false;busy=false;picker=false;page=null;guards=emptyList();result=null;output=null;frozen=null;recipe=PhotoRecipe();dabs=emptyList();strokes=emptyList();reviewed=false;ready=false;painting=false;before=true;message="";current={false};job=null}
 override fun onCleared(){close();super.onCleared()}
}
