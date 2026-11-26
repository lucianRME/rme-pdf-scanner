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
import org.synapseworks.pageharbor.image.DocumentFilter

@Composable
internal fun ShadowGlareDialog(page: DocumentPage, pageNumber:Int, pageCount:Int,
    initial:DocumentFilter?, onDismiss:()->Unit, onApply:(DocumentFilter)->Boolean) {
    val filters=listOf(DocumentFilter.ORIGINAL,DocumentFilter.SHADOW_GLARE_LOW_V1,
        DocumentFilter.SHADOW_GLARE_BALANCED_V1,DocumentFilter.SHADOW_GLARE_STRONG_V1)
    val default=filters.indexOf(initial ?: page.filter).takeIf{it>0} ?: 2
    var choice by rememberSaveable(page.id,page.source,page.filter,page.rotation,page.adjustments,initial){mutableIntStateOf(default)}
    var before by rememberSaveable(page.id,page.source,page.filter,page.rotation,page.adjustments,initial){mutableStateOf(false)}
    var stale by rememberSaveable(page.id,page.source,page.filter,page.rotation,page.adjustments,initial){mutableStateOf(false)}
    val selected=if(before)page.filter else filters[choice]
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        Surface(Modifier.safeDrawingPadding().imePadding().padding(12.dp).widthIn(max=640.dp).fillMaxWidth().fillMaxHeight(0.95f),shape=MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.shadow_title),style=MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.shadow_help),style=MaterialTheme.typography.bodySmall)
                Text(stringResource(if(before)R.string.shadow_before else R.string.shadow_after),Modifier.testTag("shadow-comparison").semantics{liveRegion=LiveRegionMode.Polite})
                FilteredDocumentPreview(FilteredPreviewRequest(page.id.value,page.source.reference,selected,page.rotation,page.imageMetadata,page.adjustments),
                    page.source.toAndroidUri(),pageNumber,pageCount,minHeight=100.dp,maxHeight=220.dp)
                RmeSecondaryButton(onClick={before=!before},modifier=Modifier.fillMaxWidth().testTag("shadow-compare")) {
                    Text(stringResource(if(before)R.string.shadow_show_after else R.string.shadow_show_before))
                }
                Text(stringResource(R.string.shadow_intensity),style=MaterialTheme.typography.labelLarge)
                Column(Modifier.selectableGroup()) {
                    for(i in 1..3) {
                        val label=stringResource(when(i){1->R.string.shadow_low;2->R.string.shadow_balanced;else->R.string.shadow_strong})
                        Row(Modifier.fillMaxWidth().testTag("shadow-level-$i").selectable(choice==i,!stale,Role.RadioButton){choice=i;before=false}.padding(4.dp)) {
                            RadioButton(choice==i,onClick=null,enabled=!stale)
                            Text(label,Modifier.weight(1f))
                        }
                    }
                }
                if(choice==0)Text(stringResource(R.string.shadow_off),Modifier.testTag("shadow-off"))
                RmeSecondaryButton(onClick={choice=0;before=false},enabled=!stale,modifier=Modifier.fillMaxWidth().testTag("shadow-reset")){Text(stringResource(R.string.shadow_reset))}
                Text(stringResource(R.string.shadow_limit),style=MaterialTheme.typography.bodySmall)
                if(stale)Text(stringResource(R.string.shadow_stale),color=MaterialTheme.colorScheme.error)
                RmePrimaryButton(onClick={if(!onApply(filters[choice]))stale=true},enabled=!stale,modifier=Modifier.fillMaxWidth().testTag("shadow-apply")){Text(stringResource(R.string.manual_apply))}
                RmeTextButton(onClick=onDismiss,modifier=Modifier.fillMaxWidth().testTag("shadow-cancel")){Text(stringResource(R.string.manual_cancel))}
            }
        }
    }
}
