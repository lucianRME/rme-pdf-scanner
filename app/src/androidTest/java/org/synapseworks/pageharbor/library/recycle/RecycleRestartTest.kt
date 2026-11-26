package org.synapseworks.pageharbor.library.recycle

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import org.synapseworks.pageharbor.library.*

/** Each selector is run in a separate instrumentation process, against synthetic private storage. */
class RecycleRestartTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val name="m18-synthetic-restart.db"
    private val root=File(context.filesDir,"document-library/m18-synthetic-restart")
    private fun open()=Room.databaseBuilder(context,LibraryDatabase::class.java,name).build()
    private fun hash(f:File)=MessageDigest.getInstance("SHA-256").digest(f.readBytes()).toList()
    @Test fun aPrepareTrash()=runBlocking {
        check(context.packageName.endsWith(".phase2test"));context.deleteDatabase(name);root.deleteRecursively()
        val db=open()
        try {
            val dao=db.libraryDao();val store=LibraryFileStore(context,root)
            val file=File(root,"restart/revisions/test/source.bin").apply{parentFile!!.mkdirs();writeBytes(ByteArray(1024){(it%127).toByte()})}
            val d=LibraryDocumentEntity(documentId="restart",title="Synthetic restart",createdAtMillis=1,modifiedAtMillis=1,pageCount=1,folderId=null,thumbnailRelativePath=null,ocrStatus="INDEXED",isFavorite=true)
            val p=LibraryPageEntity(pageId="restart-page",documentId="restart",position=0,relativePath="restart/revisions/test/source.bin",contentType="image/jpeg",sourceCategory="SCAN",width=1,height=1,sourceByteCount=file.length(),rotationDegrees=0,filterName="ORIGINAL",ocrText="synthetic",ocrError=null)
            dao.replaceDocument(d,listOf(p),"synthetic")
            org.synapseworks.pageharbor.library.organization.LibraryOrganizationRepository(dao).create("Restart retained","restart")
            assertTrue(LibraryRepository(context,dao,store).deleteDocument("restart") is LibraryResult.Success)
            assertTrue(file.isFile);assertEquals("TRASHED",dao.recycleDocument("restart")!!.libraryState)
        }finally{db.close()}
    }
    @Test fun bRestoreAndInterruptPurgeBeforeCommit()=runBlocking {
        check(context.packageName.endsWith(".phase2test"));val db=open()
        try {
            val dao=db.libraryDao();val store=LibraryFileStore(context,root);val repo=LibraryRepository(context,dao,store)
            val row=dao.recycleDocument("restart")!!;assertNull(dao.document("restart"));assertTrue(row.isFavorite);assertEquals(1,dao.documentTagIds("restart").size)
            val file=File(root,"restart/revisions/test/source.bin");val before=hash(file)
            assertEquals(RecycleResult.Done(),repo.restoreFromRecycle("restart",row.trashRevision));assertEquals(before,hash(file));assertEquals("synthetic",dao.pages("restart").single().ocrText)
            repo.deleteDocument("restart");assertTrue(store.quarantineDocument("restart") is DocumentDeletionPreparation.Quarantined)
            assertFalse(file.exists()) // Process exits before database deletion.
        }finally{db.close()}
    }
    @Test fun cRecoverTrashThenInterruptAfterCommit()=runBlocking {
        check(context.packageName.endsWith(".phase2test"));val db=open()
        try {
            val dao=db.libraryDao();val store=LibraryFileStore(context,root);val repo=LibraryRepository(context,dao,store)
            assertEquals(1,repo.recoverPendingDocumentDeletions().restored)
            val file=File(root,"restart/revisions/test/source.bin");assertEquals(ByteArray(1024){(it%127).toByte()}.toList(),file.readBytes().toList())
            val row=dao.recycleDocument("restart")!!;assertEquals("TRASHED",row.libraryState)
            assertTrue(store.quarantineDocument("restart") is DocumentDeletionPreparation.Quarantined)
            assertNotNull(dao.purgeTrashedDocument("restart",row.trashRevision,9)) // Process exits before filesystem cleanup.
        }finally{db.close()}
    }
    @Test fun dRecoverCommittedPurge()=runBlocking {
        check(context.packageName.endsWith(".phase2test"));val db=open()
        try {
            val dao=db.libraryDao();val store=LibraryFileStore(context,root);val repo=LibraryRepository(context,dao,store)
            assertNull(dao.documentAnyState("restart"));assertTrue(dao.isDeletedArchiveIdentity("restart"))
            assertEquals(1,repo.recoverPendingDocumentDeletions().deleted)
            assertTrue((store.pendingDocumentDeletions() as DocumentDirectoryScan.Available).entries.isEmpty())
            assertFalse(File(root,"restart").exists());assertTrue(dao.documentTagIds("restart").isEmpty())
        }finally{db.close();context.deleteDatabase(name);root.deleteRecursively()}
    }
}
