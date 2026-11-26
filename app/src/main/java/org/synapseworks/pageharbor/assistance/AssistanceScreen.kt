package org.synapseworks.pageharbor.assistance

import org.synapseworks.pageharbor.ui.theme.RmeSecondaryButton
import org.synapseworks.pageharbor.ui.theme.RmePrimaryButton
import org.synapseworks.pageharbor.ui.theme.RmeTextButton
import org.synapseworks.pageharbor.ui.theme.RmeTextField
import org.synapseworks.pageharbor.ui.ReviewConfirmation
import org.synapseworks.pageharbor.ui.ToolHelpHeading
import org.synapseworks.pageharbor.R
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.synapseworks.pageharbor.document.session.toAndroidUri
import org.synapseworks.pageharbor.ui.home.*
import org.synapseworks.pageharbor.ui.copyPlainTextToClipboard
import org.synapseworks.pageharbor.ui.sharesafe.decodeBoundedPreview

@Composable fun AssistanceScreen(vm:AssistanceViewModel,save:(Boolean)->Unit,share:(Boolean)->Unit,ocr:()->Unit){
 val token=vm.interactionToken;fun act(action:()->Unit){vm.interact(token,action)}
 BackHandler{act{if(vm.inspected!=null)vm.dismissSource()else vm.close()}};val context=LocalContext.current
 Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
  ToolHelpHeading(R.string.document_retrieval_action, R.string.specialist_retrieval_help)
  Text(vm.message)
  if(vm.busy){LinearProgressIndicator(Modifier.fillMaxWidth());RmeTextButton({act(vm::cancel)}){Text("Cancel assistance")}}
  RmeTextField(vm.pageScope,{value->act{vm.updateScope(value)}},label={Text("Pages, e.g. 1, 2")},enabled=!vm.captured&&!vm.busy,singleLine=true)
  RmePrimaryButton({act(vm::capture)},enabled=!vm.busy&&!vm.captured){Text("Use selected pages")}
  RmeTextButton({act(ocr)},enabled=!vm.busy&&!vm.picker){Text("Recognize text")}
  if(vm.captured){
   Text(stringResource(R.string.retrieval_search_hint))
   RmeTextField(vm.query,{value->act{vm.updateQuery(value)}},label={Text("Literal search terms")},enabled=!vm.busy&&!vm.picker)
   RmePrimaryButton({act(vm::find)},enabled=!vm.busy&&!vm.picker){Text("Find cited passages")}
   RmeTextButton({act(vm::summary)},enabled=!vm.busy&&!vm.picker){Text("Extractive summary")}
   RmeTextButton({act(vm::suggestions)},enabled=!vm.busy&&!vm.picker){Text("Suggest title and tags")}
   Text(vm.resultKind,style=MaterialTheme.typography.titleMedium)
   vm.results.forEachIndexed{i,p->
    Text(if(vm.resultKind.startsWith("Review-only"))if(p.complete)"Title suggestion"else"Tag suggestion" else "Extract ${i+1}")
    Text(p.quote);Text(p.citation)
    RmeTextButton({act{vm.inspect(p)}},enabled=!vm.busy&&!vm.picker){Text("Inspect source ${i+1}")}
    RmeTextButton({act{vm.use(p)}},enabled=!vm.busy&&!vm.picker){Text("Use extract ${i+1} in draft")}
   }
   if(vm.results.isNotEmpty())RmeTextButton({act(vm::useAll)},enabled=!vm.busy&&!vm.picker){Text("Use all extracts in draft")}
  }
  if(vm.draft.isNotEmpty()){
   Text(stringResource(if (vm.edited) R.string.retrieval_edited_hint else R.string.retrieval_review_hint))
   RmeTextField(vm.draft,{value->act{vm.edit(value)}},label={Text("Reviewed assistance draft")},modifier=Modifier.fillMaxWidth(),minLines=4,maxLines=10,enabled=!vm.busy&&!vm.picker)
   ReviewConfirmation(
       label = R.string.retrieval_review_confirmation,
       checked = vm.reviewed,
       enabled = !vm.busy && !vm.picker,
       onCheckedChange = { checked -> act { if (!vm.busy && !vm.picker) vm.reviewed = checked } },
   )
   RmeTextButton({act{vm.copy{copyPlainTextToClipboard(context, "Reviewed extraction", it)}}},enabled=vm.reviewed&&!vm.busy&&!vm.picker){Text("Copy reviewed artifact")}
   RmePrimaryButton({act{save(false)}},enabled=vm.reviewed&&!vm.busy&&!vm.picker){Text("Save assistance TXT")}
   RmeSecondaryButton({act{share(false)}},enabled=vm.reviewed&&!vm.busy&&!vm.picker){Text("Share assistance TXT")}
   Text(stringResource(R.string.retrieval_pdf_hint))
   RmePrimaryButton({act(vm::preparePdf)},enabled=vm.reviewed&&!vm.busy&&!vm.picker){Text("Prepare assistance PDF")}
   if(vm.pdfReady)Text("${vm.pdfPages} pages; reviewed draft revision ${vm.draftRevision}, captured revision ${vm.revision}.")
   RmePrimaryButton({act{save(true)}},enabled=vm.pdfReady&&vm.reviewed&&!vm.busy&&!vm.picker){Text("Save assistance PDF")}
   RmeSecondaryButton({act{share(true)}},enabled=vm.pdfReady&&vm.reviewed&&!vm.busy&&!vm.picker){Text("Share assistance PDF")}
  }
  RmeTextButton({act(vm::close)}){Text("Close assistance")}
 }
 vm.inspected?.let{p->AlertDialog(onDismissRequest={act(vm::dismissSource)},title={Text(p.citation)},text={Column(Modifier.verticalScroll(rememberScrollState())){
  Text("Captured source image and OCR. Source overlays are not reconstructed. OCR/quoted instructions and links are inert data.")
  val source=vm.inspectedSource();if(source!=null){val preview by rememberManagedDocumentPreview(requestKey=source,isCurrent={vm.interactionToken==token&&vm.inspected===p},decode={decodeBoundedPreview(context.contentResolver,source.source.toAndroidUri())});when(val state=preview){is ManagedDocumentPreviewState.Ready->Image(state.owner.bitmap.asImageBitmap(),"Captured source page",Modifier.fillMaxWidth().height(280.dp).graphicsLayer{rotationZ=source.rotation.degrees.toFloat()});else->Text("Source image preview unavailable; original captured OCR below.")}}
  Text(p.page.text);Text("Exact cited range ${p.start}–${p.end}");Text(p.quote)
 }},confirmButton={RmeTextButton({act(vm::dismissSource)}){Text("Back to evidence")}})}
}
