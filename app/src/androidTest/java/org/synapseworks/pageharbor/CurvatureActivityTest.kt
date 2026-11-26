package org.synapseworks.pageharbor

import android.app.Application
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.*
import org.junit.Assert.*
import org.synapseworks.pageharbor.document.booklayout.BookTestFixtures
import org.synapseworks.pageharbor.document.curvature.*
import org.synapseworks.pageharbor.document.session.*
import org.synapseworks.pageharbor.image.*
import org.synapseworks.pageharbor.security.*

class CurvatureActivityTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private lateinit var root:File
    private fun model()=ViewModelProvider(compose.activity)[CurvatureViewModel::class.java]
    private fun session()=ViewModelProvider(compose.activity)[PageHarborSessionViewModel::class.java]
    @Before fun setup(){
        check(app.packageName.endsWith(".phase2test"));root=File(app.cacheDir,"shared-pdfs/curvature-ui-${UUID.randomUUID()}").apply{mkdirs()}
        val page=CurvatureFixtures.source(root).copy(filter=DocumentFilter.PAPER_WHITE_COLOUR_STRONG_V1,adjustments=ManualImageAdjustments(20,-25))
        val other=CurvatureFixtures.source(root,24,20)
        compose.runOnIdle{val lock=ViewModelProvider(compose.activity)[AppLockViewModel::class.java]
            if(lock.state.value.phase==AppLockPhase.LOCKED)lock.beginAuthentication(AppLockAuthenticationAvailability.AVAILABLE)?.let{lock.onAuthenticationResult(it.id,AppLockAuthenticationResult.Success)}
            lock.disableAfterAuthentication();compose.activity.installDocumentSessionForTest(DocumentSession(listOf(page,other)))}
    }
    @After fun cleanup(){compose.runOnIdle{model().beforePublishForTest=null;model().close();session().clearScan()};root.deleteRecursively()}
    private fun click(tag:String)=compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().performClick()
    private fun ready(){compose.waitUntil(60_000){model().state?.let{it.review!=null&&it.work==null}==true};assertFalse(model().state!!.failed)}
    private fun open(){
        if(compose.onAllNodesWithContentDescription("Edit").fetchSemanticsNodes().isNotEmpty())compose.onNodeWithContentDescription("Edit").assertIsDisplayed().performClick()
        compose.onNodeWithText("Page curvature").performScrollTo().assertIsDisplayed().performClick();ready()
    }
    private fun settled(){compose.waitUntil(30_000){model().state==null&&!session().hasActiveDocumentSessionLeases()}}
    private fun anchor(index:Int,value:Float){compose.onNodeWithTag("curve-anchor-$index").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress){assertTrue(it(value))};ready()}
    @Test fun automaticBeforeAfterUseKeepsOriginalAndUnaffectedEditsWithFreshGeometryIdentity(){
        val before=session().documentSession;val rev=session().documentRevision;val file=File(Uri.parse(before.pages[0].source.reference).path!!);val hash=BookTestFixtures.sha(file)
        open();val owner=model().state!!.owner;compose.runOnIdle{model().onDocumentRevisionChanged(rev)};assertEquals(owner,model().state!!.owner)
        assertEquals(CurvatureStatus.PROPOSED,model().state!!.review!!.initial.estimate.status)
        compose.onNodeWithTag("curve-before").performScrollTo().assertIsDisplayed();compose.onNodeWithTag("curve-after").performScrollTo().assertIsDisplayed()
        click("curve-apply");settled();val after=session().documentSession;assertEquals(2,after.pages.size);assertSame(before.pages[1],after.pages[1]);assertEquals(rev+1,session().documentRevision)
        assertNotEquals(before.pages[0].id,after.pages[0].id);assertNull(after.pages[0].persistentId);assertEquals(before.pages[0].filter,after.pages[0].filter);assertEquals(before.pages[0].adjustments,after.pages[0].adjustments)
        assertEquals(DocumentPageRotation.DEGREES_0,after.pages[0].rotation);assertEquals(hash,BookTestFixtures.sha(file));assertTrue(File(after.pages[0].source.ownedTemporaryFile!!.path).isFile)
        val adopted=File(after.pages[0].source.ownedTemporaryFile!!.path);compose.runOnIdle{session().clearScan()};compose.waitUntil(30_000){!adopted.exists()}
        assertEquals(hash,BookTestFixtures.sha(file))
    }
    @Test fun manualAnchorsTaperResetAndCancelStayDraftOnly(){
        val before=session().documentSession;open();val initial=model().state!!.review!!.field
        anchor(1,.045f);anchor(3,-.035f)
        compose.onNodeWithTag("curve-taper").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress){assertTrue(it(.08f))};ready()
        assertTrue(model().state!!.review!!.manual);assertSame(before,session().documentSession)
        click("curve-reset");ready();assertEquals(initial,model().state!!.review!!.field);assertFalse(model().state!!.review!!.manual)
        click("curve-cancel");settled();assertSame(before,session().documentSession)
    }
    @Test fun flatPageStaysByteExactUnlessManualCorrectionIsExplicitlyRequested(){
        val page=CurvatureFixtures.source(root,24,30);compose.runOnIdle{compose.activity.installDocumentSessionForTest(DocumentSession(listOf(page)))}
        val before=session().documentSession;val rev=session().documentRevision;open();assertFalse(model().state!!.review!!.enabled)
        click("curve-apply");settled();assertSame(before,session().documentSession);assertEquals(rev,session().documentRevision)
        open();click("curve-toggle");ready();anchor(2,.06f);assertTrue(model().state!!.review!!.manual);click("curve-apply");settled();assertEquals(1,session().documentPages.size);assertEquals(rev+1,session().documentRevision)
    }
    @Test fun stalePublicationHookCannotAttachTheWarpToAChangedSession(){
        val before=session().documentSession;open();compose.runOnIdle{val document=session();model().beforePublishForTest={document.rotatePageClockwise(before.pages[1].id.value)}}
        click("curve-apply");settled();assertSame(before.pages[0],session().documentPages[0]);assertEquals(before.pages[1].rotation.clockwise(),session().documentPages[1].rotation)
        assertTrue(File(app.cacheDir,"page-curvature").listFiles().orEmpty().all{it.listFiles().isNullOrEmpty()})
    }
    @Test fun backgroundAndRecreationCancelWithoutClaimingProcessDeathRecovery(){
        val before=session().documentSession;open();compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED);compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED);settled();assertSame(before,session().documentSession)
        open();compose.activityRule.scenario.recreate();settled();assertSame(before,session().documentSession)
    }
    @Test fun appLockClosesTheReviewAndReleasesItsSourceLease(){
        val before=session().documentSession;open();compose.runOnIdle{val lock=ViewModelProvider(compose.activity)[AppLockViewModel::class.java];lock.setup(AutoLockTimeout.IMMEDIATELY,AppLockAuthenticationAvailability.AVAILABLE);lock.lockNow()}
        settled();compose.onAllNodesWithTag("curve-apply").assertCountEquals(0)
        compose.runOnIdle{val lock=ViewModelProvider(compose.activity)[AppLockViewModel::class.java];val a=lock.beginAuthentication(AppLockAuthenticationAvailability.AVAILABLE)!!;lock.onAuthenticationResult(a.id,AppLockAuthenticationResult.Success);lock.disableAfterAuthentication()}
        assertSame(before,session().documentSession)
    }
    @Test fun rapidCorrectionsAndDoubleUsePublishExactlyOneResultWithBoundedFiles(){
        val rev=session().documentRevision;open();compose.runOnIdle{repeat(12){model().setAnchor(1,.02f+it*.001f);model().setAnchor(3,-.02f-it*.001f)}};ready()
        val s=model().state!!;assertEquals(.031f,s.review!!.field!!.offset(.25f),.001f);assertTrue(s.preview!!.after.file.parentFile!!.listFiles().orEmpty().size<=2)
        compose.runOnIdle{compose.activity.applyCurvature();compose.activity.applyCurvature()};settled();assertEquals(rev+1,session().documentRevision);assertEquals(2,session().documentPages.size)
    }
    @Test fun missingSourceFailsWithoutMutationAndEvictedWorkspaceStillReleasesLease(){
        val before=session().documentSession;open();model().state!!.preview!!.before.file.parentFile!!.deleteRecursively();click("curve-cancel");settled();assertSame(before,session().documentSession)
        open();File(Uri.parse(before.pages[0].source.reference).path!!).delete();click("curve-apply")
        compose.waitUntil(30_000){model().state?.failed==true};assertSame(before,session().documentSession);click("curve-cancel");settled()
    }
    @Test fun backCancelLeavesDocumentAndM10CaptureResourcesUntouched(){
        val before=session().documentSession;open();InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);settled();assertSame(before,session().documentSession)
        val book=ViewModelProvider(compose.activity)[org.synapseworks.pageharbor.document.booklayout.BookSplitViewModel::class.java];assertNull(book.state)
    }

    @Test fun adjustingBookCornersKeepsDraftAndSourceUntilApply() {
        val before = session().documentSession
        val original = File(Uri.parse(before.pages.first().source.reference).path!!)
        val hash = BookTestFixtures.sha(original)
        open()
        click("book-crop-adjust")
        val corner = compose.onNodeWithTag("book-crop-corner-0").performScrollTo().fetchSemanticsNode()
        val actions = corner.config[androidx.compose.ui.semantics.SemanticsActions.CustomActions]
        compose.runOnIdle { assertTrue(actions[1].action()) }
        click("book-crop-preview-adjusted")
        ready()
        val review = model().state!!.review!!
        assertTrue(review.straighten)
        assertNotNull(review.perspective)
        assertSame(before, session().documentSession)
        click("curve-apply")
        settled()
        assertNotEquals(before.pages.first().id, session().documentSession.pages.first().id)
        assertEquals(hash, BookTestFixtures.sha(original))
    }


    @Test fun oneFingerScrollsBookReviewAtNormalPreviewZoom() {
        open()
        val preview = compose.onNodeWithTag("curve-after").performScrollTo()
        val priorBottom = preview.fetchSemanticsNode().boundsInRoot.bottom
        preview.performTouchInput {
            swipeUp(startY = bottom * .85f, endY = bottom * .25f, durationMillis = 450)
        }
        assertTrue(preview.fetchSemanticsNode().boundsInRoot.bottom < priorBottom)
    }

}
