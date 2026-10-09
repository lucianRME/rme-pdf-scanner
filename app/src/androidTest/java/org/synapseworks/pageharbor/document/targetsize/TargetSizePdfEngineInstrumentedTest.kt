package org.synapseworks.pageharbor.document.targetsize

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Debug
import android.util.Log
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.synapseworks.pageharbor.document.session.DocumentImageMetadata
import org.synapseworks.pageharbor.document.session.DocumentPageRotation
import org.synapseworks.pageharbor.document.session.DocumentSourceCategory
import org.synapseworks.pageharbor.ocr.OcrPageLayout
import org.synapseworks.pageharbor.ocr.OcrPageResult
import org.synapseworks.pageharbor.ocr.OcrTextBounds
import org.synapseworks.pageharbor.ocr.OcrTextLine
import org.synapseworks.pageharbor.document.PageExportResult
import org.synapseworks.pageharbor.document.SizedJpegEncoding
import org.synapseworks.pageharbor.document.writeSizedJpegToDestination

class TargetSizePdfEngineInstrumentedTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var testRoot: File

    @Before
    fun setUp() {
        PDFBoxResourceLoader.init(context)
        testRoot = File(context.cacheDir, "target-size-engine-test-${System.nanoTime()}")
        assertTrue(testRoot.mkdirs())
    }

    @After
    fun tearDown() {
        testRoot.deleteRecursively()
        File(context.cacheDir, TARGET_SIZE_DIRECTORY_NAME).listFiles()?.forEach { it.deleteRecursively() }
    }

    @Test
    fun sizedPageWriterProducesAReadableBoundedJpeg() {
        val source = createFixture("writer", 900, 1_200, FixtureKind.INVOICE)
        val output = File(testRoot, "writer-output.jpg")

        val result = writeSizedJpegToDestination(
            openSource = { source.inputStream() },
            destination = output.outputStream(),
            filter = org.synapseworks.pageharbor.image.DocumentFilter.ORIGINAL,
            rotation = DocumentPageRotation.DEGREES_0,
            imageMetadata = DocumentImageMetadata(source.length(), 900, 1_200),
            encoding = SizedJpegEncoding(450, 600, 86),
        )

        assertEquals(PageExportResult.Success, result)
        assertTrue(output.isFile && output.length() > 0L)
        assertEquals(450 to 600, readJpegDimensions(output))
    }

    @Test
    fun staleCleanupDeletesOnlyOldTargetSizeRunDirectories() {
        val root = File(context.cacheDir, TARGET_SIZE_DIRECTORY_NAME).apply { mkdirs() }
        val oldRun = File(root, "target-size-old").apply { mkdirs() }
        File(oldRun, "candidate.pdf").writeText("old")
        oldRun.setLastModified(1_000L)
        val freshRun = File(root, "target-size-fresh").apply { mkdirs() }
        File(freshRun, "candidate.pdf").writeText("fresh")
        freshRun.setLastModified(20_000L)
        val unrelated = File(root, "unrelated").apply { mkdirs() }
        File(unrelated, "keep").writeText("keep")

        deleteStaleTargetSizePdfs(
            cacheDirectory = context.cacheDir,
            nowMillis = 20_000L + TARGET_SIZE_MAX_STALE_AGE_MILLIS - 1L,
        )

        assertFalse(oldRun.exists())
        assertTrue(freshRun.exists())
        assertTrue(unrelated.exists())
    }

    @Test
    fun processStartupCleanupDeletesFreshTargetSizeRunDirectories() {
        val root = File(context.cacheDir, TARGET_SIZE_DIRECTORY_NAME).apply { mkdirs() }
        val interruptedRun = File(root, "target-size-interrupted").apply { mkdirs() }
        File(interruptedRun, "calibration-0-0.jpg").writeText("partial")
        interruptedRun.setLastModified(20_000L)
        val unrelated = File(root, "unrelated").apply { mkdirs() }
        File(unrelated, "keep").writeText("keep")

        deleteStaleTargetSizePdfs(
            cacheDirectory = context.cacheDir,
            nowMillis = 20_000L,
            minimumAgeMillis = 0L,
        )

        assertFalse(interruptedRun.exists())
        assertTrue(unrelated.exists())
    }

    @Test
    fun targetMatrixProducesExactBoundedReopenableSinglePagePdfs() = runBlocking {
        val source = createFixture("invoice", 900, 1_200, FixtureKind.INVOICE)
        val targets = listOf(
            TargetSizeBytes.KB_200,
            TargetSizeBytes.KB_500,
            TargetSizeBytes.MB_1,
            TargetSizeBytes.MB_2,
        )

        targets.forEach { target ->
            val engine = engine()
            val result = engine.prepare(request(listOf(source), target))
            assertTrue("target=$target result=$result", result is TargetSizePdfResult.TargetReached)
            val prepared = (result as TargetSizePdfResult.TargetReached).prepared
            try {
                assertTrue(prepared.finalBytes <= target)
                assertEquals(prepared.file.length(), prepared.finalBytes)
                assertReopensAndRenders(prepared.file, expectedPages = 1)
                assertTrue(prepared.performance.fullDocumentPassCount <= 2)
                assertTrue(prepared.performance.calibrationTrialCount <= 3)
            } finally {
                engine.discard(prepared)
            }
        }
    }

    @Test
    fun alreadyFitsCopyAndVerifiedDestinationRemainByteExactAndShareable() = runBlocking {
        val sourceImage = createFixture("already-fits", 600, 800, FixtureKind.TEXT_FORM)
        val sourcePdf = createPdf("already-fits-source", sourceImage, addText = true, pageCount = 2)
        val originalBytes = sourcePdf.readBytes()
        val engine = engine()
        val request = request(listOf(sourceImage, sourceImage), TargetSizeBytes.MB_2).copy(
            originalPdfUris = listOf(Uri.fromFile(sourcePdf)),
        )

        val result = engine.prepareExistingPdfCopy(
            TargetSizeExistingPdfCopyRequest(Uri.fromFile(sourcePdf), request),
        )

        assertTrue("result=$result", result is TargetSizePdfResult.TargetReached)
        val prepared = (result as TargetSizePdfResult.TargetReached).prepared
        try {
            assertTrue(prepared.finalBytes <= TargetSizeBytes.MB_2)
            assertEquals(0, prepared.performance.fullDocumentPassCount)
            assertTrue(originalBytes.contentEquals(prepared.file.readBytes()))
            assertTrue(originalBytes.contentEquals(sourcePdf.readBytes()))

            val destination = File(
                File(context.cacheDir, TARGET_SIZE_DIRECTORY_NAME).apply { mkdirs() },
                "verified-destination-${System.nanoTime()}.pdf",
            ).apply { createNewFile() }
            val destinationUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                destination,
            )
            val writeResult = TargetSizeDestinationWriter(context).write(prepared, destinationUri)

            assertEquals(TargetSizeDestinationResult.Success(prepared.finalBytes), writeResult)
            assertTrue(originalBytes.contentEquals(destination.readBytes()))
            assertReopensAndRenders(destination, expectedPages = 2)
            PDDocument.load(destination).use { document ->
                val text = PDFTextStripper().getText(document)
                assertTrue(text.indexOf("embedded text 1") < text.indexOf("embedded text 2"))
            }
            val shareUri = targetSizeShareUri(context, prepared)
            assertNotNull(shareUri)
            assertEquals("content", shareUri?.scheme)
        } finally {
            engine.discard(prepared)
        }
    }

    @Test
    fun representativeFixturesPreserveOrderUnicodeAndBoundedPassCount() = runBlocking {
        val fixtureKinds = listOf(
            FixtureKind.TEXT_FORM,
            FixtureKind.RECEIPT,
            FixtureKind.INVOICE,
            FixtureKind.PHOTO,
            FixtureKind.MIXED,
            FixtureKind.GRAYSCALE,
            FixtureKind.BLACK_AND_WHITE,
            FixtureKind.TINY_TEXT,
        )
        val sources = fixtureKinds.mapIndexed { index, kind ->
            createFixture("fixture-$index", 900, 1_200, kind)
        } + createFixture("rotated", 1_200, 900, FixtureKind.TEXT_FORM)
        val unicode = "Română ș ț 中文 日本語 한국어 हिन्दी"
        val pages = sources.mapIndexed { index, file ->
            page(
                file = file,
                id = index.toLong(),
                rotation = if (index == sources.lastIndex) {
                    DocumentPageRotation.DEGREES_90
                } else {
                    DocumentPageRotation.DEGREES_0
                },
                ocr = if (index == 0) positionedOcr(index, 900, 1_200, unicode) else null,
                profile = fixtureKinds.getOrNull(index).toProfile(),
            )
        }
        val engine = engine()

        val result = engine.prepare(
            TargetSizePdfRequest(pages = pages, targetBytes = TargetSizeBytes.MB_2),
        )

        assertTrue(result is TargetSizePdfResult.TargetReached)
        val prepared = (result as TargetSizePdfResult.TargetReached).prepared
        try {
            assertEquals(pages.size, prepared.pageCount)
            assertEquals(1, prepared.textLayerPageCount)
            assertTrue(prepared.finalBytes <= TargetSizeBytes.MB_2)
            assertTrue(prepared.performance.fullDocumentPassCount <= 2)
            assertTrue(prepared.performance.calibrationTrialCount <= 9)
            PDDocument.load(prepared.file).use { document ->
                assertEquals(pages.size, document.numberOfPages)
                assertTrue(PDFTextStripper().getText(document).contains(unicode))
            }
            assertReopensAndRenders(prepared.file, pages.size)
        } finally {
            engine.discard(prepared)
        }
    }

    @Test
    fun eightTwentyAndFiftyPageDocumentsStayWithinBoundAndDoNotMutateSources() = runBlocking {
        listOf(8, 20, 50).forEach { count ->
            val sources = List(count) { index ->
                createFixture("long-$count-$index", 600, 800, FixtureKind.TEXT_FORM)
            }
            val before = sources.map(File::readBytes)
            val engine = engine()
            val result = engine.prepare(request(sources, TargetSizeBytes.MB_2))
            assertTrue("count=$count result=$result", result is TargetSizePdfResult.TargetReached)
            val prepared = (result as TargetSizePdfResult.TargetReached).prepared
            try {
                Log.i(
                    "RME_M4_METRICS",
                    "pages=$count planning_ms=${prepared.performance.planningMillis} " +
                        "calibration_ms=${prepared.performance.calibrationMillis} " +
                        "encode_ms=${prepared.performance.encodingMillis} " +
                        "passes=${prepared.performance.fullDocumentPassCount} " +
                        "calibration_trials=${prepared.performance.calibrationTrialCount} " +
                        "temp_peak_bytes=${prepared.performance.peakTemporaryBytes} " +
                        "final_bytes=${prepared.finalBytes}",
                )
                assertEquals(count, prepared.pageCount)
                assertTrue(prepared.finalBytes <= TargetSizeBytes.MB_2)
                assertTrue(prepared.performance.fullDocumentPassCount <= 2)
                assertTrue(prepared.performance.calibrationTrialCount <= 9)
                assertReopensAndRenders(prepared.file, count)
                sources.zip(before).forEach { (source, bytes) ->
                    assertTrue(source.readBytes().contentEquals(bytes))
                }
            } finally {
                engine.discard(prepared)
            }
        }
    }

    @Test
    fun cancellationDuringPageEncodingLeavesNoPreparedOrTemporaryOutput() = runBlocking {
        val source = createFixture("cancel", 1_600, 2_000, FixtureKind.PHOTO)
        val checks = AtomicInteger()
        val engine = engine()
        val result = engine.prepare(
            TargetSizePdfRequest(
                pages = listOf(
                    page(source, 1).copy(
                        filter = org.synapseworks.pageharbor.image.DocumentFilter.GRAYSCALE,
                    ),
                ),
                targetBytes = TargetSizeBytes.KB_500,
                cancellationSignal = TargetSizeCancellationSignal {
                    checks.incrementAndGet() >= 20
                },
            ),
        )

        assertEquals(
            TargetSizePdfResult.Failure(TargetSizePdfFailure.CANCELED),
            result,
        )
        assertTrue(
            File(context.cacheDir, TARGET_SIZE_DIRECTORY_NAME).listFiles().isNullOrEmpty(),
        )
        assertTrue(source.isFile && source.length() > 0L)
    }

    @Test
    fun lowStorageFailsBeforeCalibrationAndCleansPrivateWork() = runBlocking {
        val source = createFixture("low-storage", 900, 1_200, FixtureKind.INVOICE)
        val engine = engine(availableBytes = { 0L })

        val result = engine.prepare(request(listOf(source), TargetSizeBytes.KB_500))

        assertEquals(
            TargetSizePdfResult.Failure(TargetSizePdfFailure.INSUFFICIENT_TEMPORARY_STORAGE),
            result,
        )
        assertTrue(
            File(context.cacheDir, TARGET_SIZE_DIRECTORY_NAME).listFiles().isNullOrEmpty(),
        )
    }

    @Test
    fun impossibleHundredPageTargetReturnsBeforeAnyEncodePass() = runBlocking {
        val source = createFixture("early-impossible", 600, 800, FixtureKind.TEXT_FORM)
        val pages = List(100) { index -> page(source, index.toLong()) }
        val engine = engine()

        val result = engine.prepare(
            TargetSizePdfRequest(pages = pages, targetBytes = TargetSizeBytes.KB_200),
        )

        assertTrue(result is TargetSizePdfResult.TargetNotReachableAtAcceptableQuality)
        result as TargetSizePdfResult.TargetNotReachableAtAcceptableQuality
        assertEquals(
            TargetSizeNotReachableReason.MATHEMATICALLY_UNREALISTIC_FOR_PAGE_COUNT,
            result.reason,
        )
        assertFalse(result.bytesAreMeasured)
        assertTrue(result.smallestAcceptableBytes > TargetSizeBytes.KB_200)
        assertEquals(null, result.smallestAcceptable)
    }

    @Test
    fun photoHeavyTargetCanReportMeasuredQualityFloorFailure() = runBlocking {
        val source = createFixture("floor-failure", 1_600, 2_000, FixtureKind.PHOTO)
        val engine = engine()

        val result = engine.prepare(
            TargetSizePdfRequest(
                pages = listOf(
                    page(
                        source,
                        id = 1,
                        profile = TargetSizePageContentProfile.PHOTO_HEAVY,
                    ),
                ),
                targetBytes = TargetSizeBytes.KB_200,
            ),
        )

        assertTrue("result=$result", result is TargetSizePdfResult.TargetNotReachableAtAcceptableQuality)
        result as TargetSizePdfResult.TargetNotReachableAtAcceptableQuality
        assertTrue(result.bytesAreMeasured)
        assertEquals(TargetSizeNotReachableReason.QUALITY_FLOOR_REACHED, result.reason)
        assertNotNull(result.smallestAcceptable)
        val prepared = requireNotNull(result.smallestAcceptable)
        try {
            assertTrue(prepared.finalBytes > TargetSizeBytes.KB_200)
            assertTrue(prepared.plan.pages.all(TargetSizeQualitySearch::isAtFloor))
            assertReopensAndRenders(prepared.file, 1)
        } finally {
            engine.discard(prepared)
        }
    }

    @Test
    fun recordsPerformancePassTempAndObservedHeapMetricsForEightPages() = runBlocking {
        val sources = List(8) { index ->
            createFixture("metrics-$index", 900, 1_200, if (index == 0) FixtureKind.PHOTO else FixtureKind.INVOICE)
        }
        val engine = engine()
        val baselinePss = currentPssBytes()
        val peakPss = AtomicLong(baselinePss)
        val export = async(Dispatchers.Default) {
            engine.prepare(request(sources, TargetSizeBytes.MB_2))
        }
        while (!export.isCompleted) {
            peakPss.updateAndGet { previous -> maxOf(previous, currentPssBytes()) }
            delay(5)
        }
        val result = export.await()
        assertTrue(result is TargetSizePdfResult.TargetReached)
        val prepared = (result as TargetSizePdfResult.TargetReached).prepared
        val performance = prepared.performance
        val finalBytes = prepared.finalBytes
        try {
            assertTrue(performance.fullDocumentPassCount <= 2)
            assertTrue(performance.peakTemporaryBytes >= finalBytes)
        } finally {
            engine.discard(prepared)
        }
        System.gc()
        delay(100)
        val retainedPss = (currentPssBytes() - baselinePss).coerceAtLeast(0L)
        val peakGrowth = (peakPss.get() - baselinePss).coerceAtLeast(0L)
        Log.i(
            "RME_M4_METRICS",
            "pages=8 planning_ms=${performance.planningMillis} " +
                "calibration_ms=${performance.calibrationMillis} " +
                "encode_ms=${performance.encodingMillis} " +
                "passes=${performance.fullDocumentPassCount} " +
                "calibration_trials=${performance.calibrationTrialCount} " +
                "temp_peak_bytes=${performance.peakTemporaryBytes} " +
                "final_bytes=$finalBytes peak_pss_growth_bytes=$peakGrowth " +
                "retained_pss_growth_bytes=$retainedPss",
        )
        assertTrue(peakGrowth >= 0L)
    }

    @Test
    fun pdfInspectorSeparatesImageMixedEncryptedAndMalformedSources() = runBlocking {
        val jpeg = createFixture("pdf-image", 400, 600, FixtureKind.INVOICE)
        val imagePdf = createPdf("image-only", jpeg, addText = false)
        val mixedPdf = createPdf("mixed", jpeg, addText = true)
        val encryptedPdf = createEncryptedPdf()
        val malformedPdf = File(testRoot, "malformed.pdf").apply { writeText("not pdf") }
        val inspector = TargetSizePdfInputInspector(context, ::openFileUri)

        val image = inspector.inspect(Uri.fromFile(imagePdf))
        val mixed = inspector.inspect(Uri.fromFile(mixedPdf))

        assertTrue(image is TargetSizePdfInspectionResult.Success)
        assertTrue((image as TargetSizePdfInspectionResult.Success).facts.allPagesContainRasterImage)
        assertFalse(image.facts.hasTextOrVectorContent)
        assertTrue(mixed is TargetSizePdfInspectionResult.Success)
        assertTrue((mixed as TargetSizePdfInspectionResult.Success).facts.hasTextOrVectorContent)
        assertEquals(
            TargetSizePdfInspectionResult.Encrypted,
            inspector.inspect(Uri.fromFile(encryptedPdf)),
        )
        assertEquals(
            TargetSizePdfInspectionResult.Malformed,
            inspector.inspect(Uri.fromFile(malformedPdf)),
        )
    }

    @Test
    fun imagePdfReconstructionIsClassifiedAndExportedWithoutChangingOriginal() = runBlocking {
        val renderedPage = createFixture("rendered", 600, 800, FixtureKind.INVOICE)
        val originalPdf = createPdf("original", renderedPage, addText = false)
        val originalBytes = originalPdf.readBytes()
        val engine = engine()
        val result = engine.prepare(
            TargetSizePdfRequest(
                pages = listOf(
                    page(
                        file = renderedPage,
                        id = 1,
                        sourceCategory = DocumentSourceCategory.RENDERED_PDF_PAGE,
                    ),
                ),
                originalPdfUris = listOf(Uri.fromFile(originalPdf)),
                targetBytes = TargetSizeBytes.KB_500,
            ),
        )

        assertTrue(result is TargetSizePdfResult.TargetReached)
        val prepared = (result as TargetSizePdfResult.TargetReached).prepared
        try {
            assertEquals(TargetSizeInputClass.IMAGE_BASED_PDF_SUPPORTED, prepared.plan.inputClass)
            assertTrue(originalPdf.readBytes().contentEquals(originalBytes))
        } finally {
            engine.discard(prepared)
        }
    }

    private fun engine(
        availableBytes: () -> Long? = { Long.MAX_VALUE },
    ): LocalTargetSizePdfEngine {
        val opener: (Uri) -> InputStream? = ::openFileUri
        return LocalTargetSizePdfEngine(
            context = context,
            pdfInspector = TargetSizePdfInputInspector(context, opener),
            openPageInputStream = opener,
            availableTemporaryBytes = availableBytes,
        )
    }

    private fun request(
        sources: List<File>,
        target: Long,
        cancellationSignal: TargetSizeCancellationSignal = TargetSizeCancellationSignal { false },
    ) = TargetSizePdfRequest(
        pages = sources.mapIndexed { index, file -> page(file, index.toLong()) },
        targetBytes = target,
        cancellationSignal = cancellationSignal,
    )

    private fun page(
        file: File,
        id: Long,
        rotation: DocumentPageRotation = DocumentPageRotation.DEGREES_0,
        ocr: OcrPageResult? = null,
        profile: TargetSizePageContentProfile = TargetSizePageContentProfile.TEXT_HEAVY,
        sourceCategory: DocumentSourceCategory = DocumentSourceCategory.SCAN,
    ): TargetSizePdfPage {
        val dimensions = readJpegDimensions(file)
        return TargetSizePdfPage(
            pageId = id,
            sourceUri = Uri.fromFile(file),
            sourceCategory = sourceCategory,
            contentType = "image/jpeg",
            imageMetadata = DocumentImageMetadata(
                sourceByteCount = file.length(),
                width = dimensions.first,
                height = dimensions.second,
            ),
            rotation = rotation,
            effectiveOcr = ocr,
            contentProfile = profile,
        )
    }

    private fun createFixture(
        name: String,
        width: Int,
        height: Int,
        kind: FixtureKind,
    ): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            when (kind) {
                FixtureKind.PHOTO -> {
                    for (y in 0 until height step 8) {
                        for (x in 0 until width step 8) {
                            paint.color = Color.rgb(
                                (x * 31 + y * 17) and 255,
                                (x * 13 + y * 29) and 255,
                                (x * 7 + y * 11) and 255,
                            )
                            canvas.drawRect(x.toFloat(), y.toFloat(), (x + 8).toFloat(), (y + 8).toFloat(), paint)
                        }
                    }
                }
                FixtureKind.GRAYSCALE -> {
                    for (y in 0 until height step 16) {
                        val gray = y * 255 / height
                        paint.color = Color.rgb(gray, gray, gray)
                        canvas.drawRect(0f, y.toFloat(), width.toFloat(), (y + 16).toFloat(), paint)
                    }
                }
                FixtureKind.BLACK_AND_WHITE -> {
                    canvas.drawColor(Color.WHITE)
                    paint.color = Color.BLACK
                    repeat(30) { line ->
                        val y = 30f + line * 30f
                        canvas.drawRect(40f, y, width - 40f, y + 5f, paint)
                    }
                }
                else -> {
                    canvas.drawColor(Color.WHITE)
                    paint.color = if (kind == FixtureKind.RECEIPT) Color.DKGRAY else Color.rgb(35, 55, 90)
                    paint.textSize = if (kind == FixtureKind.TINY_TEXT) 10f else 24f
                    val prefix = when (kind) {
                        FixtureKind.TEXT_FORM -> "FORM FIELD"
                        FixtureKind.RECEIPT -> "RECEIPT ITEM"
                        FixtureKind.INVOICE -> "INVOICE TOTAL"
                        FixtureKind.MIXED -> "MIXED PAGE"
                        FixtureKind.TINY_TEXT -> "tiny text line"
                        else -> "DOCUMENT"
                    }
                    repeat(if (kind == FixtureKind.TINY_TEXT) 80 else 28) { line ->
                        canvas.drawText("$prefix $line", 30f, 35f + line * paint.textSize * 1.35f, paint)
                    }
                    if (kind == FixtureKind.MIXED) {
                        paint.color = Color.rgb(185, 95, 70)
                        canvas.drawRect(width * 0.45f, height * 0.45f, width * 0.9f, height * 0.8f, paint)
                    }
                }
            }
            val file = File(testRoot, "$name.jpg")
            FileOutputStream(file).use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 96, output))
                output.flush()
                output.fd.sync()
            }
            return file
        } finally {
            bitmap.recycle()
        }
    }

    private fun createPdf(
        name: String,
        jpeg: File,
        addText: Boolean,
        pageCount: Int = 1,
    ): File {
        val output = File(testRoot, "$name.pdf")
        PDDocument().use { document ->
            val image = JPEGFactory.createFromStream(document, jpeg.inputStream())
            repeat(pageCount) { index ->
                val page = PDPage(PDRectangle(image.width.toFloat(), image.height.toFloat()))
                document.addPage(page)
                PDPageContentStream(document, page).use { content ->
                    content.drawImage(image, 0f, 0f, image.width.toFloat(), image.height.toFloat())
                    if (addText) {
                        content.beginText()
                        content.setFont(com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA, 12f)
                        content.newLineAtOffset(20f, 20f)
                        content.showText("embedded text ${index + 1}")
                        content.endText()
                    }
                }
            }
            document.save(output)
        }
        return output
    }

    private fun createEncryptedPdf(): File {
        val output = File(testRoot, "encrypted.pdf")
        PDDocument().use { document ->
            document.addPage(PDPage())
            document.protect(
                StandardProtectionPolicy("owner-password", "user-password", AccessPermission()),
            )
            document.save(output)
        }
        return output
    }

    private fun positionedOcr(
        pageIndex: Int,
        width: Int,
        height: Int,
        text: String,
    ) = OcrPageResult(
        pageIndex = pageIndex,
        text = text,
        layout = OcrPageLayout(
            imageWidthPx = width,
            imageHeightPx = height,
            rotationDegrees = 0,
            lines = listOf(
                OcrTextLine(
                    text = text,
                    bounds = OcrTextBounds(20f, 20f, width - 20f, 70f),
                ),
            ),
        ),
    )

    private fun assertReopensAndRenders(file: File, expectedPages: Int) {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                assertEquals(expectedPages, renderer.pageCount)
                repeat(renderer.pageCount) { index ->
                    renderer.openPage(index).use { page ->
                        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
                        try {
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
            }
        }
    }

    private fun readJpegDimensions(file: File): Pair<Int, Int> {
        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.path, options)
        assertTrue(options.outWidth > 0 && options.outHeight > 0)
        return options.outWidth to options.outHeight
    }

    private fun openFileUri(uri: Uri): InputStream? = uri.path?.let { File(it).inputStream() }

    private fun currentPssBytes(): Long {
        val memory = Debug.MemoryInfo()
        Debug.getMemoryInfo(memory)
        return memory.totalPss.toLong() * 1_024L
    }

    private enum class FixtureKind {
        TEXT_FORM,
        RECEIPT,
        INVOICE,
        PHOTO,
        MIXED,
        GRAYSCALE,
        BLACK_AND_WHITE,
        TINY_TEXT,
    }

    private fun FixtureKind?.toProfile(): TargetSizePageContentProfile = when (this) {
        FixtureKind.PHOTO -> TargetSizePageContentProfile.PHOTO_HEAVY
        FixtureKind.GRAYSCALE -> TargetSizePageContentProfile.GRAYSCALE
        FixtureKind.BLACK_AND_WHITE -> TargetSizePageContentProfile.BLACK_AND_WHITE
        FixtureKind.TINY_TEXT -> TargetSizePageContentProfile.TINY_TEXT
        FixtureKind.MIXED -> TargetSizePageContentProfile.MIXED_TEXT_PHOTO
        else -> TargetSizePageContentProfile.TEXT_HEAVY
    }
}
