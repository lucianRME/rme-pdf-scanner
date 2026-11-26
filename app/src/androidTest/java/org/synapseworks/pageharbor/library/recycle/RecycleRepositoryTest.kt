package org.synapseworks.pageharbor.library.recycle

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.synapseworks.pageharbor.library.*
import org.synapseworks.pageharbor.library.organization.*
import org.synapseworks.pageharbor.document.session.*
import org.synapseworks.pageharbor.portability.export.*

class RecycleRepositoryTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private lateinit var root:File
    private lateinit var db:LibraryDatabase
    private lateinit var store:LibraryFileStore
    private lateinit var repo:LibraryRepository
    private val dao get()=db.libraryDao()
    private val pins=Any()
    @Before fun setup(){check(context.packageName.endsWith(".phase2test"));root=File(context.filesDir,"document-library/recycle-${UUID.randomUUID()}").apply{mkdirs()};db=Room.inMemoryDatabaseBuilder(context,LibraryDatabase::class.java).build();store=LibraryFileStore(context,File(root,"library"));repo=LibraryRepository(context,dao,store)}
    @After fun cleanup(){LibrarySourceReaders.retain(pins,emptyList());db.close();root.deleteRecursively()}
    private suspend fun seed(folder:String?=null):String {
        val source=File(root,"synthetic.png");val bitmap=Bitmap.createBitmap(100,200,Bitmap.Config.ARGB_8888)
        try{bitmap.eraseColor(android.graphics.Color.WHITE);source.outputStream().use{assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}}finally{bitmap.recycle()}
        val p=DocumentPage(DocumentPageId(1),DocumentResource(Uri.fromFile(source).toString(),DocumentResourceOwnership.USER_OR_EXTERNAL),DocumentSourceCategory.SELECTED_IMAGE,
            contentType="image/png",imageMetadata=DocumentImageMetadata(source.length(),100,200),persistentId=UUID.randomUUID().toString(),
            annotations=org.synapseworks.pageharbor.document.signature.SignatureFixtures.layer(),paperSize=DocumentPaperSize(72f,144f))
        val id=(repo.saveSession(DocumentSession(listOf(p)),"Synthetic recycle",folder) as LibraryResult.Success).value.id
        dao.replaceOcr(id,dao.pages(id).map{it.pageId to ("synthetic amber glyph" to null)},LibraryOcrStatus.INDEXED.name,3)
        return id
    }
    private fun hashes():Map<String,String> = store.root.walkTopDown().filter{it.isFile}.associate{it.relativeTo(store.root).path to MessageDigest.getInstance("SHA-256").digest(it.readBytes()).joinToString(""){b->"%02x".format(b)}}
    private suspend fun trash(id:String):LibraryDocumentEntity {assertTrue(repo.deleteDocument(id) is LibraryResult.Success);return dao.recycleDocument(id)!!}
    @Test fun trashRetainsEveryMetadataLayerAndBytesButExcludesAllActiveInputs()=runBlocking {
        dao.insertFolder(LibraryFolderEntity("folder","Saved folder","saved folder",1,1));val id=seed("folder")
        val org=LibraryOrganizationRepository(dao);val tag=(org.create("Retained",id) as OrganizationResult.Saved).tagId!!;org.favorite(id,true)
        val before=dao.document(id)!!;val pages=dao.pages(id);val bytes=hashes();val opened=(repo.openDocument(id) as LibraryResult.Success).value.session
        val trashed=trash(id);assertEquals("TRASHED",trashed.libraryState);assertEquals(before.modifiedAtMillis,trashed.modifiedAtMillis)
        assertEquals(pages,dao.pagesAnyState(id));assertEquals(bytes,hashes());assertEquals(listOf(tag),dao.documentTagIds(id));assertTrue(trashed.isFavorite)
        assertNull(dao.document(id));assertTrue(dao.pages(id).isEmpty());assertTrue(repo.searchHits("glyph").isEmpty())
        assertTrue(org.page(null,"",LibrarySortOrder.MODIFIED_DESC,LibraryOrganizationFilter(setOf(tag),favoritesOnly=true),0).first().items.isEmpty())
        assertTrue(repo.openDocument(id) is LibraryResult.Failure);assertTrue(repo.saveSession(opened,"Stale") is LibraryResult.Failure)
        val output=ByteArrayOutputStream();assertEquals(PortableExportPdfWriteResult.SOURCE_UNAVAILABLE,RoomPortableExportPdfSource(context,dao,store).writePdf(id,output));assertEquals(0,output.size())
        assertEquals(RecycleResult.Done(),repo.restoreFromRecycle(id,trashed.trashRevision));assertEquals(before,dao.document(id));assertEquals(pages,dao.pages(id));assertEquals(bytes,hashes())
        assertFalse(dao.isDeletedArchiveIdentity(id));assertTrue(repo.searchHits("glyph").isNotEmpty())
    }
    @Test fun deletedFolderFallsBackAndDeletedTagIsNotRecreated()=runBlocking {
        dao.insertFolder(LibraryFolderEntity("folder","Saved folder","saved folder",1,1));val id=seed("folder")
        val org=LibraryOrganizationRepository(dao);val tag=(org.create("Removed tag",id) as OrganizationResult.Saved).tagId!!;org.favorite(id,true)
        val t=trash(id);dao.deleteFolder("folder",4);org.delete(tag)
        assertEquals(RecycleResult.Done(),repo.restoreFromRecycle(id,t.trashRevision));val restored=dao.document(id)!!
        assertNull(restored.folderId);assertTrue(restored.isFavorite);assertTrue(dao.documentTagIds(id).isEmpty());assertTrue(dao.organizationTags().isEmpty())
    }
    @Test fun leasesProtectPurgeAndNewCapturesOrStaleSavesCannotResurrect()=runBlocking {
        val id=seed();val session=(repo.openDocument(id) as LibraryResult.Success).value.session
        val pages=dao.pages(id)
        LibrarySourceReaders.retain(pins,listOf(session));val t=trash(id)
        assertFalse(LibrarySourceReaders.canCapture(session));assertEquals(RecycleResult.Failed(RecycleFailure.IN_USE),repo.permanentlyDelete(id,t.trashRevision));assertTrue(hashes().isNotEmpty())
        LibrarySourceReaders.retain(pins,emptyList());assertEquals(RecycleResult.Done(),repo.permanentlyDelete(id,t.trashRevision));assertNull(dao.documentAnyState(id));assertTrue(dao.isDeletedArchiveIdentity(id))
        assertTrue(store.pendingDocumentDeletions() is DocumentDirectoryScan.Available);assertTrue(repo.saveSession(session,"Stale") is LibraryResult.Failure)
        try{dao.replaceDocument(t.copy(libraryState="ACTIVE"),pages,"");fail("Stale creation must reject")}catch(_:IllegalStateException){}
        assertNull(dao.documentAnyState(id))
    }
    @Test fun databaseFailureRollsBackPurgeAndUncertainRecoveryPreservesTrash()=runBlocking {
        val id=seed();val t=trash(id);val bytes=hashes()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_purge BEFORE DELETE ON library_documents BEGIN SELECT RAISE(ABORT,'synthetic failure'); END")
        assertEquals(RecycleResult.Failed(RecycleFailure.UNAVAILABLE),repo.permanentlyDelete(id,t.trashRevision))
        assertEquals("TRASHED",dao.recycleDocument(id)!!.libraryState);assertEquals(bytes,hashes())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_purge")
        val q=store.quarantineDocument(id);assertTrue(q is DocumentDeletionPreparation.Quarantined)
        db.close();val unknown=repo.recoverPendingDocumentDeletions();assertTrue(unknown.databaseUnavailable)
        assertTrue((q as DocumentDeletionPreparation.Quarantined).pending.directory.isDirectory)
    }
    @Test fun restoreRepairsInterruptedQuarantineBeforeActivationOrFailsClosed()=runBlocking {
        val id=seed();val t=trash(id);val bytes=hashes()
        assertTrue(store.quarantineDocument(id) is DocumentDeletionPreparation.Quarantined)
        val failing=object:LibraryFileStore(context,store.root){override fun restoreQuarantinedDocument(pending:PendingDocumentDeletion)=false}
        assertEquals(RecycleResult.Failed(RecycleFailure.STATE_UNCERTAIN),LibraryRepository(context,dao,failing).restoreFromRecycle(id,t.trashRevision))
        assertEquals("TRASHED",dao.recycleDocument(id)!!.libraryState)
        assertEquals(RecycleResult.Done(),repo.restoreFromRecycle(id,t.trashRevision));assertEquals(bytes,hashes());assertNotNull(dao.document(id))
    }
    @Test fun partialCleanupIsDurableAndExplicitRetryFinishesIt()=runBlocking {
        val id=seed();val t=trash(id);var fail=true
        val failing=object:LibraryFileStore(context,store.root){override fun completeDocumentDeletion(pending:PendingDocumentDeletion):Boolean {
            if(fail){pending.directory.walkTopDown().firstOrNull{it.isFile}?.delete();return false};return super.completeDocumentDeletion(pending)
        }}
        val r=LibraryRepository(context,dao,failing)
        assertEquals(RecycleResult.Done(true),r.permanentlyDelete(id,t.trashRevision));assertNull(dao.documentAnyState(id));assertTrue(dao.isDeletedArchiveIdentity(id))
        fail=false;assertEquals(1,r.recoverPendingDocumentDeletions().deleted)
        assertTrue((failing.pendingDocumentDeletions() as DocumentDirectoryScan.Available).entries.isEmpty())
    }
    @Test fun emptySelectionExcludesLaterTrashAndNewEpisodesAndRestorePurgeConflictIsSafe()=runBlocking {
        val a=seed();val b=seed();val first=trash(a);val preview=repo.recyclePreview()
        repo.restoreFromRecycle(a,first.trashRevision);val second=trash(a);trash(b)
        assertEquals(EmptyBinResult(0,0,0),repo.emptyRecycleBin(preview.watermark));assertTrue(dao.recycleDocument(a)!!.trashRevision>preview.watermark)
        assertEquals(RecycleResult.Failed(RecycleFailure.CHANGED),repo.permanentlyDelete(a,first.trashRevision))
        coroutineScope{val x=async{repo.restoreFromRecycle(a,second.trashRevision)};val y=async{repo.permanentlyDelete(a,second.trashRevision)};x.await();y.await()}
        val row=dao.documentAnyState(a);assertTrue(row==null||row.libraryState=="ACTIVE")
        assertEquals(1,repo.emptyRecycleBin(repo.recyclePreview().watermark).deleted)
        db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use{assertFalse(it.moveToFirst())}
    }
}
