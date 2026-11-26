package org.synapseworks.pageharbor

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.geometry.Offset
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.*
import org.junit.Assert.*
import org.synapseworks.pageharbor.document.session.*
import org.synapseworks.pageharbor.document.annotation.AnnotationPoint
import org.synapseworks.pageharbor.document.objectremoval.*
import org.synapseworks.pageharbor.security.*
import org.synapseworks.pageharbor.image.*

class ObjectRemovalActivityTest {
 @get:Rule val compose=createAndroidComposeRule<MainActivity>()
 private val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
 private lateinit var root:File;private lateinit var source:File;private lateinit var before:ByteArray
 private fun session()=ViewModelProvider(compose.activity)[PageHarborSessionViewModel::class.java]
 private fun editor()=compose.activity.objectRemoval
 @Before fun setup(){check(app.packageName.endsWith(".phase2test"));root=File(app.cacheDir,"shared-pdfs/m28-ui-${UUID.randomUUID()}").apply{mkdirs()};source=File(root,"source.png");load("dark-spot")
  compose.runOnIdle{val lock=ViewModelProvider(compose.activity)[AppLockViewModel::class.java];if(lock.state.value.phase==AppLockPhase.LOCKED)lock.beginAuthentication(AppLockAuthenticationAvailability.AVAILABLE)?.let{lock.onAuthenticationResult(it.id,AppLockAuthenticationResult.Success)};lock.disableAfterAuthentication();install()}
 }
 private fun load(profile:String){source.writeBytes(InstrumentationRegistry.getInstrumentation().context.assets.open("m28/$profile-512-source.png").use{it.readBytes()});before=source.readBytes()}
 private fun install(rotation:DocumentPageRotation=DocumentPageRotation.DEGREES_0){
  val resource=DocumentResource(FileProvider.getUriForFile(app,"${app.packageName}.fileprovider",source).toString(),DocumentResourceOwnership.USER_OR_EXTERNAL)
  compose.activity.installDocumentSessionForTest(DocumentSession((1L..2L).map{DocumentPage(DocumentPageId(it),resource,DocumentSourceCategory.SCAN,"image/png",DocumentImageMetadata(source.length(),512,384),rotation,adjustments=ManualImageAdjustments(-10,15))}))
 }
 @After fun cleanup(){compose.runOnIdle{editor().close();session().clearScan()};root.deleteRecursively()}
 private fun click(tag:String){val node=compose.onNodeWithTag(tag);if(tag !in setOf("removal-cancel","removal-preview","removal-apply"))node.performScrollTo();node.assertIsDisplayed().performClick()}
 private fun open(){if(compose.onAllNodesWithContentDescription("Edit").fetchSemanticsNodes().isNotEmpty())compose.onNodeWithContentDescription("Edit").performClick();compose.onNodeWithText("Object-removal brush").performScrollTo().performClick();ready()}
 private fun ready(){compose.waitUntil(30_000){editor().state?.analysis!=null&&editor().state?.work==null};compose.waitUntil(30_000){compose.onAllNodesWithTag("removal-loading").fetchSemanticsNodes().isEmpty()};compose.onAllNodesWithTag("removal-unavailable").assertCountEquals(0)}
 private fun paint(x:Float=286f/512,y:Float=245f/384){
  val rotation=editor().state!!.page.rotation.degrees;val p=AnnotationPoint(x,y).rotated(rotation);val iw=if(rotation%180==0)512f else 384f;val ih=if(rotation%180==0)384f else 512f
  compose.onNodeWithTag("removal-canvas").performScrollTo().performTouchInput{val scale=minOf(width/iw,height/ih);click(Offset(width/2f+(p.x-.5f)*iw*scale,height/2f+(p.y-.5f)*ih*scale))};compose.waitUntil(30_000){editor().state?.work==null&&editor().state?.mask!=null}
 }
 private fun preview(){click("removal-preview");compose.waitUntil(30_000){editor().state?.work==null};compose.waitUntil(30_000){editor().state?.prepared==null||editor().state?.reviewedDigest!=null};assertEquals(RemovalStatus.READY,editor().state?.status);compose.onNodeWithTag("removal-apply").assertIsEnabled()}
 @Test fun actualPaintReviewApplyUndoRedoUsesExactSnapshotAndOneTransaction(){
  val initial=session().documentSession;val revision=session().documentRevision;open();compose.onNodeWithTag("removal-apply").assertIsNotEnabled();paint();preview()
  val prepared=editor().state!!.prepared!!;val result=File(prepared.result.source.ownedTemporaryFile!!.path);val snapshot=File(prepared.snapshot.ownedTemporaryFile!!.path);val resultBytes=result.readBytes();assertArrayEquals(before,snapshot.readBytes())
  val evidence=File(app.getExternalFilesDir(null),"m28-evidence").apply{mkdirs()};val screenshot=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot();try{File(evidence,"ordinary-preview.png").outputStream().use{screenshot.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{screenshot.recycle()}
  click("removal-apply");compose.waitUntil(30_000){editor().state==null&&!session().historyState.busy};assertEquals(revision+1,session().documentRevision);assertEquals(1,session().historyState.undoCount);assertSame(initial.pages[1],session().documentPages[1]);assertNotEquals(initial.pages[0].id,session().documentPages[0].id)
  val accepted=session().documentSession;compose.onNodeWithTag("history-undo").performClick();compose.waitUntil(30_000){!session().historyState.busy&&session().historyState.redoCount==1};assertEquals(prepared.snapshot,session().documentPages[0].source);assertFalse(session().historyState.dirty);assertArrayEquals(before,snapshot.readBytes())
  compose.onNodeWithTag("history-redo").performClick();compose.waitUntil(30_000){!session().historyState.busy&&session().documentSession===accepted};assertArrayEquals(resultBytes,result.readBytes());assertArrayEquals(before,source.readBytes())
 }
 @Test fun eraseUndoClearComparisonAndCancelNeverMutateTheSource(){
  val initial=session().documentSession;val revision=session().documentRevision;open();paint();val count=editor().state!!.mask!!.copyCells().count{it};click("removal-erase");compose.runOnIdle{editor().radius(5)};paint();assertTrue(editor().state!!.mask!!.copyCells().count{it}<count);click("removal-undo-mask");compose.waitUntil(30_000){editor().state?.work==null};assertEquals(count,editor().state!!.mask!!.copyCells().count{it})
  click("removal-clear");compose.waitUntil(30_000){editor().state?.work==null};assertFalse(editor().state!!.mask!!.copyCells().any{it});click("removal-draw");compose.runOnIdle{editor().radius(14)};paint();preview();click("removal-view-BEFORE");click("removal-view-AFTER");val work=editor().state!!.analysis!!.snapshot.file.parentFile!!;click("removal-cancel");compose.waitUntil(30_000){!work.exists()};assertSame(initial,session().documentSession);assertEquals(revision,session().documentRevision);assertArrayEquals(before,source.readBytes())
 }
 @Test fun maskChangeInvalidatesFrozenResultAndStaleReviewCallback(){
  open();paint();preview();val old=editor().state!!.prepared!!;val file=File(old.result.source.ownedTemporaryFile!!.path);click("removal-clear");compose.waitUntil(30_000){editor().state?.work==null};compose.runOnIdle{editor().markReviewed(old)};assertNull(editor().state?.prepared);assertNull(editor().state?.reviewedDigest);assertFalse(file.exists());compose.onNodeWithTag("removal-apply").assertIsNotEnabled();click("removal-cancel")
 }
 @Test fun sourceReplacementAndLateDocumentMutationCannotPublish(){
  open();paint();preview();val initial=session().documentSession;source.appendBytes(byteArrayOf(0));click("removal-apply");compose.waitUntil(30_000){editor().state?.work==null};assertTrue(editor().state!!.failed);assertSame(initial,session().documentSession);click("removal-cancel");source.writeBytes(before)
  open();paint();preview();compose.runOnIdle{editor().beforePublishForTest={session().rotatePageClockwise(1)}};click("removal-apply");compose.waitUntil(30_000){editor().state==null};assertEquals(initial.pages[0].id,session().documentPages[0].id);assertEquals(DocumentPageRotation.DEGREES_90,session().documentPages[0].rotation);assertArrayEquals(before,source.readBytes());compose.runOnIdle{editor().beforePublishForTest=null}
 }
 @Test fun recreationAndBackgroundDiscardUnacceptedDraftFiles(){
  val initial=session().documentSession;open();paint();preview();val work=editor().state!!.analysis!!.snapshot.file.parentFile!!;compose.activityRule.scenario.recreate();compose.waitUntil(30_000){editor().state==null&&!work.exists()};assertSame(initial,session().documentSession)
  open();paint();val second=editor().state!!.analysis!!.snapshot.file.parentFile!!;compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED);compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED);compose.waitUntil(30_000){editor().state==null&&!second.exists()};assertSame(initial,session().documentSession);assertArrayEquals(before,source.readBytes())
 }
 @Test fun rotatedPaintUsesExactSourceOccurrenceAndKeepsRotation(){
  compose.runOnIdle{install(DocumentPageRotation.DEGREES_90)};open();paint();preview();val p=editor().state!!.prepared!!;val bitmap=BitmapFactory.decodeFile(p.result.source.ownedTemporaryFile!!.path);try{assertEquals(232,bitmap.getPixel(286,245)and 255)}finally{bitmap.recycle()};click("removal-apply");compose.waitUntil(30_000){editor().state==null};assertEquals(DocumentPageRotation.DEGREES_90,session().documentPages[0].rotation);assertArrayEquals(before,source.readBytes())
 }
 @Test fun crossingLineDeclinesWithNoApplyOrHistoryChange(){
  load("cross-line");compose.runOnIdle{install()};val revision=session().documentRevision;open();paint(410f/512,75f/384);click("removal-preview");compose.waitUntil(30_000){editor().state?.work==null};assertEquals(RemovalStatus.UNSUPPORTED,editor().state?.status);compose.onNodeWithTag("removal-declined").performScrollTo().assertIsDisplayed();compose.onNodeWithTag("removal-apply").assertIsNotEnabled();click("removal-cancel");assertEquals(revision,session().documentRevision);assertArrayEquals(before,source.readBytes())
 }
 @Test fun appLockClosesReviewAndReleasesUnacceptedSnapshots(){
  val initial=session().documentSession;open();paint();preview();val work=editor().state!!.analysis!!.snapshot.file.parentFile!!
  compose.runOnIdle{val lock=ViewModelProvider(compose.activity)[AppLockViewModel::class.java];lock.setup(AutoLockTimeout.IMMEDIATELY,AppLockAuthenticationAvailability.AVAILABLE);lock.lockNow()}
  compose.waitUntil(30_000){editor().state==null&&!work.exists()}
  compose.runOnIdle{val lock=ViewModelProvider(compose.activity)[AppLockViewModel::class.java];val request=lock.beginAuthentication(AppLockAuthenticationAvailability.AVAILABLE)!!;lock.onAuthenticationResult(request.id,AppLockAuthenticationResult.Success);lock.disableAfterAuthentication()};assertSame(initial,session().documentSession);assertArrayEquals(before,source.readBytes())
 }
 @Test fun rapidMaskGenerationsAndDoubleApplyPublishOneReviewedResult(){
  val revision=session().documentRevision;open();compose.runOnIdle{repeat(12){editor().stroke(listOf(AnnotationPoint(286f/512,245f/384)))}};compose.waitUntil(30_000){editor().state?.work==null};assertEquals(12,editor().state!!.strokes.size);preview()
  compose.runOnIdle{compose.activity.applyObjectRemoval();compose.activity.applyObjectRemoval()};compose.waitUntil(30_000){editor().state==null&&!session().historyState.busy};assertEquals(revision+1,session().documentRevision);assertEquals(1,session().historyState.undoCount);assertArrayEquals(before,source.readBytes())
 }
}
