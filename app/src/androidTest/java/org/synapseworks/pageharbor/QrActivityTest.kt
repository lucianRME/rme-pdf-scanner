package org.synapseworks.pageharbor

import android.app.Application
import android.app.Activity
import android.app.Instrumentation
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.*
import org.junit.Assert.*
import org.synapseworks.pageharbor.ui.PageHarborScreen
import org.synapseworks.pageharbor.qr.*
import org.synapseworks.pageharbor.security.*
import org.synapseworks.pageharbor.document.session.*

class QrActivityTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val app=instrumentation.targetContext.applicationContext as Application
    private val model get()=compose.activity.qr
    private lateinit var root:File
    private val files=mutableListOf<File>()
    private val session get()=ViewModelProvider(compose.activity)[PageHarborSessionViewModel::class.java]
    private fun unlock(){val lock=ViewModelProvider(compose.activity)[AppLockViewModel::class.java];if(lock.state.value.phase==AppLockPhase.LOCKED){val t=lock.beginAuthentication(AppLockAuthenticationAvailability.AVAILABLE)!!;lock.onAuthenticationResult(t.id,AppLockAuthenticationResult.Success)};lock.disableAfterAuthentication()}
    @Before fun setup(){check(app.packageName.endsWith(".phase2test"));root=File(app.cacheDir,"m19-ui-${UUID.randomUUID()}").apply{mkdirs()};compose.runOnIdle{unlock();model.close();session.clearScan()}}
    @After fun cleanup(){compose.runOnIdle{unlock();model.close();session.clearScan()};root.deleteRecursively()}
    private fun open(){
        val tools=app.getString(R.string.navigation_tools)
        compose.onNode((hasText(tools) or hasContentDescription(tools)) and hasClickAction()).assertIsDisplayed().performClick()
        compose.onNodeWithTag("tools-grid").performScrollToNode(hasTestTag("open-qr"));compose.onNodeWithTag("open-qr").assertIsDisplayed().performClick()
        compose.waitUntil{model.state!=null};compose.onNodeWithTag("qr-screen").assertIsDisplayed()
    }
    private fun click(tag:String){val list=if(model.state?.linkReview==true)"qr-link-list"else"qr-list";compose.onNodeWithTag(list).performScrollToNode(hasTestTag(tag));compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled().performClick()}
    private fun fixture(name:String):File=File(root,"$name.png").also{f->instrumentation.context.assets.open("m19/$name.png").use{input->f.outputStream().use{input.copyTo(it)}};files+=f}
    private fun import(name:String,viaPicker:Boolean=false):String{
        val file=fixture(name);val expected=instrumentation.context.assets.open("m19/$name.txt").bufferedReader(Charsets.UTF_8).use{it.readText()}
        if(viaPicker){
            var selected=false
            val monitor=object:Instrumentation.ActivityMonitor(){override fun onStartActivity(intent:Intent):Instrumentation.ActivityResult?{
                if(intent.action==Intent.ACTION_GET_CONTENT){
                    assertEquals("image/*",intent.type);assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE));assertEquals(0,intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                    selected=true;return Instrumentation.ActivityResult(Activity.RESULT_OK,Intent().setData(Uri.fromFile(file)))
                };return null}}
            instrumentation.addMonitor(monitor)
            try{click("qr-image");compose.waitUntil(30_000){selected && model.state?.phase==QrPhase.RESULT}}finally{instrumentation.removeMonitor(monitor)}
        }else compose.runOnIdle{assertTrue(model.beginPicker());model.receivePickedImage(Uri.fromFile(file))}
        compose.waitUntil(30_000){model.state?.phase==QrPhase.RESULT};assertEquals(expected,model.state!!.result!!.text);return expected
    }
    private fun capture(name:String,tag:String){File(app.cacheDir,name).outputStream().use{compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}}
    @Test fun reachableImageReviewCopyAndExplicitWebHandoffPreserveTheDocument(){
        val source=fixture("web");val original=DocumentSession(listOf(DocumentPage(DocumentPageId(11),DocumentResource(Uri.fromFile(source).toString(),DocumentResourceOwnership.USER_OR_EXTERNAL),DocumentSourceCategory.SELECTED_IMAGE)))
        compose.runOnIdle{compose.activity.installDocumentSessionForTest(original);session.screen=PageHarborScreen.Home}
        val before=session.documentSession;open();val expected=import("web",viaPicker=true)
        assertSame(before,session.documentSession)
        var opened:Intent?=null
        val monitor=object:Instrumentation.ActivityMonitor(){override fun onStartActivity(intent:Intent):Instrumentation.ActivityResult?{
            if(intent.action==Intent.ACTION_VIEW){opened=Intent(intent);return Instrumentation.ActivityResult(Activity.RESULT_CANCELED,null)};return null}}
        instrumentation.addMonitor(monitor)
        try{
            assertNull(opened);click("qr-copy");assertEquals(expected,app.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
            click("qr-review-link");compose.onNodeWithTag("qr-link-address").assertTextEquals(expected);assertNull(opened)
            capture("m19-qr-destination.png","qr-link-list");click("qr-link-cancel");assertNull(opened)
            click("qr-review-link");click("qr-open-link");compose.waitUntil{opened!=null}
            assertEquals(expected,opened!!.dataString);assertTrue(opened!!.hasCategory(Intent.CATEGORY_BROWSABLE));assertNull(opened!!.extras);assertNull(opened!!.clipData)
        }finally{instrumentation.removeMonitor(monitor)}
        assertSame(before,session.documentSession);click("qr-rescan");assertNull(model.state!!.result);click("qr-close");assertSame(before,session.documentSession)
    }
    @Test fun unsupportedAndInvisibleContentStayTextOnlyAndExplicitCopyUsesExactText(){
        open();val expected=import("controls");assertTrue(model.state!!.result!!.display.contains("\\u{202E}"));assertTrue(model.state!!.result!!.display.contains("\\u{0}"))
        compose.onAllNodesWithTag("qr-review-link").assertCountEquals(0);click("qr-copy");assertEquals(expected,app.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
        capture("m19-qr-review.png","qr-screen");click("qr-rescan");import("userinfo");compose.onAllNodesWithTag("qr-review-link").assertCountEquals(0)
        click("qr-rescan");import("unsupported");compose.onAllNodesWithTag("qr-review-link").assertCountEquals(0);click("qr-close")
    }
    @Test fun recreationBackgroundLockRescanAndLateCallbacksDoNotExposeOldResults(){
        open();val expected=import("unicode");compose.activityRule.scenario.recreate();assertEquals(expected,model.state!!.result!!.text)
        click("qr-rescan");var old:QrTicket?=null;compose.runOnIdle{old=model.frameTicket();model.rescan();model.completeFrame(old!!,QrDecode.Found(QrPayload.accept("Old synthetic")!!))};assertNull(model.state!!.result)
        import("web");click("qr-review-link");compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertNull(model.state!!.result);assertFalse(model.state!!.linkReview)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        import("web");compose.runOnIdle{val lock=ViewModelProvider(compose.activity)[AppLockViewModel::class.java];lock.setup(AutoLockTimeout.IMMEDIATELY,AppLockAuthenticationAvailability.AVAILABLE);lock.lockNow()}
        compose.waitUntil{model.state==null};compose.onAllNodesWithTag("qr-content").assertCountEquals(0)
        compose.runOnIdle{unlock();model.completeFrame(old!!,QrDecode.Found(QrPayload.accept("Old synthetic")!!))};assertNull(model.state)
    }
    @Test fun pendingPickerSurvivesRecreationAndAuthenticationButCannotTargetAReopenedScan(){
        open();var requestCode=0
        compose.runOnIdle{
            assertTrue(model.beginPicker())
            // Mark a synthetic platform launch through the registry's public save/restore API.
            // Delivery below uses the real Activity registry after the QR composition is removed.
            val saved=android.os.Bundle();compose.activity.activityResultRegistry.onSaveInstanceState(saved)
            val keys=saved.getStringArrayList("KEY_COMPONENT_ACTIVITY_REGISTERED_KEYS")!!
            val codes=saved.getIntegerArrayList("KEY_COMPONENT_ACTIVITY_REGISTERED_RCS")!!
            val key="qr-image:"+model.activityResultOwner;requestCode=codes[keys.indexOf(key)]
            val launched=saved.getStringArrayList("KEY_COMPONENT_ACTIVITY_LAUNCHED_KEYS")?:arrayListOf()
            launched.add(key);saved.putStringArrayList("KEY_COMPONENT_ACTIVITY_LAUNCHED_KEYS",launched)
            compose.activity.activityResultRegistry.onRestoreInstanceState(saved)
        }
        compose.activityRule.scenario.recreate()
        val file=fixture("web")
        compose.runOnIdle{val lock=ViewModelProvider(compose.activity)[AppLockViewModel::class.java];lock.setup(AutoLockTimeout.IMMEDIATELY,AppLockAuthenticationAvailability.AVAILABLE);lock.lockNow()}
        compose.waitUntil{ViewModelProvider(compose.activity)[AppLockViewModel::class.java].state.value.phase==AppLockPhase.LOCKED}
        compose.onAllNodesWithTag("qr-screen").assertCountEquals(0)
        compose.runOnIdle{assertTrue(compose.activity.activityResultRegistry.dispatchResult(requestCode,Activity.RESULT_OK,Intent().setData(Uri.fromFile(file))))}
        assertNull(model.state!!.result);assertEquals(QrPhase.IMPORTING,model.state!!.phase)
        compose.runOnIdle{unlock()};compose.waitUntil(30_000){model.state?.phase==QrPhase.RESULT}
        click("qr-rescan");compose.runOnIdle{assertTrue(model.beginPicker());model.close();compose.activity.openQrScanner();assertFalse(model.beginPicker());model.receivePickedImage(Uri.fromFile(file))}
        assertNull(model.state!!.result);click("qr-close")
    }
}
