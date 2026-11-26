package org.synapseworks.pageharbor.ui.home

import org.synapseworks.pageharbor.ui.theme.RmeActionRow
import org.synapseworks.pageharbor.ui.theme.RmePrimaryButton
import org.synapseworks.pageharbor.ui.theme.RmeSecondaryButton
import org.synapseworks.pageharbor.ui.theme.RmeTextButton
import org.synapseworks.pageharbor.ui.theme.RmeTextField

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.synapseworks.pageharbor.businesscard.*
import org.synapseworks.pageharbor.table.*
import org.synapseworks.pageharbor.document.capture.CameraAccess

@Composable internal fun TableScreen(model: TableViewModel, chooseImage: (String, Long) -> Unit,
    allowCamera: (String, Long) -> Unit, save:(String,Long,String)->Unit, workbook:(()->Unit)?=null, dismiss:()->Unit={model.close()}) {
    val s = model.state ?: return
    val context = LocalContext.current; val lifecycle = LocalLifecycleOwner.current
    var allowed by remember(s.owner, s.epoch) { mutableStateOf(CameraAccess.allowed(context)) }
    var showSource by remember(s.owner, s.epoch) { mutableStateOf(false) }
    val horizontal=rememberScrollState()
    var column by remember(s.owner) { mutableIntStateOf(0) }
    var evidence by remember(s.owner,s.epoch) { mutableStateOf<String?>(null) }
    DisposableEffect(s.owner, s.epoch, lifecycle) {
        var watching = true
        val observer = LifecycleEventObserver { _, event -> if(event == Lifecycle.Event.ON_RESUME) allowed = CameraAccess.allowed(context) }
        lifecycle.lifecycle.addObserver(observer)
        val ops = context.getSystemService(android.app.AppOpsManager::class.java)
        val listener = android.app.AppOpsManager.OnOpChangedListener { op, pkg ->
            if((op == null || op == android.app.AppOpsManager.OPSTR_CAMERA) && (pkg == null || pkg == context.packageName))
                ContextCompat.getMainExecutor(context).execute { if(watching) { allowed = CameraAccess.allowed(context); if(!allowed) model.pause(s.owner, s.epoch) } }
        }
        try { ops?.startWatchingMode(android.app.AppOpsManager.OPSTR_CAMERA, context.packageName, listener) }
        catch(_: SecurityException) { allowed = false; model.pause(s.owner, s.epoch) }
        onDispose { watching = false; lifecycle.lifecycle.removeObserver(observer); runCatching { ops?.stopWatchingMode(listener) } }
    }
    BackHandler { when(s.phase){TablePhase.REVIEW->model.correct();TablePhase.DESTINATION,TablePhase.WAITING,TablePhase.SAVING->model.cancelExport();else->dismiss()} }
    Surface(Modifier.fillMaxSize().testTag("table-screen")) {
        if (allowed && (s.phase == TablePhase.CAMERA || s.phase == TablePhase.CAPTURING)) {
            SpecialistCamera(
                tool = "table", title = "Extract table", shutterLabel = "Capture table", manualLabel = "Enter cells manually",
                owner = s.owner, epoch = s.epoch, capturing = s.phase == TablePhase.CAPTURING,
                begin = { model.beginCapture(s.owner, s.epoch) }, captured = model::captured, failed = model::captureFailed,
                unavailable = { model.pause(s.owner, s.epoch) }, chooseImage = { chooseImage(s.owner, s.epoch) },
                manual = model::manual, close = dismiss,
            )
            return@Surface
        }
        LazyColumn(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(12.dp).testTag("table-list"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item(key = "table-item-1") { Text("Extract table", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() }) }
            item(key = "table-item-2") { Text("On-device Latin OCR proposes one table, up to 40 rows and 12 columns. Verify cell text, blank positions and structure before saving CSV. Text and drafts stay temporary; links and formulas never run here.") }
            when(s.phase) {
                TablePhase.CAMERA, TablePhase.CAPTURING -> {
                    item(key = "table-item-4") { RmePrimaryButton(onClick = { allowCamera(s.owner, s.epoch) }, modifier = Modifier.fillMaxWidth().testTag("table-permission")) { Text("Allow camera") } }
                }
                TablePhase.PAUSED -> item(key = "table-item-6") { RmePrimaryButton(onClick = model::reset, modifier = Modifier.fillMaxWidth().testTag("table-retry")) { Text("Resume camera") } }
                TablePhase.IMAGE -> item(key = "table-item-7") { Text("Waiting for image selection…", Modifier.testTag("table-waiting")) }
                TablePhase.OCR -> item(key = "table-item-8") { Text("Recognizing table…", Modifier.testTag("table-working").semantics { liveRegion = LiveRegionMode.Polite }) }
                TablePhase.EDIT,TablePhase.REVIEW -> {
                    val reviewing=s.phase==TablePhase.REVIEW
                    val grid=if(reviewing)s.review!!.grid else s.grid
                    item(key="grid-guide") {
                        Text("${grid.rows.size} rows × ${grid.columns} columns. First row stays in place; blank cells remain blank. Verify every column and row against the original. Swipe rows sideways to see all columns; scroll down for more rows.")
                        s.extraction?.concerns?.forEach { Text(it.message,color=MaterialTheme.colorScheme.error) }
                        if(s.layoutChanged)Text("Layout corrected by you. Original OCR remains available below.")
                    }
                    if(reviewing)item(key="review-title"){Text("Review CSV grid",Modifier.testTag("table-reviewed"),style=MaterialTheme.typography.titleMedium)}
                    if(!reviewing) {
                        item(key="column-controls") {
                            val selected=column.coerceIn(0,grid.columns-1)
                            Text("Column ${selected+1} of ${grid.columns}")
                            RmeTextButton(onClick={column=(selected+1)%grid.columns},modifier=Modifier.testTag("table-column-next")){Text("Select next column")}
                            RmeActionRow {
                                RmeSecondaryButton(onClick={model.layout(s.owner,s.epoch){it.insertColumn(selected+1)}},enabled=grid.columns<TableLimits.COLS,modifier=Modifier.weight(1f).testTag("table-add-column")){Text("Insert column after")}
                                RmeSecondaryButton(onClick={model.layout(s.owner,s.epoch){it.deleteColumn(selected)}},enabled=grid.columns>1,modifier=Modifier.weight(1f).testTag("table-delete-column")){Text("Delete column")}
                            }
                            RmeActionRow {
                                RmeTextButton(onClick={model.layout(s.owner,s.epoch){it.moveColumn(selected,selected-1)};column=(selected-1).coerceAtLeast(0)},enabled=selected>0,modifier=Modifier.weight(1f).testTag("table-column-left")){Text("Move left")}
                                RmeTextButton(onClick={model.layout(s.owner,s.epoch){it.moveColumn(selected,selected+1)};column=(selected+1).coerceAtMost(grid.columns-1)},enabled=selected<grid.columns-1,modifier=Modifier.weight(1f).testTag("table-column-right")){Text("Move right")}
                            }
                        }
                    }
                    grid.rows.forEachIndexed { rowIndex,row ->
                        item(key="row-header-$rowIndex") {
                            Text("Row ${rowIndex+1}",Modifier.testTag("table-row-$rowIndex"),style=MaterialTheme.typography.titleMedium)
                            if(!reviewing) {
                                RmeActionRow {
                                    RmeTextButton(onClick={model.layout(s.owner,s.epoch){it.insertRow(rowIndex+1)}},enabled=grid.rows.size<TableLimits.ROWS,modifier=Modifier.weight(1f).testTag("table-row-insert-$rowIndex")){Text("Insert row below")}
                                    RmeTextButton(onClick={model.layout(s.owner,s.epoch){it.deleteRow(rowIndex)}},enabled=grid.rows.size>1,modifier=Modifier.weight(1f).testTag("table-row-delete-$rowIndex")){Text("Delete row")}
                                }
                                RmeActionRow {
                                    RmeTextButton(onClick={model.layout(s.owner,s.epoch){it.moveRow(rowIndex,rowIndex-1)}},enabled=rowIndex>0,modifier=Modifier.weight(1f).testTag("table-row-up-$rowIndex")){Text("Move row up")}
                                    RmeTextButton(onClick={model.layout(s.owner,s.epoch){it.moveRow(rowIndex,rowIndex+1)}},enabled=rowIndex<grid.rows.lastIndex,modifier=Modifier.weight(1f).testTag("table-row-down-$rowIndex")){Text("Move row down")}
                                }
                            }
                        }
                        item(key="row-cells-$rowIndex") {
                            Row(Modifier.fillMaxWidth().horizontalScroll(horizontal).testTag("table-horizontal-$rowIndex"),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                row.forEachIndexed { columnIndex,cell -> key(cell.id) {
                                    Column(Modifier.width(180.dp)) {
                                        if(reviewing) {
                                            Text("Column ${columnIndex+1}")
                                            Text(if(cell.value.isEmpty())"(blank)" else cardDisplay(cell.value),Modifier.testTag("table-reviewed-$rowIndex-$columnIndex"))
                                            val outgoing=TableCsv.outgoing(cell.value)
                                            if(outgoing!=cell.value)Text("CSV text: "+cardDisplay(outgoing),Modifier.testTag("table-escaped-$rowIndex-$columnIndex"),color=MaterialTheme.colorScheme.primary)
                                        } else RmeTextField(value=cell.value,onValueChange={model.edit(s.owner,s.epoch,cell.id,it)},label={Text("Row ${rowIndex+1}, column ${columnIndex+1}")},maxLines=6,modifier=Modifier.fillMaxWidth().testTag("table-cell-$rowIndex-$columnIndex"))
                                        if(cell.uncertain)Text("Uncertain placement / blank — verify")
                                        if(cell.corrected)Text("Corrected by you")
                                        RmeTextButton(onClick={evidence=cell.id},modifier=Modifier.testTag("table-evidence-$rowIndex-$columnIndex")){Text("Original evidence")}
                                    }
                                } }
                            }
                        }
                    }
                    item(key="source-toggle"){RmeTextButton(onClick={showSource=!showSource},modifier=Modifier.testTag("table-source-toggle")){Text("Review original OCR text")}}
                    if(showSource)item(key="source"){Text(cardDisplay(s.extraction?.source.orEmpty()),Modifier.testTag("table-source"))}
                    if(reviewing) {
                        item(key="csv-policy") { Text("CSV uses UTF-8, commas, quoted cells and CRLF rows. Formula-like text gets the visible prefix ‘text: ’; numeric literals stay unchanged. Original values remain above. Import columns as Text in your spreadsheet to keep leading zeros and avoid automatic date/number conversion. No table draft is saved in RME.") }
                        item(key="confirm") { Row {
                            Checkbox(checked=s.confirmed,onCheckedChange={model.confirm(s.owner,s.epoch,s.review!!.id,it)},modifier=Modifier.testTag("table-confirm"))
                            Text("I checked the grid, blank cells, uncertain structure and CSV escaping.",Modifier.weight(1f))
                        } }
                        if(workbook!=null)item(key="workbook"){RmePrimaryButton(onClick=workbook,enabled=s.confirmed,modifier=Modifier.fillMaxWidth().testTag("table-workbook")){Text("Export reviewed workbook")}}
                        item(key="save"){RmePrimaryButton(onClick={save(s.owner,s.epoch,s.review!!.id)},enabled=s.confirmed,modifier=Modifier.fillMaxWidth().testTag("table-save")){Text("Save CSV")}}
                        item(key="correct"){RmeSecondaryButton(onClick=model::correct,modifier=Modifier.fillMaxWidth().testTag("table-correct")){Text("Correct grid")}}
                    } else item(key="review"){RmePrimaryButton(onClick={model.review(s.owner,s.epoch)},modifier=Modifier.fillMaxWidth().testTag("table-review")){Text("Review CSV grid")}}
                }
                TablePhase.DESTINATION,TablePhase.WAITING,TablePhase.SAVING -> {
                    item(key="save-progress"){Text(if(s.phase==TablePhase.SAVING)"Saving reviewed CSV…"else"Waiting for Save destination…",Modifier.testTag("table-saving").semantics{liveRegion=LiveRegionMode.Polite})}
                    item(key="cancel-export"){RmeSecondaryButton(onClick=model::cancelExport,modifier=Modifier.fillMaxWidth().testTag("table-cancel-export")){Text("Cancel export")}}
                }
            }
            s.problem?.let { problem->item(key="problem"){Text(problem,Modifier.testTag("table-error"),color=MaterialTheme.colorScheme.error)} }
            if(s.phase in setOf(TablePhase.CAMERA,TablePhase.PAUSED))item(key="manual"){RmeSecondaryButton(onClick=model::manual,modifier=Modifier.fillMaxWidth().testTag("table-manual")){Text("Enter cells manually")}}
            if(s.phase in setOf(TablePhase.CAMERA,TablePhase.PAUSED,TablePhase.EDIT,TablePhase.REVIEW))item(key="image"){RmeSecondaryButton(onClick={chooseImage(s.owner,s.epoch)},modifier=Modifier.fillMaxWidth().testTag("table-image")){Text("Choose table image")}}
            if(s.phase in setOf(TablePhase.EDIT,TablePhase.REVIEW))item(key="rescan"){RmeSecondaryButton(onClick=model::reset,modifier=Modifier.fillMaxWidth().testTag("table-recapture")){Text("Discard and scan again")}}
            item(key="close"){RmeTextButton(onClick=dismiss,modifier=Modifier.fillMaxWidth().testTag("table-close")){Text("Back to library")}}
        }
    }
    val selected=s.grid.rows.flatten().firstOrNull{it.id==evidence}
    if(selected!=null)AlertDialog(onDismissRequest={evidence=null},title={Text("Original cell evidence")},text={
        Column(Modifier.heightIn(max=320.dp).verticalScroll(rememberScrollState())) {
            if(selected.evidence.isEmpty())Text("Manual cell — no original OCR reference.")
            selected.evidence.forEach { id->s.extraction?.units?.firstOrNull{it.id==id}?.let{unit->
                Text("Original OCR row ${unit.line+1}: "+cardDisplay(unit.text))
                if(s.phase==TablePhase.EDIT)RmeTextButton(onClick={model.edit(s.owner,s.epoch,selected.id,unit.text);evidence=null},modifier=Modifier.testTag("table-use-original")){Text("Use original OCR")}
            } }
            selected.readings.forEach { reading->
                Text("Source crop (${reading.targetTextHeight}px text): "+cardDisplay(reading.text))
                if(s.phase==TablePhase.EDIT)RmeTextButton(onClick={model.edit(s.owner,s.epoch,selected.id,reading.text);evidence=null},modifier=Modifier.testTag("table-use-crop-${reading.targetTextHeight}")){Text("Use this crop reading")}
            }
        }
    },confirmButton={RmeTextButton(onClick={evidence=null},modifier=Modifier.testTag("table-evidence-close")){Text("Close")}})
}
