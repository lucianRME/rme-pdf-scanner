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
import org.synapseworks.pageharbor.document.batch.*

class BatchEditingActivityTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private lateinit var root: File
    private lateinit var source: File
    private lateinit var before: ByteArray
    private fun model() = ViewModelProvider(compose.activity)[PageHarborSessionViewModel::class.java]
    @Before fun setup() {
        check(app.packageName.endsWith(".phase2test"))
        root = File(app.cacheDir,"shared-pdfs/m07-${UUID.randomUUID()}").apply { check(mkdirs()) }
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
            compose.activity.installDocumentSessionForTest(DocumentSession((1L..4L).map {
                DocumentPage(DocumentPageId(it),resource,DocumentSourceCategory.SCAN,imageMetadata=DocumentImageMetadata(source.length(),120,160),
                    filter=if(it==1L)org.synapseworks.pageharbor.image.DocumentFilter.GRAYSCALE else org.synapseworks.pageharbor.image.DocumentFilter.ORIGINAL,
                    rotation=if(it==1L)DocumentPageRotation.DEGREES_90 else if(it==3L)DocumentPageRotation.DEGREES_270 else DocumentPageRotation.DEGREES_0,
                    adjustments=ManualImageAdjustments(if(it==1L)10 else if(it==3L)-20 else 0,if(it==1L)-20 else if(it==3L)40 else 0))
            }))
        }
    }
    @After fun cleanup() { root.deleteRecursively() }
    private fun safe(block: () -> Unit) {
        try { block() } catch (e: Throwable) {
            val line=e.stackTrace.firstOrNull { it.className.startsWith("org.synapseworks") }?.lineNumber
            throw AssertionError("M07 UI assertion failed: ${e.javaClass.simpleName}, line $line" + if(e.message?.startsWith("M06 slider numeric mismatch")==true) " ${e.message}" else "")
        }
    }
    private fun open() {
        if(compose.onAllNodesWithContentDescription("Edit").fetchSemanticsNodes().isNotEmpty())compose.onNodeWithContentDescription("Edit").assertIsDisplayed().performClick()
        compose.onNodeWithText("Edit selected pages").performScrollTo().assertIsDisplayed().performClick()
    }
    private fun click(tag:String)=compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().performClick()
    private fun range(text:String) {
        compose.onNodeWithTag("batch-range").performScrollTo().performTextReplacement(text)
        click("batch-select-range")
    }
    private fun brightness(n:Float) {
        click("batch-set-brightness")
        compose.onNodeWithTag("batch-brightness").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress){assertTrue(it(n))}
        compose.onNodeWithTag("batch-brightness").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"${n.toInt()} %"))
    }
    private fun await(outcome:BatchEditOutcome) { compose.waitUntil(30_000){model().batchEditor.state?.outcome==outcome} }
    @Test fun emptySingleAllClearAndNoncontiguousRangeHaveAccessibleSelection()=safe {
        open();compose.onNodeWithTag("batch-apply").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("batch-undo").performScrollTo().assertIsNotEnabled()
        click("batch-all");compose.runOnIdle{assertEquals(setOf(1L,2L,3L,4L),model().batchEditor.state!!.selectedIds)}
        compose.onNodeWithTag("batch-count").assertTextEquals("4 of 4 pages selected")
        click("batch-clear");compose.onNodeWithTag("batch-pages").performScrollTo().performScrollToNode(hasTestTag("batch-page-2"))
        compose.onNodeWithTag("batch-page-2").assertIsOff().performClick().assertIsOn()
        compose.runOnIdle{assertEquals(setOf(2L),model().batchEditor.state!!.selectedIds)}
        range("0,8");compose.onNodeWithText("Enter valid page numbers or ascending ranges within this document.").assertExists()
        compose.runOnIdle{assertEquals(setOf(2L),model().batchEditor.state!!.selectedIds)}
        range("1,3");compose.onNodeWithTag("batch-count").assertTextEquals("2 of 4 pages selected")
        compose.onNodeWithTag("batch-pages").performScrollTo().performScrollToNode(hasTestTag("batch-page-3"))
        compose.onNodeWithTag("batch-page-3").assertIsOn()
        click("batch-close");assertArrayEquals(before,source.readBytes())
    }
    @Test fun fieldSpecificApplyNoOpAndSingleBatchUndoAreAtomic()=safe {
        val original=model().documentSession;val revision=model().documentRevision
        open();range("1,3");compose.onNodeWithTag("batch-mixed").assertTextContains("Mixed",substring=true)
        brightness(20f);click("batch-rotation");click("batch-apply");await(BatchEditOutcome.APPLIED)
        compose.runOnIdle {
            val pages=model().documentPages
            for(i in pages.indices) {
                if(i==0||i==2) {
                    assertEquals(20,pages[i].adjustments.brightness)
                    assertEquals(original.pages[i].adjustments.contrast,pages[i].adjustments.contrast)
                    assertEquals(original.pages[i].filter,pages[i].filter)
                    assertEquals(original.pages[i].rotation.clockwise(),pages[i].rotation)
                } else assertEquals(original.pages[i],pages[i])
                assertSame(original.pages[i].source,pages[i].source)
            }
            assertEquals(revision+1,model().documentRevision)
        }
        click("batch-apply");await(BatchEditOutcome.NO_CHANGES);assertEquals(revision+1,model().documentRevision)
        click("batch-undo");await(BatchEditOutcome.UNDONE)
        compose.runOnIdle{assertEquals(original,model().documentSession);assertEquals(revision+2,model().documentRevision)}
        compose.onNodeWithTag("batch-undo").assertIsNotEnabled();assertArrayEquals(before,source.readBytes())
    }
    @Test fun recreationKeepsDraftIdsAndReorderInvalidatesInsteadOfRetargeting()=safe {
        val revision=model().documentRevision
        open();range("1,3");brightness(35f)
        compose.activityRule.scenario.recreate()
        compose.runOnIdle{assertEquals(setOf(1L,3L),model().batchEditor.state!!.selectedIds);assertEquals(35,model().batchEditor.state!!.edit.brightness);assertNull(model().batchEditor.state!!.edit.contrast);assertEquals(revision,model().documentRevision)}
        click("batch-apply");await(BatchEditOutcome.APPLIED)
        compose.runOnIdle{assertTrue(model().reorderPages(listOf(4,3,2,1)))}
        await(BatchEditOutcome.STALE);compose.onNodeWithTag("batch-status").assertTextEquals("The document changed. Close and select pages again.");compose.onNodeWithTag("batch-apply").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("batch-undo").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle{assertTrue(model().batchEditor.state!!.selectedIds.isEmpty());assertEquals(35,model().documentPages.first{it.id.value==1L}.adjustments.brightness);assertEquals(0,model().documentPages.first{it.id.value==4L}.adjustments.brightness)}
        click("batch-close");open();compose.onNodeWithTag("batch-count").assertTextEquals("0 of 4 pages selected");click("batch-close")
        assertArrayEquals(before,source.readBytes())
    }
    @Test fun largeDocumentSelectionListRemainsVirtualAndCanReachLastPage()=safe {
        compose.runOnIdle {
            val template=model().documentPages.first()
            compose.activity.installDocumentSessionForTest(DocumentSession((1L..5000L).map{template.copy(id=DocumentPageId(it))}))
        }
        open();click("batch-all")
        compose.onNodeWithTag("batch-count").assertTextEquals("5000 of 5000 pages selected")
        compose.onNodeWithTag("batch-pages").performScrollTo()
        val row=SemanticsMatcher("batch page row") { it.config.getOrElse(SemanticsProperties.TestTag){""}.startsWith("batch-page-") }
        val rendered=compose.onAllNodes(row).fetchSemanticsNodes().size
        assertTrue(rendered in 1..20)
        compose.onNodeWithTag("batch-pages").performScrollToIndex(4999)
        compose.onNodeWithTag("batch-page-5000").assertIsDisplayed().assertIsOn()
        click("batch-close");assertArrayEquals(before,source.readBytes())
    }
    @Test fun filterPreviewAndCloseCancelTheDraftWithoutChangingAnyPage()=safe {
        val original=model().documentSession;val revision=model().documentRevision
        open();range("1,3");repeat(3){click("batch-filter")}
        compose.onNodeWithTag("batch-pages").performScrollTo().performScrollToNode(hasTestTag("batch-preview-3"))
        click("batch-preview-3")
        compose.runOnIdle{assertEquals(org.synapseworks.pageharbor.image.DocumentFilter.GRAYSCALE,model().batchEditor.state!!.edit.filter);assertEquals(3L,model().batchEditor.state!!.previewId)}
        brightness(-100f)
        click("batch-close")
        compose.runOnIdle{assertEquals(original,model().documentSession);assertEquals(revision,model().documentRevision);assertNull(model().batchEditor.state)}
        assertArrayEquals(before,source.readBytes())
    }
}
