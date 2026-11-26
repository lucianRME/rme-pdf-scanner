package org.synapseworks.pageharbor.ui.home

import org.synapseworks.pageharbor.ui.theme.RmeActionRow
import org.synapseworks.pageharbor.ui.theme.RmeFilterChip
import org.synapseworks.pageharbor.ui.theme.RmeIconButton
import org.synapseworks.pageharbor.ui.theme.RmePrimaryButton
import org.synapseworks.pageharbor.ui.theme.RmeSecondaryButton
import org.synapseworks.pageharbor.ui.theme.RmeTextButton
import org.synapseworks.pageharbor.ui.theme.RmeTextField

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import org.synapseworks.pageharbor.library.*
import org.synapseworks.pageharbor.library.organization.*

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun LibraryOrganizationScreen(model:LibraryOrganizationViewModel,onOpen:(String,String?)->Unit){
    val s by model.uiState.collectAsState();val r=s.request
    val keyboard=LocalSoftwareKeyboardController.current
    val focus=LocalFocusManager.current
    if(!r.open)return
    var filtersExpanded by rememberSaveable{mutableStateOf(true)}
    var foldersOpen by rememberSaveable{mutableStateOf(false)}
    BackHandler{when{ s.deleteId!=null->model.askDelete(null);s.managing->model.manage(false);s.documentId!=null->model.closeDocument();else->model.close()}}
    Surface(Modifier.fillMaxSize().testTag("organization-screen")){
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(12.dp).testTag("organization-viewport")){
        val compact=maxHeight<400.dp
        Column(Modifier.fillMaxSize()){
            if(!compact)OrganizationHeading(s)
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("organization-results"),verticalArrangement=Arrangement.spacedBy(8.dp)){
                if(compact)item(key="heading"){OrganizationHeading(s)}
                item{Text("Tags and favorites belong to saved documents. They stay local and are excluded from PDF/JPEG names and sharing metadata.")}
                item(key="query"){RmeTextField(value=r.query,onValueChange=model::query,label={Text("Search titles, OCR, folders or tags")},modifier=Modifier.fillMaxWidth().testTag("organization-query"),singleLine=true,keyboardOptions=KeyboardOptions(imeAction=ImeAction.Done),keyboardActions=KeyboardActions(onDone={focus.clearFocus();keyboard?.hide()}))}
                item{RmeSecondaryButton(onClick={filtersExpanded=!filtersExpanded},modifier=Modifier.fillMaxWidth().testTag("organization-filters")){Text(if(filtersExpanded)"Hide filters" else "Show filters")}}
                if(filtersExpanded){
                    item{Row{Checkbox(checked=r.filter.favoritesOnly,onCheckedChange=model::favorites,modifier=Modifier.testTag("organization-favorites-only").semantics{contentDescription="Favorites only"});Text("Favorites only")}}
                    item{Text("Selected tags: ${r.filter.tagIds.size}/16. Tag condition AND folder AND favorites AND text.")}
                    item{FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                        for(mode in TagMatchMode.entries)RmeFilterChip(selected=mode==r.filter.match,onClick={model.mode(mode)},label={Text(if(mode==TagMatchMode.ANY)"Any selected tag"else"All selected tags")},modifier=Modifier.testTag("organization-match-${mode.name}"))}}
                    items(s.tags,key={"filter-${it.tagId}"}){tag->
                        RmeFilterChip(selected=tag.tagId in r.filter.tagIds,onClick={model.filterTag(tag.tagId,tag.tagId !in r.filter.tagIds)},label={Text(tag.name)},modifier=Modifier.fillMaxWidth().testTag("organization-filter-${tag.tagId}"))
                    }
                    item{RmeSecondaryButton(onClick={foldersOpen=true},modifier=Modifier.fillMaxWidth().testTag("organization-folder")){Text("Folder: "+(r.folderId?.let{id->s.folders.firstOrNull{it.id==id}?.name?:"Unavailable folder"}?:"All folders"))}}
                    item{Text("Sort results")
                        for(sort in LibrarySortOrder.entries)RmeSecondaryButton(onClick={model.sort(sort)},modifier=Modifier.fillMaxWidth().testTag("organization-sort-${sort.name}")){
                            Text(sort.label()+if(sort==r.sort)" · selected"else"")}}
                    item{RmeTextButton(onClick=model::clearFilters,modifier=Modifier.fillMaxWidth().testTag("organization-clear")){Text("Clear filters and search")}}
                }
                item{RmePrimaryButton(onClick={model.manage(true)},enabled=!s.working&&!s.catalogLoading,modifier=Modifier.fillMaxWidth().testTag("organization-manage")){Text("Manage tags (${s.tags.size}/256)")}}
                item{Text("${s.page.total} matching documents · page ${r.offset/30+1}",Modifier.testTag("organization-count").semantics{liveRegion=LiveRegionMode.Polite})}
                if(s.page.loading)item{Text("Loading results…",Modifier.testTag("organization-loading"))}
                else if(s.page.failed)item{Text("The library could not be read. Change a filter or reopen this view to retry.",color=MaterialTheme.colorScheme.error)}
                else if(s.page.items.isEmpty())item{Text("No documents match. Clear filters, choose another folder, or save a document to RME first.",Modifier.testTag("organization-empty"))}
                items(s.page.items,key={"document-${it.id}"}){document->
                    Card(Modifier.fillMaxWidth().testTag("organization-document-${document.id}")){
                        Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
                            Text(document.title,style=MaterialTheme.typography.titleMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
                            document.folderName?.let{Text(it,style=MaterialTheme.typography.bodySmall)}
                            document.searchMatch?.let{Text("Match: "+it.name.lowercase(),style=MaterialTheme.typography.bodySmall)}
                            Row{
                                RmeIconButton(onClick={model.favorite(document.id,!document.isFavorite)},enabled=!s.working&&!s.page.loading,
                                    modifier=Modifier.testTag("organization-favorite-${document.id}").semantics{selected=document.isFavorite}){
                                    Icon(Icons.Default.Star,contentDescription=if(document.isFavorite)"Remove favorite"else"Add favorite",
                                        tint=if(document.isFavorite)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)}
                                Text(if(document.isFavorite)"Favorite"else"Not favorite")
                            }
                            RmeSecondaryButton(onClick={model.editDocument(document.id)},enabled=!s.working,modifier=Modifier.fillMaxWidth().testTag("organization-tags-${document.id}")){Text("Assign or remove tags")}
                            RmeTextButton(onClick={model.close();onOpen(document.id,document.matchingPageId)},enabled=!s.working,modifier=Modifier.fillMaxWidth().testTag("organization-open-${document.id}")){Text("Open document")}
                        }
                    }
                }
                if(compact)item{OrganizationPager(s,model)}
                if(compact)item{OrganizationBack(model)}
            }
            if(!compact){OrganizationPager(s,model);OrganizationBack(model)}
        }
        }
    }
    if(foldersOpen)AlertDialog(onDismissRequest={foldersOpen=false},title={Text("Choose folder")},text={
        LazyColumn(Modifier.heightIn(max=320.dp)){
            item{RmeTextButton(onClick={model.folder(null);foldersOpen=false},modifier=Modifier.testTag("organization-folder-all")){Text("All folders")}}
            items(s.folders,key={it.id}){folder->RmeTextButton(onClick={model.folder(folder.id);foldersOpen=false},modifier=Modifier.testTag("organization-folder-${folder.id}")){
                Text(s.folders.breadcrumbTo(folder.id).joinToString(" / "){it.name}.ifBlank{folder.name})}}
        }
    },confirmButton={RmeTextButton(onClick={foldersOpen=false}){Text("Cancel")}})
    if(s.managing||s.documentId!=null)OrganizationTagDialog(s,model)
    s.deleteId?.let{id->AlertDialog(onDismissRequest={model.askDelete(null)},title={Text("Delete tag?")},text={Text("This removes the tag and its assignments. Documents and their favorites remain.")},
        confirmButton={RmeTextButton(onClick=model::deleteConfirmed,contentColor=MaterialTheme.colorScheme.error,enabled=!s.working,modifier=Modifier.testTag("organization-delete-confirm")){Text("Delete tag")}},
        dismissButton={RmeTextButton(onClick={model.askDelete(null)},enabled=!s.working){Text("Cancel")}})}
}
@Composable private fun OrganizationTagDialog(s:OrganizationUiState,model:LibraryOrganizationViewModel){
    androidx.compose.ui.window.Dialog(onDismissRequest={if(s.managing)model.manage(false)else model.closeDocument()},properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)){
        val keyboard=LocalSoftwareKeyboardController.current
        val focus=LocalFocusManager.current
        Surface(Modifier.fillMaxSize()){
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding().testTag("organization-tag-dialog")){
            val compact=maxHeight<400.dp
            Column(Modifier.fillMaxSize().padding(12.dp)){
                if(!compact)OrganizationTagHeading(s)
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    if(compact)OrganizationTagHeading(s)
                    if(!s.managing){
                        Text(s.document.firstOrNull()?.title?:if(s.documentLoading)"Loading document…"else"This document is unavailable.")
                        Text("Assignments save immediately. Closing this dialog does not undo confirmed changes.")
                        val assigned=s.document.mapNotNull{it.tagId}.toSet()
                        for(tag in s.tags)Row(Modifier.fillMaxWidth()){
                            Checkbox(checked=tag.tagId in assigned,onCheckedChange={model.assignment(tag.tagId,it)},enabled=!s.working&&!s.documentLoading&&s.document.isNotEmpty(),
                                modifier=Modifier.testTag("organization-assign-${tag.tagId}").semantics{contentDescription="Tag: ${tag.name}"})
                            Text(tag.name,Modifier.weight(1f))
                        }
                        RmeTextButton(onClick={model.manage(true)},enabled=!s.working){Text("Manage tag names")}
                    }else{
                        Text("Renaming keeps assignments. Deleting a tag preserves every document.")
                        for(tag in s.tags)Column(Modifier.fillMaxWidth()){
                            Text(tag.name)
                            RmeActionRow {
                                RmeTextButton(onClick={model.startRename(tag)},enabled=!s.working,modifier=Modifier.testTag("organization-rename-${tag.tagId}")){Text("Rename")}
                                RmeTextButton(onClick={model.askDelete(tag.tagId)},enabled=!s.working,modifier=Modifier.testTag("organization-delete-${tag.tagId}")){Text("Delete")}
                            }
                        }
                    }
                    RmeTextField(value=s.nameInput,onValueChange=model::name,label={Text(if(s.renameId!=null)"Rename tag"else"New tag name")},
                        enabled=!s.working,modifier=Modifier.fillMaxWidth().testTag("organization-name"),singleLine=true,keyboardOptions=KeyboardOptions(imeAction=ImeAction.Done),keyboardActions=KeyboardActions(onDone={focus.clearFocus();keyboard?.hide()}))
                    Text("1–40 characters. Spacing/case variants share one name; collisions are rejected.")
                    RmePrimaryButton(onClick=model::saveName,enabled=!s.working&&s.nameInput.isNotBlank()&&(s.managing||s.document.isNotEmpty()),
                        modifier=Modifier.fillMaxWidth().testTag("organization-save-name")){
                        Text(if(s.renameId!=null)"Save tag name"else if(s.managing)"Create tag"else"Create and assign tag")}
                    if(s.renameId!=null)RmeTextButton(onClick=model::cancelRename,enabled=!s.working){Text("Cancel rename")}
                    if(compact)OrganizationDialogDone(s,model)
                }
                if(!compact)OrganizationDialogDone(s,model)
            }
            }
        }
    }
}
@Composable private fun OrganizationHeading(s:OrganizationUiState){
    Column {
        Text("Tags & favorites",style=MaterialTheme.typography.titleLarge)
        if(s.working)Text("Saving metadata…",Modifier.semantics{liveRegion=LiveRegionMode.Polite})
        s.notice?.let{Text(it,Modifier.semantics{liveRegion=LiveRegionMode.Polite})}
        s.error?.let{Text(it.message(),Modifier.testTag("organization-error"),color=MaterialTheme.colorScheme.error)}
    }
}
@Composable private fun OrganizationPager(s:OrganizationUiState,model:LibraryOrganizationViewModel){
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){
        RmeSecondaryButton(onClick=model::previous,enabled=s.request.offset>0&&!s.page.loading,modifier=Modifier.weight(1f).testTag("organization-previous")){Text("Previous")}
        RmeSecondaryButton(onClick=model::next,enabled=s.page.hasNext&&!s.page.loading&&!s.page.failed,modifier=Modifier.weight(1f).testTag("organization-next")){Text("Next")}
    }
}
@Composable private fun OrganizationBack(model:LibraryOrganizationViewModel){
    RmeTextButton(onClick=model::close,modifier=Modifier.fillMaxWidth().testTag("organization-close")){Text("Back to library")}
}
@Composable private fun OrganizationTagHeading(s:OrganizationUiState){
    Column {
        Text(if(s.managing)"Manage tags"else "Document tags",style=MaterialTheme.typography.titleLarge)
        s.error?.let{Text(it.message(),Modifier.testTag("organization-tag-error"),color=MaterialTheme.colorScheme.error)}
    }
}
@Composable private fun OrganizationDialogDone(s:OrganizationUiState,model:LibraryOrganizationViewModel){
    RmeTextButton(onClick={if(s.managing)model.manage(false)else model.closeDocument()},enabled=!s.working,modifier=Modifier.fillMaxWidth().testTag("organization-dialog-close")){Text("Done")}
}
private fun LibrarySortOrder.label()=when(this){LibrarySortOrder.MODIFIED_DESC->"Last modified";LibrarySortOrder.CREATED_DESC->"Created date";LibrarySortOrder.DOCUMENT_DATE_DESC->"Document date";LibrarySortOrder.TITLE_ASC->"Title"}
private fun OrganizationFailure.message()=when(this){
    OrganizationFailure.INVALID_NAME->"Use 1–40 characters without control/format characters. Nothing was truncated."
    OrganizationFailure.NAME_EXISTS->"A tag with that normalized name already exists."
    OrganizationFailure.TAG_LIMIT->"The library already has 256 tags."
    OrganizationFailure.ASSIGNMENT_LIMIT->"A document can have at most 32 tags."
    OrganizationFailure.NOT_FOUND->"The selected tag or document is no longer available."
    OrganizationFailure.UNAVAILABLE->"Metadata could not be saved. Nothing was partially changed."
    OrganizationFailure.BUSY->"Wait for the current operation."
}
