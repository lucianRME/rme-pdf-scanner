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
import org.synapseworks.pageharbor.document.booklayout.*
import org.synapseworks.pageharbor.document.session.*
import org.synapseworks.pageharbor.image.*
import org.synapseworks.pageharbor.security.*

class BookSplitActivityTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private lateinit var root:File
    private fun model()=ViewModelProvider(compose.activity)[BookSplitViewModel::class.java]
    private fun session()=ViewModelProvider(compose.activity)[PageHarborSessionViewModel::class.java]
    @Before fun setup(){
        check(app.packageName.endsWith(".phase2test"));root=File(app.cacheDir,"shared-pdfs/book-ui-${UUID.randomUUID()}").apply{mkdirs()}
        val spread=BookTestFixtures.source(root,1,10).copy(filter=DocumentFilter.PAPER_WHITE_COLOUR_STRONG_V1,adjustments=ManualImageAdjustments(20,-25))
        val other=BookTestFixtures.source(root,24,20)
        compose.runOnIdle{
            val lock=ViewModelProvider(compose.activity)[AppLockViewModel::class.java]
            if(lock.state.value.phase==AppLockPhase.LOCKED)lock.beginAuthentication(AppLockAuthenticationAvailability.AVAILABLE)?.let{lock.onAuthenticationResult(it.id,AppLockAuthenticationResult.Success)}
            lock.disableAfterAuthentication();compose.activity.installDocumentSessionForTest(DocumentSession(listOf(spread,other)))
        }
    }
    @After fun cleanup(){compose.runOnIdle{model().beforePublishForTest=null;model().close();session().clearScan()};root.deleteRecursively()}
    private fun click(tag:String)=compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().performClick()
    private fun ready(){compose.waitUntil(60_000){model().state?.let{it.reviews.isNotEmpty()&&it.work==null}==true};assertFalse(model().state!!.failed)}
    private fun open(){
        if(compose.onAllNodesWithContentDescription("Edit").fetchSemanticsNodes().isNotEmpty())compose.onNodeWithContentDescription("Edit").assertIsDisplayed().performClick()
        compose.onNodeWithText("Book split").performScrollTo().assertIsDisplayed().performClick();ready()
    }
    private fun settled(){compose.waitUntil(30_000){model().state==null&&!session().hasActiveDocumentSessionLeases()}}
    @Test fun automaticPreviewAndRightFirstUseKeepOriginalBytesAndUnselectedEdits(){
        val original=session().documentSession;val revision=session().documentRevision;val source=File(Uri.parse(original.pages[0].source.reference).path!!);val hash=BookTestFixtures.sha(source)
        open();val reviewOwner=model().state!!.owner
        compose.runOnIdle{model().onDocumentRevisionChanged(session().documentRevision)}
        assertEquals(reviewOwner,model().state!!.owner)
        assertEquals(BookDetectionStatus.CONFIDENT,model().state!!.current!!.analysis.detection.status)
        compose.onNodeWithTag("book-half-1").performScrollTo().assertIsDisplayed();compose.onNodeWithTag("book-half-2").performScrollTo().assertIsDisplayed()
        click("book-rtl");ready();compose.onNodeWithTag("book-rtl").assertIsSelected();click("book-apply");settled()
        val pages=session().documentPages;assertEquals(3,pages.size);assertSame(original.pages[1],pages[2]);assertEquals(revision+1,session().documentRevision)
        assertTrue(pages[0].imageMetadata.width!!>pages[1].imageMetadata.width!!)
        for(page in pages.take(2)){assertEquals(original.pages[0].filter,page.filter);assertEquals(original.pages[0].adjustments,page.adjustments);assertEquals(DocumentPageRotation.DEGREES_0,page.rotation);assertTrue(File(page.source.ownedTemporaryFile!!.path).exists())}
        assertEquals(hash,BookTestFixtures.sha(source))
    }
    @Test fun geometryRotationAndResetAreReviewOnlyUntilUse(){
        val original=session().documentSession;open();val initial=model().state!!.current!!.line!!
        compose.onNodeWithTag("book-top").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress){assertTrue(it(.45f))};ready()
        compose.onNodeWithTag("book-bottom").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress){assertTrue(it(.55f))};ready()
        assertTrue(model().state!!.current!!.manual);assertEquals(.45f,model().state!!.current!!.line!!.top,.001f)
        click("book-reset");ready();assertEquals(initial,model().state!!.current!!.line);assertFalse(model().state!!.current!!.manual)
        click("book-rotate");ready();assertEquals(1,model().state!!.current!!.turns)
        click("book-reset");ready();assertEquals(0,model().state!!.current!!.turns);assertSame(original,session().documentSession)
        click("book-cancel");settled();assertSame(original,session().documentSession)
    }
    @Test fun ambiguousInputCanRemainUnchangedOrBeSplitOnlyAfterManualChoice(){
        val page=BookTestFixtures.source(root,26,30)
        compose.runOnIdle{compose.activity.installDocumentSessionForTest(DocumentSession(listOf(page)))}
        val original=session().documentSession;val revision=session().documentRevision;open();assertFalse(model().state!!.current!!.split)
        click("book-apply");settled();assertSame(original,session().documentSession);assertEquals(revision,session().documentRevision)
        open();click("book-toggle-split");ready();assertTrue(model().state!!.current!!.manual);assertEquals(2,model().state!!.preview!!.halves.size)
        click("book-apply");settled();assertEquals(2,session().documentPages.size)
    }
    @Test fun staleRevisionAtPublicationRejectsAllNewHalvesAndReleasesTheLease(){
        val original=session().documentSession;open()
        compose.runOnIdle{val document=session();model().beforePublishForTest={document.rotatePageClockwise(original.pages[1].id.value)}}
        click("book-apply");settled();assertEquals(2,session().documentPages.size);assertSame(original.pages[0],session().documentPages[0])
        assertEquals(original.pages[1].rotation.clockwise(),session().documentPages[1].rotation)
        assertTrue(File(app.cacheDir,"book-splits").listFiles().orEmpty().all{it.listFiles().isNullOrEmpty()})
    }
    @Test fun progressiveAllPageReviewAndBackCancelLeaveTheOriginalDocument(){
        val original=session().documentSession;open();click("book-all");ready();assertEquals(2,model().state!!.reviews.size)
        click("book-next");ready();assertFalse(model().state!!.current!!.split);click("book-previous");ready()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);settled();assertSame(original,session().documentSession)
    }
    @Test fun backgroundAndRecreationCloseReviewWithoutPretendingToRecoverAfterProcessDeath(){
        val original=session().documentSession;open();compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED);compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        settled();assertSame(original,session().documentSession)
        open();compose.activityRule.scenario.recreate();settled();assertSame(original,session().documentSession)
    }
    @Test fun appLockClosesReviewAndCannotPublishHiddenWork(){
        val original=session().documentSession;open()
        compose.runOnIdle{val lock=ViewModelProvider(compose.activity)[AppLockViewModel::class.java];lock.setup(AutoLockTimeout.IMMEDIATELY,AppLockAuthenticationAvailability.AVAILABLE);lock.lockNow()}
        settled();compose.onAllNodesWithTag("book-apply").assertCountEquals(0)
        compose.runOnIdle{val lock=ViewModelProvider(compose.activity)[AppLockViewModel::class.java];val attempt=lock.beginAuthentication(AppLockAuthenticationAvailability.AVAILABLE)!!;lock.onAuthenticationResult(attempt.id,AppLockAuthenticationResult.Success);lock.disableAfterAuthentication()}
        assertSame(original,session().documentSession)
    }
    @Test fun rapidCorrectionsAndRepeatedUsePublishOneBatchWithBoundedPreviewFiles(){
        val revision=session().documentRevision;open()
        compose.runOnIdle{repeat(12){model().setTop(.32f+it*.002f);model().setBottom(.38f-it*.001f)}};ready()
        val s=model().state!!;assertEquals(.342f,s.current!!.line!!.top,.001f)
        val directory=s.preview!!.spread.file.parentFile!!;assertTrue(directory.listFiles().orEmpty().size<=3)
        compose.runOnIdle{compose.activity.applyBookSplit();compose.activity.applyBookSplit()};settled()
        assertEquals(revision+1,session().documentRevision);assertEquals(3,session().documentPages.size)
    }
    @Test fun missingReviewedSourceFailsWithoutReplacingTheDocument(){
        val original=session().documentSession;open()
        assertTrue(model().state!!.preview!!.spread.file.parentFile!!.deleteRecursively())
        click("book-cancel");settled();assertSame(original,session().documentSession)
        open();assertTrue(File(Uri.parse(original.pages[0].source.reference).path!!).delete())
        click("book-apply");compose.waitUntil(30_000){model().state?.failed==true};compose.onNodeWithTag("book-error").performScrollTo().assertIsDisplayed()
        assertSame(original,session().documentSession);click("book-cancel");settled()
    }
}
