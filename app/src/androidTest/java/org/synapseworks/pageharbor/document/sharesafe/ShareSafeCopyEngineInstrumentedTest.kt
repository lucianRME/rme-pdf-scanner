package org.synapseworks.pageharbor.document.sharesafe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Debug
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.synapseworks.pageharbor.document.redaction.RedactionBounds
import org.synapseworks.pageharbor.document.redaction.RedactionCoordinateSpace
import org.synapseworks.pageharbor.document.redaction.RedactionRegion
import org.synapseworks.pageharbor.document.redaction.RedactionRegionId
import org.synapseworks.pageharbor.document.redaction.RedactionTarget
import org.synapseworks.pageharbor.document.redaction.RedactionTargetKind
import org.synapseworks.pageharbor.document.redaction.RedactedPdfVerificationResult
import org.synapseworks.pageharbor.document.redaction.SecureRedactedPdfEngine
import org.synapseworks.pageharbor.document.redaction.SecureRedactedOcrProvenance
import org.synapseworks.pageharbor.document.redaction.SecureRedactedPdfPage
import org.synapseworks.pageharbor.document.redaction.SecureRedactedPdfRequest
import org.synapseworks.pageharbor.document.redaction.SecureRedactedPdfResult
import org.synapseworks.pageharbor.document.redaction.secureRedactionSha256
import org.synapseworks.pageharbor.document.cardcopy.CardCopyAcquisition
import org.synapseworks.pageharbor.document.cardcopy.CardCopyPdfComposer
import org.synapseworks.pageharbor.document.cardcopy.CardCopyPrepareResult
import org.synapseworks.pageharbor.document.cardcopy.CardCopyPreparedPdf
import org.synapseworks.pageharbor.document.cardcopy.CardCopySettings
import org.synapseworks.pageharbor.document.cardcopy.CardCopySide
import org.synapseworks.pageharbor.document.cardcopy.CardCopySource
import org.synapseworks.pageharbor.document.session.DocumentImageMetadata
import org.synapseworks.pageharbor.document.session.DocumentPageRotation
import org.synapseworks.pageharbor.document.targetsize.TargetSizeBytes
import org.synapseworks.pageharbor.ocr.OcrPageLayout
import org.synapseworks.pageharbor.ocr.OcrPageResult
import org.synapseworks.pageharbor.ocr.OcrTextBounds
import org.synapseworks.pageharbor.ocr.OcrTextLine

class ShareSafeCopyEngineInstrumentedTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File

    @Before
    fun setUp() {
        root = File(context.cacheDir, "share-safe-test-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
        deleteStaleShareSafeFiles(context.cacheDir, Long.MAX_VALUE, 0L)
        File(context.cacheDir, "secure-redacted-pdfs").deleteRecursively()
        File(context.cacheDir, "target-size-pdfs").deleteRecursively()
    }

    @Test
    fun redactionThenWatermarkThenTargetSizeProducesOneVerifiedFinalCapability() = runBlocking {
        val source = fixture()
        val sourceHash = requireNotNull(secureRedactionSha256(source::inputStream))
        val generation = 7L
        val pageId = "synthetic-page"
        val region = RedactionRegion(
            id = RedactionRegionId("secret-region"),
            coordinateSpace = RedactionCoordinateSpace.SOURCE_NORMALIZED,
            bounds = RedactionBounds(0.08f, 0.08f, 0.62f, 0.24f),
            target = RedactionTarget(RedactionTargetKind.DOCUMENT_PAGE, pageId),
            confirmed = true,
            order = 0,
        )
        val input = ShareSafeDocumentInput(
            title = "Synthetic document",
            sourceGeneration = generation,
            pages = listOf(
                ShareSafeDocumentPage(
                    source = SecureRedactedPdfPage(
                        pageId = pageId,
                        openSource = source::inputStream,
                        imageMetadata = DocumentImageMetadata(source.length(), WIDTH, HEIGHT),
                        expectedSourceSha256 = sourceHash,
                        regions = listOf(region),
                        effectiveOcr = OcrPageResult(
                            pageIndex = 0,
                            text = "$SECRET\n$SAFE_TEXT",
                            layout = OcrPageLayout(
                                imageWidthPx = WIDTH,
                                imageHeightPx = HEIGHT,
                                lines = listOf(
                                    OcrTextLine(SECRET, OcrTextBounds(80f, 90f, 700f, 260f)),
                                    OcrTextLine(SAFE_TEXT, OcrTextBounds(80f, 800f, 720f, 900f)),
                                ),
                            ),
                        ),
                        ocrProvenance = SecureRedactedOcrProvenance(
                            sourceHash,
                            WIDTH,
                            HEIGHT,
                            DocumentPageRotation.DEGREES_0,
                        ),
                    ),
                    previewUri = Uri.fromFile(source),
                ),
            ),
        )
        val watermark = ShareSafeWatermarkConfig(
            enabled = true,
            purposeText = PURPOSE,
            includeDate = true,
            dateText = "9 Oct 2026",
            placement = ShareSafeWatermarkPlacement.DIAGONAL,
            opacity = ShareSafeWatermarkOpacity.STRONG,
        )
        val stages = mutableListOf<ShareSafePipelineStage>()
        val engine = ShareSafeCopyEngine(context)

        val result = engine.prepareDocument(
            input = input,
            recipe = ShareSafeRecipe.UPLOAD_TO_PORTAL,
            watermark = watermark,
            targetSize = ShareSafeTargetSizeConfig(true, TargetSizeBytes.MB_1),
            expectedGeneration = generation,
            onProgress = stages::add,
        )

        assertTrue("result=$result", result is ShareSafePrepareResult.Success)
        val prepared = (result as ShareSafePrepareResult.Success).prepared
        try {
            assertEquals(
                listOf(
                    ShareSafePipelineStage.SANITIZING,
                    ShareSafePipelineStage.WATERMARKING,
                    ShareSafePipelineStage.COMPRESSING,
                    ShareSafePipelineStage.FINAL_VERIFYING,
                ),
                stages,
            )
            assertTrue(prepared.bytes <= TargetSizeBytes.MB_1)
            assertEquals(watermark.digest(), prepared.artifact.watermarkConfigDigest)
            assertEquals(ShareSafeArtifactStage.FINAL, prepared.artifact.stage)
            assertTrue(engine.revalidateFinal(prepared, generation))
            assertFalse(engine.revalidateFinal(prepared, generation + 1L))
            assertEquals(ShareSafeSearchableTextStatus.NOT_PRESENT, prepared.searchableTextStatus)
            assertFalse(prepared.artifact.file.readText(Charsets.ISO_8859_1).contains(SECRET))
            assertFalse(prepared.artifact.file.readText(Charsets.ISO_8859_1).contains(PURPOSE))
            PDDocument.load(prepared.artifact.file).use { pdf ->
                assertEquals(1, pdf.numberOfPages)
                assertTrue(PDFTextStripper().getText(pdf).isBlank())
                assertTrue(pdf.getPage(0).annotations.isEmpty())
            }
            assertRendersWithOpaqueRedactionAndWatermarkInk(prepared.artifact.file)
            assertNotEquals(sourceHash, prepared.sha256)
            assertTrue(prepared.performance.totalMillis >= 0L)
            assertTrue(prepared.performance.peakTemporaryBytes >= prepared.bytes)

            verifyHostileDestinationFailures(prepared, generation)
            val exactBytes = prepared.artifact.file.readBytes()
            val tampered = exactBytes.copyOf().also { bytes ->
                bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            }
            prepared.artifact.file.writeBytes(tampered)
            assertFalse(engine.revalidateFinal(prepared, generation))
            prepared.artifact.file.writeBytes(exactBytes)
            assertTrue(engine.revalidateFinal(prepared, generation))
        } finally {
            engine.discard(prepared)
        }
    }

    @Test
    fun archivePreservesOnlySourceBoundSearchableTextWithoutForcedCompression() = runBlocking {
        val source = fixture("archive-source.jpg", WIDTH, HEIGHT, SAFE_TEXT)
        val sourceHash = requireNotNull(secureRedactionSha256(source::inputStream))
        val generation = 11L
        val input = ShareSafeDocumentInput(
            title = "Synthetic archive",
            sourceGeneration = generation,
            pages = listOf(
                ShareSafeDocumentPage(
                    source = SecureRedactedPdfPage(
                        pageId = "archive-page",
                        openSource = source::inputStream,
                        imageMetadata = DocumentImageMetadata(source.length(), WIDTH, HEIGHT),
                        expectedSourceSha256 = sourceHash,
                        regions = emptyList(),
                        effectiveOcr = OcrPageResult(
                            pageIndex = 0,
                            text = SAFE_TEXT,
                            layout = OcrPageLayout(
                                imageWidthPx = WIDTH,
                                imageHeightPx = HEIGHT,
                                lines = listOf(
                                    OcrTextLine(SAFE_TEXT, OcrTextBounds(80f, 90f, 720f, 210f)),
                                ),
                            ),
                        ),
                        ocrProvenance = SecureRedactedOcrProvenance(
                            recognizedSourceSha256 = sourceHash,
                            effectiveWidthPx = WIDTH,
                            effectiveHeightPx = HEIGHT,
                            rotation = DocumentPageRotation.DEGREES_0,
                        ),
                    ),
                    previewUri = Uri.fromFile(source),
                ),
            ),
        )
        val engine = ShareSafeCopyEngine(context)
        val result = engine.prepareDocument(
            input = input,
            recipe = ShareSafeRecipe.ARCHIVE_DOCUMENT,
            watermark = ShareSafeWatermarkConfig(enabled = false),
            targetSize = ShareSafeTargetSizeConfig(enabled = false),
            expectedGeneration = generation,
        )

        assertTrue("archive result=$result", result is ShareSafePrepareResult.Success)
        val prepared = (result as ShareSafePrepareResult.Success).prepared
        try {
            assertEquals(ShareSafeSearchableTextStatus.PRESERVED, prepared.searchableTextStatus)
            assertFalse(prepared.targetSize.enabled)
            assertFalse(prepared.watermark.enabled)
            assertTrue(engine.revalidateFinal(prepared, generation))
            PDDocument.load(prepared.artifact.file).use { pdf ->
                assertEquals(SAFE_TEXT, PDFTextStripper().getText(pdf).trim())
            }
        } finally {
            engine.discard(prepared)
        }
    }

    @Test
    fun alreadyFitsTargetIsSuccessfulWithoutRecompression() = runBlocking {
        val source = fixture("tiny-white-page.jpg", 64, 64, "")
        val sourceHash = requireNotNull(secureRedactionSha256(source::inputStream))
        val generation = 17L
        val pages = List(12) { index ->
            SecureRedactedPdfPage(
                pageId = "tiny-page-$index",
                openSource = source::inputStream,
                imageMetadata = DocumentImageMetadata(source.length(), 64, 64),
                expectedSourceSha256 = sourceHash,
                regions = emptyList(),
            )
        }
        val sanitizedResult = SecureRedactedPdfEngine(context).prepare(
            SecureRedactedPdfRequest(pages = pages, requireAtLeastOneRedaction = false),
        )
        assertTrue("sanitized result=$sanitizedResult", sanitizedResult is SecureRedactedPdfResult.Success)
        val sanitized = (sanitizedResult as SecureRedactedPdfResult.Success).prepared
        val artifact = requireNotNull(sanitized.toShareSafeAttestation(generation))
        assertTrue("fixture must already fit target: bytes=${artifact.evidence.bytes}", artifact.evidence.bytes < 100_000L)

        val result = ShareSafeTargetSizeAdapter(context).compress(
            source = artifact,
            targetBytes = 100_000L,
            expectedGeneration = generation,
        )

        assertTrue("already-fits result=$result", result is ShareSafeTargetSizeResult.Success)
        val success = result as ShareSafeTargetSizeResult.Success
        assertEquals(ShareSafeTargetSizeResult.Outcome.ALREADY_FITS, success.outcome)
        assertTrue(success.artifact.evidence.bytes <= 100_000L)
        success.artifact.ownedDirectory.deleteRecursively()
        Unit
    }

    @Test
    fun largerThanTargetIsCompressedAndReverified() = runBlocking {
        val source = noisyFixture("large-photo.jpg", 1_000, 1_400)
        val sourceHash = requireNotNull(secureRedactionSha256(source::inputStream))
        val generation = 18L
        val sanitizedResult = SecureRedactedPdfEngine(context).prepare(
            SecureRedactedPdfRequest(
                pages = listOf(
                    SecureRedactedPdfPage(
                        pageId = "large-photo",
                        openSource = source::inputStream,
                        imageMetadata = DocumentImageMetadata(source.length(), 1_000, 1_400),
                        expectedSourceSha256 = sourceHash,
                        regions = emptyList(),
                    ),
                ),
                requireAtLeastOneRedaction = false,
            ),
        )
        assertTrue("sanitized result=$sanitizedResult", sanitizedResult is SecureRedactedPdfResult.Success)
        val sanitized = (sanitizedResult as SecureRedactedPdfResult.Success).prepared
        val artifact = requireNotNull(sanitized.toShareSafeAttestation(generation))
        assertTrue(
            "fixture must require compression: bytes=${artifact.evidence.bytes}",
            artifact.evidence.bytes > TargetSizeBytes.KB_500,
        )

        val result = ShareSafeTargetSizeAdapter(context).compress(
            source = artifact,
            targetBytes = TargetSizeBytes.KB_500,
            expectedGeneration = generation,
        )

        assertTrue("compression result=$result", result is ShareSafeTargetSizeResult.Success)
        val success = result as ShareSafeTargetSizeResult.Success
        assertEquals(ShareSafeTargetSizeResult.Outcome.COMPRESSED_TO_TARGET, success.outcome)
        assertTrue(success.artifact.evidence.bytes <= TargetSizeBytes.KB_500)
        assertTrue(success.artifact.file.isFile)
        success.artifact.ownedDirectory.deleteRecursively()
        artifact.ownedDirectory.deleteRecursively()
        Unit
    }

    @Test
    fun representativeCardAndEightPagePipelinesStayBoundedAndCleanUp() = runBlocking {
        val engine = ShareSafeCopyEngine(context)
        val watermark = ShareSafeWatermarkConfig(
            enabled = true,
            purposeText = "For synthetic verification only",
            includeDate = true,
            dateText = "9 Oct 2026",
            placement = ShareSafeWatermarkPlacement.REPEATED,
            opacity = ShareSafeWatermarkOpacity.NORMAL,
        )
        val pssBefore = currentPssBytes()

        val cardA = composeCardWithFourRedactions("a")
        val cardAResult = engine.prepareAttestedCardCopy(
            initial = requireNotNull(cardA.toShareSafeAttestation(21L)),
            recipe = ShareSafeRecipe.SHARE_ID_COPY,
            watermark = watermark,
            targetSize = ShareSafeTargetSizeConfig(enabled = false),
            expectedGeneration = 21L,
        )
        assertTrue("card A result=$cardAResult", cardAResult is ShareSafePrepareResult.Success)
        val preparedA = (cardAResult as ShareSafePrepareResult.Success).prepared
        assertEquals(4, preparedA.artifact.policy.pages.single().requiredOpaqueBlackRegions.size)
        Log.i(
            PERFORMANCE_TAG,
            performanceLine("A", preparedA, redactionMillis = cardA.preparationMillis, pssBefore),
        )
        engine.discard(preparedA)

        val cardB = composeCardWithFourRedactions("b")
        val cardBResult = engine.prepareAttestedCardCopy(
            initial = requireNotNull(cardB.toShareSafeAttestation(22L)),
            recipe = ShareSafeRecipe.SHARE_ID_COPY,
            watermark = watermark,
            targetSize = ShareSafeTargetSizeConfig(true, TargetSizeBytes.MB_1),
            expectedGeneration = 22L,
        )
        assertTrue("card B result=$cardBResult", cardBResult is ShareSafePrepareResult.Success)
        val preparedB = (cardBResult as ShareSafePrepareResult.Success).prepared
        assertTrue(preparedB.bytes <= TargetSizeBytes.MB_1)
        assertEquals(4, preparedB.artifact.policy.pages.single().requiredOpaqueBlackRegions.size)
        Log.i(
            PERFORMANCE_TAG,
            performanceLine("B", preparedB, redactionMillis = cardB.preparationMillis, pssBefore),
        )
        engine.discard(preparedB)

        val documentSource = fixture("eight-page-source.jpg", 1_000, 1_400, "SYNTHETIC PAGE")
        val documentHash = requireNotNull(secureRedactionSha256(documentSource::inputStream))
        val pages = List(8) { index ->
            val redactionCount = if (index < 2) 2 else 1
            val pageId = "page-$index"
            ShareSafeDocumentPage(
                source = SecureRedactedPdfPage(
                    pageId = pageId,
                    openSource = documentSource::inputStream,
                    imageMetadata = DocumentImageMetadata(documentSource.length(), 1_000, 1_400),
                    expectedSourceSha256 = documentHash,
                    regions = List(redactionCount) { regionIndex ->
                        val top = 0.08f + regionIndex * 0.2f
                        RedactionRegion(
                            id = RedactionRegionId("document-$index-$regionIndex"),
                            coordinateSpace = RedactionCoordinateSpace.SOURCE_NORMALIZED,
                            bounds = RedactionBounds(0.1f, top, 0.45f, top + 0.1f),
                            target = RedactionTarget(RedactionTargetKind.DOCUMENT_PAGE, pageId),
                            confirmed = true,
                            order = regionIndex,
                        )
                    },
                ),
                previewUri = Uri.fromFile(documentSource),
            )
        }
        assertEquals(10, pages.sumOf { it.redactions.size })
        val documentResult = engine.prepareDocument(
            input = ShareSafeDocumentInput("Eight synthetic pages", pages, 23L),
            recipe = ShareSafeRecipe.UPLOAD_TO_PORTAL,
            watermark = watermark,
            targetSize = ShareSafeTargetSizeConfig(true, TargetSizeBytes.MB_2),
            expectedGeneration = 23L,
        )
        assertTrue("document C result=$documentResult", documentResult is ShareSafePrepareResult.Success)
        val preparedC = (documentResult as ShareSafePrepareResult.Success).prepared
        assertEquals(8, preparedC.pageCount)
        assertTrue(preparedC.bytes <= TargetSizeBytes.MB_2)
        assertEquals(10, preparedC.artifact.policy.pages.sumOf { it.requiredOpaqueBlackRegions.size })
        Log.i(PERFORMANCE_TAG, performanceLine("C", preparedC, pssBefore = pssBefore))
        engine.discard(preparedC)

        assertTrue(
            File(context.cacheDir, SHARE_SAFE_PUBLISHED_DIRECTORY)
                .walkTopDown().none(File::isFile),
        )
        assertTrue(
            File(context.cacheDir, SHARE_SAFE_WORK_DIRECTORY)
                .walkTopDown().none(File::isFile),
        )
    }

    private suspend fun verifyHostileDestinationFailures(
        prepared: ShareSafePreparedPdf,
        generation: Long,
    ) {
        val uri = Uri.parse("content://share-safe-test/destination.pdf")
        val expected = prepared.artifact.evidence

        suspend fun run(
            output: OutputStream?,
            destinationBytes: () -> ByteArray?,
            cleanup: AtomicBoolean = AtomicBoolean(false),
        ): ShareSafeDestinationResult = ShareSafeDestinationWriter(
            context = context,
            openOutput = { output },
            openInput = { destinationBytes()?.inputStream() },
            verifyDestination = { _, _ -> RedactedPdfVerificationResult.Verified(expected) },
            cleanupPartialIfSafe = { cleanup.set(true); true },
        ).write(prepared, generation, uri)

        val exact = ByteArrayOutputStream()
        assertEquals(
            ShareSafeDestinationResult.Success(prepared.bytes),
            run(exact, exact::toByteArray),
        )

        var committedAfterClose: ByteArray? = null
        val delayed = object : ByteArrayOutputStream() {
            override fun close() {
                super.close()
                committedAfterClose = toByteArray()
            }
        }
        assertEquals(
            ShareSafeDestinationResult.Success(prepared.bytes),
            run(delayed, { committedAfterClose }),
        )

        val short = ByteArrayOutputStream()
        val cleanup = AtomicBoolean(false)
        val shortResult = run(
            object : OutputStream() {
                private var count = 0
                override fun write(value: Int) {
                    if (count++ < 64) short.write(value)
                }
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    val accepted = (64 - count).coerceAtLeast(0).coerceAtMost(length)
                    short.write(bytes, offset, accepted)
                    count += accepted
                }
            },
            short::toByteArray,
            cleanup,
        )
        assertEquals(
            ShareSafeDestinationResult.Failure(ShareSafeFailure.FINAL_VERIFICATION_FAILED),
            shortResult,
        )
        assertTrue(cleanup.get())

        assertEquals(
            ShareSafeDestinationResult.Failure(ShareSafeFailure.WRITE_FAILED),
            run(
                object : OutputStream() {
                    override fun write(value: Int) = throw IOException()
                },
                { null },
            ),
        )
        assertEquals(
            ShareSafeDestinationResult.Failure(ShareSafeFailure.FINAL_VERIFICATION_FAILED),
            run(ByteArrayOutputStream(), { null }),
        )

        val transformed = prepared.artifact.file.readBytes().also { bytes ->
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        }
        assertEquals(
            ShareSafeDestinationResult.Failure(ShareSafeFailure.FINAL_VERIFICATION_FAILED),
            run(ByteArrayOutputStream(), { transformed }),
        )

        var cancellationPropagated = false
        try {
            run(
                object : OutputStream() {
                    override fun write(value: Int) = throw CancellationException()
                    override fun write(bytes: ByteArray, offset: Int, length: Int) =
                        throw CancellationException()
                },
                { null },
            )
        } catch (_: CancellationException) {
            cancellationPropagated = true
        }
        assertTrue(cancellationPropagated)
    }

    private fun fixture(): File = fixture("synthetic.jpg", WIDTH, HEIGHT, SECRET)

    private fun fixture(name: String, width: Int, height: Int, label: String): File =
        File(root, name).also { file ->
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 72f
            }
            canvas.drawText(label, 80f, 180f, paint)
            paint.color = Color.rgb(20, 70, 150)
            canvas.drawText(SAFE_TEXT, 80f, 870f, paint)
            file.outputStream().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 96, output))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun noisyFixture(name: String, width: Int, height: Int): File =
        File(root, name).also { file ->
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(width * height)
            var seed = 0x13579BDF
            pixels.indices.forEach { index ->
                seed = seed * 1_664_525 + 1_013_904_223
                pixels[index] = Color.rgb(
                    (seed ushr 16) and 0xff,
                    (seed ushr 8) and 0xff,
                    seed and 0xff,
                )
            }
            try {
                bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
                file.outputStream().use { output ->
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output))
                }
            } finally {
                bitmap.recycle()
            }
        }

    private suspend fun composeCardWithFourRedactions(suffix: String): CardCopyPreparedPdf {
        fun source(side: CardCopySide, color: Int): CardCopySource {
            val directory = File(root, "$suffix-${side.name.lowercase()}").apply { mkdirs() }
            val file = File(directory, "synthetic-card.jpg")
            val bitmap = Bitmap.createBitmap(1_012, 638, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                canvas.drawColor(color)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = Color.WHITE
                    textSize = 60f
                }
                canvas.drawText("SYNTHETIC ${side.name}", 70f, 180f, paint)
                file.outputStream().use { output ->
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 96, output))
                }
            } finally {
                bitmap.recycle()
            }
            val initial = CardCopySource(
                side = side,
                acquisition = CardCopyAcquisition.IMPORT,
                file = file,
                ownedDirectory = directory,
                metadata = DocumentImageMetadata(file.length(), 1_012, 638),
            )
            val regions = listOf(
                RedactionBounds(0.08f, 0.12f, 0.42f, 0.27f),
                RedactionBounds(0.56f, 0.62f, 0.9f, 0.77f),
            ).mapIndexed { index, bounds ->
                RedactionRegion(
                    id = RedactionRegionId("$suffix-${side.name}-$index"),
                    coordinateSpace = RedactionCoordinateSpace.SOURCE_NORMALIZED,
                    bounds = bounds,
                    target = RedactionTarget(RedactionTargetKind.CARD_SOURCE, initial.sourceId),
                    confirmed = true,
                    order = index,
                )
            }
            return initial.copy(redactions = regions)
        }

        val result = CardCopyPdfComposer(context).prepare(
            front = source(CardCopySide.FRONT, Color.rgb(120, 35, 35)),
            back = source(CardCopySide.BACK, Color.rgb(25, 65, 125)),
            settings = CardCopySettings(),
        )
        assertTrue("card composition result=$result", result is CardCopyPrepareResult.Success)
        return (result as CardCopyPrepareResult.Success).prepared
    }

    private fun performanceLine(
        fixture: String,
        prepared: ShareSafePreparedPdf,
        redactionMillis: Long = prepared.performance.sanitizationMillis,
        pssBefore: Long,
    ): String = with(prepared.performance) {
        val retainedPss = (currentPssBytes() - pssBefore).coerceAtLeast(0L)
        "fixture=$fixture redactionMs=$redactionMillis watermarkMs=$watermarkMillis " +
            "compressionMs=$compressionMillis verifyMs=$finalVerificationMillis " +
            "totalMs=${redactionMillis + watermarkMillis + compressionMillis + finalVerificationMillis} " +
            "estimatedPeakBitmapBytes=$estimatedPeakBitmapBytes retainedPssBytes=$retainedPss " +
            "peakTempBytes=$peakTemporaryBytes finalBytes=${prepared.bytes} cleanup=verified"
    }

    private fun currentPssBytes(): Long = Debug.getPss().toLong() * 1_024L

    private fun assertRendersWithOpaqueRedactionAndWatermarkInk(file: File) {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                renderer.openPage(0).use { page ->
                    val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val redaction = bitmap.getPixel(bitmap.width / 4, bitmap.height / 7)
                        assertEquals(255, Color.alpha(redaction))
                        assertTrue(Color.red(redaction) < 32)
                        assertTrue(Color.green(redaction) < 32)
                        assertTrue(Color.blue(redaction) < 32)
                        var nonWhiteOutsideRedaction = 0
                        for (y in bitmap.height / 3 until bitmap.height * 2 / 3 step 3) {
                            for (x in bitmap.width / 5 until bitmap.width * 4 / 5 step 3) {
                                val pixel = bitmap.getPixel(x, y)
                                if (Color.red(pixel) < 220 || Color.green(pixel) < 220 ||
                                    Color.blue(pixel) < 220
                                ) nonWhiteOutsideRedaction++
                            }
                        }
                        assertTrue("expected flattened watermark ink", nonWhiteOutsideRedaction > 20)
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        }
    }

    private companion object {
        const val WIDTH = 1_000
        const val HEIGHT = 1_400
        const val SECRET = "SECRET-ALPHA-927"
        const val SAFE_TEXT = "PUBLIC INVOICE"
        const val PURPOSE = "For rental application only"
        const val PERFORMANCE_TAG = "RME_SHARE_SAFE_PERF"
    }
}
