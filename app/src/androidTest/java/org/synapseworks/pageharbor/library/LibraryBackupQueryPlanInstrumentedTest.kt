package org.synapseworks.pageharbor.library

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryBackupQueryPlanInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun backupKeysetQueriesUseRangeIndexesWithoutTemporaryOrdering() {
        val room = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val database = room.openHelper.readableDatabase
            val documentDrivenQueries = linkedMapOf(
                "documents" to """
                    SELECT d.*,
                           (SELECT COUNT(*) FROM library_pages p WHERE p.document_id=d.document_id) backup_page_count,
                           (SELECT COUNT(*) FROM library_source_assets s WHERE s.document_id=d.document_id) backup_source_asset_count
                    FROM library_documents d
                    WHERE d.library_state='ACTIVE' AND d.row_id > -1
                    ORDER BY d.row_id LIMIT 256
                """.trimIndent(),
                "pages" to """
                    SELECT p.*, d.row_id AS backup_document_row_id
                    FROM library_pages p
                    JOIN library_documents d ON d.document_id=p.document_id
                    WHERE d.library_state='ACTIVE'
                      AND (d.row_id > -1 OR (d.row_id=-1 AND p.page_position > -1))
                    ORDER BY d.row_id, p.page_position LIMIT 32
                """.trimIndent(),
                "artifacts" to """
                    SELECT a.*, d.row_id, p.page_position,
                           (SELECT COUNT(*) FROM library_page_ocr_lines l
                            WHERE l.page_id=a.page_id AND l.artifact_revision=a.artifact_revision)
                    FROM library_page_ocr_artifacts a
                    JOIN library_pages p ON p.page_id=a.page_id
                    JOIN library_documents d ON d.document_id=p.document_id
                    WHERE d.library_state='ACTIVE'
                      AND (d.row_id > -1 OR (d.row_id=-1 AND
                           (p.page_position > -1 OR
                            (p.page_position=-1 AND a.artifact_revision > -1))))
                    ORDER BY d.row_id, p.page_position, a.artifact_revision LIMIT 32
                """.trimIndent(),
                "lines" to """
                    SELECT l.*, d.row_id, p.page_position
                    FROM library_page_ocr_lines l
                    JOIN library_pages p ON p.page_id=l.page_id
                    JOIN library_documents d ON d.document_id=p.document_id
                    WHERE d.library_state='ACTIVE'
                      AND (d.row_id > -1 OR (d.row_id=-1 AND
                           (p.page_position > -1 OR (p.page_position=-1 AND
                            (l.artifact_revision > -1 OR
                             (l.artifact_revision=-1 AND l.line_ordinal > -1))))))
                    ORDER BY d.row_id, p.page_position, l.artifact_revision, l.line_ordinal LIMIT 32
                """.trimIndent(),
                "corrections" to """
                    SELECT c.*, d.row_id, p.page_position
                    FROM library_page_ocr_corrections c
                    JOIN library_pages p ON p.page_id=c.page_id
                    JOIN library_documents d ON d.document_id=p.document_id
                    WHERE d.library_state='ACTIVE'
                      AND (d.row_id > -1 OR (d.row_id=-1 AND p.page_position > -1))
                    ORDER BY d.row_id, p.page_position LIMIT 32
                """.trimIndent(),
                "correction lines" to """
                    SELECT l.*, d.row_id, p.page_position
                    FROM library_page_ocr_correction_lines l
                    JOIN library_pages p ON p.page_id=l.page_id
                    JOIN library_documents d ON d.document_id=p.document_id
                    WHERE d.library_state='ACTIVE'
                      AND (d.row_id > -1 OR (d.row_id=-1 AND
                           (p.page_position > -1 OR (p.page_position=-1 AND
                            (l.base_artifact_revision > -1 OR
                             (l.base_artifact_revision=-1 AND l.line_ordinal > -1))))))
                    ORDER BY d.row_id, p.page_position, l.base_artifact_revision, l.line_ordinal LIMIT 32
                """.trimIndent(),
            )
            documentDrivenQueries.forEach { (name, sql) ->
                val plan = explain(database, sql)
                assertTrue(
                    "$name must range-seek active documents: $plan",
                    plan.any { it.contains("index_library_documents_library_state_row_id") },
                )
                assertFalse(
                    "$name must not sort the remaining library: $plan",
                    plan.any { it.contains("TEMP B-TREE", ignoreCase = true) },
                )
            }

            val sourcePlan = explain(
                database,
                """
                SELECT s.* FROM library_source_assets s
                WHERE s.asset_id > ''
                  AND EXISTS (
                    SELECT 1 FROM library_documents d
                    WHERE d.document_id=s.document_id AND d.library_state='ACTIVE'
                  )
                ORDER BY s.asset_id LIMIT 256
                """.trimIndent(),
            )
            assertTrue(
                "source assets must range-seek asset IDs: $sourcePlan",
                sourcePlan.any { it.contains("SEARCH s") && it.contains("asset_id>?") },
            )
            assertFalse(
                "source assets must not sort the remaining library: $sourcePlan",
                sourcePlan.any { it.contains("TEMP B-TREE", ignoreCase = true) },
            )

            val duplicateSourcePlan = explain(
                database,
                """
                SELECT d.document_id
                FROM library_source_assets s INDEXED BY index_library_source_assets_sha256_byte_count
                CROSS JOIN library_documents d ON d.document_id=s.document_id
                WHERE s.sha256 IN ('${"ab".repeat(32)}', '${"cd".repeat(32)}')
                  AND d.library_state='ACTIVE'
                LIMIT 1
                """.trimIndent(),
            )
            assertTrue(
                "source duplicate lookup must start from the SHA index: $duplicateSourcePlan",
                duplicateSourcePlan.firstOrNull()?.contains(
                    "index_library_source_assets_sha256_byte_count",
                ) == true,
            )
            assertFalse(
                "source duplicate lookup must not scan or sort active documents: $duplicateSourcePlan",
                duplicateSourcePlan.any {
                    it.contains("TEMP B-TREE", ignoreCase = true) ||
                        it.contains("SCAN d", ignoreCase = true)
                },
            )
        } finally {
            room.close()
        }
    }

    private fun explain(database: SupportSQLiteDatabase, sql: String): List<String> = buildList {
        database.query("EXPLAIN QUERY PLAN $sql").use { cursor ->
            while (cursor.moveToNext()) add(cursor.getString(3))
        }
    }
}
