package org.synapseworks.pageharbor.validation
import android.app.*
import androidx.lifecycle.viewModelScope
import android.os.Bundle
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.util.UUID
import org.synapseworks.pageharbor.translation.*
import org.synapseworks.pageharbor.document.session.*
import org.synapseworks.pageharbor.ocr.*
import org.synapseworks.pageharbor.workflow.*
/** Synthetic state/ownership controls, separate from genuine UI/OCR evidence. */
class TranslationWorkflowChecks:Instrumentation(){
 override fun onCreate(args:Bundle?){super.onCreate(args);start()}
 override fun onStart(){val result=Bundle();val dir=File(File(targetContext.cacheDir,"shared-pdfs").apply{mkdirs()},"m37-control-"+UUID.randomUUID());var vm:TranslationViewModel?=null
 try{check(targetContext.packageName.endsWith(".m36packsmoke")||targetContext.packageName.endsWith(".m36packr8"));check(dir.mkdir());val source=File(dir,"source.bin").apply{writeBytes(byteArrayOf(1,2,3))};val target=File(dir,"Translation.txt").apply{createNewFile()};val guard=workflowSourceGuard(targetContext,Uri.fromFile(source))
  val alias=androidx.core.content.FileProvider.getUriForFile(targetContext,targetContext.packageName+".fileprovider",source)
  ParcelFileDescriptor.open(source,ParcelFileDescriptor.MODE_READ_WRITE).use{check(!workflowDifferentSources(targetContext,it,alias,listOf(guard)))}
  ParcelFileDescriptor.open(target,ParcelFileDescriptor.MODE_READ_WRITE).use{check(workflowDifferentSources(targetContext,it,Uri.fromFile(target),listOf(guard)))}
  // Capture must not publish text until its source-descriptor guards are complete.
  val controlled=Uri.parse("content://"+targetContext.packageName+".m37-control/owned-control")
  fun document(uri:Uri)=DocumentSession(listOf(DocumentPage(DocumentPageId(88),DocumentResource(uri.toString(),DocumentResourceOwnership.USER_OR_EXTERNAL),DocumentSourceCategory.SELECTED_IMAGE)))
  val captureGate=TranslationIoGate(source,false);TranslationControlProvider.active=captureGate
  runOnMainSync{vm=TranslationViewModel(targetContext.applicationContext as Application);vm!!.open(document(controlled),20,{true},{}){OcrResult(listOf(OcrPageResult(0,"Guarded capture")))}}
  try{check(captureGate.entered.await(2,java.util.concurrent.TimeUnit.SECONDS));runOnMainSync{check(vm!!.busy&&vm!!.pages.isEmpty())}}finally{captureGate.release.countDown()}
  repeat(100){if(vm!!.busy)Thread.sleep(20)};runOnMainSync{check(!vm!!.busy&&vm!!.pages.single().text=="Guarded capture");vm!!.close()}
  // A late cancelled writer cannot clear busy/message state of a newer capture.
  val writerGate=TranslationIoGate(target,true);TranslationControlProvider.active=writerGate
  runOnMainSync{vm!!.open(document(Uri.fromFile(source)),21,{true},{}){OcrResult(listOf(OcrPageResult(0,"First capture")))}}
  repeat(100){if(vm!!.busy)Thread.sleep(20)}
  runOnMainSync{vm!!.edit("Frozen draft țară");vm!!.reviewed=true;check(vm!!.beginSave()!=null);vm!!.destination(controlled)}
  check(writerGate.entered.await(2,java.util.concurrent.TimeUnit.SECONDS));val writer=vm!!.viewModelScope.coroutineContext[kotlinx.coroutines.Job]!!.children.single();val nextOcr=kotlinx.coroutines.CompletableDeferred<OcrResult>()
  runOnMainSync{vm!!.close();vm!!.open(document(Uri.fromFile(source)),22,{true},{}){nextOcr.await()}}
  writerGate.release.countDown();kotlinx.coroutines.runBlocking{kotlinx.coroutines.withTimeout(2000){writer.join()}}
  runOnMainSync{check(vm!!.busy&&vm!!.pages.isEmpty());check(!vm!!.message.startsWith("Save interrupted"));nextOcr.complete(OcrResult(listOf(OcrPageResult(0,"Second capture"))))}
  repeat(100){if(vm!!.busy)Thread.sleep(20)};runOnMainSync{check(vm!!.pages.single().text=="Second capture");vm!!.close()};check(target.length()==0L);TranslationControlProvider.active=null
  var current=true;var releases=0
  runOnMainSync{vm=TranslationViewModel(targetContext.applicationContext as Application);vm!!.open(DocumentSession(listOf(DocumentPage(DocumentPageId(7),DocumentResource(Uri.fromFile(source).toString(),DocumentResourceOwnership.USER_OR_EXTERNAL),DocumentSourceCategory.SELECTED_IMAGE))),12,{current},{releases++}){OcrResult(listOf(OcrPageResult(0,"Public test source.")))}}
  repeat(100){if(vm!!.busy)Thread.sleep(20)};check(!vm!!.busy&&releases==1&&vm!!.pages.single().text=="Public test source.")
  runOnMainSync{vm!!.edit("Draft țară");vm!!.reviewed=true;check(vm!!.beginSave()=="Translation.txt");vm!!.edit("Retarget");check(vm!!.draft=="Draft țară");vm!!.destination(null);check(!vm!!.picker);vm!!.edit("Second draft");vm!!.reviewed=true;check(vm!!.beginSave()!=null);current=false;vm!!.destination(Uri.fromFile(target));vm!!.changed();check(!vm!!.visible&&vm!!.draft.isEmpty());vm!!.destination(Uri.fromFile(target))}
  check(target.length()==0L&&source.readBytes().contentEquals(byteArrayOf(1,2,3)))
  runOnMainSync{current=true;vm!!.open(DocumentSession(listOf(DocumentPage(DocumentPageId(9),DocumentResource(Uri.fromFile(source).toString(),DocumentResourceOwnership.USER_OR_EXTERNAL),DocumentSourceCategory.SELECTED_IMAGE))),13,{current},{releases++}){kotlinx.coroutines.delay(200);OcrResult(listOf(OcrPageResult(0,"Late capture")))};vm!!.close()};Thread.sleep(300);check(releases==2&&!vm!!.visible&&vm!!.pages.isEmpty());runOnMainSync{vm!!.open(DocumentSession(listOf(DocumentPage(DocumentPageId(10),DocumentResource(Uri.fromFile(source).toString(),DocumentResourceOwnership.USER_OR_EXTERNAL),DocumentSourceCategory.SELECTED_IMAGE))),14,{current},{releases++}){OcrResult(listOf(OcrPageResult(0,"Hello world.")))}};repeat(100){if(vm!!.busy)Thread.sleep(20)}
  runOnMainSync{vm!!.translate();vm!!.cancel()};repeat(500){if(org.synapseworks.pageharbor.resourcepack.LocalModelUse.running.get())Thread.sleep(20)};check(!org.synapseworks.pageharbor.resourcepack.LocalModelUse.running.get());runOnMainSync{check(vm!!.automatic.isEmpty()&&vm!!.draft.isEmpty());vm!!.close()};result.putString("status","PASS");result.putString("scope","atomic guarded capture; stale writer cannot retarget new capture; source alias; independent destination; frozen draft; picker cancel; stale revision/callback no write; late capture release; cancelled model job rejects draft and releases worker ownership")
 }catch(t:Throwable){result.putString("status","FAIL");result.putString("failureClass",t.javaClass.simpleName)}finally{TranslationControlProvider.active?.release?.countDown();TranslationControlProvider.active=null;runOnMainSync{vm?.close()};dir.deleteRecursively()};finish(Activity.RESULT_OK,result)}
}
