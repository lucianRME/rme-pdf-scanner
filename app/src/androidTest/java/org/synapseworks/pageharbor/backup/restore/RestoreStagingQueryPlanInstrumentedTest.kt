package org.synapseworks.pageharbor.backup.restore

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RestoreStagingQueryPlanInstrumentedTest {
    @Test
    fun folderFrontierAndRelationshipChecksStayIndexed() {
        val database = SQLiteDatabase.create(null)
        try {
            database.execSQL(
                "CREATE TABLE folders(" +
                    "ordinal INTEGER PRIMARY KEY, record_id TEXT NOT NULL UNIQUE, " +
                    "plan_ready INTEGER NOT NULL, target_id TEXT UNIQUE, target_parent_id TEXT, " +
                    "planned_name TEXT, planned_path_characters INTEGER, json TEXT)",
            )
            database.execSQL("CREATE INDEX folders_plan_ready_ordinal ON folders(plan_ready,ordinal)")
            database.execSQL(
                "CREATE TABLE documents(" +
                    "ordinal INTEGER PRIMARY KEY, record_id TEXT UNIQUE, page_count INTEGER, source_count INTEGER)",
            )
            database.execSQL(
                "CREATE TABLE pages(" +
                    "ordinal INTEGER PRIMARY KEY, document_id TEXT, page_position INTEGER, " +
                    "UNIQUE(document_id,page_position))",
            )
            database.execSQL("CREATE INDEX pages_document_position ON pages(document_id,page_position)")
            database.execSQL(
                "CREATE TABLE sources(" +
                    "ordinal INTEGER PRIMARY KEY, document_id TEXT, record_id TEXT, UNIQUE(document_id,record_id))",
            )
            database.execSQL("CREATE INDEX sources_document_id ON sources(document_id,record_id)")

            val frontier = explain(
                database,
                "SELECT json FROM folders WHERE plan_ready=1 ORDER BY ordinal LIMIT 256",
            )
            assertTrue(
                "ready folders must seek the on-disk frontier: $frontier",
                frontier.any { it.contains("folders_plan_ready_ordinal") },
            )
            assertBoundedPlan("ready folders", frontier)

            val targetPath = explain(
                database,
                """
                WITH RECURSIVE ancestors(
                  target_id, target_parent_id, planned_name, depth, expected_path_characters
                ) AS (
                  SELECT target_id, target_parent_id, planned_name, 0, planned_path_characters
                  FROM folders
                  WHERE record_id='original-id' AND target_id IS NOT NULL AND planned_name IS NOT NULL
                    AND planned_path_characters IS NOT NULL
                  UNION ALL
                  SELECT parent.target_id, parent.target_parent_id, parent.planned_name,
                         child.depth + 1, child.expected_path_characters
                  FROM folders parent
                  JOIN ancestors child ON parent.target_id=child.target_parent_id
                  WHERE parent.target_id IS NOT NULL AND parent.planned_name IS NOT NULL
                )
                SELECT target_id, target_parent_id, planned_name, depth, expected_path_characters
                FROM ancestors
                """.trimIndent(),
            )
            assertTrue(
                "target path seed must seek the original record id: $targetPath",
                targetPath.any {
                    it.contains("INDEX", ignoreCase = true) &&
                        it.contains("record_id=?", ignoreCase = true)
                },
            )
            assertTrue(
                "each recursive parent step must seek the target id: $targetPath",
                targetPath.any {
                    it.contains("INDEX", ignoreCase = true) &&
                        it.contains("target_id=?", ignoreCase = true)
                },
            )
            assertBoundedPlan("target folder path traversal", targetPath)

            val pages = explain(
                database,
                """
                SELECT 1 FROM documents d
                WHERE (SELECT COUNT(*) FROM pages p WHERE p.document_id=d.record_id) != d.page_count OR
                      (d.page_count > 0 AND
                       ((SELECT MIN(p.page_position) FROM pages p WHERE p.document_id=d.record_id) != 0 OR
                        (SELECT MAX(p.page_position) FROM pages p WHERE p.document_id=d.record_id) !=
                            d.page_count - 1))
                LIMIT 1
                """.trimIndent(),
            )
            assertTrue(
                "page relationship checks must use document-position indexes: $pages",
                pages.count { it.contains("pages_document_position") } >= 3,
            )
            assertBoundedPlan("page relationship checks", pages)

            val sources = explain(
                database,
                """
                SELECT 1 FROM documents d
                WHERE (SELECT COUNT(*) FROM sources s WHERE s.document_id=d.record_id) != d.source_count
                LIMIT 1
                """.trimIndent(),
            )
            assertTrue(
                "source relationship checks must use document indexes: $sources",
                sources.any { it.contains("sources_document_id") },
            )
            assertBoundedPlan("source relationship checks", sources)
        } finally {
            database.close()
        }
    }

    private fun explain(database: SQLiteDatabase, sql: String): List<String> = buildList {
        database.rawQuery("EXPLAIN QUERY PLAN $sql", null).use { cursor ->
            while (cursor.moveToNext()) add(cursor.getString(3))
        }
    }

    private fun assertBoundedPlan(name: String, plan: List<String>) {
        assertFalse(
            "$name must not materialize or sort the full staging index: $plan",
            plan.any {
                it.contains("MATERIALIZE", ignoreCase = true) ||
                    it.contains("TEMP B-TREE", ignoreCase = true)
            },
        )
    }
}
