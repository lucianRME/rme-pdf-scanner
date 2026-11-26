package org.synapseworks.pageharbor.library.organization

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.synapseworks.pageharbor.library.*

class OrganizationStoreInstrumentedTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db:LibraryDatabase
    private lateinit var dao:LibraryDao
    private lateinit var repo:LibraryOrganizationRepository
    @Before fun setup(){check(context.packageName.endsWith(".phase2test"));db=Room.inMemoryDatabaseBuilder(context,LibraryDatabase::class.java).build();dao=db.libraryDao();repo=LibraryOrganizationRepository(dao)}
    @After fun cleanup(){db.close()}
    private suspend fun tag(name:String,doc:String?=null)=(repo.create(name,doc) as OrganizationResult.Saved).tagId!!
    private suspend fun seed(index:Int,folder:String?=null):LibraryDocumentEntity {
        val id="doc-${index.toString().padStart(4,'0')}"
        val d=LibraryDocumentEntity(documentId=id,title="Synthetic $index",createdAtMillis=1000,modifiedAtMillis=1000,pageCount=2,folderId=folder,thumbnailRelativePath="synthetic-thumb",ocrStatus="INDEXED",contentHashVersion=4,contentSha256="a".repeat(64),contentByteCount=2)
        val pages=(0..1).map{p->LibraryPageEntity(pageId="$id-page-$p",documentId=id,position=p,relativePath="synthetic-$p",contentType="image/jpeg",sourceCategory="SCAN",width=10,height=20,sourceByteCount=1,rotationDegrees=0,filterName="ORIGINAL",paperSize="",ocrText="quartz ledger",ocrError=null,contentSha256="b".repeat(64))}
        return dao.replaceDocument(d,pages,"quartz ledger")
    }
    @Test fun namesCollisionsAssignmentDeleteAndAbsoluteActionsAreTransactional()=runBlocking {
        val d=seed(0);val a=tag("  Ａrchive ",d.documentId);val b=tag("Receipts")
        assertEquals(OrganizationResult.Failed(OrganizationFailure.NAME_EXISTS),repo.create("ARCHIVE"))
        assertEquals(OrganizationResult.Failed(OrganizationFailure.NAME_EXISTS),repo.rename(b,"archive"))
        assertTrue(repo.rename(a,"ARCHIVE") is OrganizationResult.Saved);assertEquals(a,dao.documentTagIds(d.documentId).single())
        coroutineScope{repeat(20){launch{repo.assign(d.documentId,a,true);repo.favorite(d.documentId,true)}}}
        assertEquals(listOf(a),dao.documentTagIds(d.documentId));assertTrue(dao.document(d.documentId)!!.isFavorite)
        assertTrue(repo.assign(d.documentId,b,true) is OrganizationResult.Saved)
        assertTrue(repo.delete(a) is OrganizationResult.Saved)
        assertEquals(listOf(b),dao.documentTagIds(d.documentId));assertTrue(dao.document(d.documentId)!!.isFavorite)
        assertEquals(OrganizationResult.Failed(OrganizationFailure.NOT_FOUND),repo.assign(d.documentId,a,true))
        dao.deleteDocument(d.documentId);assertTrue(dao.documentTagIds(d.documentId).isEmpty());assertEquals(listOf(b),dao.organizationTags().map{it.tagId})
        db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use{assertFalse(it.moveToFirst())}
    }
    @Test fun combinedFiltersRunBeforePaginationWithStableUniqueDocumentsAndOcrPage()=runBlocking {
        dao.insertFolder(LibraryFolderEntity("folder","Synthetic folder","synthetic folder",1,1))
        val a=tag("Archive");val b=tag("Receipts");val punctuation=tag("%_\\")
        db.withTransaction{repeat(325){i->val d=seed(i,if(i%2==0)"folder" else null);dao.setOrganizationAssignment(d.documentId,a,true,1);if(i%3==0)dao.setOrganizationAssignment(d.documentId,b,true,1);if(i%5==0)dao.setOrganizationFavorite(d.documentId,true,1)}}
        suspend fun page(offset:Int,filter:LibraryOrganizationFilter=LibraryOrganizationFilter(),query:String="",folder:String?=null,sort:LibrarySortOrder=LibrarySortOrder.MODIFIED_DESC)=repo.page(folder,query,sort,filter,offset).first()
        val any=LibraryOrganizationFilter(setOf(a,b))
        val ids=mutableListOf<String>();var offset=0
        do {val p=page(offset,any);assertEquals(325L,p.total);ids+=p.items.map{it.id};offset+=30}while(p.hasNext)
        assertEquals(325,ids.size);assertEquals(325,ids.distinct().size);assertEquals(ids,page(0,any).items.map{it.id}+ids.drop(30))
        val expected=(0 until 325).filter{it%2==0&&it%3==0&&it%5==0}.map{"doc-${it.toString().padStart(4,'0')}"}.toSet()
        val combined=page(0,LibraryOrganizationFilter(setOf(a,b),TagMatchMode.ALL,true),"quartz","folder")
        assertEquals(expected,combined.items.map{it.id}.toSet());assertTrue(combined.items.all{it.matchingPagePosition==0&&it.matchingPageId==it.id+"-page-0"})
        assertTrue(page(999,any).items.isEmpty());assertEquals(325L,page(999,any).total)
        assertTrue(page(0,LibraryOrganizationFilter(setOf("deleted")),"quartz").items.isEmpty())
        dao.setOrganizationAssignment("doc-0324",punctuation,true,1)
        val literal=page(0,query="%_\\");assertEquals(listOf("doc-0324"),literal.items.map{it.id});assertEquals(LibrarySearchMatch.TAG,literal.items.single().searchMatch)
        // Title attribution uses the same prefix/Unicode matching as ordinary library search.
        dao.renameDocument("doc-0000","Synthetic multilingual sample",2)
        assertEquals(LibrarySearchMatch.TITLE,page(0,query="synth samp").items.single().searchMatch)
        dao.renameDocument("doc-0000","ÎNREGISTRARE SINTETICĂ",3)
        assertEquals(LibrarySearchMatch.TITLE,page(0,query="înreg sint").items.single().searchMatch)
        assertTrue(page(0,query="synthetic folder").items.all{it.searchMatch==LibrarySearchMatch.FOLDER})
        for(sort in LibrarySortOrder.entries){val first=page(0,any,sort=sort);assertEquals(first,page(0,any,sort=sort))}
    }
    @Test fun staleOpenSaveMoveRenameAndNewCopiesCannotOverwriteOrganization()=runBlocking {
        val stale=seed(1);val pages=dao.pages(stale.documentId);val a=tag("Invoice",stale.documentId)
        repo.favorite(stale.documentId,true)
        dao.replaceDocument(stale.copy(title="Updated title",isFavorite=false),pages,"quartz ledger")
        val saved=dao.document(stale.documentId)!!
        assertTrue(saved.isFavorite);assertEquals(listOf(a),dao.documentTagIds(stale.documentId));assertEquals(pages,dao.pages(stale.documentId));assertEquals(stale.contentSha256,saved.contentSha256)
        dao.insertFolder(LibraryFolderEntity("destination","Destination","destination",1,1))
        dao.renameDocument(stale.documentId,"Renamed",2)
        dao.moveDocument(stale.documentId,"destination",3)
        assertTrue(dao.document(stale.documentId)!!.isFavorite);assertEquals(listOf(a),dao.documentTagIds(stale.documentId))
        val copy=seed(2);assertFalse(copy.isFavorite);assertTrue(dao.documentTagIds(copy.documentId).isEmpty())
    }
    @Test fun restoreAssignmentKeysetBatchesStayCompleteAndMissingMappingRollsBack()=runBlocking {
        val operation=LibraryDataOperationEntity("organization-operation","RESTORE","PREPARING","ZIP",null,0,1,1,40,40,40,0,0,0,null,0,false,null)
        dao.insertOperation(operation)
        val definitions=(0 until 32).map{LibraryTagEntity("incoming-$it","Batch tag $it","batch tag $it")}
        repeat(40){i->
            val d=LibraryDocumentEntity(documentId="pending-${i.toString().padStart(3,'0')}",title="Synthetic pending",createdAtMillis=1,modifiedAtMillis=1,pageCount=0,folderId=null,thumbnailRelativePath=null,ocrStatus="NOT_INDEXED",libraryState="PENDING",pendingOperationId=operation.operationId)
            dao.beginStreamingPendingRestoreDocument(d)
            dao.insertOperationItems(listOf(LibraryDataOperationItemEntity("item-$i",operation.operationId,i,"PREPARED",d.documentId,null,"Synthetic pending",null,null,null,0,null,null,"NONE",null,"IMPORT_ANYWAY",null)))
            dao.stageOrganizationAssignments(operation.operationId,d.documentId,definitions.map{it.tagId})
        }
        try {
            dao.activateRestoreOperation(operation.operationId,0,operation.copy(phase="COMPLETED",importedDocumentCount=40),2,definitions.dropLast(1))
            fail("Missing mapping must reject activation")
        } catch(_:IllegalArgumentException) {}
        assertTrue(dao.organizationTags().isEmpty());assertTrue(dao.activeDocumentsPage(-1,50).isEmpty())
        assertEquals(960,dao.pendingTagRows(operation.operationId,"",960).size)
        dao.activateRestoreOperation(operation.operationId,0,operation.copy(phase="COMPLETED",importedDocumentCount=40),2,definitions)
        assertEquals(40,dao.activeDocumentsPage(-1,50).size)
        for(d in dao.activeDocumentsPage(-1,50))assertEquals(32,dao.documentTagIds(d.documentId).size)
        assertTrue(dao.pendingTagRows(operation.operationId,"",960).isEmpty())
    }
    @Test fun cancelledWriterWaitingForLibraryGateDoesNotPublishMetadata()=runBlocking {
        val gate=LibraryOperationGate();val lockedRepo=LibraryOrganizationRepository(dao,gate)
        gate.withStableSnapshot {
            val job=launch(Dispatchers.Default){lockedRepo.create("Cancelled tag")}
            delay(50);job.cancelAndJoin()
        }
        assertTrue(dao.organizationTags().isEmpty())
    }
    @Test fun limitsRejectWithoutPartialTagCreationOrAssignment()=runBlocking {
        val d=seed(0)
        repeat(32){tag("Tag $it",d.documentId)}
        assertEquals(OrganizationResult.Failed(OrganizationFailure.ASSIGNMENT_LIMIT),repo.create("Overflow",d.documentId))
        assertEquals(32,dao.organizationTags().size);assertEquals(32,dao.documentTagIds(d.documentId).size)
        repeat(224){tag("Unused $it")}
        assertEquals(OrganizationResult.Failed(OrganizationFailure.TAG_LIMIT),repo.create("Too many"));assertEquals(256,dao.organizationTags().size)
    }
}
