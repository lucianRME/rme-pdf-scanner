package org.synapseworks.pageharbor.library.organization

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runners.MethodSorters
import org.synapseworks.pageharbor.library.*

/** Run each selector in a separate instrumentation process to prove disk persistence. */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class OrganizationRestartTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val name="m17-synthetic-restart.db"
    @Test fun aPrepareFavoriteAndAssignments()=runBlocking {
        check(context.packageName.endsWith(".phase2test"));context.deleteDatabase(name)
        val db=Room.databaseBuilder(context,LibraryDatabase::class.java,name).build()
        try {
            val dao=db.libraryDao()
            val d=LibraryDocumentEntity(documentId="restart",title="Synthetic restart",createdAtMillis=1,modifiedAtMillis=1,pageCount=1,folderId=null,thumbnailRelativePath=null,ocrStatus="NOT_INDEXED")
            val p=LibraryPageEntity(pageId="restart-page",documentId="restart",position=0,relativePath="synthetic",contentType="image/jpeg",sourceCategory="SCAN",width=1,height=1,sourceByteCount=1,rotationDegrees=0,filterName="ORIGINAL",ocrText=null,ocrError=null)
            dao.replaceDocument(d,listOf(p),"")
            val repo=LibraryOrganizationRepository(dao)
            assertTrue(repo.create("Restart tag","restart") is OrganizationResult.Saved)
            assertTrue(repo.favorite("restart",true) is OrganizationResult.Saved)
        } finally {db.close()}
    }
    @Test fun bVerifyFavoriteAndAssignmentsAfterRestart()=runBlocking {
        check(context.packageName.endsWith(".phase2test"))
        val db=Room.databaseBuilder(context,LibraryDatabase::class.java,name).build()
        try {
            val dao=db.libraryDao();val repo=LibraryOrganizationRepository(dao)
            assertTrue(dao.document("restart")!!.isFavorite)
            val id=dao.organizationTags().single().tagId;assertEquals(listOf(id),dao.documentTagIds("restart"))
            assertEquals(listOf("restart"),repo.page(null,"restart tag",LibrarySortOrder.MODIFIED_DESC,LibraryOrganizationFilter(setOf(id),favoritesOnly=true),0).first().items.map{it.id})
        } finally {db.close();context.deleteDatabase(name)}
    }
}
