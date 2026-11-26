package org.synapseworks.pageharbor

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.*
import org.junit.Assert.*
import org.synapseworks.pageharbor.document.session.*
import org.synapseworks.pageharbor.image.ManualImageAdjustments
import org.synapseworks.pageharbor.security.*

class ManualAdjustmentActivityTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private lateinit var root: File
    private lateinit var source: File
    private lateinit var before: ByteArray
    private fun model() = ViewModelProvider(compose.activity)[PageHarborSessionViewModel::class.java]
    @Before fun setup() {
        check(app.packageName.endsWith(".phase2test"))
        root = File(app.cacheDir,"shared-pdfs/m06-${UUID.randomUUID()}").apply { check(mkdirs()) }
        source = File(root,"source.jpg")
        val bitmap = Bitmap.createBitmap(120,160,Bitmap.Config.ARGB_8888)
        try { bitmap.eraseColor(Color.rgb(64,96,128)); source.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG,100,it)) } }
        finally { bitmap.recycle() }
        before = source.readBytes()
        val resource = DocumentResource(FileProvider.getUriForFile(app,"${app.packageName}.fileprovider",source).toString(),DocumentResourceOwnership.USER_OR_EXTERNAL)
        compose.runOnIdle {
            val lock = ViewModelProvider(compose.activity)[AppLockViewModel::class.java]
            if (lock.state.value.phase == AppLockPhase.LOCKED) lock.beginAuthentication(AppLockAuthenticationAvailability.AVAILABLE)?.let {
                lock.onAuthenticationResult(it.id,AppLockAuthenticationResult.Success)
            }
            lock.disableAfterAuthentication()
            compose.activity.installDocumentSessionForTest(DocumentSession((1L..2L).map {
                DocumentPage(DocumentPageId(it),resource,DocumentSourceCategory.SCAN,imageMetadata=DocumentImageMetadata(source.length(),120,160))
            }))
        }
    }
    @After fun cleanup() { root.deleteRecursively() }
    private fun safe(block: () -> Unit) {
        try { block() } catch (e: Throwable) {
            val line=e.stackTrace.firstOrNull { it.className.startsWith("org.synapseworks") }?.lineNumber
            throw AssertionError("M06 UI assertion failed: ${e.javaClass.simpleName}, line $line" + if(e.message?.startsWith("M06 slider numeric mismatch")==true) " ${e.message}" else "")
        }
    }
    private fun open() {
        // M03 intentionally hides bottom-bar text at fontScale>=1.8; its accessible label remains.
        if (compose.onAllNodesWithContentDescription("Edit").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithContentDescription("Edit").assertIsDisplayed().performClick()
        compose.onNodeWithText("Brightness & contrast").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("manual-brightness").assertExists()
    }
    private fun set(tag:String,value:Float) = compose.onNodeWithTag(tag).performScrollTo()
        .performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(value)) }
    private fun value(tag:String,value:Float) {
        val node=compose.onNodeWithTag(tag)
        val actual=node.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        // Material snaps its Float range (e.g.20 becomes20.000008). The integer draft/label
        // remains exact; tolerate only sub-millipercent representation noise, never a step.
        assertEquals(-100f..100f,actual.range);assertEquals(199,actual.steps)
        if (kotlin.math.abs(value-actual.current)>0.001f) throw AssertionError("M06 slider numeric mismatch: tag=$tag expected=$value actual=${actual.current} label=${node.fetchSemanticsNode().config.getOrElse(SemanticsProperties.StateDescription){"absent"}}")
        assertEquals(value,actual.current,0.001f)
        node.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"${value.toInt()} %"))
    }
    @Test fun applyAffectsOnlyCurrentPageAndPreservesSourceAndIdentity() = safe {
        val revision=compose.activity.documentRevisionForTest()
        open(); value("manual-brightness",0f);value("manual-contrast",0f)
        set("manual-brightness",20f);set("manual-contrast",25f)
        compose.onNodeWithTag("manual-apply").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(ManualImageAdjustments(20,25),model().documentPages[0].adjustments)
            assertEquals(ManualImageAdjustments(),model().documentPages[1].adjustments)
            assertEquals(listOf(1L,2L),model().documentPages.map{it.id.value})
            assertEquals(revision+1,compose.activity.documentRevisionForTest())
        }
        assertArrayEquals(before,source.readBytes())
    }
    @Test fun cancelResetAndNeutralReapplyRespectExplicitCommitBoundary() = safe {
        open();set("manual-brightness",40f)
        compose.onNodeWithTag("manual-cancel").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(ManualImageAdjustments(),model().documentPages[0].adjustments) }
        open();compose.onNodeWithText("Brighter").performScrollTo().performClick()
        value("manual-brightness",20f)
        compose.onNodeWithTag("manual-reset").performScrollTo().performClick()
        value("manual-brightness",0f);value("manual-contrast",0f)
        val revision=compose.activity.documentRevisionForTest()
        compose.onNodeWithTag("manual-apply").performScrollTo().performClick()
        assertEquals(revision,compose.activity.documentRevisionForTest())
        assertArrayEquals(before,source.readBytes())
    }
    @Test fun recreatedDraftStaysUnappliedUntilExplicitApply() = safe {
        open();set("manual-brightness",55f);set("manual-contrast",-25f)
        compose.activityRule.scenario.recreate()
        value("manual-brightness",55f);value("manual-contrast",-25f)
        compose.runOnIdle { assertEquals(ManualImageAdjustments(),model().documentPages[0].adjustments) }
        compose.onNodeWithTag("manual-apply").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(ManualImageAdjustments(55,-25),model().documentPages[0].adjustments) }
    }
    @Test fun rapidChangesPresetAndCancelDoNotLeakIntoOtherPages() = safe {
        open()
        set("manual-brightness",-100f);value("manual-brightness",-100f)
        set("manual-brightness",100f);value("manual-brightness",100f)
        repeat(12) { set("manual-brightness",(it*10-50).toFloat()) }
        compose.onNodeWithText("More contrast").performScrollTo().performClick()
        value("manual-brightness",0f);value("manual-contrast",25f)
        compose.onNodeWithTag("manual-apply").performScrollTo().performClick()
        // Apply returns directly to the page navigator in the current editor.
        compose.onAllNodesWithTag("manual-brightness").assertCountEquals(0)
        compose.onNodeWithContentDescription("Next page").assertIsDisplayed().performClick()
        compose.onNodeWithText(app.getString(R.string.ocr_page_indicator,2,2)).assertIsDisplayed()

        open();value("manual-brightness",0f);value("manual-contrast",0f)
        android.os.ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent 4")).use {it.readBytes()}
        compose.waitUntil(20_000){compose.onAllNodesWithTag("manual-brightness").fetchSemanticsNodes().isEmpty()}
        compose.runOnIdle {
            assertEquals(ManualImageAdjustments(0,25),model().documentPages[0].adjustments)
            assertEquals(ManualImageAdjustments(),model().documentPages[1].adjustments)
        }
    }
}
