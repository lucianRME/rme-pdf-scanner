package org.synapseworks.pageharbor.forms

import android.app.*
import android.content.Intent
import android.net.Uri
import android.os.*
import android.view.accessibility.AccessibilityNodeInfo as Node
import java.io.File
import java.security.MessageDigest
import org.synapseworks.pageharbor.MainActivity

/** Fixed synthetic evidence only. This class/assets are absent from production. */
internal var formSmokeStage="input"
internal fun Instrumentation.formFirstOffline(){
    val app=targetContext.applicationContext as Application
    val dir=File(app.cacheDir,"m23-optimized").apply{mkdirs()}
    val file=File(dir,"input.pdf").apply{writeBytes(app.assets.open("compatible.pdf").use{it.readBytes()})}
    val before=MessageDigest.getInstance("SHA-256").digest(file.readBytes())
    val activity=startActivitySync(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    val vm=activity.forms;var saved:Uri?=null
    val monitor=object:Instrumentation.ActivityMonitor(){override fun onStartActivity(intent:Intent):Instrumentation.ActivityResult?=if(intent.action==Intent.ACTION_GET_CONTENT){check(intent.type=="application/pdf");Instrumentation.ActivityResult(Activity.RESULT_OK,Intent().setData(Uri.fromFile(file)))}else null}
    fun until(block:()->Boolean){val end=SystemClock.uptimeMillis()+40000;while(SystemClock.uptimeMillis()<end){if(block())return;SystemClock.sleep(100)};error("Form validation timeout")}
    fun node(predicate:(Node)->Boolean):Node?{val root=uiAutomation.rootInActiveWindow?:return null;val q=java.util.ArrayDeque<Node>();q.add(root);var n=0;while(q.isNotEmpty()&&++n<=1024){val x=q.removeFirst();if(predicate(x))return x;for(i in 0 until x.childCount)x.getChild(i)?.let(q::addLast)};return null}
    fun activate(n:Node?):Boolean{var x=n;repeat(8){val p=x?:return false;if(p.isClickable&&p.isEnabled)return p.performAction(Node.ACTION_CLICK);x=p.parent};return false}
    fun scroll(back:Boolean=false):Boolean{val moved=node{val r=android.graphics.Rect();it.getBoundsInScreen(r);it.isScrollable&&r.height()>r.width()}?.performAction(if(back)Node.ACTION_SCROLL_BACKWARD else Node.ACTION_SCROLL_FORWARD)==true;waitForIdleSync();SystemClock.sleep(150);return moved}
    fun click(text:String,done:()->Boolean={false}){var back=false;until{if(done()||activate(node{it.text?.toString()?.equals(text,true)==true||it.contentDescription?.toString()?.equals(text,true)==true})){waitForIdleSync();SystemClock.sleep(150);true}else{if(!scroll(back))back=!back;false}}}
    fun confirm(){until{if(node{it.text?.toString()=="I checked the values, choices and all listed pages."}!=null)true else{scroll();false}}
        until{if(activate(node{it.isCheckable&&!it.isChecked}))true else{scroll();false}}}
    fun replace(old:String,new:String){repeat(14){scroll(true);SystemClock.sleep(20)};until{val x=node{it.isEditable&&it.text?.toString()?.contains(old)==true};if(x!=null)x.performAction(Node.ACTION_SET_TEXT,Bundle().apply{putCharSequence(Node.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,new)})else{scroll();false}}}
    fun destination(){until{val x=node{it.contentDescription?.toString() in listOf("Show roots","Show navigation drawer")};x?.performAction(Node.ACTION_CLICK)==true};until{var x=node{n->if(n.text?.toString()!="Downloads")false else{var parent:Node?=n;var found=false;repeat(12){if(parent?.viewIdResourceName?.endsWith("/roots_list")==true)found=true;parent=parent?.parent};found}}
            if(activate(x))true else{val roots=node{it.viewIdResourceName?.endsWith("/roots_list")==true};if(roots?.performAction(Node.ACTION_SCROLL_FORWARD)==false)roots.performAction(Node.ACTION_SCROLL_BACKWARD);false}};click("Save")}
    addMonitor(monitor)
    try{
        runOnMainSync{vm.onSavedForTest={saved=it}}
        formSmokeStage="reachable-input"
        formSmokeStage="more";click("More");formSmokeStage="entry";click("Fill PDF form"){vm.state!=null};formSmokeStage="picker";click("Choose PDF form");formSmokeStage="load";until{vm.state?.phase==FormPhase.EDIT||vm.state?.message!=null};check(vm.state?.phase==FormPhase.EDIT)
        check(vm.state!!.model!!.fields.size==7&&vm.state!!.values["reference"]==listOf("KEEP 0010"))
        formSmokeStage="text-control"
        replace("Alex 0012","Zoë €123");until{vm.state!!.values["customer.name"]==listOf("Zoë €123")}
        formSmokeStage="review-and-prepare"
        click("Review all values"){vm.state?.phase==FormPhase.REVIEW};until{vm.state?.phase==FormPhase.REVIEW};confirm();until{vm.state?.confirmed==true};click("Prepare filled PDF");until{vm.state?.phase==FormPhase.READY}
        formSmokeStage="actual-cancel"
        click("Save PDF copy");until{uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui")==true}
        ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand("input keyevent 4")).use{it.readBytes()};until{vm.state?.phase==FormPhase.READY};check(saved==null&&!vm.state!!.confirmed)
        formSmokeStage="actual-save"
        confirm();until{vm.state?.confirmed==true};click("Save PDF copy");destination();until{saved!=null&&vm.state?.message=="PDF copy saved."}
        val collected=File(app.getExternalFilesDir(null),"m23-evidence").apply{mkdirs()};File(collected,"m23-optimized-ui.pdf").writeBytes(app.contentResolver.openInputStream(saved!!)!!.use{it.readBytes()})
        check(MessageDigest.isEqual(before,MessageDigest.getInstance("SHA-256").digest(file.readBytes())))
        android.provider.DocumentsContract.deleteDocument(app.contentResolver,saved!!);saved=null
        click("Correct values");until{vm.state?.phase==FormPhase.EDIT};check(vm.state!!.review==null)
        // Engine coverage in the actual optimized package, using independently frozen expected values.
        formSmokeStage="independent-frozen-engine"
        val expected=org.json.JSONObject(app.assets.open("expected.json").bufferedReader().use{it.readText()})
        val values=expected.getJSONObject("filled").let{v->v.keys().asSequence().associateWith{k->v.getJSONArray(k).let{a->(0 until a.length()).map{a.getString(it)}}}}
        for(name in listOf("compatible.pdf","rotated.pdf")){
            val source=File(dir,name).apply{writeBytes(app.assets.open(name).use{it.readBytes()})};val scratch=File(dir,"scratch").apply{mkdirs()};val m=FormPdfEngine.inspect(source,scratch);val out=File(dir,"filled-$name");FormPdfEngine.prepare(source,out,scratch,m,FormReview.create(m,values)!!);check(FormPdfEngine.inspect(out,scratch).initial()==values);out.copyTo(File(collected,"m23-optimized-$name"),true)
        }
        val declined=expected.getJSONArray("declined");for(n in 0 until declined.length()){
            val source=File(dir,"declined.pdf").apply{writeBytes(app.assets.open(declined.getString(n)+".pdf").use{it.readBytes()})};var refused=false;try{FormPdfEngine.inspect(source,File(dir,"scratch"))}catch(_:Exception){refused=true};check(refused)
        }
        runOnMainSync{vm.background(false);check(vm.state!!.model==null);vm.setActive(true);vm.close()}
    }catch(t:Throwable){
        formSmokeStage+=":"+(vm.state?.phase?.name?:"closed")+":"+(FormFailure.entries.firstOrNull{it.message==vm.state?.message}?.name?:"none")
        runCatching{val evidence=File(app.getExternalFilesDir(null),"m23-evidence").apply{mkdirs()};val bitmap=uiAutomation.takeScreenshot();File(evidence,"m23-optimized-failure.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
        throw t
    }finally{removeMonitor(monitor);saved?.let{runCatching{android.provider.DocumentsContract.deleteDocument(app.contentResolver,it)}};runOnMainSync{vm.onSavedForTest=null;vm.close();activity.finish()};dir.deleteRecursively()}
}
