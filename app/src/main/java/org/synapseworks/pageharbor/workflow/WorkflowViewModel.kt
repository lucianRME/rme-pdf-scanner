package org.synapseworks.pageharbor.workflow

import android.app.Application
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.synapseworks.pageharbor.document.session.DocumentSession
import org.synapseworks.pageharbor.ocr.OcrResult
import org.synapseworks.pageharbor.library.smartnaming.LocalSmartNamingEngine
import org.synapseworks.pageharbor.library.smartnaming.SmartNamingInput

internal data class WorkflowReview(val recipe: WorkflowRecipe,val captured: WorkflowCaptureResult,val name:String,val at:String,val zone:String,val revision:Long,val owner:String,val key:String,val pages:Int)
internal data class DocxRecipeTicket(val recipe:WorkflowRecipe,val name:String,val at:String,val zone:String,val revision:Long,val owner:String,val key:String) {
    override fun toString()="DocxRecipeTicket(<redacted>)"
}
internal data class OfficeRecipeTicket(val request:String,val recipe:WorkflowRecipe,val name:String,val at:String,val zone:String) {
    override fun toString()="OfficeRecipeTicket(<redacted>)"
}
internal class WorkflowViewModel(application: Application): AndroidViewModel(application) {
    var docxTicket:DocxRecipeTicket?=null;private set
    val activityOwner=UUID.randomUUID().toString()
    internal val queue=WorkflowQueue(application,WorkflowStore(File(application.noBackupFilesDir,"workflow-v1")))
    var data by mutableStateOf(queue.data());private set
    var visible by mutableStateOf(false);private set
    var draft by mutableStateOf<WorkflowRecipe?>(null);private set
    var review by mutableStateOf<WorkflowReview?>(null);private set
    var preview by mutableStateOf<Bitmap?>(null);private set
    var page by mutableStateOf(0);private set
    var archivalAccepted by mutableStateOf(false);private set
    fun acceptArchival(value:Boolean) { archivalAccepted=value }
    var busy by mutableStateOf(false);private set
    var message by mutableStateOf<String?>(null);private set
    var suggestion by mutableStateOf<String?>(null);private set
    var suggestionRevision by mutableStateOf<Long?>(null);private set
    var treePending=false;private set
    private var manualOutstanding=false
    var manualToken:String?=null;private set
    private var operation:Job?=null
    private var epoch=0L
    private var officeRequest:String?=null
    private var officeSourceGrant:String?=null
    private var officePreview:org.synapseworks.pageharbor.ui.home.OwnedPreviewBitmap?=null
    init { queue.changed={viewModelScope.launch { data=queue.data() }} }
    fun noDocument() { message="Open a document before capturing a recipe." }
    fun open() { visible=true;message=if(queue.store.healthy)null else if(queue.store.busyOwner)"This queue is open in another RME screen. Return to that screen; no work can start here." else "Queue metadata is unavailable. No writes are allowed." }
    fun close() { org.synapseworks.pageharbor.office.OfficeRecipeOffers.endRequest(officeRequest);officeRequest=null;queue.invalidateManual();discardReview();draft=null;treePending=false;manualToken=null;visible=false;safely { queue.releaseUnusedGrants() } }
    fun create() { discardReview();draft=WorkflowRecipe(UUID.randomUUID().toString(),label="Export preset",preset=WorkflowPreset.NORMAL);suggestion=null }
    fun edit(recipe:WorkflowRecipe) { discardReview();draft=recipe;suggestion=null;treePending=false }
    fun change(recipe:WorkflowRecipe) { if(busy)return;discardReview();draft=recipe;treePending=false }
    fun saveRecipe() = safely { val d=draft?:return@safely;queue.saveRecipe(d);draft=null }
    fun dismissDraft() {
        if (busy) return
        draft = null
        suggestion = null
        suggestionRevision = null
        treePending = false
        safely { queue.releaseUnusedGrants() }
    }
    fun select(recipe:WorkflowRecipe) = edit(recipe)
    fun titleSuggestion(ocr:OcrResult?,revision:Long?=null) {
        suggestionRevision=revision
        suggestion=LocalSmartNamingEngine.suggest(SmartNamingInput("Document",ocr?.pages.orEmpty().map { it.text }))?.name
        if(suggestion==null)message="No conservative title suggestion. Enter and review a title."
    }
    fun confirmSuggestion() { val s=suggestion?:return;draft?.let { change(it.copy(custom=s)) };suggestion=null }
    fun beginTree():Boolean { if(busy || draft==null || treePending)return false;treePending=true;return true }
    fun treeReturned(uri:Uri?) {
        if(!treePending)return;treePending=false
        if(uri==null)return
        if(runCatching { queue.chooseTree(uri) }.getOrDefault(false)) { draft=draft?.copy(tree=uri.toString(),treeIdentity=queue.destination.identity(uri.toString()));message="Local primary-storage folder verified. Review before saving the recipe." }
        else { draft=draft?.copy(tree=null);message="This folder is not proven local. Use an explicit manual Save for every job." }
    }
    fun capture(document:DocumentSession,revision:Long,owner:String,ocr:OcrResult?,password:CharArray,current:()->Boolean,release:()->Unit) {
        val recipe=draft;val owned=password.copyOf();password.fill('\u0000')
        if(recipe==null || recipe.preset in setOf(WorkflowPreset.DOCX,WorkflowPreset.XLSX,WorkflowPreset.PPTX,WorkflowPreset.OFFICE) || busy || data.recipes.none { it==recipe }) { owned.fill('\u0000');release();message="Save the recipe, then select it before capture.";return }
        val key="$owner:$revision:${recipe.id}:${recipe.version}:${recipe.custom}"
        if(queue.data().jobs.any { it.duplicateKey==key }) {
            owned.fill('\u0000');release();message="This revision and recipe already have a job. Use its frozen name/status, or edit and save the recipe for a new copy.";return
        }
        discardReview();val token=epoch;val at=Instant.now();val zone=ZoneId.systemDefault()
        val name=runCatching { WorkflowNaming.name(recipe.template,recipe.custom,at,zone,recipe.nextCounter,recipe.preset.extension) }.getOrElse { owned.fill('\u0000');release();message="Invalid name, template or exhausted counter.";return }
        busy=true;message=null
        operation=viewModelScope.launch {
            var captured:WorkflowCaptureResult?=null
            try {
                captured=withContext(Dispatchers.IO) { captureWorkflow(getApplication(),queue.store.root,document,recipe.preset,ocr,owned) { queue.active } }
                ensureActive();check(token==epoch && visible && current() && queue.active && queue.data().recipes.any { it==recipe })
                review=WorkflowReview(recipe,captured,name,at.toString(),zone.id,revision,owner,key,document.pages.size);captured=null;busy=false
                renderPage(0)
            } catch(_:CancellationException) { } catch(_:OutOfMemoryError) { message="Capture exceeded available resources. Nothing queued." } catch(_:Exception) { message="Capture declined: source changed, unavailable OCR, storage, password or processing limit. Nothing queued." }
            finally { owned.fill('\u0000');captured?.directory?.deleteRecursively();release();if(token==epoch && review==null)busy=false }
        }
    }
    fun beginDocx(revision:Long,owner:String):DocxRecipeTicket? {
        val r=draft?:return null
        if(busy||!queue.active||r.preset!=WorkflowPreset.DOCX||queue.data().recipes.none{it==r})return null
        val key="$owner:$revision:${r.id}:${r.version}:${r.custom}"
        if(queue.data().jobs.any{it.duplicateKey==key}){message="This revision and recipe already have a frozen job.";return null}
        val at=Instant.now();val zone=ZoneId.systemDefault()
        val name=runCatching{WorkflowNaming.name(r.template,r.custom,at,zone,r.nextCounter,r.preset.extension)}.getOrElse{message="Invalid DOCX name or exhausted counter.";return null}
        discardReview();return DocxRecipeTicket(r,name,at.toString(),zone.id,revision,owner,key).also{docxTicket=it}
    }
    fun beginOffice():OfficeRecipeTicket? {
        val recipe=draft?:return null
        if(busy || !visible || !queue.active || recipe.preset!=WorkflowPreset.OFFICE || !recipe.preset.available || queue.data().recipes.none{it==recipe})return null
        val at=Instant.now();val zone=ZoneId.systemDefault()
        val name=runCatching{WorkflowNaming.name(recipe.template,recipe.custom,at,zone,recipe.nextCounter)}.getOrElse{message="Invalid recipe name or exhausted counter.";return null}
        discardReview()
        org.synapseworks.pageharbor.office.OfficeRecipeOffers.endRequest(officeRequest)
        val ticket=OfficeRecipeTicket(UUID.randomUUID().toString(),recipe,name,at.toString(),zone.id)
        officeRequest=ticket.request
        org.synapseworks.pageharbor.office.OfficeRecipeOffers.register(ticket.request) { guard->
            check(visible && queue.store.healthy && queue.data().recipes.any{it==recipe})
            queue.reserveOfficeSource(guard,reviewOnly=true)
            val release:()->Unit={queue.releaseOfficeSource(guard.uri)}
            release
        }
        return ticket
    }
    fun acceptOffice(ticket:OfficeRecipeTicket,id:String,current:()->Boolean) {
        val offer=org.synapseworks.pageharbor.office.OfficeRecipeOffers.claim(id,ticket.request)?:return
        if(busy || !visible || !queue.active || !current() || queue.data().recipes.none{it==ticket.recipe}) { runCatching{offer.close()};message="Office recipe request expired. Review again.";return }
        val key="office:${offer.source.device}:${offer.source.inode}:${offer.sourceDigest}:${ticket.recipe.id}:${ticket.recipe.version}"
        if(queue.data().jobs.any{it.duplicateKey==key}) { runCatching{offer.close()};message="This exact Office source and recipe already have a frozen job; no new counter consumed.";return }
        discardReview();val token=epoch;busy=true;message=null
        operation=viewModelScope.launch {
            var directory:File?=null;var reserved=false
            try {
                val frozen=withContext(Dispatchers.IO) {
                    val check:()->Unit={ensureActive();check(epoch==token && queue.active && current())}
                    check();require(queue.ownedBytes()+offer.file.length()<=WORKFLOW_MAX_OWNED)
                    queue.reserveOfficeSource(offer.source);reserved=true
                    requireNotNull(getApplication<Application>().contentResolver.openFileDescriptor(Uri.parse(offer.source.uri),"r")).use { fd->
                        require(org.synapseworks.pageharbor.office.officeIdentity(fd)==org.synapseworks.pageharbor.office.OfficeIdentity(offer.source.device,offer.source.inode))
                        require(org.synapseworks.pageharbor.office.officeDigest(fd,org.synapseworks.pageharbor.office.OFFICE_INPUT,check)==offer.sourceDigest)
                    }
                    org.synapseworks.pageharbor.office.officeProcessingMutex.withLock {
                        org.synapseworks.pageharbor.office.officeCoordinatorIdle(getApplication())
                        org.synapseworks.pageharbor.office.recoverOfficePreviewLeases(getApplication())
                        queue.store.root.listFiles().orEmpty().filter{it.isDirectory && it.name.matches(Regex("office-capture-[a-f0-9-]{36}"))}.forEach{require(!java.nio.file.Files.isSymbolicLink(it.toPath()));check(it.deleteRecursively())}
                    }
                    val dir=File(queue.store.root,"office-capture-${UUID.randomUUID()}").apply{check(mkdir())};directory=dir
                    val pdf=File(dir,"reviewed.pdf")
                    offer.file.inputStream().use { input->java.io.FileOutputStream(pdf).use { out->
                        val b=ByteArray(65536);var count=0L
                        while(true){check();val n=input.read(b);if(n<0)break;count+=n;require(count<=WORKFLOW_MAX_PDF);out.write(b,0,n)};out.fd.sync()
                    } }
                    require(workflowDigest(pdf.inputStream(),check=check)==offer.digest to pdf.length())
                    require(pdf.setReadOnly());check()
                    WorkflowCaptureResult(dir,pdf,pdf,offer.digest,pdf.length(),offer.digest,listOf(offer.source))
                }
                ensureActive();check(token==epoch && visible && queue.active && current() && queue.data().recipes.any{it==ticket.recipe})
                review=WorkflowReview(ticket.recipe,frozen,ticket.name,ticket.at,ticket.zone,0,offer.owner,key,offer.pages)
                officeSourceGrant=offer.source.uri;reserved=false;directory=null;busy=false;renderPage(0)
            } catch(_:CancellationException) {} catch(_:Exception) { if(token==epoch)message="Office recipe capture declined: source access, identity, settings or storage changed. Nothing queued." }
            finally {
                withContext(NonCancellable+Dispatchers.IO) { directory?.deleteRecursively();if(reserved)runCatching{queue.releaseOfficeSource(offer.source.uri)};runCatching{offer.close()} }
                if(token==epoch && review==null)busy=false
            }
        }
    }
    fun acceptDocx(prepared:org.synapseworks.pageharbor.docx.DocxPrepared,capture:org.synapseworks.pageharbor.docx.DocxCapture,current:()->Boolean,done:()->Unit) {
        val ticket=docxTicket?:return
        if(busy||!queue.active||!current()||prepared.revision!=ticket.revision||prepared.owner!=ticket.owner||prepared.document.mode!=org.synapseworks.pageharbor.docx.DocxMode.REFLOW||prepared.document.paper!=org.synapseworks.pageharbor.docx.DocxPaper.LETTER)return
        val token=epoch;busy=true
        operation=viewModelScope.launch {
            var directory:File?=null
            try {
                val frozen=withContext(Dispatchers.IO){
                    val check:()->Unit={ensureActive();check(token==epoch&&queue.active&&current())}
                    check();require(queue.ownedBytes()+prepared.digest.second+capture.frozen.preview.length()<=WORKFLOW_MAX_OWNED)
                    require(workflowDigest(prepared.file.inputStream(),check=check)==prepared.digest)
                    val dir=File(queue.store.root,"capture-${UUID.randomUUID()}").apply{check(mkdir())};directory=dir
                    fun copy(from:File,to:File){from.inputStream().use{input->to.outputStream().use{out->val b=ByteArray(65536);while(true){check();val n=input.read(b);if(n<0)break;out.write(b,0,n)}}}}
                    val out=File(dir,"reviewed.docx");copy(prepared.file,out);val source=File(dir,"appearance.pdf");copy(capture.frozen.preview,source)
                    require(workflowDigest(out.inputStream(),check=check)==prepared.digest)
                    require(workflowDigest(source.inputStream(),check=check).first==capture.frozen.previewDigest)
                    WorkflowCaptureResult(dir,out,source,prepared.digest.first,prepared.digest.second,capture.frozen.previewDigest,capture.frozen.sourceGuards)
                }
                ensureActive();check(token==epoch&&queue.active&&current()&&docxTicket==ticket&&queue.data().recipes.any{it==ticket.recipe})
                review=WorkflowReview(ticket.recipe,frozen,ticket.name,ticket.at,ticket.zone,ticket.revision,ticket.owner,ticket.key,prepared.document.pages.size)
                directory=null;docxTicket=null;busy=false;done();renderPage(0)
            }catch(_:CancellationException){}catch(_:Exception){message="DOCX recipe capture declined. The recipe, reviewed source, name or storage changed. Nothing queued."}
            finally{withContext(NonCancellable+Dispatchers.IO){directory?.deleteRecursively()};if(token==epoch)busy=false}
        }
    }
    fun acceptXlsx(prepared:org.synapseworks.pageharbor.xlsx.XlsxPrepared,current:()->Boolean,done:()->Unit) {
        val recipe=draft?:return
        if(busy||!queue.active||!current()||recipe.preset!=WorkflowPreset.XLSX||data.recipes.none{it==recipe})return
        val key="${prepared.owner}:${prepared.revision}:${recipe.id}:${recipe.version}:${recipe.custom}"
        if(data.jobs.any{it.duplicateKey==key}){message="This reviewed workbook and recipe already have a frozen job.";return}
        val at=Instant.now();val zone=ZoneId.systemDefault()
        val name=runCatching{WorkflowNaming.name(recipe.template,recipe.custom,at,zone,recipe.nextCounter,recipe.preset.extension)}.getOrElse{message="Invalid workbook name or exhausted counter.";return}
        discardReview();val token=epoch;busy=true
        operation=viewModelScope.launch {
            var frozen:WorkflowCaptureResult?=null
            try {
                withContext(Dispatchers.IO){
                    val check:()->Unit={ensureActive();check(epoch==token&&queue.active&&current())}
                    require(queue.ownedBytes()+prepared.digest.second+65536<=WORKFLOW_MAX_OWNED)
                    frozen=org.synapseworks.pageharbor.xlsx.captureXlsxRecipe(queue.store.root,prepared,check)
                }
                ensureActive();check(epoch==token&&queue.active&&current()&&queue.data().recipes.any{it==recipe})
                review=WorkflowReview(recipe,checkNotNull(frozen),name,at.toString(),zone.id,prepared.revision,prepared.owner,key,1)
                frozen=null;busy=false;visible=true;done();renderPage(0)
            }catch(_:CancellationException){}catch(_:Exception){message="Workbook recipe declined: reviewed bytes, recipe, source or storage changed. Nothing queued."}
            finally{withContext(NonCancellable+Dispatchers.IO){frozen?.directory?.deleteRecursively()};if(epoch==token)busy=false}
        }
    }
    fun acceptPptx(prepared:org.synapseworks.pageharbor.pptx.PptxPrepared,current:()->Boolean,done:()->Unit) {
        val recipe=draft?:return
        if(busy||!queue.active||!current()||recipe.preset!=WorkflowPreset.PPTX||data.recipes.none{it==recipe})return
        val key="${prepared.owner}:${prepared.revision}:${recipe.id}:${recipe.version}:${recipe.custom}"
        if(data.jobs.any{it.duplicateKey==key}){message="This reviewed presentation and recipe already have a frozen job.";return}
        val at=Instant.now();val zone=ZoneId.systemDefault()
        val name=runCatching{WorkflowNaming.name(recipe.template,recipe.custom,at,zone,recipe.nextCounter,recipe.preset.extension)}.getOrElse{message="Invalid presentation name or exhausted counter.";return}
        discardReview();val token=epoch;busy=true
        operation=viewModelScope.launch {
            var frozen:WorkflowCaptureResult?=null
            try {
                withContext(Dispatchers.IO){
                    val check:()->Unit={ensureActive();check(epoch==token&&queue.active&&current())}
                    require(queue.ownedBytes()+prepared.digest.second+16L*1024*1024<=WORKFLOW_MAX_OWNED)
                    frozen=org.synapseworks.pageharbor.pptx.capturePptxRecipe(queue.store.root,prepared,check)
                }
                ensureActive();check(epoch==token&&queue.active&&current()&&queue.data().recipes.any{it==recipe})
                review=WorkflowReview(recipe,checkNotNull(frozen),name,at.toString(),zone.id,prepared.revision,prepared.owner,key,prepared.deck.pages.size)
                frozen=null;busy=false;visible=true;done();renderPage(0)
            }catch(_:CancellationException){}catch(_:Exception){message="Presentation recipe declined: reviewed bytes, recipe, source or storage changed. Nothing queued."}
            finally{withContext(NonCancellable+Dispatchers.IO){frozen?.directory?.deleteRecursively()};if(epoch==token)busy=false}
        }
    }
    fun renderPage(index:Int) {
        val captured=review?:return;if(busy || index !in 0 until captured.pages)return
        val token=epoch;busy=true
        if(captured.recipe.preset==WorkflowPreset.OFFICE) { preview=null;officePreview?.release();officePreview=null }
        operation=viewModelScope.launch {
            var bitmap:Bitmap?=null
            try {
                if(captured.recipe.preset==WorkflowPreset.OFFICE) {
                    bitmap=org.synapseworks.pageharbor.office.officeProcessingMutex.withLock {
                        org.synapseworks.pageharbor.office.OfficePreviewOwnership.awaitRetirement()
                        withContext(Dispatchers.IO){check(workflowDigest(captured.captured.preview.inputStream()).first==captured.captured.previewDigest)}
                        org.synapseworks.pageharbor.office.previewOffice(getApplication(),captured.captured.preview,index,captured.pages)
                    }
                } else bitmap=withContext(Dispatchers.IO) {
                    check(workflowDigest(captured.captured.preview.inputStream()).first==captured.captured.previewDigest)
                    PdfRenderer(ParcelFileDescriptor.open(captured.captured.preview,ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer -> renderer.openPage(index).use { p ->
                        val scale=minOf(768.0/p.width,2048.0/p.height);val w=(p.width*scale).toInt().coerceAtLeast(1);val h=(p.height*scale).toInt().coerceAtLeast(1)
                        Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888).also { it.eraseColor(android.graphics.Color.WHITE);p.render(it,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                    } }
                }
                if(token==epoch && review===captured) {
                    if(captured.recipe.preset==WorkflowPreset.OFFICE)officePreview=org.synapseworks.pageharbor.office.OfficePreviewOwnership.own(checkNotNull(bitmap)) else preview?.recycle()
                    preview=bitmap;bitmap=null;page=index
                }
            } catch(_:Exception) { message="Preview unavailable. Cancel this capture." }
            finally { bitmap?.recycle();if(token==epoch)busy=false }
        }
    }
    fun enqueue()=safely {
        val r=review?:return@safely;check(preview!=null && !busy)
        check(r.recipe.preset!=WorkflowPreset.ARCHIVAL || archivalAccepted)
        check(workflowDigest(r.captured.preview.inputStream()).first==r.captured.previewDigest)
        val job=queue.enqueue(r.recipe,r.captured.output,r.captured.digest,r.captured.bytes,r.name,r.revision,r.owner,r.key,r.at,r.zone,r.captured.sourceGuards)
        discardReview();draft=null;message="Captured job ${job.status}. Enable its recipe and run, or choose manual Save."
    }
    fun discardReview() {
        docxTicket=null;archivalAccepted=false;epoch++
        val previous=operation;previous?.cancel();operation=null
        val captured=review;review=null
        if(captured?.recipe?.preset==WorkflowPreset.OFFICE) {
            CoroutineScope(NonCancellable+Dispatchers.IO).launch {
                previous?.join()
                // Unknown worker ownership quarantines the copy until the next explicit review.
                runCatching {
                    org.synapseworks.pageharbor.office.officeProcessingMutex.withLock {
                        org.synapseworks.pageharbor.office.officeCoordinatorIdle(getApplication())
                        org.synapseworks.pageharbor.office.recoverOfficePreviewLeases(getApplication())
                        captured.captured.directory.deleteRecursively()
                    }
                }
            }
        } else captured?.captured?.directory?.deleteRecursively()
        if(officePreview!=null)officePreview?.release() else preview?.recycle()
        officePreview=null;preview=null
        officeSourceGrant?.let{runCatching{queue.releaseOfficeSource(it)}};officeSourceGrant=null;busy=false
    }
    fun enable(id:String,value:Boolean)=safely { queue.enable(id,value) }
    fun revoke(id:String)=safely { queue.revoke(id) }
    fun delete(id:String)=safely { queue.deleteRecipe(id) }
    fun cancel(id:String)=safely { queue.cancel(id) }
    fun keep(id:String)=safely { queue.keepExternalAndCancel(id) }
    fun remove(id:String)=safely { queue.remove(id) }
    fun run(id:String) { viewModelScope.launch(Dispatchers.IO) { runCatching { queue.run(id) }.onFailure { withContext(Dispatchers.Main) { message="Queue storage failed. No saved result is claimed; reconcile any external file." } } } }
    fun reconcile(id:String) { viewModelScope.launch(Dispatchers.IO) { runCatching { queue.reconcile(id) }.onFailure { withContext(Dispatchers.Main) { message="Reconciliation unavailable. Keep the external file and retry only after review." } } } }
    fun manual(id:String):String? { if(manualOutstanding){message="Finish or cancel the outstanding Android Save before starting another.";return null};manualToken=queue.manualRequest(id);manualOutstanding=manualToken!=null;if(!manualOutstanding)message="This export is paused or already running.";return if(manualOutstanding)data.jobs.firstOrNull { it.id==id }?.name else null }
    fun manualResult(uri:Uri?) { if(!manualOutstanding)return;manualOutstanding=false;val token=manualToken?:return;manualToken=null;val id=queue.consumeManual(token,uri)?:return;viewModelScope.launch(Dispatchers.IO) { runCatching { queue.run(id,uri) } } }
    fun foreground(unlocked:Boolean) { queue.active=unlocked }
    fun background(lock:Boolean=false) { discardReview();safely { queue.suspendWrites() };if(lock)close() }
    private fun safely(block:()->Unit) { try { block();data=queue.data() } catch(_:Exception) { message="Operation declined. Check the name, queue capacity, saved recipe, grants and storage. Existing files were preserved." } }
    override fun onCleared() { org.synapseworks.pageharbor.office.OfficeRecipeOffers.endRequest(officeRequest);queue.active=false;discardReview();if(!queue.closeOwnerIfIdle())CoroutineScope(NonCancellable+Dispatchers.IO).launch { runCatching { queue.closeOwner() } };super.onCleared() }
}
