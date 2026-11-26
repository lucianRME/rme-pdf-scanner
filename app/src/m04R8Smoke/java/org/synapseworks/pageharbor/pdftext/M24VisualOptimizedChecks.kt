package org.synapseworks.pageharbor.pdftext

import org.synapseworks.pageharbor.forms.*

import android.app.*
import android.content.Intent
import android.net.Uri
import android.os.*
import android.view.accessibility.AccessibilityNodeInfo as Node
import java.io.File
import java.security.MessageDigest
import org.synapseworks.pageharbor.MainActivity

/** Fixed synthetic evidence only. This class/assets are absent from production. */
internal var visualSmokeStage="input"
internal fun Instrumentation.visualFirstOffline(){
    val app=targetContext.applicationContext as Application
    val dir=File(app.cacheDir,"m24-visual-optimized").apply{mkdirs()}
    val file=File(dir,"input.pdf").apply{writeBytes(app.assets.open("m24visual/image.pdf").use{it.readBytes()})}
    val before=MessageDigest.getInstance("SHA-256").digest(file.readBytes())
    val activity=startActivitySync(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    val vm=activity.pdfText;var saved:Uri?=null
    val monitor=object:Instrumentation.ActivityMonitor(){override fun onStartActivity(intent:Intent):Instrumentation.ActivityResult?=if(intent.action==Intent.ACTION_GET_CONTENT){check(intent.type=="application/pdf");Instrumentation.ActivityResult(Activity.RESULT_OK,Intent().setData(Uri.fromFile(file)))}else null}
    fun until(block:()->Boolean){val end=SystemClock.uptimeMillis()+40000;while(SystemClock.uptimeMillis()<end){if(block())return;SystemClock.sleep(100)};error("Text validation timeout")}
    fun node(predicate:(Node)->Boolean):Node?{val root=uiAutomation.rootInActiveWindow?:return null;val q=java.util.ArrayDeque<Node>();q.add(root);var n=0;while(q.isNotEmpty()&&++n<=1024){val x=q.removeFirst();if(predicate(x))return x;for(i in 0 until x.childCount)x.getChild(i)?.let(q::addLast)};return null}
    fun activate(n:Node?):Boolean{var x=n;repeat(8){val p=x?:return false;if(p.isClickable&&p.isEnabled)return p.performAction(Node.ACTION_CLICK);x=p.parent};return false}
    fun scroll(back:Boolean=false):Boolean{val moved=node{val r=android.graphics.Rect();it.getBoundsInScreen(r);it.isScrollable&&r.height()>r.width()}?.performAction(if(back)Node.ACTION_SCROLL_BACKWARD else Node.ACTION_SCROLL_FORWARD)==true;waitForIdleSync();SystemClock.sleep(150);return moved}
    fun click(text:String,done:()->Boolean={false}){var back=false;until{if(done()||activate(node{it.text?.toString()?.equals(text,true)==true||it.contentDescription?.toString()?.equals(text,true)==true})){waitForIdleSync();SystemClock.sleep(150);true}else{if(!scroll(back))back=!back;false}}}
    fun confirm(){until{if(node{it.text?.toString()=="I checked the visual replacement and understand covered source content can remain in the PDF."}!=null)true else{scroll();false}}
        until{if(activate(node{it.isCheckable&&!it.isChecked}))true else{scroll();false}}}
    fun replace(old:String,new:String){repeat(14){scroll(true);SystemClock.sleep(20)};until{val x=node{it.isEditable&&it.text?.toString()?.contains(old)==true};if(x!=null)x.performAction(Node.ACTION_SET_TEXT,Bundle().apply{putCharSequence(Node.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,new)})else{scroll();false}}}
    fun destination(){until{val x=node{it.contentDescription?.toString() in listOf("Show roots","Show navigation drawer")};x?.performAction(Node.ACTION_CLICK)==true};until{var x=node{n->if(n.text?.toString()!="Downloads")false else{var parent:Node?=n;var found=false;repeat(12){if(parent?.viewIdResourceName?.endsWith("/roots_list")==true)found=true;parent=parent?.parent};found}}
            if(activate(x))true else{val roots=node{it.viewIdResourceName?.endsWith("/roots_list")==true};if(roots?.performAction(Node.ACTION_SCROLL_FORWARD)==false)roots.performAction(Node.ACTION_SCROLL_BACKWARD);false}};click("Save")}
    addMonitor(monitor)
    try{
        runOnMainSync{vm.onSavedForTest={saved=it}}
        visualSmokeStage="reachable-input"
        visualSmokeStage="more";click("More");visualSmokeStage="entry";click("Edit PDF text"){vm.state!=null};visualSmokeStage="mode";click("Visual replacement");check(vm.mode==PdfEditMode.VISUAL);visualSmokeStage="picker";click("Choose source PDF");visualSmokeStage="load";until{vm.state?.phase==FormPhase.EDIT||vm.state?.message!=null};check(vm.state?.phase==FormPhase.EDIT)
        check(vm.visualDocument!!.pages.size==1)
        click("Select central region");until{vm.visualRegion==DEFAULT_VISUAL_REGION}
        visualSmokeStage="text-control"
        replace("","Zoë 0020");until{vm.state!!.values[vm.selected]==listOf("Zoë 0020")}
        visualSmokeStage="review-and-prepare"
        click("Review visual replacement");until{vm.state?.phase==FormPhase.READY};check(vm.preview!=null&&!vm.state!!.confirmed);confirm();until{vm.state?.confirmed==true}
        visualSmokeStage="actual-cancel"
        click("Save visual replacement copy");until{uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui")==true}
        ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand("input keyevent 4")).use{it.readBytes()};until{vm.state?.phase==FormPhase.READY};check(saved==null&&!vm.state!!.confirmed)
        visualSmokeStage="actual-save"
        confirm();until{vm.state?.confirmed==true};click("Save visual replacement copy");destination();until{saved!=null&&vm.state?.message=="PDF copy saved."}
        val collected=File(app.getExternalFilesDir(null),"m24-visual-evidence").apply{mkdirs()};File(collected,"m24-visual-optimized-ui.pdf").writeBytes(app.contentResolver.openInputStream(saved!!)!!.use{it.readBytes()})
        check(MessageDigest.isEqual(before,MessageDigest.getInstance("SHA-256").digest(file.readBytes())))
        val originalDestination=saved!!;val destinationBytes=app.contentResolver.openInputStream(originalDestination)!!.use{it.readBytes()}
        visualSmokeStage="stale-registered-callback"
        var code=0
        runOnMainSync{val state=vm.state!!;vm.confirm(state.owner,state.epoch,state.review!!.id,true);check(vm.beginSave(state.owner,state.epoch,state.review.id));val bundle=Bundle();activity.activityResultRegistry.onSaveInstanceState(bundle);val keys=bundle.getStringArrayList("KEY_COMPONENT_ACTIVITY_REGISTERED_KEYS")!!;val codes=bundle.getIntegerArrayList("KEY_COMPONENT_ACTIVITY_REGISTERED_RCS")!!;val key="text-pdf:"+vm.activityResultOwner;code=codes[keys.indexOf(key)];val launched=bundle.getStringArrayList("KEY_COMPONENT_ACTIVITY_LAUNCHED_KEYS")?:arrayListOf();launched.add(key);bundle.putStringArrayList("KEY_COMPONENT_ACTIVITY_LAUNCHED_KEYS",launched);activity.activityResultRegistry.onRestoreInstanceState(bundle);vm.cancelWork();vm.chooseMode(PdfEditMode.NATIVE);val fresh=vm.state!!;check(activity.activityResultRegistry.dispatchResult(code,Activity.RESULT_OK,Intent().setData(originalDestination)));check(vm.state==fresh&&vm.mode==PdfEditMode.NATIVE&&vm.visualReview==null)}
        check(destinationBytes.contentEquals(app.contentResolver.openInputStream(originalDestination)!!.use{it.readBytes()}));android.provider.DocumentsContract.deleteDocument(app.contentResolver,originalDestination);saved=null
        check(vm.state!!.model==null)
        // Engine coverage in the actual optimized package, using independently frozen expected values.
        visualSmokeStage="independent-frozen-engine"
        val expected=org.json.JSONObject(app.assets.open("m24visual/expected.json").bufferedReader().use{it.readText()})
        val array=expected.getJSONArray("supported");val supported=(0 until array.length()).map{array.getString(it)}+listOf("mixed-two-pages.pdf","image-ocr.pdf")
        for(name in supported){
            val source=File(dir,name).apply{writeBytes(app.assets.open("m24visual/$name").use{it.readBytes()})};val scratch=File(dir,"scratch").apply{mkdirs()};val m=VisualPdfEngine.inspect(source,scratch);val out=File(dir,"visual-$name");VisualPdfEngine.prepare(source,out,scratch,m,VisualEdit(if(name=="mixed-two-pages.pdf")1 else 0,DEFAULT_VISUAL_REGION,"Zoë 0020"));check(VisualPdfEngine.inspect(out,scratch)==m);out.copyTo(File(collected,"m24-visual-optimized-$name"),true)
        }
        val declined=expected.getJSONArray("declined");for(n in 0 until declined.length()){
            val source=File(dir,"declined.pdf").apply{writeBytes(app.assets.open("m24visual/"+declined.getString(n)).use{it.readBytes()})};var refused=false;try{VisualPdfEngine.inspect(source,File(dir,"scratch"))}catch(_:Exception){refused=true};check(refused)
        }
        runOnMainSync{vm.background(false);check(vm.state!!.model==null);vm.setActive(true);vm.close()}
        visualSmokeStage="visual-save-protections"
        kotlinx.coroutines.runBlocking { FormWork.run {
            val source=FormHandle.capture(app,Uri.fromFile(file)){}
            try {
                val m=VisualPdfEngine.inspect(source.input,source.scratch);val copy=source.output();VisualPdfEngine.prepare(source.input,copy,source.scratch,m,VisualEdit(0,DEFAULT_VISUAL_REGION,"Zoë 0020"))
                var refused=false
                try{FormDestination.write(source,copy,Uri.parse("content://synthetic/alias"),{ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_WRITE)}){}}catch(e:FormException){refused=e.reason==FormFailure.DESTINATION};check(refused)
                val target=File(dir,"existing.pdf").apply{writeText("KEEP")};refused=false
                try{FormDestination.write(source,copy,Uri.parse("content://synthetic/existing"),{ParcelFileDescriptor.open(target,ParcelFileDescriptor.MODE_READ_WRITE)}){}}catch(e:FormException){refused=e.reason==FormFailure.DESTINATION};check(refused&&target.readText()=="KEEP")
                var opened=false;try{FormDestination.write(source,copy,Uri.parse("content://synthetic/cancelled"),{opened=true;null}){throw kotlinx.coroutines.CancellationException()};error("Expected cancellation")}catch(_:kotlinx.coroutines.CancellationException){};check(!opened)
                val retired=File(dir,"retired.pdf");check(file.renameTo(retired));file.writeText("NEW SOURCE");refused=false
                try{FormDestination.write(source,copy,Uri.parse("content://synthetic/replacement"),{opened=true;null}){}}catch(e:FormException){refused=e.reason==FormFailure.SOURCE_CHANGED};check(refused&&!opened&&file.readText()=="NEW SOURCE");check(MessageDigest.isEqual(before,MessageDigest.getInstance("SHA-256").digest(retired.readBytes())))
            }finally{source.close()}
        }}
    }catch(t:Throwable){
        visualSmokeStage+=":"+(vm.state?.phase?.name?:"closed")+":"+(FormFailure.entries.firstOrNull{it.message==vm.state?.message}?.name?:"none")
        runCatching{val evidence=File(app.getExternalFilesDir(null),"m24-visual-evidence").apply{mkdirs()};val bitmap=uiAutomation.takeScreenshot();File(evidence,"m24-visual-optimized-failure.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
        throw t
    }finally{removeMonitor(monitor);saved?.let{runCatching{android.provider.DocumentsContract.deleteDocument(app.contentResolver,it)}};runOnMainSync{vm.onSavedForTest=null;vm.close();activity.finish()};dir.deleteRecursively()}
}
