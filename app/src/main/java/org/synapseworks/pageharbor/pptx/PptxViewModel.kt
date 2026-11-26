package org.synapseworks.pageharbor.pptx

import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.*
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import org.synapseworks.pageharbor.docx.*
import org.synapseworks.pageharbor.document.session.DocumentSession
import org.synapseworks.pageharbor.ocr.OcrResult
import org.synapseworks.pageharbor.workflow.*

internal data class PptxPreview(val file:File,val width:Int,val height:Int){override fun toString()="PptxPreview(<redacted>)"}
internal data class PptxPrepared(val file:File,val preview:File,val digest:Pair<String,Long>,val previewDigest:String,val deck:PptxDeck,
    val revision:Long,val owner:String,val guards:List<WorkflowSourceGuard>){override fun toString()="PptxPrepared(<redacted>)"}
internal class PptxViewModel(application:Application):AndroidViewModel(application) {
    private companion object{val owners=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()}
    val activityOwner=UUID.randomUUID().toString()
    private val base=File(application.noBackupFilesDir,"pptx-v1");private val root=File(base,activityOwner)
    var visible by mutableStateOf(false);private set
    var capture by mutableStateOf<PptxCapture?>(null);private set
    var deck by mutableStateOf<PptxDeck?>(null);private set
    var prepared by mutableStateOf<PptxPrepared?>(null);private set
    var preview by mutableStateOf<PptxPreview?>(null);private set
    var previewReady by mutableStateOf(false);private set
    fun displayReady(request:PptxPreview,ready:Boolean){if(preview===request)previewReady=ready}
    var sourceView by mutableStateOf(true);private set
    var previewStale by mutableStateOf(false);private set
    var page by mutableIntStateOf(0);private set
    var busy by mutableStateOf(false);private set
    var progress by mutableIntStateOf(0);private set
    var message by mutableStateOf<String?>(null);private set
    var name by mutableStateOf("Slides.pptx");private set
    var picker by mutableStateOf(false);private set
    var savedDestination:Uri?=null;private set
    private data class Reservation(val epoch:Long,val prepared:PptxPrepared,val name:String){override fun toString()="Reservation(<redacted>)"}
    private var reservation:Reservation?=null
    @Volatile private var active=true
    @Volatile private var epoch=0L
    private var revision=0L
    private var sourceRevision=0L
    private var sourceOwner=""
    private var operation:Job?=null
    init {
        owners+=activityOwner
        base.listFiles()?.filter{it.isDirectory&&it.name !in owners&&System.currentTimeMillis()-it.lastModified()>86_400_000L}?.take(16)?.forEach{it.deleteRecursively()}
        File(application.cacheDir,"shared-pdfs").listFiles()?.filter{it.name.startsWith("pptx-")&&System.currentTimeMillis()-it.lastModified()>86_400_000L}?.take(16)?.forEach{it.deleteRecursively()}
    }
    private fun space(reserve:Long) {
        val dirs=base.listFiles().orEmpty();require(dirs.size<32||root.isDirectory&&dirs.size==32)
        val used=base.walkTopDown().filter{it.isFile}.sumOf{it.length()};require(used+reserve<=256L*1024*1024&&getApplication<Application>().noBackupFilesDir.usableSpace>=320L*1024*1024)
    }
    fun open(session:DocumentSession,revision:Long,owner:String,current:()->Boolean,release:()->Unit,ocrProvider:suspend()->OcrResult?) {
        if(visible||busy||!active){release();return};visible=true;busy=true;message=null;sourceRevision=revision;sourceOwner=owner;val token=++epoch
        operation=viewModelScope.launch {
            var result:PptxCapture?=null
            try{
                val ocr=ocrProvider();ensureActive();check(active&&epoch==token&&current())
                withContext(Dispatchers.IO){DocxWork.mutex.withLock{space(190L*1024*1024);check(root.mkdirs()||root.isDirectory);result=capturePptx(getApplication(),root,session,ocr){active&&epoch==token&&current()}}}
                ensureActive();check(active&&epoch==token&&current());capture=checkNotNull(result);deck=result!!.deck.owned();result=null;busy=false;page=0;sourceView=true;render()
            }catch(_:CancellationException){}catch(_:OutOfMemoryError){message="Capture exceeded memory limits. Original sources unchanged."}catch(_:Exception){message="Capture declined: changed/missing source, unsupported PDF or resource limit. Original sources unchanged."}
            finally{withContext(NonCancellable+Dispatchers.IO){result?.source?.frozen?.directory?.deleteRecursively()};release();if(epoch==token&&capture==null)busy=false}
        }
    }
    private fun discardOutput(){prepared?.let{it.file.delete();it.preview.delete()};prepared=null;savedDestination=null}
    private fun mutate(value:PptxDeck){if(!active||busy||picker)return;discardOutput();epoch++;revision++;deck=value.owned();previewStale=!sourceView;message=null}
    fun settings(mode:PptxMode,size:PptxSize){val d=deck?:return;if(busy||picker)return
        val pages=d.pages.map{p->val factor=PptxGeometry.page(p,size).width/PptxGeometry.page(p,d.size).width;p.copy(coverage=false,items=p.items.map{if(it.kind==PptxKind.TEXT)it.copy(font=it.font*factor)else it})}
        mutate(d.copy(pages=pages,mode=mode,size=size));sourceView=false;render()
    }
    fun rename(value:String){if(!busy&&!picker&&active){discardOutput();name=value.take(120);epoch++;revision++}}
    fun nameValid()=runCatching{WorkflowNaming.verifyExported(name,"pptx")}.isSuccess
    fun item(value:PptxItem){val d=deck?:return;val p=d.pages[page];mutate(d.copy(pages=d.pages.toMutableList().apply{set(page,p.copy(coverage=false,items=p.items.map{if(it.id==value.id)value.copy(corrected=true)else it}))}))}
    fun move(id:Int,delta:Int){val d=deck?:return;val p=d.pages[page];val i=p.items.indexOfFirst{it.id==id};if(i<0||i+delta !in p.items.indices)return;mutate(d.copy(pages=d.pages.toMutableList().apply{set(page,p.copy(coverage=false,items=p.items.toMutableList().apply{add(i+delta,removeAt(i).copy(corrected=true))}))}))}
    fun omit(id:Int){val d=deck?:return;val p=d.pages[page];val b=p.items.firstOrNull{it.id==id}?:return;if(busy||picker)return
        mutate(d.copy(pages=d.pages.toMutableList().apply{set(page,p.copy(coverage=false,items=p.items.filter{it.id!=id},omitted=p.omitted+b.sourceIds))}));b.media?.file?.delete()
    }
    fun add(kind:PptxKind,box:DocxBox?){val d=deck?:return;val p=d.pages[page];if(kind==PptxKind.IMAGE||p.items.size>=128)return
        val b=PptxItem((p.items.maxOfOrNull{it.id}?:0)+1,kind,box,font=18f,color=if(kind==PptxKind.TEXT)"000000"else"2563EB",corrected=true)
        mutate(d.copy(pages=d.pages.toMutableList().apply{set(page,p.copy(coverage=false,items=p.items+b))}))
    }
    fun picture(box:DocxBox,textFreeConfirmed:Boolean){val c=capture?:return;val d=deck?:return;val index=page;val p=d.pages[index]
        if(busy||picker||!textFreeConfirmed)return
        if(d.pages.sumOf{it.items.count{b->b.kind==PptxKind.IMAGE}}>=20||p.items.size>=128){message="Picture/object budget reached.";return}
        if(p.items.any{it.kind==PptxKind.TEXT&&it.box?.intersects(box)==true}){message="Image selection intersects native text. Select text-free artwork.";return}
        val token=epoch;busy=true
        operation=viewModelScope.launch {
            var pic:PptxMedia?=null
            try{
                val pair=withContext(Dispatchers.IO){DocxWork.mutex.withLock{space(2L*1024*1024);cropPptxPicture(c,index,box){ensureActive();check(active&&epoch==token)}}};pic=pair.first
                ensureActive();check(active&&epoch==token&&capture===c);busy=false
                val b=PptxItem((p.items.maxOfOrNull{it.id}?:0)+1,PptxKind.IMAGE,pair.second,media=pic,sourceRegion=pair.second,corrected=true)
                mutate(d.copy(pages=d.pages.toMutableList().apply{set(index,p.copy(coverage=false,items=p.items+b))}));pic=null
            }catch(_:CancellationException){}catch(_:OutOfMemoryError){message="Crop exceeded memory limits. Original sources unchanged."}catch(_:Exception){message="Crop declined: original text, incomplete geometry or resource limit. No original content changed."}
            finally{pic?.file?.delete();if(epoch==token)busy=false}
        }
    }
    fun reset(){val c=capture?:return;val d=deck?:return;if(busy||picker)return;val old=d.pages[page];mutate(d.copy(pages=d.pages.toMutableList().apply{set(page,c.deck.pages[page].owned())}));old.items.mapNotNull{it.media?.file}.forEach{it.delete()}}
    fun show(index:Int){val d=deck?:return;if(busy||picker||index !in d.pages.indices)return;page=index;render()}
    fun compare(source:Boolean){if(!busy&&!picker){sourceView=source;render()}}
    private fun render(){val d=deck?.owned()?:return;val c=capture?:return;if(busy)return;val index=page;val original=sourceView;val token=epoch
        preview?.file?.delete();preview=null;previewReady=false;busy=true
        operation=viewModelScope.launch {
            var output:File?=null
            try{
                val result=withContext(Dispatchers.IO){DocxWork.mutex.withLock{
                    val check:()->Unit={ensureActive();check(active&&epoch==token)};space(8L*1024*1024);check()
                    val bitmap=if(original){val m=d.pages[index].visual;var sample=1;while(m.width/sample>900||m.height/sample>1600)sample*=2;BitmapFactory.decodeFile(m.file.path,BitmapFactory.Options().apply{inSampleSize=sample})?:error("Preview unavailable")}else PptxRender.bitmap(d,index,check)
                    try{
                        val file=File(c.source.frozen.directory,"display-${UUID.randomUUID()}.png");output=file
                        file.outputStream().use{raw->val bounded=object:java.io.FilterOutputStream(raw){var bytes=0L
                            override fun write(b:Int){check();require(++bytes<=8L*1024*1024);out.write(b)}
                            override fun write(b:ByteArray,off:Int,len:Int){check();bytes+=len;require(bytes<=8L*1024*1024);out.write(b,off,len)}
                        };check(bitmap.compress(Bitmap.CompressFormat.PNG,100,bounded))}
                        check();PptxPreview(file,bitmap.width,bitmap.height)
                    }finally{bitmap.recycle()}
                }}
                ensureActive();check(active&&epoch==token);preview=result;output=null;previewStale=false
            }catch(_:CancellationException){}catch(_:OutOfMemoryError){message="Preview exceeded memory limits. Cancel or reduce the selection.";previewStale=true}catch(_:Exception){message="Preview unavailable or unsupported glyph/placement. Review the source and correct objects before export.";previewStale=true}
            finally{withContext(NonCancellable+Dispatchers.IO){output?.delete()};if(epoch==token)busy=false}
        }
    }
    fun review(value:Boolean){val d=deck?:return;if(busy||picker||sourceView||previewStale||preview==null||!previewReady)return;discardOutput();deck=d.copy(pages=d.pages.toMutableList().apply{set(page,get(page).copy(coverage=value))})}
    fun generate(){val c=capture?:return;val d=deck?.owned()?:return
        if(busy||picker||!active||!nameValid()||d.pages.any{!it.coverage}||d.problem()!=null)return
        discardOutput();busy=true;progress=0;val token=epoch
        operation=viewModelScope.launch {
            var output:File?=null;var pdf:File?=null
            try{
                val result=withContext(Dispatchers.IO){DocxWork.mutex.withLock{space(48L*1024*1024);val check:()->Unit={ensureActive();check(active&&epoch==token)};PptxRender.fit(d)
                    output=File(c.source.frozen.directory,"export-${UUID.randomUUID()}.pptx");pdf=File(c.source.frozen.directory,"review-${UUID.randomUUID()}.pdf")
                    PptxWriter.write(d,output!!,check){n->viewModelScope.launch{if(epoch==token)progress=n}};PptxRender.pdf(d,pdf!!,check)
                    PptxPrepared(output!!,pdf!!,workflowDigest(output!!.inputStream(),PPTX_MAX,check),workflowDigest(pdf!!.inputStream(),16L*1024*1024,check).first,d,sourceRevision,sourceOwner+":"+revision,c.source.frozen.sourceGuards+workflowSourceGuard(getApplication(),Uri.fromFile(output!!)))
                }}
                ensureActive();check(active&&epoch==token);prepared=result;output=null;pdf=null;message="Reviewed PPTX generated. Local preview approximates the export plan; verify fonts and edits in your presentation app. Original sources unchanged."
            }catch(_:CancellationException){}catch(_:OutOfMemoryError){message="Export exceeded memory limits. No output accepted."}catch(_:Exception){message="PPTX declined: unsupported fit/glyph, missing resource or storage limit. No output accepted."}
            finally{withContext(NonCancellable+Dispatchers.IO){output?.delete();pdf?.delete()};if(epoch==token)busy=false}
        }
    }
    fun beginSave():String?{if(reservation!=null){message="Finish or cancel the outstanding Android Save first.";return null};val p=prepared?:return null;if(!active||busy||picker||!nameValid())return null;reservation=Reservation(epoch,p,name);picker=true;return name}
    fun destination(uri:Uri?){val pending=reservation?:return;reservation=null;if(!active||!picker||epoch!=pending.epoch||prepared!==pending.prepared)return;picker=false
        if(uri==null){message="Save cancelled; frozen reviewed bytes retained.";return};val token=epoch;busy=true
        operation=viewModelScope.launch {try{withContext(Dispatchers.IO){DocxWork.mutex.withLock{writePptx(getApplication(),pending.prepared.file,uri,pending.name,pending.prepared.digest,pending.prepared.guards){ensureActive();check(active&&epoch==token)}}};ensureActive();check(active&&epoch==token);savedDestination=uri;message="Separate PPTX saved and bytes verified. Original sources unchanged."}catch(_:CancellationException){}catch(_:Exception){message="Save failed or interrupted. Inspect user-owned partial output before retrying; it was not deleted or overwritten."}finally{if(epoch==token)busy=false}}
    }
    fun share(launch:(Intent)->Unit){val p=prepared?:return;if(!active||busy||picker||!nameValid())return;val token=epoch;val frozenName=name;busy=true
        operation=viewModelScope.launch {var dir:File?=null;var handed=false
            try{val file=withContext(Dispatchers.IO){DocxWork.mutex.withLock{val check:()->Unit={ensureActive();check(active&&epoch==token)};val base=File(getApplication<Application>().cacheDir,"shared-pdfs").apply{mkdirs()};require(base.listFiles().orEmpty().count{it.name.startsWith("pptx-")}<4);check();val owned=File(base,"pptx-${UUID.randomUUID()}").apply{check(mkdir())};dir=owned;val f=File(owned,frozenName);require(workflowDigest(p.file.inputStream(),PPTX_MAX,check)==p.digest);p.file.inputStream().use{input->f.outputStream().use{out->val b=ByteArray(65536);while(true){check();val n=input.read(b);if(n<0)break;out.write(b,0,n)}}};check();f}}
                ensureActive();check(active&&epoch==token);val app=getApplication<Application>();val uri=FileProvider.getUriForFile(app,app.packageName+".fileprovider",file);launch(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type=PPTX_MIME;putExtra(Intent.EXTRA_STREAM,uri);clipData=ClipData.newRawUri("PPTX",uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},"Share reviewed PPTX"));handed=true
            }catch(_:CancellationException){}catch(_:Exception){message="Share unavailable; no original changed."}finally{if(!handed)dir?.deleteRecursively();if(epoch==token)busy=false}
        }
    }
    fun foreground(unlocked:Boolean){active=unlocked}
    fun background(recreating:Boolean){if(picker&&!recreating){active=false;return};close()}
    fun close(){val old=capture;active=false;epoch++;val job=operation;job?.cancel();capture=null;deck=null;prepared=null;preview?.file?.delete();preview=null;previewReady=false;visible=false;busy=false;picker=false;savedDestination=null;name="Slides.pptx";viewModelScope.launch(NonCancellable+Dispatchers.IO){job?.join();old?.source?.frozen?.directory?.deleteRecursively();root.delete()}}
    override fun onCleared(){close();owners-=activityOwner;super.onCleared()}
}
