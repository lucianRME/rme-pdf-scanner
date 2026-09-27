package org.synapseworks.pageharbor.library

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.synapseworks.pageharbor.document.session.DocumentSourceCategory
import org.synapseworks.pageharbor.image.DocumentFilter

@RunWith(AndroidJUnit4::class)
class LargeLibraryQueryInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val libraryDirectory = File(context.cacheDir, "large-library-query-test")
    private lateinit var database: LibraryDatabase
    private lateinit var repository: LibraryRepository
    private val measuredQueryCount = AtomicInteger()
    @Volatile private var measureQueries = false

    @Before
    fun setUp() {
        libraryDirectory.deleteRecursively()
        database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryCallback(
                { _, _ -> if (measureQueries) measuredQueryCount.incrementAndGet() },
                Executor(Runnable::run),
            )
            .build()
        repository = LibraryRepository(
            context = context,
            dao = database.libraryDao(),
            fileStore = LibraryFileStore(context, libraryDirectory),
        )
    }

    @After
    fun tearDown() {
        database.close()
        libraryDirectory.deleteRecursively()
    }

    @Test
    fun thousandDocumentLibraryLoadsNestedFoldersSortsAndSearchesWithBoundedMemory() = runBlocking {
        warmQueryPaths()
        val heapBefore = compactedHeapBytes()

        seedLargeLibrary()
        assertIndexedSearchPlan()

        val byModified = repository.observeDocuments(null, LibrarySortOrder.MODIFIED_DESC).first()
        val byCreated = repository.observeDocuments(null, LibrarySortOrder.CREATED_DESC).first()
        val byTitle = repository.observeDocuments(null, LibrarySortOrder.TITLE_ASC).first()

        assertEquals(DOCUMENT_COUNT, byModified.size)
        assertEquals(DOCUMENT_COUNT, byModified.mapTo(hashSetOf()) { it.id }.size)
        assertEquals(documentId(expectedLatestModifiedIndex()), byModified.first().id)
        assertEquals(documentId(expectedEarliestModifiedIndex()), byModified.last().id)
        assertEquals(documentId(DOCUMENT_COUNT - 1), byCreated.first().id)
        assertEquals(documentId(0), byCreated.last().id)
        assertEquals(documentId(0), byTitle.first().id)
        assertEquals(documentId(TITLE_MATCH_INDEX), byTitle.last().id)
        assertTrue(byModified.zipWithNext().all { (first, second) ->
            first.modifiedAtMillis > second.modifiedAtMillis
        })
        assertTrue(byCreated.zipWithNext().all { (first, second) ->
            first.createdAtMillis > second.createdAtMillis
        })
        assertTrue(byTitle.zipWithNext().all { (first, second) ->
            first.title.compareTo(second.title, ignoreCase = true) <= 0
        })
        assertTrue(byModified.all { it.pageCount == PAGES_PER_DOCUMENT })

        val folders = repository.observeFolders().first().associateBy(LibraryFolder::id)
        assertEquals(ROOT_FOLDER_COUNT + LEAF_FOLDER_COUNT, folders.size)
        val sampleRoot = folders.getValue(rootFolderId(SAMPLE_ROOT_INDEX))
        val sampleLeaf = folders.getValue(leafFolderId(SAMPLE_ROOT_INDEX, SAMPLE_LEAF_INDEX))
        assertEquals(null, sampleRoot.parentFolderId)
        assertEquals(sampleRoot.id, sampleLeaf.parentFolderId)
        assertEquals(DOCUMENTS_PER_LEAF, sampleLeaf.documentCount)

        val leafDocuments = repository.observeDocuments(
            folderId = sampleLeaf.id,
            sortOrder = LibrarySortOrder.MODIFIED_DESC,
        ).first()
        assertEquals(DOCUMENTS_PER_LEAF, leafDocuments.size)
        assertTrue(leafDocuments.all { document ->
            document.folderId == sampleLeaf.id && document.folderName == sampleLeaf.name
        })

        val quartzMatches = requireNotNull(repository.observeSearch("quartz")).first()
        assertEquals(OCR_MATCH_COUNT + 1, quartzMatches.size)
        assertEquals(documentId(TITLE_MATCH_INDEX), quartzMatches.first().id)
        assertEquals(LibrarySearchMatch.TITLE, quartzMatches.first().searchMatch)
        assertTrue(quartzMatches.drop(1).all { it.searchMatch == LibrarySearchMatch.OCR })

        val prefixMatches = requireNotNull(repository.observeSearch("quar ledg")).first()
        assertEquals(OCR_MATCH_COUNT, prefixMatches.size)
        assertTrue(prefixMatches.all { it.searchMatch == LibrarySearchMatch.OCR })
        assertTrue(prefixMatches.all { it.searchSnippet?.contains("quartz ledger") == true })
        assertTrue(prefixMatches.zipWithNext().all { (first, second) ->
            first.modifiedAtMillis > second.modifiedAtMillis
        })
        val sampleDocumentId = documentId(SAMPLE_DOCUMENT_INDEX)

        val titleStartedAt = SystemClock.elapsedRealtime()
        val firstTitlePage = repository.searchHits("quartz title", limit = SEARCH_RESULT_LIMIT)
        val titleQueryMillis = SystemClock.elapsedRealtime() - titleStartedAt
        assertTrue(firstTitlePage.isNotEmpty())
        assertTrue(firstTitlePage.size <= SEARCH_RESULT_LIMIT)

        val pageStartedAt = SystemClock.elapsedRealtime()
        val firstOcrPage = repository.searchHits("quartz ledger", limit = SEARCH_RESULT_LIMIT)
        val pageQueryMillis = SystemClock.elapsedRealtime() - pageStartedAt
        assertTrue(firstOcrPage.any { it.pageId != null })
        assertTrue(firstOcrPage.size <= SEARCH_RESULT_LIMIT)

        val commonHits = repository.searchHits("searchable record", limit = SEARCH_RESULT_LIMIT)
        assertEquals(SEARCH_RESULT_LIMIT, commonHits.size)
        val correctedPageId = "$sampleDocumentId-page-1"
        val correctionSnapshot = requireNotNull(
            database.libraryDao().ocrPageSnapshot(sampleDocumentId, correctedPageId),
        )
        val correctionStartedAt = SystemClock.elapsedRealtime()
        assertEquals(
            LibraryOcrCommitResult.APPLIED,
            repository.saveOcrCorrection(
                correctionSnapshot,
                LibraryOcrCorrectionDraft(
                    correctedText = "unique correction benchmark token",
                    alignment = LibraryOcrCorrectionAlignment.FREEFORM,
                ),
            ),
        )
        val correctionMillis = SystemClock.elapsedRealtime() - correctionStartedAt
        assertEquals(
            correctedPageId,
            repository.searchHits("unique correction benchmark", limit = SEARCH_RESULT_LIMIT)
                .single().pageId,
        )

        val databaseBytes = database.openHelper.readableDatabase.query(
            "SELECT (SELECT page_count FROM pragma_page_count) * " +
                "(SELECT page_size FROM pragma_page_size)",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getLong(0)
        }
        val heapGrowth = (compactedHeapBytes() - heapBefore).coerceAtLeast(0L)
        Log.i(
            "LargeLibraryQuery",
            "docs=$DOCUMENT_COUNT pages=${DOCUMENT_COUNT * PAGES_PER_DOCUMENT} " +
                "titleMs=$titleQueryMillis pageMs=$pageQueryMillis correctionMs=$correctionMillis " +
                "dbBytes=$databaseBytes heapGrowth=$heapGrowth",
        )
        assertTrue("Title first-page query took ${titleQueryMillis}ms", titleQueryMillis < MAX_QUERY_MILLIS)
        assertTrue("OCR page-hit query took ${pageQueryMillis}ms", pageQueryMillis < MAX_QUERY_MILLIS)
        assertTrue("Correction/index update took ${correctionMillis}ms", correctionMillis < MAX_UPDATE_MILLIS)
        assertTrue("In-memory SQLite proxy was $databaseBytes bytes", databaseBytes < MAX_DATABASE_BYTES)

        assertEquals(PAGES_PER_DOCUMENT, database.libraryDao().pages(sampleDocumentId).size)
        val sampleAsset = database.libraryDao().sourceAssets(sampleDocumentId).single()
        assertEquals("ORIGINAL_DOCUMENT", sampleAsset.role)
        assertEquals("application/pdf", sampleAsset.contentType)
        assertEquals(syntheticHash(SAMPLE_DOCUMENT_INDEX), sampleAsset.sha256)
        assertTrue(libraryDirectory.listFiles().isNullOrEmpty())

        assertTrue(
            "The metadata-only 1,000-document query fixture grew the managed heap by $heapGrowth bytes",
            heapGrowth < MAX_MANAGED_HEAP_GROWTH_BYTES,
        )
    }

    private suspend fun warmQueryPaths() {
        assertTrue(repository.observeDocuments(null, LibrarySortOrder.MODIFIED_DESC).first().isEmpty())
        assertTrue(repository.observeFolders().first().isEmpty())
        assertTrue(requireNotNull(repository.observeSearch("warmup")).first().isEmpty())
    }

    private suspend fun seedLargeLibrary() {
        val dao = database.libraryDao()
        database.withTransaction {
            repeat(ROOT_FOLDER_COUNT) { rootIndex ->
                val rootId = rootFolderId(rootIndex)
                dao.insertFolder(
                    LibraryFolderEntity(
                        folderId = rootId,
                        name = "Root ${rootIndex.toString().padStart(2, '0')}",
                        normalizedName = "root ${rootIndex.toString().padStart(2, '0')}",
                        createdAtMillis = 1_000L + rootIndex,
                        modifiedAtMillis = 2_000L + rootIndex,
                    ),
                )
                repeat(LEAVES_PER_ROOT) { leafIndex ->
                    dao.insertFolder(
                        LibraryFolderEntity(
                            folderId = leafFolderId(rootIndex, leafIndex),
                            name = "Quarter ${leafIndex.toString().padStart(2, '0')}",
                            normalizedName = "quarter ${leafIndex.toString().padStart(2, '0')}",
                            createdAtMillis = 3_000L + (rootIndex * LEAVES_PER_ROOT) + leafIndex,
                            modifiedAtMillis = 4_000L + (rootIndex * LEAVES_PER_ROOT) + leafIndex,
                            parentFolderId = rootId,
                        ),
                    )
                }
            }

        }
        var firstIndex = 0
        SEARCH_SCALE_CHECKPOINTS.forEach { checkpoint ->
            database.withTransaction {
                (firstIndex until checkpoint).forEach { documentIndex ->
                    insertSyntheticDocument(dao, documentIndex)
                }
            }
            benchmarkSearchScale(checkpoint)
            firstIndex = checkpoint
        }
    }

    private suspend fun insertSyntheticDocument(dao: LibraryDao, documentIndex: Int) {
        val id = documentId(documentIndex)
        val rootIndex = documentIndex / DOCUMENTS_PER_ROOT
        val leafIndex = (documentIndex / DOCUMENTS_PER_LEAF) % LEAVES_PER_ROOT
        val ocrText = if (documentIndex % OCR_MATCH_INTERVAL == 0) {
            "archive quartz ledger record ${documentIndex.toString().padStart(4, '0')}"
        } else {
            "ordinary searchable record ${documentIndex.toString().padStart(4, '0')}"
        }
        val document = LibraryDocumentEntity(
            documentId = id,
            title = if (documentIndex == TITLE_MATCH_INDEX) {
                "Quartz title ${documentIndex.toString().padStart(4, '0')}"
            } else {
                "Document ${documentIndex.toString().padStart(4, '0')}"
            },
            createdAtMillis = createdAt(documentIndex),
            modifiedAtMillis = modifiedAt(documentIndex),
            pageCount = PAGES_PER_DOCUMENT,
            folderId = leafFolderId(rootIndex, leafIndex),
            thumbnailRelativePath = "documents/$id/thumbnail.webp",
            ocrStatus = LibraryOcrStatus.INDEXED.name,
            contentHashVersion = 1,
            contentSha256 = syntheticHash(documentIndex),
            contentByteCount = PAGES_PER_DOCUMENT * SYNTHETIC_PAGE_BYTES,
            sourceModifiedAtMillis = 9_000L + documentIndex,
            importedAtMillis = 10_000L + documentIndex,
        )
        val pages = List(PAGES_PER_DOCUMENT) { position ->
            LibraryPageEntity(
                pageId = "$id-page-$position",
                documentId = id,
                position = position,
                relativePath = "documents/$id/pages/$position.webp",
                contentType = "image/webp",
                sourceCategory = DocumentSourceCategory.RENDERED_PDF_PAGE.name,
                width = 1_200,
                height = 1_600,
                sourceByteCount = SYNTHETIC_PAGE_BYTES,
                rotationDegrees = 0,
                filterName = DocumentFilter.ORIGINAL.name,
                ocrText = buildString {
                    append(if (position == 0) ocrText else "ordinary searchable page $position")
                    repeat(OCR_PARAGRAPH_REPETITIONS) {
                        append(" local private document text block ")
                        append(documentIndex)
                        append(' ')
                        append(position)
                    }
                },
                ocrError = null,
                contentSha256 = syntheticHash(
                    (documentIndex * PAGES_PER_DOCUMENT) + position + DOCUMENT_COUNT,
                ),
            )
        }
        dao.replaceDocument(
            document = document,
            pages = pages,
            ocrText = ocrText,
            sourceAssets = listOf(
                LibrarySourceAssetEntity(
                    assetId = "$id-source",
                    documentId = id,
                    role = "ORIGINAL_DOCUMENT",
                    relativePath = "documents/$id/sources/original.pdf",
                    contentType = "application/pdf",
                    byteCount = PAGES_PER_DOCUMENT * SYNTHETIC_PAGE_BYTES,
                    sha256 = syntheticHash(documentIndex),
                    sourceModifiedAtMillis = 9_000L + documentIndex,
                    createdAtMillis = 10_000L + documentIndex,
                    matchesCurrentRevision = true,
                ),
            ),
        )
    }

    private suspend fun benchmarkSearchScale(documentCount: Int) {
        val heapBefore = compactedHeapBytes()
        listOf("searchable record", "record 0096", "no such local token").forEach { query ->
            measuredQueryCount.set(0)
            measureQueries = true
            val startedAt = SystemClock.elapsedRealtime()
            val hits = try {
                repository.searchHits(query, limit = SEARCH_RESULT_LIMIT)
            } finally {
                measureQueries = false
            }
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            val queries = measuredQueryCount.get()
            Log.i(
                "LargeLibraryQuery",
                "scaleDocs=$documentCount pages=${documentCount * PAGES_PER_DOCUMENT} " +
                    "query='$query' durationMs=$elapsed results=${hits.size} sqlQueries=$queries",
            )
            assertTrue("Scale query took ${elapsed}ms at $documentCount documents", elapsed < MAX_QUERY_MILLIS)
            assertTrue("Search returned too many results", hits.size <= SEARCH_RESULT_LIMIT)
            assertTrue("Expected a bounded query count but observed $queries", queries in 1..4)
        }
        val heapGrowth = (compactedHeapBytes() - heapBefore).coerceAtLeast(0L)
        Log.i("LargeLibraryQuery", "scaleDocs=$documentCount heapGrowth=$heapGrowth")
        assertTrue(heapGrowth < MAX_MANAGED_HEAP_GROWTH_BYTES)
    }

    private fun assertIndexedSearchPlan() {
        val plans = listOf(
            "document" to """
                SELECT d.document_id
                FROM library_document_search_v3 AS search
                JOIN library_document_search_content_v3 AS c ON c.rowid = search.rowid
                JOIN library_documents AS d ON d.row_id = c.rowid
                WHERE d.library_state = 'ACTIVE'
                  AND library_document_search_v3 MATCH 'searchable*'
                ORDER BY d.modified_at DESC, d.document_id ASC
                LIMIT 30
            """.trimIndent(),
            "page" to """
                SELECT d.document_id, p.page_id
                FROM library_page_search_v3 AS search
                JOIN library_page_search_content_v3 AS c ON c.rowid = search.rowid
                JOIN library_pages AS p ON p.page_id = c.page_id
                JOIN library_documents AS d ON d.document_id = p.document_id
                WHERE d.library_state = 'ACTIVE'
                  AND library_page_search_v3 MATCH 'searchable*'
                ORDER BY d.modified_at DESC, p.page_id ASC
                LIMIT 30
            """.trimIndent(),
        )
        plans.forEach { (name, sql) ->
            val details = mutableListOf<String>()
            database.openHelper.readableDatabase.query("EXPLAIN QUERY PLAN $sql").use { cursor ->
                while (cursor.moveToNext()) details += cursor.getString(3)
            }
            Log.i("LargeLibraryQuery", "${name}QueryPlan=${details.joinToString(" | ")}")
            assertTrue(details.any { it.contains("VIRTUAL TABLE INDEX", ignoreCase = true) })
            assertTrue(details.none { detail ->
                detail.startsWith("SCAN c", ignoreCase = true) ||
                    detail.startsWith("SCAN p", ignoreCase = true) ||
                    detail.startsWith("SCAN d", ignoreCase = true)
            })
        }
    }

    private fun expectedLatestModifiedIndex(): Int =
        (0 until DOCUMENT_COUNT).maxBy(::modifiedAt)

    private fun expectedEarliestModifiedIndex(): Int =
        (0 until DOCUMENT_COUNT).minBy(::modifiedAt)

    private fun createdAt(index: Int): Long = 100_000L + index

    private fun modifiedAt(index: Int): Long = 200_000L + ((index * 37) % DOCUMENT_COUNT)

    private fun documentId(index: Int): String =
        "document-${index.toString().padStart(4, '0')}"

    private fun rootFolderId(rootIndex: Int): String = "root-$rootIndex"

    private fun leafFolderId(rootIndex: Int, leafIndex: Int): String =
        "leaf-$rootIndex-$leafIndex"

    private fun syntheticHash(value: Int): String = value.toString(16).padStart(64, '0')

    private fun compactedHeapBytes(): Long {
        val runtime = Runtime.getRuntime()
        runtime.gc()
        System.runFinalization()
        runtime.gc()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    private companion object {
        const val DOCUMENT_COUNT = 1_000
        val SEARCH_SCALE_CHECKPOINTS = listOf(100, 500, DOCUMENT_COUNT)
        const val PAGES_PER_DOCUMENT = 3
        const val ROOT_FOLDER_COUNT = 5
        const val LEAVES_PER_ROOT = 10
        const val LEAF_FOLDER_COUNT = ROOT_FOLDER_COUNT * LEAVES_PER_ROOT
        const val DOCUMENTS_PER_ROOT = DOCUMENT_COUNT / ROOT_FOLDER_COUNT
        const val DOCUMENTS_PER_LEAF = DOCUMENT_COUNT / LEAF_FOLDER_COUNT
        const val OCR_MATCH_INTERVAL = 100
        const val OCR_MATCH_COUNT = DOCUMENT_COUNT / OCR_MATCH_INTERVAL
        const val TITLE_MATCH_INDEX = 777
        const val SAMPLE_ROOT_INDEX = 2
        const val SAMPLE_LEAF_INDEX = 7
        const val SAMPLE_DOCUMENT_INDEX = 517
        const val SYNTHETIC_PAGE_BYTES = 1_024L
        const val MAX_MANAGED_HEAP_GROWTH_BYTES = 64L * 1024L * 1024L
        const val OCR_PARAGRAPH_REPETITIONS = 20
        const val SEARCH_RESULT_LIMIT = 30
        const val MAX_QUERY_MILLIS = 2_000L
        const val MAX_UPDATE_MILLIS = 1_000L
        const val MAX_DATABASE_BYTES = 128L * 1024L * 1024L
    }
}
