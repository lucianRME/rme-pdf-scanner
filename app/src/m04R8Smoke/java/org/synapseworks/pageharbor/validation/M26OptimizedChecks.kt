package org.synapseworks.pageharbor.validation

import android.app.*
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo as Node
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import java.io.File
import org.synapseworks.pageharbor.*
import org.synapseworks.pageharbor.document.*
import org.synapseworks.pageharbor.document.session.*
import org.synapseworks.pageharbor.image.*

/** Fixed synthetic input; this validation source and fixture are absent from production. */
internal var creaseStainSmokeStage="input"
internal fun Instrumentation.creaseStain(){
    val app=targetContext.applicationContext as Application
    val root=File(app.cacheDir,"shared-pdfs/m26-optimized").apply{mkdirs()}
    val source=File(root,"source.png").apply{writeBytes(app.assets.open("m26/combined-source.png").use{it.readBytes()})}
    val before=source.readBytes()
    val activity=startActivitySync(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))as MainActivity
    val vm=ViewModelProvider(activity)[PageHarborSessionViewModel::class.java]
    fun until(block:()->Boolean){val end=SystemClock.uptimeMillis()+40000;while(SystemClock.uptimeMillis()<end){if(block())return;SystemClock.sleep(100)};error("M26 validation timeout")}
    fun node(predicate:(Node)->Boolean):Node?{val q=java.util.ArrayDeque<Node>();uiAutomation.rootInActiveWindow?.let(q::add);var count=0;while(q.isNotEmpty()&&++count<1024){val n=q.removeFirst();if(predicate(n))return n;for(i in 0 until n.childCount)n.getChild(i)?.let(q::add)};return null}
    fun activate(n:Node?):Boolean{if(n?.isVisibleToUser!=true)return false;var x=n;repeat(8){val p=x?:return false;if(p.isClickable&&p.isEnabled)return p.performAction(Node.ACTION_CLICK);x=p.parent};return false}
    fun touchVisibleTool(n:Node?):Boolean {
        if(n==null||vm.historyState.busy)return false
        val row=node{val r=android.graphics.Rect();it.getBoundsInScreen(r);it.isScrollable&&r.width()>r.height()}?:return false
        val visible=android.graphics.Rect();n.getBoundsInScreen(visible);val bounds=android.graphics.Rect();row.getBoundsInScreen(bounds)
        if(!visible.intersect(bounds)||visible.width()<8||visible.height()<8)return false
        val down=SystemClock.uptimeMillis();val x=visible.exactCenterX();val y=visible.exactCenterY()
        for(action in listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP)){
            val event=android.view.MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0)
            try{event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;check(uiAutomation.injectInputEvent(event,true))}finally{event.recycle()}
        }
        waitForIdleSync();SystemClock.sleep(150);return true
    }
    fun swipeToolRow(n:Node?,back:Boolean) {
        if(n==null)return
        val r=android.graphics.Rect();n.getBoundsInScreen(r);if(r.width()<40||r.height()<20)return
        val down=SystemClock.uptimeMillis();val start=r.left+r.width()*(if(back).4f else .7f);val end=r.left+r.width()*(if(back).6f else .5f);val y=r.exactCenterY()
        fun event(action:Int,x:Float){val e=android.view.MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0);try{e.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;check(uiAutomation.injectInputEvent(e,true))}finally{e.recycle()}}
        event(android.view.MotionEvent.ACTION_DOWN,start)
        for(i in 1..10){SystemClock.sleep(20);event(android.view.MotionEvent.ACTION_MOVE,start+(end-start)*i/10)}
        event(android.view.MotionEvent.ACTION_UP,end);waitForIdleSync()
    }
    fun click(text:String,prefix:Boolean=false){creaseStainSmokeStage="control:"+text;var back=false;until{val n=node{val a=it.text?.toString();val b=it.contentDescription?.toString();if(prefix)a?.startsWith(text)==true||b?.startsWith(text)==true else a==text||b==text}
        if(activate(n)||(text==app.getString(R.string.crease_stain_title)&&touchVisibleTool(n))){waitForIdleSync();SystemClock.sleep(150);true}else{val scroller=node{val r=android.graphics.Rect();it.getBoundsInScreen(r);it.isScrollable&&if(text==app.getString(R.string.crease_stain_title))r.width()>r.height()else r.height()>r.width()};if(text==app.getString(R.string.crease_stain_title)){
            val actions=scroller?.actionList.orEmpty().map{it.id}
            if(!back&&Node.ACTION_SCROLL_FORWARD !in actions)back=true
            if(back&&Node.ACTION_SCROLL_BACKWARD !in actions)back=false
            swipeToolRow(scroller,back)
        }else if(scroller?.performAction(if(back)Node.ACTION_SCROLL_BACKWARD else Node.ACTION_SCROLL_FORWARD)!=true)back=!back;waitForIdleSync();false}}}
    try{
        runOnMainSync{activity.installDocumentSessionForTest(DocumentSession(listOf(DocumentPage(DocumentPageId(1),
            DocumentResource(FileProvider.getUriForFile(app,"${app.packageName}.fileprovider",source).toString(),DocumentResourceOwnership.USER_OR_EXTERNAL),DocumentSourceCategory.SCAN,
            imageMetadata=DocumentImageMetadata(source.length(),512,384),adjustments=ManualImageAdjustments(-10,15)))))}
        val original=vm.documentSession
        click("Edit");click("Crease & stain removal");click("Strong")
        creaseStainSmokeStage="preview-ready"
        until{node{it.text?.toString()==app.getString(R.string.scan_preview_loading)}==null}
        check(node{it.text?.toString()==app.getString(R.string.scan_preview_unavailable)}==null)
        val screenshot=uiAutomation.takeScreenshot()
        try{File(app.getExternalFilesDir(null),"m26-evidence").apply{mkdirs()}.let{File(it,"optimized-preview.png").outputStream().use{out->check(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out))}}}finally{screenshot.recycle()}
        click("Show before");click("Cancel");check(vm.documentSession==original)
        click("Crease & stain removal");click("Strong");click(app.getString(R.string.manual_apply));until{vm.documentPages.single().filter==DocumentFilter.CREASE_STAIN_STRONG_V1&&!vm.historyState.busy}
        click("Undo ",true);until{vm.documentSession==original&&!vm.historyState.busy}
        click("Crease & stain removal");click("Strong");click(app.getString(R.string.manual_apply));until{vm.documentPages.single().filter==DocumentFilter.CREASE_STAIN_STRONG_V1&&!vm.historyState.busy}
        creaseStainSmokeStage="frozen-export"
        val snapshot=vm.documentSession
        val pixels=BitmapFactory.decodeFile(source.path);val data=IntArray(pixels.width*pixels.height);pixels.getPixels(data,0,pixels.width,0,0,pixels.width,pixels.height);pixels.recycle()
        val adjusted=DocumentImageFilterEngine.apply(ArgbImage(512,384,data),DocumentFilter.CREASE_STAIN_STRONG_V1,ManualImageAdjustments(-10,15))
        check(!adjusted.pixels.contentEquals(data))
        kotlinx.coroutines.runBlocking {
            val result=recomposeNormalPdf(app,(normalPdfExportPlan(snapshot)as NormalPdfExportPlan.RecomposeFromPages).pages)as NormalPdfRecompositionResult.Ready
            try{File(app.getExternalFilesDir(null),"m26-evidence").apply{mkdirs()}.let{result.file.copyTo(File(it,"optimized.pdf"),true)}}finally{deleteNormalPdfRecomposition(result.file)}
        }
        // A review captured before a source/session mutation may never retarget its Apply.
        creaseStainSmokeStage="stale-review"
        val reviewed=vm.documentPages.single();val revision=vm.documentRevision
        runOnMainSync{vm.rotatePageClockwise(1);check(!vm.applyReviewedCreaseStain(reviewed,revision,DocumentFilter.CREASE_STAIN_LOW_V1,vm.documentOwnerToken))}
        check(before.contentEquals(source.readBytes()))
    }catch(error:Throwable){
        runCatching{val bitmap=uiAutomation.takeScreenshot();try{File(app.getExternalFilesDir(null),"m26-evidence").apply{mkdirs()}.let{File(it,"optimized-failure.png").outputStream().use{out->bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out)}}}finally{bitmap.recycle()}}
        throw error
    }finally{runOnMainSync{vm.clearScan();activity.finish()};root.deleteRecursively()}
}
