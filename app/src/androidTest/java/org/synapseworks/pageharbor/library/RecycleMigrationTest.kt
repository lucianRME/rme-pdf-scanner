package org.synapseworks.pageharbor.library

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RecycleMigrationTest {
    @Test fun exportedVersionNineAddsEmptyTrashAndIdentityTablesExactly() = migrate(9)
    @Test fun exportedVersionEightChainsIntoTrashWithoutChangingPages() = migrate(8)
    private fun migrate(version:Int) = runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        check(context.packageName.endsWith(".phase2test"))
        val name="m18-${UUID.randomUUID()}.db"
        val schema=instrumentation.context.assets.open("org.synapseworks.pageharbor.library.LibraryDatabase/$version.json")
            .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        val helper=FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name).callback(object:SupportSQLiteOpenHelper.Callback(version) {
                override fun onCreate(db:SupportSQLiteDatabase) {
                    val entities=schema.getJSONArray("entities")
                    for (i in 0 until entities.length()) {
                        val entity=entities.getJSONObject(i)
                        db.execSQL(entity.getString("createSql").replace("${'$'}{TABLE_NAME}",entity.getString("tableName")))
                        val indices=entity.optJSONArray("indices") ?: org.json.JSONArray()
                        for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("${'$'}{TABLE_NAME}",entity.getString("tableName")))
                    }
                    for (i in 0 until entities.length()) {
                        val triggers=entities.getJSONObject(i).optJSONArray("contentSyncTriggers") ?: org.json.JSONArray()
                        for (j in 0 until triggers.length()) db.execSQL(triggers.getString(j))
                    }
                    val views=schema.optJSONArray("views") ?: org.json.JSONArray()
                    for (i in 0 until views.length()) db.execSQL(views.getJSONObject(i).getString("createSql"))
                    val setup=schema.getJSONArray("setupQueries")
                    for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
                }
                override fun onUpgrade(db:SupportSQLiteDatabase,oldVersion:Int,newVersion:Int) = error("Unexpected upgrade")
            }).build())
        try {
            helper.writableDatabase.apply {
                execSQL("INSERT INTO library_documents(document_id,title,created_at,modified_at,page_count,ocr_status,library_state,content_revision) VALUES ('document','Synthetic',1,1,1,'NOT_INDEXED','ACTIVE',0)")
                execSQL("INSERT INTO library_pages(page_id,document_id,page_position,relative_path,content_type,source_category,rotation_degrees,filter_name,visual_revision,ocr_state_revision) VALUES ('page','document',0,'synthetic.jpg','image/jpeg','SCAN',90,'GRAYSCALE',3,2)")
            }
            val expected=if(version>=6)org.synapseworks.pageharbor.document.annotation.AnnotationFixtures.layer().encode()else ""
            if(version>=6)helper.writableDatabase.execSQL("UPDATE library_pages SET annotations = ?",arrayOf(expected))
            if(version==9)helper.writableDatabase.apply {
                execSQL("UPDATE library_documents SET is_favorite=1 WHERE document_id='document'")
                execSQL("INSERT INTO library_tags(tag_id,name,normalized_name) VALUES ('retained-tag','Retained','retained')")
                execSQL("INSERT INTO library_document_tags(document_id,tag_id) VALUES ('document','retained-tag')")
            }
            helper.close()
            val db=Room.databaseBuilder(context,LibraryDatabase::class.java,name).addMigrations(MIGRATION_7_8, org.synapseworks.pageharbor.library.organization.MIGRATION_8_9, org.synapseworks.pageharbor.library.recycle.MIGRATION_9_10).build()
            try {
                val page=db.libraryDao().pages("document").single()
                assertEquals(0,page.brightness);assertEquals(0,page.contrast)
                assertEquals("page",page.pageId);assertEquals(90,page.rotationDegrees);assertEquals("GRAYSCALE",page.filterName)
                assertEquals(3L,page.visualRevision);assertEquals(2L,page.ocrStateRevision)
                db.openHelper.readableDatabase.query("PRAGMA user_version").use { assertTrue(it.moveToFirst());assertEquals(10,it.getInt(0));assertEquals(expected,page.annotations);assertEquals("",page.paperSize);assertEquals(0L,db.libraryDao().document("document")!!.trashRevision);assertNull(db.libraryDao().document("document")!!.trashedAtMillis);assertFalse(db.libraryDao().isDeletedLocalIdentity("document"));assertEquals(version==9,db.libraryDao().document("document")!!.isFavorite);assertEquals(if(version==9)listOf("retained-tag")else emptyList<String>(),db.libraryDao().organizationTags().map{it.tagId});assertEquals(if(version==9)listOf("retained-tag")else emptyList<String>(),db.libraryDao().documentTagIds("document")) }
            } finally { db.close() }
        } finally { helper.close();context.deleteDatabase(name) }
    }
}
