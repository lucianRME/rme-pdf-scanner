package org.synapseworks.pageharbor.translation

import org.synapseworks.pageharbor.ui.theme.RmePrimaryButton
import org.synapseworks.pageharbor.ui.theme.RmeSecondaryButton
import org.synapseworks.pageharbor.ui.theme.RmeTextButton
import org.synapseworks.pageharbor.ui.theme.RmeTextField
import androidx.compose.runtime.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.synapseworks.pageharbor.resourcepack.ModelRuntimeAvailability
import org.synapseworks.pageharbor.resourcepack.ResourcePackActivity

@Composable fun TranslationScreen(vm:TranslationViewModel,save:()->Unit,share:()->Unit,pdfSave:()->Unit,pdfShare:()->Unit,ocr:()->Unit){
 BackHandler{vm.close()}
 Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
  Text("Translate text", style = MaterialTheme.typography.headlineSmall)
  val runtime = remember(vm) { vm.runtimeAvailability() }
  if (runtime != ModelRuntimeAvailability.AVAILABLE) {
    Text(when (runtime) {
      ModelRuntimeAvailability.UNSUPPORTED_DEVICE -> "Requires Android 15 or later on a 64-bit ARM device."
      else -> "Translation engine unavailable in this build."
    })
    RmeTextButton(vm::close) { Text("Close") }
    return@Column
  }
  var showDirections by remember { mutableStateOf(false) }
  Box {
    RmeSecondaryButton(onClick = { showDirections = true }, enabled = !vm.busy && !vm.picker && vm.automatic.isEmpty()) {
      Text(vm.direction.label, Modifier.weight(1f, fill = false))
      Spacer(Modifier.width(6.dp))
      Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
    }
    DropdownMenu(expanded = showDirections, onDismissRequest = { showDirections = false }) {
      TranslationDirection.supported.forEach { direction ->
        DropdownMenuItem(
          text = { Text(direction.label) },
          onClick = { vm.chooseDirection(direction); showDirections = false },
        )
      }
    }
  }
  if (vm.automatic.isEmpty()) {
    when {
      vm.direction.usesIrishPack -> Text("Uses the Irish pack from Resource packs.")
      vm.direction.usesMultilingualPack -> Text("Uses the multilingual pack from Resource packs.")
    }
    val context = LocalContext.current
    RmeTextButton(onClick = { context.startActivity(android.content.Intent(context, ResourcePackActivity::class.java)) }, enabled = !vm.busy && !vm.picker) {
      Text("Install or manage languages")
    }
  }
  Text("Translate up to 3 pages into an editable draft.")
  Text("Covered text can appear in OCR. Use Share-Safe for secure removal.")
  var showLimits by remember { mutableStateOf(false) }
  RmeTextButton(onClick = { showLimits = !showLimits }) { Text(if (showLimits) "Hide details" else "Translation limits") }
  if (showLimits) {
    Text("Review names, numbers and meaning before export. TXT and searchable PDF use a new layout.")
    Text("Limits: 8,192 UTF-8 source bytes and 32 segments. Raw output is preserved.")
  }
  Text(vm.message)
  if(vm.busy){LinearProgressIndicator(Modifier.fillMaxWidth());Text("Progress: ${vm.progress} segments")}
  RmeTextField(vm.pageScope,{if(!vm.busy&&!vm.picker&&it.length<=12)vm.pageScope=it},label={Text("Page numbers, e.g.1,2")},singleLine=true,enabled=!vm.busy&&!vm.picker)
  RmeTextButton(ocr,enabled=!vm.busy&&!vm.picker){Text("Recognize source text")}
  RmePrimaryButton({vm.translate()},enabled=!vm.busy&&!vm.picker){Text("Translate pages")}
  if(vm.busy)RmeTextButton({vm.cancel()}){Text("Cancel current work")}
  vm.pages.forEachIndexed{i,p->Text("Source page${i+1} · ${p.id}",style=MaterialTheme.typography.titleMedium);Text(p.text.ifBlank{"Current OCR unavailable"})}
  if(vm.automatic.isNotEmpty()){
   Text("Draft revision ${vm.reviewedDraftRevision}; Save/Share freezes these reviewed bytes.")
   var showRanges by remember { mutableStateOf(false) }
   RmeTextButton({showRanges=!showRanges}){Text("Show source/translation ranges")}
   if(showRanges)vm.trace.forEachIndexed{i,p->if(!p.separator){Text("Page ${p.pageId} · UTF-16 ${p.start}–${p.end}");Text(p.source);Text(vm.segmentOutputs.getOrNull(i).orEmpty())}}
   Text("Raw automatic translation",style=MaterialTheme.typography.titleMedium);Text(vm.automatic)
   Text("Review and edit independent draft",style=MaterialTheme.typography.titleMedium)
   if (vm.direction.target == TranslationLanguage.ROMANIAN) {
    RmeTextButton(vm::beginCorrection,enabled=!vm.busy&&!vm.picker){Text("Review Romanian letter corrections")}
    RmeTextButton(vm::undoCorrection,enabled=vm.canUndoCorrection){Text("Undo draft correction")}
   }
   vm.correctionPreview?.let{changes->
    Text("Occurrence preview: select deliberately; names/quotations may remain unchanged. Maximum256 proposals. No raw/source changes.")
    changes.forEach{c->Row{Checkbox(c.offset in vm.correctionSelected,{vm.selectCorrection(c.offset,it)});Text("${c.from} → ${c.to} at UTF-16 ${c.offset}: ${c.context}")}}
    RmeTextButton(vm::selectAllCorrections){Text("Select all proposed occurrences")}
    Text("Proposed draft");Text(vm.correctionResult)
    RmePrimaryButton(vm::applyCorrection,enabled=vm.correctionSelected.isNotEmpty()){Text("Apply reviewed corrections")}
    RmeTextButton(vm::cancelCorrection){Text("Cancel correction preview")}
    Text("Undo restores the prior draft until a subsequent edit.")
   }
   RmeTextField(vm.draft,vm::edit,label={Text("Translation draft")},modifier=Modifier.fillMaxWidth(),minLines=4,maxLines=10,enabled=!vm.busy&&!vm.picker&&vm.correctionPreview==null)
   Text("${vm.trace.count{!it.separator}} source ranges retained; page separation is two LF characters. Editing does not improve the recorded automatic-quality evidence.")
   Row{Checkbox(vm.reviewed,{if(!vm.busy&&!vm.picker)vm.reviewed=it},enabled=!vm.busy&&!vm.picker);Text("I reviewed this draft and its limitations.")}
   RmePrimaryButton(save,enabled=vm.reviewed&&!vm.busy&&!vm.picker&&vm.correctionPreview==null){Text("Save separate UTF-8 TXT")}
   RmeSecondaryButton(share,enabled=vm.reviewed&&!vm.busy&&!vm.picker&&vm.correctionPreview==null){Text("Share reviewed TXT")}
   Text("PDF profile: searchable reflowed A4 portrait, system monospace, at most12 pages/16MiB. Precomposed Latin/Romanian and selected punctuation/μ; unsupported glyphs, controls or fit are declined. Paragraphs preserved; captured input page references are global, not invented paragraph origins after edits.")
   RmePrimaryButton(vm::preparePdf,enabled=vm.reviewed&&!vm.busy&&!vm.picker&&vm.correctionPreview==null){Text("Prepare reviewed translated PDF")}
   if(vm.pdfReady){Text(vm.pdfReferences);Text("Reflowed PDF: ${vm.pdfPages} pages from draft revision ${vm.reviewedDraftRevision}. Save/Share freeze this format and revision.")}
   RmePrimaryButton(pdfSave,enabled=vm.pdfReady&&vm.reviewed&&!vm.busy&&!vm.picker&&vm.correctionPreview==null){Text("Save separate translated PDF")}
   RmeSecondaryButton(pdfShare,enabled=vm.pdfReady&&vm.reviewed&&!vm.busy&&!vm.picker&&vm.correctionPreview==null){Text("Share reviewed PDF")}
  }
  RmeTextButton({vm.close()}){Text("Close translation")}
 }
}
