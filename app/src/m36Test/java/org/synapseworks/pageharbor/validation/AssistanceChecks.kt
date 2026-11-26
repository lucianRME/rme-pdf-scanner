package org.synapseworks.pageharbor.validation
import android.app.*
import android.os.*
import android.net.Uri
import java.io.File
import java.util.UUID
import org.json.JSONObject
import org.synapseworks.pageharbor.assistance.*
import org.synapseworks.pageharbor.document.session.*
import org.synapseworks.pageharbor.ocr.*
/** Public synthetic corpus, no model or inference calls. */
class AssistanceChecks:Instrumentation(){
 override fun onCreate(args:Bundle?){super.onCreate(args);start()}
 override fun onStart(){val r=Bundle();val root=File(targetContext.cacheDir,"m38-check-"+UUID.randomUUID());var vm:AssistanceViewModel?=null
 try{check(targetContext.packageName.endsWith(".m36packsmoke")||targetContext.packageName.endsWith(".m36packr8"));check(root.mkdir());r.putBoolean("optionalRuntimeClassPresent",runCatching{Class.forName("org.synapseworks.pageharbor.resourcepack.KnownModelRuntime",false,targetContext.classLoader)}.isSuccess);val started=SystemClock.elapsedRealtime();val file=File(root,"source.bin").apply{writeBytes(byteArrayOf(1,2,3))};val corpus=JSONObject(targetContext.assets.open("m38-corpus.json").bufferedReader().use{it.readText()});val p=corpus.getJSONArray("pages");val session=DocumentSession((0 until p.length()).map{i->DocumentPage(DocumentPageId(i.toLong()),DocumentResource(Uri.fromFile(file).toString(),DocumentResourceOwnership.USER_OR_EXTERNAL),DocumentSourceCategory.SELECTED_IMAGE,persistentId=p.getJSONObject(i).getString("id"))});val ocr=OcrResult((0 until p.length()).map{i->OcrPageResult(i,p.getJSONObject(i).getString("text"))});var current=true;var released=0;var oldInteraction=0L
  fun ready(){repeat(500){if(vm!!.busy)Thread.sleep(20)};check(!vm!!.busy)}
  runOnMainSync{vm=AssistanceViewModel(targetContext.applicationContext as Application);vm!!.open(session,42,{current},{released++}){ocr};vm!!.pageScope="1,2,3";vm!!.capture()};ready();check(vm!!.captured&&vm!!.indexBuilds==1);r.putLong("captureElapsedMs",SystemClock.elapsedRealtime()-started)
  val cases=corpus.getJSONArray("queries");var matched=0;val queryStart=SystemClock.elapsedRealtime()
  for(i in 0 until cases.length()){val c=cases.getJSONObject(i);runOnMainSync{vm!!.updateQuery(c.getString("q"));vm!!.find()};ready();val expected=c.getJSONArray("pages");check(vm!!.results.map{it.page.id}==(0 until expected.length()).map{expected.getString(it)});if(expected.length()>0)check(vm!!.results.single().quote==c.getString("quote"));vm!!.results.forEach{check(it.quote==it.page.text.substring(it.start,it.end))};matched++}
  check(vm!!.indexBuilds==1);r.putInt("corpusCases",matched);r.putLong("nineQueriesIncludingThreadHandoffsMs",SystemClock.elapsedRealtime()-queryStart);r.putString("stage","summary and sources")
  runOnMainSync{vm!!.summary()};ready();check(vm!!.results.size==5&&vm!!.results.all{it.complete});val first=vm!!.results.first();runOnMainSync{vm!!.inspect(first);check(vm!!.inspectedSource()?.persistentId=="p1");vm!!.dismissSource();vm!!.suggestions()};ready();check(vm!!.results.isNotEmpty());check(released==0)
  r.putString("stage","independent reviewed export")
  val reviewed="Școală și țară. Şcoală şi ţară. μ µ.\n\nReviewed independent extract 12.\n\n"+(1..40).joinToString("\n\n"){"Reviewed paragraph $it. Source references are global only."}
  runOnMainSync{vm!!.updateQuery("PRIVATE_M38_QUERY");vm!!.useAll();vm!!.edit(corpus.getString("removedCanary")+reviewed);vm!!.edit(reviewed);vm!!.reviewed=true;vm!!.preparePdf()};ready();check(vm!!.pdfReady&&vm!!.edited&&vm!!.pdfPages>1)
  val evidence=File(targetContext.cacheDir,"m38-evidence").apply{mkdirs()};File(evidence,"reviewed.txt").writeText(reviewed)
  val uri=Uri.parse("content://"+targetContext.packageName+".m37-control/owned-control")
  for(pdf in listOf(false,true)){val target=File(root,if(pdf)"Assistance.pdf"else"Assistance.txt").apply{createNewFile()};val gate=TranslationIoGate(target,true,pdf,"Assistance");gate.release.countDown();TranslationControlProvider.active=gate
   runOnMainSync{check(vm!!.beginSave(pdf)!=null);val old=vm!!.saveTicket;vm!!.destination(old,null);check(vm!!.beginSave(pdf)!=null);vm!!.destination(old,uri);check(vm!!.picker&&target.length()==0L);vm!!.edit("LATE RETARGET");check(vm!!.draft==reviewed);vm!!.destination(vm!!.saveTicket,uri)};ready();check(vm!!.message.startsWith("Separate reviewed artifact saved"));if(pdf){check(target.readBytes().take(4)=="%PDF".toByteArray().toList());target.copyTo(File(evidence,"reviewed.pdf"),overwrite=true)}else check(target.readText()==reviewed)
  }
  runOnMainSync{var copied="";vm!!.copy{copied=it;true};check(copied==reviewed);vm!!.copy{false};check(vm!!.message=="Clipboard unavailable.");oldInteraction=vm!!.interactionToken;vm!!.close();vm!!.edit("STALE_DRAFT_CANARY");vm!!.updateQuery("STALE_QUERY_CANARY");check(vm!!.draft.isEmpty()&&vm!!.results.isEmpty()&&vm!!.query.isEmpty()&&!vm!!.captured)};check(released==1&&file.readBytes().contentEquals(byteArrayOf(1,2,3)))
  r.putString("stage","stale source and cancellation")
  val pending=kotlinx.coroutines.CompletableDeferred<OcrResult>();runOnMainSync{vm!!.open(session,43,{true},{released++}){pending.await()};vm!!.capture();vm!!.close()};Thread.sleep(100);check(released==2)
  runOnMainSync{current=true;vm!!.open(session,44,{current},{released++}){ocr};vm!!.capture()};ready();runOnMainSync{vm!!.updateQuery("books");vm!!.find()};ready();val cited=vm!!.results.single();runOnMainSync{current=false;vm!!.inspect(cited);check(!vm!!.visible&&vm!!.results.isEmpty()&&vm!!.draft.isEmpty())};check(released==3)
  runOnMainSync{vm!!.open(session,45,{true},{released++}){null};vm!!.capture()};ready();check(!vm!!.captured&&vm!!.message.contains("missing OCR"));runOnMainSync{vm!!.close()};check(released==4)
  val other=DocumentSession(listOf(DocumentPage(DocumentPageId(99),DocumentResource(Uri.fromFile(file).toString(),DocumentResourceOwnership.USER_OR_EXTERNAL),DocumentSourceCategory.SELECTED_IMAGE,persistentId="other")))
  runOnMainSync{vm!!.open(other,46,{true},{released++}){OcrResult(listOf(OcrPageResult(0,"Only another document has mango.")))};vm!!.interact(oldInteraction){vm!!.edit("CROSS_DOCUMENT_STALE_DRAFT");vm!!.updateQuery("STALE_QUERY")};check(vm!!.draft.isEmpty()&&vm!!.query.isEmpty());vm!!.capture()};ready();runOnMainSync{vm!!.updateQuery("books");vm!!.find()};ready();check(vm!!.results.isEmpty()&&vm!!.indexBuilds==1);runOnMainSync{vm!!.close()};check(released==5)
  check(!File("/proc/self/maps").readText().contains("libonnxruntime"));r.putBoolean("modelNativeLoaded",false);r.putString("stage","complete");r.putString("status","PASS");r.putString("scope","9 frozen cases; reusable index; exact ranges; summary/title/tags; source identity; reviewed-only TXT/PDF; stale tickets; copy; cancellation; lease and state cleanup")
 }catch(t:Throwable){r.putString("status","FAIL");r.putString("failureClass",t.javaClass.simpleName)}finally{TranslationControlProvider.active?.release?.countDown();TranslationControlProvider.active=null;runOnMainSync{vm?.close()};root.deleteRecursively()};finish(Activity.RESULT_OK,r)}
}
