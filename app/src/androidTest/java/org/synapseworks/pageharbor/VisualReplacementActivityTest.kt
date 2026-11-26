package org.synapseworks.pageharbor

import android.app.*
import android.content.Intent
import android.net.Uri
import android.os.*
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.lifecycle.*
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID
import org.synapseworks.pageharbor.forms.*
import org.synapseworks.pageharbor.pdftext.*
import org.synapseworks.pageharbor.security.*
import org.synapseworks.pageharbor.ui.PageHarborScreen

class VisualReplacementActivityTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val i=InstrumentationRegistry.getInstrumentation();private val app=i.targetContext.applicationContext as Application
    private val model get()=compose.activity.pdfText
    private lateinit var file:File;private var saved:Uri?=null
    private fun unlock(){val lock=ViewModelProvider(compose.activity)[AppLockViewModel::class.java];if(lock.state.value.phase==AppLockPhase.LOCKED){val t=lock.beginAuthentication(AppLockAuthenticationAvailability.AVAILABLE)!!;lock.onAuthenticationResult(t.id,AppLockAuthenticationResult.Success)};lock.disableAfterAuthentication()}
    @Before fun setup(){check(app.packageName.endsWith(".phase2test"));file=File(app.cacheDir,"m24-visual-input-${UUID.randomUUID()}.pdf").apply{writeBytes(i.context.assets.open("m24visual/image.pdf").use{it.readBytes()})}
        compose.runOnIdle{unlock();model.close();ViewModelProvider(compose.activity)[PageHarborSessionViewModel::class.java].screen=PageHarborScreen.Home;model.onSavedForTest={saved=it}}}
    @After fun cleanup(){compose.runOnIdle{unlock();model.close();model.onSavedForTest=null};saved?.let{runCatching{android.provider.DocumentsContract.deleteDocument(app.contentResolver,it)}};file.delete();kotlinx.coroutines.runBlocking{FormWork.run{}};i.waitForIdleSync();kotlinx.coroutines.runBlocking{FormWork.run{}}}
    private fun click(tag:String){compose.onNodeWithTag("text-list").performScrollToNode(hasTestTag(tag));compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled().performClick()}
    private fun text(value:String){compose.onNodeWithTag("text-list").performScrollToNode(hasTestTag("text-edit"));compose.onNodeWithTag("text-edit").performTextReplacement(value)
        compose.runOnIdle{compose.activity.currentFocus?.clearFocus();androidx.core.view.WindowInsetsControllerCompat(compose.activity.window,compose.activity.window.decorView).hide(androidx.core.view.WindowInsetsCompat.Type.ime())}}
    private fun open(){val tools=app.getString(R.string.navigation_tools);compose.onNode((hasText(tools)or hasContentDescription(tools))and hasClickAction()).performClick();compose.onNodeWithTag("tools-grid").performScrollToNode(hasTestTag("open-text"));compose.onNodeWithTag("open-text").assertIsDisplayed().performClick();compose.waitUntil{model.state!=null};click("text-visual-mode");assertEquals(PdfEditMode.VISUAL,model.mode)}
    private fun input(){val monitor=object:Instrumentation.ActivityMonitor(){override fun onStartActivity(intent:Intent):Instrumentation.ActivityResult?{if(intent.action==Intent.ACTION_GET_CONTENT){assertEquals("application/pdf",intent.type);assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE));return Instrumentation.ActivityResult(Activity.RESULT_OK,Intent().setData(Uri.fromFile(file)))};return null}}
        i.addMonitor(monitor);try{click("text-input");compose.waitUntil(30_000){model.state?.phase==FormPhase.EDIT||model.state?.message!=null};assertEquals(FormPhase.EDIT,model.state!!.phase)}finally{i.removeMonitor(monitor)}}
    private fun select(){compose.waitUntil(30_000){model.preview!=null};click("visual-select-region");assertEquals(DEFAULT_VISUAL_REGION,model.visualRegion)}
    private fun prepare(){click("text-review");compose.waitUntil(30_000){model.state?.phase==FormPhase.READY||model.state?.message!=null};assertEquals(FormPhase.READY,model.state!!.phase);assertNotNull(model.preview);assertFalse(model.state!!.confirmed);click("text-confirm")}
    private fun node(predicate:(AccessibilityNodeInfo)->Boolean):AccessibilityNodeInfo?{val root=i.uiAutomation.rootInActiveWindow?:return null;val queue=java.util.ArrayDeque<AccessibilityNodeInfo>();queue.add(root);var count=0;while(queue.isNotEmpty()&&count++<1024){val n=queue.removeFirst();if(predicate(n))return n;for(k in 0 until n.childCount)n.getChild(k)?.let(queue::addLast)};return null}
    private fun systemClick(text:String,roots:Boolean=false){val end=SystemClock.uptimeMillis()+20_000
        fun inRoots(n:AccessibilityNodeInfo):Boolean{var p:AccessibilityNodeInfo?=n;while(p!=null){if(p.viewIdResourceName?.endsWith("/roots_list")==true)return true;p=p.parent};return false}
        while(SystemClock.uptimeMillis()<end){var n=node{it.text?.toString()?.equals(text,true)==true&&(!roots||inRoots(it))};while(n!=null&&!n.isClickable)n=n.parent;if(n?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true)return
            if(roots)node{it.viewIdResourceName?.endsWith("/roots_list")==true}?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);SystemClock.sleep(100)};throw AssertionError("Synthetic system action unavailable")}
    private fun destination(){val end=SystemClock.uptimeMillis()+15_000;while(SystemClock.uptimeMillis()<end){val n=node{it.contentDescription?.toString() in listOf("Show roots","Show navigation drawer")};if(n!=null){n.performAction(AccessibilityNodeInfo.ACTION_CLICK);break};SystemClock.sleep(100)};systemClick("Downloads",true);systemClick("Save")}
    @Test fun reachableVisualRegionTextPreviewReviewAndActualSeparateSave(){
        val original=file.readBytes();open();input();select();text("Zoë 0020");prepare();assertEquals(VisualEdit(0,DEFAULT_VISUAL_REGION,"Zoë 0020"),model.visualReview)
        compose.onNodeWithTag("text-list").performScrollToNode(hasTestTag("text-reviewed"));compose.onNodeWithTag("text-reviewed").assertTextContains("Visual replacement",substring=true)
        File(app.cacheDir,"m24-visual-review.png").outputStream().use{compose.onNodeWithTag("text-screen").captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        click("text-save");destination();compose.waitUntil(30_000){model.state?.message=="PDF copy saved."};assertNotNull(saved);val out=File(app.cacheDir,"m24-visual-ui.pdf").apply{writeBytes(app.contentResolver.openInputStream(saved!!)!!.use{it.readBytes()})};assertArrayEquals(original,file.readBytes());assertFalse(model.state!!.confirmed)
        com.tom_roush.pdfbox.pdmodel.PDDocument.load(out).use{assertTrue(com.tom_roush.pdfbox.text.PDFTextStripper().getText(it).contains("Zoë 0020"))}
        click("text-correct");assertEquals(FormPhase.EDIT,model.state!!.phase);assertNull(model.state!!.review);assertNull(model.visualReview)
    }
    @Test fun actualSaveCancellationWritesNothingAndAllowsExplicitRetry(){
        var stage="open"
        try{open();input();select();text("Zoë 0020");prepare();stage="launch";click("text-save")
            compose.waitUntil(20_000){i.uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui")==true}
            stage="back";ParcelFileDescriptor.AutoCloseInputStream(i.uiAutomation.executeShellCommand("input keyevent 4")).use{it.readBytes()};SystemClock.sleep(500)
            // The first Back can dismiss the system filename IME. Only send a second Back if
            // the document dialog still owns the foreground; never send it to the app.
            if(model.state?.phase==FormPhase.DESTINATION&&i.uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui")==true)ParcelFileDescriptor.AutoCloseInputStream(i.uiAutomation.executeShellCommand("input keyevent 4")).use{it.readBytes()}
            compose.waitUntil(20_000){model.state?.phase==FormPhase.READY};assertNull(saved);assertTrue(model.state!!.message!!.contains("cancelled"));assertFalse(model.state!!.confirmed)
            stage="retry";click("text-confirm");click("text-save");destination();compose.waitUntil(30_000){saved!=null}
        }catch(t:Throwable){File(app.cacheDir,"m24-visual-cancel-failure.png").outputStream().use{out->i.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,out)};throw AssertionError("Visual Save stage $stage, phase ${model.state?.phase}",t)}
    }
    @Test fun recreateKeepsMemoryButSavedStateBackgroundAndLockExcludeVisualText(){open();input();select();val secret="M24VisualEphemeralSyntheticOnlyQ0";text(secret);val bundle=Bundle();compose.runOnIdle{i.callActivityOnSaveInstanceState(compose.activity,bundle)};val parcel=Parcel.obtain();val bytes=try{bundle.writeToParcel(parcel,0);parcel.marshall()}finally{parcel.recycle()}
        for(charset in listOf(Charsets.UTF_8,Charsets.UTF_16LE,Charsets.UTF_16BE)){val needle=secret.toByteArray(charset);assertFalse((0..(bytes.size-needle.size).coerceAtLeast(-1)).any{start->needle.indices.all{bytes[start+it]==needle[it]}})}
        compose.activityRule.scenario.recreate();assertEquals(listOf(secret),model.state!!.values[model.selected]);compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED);assertNull(model.state!!.model);compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED);input()
        compose.runOnIdle{val lock=ViewModelProvider(compose.activity)[AppLockViewModel::class.java];lock.setup(AutoLockTimeout.IMMEDIATELY,AppLockAuthenticationAvailability.AVAILABLE);lock.lockNow()};compose.waitUntil{model.state==null};compose.onAllNodesWithTag("text-screen").assertCountEquals(0)
    }
    @Test fun registeredStaleSaveAfterRecreationAndLockCannotOpenDestination(){open();input();select();text("Zoë 0020");prepare();var code=0
        compose.runOnIdle{val s=model.state!!;assertTrue(model.beginSave(s.owner,s.epoch,s.review!!.id));val b=Bundle();compose.activity.activityResultRegistry.onSaveInstanceState(b);val keys=b.getStringArrayList("KEY_COMPONENT_ACTIVITY_REGISTERED_KEYS")!!;val codes=b.getIntegerArrayList("KEY_COMPONENT_ACTIVITY_REGISTERED_RCS")!!;val key="text-pdf:"+model.activityResultOwner;code=codes[keys.indexOf(key)];val launched=b.getStringArrayList("KEY_COMPONENT_ACTIVITY_LAUNCHED_KEYS")?:arrayListOf();launched.add(key);b.putStringArrayList("KEY_COMPONENT_ACTIVITY_LAUNCHED_KEYS",launched);compose.activity.activityResultRegistry.onRestoreInstanceState(b)}
        compose.activityRule.scenario.recreate();assertEquals(FormPhase.DESTINATION,model.state!!.phase);val target=File(app.cacheDir,"m24-visual-stale.pdf").apply{writeText("UNCHANGED")}
        compose.runOnIdle{val lock=ViewModelProvider(compose.activity)[AppLockViewModel::class.java];lock.setup(AutoLockTimeout.IMMEDIATELY,AppLockAuthenticationAvailability.AVAILABLE);lock.lockNow()};compose.waitUntil{model.state==null};compose.runOnIdle{assertTrue(compose.activity.activityResultRegistry.dispatchResult(code,Activity.RESULT_OK,Intent().setData(Uri.fromFile(target))))};assertEquals("UNCHANGED",target.readText());target.delete();assertNull(saved)
    }
    @Test fun originalAliasAndOpaqueProviderCallbackFailClearlyWithoutMutation(){open();input();select();text("Zoë 0020");prepare();val bytes=file.readBytes();val monitor=object:Instrumentation.ActivityMonitor(){override fun onStartActivity(intent:Intent):Instrumentation.ActivityResult?=if(intent.action==Intent.ACTION_CREATE_DOCUMENT)Instrumentation.ActivityResult(Activity.RESULT_OK,Intent().setData(Uri.fromFile(file)))else null};i.addMonitor(monitor);try{click("text-save");compose.waitUntil(20_000){model.state?.message==FormFailure.DESTINATION.message};assertArrayEquals(bytes,file.readBytes());assertNull(saved)}finally{i.removeMonitor(monitor)}}
    @Test fun dragAndAccessibleRegionControlsCancelWithoutChangingOriginal(){val before=file.readBytes();open();input();compose.waitUntil(30_000){model.preview!=null};compose.onNodeWithTag("text-list").performScrollToNode(hasTestTag("visual-region-page"));compose.onNodeWithTag("visual-region-page").performTouchInput{down(androidx.compose.ui.geometry.Offset(width*.15f,height*.30f));moveTo(androidx.compose.ui.geometry.Offset(width*.85f,height*.65f),500);up()};assertNotNull(model.visualRegion)
        click("visual-select-region");compose.onNodeWithTag("text-list").performScrollToNode(hasTestTag("visual-left"));compose.onNodeWithTag("visual-left").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress){it(.10f)};assertEquals(.10f,model.visualRegion!!.left,.001f);click("visual-cancel");assertEquals(PdfEditMode.NATIVE,model.mode);assertNull(model.visualRegion);assertNull(model.visualDocument);assertNull(saved);assertArrayEquals(before,file.readBytes())}

    @Test fun pageNavigationBindsReviewedCopyToSecondPage(){file.writeBytes(i.context.assets.open("m24visual/mixed-two-pages.pdf").use{it.readBytes()});val before=file.readBytes();open();input();compose.waitUntil(30_000){model.preview!=null};click("visual-next");assertEquals(1,model.visualPage);assertNull(model.visualRegion);select();text("Zoë 0020");prepare();assertEquals(1,model.visualReview!!.page);click("text-save");destination();compose.waitUntil(30_000){saved!=null};File(app.cacheDir,"m24-visual-two-page-ui.pdf").writeBytes(app.contentResolver.openInputStream(saved!!)!!.use{it.readBytes()});assertArrayEquals(before,file.readBytes())}

}
