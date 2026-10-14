package org.synapseworks.pageharbor.library

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.synapseworks.pageharbor.PageHarborSessionViewModel
import org.synapseworks.pageharbor.R
import org.synapseworks.pageharbor.document.session.DocumentImageMetadata
import org.synapseworks.pageharbor.document.session.DocumentPage
import org.synapseworks.pageharbor.document.session.DocumentPageId
import org.synapseworks.pageharbor.document.session.DocumentPageRotation
import org.synapseworks.pageharbor.document.session.DocumentResource
import org.synapseworks.pageharbor.document.session.DocumentResourceOwnership
import org.synapseworks.pageharbor.document.session.DocumentSession
import org.synapseworks.pageharbor.document.session.DocumentSourceCategory
import org.synapseworks.pageharbor.ocr.OcrPageLayout
import org.synapseworks.pageharbor.ocr.OcrPageResult
import org.synapseworks.pageharbor.ocr.OcrResult
import org.synapseworks.pageharbor.ocr.OcrTextBounds
import org.synapseworks.pageharbor.ocr.OcrTextLine
import org.synapseworks.pageharbor.ocr.persistence.BundledLatinOcrArtifactContract

@RunWith(AndroidJUnit4::class)
class LibraryRepositoryInstrumentedTest {
    private val restartDatabaseName = "library-restart-test.db"
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: LibraryDatabase
    private lateinit var repository: LibraryRepository
    private lateinit var fileStore: LibraryFileStore
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
        fileStore = LibraryFileStore(context, libraryDirectory)
        repository = LibraryRepository(
            context = context,
            dao = database.libraryDao(),
            fileStore = fileStore,
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
        assertEquals(listOf(null, "alpha term"), database.libraryDao().pages(saved.id).map { it.ocrText })
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
        val exactTitle = repository.searchHits("March invoice", limit = 10).first()
        assertEquals(invoice.id, exactTitle.documentId)
        assertEquals(LibrarySearchMatch.TITLE, exactTitle.matchType)
        val ocrMatch = repository.observeSearch("consult")!!.first().single()
        assertEquals(invoice.id, ocrMatch.id)
        assertEquals(LibrarySearchMatch.OCR, ocrMatch.searchMatch)

        val folder = repository.createFolder("Taxes").successValue()
        repository.moveDocument(invoice.id, folder.id).successValue()
        assertEquals(folder.id, repository.openDocument(invoice.id).successValue().summary.folderId)
        val metadataMatch = repository.searchHits("taxes", limit = 10).single()
        assertEquals(invoice.id, metadataMatch.documentId)
        assertEquals(LibrarySearchMatch.FOLDER, metadataMatch.matchType)
        repository.deleteFolder(folder.id).successValue()
        assertEquals(null, repository.openDocument(invoice.id).successValue().summary.folderId)
    }

    @Test
    fun documentDateSortIsDeterministicAndNeverSubstitutesFileOrImportTimes() = runBlocking {
        val dao = database.libraryDao()
        suspend fun insert(
            id: String,
            documentDate: Long?,
            importedAt: Long?,
            sourceModifiedAt: Long?,
        ) {
            dao.replaceDocument(
                LibraryDocumentEntity(
                    documentId = id,
                    title = id,
                    createdAtMillis = 1,
                    modifiedAtMillis = 2,
                    pageCount = 1,
                    folderId = null,
                    thumbnailRelativePath = null,
                    ocrStatus = LibraryOcrStatus.NOT_INDEXED.name,
                    sourceModifiedAtMillis = sourceModifiedAt,
                    importedAtMillis = importedAt,
                    documentDateEpochDay = documentDate,
                    documentDateProvenance = documentDate?.let {
                        LibraryDocumentDateProvenance.USER_CONFIRMED.name
                    },
                    documentDateConfidence = documentDate?.let {
                        LibraryDocumentDateConfidence.EXACT.name
                    },
                ),
                listOf(
                    LibraryPageEntity(
                        pageId = "page-$id",
                        documentId = id,
                        position = 0,
                        relativePath = "$id/page.jpg",
                        contentType = "image/jpeg",
                        sourceCategory = "TEST",
                        width = 10,
                        height = 10,
                        sourceByteCount = 1,
                        rotationDegrees = 0,
                        filterName = "ORIGINAL",
                        ocrText = null,
                        ocrError = null,
                        contentSha256 = "hash-$id",
                    ),
                ),
                "",
            )
        }

        insert("dated-old", documentDate = 10, importedAt = 900, sourceModifiedAt = 1_000)
        insert("dated-new", documentDate = 20, importedAt = 100, sourceModifiedAt = 50)
        insert("unknown-new-import", documentDate = null, importedAt = 800, sourceModifiedAt = 9_999)
        insert("unknown-old-import", documentDate = null, importedAt = 700, sourceModifiedAt = 10_000)
        insert("unknown-no-import", documentDate = null, importedAt = null, sourceModifiedAt = 20_000)

        val ordered = dao.observeDocumentsByDocumentDate(null).first()
        assertEquals(
            listOf(
                "dated-new",
                "dated-old",
                "unknown-new-import",
                "unknown-old-import",
                "unknown-no-import",
            ),
            ordered.map(LibraryDocumentListingRow::documentId),
        )
        val unknown = ordered.associateBy(LibraryDocumentListingRow::documentId)
            .getValue("unknown-no-import")
        assertEquals(null, unknown.documentDateEpochDay)
        assertEquals(null, unknown.documentDateProvenance)
    }

    @Test
    fun boundedSearchKeepsTitleFirstAndEveryStablePageAtItsCurrentPosition() = runBlocking {
        val saved = repository.saveSession(
            session(
                page(10, "first-search.jpg", 0xff112233.toInt()),
                page(11, "second-search.jpg", 0xff223344.toInt()),
                page(12, "third-search.jpg", 0xff334455.toInt()),
            ),
            "Needle contract",
            ocrResult = OcrResult(
                listOf(
                    OcrPageResult(0, "ordinary first page"),
                    OcrPageResult(1, "needle amount due 42.16"),
                    OcrPageResult(2, "needle invoice date September"),
                ),
            ),
        ).successValue()
        val opened = repository.openDocument(saved.id).successValue().session
        val stableIds = opened.pages.map { requireNotNull(it.persistentId) }

        val initial = repository.searchHits("needle", limit = 10)
        assertEquals(LibrarySearchMatch.TITLE, initial.first().matchType)
        assertEquals(
            listOf(stableIds[1] to 1, stableIds[2] to 2),
            initial.filter { it.matchType == LibrarySearchMatch.OCR }
                .map { it.pageId to it.currentPagePosition },
        )
        assertTrue(initial.filter { it.matchType == LibrarySearchMatch.OCR }.all { hit ->
            hit.snippet?.contains("needle", ignoreCase = true) == true
        })

        val reordered = requireNotNull(
            opened.reorder(listOf(opened.pages[2].id, opened.pages[0].id, opened.pages[1].id)),
        )
        repository.saveSession(reordered, "Needle contract").successValue()

        val afterReorder = repository.searchHits("needle", limit = 10)
            .filter { it.matchType == LibrarySearchMatch.OCR }
        assertEquals(
            listOf(stableIds[2] to 0, stableIds[1] to 2),
            afterReorder.map { it.pageId to it.currentPagePosition },
        )

        val reorderedOpened = repository.openDocument(saved.id).successValue().session
        val withoutFormerThird = requireNotNull(
            reorderedOpened.remove(reorderedOpened.pages.first().id),
        )
        repository.saveSession(withoutFormerThird, "Needle contract").successValue()
        val afterDelete = repository.searchHits("needle", limit = 10)
            .filter { it.matchType == LibrarySearchMatch.OCR }
        assertEquals(listOf(stableIds[1]), afterDelete.map { it.pageId })
        assertEquals(1, afterDelete.single().currentPagePosition)
    }

    @Test
    fun activeBoundedSearchObservesEffectiveOcrCorrectionWithoutRetyping() = runBlocking {
        val saved = repository.saveSession(
            session(page(20, "reactive-search.jpg", 0xff556677.toInt())),
            "Reactive search",
            ocrResult = OcrResult(listOf(OcrPageResult(0, "original searchable phrase"))),
        ).successValue()
        val pageId = database.libraryDao().pages(saved.id).single().pageId
        val snapshot = requireNotNull(database.libraryDao().ocrPageSnapshot(saved.id, pageId))
        val correctedEmission = async {
            repository.observeSearchHits("corrected phrase", limit = 10)!!
                .filter(List<LibrarySearchHit>::isNotEmpty)
                .first()
        }

        assertEquals(
            LibraryOcrCommitResult.APPLIED,
            repository.saveOcrCorrection(
                snapshot,
                LibraryOcrCorrectionDraft(
                    correctedText = "corrected phrase amount 42.16",
                    alignment = LibraryOcrCorrectionAlignment.FREEFORM,
                ),
            ),
        )

        val hits = withTimeout(2_000L) { correctedEmission.await() }
        assertEquals(pageId, hits.single().pageId)
        assertTrue(repository.searchHits("original searchable", limit = 10).isEmpty())
    }

    @Test
    fun firstSavePromotesPositionedOcrToVerifiedPageArtifact() = runBlocking {
        val saved = repository.saveSession(
            session(page(3, "verified-first-save.jpg", 0xffccddee.toInt())),
            "Verified first save",
            ocrResult = OcrResult(
                listOf(
                    OcrPageResult(
                        pageIndex = 0,
                        text = "positioned emerald text",
                        layout = OcrPageLayout(
                            imageWidthPx = 100,
                            imageHeightPx = 200,
                            lines = listOf(
                                OcrTextLine(
                                    text = "positioned emerald text",
                                    bounds = OcrTextBounds(
                                        left = 10f,
                                        top = 20f,
                                        right = 90f,
                                        bottom = 60f,
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        ).successValue()

        val dao = database.libraryDao()
        val page = dao.pages(saved.id).single()
        val snapshot = requireNotNull(dao.ocrPageSnapshot(saved.id, page.pageId))
        val artifact = requireNotNull(
            dao.ocrArtifact(page.pageId, requireNotNull(snapshot.activeArtifactRevision)),
        )
        val line = dao.ocrLines(page.pageId, artifact.artifactRevision).single()

        assertEquals(LibraryOcrArtifactVerification.CURRENT_VERIFIED.name, artifact.verificationState)
        assertEquals("positioned emerald text", artifact.rawText)
        assertEquals(page.contentSha256, artifact.contentSha256)
        assertEquals(snapshot.documentContentRevision, artifact.capturedDocumentContentRevision)
        assertEquals(snapshot.pageVisualRevision, artifact.capturedPageVisualRevision)
        assertEquals(BundledLatinOcrArtifactContract.RECOGNIZER_ID, artifact.recognizerId)
        assertEquals(BundledLatinOcrArtifactContract.PIPELINE_VERSION, artifact.pipelineVersion)
        assertTrue(artifact.inputFingerprint?.matches(Regex("sha256:[0-9a-f]{64}")) == true)
        assertEquals(0.1, line.topLeftX, 0.000_001)
        assertEquals(0.3, line.bottomRightY, 0.000_001)
        assertEquals(
            "positioned emerald text",
            dao.effectiveOcrPage(saved.id, page.pageId)?.effectiveText,
        )
        assertEquals(
            page.pageId,
            dao.pageSearchPage("emerald*", Long.MAX_VALUE, "", 10).single().pageId,
        )
    }

    @Test
    fun firstSaveWithoutPositionedGeometryRetainsLegacyRawText() = runBlocking {
        val rawText = "legacy fallback sapphire"
        val saved = repository.saveSession(
            session(page(4, "legacy-first-save.jpg", 0xffbbccdd.toInt())),
            "Legacy first save",
            ocrResult = OcrResult(listOf(OcrPageResult(pageIndex = 0, text = rawText))),
        ).successValue()

        val dao = database.libraryDao()
        val page = dao.pages(saved.id).single()
        val snapshot = requireNotNull(dao.ocrPageSnapshot(saved.id, page.pageId))
        val artifact = requireNotNull(
            dao.ocrArtifact(page.pageId, requireNotNull(snapshot.activeArtifactRevision)),
        )

        assertEquals(LibraryOcrArtifactVerification.LEGACY_UNVERIFIED.name, artifact.verificationState)
        assertEquals(rawText, artifact.rawText)
        assertEquals(rawText, page.ocrText)
        assertEquals(rawText, dao.effectiveOcrPage(saved.id, page.pageId)?.effectiveText)
        assertEquals(
            page.pageId,
            dao.pageSearchPage("sapphire*", Long.MAX_VALUE, "", 10).single().pageId,
        )
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
        val untouched = repository.saveSession(
            session(page(2, "untouched-document.jpg", 0xff765432.toInt())),
            title = "Untouched document",
        ).successValue()
        val untouchedDirectory = File(libraryDirectory, untouched.id)
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
        assertNotNull(database.libraryDao().document(untouched.id))
        assertTrue(untouchedDirectory.isDirectory)
        assertEquals(
            LibraryResult.Failure(LibraryError.DOCUMENT_NOT_FOUND),
            repository.deleteDocument(saved.id),
        )
    }

    @Test
    fun deleteDoesNotReportSuccessWhenPrivateDocumentDirectoryCannotBeRemoved() = runBlocking {
        val saved = repository.saveSession(
            session(page(1, "undeletable-private-copy.jpg", 0xff123456.toInt())),
            title = "Deletion failure contract",
        ).successValue()
        val documentDirectory = File(libraryDirectory, saved.id)
        assertTrue(documentDirectory.isDirectory)

        val directories = documentDirectory.walkTopDown()
            .filter(File::isDirectory)
            .toList()
            .asReversed()
        directories.forEach { directory ->
            assertTrue("Could not make ${directory.name} read-only", directory.setWritable(false, false))
        }

        try {
            val result = repository.deleteDocument(saved.id)

            assertFalse(
                "Deletion reported success while the private document directory still existed",
                result is LibraryResult.Success && documentDirectory.exists(),
            )
            if (result is LibraryResult.Failure) {
                assertNotNull(database.libraryDao().document(saved.id))
            }
        } finally {
            directories.asReversed().forEach { directory ->
                directory.setWritable(true, false)
            }
        }
    }

    @Test
    fun committedDeletionReportsDurablePendingCleanupUntilBytesAreRemoved() = runBlocking {
        val saved = repository.saveSession(
            session(page(1, "pending-private-cleanup.jpg", 0xff1a2b3c.toInt())),
            title = "Pending private cleanup",
        ).successValue()
        val documentDirectory = File(libraryDirectory, saved.id)
        val protectedDirectories = documentDirectory.walkTopDown()
            .filter { candidate -> candidate.isDirectory && candidate != documentDirectory }
            .toList()
            .asReversed()
        protectedDirectories.forEach { directory ->
            assertTrue("Could not make ${directory.name} read-only", directory.setWritable(false, false))
        }

        var pending: PendingDocumentDeletion? = null
        try {
            val result = repository.deleteDocument(saved.id)

            assertTrue("Expected cleanup-pending success but was $result", result is LibraryResult.Success)
            assertEquals(
                LibraryWarning.DELETION_CLEANUP_PENDING,
                (result as LibraryResult.Success).warning,
            )
            assertEquals(null, database.libraryDao().document(saved.id))
            assertFalse(documentDirectory.exists())
            pending = pendingDeletions().single()
        } finally {
            pending?.directory?.walkTopDown()
                ?.filter(File::isDirectory)
                ?.toList()
                ?.asReversed()
                ?.forEach { directory -> directory.setWritable(true, false) }
            protectedDirectories.forEach { directory -> directory.setWritable(true, false) }
        }

        val recovery = repository.recoverPendingDocumentDeletions()
        assertEquals(1, recovery.deleted)
        assertEquals(0, recovery.stillPending)
        assertTrue(pendingDeletions().isEmpty())
    }

    @Test
    fun databaseDeleteFailureRestoresQuarantinedFilesAndKeepsRecord() = runBlocking {
        val saved = repository.saveSession(
            session(page(1, "database-delete-failure.jpg", 0xff234567.toInt())),
            title = "Database failure rollback",
        ).successValue()
        val documentDirectory = File(libraryDirectory, saved.id)
        val sqlite = database.openHelper.writableDatabase
        sqlite.execSQL(
            """
            CREATE TRIGGER fail_library_document_delete
            BEFORE DELETE ON library_documents
            BEGIN
                SELECT RAISE(ABORT, 'forced deletion failure');
            END
            """.trimIndent(),
        )

        val result = try {
            repository.deleteDocument(saved.id)
        } finally {
            sqlite.execSQL("DROP TRIGGER fail_library_document_delete")
        }

        assertEquals(LibraryResult.Failure(LibraryError.DATABASE_UNAVAILABLE), result)
        assertNotNull(database.libraryDao().document(saved.id))
        assertTrue(documentDirectory.isDirectory)
        assertTrue(pendingDeletions().isEmpty())
    }

    @Test
    fun repeatedDeleteTracksQuarantineWhenDatabaseAndRestorationPreviouslyFailed() = runBlocking {
        var failRestoration = true
        var failCleanup = true
        val failingStore = object : LibraryFileStore(context, libraryDirectory) {
            override fun restoreQuarantinedDocument(pending: PendingDocumentDeletion): Boolean =
                if (failRestoration) false else super.restoreQuarantinedDocument(pending)

            override fun completeDocumentDeletion(pending: PendingDocumentDeletion): Boolean =
                if (failCleanup) false else super.completeDocumentDeletion(pending)
        }
        val failingRepository = LibraryRepository(
            context = context,
            dao = database.libraryDao(),
            fileStore = failingStore,
            nowMillis = { ++clock },
        )
        val saved = failingRepository.saveSession(
            session(page(1, "rollback-and-cleanup-failure.jpg", 0xff334455.toInt())),
            title = "Tracked repeated deletion",
        ).successValue()
        val sqlite = database.openHelper.writableDatabase
        sqlite.execSQL(
            """
            CREATE TRIGGER fail_repeated_library_document_delete
            BEFORE DELETE ON library_documents
            BEGIN
                SELECT RAISE(ABORT, 'forced deletion failure');
            END
            """.trimIndent(),
        )

        val first = try {
            failingRepository.deleteDocument(saved.id)
        } finally {
            sqlite.execSQL("DROP TRIGGER fail_repeated_library_document_delete")
        }

        assertEquals(LibraryResult.Failure(LibraryError.DELETION_RECOVERY_PENDING), first)
        assertNotNull(database.libraryDao().document(saved.id))
        assertFalse(File(libraryDirectory, saved.id).exists())
        assertEquals(saved.id, pendingDeletions(failingStore).single().documentId)

        val repeated = failingRepository.deleteDocument(saved.id)
        assertTrue(repeated is LibraryResult.Success)
        assertEquals(
            LibraryWarning.DELETION_CLEANUP_PENDING,
            (repeated as LibraryResult.Success).warning,
        )
        assertEquals(null, database.libraryDao().documentAnyState(saved.id))
        assertEquals(saved.id, pendingDeletions(failingStore).single().documentId)

        failRestoration = false
        failCleanup = false
        val recovered = failingRepository.recoverPendingDocumentDeletions()
        assertEquals(1, recovered.deleted)
        assertEquals(0, recovered.stillPending)
        assertTrue(pendingDeletions(failingStore).isEmpty())
    }

    @Test
    fun transientRestorationFailureRecoversDocumentWithoutDeletingIt() = runBlocking {
        var failRestoration = true
        val failingStore = object : LibraryFileStore(context, libraryDirectory) {
            override fun restoreQuarantinedDocument(pending: PendingDocumentDeletion): Boolean =
                if (failRestoration) false else super.restoreQuarantinedDocument(pending)
        }
        val failingRepository = LibraryRepository(
            context = context,
            dao = database.libraryDao(),
            fileStore = failingStore,
            nowMillis = { ++clock },
        )
        val saved = failingRepository.saveSession(
            session(page(1, "transient-restoration.jpg", 0xff445566.toInt())),
            title = "Transient restoration",
        ).successValue()
        val sqlite = database.openHelper.writableDatabase
        sqlite.execSQL(
            """
            CREATE TRIGGER fail_transient_library_document_delete
            BEFORE DELETE ON library_documents
            BEGIN
                SELECT RAISE(ABORT, 'forced deletion failure');
            END
            """.trimIndent(),
        )
        try {
            assertEquals(
                LibraryResult.Failure(LibraryError.DELETION_RECOVERY_PENDING),
                failingRepository.deleteDocument(saved.id),
            )
        } finally {
            sqlite.execSQL("DROP TRIGGER fail_transient_library_document_delete")
        }

        failRestoration = false
        val recovery = failingRepository.recoverPendingDocumentDeletions()
        assertEquals(1, recovery.restored)
        assertEquals(0, recovery.stillPending)
        assertNotNull(database.libraryDao().document(saved.id))
        assertTrue(File(libraryDirectory, saved.id).isDirectory)
        assertTrue(pendingDeletions(failingStore).isEmpty())
    }

    @Test
    fun restartRecoveryResolvesBothInterruptionWindows() = runBlocking {
        val persistentStore = LibraryFileStore(context, libraryDirectory)
        var persistentDatabase = Room.databaseBuilder(
            context,
            LibraryDatabase::class.java,
            restartDatabaseName,
        ).build()
        var persistentRepository = LibraryRepository(
            context = context,
            dao = persistentDatabase.libraryDao(),
            fileStore = persistentStore,
            nowMillis = { ++clock },
        )
        val saved = persistentRepository.saveSession(
            session(page(1, "interrupted-delete.jpg", 0xff345678.toInt())),
            title = "Interrupted deletion",
        ).successValue()
        val documentDirectory = File(libraryDirectory, saved.id)

        val beforeCommit = persistentStore.quarantineDocument(saved.id)
        assertTrue(beforeCommit is DocumentDeletionPreparation.Quarantined)
        assertFalse(documentDirectory.exists())
        persistentDatabase.close()

        persistentDatabase = Room.databaseBuilder(
            context = context,
            klass = LibraryDatabase::class.java,
            name = restartDatabaseName,
        ).build()
        persistentRepository = LibraryRepository(
            context = context,
            dao = persistentDatabase.libraryDao(),
            fileStore = persistentStore,
            nowMillis = { ++clock },
        )
        val restored = persistentRepository.recoverPendingDocumentDeletions()
        assertEquals(1, restored.restored)
        assertTrue(documentDirectory.isDirectory)
        assertNotNull(persistentDatabase.libraryDao().document(saved.id))

        val afterCommit = persistentStore.quarantineDocument(saved.id)
        assertTrue(afterCommit is DocumentDeletionPreparation.Quarantined)
        assertNotNull(persistentDatabase.libraryDao().deleteDocument(saved.id))
        persistentDatabase.close()

        persistentDatabase = Room.databaseBuilder(
            context = context,
            klass = LibraryDatabase::class.java,
            name = restartDatabaseName,
        ).build()
        persistentRepository = LibraryRepository(
            context = context,
            dao = persistentDatabase.libraryDao(),
            fileStore = persistentStore,
            nowMillis = { ++clock },
        )
        try {
            val deleted = persistentRepository.recoverPendingDocumentDeletions()
            assertEquals(1, deleted.deleted)
            assertEquals(0, deleted.stillPending)
            assertFalse(documentDirectory.exists())
            assertTrue(pendingDeletions(persistentStore).isEmpty())
        } finally {
            persistentDatabase.close()
        }
    }

    @Test
    fun partialQuarantineCleanupRemainsPendingUntilRetrySucceeds() = runBlocking {
        val saved = repository.saveSession(
            session(page(1, "partial-cleanup.jpg", 0xff56789a.toInt())),
            title = "Partial cleanup",
        ).successValue()
        val prepared = fileStore.quarantineDocument(saved.id)
        assertTrue(prepared is DocumentDeletionPreparation.Quarantined)
        val pending = (prepared as DocumentDeletionPreparation.Quarantined).pending
        val directories = pending.directory.walkTopDown()
            .filter(File::isDirectory)
            .toList()
            .asReversed()
        directories.forEach { directory ->
            assertTrue("Could not make ${directory.name} read-only", directory.setWritable(false, false))
        }
        assertNotNull(database.libraryDao().deleteDocument(saved.id))

        val firstRecovery = repository.recoverPendingDocumentDeletions()
        assertEquals(0, firstRecovery.deleted)
        assertEquals(1, firstRecovery.stillPending)
        assertEquals(saved.id, pendingDeletions().single().documentId)

        pending.directory.walkTopDown()
            .filter(File::isDirectory)
            .toList()
            .asReversed()
            .forEach { directory -> directory.setWritable(true, false) }
        val secondRecovery = repository.recoverPendingDocumentDeletions()
        assertEquals(1, secondRecovery.deleted)
        assertEquals(0, secondRecovery.stillPending)
        assertTrue(pendingDeletions().isEmpty())
    }

    @Test
    fun legacyConfirmedOrphanIsQuarantinedAndCleanupIsIdempotent() = runBlocking {
        val orphanId = "legacy-confirmed-orphan"
        val orphan = createLegacyDocumentDirectory(orphanId)

        val first = repository.recoverPendingDocumentDeletions()
        assertEquals(1, first.legacyQuarantined)
        assertEquals(1, first.deleted)
        assertEquals(0, first.stillPending)
        assertFalse(orphan.exists())

        val second = repository.recoverPendingDocumentDeletions()
        assertEquals(0, second.legacyQuarantined)
        assertEquals(0, second.deleted)
        assertEquals(0, second.stillPending)
    }

    @Test
    fun legacyDirectoryOwnedByNonterminalMigrationIsPreserved() = runBlocking {
        val documentId = "migration-owned-document"
        val owned = createLegacyDocumentDirectory(documentId)
        val operation = migrationOperation("migration-owner")
        database.libraryDao().insertOperation(operation)
        database.libraryDao().insertOperationItems(
            listOf(migrationItem(operation.operationId, documentId)),
        )

        val recovery = repository.recoverPendingDocumentDeletions()

        assertEquals(0, recovery.legacyQuarantined)
        assertEquals(1, recovery.legacyPreservedOwned)
        assertTrue(owned.isDirectory)
        assertTrue(pendingDeletions().isEmpty())
    }

    @Test
    fun uncertainLegacyStateIsPreservedWhenDatabaseCannotClassifyOwnership() = runBlocking {
        val documentId = "database-uncertain-document"
        val candidate = createLegacyDocumentDirectory(documentId)
        database.close()

        val recovery = repository.recoverPendingDocumentDeletions()

        assertEquals(0, recovery.legacyQuarantined)
        assertEquals(1, recovery.legacyPreservedUncertain)
        assertTrue(recovery.databaseUnavailable)
        assertTrue(candidate.isDirectory)
    }

    @Test
    fun pendingEnumerationFailureIsNotTreatedAsAnEmptyQuarantine() = runBlocking {
        val candidate = createLegacyDocumentDirectory("preserved-when-pending-scan-fails")
        val unavailableStore = object : LibraryFileStore(context, libraryDirectory) {
            override fun pendingDocumentDeletions(): DocumentDirectoryScan<PendingDocumentDeletion> =
                DocumentDirectoryScan.Unavailable
        }
        val unavailableRepository = LibraryRepository(
            context = context,
            dao = database.libraryDao(),
            fileStore = unavailableStore,
            nowMillis = { ++clock },
        )

        val recovery = unavailableRepository.recoverPendingDocumentDeletions()

        assertTrue(recovery.filesystemScanUnavailable)
        assertEquals(0, recovery.legacyQuarantined)
        assertTrue(candidate.isDirectory)
    }

    @Test
    fun mixedLegacyStatesDeleteOnlyConfirmedAbandonedDocumentBytes() = runBlocking {
        val active = repository.saveSession(
            session(page(1, "mixed-active.jpg", 0xff667788.toInt())),
            title = "Mixed active",
        ).successValue()
        val activeDirectory = File(libraryDirectory, active.id)
        val orphan = createLegacyDocumentDirectory("mixed-confirmed-orphan")
        val uncertain = File(libraryDirectory, "mixed-unknown-shape").apply {
            assertTrue(mkdirs())
            File(this, "unrecognized-private-data").writeBytes(byteArrayOf(1, 2, 3))
        }
        val operationOwned = createLegacyDocumentDirectory("mixed-operation-owned")
        val operation = migrationOperation("mixed-migration")
        database.libraryDao().insertOperation(operation)
        database.libraryDao().insertOperationItems(
            listOf(migrationItem(operation.operationId, operationOwned.name)),
        )

        val recovery = repository.recoverPendingDocumentDeletions()

        assertEquals(1, recovery.legacyQuarantined)
        assertEquals(1, recovery.deleted)
        assertEquals(2, recovery.legacyPreservedOwned)
        assertTrue(activeDirectory.isDirectory)
        assertFalse(orphan.exists())
        assertTrue(operationOwned.isDirectory)
        assertTrue(uncertain.isDirectory)
    }

    @Test
    fun deletionWaitsForRealDocumentSessionLeaseBeforeRemovingLibrarySources() = runBlocking {
        val saved = repository.saveSession(
            session(page(1, "leased-library-source.jpg", 0xff778899.toInt())),
            title = "Leased library source",
        ).successValue()
        val opened = repository.openDocument(saved.id).successValue()
        val sessionViewModel = PageHarborSessionViewModel()
        sessionViewModel.installDocumentSessionForTest(opened.session)
        val lease = requireNotNull(sessionViewModel.acquireDocumentSessionLease())
        val source = File(requireNotNull(opened.session.pages.single().source.ownedLibraryFile).path)

        val deletion = async {
            sessionViewModel.awaitDocumentSessionLeasesReleased()
            repository.deleteDocument(saved.id)
        }
        yield()
        assertFalse("Deletion bypassed an active DocumentSessionLease", deletion.isCompleted)
        assertTrue(source.isFile)
        assertNotNull(database.libraryDao().document(saved.id))

        sessionViewModel.releaseDocumentSessionLease(lease)
        deletion.await().successValue()
        assertFalse(source.exists())
        assertEquals(null, database.libraryDao().documentAnyState(saved.id))
    }

    @Test
    fun deletionMessagesDistinguishPendingCleanupAndPendingRestoration() {
        assertEquals(
            R.string.library_delete_cleanup_pending,
            org.synapseworks.pageharbor.ui.home.libraryActionMessageResource(
                LibraryActionState.Succeeded(
                    eventId = 1,
                    action = LibraryActionSuccess.DELETED,
                    warning = LibraryWarning.DELETION_CLEANUP_PENDING,
                ),
            ),
        )
        assertEquals(
            R.string.library_error_deletion_recovery_pending,
            org.synapseworks.pageharbor.ui.home.libraryActionMessageResource(
                LibraryActionState.Failed(2, LibraryError.DELETION_RECOVERY_PENDING),
            ),
        )
        assertEquals(
            R.string.library_error_deletion_state_uncertain,
            org.synapseworks.pageharbor.ui.home.libraryActionMessageResource(
                LibraryActionState.Failed(3, LibraryError.DELETION_STATE_UNCERTAIN),
            ),
        )
    }

    @Test
    fun deletionWaitsForActiveStableRevisionSnapshot() = runBlocking {
        val gate = LibraryOperationGate()
        val gatedRepository = LibraryRepository(
            context = context,
            dao = database.libraryDao(),
            fileStore = fileStore,
            nowMillis = { ++clock },
            operationGate = gate,
        )
        val saved = gatedRepository.saveSession(
            session(page(1, "stable-snapshot.jpg", 0xff456789.toInt())),
            title = "Stable snapshot",
        ).successValue()
        val documentDirectory = File(libraryDirectory, saved.id)
        val snapshotStarted = CompletableDeferred<Unit>()
        val releaseSnapshot = CompletableDeferred<Unit>()
        val snapshot = async {
            gate.withStableSnapshot {
                assertTrue(documentDirectory.isDirectory)
                snapshotStarted.complete(Unit)
                releaseSnapshot.await()
                assertTrue(documentDirectory.isDirectory)
            }
        }
        snapshotStarted.await()

        val deletion = async { gatedRepository.deleteDocument(saved.id) }
        yield()
        assertFalse("Deletion bypassed the active revision snapshot", deletion.isCompleted)
        releaseSnapshot.complete(Unit)
        snapshot.await()
        deletion.await().successValue()

        assertFalse(documentDirectory.exists())
        assertEquals(null, database.libraryDao().document(saved.id))
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

    private fun pendingDeletions(
        store: LibraryFileStore = fileStore,
    ): List<PendingDocumentDeletion> =
        when (val scan = store.pendingDocumentDeletions()) {
            is DocumentDirectoryScan.Available -> scan.entries
            DocumentDirectoryScan.Unavailable -> error("Pending-deletion directory was unavailable")
        }

    private fun createLegacyDocumentDirectory(documentId: String): File {
        val revision = File(libraryDirectory, "$documentId/revisions/revision-1")
        assertTrue(revision.mkdirs())
        File(revision, "page.jpg").writeBytes(byteArrayOf(1, 2, 3, 4))
        return File(libraryDirectory, documentId)
    }

    private fun migrationOperation(operationId: String) = LibraryDataOperationEntity(
        operationId = operationId,
        operationType = "VERIFIED_MIGRATION",
        phase = "STAGING",
        sourceKind = "SAF_TREE",
        sourceRootUri = null,
        sourceGrantFlags = 0,
        createdAtMillis = ++clock,
        updatedAtMillis = clock,
        discoveredItemCount = 1,
        plannedDocumentCount = 1,
        preparedDocumentCount = 0,
        importedDocumentCount = 0,
        skippedDuplicateCount = 0,
        failedItemCount = 0,
        totalSourceBytes = 4,
        processedSourceBytes = 0,
        cancelRequested = false,
        terminalErrorCode = null,
    )

    private fun migrationItem(
        operationId: String,
        documentId: String,
    ) = LibraryDataOperationItemEntity(
        itemId = "$operationId-item",
        operationId = operationId,
        ordinal = 0,
        itemState = "STAGING",
        targetDocumentId = documentId,
        targetRevisionId = null,
        proposedTitle = "Migration candidate",
        proposedFolderPath = null,
        sourceModifiedAtMillis = null,
        sourceByteCount = 4,
        sourcePageCount = 1,
        logicalHashVersion = null,
        logicalSha256 = null,
        duplicateKind = "DIFFERENT",
        duplicateDocumentId = null,
        duplicateDecision = "IMPORT",
        failureCode = null,
    )
}
