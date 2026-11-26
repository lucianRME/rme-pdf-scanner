package org.synapseworks.pageharbor

import android.app.*
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID
import org.synapseworks.pageharbor.receipt.*
import org.synapseworks.pageharbor.security.*
import org.synapseworks.pageharbor.ui.PageHarborScreen

class ReceiptActivityTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val i=InstrumentationRegistry.getInstrumentation()
    private val app=i.targetContext.applicationContext as Application
    private val model get()=compose.activity.receipt
    private lateinit var file:File
    private fun unlock() {
        val lock=ViewModelProvider(compose.activity)[AppLockViewModel::class.java]
        if(lock.state.value.phase==AppLockPhase.LOCKED) { val t=lock.beginAuthentication(AppLockAuthenticationAvailability.AVAILABLE)!!;lock.onAuthenticationResult(t.id,AppLockAuthenticationResult.Success) }
        lock.disableAfterAuthentication()
    }
    @Before fun setup() { check(app.packageName.endsWith(".phase2test"));file=File(app.cacheDir,"m21-${UUID.randomUUID()}.png").apply{writeBytes(ReceiptFixture.bytes())}
        compose.runOnIdle{unlock();model.close();ViewModelProvider(compose.activity)[PageHarborSessionViewModel::class.java].screen=PageHarborScreen.Home} }
    @After fun cleanup(){compose.runOnIdle{unlock();model.close()};file.delete()}
    private fun click(tag:String){compose.onNodeWithTag("receipt-list").performScrollToNode(hasTestTag(tag));compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled().performClick()}
    private fun field(tag:String,value:String){compose.onNodeWithTag("receipt-list").performScrollToNode(hasTestTag(tag));compose.onNodeWithTag(tag).performTextReplacement(value)
        compose.runOnIdle{compose.activity.currentFocus?.clearFocus();androidx.core.view.WindowInsetsControllerCompat(compose.activity.window,compose.activity.window.decorView).hide(androidx.core.view.WindowInsetsCompat.Type.ime())} }
    private fun open(){val tools=app.getString(R.string.navigation_tools);compose.onNode((hasText(tools)or hasContentDescription(tools))and hasClickAction()).performClick()
        compose.onNodeWithTag("tools-grid").performScrollToNode(hasTestTag("open-receipt"));compose.onNodeWithTag("open-receipt").assertIsDisplayed().performClick();compose.waitUntil{model.state!=null} }
    private fun image(){val monitor=object:Instrumentation.ActivityMonitor(){override fun onStartActivity(intent:Intent):Instrumentation.ActivityResult? {
        if(intent.action==Intent.ACTION_GET_CONTENT){assertEquals("image/*",intent.type);assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE));return Instrumentation.ActivityResult(Activity.RESULT_OK,Intent().setData(Uri.fromFile(file)))};return null } }
        i.addMonitor(monitor);try{click("receipt-image");compose.waitUntil(40_000){model.state?.phase==ReceiptPhase.EDIT}}finally{i.removeMonitor(monitor)} }
    @Test fun reachableImageExtractionCorrectionEvidenceAndCompleteReview() {
        val session=ViewModelProvider(compose.activity)[PageHarborSessionViewModel::class.java];val before=session.documentSession
        open();image();assertEquals(ReceiptFixture.expected,model.state!!.draft.normalized(ReceiptRules()))
        click("receipt-evidence-total");compose.onNodeWithText("Original: 12.00; normalized suggestion: 12.00").assertExists()
        field("receipt-total","13.00");click("receipt-source-toggle");compose.onNodeWithTag("receipt-source").assertTextContains("Total 12.00",substring=true)
        click("receipt-review");assertEquals(ReceiptPhase.REVIEW,model.state!!.phase);assertEquals("13.00",model.state!!.reviewed!![ReceiptField.TOTAL])
        assertTrue(ReceiptValues.warnings(model.state!!.reviewed!!).isNotEmpty());assertEquals("12.00",model.state!!.extraction!!.fields.getValue(ReceiptField.TOTAL).candidates.single().raw)
        for(f in ReceiptField.entries){compose.onNodeWithTag("receipt-list").performScrollToNode(hasTestTag("receipt-reviewed-${f.name.lowercase()}"));compose.onNodeWithTag("receipt-reviewed-${f.name.lowercase()}").assertIsDisplayed()}
        click("receipt-correct");field("receipt-total","12.00");click("receipt-review");assertTrue(ReceiptValues.warnings(model.state!!.reviewed!!).isEmpty())
        compose.onNodeWithTag("receipt-list").performScrollToNode(hasTestTag("receipt-reviewed-merchant"))
        File(app.cacheDir,"m21-review.png").outputStream().use { compose.onNodeWithTag("receipt-screen").captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        assertSame(before,session.documentSession);click("receipt-close");assertNull(model.state)
    }
    @Test fun manualAmbiguousFormatsInvalidValuesAndSparseReviewAreReachable() {
        open();click("receipt-manual");field("receipt-date","03/04/2026");field("receipt-currency","$");field("receipt-total","12.OO");click("receipt-review")
        assertEquals(ReceiptPhase.EDIT,model.state!!.phase);compose.onNodeWithTag("receipt-error").assertExists()
        field("receipt-currency","USD");field("receipt-total","-5.20");click("receipt-date-rule");click("receipt-review")
        assertEquals("2026-04-03",model.state!!.reviewed!![ReceiptField.DATE]);assertEquals("-5.20",model.state!!.reviewed!![ReceiptField.TOTAL])
        click("receipt-correct");field("receipt-date","");field("receipt-currency","");field("receipt-total","");click("receipt-review");assertTrue(model.state!!.reviewed!!.isEmpty())
    }
    @Test fun configurationRetainsEditsBackgroundAndLockClearThem() {
        open();click("receipt-manual");field("receipt-merchant","SYNTHETIC RETAINED");click("receipt-review")
        compose.activityRule.scenario.recreate();compose.waitUntil{model.state?.phase==ReceiptPhase.REVIEW};assertEquals("SYNTHETIC RETAINED",model.state!!.reviewed!![ReceiptField.MERCHANT])
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED);assertTrue(model.state!!.draft.values.isEmpty());assertNull(model.state!!.extraction)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        click("receipt-manual");field("receipt-merchant","SYNTHETIC CLEAR")
        compose.runOnIdle{val lock=ViewModelProvider(compose.activity)[AppLockViewModel::class.java];lock.setup(AutoLockTimeout.IMMEDIATELY,AppLockAuthenticationAvailability.AVAILABLE);lock.lockNow()}
        compose.waitUntil{model.state==null};compose.onAllNodesWithTag("receipt-screen").assertCountEquals(0)
    }
    @Test fun canceledPickerOldCallbackAndProtectedDeferredInputCannotReplaceNewSource() {
        open();compose.runOnIdle{val s=model.state!!;assertTrue(model.beginPicker(s.owner,s.epoch));model.close();compose.activity.openReceipt();model.picked(Uri.fromFile(file));assertNull(model.state!!.extraction)
            val n=model.state!!;assertTrue(model.beginPicker(n.owner,n.epoch));model.lock();model.picked(Uri.fromFile(file));assertEquals(ReceiptPhase.OCR,model.state!!.phase);assertNull(model.state!!.extraction)}
        compose.runOnIdle{model.setActive(true)};compose.waitUntil(40_000){model.state?.phase==ReceiptPhase.EDIT};assertEquals(ReceiptFixture.expected,model.state!!.draft.normalized(ReceiptRules()))
        click("receipt-recapture");compose.runOnIdle{val s=model.state!!;assertTrue(model.beginPicker(s.owner,s.epoch));model.picked(null)};assertEquals(ReceiptPhase.PAUSED,model.state!!.phase)
    }
    @Test fun registeredPickerSurvivesRecreationAndWaitsForAuthenticationWithoutOcr() {
        open();var code=0
        compose.runOnIdle {
            val state=model.state!!;assertTrue(model.beginPicker(state.owner,state.epoch))
            val bundle=android.os.Bundle();compose.activity.activityResultRegistry.onSaveInstanceState(bundle)
            val keys=bundle.getStringArrayList("KEY_COMPONENT_ACTIVITY_REGISTERED_KEYS")!!;val codes=bundle.getIntegerArrayList("KEY_COMPONENT_ACTIVITY_REGISTERED_RCS")!!
            val key="receipt-image:"+model.activityResultOwner;code=codes[keys.indexOf(key)]
            val launched=bundle.getStringArrayList("KEY_COMPONENT_ACTIVITY_LAUNCHED_KEYS") ?: arrayListOf();launched.add(key);bundle.putStringArrayList("KEY_COMPONENT_ACTIVITY_LAUNCHED_KEYS",launched)
            compose.activity.activityResultRegistry.onRestoreInstanceState(bundle)
        }
        compose.activityRule.scenario.recreate()
        compose.runOnIdle{val lock=ViewModelProvider(compose.activity)[AppLockViewModel::class.java];lock.setup(AutoLockTimeout.IMMEDIATELY,AppLockAuthenticationAvailability.AVAILABLE);lock.lockNow()}
        compose.waitUntil{ViewModelProvider(compose.activity)[AppLockViewModel::class.java].state.value.phase==AppLockPhase.LOCKED}
        compose.runOnIdle{assertTrue(compose.activity.activityResultRegistry.dispatchResult(code,Activity.RESULT_OK,Intent().setData(Uri.fromFile(file))))}
        assertEquals(ReceiptPhase.OCR,model.state!!.phase);assertNull(model.state!!.extraction)
        compose.runOnIdle{unlock()};compose.waitUntil(40_000){model.state?.phase==ReceiptPhase.EDIT};assertEquals(ReceiptFixture.expected,model.state!!.draft.normalized(ReceiptRules()))
    }
    @Test fun actualEmulatedCameraStillCaptureReachesReviewWithoutCaptureFiles() {
        assertTrue(org.synapseworks.pageharbor.document.capture.CameraAccess.allowed(app));open()
        compose.waitUntil(30_000){compose.onAllNodesWithTag("receipt-shutter").fetchSemanticsNodes().isNotEmpty() && runCatching{compose.onNodeWithTag("receipt-shutter").assertIsEnabled()}.isSuccess}
        compose.onNodeWithTag("receipt-shutter").assertIsDisplayed().assertIsEnabled().performClick();compose.waitUntil(40_000){model.state?.phase==ReceiptPhase.EDIT};click("receipt-review");assertEquals(ReceiptPhase.REVIEW,model.state!!.phase)
    }
}
