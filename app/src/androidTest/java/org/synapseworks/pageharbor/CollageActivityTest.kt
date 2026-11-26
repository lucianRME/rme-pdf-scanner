package org.synapseworks.pageharbor

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.*
import org.junit.Assert.*
import org.synapseworks.pageharbor.document.annotation.*
import org.synapseworks.pageharbor.document.collage.*
import org.synapseworks.pageharbor.document.session.*
import org.synapseworks.pageharbor.security.*

class CollageActivityTest {
 @get:Rule val compose=createAndroidComposeRule<MainActivity>()
 private val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
 private lateinit var root:File
 private fun session()=ViewModelProvider(compose.activity)[PageHarborSessionViewModel::class.java]
 private fun model()=compose.activity.collage
 private fun unlock(){val lock=ViewModelProvider(compose.activity)[AppLockViewModel::class.java];if(lock.state.value.phase==AppLockPhase.LOCKED){val r=lock.beginAuthentication(AppLockAuthenticationAvailability.AVAILABLE)!!;lock.onAuthenticationResult(r.id,AppLockAuthenticationResult.Success)};lock.disableAfterAuthentication()}
 private fun ready(){compose.waitUntil(30_000){!session().historyState.busy}}
 private fun draftReady(){compose.waitUntil(30_000){model().state?.working==false};assertFalse(model().state!!.failed)}
 private fun click(tag:String){val n=compose.onNodeWithTag(tag);if(tag !in setOf("collage-use","collage-cancel","history-undo","history-redo"))n.performScrollTo();n.assertIsDisplayed().assertIsEnabled().performClick()}
 @Before fun setup(){check(app.packageName.endsWith(".phase2test"));root=File(app.cacheDir,"shared-pdfs/collage-ui-${UUID.randomUUID()}").apply{mkdirs()};val a=AnnotationFixtures.source(root).copy(annotations=org.synapseworks.pageharbor.document.signature.SignatureFixtures.layer())
  val b=AnnotationFixtures.source(root,2).copy(annotations=AnnotationLayer.of(listOf(AnnotationMark.create("synthetic-caption",AnnotationTool.TEXT,AnnotationBounds(.1f,.2f,.9f,.8f),text="Synthetic collage sample"))))
  compose.runOnIdle{unlock();compose.activity.installDocumentSessionForTest(DocumentSession(listOf(a,b)))};ready()}
 @After fun cleanup(){compose.runOnIdle{unlock();model().close();session().clearScan()};ready();root.deleteRecursively()}
 private fun open(){compose.onNodeWithText("Edit").performClick();compose.onNodeWithTag("open-collage").performScrollTo().assertIsDisplayed().performClick();draftReady()}
 @Test fun reachableControlsRepeatReorderCropOrientationPreviewAndPinnedAccept(){
  val old=session().documentSession;open();click("collage-slots-6");click("collage-paper-LETTER");click("collage-orientation");click("collage-add");click("collage-add");click("collage-add");draftReady()
  assertEquals(listOf(1L,2L,1L,1L,1L),model().state!!.draft.tiles.map{it.pageId})
  click("collage-tile-4");click("collage-earlier");click("collage-fit");click("collage-rotate");draftReady()
  val d=model().state!!.draft;assertEquals(5,d.tiles.map{it.id}.distinct().size);assertEquals(1,d.tiles[3].turns);assertEquals(CollageFit.CROP,d.tiles[3].fit)
  assertSame(old,session().documentSession);assertEquals(0,session().historyState.undoCount)
  compose.onNodeWithTag("collage-preview-0").performScrollTo().assertIsDisplayed()
  File(app.cacheDir,"m16-collage-review.png").outputStream().use{compose.onNodeWithTag("collage-screen").captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
  click("collage-use");compose.waitUntil(30_000){model().state==null};ready();assertEquals(3,session().documentPages.size);assertSame(old.pages[0],session().documentPages[0]);assertSame(old.pages[1],session().documentPages[1]);assertEquals(1,session().historyState.undoCount)
  assertEquals(792f,session().documentPages.last().paperSize!!.widthPoints,0f);assertEquals(612f,session().documentPages.last().paperSize!!.heightPoints,0f)
  click("history-undo");ready();assertSame(old,session().documentSession);click("history-redo");ready();assertEquals(3,session().documentPages.size)
 }
 @Test fun recreationRetainsDraftAndCancelDoesNotTouchDocument(){
  val old=session().documentSession;open();click("collage-add");click("collage-slots-2");draftReady();val d=model().state!!.draft;val owner=model().state!!.owner
  compose.activityRule.scenario.recreate();draftReady();assertEquals(owner,model().state!!.owner);assertSame(d,model().state!!.draft);click("collage-remove");draftReady();click("collage-reset");draftReady();assertEquals(2,model().state!!.draft.tiles.size)
  click("collage-cancel");assertNull(model().state);assertSame(old,session().documentSession);assertEquals(0,session().historyState.undoCount)
 }
 @Test fun backgroundLockAndRevisionChangeDiscardDraftWithoutPublishing(){
  open();compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED);assertNull(model().state);compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED);ready()
  open();compose.runOnIdle{session().rotatePageClockwise(1)};ready();compose.waitUntil{model().state==null};assertEquals(2,session().documentPages.size)
  open();compose.runOnIdle{val lock=ViewModelProvider(compose.activity)[AppLockViewModel::class.java];lock.setup(AutoLockTimeout.IMMEDIATELY,AppLockAuthenticationAvailability.AVAILABLE);lock.lockNow()};compose.waitUntil{model().state==null};compose.runOnIdle{unlock()};ready();assertEquals(2,session().documentPages.size)
 }
 @Test fun changedSourceAtLateHandoffFailsClosedWithoutAnyAppend(){
  open();val old=session().documentSession;val source=File(android.net.Uri.parse(old.pages[0].source.reference).path!!)
  compose.runOnIdle{model().beforePublishForTest={source.appendBytes(byteArrayOf(1))};compose.activity.applyCollage()}
  compose.waitUntil(30_000){model().state?.failed==true};assertSame(old,session().documentSession);assertEquals(0,session().historyState.undoCount)
  compose.onNodeWithTag("collage-use").assertIsNotEnabled();click("collage-cancel")
 }
 @Test fun lateHandoffCancellationAndStaleRevisionCannotAppend(){
  open();val before=session().documentSession
  compose.runOnIdle{model().beforePublishForTest={model().close()};compose.activity.applyCollage()}
  compose.waitUntil(30_000){model().state==null};ready();assertSame(before,session().documentSession);assertEquals(0,session().historyState.undoCount)
  compose.runOnIdle{model().beforePublishForTest=null};open()
  compose.runOnIdle{model().beforePublishForTest={session().rotatePageClockwise(1)};compose.activity.applyCollage()}
  compose.waitUntil(30_000){model().state==null};ready();assertEquals(2,session().documentPages.size);assertEquals(DocumentPageRotation.DEGREES_90,session().documentPages[0].rotation)
 }
}
