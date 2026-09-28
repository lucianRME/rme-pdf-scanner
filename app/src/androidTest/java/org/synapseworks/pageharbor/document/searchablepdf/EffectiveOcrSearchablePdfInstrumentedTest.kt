package org.synapseworks.pageharbor.document.searchablepdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.synapseworks.pageharbor.library.LibraryDao
import org.synapseworks.pageharbor.library.LibraryDatabase
import org.synapseworks.pageharbor.library.LibraryDocumentEntity
import org.synapseworks.pageharbor.library.LibraryFileStore
import org.synapseworks.pageharbor.library.LibraryOcrArtifactDraft
import org.synapseworks.pageharbor.library.LibraryOcrCommitResult
import org.synapseworks.pageharbor.library.LibraryOcrCorrectionAlignment
import org.synapseworks.pageharbor.library.LibraryOcrCorrectionDraft
import org.synapseworks.pageharbor.library.LibraryOcrLineDraft
import org.synapseworks.pageharbor.library.LibraryOcrStatus
import org.synapseworks.pageharbor.library.LibraryPageEntity
import org.synapseworks.pageharbor.library.LibraryRepository
import org.synapseworks.pageharbor.ocr.OcrDurablePageCurrentness
import org.synapseworks.pageharbor.ocr.OcrEngine
import org.synapseworks.pageharbor.ocr.OcrPage
import org.synapseworks.pageharbor.ocr.OcrPageLayout
import org.synapseworks.pageharbor.ocr.OcrPageResult
import org.synapseworks.pageharbor.ocr.OcrResult
import org.synapseworks.pageharbor.ocr.OcrPageAddress
import org.synapseworks.pageharbor.ocr.OcrRecognitionCurrentness
import org.synapseworks.pageharbor.ocr.OcrTextBounds
import org.synapseworks.pageharbor.ocr.OcrTextLine
import org.synapseworks.pageharbor.ocr.persistence.LibraryEffectiveOcrPageProvider

class EffectiveOcrSearchablePdfInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val root = File(context.cacheDir, "effective-ocr-pdf-test")
    private lateinit var database: LibraryDatabase
    private lateinit var dao: LibraryDao
    private lateinit var repository: LibraryRepository
    private lateinit var provider: LibraryEffectiveOcrPageProvider
    private lateinit var jpeg: ByteArray

    @Before
    fun setUp() {
        root.deleteRecursively()
        database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.libraryDao()
        repository = LibraryRepository(context, dao, LibraryFileStore(context, root))
        provider = LibraryEffectiveOcrPageProvider(repository)
        jpeg = jpegFixture()
    }

    @After
    fun tearDown() {
        database.close()
        root.deleteRecursively()
    }

    @Test
    fun rawLineAlignedAndFreeformCorrectionsMatchLibrarySearchAndExtractedPdf() = runBlocking {
        storeDocument()
        commitRaw("V0daf0ne Invoice", revisionTime = 10)
        assertPdfAndSearch("V0daf0ne Invoice", obsoleteText = null)

        val beforeAligned = requireNotNull(dao.ocrPageSnapshot(DOCUMENT_ID, PAGE_ID))
        assertEquals(
            LibraryOcrCommitResult.APPLIED,
            dao.saveOcrCorrection(
                beforeAligned,
                LibraryOcrCorrectionDraft(
                    correctedText = "Vodafone Invoice",
                    alignment = LibraryOcrCorrectionAlignment.LINE_ALIGNED,
                    correctedLines = listOf("Vodafone Invoice"),
                ),
                modifiedAt = 20,
            ),
        )
        assertPdfAndSearch("Vodafone Invoice", obsoleteText = "V0daf0ne Invoice")

        val beforeAddition = requireNotNull(dao.ocrPageSnapshot(DOCUMENT_ID, PAGE_ID))
        assertEquals(
            LibraryOcrCommitResult.APPLIED,
            dao.saveOcrCorrection(
                beforeAddition,
                LibraryOcrCorrectionDraft(
                    correctedText = "Vodafone Invoice Total",
                    alignment = LibraryOcrCorrectionAlignment.LINE_ALIGNED,
                    correctedLines = listOf("Vodafone Invoice Total"),
                ),
                modifiedAt = 25,
            ),
        )
        assertPdfAndSearch("Vodafone Invoice Total", obsoleteText = "V0daf0ne Invoice")

        val beforeRemoval = requireNotNull(dao.ocrPageSnapshot(DOCUMENT_ID, PAGE_ID))
        assertEquals(
            LibraryOcrCommitResult.APPLIED,
            dao.saveOcrCorrection(
                beforeRemoval,
                LibraryOcrCorrectionDraft(
                    correctedText = "Vodafone",
                    alignment = LibraryOcrCorrectionAlignment.LINE_ALIGNED,
                    correctedLines = listOf("Vodafone"),
                ),
                modifiedAt = 27,
            ),
        )
        assertPdfAndSearch("Vodafone", obsoleteText = "Invoice")

        val beforeFreeform = requireNotNull(dao.ocrPageSnapshot(DOCUMENT_ID, PAGE_ID))
        val freeform = "Vodafone Invoice corrected 日本語 भारत"
        assertEquals(
            LibraryOcrCommitResult.APPLIED,
            dao.saveOcrCorrection(
                beforeFreeform,
                LibraryOcrCorrectionDraft(freeform, LibraryOcrCorrectionAlignment.FREEFORM),
                modifiedAt = 30,
            ),
        )
        assertPdfAndSearch(freeform, obsoleteText = "V0daf0ne Invoice")
    }

    @Test
    fun reOcrKeepsCorrectionAuthoritativeUntilRevertThenUsesLatestRaw() = runBlocking {
        storeDocument()
        commitRaw("Initial raw invoice", revisionTime = 10)
        val beforeCorrection = requireNotNull(dao.ocrPageSnapshot(DOCUMENT_ID, PAGE_ID))
        assertEquals(
            LibraryOcrCommitResult.APPLIED,
            dao.saveOcrCorrection(
                beforeCorrection,
                LibraryOcrCorrectionDraft(
                    "Authoritative corrected invoice",
                    LibraryOcrCorrectionAlignment.FREEFORM,
                ),
                modifiedAt = 20,
            ),
        )

        commitRaw("Latest raw receipt", revisionTime = 30)
        assertPdfAndSearch("Authoritative corrected invoice", obsoleteText = "Latest raw receipt")

        val beforeRevert = requireNotNull(dao.ocrPageSnapshot(DOCUMENT_ID, PAGE_ID))
        assertEquals(LibraryOcrCommitResult.APPLIED, dao.revertOcrCorrection(beforeRevert, 40))
        assertPdfAndSearch("Latest raw receipt", obsoleteText = "Authoritative corrected invoice")
    }

    @Test
    fun staleProviderSnapshotFailsWithoutProducingAReplacementPdf() = runBlocking {
        storeDocument()
        commitRaw("Current invoice", revisionTime = 10)
        val staleRequest = effectiveRequest()
        commitRaw("Newer invoice", revisionTime = 20)
        val coordinator = coordinator()

        val prepared = coordinator.prepare(exportRequest(staleRequest))

        assertEquals(
            SearchablePdfPreparedExport.Failure(SearchablePdfPreparationError.OCR_RESULT_MISMATCH),
            prepared,
        )
    }

    @Test
    fun migratedFreeformCorrectionUsesFreshLayoutAndNeverExportsObsoleteRawText() = runBlocking {
        val expectedText = "Authoritative migrated correction"
        val obsoleteRawText = "Obsolete migrated raw text"
        val currentness = OcrRecognitionCurrentness(
            inputFingerprintVersion = 1,
            inputFingerprint = "unavailable:$PAGE_ID",
            durable = OcrDurablePageCurrentness(
                documentContentRevision = 1L,
                pageVisualRevision = 1L,
                ocrStateRevision = 4L,
                activeArtifactRevision = 2L,
            ),
        )
        val request = EffectiveOcrPageRequest(
            address = OcrPageAddress(DOCUMENT_ID, PAGE_ID),
            expectedCurrentness = currentness,
        )
        var recognitionCount = 0
        val coordinator = LocalSearchablePdfExportCoordinator(
            context = context,
            ocrEngine = object : OcrEngine {
                override fun recognize(pages: List<OcrPage>): OcrResult {
                    recognitionCount += pages.size
                    return OcrResult(
                        listOf(
                            OcrPageResult(
                                pageIndex = 0,
                                text = obsoleteRawText,
                                layout = OcrPageLayout(
                                    imageWidthPx = 320,
                                    imageHeightPx = 480,
                                    lines = listOf(
                                        OcrTextLine(
                                            text = obsoleteRawText,
                                            bounds = OcrTextBounds(32f, 96f, 288f, 144f),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    )
                }
            },
            openSourceInputStream = { ByteArrayInputStream(jpeg) },
        )
        val provider = EffectiveOcrPageProvider {
            EffectiveOcrPageProvision.CorrectedTextOnly(
                address = request.address,
                effectiveText = expectedText,
                currentness = currentness,
            )
        }

        val prepared = coordinator.prepare(
            SearchablePdfExportRequest(
                pageUris = listOf(Uri.parse("content://phase8-test/migrated.jpg")),
                effectiveOcrPageProvider = provider,
                effectiveOcrPageRequests = listOf(request),
            ),
        )

        assertTrue(prepared is SearchablePdfPreparedExport.Ready)
        prepared as SearchablePdfPreparedExport.Ready
        try {
            assertEquals(1, recognitionCount)
            ParcelFileDescriptor.open(
                prepared.temporaryFile,
                ParcelFileDescriptor.MODE_READ_ONLY,
            ).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    assertEquals(1, renderer.pageCount)
                }
            }
            PDDocument.load(prepared.temporaryFile).use { document ->
                val extracted = PDFTextStripper().getText(document)
                assertTrue(extracted.contains(expectedText))
                assertFalse(extracted.contains(obsoleteRawText))
            }
        } finally {
            coordinator.discardPreparedExport(prepared)
        }
    }

    @Test
    fun migratedUncorrectedArtifactUsesFreshPositionedRecognition() = runBlocking {
        val expectedText = "Fresh positioned migration text"
        val currentness = OcrRecognitionCurrentness(
            inputFingerprintVersion = 1,
            inputFingerprint = "unavailable:$PAGE_ID",
            durable = OcrDurablePageCurrentness(
                documentContentRevision = 1L,
                pageVisualRevision = 1L,
                ocrStateRevision = 1L,
                activeArtifactRevision = 1L,
            ),
        )
        val request = EffectiveOcrPageRequest(
            address = OcrPageAddress(DOCUMENT_ID, PAGE_ID),
            expectedCurrentness = currentness,
        )
        val coordinator = LocalSearchablePdfExportCoordinator(
            context = context,
            ocrEngine = object : OcrEngine {
                override fun recognize(pages: List<OcrPage>) = OcrResult(
                    listOf(
                        OcrPageResult(
                            pageIndex = 0,
                            text = expectedText,
                            layout = OcrPageLayout(
                                imageWidthPx = 320,
                                imageHeightPx = 480,
                                lines = listOf(
                                    OcrTextLine(
                                        text = expectedText,
                                        bounds = OcrTextBounds(32f, 96f, 288f, 144f),
                                    ),
                                ),
                            ),
                        ),
                    ),
                )
            },
            openSourceInputStream = { ByteArrayInputStream(jpeg) },
        )
        val prepared = coordinator.prepare(
            SearchablePdfExportRequest(
                pageUris = listOf(Uri.parse("content://phase8-test/legacy.jpg")),
                effectiveOcrPageProvider = EffectiveOcrPageProvider {
                    EffectiveOcrPageProvision.Unavailable(
                        EffectiveOcrPageUnavailableReason.POSITIONED_LAYOUT_NOT_AVAILABLE,
                    )
                },
                effectiveOcrPageRequests = listOf(request),
            ),
        )

        assertTrue(prepared is SearchablePdfPreparedExport.Ready)
        prepared as SearchablePdfPreparedExport.Ready
        try {
            PDDocument.load(prepared.temporaryFile).use { document ->
                assertTrue(PDFTextStripper().getText(document).contains(expectedText))
            }
        } finally {
            coordinator.discardPreparedExport(prepared)
        }
    }

    private suspend fun assertPdfAndSearch(expectedText: String, obsoleteText: String?) {
        val firstToken = expectedText.substringBefore(' ').lowercase() + "*"
        assertEquals(PAGE_ID, dao.pageSearchPage(firstToken, Long.MAX_VALUE, "", 10).single().pageId)
        assertEquals(expectedText, dao.ocrReviewPage(DOCUMENT_ID, PAGE_ID)?.effectiveText)
        val coordinator = coordinator()
        val prepared = coordinator.prepare(exportRequest(effectiveRequest()))
        assertTrue(prepared is SearchablePdfPreparedExport.Ready)
        prepared as SearchablePdfPreparedExport.Ready
        try {
            PDDocument.load(prepared.temporaryFile).use { document ->
                assertEquals(1, document.numberOfPages)
                assertTrue(document.getPage(0).resources.xObjectNames.iterator().hasNext())
                val extracted = PDFTextStripper().getText(document).trim()
                assertEquals(expectedText.replace(Regex("\\s+"), " "), extracted.replace(Regex("\\s+"), " "))
                obsoleteText?.let { assertFalse(extracted.contains(it)) }
            }
        } finally {
            coordinator.discardPreparedExport(prepared)
        }
    }

    private fun coordinator() = LocalSearchablePdfExportCoordinator(
        context = context,
        ocrEngine = object : OcrEngine {
            override fun recognize(pages: List<OcrPage>): OcrResult =
                error("Persisted OCR must not invoke recognition")
        },
        openSourceInputStream = { ByteArrayInputStream(jpeg) },
    )

    private fun exportRequest(request: EffectiveOcrPageRequest) = SearchablePdfExportRequest(
        pageUris = listOf(Uri.parse("content://phase7-test/page.jpg")),
        effectiveOcrPageProvider = provider,
        effectiveOcrPageRequests = listOf(request),
    )

    private suspend fun effectiveRequest(): EffectiveOcrPageRequest {
        val page = requireNotNull(dao.effectiveOcrPage(DOCUMENT_ID, PAGE_ID))
        return EffectiveOcrPageRequest(
            address = OcrPageAddress(DOCUMENT_ID, PAGE_ID),
            expectedCurrentness = OcrRecognitionCurrentness(
                inputFingerprintVersion = requireNotNull(page.inputFingerprintVersion),
                inputFingerprint = requireNotNull(page.inputFingerprint),
                durable = OcrDurablePageCurrentness(
                    documentContentRevision = page.documentContentRevision,
                    pageVisualRevision = page.pageVisualRevision,
                    ocrStateRevision = page.ocrStateRevision,
                    activeArtifactRevision = page.activeArtifactRevision,
                ),
            ),
        )
    }

    private suspend fun storeDocument() {
        dao.replaceDocument(
            LibraryDocumentEntity(
                documentId = DOCUMENT_ID,
                title = "Document 1",
                createdAtMillis = 1,
                modifiedAtMillis = 1,
                pageCount = 1,
                folderId = null,
                thumbnailRelativePath = null,
                ocrStatus = LibraryOcrStatus.NOT_INDEXED.name,
            ),
            listOf(
                LibraryPageEntity(
                    pageId = PAGE_ID,
                    documentId = DOCUMENT_ID,
                    position = 0,
                    relativePath = "page.jpg",
                    contentType = "image/jpeg",
                    sourceCategory = "TEST",
                    width = 320,
                    height = 480,
                    sourceByteCount = jpeg.size.toLong(),
                    rotationDegrees = 0,
                    filterName = "ORIGINAL",
                    ocrText = null,
                    ocrError = null,
                    contentSha256 = "a".repeat(64),
                ),
            ),
            "",
        )
    }

    private suspend fun commitRaw(text: String, revisionTime: Long) {
        val snapshot = requireNotNull(dao.ocrPageSnapshot(DOCUMENT_ID, PAGE_ID))
        assertEquals(
            LibraryOcrCommitResult.APPLIED,
            dao.commitOcrArtifact(
                snapshot,
                LibraryOcrArtifactDraft(
                    inputFingerprintVersion = 1,
                    inputFingerprint = "fingerprint-$revisionTime",
                    contentSha256 = requireNotNull(snapshot.contentSha256),
                    rotationDegrees = snapshot.rotationDegrees,
                    filterName = snapshot.filterName,
                    uprightWidth = 320,
                    uprightHeight = 480,
                    coordinateSystemVersion = 1,
                    transformVersion = 1,
                    actualScript = "LATIN",
                    recognizerId = "phase7-test-latin",
                    pipelineVersion = "phase7-test-v1",
                    clientVersion = null,
                    delivery = "BUNDLED",
                    recognizedAtMillis = revisionTime,
                    rawText = text,
                    lines = listOf(
                        LibraryOcrLineDraft(
                            lineOrdinal = 0,
                            rawText = text,
                            topLeftX = 0.1,
                            topLeftY = 0.2,
                            topRightX = 0.9,
                            topRightY = 0.2,
                            bottomRightX = 0.9,
                            bottomRightY = 0.3,
                            bottomLeftX = 0.1,
                            bottomLeftY = 0.3,
                            baselineStartX = 0.1,
                            baselineStartY = 0.28,
                            baselineEndX = 0.9,
                            baselineEndY = 0.28,
                            baselineAngleDegrees = 0.0,
                        ),
                    ),
                ),
                revisionTime,
            ),
        )
    }

    private fun jpegFixture(): ByteArray {
        val bitmap = Bitmap.createBitmap(320, 480, Bitmap.Config.ARGB_8888)
        return try {
            Canvas(bitmap).drawColor(Color.rgb(230, 235, 240))
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val DOCUMENT_ID = "phase7-document"
        const val PAGE_ID = "phase7-page"
    }
}
