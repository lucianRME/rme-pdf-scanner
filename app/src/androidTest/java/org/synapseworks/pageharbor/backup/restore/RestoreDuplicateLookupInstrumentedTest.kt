package org.synapseworks.pageharbor.backup.restore

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.synapseworks.pageharbor.document.session.DocumentSourceCategory
import org.synapseworks.pageharbor.image.DocumentFilter
import org.synapseworks.pageharbor.library.LibraryDao
import org.synapseworks.pageharbor.library.LibraryDatabase
import org.synapseworks.pageharbor.library.LibraryDocumentEntity
import org.synapseworks.pageharbor.library.LibraryFileStore
import org.synapseworks.pageharbor.library.LibraryOcrCommitResult
import org.synapseworks.pageharbor.library.LibraryOcrCorrectionAlignment
import org.synapseworks.pageharbor.library.LibraryOcrCorrectionDraft
import org.synapseworks.pageharbor.library.LibraryOcrStatus
import org.synapseworks.pageharbor.library.LibraryPageEntity
import org.synapseworks.pageharbor.library.duplicate.DocumentFingerprint
import org.synapseworks.pageharbor.library.duplicate.DocumentFingerprintV1
import org.synapseworks.pageharbor.library.duplicate.DuplicateKind
import org.synapseworks.pageharbor.library.duplicate.IncomingDocumentIdentity
import org.synapseworks.pageharbor.library.duplicate.OcrStateDigest
import org.synapseworks.pageharbor.library.duplicate.OcrStateDigestV1

@RunWith(AndroidJUnit4::class)
class RestoreDuplicateLookupInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun imageOnlyAndLegacyUnknownLengthMatchesRemainConservativelyPossible() = runBlocking {
        withRoom { room ->
            seedDocument(room.libraryDao(), "near", pageCount = 1, contentByteCount = 950L)
            seedDocument(room.libraryDao(), "legacy", pageCount = 2, contentByteCount = null)
            val store = restoreStore(room.libraryDao())

            val near = store.classifyDuplicate(incoming(pageCount = 1, contentByteCount = 1_000L))
            assertEquals(DuplicateKind.POSSIBLE, near.kind)
            assertEquals("near", near.documentId)

            val legacy = store.classifyDuplicate(incoming(pageCount = 2, contentByteCount = 1_000L))
            assertEquals(DuplicateKind.POSSIBLE, legacy.kind)
            assertEquals("legacy", legacy.documentId)
        }
    }

    @Test
    fun imageOnlyMatchOutsideFivePercentWindowIsDifferent() = runBlocking {
        withRoom { room ->
            seedDocument(room.libraryDao(), "outside", pageCount = 1, contentByteCount = 1_053L)

            val match = restoreStore(room.libraryDao()).classifyDuplicate(
                incoming(pageCount = 1, contentByteCount = 1_000L),
            )

            assertEquals(DuplicateKind.DIFFERENT, match.kind)
            assertNull(match.documentId)
        }
    }

    @Test
    fun exactRequiresMatchingRoomOcrDigestAndCorrectionDifferenceRemainsPossible() = runBlocking {
        withRoom { room ->
            val dao = room.libraryDao()
            val fingerprint = DocumentFingerprint(DocumentFingerprintV1.VERSION, "ab".repeat(32))
            val rawText = "raw migration-safe text"
            seedDocument(
                dao = dao,
                documentId = "digest-match",
                pageCount = 1,
                contentByteCount = 1_000L,
                fingerprint = fingerprint,
                rawText = rawText,
            )
            val digest = OcrStateDigestV1.calculate(
                scriptPreference = null,
                pages = listOf(
                    OcrStateDigestV1.legacyV1Page(
                        pageId = "digest-match-page-0",
                        legacyText = rawText,
                        legacyError = null,
                    ),
                ),
            )
            val identity = incoming(
                pageCount = 1,
                contentByteCount = 1_000L,
                fingerprint = fingerprint,
                ocrStateDigest = digest,
            )
            val store = restoreStore(dao)

            val exact = store.classifyDuplicate(identity)
            assertEquals(DuplicateKind.EXACT, exact.kind)
            assertEquals("digest-match", exact.documentId)

            val snapshot = requireNotNull(
                dao.ocrPageSnapshot("digest-match", "digest-match-page-0"),
            )
            assertEquals(
                LibraryOcrCommitResult.APPLIED,
                dao.saveOcrCorrection(
                    expected = snapshot,
                    correction = LibraryOcrCorrectionDraft(
                        correctedText = "corrected migration-safe text",
                        alignment = LibraryOcrCorrectionAlignment.FREEFORM,
                    ),
                    modifiedAt = 3L,
                ),
            )

            val changedOcr = store.classifyDuplicate(identity)
            assertEquals(DuplicateKind.POSSIBLE, changedOcr.kind)
            assertEquals("digest-match", changedOcr.documentId)
        }
    }

    @Test
    fun shapeFallbackRangeSeeksTheCompositeIndexWithoutTemporarySorting() {
        val room = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val database = room.openHelper.readableDatabase
            val queries = linkedMapOf(
                "page-count fallback" to """
                    SELECT document_id FROM library_documents
                        INDEXED BY $SHAPE_INDEX
                    WHERE library_state='ACTIVE' AND page_count=3
                    ORDER BY content_byte_count, row_id LIMIT 1
                """.trimIndent(),
                "known byte range" to """
                    SELECT document_id FROM library_documents
                        INDEXED BY $SHAPE_INDEX
                    WHERE library_state='ACTIVE' AND page_count=3
                      AND content_byte_count BETWEEN 950 AND 1052
                    ORDER BY content_byte_count, row_id LIMIT 1
                """.trimIndent(),
                "legacy unknown byte count" to """
                    SELECT document_id FROM library_documents
                        INDEXED BY $SHAPE_INDEX
                    WHERE library_state='ACTIVE' AND page_count=3
                      AND content_byte_count IS NULL
                    ORDER BY row_id LIMIT 1
                """.trimIndent(),
            )

            queries.forEach { (name, sql) ->
                val plan = explain(database, sql)
                assertTrue(
                    "$name must seek the shape index: $plan",
                    plan.any {
                        it.contains("SEARCH", ignoreCase = true) && it.contains(SHAPE_INDEX)
                    },
                )
                assertFalse(
                    "$name must not scan or sort the active library: $plan",
                    plan.any {
                        it.contains("SCAN library_documents", ignoreCase = true) ||
                            it.contains("TEMP B-TREE", ignoreCase = true)
                    },
                )
            }
        } finally {
            room.close()
        }
    }

    private suspend fun seedDocument(
        dao: LibraryDao,
        documentId: String,
        pageCount: Int,
        contentByteCount: Long?,
        fingerprint: DocumentFingerprint? = null,
        rawText: String? = null,
    ) {
        val pages = List(pageCount) { position ->
            LibraryPageEntity(
                pageId = "$documentId-page-$position",
                documentId = documentId,
                position = position,
                relativePath = "$documentId/page-$position.jpg",
                contentType = "image/jpeg",
                sourceCategory = DocumentSourceCategory.SELECTED_IMAGE.name,
                width = 10,
                height = 10,
                sourceByteCount = contentByteCount?.div(pageCount),
                rotationDegrees = 0,
                filterName = DocumentFilter.ORIGINAL.name,
                ocrText = rawText,
                ocrError = null,
                contentSha256 = "%064x".format(position + 1),
            )
        }
        dao.replaceDocument(
            document = LibraryDocumentEntity(
                documentId = documentId,
                title = "Fixture",
                createdAtMillis = 1L,
                modifiedAtMillis = 2L,
                pageCount = pageCount,
                folderId = null,
                thumbnailRelativePath = null,
                ocrStatus = LibraryOcrStatus.NOT_INDEXED.name,
                contentHashVersion = fingerprint?.version,
                contentSha256 = fingerprint?.sha256,
                contentByteCount = contentByteCount,
            ),
            pages = pages,
            ocrText = "",
        )
    }

    private fun restoreStore(dao: LibraryDao): RoomRestoreLibraryStore = RoomRestoreLibraryStore(
        context = context,
        dao = dao,
        fileStore = LibraryFileStore(context, File(context.cacheDir, "duplicate-lookup-files")),
    )

    private fun incoming(
        pageCount: Int,
        contentByteCount: Long,
        fingerprint: DocumentFingerprint = DocumentFingerprint(
            DocumentFingerprintV1.VERSION,
            "ab".repeat(32),
        ),
        ocrStateDigest: OcrStateDigest? = null,
    ): IncomingDocumentIdentity =
        IncomingDocumentIdentity(
            fingerprint = fingerprint,
            pageCount = pageCount,
            contentByteLength = contentByteCount,
            orderedMimeTypes = List(pageCount) { "image/jpeg" },
            ocrStateDigestVersion = ocrStateDigest?.version,
            ocrStateSha256 = ocrStateDigest?.sha256,
        )

    private fun explain(database: SupportSQLiteDatabase, sql: String): List<String> = buildList {
        database.query("EXPLAIN QUERY PLAN $sql").use { cursor ->
            while (cursor.moveToNext()) add(cursor.getString(3))
        }
    }

    private suspend fun withRoom(block: suspend (LibraryDatabase) -> Unit) {
        val room = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            block(room)
        } finally {
            room.close()
        }
    }

    private companion object {
        const val SHAPE_INDEX =
            "index_library_documents_library_state_page_count_content_byte_count_row_id"
    }
}
