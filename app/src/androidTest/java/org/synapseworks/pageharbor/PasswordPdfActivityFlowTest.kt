package org.synapseworks.pageharbor

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.synapseworks.pageharbor.document.protectedpdf.PasswordPdfExportViewModel
import org.synapseworks.pageharbor.document.session.*
import org.synapseworks.pageharbor.protectedpdf.M05DocumentsProvider
import org.synapseworks.pageharbor.protectedpdf.M05ShareTargetActivity
import org.synapseworks.pageharbor.security.*

class PasswordPdfActivityFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as Application
    private lateinit var root: File
    private lateinit var pdf: File
    private lateinit var before: ByteArray
    private val password get() = "synthetic-ui-password"
    private val destinationRoot get() = File(app.cacheDir, "m05-system-destination")
    private fun model() = ViewModelProvider(compose.activity)[PasswordPdfExportViewModel::class.java]
    private fun lock() = ViewModelProvider(compose.activity)[AppLockViewModel::class.java]
    private var checkpoint = "start"
    private fun safeUi(block: () -> Unit) {
        try { block() } catch (failure: Throwable) {
            val line = failure.stackTrace.firstOrNull { it.className.startsWith("org.synapseworks") }?.lineNumber
            throw AssertionError("M05 Android handoff requirement failed: ${failure.javaClass.simpleName}, line $line, checkpoint $checkpoint, lock ${lock().state.value.phase}, work ${model().state.work}, package ${instrumentation.uiAutomation.rootInActiveWindow?.packageName}")
        }
    }

    @Before fun setup() {
        check(app.packageName.endsWith(".phase2test"))
        compose.runOnIdle { unlockAndDisable() }
        destinationRoot.deleteRecursively()
        M05DocumentsProvider.denyWrites = false
        M05ShareTargetActivity.receivedCount = 0
        M05ShareTargetActivity.passwordRequired = false
        M05ShareTargetActivity.readGrantObserved = false
        root = File(app.cacheDir, "shared-pdfs/m05-ui-${UUID.randomUUID()}").apply { check(mkdirs()) }
        pdf = File(root, "source.pdf")
        PDFBoxResourceLoader.init(app)
        PDDocument().use { document -> repeat(2) { document.addPage(PDPage()) }; document.save(pdf) }
        before = pdf.readBytes()
        val image = File(root, "page.jpg")
        val bitmap = Bitmap.createBitmap(240, 320, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            image.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
        } finally { bitmap.recycle() }
        val pageResource = DocumentResource(uri(image).toString(), DocumentResourceOwnership.USER_OR_EXTERNAL)
        val direct = DocumentResource(uri(pdf).toString(), DocumentResourceOwnership.USER_OR_EXTERNAL)
        val pages = (1L..2L).map { DocumentPage(
            DocumentPageId(it), pageResource, DocumentSourceCategory.SCAN,
            imageMetadata = DocumentImageMetadata(image.length(), 240, 320),
        ) }
        compose.runOnIdle { compose.activity.installDocumentSessionForTest(DocumentSession(
            pages = pages, directPdfSource = direct, directPdfPageIds = pages.map { it.id },
        )) }
    }
    @After fun cleanup() {
        compose.runOnIdle { model().close(); unlockAndDisable() }
        destinationRoot.deleteRecursively()
        File(app.cacheDir, "m05-received-encrypted.pdf").delete()
        root.deleteRecursively()
    }
    private fun uri(file: File): Uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
    private fun unlockAndDisable() {
        val lock = lock()
        if (lock.state.value.phase == AppLockPhase.LOCKED) {
            lock.beginAuthentication(AppLockAuthenticationAvailability.AVAILABLE)?.let {
                lock.onAuthenticationResult(it.id, AppLockAuthenticationResult.Success)
            }
        }
        lock.disableAfterAuthentication()
    }
    private fun open() {
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Password-protected PDF").performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(30_000) { model().state.source != null && !model().state.busy }
        compose.onNodeWithTag("protect-password").assertExists()
    }
    private fun prepare() {
        open()
        compose.onNodeWithTag("protect-password").performScrollTo().performTextInput(password)
        compose.onNodeWithTag("protect-confirmation").performScrollTo().performTextInput(password)
        compose.onNodeWithTag("protect-confirmation").performImeAction()
        compose.onNodeWithTag("protect-prepare").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(30_000) { model().state.output?.isFile == true && !model().state.busy }
    }
    private fun systemNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (predicate(node)) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { child -> find(child)?.let { return it } }
            return null
        }
        return find(root)
    }
    private fun clickSystemText(text: String, rootRow: Boolean = false) {
        fun inRootsList(node: AccessibilityNodeInfo): Boolean {
            var ancestor: AccessibilityNodeInfo? = node
            while (ancestor != null) {
                if (ancestor.viewIdResourceName?.endsWith("/roots_list") == true) return true
                ancestor = ancestor.parent
            }
            return false
        }
        checkpoint = "system action $text"
        val until = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < until) {
            var node = systemNode {
                it.text?.toString()?.equals(text, ignoreCase = true) == true && (!rootRow || inRootsList(it))
            }
            while (node != null && !node.isClickable) node = node.parent
            if (node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return
            if (rootRow) {
                // Match the provider row, never its identical remembered toolbar title. Only the
                // known system roots list may be scrolled; no arbitrary file is selected.
                val roots = systemNode { it.viewIdResourceName?.endsWith("/roots_list") == true }
                if (roots?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == false) {
                    roots.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                }
            }
            SystemClock.sleep(100)
        }
        throw AssertionError("System action unavailable")
    }
    private fun systemBack() {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("input keyevent 4"))
            .use { it.readBytes() }
    }
    private fun chooseSyntheticDestination() {
        val until = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < until) {
            // A remembered provider can put its title in the toolbar. Always select its drawer
            // row explicitly instead of treating a non-clickable toolbar title as a root action.
            val button = systemNode { it.contentDescription?.toString() in listOf("Show roots", "Show navigation drawer") }
            if (button != null) { button.performAction(AccessibilityNodeInfo.ACTION_CLICK); break }
            if (systemNode { it.text?.toString() == "RME test destination" && it.viewIdResourceName?.endsWith(":id/title") == true } != null) break
            SystemClock.sleep(100)
        }
        clickSystemText("RME test destination", rootRow = true)
        clickSystemText("Save")
    }

    @Test fun actualSystemSaveWritesEncryptedCopyAndPreservesSourceAndSession() = safeUi {
        val revision = compose.activity.documentRevisionForTest()
        prepare()
        compose.onNodeWithText("Save encrypted PDF").performScrollTo().performClick()
        chooseSyntheticDestination()
        compose.waitUntil(30_000) { model().state.message == R.string.protect_saved }
        val saved = destinationRoot.listFiles().orEmpty().single()
        assertArrayEquals(requireNotNull(model().state.output).readBytes(), saved.readBytes())
        PDDocument.load(saved, password).use { assertEquals(2, it.numberOfPages) }
        assertArrayEquals(before, pdf.readBytes())
        assertEquals(revision, compose.activity.documentRevisionForTest())
    }

    @Test fun cancellingActualSavePickerReleasesItsTicketAndAllowsAnotherSave() = safeUi {
        prepare()
        compose.onNodeWithText("Save encrypted PDF").performScrollTo().performClick()
        // A real system picker must be active before cancellation.
        compose.waitUntil(20_000) { instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui") == true }
        systemBack()
        compose.waitUntil(20_000) { !model().state.busy }
        assertTrue(destinationRoot.listFiles().orEmpty().isEmpty())
        compose.onNodeWithText("Save encrypted PDF").performScrollTo().assertIsEnabled().performClick()
        chooseSyntheticDestination()
        compose.waitUntil(30_000) { model().state.message == R.string.protect_saved }
        assertArrayEquals(before, pdf.readBytes())
    }

    @Test fun actualShareChooserCancellationAndReadGrantReceiverAreSafe() = safeUi {
        prepare()
        compose.onNodeWithText("Share encrypted PDF").performScrollTo().performClick()
        compose.waitUntil(20_000) { instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() in listOf("android", "com.android.intentresolver") }
        systemBack()
        compose.waitForIdle()
        assertEquals(0, M05ShareTargetActivity.receivedCount)
        compose.onNodeWithText("Share encrypted PDF").performScrollTo().performClick()
        clickSystemText("RME test receiver")
        compose.waitUntil(20_000) { M05ShareTargetActivity.receivedCount == 1 }
        assertTrue(M05ShareTargetActivity.passwordRequired)
        assertTrue(M05ShareTargetActivity.readGrantObserved)
        assertArrayEquals(requireNotNull(model().state.output).readBytes(), File(app.cacheDir, "m05-received-encrypted.pdf").readBytes())
        assertArrayEquals(before, pdf.readBytes())
    }

    @Test fun recreationKeepsPreparedOwnershipWithoutRestoringPasswordFields() = safeUi {
        prepare()
        val output = requireNotNull(model().state.output)
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        assertTrue(model().state.output === output)
        compose.onNodeWithTag("protect-password").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithTag("protect-confirmation").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithText("Save encrypted PDF").performScrollTo().assertIsEnabled()
        assertArrayEquals(before, pdf.readBytes())
    }

    @Test fun saveResultWaitsBehindAppLockAndPublishesOnlyAfterAuthentication() = safeUi {
        prepare()
        compose.runOnIdle { lock().setup(AutoLockTimeout.IMMEDIATELY, AppLockAuthenticationAvailability.AVAILABLE) }
        compose.onNodeWithText("Save encrypted PDF").performScrollTo().performClick()
        chooseSyntheticDestination()
        // The lock may engage as soon as the picker starts. Wait for the real result to return,
        // not merely for LOCKED, before inspecting the provider-created empty destination.
        compose.waitUntil(20_000) {
            instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == app.packageName &&
                destinationRoot.listFiles().orEmpty().size == 1
        }
        assertEquals(AppLockPhase.LOCKED, lock().state.value.phase)
        assertEquals(0L, destinationRoot.listFiles().orEmpty().single().length())
        compose.runOnIdle {
            val attempt = requireNotNull(lock().beginAuthentication(AppLockAuthenticationAvailability.AVAILABLE))
            lock().onAuthenticationResult(attempt.id, AppLockAuthenticationResult.Success)
        }
        compose.waitUntil(30_000) { model().state.message == R.string.protect_saved }
        PDDocument.load(destinationRoot.listFiles().orEmpty().single(), password).use { assertEquals(2, it.numberOfPages) }
        assertArrayEquals(before, pdf.readBytes())
    }
}
