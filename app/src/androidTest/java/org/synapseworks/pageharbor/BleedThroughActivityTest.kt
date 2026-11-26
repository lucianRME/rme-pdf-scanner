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
import org.synapseworks.pageharbor.image.DocumentFilter
import org.synapseworks.pageharbor.document.batch.*

class BleedThroughActivityTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private lateinit var root: File
    private lateinit var source: File
    private lateinit var before: ByteArray
    private fun model() = ViewModelProvider(compose.activity)[PageHarborSessionViewModel::class.java]
    @Before fun setup() {
        check(app.packageName.endsWith(".phase2test"))
        root = File(app.cacheDir,"shared-pdfs/m25-${UUID.randomUUID()}").apply { check(mkdirs()) }
        source = File(root,"source.png")
        source.writeBytes(InstrumentationRegistry.getInstrumentation().context.assets.open("m25/medium-source.png").use{it.readBytes()})
        before = source.readBytes()
        val resource = DocumentResource(FileProvider.getUriForFile(app,"${app.packageName}.fileprovider",source).toString(),DocumentResourceOwnership.USER_OR_EXTERNAL)
        compose.runOnIdle {
            val lock = ViewModelProvider(compose.activity)[AppLockViewModel::class.java]
            if (lock.state.value.phase == AppLockPhase.LOCKED) lock.beginAuthentication(AppLockAuthenticationAvailability.AVAILABLE)?.let {
                lock.onAuthenticationResult(it.id,AppLockAuthenticationResult.Success)
            }
            lock.disableAfterAuthentication()
            compose.activity.installDocumentSessionForTest(DocumentSession((1L..3L).map {
                DocumentPage(DocumentPageId(it),resource,DocumentSourceCategory.SCAN,imageMetadata=DocumentImageMetadata(source.length(),512,384),adjustments=ManualImageAdjustments(-10,15))
            }))
        }
    }
    @After fun cleanup() { root.deleteRecursively() }
    private var step="setup"
    private fun safe(block: () -> Unit) {
        try { block() } catch (e: Throwable) {
            val line=e.stackTrace.firstOrNull { it.className.startsWith("org.synapseworks") }?.lineNumber
            throw AssertionError("M25 UI assertion failed: ${e.javaClass.simpleName}, line $line, control $step" + if(e.message?.let{it.startsWith("M06 slider numeric mismatch")||it.startsWith("M25 batch step ")}==true) " ${e.message}" else "")
        }
    }
    private fun open() {
        if(compose.onAllNodesWithContentDescription("Edit").fetchSemanticsNodes().isNotEmpty())compose.onNodeWithContentDescription("Edit").assertIsDisplayed().performClick()
        compose.onNodeWithText("Bleed-through removal").performScrollTo().assertIsDisplayed().performClick()
    }
    private fun click(tag:String){step=tag;compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().performClick()}
    private fun previewReady() {
        compose.waitUntil(30_000){compose.onAllNodesWithText(app.getString(R.string.scan_preview_loading)).fetchSemanticsNodes().isEmpty()}
        compose.onAllNodesWithText(app.getString(R.string.scan_preview_unavailable)).assertCountEquals(0)
    }
    @Test fun reviewComparisonAndIntensityApplyOnlyTheCurrentPage()=safe {
        val original=model().documentSession;val revision=model().documentRevision
        open();compose.onNodeWithTag("bleed-level-2").assertIsSelected()
        click("bleed-compare");compose.onNodeWithTag("bleed-comparison").assertTextEquals("Before · current edits")
        click("bleed-level-3");compose.onNodeWithTag("bleed-level-3").assertIsSelected()
        compose.onNodeWithTag("bleed-comparison").assertTextEquals("After · proposed filter")
        previewReady()
        compose.onNodeWithTag("bleed-comparison").performScrollTo()
        val evidence=File(app.getExternalFilesDir(null),"m25-evidence").apply{mkdirs()}
        val screenshot=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        try{File(evidence,"ordinary-preview.png").outputStream().use{check(screenshot.compress(Bitmap.CompressFormat.PNG,100,it))}}finally{screenshot.recycle()}
        assertEquals(revision,model().documentRevision)
        click("bleed-apply")
        compose.runOnIdle {
            assertEquals(original.pages[0].copy(filter=DocumentFilter.BLEED_THROUGH_STRONG_V1),model().documentPages[0])
            assertEquals(original.pages.drop(1),model().documentPages.drop(1));assertEquals(revision+1,model().documentRevision)
        }
        compose.waitUntil(30_000){!model().historyState.busy}
        compose.onNodeWithTag("history-undo").assertIsDisplayed().assertIsEnabled().performClick()
        compose.waitUntil(30_000){model().documentSession==original&&!model().historyState.busy}
        assertEquals(revision+2,model().documentRevision)
        assertArrayEquals(before,source.readBytes())
    }
    @Test fun resetAndCancelPreserveManualSettingsAndNoOpRevisions()=safe {
        val original=model().documentSession;val revision=model().documentRevision
        open();click("bleed-level-1");click("bleed-reset");compose.onNodeWithTag("bleed-off").assertExists();click("bleed-cancel")
        assertEquals(original,model().documentSession);assertEquals(revision,model().documentRevision)
        open();click("bleed-level-3");click("bleed-apply")
        open();click("bleed-reset");click("bleed-apply")
        assertEquals(original,model().documentSession);assertEquals(revision+2,model().documentRevision)
        open();click("bleed-reset");click("bleed-apply");assertEquals(revision+2,model().documentRevision)
        assertArrayEquals(before,source.readBytes())
    }
    @Test fun recreationRetainsReviewedIntensityAndBeforeStateWithoutApplying()=safe {
        val revision=model().documentRevision;val owner=model().documentOwnerToken
        open();click("bleed-level-1");click("bleed-compare")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("bleed-level-1").assertIsSelected()
        compose.onNodeWithTag("bleed-comparison").assertTextEquals("Before · current edits")
        assertEquals(owner,model().documentOwnerToken);assertEquals(revision,model().documentRevision)
        click("bleed-apply");assertEquals(DocumentFilter.BLEED_THROUGH_LOW_V1,model().documentPages[0].filter)
        assertEquals(revision+1,model().documentRevision);assertArrayEquals(before,source.readBytes())
    }
    @Test fun rapidComparisonsCloseOnDocumentMutationInsteadOfRetargeting()=safe {
        open();repeat(6){click("bleed-level-${it%3+1}");click("bleed-compare")}
        compose.runOnIdle{model().reorderPages(listOf(3,2,1))}
        compose.waitUntil(30_000){compose.onAllNodesWithTag("bleed-apply").fetchSemanticsNodes().isEmpty()}
        assertTrue(model().documentPages.all{it.filter==DocumentFilter.ORIGINAL})
        open();click("bleed-level-2");click("bleed-cancel");assertTrue(model().documentPages.all{it.filter==DocumentFilter.ORIGINAL})
        assertArrayEquals(before,source.readBytes())
    }
    @Test fun filterChoiceOpensReviewWithoutMutatingTheDocument()=safe {
        val original=model().documentSession;val revision=model().documentRevision
        compose.onNodeWithContentDescription("Edit").assertIsDisplayed().performClick()
        compose.onNodeWithText(app.getString(R.string.document_edit_filter)).performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Bleed-through · Strong").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("bleed-level-3").assertIsSelected()
        assertEquals(original,model().documentSession);assertEquals(revision,model().documentRevision)
        click("bleed-cancel");assertEquals(original,model().documentSession);assertArrayEquals(before,source.readBytes())
    }
    @Test fun selectedPageBatchUsesApprovedVersionedFilterAndSingleBatchUndo()=safe {
        val original=model().documentSession;val revision=model().documentRevision
        compose.onNodeWithContentDescription("Edit").assertIsDisplayed().performClick()
        compose.onNodeWithText("Edit selected pages").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("batch-range").performScrollTo().performTextReplacement("1,3")
        click("batch-select-range")
        // Observe each rendered transition instead of issuing an unchecked burst of taps.
        val expectedSteps=listOf(DocumentFilter.ORIGINAL,DocumentFilter.AUTO_ENHANCE,DocumentFilter.GRAYSCALE,
            DocumentFilter.BLACK_AND_WHITE,DocumentFilter.HIGH_CONTRAST,DocumentFilter.DOCUMENT_CLEANUP,
            DocumentFilter.SHADOW_GLARE_LOW_V1,DocumentFilter.SHADOW_GLARE_BALANCED_V1,DocumentFilter.SHADOW_GLARE_STRONG_V1,
            DocumentFilter.PAPER_WHITE_NEUTRAL_LOW_V1,DocumentFilter.PAPER_WHITE_NEUTRAL_BALANCED_V1,DocumentFilter.PAPER_WHITE_NEUTRAL_STRONG_V1,
            DocumentFilter.PAPER_WHITE_COLOUR_LOW_V1,DocumentFilter.PAPER_WHITE_COLOUR_BALANCED_V1,DocumentFilter.PAPER_WHITE_COLOUR_STRONG_V1,
            DocumentFilter.BLEED_THROUGH_LOW_V1,DocumentFilter.BLEED_THROUGH_BALANCED_V1,DocumentFilter.BLEED_THROUGH_STRONG_V1)
        expectedSteps.forEachIndexed{index,expected->
            click("batch-filter");compose.waitForIdle()
            compose.runOnIdle{assertEquals("M25 batch step ${index+1}",expected,model().batchEditor.state!!.edit.filter)}
        }
        compose.runOnIdle{assertEquals(DocumentFilter.BLEED_THROUGH_STRONG_V1,model().batchEditor.state!!.edit.filter)}
        click("batch-apply");compose.waitUntil(30_000){model().batchEditor.state?.outcome==BatchEditOutcome.APPLIED}
        compose.runOnIdle {
            assertEquals(original.pages[0].copy(filter=DocumentFilter.BLEED_THROUGH_STRONG_V1),model().documentPages[0])
            assertEquals(original.pages[1],model().documentPages[1]);assertEquals(original.pages[2].copy(filter=DocumentFilter.BLEED_THROUGH_STRONG_V1),model().documentPages[2]);assertEquals(revision+1,model().documentRevision)
        }
        click("batch-undo");compose.waitUntil(30_000){model().batchEditor.state?.outcome==BatchEditOutcome.UNDONE}
        assertEquals(original,model().documentSession);assertEquals(revision+2,model().documentRevision);assertArrayEquals(before,source.readBytes())
    }
}
