package org.synapseworks.pageharbor.translation

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
import java.util.concurrent.atomic.AtomicBoolean
import org.synapseworks.pageharbor.resourcepack.*
import org.synapseworks.pageharbor.document.session.*
import org.synapseworks.pageharbor.ocr.OcrResult
import org.synapseworks.pageharbor.workflow.*

class TranslationViewModel(app:Application):AndroidViewModel(app){
 val owner=UUID.randomUUID().toString()
 var visible by mutableStateOf(false);private set
 var busy by mutableStateOf(false);private set
 var message by mutableStateOf("");private set
 var pages by mutableStateOf<List<TranslationPage>>(emptyList());private set
 var automatic by mutableStateOf("");private set
 var draft by mutableStateOf("");private set
 var reviewed by mutableStateOf(false)
 var pageScope by mutableStateOf("1")
 var progress by mutableStateOf(0);private set
 var trace by mutableStateOf<List<TranslationPiece>>(emptyList());private set
 var segmentOutputs by mutableStateOf<List<String>>(emptyList());private set
 val reviewedDraftRevision get()=draftRevision
 var picker by mutableStateOf(false);private set
 internal var direction by mutableStateOf(TranslationDirection.EnglishToRomanian)
  private set
 internal fun chooseDirection(value: TranslationDirection) {
  if (!busy && !picker && automatic.isEmpty() && value in TranslationDirection.supported) direction = value
 }
 @Volatile private var epoch=0L
 private var draftRevision=0L
 @Volatile private var active=true
 private var flag=AtomicBoolean(false)
 private var current:()->Boolean={false}
 private var guards=emptyList<WorkflowSourceGuard>()
 private class FrozenExport(val bytes:ByteArray,val pdf:Boolean,val revision:Long,val ticket:String=UUID.randomUUID().toString()){val name get()=if(pdf)"Translation.pdf" else "Translation.txt";val mime get()=if(pdf)"application/pdf" else "text/plain"}
 private var frozen:FrozenExport?=null
 val saveTicket get()=frozen?.ticket
 private var pdfBytes:ByteArray?=null
 private var pdfRevision=-1L
 var pdfPages by mutableStateOf(0);private set
 var pdfReferences by mutableStateOf("");private set
 val pdfReady get()=pdfBytes!=null&&pdfRevision==draftRevision
 private var pdfJob:Job?=null
 var correctionPreview by mutableStateOf<List<RomanianDraftChange>?>(null);private set
 var correctionSelected by mutableStateOf<Set<Int>>(emptySet());private set
 private var correctionSource=""
 private var correctionRevision=-1L
 private var correctionUndo:String?=null
 private var correctionUndoRevision=-1L
 val canUndoCorrection get()=correctionUndo!=null&&correctionUndoRevision==draftRevision&&!busy&&!picker
 val correctionResult get()=RomanianDraftCorrection.apply(correctionSource,correctionSelected)
 private fun invalidatePdf(){pdfBytes=null;pdfPages=0;pdfRevision=-1;pdfReferences=""}
 fun beginCorrection(){if(busy||picker||draft.isBlank())return;correctionSource=draft;correctionRevision=draftRevision;correctionSelected=emptySet();correctionPreview=try{RomanianDraftCorrection.propose(draft)}catch(_:Exception){message="Correction preview supports at most256 occurrences; edit a smaller draft.";return};message=if(correctionPreview!!.isEmpty())"No legacy Romanian letters proposed." else "Select only reviewed occurrences. Names and quotations may remain unchanged."}
 fun selectCorrection(offset:Int,selected:Boolean){if(correctionPreview?.any{it.offset==offset}==true)correctionSelected=if(selected)correctionSelected+offset else correctionSelected-offset}
 fun selectAllCorrections(){correctionSelected=correctionPreview.orEmpty().map{it.offset}.toSet()}
 fun cancelCorrection(){correctionPreview=null;correctionSelected=emptySet();correctionSource=""}
 fun applyCorrection(){if(busy||picker||correctionRevision!=draftRevision||draft!=correctionSource||correctionSelected.isEmpty())return;val before=draft;val after=correctionResult;edit(after);correctionUndo=before;correctionUndoRevision=draftRevision;cancelCorrection();message="Reviewed occurrences corrected in the draft only. Undo is available until the next edit."}
 fun undoCorrection(){if(!canUndoCorrection)return;val before=correctionUndo!!;edit(before);message="Draft correction undone; raw output and source unchanged."}
 fun preparePdf(){if(!active||busy||picker||!reviewed||draft.isBlank()||!current()||correctionPreview!=null)return
  val refs=trace.map{it.pageId}.distinct().map{id->(pages.indexOfFirst{it.id==id}+1) to id};if(refs.isEmpty()){message="PDF needs captured source-page references from a completed translation.";return}
  val snapshot=TranslationPdfSnapshot(draft,draftRevision,sourceRevision,refs);val token=epoch;busy=true;invalidatePdf();message="Preparing searchable reflowed PDF from the reviewed draft…"
  pdfJob=viewModelScope.launch{try{val result=TranslationPdf.create(getApplication(),snapshot){check(valid(token)&&draftRevision==snapshot.draftRevision)};if(valid(token)&&draftRevision==snapshot.draftRevision){pdfBytes=result.first;pdfPages=result.second;pdfRevision=snapshot.draftRevision;pdfReferences="Captured input references: "+snapshot.references.joinToString("; "){"page ${it.first} [${it.second}]"}+"; source revision ${snapshot.sourceRevision}. No paragraph attribution.";message="Searchable reflowed PDF ready: ${result.second} pages, draft revision ${snapshot.draftRevision}. Global input references only; no paragraph attribution."}}catch(_:CancellationException){}catch(_:Exception){if(token==epoch)message="PDF declined: unsupported character/glyph, word fit, page/storage limit or changed draft. TXT remains available; no substitution performed."}finally{if(token==epoch)busy=false}}
 }
 val capturedRevision get()=sourceRevision
 private var sourceRevision=0L
 private var captureJob:Job?=null
 internal fun runtimeAvailability(): ModelRuntimeAvailability = modelRuntimeAvailability(
  Build.VERSION.SDK_INT, android.os.Process.is64Bit(), Build.SUPPORTED_64_BIT_ABIS.firstOrNull(),
 ) {
  runCatching { Class.forName("org.synapseworks.pageharbor.resourcepack.KnownModelRuntime", false, getApplication<Application>().classLoader) }.isSuccess
 }
 fun open(session:DocumentSession,revision:Long,isCurrent:()->Boolean,release:()->Unit,ocr:suspend()->OcrResult?){
  if(visible){release();return};visible=true;active=true;current=isCurrent;sourceRevision=revision;val token=++epoch
  if (runtimeAvailability() != ModelRuntimeAvailability.AVAILABLE) {
   release()
   return
  }
  busy=true
  captureJob=viewModelScope.launch{try{
   require(session.pages.size in 1..3);val result=ocr();check(current()&&token==epoch&&active)
   val values=session.pages.mapIndexed{i,p->TranslationPage(p.persistentId?:p.id.value.toString(),result?.pages?.singleOrNull{it.pageIndex==i&&it.error==null}?.text.orEmpty())}
   require(values.sumOf{it.text.length}<=8192&&values.sumOf{it.text.toByteArray(Charsets.UTF_8).size}<=8192);val capturedGuards=withContext(Dispatchers.IO){val sources=(session.originalPdfSources+session.pages.map{it.source}).distinctBy{it.reference};require(sources.size in 1..40);sources.map{workflowSourceGuard(getApplication(),it.toAndroidUri())}}
   check(current()&&token==epoch&&active);guards=capturedGuards;pages=values;message=if(values.any{it.text.isBlank()})"Recognize text on the source pages first." else "Choose up to 3 pages of English text."
  }catch(_:CancellationException){}catch(_:Exception){if(token==epoch&&active)message="OCR capture unavailable or source changed. Run OCR on the source and retry."}finally{release();if(token==epoch)busy=false}}
 }
 fun changed(){if(visible&&!current())close()}
 fun edit(s:String){if(busy||picker)return;try{TranslationUtf8.encode(s);draft=s;draftRevision++;reviewed=false;invalidatePdf();correctionUndo=null;cancelCorrection()}catch(_:Exception){message="Draft must be well-formed Unicode and at most32768 UTF-8 bytes."}}
 fun translate(){
  if(busy||picker||!active||!current())return
  val app=getApplication<Application>()
  val runtime = runtimeAvailability()
  if(runtime!=ModelRuntimeAvailability.AVAILABLE){message=runtime.guidance;return}
  val selectedDirection = direction
  val selected=try{val indices=pageScope.split(',').map{it.trim().toInt()-1};require(indices.size in 1..3&&indices.distinct().size==indices.size);indices.map{pages[it]}.also{require(it.all{p->p.text.isNotBlank()})}}catch(_:Exception){message="Choose1–3 distinct page numbers with current OCR, separated by commas.";return}
  val initial=try{TranslationPlan.plan(selected)}catch(_:Exception){message="Source exceeds8192 UTF-8 bytes/32 segments or contains unsupported Unicode. Select fewer pages.";return}
  if(!LocalModelUse.running.compareAndSet(false,true)){message="Another model consumer is finishing. Close it, wait, then retry.";return}
  val token=++epoch;val stop=AtomicBoolean(false);flag=stop;busy=true;progress=0;automatic="";draft="";reviewed=false;invalidatePdf();correctionUndo=null;cancelCorrection();message="Validating model and translating locally…";val handler=Handler(Looper.getMainLooper())
  Thread({var result:String?=null;var acceptedTrace:List<TranslationPiece> = emptyList();var acceptedOutputs:List<String> = emptyList();var failure = when {
   selectedDirection.usesIrishPack -> "Install Irish using Install or manage languages."
   selectedDirection.usesMultilingualPack -> "Install ${selectedDirection.target.takeUnless { it == TranslationLanguage.ENGLISH }?.label ?: selectedDirection.source.label} using Install or manage languages."
   else -> "Install Romanian using Install or manage languages."
  }
   try{
    val deadline=SystemClock.elapsedRealtime()+120000
    fun checkJob(){if(stop.get()||token!=epoch||!active||!current())throw java.util.concurrent.CancellationException();if(SystemClock.elapsedRealtime()>=deadline)throw IllegalStateException("JOB_TIME_LIMIT")}
    val store = when {
     selectedDirection.usesIrishPack -> IrishTranslationPack.store(app.noBackupFilesDir)
     selectedDirection.usesMultilingualPack -> MultilingualPack.store(app.noBackupFilesDir)
     else -> ModelPackStore(File(app.noBackupFilesDir, "binary-model-packs-v1"))
    }
    store.usePack(::checkJob) { root ->
     checkJob()
     val className = if (selectedDirection.usesMultilingualPack) "MultilingualModelRuntime" else "KnownModelRuntime"
     val type = Class.forName("org.synapseworks.pageharbor.resourcepack.$className")
     val client = when {
      selectedDirection.usesIrishPack -> {
       val pair = if (selectedDirection.source == TranslationLanguage.ENGLISH) "en-ga" else "ga-en"
       type.getConstructor(File::class.java, String::class.java).newInstance(root, pair)
      }
      selectedDirection.usesMultilingualPack ->
       type.getConstructor(File::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        .newInstance(root, selectedDirection.source.modelId, selectedDirection.target.modelId)
      else -> type.getConstructor(File::class.java).newInstance(root)
     } as AutoCloseable
     try{val queue=initial.toMutableList();val outputs=mutableListOf<String>();var i=0;var calls=0
      while(i<queue.size){checkJob();val piece=queue[i]
       if(piece.separator){outputs+=piece.source;i++;continue}
       failure="Unsupported or exhausted segment on page ${piece.pageId} (selection${piece.page+1}), UTF-16 range${piece.start}–${piece.end}. No complete draft accepted."
       if(!(type.getMethod("fits",String::class.java).invoke(client,piece.source) as Boolean)){
        val split=TranslationPlan.split(piece);queue.removeAt(i);queue.addAll(i,split);require(queue.size<=128&&queue.count{!it.separator}<=32);continue
       }
       require(++calls<=32);val translated=type.getMethod("translate",String::class.java,AtomicBoolean::class.java).invoke(client,piece.source,stop) as String
       checkJob();outputs+=translated;i++;handler.post{if(token==epoch&&active){progress=calls;message="Translated$calls segments. Cooperative cancel waits for native return."}}
      }
      val perPage=selected.indices.map{page->queue.indices.filter{queue[it].page==page}.joinToString(""){outputs[it]}}
      result=perPage.joinToString("\n\n");require(result!!.toByteArray(Charsets.UTF_8).size<=32768);acceptedTrace=queue.toList();acceptedOutputs=outputs.toList();checkJob()
     }finally{client.close()}
    }
   }catch(t:Throwable){result=null;var cause:Throwable?=t;repeat(8){when(cause?.message){"MODEL_LOAD_LIMIT"->failure="Model loading exceeded10 seconds after native return. Use supported hardware; no draft accepted.";"MISSING_EOS_OR_OUTPUT_LIMIT"->failure+=" Model did not reach EOS within64 output tokens.";"SEGMENT_TIME_LIMIT"->failure+=" Segment exceeded20 seconds after native return.";"JOB_TIME_LIMIT"->failure="The120-second cooperative job budget was exhausted; no complete draft accepted."};cause=cause?.cause}}
   finally{LocalModelUse.running.set(false);val value=result;handler.post{if(token==epoch){busy=false;if(active&&current()&&!stop.get()&&value!=null){automatic=value;draft=value;trace=acceptedTrace;segmentOutputs=acceptedOutputs;draftRevision++;message="Automatic draft ready. Model sessions closed and lease released. Review meaning, names, negation and numbers; human bilingual review not performed."}else message=if(stop.get())"Cancelled after native return; no draft accepted." else failure}}}
  },"local-document-translation").start()
 }
 fun cancel(){flag.set(true);epoch++;pdfJob?.cancel();invalidatePdf();correctionUndo=null;cancelCorrection();busy=false;automatic="";draft="";frozen=null;picker=false;reviewed=false;message="Cancellation requested. Native ownership remains held until its worker returns."}
 private fun valid(token:Long)=active&&token==epoch&&current()
 fun beginSave():String?{if(!active||busy||picker||!reviewed||draft.isBlank()||!current())return null;frozen=FrozenExport(TranslationUtf8.encode(draft).copyOf(),false,draftRevision);picker=true;return "Translation.txt"}
 fun beginPdfSave():String?{if(!active||busy||picker||!reviewed||!pdfReady||!current())return null;frozen=FrozenExport(pdfBytes!!.copyOf(),true,draftRevision);picker=true;return "Translation.pdf"}
 fun destination(uri:Uri?)=destination(saveTicket,uri)
 fun destination(ticket:String?,uri:Uri?){if(!picker||ticket==null||ticket!=frozen?.ticket)return;picker=false;val bytes=frozen;frozen=null;if(uri==null||bytes==null){message="Save cancelled.";return};val token=epoch;if(!valid(token))return;busy=true
  viewModelScope.launch{try{withContext(Dispatchers.IO){write(uri,bytes){check(valid(token)&&draftRevision==bytes.revision)}};if(valid(token))message=if(bytes.pdf)"Separate searchable PDF saved and bytes verified. Source unchanged." else "Separate UTF-8 TXT saved and bytes verified. Source OCR/images unchanged."}catch(_:Exception){if(token==epoch)message="Save interrupted or declined. Inspect any external partial file; it was not deleted or overwritten."}finally{if(token==epoch)busy=false}}
 }
 private fun write(uri:Uri,bytes:FrozenExport,check:()->Unit){val app=getApplication<Application>();val r=app.contentResolver;require(uri.scheme=="content");check()
  r.query(uri,arrayOf(D.COLUMN_DISPLAY_NAME,D.COLUMN_SIZE,D.COLUMN_MIME_TYPE),null,null,null)?.use{c->require(c.moveToFirst()&&c.getString(0)==bytes.name&&!c.isNull(1)&&c.getLong(1)==0L&&c.getString(2)==bytes.mime)}?:error("Destination unavailable")
  val fd=requireNotNull(r.openFileDescriptor(uri,"rw"));try{require(fd.statSize==0L&&workflowDifferentSources(app,fd,uri,guards));check()}catch(t:Throwable){fd.close();throw t}
  ParcelFileDescriptor.AutoCloseOutputStream(fd).use{out->check();out.write(bytes.bytes);out.flush();check()}
  require(r.openInputStream(uri)?.use{input->var offset=0;val b=ByteArray(4096);while(true){check();val n=input.read(b);if(n<0)break;require(n>0&&offset+n<=bytes.bytes.size);for(i in 0 until n)require(b[i]==bytes.bytes[offset+i]);offset+=n};offset==bytes.bytes.size}==true);check()
 }
 fun share(launch:(Intent)->Unit)=shareFormat(false,launch)
 fun sharePdf(launch:(Intent)->Unit)=shareFormat(true,launch)
 private fun shareFormat(pdf:Boolean,launch:(Intent)->Unit){if(!active||busy||picker||!reviewed||draft.isBlank()||!current())return;val token=epoch;if(pdf&&!pdfReady)return;val bytes=FrozenExport(if(pdf)pdfBytes!!.copyOf() else TranslationUtf8.encode(draft),pdf,draftRevision);busy=true
  viewModelScope.launch{var dir:File?=null;var handed=false;try{val file=withContext(Dispatchers.IO){check(valid(token));val base=File(getApplication<Application>().cacheDir,"shared-pdfs");base.mkdirs();base.listFiles().orEmpty().filter{it.name.startsWith("translation-")&&System.currentTimeMillis()-it.lastModified()>86400000}.take(16).forEach{it.deleteRecursively()};require(base.listFiles().orEmpty().count{it.name.startsWith("translation-")}<4);File(base,"translation-${UUID.randomUUID()}").also{dir=it;check(it.mkdir())}.let{File(it,bytes.name).also{f->f.writeBytes(bytes.bytes)}}};check(valid(token));val app=getApplication<Application>();val uri=FileProvider.getUriForFile(app,app.packageName+".fileprovider",file);launch(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(bytes.mime).putExtra(Intent.EXTRA_STREAM,uri).apply{clipData=ClipData.newRawUri("Translation",uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},"Share reviewed translation"));handed=true;message=if(bytes.pdf)"Shared frozen reviewed PDF; recipient controls its copy." else "Shared frozen UTF-8 draft; recipient controls its copy."}catch(_:Exception){if(token==epoch)message="Share cancelled or unavailable."}finally{if(!handed)dir?.deleteRecursively();if(token==epoch)busy=false}}
 }
 fun foreground(unlocked:Boolean){active=unlocked}
 fun background(recreating:Boolean){if(picker&&!recreating){active=false;return};close()}
 fun close(){flag.set(true);epoch++;pdfJob?.cancel();invalidatePdf();correctionUndo=null;cancelCorrection();captureJob?.cancel();visible=false;active=false;busy=false;pages=emptyList();automatic="";draft="";trace=emptyList();segmentOutputs=emptyList();guards=emptyList();frozen=null;picker=false;reviewed=false}
 override fun onCleared(){close();super.onCleared()}
}
