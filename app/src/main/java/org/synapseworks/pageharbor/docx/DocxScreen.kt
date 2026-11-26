package org.synapseworks.pageharbor.docx

import org.synapseworks.pageharbor.ui.theme.RmeSecondaryButton
import org.synapseworks.pageharbor.ui.theme.RmeActionRow
import org.synapseworks.pageharbor.ui.theme.RmePrimaryButton
import org.synapseworks.pageharbor.ui.theme.RmeTextButton
import org.synapseworks.pageharbor.ui.theme.RmeTextField

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import org.synapseworks.pageharbor.table.TableGrid

/** Structure review, deliberately not presented as an office rendering. No rememberSaveable text. */
@Composable internal fun DocxScreen(vm:DocxViewModel,save:()->Unit,share:()->Unit,queue:()->Unit={}) {
    BackHandler{vm.close()}
    val d=vm.document;val p=d?.pages?.getOrNull(vm.page)
    var region by remember(vm.capture,vm.page){mutableStateOf<DocxBox?>(null)}
    var omit by remember(vm.capture,vm.page){mutableStateOf<Int?>(null)}
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp).testTag("docx-screen"),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("Editable DOCX",style=MaterialTheme.typography.headlineSmall)
        Text("Editable reflow: paragraphs, headings, lists, rectangular tables and selected images. This does not recover the original Word document. Measured Letter layout is available for aligned one/two-column profiles validated in LibreOffice 26.8.2. Full editable-layout parity remains OPEN.")
        Text("Source PDF forms, cryptographic signature validity and native objects are not transferred. Word font/style recovery is limited to the declared styles. Annotations and handwriting use raster appearance references, not editable Word marks.")
        Text("Source OCR can predate visual edits. Covered text may still be exported. Review every block; use Share-Safe for secure removal. Source PDFs and images are preserved.")
        RmeTextButton(onClick=vm::close,modifier=Modifier.testTag("docx-cancel")){Text("Cancel / close DOCX")}
        vm.message?.let{Text(it,modifier=Modifier.testTag("docx-message"))}
        if(vm.busy){LinearProgressIndicator(Modifier.fillMaxWidth());Text("Processing locally · ${vm.progress} source pages generated")}
        if(d!=null&&p!=null) {
            Text("Captured revision ${vm.prepared?.revision?:"review snapshot"} · ${vm.capture?.nativeReason.orEmpty()}")
            if(d.mode==DocxMode.LAYOUT)Text("Measured in LibreOffice 26.8.2. Pages compatibility is measured separately; inspect the exported layout in your office application. Microsoft Word layout is NOT_RUN. This is approximate layout, not a cross-office facsimile. Include markup/signatures using explicit figure regions; they are not inserted automatically in layout mode.")
            Text("${d.mode} · ${d.paper} · Arial 12, heading 18; Greek text requests Helvetica; office font substitution may occur. Supported LTR Latin, Greek, Cyrillic and East Asian text. RTL/Indic, complex tables and arbitrary layouts remain unsupported.")
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                RmeTextButton(onClick={vm.settings(DocxMode.REFLOW,d.paper)},enabled=!vm.recipeMode&&!vm.busy&&!vm.picker){Text("Editable reflow")}
                if(DOCX_LAYOUT_AVAILABLE)RmeTextButton(onClick={vm.settings(DocxMode.LAYOUT,d.paper)},enabled=!vm.busy&&!vm.picker){Text("LibreOffice-measured layout")}
            }
            Row {DocxPaper.entries.forEach{paper->RmeTextButton(onClick={vm.settings(d.mode,paper)},enabled=!vm.recipeMode&&!vm.busy&&!vm.picker){Text(paper.name)}}}
            Text("Page ${vm.page+1}/${d.pages.size} · ${p.origin} · proposed ${p.columns} column(s)")
            RmeActionRow {RmeTextButton(onClick={vm.showPage(vm.page-1)},enabled=!vm.busy&&vm.page>0){Text("Previous page")};RmeTextButton(onClick={vm.showPage(vm.page+1)},enabled=!vm.busy&&vm.page<d.pages.lastIndex){Text("Next page")}}
            Text("Frozen current appearance — comparison source. Drag a rectangle for a figure or table; the export structure below is editable, not an office-rendered preview.")
            vm.preview?.let { bitmap ->
                val image=bitmap.asImageBitmap()
                Canvas(Modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat()/bitmap.height).testTag("docx-region").pointerInput(vm.capture,vm.page,vm.busy,vm.picker){
                    var start=Offset.Zero
                    detectDragGestures(onDragStart={start=it;region=null},onDrag={change,_->
                        if(!vm.busy&&!vm.picker){val a=(start.x/size.width).coerceIn(0f,1f);val b=(start.y/size.height).coerceIn(0f,1f);val x=(change.position.x/size.width).coerceIn(0f,1f);val y=(change.position.y/size.height).coerceIn(0f,1f);if(kotlin.math.abs(a-x)>.002f&&kotlin.math.abs(b-y)>.002f)region=DocxBox(minOf(a,x),minOf(b,y),maxOf(a,x),maxOf(b,y))};change.consume()
                    })
                }) {
                    drawImage(image,dstSize=androidx.compose.ui.unit.IntSize(size.width.toInt(),size.height.toInt()))
                    region?.let{drawRect(Color.Red,Offset(it.left*size.width,it.top*size.height),androidx.compose.ui.geometry.Size(it.width*size.width,it.height*size.height),style=Stroke(3f))}
                }
            }
            region?.let{box->
                Text("Selected region ${ (box.left*100).toInt()}%, ${(box.top*100).toInt()}% to ${(box.right*100).toInt()}%, ${(box.bottom*100).toInt()}%")
                RmeActionRow {RmePrimaryButton(onClick={vm.region(box,false);region=null},enabled=!vm.busy&&!vm.picker){Text("Add figure")};RmeTextButton(onClick={vm.region(box,true);region=null},enabled=!vm.busy&&!vm.picker){Text("Propose table")}}
            }
            p.concerns.forEach{Text(it,style=MaterialTheme.typography.bodySmall)}
            if(p.omissions.isNotEmpty())Text("Explicitly omitted source lines: ${p.omissions.size}. Reset proposals restores them.")
            RmeActionRow {RmeTextButton(onClick=vm::addParagraph,enabled=!vm.busy&&!vm.picker){Text("Add paragraph")};RmeTextButton(onClick=vm::resetPage,enabled=!vm.busy&&!vm.picker){Text("Reset proposals")}}
            p.blocks.forEachIndexed { index,b ->
                key(vm.page,b.id) {
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                            Text("${index+1}. ${b.kind} · ${if(b.corrected)"USER CORRECTED"else "AUTOMATIC PROPOSAL"} · column ${b.column+1}")
                            b.note?.let{Text(it,style=MaterialTheme.typography.bodySmall)}
                            if(b.sourceIds.isNotEmpty())Text("Source lines ${b.sourceIds.joinToString()} · ${b.box?.let{"coordinates retained"}?:"geometry unavailable"}",style=MaterialTheme.typography.bodySmall)
                            if(b.kind !in setOf(DocxKind.IMAGE,DocxKind.TABLE)) {
                                RmeTextField(value=b.text,onValueChange={vm.block(b.copy(text=it.take(32768)))},label={Text("Block ${index+1} text")},enabled=!vm.busy&&!vm.picker,modifier=Modifier.fillMaxWidth().testTag("docx-text-${b.id}"))
                                Row(Modifier.horizontalScroll(rememberScrollState())){listOf(DocxKind.BODY,DocxKind.HEADING,DocxKind.BULLET,DocxKind.NUMBER).forEach{kind->RmeTextButton(onClick={vm.block(b.copy(kind=kind))},enabled=!vm.busy&&!vm.picker){Text(kind.name)}}}
                                RmeActionRow {RmeTextButton(onClick={vm.split(b.id)},enabled=!vm.busy&&!vm.picker){Text("Split at newlines")};RmeTextButton(onClick={vm.join(b.id)},enabled=!vm.busy&&!vm.picker){Text("Join next")}}
                            }
                            if(b.kind==DocxKind.TABLE)b.table?.let { grid -> DocxTableReview(grid,!vm.busy&&!vm.picker){vm.table(b.id,it)} }
                            Row(Modifier.horizontalScroll(rememberScrollState())) {
                                RmeTextButton(onClick={vm.move(b.id,-1)},enabled=!vm.busy&&!vm.picker&&index>0){Text("Move up")}
                                RmeTextButton(onClick={vm.move(b.id,1)},enabled=!vm.busy&&!vm.picker&&index<p.blocks.lastIndex){Text("Move down")}
                                RmeTextButton(onClick={vm.block(b.copy(column=if(b.column==0)1 else 0))},enabled=!vm.busy&&!vm.picker){Text("Change column")}
                                RmeTextButton(onClick={omit=b.id},enabled=!vm.busy&&!vm.picker){Text("Omit block…")}
                            }
                        }
                    }
                }
            }
            Row {Checkbox(vm.page in vm.reviewed,{vm.reviewPage(it)},enabled=!vm.busy&&!vm.picker,modifier=Modifier.testTag("docx-reviewed"));Text("I reviewed this page's text, order, grouping, tables, figures and explicit omissions.",Modifier.weight(1f))}
            if(d.mode==DocxMode.REFLOW)Row {Checkbox(vm.appearances,vm::appearance,enabled=vm.capture?.requiresAppearance!=true&&!vm.recipeMode&&!vm.busy&&!vm.picker);Text("Append current-appearance reference per source page. Captures filters, adjustments, annotations and signatures as images; duplicates visible text and can add pages. This is separate from the editable structure.",Modifier.weight(1f))}
            RmeTextField(vm.name,vm::rename,label={Text("Separate DOCX name")},isError=vm.nameProblem(),enabled=!vm.recipeMode&&!vm.busy&&!vm.picker,modifier=Modifier.fillMaxWidth())
            d.problem()?.let{Text(it,modifier=Modifier.testTag("docx-problem"))}
            RmePrimaryButton(onClick=vm::generate,enabled=!vm.busy&&!vm.picker&&vm.reviewed.size==d.pages.size&&d.problem()==null&&!vm.nameProblem()&&vm.preview!=null,modifier=Modifier.testTag("docx-generate")){Text("Generate reviewed DOCX")}
            vm.prepared?.let { result ->
                if(vm.recipeMode)RmePrimaryButton(onClick=queue,enabled=!vm.busy&&!vm.picker){Text("Review captured DOCX recipe")}
                Text("Frozen reviewed output · ${result.digest.second} bytes · ${result.document.mode} · source revision ${result.revision}. Later source edits cannot retarget these bytes.")
                RmeActionRow {RmePrimaryButton(onClick=save,enabled=!vm.busy&&!vm.picker,modifier=Modifier.testTag("docx-save")){Text("Save separate DOCX")};RmeSecondaryButton(onClick=share,enabled=!vm.busy&&!vm.picker,modifier=Modifier.testTag("docx-share")){Text("Share DOCX")}}
            }
            Text("M30 DOCX recipes require this per-document structure review; fixed Letter reflow with appearance references. Other Office operations remain unavailable. Full editable-layout parity remains OPEN.",style=MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(24.dp))
    }
    omit?.let{id->AlertDialog(onDismissRequest={omit=null},title={Text("Explicitly omit this block?")},text={Text("Its source lines will be recorded as deliberately omitted from this export. Original source bytes remain unchanged.")},confirmButton={RmeTextButton(onClick={vm.omit(id);omit=null}){Text("Omit from DOCX")}},dismissButton={RmeTextButton(onClick={omit=null}){Text("Keep block")}})}
}

@Composable private fun DocxTableReview(grid:TableGrid,enabled:Boolean,change:(TableGrid)->Unit) {
    Text("Editable table ${grid.rows.size} × ${grid.columns}. Verify source cells before correction.")
    Column(Modifier.horizontalScroll(rememberScrollState())) {
        grid.rows.forEachIndexed{r,row->Row {row.forEachIndexed{c,cell->RmeTextField(cell.value,{change(grid.edit(cell.id,it.take(4096)))},label={Text("R${r+1} C${c+1}")},enabled=enabled,modifier=Modifier.width(150.dp))};RmeTextButton(onClick={change(grid.deleteRow(r))},enabled=enabled&&grid.rows.size>1){Text("Delete row")};if(r>0)RmeTextButton(onClick={change(grid.moveRow(r,r-1))},enabled=enabled){Text("Row up")}}}
        Row {repeat(grid.columns){c->RmeTextButton(onClick={change(grid.deleteColumn(c))},enabled=enabled&&grid.columns>1,modifier=Modifier.width(150.dp)){Text("Delete col ${c+1}")}}}
        RmeActionRow {RmePrimaryButton(onClick={change(grid.insertRow(grid.rows.size))},enabled=enabled&&grid.rows.size<20){Text("Add row")};RmeTextButton(onClick={change(grid.insertColumn(grid.columns))},enabled=enabled&&grid.columns<6){Text("Add column")}}
    }
}
