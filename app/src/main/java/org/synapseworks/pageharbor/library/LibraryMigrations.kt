package org.synapseworks.pageharbor.library

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
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
            INSERT OR IGNORE INTO `library_metadata`
                (`metadata_id`, `library_revision`, `modified_at`)
            VALUES ('$LIBRARY_METADATA_ID', 0, 0)
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
            CREATE INDEX IF NOT EXISTS `index_library_data_operations_phase_updated_at`
            ON `library_data_operations` (`phase`, `updated_at`)
            """.trimIndent(),
        )

        database.execSQL(
            """
            ALTER TABLE `library_folders`
            ADD COLUMN `parent_folder_id` TEXT
            REFERENCES `library_folders`(`folder_id`)
            ON UPDATE NO ACTION ON DELETE SET NULL
            """.trimIndent(),
        )
        database.execSQL(
            """
            ALTER TABLE `library_folders`
            ADD COLUMN `parent_scope` TEXT NOT NULL DEFAULT ''
            """.trimIndent(),
        )
        database.execSQL("DROP INDEX IF EXISTS `index_library_folders_normalized_name`")
        database.execSQL(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS `index_library_folders_parent_scope_normalized_name`
            ON `library_folders` (`parent_scope`, `normalized_name`)
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_library_folders_parent_folder_id`
            ON `library_folders` (`parent_folder_id`)
            """.trimIndent(),
        )

        database.execSQL(
            """
            ALTER TABLE `library_documents`
            ADD COLUMN `library_state` TEXT NOT NULL DEFAULT 'ACTIVE'
            """.trimIndent(),
        )
        database.execSQL(
            """
            ALTER TABLE `library_documents`
            ADD COLUMN `pending_operation_id` TEXT
            REFERENCES `library_data_operations`(`operation_id`)
            ON UPDATE NO ACTION ON DELETE NO ACTION
            """.trimIndent(),
        )
        database.execSQL("ALTER TABLE `library_documents` ADD COLUMN `content_hash_version` INTEGER")
        database.execSQL("ALTER TABLE `library_documents` ADD COLUMN `content_sha256` TEXT")
        database.execSQL("ALTER TABLE `library_documents` ADD COLUMN `content_byte_count` INTEGER")
        database.execSQL("ALTER TABLE `library_documents` ADD COLUMN `source_modified_at` INTEGER")
        database.execSQL("ALTER TABLE `library_documents` ADD COLUMN `imported_at` INTEGER")
        database.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_library_documents_pending_operation_id`
            ON `library_documents` (`pending_operation_id`)
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_library_documents_library_state_modified_at`
            ON `library_documents` (`library_state`, `modified_at`)
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_library_documents_library_state_folder_id_modified_at`
            ON `library_documents` (`library_state`, `folder_id`, `modified_at`)
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_library_documents_library_state_content_hash_version_content_sha256`
            ON `library_documents` (`library_state`, `content_hash_version`, `content_sha256`)
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_library_documents_library_state_page_count_content_byte_count_row_id`
            ON `library_documents` (`library_state`, `page_count`, `content_byte_count`, `row_id`)
            """.trimIndent(),
        )

        database.execSQL("ALTER TABLE `library_pages` ADD COLUMN `content_sha256` TEXT")

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
            CREATE INDEX IF NOT EXISTS `index_library_source_assets_document_id_role`
            ON `library_source_assets` (`document_id`, `role`)
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_library_source_assets_sha256_byte_count`
            ON `library_source_assets` (`sha256`, `byte_count`)
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
            CREATE UNIQUE INDEX IF NOT EXISTS `index_library_data_operation_items_operation_id_ordinal`
            ON `library_data_operation_items` (`operation_id`, `ordinal`)
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS `index_library_data_operation_items_operation_id_target_document_id`
            ON `library_data_operation_items` (`operation_id`, `target_document_id`)
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_library_data_operation_items_operation_id_item_state`
            ON `library_data_operation_items` (`operation_id`, `item_state`)
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
            """
            CREATE UNIQUE INDEX IF NOT EXISTS `index_library_data_operation_sources_item_id_source_position`
            ON `library_data_operation_sources` (`item_id`, `source_position`)
            """.trimIndent(),
        )
    }
}

internal val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `library_pending_restore_folders` (
                `folder_id` TEXT NOT NULL,
                `operation_id` TEXT NOT NULL,
                `ordinal` INTEGER NOT NULL,
                `name` TEXT NOT NULL,
                `normalized_name` TEXT NOT NULL,
                `created_at` INTEGER NOT NULL,
                `modified_at` INTEGER NOT NULL,
                `parent_folder_id` TEXT,
                `parent_scope` TEXT NOT NULL,
                PRIMARY KEY(`folder_id`),
                FOREIGN KEY(`operation_id`) REFERENCES `library_data_operations`(`operation_id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        database.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_library_pending_restore_folders_operation_id_ordinal` " +
                "ON `library_pending_restore_folders` (`operation_id`, `ordinal`)",
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_library_pending_restore_folders_operation_id_parent_folder_id` " +
                "ON `library_pending_restore_folders` (`operation_id`, `parent_folder_id`)",
        )
        database.execSQL("ALTER TABLE `library_documents` ADD COLUMN `content_revision` INTEGER NOT NULL DEFAULT 0")
        database.execSQL("ALTER TABLE `library_documents` ADD COLUMN `ocr_script_preference` TEXT")
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_library_documents_library_state_row_id` " +
                "ON `library_documents` (`library_state`, `row_id`)",
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS " +
                "`index_library_documents_library_state_page_count_content_byte_count_row_id` " +
                "ON `library_documents` " +
                "(`library_state`, `page_count`, `content_byte_count`, `row_id`)",
        )
        database.execSQL("ALTER TABLE `library_pages` ADD COLUMN `visual_revision` INTEGER NOT NULL DEFAULT 0")
        database.execSQL("ALTER TABLE `library_pages` ADD COLUMN `ocr_state_revision` INTEGER NOT NULL DEFAULT 0")
        database.execSQL("ALTER TABLE `library_pages` ADD COLUMN `active_ocr_artifact_revision` INTEGER")

        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `library_page_ocr_artifacts` (
                `page_id` TEXT NOT NULL,
                `artifact_revision` INTEGER NOT NULL,
                `captured_document_content_revision` INTEGER,
                `captured_page_visual_revision` INTEGER,
                `input_fingerprint_version` INTEGER,
                `input_fingerprint` TEXT,
                `verification_state` TEXT NOT NULL,
                `content_sha256` TEXT,
                `rotation_degrees` INTEGER,
                `filter_name` TEXT,
                `upright_width` INTEGER,
                `upright_height` INTEGER,
                `coordinate_system_version` INTEGER,
                `transform_version` INTEGER,
                `actual_script` TEXT,
                `recognizer_id` TEXT NOT NULL,
                `pipeline_version` TEXT,
                `client_version` TEXT,
                `delivery` TEXT,
                `recognized_at` INTEGER,
                `raw_text` TEXT NOT NULL,
                PRIMARY KEY(`page_id`, `artifact_revision`),
                FOREIGN KEY(`page_id`) REFERENCES `library_pages`(`page_id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO `library_page_ocr_artifacts` (
                `page_id`, `artifact_revision`, `captured_document_content_revision`,
                `captured_page_visual_revision`, `input_fingerprint_version`, `input_fingerprint`,
                `verification_state`, `content_sha256`, `rotation_degrees`, `filter_name`,
                `upright_width`, `upright_height`, `coordinate_system_version`, `transform_version`,
                `actual_script`, `recognizer_id`, `pipeline_version`, `client_version`, `delivery`,
                `recognized_at`, `raw_text`
            )
            SELECT `page_id`, 1, NULL, NULL, NULL, NULL,
                   'LEGACY_UNVERIFIED', NULL, NULL, NULL,
                   NULL, NULL, NULL, NULL,
                   'LATIN', '$LEGACY_OCR_RECOGNIZER_ID', NULL, NULL, NULL,
                   NULL, `ocr_text`
            FROM `library_pages`
            WHERE `ocr_text` IS NOT NULL
            """.trimIndent(),
        )
        database.execSQL(
            """
            UPDATE `library_pages`
            SET `active_ocr_artifact_revision` = 1,
                `ocr_state_revision` = 1
            WHERE `ocr_text` IS NOT NULL
            """.trimIndent(),
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_library_page_ocr_artifacts_page_id_verification_state` " +
                "ON `library_page_ocr_artifacts` (`page_id`, `verification_state`)",
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_library_page_ocr_artifacts_actual_script` " +
                "ON `library_page_ocr_artifacts` (`actual_script`)",
        )

        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `library_page_ocr_lines` (
                `page_id` TEXT NOT NULL,
                `artifact_revision` INTEGER NOT NULL,
                `line_ordinal` INTEGER NOT NULL,
                `raw_text` TEXT NOT NULL,
                `top_left_x` REAL NOT NULL, `top_left_y` REAL NOT NULL,
                `top_right_x` REAL NOT NULL, `top_right_y` REAL NOT NULL,
                `bottom_right_x` REAL NOT NULL, `bottom_right_y` REAL NOT NULL,
                `bottom_left_x` REAL NOT NULL, `bottom_left_y` REAL NOT NULL,
                `baseline_start_x` REAL NOT NULL, `baseline_start_y` REAL NOT NULL,
                `baseline_end_x` REAL NOT NULL, `baseline_end_y` REAL NOT NULL,
                `baseline_angle_degrees` REAL NOT NULL,
                `writing_orientation` TEXT,
                PRIMARY KEY(`page_id`, `artifact_revision`, `line_ordinal`),
                FOREIGN KEY(`page_id`, `artifact_revision`)
                    REFERENCES `library_page_ocr_artifacts`(`page_id`, `artifact_revision`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_library_page_ocr_lines_page_id_artifact_revision` " +
                "ON `library_page_ocr_lines` (`page_id`, `artifact_revision`)",
        )
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `library_page_ocr_corrections` (
                `page_id` TEXT NOT NULL,
                `base_artifact_revision` INTEGER NOT NULL,
                `corrected_text` TEXT NOT NULL,
                `corrected_at` INTEGER NOT NULL,
                `alignment_state` TEXT NOT NULL,
                PRIMARY KEY(`page_id`, `base_artifact_revision`),
                FOREIGN KEY(`page_id`) REFERENCES `library_pages`(`page_id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`page_id`, `base_artifact_revision`)
                    REFERENCES `library_page_ocr_artifacts`(`page_id`, `artifact_revision`)
                    ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED
            )
            """.trimIndent(),
        )
        database.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_library_page_ocr_corrections_page_id` " +
                "ON `library_page_ocr_corrections` (`page_id`)",
        )
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `library_page_ocr_correction_lines` (
                `page_id` TEXT NOT NULL,
                `base_artifact_revision` INTEGER NOT NULL,
                `line_ordinal` INTEGER NOT NULL,
                `corrected_text` TEXT NOT NULL,
                PRIMARY KEY(`page_id`, `base_artifact_revision`, `line_ordinal`),
                FOREIGN KEY(`page_id`, `base_artifact_revision`)
                    REFERENCES `library_page_ocr_corrections`(`page_id`, `base_artifact_revision`)
                    ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`page_id`, `base_artifact_revision`, `line_ordinal`)
                    REFERENCES `library_page_ocr_lines`(`page_id`, `artifact_revision`, `line_ordinal`)
                    ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED
            )
            """.trimIndent(),
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_library_page_ocr_correction_lines_page_id_base_artifact_revision` " +
                "ON `library_page_ocr_correction_lines` (`page_id`, `base_artifact_revision`)",
        )

        createSearchSchema(database)
        backfillSearchContent(database)
        database.execSQL(
            "INSERT INTO `library_document_search_v3`(`library_document_search_v3`) VALUES('rebuild')",
        )
        database.execSQL(
            "INSERT INTO `library_page_search_v3`(`library_page_search_v3`) VALUES('rebuild')",
        )
        assertV3Backfill(database)
        database.execSQL("DROP TABLE IF EXISTS `library_document_search`")
        createOcrBatchSchema(database)

        database.query("PRAGMA foreign_key_check").use { cursor ->
            check(!cursor.moveToFirst()) { "Foreign-key violation after library migration 2 to 3" }
        }
    }
}

private fun assertV3Backfill(database: SupportSQLiteDatabase) {
    fun scalar(sql: String): Long = database.query(sql).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getLong(0)
    }
    check(
        scalar("SELECT COUNT(*) FROM library_document_search_content_v3") ==
            scalar("SELECT COUNT(*) FROM library_documents WHERE library_state = 'ACTIVE'"),
    ) { "Document search backfill is incomplete" }
    check(
        scalar("SELECT COUNT(*) FROM library_document_search_v3") ==
            scalar("SELECT COUNT(*) FROM library_document_search_content_v3"),
    ) { "Document FTS rebuild is incomplete" }
    check(
        scalar("SELECT COUNT(*) FROM library_page_search_content_v3") ==
            scalar(
                """
                SELECT COUNT(*) FROM library_pages AS p
                JOIN library_documents AS d ON d.document_id = p.document_id
                WHERE d.library_state = 'ACTIVE' AND p.active_ocr_artifact_revision IS NOT NULL
                """.trimIndent(),
            ),
    ) { "Page search backfill is incomplete" }
    check(
        scalar("SELECT COUNT(*) FROM library_page_search_v3") ==
            scalar("SELECT COUNT(*) FROM library_page_search_content_v3"),
    ) { "Page FTS rebuild is incomplete" }
    check(
        scalar(
            """
            SELECT COUNT(*) FROM library_pages AS p
            LEFT JOIN library_page_ocr_artifacts AS a
              ON a.page_id = p.page_id
             AND a.artifact_revision = p.active_ocr_artifact_revision
            WHERE (p.ocr_text IS NOT NULL AND (
                    p.active_ocr_artifact_revision != 1 OR p.ocr_state_revision != 1 OR
                    a.raw_text IS NOT p.ocr_text OR a.verification_state != 'LEGACY_UNVERIFIED'
                ))
               OR (p.ocr_text IS NULL AND (
                    p.active_ocr_artifact_revision IS NOT NULL OR p.ocr_state_revision != 0
                ))
            """.trimIndent(),
        ) == 0L,
    ) { "Legacy OCR artifact pointers are inconsistent" }
    check(
        scalar(
            """
            SELECT COUNT(*) FROM library_pages AS p
            LEFT JOIN library_page_ocr_artifacts AS a
              ON a.page_id = p.page_id
             AND a.artifact_revision = p.active_ocr_artifact_revision
            WHERE p.active_ocr_artifact_revision IS NOT NULL AND a.page_id IS NULL
            """.trimIndent(),
        ) == 0L,
    ) { "An active OCR pointer has no artifact" }
    check(
        scalar(
            """
            SELECT COUNT(*) FROM library_page_ocr_corrections AS c
            LEFT JOIN library_page_ocr_artifacts AS a
              ON a.page_id = c.page_id AND a.artifact_revision = c.base_artifact_revision
            WHERE a.page_id IS NULL
            """.trimIndent(),
        ) == 0L,
    ) { "An OCR correction has no base artifact" }
}

private fun createSearchSchema(database: SupportSQLiteDatabase) {
    database.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `library_document_search_content_v3` (
            `rowid` INTEGER NOT NULL,
            `document_id` TEXT NOT NULL,
            `title` TEXT NOT NULL,
            `folder_path` TEXT NOT NULL,
            `auxiliary_terms` TEXT NOT NULL,
            PRIMARY KEY(`rowid`),
            FOREIGN KEY(`document_id`) REFERENCES `library_documents`(`document_id`)
                ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
    )
    database.execSQL(
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_library_document_search_content_v3_document_id` " +
            "ON `library_document_search_content_v3` (`document_id`)",
    )
    database.execSQL(
        """
        CREATE VIRTUAL TABLE IF NOT EXISTS `library_document_search_v3`
        USING FTS4(
            `document_id` TEXT NOT NULL, `title` TEXT NOT NULL, `folder_path` TEXT NOT NULL,
            `auxiliary_terms` TEXT NOT NULL, tokenize=unicode61 `remove_diacritics=0`,
            content=`library_document_search_content_v3`, notindexed=`document_id`
        )
        """.trimIndent(),
    )
    database.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `library_page_search_content_v3` (
            `rowid` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `page_id` TEXT NOT NULL,
            `effective_text` TEXT NOT NULL,
            `auxiliary_terms` TEXT NOT NULL,
            FOREIGN KEY(`page_id`) REFERENCES `library_pages`(`page_id`)
                ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
    )
    database.execSQL(
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_library_page_search_content_v3_page_id` " +
            "ON `library_page_search_content_v3` (`page_id`)",
    )
    database.execSQL(
        """
        CREATE VIRTUAL TABLE IF NOT EXISTS `library_page_search_v3`
        USING FTS4(
            `page_id` TEXT NOT NULL, `effective_text` TEXT NOT NULL,
            `auxiliary_terms` TEXT NOT NULL, tokenize=unicode61 `remove_diacritics=0`,
            content=`library_page_search_content_v3`, notindexed=`page_id`
        )
        """.trimIndent(),
    )
    val triggers = listOf(
        "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_library_document_search_v3_BEFORE_UPDATE BEFORE UPDATE ON `library_document_search_content_v3` BEGIN DELETE FROM `library_document_search_v3` WHERE `docid`=OLD.`rowid`; END",
        "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_library_document_search_v3_BEFORE_DELETE BEFORE DELETE ON `library_document_search_content_v3` BEGIN DELETE FROM `library_document_search_v3` WHERE `docid`=OLD.`rowid`; END",
        "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_library_document_search_v3_AFTER_UPDATE AFTER UPDATE ON `library_document_search_content_v3` BEGIN INSERT INTO `library_document_search_v3`(`docid`, `document_id`, `title`, `folder_path`, `auxiliary_terms`) VALUES (NEW.`rowid`, NEW.`document_id`, NEW.`title`, NEW.`folder_path`, NEW.`auxiliary_terms`); END",
        "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_library_document_search_v3_AFTER_INSERT AFTER INSERT ON `library_document_search_content_v3` BEGIN INSERT INTO `library_document_search_v3`(`docid`, `document_id`, `title`, `folder_path`, `auxiliary_terms`) VALUES (NEW.`rowid`, NEW.`document_id`, NEW.`title`, NEW.`folder_path`, NEW.`auxiliary_terms`); END",
        "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_library_page_search_v3_BEFORE_UPDATE BEFORE UPDATE ON `library_page_search_content_v3` BEGIN DELETE FROM `library_page_search_v3` WHERE `docid`=OLD.`rowid`; END",
        "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_library_page_search_v3_BEFORE_DELETE BEFORE DELETE ON `library_page_search_content_v3` BEGIN DELETE FROM `library_page_search_v3` WHERE `docid`=OLD.`rowid`; END",
        "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_library_page_search_v3_AFTER_UPDATE AFTER UPDATE ON `library_page_search_content_v3` BEGIN INSERT INTO `library_page_search_v3`(`docid`, `page_id`, `effective_text`, `auxiliary_terms`) VALUES (NEW.`rowid`, NEW.`page_id`, NEW.`effective_text`, NEW.`auxiliary_terms`); END",
        "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_library_page_search_v3_AFTER_INSERT AFTER INSERT ON `library_page_search_content_v3` BEGIN INSERT INTO `library_page_search_v3`(`docid`, `page_id`, `effective_text`, `auxiliary_terms`) VALUES (NEW.`rowid`, NEW.`page_id`, NEW.`effective_text`, NEW.`auxiliary_terms`); END",
    )
    triggers.forEach(database::execSQL)
}

private fun backfillSearchContent(database: SupportSQLiteDatabase) {
    database.query(
        """
        WITH RECURSIVE folder_paths(folder_id, path) AS (
            SELECT folder_id, name FROM library_folders WHERE parent_folder_id IS NULL
            UNION ALL
            SELECT child.folder_id, parent.path || '/' || child.name
            FROM library_folders AS child
            JOIN folder_paths AS parent ON parent.folder_id = child.parent_folder_id
        )
        SELECT d.row_id, d.document_id, d.title, COALESCE(fp.path, '')
        FROM library_documents AS d
        LEFT JOIN folder_paths AS fp ON fp.folder_id = d.folder_id
        WHERE d.library_state = 'ACTIVE'
        ORDER BY d.row_id
        """.trimIndent(),
    ).use { cursor ->
        while (cursor.moveToNext()) {
            val title = cursor.getString(2)
            val folderPath = cursor.getString(3)
            database.execSQL(
                "INSERT INTO library_document_search_content_v3 " +
                    "(rowid, document_id, title, folder_path, auxiliary_terms) VALUES (?, ?, ?, ?, ?)",
                arrayOf(cursor.getLong(0), cursor.getString(1), title, folderPath,
                    searchAuxiliaryTerms("$title $folderPath")),
            )
        }
    }
    database.query(
        """
        SELECT p.page_id, a.raw_text
        FROM library_pages AS p
        JOIN library_documents AS d ON d.document_id = p.document_id
        JOIN library_page_ocr_artifacts AS a
          ON a.page_id = p.page_id AND a.artifact_revision = p.active_ocr_artifact_revision
        WHERE d.library_state = 'ACTIVE'
        ORDER BY d.row_id, p.page_position
        """.trimIndent(),
    ).use { cursor ->
        while (cursor.moveToNext()) {
            val text = cursor.getString(1)
            database.execSQL(
                "INSERT INTO library_page_search_content_v3 " +
                    "(page_id, effective_text, auxiliary_terms) VALUES (?, ?, ?)",
                arrayOf(cursor.getString(0), text, searchAuxiliaryTerms(text)),
            )
        }
    }
}

private fun createOcrBatchSchema(database: SupportSQLiteDatabase) {
    database.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `ocr_batch_jobs` (
            `job_id` TEXT NOT NULL, `selection_policy` TEXT NOT NULL,
            `requested_script_selection` TEXT NOT NULL,
            `locale_recommendation_snapshot` TEXT NOT NULL, `state` TEXT NOT NULL,
            `total_item_count` INTEGER NOT NULL, `completed_item_count` INTEGER NOT NULL,
            `failed_item_count` INTEGER NOT NULL, `skipped_item_count` INTEGER NOT NULL,
            `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL,
            `run_generation` INTEGER NOT NULL, `cancel_requested` INTEGER NOT NULL,
            `target_population_complete` INTEGER NOT NULL, `terminal_error_code` TEXT,
            PRIMARY KEY(`job_id`)
        )
        """.trimIndent(),
    )
    database.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_ocr_batch_jobs_state_updated_at` " +
            "ON `ocr_batch_jobs` (`state`, `updated_at`)",
    )
    database.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `ocr_batch_items` (
            `item_id` TEXT NOT NULL, `job_id` TEXT NOT NULL,
            `document_id` TEXT NOT NULL, `page_id` TEXT NOT NULL, `ordinal` INTEGER NOT NULL,
            `requested_script_selection` TEXT NOT NULL, `resolved_script` TEXT NOT NULL,
            `expected_document_content_revision` INTEGER NOT NULL,
            `expected_page_visual_revision` INTEGER NOT NULL,
            `expected_input_fingerprint_version` INTEGER, `expected_input_fingerprint` TEXT,
            `expected_active_artifact_revision` INTEGER,
            `expected_ocr_state_revision` INTEGER NOT NULL, `state` TEXT NOT NULL,
            `attempt_number` INTEGER NOT NULL, `claim_generation` INTEGER,
            `claim_token` TEXT, `safe_error_code` TEXT, PRIMARY KEY(`item_id`),
            FOREIGN KEY(`job_id`) REFERENCES `ocr_batch_jobs`(`job_id`)
                ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
    )
    database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_ocr_batch_items_job_id_page_id` ON `ocr_batch_items` (`job_id`, `page_id`)")
    database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_ocr_batch_items_job_id_ordinal` ON `ocr_batch_items` (`job_id`, `ordinal`)")
    database.execSQL("CREATE INDEX IF NOT EXISTS `index_ocr_batch_items_job_id_state_ordinal` ON `ocr_batch_items` (`job_id`, `state`, `ordinal`)")
}
