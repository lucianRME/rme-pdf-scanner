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
            CREATE UNIQUE INDEX IF NOT EXISTS `index_library_source_assets_document_id_role`
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
