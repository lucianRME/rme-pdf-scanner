package org.synapseworks.pageharbor.library.organization

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.synapseworks.pageharbor.library.*

class LibraryOrganizationRepository internal constructor(private val dao:LibraryDao,private val gate:LibraryOperationGate=LibraryOperationCoordinator.gate) {
    constructor(context:Context):this(LibraryDatabase.get(context).libraryDao())
    fun tags()=dao.observeTags()
    fun document(id:String)=dao.observeDocumentOrganization(id)
    fun page(folderId:String?,text:String,sort:LibrarySortOrder,filter:LibraryOrganizationFilter,offset:Int):Flow<OrganizationPage> =
        dao.observeOrganizationPage(OrganizationBrowse.query(folderId,text,sort,filter,offset)).map{rows->
            OrganizationPage(rows.mapNotNull{it.summary(text)},rows.firstOrNull()?.total?:0,offset)
        }.flowOn(Dispatchers.IO)
    private suspend fun write(block:suspend()->String?):OrganizationResult=withContext(Dispatchers.IO){
        try{gate.withMutation{currentCoroutineContext().ensureActive();OrganizationResult.Saved(block())}}
        catch(e:CancellationException){throw e}
        catch(e:OrganizationConstraint){OrganizationResult.Failed(e.reason)}
        catch(_:Exception){OrganizationResult.Failed(OrganizationFailure.UNAVAILABLE)}
    }
    suspend fun create(raw:String,documentId:String?=null):OrganizationResult {
        val name=LibraryTagRules.name(raw)?:return OrganizationResult.Failed(OrganizationFailure.INVALID_NAME)
        return write{dao.createOrganizationTag(name,documentId,System.currentTimeMillis())}
    }
    suspend fun rename(id:String,raw:String):OrganizationResult {
        val name=LibraryTagRules.name(raw)?:return OrganizationResult.Failed(OrganizationFailure.INVALID_NAME)
        return write{dao.renameOrganizationTag(id,name,System.currentTimeMillis());id}
    }
    suspend fun delete(id:String)=write{dao.deleteOrganizationTag(id,System.currentTimeMillis());null}
    suspend fun assign(documentId:String,tagId:String,value:Boolean)=write{dao.setOrganizationAssignment(documentId,tagId,value,System.currentTimeMillis());null}
    suspend fun favorite(documentId:String,value:Boolean)=write{dao.setOrganizationFavorite(documentId,value,System.currentTimeMillis());null}
}
