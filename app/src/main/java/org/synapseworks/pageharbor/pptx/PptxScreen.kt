package org.synapseworks.pageharbor.pptx

import org.synapseworks.pageharbor.ui.theme.RmeSecondaryButton
import org.synapseworks.pageharbor.ui.theme.RmeActionRow
import org.synapseworks.pageharbor.ui.theme.RmePrimaryButton
import org.synapseworks.pageharbor.ui.theme.RmeTextButton
import org.synapseworks.pageharbor.ui.theme.RmeTextField

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.synapseworks.pageharbor.docx.DocxBox
import android.graphics.BitmapFactory
import org.synapseworks.pageharbor.ui.home.ManagedDocumentPreviewState
import org.synapseworks.pageharbor.ui.home.rememberManagedDocumentPreview

/** Managed plan comparison. Native office rendering remains independent validation. */
@Composable internal fun PptxScreen(vm:PptxViewModel,save:()->Unit,share:()->Unit,queue:(()->Unit)?=null) {
    BackHandler{vm.close()}
    val d=vm.deck;val p=d?.pages?.getOrNull(vm.page);val enabled=!vm.busy&&!vm.picker
    var region by remember(vm.capture,vm.page){mutableStateOf<DocxBox?>(null)}
    var textFree by remember(vm.capture,vm.page,region){mutableStateOf(false)}
    var omit by remember{mutableStateOf<Int?>(null)}
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(14.dp).testTag("pptx-screen"),verticalArrangement=Arrangement.spacedBy(9.dp)) {
        Text("PPTX export",style=MaterialTheme.typography.headlineSmall)
        Text("Page-image slides preserve current appearance as images. Editable slides use native text, selected text-free images and manually reviewed simple shapes. Original presentation objects, charts, animations and fonts are not recovered.")
        Text("OCR can predate visual edits or miss content. Pictures can retain covered/unrecognized content. This is not secure removal; use Share-Safe. Originals remain unchanged.")
        RmeTextButton(onClick=vm::close,modifier=Modifier.testTag("pptx-cancel")){Text("Cancel / close PPTX")}
        vm.message?.let{Text(it,Modifier.testTag("pptx-message"))}
        if(vm.busy){LinearProgressIndicator(Modifier.fillMaxWidth());Text("Processing locally · ${vm.progress} slides",Modifier.testTag("pptx-progress"))}
        if(d!=null&&p!=null) {
            Row{PptxMode.entries.forEach{m->RmeTextButton(onClick={vm.settings(m,d.size)},enabled=enabled,modifier=Modifier.testTag("pptx-mode-${m.name}")){Text(m.label)}}}
            Row{PptxSize.entries.forEach{s->RmeTextButton(onClick={vm.settings(d.mode,s)},enabled=enabled,modifier=Modifier.testTag("pptx-size-${s.name}")){Text(s.name)}}}
            Text("${d.mode.label} · ${d.size.name} ${d.size.width.toInt()} × ${d.size.height.toInt()} pt · page ${vm.page+1}/${d.pages.size}")
            RmeActionRow {RmeTextButton(onClick={vm.show(vm.page-1)},enabled=enabled&&vm.page>0){Text("Previous page")};RmeTextButton(onClick={vm.show(vm.page+1)},enabled=enabled&&vm.page<d.pages.lastIndex){Text("Next page")}}
            RmeActionRow {RmeTextButton(onClick={vm.compare(true)},enabled=enabled,modifier=Modifier.testTag("pptx-source")){Text("Compare source")};RmeTextButton(onClick={vm.compare(false)},enabled=enabled,modifier=Modifier.testTag("pptx-result")){Text("Refresh slide preview")}}
            Text(if(vm.sourceView)"Frozen current appearance. Drag to select a source region."else"Local export-plan preview; font substitution and reflow can differ in presentation apps.")
            if(vm.previewStale)Text("Preview is stale. Refresh before confirming this page.")
            vm.preview?.let{request->key(request){
                val display by rememberManagedDocumentPreview(requestKey=request,decode={BitmapFactory.decodeFile(request.file.path)})
                LaunchedEffect(request,display){vm.displayReady(request,display is ManagedDocumentPreviewState.Ready)}
                val bitmap=(display as? ManagedDocumentPreviewState.Ready)?.owner?.bitmap
                if(display==ManagedDocumentPreviewState.Unavailable)Text("Display unavailable. Refresh or cancel; this page cannot be reviewed.")
                if(bitmap!=null){val image=bitmap.asImageBitmap()
                Canvas(Modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat()/bitmap.height).testTag("pptx-preview").pointerInput(vm.capture,vm.page,vm.sourceView,enabled){var start=Offset.Zero
                    detectDragGestures(onDragStart={start=it;region=null},onDrag={change,_->if(enabled&&vm.sourceView){val a=(start.x/size.width).coerceIn(0f,1f);val b=(start.y/size.height).coerceIn(0f,1f);val x=(change.position.x/size.width).coerceIn(0f,1f);val y=(change.position.y/size.height).coerceIn(0f,1f);if(kotlin.math.abs(a-x)>.003f&&kotlin.math.abs(b-y)>.003f)region=DocxBox(minOf(a,x),minOf(b,y),maxOf(a,x),maxOf(b,y))};change.consume()})
                }){drawImage(image,dstSize=androidx.compose.ui.unit.IntSize(size.width.toInt(),size.height.toInt()));if(vm.sourceView)region?.let{drawRect(Color.Red,Offset(it.left*size.width,it.top*size.height),androidx.compose.ui.geometry.Size(it.width*size.width,it.height*size.height),style=Stroke(3f))}}
            }}}
            if(d.mode==PptxMode.EDITABLE) {
                Text("Font requests: Arial; Helvetica for Greek/µ; East Asian fallback depends on the reader. Supplied BMP Latin/Greek/Cyrillic/East Asian LTR text is supported; original-font recovery, RTL/Indic/emoji and unrestricted reconstruction stay open. Editable rotated or annotated/signature pages are unsupported; use image mode.")
                Text("Automatic proposals preserve source text and geometry; figures/shapes are not discovered automatically. Correct every object and acknowledge missing artwork explicitly.")
                Text("Source ${p.source.origin} · ${if(p.geometry)"positioned geometry"else"missing/uncertain geometry: place text manually"} · omitted source lines ${p.omitted.size}")
                var raw by remember(vm.capture,vm.page){mutableStateOf(false)}
                RmeTextButton(onClick={raw=!raw}){Text("Show raw source OCR / native text")};if(raw)Text(p.source.sourceText)
                region?.let{box->Text("Selected source rectangle ${(box.left*100).toInt()}%, ${(box.top*100).toInt()}% to ${(box.right*100).toInt()}%, ${(box.bottom*100).toInt()}%")
                    Row{Checkbox(textFree,{textFree=it},enabled=enabled);Text("I checked this image region contains no text.",Modifier.weight(1f))}
                    RmePrimaryButton(onClick={vm.picture(box,textFree);region=null},enabled=enabled&&textFree){Text("Add reviewed image crop")}
                    Row(Modifier.horizontalScroll(rememberScrollState())){listOf(PptxKind.RECT,PptxKind.ELLIPSE,PptxKind.LINE).forEach{k->RmeTextButton(onClick={vm.add(k,box);region=null},enabled=enabled){Text("Trace ${k.name}")}}}
                }
                RmeActionRow {RmeTextButton(onClick={vm.add(PptxKind.TEXT,region?:DocxBox(.1f,.1f,.9f,.3f));region=null},enabled=enabled){Text("Add native text")};RmeTextButton(onClick=vm::reset,enabled=enabled){Text("Reset proposals")}}
                p.items.forEachIndexed{index,b->key(vm.page,b.id){
                    OutlinedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                        Text("${index+1}. ${b.kind} · ${if(b.corrected)"USER CORRECTED / ADDED"else"AUTOMATIC PROPOSAL"} · source lines ${b.sourceIds.joinToString{(it+1).toString()}}")
                        if(b.kind==PptxKind.TEXT){
                            RmeTextField(b.text,{vm.item(b.copy(text=it.take(32768)))},label={Text("Object ${index+1} text")},enabled=enabled,modifier=Modifier.fillMaxWidth().testTag("pptx-text-${b.id}"))
                            var font by remember(b.font){mutableStateOf(b.font.toString())};RmeTextField(font,{font=it.take(8)},label={Text("Font points 8–48")},enabled=enabled)
                            RmeActionRow {RmeTextButton(onClick={font.toFloatOrNull()?.let{vm.item(b.copy(font=it))}},enabled=enabled){Text("Apply font")};RmeTextButton(onClick={vm.item(b.copy(bold=!b.bold))},enabled=enabled){Text(if(b.bold)"Bold on"else"Bold off")};RmeTextButton(onClick={vm.item(b.copy(align=PptxAlign.entries[(b.align.ordinal+1)%3]))},enabled=enabled){Text(b.align.name)}}
                        }
                        var position by remember(b.box){mutableStateOf(b.box?.let{"${it.left*100},${it.top*100},${it.width*100},${it.height*100}"}?:"10,10,80,20")}
                        var bad by remember{mutableStateOf(false)}
                        RmeTextField(position,{position=it.take(80);bad=false},label={Text("x,y,width,height % of source page")},enabled=enabled,isError=bad,modifier=Modifier.fillMaxWidth().testTag("pptx-position-${b.id}"))
                        RmeTextButton(onClick={val values=position.split(',').mapNotNull{it.trim().toFloatOrNull()};val box=runCatching{require(values.size==4);DocxBox(values[0]/100,values[1]/100,(values[0]+values[2])/100,(values[1]+values[3])/100)}.getOrNull();if(box!=null)vm.item(b.copy(box=box))else bad=true},enabled=enabled,modifier=Modifier.testTag("pptx-place-${b.id}")){Text("Apply placement (images keep aspect ratio)")}
                        if(b.kind!=PptxKind.IMAGE){
                            var color by remember(b.color){mutableStateOf(b.color)}
                            val validColor=color.matches(Regex("[0-9A-F]{6}"))
                            RmeTextField(color,{color=it.take(6).uppercase(java.util.Locale.ROOT)},label={Text("Opaque RGB color · six hexadecimal digits")},isError=!validColor,enabled=enabled,modifier=Modifier.testTag("pptx-color-${b.id}"))
                            RmeTextButton(onClick={vm.item(b.copy(color=color))},enabled=enabled&&validColor,modifier=Modifier.testTag("pptx-color-apply-${b.id}")){Text("Apply color")}
                        }
                        RmeActionRow {RmeTextButton(onClick={vm.move(b.id,-1)},enabled=enabled&&index>0){Text("Move up")};RmeTextButton(onClick={vm.move(b.id,1)},enabled=enabled&&index<p.items.lastIndex){Text("Move down")};RmeTextButton(onClick={omit=b.id},enabled=enabled){Text("Remove / omit…")}}
                    }}
                }}
            }else Text("Image-based: text, annotations and handwritten signatures are pixels. No editable text or cryptographic signature validity is transferred.")
            Row{Checkbox(p.coverage,vm::review,enabled=enabled&&!vm.sourceView&&!vm.previewStale&&vm.preview!=null&&vm.previewReady,modifier=Modifier.testTag("pptx-reviewed"));Text(if(d.mode==PptxMode.IMAGE)"I compared this page's current appearance, scaling and rotation."else"I reviewed native text, placement/order, images/shapes, source lines and all recreated or explicitly omitted artwork.",Modifier.weight(1f))}
            Text("Refresh the slide preview before confirming each page. Text boxes request shrink-on-overflow after external edits; inspect font size and layout in your presentation app. Unrestricted external growth is not guaranteed.")
            RmeTextField(vm.name,vm::rename,label={Text("Separate PPTX name")},enabled=enabled,isError=!vm.nameValid(),modifier=Modifier.fillMaxWidth().testTag("pptx-name"))
            d.problem()?.let{Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("pptx-problem"))}
            RmePrimaryButton(onClick=vm::generate,enabled=enabled&&vm.nameValid()&&d.pages.all{it.coverage}&&d.problem()==null,modifier=Modifier.testTag("pptx-generate")){Text("Generate reviewed PPTX")}
            vm.prepared?.let{r->Text("Frozen ${r.deck.mode.label} · ${r.digest.second} bytes · captured source revision ${r.revision}. Later source edits cannot retarget this copy.",Modifier.testTag("pptx-prepared"))
                RmeActionRow {RmePrimaryButton(onClick=save,enabled=enabled,modifier=Modifier.testTag("pptx-save")){Text("Save separate PPTX")};RmeSecondaryButton(onClick=share,enabled=enabled,modifier=Modifier.testTag("pptx-share")){Text("Share PPTX")}}
                if(queue!=null)RmePrimaryButton(onClick=queue,enabled=enabled){Text("Review frozen PPTX recipe")}
            }
        }
        Spacer(Modifier.height(24.dp))
    }
    omit?.let{id->AlertDialog(onDismissRequest={omit=null},title={Text("Explicitly omit this object?")},text={Text("Source lines are recorded as deliberately omitted from this export. Original source bytes and raw OCR remain unchanged. This is not secure removal.")},confirmButton={RmeTextButton(onClick={vm.omit(id);omit=null}){Text("Omit object")}},dismissButton={RmeTextButton(onClick={omit=null}){Text("Keep object")}})}
}
