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

class ShadowGlareActivityTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private lateinit var root: File
    private lateinit var source: File
    private lateinit var before: ByteArray
    private fun model() = ViewModelProvider(compose.activity)[PageHarborSessionViewModel::class.java]
    @Before fun setup() {
        check(app.packageName.endsWith(".phase2test"))
        root = File(app.cacheDir,"shared-pdfs/m08-${UUID.randomUUID()}").apply { check(mkdirs()) }
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
            compose.activity.installDocumentSessionForTest(DocumentSession((1L..3L).map {
                DocumentPage(DocumentPageId(it),resource,DocumentSourceCategory.SCAN,imageMetadata=DocumentImageMetadata(source.length(),120,160),adjustments=ManualImageAdjustments(20,-25))
            }))
        }
    }
    @After fun cleanup() { root.deleteRecursively() }
    private fun safe(block: () -> Unit) {
        try { block() } catch (e: Throwable) {
            val line=e.stackTrace.firstOrNull { it.className.startsWith("org.synapseworks") }?.lineNumber
            throw AssertionError("M08 UI assertion failed: ${e.javaClass.simpleName}, line $line" + if(e.message?.startsWith("M06 slider numeric mismatch")==true) " ${e.message}" else "")
        }
    }
    private fun open() {
        if(compose.onAllNodesWithContentDescription("Edit").fetchSemanticsNodes().isNotEmpty())compose.onNodeWithContentDescription("Edit").assertIsDisplayed().performClick()
        compose.onNodeWithText("Shadow & glare").performScrollTo().assertIsDisplayed().performClick()
    }
    private fun click(tag:String)=compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().performClick()
    @Test fun reviewComparisonAndIntensityApplyOnlyTheCurrentPage()=safe {
        val original=model().documentSession;val revision=model().documentRevision
        open();compose.onNodeWithTag("shadow-level-2").assertIsSelected()
        click("shadow-compare");compose.onNodeWithTag("shadow-comparison").assertTextEquals("Before · current edits")
        click("shadow-level-3");compose.onNodeWithTag("shadow-level-3").assertIsSelected()
        compose.onNodeWithTag("shadow-comparison").assertTextEquals("After · proposed filter")
        assertEquals(revision,model().documentRevision)
        click("shadow-apply")
        compose.runOnIdle {
            assertEquals(original.pages[0].copy(filter=DocumentFilter.SHADOW_GLARE_STRONG_V1),model().documentPages[0])
            assertEquals(original.pages.drop(1),model().documentPages.drop(1));assertEquals(revision+1,model().documentRevision)
        }
        assertArrayEquals(before,source.readBytes())
    }
    @Test fun resetAndCancelPreserveManualSettingsAndNoOpRevisions()=safe {
        val original=model().documentSession;val revision=model().documentRevision
        open();click("shadow-level-1");click("shadow-reset");compose.onNodeWithTag("shadow-off").assertExists();click("shadow-cancel")
        assertEquals(original,model().documentSession);assertEquals(revision,model().documentRevision)
        open();click("shadow-level-3");click("shadow-apply")
        open();click("shadow-reset");click("shadow-apply")
        assertEquals(original,model().documentSession);assertEquals(revision+2,model().documentRevision)
        open();click("shadow-reset");click("shadow-apply");assertEquals(revision+2,model().documentRevision)
        assertArrayEquals(before,source.readBytes())
    }
    @Test fun recreationRetainsReviewedIntensityAndBeforeStateWithoutApplying()=safe {
        val revision=model().documentRevision;val owner=model().documentOwnerToken
        open();click("shadow-level-1");click("shadow-compare")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("shadow-level-1").assertIsSelected()
        compose.onNodeWithTag("shadow-comparison").assertTextEquals("Before · current edits")
        assertEquals(owner,model().documentOwnerToken);assertEquals(revision,model().documentRevision)
        click("shadow-apply");assertEquals(DocumentFilter.SHADOW_GLARE_LOW_V1,model().documentPages[0].filter)
        assertEquals(revision+1,model().documentRevision);assertArrayEquals(before,source.readBytes())
    }
    @Test fun rapidComparisonsCloseOnDocumentMutationInsteadOfRetargeting()=safe {
        open();repeat(6){click("shadow-level-${it%3+1}");click("shadow-compare")}
        compose.runOnIdle{model().reorderPages(listOf(3,2,1))}
        compose.waitUntil(30_000){compose.onAllNodesWithTag("shadow-apply").fetchSemanticsNodes().isEmpty()}
        assertTrue(model().documentPages.all{it.filter==DocumentFilter.ORIGINAL})
        open();click("shadow-level-2");click("shadow-cancel");assertTrue(model().documentPages.all{it.filter==DocumentFilter.ORIGINAL})
        assertArrayEquals(before,source.readBytes())
    }
    @Test fun filterChoiceOpensReviewWithoutMutatingTheDocument()=safe {
        val original=model().documentSession;val revision=model().documentRevision
        compose.onNodeWithContentDescription("Edit").assertIsDisplayed().performClick()
        compose.onNodeWithText(app.getString(R.string.document_edit_filter)).performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Shadow & glare · Strong").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("shadow-level-3").assertIsSelected()
        assertEquals(original,model().documentSession);assertEquals(revision,model().documentRevision)
        click("shadow-cancel");assertEquals(original,model().documentSession);assertArrayEquals(before,source.readBytes())
    }
    @Test fun selectedPageBatchUsesApprovedVersionedFilterAndSingleBatchUndo()=safe {
        val original=model().documentSession;val revision=model().documentRevision
        compose.onNodeWithContentDescription("Edit").assertIsDisplayed().performClick()
        compose.onNodeWithText("Edit selected pages").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("batch-range").performScrollTo().performTextReplacement("1,3")
        click("batch-select-range");repeat(8){click("batch-filter")}
        compose.runOnIdle{assertEquals(DocumentFilter.SHADOW_GLARE_BALANCED_V1,model().batchEditor.state!!.edit.filter)}
        click("batch-apply");compose.waitUntil(30_000){model().batchEditor.state?.outcome==BatchEditOutcome.APPLIED}
        compose.runOnIdle {
            assertEquals(original.pages[0].copy(filter=DocumentFilter.SHADOW_GLARE_BALANCED_V1),model().documentPages[0])
            assertEquals(original.pages[1],model().documentPages[1]);assertEquals(original.pages[2].copy(filter=DocumentFilter.SHADOW_GLARE_BALANCED_V1),model().documentPages[2]);assertEquals(revision+1,model().documentRevision)
        }
        click("batch-undo");compose.waitUntil(30_000){model().batchEditor.state?.outcome==BatchEditOutcome.UNDONE}
        assertEquals(original,model().documentSession);assertEquals(revision+2,model().documentRevision);assertArrayEquals(before,source.readBytes())
    }
}
