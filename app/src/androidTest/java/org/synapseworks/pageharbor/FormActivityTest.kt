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
import org.synapseworks.pageharbor.security.*
import org.synapseworks.pageharbor.ui.PageHarborScreen

class FormActivityTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val i=InstrumentationRegistry.getInstrumentation();private val app=i.targetContext.applicationContext as Application
    private val model get()=compose.activity.forms
    private lateinit var file:File;private var saved:Uri?=null
    private fun unlock(){val lock=ViewModelProvider(compose.activity)[AppLockViewModel::class.java];if(lock.state.value.phase==AppLockPhase.LOCKED){val t=lock.beginAuthentication(AppLockAuthenticationAvailability.AVAILABLE)!!;lock.onAuthenticationResult(t.id,AppLockAuthenticationResult.Success)};lock.disableAfterAuthentication()}
    @Before fun setup(){check(app.packageName.endsWith(".phase2test"));file=File(app.cacheDir,"m23-input-${UUID.randomUUID()}.pdf").apply{writeBytes(i.context.assets.open("m23/compatible.pdf").use{it.readBytes()})}
        compose.runOnIdle{unlock();model.close();ViewModelProvider(compose.activity)[PageHarborSessionViewModel::class.java].screen=PageHarborScreen.Home;model.onSavedForTest={saved=it}}}
    @After fun cleanup(){compose.runOnIdle{unlock();model.close();model.onSavedForTest=null};saved?.let{runCatching{android.provider.DocumentsContract.deleteDocument(app.contentResolver,it)}};file.delete()}
    private fun click(tag:String){compose.onNodeWithTag("form-list").performScrollToNode(hasTestTag(tag));compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled().performClick()}
    private fun text(id:String,value:String){compose.onNodeWithTag("form-list").performScrollToNode(hasTestTag("form-text-$id"));compose.onNodeWithTag("form-text-$id").performTextReplacement(value)
        compose.runOnIdle{compose.activity.currentFocus?.clearFocus();androidx.core.view.WindowInsetsControllerCompat(compose.activity.window,compose.activity.window.decorView).hide(androidx.core.view.WindowInsetsCompat.Type.ime())}}
    private fun options(id:String,vararg selections:Int){click("form-options-$id");for(index in selections)compose.onNodeWithTag("form-option-$index").performScrollTo().performClick();compose.onNodeWithTag("form-options-done").performClick()}
    private fun open(){val tools=app.getString(R.string.navigation_tools);compose.onNode((hasText(tools)or hasContentDescription(tools))and hasClickAction()).performClick();compose.onNodeWithTag("tools-grid").performScrollToNode(hasTestTag("open-forms"));compose.onNodeWithTag("open-forms").assertIsDisplayed().performClick();compose.waitUntil{model.state!=null}}
    private fun input(){val monitor=object:Instrumentation.ActivityMonitor(){override fun onStartActivity(intent:Intent):Instrumentation.ActivityResult?{if(intent.action==Intent.ACTION_GET_CONTENT){assertEquals("application/pdf",intent.type);assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE));return Instrumentation.ActivityResult(Activity.RESULT_OK,Intent().setData(Uri.fromFile(file)))};return null}}
        i.addMonitor(monitor);try{click("form-input");compose.waitUntil(30_000){model.state?.phase==FormPhase.EDIT||model.state?.message!=null};assertEquals(FormPhase.EDIT,model.state!!.phase)}finally{i.removeMonitor(monitor)}}
    private fun prepare(){click("form-review");assertEquals(FormPhase.REVIEW,model.state!!.phase);click("form-confirm");click("form-prepare");compose.waitUntil(30_000){model.state?.phase==FormPhase.READY}}
    private fun node(predicate:(AccessibilityNodeInfo)->Boolean):AccessibilityNodeInfo?{val root=i.uiAutomation.rootInActiveWindow?:return null;val queue=java.util.ArrayDeque<AccessibilityNodeInfo>();queue.add(root);var count=0;while(queue.isNotEmpty()&&count++<1024){val n=queue.removeFirst();if(predicate(n))return n;for(k in 0 until n.childCount)n.getChild(k)?.let(queue::addLast)};return null}
    private fun systemClick(text:String,roots:Boolean=false){val end=SystemClock.uptimeMillis()+20_000
        fun inRoots(n:AccessibilityNodeInfo):Boolean{var p:AccessibilityNodeInfo?=n;while(p!=null){if(p.viewIdResourceName?.endsWith("/roots_list")==true)return true;p=p.parent};return false}
        while(SystemClock.uptimeMillis()<end){var n=node{it.text?.toString()?.equals(text,true)==true&&(!roots||inRoots(it))};while(n!=null&&!n.isClickable)n=n.parent;if(n?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true)return
            if(roots)node{it.viewIdResourceName?.endsWith("/roots_list")==true}?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);SystemClock.sleep(100)};throw AssertionError("Synthetic system action unavailable")}
    private fun destination(){val end=SystemClock.uptimeMillis()+15_000;while(SystemClock.uptimeMillis()<end){val n=node{it.contentDescription?.toString() in listOf("Show roots","Show navigation drawer")};if(n!=null){n.performAction(AccessibilityNodeInfo.ACTION_CLICK);break};SystemClock.sleep(100)};systemClick("Downloads",true);systemClick("Save")}
    @Test fun reachableAllFieldControlsReviewAndActualLocalSavePreserveEditableValues(){
        val original=file.readBytes();open();input();assertEquals(listOf("Alex 0012"),model.state!!.values["customer.name"]);text("customer.name","Zoë €123");text("notes","Line 1\nCafé €20");click("form-check-consent");options("contact",1);options("country",0);options("topics",0,1,2)
        assertEquals(listOf("KEEP 0010"),model.state!!.values["reference"]);prepare();compose.onNodeWithTag("form-list").performScrollToNode(hasTestTag("form-reviewed-customer.name"));compose.onNodeWithTag("form-reviewed-customer.name").assertTextEquals("Zoë €123")
        File(app.cacheDir,"m23-review.png").outputStream().use{compose.onNodeWithTag("form-screen").captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        click("form-save");destination();compose.waitUntil(30_000){model.state?.message=="PDF copy saved."};assertNotNull(saved);assertTrue(FormDestination.supports(app,saved!!));val out=app.contentResolver.openInputStream(saved!!)!!.use{it.readBytes()};File(app.cacheDir,"m23-ui.pdf").writeBytes(out);assertArrayEquals(original,file.readBytes());assertFalse(model.state!!.confirmed)
        val expected=org.json.JSONObject(i.context.assets.open("m23/expected.json").bufferedReader().use{it.readText()}).getJSONObject("filled");val actual=model.state!!.review!!.values;for(key in expected.keys()){assertEquals((0 until expected.getJSONArray(key).length()).map{expected.getJSONArray(key).getString(it)},actual[key])}
        click("form-correct");assertEquals(FormPhase.EDIT,model.state!!.phase);assertNull(model.state!!.review)
    }
    @Test fun actualSaveCancellationWritesNothingAndAllowsExplicitRetry(){open();input();prepare();click("form-save");compose.waitUntil(20_000){i.uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui")==true};ParcelFileDescriptor.AutoCloseInputStream(i.uiAutomation.executeShellCommand("input keyevent 4")).use{it.readBytes()};compose.waitUntil(20_000){model.state?.phase==FormPhase.READY};assertNull(saved);assertTrue(model.state!!.message!!.contains("cancelled"));assertFalse(model.state!!.confirmed)
        click("form-confirm");click("form-save");destination();compose.waitUntil(30_000){saved!=null}}
    @Test fun recreateKeepsMemoryButSavedStateBackgroundAndLockExcludeFormText(){open();input();val secret="M23EphemeralSyntheticOnlyQ0";text("customer.name",secret);val bundle=Bundle();compose.runOnIdle{i.callActivityOnSaveInstanceState(compose.activity,bundle)};val parcel=Parcel.obtain();val bytes=try{bundle.writeToParcel(parcel,0);parcel.marshall()}finally{parcel.recycle()}
        for(charset in listOf(Charsets.UTF_8,Charsets.UTF_16LE,Charsets.UTF_16BE)){val needle=secret.toByteArray(charset);assertFalse((0..(bytes.size-needle.size).coerceAtLeast(-1)).any{start->needle.indices.all{bytes[start+it]==needle[it]}})}
        compose.activityRule.scenario.recreate();assertEquals(listOf(secret),model.state!!.values["customer.name"]);compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED);assertNull(model.state!!.model);compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED);input()
        compose.runOnIdle{val lock=ViewModelProvider(compose.activity)[AppLockViewModel::class.java];lock.setup(AutoLockTimeout.IMMEDIATELY,AppLockAuthenticationAvailability.AVAILABLE);lock.lockNow()};compose.waitUntil{model.state==null};compose.onAllNodesWithTag("form-screen").assertCountEquals(0)
    }
    @Test fun registeredStaleSaveAfterRecreationAndLockCannotOpenDestination(){open();input();prepare();var code=0
        compose.runOnIdle{val s=model.state!!;assertTrue(model.beginSave(s.owner,s.epoch,s.review!!.id));val b=Bundle();compose.activity.activityResultRegistry.onSaveInstanceState(b);val keys=b.getStringArrayList("KEY_COMPONENT_ACTIVITY_REGISTERED_KEYS")!!;val codes=b.getIntegerArrayList("KEY_COMPONENT_ACTIVITY_REGISTERED_RCS")!!;val key="form-pdf:"+model.activityResultOwner;code=codes[keys.indexOf(key)];val launched=b.getStringArrayList("KEY_COMPONENT_ACTIVITY_LAUNCHED_KEYS")?:arrayListOf();launched.add(key);b.putStringArrayList("KEY_COMPONENT_ACTIVITY_LAUNCHED_KEYS",launched);compose.activity.activityResultRegistry.onRestoreInstanceState(b)}
        compose.activityRule.scenario.recreate();assertEquals(FormPhase.DESTINATION,model.state!!.phase);val target=File(app.cacheDir,"m23-stale.pdf").apply{writeText("UNCHANGED")}
        compose.runOnIdle{val lock=ViewModelProvider(compose.activity)[AppLockViewModel::class.java];lock.setup(AutoLockTimeout.IMMEDIATELY,AppLockAuthenticationAvailability.AVAILABLE);lock.lockNow()};compose.waitUntil{model.state==null};compose.runOnIdle{assertTrue(compose.activity.activityResultRegistry.dispatchResult(code,Activity.RESULT_OK,Intent().setData(Uri.fromFile(target))))};assertEquals("UNCHANGED",target.readText());target.delete();assertNull(saved)
    }
    @Test fun originalAliasAndOpaqueProviderCallbackFailClearlyWithoutMutation(){open();input();prepare();val bytes=file.readBytes();val monitor=object:Instrumentation.ActivityMonitor(){override fun onStartActivity(intent:Intent):Instrumentation.ActivityResult?=if(intent.action==Intent.ACTION_CREATE_DOCUMENT)Instrumentation.ActivityResult(Activity.RESULT_OK,Intent().setData(Uri.fromFile(file)))else null};i.addMonitor(monitor);try{click("form-save");compose.waitUntil(20_000){model.state?.message==FormFailure.DESTINATION.message};assertArrayEquals(bytes,file.readBytes());assertNull(saved)}finally{i.removeMonitor(monitor)}}
}
