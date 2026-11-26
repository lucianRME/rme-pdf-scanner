package org.synapseworks.pageharbor.ui.home

import org.synapseworks.pageharbor.ui.theme.RmePrimaryButton
import org.synapseworks.pageharbor.ui.theme.RmeSecondaryButton
import org.synapseworks.pageharbor.ui.theme.RmeTextButton

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.synapseworks.pageharbor.R
import org.synapseworks.pageharbor.document.session.DocumentPage
import org.synapseworks.pageharbor.document.session.toAndroidUri
import org.synapseworks.pageharbor.image.*

@Composable
internal fun BackgroundWhiteningDialog(page: DocumentPage, pageNumber:Int, pageCount:Int,
    initial:DocumentFilter?, onDismiss:()->Unit, onApply:(DocumentFilter)->Boolean) {
    val filters=DocumentFilter.entries.filter{it.whiteningRecipe!=null}
    val current=(initial ?: page.filter).whiteningRecipe
    var colour by rememberSaveable(page.id,page.source,page.filter,page.rotation,page.adjustments,initial){mutableStateOf(current?.paper!=WhiteningPaper.NEUTRAL)}
    var choice by rememberSaveable(page.id,page.source,page.filter,page.rotation,page.adjustments,initial){mutableIntStateOf(when(current?.amount){0.35f->1;1f->3;else->2})}
    val selectedFilter=if(choice==0)DocumentFilter.ORIGINAL else filters[(if(colour)3 else 0)+choice-1]
    var before by rememberSaveable(page.id,page.source,page.filter,page.rotation,page.adjustments,initial){mutableStateOf(false)}
    var stale by rememberSaveable(page.id,page.source,page.filter,page.rotation,page.adjustments,initial){mutableStateOf(false)}
    val selected=if(before)page.filter else selectedFilter
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        Surface(Modifier.safeDrawingPadding().imePadding().padding(12.dp).widthIn(max=640.dp).fillMaxWidth().fillMaxHeight(0.95f),shape=MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.whitening_title),style=MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.whitening_help),style=MaterialTheme.typography.bodySmall)
                Text(stringResource(if(before)R.string.shadow_before else R.string.shadow_after),Modifier.testTag("whitening-comparison").semantics{liveRegion=LiveRegionMode.Polite})
                FilteredDocumentPreview(FilteredPreviewRequest(page.id.value,page.source.reference,selected,page.rotation,page.imageMetadata,page.adjustments),
                    page.source.toAndroidUri(),pageNumber,pageCount,minHeight=100.dp,maxHeight=220.dp)
                RmeSecondaryButton(onClick={before=!before},modifier=Modifier.fillMaxWidth().testTag("whitening-compare")) {
                    Text(stringResource(if(before)R.string.shadow_show_after else R.string.shadow_show_before))
                }
                Text(stringResource(R.string.whitening_mode_help),style=MaterialTheme.typography.bodySmall)
                Column(Modifier.selectableGroup()) {
                    for(mode in listOf(false,true)) {
                        Row(Modifier.fillMaxWidth().testTag(if(mode)"whitening-colour" else "whitening-neutral")
                            .selectable(colour==mode,!stale,Role.RadioButton){colour=mode;before=false}.padding(4.dp)) {
                            RadioButton(colour==mode,onClick=null,enabled=!stale)
                            Text(stringResource(if(mode)R.string.whitening_colour else R.string.whitening_neutral),Modifier.weight(1f))
                        }
                    }
                }
                Text(stringResource(R.string.shadow_intensity),style=MaterialTheme.typography.labelLarge)
                Column(Modifier.selectableGroup()) {
                    for(i in 1..3) {
                        val label=stringResource(when(i){1->R.string.shadow_low;2->R.string.shadow_balanced;else->R.string.shadow_strong})
                        Row(Modifier.fillMaxWidth().testTag("whitening-level-$i").selectable(choice==i,!stale,Role.RadioButton){choice=i;before=false}.padding(4.dp)) {
                            RadioButton(choice==i,onClick=null,enabled=!stale)
                            Text(label,Modifier.weight(1f))
                        }
                    }
                }
                if(choice==0)Text(stringResource(R.string.shadow_off),Modifier.testTag("whitening-off"))
                RmeSecondaryButton(onClick={choice=0;before=false},enabled=!stale,modifier=Modifier.fillMaxWidth().testTag("whitening-reset")){Text(stringResource(R.string.shadow_reset))}
                Text(stringResource(R.string.whitening_limit),style=MaterialTheme.typography.bodySmall)
                if(stale)Text(stringResource(R.string.shadow_stale),color=MaterialTheme.colorScheme.error)
                RmePrimaryButton(onClick={if(!onApply(selectedFilter))stale=true},enabled=!stale,modifier=Modifier.fillMaxWidth().testTag("whitening-apply")){Text(stringResource(R.string.manual_apply))}
                RmeTextButton(onClick=onDismiss,modifier=Modifier.fillMaxWidth().testTag("whitening-cancel")){Text(stringResource(R.string.manual_cancel))}
            }
        }
    }
}
