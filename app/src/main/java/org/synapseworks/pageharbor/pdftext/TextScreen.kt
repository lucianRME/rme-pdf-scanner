package org.synapseworks.pageharbor.pdftext

import org.synapseworks.pageharbor.ui.theme.RmePrimaryButton
import org.synapseworks.pageharbor.ui.theme.RmeSecondaryButton
import org.synapseworks.pageharbor.ui.theme.RmeTextButton
import org.synapseworks.pageharbor.ui.theme.RmeTextField

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import org.synapseworks.pageharbor.forms.*
import org.synapseworks.pageharbor.ui.theme.RmeTonalChoice

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun TextScreen(vm:TextViewModel,choose:(String,Long)->Unit,save:(String,Long,String)->Unit){
    val s=vm.state?:return;val visual=vm.mode==PdfEditMode.VISUAL;val reviewedVisual=vm.visualReview;val run=vm.document?.runs?.singleOrNull{it.id==vm.selected}
    BackHandler{if(s.phase in setOf(FormPhase.REVIEW,FormPhase.READY))vm.correct()else vm.close()}
    Surface(Modifier.fillMaxSize().testTag("text-screen")){
        LazyColumn(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(12.dp).testTag("text-list"),verticalArrangement=Arrangement.spacedBy(12.dp)){
            item{Text("Edit PDF text",style=MaterialTheme.typography.headlineSmall,modifier=Modifier.semantics{heading()})}
            if(s.phase in setOf(FormPhase.OPEN,FormPhase.EDIT,FormPhase.REVIEW,FormPhase.READY))item("mode"){
                FlowRow(
                    modifier = Modifier.selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RmeTonalChoice(
                        selected = !visual,
                        onClick = { if (visual) vm.chooseMode(PdfEditMode.NATIVE) },
                        modifier = Modifier.testTag("text-native-mode"),
                    ) { Text("Native text edit") }
                    RmeTonalChoice(
                        selected = visual,
                        onClick = { if (!visual) vm.chooseMode(PdfEditMode.VISUAL) },
                        modifier = Modifier.testTag("text-visual-mode"),
                    ) { Text("Visual replacement") }
                }
            }
            if(visual)item("visual-policy"){Text("Visual replacement covers a selected region with opaque white and adds black 12pt Helvetica text. Covered source content can remain in the PDF and remain extractable. This is not secure redaction. Use Share-Safe for secure removal. Plain white regions only; supported Western Latin text must fit without wrapping.",Modifier.testTag("visual-warning"))}
            if(!visual)item{Text("Native text-object replacement in compatible PDFs. Select a complete occurrence. Standard Helvetica and supported Western Latin only; replacement must fit the original width. Scans, OCR layers, forms and complex text are declined. Temporary drafts; original unchanged.")}
            if(s.phase==FormPhase.EDIT){
                if(visual){
                    if(vm.visualDocument!=null)visualRegionItems(vm)
                    if(vm.visualRegion!=null){
                        item("visual-text"){RmeTextField(s.values["visual"]!!.single(),vm::edit,label={Text("Visual replacement text")},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("text-edit"))}
                        item("visual-review"){RmePrimaryButton(vm::review,enabled=vm.preview!=null&&s.values["visual"]!!.single().isNotBlank(),modifier=Modifier.fillMaxWidth().testTag("text-review")){Text("Review visual replacement")}}
                    }
                    item("visual-cancel"){RmeTextButton({vm.chooseMode(PdfEditMode.NATIVE)},Modifier.testTag("visual-cancel")){Text("Cancel visual replacement")}}
                }
                vm.document?.runs?.forEachIndexed{index,r->item("run-${r.id}"){RmeSecondaryButton(onClick={vm.select(r.id)},modifier=Modifier.fillMaxWidth().testTag("text-run-$index")){Column{Text("Page ${r.page} · occurrence ${index+1} · x ${r.x.toInt()}, y ${r.y.toInt()}"+(if(r.id==vm.selected)" · Selected"else""));Text(r.text)}}}}
                if(run!=null){
                    item{Text("Selected source occurrence: ${run.text}",Modifier.testTag("text-selected"))}
                    item{RmeTextField(s.values[run.id]!!.single(),vm::edit,label={Text("Replacement")},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("text-edit"))}
                    item{RmePrimaryButton(vm::review,enabled=s.values[run.id]!!.single().isNotEmpty(),modifier=Modifier.fillMaxWidth().testTag("text-review")){Text("Review replacement")}}
                }
            }
            if(!visual||s.phase!=FormPhase.EDIT)vm.preview?.let{bitmap->item("preview"){Image(bitmap.asImageBitmap(),if(visual)"Reviewed visual replacement output page"else if(s.phase==FormPhase.READY)"Reviewed output page, selected occurrence outlined in blue"else"Source page, selected occurrence outlined in blue",modifier=Modifier.fillMaxWidth().heightIn(max=480.dp).testTag("text-preview"))}}
            if(s.phase in setOf(FormPhase.REVIEW,FormPhase.READY)){
                if(visual&&reviewedVisual!=null)item{Text("Visual replacement · page ${reviewedVisual.page+1}\nAdded text: ${reviewedVisual.text}\nOpaque white cover; source content is retained.",Modifier.testTag("text-reviewed"))}
                else item{Text("Text-object replacement · page ${run?.page}\nOriginal: ${run?.text}\nReplacement: ${s.review?.values?.get(run?.id)?.singleOrNull().orEmpty()}",Modifier.testTag("text-reviewed"))}
                if(!visual)item{Text("This changes visible and extractable text in the selected occurrence. Other occurrences are retained. For sensitive information use Share-Safe redaction. The blue outline is only a review marker.")}
                if(s.phase==FormPhase.READY){
                    item{Row{Checkbox(s.confirmed,{vm.confirm(s.owner,s.epoch,s.review!!.id,it)},Modifier.testTag("text-confirm"));Text(if(visual)"I checked the visual replacement and understand covered source content can remain in the PDF."else"I checked the replacement and surrounding page.",Modifier.weight(1f))}}
                    item{RmePrimaryButton({save(s.owner,s.epoch,s.review!!.id)},enabled=s.confirmed&&vm.preview!=null,modifier=Modifier.fillMaxWidth().testTag("text-save")){Text(if(visual)"Save visual replacement copy"else"Save edited PDF copy")}}
                    item{Text("Choose a new empty file in system local Documents or Downloads. Other destinations are declined to protect the original.")}
                }
                item{RmeSecondaryButton(vm::correct,modifier=Modifier.testTag("text-correct")){Text("Return to edit")}}
            }
            if(s.phase in setOf(FormPhase.PICKING,FormPhase.READING,FormPhase.PREPARING,FormPhase.DESTINATION,FormPhase.WAITING,FormPhase.SAVING)){
                item{Text(when(s.phase){FormPhase.PREPARING->"Preparing reviewed PDF and page preview…";FormPhase.READING->if(visual)"Checking passive visual replacement compatibility…"else"Checking native text compatibility…";else->"Waiting for PDF operation…"},Modifier.semantics{liveRegion=LiveRegionMode.Polite})}
                item{RmeSecondaryButton(vm::cancelWork,modifier=Modifier.testTag("text-cancel")){Text("Cancel operation")}}
            }
            s.message?.let{message->item{Text(FormFailure.entries.firstOrNull{it.message==message}?.let{if(visual)visualFailure(it)else textFailure(it)}?:message,Modifier.testTag("text-message").semantics{liveRegion=LiveRegionMode.Polite})}}
            if(s.phase in setOf(FormPhase.OPEN,FormPhase.EDIT,FormPhase.REVIEW,FormPhase.READY))item{RmeSecondaryButton({choose(s.owner,s.epoch)},modifier=Modifier.testTag("text-input")){Text("Choose source PDF")}}
            item{RmeTextButton(vm::close,Modifier.testTag("text-close")){Text("Back to library")}}
        }
    }
}
