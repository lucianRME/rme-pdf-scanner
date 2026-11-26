package org.synapseworks.pageharbor.ui.home

import org.synapseworks.pageharbor.ui.theme.RmeFilterChip
import org.synapseworks.pageharbor.ui.theme.RmePrimaryButton
import org.synapseworks.pageharbor.ui.theme.RmeSecondaryButton
import org.synapseworks.pageharbor.ui.theme.RmeTextButton
import org.synapseworks.pageharbor.ui.theme.RmeTextField

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Canvas as AndroidCanvas
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.synapseworks.pageharbor.document.annotation.*
import org.synapseworks.pageharbor.document.session.toAndroidUri
import org.synapseworks.pageharbor.document.session.DocumentPage
import org.synapseworks.pageharbor.document.session.DocumentPageRotation
import org.synapseworks.pageharbor.image.BitmapDisplaySize
import kotlin.math.max
import kotlin.math.min

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun AnnotationEditorDialog(editor:AnnotationEditorViewModel,onApply:(DocumentPage,Long,String,AnnotationLayer)->Boolean) {
 val draft=editor.state?:return
 val context=LocalContext.current
 val lifecycle=LocalLifecycleOwner.current
 DisposableEffect(lifecycle,editor){
  val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_STOP&&(context as? Activity)?.isChangingConfigurations!=true)editor.cancel()}
  lifecycle.lifecycle.addObserver(observer)
  onDispose{lifecycle.lifecycle.removeObserver(observer);if((context as? Activity)?.isChangingConfigurations!=true)editor.cancel()}
 }
 Dialog(onDismissRequest=editor::cancel,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)){
  Surface(Modifier.fillMaxSize().safeDrawingPadding().imePadding().testTag("annotation-editor")) {
   Column(Modifier.fillMaxSize().padding(12.dp)) {
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
     val previewHeight=(maxHeight*.65f).coerceIn(48.dp,300.dp)
     Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
     Text("Annotations and signatures",style=MaterialTheme.typography.titleLarge)
     AnnotationCanvas(editor,draft,previewHeight)
     Text("Editable in RME. Exported as appearance. Shapes do not securely redact content.",style=MaterialTheme.typography.bodySmall)
     if(draft.page.sourceCategory==org.synapseworks.pageharbor.document.session.DocumentSourceCategory.RENDERED_PDF_PAGE)Text("Imported PDF annotations use the rendered page appearance. Some comments or interactive marks may be absent from rendering. Edited exports retain the rendered appearance only.",style=MaterialTheme.typography.bodySmall)
     FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
      for(mode in AnnotationMode.entries)RmeFilterChip(selected=draft.mode==mode,onClick={editor.mode(mode)},label={Text(mode.label())},modifier=Modifier.testTag("annotation-mode-${mode.name}"))
     }
     RmePrimaryButton(onClick={editor.signatures.open(org.synapseworks.pageharbor.document.signature.SignatureTemplateStore.get(context))},modifier=Modifier.testTag("signature-open")){Text("Handwritten signature")}
     Text("Tools",style=MaterialTheme.typography.titleSmall)
     FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
      for(tool in AnnotationTool.entries.filter{it!=AnnotationTool.SIGNATURE})RmeFilterChip(selected=draft.tool==tool,onClick={editor.tool(tool)},label={Text(tool.label())},modifier=Modifier.testTag("annotation-tool-${tool.name}"))
     }
     RmePrimaryButton(onClick={editor.add()},modifier=Modifier.testTag("annotation-add")){Text("Add ${draft.tool.label()}")}
     Text("Drag in Draw mode, or add a centered mark and use the controls below. Pan mode moves/zooms the view. Marks are clipped to the page.",style=MaterialTheme.typography.bodySmall)
     if(draft.layer.marks.isNotEmpty()) {
      Text("Select a mark (topmost last)",style=MaterialTheme.typography.titleSmall)
      FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
       draft.layer.marks.forEachIndexed{i,mark->RmeFilterChip(selected=draft.selected==mark.id,onClick={editor.select(mark.id)},label={Text("${i+1}. ${mark.tool.label()}")},modifier=Modifier.testTag("annotation-select-$i"))}
      }
     }
     val selected=draft.layer.marks.firstOrNull{it.id==draft.selected}
     if(selected!=null)AnnotationProperties(editor,selected,draft.page.rotation)
     if(draft.validating)Text("Checking appearance…",Modifier.testTag("annotation-validating"))
     (draft.error?:draft.appearanceError)?.let{Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("annotation-error"))}
     }
    }
    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
     RmeTextButton(onClick=editor::cancel,modifier=Modifier.testTag("annotation-cancel")){Text("Cancel")}
     RmeTextButton(onClick=editor::reset,modifier=Modifier.testTag("annotation-reset")){Text("Reset draft")}
     RmePrimaryButton(onClick={editor.apply(onApply)},enabled=editor.signatures.state==null&&!draft.validating&&draft.error==null&&draft.appearanceError==null,modifier=Modifier.testTag("annotation-apply")){Text("Apply")}
    }
   }
  }
 }
 SignatureLibraryDialog(editor)
}
private fun AnnotationMode.label()=when(this){AnnotationMode.SELECT->"Select / move";AnnotationMode.DRAW->"Draw";AnnotationMode.PAN->"Zoom / pan"}
internal fun AnnotationTool.label()=when(this){AnnotationTool.FREEHAND->"Freehand";AnnotationTool.HIGHLIGHT->"Highlight";AnnotationTool.ARROW->"Arrow";AnnotationTool.RECTANGLE->"Rectangle";AnnotationTool.ELLIPSE->"Ellipse";AnnotationTool.LINE->"Line";AnnotationTool.TEXT->"Text box";AnnotationTool.SIGNATURE->"Handwritten signature"}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun AnnotationProperties(editor:AnnotationEditorViewModel,mark:AnnotationMark,rotation:DocumentPageRotation) {
 Text("Selected ${mark.tool.label()}",style=MaterialTheme.typography.titleSmall)
 FlowRow(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
  for((label,delta)in listOf("Move left" to (-.02f to 0f),"Move right" to (.02f to 0f),"Move up" to (0f to -.02f),"Move down" to (0f to .02f)))
   RmeSecondaryButton(onClick={val (x,y)=when(rotation.degrees){90->delta.second to -delta.first;180->-delta.first to -delta.second;270->-delta.second to delta.first;else->delta};editor.put(mark.withBounds(mark.bounds.moved(x,y)))},modifier=Modifier.testTag("annotation-$label")){Text(label)}
  RmeSecondaryButton(onClick={if(mark.signature!=null)editor.resizeSignature(mark,.9f)else editor.put(mark.withBounds(mark.bounds.resized(.9f)))}){Text("Smaller")}
  RmeSecondaryButton(onClick={if(mark.signature!=null)editor.resizeSignature(mark,1.1f)else editor.put(mark.withBounds(mark.bounds.resized(1.1f)))}){Text("Larger")}
  if(mark.signature==null){
  RmeSecondaryButton(onClick={editor.put(mark.withBounds(mark.bounds.resized(1.1f,1f)))}){Text("Wider")}
  RmeSecondaryButton(onClick={editor.put(mark.withBounds(mark.bounds.resized(.9f,1f)))}){Text("Narrower")}
  RmeSecondaryButton(onClick={editor.put(mark.withBounds(mark.bounds.resized(1f,1.1f)))}){Text("Taller")}
  RmeSecondaryButton(onClick={editor.put(mark.withBounds(mark.bounds.resized(1f,.9f)))}){Text("Shorter")}
  }else RmeSecondaryButton(onClick={editor.rotateSignature(mark)},modifier=Modifier.testTag("signature-rotate")){Text("Rotate 90°")}
  RmeSecondaryButton(onClick=editor::delete,modifier=Modifier.testTag("annotation-delete")){Text("Delete")}
 }
 FlowRow(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
  for((label,rgb)in listOf("Black" to 0x000000,"Red" to 0xD02020,"Blue" to 0x2040D0,"Yellow" to 0xFFFF00,"Green" to 0x208040,"White" to 0xFFFFFF))
   RmeFilterChip(selected=mark.style.rgb==rgb,onClick={editor.put(mark.withStyle(mark.style.copy(rgb=rgb)))},label={Text(label)})
 }
 Text("Opacity ${(mark.style.opacity*100).toInt()}%")
 Slider(value=mark.style.opacity,onValueChange={editor.put(mark.withStyle(mark.style.copy(opacity=it)))},valueRange=.05f..1f,modifier=Modifier.semantics{contentDescription="Annotation opacity"})
 if(mark.tool!=AnnotationTool.TEXT&&mark.tool!=AnnotationTool.HIGHLIGHT){
  Text("Stroke width")
  Slider(value=mark.style.stroke,onValueChange={editor.put(mark.withStyle(mark.style.copy(stroke=it)))},valueRange=.001f .. .05f,modifier=Modifier.semantics{contentDescription="Annotation stroke width"})
 }
 if(mark.tool==AnnotationTool.TEXT){
  val text=editor.state?.textInputs?.get(mark.id)?:mark.text
  val focus=LocalFocusManager.current
  val keyboard=LocalSoftwareKeyboardController.current
  RmeTextField(value=text,onValueChange={editor.text(mark.id,it)},label={Text("Annotation text")},modifier=Modifier.fillMaxWidth().testTag("annotation-text"),minLines=2)
  RmeTextButton(onClick={focus.clearFocus();keyboard?.hide()},modifier=Modifier.testTag("annotation-hide-keyboard")){Text("Hide keyboard")}
  Text("Latin characters and accents only. Text wraps inside the box; enlarge it or reduce size if it does not fit.",style=MaterialTheme.typography.bodySmall)
  Text("Text size")
  Slider(value=mark.style.textSize,onValueChange={editor.put(mark.withStyle(mark.style.copy(textSize=it)))},valueRange=.01f .. .15f,modifier=Modifier.semantics{contentDescription="Annotation text size"})
 }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun AnnotationCanvas(editor:AnnotationEditorViewModel,draft:AnnotationDraft,previewHeight:Dp) {
 val context=LocalContext.current;val resolver=context.contentResolver
 var viewport by remember{mutableStateOf(IntSize.Zero)}
 var zoom by remember(draft.page){mutableFloatStateOf(1f)}
 var pan by remember(draft.page){mutableStateOf(Offset.Zero)}
 val page=draft.page
 val request=FilteredPreviewRequest(page.id.value,page.source.reference,page.filter,page.rotation,page.imageMetadata,page.adjustments,AnnotationLayer.EMPTY)
 val base by rememberManagedDocumentPreview(requestKey=page,decode={decodeDocumentPreview(resolver,page.source.toAndroidUri(),request,BitmapDisplaySize(1600,1600))},transform={applyPreviewRotationAndFilter(it,page.rotation,page.filter,page.adjustments)})
 val ready=base as? ManagedDocumentPreviewState.Ready
 val bitmap=ready?.owner?.bitmap
 LaunchedEffect(bitmap,page.rotation){if(bitmap!=null){val swap=page.rotation.degrees%180!=0;editor.dimensions(if(swap)bitmap.height else bitmap.width,if(swap)bitmap.width else bitmap.height)}}
 val appearance by rememberManagedDocumentPreview(requestKey=Triple(page,draft.layer,bitmap),debounceMillis=80,decode={bitmap?.takeUnless{it.isRecycled}?.copy(Bitmap.Config.ARGB_8888,true)},transform={
  org.synapseworks.pageharbor.document.annotation.AnnotationRenderer.draw(it,draft.layer,page.rotation);it
 })
 // Include decoded base identity: layer can remain equal while initial decode finishes.
 val image=(appearance as? ManagedDocumentPreviewState.Ready)?.owner?.bitmap
 val width=bitmap?.width?:1;val height=bitmap?.height?:1
 val geometry=if(viewport.width>0&&viewport.height>0)AnnotationViewport(viewport.width.toFloat(),viewport.height.toFloat(),width.toFloat(),height.toFloat(),zoom,pan.x,pan.y)else null
 val latest by rememberUpdatedState(draft)
 val latestGeometry by rememberUpdatedState(geometry)
 val points=remember(page){mutableStateListOf<AnnotationPoint>()}
 var origin by remember(page){mutableStateOf<AnnotationBounds?>(null)}
 fun source(position:Offset)=latestGeometry?.toSource(position.x,position.y)?.rotated((360-page.rotation.degrees)%360)
 val gestures=when(draft.mode){
  AnnotationMode.PAN->Modifier.pointerInput(page){detectTransformGestures{_,delta,scale,_->zoom=(zoom*scale).coerceIn(1f,4f);pan=Offset((pan.x+delta.x).coerceIn(-size.width*1.5f,size.width*1.5f),(pan.y+delta.y).coerceIn(-size.height*1.5f,size.height*1.5f))}}
  AnnotationMode.SELECT->Modifier.pointerInput(page,draft.mode){detectTapGestures{pos->source(pos)?.let{p->editor.select(latest.layer.marks.lastOrNull{p.x in it.bounds.left..it.bounds.right&&p.y in it.bounds.top..it.bounds.bottom}?.id)}}}
   .pointerInput(page,draft.mode){var start:AnnotationPoint?=null;detectDragGestures(onDragStart={pos->val p=source(pos);start=p;val mark=p?.let{q->latest.layer.marks.lastOrNull{q.x in it.bounds.left..it.bounds.right&&q.y in it.bounds.top..it.bounds.bottom}};origin=mark?.bounds;editor.select(mark?.id)},onDrag={change,_->change.consume();val p=source(change.position);val a=start;val b=origin;val selected=latest.layer.marks.firstOrNull{it.id==latest.selected};if(p!=null&&a!=null&&b!=null&&selected!=null)editor.put(selected.withBounds(b.moved(p.x-a.x,p.y-a.y)))})}
  AnnotationMode.DRAW->Modifier.pointerInput(page,draft.mode){
   awaitEachGesture {
    val down=awaitFirstDown(requireUnconsumed=false)
    val first=source(down.position)?:return@awaitEachGesture
    points.clear();points.add(first);down.consume()
    var overflow=false;var cancelled=false
    try {
     while(true){
      val event=awaitPointerEvent()
      if(event.changes.count{it.pressed}>1){cancelled=true;break}
      val change=event.changes.firstOrNull{it.id==down.id}?:break
      val point=source(change.position)
      if(point!=null&&point!=points.lastOrNull()){
       if(points.size>=2048)overflow=true else points.add(point)
      }
      change.consume()
      if(!change.pressed)break
     }
     if(!cancelled){
      if(overflow)editor.error("Stroke exceeds 2048 points. Draw a shorter stroke.")
      else if(points.size>=2){
       val left=points.minOf{it.x}.coerceAtMost(.99f);val right=max(left+.01f,points.maxOf{it.x}).coerceAtMost(1f)
       val top=points.minOf{it.y}.coerceAtMost(.99f);val bottom=max(top+.01f,points.maxOf{it.y}).coerceAtMost(1f)
       val bounds=AnnotationBounds(left,top,right,bottom)
       val samples=if(latest.tool==AnnotationTool.FREEHAND)points.toList()else listOf(points.first(),points.last())
       val local=if(latest.tool in setOf(AnnotationTool.FREEHAND,AnnotationTool.ARROW,AnnotationTool.LINE))samples.map{AnnotationPoint(((it.x-left)/(right-left)).coerceIn(0f,1f),((it.y-top)/(bottom-top)).coerceIn(0f,1f))}else emptyList()
       editor.add(bounds,local)
      }
     }
    }finally{points.clear()}
   }
  }
 }
 val canvasBackground = MaterialTheme.colorScheme.surfaceContainerHigh
 Canvas(Modifier.fillMaxWidth().height(previewHeight).onSizeChanged{viewport=it}.then(gestures).testTag("annotation-canvas").semantics{contentDescription="Annotation page preview. Use mark list and movement controls for accessible editing."}) {
  drawRect(canvasBackground)
  val g=geometry
  if(g!=null&&image!=null&&!image.isRecycled){
   val a=g.toDisplay(AnnotationPoint(0f,0f));val b=g.toDisplay(AnnotationPoint(1f,1f))
   drawIntoCanvas{canvas->canvas.nativeCanvas.drawBitmap(image,null,android.graphics.RectF(a.first,a.second,b.first,b.second),Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))}
   points.zipWithNext().forEach{(a,b)->val p=g.toDisplay(a.rotated(page.rotation.degrees));val q=g.toDisplay(b.rotated(page.rotation.degrees));drawLine(Color.Red,Offset(p.first,p.second),Offset(q.first,q.second),3.dp.toPx())}
   draft.layer.marks.firstOrNull{it.id==draft.selected}?.let{mark->
    val corners=listOf(AnnotationPoint(mark.bounds.left,mark.bounds.top),AnnotationPoint(mark.bounds.right,mark.bounds.bottom),AnnotationPoint(mark.bounds.left,mark.bounds.bottom),AnnotationPoint(mark.bounds.right,mark.bounds.top)).map{g.toDisplay(it.rotated(page.rotation.degrees))}
    drawRect(Color.Cyan,Offset(corners.minOf{it.first},corners.minOf{it.second}),androidx.compose.ui.geometry.Size(corners.maxOf{it.first}-corners.minOf{it.first},corners.maxOf{it.second}-corners.minOf{it.second}),style=androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx()))
   }
  }
 }
 if(base==ManagedDocumentPreviewState.Unavailable)Text("Page preview unavailable. Cancel and reopen the page.",color=MaterialTheme.colorScheme.error)
 if(appearance==ManagedDocumentPreviewState.Unavailable&&bitmap!=null)Text("Appearance unavailable; resolve the text error before applying.",color=MaterialTheme.colorScheme.error)
 FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
  RmeSecondaryButton(onClick={zoom=(zoom-.25f).coerceAtLeast(1f)}){Text("Zoom out")}
  RmeSecondaryButton(onClick={zoom=(zoom+.25f).coerceAtMost(4f)}){Text("Zoom in")}
  RmeTextButton(onClick={zoom=1f;pan=Offset.Zero}){Text("Fit page")}
 }
}
