package org.synapseworks.pageharbor.workflow

import org.synapseworks.pageharbor.ui.theme.RmeActionRow
import org.synapseworks.pageharbor.ui.theme.RmeIconButton
import org.synapseworks.pageharbor.ui.theme.RmePrimaryButton
import org.synapseworks.pageharbor.ui.theme.RmeSecondaryButton
import org.synapseworks.pageharbor.ui.theme.RmeTextButton
import org.synapseworks.pageharbor.ui.theme.RmeTextField

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription

@Composable
internal fun WorkflowScreen(vm:WorkflowViewModel,canCapture:Boolean,onCapture:(CharArray)->Unit,onSuggest:()->Unit,onTree:()->Unit,onManual:(String)->Unit) {
    var password by remember { mutableStateOf("") }
    val draft = vm.draft
    val review = vm.review
    val back: () -> Unit = {
        password = ""
        when {
            review != null -> vm.discardReview()
            draft != null -> vm.dismissDraft()
            else -> vm.close()
        }
    }
    BackHandler(onBack = back)
    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp).testTag("workflows"),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val isPresetList = draft == null && review == null
            RmeIconButton(onClick = back) {
                Icon(
                    imageVector = if (isPresetList) Icons.Default.Close else Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = if (isPresetList) "Close" else "Back",
                )
            }
            Text(
                text = when {
                    review != null -> "Review export"
                    draft != null -> "Export preset"
                    else -> "Export presets"
                },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.headlineSmall,
            )
        }
        vm.message?.let { Text(it, Modifier.testTag("workflow-message")) }
        if (!vm.queue.store.healthy) return@Column
        if (vm.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Preparing copy…")
        }
        if(review!=null) {
            Text(if(review.recipe.preset==WorkflowPreset.OFFICE)"Review captured Office PDF"else "Review captured revision ${review.revision}",style=MaterialTheme.typography.titleLarge)
            Text("${review.recipe.preset.label}\n${review.name}\n${review.captured.bytes} bytes\nDate: ${review.at} (${review.zone})\nCounter: ${review.recipe.nextCounter}\nDestination: ${workflowDestinationLabel(review.recipe.tree)}")
            if(review.recipe.preset !in setOf(WorkflowPreset.DOCX,WorkflowPreset.XLSX,WorkflowPreset.PPTX,WorkflowPreset.OFFICE))Text("Raster-based separate PDF. Current edits, annotations and signature appearance are included. Native PDF objects are not preserved. Share-Safe remains secure removal.")
            if(review.recipe.preset==WorkflowPreset.OFFICE)Text("Immutable reviewed Office PDF. Every page and substitution/print limits were reviewed before capture. This isolated preview shows those exact PDF bytes. Original Office access is retained only for destination alias checks, never reconversion. Covered or hidden text can remain recoverable; Share-Safe remains secure removal.")
            if(review.recipe.preset==WorkflowPreset.DOCX)Text("Reviewed editable reflow DOCX · Letter · Arial 12/18 · current-appearance references. This image preview shows the captured source appearance, not a Word rendering. Structure was reviewed in the DOCX editor. The queued bytes and name are immutable.")
            if(review.recipe.preset==WorkflowPreset.XLSX)Text("Frozen reviewed XLSX. Values, types, source references and totals were reviewed in the workbook editor. This summary is not a spreadsheet rendering. Jobs retain exact reviewed bytes, not live extraction settings.")
            if(review.recipe.preset==WorkflowPreset.PPTX)Text("Frozen reviewed PPTX: mode, slide size, native objects or image-based appearance were reviewed in the presentation editor. This is a local export-plan preview, not an Office rendering. Exact queued bytes and settings cannot be retargeted by later edits.")
            if(review.recipe.preset==WorkflowPreset.ARCHIVAL) {
                Text(ARCHIVAL_NOTICE,Modifier.testTag("archival-notice"))
                Row { Checkbox(checked=vm.archivalAccepted,onCheckedChange=vm::acceptArchival,modifier=Modifier.testTag("archival-accept").semantics { contentDescription="Accept flattened archival copy" });Text("I reviewed the flattened image-only archival copy") }
            }
            Text(if(review.recipe.preset==WorkflowPreset.XLSX)"Workbook summary"else "Page ${vm.page+1} of ${review.pages}")
            vm.preview?.let { Image(it.asImageBitmap(),"Captured PDF preview",Modifier.fillMaxWidth().heightIn(max=480.dp).testTag("workflow-preview")) }
            RmeActionRow {
                RmeTextButton(onClick={vm.renderPage(vm.page-1)},enabled=!vm.busy && vm.page>0){Text("Previous")}
                RmeTextButton(onClick={vm.renderPage(vm.page+1)},enabled=!vm.busy && vm.page+1<review.pages){Text("Next")}
            }
            RmePrimaryButton(onClick=vm::enqueue,enabled=!vm.busy && vm.preview!=null && (review.recipe.preset!=WorkflowPreset.ARCHIVAL || vm.archivalAccepted),modifier=Modifier.testTag("workflow-enqueue")){Text("Add to export queue")}
            return@Column
        }
        if (draft != null) {
                val saved = vm.data.recipes.any { it == draft }
                if (!draft.preset.available) Text("This preset uses an unavailable format. Choose another format to use it here.")
                RmeTextField(draft.label,{vm.change(draft.copy(label=it))},singleLine=true,label={Text("Preset name")},modifier=Modifier.fillMaxWidth(),enabled=!vm.busy)
                var formatMenu by remember { mutableStateOf(false) }
                Text("Format", style = MaterialTheme.typography.labelLarge)
                Box {
                    RmeSecondaryButton(onClick = { formatMenu = true }, enabled = !vm.busy) {
                        Text(draft.preset.label, Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    DropdownMenu(expanded = formatMenu, onDismissRequest = { formatMenu = false }) {
                        WorkflowPreset.entries.filter { it.available }.forEach { preset ->
                            DropdownMenuItem(
                                text = { Text(preset.label) },
                                onClick = {
                                    formatMenu = false
                                    vm.change(draft.copy(preset = preset))
                                },
                            )
                        }
                    }
                }
                if(draft.preset==WorkflowPreset.ARCHIVAL)Text(ARCHIVAL_NOTICE)
                var fileSettings by remember { mutableStateOf(false) }
                RmeTextButton(onClick = { fileSettings = !fileSettings }) {
                    Text(if (fileSettings) "Hide filename and folder" else "Filename and folder")
                }
                if (fileSettings) {
                    RmeTextField(draft.custom,{vm.change(draft.copy(custom=it))},singleLine=true,label={Text("File title")},modifier=Modifier.fillMaxWidth(),enabled=!vm.busy)
                    RmeTextField(draft.template,{vm.change(draft.copy(template=it))},singleLine=true,label={Text("Filename pattern")},supportingText={Text("Use {custom}, {date} or {counter}.")},modifier=Modifier.fillMaxWidth(),enabled=!vm.busy)
                    if (canCapture) RmeTextButton(onClick=onSuggest,enabled=!vm.busy){Text("Suggest title from text")}
                    vm.suggestion?.let { title ->
                        Text(title)
                        RmeTextButton(onClick=vm::confirmSuggestion){Text("Use suggested title")}
                    }
                    Text(workflowDestinationLabel(draft.tree))
                    RmeTextButton(onClick=onTree,enabled=!vm.busy){Text("Choose local folder")}
                    if (draft.tree != null) RmeTextButton(onClick={vm.change(draft.copy(tree=null))},enabled=!vm.busy){Text("Ask where to save")}
                    Text("Existing files are protected. The Android picker can include cloud folders.")
                } else Text(workflowDestinationLabel(draft.tree))
                if (!saved) {
                    RmePrimaryButton(onClick=vm::saveRecipe,enabled=!vm.busy && draft.preset.available){Text("Save preset")}
                } else if (draft.preset.available && (canCapture || draft.preset==WorkflowPreset.OFFICE)) {
                    if(draft.preset==WorkflowPreset.ENCRYPTED) RmeTextField(password,{password=it},label={Text("PDF password")},supportingText={Text("Passwords are never stored.")},singleLine=true,keyboardOptions=KeyboardOptions(autoCorrectEnabled=false,keyboardType=KeyboardType.Password,imeAction=ImeAction.Done),visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth().testTag("workflow-password"),enabled=!vm.busy)
                    RmePrimaryButton(onClick={val secret=password.toCharArray();password="";onCapture(secret)},enabled=!vm.busy,modifier=Modifier.testTag("workflow-capture")){
                        Text(when (draft.preset) {
                            WorkflowPreset.OFFICE -> "Choose Office file"
                            WorkflowPreset.XLSX -> "Review workbook"
                            else -> "Review document copy"
                        })
                    }
                } else if (draft.preset.available) {
                    Text("Open a document to use this preset.")
                }
            return@Column
        }
        Text("Reuse a file format, filename and save location.")
        RmePrimaryButton(onClick=vm::create,enabled=!vm.busy){Text("New preset")}
        Text("Saved presets", style = MaterialTheme.typography.titleMedium)
        if (vm.data.recipes.isEmpty()) Text("No presets yet")
        HorizontalDivider()
        vm.data.recipes.forEach { recipe ->
            Text(recipe.label, style=MaterialTheme.typography.titleMedium)
            Text(if (recipe.preset.available) recipe.preset.label else "Format unavailable")
            RmeActionRow { RmeTextButton(onClick={vm.select(recipe)},enabled=!vm.busy){Text("Open preset")};RmeTextButton(onClick={vm.enable(recipe.id,!recipe.enabled)},enabled=recipe.enabled || recipe.preset.available && vm.data.jobs.any { it.recipeId==recipe.id && it.status.pending() }){Text(if(recipe.enabled)"Pause" else "Enable queued exports")} }
            RmeActionRow { RmeTextButton(onClick={vm.revoke(recipe.id)}){Text("Revoke")};RmeTextButton(onClick={vm.delete(recipe.id)},contentColor=MaterialTheme.colorScheme.error){Text("Delete preset")} }
        }
        HorizontalDivider()
        Text("Export queue", style = MaterialTheme.typography.titleMedium)
        if (vm.data.jobs.isEmpty()) Text("No exports waiting")
        else Text("Exports run while RME is open and unlocked.")
        vm.data.jobs.forEach { job ->
            Text("${job.name}\n${job.preset.label} · ${if(job.preset==WorkflowPreset.OFFICE)"source byte snapshot"else "revision ${job.revision}"} · ${job.status}",Modifier.testTag("workflow-job-${job.id}"))
            Text(if (job.tree == null) "Android Save may list cloud folders." else workflowDestinationLabel(job.tree))
            if(job.status==WorkflowStatus.UNCERTAIN) {
                Text("An external file may exist, including a partial file. No blind retry or automatic deletion. Reconcile exact bytes, or keep the external file and cancel this job. Review a new name for another copy.")
                RmeTextButton(onClick={vm.reconcile(job.id)}){Text("Reconcile output")}
                RmeTextButton(onClick={vm.keep(job.id)}){Text("Keep external file and cancel")}
            } else if(job.status.pending()) {
                val preset = vm.data.recipes.firstOrNull { it.id == job.recipeId }
                when {
                    !job.preset.available || preset?.preset?.available != true -> Text("Format unavailable")
                    job.status == WorkflowStatus.PICKER || job.status == WorkflowStatus.WRITING -> Text("Export in progress")
                    !preset.enabled -> RmeTextButton(onClick = { vm.enable(preset.id, true) }) { Text("Enable preset") }
                    else -> RmeTextButton(onClick = {
                        if (job.tree == null) onManual(job.id) else vm.run(job.id)
                    }) { Text(if (job.tree == null) "Choose where to save" else "Save to folder") }
                }
                RmeTextButton(onClick={vm.cancel(job.id)}){Text("Cancel export")}
            } else RmeTextButton(onClick={vm.remove(job.id)}){Text("Remove job record")}
        }
        if (vm.data.jobs.isNotEmpty()) Text("Queue items stay on this device and are excluded from backups.")
    }
}

private fun workflowDestinationLabel(tree:String?):String = if(tree==null)"Ask where to save" else runCatching {
    "Internal storage / "+android.provider.DocumentsContract.getTreeDocumentId(android.net.Uri.parse(tree)).removePrefix("primary:")
}.getOrDefault("Approved folder unavailable")

internal const val ARCHIVAL_NOTICE="PDF/A-1b image-only separate copy. Renders the current appearance to opaque sRGB on white. Forms, annotations and digital signatures become pixels; cryptographic signature validity, transparency and extractable text are not retained. Page size follows current settings. JPEG encoding can change fine detail. Searchable archival is unavailable. Review every page. Development fixtures passed independent PDF/A-1b checks; the app does not validate each copy. Keep originals. Use Share-Safe for secure removal."
