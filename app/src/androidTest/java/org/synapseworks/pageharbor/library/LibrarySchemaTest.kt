package org.synapseworks.pageharbor.library

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibrarySchemaTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun cleanUp() {
        context.deleteDatabase(MIGRATION_DATABASE_NAME)
    }

    @Test
    fun versionThreeCreatesPageAwareFtsAndOcrJournalSchema() {
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            database.openHelper.readableDatabase.query("PRAGMA user_version").use { cursor ->
                cursor.moveToFirst()
                assertEquals(3, cursor.getInt(0))
            }
            database.openHelper.readableDatabase.query(
                "SELECT count(*) FROM library_document_search_v3",
            ).use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
            database.openHelper.readableDatabase.query(
                "SELECT count(*) FROM library_data_operations",
            ).use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
            database.openHelper.readableDatabase.query(
                "SELECT count(*) FROM ocr_batch_jobs",
            ).use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun migrationOneToThreePreservesRowsAndCreatesLegacyArtifactAndPageFts() {
        context.deleteDatabase(MIGRATION_DATABASE_NAME)
        SQLiteDatabase.openOrCreateDatabase(
            context.getDatabasePath(MIGRATION_DATABASE_NAME),
            null,
        ).apply {
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS library_folders (
                    folder_id TEXT NOT NULL,
                    name TEXT NOT NULL,
                    normalized_name TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    modified_at INTEGER NOT NULL,
                    PRIMARY KEY(folder_id)
                )
                """.trimIndent(),
            )
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS library_documents (
                    row_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    document_id TEXT NOT NULL,
                    title TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    modified_at INTEGER NOT NULL,
                    page_count INTEGER NOT NULL,
                    folder_id TEXT,
                    thumbnail_path TEXT,
                    ocr_status TEXT NOT NULL,
                    FOREIGN KEY(folder_id) REFERENCES library_folders(folder_id)
                        ON UPDATE NO ACTION ON DELETE SET NULL
                )
                """.trimIndent(),
            )
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS library_pages (
                    page_id TEXT NOT NULL,
                    document_id TEXT NOT NULL,
                    page_position INTEGER NOT NULL,
                    relative_path TEXT NOT NULL,
                    content_type TEXT NOT NULL,
                    source_category TEXT NOT NULL,
                    width INTEGER,
                    height INTEGER,
                    source_byte_count INTEGER,
                    rotation_degrees INTEGER NOT NULL,
                    filter_name TEXT NOT NULL,
                    ocr_text TEXT,
                    ocr_error TEXT,
                    PRIMARY KEY(page_id),
                    FOREIGN KEY(document_id) REFERENCES library_documents(document_id)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            execSQL(
                "CREATE VIRTUAL TABLE IF NOT EXISTS library_document_search " +
                    "USING FTS4(title TEXT NOT NULL, ocr_text TEXT NOT NULL)",
            )
            execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_library_folders_normalized_name " +
                    "ON library_folders(normalized_name)",
            )
            execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_library_documents_document_id " +
                    "ON library_documents(document_id)",
            )
            execSQL(
                "CREATE INDEX IF NOT EXISTS index_library_documents_folder_id " +
                    "ON library_documents(folder_id)",
            )
            execSQL(
                "CREATE INDEX IF NOT EXISTS index_library_documents_modified_at " +
                    "ON library_documents(modified_at)",
            )
            execSQL(
                "CREATE INDEX IF NOT EXISTS index_library_pages_document_id " +
                    "ON library_pages(document_id)",
            )
            execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_library_pages_document_id_page_position " +
                    "ON library_pages(document_id, page_position)",
            )
            execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            execSQL(
                "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES(42, ?)",
                arrayOf(VERSION_ONE_IDENTITY_HASH),
            )
            execSQL(
                """
                INSERT INTO library_folders
                    (folder_id, name, normalized_name, created_at, modified_at)
                VALUES ('folder-1', 'Archive', 'archive', 100, 200)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO library_documents
                    (row_id, document_id, title, created_at, modified_at, page_count,
                     folder_id, thumbnail_path, ocr_status)
                VALUES (42, 'document-1', 'Migration proof', 300, 400, 1,
                        'folder-1', 'document-1/revisions/rev/thumbnail.jpg', 'INDEXED')
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO library_pages
                    (page_id, document_id, page_position, relative_path, content_type,
                     source_category, width, height, source_byte_count, rotation_degrees,
                     filter_name, ocr_text, ocr_error)
                VALUES ('page-1', 'document-1', 0,
                        'document-1/revisions/rev/page-1.jpg', 'image/jpeg',
                        'SELECTED_IMAGE', 100, 200, 1234, 0, 'ORIGINAL',
                        'needle migration text', NULL)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO library_document_search(rowid, title, ocr_text)
                VALUES (42, 'Migration proof', 'needle migration text')
                """.trimIndent(),
            )
            version = 1
            close()
        }

        val room = Room.databaseBuilder(context, LibraryDatabase::class.java, MIGRATION_DATABASE_NAME)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
        try {
            val database = room.openHelper.writableDatabase
            database.query(
                """
                SELECT row_id, library_state, pending_operation_id, content_sha256,
                       content_revision, ocr_script_preference
                FROM library_documents WHERE document_id = 'document-1'
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(42L, cursor.getLong(0))
                assertEquals(LibraryDocumentState.ACTIVE.name, cursor.getString(1))
                assertNull(cursor.getString(2))
                assertNull(cursor.getString(3))
                assertEquals(0L, cursor.getLong(4))
                assertNull(cursor.getString(5))
                assertFalse(cursor.moveToNext())
            }
            database.query(
                "SELECT parent_folder_id, parent_scope FROM library_folders WHERE folder_id = 'folder-1'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.isNull(0))
                assertEquals("", cursor.getString(1))
            }
            database.execSQL(
                """
                INSERT INTO library_folders
                    (folder_id, name, normalized_name, created_at, modified_at,
                     parent_folder_id, parent_scope)
                VALUES ('folder-2', 'Personal', 'personal', 100, 200, NULL, '')
                """.trimIndent(),
            )
            database.execSQL(
                """
                INSERT INTO library_folders
                    (folder_id, name, normalized_name, created_at, modified_at,
                     parent_folder_id, parent_scope)
                VALUES ('folder-1-year', '2024', '2024', 100, 200,
                        'folder-1', 'folder-1')
                """.trimIndent(),
            )
            database.execSQL(
                """
                INSERT INTO library_folders
                    (folder_id, name, normalized_name, created_at, modified_at,
                     parent_folder_id, parent_scope)
                VALUES ('folder-2-year', '2024', '2024', 100, 200,
                        'folder-2', 'folder-2')
                """.trimIndent(),
            )
            database.query(
                "SELECT count(*) FROM library_folders WHERE normalized_name = '2024'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(2, cursor.getInt(0))
            }
            database.execSQL(
                """
                INSERT INTO library_source_assets
                    (asset_id, document_id, role, relative_path, content_type,
                     byte_count, sha256, source_modified_at, created_at,
                     matches_current_revision)
                VALUES ('asset-1', 'document-1', 'ORIGINAL_DOCUMENT',
                        'sources/first.pdf', 'application/pdf', 10, 'hash-1',
                        100, 500, 1)
                """.trimIndent(),
            )
            database.execSQL(
                """
                INSERT INTO library_source_assets
                    (asset_id, document_id, role, relative_path, content_type,
                     byte_count, sha256, source_modified_at, created_at,
                     matches_current_revision)
                VALUES ('asset-2', 'document-1', 'ORIGINAL_DOCUMENT',
                        'sources/second.pdf', 'application/pdf', 20, 'hash-2',
                        200, 600, 0)
                """.trimIndent(),
            )
            database.query(
                """
                SELECT count(*) FROM library_source_assets
                WHERE document_id = 'document-1' AND role = 'ORIGINAL_DOCUMENT'
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(2, cursor.getInt(0))
            }
            database.query(
                """
                SELECT p.page_id FROM library_page_search_v3 AS search
                JOIN library_page_search_content_v3 AS c ON c.rowid = search.rowid
                JOIN library_pages AS p ON p.page_id = c.page_id
                WHERE library_page_search_v3 MATCH 'needle*'
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("page-1", cursor.getString(0))
                assertFalse(cursor.moveToNext())
            }
            database.query(
                """
                SELECT p.active_ocr_artifact_revision, p.ocr_state_revision,
                       a.raw_text, a.verification_state, a.actual_script, a.recognizer_id
                FROM library_pages AS p
                JOIN library_page_ocr_artifacts AS a
                  ON a.page_id = p.page_id
                 AND a.artifact_revision = p.active_ocr_artifact_revision
                WHERE p.page_id = 'page-1'
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1L, cursor.getLong(0))
                assertEquals(1L, cursor.getLong(1))
                assertEquals("needle migration text", cursor.getString(2))
                assertEquals(LibraryOcrArtifactVerification.LEGACY_UNVERIFIED.name, cursor.getString(3))
                assertEquals("LATIN", cursor.getString(4))
                assertEquals(LEGACY_OCR_RECOGNIZER_ID, cursor.getString(5))
            }
            database.query("PRAGMA foreign_key_check").use { cursor ->
                assertEquals(0, cursor.count)
            }
        } finally {
            room.close()
        }
    }

    @Test
    fun migrationTwoToThreePreservesRawAndErrorOnlyPagesAndReopensAtVersionThree() {
        val name = "library-migration-2-3-test.db"
        context.deleteDatabase(name)
        val path = context.getDatabasePath(name)
        SQLiteDatabase.openOrCreateDatabase(path, null).apply {
            execSQL("CREATE TABLE library_folders (folder_id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, normalized_name TEXT NOT NULL, created_at INTEGER NOT NULL, modified_at INTEGER NOT NULL)")
            execSQL("CREATE TABLE library_documents (row_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, document_id TEXT NOT NULL, title TEXT NOT NULL, created_at INTEGER NOT NULL, modified_at INTEGER NOT NULL, page_count INTEGER NOT NULL, folder_id TEXT, thumbnail_path TEXT, ocr_status TEXT NOT NULL, FOREIGN KEY(folder_id) REFERENCES library_folders(folder_id) ON UPDATE NO ACTION ON DELETE SET NULL)")
            execSQL("CREATE TABLE library_pages (page_id TEXT NOT NULL PRIMARY KEY, document_id TEXT NOT NULL, page_position INTEGER NOT NULL, relative_path TEXT NOT NULL, content_type TEXT NOT NULL, source_category TEXT NOT NULL, width INTEGER, height INTEGER, source_byte_count INTEGER, rotation_degrees INTEGER NOT NULL, filter_name TEXT NOT NULL, ocr_text TEXT, ocr_error TEXT, FOREIGN KEY(document_id) REFERENCES library_documents(document_id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            execSQL("CREATE VIRTUAL TABLE library_document_search USING FTS4(title TEXT NOT NULL, ocr_text TEXT NOT NULL)")
            execSQL("CREATE UNIQUE INDEX index_library_folders_normalized_name ON library_folders(normalized_name)")
            execSQL("CREATE UNIQUE INDEX index_library_documents_document_id ON library_documents(document_id)")
            execSQL("CREATE INDEX index_library_documents_folder_id ON library_documents(folder_id)")
            execSQL("CREATE INDEX index_library_documents_modified_at ON library_documents(modified_at)")
            execSQL("CREATE INDEX index_library_pages_document_id ON library_pages(document_id)")
            execSQL("CREATE UNIQUE INDEX index_library_pages_document_id_page_position ON library_pages(document_id,page_position)")
            execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            execSQL("INSERT INTO room_master_table (id, identity_hash) VALUES(42, ?)", arrayOf(VERSION_ONE_IDENTITY_HASH))
            execSQL("INSERT INTO library_folders VALUES('folder-v2','Archive','archive',1,2)")
            execSQL("INSERT INTO library_documents VALUES(7,'document-v2','V2 proof',3,4,2,'folder-v2',NULL,'PARTIAL')")
            execSQL("INSERT INTO library_pages VALUES('page-raw','document-v2',0,'raw.jpg','image/jpeg','TEST',100,200,10,0,'ORIGINAL','raw migration token',NULL)")
            execSQL("INSERT INTO library_pages VALUES('page-error','document-v2',1,'error.jpg','image/jpeg','TEST',100,200,11,0,'ORIGINAL',NULL,'SAFE_FAILURE')")
            execSQL("INSERT INTO library_document_search(rowid,title,ocr_text) VALUES(7,'V2 proof','raw migration token')")
            version = 1
            close()
        }
        val v2Helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(2) {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) = Unit

                        override fun onUpgrade(
                            db: androidx.sqlite.db.SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) {
                            assertEquals(1, oldVersion)
                            assertEquals(2, newVersion)
                            MIGRATION_1_2.migrate(db)
                        }
                    },
                )
                .build(),
        )
        v2Helper.writableDatabase.apply {
            execSQL(
                "UPDATE library_metadata SET library_revision = 17, modified_at = 99 " +
                    "WHERE metadata_id = 'library'",
            )
            execSQL(
                "INSERT INTO library_folders " +
                    "(folder_id,name,normalized_name,created_at,modified_at,parent_folder_id,parent_scope) " +
                    "VALUES('folder-v2-child','2026','2026',5,6,'folder-v2','folder-v2')",
            )
            execSQL(
                "UPDATE library_documents SET folder_id='folder-v2-child' " +
                    "WHERE document_id='document-v2'",
            )
        }
        v2Helper.close()

        fun openV3() = Room.databaseBuilder(context, LibraryDatabase::class.java, name)
            .addMigrations(MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()

        val migrated = openV3()
        try {
            val room = migrated
            val db = room.openHelper.writableDatabase
            db.query("SELECT library_revision, modified_at FROM library_metadata").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(17L, cursor.getLong(0))
                assertEquals(99L, cursor.getLong(1))
            }
            db.query(
                """
                SELECT d.row_id, d.title, d.created_at, d.modified_at, d.page_count, d.folder_id,
                       f.parent_folder_id, f.parent_scope
                FROM library_documents AS d
                JOIN library_folders AS f ON f.folder_id = d.folder_id
                WHERE d.document_id = 'document-v2'
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(7L, cursor.getLong(0))
                assertEquals("V2 proof", cursor.getString(1))
                assertEquals(3L, cursor.getLong(2))
                assertEquals(4L, cursor.getLong(3))
                assertEquals(2, cursor.getInt(4))
                assertEquals("folder-v2-child", cursor.getString(5))
                assertEquals("folder-v2", cursor.getString(6))
                assertEquals("folder-v2", cursor.getString(7))
                assertFalse(cursor.moveToNext())
            }
            db.query(
                "SELECT page_id, page_position FROM library_pages " +
                    "WHERE document_id='document-v2' ORDER BY page_position",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("page-raw", cursor.getString(0))
                assertEquals(0, cursor.getInt(1))
                assertTrue(cursor.moveToNext())
                assertEquals("page-error", cursor.getString(0))
                assertEquals(1, cursor.getInt(1))
                assertFalse(cursor.moveToNext())
            }
            db.query("SELECT active_ocr_artifact_revision, ocr_state_revision, ocr_error FROM library_pages WHERE page_id='page-raw'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1L, cursor.getLong(0))
                assertEquals(1L, cursor.getLong(1))
                assertTrue(cursor.isNull(2))
            }
            db.query("SELECT active_ocr_artifact_revision, ocr_state_revision, ocr_error FROM library_pages WHERE page_id='page-error'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.isNull(0))
                assertEquals(0L, cursor.getLong(1))
                assertEquals("SAFE_FAILURE", cursor.getString(2))
            }
            db.query("SELECT raw_text, actual_script, recognizer_id FROM library_page_ocr_artifacts WHERE page_id='page-raw' AND artifact_revision=1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("raw migration token", cursor.getString(0))
                assertEquals("LATIN", cursor.getString(1))
                assertEquals(LEGACY_OCR_RECOGNIZER_ID, cursor.getString(2))
            }
            db.query("SELECT page_id FROM library_page_search_v3 WHERE library_page_search_v3 MATCH 'migration*'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("page-raw", cursor.getString(0))
            }
            db.query("PRAGMA foreign_key_check").use { cursor -> assertEquals(0, cursor.count) }
        } finally {
            migrated.close()
        }
        val reopened = openV3()
        try {
            val room = reopened
            room.openHelper.readableDatabase.query("PRAGMA user_version").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(3, cursor.getInt(0))
            }
            room.openHelper.readableDatabase.query(
                "SELECT COUNT(*) FROM library_pages WHERE document_id='document-v2'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(2, cursor.getInt(0))
            }
        } finally {
            reopened.close()
        }
        context.deleteDatabase(name)
    }

    private companion object {
        const val MIGRATION_DATABASE_NAME = "library-migration-1-2-test.db"
        const val VERSION_ONE_IDENTITY_HASH = "1e51e807e9dfeb571dde417386b439a4"
    }
}
