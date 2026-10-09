package org.synapseworks.pageharbor.document.redaction

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.os.Debug
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.CoroutineContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.synapseworks.pageharbor.document.session.DocumentImageMetadata
import org.synapseworks.pageharbor.document.session.DocumentPageRotation
import org.synapseworks.pageharbor.document.searchablepdf.SearchablePdfGenerationResult
import org.synapseworks.pageharbor.document.searchablepdf.SearchablePdfGenerator
import org.synapseworks.pageharbor.document.searchablepdf.SearchablePdfRequest
import org.synapseworks.pageharbor.document.searchablepdf.PdfBoxSearchablePdfGenerator
import org.synapseworks.pageharbor.ocr.OcrPageLayout
import org.synapseworks.pageharbor.ocr.OcrPageResult
import org.synapseworks.pageharbor.ocr.OcrTextBounds
import org.synapseworks.pageharbor.ocr.OcrTextLine

class SecureRedactedPdfEngineInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val testRoot = File(context.cacheDir, "secure-redaction-engine-test")

    @Before
    fun setUp() {
        testRoot.deleteRecursively()
        testRoot.mkdirs()
    }

    @After
    fun tearDown() {
        testRoot.deleteRecursively()
        deleteStaleSecureRedactedPdfs(context.cacheDir, Long.MAX_VALUE)
    }

    @Test
    fun rebuildsFromSanitizedRasterAndRemovesIntersectingSearchableText() = runBlocking {
        val source = File(testRoot, "synthetic-source.jpg")
        writeFixture(source)
        val originalHash = requireNotNull(secureRedactionSha256(source::inputStream))
        val engine = SecureRedactedPdfEngine(context)

        val result = engine.prepare(
            SecureRedactedPdfRequest(
                listOf(
                    SecureRedactedPdfPage(
                        pageId = PAGE_ID,
                        openSource = source::inputStream,
                        imageMetadata = DocumentImageMetadata(
                            sourceByteCount = source.length(),
                            width = WIDTH,
                            height = HEIGHT,
                        ),
                        expectedSourceSha256 = originalHash,
                        regions = listOf(region()),
                        effectiveOcr = OcrPageResult(
                            pageIndex = 0,
                            text = "$SECRET\n$SAFE_TEXT",
                            layout = OcrPageLayout(
                                imageWidthPx = WIDTH,
                                imageHeightPx = HEIGHT,
                                lines = listOf(
                                    OcrTextLine(
                                        SECRET,
                                        OcrTextBounds(60f, 70f, 360f, 190f),
                                    ),
                                    OcrTextLine(
                                        SAFE_TEXT,
                                        OcrTextBounds(60f, 400f, 360f, 470f),
                                    ),
                                ),
                            ),
                        ),
                        ocrProvenance = SecureRedactedOcrProvenance(
                            recognizedSourceSha256 = originalHash,
                            effectiveWidthPx = WIDTH,
                            effectiveHeightPx = HEIGHT,
                            rotation = DocumentPageRotation.DEGREES_0,
                        ),
                    ),
                ),
            ),
        )

        assertTrue(result is SecureRedactedPdfResult.Success)
        val prepared = (result as SecureRedactedPdfResult.Success).prepared
        try {
            assertTrue(prepared.securelyVerified)
            assertTrue(prepared.sourceHashesVerified)
            assertEquals(1, prepared.pageCount)
            assertEquals(1, prepared.textLayerPageCount)
            assertEquals(0, prepared.omittedUnsafeTextPageCount)
            assertEquals(originalHash, secureRedactionSha256(source::inputStream))
            PDDocument.load(prepared.file).use { pdf ->
                assertEquals(1, pdf.numberOfPages)
                val extracted = PDFTextStripper().getText(pdf)
                assertFalse(extracted.contains(SECRET))
                assertTrue(extracted.contains(SAFE_TEXT))
                assertTrue(pdf.getPage(0).annotations.isEmpty())
                val xObjects = pdf.getPage(0).resources.xObjectNames
                    .map(pdf.getPage(0).resources::getXObject)
                assertEquals(1, xObjects.size)
                assertTrue(xObjects.single() is PDImageXObject)
            }
        } finally {
            engine.discard(prepared)
        }
        assertTrue(source.isFile)
        assertEquals(originalHash, secureRedactionSha256(source::inputStream))
    }

    @Test
    fun hiddenSourcePdfTextAndMetadataNeverCrossRasterReconstructionBoundary() = runBlocking {
        val visibleImage = File(testRoot, "hidden-pdf-visible-source.jpg")
        writeFixture(visibleImage)
        val sourcePdf = File(testRoot, "hidden-source.pdf")
        PDDocument().use { document ->
            document.documentInformation.subject = SECRET
            val page = PDPage(PDRectangle(WIDTH.toFloat(), HEIGHT.toFloat()))
            document.addPage(page)
            val image = visibleImage.inputStream().use { JPEGFactory.createFromStream(document, it) }
            PDPageContentStream(document, page).use { content ->
                content.drawImage(image, 0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat())
                content.beginText()
                content.setRenderingMode(RenderingMode.NEITHER)
                content.setFont(PDType1Font.HELVETICA, 12f)
                content.newLineAtOffset(12f, 12f)
                content.showText(SECRET)
                content.endText()
            }
            document.save(sourcePdf)
        }
        val sourcePdfHash = requireNotNull(secureRedactionSha256(sourcePdf::inputStream))
        PDDocument.load(sourcePdf).use { document ->
            assertTrue(PDFTextStripper().getText(document).contains(SECRET))
            assertEquals(SECRET, document.documentInformation.subject)
        }

        val renderedSource = File(testRoot, "hidden-source-rendered.jpg")
        ParcelFileDescriptor.open(sourcePdf, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                renderer.openPage(0).use { page ->
                    val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        FileOutputStream(renderedSource).use { output ->
                            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output))
                        }
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        }
        val renderedHash = requireNotNull(secureRedactionSha256(renderedSource::inputStream))
        val engine = SecureRedactedPdfEngine(context)
        val result = engine.prepare(
            SecureRedactedPdfRequest(
                listOf(
                    SecureRedactedPdfPage(
                        pageId = PAGE_ID,
                        openSource = renderedSource::inputStream,
                        imageMetadata = DocumentImageMetadata(
                            renderedSource.length(),
                            WIDTH,
                            HEIGHT,
                        ),
                        expectedSourceSha256 = renderedHash,
                        regions = listOf(region()),
                    ),
                ),
            ),
        )

        assertTrue("result=$result", result is SecureRedactedPdfResult.Success)
        val prepared = (result as SecureRedactedPdfResult.Success).prepared
        try {
            assertFalse(prepared.file.readBytes().toString(Charsets.ISO_8859_1).contains(SECRET))
            PDDocument.load(prepared.file).use { document ->
                assertFalse(PDFTextStripper().getText(document).contains(SECRET))
                assertTrue(document.documentInformation.subject.isNullOrEmpty())
                val images = document.getPage(0).resources.xObjectNames
                    .map(document.getPage(0).resources::getXObject)
                    .filterIsInstance<PDImageXObject>()
                assertEquals(1, images.size)
                val image = images.single().image
                try {
                    val bounds = requireNotNull(RedactionCoordinateMapper.toRasterOutward(
                        region().bounds,
                        image.width,
                        image.height,
                    ))
                    for (y in bounds.top until bounds.bottomExclusive) {
                        for (x in bounds.left until bounds.rightExclusive) {
                            val pixel = image.getPixel(x, y)
                            assertTrue(Color.red(pixel) <= 16)
                            assertTrue(Color.green(pixel) <= 16)
                            assertTrue(Color.blue(pixel) <= 16)
                        }
                    }
                } finally {
                    image.recycle()
                }
            }
        } finally {
            engine.discard(prepared)
        }
        assertEquals(sourcePdfHash, secureRedactionSha256(sourcePdf::inputStream))
        assertEquals(renderedHash, secureRedactionSha256(renderedSource::inputStream))
    }

    @Test
    fun staleOcrProvenanceOmitsTheWholeSearchableLayer() = runBlocking {
        val source = File(testRoot, "stale-ocr-source.jpg")
        writeFixture(source)
        val hash = requireNotNull(secureRedactionSha256(source::inputStream))
        val result = SecureRedactedPdfEngine(context).prepare(
            SecureRedactedPdfRequest(
                listOf(
                    SecureRedactedPdfPage(
                        pageId = PAGE_ID,
                        openSource = source::inputStream,
                        imageMetadata = DocumentImageMetadata(source.length(), WIDTH, HEIGHT),
                        expectedSourceSha256 = hash,
                        regions = listOf(region()),
                        effectiveOcr = OcrPageResult(
                            pageIndex = 0,
                            text = SECRET,
                            layout = OcrPageLayout(
                                imageWidthPx = WIDTH,
                                imageHeightPx = HEIGHT,
                                lines = listOf(
                                    OcrTextLine(
                                        SECRET,
                                        OcrTextBounds(60f, 400f, 360f, 470f),
                                    ),
                                ),
                            ),
                        ),
                        ocrProvenance = SecureRedactedOcrProvenance(
                            recognizedSourceSha256 = "0".repeat(64),
                            effectiveWidthPx = WIDTH,
                            effectiveHeightPx = HEIGHT,
                            rotation = DocumentPageRotation.DEGREES_0,
                        ),
                    ),
                ),
            ),
        )

        assertTrue("result=$result", result is SecureRedactedPdfResult.Success)
        val prepared = (result as SecureRedactedPdfResult.Success).prepared
        try {
            assertEquals(0, prepared.textLayerPageCount)
            assertEquals(1, prepared.omittedUnsafeTextPageCount)
            PDDocument.load(prepared.file).use { pdf ->
                assertFalse(PDFTextStripper().getText(pdf).contains(SECRET))
            }
        } finally {
            SecureRedactedPdfEngine(context).discard(prepared)
        }
    }

    @Test
    fun unpositionedCorrectedTextIsOmittedInsteadOfGuessed() = runBlocking {
        val source = File(testRoot, "corrected-source.jpg")
        writeFixture(source)
        val engine = SecureRedactedPdfEngine(context)
        val result = engine.prepare(
            SecureRedactedPdfRequest(
                listOf(
                    SecureRedactedPdfPage(
                        pageId = PAGE_ID,
                        openSource = source::inputStream,
                        imageMetadata = DocumentImageMetadata(source.length(), WIDTH, HEIGHT),
                        expectedSourceSha256 = requireNotNull(
                            secureRedactionSha256(source::inputStream),
                        ),
                        regions = listOf(region()),
                        effectiveOcr = OcrPageResult(
                            pageIndex = 0,
                            text = "Corrected $SECRET",
                            layout = null,
                        ),
                    ),
                ),
            ),
        )

        assertTrue(result is SecureRedactedPdfResult.Success)
        val prepared = (result as SecureRedactedPdfResult.Success).prepared
        try {
            assertEquals(0, prepared.textLayerPageCount)
            assertEquals(1, prepared.omittedUnsafeTextPageCount)
            PDDocument.load(prepared.file).use { pdf ->
                assertFalse(PDFTextStripper().getText(pdf).contains(SECRET))
            }
        } finally {
            engine.discard(prepared)
        }
    }

    @Test
    fun sourceHashMismatchFailsBeforeProducingAShareableResult() = runBlocking {
        val source = File(testRoot, "changed-source.jpg")
        writeFixture(source)

        val result = SecureRedactedPdfEngine(context).prepare(
            SecureRedactedPdfRequest(
                listOf(
                    SecureRedactedPdfPage(
                        pageId = PAGE_ID,
                        openSource = source::inputStream,
                        imageMetadata = DocumentImageMetadata(source.length(), WIDTH, HEIGHT),
                        expectedSourceSha256 = "0".repeat(64),
                        regions = listOf(region()),
                    ),
                ),
            ),
        )

        assertEquals(
            SecureRedactedPdfResult.Failure(SecureRedactedPdfFailure.SOURCE_CHANGED),
            result,
        )
    }

    @Test
    fun processRestartCleanupDeletesOnlyStaleOwnedWorkspaces() {
        val root = File(context.cacheDir, "secure-redacted-pdfs").apply { mkdirs() }
        val stale = File(root, "secure-redacted-stale.work").apply {
            mkdirs()
            File(this, "sanitized-page-0.jpg").writeBytes(byteArrayOf(1, 2, 3))
            setLastModified(1_000L)
        }
        val fresh = File(root, "secure-redacted-fresh.work").apply {
            mkdirs()
            setLastModified(20_000L)
        }

        deleteStaleSecureRedactedPdfs(
            cacheDirectory = context.cacheDir,
            nowMillis = 24L * 60L * 60L * 1_000L + 10_000L,
        )

        assertFalse(stale.exists())
        assertTrue(fresh.exists())

        val createdAfterProcessStart = File(root, "secure-redacted-new.work").apply {
            mkdirs()
            setLastModified(40_000L)
        }
        deleteStaleSecureRedactedPdfs(
            cacheDirectory = context.cacheDir,
            nowMillis = 30_000L,
            minimumAgeMillis = 0L,
        )
        assertFalse(fresh.exists())
        assertTrue(createdAfterProcessStart.exists())
    }

    @Test
    fun cancellationDuringPdfRebuildRemovesSanitizedPagesAndOutput() = runBlocking {
        val source = File(testRoot, "cancel-source.jpg")
        writeFixture(source)
        val hash = requireNotNull(secureRedactionSha256(source::inputStream))
        val enteredGenerator = CompletableDeferred<Unit>()
        val blockingGenerator = object : SearchablePdfGenerator {
            override suspend fun generate(
                request: SearchablePdfRequest,
            ): SearchablePdfGenerationResult {
                enteredGenerator.complete(Unit)
                awaitCancellation()
            }
        }
        val engine = SecureRedactedPdfEngine(context, generator = blockingGenerator)
        val job = launch {
            engine.prepare(
                SecureRedactedPdfRequest(
                    listOf(
                        SecureRedactedPdfPage(
                            pageId = PAGE_ID,
                            openSource = source::inputStream,
                            imageMetadata = DocumentImageMetadata(source.length(), WIDTH, HEIGHT),
                            expectedSourceSha256 = hash,
                            regions = listOf(region()),
                        ),
                    ),
                ),
            )
        }
        enteredGenerator.await()
        job.cancelAndJoin()

        assertEquals(hash, secureRedactionSha256(source::inputStream))
        val root = File(context.cacheDir, "secure-redacted-pdfs")
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun cancellationAtPreparedResultHandoffDeletesVerifiedWorkspace() = runBlocking {
        val source = File(testRoot, "handoff-source.jpg")
        writeFixture(source)
        val hash = requireNotNull(secureRedactionSha256(source::inputStream))
        val root = File(context.cacheDir, "secure-redacted-pdfs")
        root.listFiles()?.forEach(File::deleteRecursively)
        val handoffReady = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                executor.execute {
                    block.run()
                    val prepared = root.listFiles().orEmpty().singleOrNull()
                    if (prepared?.listFiles().orEmpty().map(File::getName) ==
                        listOf("redacted.pdf")
                    ) {
                        handoffReady.countDown()
                    }
                }
            }
        }
        try {
            val engine = SecureRedactedPdfEngine(context, ioDispatcher = dispatcher)
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                engine.prepare(
                    SecureRedactedPdfRequest(
                        listOf(
                            SecureRedactedPdfPage(
                                pageId = PAGE_ID,
                                openSource = source::inputStream,
                                imageMetadata = DocumentImageMetadata(
                                    source.length(),
                                    WIDTH,
                                    HEIGHT,
                                ),
                                expectedSourceSha256 = hash,
                                regions = listOf(region()),
                            ),
                        ),
                    ),
                )
            }

            assertTrue(handoffReady.await(30, TimeUnit.SECONDS))
            job.cancel()
            job.join()

            assertTrue(job.isCancelled)
            assertTrue(root.listFiles().orEmpty().isEmpty())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun sourceMutationDuringPdfRebuildFailsTheFinalAllPageAttestation() = runBlocking {
        val source = File(testRoot, "mutated-during-rebuild.jpg")
        writeFixture(source)
        val hash = requireNotNull(secureRedactionSha256(source::inputStream))
        val enteredGenerator = CompletableDeferred<Unit>()
        val resumeGenerator = CompletableDeferred<Unit>()
        val delegate = PdfBoxSearchablePdfGenerator(context)
        val blockingGenerator = object : SearchablePdfGenerator {
            override suspend fun generate(
                request: SearchablePdfRequest,
            ): SearchablePdfGenerationResult {
                enteredGenerator.complete(Unit)
                resumeGenerator.await()
                return delegate.generate(request)
            }
        }
        val engine = SecureRedactedPdfEngine(context, generator = blockingGenerator)
        val result = async {
            engine.prepare(
                SecureRedactedPdfRequest(
                    listOf(
                        SecureRedactedPdfPage(
                            pageId = PAGE_ID,
                            openSource = source::inputStream,
                            imageMetadata = DocumentImageMetadata(source.length(), WIDTH, HEIGHT),
                            expectedSourceSha256 = hash,
                            regions = listOf(region()),
                        ),
                    ),
                ),
            )
        }

        enteredGenerator.await()
        val changed = source.readBytes().also { bytes ->
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
        }
        FileOutputStream(source, false).use { it.write(changed) }
        resumeGenerator.complete(Unit)

        assertEquals(
            SecureRedactedPdfResult.Failure(SecureRedactedPdfFailure.SOURCE_CHANGED),
            result.await(),
        )
        assertTrue(
            File(context.cacheDir, "secure-redacted-pdfs").listFiles().orEmpty().isEmpty(),
        )
    }

    @Test
    fun fullPageRedactionProducesOneOpaqueImageAndNoText() = runBlocking {
        val source = File(testRoot, "full-page-source.jpg")
        writeFixture(source)
        val hash = requireNotNull(secureRedactionSha256(source::inputStream))
        val engine = SecureRedactedPdfEngine(context)
        val result = engine.prepare(
            SecureRedactedPdfRequest(
                listOf(
                    SecureRedactedPdfPage(
                        pageId = PAGE_ID,
                        openSource = source::inputStream,
                        imageMetadata = DocumentImageMetadata(source.length(), WIDTH, HEIGHT),
                        expectedSourceSha256 = hash,
                        regions = listOf(region(PAGE_ID, 0, RedactionBounds.FullPage)),
                        effectiveOcr = OcrPageResult(
                            pageIndex = 0,
                            text = SECRET,
                            layout = OcrPageLayout(
                                imageWidthPx = WIDTH,
                                imageHeightPx = HEIGHT,
                                lines = listOf(
                                    OcrTextLine(
                                        SECRET,
                                        OcrTextBounds(1f, 1f, WIDTH - 1f, HEIGHT - 1f),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertTrue("result=$result", result is SecureRedactedPdfResult.Success)
        val prepared = (result as SecureRedactedPdfResult.Success).prepared
        try {
            assertEquals(0, prepared.textLayerPageCount)
            PDDocument.load(prepared.file).use { pdf ->
                assertTrue(PDFTextStripper().getText(pdf).isBlank())
                val image = pdf.getPage(0).resources.xObjectNames
                    .map(pdf.getPage(0).resources::getXObject)
                    .filterIsInstance<PDImageXObject>()
                    .single().image
                try {
                    listOf(
                        0 to 0,
                        image.width - 1 to 0,
                        0 to image.height - 1,
                        image.width / 2 to image.height / 2,
                        image.width - 1 to image.height - 1,
                    ).forEach { (x, y) ->
                        val pixel = image.getPixel(x, y)
                        assertEquals(255, Color.alpha(pixel))
                        assertTrue(Color.red(pixel) <= 16)
                        assertTrue(Color.green(pixel) <= 16)
                        assertTrue(Color.blue(pixel) <= 16)
                    }
                } finally {
                    image.recycle()
                }
            }
        } finally {
            engine.discard(prepared)
        }
        assertEquals(hash, secureRedactionSha256(source::inputStream))
    }

    @Test
    fun twentyPagesAndFiftyRegionsRemainBoundedAndDeterministic() = runBlocking {
        val source = File(testRoot, "twenty-page-source.jpg")
        writeFixture(source)
        val hash = requireNotNull(secureRedactionSha256(source::inputStream))
        val pages = List(20) { pageIndex ->
            val pageId = "performance-page-$pageIndex"
            val count = if (pageIndex < 10) 3 else 2
            val regions = List(count) { regionIndex ->
                val column = (pageIndex + regionIndex) % 5
                val row = (pageIndex * 2 + regionIndex) % 6
                val left = 0.04f + column * 0.18f
                val top = 0.04f + row * 0.14f
                region(
                    pageId,
                    regionIndex,
                    RedactionBounds(left, top, left + 0.10f, top + 0.06f),
                )
            }
            SecureRedactedPdfPage(
                pageId = pageId,
                openSource = source::inputStream,
                imageMetadata = DocumentImageMetadata(source.length(), WIDTH, HEIGHT),
                expectedSourceSha256 = hash,
                regions = regions,
            )
        }
        val baselinePss = Debug.getPss().toLong() * 1_024L
        val started = SystemClock.elapsedRealtime()
        val engine = SecureRedactedPdfEngine(context)
        val result = engine.prepare(SecureRedactedPdfRequest(pages))
        val elapsed = SystemClock.elapsedRealtime() - started

        assertTrue("result=$result", result is SecureRedactedPdfResult.Success)
        val prepared = (result as SecureRedactedPdfResult.Success).prepared
        try {
            val retainedPss = (Debug.getPss().toLong() * 1_024L - baselinePss).coerceAtLeast(0L)
            Log.i(
                "RME_M7_METRICS",
                "pages=20 regions=50 elapsed_ms=$elapsed " +
                    "render_redact_ms=${prepared.performance.sourceRenderAndRedactionMillis} " +
                    "rebuild_ms=${prepared.performance.pdfRebuildMillis} " +
                    "verify_ms=${prepared.performance.verificationMillis} " +
                    "temp_peak_bytes=${prepared.performance.peakTemporaryBytes} " +
                    "estimated_peak_bitmap_bytes=${prepared.performance.estimatedPeakBitmapBytes} " +
                    "retained_pss_growth_bytes=$retainedPss",
            )
            assertEquals(20, prepared.pageCount)
            assertEquals(50, prepared.redactionCount)
            assertEquals(20, prepared.verificationEvidence.pageCount)
            assertTrue(prepared.performance.peakTemporaryBytes >= prepared.file.length())
            assertTrue(prepared.performance.estimatedPeakBitmapBytes in 1..8_000_000L)
            PDDocument.load(prepared.file).use { pdf ->
                assertEquals(20, pdf.numberOfPages)
                assertTrue(PDFTextStripper().getText(pdf).isBlank())
            }
        } finally {
            engine.discard(prepared)
        }
        assertEquals(hash, secureRedactionSha256(source::inputStream))
    }

    private fun writeFixture(file: File) {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(210, 25, 190)
                textSize = 48f
            }
            canvas.drawRect(55f, 65f, 365f, 195f, paint)
            paint.color = Color.BLACK
            canvas.drawText(SECRET, 65f, 145f, paint)
            canvas.drawText(SAFE_TEXT, 65f, 445f, paint)
            FileOutputStream(file).use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output))
                output.flush()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun region() = region(
        pageId = PAGE_ID,
        ordinal = 0,
        bounds = RedactionBounds(0.09f, 0.08f, 0.62f, 0.26f),
    )

    private fun region(pageId: String, ordinal: Int, bounds: RedactionBounds) = RedactionRegion(
        id = RedactionRegionId("$pageId-region-$ordinal"),
        coordinateSpace = RedactionCoordinateSpace.SOURCE_NORMALIZED,
        bounds = bounds,
        target = RedactionTarget(RedactionTargetKind.DOCUMENT_PAGE, pageId),
        confirmed = true,
        order = 0,
    )

    private companion object {
        const val PAGE_ID = "page-1"
        const val WIDTH = 600
        const val HEIGHT = 800
        const val SECRET = "RME_SECRET_7F42A9"
        const val SAFE_TEXT = "SAFE INVOICE"
    }
}
