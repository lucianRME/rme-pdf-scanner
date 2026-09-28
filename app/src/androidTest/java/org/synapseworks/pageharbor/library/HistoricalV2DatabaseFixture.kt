package org.synapseworks.pageharbor.library

import android.content.Context
import android.database.sqlite.SQLiteDatabase

/**
 * Creates the exact Room v2 schema exported by the frozen v1.5 source at
 * d8e2b2dba4a2f4ce794dfdd26beb85ea0395afaa.
 *
 * This intentionally does not invoke the current MIGRATION_1_2: migrations may evolve to keep
 * older upgrade chains valid, while this fixture must continue to represent the database that
 * v1.5 actually shipped internally.
 */
internal object HistoricalV2DatabaseFixture {
    const val IDENTITY_HASH = "5a70565e87cff03309a46ac3ab34decf"

    const val REQUIRED_V3_COMPOSITE_INDEX =
        "index_library_documents_library_state_page_count_content_byte_count_row_id"

    val DOCUMENT_COLUMNS = listOf(
        "row_id",
        "document_id",
        "title",
        "created_at",
        "modified_at",
        "page_count",
        "folder_id",
        "thumbnail_path",
        "ocr_status",
        "library_state",
        "pending_operation_id",
        "content_hash_version",
        "content_sha256",
        "content_byte_count",
        "source_modified_at",
        "imported_at",
    )

    val DOCUMENT_INDEXES = setOf(
        "index_library_documents_document_id",
        "index_library_documents_folder_id",
        "index_library_documents_modified_at",
        "index_library_documents_pending_operation_id",
        "index_library_documents_library_state_modified_at",
        "index_library_documents_library_state_folder_id_modified_at",
        "index_library_documents_library_state_content_hash_version_content_sha256",
    )

    fun create(context: Context, name: String) {
        context.deleteDatabase(name)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { database ->
            database.execSQL("PRAGMA foreign_keys = ON")
            database.beginTransaction()
            try {
                createSchema(database)
                seedRepresentativeData(database)
                database.version = 2
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
        }
    }

    private fun createSchema(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `library_folders` (
                `folder_id` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `normalized_name` TEXT NOT NULL,
                `created_at` INTEGER NOT NULL,
                `modified_at` INTEGER NOT NULL,
                `parent_folder_id` TEXT,
                `parent_scope` TEXT NOT NULL DEFAULT '',
                PRIMARY KEY(`folder_id`),
                FOREIGN KEY(`parent_folder_id`) REFERENCES `library_folders`(`folder_id`)
                    ON UPDATE NO ACTION ON DELETE SET NULL
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `library_metadata` (
                `metadata_id` TEXT NOT NULL,
                `library_revision` INTEGER NOT NULL,
                `modified_at` INTEGER NOT NULL,
                PRIMARY KEY(`metadata_id`)
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `library_data_operations` (
                `operation_id` TEXT NOT NULL,
                `operation_type` TEXT NOT NULL,
                `phase` TEXT NOT NULL,
                `source_kind` TEXT NOT NULL,
                `source_root_uri` TEXT,
                `source_grant_flags` INTEGER NOT NULL,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                `discovered_item_count` INTEGER NOT NULL,
                `planned_document_count` INTEGER NOT NULL,
                `prepared_document_count` INTEGER NOT NULL,
                `imported_document_count` INTEGER NOT NULL,
                `skipped_duplicate_count` INTEGER NOT NULL,
                `failed_item_count` INTEGER NOT NULL,
                `total_source_bytes` INTEGER,
                `processed_source_bytes` INTEGER NOT NULL,
                `cancel_requested` INTEGER NOT NULL,
                `terminal_error_code` TEXT,
                PRIMARY KEY(`operation_id`)
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `library_documents` (
                `row_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `document_id` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `created_at` INTEGER NOT NULL,
                `modified_at` INTEGER NOT NULL,
                `page_count` INTEGER NOT NULL,
                `folder_id` TEXT,
                `thumbnail_path` TEXT,
                `ocr_status` TEXT NOT NULL,
                `library_state` TEXT NOT NULL DEFAULT 'ACTIVE',
                `pending_operation_id` TEXT,
                `content_hash_version` INTEGER,
                `content_sha256` TEXT,
                `content_byte_count` INTEGER,
                `source_modified_at` INTEGER,
                `imported_at` INTEGER,
                FOREIGN KEY(`folder_id`) REFERENCES `library_folders`(`folder_id`)
                    ON UPDATE NO ACTION ON DELETE SET NULL,
                FOREIGN KEY(`pending_operation_id`) REFERENCES `library_data_operations`(`operation_id`)
                    ON UPDATE NO ACTION ON DELETE NO ACTION
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `library_pages` (
                `page_id` TEXT NOT NULL,
                `document_id` TEXT NOT NULL,
                `page_position` INTEGER NOT NULL,
                `relative_path` TEXT NOT NULL,
                `content_type` TEXT NOT NULL,
                `source_category` TEXT NOT NULL,
                `width` INTEGER,
                `height` INTEGER,
                `source_byte_count` INTEGER,
                `rotation_degrees` INTEGER NOT NULL,
                `filter_name` TEXT NOT NULL,
                `ocr_text` TEXT,
                `ocr_error` TEXT,
                `content_sha256` TEXT,
                PRIMARY KEY(`page_id`),
                FOREIGN KEY(`document_id`) REFERENCES `library_documents`(`document_id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `library_source_assets` (
                `asset_id` TEXT NOT NULL,
                `document_id` TEXT NOT NULL,
                `role` TEXT NOT NULL,
                `relative_path` TEXT NOT NULL,
                `content_type` TEXT NOT NULL,
                `byte_count` INTEGER NOT NULL,
                `sha256` TEXT NOT NULL,
                `source_modified_at` INTEGER,
                `created_at` INTEGER NOT NULL,
                `matches_current_revision` INTEGER NOT NULL,
                PRIMARY KEY(`asset_id`),
                FOREIGN KEY(`document_id`) REFERENCES `library_documents`(`document_id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `library_data_operation_items` (
                `item_id` TEXT NOT NULL,
                `operation_id` TEXT NOT NULL,
                `ordinal` INTEGER NOT NULL,
                `item_state` TEXT NOT NULL,
                `target_document_id` TEXT NOT NULL,
                `target_revision_id` TEXT,
                `proposed_title` TEXT NOT NULL,
                `proposed_folder_path` TEXT,
                `source_modified_at` INTEGER,
                `source_byte_count` INTEGER,
                `source_page_count` INTEGER,
                `logical_hash_version` INTEGER,
                `logical_sha256` TEXT,
                `duplicate_kind` TEXT NOT NULL,
                `duplicate_document_id` TEXT,
                `duplicate_decision` TEXT NOT NULL,
                `failure_code` TEXT,
                PRIMARY KEY(`item_id`),
                FOREIGN KEY(`operation_id`) REFERENCES `library_data_operations`(`operation_id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `library_data_operation_sources` (
                `source_id` TEXT NOT NULL,
                `item_id` TEXT NOT NULL,
                `source_position` INTEGER NOT NULL,
                `source_uri` TEXT,
                `relative_source_path` TEXT,
                `display_name` TEXT,
                `declared_mime_type` TEXT,
                `detected_mime_type` TEXT,
                `source_byte_count` INTEGER,
                `source_modified_at` INTEGER,
                `source_sha256` TEXT,
                `source_state` TEXT NOT NULL,
                `failure_code` TEXT,
                PRIMARY KEY(`source_id`),
                FOREIGN KEY(`item_id`) REFERENCES `library_data_operation_items`(`item_id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        database.execSQL(
            "CREATE VIRTUAL TABLE IF NOT EXISTS `library_document_search` " +
                "USING FTS4(`title` TEXT NOT NULL, `ocr_text` TEXT NOT NULL)",
        )

        listOf(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_library_folders_parent_scope_normalized_name` ON `library_folders` (`parent_scope`, `normalized_name`)",
            "CREATE INDEX IF NOT EXISTS `index_library_folders_parent_folder_id` ON `library_folders` (`parent_folder_id`)",
            "CREATE INDEX IF NOT EXISTS `index_library_data_operations_phase_updated_at` ON `library_data_operations` (`phase`, `updated_at`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_library_documents_document_id` ON `library_documents` (`document_id`)",
            "CREATE INDEX IF NOT EXISTS `index_library_documents_folder_id` ON `library_documents` (`folder_id`)",
            "CREATE INDEX IF NOT EXISTS `index_library_documents_modified_at` ON `library_documents` (`modified_at`)",
            "CREATE INDEX IF NOT EXISTS `index_library_documents_pending_operation_id` ON `library_documents` (`pending_operation_id`)",
            "CREATE INDEX IF NOT EXISTS `index_library_documents_library_state_modified_at` ON `library_documents` (`library_state`, `modified_at`)",
            "CREATE INDEX IF NOT EXISTS `index_library_documents_library_state_folder_id_modified_at` ON `library_documents` (`library_state`, `folder_id`, `modified_at`)",
            "CREATE INDEX IF NOT EXISTS `index_library_documents_library_state_content_hash_version_content_sha256` ON `library_documents` (`library_state`, `content_hash_version`, `content_sha256`)",
            "CREATE INDEX IF NOT EXISTS `index_library_pages_document_id` ON `library_pages` (`document_id`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_library_pages_document_id_page_position` ON `library_pages` (`document_id`, `page_position`)",
            "CREATE INDEX IF NOT EXISTS `index_library_source_assets_document_id_role` ON `library_source_assets` (`document_id`, `role`)",
            "CREATE INDEX IF NOT EXISTS `index_library_source_assets_sha256_byte_count` ON `library_source_assets` (`sha256`, `byte_count`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_library_data_operation_items_operation_id_ordinal` ON `library_data_operation_items` (`operation_id`, `ordinal`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_library_data_operation_items_operation_id_target_document_id` ON `library_data_operation_items` (`operation_id`, `target_document_id`)",
            "CREATE INDEX IF NOT EXISTS `index_library_data_operation_items_operation_id_item_state` ON `library_data_operation_items` (`operation_id`, `item_state`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_library_data_operation_sources_item_id_source_position` ON `library_data_operation_sources` (`item_id`, `source_position`)",
        ).forEach(database::execSQL)

        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `room_master_table` " +
                "(`id` INTEGER PRIMARY KEY, `identity_hash` TEXT)",
        )
        database.execSQL(
            "INSERT OR REPLACE INTO `room_master_table` (`id`, `identity_hash`) VALUES(42, ?)",
            arrayOf(IDENTITY_HASH),
        )
    }

    private fun seedRepresentativeData(database: SQLiteDatabase) {
        database.execSQL(
            "INSERT INTO `library_metadata` (`metadata_id`, `library_revision`, `modified_at`) " +
                "VALUES('library', 17, 99)",
        )
        database.execSQL(
            "INSERT INTO `library_folders` " +
                "(`folder_id`,`name`,`normalized_name`,`created_at`,`modified_at`,`parent_folder_id`,`parent_scope`) " +
                "VALUES('folder-root','Projects','projects',1,2,NULL,'')",
        )
        database.execSQL(
            "INSERT INTO `library_folders` " +
                "(`folder_id`,`name`,`normalized_name`,`created_at`,`modified_at`,`parent_folder_id`,`parent_scope`) " +
                "VALUES('folder-child','Archive','archive',3,4,'folder-root','folder-root')",
        )
        database.execSQL(
            """
            INSERT INTO `library_data_operations` (
                `operation_id`,`operation_type`,`phase`,`source_kind`,`source_root_uri`,
                `source_grant_flags`,`created_at`,`updated_at`,`discovered_item_count`,
                `planned_document_count`,`prepared_document_count`,`imported_document_count`,
                `skipped_duplicate_count`,`failed_item_count`,`total_source_bytes`,
                `processed_source_bytes`,`cancel_requested`,`terminal_error_code`
            ) VALUES (
                'operation-1','IMPORT','COMPLETED','FILES',NULL,
                0,5,6,1,
                1,1,1,
                0,0,4096,
                4096,0,NULL
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO `library_documents` (
                `row_id`,`document_id`,`title`,`created_at`,`modified_at`,`page_count`,
                `folder_id`,`thumbnail_path`,`ocr_status`,`library_state`,`pending_operation_id`,
                `content_hash_version`,`content_sha256`,`content_byte_count`,
                `source_modified_at`,`imported_at`
            ) VALUES (
                7,'document-primary','Renamed Quarterly Report',10,20,3,
                'folder-child','document-primary/revisions/rev-2/thumbnail.jpg','PARTIAL','ACTIVE',NULL,
                1,'document-primary-sha',3000,
                9,10
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO `library_documents` (
                `row_id`,`document_id`,`title`,`created_at`,`modified_at`,`page_count`,
                `folder_id`,`thumbnail_path`,`ocr_status`,`library_state`,`pending_operation_id`,
                `content_hash_version`,`content_sha256`,`content_byte_count`,
                `source_modified_at`,`imported_at`
            ) VALUES (
                8,'document-secondary','Secondary Receipt',11,21,1,
                'folder-root','document-secondary/revisions/rev-1/thumbnail.jpg','NOT_INDEXED','ACTIVE',NULL,
                1,'document-secondary-sha',1000,
                10,11
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO `library_pages` VALUES
                ('page-primary-0','document-primary',0,'document-primary/revisions/rev-2/page-0.jpg',
                 'image/jpeg','RENDERED_PDF_PAGE',1200,1600,1000,0,'ORIGINAL',
                 'quarterly migration token',NULL,'page-primary-0-sha'),
                ('page-primary-1','document-primary',1,'document-primary/revisions/rev-2/page-1.jpg',
                 'image/jpeg','RENDERED_PDF_PAGE',1200,1600,1001,90,'GRAYSCALE',
                 NULL,'SAFE_FAILURE','page-primary-1-sha'),
                ('page-primary-2','document-primary',2,'document-primary/revisions/rev-2/page-2.jpg',
                 'image/jpeg','SELECTED_IMAGE',900,1200,999,0,'ORIGINAL',
                 'second legacy text',NULL,'page-primary-2-sha'),
                ('page-secondary-0','document-secondary',0,'document-secondary/revisions/rev-1/page-0.jpg',
                 'image/jpeg','SELECTED_IMAGE',800,1000,1000,0,'ORIGINAL',
                 NULL,NULL,'page-secondary-0-sha')
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO `library_source_assets` (
                `asset_id`,`document_id`,`role`,`relative_path`,`content_type`,`byte_count`,
                `sha256`,`source_modified_at`,`created_at`,`matches_current_revision`
            ) VALUES (
                'asset-pdf','document-primary','ORIGINAL_DOCUMENT',
                'document-primary/sources/original.pdf','application/pdf',4096,
                'source-pdf-sha',9,10,1
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO `library_data_operation_items` (
                `item_id`,`operation_id`,`ordinal`,`item_state`,`target_document_id`,
                `target_revision_id`,`proposed_title`,`proposed_folder_path`,`source_modified_at`,
                `source_byte_count`,`source_page_count`,`logical_hash_version`,`logical_sha256`,
                `duplicate_kind`,`duplicate_document_id`,`duplicate_decision`,`failure_code`
            ) VALUES (
                'item-1','operation-1',0,'IMPORTED','document-primary',
                'rev-2','Quarterly Report','Projects/Archive',9,
                4096,3,1,'logical-sha',
                'NONE',NULL,'IMPORT',NULL
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO `library_data_operation_sources` (
                `source_id`,`item_id`,`source_position`,`source_uri`,`relative_source_path`,
                `display_name`,`declared_mime_type`,`detected_mime_type`,`source_byte_count`,
                `source_modified_at`,`source_sha256`,`source_state`,`failure_code`
            ) VALUES (
                'source-1','item-1',0,NULL,'incoming/original.pdf',
                'quarterly.pdf','application/pdf','application/pdf',4096,
                9,'source-pdf-sha','COPIED',NULL
            )
            """.trimIndent(),
        )
        database.execSQL(
            "INSERT INTO `library_document_search` (`rowid`,`title`,`ocr_text`) " +
                "VALUES(7,'Renamed Quarterly Report','quarterly migration token second legacy text')",
        )
        database.execSQL(
            "INSERT INTO `library_document_search` (`rowid`,`title`,`ocr_text`) " +
                "VALUES(8,'Secondary Receipt','')",
        )
    }
}
