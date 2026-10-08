package org.synapseworks.pageharbor.library

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
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
        context.deleteDatabase(HISTORICAL_V2_MIGRATION_DATABASE_NAME)
        context.deleteDatabase(HISTORICAL_V3_MIGRATION_DATABASE_NAME)
    }

    @Test
    fun versionFourCreatesPageAwareFtsOcrJournalAndProvenanceSchema() {
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            database.openHelper.readableDatabase.query("PRAGMA user_version").use { cursor ->
                cursor.moveToFirst()
                assertEquals(4, cursor.getInt(0))
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
            assertRequiredCompositeIndex(database.openHelper.readableDatabase)
        } finally {
            database.close()
        }
    }

    @Test
    fun migrationOneToFourPreservesRowsCreatesLegacyArtifactAndLeavesProvenanceUnknown() {
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
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .allowMainThreadQueries()
            .build()
        try {
            val database = room.openHelper.writableDatabase
            assertRequiredCompositeIndex(database)
            database.query(
                """
                SELECT row_id, library_state, pending_operation_id, content_sha256,
                       content_revision, ocr_script_preference,
                       document_date_epoch_day, document_date_provenance,
                       document_date_confidence, original_created_at,
                       original_created_at_provenance, source_modified_at_provenance,
                       source_provenance_kind, source_provenance_label
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
                (6..13).forEach { column -> assertTrue(cursor.isNull(column)) }
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
                       a.raw_text, a.verification_state, a.actual_script, a.recognizer_id,
                       a.text_source_kind, a.source_descriptor
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
                assertEquals(LibraryTextSourceKind.LOCAL_OCR.name, cursor.getString(6))
                assertTrue(cursor.isNull(7))
            }
            database.query("PRAGMA foreign_key_check").use { cursor ->
                assertEquals(0, cursor.count)
            }
        } finally {
            room.close()
        }
        val reopened = Room.databaseBuilder(
            context,
            LibraryDatabase::class.java,
            MIGRATION_DATABASE_NAME,
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .allowMainThreadQueries()
            .build()
        try {
            reopened.openHelper.readableDatabase.query("PRAGMA user_version").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(4, cursor.getInt(0))
            }
            reopened.openHelper.readableDatabase.query(
                "SELECT COUNT(*) FROM library_documents WHERE document_id='document-1'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
        } finally {
            reopened.close()
        }
    }

    @Test
    fun migrationHistoricalVersionTwoToFourPreservesRepresentativeDataAndReopens() {
        val name = HISTORICAL_V2_MIGRATION_DATABASE_NAME
        HistoricalV2DatabaseFixture.create(context, name)

        SQLiteDatabase.openDatabase(
            context.getDatabasePath(name).absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use { historical ->
            assertEquals(2, historical.version)
            historical.rawQuery("PRAGMA table_info(`library_documents`)", null).use { cursor ->
                val columns = buildList {
                    while (cursor.moveToNext()) add(cursor.getString(1))
                }
                assertEquals(HistoricalV2DatabaseFixture.DOCUMENT_COLUMNS, columns)
            }
            historical.rawQuery("PRAGMA index_list(`library_documents`)", null).use { cursor ->
                val indexes = buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(1))
                }
                assertEquals(HistoricalV2DatabaseFixture.DOCUMENT_INDEXES, indexes)
                assertFalse(HistoricalV2DatabaseFixture.REQUIRED_V3_COMPOSITE_INDEX in indexes)
            }
            historical.rawQuery(
                "SELECT identity_hash FROM room_master_table WHERE id = 42",
                null,
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(HistoricalV2DatabaseFixture.IDENTITY_HASH, cursor.getString(0))
            }
        }

        fun openV4() = Room.databaseBuilder(context, LibraryDatabase::class.java, name)
            .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
            .allowMainThreadQueries()
            .build()

        val migrated = openV4()
        try {
            val room = migrated
            val db = room.openHelper.writableDatabase
            db.query("SELECT library_revision, modified_at FROM library_metadata").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(17L, cursor.getLong(0))
                assertEquals(99L, cursor.getLong(1))
            }
            assertRequiredCompositeIndex(db)
            db.query(
                """
                SELECT d.row_id, d.document_id, d.title, d.created_at, d.modified_at,
                       d.page_count, d.folder_id, d.thumbnail_path,
                       f.parent_folder_id, f.parent_scope
                FROM library_documents AS d
                JOIN library_folders AS f ON f.folder_id = d.folder_id
                ORDER BY d.row_id
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(7L, cursor.getLong(0))
                assertEquals("document-primary", cursor.getString(1))
                assertEquals("Renamed Quarterly Report", cursor.getString(2))
                assertEquals(10L, cursor.getLong(3))
                assertEquals(20L, cursor.getLong(4))
                assertEquals(3, cursor.getInt(5))
                assertEquals("folder-child", cursor.getString(6))
                assertEquals("document-primary/revisions/rev-2/thumbnail.jpg", cursor.getString(7))
                assertEquals("folder-root", cursor.getString(8))
                assertEquals("folder-root", cursor.getString(9))
                assertTrue(cursor.moveToNext())
                assertEquals(8L, cursor.getLong(0))
                assertEquals("document-secondary", cursor.getString(1))
                assertEquals("Secondary Receipt", cursor.getString(2))
                assertEquals(1, cursor.getInt(5))
                assertEquals("folder-root", cursor.getString(6))
                assertTrue(cursor.isNull(8))
                assertEquals("", cursor.getString(9))
                assertFalse(cursor.moveToNext())
            }
            db.query(
                "SELECT page_id, page_position FROM library_pages " +
                    "WHERE document_id='document-primary' ORDER BY page_position",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("page-primary-0", cursor.getString(0))
                assertEquals(0, cursor.getInt(1))
                assertTrue(cursor.moveToNext())
                assertEquals("page-primary-1", cursor.getString(0))
                assertEquals(1, cursor.getInt(1))
                assertTrue(cursor.moveToNext())
                assertEquals("page-primary-2", cursor.getString(0))
                assertEquals(2, cursor.getInt(1))
                assertFalse(cursor.moveToNext())
            }
            db.query(
                "SELECT active_ocr_artifact_revision, ocr_state_revision, ocr_error " +
                    "FROM library_pages WHERE page_id='page-primary-0'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1L, cursor.getLong(0))
                assertEquals(1L, cursor.getLong(1))
                assertTrue(cursor.isNull(2))
            }
            db.query(
                "SELECT active_ocr_artifact_revision, ocr_state_revision, ocr_error " +
                    "FROM library_pages WHERE page_id='page-primary-1'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.isNull(0))
                assertEquals(0L, cursor.getLong(1))
                assertEquals("SAFE_FAILURE", cursor.getString(2))
            }
            db.query(
                "SELECT page_id, raw_text, verification_state, actual_script, recognizer_id, " +
                    "text_source_kind, source_descriptor " +
                    "FROM library_page_ocr_artifacts ORDER BY page_id",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("page-primary-0", cursor.getString(0))
                assertEquals("quarterly migration token", cursor.getString(1))
                assertEquals(LibraryOcrArtifactVerification.LEGACY_UNVERIFIED.name, cursor.getString(2))
                assertEquals("LATIN", cursor.getString(3))
                assertEquals(LEGACY_OCR_RECOGNIZER_ID, cursor.getString(4))
                assertEquals(LibraryTextSourceKind.LOCAL_OCR.name, cursor.getString(5))
                assertTrue(cursor.isNull(6))
                assertTrue(cursor.moveToNext())
                assertEquals("page-primary-2", cursor.getString(0))
                assertEquals("second legacy text", cursor.getString(1))
                assertEquals(LibraryOcrArtifactVerification.LEGACY_UNVERIFIED.name, cursor.getString(2))
                assertEquals(LibraryTextSourceKind.LOCAL_OCR.name, cursor.getString(5))
                assertTrue(cursor.isNull(6))
                assertFalse(cursor.moveToNext())
            }
            db.query(
                "SELECT page_id FROM library_page_search_v3 " +
                    "WHERE library_page_search_v3 MATCH 'migration*'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("page-primary-0", cursor.getString(0))
                assertFalse(cursor.moveToNext())
            }
            db.query(
                "SELECT document_id FROM library_document_search_v3 " +
                    "WHERE library_document_search_v3 MATCH 'Quarterly*'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("document-primary", cursor.getString(0))
                assertFalse(cursor.moveToNext())
            }
            db.query(
                "SELECT asset_id, document_id, role, relative_path, content_type, byte_count, " +
                    "sha256, matches_current_revision FROM library_source_assets",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("asset-pdf", cursor.getString(0))
                assertEquals("document-primary", cursor.getString(1))
                assertEquals("ORIGINAL_DOCUMENT", cursor.getString(2))
                assertEquals("document-primary/sources/original.pdf", cursor.getString(3))
                assertEquals("application/pdf", cursor.getString(4))
                assertEquals(4096L, cursor.getLong(5))
                assertEquals("source-pdf-sha", cursor.getString(6))
                assertEquals(1, cursor.getInt(7))
                assertFalse(cursor.moveToNext())
            }
            db.query(
                "SELECT operation_id, phase, imported_document_count FROM library_data_operations",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("operation-1", cursor.getString(0))
                assertEquals("COMPLETED", cursor.getString(1))
                assertEquals(1, cursor.getInt(2))
                assertFalse(cursor.moveToNext())
            }
            db.query("SELECT COUNT(*) FROM library_data_operation_items").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
            db.query("SELECT COUNT(*) FROM library_data_operation_sources").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
            db.query(
                "SELECT COUNT(*) FROM library_pages AS p " +
                    "LEFT JOIN library_documents AS d ON d.document_id = p.document_id " +
                    "WHERE d.document_id IS NULL",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            db.query(
                "SELECT COUNT(*) FROM library_source_assets AS a " +
                    "LEFT JOIN library_documents AS d ON d.document_id = a.document_id " +
                    "WHERE d.document_id IS NULL",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            db.query("PRAGMA foreign_key_check").use { cursor -> assertEquals(0, cursor.count) }
            assertHistoricalProvenanceUnknown(db)
        } finally {
            migrated.close()
        }
        val reopened = openV4()
        try {
            val room = reopened
            room.openHelper.readableDatabase.query("PRAGMA user_version").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(4, cursor.getInt(0))
            }
            room.openHelper.readableDatabase.query(
                "SELECT COUNT(*) FROM library_documents",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(2, cursor.getInt(0))
            }
            room.openHelper.readableDatabase.query(
                "SELECT COUNT(*) FROM library_pages WHERE document_id='document-primary'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(3, cursor.getInt(0))
            }
            room.openHelper.readableDatabase.query(
                "SELECT page_id FROM library_page_search_v3 " +
                    "WHERE library_page_search_v3 MATCH 'quarterly*'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("page-primary-0", cursor.getString(0))
            }
        } finally {
            reopened.close()
        }
        context.deleteDatabase(name)
    }

    @Test
    fun migrationFrozenVersionThreeToFourPreservesKnownTimesAndAddsOnlyUnknownProvenance() {
        val name = HISTORICAL_V3_MIGRATION_DATABASE_NAME
        createDatabaseFromFrozenSchema(name, version = 3).apply {
            execSQL(
                """
                INSERT INTO library_documents
                    (row_id, document_id, title, created_at, modified_at, page_count,
                     folder_id, thumbnail_path, ocr_status, library_state,
                     content_hash_version, content_sha256, content_byte_count,
                     source_modified_at, imported_at, content_revision, ocr_script_preference)
                VALUES (77, 'v3-document', 'Frozen v3', 100, 200, 1,
                        NULL, NULL, 'INDEXED', 'ACTIVE', 1,
                        'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                        512, 222, 333, 4, 'LATIN')
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO library_pages
                    (page_id, document_id, page_position, relative_path, content_type,
                     source_category, width, height, source_byte_count, rotation_degrees,
                     filter_name, ocr_text, ocr_error, content_sha256, visual_revision,
                     ocr_state_revision, active_ocr_artifact_revision)
                VALUES ('v3-page', 'v3-document', 0, 'v3/page.jpg', 'image/jpeg',
                        'TEST', 100, 200, 512, 0, 'ORIGINAL', 'frozen searchable', NULL,
                        'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                        2, 3, 5)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO library_page_ocr_artifacts
                    (page_id, artifact_revision, captured_document_content_revision,
                     captured_page_visual_revision, input_fingerprint_version,
                     input_fingerprint, verification_state, content_sha256,
                     rotation_degrees, filter_name, upright_width, upright_height,
                     coordinate_system_version, transform_version, actual_script,
                     recognizer_id, pipeline_version, client_version, delivery,
                     recognized_at, raw_text)
                VALUES ('v3-page', 5, 4, 2, 1, 'frozen-fingerprint',
                        'CURRENT_VERIFIED',
                        'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                        0, 'ORIGINAL', 100, 200, 1, 1, 'LATIN', 'FROZEN_TEST',
                        'v3', NULL, 'BUNDLED', 150, 'frozen searchable')
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO library_document_search_content_v3
                    (rowid, document_id, title, folder_path, auxiliary_terms)
                VALUES (77, 'v3-document', 'Frozen v3', '', '')
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO library_page_search_content_v3
                    (rowid, page_id, effective_text, auxiliary_terms)
                VALUES (88, 'v3-page', 'frozen searchable', '')
                """.trimIndent(),
            )
            execSQL("INSERT INTO library_document_search_v3(library_document_search_v3) VALUES('rebuild')")
            execSQL("INSERT INTO library_page_search_v3(library_page_search_v3) VALUES('rebuild')")
            close()
        }

        val migrated = Room.databaseBuilder(context, LibraryDatabase::class.java, name)
            .addMigrations(MIGRATION_3_4)
            .allowMainThreadQueries()
            .build()
        try {
            val db = migrated.openHelper.writableDatabase
            db.query(
                """
                SELECT source_modified_at, imported_at, content_revision,
                       document_date_epoch_day, document_date_provenance,
                       document_date_confidence, original_created_at,
                       original_created_at_provenance, source_modified_at_provenance,
                       source_provenance_kind, source_provenance_label
                FROM library_documents WHERE document_id='v3-document'
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(222L, cursor.getLong(0))
                assertEquals(333L, cursor.getLong(1))
                assertEquals(4L, cursor.getLong(2))
                (3..10).forEach { column -> assertTrue(cursor.isNull(column)) }
            }
            db.query(
                """
                SELECT raw_text, text_source_kind, source_descriptor
                FROM library_page_ocr_artifacts
                WHERE page_id='v3-page' AND artifact_revision=5
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("frozen searchable", cursor.getString(0))
                assertEquals(LibraryTextSourceKind.LOCAL_OCR.name, cursor.getString(1))
                assertTrue(cursor.isNull(2))
            }
            db.query(
                "SELECT page_id FROM library_page_search_v3 " +
                    "WHERE library_page_search_v3 MATCH 'frozen*'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("v3-page", cursor.getString(0))
            }
            db.query("PRAGMA foreign_key_check").use { cursor -> assertEquals(0, cursor.count) }
        } finally {
            migrated.close()
        }
    }

    private fun createDatabaseFromFrozenSchema(
        name: String,
        version: Int,
    ): SQLiteDatabase {
        context.deleteDatabase(name)
        val root = InstrumentationRegistry.getInstrumentation().context.assets.open(
            "org.synapseworks.pageharbor.library.LibraryDatabase/$version.json",
        ).bufferedReader().use { reader -> JSONObject(reader.readText()) }
        val schema = root.getJSONObject("database")
        require(schema.getInt("version") == version)
        return SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).apply {
            execSQL("PRAGMA foreign_keys=OFF")
            val entities = schema.getJSONArray("entities")
            for (entityIndex in 0 until entities.length()) {
                val entity = entities.getJSONObject(entityIndex)
                val tableName = entity.getString("tableName")
                execSQL(entity.getString("createSql").replace("${'$'}{TABLE_NAME}", tableName))
                val indices = entity.optJSONArray("indices")
                if (indices != null) {
                    for (indexPosition in 0 until indices.length()) {
                        execSQL(
                            indices.getJSONObject(indexPosition)
                                .getString("createSql")
                                .replace("${'$'}{TABLE_NAME}", tableName),
                        )
                    }
                }
            }
            val setupQueries = schema.getJSONArray("setupQueries")
            for (queryIndex in 0 until setupQueries.length()) {
                execSQL(setupQueries.getString(queryIndex))
            }
            this.version = version
            execSQL("PRAGMA foreign_keys=ON")
        }
    }

    private fun assertHistoricalProvenanceUnknown(database: SupportSQLiteDatabase) {
        database.query(
            """
            SELECT COUNT(*) FROM library_documents
            WHERE document_date_epoch_day IS NOT NULL
               OR document_date_provenance IS NOT NULL
               OR document_date_confidence IS NOT NULL
               OR original_created_at IS NOT NULL
               OR original_created_at_provenance IS NOT NULL
               OR source_modified_at_provenance IS NOT NULL
               OR source_provenance_kind IS NOT NULL
               OR source_provenance_label IS NOT NULL
            """.trimIndent(),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
    }

    private fun assertRequiredCompositeIndex(database: SupportSQLiteDatabase) {
        database.query("PRAGMA index_list(`library_documents`)").use { cursor ->
            val nameColumn = cursor.getColumnIndexOrThrow("name")
            val uniqueColumn = cursor.getColumnIndexOrThrow("unique")
            var found = false
            while (cursor.moveToNext()) {
                if (cursor.getString(nameColumn) == HistoricalV2DatabaseFixture.REQUIRED_V3_COMPOSITE_INDEX) {
                    assertEquals(0, cursor.getInt(uniqueColumn))
                    found = true
                }
            }
            assertTrue(found)
        }
        database.query(
            "PRAGMA index_info(`${HistoricalV2DatabaseFixture.REQUIRED_V3_COMPOSITE_INDEX}`)",
        ).use { cursor ->
            val columns = buildList {
                val nameColumn = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) add(cursor.getString(nameColumn))
            }
            assertEquals(
                listOf("library_state", "page_count", "content_byte_count", "row_id"),
                columns,
            )
        }
    }

    private companion object {
        const val MIGRATION_DATABASE_NAME = "library-migration-1-2-test.db"
        const val HISTORICAL_V2_MIGRATION_DATABASE_NAME = "library-historical-migration-2-3-test.db"
        const val HISTORICAL_V3_MIGRATION_DATABASE_NAME = "library-historical-migration-3-4-test.db"
        const val VERSION_ONE_IDENTITY_HASH = "1e51e807e9dfeb571dde417386b439a4"
    }
}
