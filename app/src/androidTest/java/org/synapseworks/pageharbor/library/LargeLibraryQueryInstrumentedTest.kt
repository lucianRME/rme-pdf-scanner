package org.synapseworks.pageharbor.library

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
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

    @Before
    fun setUp() {
        libraryDirectory.deleteRecursively()
        database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .allowMainThreadQueries()
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
        assertEquals(PAGES_PER_DOCUMENT, database.libraryDao().pages(sampleDocumentId).size)
        val sampleAsset = database.libraryDao().sourceAssets(sampleDocumentId).single()
        assertEquals("ORIGINAL_DOCUMENT", sampleAsset.role)
        assertEquals("application/pdf", sampleAsset.contentType)
        assertEquals(syntheticHash(SAMPLE_DOCUMENT_INDEX), sampleAsset.sha256)
        assertTrue(libraryDirectory.listFiles().isNullOrEmpty())

        val heapGrowth = (compactedHeapBytes() - heapBefore).coerceAtLeast(0L)
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

            repeat(DOCUMENT_COUNT) { documentIndex ->
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
                        ocrText = if (position == 0) ocrText else null,
                        ocrError = null,
                        contentSha256 = syntheticHash(
                            (documentIndex * PAGES_PER_DOCUMENT) + position + DOCUMENT_COUNT,
                        ),
                    )
                }
                val sourceAsset = LibrarySourceAssetEntity(
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
                )
                dao.replaceDocument(
                    document = document,
                    pages = pages,
                    ocrText = ocrText,
                    sourceAssets = listOf(sourceAsset),
                )
            }
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
    }
}
