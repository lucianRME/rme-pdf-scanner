package org.synapseworks.pageharbor.ui.home

import org.synapseworks.pageharbor.ui.theme.RmePrimaryButton
import org.synapseworks.pageharbor.ui.theme.RmeSecondaryButton
import org.synapseworks.pageharbor.ui.theme.RmeTextButton

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import org.synapseworks.pageharbor.library.recycle.*

@Composable internal fun RecycleScreen(model:RecycleViewModel){
    val s by model.state.collectAsState()
    if(!s.open)return
    BackHandler{if(s.confirmation!=null)model.dismiss()else model.close()}
    Surface(Modifier.fillMaxSize().testTag("recycle-screen")){
        LazyColumn(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp).testTag("recycle-list"),verticalArrangement=Arrangement.spacedBy(10.dp)){
            item{Text("Recycle bin",style=MaterialTheme.typography.headlineSmall,modifier=Modifier.semantics{heading()})}
            item{Text("Items stay here until you permanently delete them. Their private files still use storage. Restore keeps the saved folder if it exists; otherwise the document returns unfiled. Deleted tags are not recreated.")}
            item{Text("${s.page.count} items · page ${s.page.offset/30+1}",Modifier.testTag("recycle-count"))}
            s.notice?.let{n->item{Text(n,Modifier.semantics{liveRegion=LiveRegionMode.Polite})}}
            s.error?.let{e->item{Text(e.message(),Modifier.testTag("recycle-error"),color=MaterialTheme.colorScheme.error)}}
            if(s.working)item{Text("Working…",Modifier.semantics{liveRegion=LiveRegionMode.Polite})}
            if(s.loading)item{Text("Loading…")}
            else if(s.page.items.isEmpty())item{Text("The recycle bin is empty.",Modifier.testTag("recycle-empty"))}
            items(s.page.items,key={it.documentId}){d->Card(Modifier.fillMaxWidth()){
                Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                    Text(d.title,style=MaterialTheme.typography.titleMedium)
                    Text(if(d.isFavorite)"Favorite retained"else"Not favorite")
                    RmePrimaryButton(onClick={model.restore(d.documentId,d.trashRevision)},enabled=!s.working,modifier=Modifier.fillMaxWidth().testTag("recycle-restore-${d.documentId}")){Text("Restore document")}
                    RmeSecondaryButton(onClick={model.askPurge(d.documentId,d.trashRevision)},enabled=!s.working,modifier=Modifier.fillMaxWidth().testTag("recycle-purge-${d.documentId}")){Text("Permanently delete")}
                }
            }}
            item{RmeSecondaryButton(onClick=model::previous,enabled=!s.working&&!s.loading&&s.page.offset>=30,modifier=Modifier.fillMaxWidth().testTag("recycle-previous")){Text("Previous page")}}
            item{RmeSecondaryButton(onClick=model::next,enabled=!s.working&&!s.loading&&s.page.offset.toLong()+s.page.items.size<s.page.count,modifier=Modifier.fillMaxWidth().testTag("recycle-next")){Text("Next page")}}
            item{RmeSecondaryButton(onClick=model::askEmpty,enabled=!s.working&&s.page.count>0,modifier=Modifier.fillMaxWidth().testTag("recycle-empty-bin")){Text("Empty bin…")}}
            item{RmeTextButton(onClick=model::retryCleanup,enabled=!s.working,modifier=Modifier.fillMaxWidth().testTag("recycle-retry")){Text("Retry interrupted file cleanup")}}
            item{RmeTextButton(onClick=model::close,modifier=Modifier.fillMaxWidth().testTag("recycle-close")){Text("Back to library")}}
        }
    }
    s.confirmation?.let{c->
        androidx.compose.ui.window.Dialog(onDismissRequest=model::dismiss,properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)){
            Surface(Modifier.fillMaxSize()){
                LazyColumn(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp).testTag("recycle-confirm-list"),verticalArrangement=Arrangement.spacedBy(12.dp)){
                    item{Text(if(c.id==null)"Empty this bin selection?"else"Permanently delete this document?",style=MaterialTheme.typography.headlineSmall)}
                    item{Text("This removes ${c.count} selected item(s), including original files, pages, OCR, annotations, placed signatures and saved metadata. It cannot be undone in RME. External copies and backups are not erased. A local opaque ID marker is retained to block known older archive identities; it has no name or content. This is file removal, not guaranteed secure erasure. Open/captured sources must be released first.")}
                    if(c.id==null)item{Text("Items deleted or restored and deleted again after this selection are excluded. Failures remain in the bin for retry.")}
                    item{RmePrimaryButton(onClick=model::confirm,destructive=true,enabled=!s.working,modifier=Modifier.fillMaxWidth().testTag("recycle-confirm")){Text(if(c.id==null)"Permanently delete selected items"else"Permanently delete document")}}
                    item{RmeTextButton(onClick=model::dismiss,enabled=!s.working,modifier=Modifier.fillMaxWidth().testTag("recycle-cancel")){Text("Cancel")}}
                }
            }
        }
    }
}
private fun RecycleFailure.message()=when(this){
    RecycleFailure.IN_USE->"This document is open or captured by an operation. Close the open document and let its readers finish, then retry."
    RecycleFailure.CHANGED,RecycleFailure.NOT_FOUND->"This item changed or is no longer in the bin. Select it again."
    RecycleFailure.STORAGE->"Private files could not be quarantined. The item remains in the bin."
    RecycleFailure.STATE_UNCERTAIN->"Deletion could not be confirmed safely. Private files are protected for recovery; retry cleanup later."
    RecycleFailure.UNAVAILABLE->"The operation could not be completed safely. Retry or reopen the bin."
}

/** Ordinary delete has a separate scrolling confirmation, including at short-window font scale 2. */
@Composable internal fun RecycleMoveConfirmation(title:String,message:String,confirmLabel:String,onDismiss:()->Unit,onConfirm:()->Unit){
    androidx.compose.ui.window.Dialog(onDismissRequest=onDismiss,properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)){
        Surface(Modifier.fillMaxSize()){
            LazyColumn(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp).testTag("recycle-move-list"),verticalArrangement=Arrangement.spacedBy(12.dp)){
                item{Text(title,style=MaterialTheme.typography.headlineSmall)}
                item{Text(message)}
                item{RmePrimaryButton(onClick=onConfirm,destructive=true,modifier=Modifier.fillMaxWidth().testTag("recycle-move-confirm")){Text(confirmLabel)}}
                item{RmeTextButton(onClick=onDismiss,modifier=Modifier.fillMaxWidth().testTag("recycle-move-cancel")){Text("Cancel")}}
            }
        }
    }
}
