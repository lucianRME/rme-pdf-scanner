package org.synapseworks.pageharbor.validation

import android.app.*
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.InputDevice
import android.view.accessibility.AccessibilityNodeInfo as Node
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import java.io.File
import org.synapseworks.pageharbor.*
import org.synapseworks.pageharbor.document.*
import org.synapseworks.pageharbor.document.session.*
import org.synapseworks.pageharbor.document.objectremoval.*
import org.synapseworks.pageharbor.image.*

internal var fingerRemovalSmokeStage="input"
/** Owned synthetic source only; not included in production builds. */
internal fun Instrumentation.fingerRemoval(){
 val app=targetContext.applicationContext as Application
 val root=File(app.cacheDir,"shared-pdfs/m29-optimized").apply{mkdirs()};val source=File(root,"source.png").apply{writeBytes(app.assets.open("m29/medium-left-512-source.png").use{it.readBytes()})};val before=source.readBytes()
 val activity=startActivitySync(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))as MainActivity
 val vm=ViewModelProvider(activity)[PageHarborSessionViewModel::class.java];val editor=activity.objectRemoval
 fun until(block:()->Boolean){val end=SystemClock.uptimeMillis()+40000;while(SystemClock.uptimeMillis()<end){if(block())return;SystemClock.sleep(100)};error("M29 validation timeout")}
 fun node(predicate:(Node)->Boolean):Node?{val q=java.util.ArrayDeque<Node>();uiAutomation.rootInActiveWindow?.let(q::add);var count=0;while(q.isNotEmpty()&&++count<1024){val n=q.removeFirst();if(predicate(n))return n;for(i in 0 until n.childCount)n.getChild(i)?.let(q::add)};return null}
 fun activate(n:Node?):Boolean{if(n?.isVisibleToUser!=true)return false;var x=n;repeat(8){val p=x?:return false;if(p.isClickable&&p.isEnabled)return p.performAction(Node.ACTION_CLICK);x=p.parent};return false}
 fun event(action:Int,down:Long,x:Float,y:Float){val e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0);try{e.source=InputDevice.SOURCE_TOUCHSCREEN;check(uiAutomation.injectInputEvent(e,true))}finally{e.recycle()}}
 fun scroll(){val m=app.resources.displayMetrics;val t=SystemClock.uptimeMillis();val x=m.widthPixels*.5f;val a=m.heightPixels*.72f;val b=m.heightPixels*.35f;event(MotionEvent.ACTION_DOWN,t,x,a);for(i in 1..10){SystemClock.sleep(20);event(MotionEvent.ACTION_MOVE,t,x,a+(b-a)*i/10)};event(MotionEvent.ACTION_UP,t,x,b);waitForIdleSync()}
 fun click(text:String,prefix:Boolean=false){fingerRemovalSmokeStage="control:"+text;var back=false;until{val n=node{val a=it.text?.toString();val b=it.contentDescription?.toString();if(prefix)a?.startsWith(text)==true||b?.startsWith(text)==true else a==text||b==text}
  if(activate(n)){waitForIdleSync();SystemClock.sleep(150);true}else{if(text==app.getString(R.string.finger_removal_title)){val scroller=node{val r=Rect();it.getBoundsInScreen(r);it.isScrollable&&r.width()>r.height()};if(scroller?.performAction(if(back)Node.ACTION_SCROLL_BACKWARD else Node.ACTION_SCROLL_FORWARD)!=true)back=!back}else scroll();false}}}
 fun automatic(){fingerRemovalSmokeStage="automatic-mask";until{editor.state?.analysis!=null&&editor.state?.work==null};check(editor.state?.mode==RemovalMode.FINGER);check(editor.state?.detection?.proposals==1);check(editor.state?.strokes?.isEmpty()==true)}
 fun preview(){click("Preview removal");fingerRemovalSmokeStage="review";until{editor.state?.prepared!=null&&editor.state?.reviewedDigest==editor.state?.prepared?.resultDigest&&editor.state?.work==null};check(editor.state?.status==RemovalStatus.READY)}
 try{
  runOnMainSync{activity.installDocumentSessionForTest(DocumentSession(listOf(DocumentPage(DocumentPageId(1),DocumentResource(FileProvider.getUriForFile(app,"${app.packageName}.fileprovider",source).toString(),DocumentResourceOwnership.USER_OR_EXTERNAL),DocumentSourceCategory.SCAN,"image/png",DocumentImageMetadata(source.length(),512,384),filter=DocumentFilter.PAPER_WHITE_COLOUR_BALANCED_V1,adjustments=ManualImageAdjustments(-10,15)))))}
  val initial=vm.documentSession;click("Edit");click("Automatic finger removal");automatic();preview()
  val evidence=File(app.getExternalFilesDir(null),"m29-evidence").apply{mkdirs()};val screenshot=uiAutomation.takeScreenshot();try{File(evidence,"optimized-preview.png").outputStream().use{check(screenshot.compress(Bitmap.CompressFormat.PNG,100,it))}}finally{screenshot.recycle()}
  click("Before");click("After");click("Keep unchanged");until{editor.state==null};check(vm.documentSession===initial)
  click("Edit");click("Automatic finger removal");automatic();preview();val prepared=editor.state!!.prepared!!;val revision=vm.documentRevision;click("Apply");until{editor.state==null&&!vm.historyState.busy&&vm.historyState.undoCount==1};check(vm.historyState.undoAction==org.synapseworks.pageharbor.document.history.HistoryAction.FINGER_REMOVAL);val accepted=vm.documentSession
  fun bytes()=app.contentResolver.openInputStream(vm.documentPages.single().source.toAndroidUri())!!.use{it.readBytes()}
  val result=bytes();check(vm.documentPages.single().id!=initial.pages.single().id)
  click("Undo ",true);until{!vm.historyState.busy&&vm.historyState.redoCount==1};check(bytes().contentEquals(before));check(!vm.historyState.dirty)
  click("Redo ",true);until{!vm.historyState.busy&&vm.documentSession===accepted};check(bytes().contentEquals(result))
  fingerRemovalSmokeStage="frozen-export"
  kotlinx.coroutines.runBlocking{val output=recomposeNormalPdf(app,(normalPdfExportPlan(accepted)as NormalPdfExportPlan.RecomposeFromPages).pages)as NormalPdfRecompositionResult.Ready;try{output.file.copyTo(File(evidence,"optimized.pdf"),true)}finally{deleteNormalPdfRecomposition(output.file)}}
  kotlinx.coroutines.runBlocking{fingerPersistence(app,initial,result,evidence)};check(vm.historyState.undoCount==1)
  runOnMainSync{check(!vm.applyObjectRemoval(initial,revision,prepared))};check(source.readBytes().contentEquals(before))
 }catch(failure:Throwable){runCatching{val b=uiAutomation.takeScreenshot();try{File(app.getExternalFilesDir(null),"m29-evidence").apply{mkdirs()}.let{File(it,"optimized-failure.png").outputStream().use{out->b.compress(Bitmap.CompressFormat.PNG,100,out)}}}finally{b.recycle()}};throw failure}
 finally{runOnMainSync{editor.close();vm.clearScan();activity.finish()};root.deleteRecursively()}
}
