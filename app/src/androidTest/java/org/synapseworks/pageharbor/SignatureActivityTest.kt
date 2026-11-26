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
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.synapseworks.pageharbor.document.annotation.*
import org.synapseworks.pageharbor.document.signature.*
import org.synapseworks.pageharbor.document.session.*
import org.synapseworks.pageharbor.security.*

class SignatureActivityTest {
 @get:Rule val compose=createAndroidComposeRule<MainActivity>()
 private val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
 private lateinit var root:File
 private val name="Synthetic M15 ${UUID.randomUUID()}"
 private fun session()=ViewModelProvider(compose.activity)[PageHarborSessionViewModel::class.java]
 private fun editor()=compose.activity.annotationEditor
 private fun unlock(){val lock=ViewModelProvider(compose.activity)[AppLockViewModel::class.java];if(lock.state.value.phase==AppLockPhase.LOCKED){val r=lock.beginAuthentication(AppLockAuthenticationAvailability.AVAILABLE)!!;lock.onAuthenticationResult(r.id,AppLockAuthenticationResult.Success)};lock.disableAfterAuthentication()}
 private fun ready(){compose.waitUntil(30_000){!session().historyState.busy}}
 private fun draftReady(){compose.waitUntil(30_000){editor().state?.validating==false}}
 private fun libraryReady(){compose.waitUntil(30_000){editor().signatures.state?.busy==false}}
 private fun click(tag:String){val node=compose.onNodeWithTag(tag);if(tag !in setOf("annotation-apply","annotation-cancel","annotation-reset","history-undo","history-redo","signature-use","signature-cancel"))node.performScrollTo();node.assertIsDisplayed().assertIsEnabled().performClick()}
 private fun enterName(value:String){
  val initialHeight=compose.onNodeWithTag("signature-library").fetchSemanticsNode().size.height
  val field=compose.onNodeWithTag("signature-name").performScrollTo();field.performClick()
  compose.waitUntil(10_000){compose.onNodeWithTag("signature-library").fetchSemanticsNode().size.height<initialHeight-32}
  field.performTextReplacement(value)
  compose.runOnIdle{assertEquals(value,editor().signatures.state!!.name)}
  field.performImeAction()
  compose.waitUntil(10_000){compose.onNodeWithTag("signature-library").fetchSemanticsNode().size.height>=initialHeight-4}
 }
 @Before fun setup(){check(app.packageName.endsWith(".phase2test"));root=File(app.cacheDir,"shared-pdfs/signature-ui-${UUID.randomUUID()}").apply{mkdirs()};val page=AnnotationFixtures.source(root);compose.runOnIdle{unlock();compose.activity.installDocumentSessionForTest(DocumentSession(listOf(page)))};ready()}
 @After fun cleanup(){compose.runOnIdle{unlock();editor().cancel();session().clearScan()};ready();runBlocking{val store=SignatureTemplateStore.get(app);store.list().filter{it.name==name||it.name==name+" renamed"}.forEach{store.delete(it.id)}};root.deleteRecursively()}
 private fun open(){compose.onNodeWithText("Edit").performClick();click("open-annotations");draftReady();click("signature-open");libraryReady()}
 private fun draw(){val pad=compose.onNodeWithTag("signature-pad").performScrollTo();pad.performTouchInput{swipe(androidx.compose.ui.geometry.Offset(width*.1f,height*.2f),androidx.compose.ui.geometry.Offset(width*.6f,height*.8f),300)};pad.performTouchInput{click(androidx.compose.ui.geometry.Offset(width*.85f,height*.2f))};compose.waitForIdle();assertEquals(2,editor().signatures.state!!.strokes.size);assertEquals(1,editor().signatures.state!!.strokes.last().points.size)}
 @Test fun explicitSaveRestartReuseDeleteAndOneGroupedApplyUndoRedo(){
  val before=session().documentSession;open();draw()
  enterName(name);click("signature-save");libraryReady()
  val saved=editor().signatures.state!!.templates.first{it.name==name};assertEquals(0,session().historyState.undoCount)
  compose.onNodeWithTag("signature-pad").performScrollTo();File(app.cacheDir,"m15-signature-library.png").outputStream().use{compose.onNodeWithTag("signature-library").captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
  click("signature-cancel");assertTrue(editor().state!!.layer.isEmpty)
  compose.activityRule.scenario.recreate();draftReady();click("signature-open");libraryReady()
  val index=editor().signatures.state!!.templates.indexOfFirst{it.id==saved.id};click("signature-template-$index");click("signature-use");draftReady()
  val mark=editor().state!!.layer.marks.single();assertEquals(saved.ink,mark.signature);assertEquals(AnnotationTool.SIGNATURE,mark.tool)
  click("signature-open");libraryReady();val i=editor().signatures.state!!.templates.indexOfFirst{it.id==saved.id};click("signature-template-$i");click("signature-delete-template");libraryReady();assertFalse(editor().signatures.state!!.templates.any{it.id==saved.id});click("signature-cancel")
  assertEquals(saved.ink,editor().state!!.layer.marks.single().signature);click("annotation-apply");ready();assertEquals(1,session().historyState.undoCount)
  click("history-undo");ready();assertSame(before,session().documentSession);click("history-redo");ready();assertEquals(saved.ink,session().documentPages.single().annotations.marks.single().signature)
 }
 @Test fun correctionsPointerCancellationAndUnsavedCancelNeverCreateTemplates(){
  val count=runBlocking{SignatureTemplateStore.get(app).list().size};open();draw()
  click("signature-undo-stroke");assertEquals(1,editor().signatures.state!!.strokes.size)
  val original=editor().signatures.state!!.strokes
  compose.onNodeWithTag("signature-pad").performScrollTo().performTouchInput{down(center);moveBy(androidx.compose.ui.geometry.Offset(10f,10f));cancel()}
  compose.waitForIdle();assertEquals(original,editor().signatures.state!!.strokes)
  click("signature-reset");assertTrue(editor().signatures.state!!.strokes.isEmpty());draw();click("signature-cancel");assertTrue(editor().state!!.layer.isEmpty)
  click("annotation-cancel");assertEquals(count,runBlocking{SignatureTemplateStore.get(app).list().size});assertEquals(0,session().historyState.undoCount)
 }
 @Test fun groupProportionalResizeRotationMoveAndRemovalRemainOneDraftTransaction(){
  open();draw();click("signature-use");draftReady();val initial=editor().state!!.layer.marks.single();val physicalRatio=initial.bounds.width*800/(initial.bounds.height*600)
  compose.onNodeWithText("Larger",useUnmergedTree=true).performScrollTo().performClick();draftReady();val bigger=editor().state!!.layer.marks.single();assertEquals(physicalRatio,bigger.bounds.width*800/(bigger.bounds.height*600),.001f)
  click("annotation-Move right");draftReady();assertTrue(editor().state!!.layer.marks.single().bounds.left>bigger.bounds.left)
  click("signature-rotate");draftReady();val rotated=editor().state!!.layer.marks.single();assertEquals(initial.id,rotated.id);assertEquals(1f/physicalRatio,rotated.bounds.width*800/(rotated.bounds.height*600),.001f);assertEquals(2,rotated.signature!!.strokes.size)
  click("annotation-delete");draftReady();assertTrue(editor().state!!.layer.isEmpty);click("annotation-cancel");assertEquals(0,session().historyState.undoCount)
 }
 @Test fun explicitRenameReplaceLeavePlacedDraftFrozenAndOutsideDocumentHistory(){
  open();draw();enterName(name);click("signature-save");libraryReady()
  val saved=editor().signatures.state!!.templates.first{it.name==name};click("signature-use");draftReady();val frozen=editor().state!!.layer
  click("signature-open");libraryReady();val i=editor().signatures.state!!.templates.indexOfFirst{it.id==saved.id};click("signature-template-$i")
  enterName(name+" renamed");click("signature-rename");libraryReady();assertEquals(name+" renamed",editor().signatures.state!!.templates.first{it.id==saved.id}.name)
  click("signature-undo-stroke");click("signature-replace");libraryReady();assertEquals(1,editor().signatures.state!!.templates.first{it.id==saved.id}.ink.strokes.size)
  assertEquals(0,session().historyState.undoCount);click("signature-cancel");assertEquals(frozen,editor().state!!.layer);assertEquals(2,frozen.marks.single().signature!!.strokes.size)
  click("annotation-apply");ready();assertEquals(1,session().historyState.undoCount);assertEquals(frozen,session().documentPages.single().annotations)
 }
 @Test fun fullPadRejectsEntireNextStrokeAndRapidSaveUseCannotDuplicate(){
  open()
  val full=List(4){SignatureStroke(List(2048){SignaturePoint(.5f,.5f)})}
  compose.runOnIdle{editor().signatures.strokes(full,3f)}
  compose.onNodeWithTag("signature-pad").performScrollTo().performTouchInput{click(center)};compose.waitForIdle()
  assertEquals(8192,editor().signatures.state!!.strokes.sumOf{it.points.size});assertNotNull(editor().signatures.state!!.error)
  click("signature-reset");draw();enterName(name)
  compose.runOnIdle{editor().signatures.save(false);editor().signatures.save(false)};libraryReady()
  compose.runOnIdle{editor().signatures.save(false)};libraryReady();assertEquals(1,editor().signatures.state!!.templates.count{it.name==name})
  compose.runOnIdle{editor().useSignature();editor().useSignature()};draftReady();assertEquals(1,editor().state!!.layer.marks.size)
  click("annotation-cancel");assertTrue(session().documentPages.single().annotations.isEmpty)
 }
 @Test fun retainedDrawingStalePageBackgroundAndLockDoNotPublishOrSave(){
  val count=runBlocking{SignatureTemplateStore.get(app).list().size};open();draw();val original=editor().signatures.ink()
  compose.activityRule.scenario.recreate();libraryReady();assertEquals(original,editor().signatures.ink())
  compose.runOnIdle{session().rotatePageClockwise(1)};ready();compose.waitUntil{editor().state==null};assertNull(editor().signatures.state)
  open();draw();compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED);assertNull(editor().state);assertNull(editor().signatures.state);compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED);ready()
  open();draw();compose.runOnIdle{val lock=ViewModelProvider(compose.activity)[AppLockViewModel::class.java];lock.setup(AutoLockTimeout.IMMEDIATELY,AppLockAuthenticationAvailability.AVAILABLE);lock.lockNow()};compose.waitUntil{editor().state==null};assertNull(editor().signatures.state);compose.runOnIdle{unlock()};ready()
  assertTrue(session().documentPages.single().annotations.isEmpty);assertEquals(count,runBlocking{SignatureTemplateStore.get(app).list().size})
 }
}
