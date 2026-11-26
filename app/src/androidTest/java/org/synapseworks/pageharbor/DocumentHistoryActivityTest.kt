package org.synapseworks.pageharbor

import android.app.Application
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.*
import org.junit.Assert.*
import org.synapseworks.pageharbor.document.curvature.CurvatureFixtures
import org.synapseworks.pageharbor.document.session.*
import org.synapseworks.pageharbor.image.*
import org.synapseworks.pageharbor.security.*

class DocumentHistoryActivityTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private lateinit var root:File
    private fun model()=ViewModelProvider(compose.activity)[PageHarborSessionViewModel::class.java]
    private fun unlock(){val lock=ViewModelProvider(compose.activity)[AppLockViewModel::class.java]
        if(lock.state.value.phase==AppLockPhase.LOCKED){val request=lock.beginAuthentication(AppLockAuthenticationAvailability.AVAILABLE)!!;lock.onAuthenticationResult(request.id,AppLockAuthenticationResult.Success)};lock.disableAfterAuthentication()}
    private fun ready(){compose.waitUntil(30_000){!model().historyState.busy}}
    @Before fun setup(){check(app.packageName.endsWith(".phase2test"));root=File(app.cacheDir,"shared-pdfs/history-ui-${UUID.randomUUID()}").apply{mkdirs()}
        val a=CurvatureFixtures.source(root,1,10);val b=CurvatureFixtures.source(root,24,20)
        compose.runOnIdle{unlock();compose.activity.installDocumentSessionForTest(DocumentSession(listOf(a,b)))};ready()}
    @After fun cleanup(){compose.runOnIdle{unlock();model().clearScan()};ready();root.deleteRecursively()}
    private fun click(tag:String){compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled().performClick();ready()}
    @Test fun accessibleControlsShowOperationsDepthAndDirtyStateAcrossUndoRedo(){
        val before=model().documentSession;compose.onNodeWithTag("history-undo").assertIsDisplayed().assertIsNotEnabled();compose.onNodeWithTag("history-redo").assertIsNotEnabled()
        compose.runOnIdle{assertTrue(model().rotatePageClockwise(10))};ready();val after=model().documentSession
        compose.onNodeWithContentDescription("Undo rotation").assertIsDisplayed();compose.onNodeWithTag("history-dirty").assertExists()
        File(app.cacheDir,"m13-history-ui.png").outputStream().use{compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        click("history-undo");assertSame(before,model().documentSession);compose.onNodeWithTag("history-dirty").assertDoesNotExist()
        compose.onNodeWithContentDescription("Redo rotation").assertIsDisplayed();click("history-redo");assertSame(after,model().documentSession)
    }
    @Test fun mixedDeleteReturnAndBranchPreservePageIdentityAndSelectionSafety(){
        val before=model().documentSession
        compose.runOnIdle{model().setPageFilter(20,DocumentFilter.GRAYSCALE)};ready();compose.runOnIdle{model().removePage(10)};ready()
        assertEquals(listOf(20L),model().documentPages.map{it.id.value});click("history-undo");assertEquals(listOf(10L,20L),model().documentPages.map{it.id.value});compose.onNodeWithText("Page 2 of 2").performScrollTo().assertIsDisplayed()
        click("history-undo");assertSame(before,model().documentSession)
        compose.runOnIdle{model().setPageAdjustments(20,ManualImageAdjustments(12,-15))};ready();compose.onNodeWithTag("history-redo").assertIsNotEnabled();assertEquals(0,model().historyState.redoCount)
    }
    @Test fun historySurvivesRecreationAndBackgroundButLockCannotNavigate(){
        compose.runOnIdle{model().rotatePageClockwise(10)};ready();val retained=model();val after=retained.documentSession
        compose.activityRule.scenario.recreate();ready();assertSame(retained,model());assertSame(after,model().documentSession);click("history-undo")
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED);assertFalse(compose.activity.navigateDocumentHistory(false))
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED);ready();click("history-redo")
        compose.runOnIdle{val lock=ViewModelProvider(compose.activity)[AppLockViewModel::class.java];lock.setup(AutoLockTimeout.IMMEDIATELY,AppLockAuthenticationAvailability.AVAILABLE);lock.lockNow();assertFalse(compose.activity.navigateDocumentHistory(true))}
        compose.onAllNodesWithTag("history-undo").assertCountEquals(0);compose.runOnIdle{unlock()};ready();assertSame(after,model().documentSession);click("history-undo")
        // A cold-session cleanup on recreation must also respect a lease after the live document clears.
        val aged=File(app.cacheDir,"document-imports/history-${UUID.randomUUID()}.jpg").apply{parentFile!!.mkdirs();writeBytes(File(android.net.Uri.parse(after.pages[0].source.reference).path!!).readBytes());check(setLastModified(System.currentTimeMillis()-25L*60*60*1000))}
        val page=after.pages[0].copy(source=DocumentResource(android.net.Uri.fromFile(aged).toString(),DocumentResourceOwnership.RME_OWNED_TEMPORARY,OwnedTemporaryFile(aged.path,aged.parentFile!!.path)))
        compose.runOnIdle{compose.activity.installDocumentSessionForTest(DocumentSession(listOf(page)))};ready()
        lateinit var lease:DocumentSessionLease
        compose.runOnIdle{lease=model().acquireDocumentSessionLease()!!;model().clearScan()};ready();compose.activityRule.scenario.recreate();ready();kotlinx.coroutines.runBlocking{kotlinx.coroutines.withTimeout(30_000){compose.activity.sourceStartupCleanupForTest!!.join()}};assertTrue(aged.exists())
        compose.runOnIdle{model().releaseDocumentSessionLease(lease)};compose.waitUntil(30_000){!aged.exists()}
    }
    @Test fun acquisitionAndBatchDraftCannotNavigateAndCancelRecordsNothing(){
        compose.runOnIdle{model().rotatePageClockwise(10)};ready();val after=model().documentSession
        compose.runOnIdle{val t=model().beginContinuousRequest()!!;assertFalse(model().undoDocument());model().cancelContinuousRequest(t);model().batchEditor.open();assertFalse(compose.activity.navigateDocumentHistory(true));model().batchEditor.close()}
        ready();assertSame(after,model().documentSession);assertEquals(1,model().historyState.undoCount);click("history-undo")
    }
    @Test fun missingRestorationDisplaysFailureAndRetainsCurrentDocumentAndCursor(){
        val removed=model().documentPages[0];compose.runOnIdle{model().removePage(10)};ready();val after=model().documentSession;val revision=model().documentRevision
        File(android.net.Uri.parse(removed.source.reference).path!!).delete();click("history-undo");assertSame(after,model().documentSession);assertEquals(revision,model().documentRevision)
        assertEquals(1,model().historyState.undoCount);compose.onNodeWithTag("history-status").assertTextContains("history position are unchanged",substring=true,ignoreCase=true)
    }
    @Test fun rapidActionsAndNewDocumentBoundaryDoNotRecordReplay(){
        compose.runOnIdle{repeat(40){model().rotatePageClockwise(10)}};ready();assertEquals(32,model().historyState.undoCount);assertTrue(model().historyState.limited)
        click("history-undo");assertEquals(31,model().historyState.undoCount);click("history-redo");assertEquals(32,model().historyState.undoCount)
        val newPage=CurvatureFixtures.source(root,24,30);compose.runOnIdle{compose.activity.installDocumentSessionForTest(DocumentSession(listOf(newPage)))};ready()
        compose.onNodeWithTag("history-undo").assertIsNotEnabled();compose.onNodeWithTag("history-redo").assertIsNotEnabled();assertFalse(model().historyState.dirty)
        val pdf=File(root,"legacy.pdf");com.tom_roush.pdfbox.pdmodel.PDDocument().use{it.addPage(com.tom_roush.pdfbox.pdmodel.PDPage());it.save(pdf)}
        compose.runOnIdle{model().replaceScannerReferencesForTest(org.synapseworks.pageharbor.scanner.createScannerResultSummary(0,1),scannedPdfReference=android.net.Uri.fromFile(pdf).toString(),scannedPageReferences=emptyList())};ready()
        compose.onNodeWithText("0 pages ready").performScrollTo().assertIsDisplayed();assertTrue(model().documentSession.canUseDirectPdf);compose.onNodeWithTag("history-undo").assertIsNotEnabled()
    }
}
