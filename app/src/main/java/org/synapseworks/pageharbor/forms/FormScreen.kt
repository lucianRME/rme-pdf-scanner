package org.synapseworks.pageharbor.forms

import org.synapseworks.pageharbor.ui.theme.RmePrimaryButton
import org.synapseworks.pageharbor.ui.theme.RmeSecondaryButton
import org.synapseworks.pageharbor.ui.theme.RmeTextButton
import org.synapseworks.pageharbor.ui.theme.RmeTextField

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

@Composable internal fun FormScreen(vm:FormViewModel,choose:(String,Long)->Unit,save:(String,Long,String)->Unit){
    val s=vm.state?:return
    var options by remember(s.owner,s.epoch){mutableStateOf<String?>(null)}
    BackHandler{if(s.phase in setOf(FormPhase.REVIEW,FormPhase.READY))vm.correct()else if(s.phase in setOf(FormPhase.PREPARING,FormPhase.DESTINATION,FormPhase.WAITING,FormPhase.SAVING))vm.cancelWork()else vm.close()}
    Surface(Modifier.fillMaxSize().testTag("form-screen")){
        LazyColumn(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(12.dp).testTag("form-list"),verticalArrangement=Arrangement.spacedBy(12.dp)){
            item("title"){Text("Fill PDF form",Modifier.semantics{heading()},style=MaterialTheme.typography.headlineSmall)}
            item("scope"){Text("Open a compatible existing AcroForm. Text, checks, radio and listed choices stay editable in the saved copy. Encrypted, signed, XFA and active PDFs are declined. Drafts stay temporary.")}
            val model=s.model
            if(model!=null&&s.phase in setOf(FormPhase.EDIT,FormPhase.REVIEW,FormPhase.READY)){
                val editing=s.phase==FormPhase.EDIT
                item("summary"){Text("${model.fields.size} fields on ${model.pages} pages. Repeated widgets share one value. Check every field and page before preparing the separate copy.")}
                model.fields.forEach{f->item("field-${f.id}"){
                    val value=if(editing)s.values[f.id].orEmpty()else s.review!!.values[f.id].orEmpty()
                    Column(verticalArrangement=Arrangement.spacedBy(4.dp),modifier=Modifier.fillMaxWidth().testTag("form-field-${f.id}")){
                        Text(f.label,style=MaterialTheme.typography.titleMedium)
                        val type=when(f.kind){FormKind.TEXT->if(f.multiline)"Multiline text"else"Text";FormKind.CHECK->"Checkbox";FormKind.RADIO->"Radio choice";FormKind.CHOICE->"Single choice";FormKind.MULTI->"Multiple choices"}
                        Text("Field ${f.id} · $type · pages ${f.pages.joinToString(", ")}"+(if(f.readOnly)" · Read-only"else"")+(if(f.required)" · Required by form"else""))
                        if(editing&&!f.readOnly){
                            when(f.kind){
                                FormKind.TEXT->RmeTextField(value=value.singleOrNull().orEmpty(),onValueChange={vm.edit(s.owner,s.epoch,f.id,listOf(it))},label={Text(f.label)},singleLine=!f.multiline,maxLines=if(f.multiline)6 else 1,modifier=Modifier.fillMaxWidth().testTag("form-text-${f.id}"))
                                FormKind.CHECK->Row{Checkbox(checked=value.singleOrNull()!="Off",onCheckedChange={vm.edit(s.owner,s.epoch,f.id,listOf(if(it)f.options.single().value else"Off"))},modifier=Modifier.testTag("form-check-${f.id}"));Text(if(value.singleOrNull()=="Off")"Not checked"else"Checked")}
                                else->{Text(value.joinToString(", "){v->f.options.firstOrNull{it.value==v}?.label?:if(v=="Off")"No selection"else v}.ifEmpty{"No selection"},Modifier.testTag("form-value-${f.id}"));RmeSecondaryButton(onClick={options=f.id},modifier=Modifier.testTag("form-options-${f.id}")){Text("Choose options")}}
                            }
                        }else Text(when(f.kind){FormKind.TEXT->value.singleOrNull().orEmpty().ifEmpty{"(blank)"};FormKind.CHECK->if(value.singleOrNull()=="Off")"Not checked"else"Checked (${value.singleOrNull()})";else->value.joinToString(", "){v->f.options.firstOrNull{it.value==v}?.label?:if(v=="Off")"No selection"else v}.ifEmpty{"No selection"}},Modifier.testTag("form-reviewed-${f.id}"))
                    }
                }}
                if(editing)item("review"){RmePrimaryButton(onClick={vm.review(s.owner,s.epoch)},modifier=Modifier.fillMaxWidth().testTag("form-review")){Text("Review all values")}}
                else {
                    item("policy"){Text("The copy keeps the field tree and widgets editable. Field appearances use 10pt Helvetica in black on white. Unsupported characters or text that does not fit must be corrected. Saving partial forms is allowed; scripts and custom validation are not run.")}
                    item("confirm"){Row{Checkbox(s.confirmed,{vm.confirm(s.owner,s.epoch,s.review!!.id,it)},Modifier.testTag("form-confirm"));Text("I checked the values, choices and all listed pages.",Modifier.weight(1f))}}
                    if(s.phase==FormPhase.REVIEW)item("prepare"){RmePrimaryButton(onClick={vm.prepare(s.owner,s.epoch,s.review!!.id)},enabled=s.confirmed,modifier=Modifier.fillMaxWidth().testTag("form-prepare")){Text("Prepare filled PDF")}}
                    if(s.phase==FormPhase.READY){
                        item("ready"){Text("Editable copy prepared. Choose a new empty file in the system local Documents or Downloads provider. Other destinations are declined to protect the original.",Modifier.testTag("form-ready"))}
                        item("save"){RmePrimaryButton(onClick={save(s.owner,s.epoch,s.review!!.id)},enabled=s.confirmed,modifier=Modifier.fillMaxWidth().testTag("form-save")){Text("Save PDF copy")}}
                    }
                    item("correct"){RmeSecondaryButton(onClick=vm::correct,modifier=Modifier.fillMaxWidth().testTag("form-correct")){Text("Correct values")}}
                }
            }
            if(s.phase in setOf(FormPhase.PICKING,FormPhase.READING,FormPhase.PREPARING,FormPhase.DESTINATION,FormPhase.WAITING,FormPhase.SAVING)){
                item("busy"){Text(when(s.phase){FormPhase.PICKING->"Waiting for PDF selection…";FormPhase.READING->"Checking compatible fields…";FormPhase.PREPARING->"Preparing and verifying appearances…";FormPhase.SAVING->"Saving the reviewed copy…";else->"Waiting for Save destination…"},Modifier.testTag("form-busy").semantics{liveRegion=LiveRegionMode.Polite})}
                if(s.phase in setOf(FormPhase.PREPARING,FormPhase.DESTINATION,FormPhase.WAITING,FormPhase.SAVING))item("cancel"){RmeSecondaryButton(onClick=vm::cancelWork,modifier=Modifier.fillMaxWidth().testTag("form-cancel")){Text("Cancel operation")}}
            }
            s.message?.let{message->item("message"){Text(message,Modifier.testTag("form-message").semantics{liveRegion=LiveRegionMode.Polite},color=if(message=="PDF copy saved.")MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)}}
            if(s.phase in setOf(FormPhase.OPEN,FormPhase.EDIT,FormPhase.REVIEW,FormPhase.READY))item("input"){RmeSecondaryButton(onClick={choose(s.owner,s.epoch)},modifier=Modifier.fillMaxWidth().testTag("form-input")){Text("Choose PDF form")}}
            item("close"){RmeTextButton(onClick=vm::close,modifier=Modifier.testTag("form-close")){Text("Back to library")}}
        }
    }
    val field=s.model?.fields?.singleOrNull{it.id==options}
    if(field!=null&&s.phase==FormPhase.EDIT&&!field.readOnly){
        val selected=s.values[field.id].orEmpty()
        AlertDialog(onDismissRequest={options=null},title={Text(field.label)},text={Column(Modifier.heightIn(max=300.dp).verticalScroll(rememberScrollState())){
            if(field.kind!=FormKind.MULTI&&(!field.noToggleOff||selected==listOf("Off"))){RmeTextButton(onClick={vm.edit(s.owner,s.epoch,field.id,if(field.kind==FormKind.RADIO)listOf("Off")else emptyList())},modifier=Modifier.testTag("form-none")){Text("No selection")}}
            field.options.forEachIndexed{index,option->Row{
                if(field.kind==FormKind.MULTI)Checkbox(option.value in selected,{checked->val next=if(checked)(selected+option.value).distinct()else selected-option.value;vm.edit(s.owner,s.epoch,field.id,field.options.map{it.value}.filter{it in next})},Modifier.testTag("form-option-$index"))
                else RadioButton(option.value in selected,{vm.edit(s.owner,s.epoch,field.id,listOf(option.value))},Modifier.testTag("form-option-$index"))
                Text(option.label+(if(option.label!=option.value)" (${option.value})"else""),Modifier.weight(1f))
            }}
        }},confirmButton={RmeTextButton(onClick={options=null},modifier=Modifier.testTag("form-options-done")){Text("Done")}})
    }
}
