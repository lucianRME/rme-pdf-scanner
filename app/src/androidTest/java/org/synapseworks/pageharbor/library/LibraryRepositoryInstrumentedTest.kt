package org.synapseworks.pageharbor.library

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.synapseworks.pageharbor.document.session.DocumentImageMetadata
import org.synapseworks.pageharbor.document.session.DocumentPage
import org.synapseworks.pageharbor.document.session.DocumentPageId
import org.synapseworks.pageharbor.document.session.DocumentPageRotation
import org.synapseworks.pageharbor.document.session.DocumentResource
import org.synapseworks.pageharbor.document.session.DocumentResourceOwnership
import org.synapseworks.pageharbor.document.session.DocumentSession
import org.synapseworks.pageharbor.document.session.DocumentSourceCategory
import org.synapseworks.pageharbor.ocr.OcrPageResult
import org.synapseworks.pageharbor.ocr.OcrResult

@RunWith(AndroidJUnit4::class)
class LibraryRepositoryInstrumentedTest {
    private val restartDatabaseName = "library-restart-test.db"
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: LibraryDatabase
    private lateinit var repository: LibraryRepository
    private val sourceDirectory = File(context.cacheDir, "library-test-sources")
    private val libraryDirectory = File(
        File(context.filesDir, LibraryFileStore.LIBRARY_DIRECTORY),
        "instrumentation-tests",
    )
    private var clock = 1_000L

    @Before
    fun setUp() {
        context.deleteDatabase(restartDatabaseName)
        sourceDirectory.mkdirs()
        database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = LibraryRepository(
            context = context,
            dao = database.libraryDao(),
            fileStore = LibraryFileStore(context, libraryDirectory),
            nowMillis = { ++clock },
        )
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(restartDatabaseName)
        sourceDirectory.deleteRecursively()
        libraryDirectory.deleteRecursively()
    }

    @Test
    fun saveReopenAndUpdateUseOneEditableSessionModel() = runBlocking {
        val original = session(page(1, "red.jpg", 0xffff0000.toInt()), page(2, "blue.jpg", 0xff0000ff.toInt()))
        val saved = repository.saveSession(
            session = original,
            title = "Field notes",
            ocrResult = OcrResult(
                listOf(
                    OcrPageResult(0, "alpha term"),
                    OcrPageResult(1, "beta term"),
                ),
            ),
        ).successValue()

        val opened = repository.openDocument(saved.id).successValue()
        assertEquals("Field notes", opened.session.libraryDocument?.title)
        assertEquals(2, opened.session.pages.size)
        assertTrue(opened.session.pages.all { it.persistentId != null })
        assertTrue(opened.session.pages.all {
            it.source.ownership == DocumentResourceOwnership.RME_OWNED_LIBRARY
        })

        val reorderedIds = opened.session.pages.reversed().map(DocumentPage::id)
        val edited = requireNotNull(opened.session.reorder(reorderedIds))
            .rotateClockwise(reorderedIds.first())!!
        repository.saveSession(edited, "Field notes revised").successValue()

        val reopened = repository.openDocument(saved.id).successValue()
        assertEquals("Field notes revised", reopened.summary.title)
        assertEquals(DocumentPageRotation.DEGREES_90, reopened.session.pages.first().rotation)
        assertEquals(opened.session.pages.last().persistentId, reopened.session.pages.first().persistentId)
        assertEquals(listOf("beta term", "alpha term"), database.libraryDao().pages(saved.id).map { it.ocrText })
    }

    @Test
    fun titleAndOcrSearchAndFolderLifecycleStayLocalAndConsistent() = runBlocking {
        val invoice = repository.saveSession(
            session(page(1, "invoice.jpg", 0xffffffff.toInt())),
            "March invoice",
            ocrResult = OcrResult(listOf(OcrPageResult(0, "consulting services"))),
        ).successValue()
        repository.saveSession(
            session(page(2, "receipt.jpg", 0xffeeeeee.toInt())),
            "Coffee receipt",
        ).successValue()

        assertEquals(invoice.id, repository.observeSearch("march")!!.first().single().id)
        val ocrMatch = repository.observeSearch("consult")!!.first().single()
        assertEquals(invoice.id, ocrMatch.id)
        assertEquals(LibrarySearchMatch.OCR, ocrMatch.searchMatch)

        val folder = repository.createFolder("Taxes").successValue()
        repository.moveDocument(invoice.id, folder.id).successValue()
        assertEquals(folder.id, repository.openDocument(invoice.id).successValue().summary.folderId)
        repository.deleteFolder(folder.id).successValue()
        assertEquals(null, repository.openDocument(invoice.id).successValue().summary.folderId)
    }

    @Test
    fun nestedFoldersKeepParentageAndRejectCycles() = runBlocking {
        val root = repository.createFolder("Projects").successValue()
        val child = repository.createFolder("Receipts", root.id).successValue()
        val grandchild = repository.createFolder("April", child.id).successValue()

        val folders = repository.observeFolders().first().associateBy(LibraryFolder::id)
        assertEquals(root.id, folders.getValue(child.id).parentFolderId)
        assertEquals(child.id, folders.getValue(grandchild.id).parentFolderId)

        val refused = repository.moveFolder(root.id, grandchild.id)
        assertEquals(LibraryError.INVALID_SELECTION, (refused as LibraryResult.Failure).reason)

        repository.deleteFolder(child.id).successValue()
        assertEquals(root.id, database.libraryDao().folder(grandchild.id)?.parentFolderId)
        assertEquals(root.id, database.libraryDao().folder(grandchild.id)?.parentScope)
    }

    @Test
    fun duplicateFolderNamesAreScopedToSiblings() = runBlocking {
        val work = repository.createFolder("Work").successValue()
        val personal = repository.createFolder("Personal").successValue()
        val workYear = repository.createFolder("2024", work.id).successValue()
        val personalYear = repository.createFolder("2024", personal.id).successValue()
        repository.createFolder("2024").successValue()

        val duplicateSibling = repository.createFolder("  2024  ", work.id)
        assertEquals(
            LibraryError.DUPLICATE_FOLDER,
            (duplicateSibling as LibraryResult.Failure).reason,
        )

        val conflictingMove = repository.moveFolder(workYear.id, personal.id)
        assertEquals(
            LibraryError.DUPLICATE_FOLDER,
            (conflictingMove as LibraryResult.Failure).reason,
        )
        assertEquals(work.id, database.libraryDao().folder(workYear.id)?.parentFolderId)
        assertEquals(work.id, database.libraryDao().folder(workYear.id)?.parentScope)

        val receipts = repository.createFolder("Receipts", work.id).successValue()
        repository.moveFolder(receipts.id, personal.id).successValue()
        assertEquals(personal.id, database.libraryDao().folder(receipts.id)?.parentFolderId)
        assertEquals(personal.id, database.libraryDao().folder(receipts.id)?.parentScope)
        assertEquals(personal.id, database.libraryDao().folder(personalYear.id)?.parentScope)
    }

    @Test
    fun deletingFolderReparentsNamesSafelyOrReportsSiblingCollision() = runBlocking {
        val archive = repository.createFolder("Archive").successValue()
        val nestedArchive = repository.createFolder("Archive", archive.id).successValue()

        repository.deleteFolder(archive.id).successValue()
        assertEquals(null, database.libraryDao().folder(nestedArchive.id)?.parentFolderId)
        assertEquals("", database.libraryDao().folder(nestedArchive.id)?.parentScope)

        val existingYear = repository.createFolder("2024").successValue()
        val work = repository.createFolder("Work").successValue()
        val workYear = repository.createFolder("2024", work.id).successValue()

        val conflict = repository.deleteFolder(work.id)

        assertEquals(LibraryError.DUPLICATE_FOLDER, (conflict as LibraryResult.Failure).reason)
        assertEquals(work.id, database.libraryDao().folder(workYear.id)?.parentFolderId)
        assertEquals(work.id, database.libraryDao().folder(workYear.id)?.parentScope)
        assertEquals(null, database.libraryDao().folder(existingYear.id)?.parentFolderId)
    }

    @Test
    fun streamingSaveHashesPagesAndRetainsDirectPdfAcrossEdits() = runBlocking {
        val documentPage = page(30, "hashed.jpg", 0xff445566.toInt())
        val pdf = File(sourceDirectory, "source.pdf").apply {
            FileOutputStream(this).use { output ->
                output.write("%PDF-1.4\nsynthetic source\n%%EOF".encodeToByteArray())
            }
        }
        val directPdf = DocumentResource(
            reference = Uri.fromFile(pdf).toString(),
            ownership = DocumentResourceOwnership.USER_OR_EXTERNAL,
        )
        val saved = repository.saveSession(
            session = DocumentSession(
                pages = listOf(documentPage),
                directPdfSource = directPdf,
                directPdfPageIds = listOf(documentPage.id),
            ),
            title = "Hash proof",
        ).successValue()

        val storedDocument = requireNotNull(database.libraryDao().document(saved.id))
        val storedPage = database.libraryDao().pages(saved.id).single()
        val storedAsset = database.libraryDao().sourceAssets(saved.id).single()
        assertEquals(1, storedDocument.contentHashVersion)
        assertEquals(64, storedDocument.contentSha256?.length)
        assertEquals(storedPage.sourceByteCount, storedDocument.contentByteCount)
        assertEquals(64, storedPage.contentSha256?.length)
        assertEquals(pdf.length(), storedAsset.byteCount)
        assertEquals(64, storedAsset.sha256.length)
        assertTrue(storedAsset.matchesCurrentRevision)

        val opened = repository.openDocument(saved.id).successValue()
        assertNotNull(opened.session.directPdfSource)
        assertTrue(opened.session.canUseDirectPdf)
        val edited = requireNotNull(opened.session.rotateClockwise(opened.session.pages.single().id))
        repository.saveSession(edited, "Hash proof").successValue()

        val reopened = repository.openDocument(saved.id).successValue()
        assertNotNull(reopened.session.directPdfSource)
        assertFalse(reopened.session.canUseDirectPdf)
        assertFalse(database.libraryDao().sourceAssets(saved.id).single().matchesCurrentRevision)
    }

    @Test
    fun multiPdfImportPersistsAndReopensEveryOriginalSource() = runBlocking {
        val originals = (1..2).map { index ->
            val pdf = File(sourceDirectory, "source-$index.pdf").apply {
                FileOutputStream(this).use { output ->
                    output.write("%PDF-1.4\nsynthetic source $index\n%%EOF".encodeToByteArray())
                }
            }
            DocumentResource(
                reference = Uri.fromFile(pdf).toString(),
                ownership = DocumentResourceOwnership.USER_OR_EXTERNAL,
            )
        }
        val saved = repository.saveSession(
            session = DocumentSession(
                pages = listOf(page(31, "multi-source.jpg", 0xff556677.toInt())),
                originalPdfSources = originals,
            ),
            title = "Multiple originals",
        ).successValue()

        val stored = database.libraryDao().sourceAssets(saved.id)
        assertEquals(2, stored.size)
        assertTrue(stored.all { it.role == "ORIGINAL_DOCUMENT" })
        assertTrue(stored.none { it.matchesCurrentRevision })

        val reopened = repository.openDocument(saved.id).successValue().session
        assertEquals(2, reopened.originalPdfSources.size)
        assertEquals(null, reopened.directPdfSource)
    }

    @Test
    fun mergeExtractAndSplitPreserveOrderAndNeverEmptyOriginal() = runBlocking {
        val first = repository.saveSession(
            session(page(1, "first.jpg", 0xff111111.toInt())),
            "First",
        ).successValue()
        val second = repository.saveSession(
            session(page(2, "second.jpg", 0xff222222.toInt())),
            "Second",
        ).successValue()
        val merged = repository.mergeDocuments(listOf(second.id, first.id), "Combined").successValue()
        val mergedPages = database.libraryDao().pages(merged.id)
        assertEquals(2, mergedPages.size)

        val extracted = repository.extractPages(
            merged.id,
            setOf(mergedPages.first().pageId),
            "Extracted",
            removeFromOriginal = false,
        ).successValue()
        assertEquals(1, repository.openDocument(extracted.id).successValue().session.pages.size)
        assertEquals(2, repository.openDocument(merged.id).successValue().session.pages.size)

        val refused = repository.extractPages(
            merged.id,
            mergedPages.mapTo(mutableSetOf()) { it.pageId },
            "Invalid split",
            removeFromOriginal = true,
        )
        assertEquals(LibraryError.EMPTY_DOCUMENT, (refused as LibraryResult.Failure).reason)

        repository.extractPages(
            merged.id,
            setOf(mergedPages.last().pageId),
            "Moved page",
            removeFromOriginal = true,
        ).successValue()
        val remainingPage = repository.openDocument(merged.id).successValue().session.pages.single()
        assertEquals(mergedPages.first().pageId, remainingPage.persistentId)
    }

    @Test
    fun libraryPersistsDocumentsBeyondTheScannerTwentyPageAcquisitionLimit() = runBlocking {
        val pages = (1L..20L).map { index ->
            page(index, "page-$index.jpg", 0xff000000.toInt() or index.toInt())
        }
        val saved = repository.saveSession(DocumentSession(pages), "Twenty pages").successValue()
        assertEquals(20, repository.openDocument(saved.id).successValue().session.pages.size)

        val twentyFirst = pages.first().copy(id = DocumentPageId(21L))
        val expanded = repository.saveSession(DocumentSession(pages + twentyFirst), "Twenty one pages")
            .successValue()
        assertEquals(21, repository.openDocument(expanded.id).successValue().session.pages.size)
    }

    @Test
    fun savedDocumentSurvivesDatabaseCloseAndReopen() = runBlocking {
        val firstDatabase = Room.databaseBuilder(
            context,
            LibraryDatabase::class.java,
            restartDatabaseName,
        ).build()
        val saved = try {
            LibraryRepository(
                context = context,
                dao = firstDatabase.libraryDao(),
                fileStore = LibraryFileStore(context, libraryDirectory),
                nowMillis = { ++clock },
            ).saveSession(
                session(page(1, "restart.jpg", 0xff123456.toInt())),
                title = "Restart proof",
            ).successValue()
        } finally {
            firstDatabase.close()
        }

        val reopenedDatabase = Room.databaseBuilder(
            context,
            LibraryDatabase::class.java,
            restartDatabaseName,
        ).build()
        try {
            val reopened = LibraryRepository(
                context = context,
                dao = reopenedDatabase.libraryDao(),
                fileStore = LibraryFileStore(context, libraryDirectory),
                nowMillis = { ++clock },
            ).openDocument(saved.id).successValue()

            assertEquals("Restart proof", reopened.summary.title)
            assertEquals(1, reopened.session.pages.size)
            assertEquals(
                DocumentResourceOwnership.RME_OWNED_LIBRARY,
                reopened.session.pages.single().source.ownership,
            )
        } finally {
            reopenedDatabase.close()
        }
    }

    @Test
    fun renameDeleteAndRevisionCleanupKeepExternalSourceSafeAndIndexConsistent() = runBlocking {
        val externalFile = File(sourceDirectory, "external-safe.jpg")
        val saved = repository.saveSession(
            session(page(1, externalFile.name, 0xff654321.toInt())),
            title = "Original token",
        ).successValue()
        assertTrue(externalFile.isFile)

        val opened = repository.openDocument(saved.id).successValue()
        repository.saveSession(opened.session, "Original token").successValue()
        val revisions = File(libraryDirectory, "${saved.id}/revisions")
        assertEquals(2, revisions.listFiles()?.count { it.isDirectory })

        repository.cleanupDocumentRevisions(saved.id)
        assertEquals(1, revisions.listFiles()?.count { it.isDirectory })

        repository.renameDocument(saved.id, "Renamed token").successValue()
        assertTrue(repository.observeSearch("original")!!.first().isEmpty())
        assertEquals(saved.id, repository.observeSearch("renamed")!!.first().single().id)

        repository.deleteDocument(saved.id).successValue()
        assertEquals(null, database.libraryDao().document(saved.id))
        assertTrue(repository.observeSearch("renamed")!!.first().isEmpty())
        assertFalse(File(libraryDirectory, saved.id).exists())
        assertTrue(externalFile.isFile)
    }

    private fun session(vararg pages: DocumentPage): DocumentSession =
        DocumentSession(pages = pages.toList())

    private fun page(id: Long, name: String, color: Int): DocumentPage {
        val source = File(sourceDirectory, name)
        val bitmap = Bitmap.createBitmap(48, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        FileOutputStream(source).use { output ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
        }
        bitmap.recycle()
        return DocumentPage(
            id = DocumentPageId(id),
            source = DocumentResource(
                reference = Uri.fromFile(source).toString(),
                ownership = DocumentResourceOwnership.USER_OR_EXTERNAL,
            ),
            sourceCategory = DocumentSourceCategory.SELECTED_IMAGE,
            imageMetadata = DocumentImageMetadata(source.length(), 48, 64),
        )
    }

    private fun <T> LibraryResult<T>.successValue(): T {
        assertTrue("Expected success but was $this", this is LibraryResult.Success)
        return (this as LibraryResult.Success).value
    }
}
