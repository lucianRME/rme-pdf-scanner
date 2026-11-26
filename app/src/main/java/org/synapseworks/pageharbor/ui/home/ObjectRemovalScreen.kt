package org.synapseworks.pageharbor.ui.home

import org.synapseworks.pageharbor.ui.theme.RmeFilterChip
import org.synapseworks.pageharbor.ui.theme.RmePrimaryButton
import org.synapseworks.pageharbor.ui.theme.RmeSecondaryButton
import org.synapseworks.pageharbor.ui.theme.RmeTextButton

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.synapseworks.pageharbor.document.annotation.AnnotationPoint
import org.synapseworks.pageharbor.document.annotation.AnnotationViewport
import org.synapseworks.pageharbor.document.objectremoval.*
import org.synapseworks.pageharbor.document.session.toAndroidUri
import org.synapseworks.pageharbor.image.BitmapDisplaySize

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun ObjectRemovalScreen(editor:ObjectRemovalViewModel,onApply:()->Unit) {
 val state=editor.state?:return
 BackHandler(onBack=editor::close)
 Surface(Modifier.fillMaxSize().testTag("removal-editor")) {
  Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(12.dp)) {
   Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
    val finger=state.mode==RemovalMode.FINGER
    Text(if(finger)"Automatic finger removal"else"Object-removal brush",style=MaterialTheme.typography.titleLarge)
    if(finger){
     Text("Possible fingers near the page edge are suggested automatically. Edge graphics can look identical. Only repair known blank paper margins; hidden text and graphics cannot be recovered. Review or keep unchanged.",style=MaterialTheme.typography.bodySmall)
     state.detection?.let{d->
      Text("${d.proposals} automatic proposal(s) before correction.",Modifier.testTag("finger-proposals"))
      Text(when(d.decision){
       org.synapseworks.pageharbor.document.finger.FingerDecision.PROPOSED->"Check every proposed mask. A proposal does not prove this is an unwanted finger."
       org.synapseworks.pageharbor.document.finger.FingerDecision.NONE->"No candidate found. Fingers can be missed. Review manually or keep unchanged."
       org.synapseworks.pageharbor.document.finger.FingerDecision.UNCERTAIN->"Detection is uncertain. Correct the mask manually on known blank paper or keep unchanged."
       org.synapseworks.pageharbor.document.finger.FingerDecision.UNSUPPORTED->"This image is outside the automatic detection profile. Review manually or keep unchanged."
      },Modifier.testTag("finger-decision"),style=MaterialTheme.typography.bodySmall)
      FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
       RmeTextButton(onClick=editor::rejectProposals,enabled=state.work==null,modifier=Modifier.testTag("finger-reject")){Text("Reject proposals")}
       RmeTextButton(onClick=editor::restoreProposals,enabled=state.work==null&&d.proposals>0,modifier=Modifier.testTag("finger-restore")){Text("Restore proposals")}
      }
     }
    }
    Text("Paint only unwanted marks on flat or gently graded light paper. Text inside a painted area can be removed. Correct the mask and compare before applying.",style=MaterialTheme.typography.bodySmall)
    if(state.page.sourceCategory==org.synapseworks.pageharbor.document.session.DocumentSourceCategory.RENDERED_PDF_PAGE)Text("This edits the rendered page image. PDF text objects, form widgets and comments are not edited; original PDF assets can remain.",style=MaterialTheme.typography.bodySmall)
    if(state.analysis!=null)RemovalCanvas(editor,state)
    if(state.work!=null)Text("Preparing…",Modifier.testTag("removal-busy").semantics{liveRegion=LiveRegionMode.Polite})
    if(state.failed)Text("The edit could not be prepared. Undo or clear the mask, or Cancel and reopen.",Modifier.testTag("removal-error"),color=MaterialTheme.colorScheme.error)
    if(state.status==RemovalStatus.UNSUPPORTED)Text("Cannot fill this mask faithfully. Use a smaller mask on a plain background, away from text, lines and texture. No change was applied.",Modifier.testTag("removal-declined"),color=MaterialTheme.colorScheme.error)
    if(state.status in listOf(RemovalStatus.EMPTY,RemovalStatus.UNCHANGED))Text("Nothing to remove. Paint a mark or Cancel.",Modifier.testTag("removal-unchanged"))
    val editing=state.analysis!=null&&state.work!=RemovalWork.OPENING&&state.work!=RemovalWork.APPLYING
    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
     RmeFilterChip(!state.erase,onClick={editor.erase(false)},enabled=editing,label={Text("Draw mask")},modifier=Modifier.testTag("removal-draw"))
     RmeFilterChip(state.erase,onClick={editor.erase(true)},enabled=editing,label={Text("Erase mask")},modifier=Modifier.testTag("removal-erase"))
     RmeTextButton(onClick=editor::undo,enabled=editing&&state.strokes.isNotEmpty(),modifier=Modifier.testTag("removal-undo-mask")){Text("Undo mask stroke")}
     RmeTextButton(onClick=editor::clear,enabled=editing&&(state.strokes.isNotEmpty()||state.mask?.hasSelection==true),modifier=Modifier.testTag("removal-clear")){Text("Clear mask")}
    }
    Text("Brush radius: ${state.radius}")
    Slider(state.radius.toFloat(),{editor.radius(it.toInt())},valueRange=2f..32f,steps=29,enabled=editing,modifier=Modifier.testTag("removal-radius").semantics{contentDescription="Brush radius"})
    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
     for(view in RemovalView.entries)RmeFilterChip(state.view==view,onClick={editor.view(view)},enabled=state.work==null&&(view!=RemovalView.AFTER||state.prepared!=null),label={Text(when(view){RemovalView.MASK->"Edit mask";RemovalView.BEFORE->"Before";RemovalView.AFTER->"After"})},modifier=Modifier.testTag("removal-view-${view.name}"))
    }
    Text("The mask uses a bounded grid; it can cover more than the exact pointer trace. Backgrounds with crossing lines, texture or missing evidence are unsupported. Hidden detail cannot be recovered. Annotations and signatures stay on top.",style=MaterialTheme.typography.bodySmall)
    Text("Cosmetic removal only. Content can remain in originals, history and backups. Use Share-Safe for secure removal.",style=MaterialTheme.typography.bodySmall)
   }
   FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
    RmeTextButton(onClick=editor::close,modifier=Modifier.testTag("removal-cancel")){Text("Cancel")}
    if(state.mode==RemovalMode.FINGER)RmeTextButton(onClick=editor::close,modifier=Modifier.testTag("finger-keep")){Text("Keep unchanged")}
    RmeSecondaryButton(onClick=editor::preview,enabled=state.work==null&&state.mask!=null,modifier=Modifier.testTag("removal-preview")){Text("Preview removal")}
    RmePrimaryButton(onClick=onApply,enabled=state.work==null&&!state.failed&&state.prepared!=null&&state.reviewedDigest==state.prepared.resultDigest,modifier=Modifier.testTag("removal-apply")){Text("Apply")}
   }
  }
 }
}

@Composable private fun RemovalCanvas(editor:ObjectRemovalViewModel,state:RemovalState) {
 val analysis=state.analysis?:return;val page=state.page;val resolver=LocalContext.current.contentResolver
 val prepared=state.prepared
 val uri=if(state.view==RemovalView.AFTER&&prepared!=null)prepared.result.source.toAndroidUri()else Uri.fromFile(analysis.snapshot.file)
 val metadata=if(state.view==RemovalView.AFTER&&prepared!=null)prepared.result.metadata else analysis.snapshot.metadata
 val request=FilteredPreviewRequest(page.id.value,uri.toString(),page.filter,page.rotation,metadata,page.adjustments,page.annotations)
 key(request) {
  val reviewPosition=remember{BringIntoViewRequester()}
  val display by rememberManagedDocumentPreview(requestKey=request,decode={decodeDocumentPreview(resolver,uri,request,BitmapDisplaySize(1600,1600))},transform={applyPreviewRotationAndFilter(it,page.rotation,page.filter,page.adjustments,page.annotations)})
  val bitmap=(display as? ManagedDocumentPreviewState.Ready)?.owner?.bitmap
  LaunchedEffect(bitmap,prepared,state.view){if(bitmap!=null&&state.view==RemovalView.AFTER&&prepared!=null){if(state.mode==RemovalMode.FINGER){withFrameNanos{};reviewPosition.bringIntoView()};editor.markReviewed(prepared)}}
  val mask=state.mask
  val overlay by rememberManagedDocumentPreview(requestKey=mask to page.rotation,decode={mask?.let{m->Bitmap.createBitmap(m.width,m.height,Bitmap.Config.ARGB_8888).apply{setPixels(IntArray(m.size){if(m[it])0x70ff0070 else 0},0,m.width,0,0,m.width,m.height)}}},transform={b->if(page.rotation.degrees==0)b else Bitmap.createBitmap(b,0,0,b.width,b.height,Matrix().apply{postRotate(page.rotation.degrees.toFloat())},false)})
  val maskBitmap=(overlay as? ManagedDocumentPreviewState.Ready)?.owner?.bitmap
  var viewport by remember{mutableStateOf(IntSize.Zero)};val points=remember{mutableStateListOf<AnnotationPoint>()}
  val geometry=if(bitmap!=null&&viewport.width>0&&viewport.height>0)AnnotationViewport(viewport.width.toFloat(),viewport.height.toFloat(),bitmap.width.toFloat(),bitmap.height.toFloat())else null
  val currentGeometry by rememberUpdatedState(geometry)
  val enabled=state.work==null
  val canvasBackground = MaterialTheme.colorScheme.surfaceContainerHigh
  Canvas(Modifier.fillMaxWidth().height(280.dp).bringIntoViewRequester(reviewPosition).onSizeChanged{viewport=it}.testTag("removal-canvas").semantics{contentDescription="Page and effective removal mask. Draw or erase with one finger."}.pointerInput(state.owner,enabled,state.erase){
   if(enabled)awaitEachGesture {
    val down=awaitFirstDown(requireUnconsumed=false)
    fun source(p:Offset)=currentGeometry?.toSource(p.x,p.y)?.rotated((360-page.rotation.degrees)%360)
    val first=source(down.position)?:return@awaitEachGesture;points.clear();points+=first;down.consume();var cancelled=false;var overflow=false
    try{while(true){val event=awaitPointerEvent();if(event.changes.count{it.pressed}>1){cancelled=true;break};val change=event.changes.firstOrNull{it.id==down.id}?:break
      source(change.position)?.let{if(points.size<2048)points+=it else overflow=true};change.consume();if(!change.pressed)break}
     if(!cancelled){if(overflow)editor.rejectStroke()else editor.stroke(points.toList())}
    }finally{points.clear()}
   }
  }) {
   drawRect(canvasBackground);val g=geometry
   if(g!=null&&bitmap!=null&&!bitmap.isRecycled){val a=g.toDisplay(AnnotationPoint(0f,0f));val b=g.toDisplay(AnnotationPoint(1f,1f));val rect=android.graphics.RectF(a.first,a.second,b.first,b.second)
    drawIntoCanvas{c->c.nativeCanvas.drawBitmap(bitmap,null,rect,Paint(Paint.FILTER_BITMAP_FLAG));if(state.view==RemovalView.MASK&&maskBitmap!=null&&!maskBitmap.isRecycled)c.nativeCanvas.drawBitmap(maskBitmap,null,rect,Paint())}
    for(p in points){val q=g.toDisplay(p.rotated(page.rotation.degrees));drawCircle(if(state.erase)Color.Cyan else Color.Magenta,4.dp.toPx(),Offset(q.first,q.second))}
   }
  }
  if(display==ManagedDocumentPreviewState.Loading)Text("Loading page…",Modifier.testTag("removal-loading"))
  if(display==ManagedDocumentPreviewState.Unavailable)Text("Page preview unavailable. Cancel and reopen.",Modifier.testTag("removal-unavailable"),color=MaterialTheme.colorScheme.error)
 }
}
