package org.synapseworks.pageharbor.library.organization

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.synapseworks.pageharbor.library.*

internal data class OrganizationRequest(val open:Boolean=false,val folderId:String?=null,val query:String="",val sort:LibrarySortOrder=LibrarySortOrder.MODIFIED_DESC,
    val filter:LibraryOrganizationFilter=LibraryOrganizationFilter(),val offset:Int=0)
internal data class OrganizationUiState(val request:OrganizationRequest=OrganizationRequest(),val tags:List<LibraryTagEntity> = emptyList(),val folders:List<LibraryFolder> = emptyList(),
    val catalogLoading:Boolean=true,val page:OrganizationPage=OrganizationPage(),val documentId:String?=null,val document:List<DocumentOrganizationRow> = emptyList(),
    val documentLoading:Boolean=false,val managing:Boolean=false,val nameInput:String="",val renameId:String?=null,val deleteId:String?=null,
    val working:Boolean=false,val error:OrganizationFailure?=null,val notice:String?=null)
/** Metadata only: no page session, source bitmap, private output destination or M13 capture. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class LibraryOrganizationViewModel(app:Application):AndroidViewModel(app) {
    private val repository=LibraryOrganizationRepository(app)
    private val request=MutableStateFlow(OrganizationRequest())
    private val documentId=MutableStateFlow<String?>(null)
    private val mutable=MutableStateFlow(OrganizationUiState())
    val uiState:StateFlow<OrganizationUiState> = mutable.asStateFlow()
    private var active=true;private var epoch=0L;private var writeJob:Job?=null
    init{
        viewModelScope.launch{repository.tags().catch{mutable.update{it.copy(catalogLoading=false,error=OrganizationFailure.UNAVAILABLE)}}.collect{tags->
            mutable.update{it.copy(tags=tags,catalogLoading=false)}
            val missing=request.value.filter.tagIds-tags.map{it.tagId}.toSet()
            if(missing.isNotEmpty()){
                changeRequest{it.copy(filter=it.filter.copy(tagIds=it.filter.tagIds-missing),offset=0)}
                mutable.update{it.copy(notice="Deleted tag filters were cleared.")}
            }
            val state=mutable.value
            if(state.renameId!=null&&tags.none{it.tagId==state.renameId})mutable.update{it.copy(renameId=null,nameInput="",error=OrganizationFailure.NOT_FOUND)}
        }}
        viewModelScope.launch{LibraryRepository(app).observeFolders().catch{mutable.update{it.copy(error=OrganizationFailure.UNAVAILABLE)}}.collect{folders->
            mutable.update{it.copy(folders=folders)}
            if(request.value.folderId!=null&&folders.none{it.id==request.value.folderId}){
                changeRequest{it.copy(folderId=null,offset=0)};mutable.update{it.copy(notice="The deleted folder filter was cleared.")}
            }
        }}
        viewModelScope.launch{request.flatMapLatest{r->
            if(!r.open)flowOf(r to OrganizationPage())else flow{
                emit(r to OrganizationPage(offset=r.offset,loading=true));delay(180)
                emitAll(repository.page(r.folderId,r.query,r.sort,r.filter,r.offset).map{r to it})
            }.catch{emit(r to OrganizationPage(offset=r.offset,failed=true))}
        }.collect{(r,page)->
            if(request.value==r){
                if(!page.loading&&!page.failed&&r.offset>0&&r.offset>=page.total){
                    changeRequest{it.copy(offset=if(page.total==0L)0 else (((page.total-1)/LibraryTagRules.PAGE_SIZE)*LibraryTagRules.PAGE_SIZE).toInt())}
                }else mutable.update{it.copy(page=page)}
            }
        }}
        viewModelScope.launch{documentId.flatMapLatest{id->if(id==null)flowOf(id to emptyList())else repository.document(id).map{id to it}.catch{emit(id to emptyList())}}
            .collect{(id,rows)->if(documentId.value==id){
                mutable.update{it.copy(document=rows,documentLoading=false,error=if(id!=null&&rows.isEmpty())OrganizationFailure.NOT_FOUND else it.error)}
            }}}
    }
    private fun canInteract()=active&&request.value.open
    fun setActive(value:Boolean){active=value;if(!value)close()}
    fun open(folderId:String?=null,query:String="",sort:LibrarySortOrder=LibrarySortOrder.MODIFIED_DESC){
        if(!active||mutable.value.working)return;epoch++;mutable.update{it.copy(error=null,notice=null)}
        changeRequest{it.copy(open=true,folderId=folderId,query=query.take(160),sort=sort,offset=0)}
    }
    fun close(){epoch++;writeJob?.cancel();writeJob=null;documentId.value=null
        changeRequest{it.copy(open=false)}
        mutable.update{it.copy(documentId=null,document=emptyList(),managing=false,nameInput="",renameId=null,deleteId=null,working=false,error=null)}
    }
    private fun changeRequest(block:(OrganizationRequest)->OrganizationRequest){
        val next=block(request.value)
        if(next==request.value)return
        request.value=next
        mutable.update{it.copy(request=next,page=it.page.copy(loading=next.open))}
    }
    private fun userRequest(block:(OrganizationRequest)->OrganizationRequest){if(canInteract())changeRequest(block)}
    fun query(value:String)=userRequest{it.copy(query=value.take(160),offset=0)}
    fun folder(value:String?)=userRequest{it.copy(folderId=value,offset=0)}
    fun sort(value:LibrarySortOrder)=userRequest{it.copy(sort=value,offset=0)}
    fun favorites(value:Boolean)=userRequest{it.copy(filter=it.filter.copy(favoritesOnly=value),offset=0)}
    fun mode(value:TagMatchMode)=userRequest{it.copy(filter=it.filter.copy(match=value),offset=0)}
    fun filterTag(id:String,value:Boolean){
        if(!canInteract())return
        val selected=request.value.filter.tagIds
        if(value&&selected.size>=LibraryTagRules.MAX_FILTER_TAGS&&id !in selected){mutable.update{it.copy(notice="Select at most 16 tag filters.")};return}
        changeRequest{it.copy(filter=it.filter.copy(tagIds=if(value)selected+id else selected-id),offset=0)}
    }
    fun clearFilters()=userRequest{it.copy(folderId=null,query="",filter=LibraryOrganizationFilter(),offset=0)}
    fun previous(){if(canInteract()&&request.value.offset>=LibraryTagRules.PAGE_SIZE)changeRequest{it.copy(offset=it.offset-LibraryTagRules.PAGE_SIZE)}}
    fun next(){val s=mutable.value;if(canInteract()&&!s.page.loading&&s.page.hasNext&&request.value.offset<=Int.MAX_VALUE-LibraryTagRules.PAGE_SIZE*2)changeRequest{it.copy(offset=it.offset+LibraryTagRules.PAGE_SIZE)}}
    fun editDocument(id:String){if(!canInteract()||mutable.value.working||documentId.value==id)return;documentId.value=id;mutable.update{it.copy(documentId=id,document=emptyList(),documentLoading=true,error=null)}}
    fun closeDocument(){if(!canInteract()||mutable.value.working)return;documentId.value=null;mutable.update{it.copy(documentId=null,document=emptyList(),nameInput="",error=null)}}
    fun manage(value:Boolean){if(!canInteract()||mutable.value.working)return;mutable.update{it.copy(managing=value,nameInput="",renameId=null,deleteId=null,error=null)}}
    fun name(value:String){
        val s=mutable.value
        if(!canInteract()||(!s.managing&&s.documentId==null)||s.working)return
        if(value.length>160){mutable.update{it.copy(error=OrganizationFailure.INVALID_NAME)};return}
        mutable.update{it.copy(nameInput=value,error=null)}
    }
    fun startRename(tag:LibraryTagEntity){if(canInteract()&&mutable.value.managing&&!mutable.value.working)mutable.update{it.copy(renameId=tag.tagId,nameInput=tag.name,error=null)}}
    fun cancelRename(){if(canInteract()&&mutable.value.managing&&!mutable.value.working)mutable.update{it.copy(renameId=null,nameInput="",error=null)}}
    fun askDelete(id:String?){if(canInteract()&&mutable.value.managing&&!mutable.value.working)mutable.update{it.copy(deleteId=id)}}
    private fun write(block:suspend()->OrganizationResult){
        if(!canInteract()||mutable.value.working)return
        val token=epoch;mutable.update{it.copy(working=true,error=null,notice=null)}
        writeJob=viewModelScope.launch{
            try{
                val result=block()
                if(active&&epoch==token)mutable.update{it.copy(error=(result as? OrganizationResult.Failed)?.reason,
                    nameInput=if(result is OrganizationResult.Saved)""else it.nameInput,renameId=if(result is OrganizationResult.Saved)null else it.renameId,
                    deleteId=if(result is OrganizationResult.Saved)null else it.deleteId)}
            }finally{if(epoch==token)mutable.update{it.copy(working=false)}}
        }
    }
    fun saveName(){val s=mutable.value;if(!s.managing&&s.documentId==null)return;val raw=s.nameInput;val rename=s.renameId
        write{if(rename==null)repository.create(raw,if(s.managing)null else s.documentId)else repository.rename(rename,raw)}}
    fun deleteConfirmed(){val id=mutable.value.deleteId?:return;write{repository.delete(id)}}
    fun assignment(tagId:String,value:Boolean){val id=mutable.value.documentId?:return;write{repository.assign(id,tagId,value)}}
    fun favorite(id:String,value:Boolean)=write{repository.favorite(id,value)}
}
