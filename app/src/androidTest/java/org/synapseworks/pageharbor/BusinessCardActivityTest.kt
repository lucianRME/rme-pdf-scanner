package org.synapseworks.pageharbor

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.lifecycle.*
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.*
import org.junit.Assert.*
import org.synapseworks.pageharbor.businesscard.*
import org.synapseworks.pageharbor.security.*
import org.synapseworks.pageharbor.ui.PageHarborScreen

class BusinessCardActivityTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application
    private val model get() = compose.activity.businessCard
    private lateinit var file: File
    private fun unlock() {
        val lock = ViewModelProvider(compose.activity)[AppLockViewModel::class.java]
        if(lock.state.value.phase == AppLockPhase.LOCKED) { val t = lock.beginAuthentication(AppLockAuthenticationAvailability.AVAILABLE)!!; lock.onAuthenticationResult(t.id, AppLockAuthenticationResult.Success) }
        lock.disableAfterAuthentication()
    }
    @Before fun setup() {
        check(app.packageName.endsWith(".phase2test")); file = File(app.cacheDir, "m20-ui-${UUID.randomUUID()}.png").apply { writeBytes(CardFixture.bytes()) }
        compose.runOnIdle { unlock(); model.close(); ViewModelProvider(compose.activity)[PageHarborSessionViewModel::class.java].screen = PageHarborScreen.Home }
    }
    @After fun cleanup() { compose.runOnIdle { unlock(); model.close() }; file.delete() }
    private fun click(tag: String) { compose.onNodeWithTag("card-list").performScrollToNode(hasTestTag(tag)); compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled().performClick() }
    private fun field(tag: String, value: String) { compose.onNodeWithTag("card-list").performScrollToNode(hasTestTag(tag)); compose.onNodeWithTag(tag).performTextReplacement(value); compose.runOnIdle { compose.activity.currentFocus?.clearFocus(); androidx.core.view.WindowInsetsControllerCompat(compose.activity.window, compose.activity.window.decorView).hide(androidx.core.view.WindowInsetsCompat.Type.ime()) } }
    private fun open() {
        val tools = app.getString(R.string.navigation_tools)
        compose.onNode((hasText(tools) or hasContentDescription(tools)) and hasClickAction()).performClick()
        compose.onNodeWithTag("tools-grid").performScrollToNode(hasTestTag("open-business-card"));compose.onNodeWithTag("open-business-card").assertIsDisplayed().performClick(); compose.waitUntil { model.state != null }
    }
    private fun image(viaPicker: Boolean = false) {
        if(viaPicker) {
            val monitor = object : Instrumentation.ActivityMonitor() { override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if(intent.action == Intent.ACTION_GET_CONTENT) { assertEquals("image/*", intent.type); assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE)); return Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.fromFile(file))) }; return null
            } }
            instrumentation.addMonitor(monitor); try { click("card-image"); compose.waitUntil(40_000) { model.state?.phase == CardPhase.EDIT } } finally { instrumentation.removeMonitor(monitor) }
        } else compose.runOnIdle { val s = model.state!!; assertTrue(model.beginPicker(s.owner, s.epoch)); model.picked(Uri.fromFile(file)) }
        compose.waitUntil(40_000) { model.state?.phase == CardPhase.EDIT }
    }
    private fun screenshot(name: String) { File(app.cacheDir, name).outputStream().use { compose.onNodeWithTag("card-screen").captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
    @Test fun reachablePickerCorrectionCompleteReviewAndSingleExplicitEditorHandoff() {
        val session = ViewModelProvider(compose.activity)[PageHarborSessionViewModel::class.java]; val before = session.documentSession
        file.writeBytes(CardFixture.bytes(email = "wrong@example.invalid"))
        open(); image(viaPicker = true); assertEquals("Jane Example", model.state!!.draft.name)
        assertEquals(listOf("wrong@example.invalid"), model.state!!.draft.emails)
        field("card-email-0", "jane@example.invalid")
        field("card-name", "Zoë Corrected"); field("card-phone-0", "+44 (0)20 7946 0100 ext. 12")
        click("card-add-email"); field("card-email-1", "Second@example.invalid")
        click("card-review"); assertEquals(CardPhase.REVIEW, model.state!!.phase); screenshot("m20-contact-review.png")
        var launches = 0; var sent: Intent? = null
        val monitor = object : Instrumentation.ActivityMonitor() { override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
            if(intent.action == Intent.ACTION_INSERT) { launches++; sent = Intent(intent); return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null) }; return null
        } }
        instrumentation.addMonitor(monitor)
        try {
            assertNull(sent); val s = model.state!!; click("card-open-editor")
            compose.runOnIdle { model.handoff(s.owner, s.epoch) { fail("Old callback launched a second editor") } }
            compose.waitUntil { model.state?.phase == CardPhase.RETURNED }; assertEquals(1, launches)
            @Suppress("DEPRECATION") val rows = sent!!.getParcelableArrayListExtra<ContentValues>(ContactsContract.Intents.Insert.DATA)!!
            assertEquals("Zoë Corrected", sent!!.getStringExtra(ContactsContract.Intents.Insert.NAME))
            assertTrue(sent!!.getBooleanExtra(ContactsContract.Intents.Insert.FULL_MODE, false))
            assertTrue(rows.any { it.getAsString(ContactsContract.CommonDataKinds.Phone.NUMBER) == "+44 (0)20 7946 0100 ext. 12" })
            assertTrue(rows.any { it.getAsString(ContactsContract.CommonDataKinds.Email.ADDRESS) == "Second@example.invalid" })
            assertTrue(rows.any { it.getAsString(ContactsContract.CommonDataKinds.Email.ADDRESS) == "jane@example.invalid" })
            assertEquals(setOf(ContactsContract.Intents.Insert.DATA, ContactsContract.Intents.Insert.NAME, ContactsContract.Intents.Insert.FULL_MODE), sent!!.extras!!.keySet()); assertNull(model.state!!.suggestions); assertEquals("", model.state!!.draft.name)
        } finally { instrumentation.removeMonitor(monitor) }
        assertSame(before, session.documentSession); click("card-close")
    }
    @Test fun frozenCompactPickerPopulatesAndHandsOffExactEmailBeforeAnyManualEdit() {
        val assets = instrumentation.context.assets
        file.writeBytes(assets.open("m20/compact-original.png").use { it.readBytes() })
        val expected = assets.open("m20/compact-email.txt").bufferedReader().use { it.readText() }
        open(); image(viaPicker = true)
        assertEquals(listOf(expected), model.state!!.draft.emails)
        compose.onNodeWithTag("card-list").performScrollToNode(hasTestTag("card-email-0"))
        compose.onNodeWithTag("card-email-0").assertTextContains(expected)
        var sent: Intent? = null
        val monitor = object : Instrumentation.ActivityMonitor() { override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
            if(intent.action == Intent.ACTION_INSERT) { sent = Intent(intent); return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null) }; return null
        } }
        instrumentation.addMonitor(monitor)
        try {
            click("card-review"); assertEquals(listOf(expected), model.state!!.draft.emails)
            click("card-open-editor"); compose.waitUntil { model.state?.phase == CardPhase.RETURNED }
            @Suppress("DEPRECATION") val rows = sent!!.getParcelableArrayListExtra<ContentValues>(ContactsContract.Intents.Insert.DATA)!!
            assertEquals(listOf(expected), rows.filter { it.getAsString(ContactsContract.Data.MIMETYPE) == ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE }
                .map { it.getAsString(ContactsContract.CommonDataKinds.Email.ADDRESS) })
        } finally { instrumentation.removeMonitor(monitor) }
    }

    @Test fun manualMissingFieldsUnsafeTextAndUnavailableEditorAreReviewable() {
        open(); click("card-manual"); field("card-name", "Manual Person"); field("card-organization", "javascript:example data")
        field("card-title", "bad\u202Evalue"); click("card-review"); assertEquals(CardPhase.EDIT, model.state!!.phase)
        compose.onNodeWithTag("card-error").performScrollTo().assertExists()
        field("card-title", "Corrected Title"); click("card-review"); click("card-correct"); assertEquals("Corrected Title", model.state!!.draft.title)
        click("card-review"); compose.runOnIdle { val s = model.state!!; model.handoff(s.owner, s.epoch) { throw android.content.ActivityNotFoundException() } }
        assertEquals(CardPhase.RETURNED, model.state!!.phase); assertTrue(model.state!!.problem!!.contains("No contact editor"))
        click("card-recapture"); file.writeBytes(CardFixture.bytes(ambiguous = true)); image()
        assertEquals("", model.state!!.draft.name); assertEquals("", model.state!!.draft.organization)
        click("card-candidates"); compose.onNodeWithTag("card-candidate-value").assertTextEquals("Jane Example")
        click("card-use-name"); assertEquals("Jane Example", model.state!!.draft.name)
        click("card-next-candidate"); click("card-use-organization"); assertEquals("Harbor Studio", model.state!!.draft.organization)
        click("card-next-candidate"); click("card-use-title"); assertEquals("Design Lead", model.state!!.draft.title)
        click("card-use-address"); assertEquals(2, model.state!!.draft.addresses.size)
        click("card-remove-address-1"); assertEquals(listOf("12 Sample Road\nDublin D02 AB12"), model.state!!.draft.addresses)
        click("card-remove-phone-0"); assertTrue(model.state!!.draft.phones.isEmpty())
        click("card-add-phone"); field("card-phone-0", "+1 202 555 0100 ext. 42")
        click("card-source-toggle"); compose.onNodeWithTag("card-list").performScrollToNode(hasTestTag("card-source")); compose.onNodeWithTag("card-source").assertIsDisplayed()
        click("card-review"); assertEquals(listOf("+1 202 555 0100 ext. 42"), model.state!!.draft.phones)
        click("card-close")
    }
    @Test fun recreationRetainsDraftButBackgroundLockAndStaleReviewCannotHandoff() {
        open(); image(); field("card-name", "Retained Synthetic")
        compose.activityRule.scenario.recreate(); assertEquals("Retained Synthetic", model.state!!.draft.name)
        click("card-review"); val old = model.state!!
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED); assertNull(model.state!!.suggestions); assertEquals("", model.state!!.draft.name)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.runOnIdle { model.handoff(old.owner, old.epoch) { fail("Background invalidated review") } }
        image(); compose.runOnIdle { val lock = ViewModelProvider(compose.activity)[AppLockViewModel::class.java]; lock.setup(AutoLockTimeout.IMMEDIATELY, AppLockAuthenticationAvailability.AVAILABLE); lock.lockNow() }
        compose.waitUntil { model.state == null }; compose.onAllNodesWithTag("card-screen").assertCountEquals(0)
        compose.runOnIdle { unlock(); model.handoff(old.owner, old.epoch) { fail("Lock invalidated review") } }
    }
    @Test fun pendingPickerCanReturnBehindAuthenticationAndOldPickerCannotTargetNewScan() {
        open(); var code = 0
        compose.runOnIdle {
            val s = model.state!!; assertTrue(model.beginPicker(s.owner, s.epoch))
            val bundle = android.os.Bundle(); compose.activity.activityResultRegistry.onSaveInstanceState(bundle)
            val keys = bundle.getStringArrayList("KEY_COMPONENT_ACTIVITY_REGISTERED_KEYS")!!; val codes = bundle.getIntegerArrayList("KEY_COMPONENT_ACTIVITY_REGISTERED_RCS")!!
            val key = "card-image:" + model.activityResultOwner; code = codes[keys.indexOf(key)]
            val launched = bundle.getStringArrayList("KEY_COMPONENT_ACTIVITY_LAUNCHED_KEYS") ?: arrayListOf(); launched.add(key); bundle.putStringArrayList("KEY_COMPONENT_ACTIVITY_LAUNCHED_KEYS", launched)
            compose.activity.activityResultRegistry.onRestoreInstanceState(bundle)
        }
        compose.activityRule.scenario.recreate()
        compose.runOnIdle { val lock = ViewModelProvider(compose.activity)[AppLockViewModel::class.java]; lock.setup(AutoLockTimeout.IMMEDIATELY, AppLockAuthenticationAvailability.AVAILABLE); lock.lockNow() }
        compose.waitUntil { ViewModelProvider(compose.activity)[AppLockViewModel::class.java].state.value.phase == AppLockPhase.LOCKED }
        compose.runOnIdle { assertTrue(compose.activity.activityResultRegistry.dispatchResult(code, Activity.RESULT_OK, Intent().setData(Uri.fromFile(file)))) }
        assertEquals(CardPhase.OCR, model.state!!.phase); assertNull(model.state!!.suggestions)
        compose.runOnIdle { unlock() }; compose.waitUntil(40_000) { model.state?.phase == CardPhase.EDIT }
        compose.runOnIdle { val s = model.state!!; assertTrue(model.beginPicker(s.owner, s.epoch)); model.close(); compose.activity.openBusinessCard(); val next = model.state!!; assertFalse(model.beginPicker(next.owner, next.epoch)); model.picked(Uri.fromFile(file)) }
        assertEquals(CardPhase.CAMERA, model.state!!.phase); assertNull(model.state!!.suggestions); click("card-close")
    }
}
