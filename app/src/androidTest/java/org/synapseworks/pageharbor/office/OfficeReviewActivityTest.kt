package org.synapseworks.pageharbor.office

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.os.*
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import org.synapseworks.pageharbor.security.*
import java.io.File
import java.util.UUID

class OfficeReviewActivityTest {
    @get:Rule val compose = createAndroidComposeRule<OfficeImportActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext
    private lateinit var source: File
    private lateinit var sourceUri: Uri
    private var saved: Uri? = null
    private var retainSyntheticArtifacts = false
    @Before fun setup() {
        Assume.assumeTrue("Qualified local bundle required", org.synapseworks.pageharbor.BuildConfig.M35_OFFICE_ENABLED)
        source = File(app.cacheDir, "shared-pdfs/m35-ui-${UUID.randomUUID()}.docx").apply {
            parentFile!!.mkdirs(); writeBytes(instrumentation.context.assets.open("m35/docx-columns.docx").use { it.readBytes() })
        }
        sourceUri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", source)
        compose.runOnIdle {
            val lock = ViewModelProvider(compose.activity)[AppLockViewModel::class.java]
            if (lock.state.value.phase == AppLockPhase.LOCKED) lock.beginAuthentication(AppLockAuthenticationAvailability.AVAILABLE)?.let { lock.onAuthenticationResult(it.id, AppLockAuthenticationResult.Success) }
            lock.disableAfterAuthentication(); compose.activity.onSavedForTest = { saved = it }
        }
    }
    @After fun cleanup() {
        if (!retainSyntheticArtifacts) saved?.let { runCatching { android.provider.DocumentsContract.deleteDocument(app.contentResolver, it) } }
        if (::source.isInitialized) source.delete()
    }
    private fun click(tag: String) {
        compose.onNodeWithTag("office-screen").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).assertIsEnabled().performClick()
    }
    private fun exists(tag: String) = runCatching { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)
    private fun awaitOpen() {
        compose.waitUntil(15000) { runCatching {
            val node = compose.onAllNodesWithTag("office-open").fetchSemanticsNodes().singleOrNull()
            node != null && !node.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
        }.getOrDefault(false) }
    }
    private fun open() {
        awaitOpen()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? =
                if (intent.action == Intent.ACTION_OPEN_DOCUMENT) Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(sourceUri)) else null
        }
        instrumentation.addMonitor(monitor)
        try { click("office-open"); compose.waitUntil(30000) { exists("office-convert") } }
        finally { instrumentation.removeMonitor(monitor) }
    }
    private fun ready() {
        open(); click("office-accept"); click("office-convert")
        compose.waitUntil(75000) { exists("office-preview") }
        click("office-reviewed")
        compose.onNodeWithTag("office-screen").performScrollToNode(hasTestTag("office-save"))
        compose.onNodeWithTag("office-save").assertIsEnabled()
    }
    private fun node(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
        val pending = java.util.ArrayDeque<AccessibilityNodeInfo>(); pending.add(root); var count = 0
        while (pending.isNotEmpty() && count++ < 2048) {
            val current = pending.removeFirst(); if (predicate(current)) return current
            for (index in 0 until current.childCount) current.getChild(index)?.let(pending::addLast)
        }
        return null
    }
    private fun systemClick(text: String, roots: Boolean = false) {
        val end = SystemClock.uptimeMillis() + 20000
        fun inRoots(item: AccessibilityNodeInfo): Boolean {
            var parent: AccessibilityNodeInfo? = item
            while (parent != null) { if (parent.viewIdResourceName?.endsWith("/roots_list") == true) return true; parent = parent.parent }; return false
        }
        while (SystemClock.uptimeMillis() < end) {
            var item = node { it.text?.toString()?.equals(text, true) == true && (!roots || inRoots(it)) }
            while (item != null && !item.isClickable) item = item.parent
            if (item?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return
            if (roots) node { it.viewIdResourceName?.endsWith("/roots_list") == true }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(100)
        }
        throw AssertionError("Synthetic Android Save control unavailable")
    }
    private fun destination(name: String = "M35-synthetic-${UUID.randomUUID()}.pdf") {
        val end = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < end) {
            val roots = node { it.contentDescription?.toString() in listOf("Show roots", "Show navigation drawer") }
            if (roots != null) { roots.performAction(AccessibilityNodeInfo.ACTION_CLICK); break }; SystemClock.sleep(100)
        }
        systemClick("Downloads", true)
        // A unique synthetic name avoids modifying an earlier user-selected file.
        val edit = node { it.className?.toString() == "android.widget.EditText" }
        assertNotNull(edit)
        edit!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, name)
        })
        systemClick("Save")
    }
    @Test fun reviewedSourcePreviewAndActualAndroidSavePreserveOriginalBytes() {
        val before = source.readBytes(); ready(); click("office-save"); destination()
        compose.waitUntil(30000) { saved != null }
        val bytes = app.contentResolver.openInputStream(saved!!)!!.use { it.readBytes() }
        assertTrue(bytes.take(5).toByteArray().contentEquals("%PDF-".toByteArray()))
        assertArrayEquals(before, source.readBytes())
    }
    @Test fun originalAdmittedCorpusActualSaveForIndependentInspection() {
        val run = InstrumentationRegistry.getArguments().getString("m35ArtifactRun")
        Assume.assumeTrue("Explicit owned synthetic artifact run required", run?.matches(Regex("[a-f0-9]{7}-(ordinary|optimized)-(api26|api35|api36|api37)-[a-f0-9]{8}")) == true)
        val cases = org.json.JSONObject(instrumentation.context.assets.open("m35/manifest.json").bufferedReader().use { it.readText() }).getJSONArray("cases")
        retainSyntheticArtifacts = true
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            if (case.getString("expectedProduct") != "ADMITTED") continue
            source.delete(); source = File(app.cacheDir, "shared-pdfs/m35-ui-${UUID.randomUUID()}.${case.getString("kind")}").apply {
                writeBytes(instrumentation.context.assets.open("m35/${case.getString("id")}.${case.getString("kind")}").use { it.readBytes() })
            }
            sourceUri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", source)
            assertEquals(case.getString("sha256"), officeDigest(source) {})
            awaitOpen(); click("office-kind-${case.getString("kind")}"); open(); click("office-accept"); click("office-convert")
            var reviewed = 0
            while (true) {
                compose.waitUntil(75000) { exists("office-preview") }
                click("office-reviewed"); reviewed++
                val next = compose.onNodeWithTag("office-next").fetchSemanticsNode()
                if (next.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)) break
                assertTrue(reviewed < 10); click("office-next")
            }
            saved = null; click("office-save"); destination("M35-$run-${case.getString("id")}.pdf")
            compose.waitUntil(30000) { saved != null }
            val bytes = app.contentResolver.openInputStream(saved!!)!!.use { it.readBytes() }
            assertTrue(bytes.size.toLong() in 1..OFFICE_OUTPUT)
            assertArrayEquals("%PDF-".toByteArray(), bytes.take(5).toByteArray())
            assertEquals(case.getString("sha256"), officeDigest(source) {})
            click("office-cancel")
            compose.waitUntil(15000) {
                val node = compose.onAllNodesWithTag("office-open").fetchSemanticsNodes().singleOrNull()
                node != null && !node.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
            }
        }
    }
    @Test fun actualShareChooserUsesReadOnlyVerifiedCopyAndExplicitCleanup() {
        val before = source.readBytes(); ready()
        var sent: Intent? = null
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action == Intent.ACTION_CHOOSER) sent = intent.getParcelableExtra(Intent.EXTRA_INTENT)
                return null // Observe the actual Android chooser, do not replace it.
            }
        }
        instrumentation.addMonitor(monitor)
        var uri: Uri? = null
        try {
            click("office-share")
            compose.waitUntil(20000) { sent != null }
            val request = checkNotNull(sent)
            assertEquals(Intent.ACTION_SEND, request.action); assertEquals("application/pdf", request.type)
            assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, request.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
            uri = request.getParcelableExtra(Intent.EXTRA_STREAM)
            assertNotNull(uri); assertEquals(uri, request.clipData?.getItemAt(0)?.uri)
            val bytes = app.contentResolver.openInputStream(uri!!)!!.use { it.readBytes() }
            assertTrue(bytes.size.toLong() in 1..OFFICE_OUTPUT)
            assertArrayEquals("%PDF-".toByteArray(), bytes.take(5).toByteArray())
            assertArrayEquals(before, source.readBytes())
            // Dismiss only the chooser. Source review ends on backgrounding.
            // The observer sees startActivity before the chooser owns a window;
            // an earlier Back would finish RME instead of cancelling the chooser.
            compose.waitUntil(15000) {
                instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() in setOf("android", "com.android.intentresolver")
            }
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("input keyevent 4")).use { it.readBytes() }
            compose.waitUntil(15000) { exists("office-clear-shares") }
            click("office-clear-shares")
            compose.waitUntil(15000) { !File(app.cacheDir, "shared-pdfs/${uri!!.lastPathSegment}").exists() }
        } finally {
            instrumentation.removeMonitor(monitor)
            uri?.let { app.revokeUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION); File(app.cacheDir, "shared-pdfs/${it.lastPathSegment}").takeIf { file -> file.name.startsWith("office-") }?.delete() }
        }
    }
    @Test fun actualSaveCancellationKeepsSourceAndAllowsFreshExplicitSave() {
        val before = source.readBytes(); ready(); click("office-save")
        compose.waitUntil(20000) { instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui") == true }
        // The first Back can dismiss DocumentsUI's filename keyboard. Cancel
        // the picker itself, never send another Back after returning to RME.
        repeat(3) {
            if (instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui") == true) {
                ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("input keyevent 4")).use { it.readBytes() }
                SystemClock.sleep(500)
            }
        }
        compose.waitUntil(20000) { exists("office-preview") }
        assertNull(saved); assertArrayEquals(before, source.readBytes())
        click("office-save"); destination(); compose.waitUntil(30000) { saved != null }
    }
    @Test fun lockAndRecreationDiscardReviewAndRejectRestoredSaveCallback() {
        val before = source.readBytes(); ready()
        var resultCode = 0
        compose.runOnIdle {
            assertNotNull(compose.activity.freezeSaveRequest())
            val registry = compose.activity.activityResultRegistry
            val savedRegistry = Bundle(); registry.onSaveInstanceState(savedRegistry)
            val keys = savedRegistry.getStringArrayList("KEY_COMPONENT_ACTIVITY_REGISTERED_KEYS")!!
            val codes = savedRegistry.getIntegerArrayList("KEY_COMPONENT_ACTIVITY_REGISTERED_RCS")!!
            val key = keys.single { it.endsWith("#1") }
            resultCode = codes[keys.indexOf(key)]
            val launched = savedRegistry.getStringArrayList("KEY_COMPONENT_ACTIVITY_LAUNCHED_KEYS") ?: arrayListOf()
            launched.add(key); savedRegistry.putStringArrayList("KEY_COMPONENT_ACTIVITY_LAUNCHED_KEYS", launched)
            // Simulate a result arriving while the registry's lifecycle callback
            // is inactive. Raw pending results are normally saved by AndroidX.
            savedRegistry.putBundle("KEY_COMPONENT_ACTIVITY_PENDING_RESULT", Bundle().apply {
                putParcelable(key, androidx.activity.result.ActivityResult(Activity.RESULT_OK, Intent().setData(sourceUri)))
            })
            registry.onRestoreInstanceState(savedRegistry)
        }
        val state = Bundle(); compose.runOnIdle { instrumentation.callActivityOnSaveInstanceState(compose.activity, state) }
        val parcel = Parcel.obtain(); val bytes = try { state.writeToParcel(parcel, 0); parcel.marshall() } finally { parcel.recycle() }
        for (charset in listOf(Charsets.UTF_8, Charsets.UTF_16LE, Charsets.UTF_16BE)) {
            val needle = sourceUri.toString().toByteArray(charset)
            assertFalse((0..(bytes.size - needle.size).coerceAtLeast(-1)).any { start -> needle.indices.all { bytes[start + it] == needle[it] } })
        }
        compose.runOnIdle { ViewModelProvider(compose.activity)[AppLockViewModel::class.java].apply { setup(AutoLockTimeout.IMMEDIATELY, AppLockAuthenticationAvailability.AVAILABLE); lockNow() } }
        compose.waitUntil(10000) { !exists("office-screen") }
        compose.activityRule.scenario.recreate()
        compose.runOnIdle { val lock = ViewModelProvider(compose.activity)[AppLockViewModel::class.java]; lock.beginAuthentication(AppLockAuthenticationAvailability.AVAILABLE)?.let { lock.onAuthenticationResult(it.id, AppLockAuthenticationResult.Success) }; lock.disableAfterAuthentication() }
        compose.waitUntil { exists("office-open") }
        compose.runOnIdle { compose.activity.activityResultRegistry.dispatchResult(resultCode, Activity.RESULT_OK, Intent().setData(sourceUri)) }
        compose.onAllNodesWithTag("office-save").assertCountEquals(0)
        assertNull(saved); assertArrayEquals(before, source.readBytes())
    }
}
