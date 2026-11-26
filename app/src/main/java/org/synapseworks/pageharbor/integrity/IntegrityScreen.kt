package org.synapseworks.pageharbor.integrity

import org.synapseworks.pageharbor.ui.theme.RmeSecondaryButton
import org.synapseworks.pageharbor.ui.theme.RmeActionRow
import org.synapseworks.pageharbor.ui.theme.RmePrimaryButton
import org.synapseworks.pageharbor.ui.theme.RmeTextButton
import android.graphics.BitmapFactory
import org.synapseworks.pageharbor.ui.ReviewConfirmation
import org.synapseworks.pageharbor.ui.ToolHelpHeading
import org.synapseworks.pageharbor.R
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import org.synapseworks.pageharbor.ui.home.*

@Composable fun IntegrityScreen(vm:IntegrityViewModel,pick:(Boolean)->Unit,save:()->Unit,share:()->Unit){
 val token=vm.interactionToken;val renderRevision=vm.draftRevision;fun act(f:()->Unit){vm.interact(token,f)};BackHandler{act(vm::close)}
 Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
  ToolHelpHeading(R.string.document_integrity_action, R.string.specialist_integrity_help)
  Text(vm.message)
  if(vm.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
  RmePrimaryButton({act{pick(true)}},enabled=!vm.busy&&!vm.picker){Text("Select baseline PDF")}
  vm.baseline?.let{Text("Baseline captured: ${it.bytes} bytes\nSHA-256: ${it.sha256}")}
  RmePrimaryButton({act{pick(false)}},enabled=!vm.busy&&!vm.picker){Text("Select comparison PDF")}
  vm.comparison?.let{Text("Comparison captured: ${it.bytes} bytes\nSHA-256: ${it.sha256}")}
  RmePrimaryButton({act(vm::inspect)},enabled=vm.baseline!=null&&vm.comparison!=null&&!vm.busy&&!vm.picker){Text("Inspect captured PDFs")}
  if(vm.report.isNotEmpty()){
   var showDetails by remember(renderRevision) { mutableStateOf(false) }
   Text(
       stringResource(when (vm.byteEqual) {
           true -> R.string.integrity_equal_bytes
           false -> R.string.integrity_different_bytes
           null -> R.string.integrity_unchecked_bytes
       }),
       style = MaterialTheme.typography.titleMedium,
   )
   Text(stringResource(R.string.integrity_comparison_hint))
   vm.differences.getOrNull(vm.page)?.let{d->
    Text(stringResource(R.string.integrity_page_number, d.page), style = MaterialTheme.typography.titleMedium)
    if (d.dimensions == "Comparison only" || d.dimensions == "Baseline only") {
        Text(stringResource(if (d.dimensions == "Comparison only") R.string.integrity_added_page else R.string.integrity_missing_page))
    } else {
        Text(stringResource(when (d.dimensions) {
            "SAME_PAGE_BOX_ROTATION" -> R.string.integrity_same_geometry
            "DIFFERENT_PAGE_BOX_OR_ROTATION" -> R.string.integrity_changed_geometry
            else -> R.string.integrity_unchecked_geometry
        }))
        Text(stringResource(when (d.visual) {
            "RENDERED_PIXELS_EQUAL" -> R.string.integrity_same_pixels
            "RENDERED_PIXELS_DIFFERENT" -> R.string.integrity_changed_pixels
            else -> R.string.integrity_incomparable_pixels
        }))
        d.changed?.let { Text(stringResource(R.string.integrity_pixel_count, it)) }
        Text(stringResource(when (d.text) {
            "EXTRACTED_TEXT_EQUAL" -> R.string.integrity_same_text
            "EXTRACTED_TEXT_DIFFERENT" -> R.string.integrity_changed_text
            else -> R.string.integrity_unchecked_text
        }))
    }
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
     for((label,doc)in listOf("Baseline" to vm.left,"Comparison" to vm.right))Column(Modifier.weight(1f)){
      Text(label);val p=doc?.pages?.getOrNull(vm.page);Text(if(p==null)"Page absent"else"${p.width}×${p.height}pt; rotation ${p.rotation}°; raster ${p.rasterWidth}×${p.rasterHeight}")
      if(p?.png!=null){val preview by rememberManagedDocumentPreview(requestKey=p.png to renderRevision,isCurrent={vm.interactionToken==token&&vm.draftRevision==renderRevision},decode={BitmapFactory.decodeFile(p.png.path)});when(val ready=preview){is ManagedDocumentPreviewState.Ready->Image(ready.owner.bitmap.asImageBitmap(),"$label captured page ${d.page}",Modifier.fillMaxWidth().height(240.dp));else->Text("Preview unavailable")}}
      Text(stringResource(R.string.integrity_extracted))
      SelectionContainer { Text(p?.text?.takeIf { it.isNotBlank() } ?: stringResource(R.string.integrity_no_extraction)) }
     }
    }
    RmeActionRow {RmeTextButton({act{vm.move(-1)}},enabled=vm.page>0&&!vm.busy){Text("Previous page")};RmeTextButton({act{vm.move(1)}},enabled=vm.page+1<vm.differences.size&&!vm.busy){Text("Next page")}}
   }
   Text("Signature inspection",style=MaterialTheme.typography.titleMedium)
   for((label,d)in listOf("Baseline" to vm.left,"Comparison" to vm.right)){
    Text(label)
    Text(when {
        d == null -> stringResource(R.string.integrity_signatures_unchecked)
        d.signatures.isEmpty() -> stringResource(R.string.integrity_no_signatures)
        else -> stringResource(R.string.integrity_signatures_found, d.signatures.size)
    })
   }
   Text(stringResource(R.string.integrity_credentials))
   Text(stringResource(R.string.integrity_trust))
   RmeTextButton(onClick = { showDetails = !showDetails }) {
       Text(stringResource(if (showDetails) R.string.integrity_hide_report else R.string.integrity_show_report))
   }
   if (showDetails) SelectionContainer { Text(vm.report) }
   ReviewConfirmation(
       label = R.string.integrity_review_confirmation,
       checked = vm.reviewed,
       enabled = !vm.busy && !vm.picker,
       onCheckedChange = { checked -> act { if (!vm.busy && !vm.picker) vm.reviewed = checked } },
   )
   RmePrimaryButton({act(save)},enabled=vm.reviewed&&!vm.busy&&!vm.picker){Text("Save integrity TXT")}
   RmeSecondaryButton({act(share)},enabled=vm.reviewed&&!vm.busy&&!vm.picker){Text("Share integrity TXT")}
  }
  RmeTextButton({act(vm::close)}){Text("Close integrity")}
 }
}
