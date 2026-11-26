package org.synapseworks.pageharbor.assistance

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
import org.synapseworks.pageharbor.ocr.OcrResult
import org.synapseworks.pageharbor.translation.*
import org.synapseworks.pageharbor.workflow.*

/** One transient current-document scope. No model, executable templates or persistent index. */
class AssistanceViewModel(app:Application):AndroidViewModel(app){
 val owner=UUID.randomUUID().toString()
 var interactionToken by mutableStateOf(0L);private set
 fun interact(token:Long,action:()->Unit){if(token==interactionToken&&visible&&active&&current())action()}
 var visible by mutableStateOf(false);private set
 var busy by mutableStateOf(false);private set
 var captured by mutableStateOf(false);private set
 var pageScope by mutableStateOf("1")
 var query by mutableStateOf("");private set
 var message by mutableStateOf("");private set
 var results by mutableStateOf<List<Passage>>(emptyList());private set
 var resultKind by mutableStateOf("");private set
 var draft by mutableStateOf("");private set
 var edited by mutableStateOf(false);private set
 var reviewed by mutableStateOf(false)
 var picker by mutableStateOf(false);private set
 var inspected by mutableStateOf<Passage?>(null);private set
 var revision=0L;private set
 var draftRevision by mutableStateOf(0L);private set
 var pdfPages by mutableStateOf(0);private set
 val pdfReady get()=pdfBytes!=null&&pdfRevision==draftRevision
 var indexBuilds=0;private set
 private var index:AssistanceIndex?=null
 private var document:DocumentSession?=null
 private var ocr:(suspend()->OcrResult?)?=null
 private var release:(()->Unit)?=null
 @Volatile private var epoch=0L
 @Volatile private var active=true
 private var current:()->Boolean={false}
 private var capturing=false
 private var job:Job?=null
 private var guards=emptyList<WorkflowSourceGuard>()
 private var pdfBytes:ByteArray?=null
 private var pdfRevision=-1L
 private class Export(val bytes:ByteArray,val pdf:Boolean,val revision:Long,val ticket:String=UUID.randomUUID().toString()){val name get()=if(pdf)"Assistance.pdf" else "Assistance.txt";val mime get()=if(pdf)"application/pdf" else "text/plain"}
 private var frozen:Export?=null
 val saveTicket get()=frozen?.ticket
 fun open(s:DocumentSession,r:Long,isCurrent:()->Boolean,releaseSource:()->Unit,readOcr:suspend()->OcrResult?){if(visible){releaseSource();return};interactionToken++;visible=true;active=true;revision=r;current=isCurrent;document=s;release=releaseSource;ocr=readOcr;indexBuilds=0;message="Search or summarize text from up to 3 pages. Choose Recognize text if a page needs OCR."}
 private fun valid(token:Long)=visible&&active&&token==epoch&&current()
 fun capture(){if(busy||captured||!visible||!current())return;val s=document?:return
  if(s.pages.size !in 1..3){message="This tool works with documents of 1–3 pages. Create a shorter copy to continue.";return}
  val selected=try{pageScope.split(',').map{it.trim().toInt()-1}.also{require(it.size in 1..3&&it.distinct().size==it.size&&it.all{p->p in s.pages.indices})}}catch(_:Exception){message="Choose 1–3 different page numbers from this document.";return}
  val token=++epoch;val capturedRevision=revision;val readOcr=ocr;busy=true;capturing=true
  job=viewModelScope.launch{try{val result=readOcr?.invoke();check(valid(token));val pages=selected.map{i->val p=s.pages[i];AssistancePage(i+1,p.persistentId?:p.id.value.toString(),result?.pages?.singleOrNull{it.pageIndex==i&&it.error==null}?.text.orEmpty())};require(pages.all{it.text.isNotBlank()}){"MISSING_OCR"}
   val pair=withContext(Dispatchers.IO){val sources=(s.originalPdfSources+selected.map{s.pages[it].source}).distinctBy{it.reference};require(sources.size in 1..40);val g=sources.map{check(valid(token));workflowSourceGuard(getApplication(),it.toAndroidUri())};val deadline=SystemClock.elapsedRealtime()+2000;val ix=AssistanceIndex(capturedRevision,pages){check(valid(token));check(SystemClock.elapsedRealtime()<deadline)};check(SystemClock.elapsedRealtime()<deadline);ix to g}
   check(valid(token));index=pair.first;guards=pair.second;indexBuilds++;captured=true;message="Selected pages are ready. Summaries use up to 5 complete source sentences."
  }catch(_:CancellationException){}catch(_:Exception){if(token==epoch)message="The selected pages could not be read. Recognize their text again or choose fewer pages. No text was shortened."}finally{if(token==epoch){capturing=false;busy=false}}}
 }
 fun updateScope(s:String){if(!visible||!active||!current()||captured||busy||picker)return;if(s.length>12){message="Use 12 characters or fewer for page numbers.";return};pageScope=s}
 fun updateQuery(s:String){if(!visible||!active||!current())return;if(!busy&&!picker&&s.toByteArray(Charsets.UTF_8).size<=128)query=s else if(!busy&&!picker)message="Shorten the search to 128 UTF-8 bytes or fewer."}
 fun find(){val capturedQuery=query;operate("Cited passages"){ix,check->ix.find(capturedQuery,check)}}
 fun summary(){operate("Summary from the first 5 complete sentences or fewer"){ix,_->ix.summary()}}
 fun suggestions(){operate("Review-only source title and frequent literal tags"){ix,_->listOfNotNull(ix.title())+ix.tags()}}
 private fun operate(kind:String,action:(AssistanceIndex,()->Unit)->List<Passage>){val ix=index?:return;if(busy||picker||!current()||!active)return;val token=++epoch;busy=true;results=emptyList();inspected=null;clearDraft()
  job=viewModelScope.launch{try{val found=withContext(Dispatchers.Default){val deadline=SystemClock.elapsedRealtime()+500;val checkWork={check(valid(token));check(SystemClock.elapsedRealtime()<deadline)};checkWork();val found=action(ix,checkWork);checkWork();found};check(valid(token));results=found.toList();resultKind=kind;message=if(found.isEmpty())"No supporting evidence in the captured scope." else "${found.size} source extracts. Inspect citations; OCR may be wrong. Suggestions never change metadata."}catch(_:CancellationException){}catch(_:Exception){if(token==epoch)message="Use 1–8 whole words, up to 128 UTF-8 bytes. Narrow your search if it finds more than 8 matches."}finally{if(token==epoch)busy=false}}
 }
 fun inspect(p:Passage){if(!current()){close();return};val ix=index?:return;val page=document?.pages?.getOrNull(p.page.number-1);if(ix.pages.none{it===p.page}||page==null||(page.persistentId?:page.id.value.toString())!=p.page.id){message="Citation unavailable: captured page identity changed.";return};inspected=p}
 fun inspectedSource():DocumentPage?{if(!current()){close();return null};return inspected?.let{document?.pages?.getOrNull(it.page.number-1)}}
 fun dismissSource(){inspected=null}
 fun use(p:Passage){if(busy||picker||!current()||results.none{it===p})return;setArtifact(p.quote+"\n\nUnverified draft reference: "+p.citation)}
 fun useAll(){if(busy||picker||!current()||results.isEmpty())return;setArtifact(results.joinToString("\n\n"){it.quote+"\nUnverified draft reference: "+it.citation})}
 private fun setArtifact(text:String){try{TranslationUtf8.encode(text);draft=text;draftRevision++;edited=false;reviewed=false;invalidatePdf()}catch(_:Exception){message="The selected text exceeds 32,768 UTF-8 bytes. Choose fewer excerpts."}}
 fun edit(s:String){if(!visible||!active||!current()||busy||picker)return;try{TranslationUtf8.encode(s);draft=s;draftRevision++;edited=true;reviewed=false;invalidatePdf()}catch(_:Exception){message="The draft exceeds 32,768 UTF-8 bytes. Shorten the edit to continue."}}
 fun copy(clip: (String) -> Boolean) {
  if (!busy && !picker && reviewed && draft.isNotBlank() && current()) {
   message = if (clip(draft)) "Copied reviewed artifact." else "Clipboard unavailable."
  }
 }
 private fun invalidatePdf(){pdfBytes=null;pdfRevision=-1;pdfPages=0}
 private fun clearDraft(){draft="";reviewed=false;edited=false;invalidatePdf()}
 fun preparePdf(){val ix=index?:return;if(busy||picker||!reviewed||draft.isBlank()||!current())return;val snapshot=TranslationPdfSnapshot(draft,draftRevision,revision,ix.pages.map{it.number to it.id});val token=epoch;busy=true;invalidatePdf()
  job=viewModelScope.launch{try{val p=TranslationPdf.create(getApplication(),snapshot,assistance=true){check(valid(token)&&draftRevision==snapshot.draftRevision)};if(valid(token)){pdfBytes=p.first;pdfPages=p.second;pdfRevision=snapshot.draftRevision;message="Reviewed searchable reflowed PDF ready. Global captured input references only; edited citations are unverified."}}catch(_:CancellationException){}catch(_:Exception){if(token==epoch)message="PDF declined: unsupported character/glyph, fit or resource limit. TXT remains available."}finally{if(token==epoch)busy=false}}
 }
 fun beginSave(pdf:Boolean):String?{if(!visible||!active||busy||picker||!reviewed||draft.isBlank()||!current()||pdf&&!pdfReady)return null;frozen=Export(if(pdf)pdfBytes!!.copyOf() else TranslationUtf8.encode(draft),pdf,draftRevision);picker=true;return frozen!!.name}
 fun destination(ticket:String?,uri:Uri?){if(!picker||ticket==null||ticket!=frozen?.ticket)return;val value=frozen!!;frozen=null;picker=false;if(uri==null){message="Save cancelled.";return};val token=epoch;if(!valid(token))return;busy=true
  job=viewModelScope.launch{try{withContext(Dispatchers.IO){write(uri,value){check(valid(token)&&draftRevision==value.revision)}};if(valid(token))message="Separate reviewed artifact saved and bytes verified. Source unchanged."}catch(_:Exception){if(token==epoch)message="Save refused or interrupted; inspect any user-owned partial file. No overwrite was requested."}finally{if(token==epoch)busy=false}}
 }
 private fun write(uri:Uri,e:Export,check:()->Unit){val app=getApplication<Application>();val r=app.contentResolver;require(uri.scheme=="content");check();r.query(uri,arrayOf(D.COLUMN_DISPLAY_NAME,D.COLUMN_SIZE,D.COLUMN_MIME_TYPE),null,null,null)?.use{c->require(c.moveToFirst()&&c.getString(0)==e.name&&!c.isNull(1)&&c.getLong(1)==0L&&c.getString(2)==e.mime)}?:error("DESTINATION")
  val fd=requireNotNull(r.openFileDescriptor(uri,"rw"));try{require(fd.statSize==0L&&workflowDifferentSources(app,fd,uri,guards));check()}catch(t:Throwable){fd.close();throw t};ParcelFileDescriptor.AutoCloseOutputStream(fd).use{check();it.write(e.bytes);it.flush();check()};require(r.openInputStream(uri)?.use{input->val b=ByteArray(4096);var at=0;while(true){check();val n=input.read(b);if(n<0)break;require(n>0&&at+n<=e.bytes.size);for(i in 0 until n)require(b[i]==e.bytes[at+i]);at+=n};at==e.bytes.size}==true);check()
 }
 fun share(pdf:Boolean,launch:(Intent)->Unit){if(!active||busy||picker||!reviewed||!current()||draft.isBlank()||pdf&&!pdfReady)return;val e=Export(if(pdf)pdfBytes!!.copyOf()else TranslationUtf8.encode(draft),pdf,draftRevision);val token=epoch;busy=true
  job=viewModelScope.launch{var dir:File?=null;var handed=false;try{val f=withContext(Dispatchers.IO){check(valid(token));val root=File(getApplication<Application>().cacheDir,"shared-pdfs").apply{mkdirs()};root.listFiles().orEmpty().filter{it.name.startsWith("assistance-")&&System.currentTimeMillis()-it.lastModified()>86400000}.take(16).forEach{it.deleteRecursively()};require(root.listFiles().orEmpty().count{it.name.startsWith("assistance-")}<4);dir=File(root,"assistance-${UUID.randomUUID()}").apply{check(mkdir())};File(dir,e.name).apply{writeBytes(e.bytes)}};check(valid(token)&&draftRevision==e.revision);val app=getApplication<Application>();val uri=FileProvider.getUriForFile(app,app.packageName+".fileprovider",f);launch(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(e.mime).putExtra(Intent.EXTRA_STREAM,uri).apply{clipData=ClipData.newRawUri("Reviewed assistance",uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},"Share reviewed extraction"));handed=true;message="Shared reviewed artifact; recipient controls its copy."}catch(_:Exception){if(token==epoch)message="Share unavailable; close old exports or retry later."}finally{if(!handed)dir?.deleteRecursively();if(token==epoch)busy=false}}
 }
 fun changed(){if(visible&&!current())close()}
 fun foreground(unlocked:Boolean){active=unlocked}
 fun background(recreating:Boolean){if(picker&&!recreating){active=false;return};close()}
 fun cancel(){close()}
 fun close(){interactionToken++;val oldRelease=release;release=null;val oldJob=job;if(capturing&&oldJob!=null)oldJob.invokeOnCompletion{oldRelease?.invoke()}else oldRelease?.invoke();capturing=false;epoch++;job?.cancel();visible=false;active=false;busy=false;captured=false;index=null;document=null;ocr=null;guards=emptyList();results=emptyList();inspected=null;query="";clearDraft();frozen=null;picker=false;pageScope="1";revision=0;draftRevision=0;indexBuilds=0;resultKind="";message="";current={false};job=null}
 override fun onCleared(){close();super.onCleared()}
}
