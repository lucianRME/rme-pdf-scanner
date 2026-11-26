package org.synapseworks.pageharbor.docx

import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.synapseworks.pageharbor.document.session.DocumentSession
import org.synapseworks.pageharbor.ocr.OcrResult
import org.synapseworks.pageharbor.table.TableGrid
import org.synapseworks.pageharbor.workflow.*

internal data class DocxPrepared(val file:File,val digest:Pair<String,Long>,val document:DocxDocument,val revision:Long,val owner:String) {
    override fun toString()="DocxPrepared(<redacted>)"
}
/** Ephemeral review. No draft, OCR, title, grant or picker state enters SavedState/Room/backup. */
internal class DocxViewModel(application:Application):AndroidViewModel(application) {
    private companion object { val activeOwners=java.util.concurrent.ConcurrentHashMap.newKeySet<String>() }
    @Volatile private var clearing=false
    val activityOwner=UUID.randomUUID().toString()
    private val root=File(application.noBackupFilesDir,"docx-v1/$activityOwner")
    var recipeMode by mutableStateOf(false);private set
    var visible by mutableStateOf(false);private set
    var capture by mutableStateOf<DocxCapture?>(null);private set
    var document by mutableStateOf<DocxDocument?>(null);private set
    var prepared by mutableStateOf<DocxPrepared?>(null);private set
    var preview by mutableStateOf<Bitmap?>(null);private set
    var page by mutableStateOf(0);private set
    var busy by mutableStateOf(false);private set
    var message by mutableStateOf<String?>(null);private set
    var reviewed by mutableStateOf(setOf<Int>());private set
    var appearances by mutableStateOf(true);private set
    var name by mutableStateOf("Document.docx");private set
    var progress by mutableStateOf(0);private set
    private var outstandingPicker=false
    var picker by mutableStateOf(false);private set
    var savedDestination:Uri?=null;private set
    @Volatile private var epoch=0L
    @Volatile private var active=true
    private var operation:Job?=null
    private var revision=0L
    private var sourceOwner=""
    init {
        activeOwners+=activityOwner
        // Other activity owners may still be alive. Only age-expired, bounded private remnants.
        File(application.noBackupFilesDir,"docx-v1").listFiles()?.filter{it.isDirectory&&it.name !in activeOwners&&System.currentTimeMillis()-it.lastModified()>86_400_000L}?.take(16)?.forEach{it.deleteRecursively()}
        val shared=File(application.cacheDir,"shared-pdfs")
        shared.listFiles()?.filter{it.name.startsWith("docx-")&&System.currentTimeMillis()-it.lastModified()>86_400_000L}?.take(16)?.forEach{it.deleteRecursively()}
    }
    fun open(session:DocumentSession,ocr:OcrResult?,revision:Long,owner:String,current:()->Boolean,release:()->Unit,recipeName:String?=null,ocrProvider:(suspend()->OcrResult?)?=null) {
        if(visible||busy||!active){release();return}
        visible=true;busy=true;message=null;this.revision=revision;sourceOwner=owner;recipeMode=recipeName!=null;if(recipeName!=null)name=recipeName
        val token=++epoch
        operation=viewModelScope.launch {
            var result:DocxCapture?=null
            try {
                val effectiveOcr=if(ocrProvider!=null)ocrProvider()else ocr
                ensureActive();check(active&&token==epoch&&current())
                withContext(Dispatchers.IO){DocxWork.mutex.withLock{check(root.mkdirs()||root.isDirectory);DocxWork.space(getApplication(),190L*1024*1024);result=captureDocx(getApplication(),root,session,effectiveOcr){active&&token==epoch&&current()}}}
                ensureActive();check(active&&token==epoch&&current())
                val accepted=checkNotNull(result);capture=accepted;document=accepted.document.owned();result=null;busy=false;showPage(0)
            } catch(_:CancellationException) { } catch(_:OutOfMemoryError){message="Capture exceeded available memory. Cancel and use a smaller document."}
            catch(_:Exception){message="Capture declined: unavailable or changed source, unsupported PDF, storage or processing limit. Originals were preserved."}
            finally{withContext(NonCancellable){withContext(Dispatchers.IO){result?.frozen?.directory?.deleteRecursively()};release();if(token==epoch&&capture==null)busy=false}}
        }
    }
    fun showPage(index:Int) {
        val c=capture?:return;if(busy||index !in c.document.pages.indices)return
        val token=epoch;busy=true
        operation=viewModelScope.launch {
            var bitmap:Bitmap?=null
            try {
                withContext(Dispatchers.IO){val f=File(c.frozen.directory,"visual-$index.jpg");val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(f.path,bounds);var sample=1;while(bounds.outWidth/sample>900||bounds.outHeight/sample>1600)sample*=2;bitmap=BitmapFactory.decodeFile(f.path,BitmapFactory.Options().apply{inSampleSize=sample})?:error("Preview unavailable")}
                ensureActive();check(token==epoch&&capture===c);preview?.recycle();preview=bitmap;bitmap=null;page=index
            } catch(_:CancellationException){}catch(_:Exception){message="Source preview unavailable. Cancel this capture."}
            finally{bitmap?.recycle();if(token==epoch)busy=false}
        }
    }
    private fun invalidate() { prepared?.file?.delete();prepared=null;reviewed=emptySet();message=null }
    fun updatePage(value:DocxPage):Boolean { if(busy||picker)return false
        val prior=document?:return false
        if(value.blocks.size>512 || value.blocks.sumOf{it.text.length}>131072){message="Review exceeds DOCX limits.";return false}
        invalidate();document=prior.copy(pages=prior.pages.toMutableList().apply{set(page,value.owned())})
        val retained=document!!.pages.flatMap{it.blocks}.mapNotNull{it.picture?.file}.toSet()
        prior.pages.flatMap{it.blocks}.mapNotNull{it.picture?.file}.filter{it !in retained}.forEach{it.delete()}
        return true }
    fun block(value:DocxBlock) { val p=document?.pages?.getOrNull(page)?:return;updatePage(p.copy(blocks=p.blocks.map{if(it.id==value.id)value.copy(corrected=true)else it})) }
    fun move(id:Int,delta:Int) { val p=document?.pages?.getOrNull(page)?:return;val i=p.blocks.indexOfFirst{it.id==id};if(i<0||i+delta !in p.blocks.indices)return;updatePage(p.copy(blocks=p.blocks.toMutableList().apply{add(i+delta,removeAt(i).copy(corrected=true))})) }
    fun split(id:Int) {
        val p=document?.pages?.getOrNull(page)?:return;val i=p.blocks.indexOfFirst{it.id==id};if(i<0)return;val b=p.blocks[i];if(b.kind in setOf(DocxKind.IMAGE,DocxKind.TABLE))return
        val lines=b.text.lines();if(lines.size<2){message="Insert a line break where this paragraph should split.";return}
        val next=(p.blocks.maxOfOrNull{it.id}?:0)+1
        val source=b.sourceIds.mapNotNull{id->p.lines.firstOrNull{it.id==id}}
        val aligned=source.size==lines.size&&source.map{it.text}==lines
        val parts=lines.mapIndexed{j,text->
            val line=source.getOrNull(j);val bounds=line?.bounds
            val box=if(aligned&&bounds!=null&&bounds.left>=0&&bounds.top>=0&&bounds.right<=p.widthPx&&bounds.bottom<=p.heightPx&&bounds.right>bounds.left&&bounds.bottom>bounds.top)DocxBox(bounds.left/p.widthPx,bounds.top/p.heightPx,bounds.right/p.widthPx,bounds.bottom/p.heightPx)else null
            b.copy(id=if(j==0)b.id else next+j,text=text,sourceIds=if(aligned)listOf(source[j].id)else if(j==0)b.sourceIds else emptyList(),box=box,corrected=true)
        }
        updatePage(p.copy(blocks=p.blocks.take(i)+parts+p.blocks.drop(i+1),concerns=if(aligned)p.concerns else p.concerns+"LAYOUT: manually split text needs reflow."))
    }
    fun join(id:Int) {val p=document?.pages?.getOrNull(page)?:return;val i=p.blocks.indexOfFirst{it.id==id};val a=p.blocks.getOrNull(i)?:return;val b=p.blocks.getOrNull(i+1)?:return;if(a.kind in setOf(DocxKind.IMAGE,DocxKind.TABLE)||b.kind in setOf(DocxKind.IMAGE,DocxKind.TABLE))return;updatePage(p.copy(blocks=p.blocks.take(i)+a.copy(text=a.text+"\n"+b.text,sourceIds=a.sourceIds+b.sourceIds,box=null,corrected=true)+p.blocks.drop(i+2),concerns=p.concerns+"LAYOUT: joined text needs reflow."))}
    fun omit(id:Int) {val p=document?.pages?.getOrNull(page)?:return;val b=p.blocks.firstOrNull{it.id==id}?:return;updatePage(p.copy(blocks=p.blocks.filter{it.id!=id},omissions=p.omissions+b.sourceIds))}
    fun resetPage(){capture?.document?.pages?.getOrNull(page)?.let{updatePage(it)}}
    fun addParagraph(){val p=document?.pages?.getOrNull(page)?:return;updatePage(p.copy(blocks=p.blocks+DocxBlock((p.blocks.maxOfOrNull{it.id}?:0)+1,DocxKind.BODY,"",emptyList(),null,corrected=true)))}
    fun table(id:Int,value:TableGrid){val b=document?.pages?.getOrNull(page)?.blocks?.firstOrNull{it.id==id}?:return;if(value.rows.size>20||value.columns>6)return;block(b.copy(table=value))}
    fun region(box:DocxBox,asTable:Boolean) {
        val c=capture?:return;val d=document?:return;val p=d.pages[page];if(busy||picker)return
        val token=epoch;busy=true;message=null
        operation=viewModelScope.launch {
            var picture:DocxPicture?=null
            try {
                val changed=withContext(Dispatchers.IO){DocxWork.mutex.withLock{
                    DocxWork.space(getApplication(),2L*1024*1024)
                    val check:()->Unit={check(token==epoch&&active);ensureActive()}
                    if(asTable)DocxReconstructor.table(p,box,check)else {
                        require(d.pages.sumOf{it.blocks.count{b->b.kind==DocxKind.IMAGE}}<20)
                        picture=cropDocxPicture(c,page,box,check)
                        require(d.pages.flatMap{it.blocks}.sumOf{it.picture?.file?.length()?:0L}+picture!!.file.length()<=20L*1024*1024)
                        p.copy(blocks=p.blocks+DocxBlock((p.blocks.maxOfOrNull{it.id}?:0)+1,DocxKind.IMAGE,"",emptyList(),box,picture=picture,corrected=true,note="User-selected current-appearance region. Move into reading order."))
                    }
                }}
                ensureActive();check(token==epoch&&capture===c);busy=false;if(updatePage(changed))picture=null
            }catch(_:CancellationException){}catch(_:Exception){message="Region declined. Select complete source lines for a rectangular table, or a smaller figure. No content was removed."}
            finally{picture?.file?.delete();if(token==epoch)busy=false}
        }
    }
    fun settings(mode:DocxMode,paper:DocxPaper){if(mode==DocxMode.LAYOUT&&capture?.requiresAppearance==true){message="Markup/signature layout transfer remains open. Use reflow with current-appearance references.";return};if(recipeMode||busy||picker||mode==DocxMode.LAYOUT&&!DOCX_LAYOUT_AVAILABLE)return;invalidate();document=document?.copy(mode=mode,paper=paper);if(mode==DocxMode.LAYOUT)appearances=false}
    fun appearance(value:Boolean){if(!value&&capture?.requiresAppearance==true){message="Markup/signatures require the current-appearance reference.";return};if(recipeMode||busy||picker)return;invalidate();appearances=value}
    fun reviewPage(value:Boolean){if(busy||picker)return;reviewed=if(value)reviewed+page else reviewed-page}
    fun rename(value:String){if(!recipeMode&&!busy&&!picker)name=value.take(120)}
    fun nameProblem():Boolean=runCatching{WorkflowNaming.verifyExported(name.removeSuffix(".docx")+".pdf");require(name.endsWith(".docx")&&name.toByteArray().size<=120)}.isFailure
    fun generate() {
        val c=capture?:return;val d=document?.owned()?:return
        if(busy||picker||preview==null||reviewed.size!=d.pages.size||d.problem()!=null||nameProblem())return
        invalidate();reviewed=d.pages.indices.toSet();busy=true;progress=0;val token=epoch;val withReferences=appearances
        operation=viewModelScope.launch {
            var output:File?=null;val refs=mutableListOf<File>()
            try {
                val final=withContext(Dispatchers.IO){DocxWork.mutex.withLock{
                    DocxWork.space(getApplication(),(32L+if(withReferences)d.pages.size*2 else 0)*1024*1024)
                    val check:()->Unit={ensureActive();check(active&&token==epoch)}
                    var generated=d
                    require(!c.requiresAppearance||withReferences&&d.mode==DocxMode.REFLOW)
                    checkDocxLayoutFit(d)
                    if(withReferences){
                        require(d.mode==DocxMode.REFLOW);require(d.pages.sumOf{it.blocks.count{b->b.kind==DocxKind.IMAGE}}+d.pages.size<=20)
                        generated=d.copy(pages=d.pages.mapIndexed{i,p->val pic=cropDocxPicture(c,i,DocxBox(0f,0f,1f,1f),check);refs+=pic.file;p.copy(blocks=p.blocks+DocxBlock((p.blocks.maxOfOrNull{it.id}?:0)+1,DocxKind.IMAGE,"",emptyList(),DocxBox(0f,0f,1f,1f),picture=pic,corrected=true,note="Current appearance reference, not editable text or a layout facsimile."))})
                    }
                    output=File(c.frozen.directory,"export-${UUID.randomUUID()}.docx")
                    DocxWriter.write(generated,output!!,check){n->viewModelScope.launch{if(token==epoch)progress=n}}
                    val digest=workflowDigest(output!!.inputStream(),32L*1024*1024,check)
                    DocxPrepared(output!!,digest,d,revision,sourceOwner)
                }}
                ensureActive();check(token==epoch&&active);prepared=final;output=null;message="Generated the reviewed editable structure. The preview is not an office rendering. Save a separate copy or Share."
            }catch(_:CancellationException){}catch(_:OutOfMemoryError){message="Export exceeded available memory. No output accepted."}catch(_:Exception){message="DOCX declined: unsupported fit, missing resource, source change or storage limit. No output accepted."}
            finally{withContext(NonCancellable+Dispatchers.IO){output?.delete();refs.forEach{it.delete()}};if(token==epoch)busy=false}
        }
    }
    fun beginSave():String? {if(outstandingPicker){message="Finish or cancel the outstanding Android Save before starting another.";return null};if(!active||busy||picker||prepared==null||nameProblem())return null;outstandingPicker=true;picker=true;return name}
    fun destination(uri:Uri?) {
        if(!outstandingPicker)return;outstandingPicker=false
        if(!picker)return;picker=false;val p=prepared?:return
        if(uri==null){message="Save cancelled. Reviewed bytes retained; source unchanged.";return}
        val guards=capture?.frozen?.sourceGuards?:return;val token=epoch;val frozenName=name
        if(!active)return
        busy=true;message="Writing separate DOCX…"
        operation=viewModelScope.launch {
            try {
                withContext(Dispatchers.IO){writeDocx(getApplication(),p.file,uri,frozenName,p.digest,guards){ensureActive();check(active&&token==epoch)}}
                ensureActive();check(token==epoch&&active);savedDestination=uri;message="Separate DOCX saved and bytes verified. Original sources unchanged."
            }catch(_:CancellationException){}catch(_:Exception){message="Save failed or was interrupted. Inspect any external partial file before retrying; it was not deleted or overwritten."}
            finally{if(token==epoch)busy=false}
        }
    }
    fun share(launch:(Intent)->Unit) {
        val p=prepared?:return;if(!active||busy||picker||nameProblem())return
        val token=epoch;busy=true;val frozenName=name
        operation=viewModelScope.launch {
            var directory:File?=null;var handed=false
            try {
                val file=withContext(Dispatchers.IO){
                    val check:()->Unit={ensureActive();check(active&&token==epoch)}
                    val base=File(getApplication<Application>().cacheDir,"shared-pdfs").apply{mkdirs()}
                    require(base.listFiles().orEmpty().filter{it.name.startsWith("docx-")}.size<4)
                    val dir=File(base,"docx-${UUID.randomUUID()}").apply{check(mkdir())};directory=dir
                    val f=File(dir,frozenName);require(workflowDigest(p.file.inputStream(),32L*1024*1024,check)==p.digest)
                    p.file.inputStream().use{input->f.outputStream().use{out->val b=ByteArray(65536);while(true){check();val n=input.read(b);if(n<0)break;out.write(b,0,n)}}};check();f
                }
                ensureActive();check(active&&token==epoch)
                val app=getApplication<Application>();val uri=FileProvider.getUriForFile(app,app.packageName+".fileprovider",file)
                launch(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type=DOCX_MIME;putExtra(Intent.EXTRA_STREAM,uri);clipData=ClipData.newRawUri("DOCX",uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},"Share reviewed DOCX"));handed=true
                message="Shared reviewed DOCX. The receiving app controls its copy."
            }catch(_:CancellationException){}catch(_:Exception){message="Share unavailable. No source was changed."}
            finally{if(!handed)directory?.deleteRecursively();if(token==epoch)busy=false}
        }
    }
    fun foreground(unlocked:Boolean){active=unlocked}
    fun background(recreating:Boolean=false){if(picker&&!recreating){active=false;return};close()}
    fun close(){active=false;epoch++;operation?.cancel();val old=capture;capture=null;document=null;prepared=null;preview?.recycle();preview=null;reviewed=emptySet();visible=false;busy=false;picker=false;recipeMode=false;savedDestination=null;appearances=true;name="Document.docx";val job=operation;viewModelScope.launch(NonCancellable+Dispatchers.IO){job?.join();old?.frozen?.directory?.deleteRecursively();root.delete();if(clearing)activeOwners-=activityOwner}}
    override fun onCleared(){clearing=true;close();super.onCleared()}
}
