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
import org.synapseworks.pageharbor.document.session.*
import org.synapseworks.pageharbor.security.*

class AnnotationActivityTest {
 @get:Rule val compose=createAndroidComposeRule<MainActivity>()
 private val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
 private lateinit var root:File
 private fun session()=ViewModelProvider(compose.activity)[PageHarborSessionViewModel::class.java]
 private fun editor()=compose.activity.annotationEditor
 private fun unlock(){val lock=ViewModelProvider(compose.activity)[AppLockViewModel::class.java];if(lock.state.value.phase==AppLockPhase.LOCKED){val r=lock.beginAuthentication(AppLockAuthenticationAvailability.AVAILABLE)!!;lock.onAuthenticationResult(r.id,AppLockAuthenticationResult.Success)};lock.disableAfterAuthentication()}
 private fun ready(){compose.waitUntil(30_000){!session().historyState.busy}}
 private fun draftReady(){compose.waitUntil(30_000){editor().state?.validating==false}}
 private fun click(tag:String){val node=compose.onNodeWithTag(tag);if(tag !in setOf("annotation-apply","annotation-cancel","annotation-reset","history-undo","history-redo"))node.performScrollTo();node.assertIsDisplayed().assertIsEnabled().performClick()}
 @Before fun setup(){check(app.packageName.endsWith(".phase2test"));root=File(app.cacheDir,"shared-pdfs/annotation-ui-${UUID.randomUUID()}").apply{mkdirs()};val page=AnnotationFixtures.source(root);compose.runOnIdle{unlock();compose.activity.installDocumentSessionForTest(DocumentSession(listOf(page)))};ready()}
 @After fun cleanup(){compose.runOnIdle{unlock();editor().cancel();session().clearScan()};ready();root.deleteRecursively()}
 private var unoccludedEditorHeight:Int?=null
 private fun replaceText(value:String){
  val id=editor().state!!.selected!!
  val editorNode=compose.onNodeWithTag("annotation-editor")
  if(unoccludedEditorHeight==null)unoccludedEditorHeight=editorNode.fetchSemanticsNode().size.height
  val field=compose.onNodeWithTag("annotation-text").performScrollTo()
  field.performClick()
  compose.waitUntil(10_000){editorNode.fetchSemanticsNode().size.height<unoccludedEditorHeight!!-32}
  field.performScrollTo().assertIsFocused().performTextReplacement(value)
  // TextField delivery and appearance validation are separate events. An old validating=false
  // does not prove that this edit reached the model. Never retry the input to satisfy the check.
  compose.waitUntil(10_000){editor().state?.textInputs?.get(id)==value}
  compose.runOnIdle{assertEquals(id,editor().state!!.selected);assertEquals(value,editor().state!!.textInputs[id])}
 }
 private fun open(){compose.onNodeWithText("Edit").performClick();click("open-annotations");compose.onNodeWithTag("annotation-editor").assertIsDisplayed();draftReady();assertTrue(session().hasActiveDocumentSessionLeases())}
 @Test fun allToolsAreReachableAndOneApplyCreatesOneUndoableLayer(){
  val before=session().documentSession;open()
  for(tool in AnnotationTool.entries.filter{it!=AnnotationTool.SIGNATURE}){click("annotation-tool-${tool.name}");click("annotation-add");draftReady()}
  assertEquals(7,editor().state!!.layer.marks.size)
  click("annotation-select-0");assertEquals(editor().state!!.layer.marks.first().id,editor().state!!.selected)
  compose.onNodeWithTag("annotation-canvas").performScrollTo()
  File(app.cacheDir,"m14-annotation-ui.png").outputStream().use{compose.onNodeWithTag("annotation-editor").captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
  click("annotation-apply");ready();assertEquals(7,session().documentPages[0].annotations.marks.size);assertEquals(1,session().historyState.undoCount)
  click("history-undo");ready();assertSame(before,session().documentSession)
  click("history-redo");ready();assertEquals(7,session().documentPages[0].annotations.marks.size)
 }
 @Test fun selectionMovementResizeDeleteResetAndCancelPreserveCommittedContent(){
  val before=session().documentSession;open();click("annotation-tool-RECTANGLE");click("annotation-add");draftReady()
  val id=editor().state!!.selected;val initial=editor().state!!.layer.marks.single()
  compose.onNodeWithText("Blue",useUnmergedTree=true).performScrollTo().performClick();draftReady();assertEquals(0x2040D0,editor().state!!.layer.marks.single().style.rgb)
  compose.onNodeWithContentDescription("Annotation opacity").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress){it(.5f)};draftReady();assertEquals(.5f,editor().state!!.layer.marks.single().style.opacity,0f)
  click("annotation-Move right");draftReady();assertTrue(editor().state!!.layer.marks.single().bounds.left>initial.bounds.left)
  compose.onNodeWithText("Larger",useUnmergedTree=true).performScrollTo().performClick();draftReady();assertEquals(id,editor().state!!.layer.marks.single().id)
  click("annotation-delete");draftReady();assertTrue(editor().state!!.layer.isEmpty)
  click("annotation-add");draftReady();click("annotation-reset");draftReady();assertTrue(editor().state!!.layer.isEmpty)
  click("annotation-cancel");assertSame(before,session().documentSession);assertEquals(0,session().historyState.undoCount)
 }
 @Test fun multilineAccentsUnsupportedInputAndOverflowStayExplicitAcrossRecreation(){
  open();click("annotation-tool-TEXT");click("annotation-add");draftReady()
  replaceText("Café\nRésumé");draftReady();assertEquals("Café\nRésumé",editor().state!!.layer.marks.single().text)
  replaceText("漢字");compose.onNodeWithTag("annotation-error").performScrollTo().assertIsDisplayed();compose.onNodeWithTag("annotation-apply").assertIsNotEnabled()
  val vm=editor();compose.activityRule.scenario.recreate();draftReady();assertSame(vm,editor());assertEquals("漢字",editor().state!!.textInputs.values.single())
  replaceText("Café\nRésumé");draftReady()
  compose.runOnIdle{val mark=editor().state!!.layer.marks.single();editor().put(mark.withBounds(AnnotationBounds(.1f,.1f,.2f,.12f)))};draftReady();assertNotNull(editor().state!!.appearanceError);assertEquals("Café\nRésumé",editor().state!!.layer.marks.single().text)
  click("annotation-cancel");assertTrue(session().documentPages[0].annotations.isEmpty)
  // A rejected aggregate text edit remains pending after a style change; it cannot be lost on Apply.
  val marks=(0..8).map{i->AnnotationMark.create("limit-$i",AnnotationTool.TEXT,AnnotationBounds(.05f,.05f,.95f,.95f),AnnotationStyle(textSize=.01f),text=when{ i<7->"x".repeat(4096);i==7->"x".repeat(4090);else->"x"})}
  val page=session().documentPages[0].copy(annotations=AnnotationLayer.of(marks))
  compose.runOnIdle{compose.activity.installDocumentSessionForTest(DocumentSession(listOf(page)))};ready();open()
  compose.runOnIdle{editor().select("limit-8");editor().text("limit-8","deliberate");val mark=editor().state!!.layer.marks.last();editor().put(mark.withStyle(mark.style.copy(rgb=0x2040D0)))};draftReady()
  assertEquals("deliberate",editor().state!!.textInputs["limit-8"]);assertEquals("x",editor().state!!.layer.marks.last().text);assertNotNull(editor().state!!.error)
  compose.onNodeWithTag("annotation-apply").assertIsNotEnabled();click("annotation-cancel")
 }
 @Test fun drawingMapsQuarterTurnLetterboxAndZoomIntoCanonicalSourceCoordinates(){
  compose.runOnIdle{session().rotatePageClockwise(1)};ready();open()
  click("annotation-tool-ELLIPSE")
  val node=compose.onNodeWithTag("annotation-canvas").performScrollTo()
  val size=node.fetchSemanticsNode().size
  val width=size.width.toFloat();val height=size.height.toFloat()
  val scale=kotlin.math.min(width/600f,height/800f);val fitW=600f*scale;val fitH=800f*scale
  fun pixel(x:Float,y:Float,zoom:Float):androidx.compose.ui.geometry.Offset {
   // Literal90-degree oracle: displayed=(1-sourceY,sourceX), centered fit then zoom.
   return androidx.compose.ui.geometry.Offset(width/2+(0.5f-y)*fitW*zoom,height/2+(x-.5f)*fitH*zoom)
  }
  node.performTouchInput{swipe(pixel(.3f,.3f,1f),pixel(.7f,.7f,1f),600)};draftReady()
  val first=editor().state!!.layer.marks.single().bounds
  assertEquals(.3f,first.left,.04f);assertEquals(.3f,first.top,.04f);assertEquals(.7f,first.right,.04f);assertEquals(.7f,first.bottom,.04f)
  compose.onNodeWithText("Fit page",useUnmergedTree=true).performScrollTo().assertIsDisplayed().performClick()
  compose.onNodeWithText("Zoom in",useUnmergedTree=true).performScrollTo().performClick()
  compose.onNodeWithText("Zoom in",useUnmergedTree=true).performClick()
  compose.onNodeWithText("Zoom in",useUnmergedTree=true).performClick()
  compose.onNodeWithText("Zoom in",useUnmergedTree=true).performClick()
  click("annotation-tool-FREEHAND")
  compose.onNodeWithTag("annotation-canvas").performScrollTo().performTouchInput{swipe(pixel(.4f,.4f,2f),pixel(.6f,.6f,2f),600)};draftReady()
  val stroke=editor().state!!.layer.marks.last();assertEquals(AnnotationTool.FREEHAND,stroke.tool);assertTrue(stroke.points.size>=2)
  assertEquals(.4f,stroke.bounds.left,.04f);assertEquals(.4f,stroke.bounds.top,.04f);assertEquals(.6f,stroke.bounds.right,.04f);assertEquals(.6f,stroke.bounds.bottom,.04f)
  click("annotation-cancel")
 }
 @Test fun stalePageBackgroundAndLockDiscardDraftWithoutPublishing(){
  open();click("annotation-add");draftReady();val revision=session().documentRevision
  compose.runOnIdle{session().rotatePageClockwise(1)};ready();compose.waitUntil{editor().state==null};assertEquals(revision+1,session().documentRevision);assertTrue(session().documentPages[0].annotations.isEmpty)
  open();click("annotation-add");draftReady();compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED);assertNull(editor().state);compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED);ready()
  open();compose.runOnIdle{val lock=ViewModelProvider(compose.activity)[AppLockViewModel::class.java];lock.setup(AutoLockTimeout.IMMEDIATELY,AppLockAuthenticationAvailability.AVAILABLE);lock.lockNow()};compose.waitUntil{editor().state==null};compose.runOnIdle{unlock()};ready();assertTrue(session().documentPages[0].annotations.isEmpty)
 }
}
