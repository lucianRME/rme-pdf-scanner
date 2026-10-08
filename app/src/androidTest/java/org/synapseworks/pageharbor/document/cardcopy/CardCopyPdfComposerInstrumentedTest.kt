package org.synapseworks.pageharbor.document.cardcopy

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
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
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
import org.synapseworks.pageharbor.document.targetsize.LocalTargetSizePdfEngine
import org.synapseworks.pageharbor.document.targetsize.TargetSizeBytes
import org.synapseworks.pageharbor.document.targetsize.TargetSizeExistingPdfCopyRequest
import org.synapseworks.pageharbor.document.targetsize.TargetSizeInputClass
import org.synapseworks.pageharbor.document.targetsize.TargetSizePageContentProfile
import org.synapseworks.pageharbor.document.targetsize.TargetSizePdfInputInspector
import org.synapseworks.pageharbor.document.targetsize.TargetSizePdfPage
import org.synapseworks.pageharbor.document.targetsize.TargetSizePdfRequest
import org.synapseworks.pageharbor.document.targetsize.TargetSizePdfResult

class CardCopyPdfComposerInstrumentedTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var testRoot: File

    @Before
    fun setUp() {
        PDFBoxResourceLoader.init(context)
        testRoot = File(context.cacheDir, "card-copy-test-${System.nanoTime()}")
        assertTrue(testRoot.mkdirs())
    }

    @After
    fun tearDown() {
        testRoot.deleteRecursively()
        listOf(CARD_COPY_SOURCE_DIRECTORY, CARD_COPY_OUTPUT_DIRECTORY).forEach { root ->
            File(context.cacheDir, root).listFiles()?.forEach { it.deleteRecursively() }
        }
    }

    @Test
    fun syntheticFixtureSetIsNonSensitiveAndDecodable() {
        val fixtures = listOf(
            fixture("landscape", 1_012, 638, Fixture.LANDSCAPE),
            fixture("portrait", 638, 1_012, Fixture.PORTRAIT),
            fixture("different-crop", 1_200, 520, Fixture.EDGE_TO_EDGE),
            fixture("high-resolution-text", 3_000, 1_900, Fixture.HIGH_RES_TEXT),
            fixture("low-resolution", 180, 110, Fixture.LOW_RES),
            fixture("rotated-front", 638, 1_012, Fixture.ROTATED_FRONT),
            fixture("rotated-back", 638, 1_012, Fixture.ROTATED_BACK),
            fixture("unicode", 1_012, 638, Fixture.UNICODE),
            fixture("photo-heavy", 1_012, 638, Fixture.PHOTO),
            fixture("edge-to-edge", 1_012, 638, Fixture.EDGE_TO_EDGE),
        )

        assertEquals(10, fixtures.size)
        fixtures.forEach { file ->
            assertTrue(file.isFile && file.length() > 0L)
            val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(file.path, options)
            assertTrue("${file.name}: ${options.outWidth}x${options.outHeight}", options.outWidth > 0)
            assertTrue(options.outHeight > 0)
        }
    }

    @Test
    fun generatedMatrixIsOnePageExactVisibleAndLeavesSourcesUnchanged() = runBlocking {
        val frontFile = fixture("front-red", 1_012, 638, Fixture.FRONT_RED)
        val backFile = fixture("back-blue", 1_012, 638, Fixture.BACK_BLUE)
        val frontHash = sha256(frontFile)
        val backHash = sha256(backFile)
        val composer = CardCopyPdfComposer(context)

        CardCopyPageSize.entries.forEach { pageSize ->
            CardCopyLayout.entries.forEach { layout ->
                val result = composer.prepare(
                    source(CardCopySide.FRONT, frontFile),
                    source(CardCopySide.BACK, backFile),
                    CardCopySettings(
                        pageSize = pageSize,
                        layout = layout,
                        alignment = CardCopyAlignment.END,
                        spacing = CardCopySpacing.WIDE,
                    ),
                )
                assertTrue("$pageSize $layout result=$result", result is CardCopyPrepareResult.Success)
                val prepared = (result as CardCopyPrepareResult.Success).prepared
                try {
                    assertTrue(prepared.file.isFile && prepared.bytes == prepared.file.length())
                    assertFalse(prepared.geometry.isClipped())
                    assertTrue(prepared.geometry.aspectRatiosPreserved())
                    PDDocument.load(prepared.file).use { document ->
                        assertEquals(1, document.numberOfPages)
                        assertEquals(pageSize.widthPoints, document.getPage(0).mediaBox.width, 0.1f)
                        assertEquals(pageSize.heightPoints, document.getPage(0).mediaBox.height, 0.1f)
                        assertEquals(2, document.getPage(0).resources.xObjectNames.count())
                    }
                    assertRenderedColors(prepared.file, prepared.geometry)
                } finally {
                    composer.discard(prepared)
                }
            }
        }
        assertTrue(frontHash.contentEquals(sha256(frontFile)))
        assertTrue(backHash.contentEquals(sha256(backFile)))
    }

    @Test
    fun rotationsDestinationVerificationAndShareUseExactPreparedPdf() = runBlocking {
        val frontFile = fixture("rotated-source-front", 638, 1_012, Fixture.FRONT_RED)
        val backFile = fixture("rotated-source-back", 638, 1_012, Fixture.BACK_BLUE)
        val composer = CardCopyPdfComposer(context)
        val result = composer.prepare(
            source(CardCopySide.FRONT, frontFile, DocumentPageRotation.DEGREES_90),
            source(CardCopySide.BACK, backFile, DocumentPageRotation.DEGREES_270),
            CardCopySettings(layout = CardCopyLayout.HORIZONTAL),
        ) as CardCopyPrepareResult.Success
        val prepared = result.prepared
        try {
            val destination = File(
                File(context.cacheDir, CARD_COPY_OUTPUT_DIRECTORY).apply { mkdirs() },
                "destination-${System.nanoTime()}.pdf",
            ).apply { createNewFile() }
            val destinationUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                destination,
            )

            assertEquals(
                CardCopyDestinationResult.Success(prepared.bytes),
                CardCopyDestinationWriter(context).write(prepared, destinationUri),
            )
            assertTrue(prepared.file.readBytes().contentEquals(destination.readBytes()))
            assertOnePageRenders(destination)
            val shareUri = cardCopyShareUri(context, prepared)
            assertNotNull(shareUri)
            assertEquals("content", shareUri?.scheme)
            val sharedBytes = requireNotNull(context.contentResolver.openInputStream(shareUri!!))
                .use(InputStream::readBytes)
            assertTrue(prepared.file.readBytes().contentEquals(sharedBytes))
        } finally {
            composer.discard(prepared)
        }
    }

    @Test
    fun m6OutputIsAcceptedByM4AtOneMegabyteWithoutChangingOriginal() = runBlocking {
        val composer = CardCopyPdfComposer(context)
        val prepared = (composer.prepare(
            source(CardCopySide.FRONT, fixture("m4-front", 1_012, 638, Fixture.FRONT_RED)),
            source(CardCopySide.BACK, fixture("m4-back", 1_012, 638, Fixture.BACK_BLUE)),
            CardCopySettings(),
        ) as CardCopyPrepareResult.Success).prepared
        val originalHash = sha256(prepared.file)
        val rendered = renderToJpeg(prepared.file, "m4-rendered")
        val opener: (Uri) -> InputStream? = { uri -> uri.path?.let { File(it).inputStream() } }
        val engine = LocalTargetSizePdfEngine(
            context = context,
            pdfInspector = TargetSizePdfInputInspector(context, opener),
            openPageInputStream = opener,
            availableTemporaryBytes = { Long.MAX_VALUE },
        )
        val page = TargetSizePdfPage(
            pageId = 1L,
            sourceUri = Uri.fromFile(rendered),
            sourceCategory = DocumentSourceCategory.RENDERED_PDF_PAGE,
            contentType = "image/jpeg",
            imageMetadata = DocumentImageMetadata(rendered.length(), 595, 842),
            contentProfile = TargetSizePageContentProfile.MIXED_TEXT_PHOTO,
        )
        val result = engine.prepareExistingPdfCopy(
            TargetSizeExistingPdfCopyRequest(
                sourcePdfUri = Uri.fromFile(prepared.file),
                documentRequest = TargetSizePdfRequest(
                    pages = listOf(page),
                    originalPdfUris = listOf(Uri.fromFile(prepared.file)),
                    targetBytes = TargetSizeBytes.MB_1,
                ),
            ),
        )

        assertTrue("result=$result", result is TargetSizePdfResult.TargetReached)
        val m4Prepared = (result as TargetSizePdfResult.TargetReached).prepared
        try {
            assertTrue(
                m4Prepared.plan.inputClass == TargetSizeInputClass.IMAGE_BASED_PDF_SUPPORTED ||
                    m4Prepared.plan.inputClass == TargetSizeInputClass.MIXED_PDF_SUPPORTED_WITH_RECONSTRUCTION,
            )
            assertTrue(m4Prepared.finalBytes <= TargetSizeBytes.MB_1)
            assertOnePageRenders(m4Prepared.file)
            assertRenderedColors(m4Prepared.file, prepared.geometry)
            assertTrue(originalHash.contentEquals(sha256(prepared.file)))
        } finally {
            engine.discard(m4Prepared)
            composer.discard(prepared)
        }
    }

    @Test
    fun highResolutionPreparationReportsBoundedPerformanceAndCleansUp() = runBlocking {
        val composer = CardCopyPdfComposer(context)
        val baselinePss = Debug.getPss().toLong() * 1_024L
        val result = composer.prepare(
            source(CardCopySide.FRONT, fixture("perf-front", 3_000, 1_900, Fixture.HIGH_RES_TEXT)),
            source(CardCopySide.BACK, fixture("perf-back", 3_000, 1_900, Fixture.PHOTO), DocumentPageRotation.DEGREES_180),
            CardCopySettings(layout = CardCopyLayout.VERTICAL),
        ) as CardCopyPrepareResult.Success
        val prepared = result.prepared
        val retainedGrowth = (Debug.getPss().toLong() * 1_024L - baselinePss).coerceAtLeast(0L)
        Log.i(
            "RME_M6_METRICS",
            "prepare_ms=${prepared.preparationMillis} estimated_peak_bitmap_bytes=${prepared.estimatedPeakBitmapBytes} " +
                "temp_peak_bytes=${prepared.peakTemporaryBytes} final_bytes=${prepared.bytes} " +
                "retained_pss_growth_bytes=$retainedGrowth",
        )
        assertTrue(prepared.preparationMillis >= 0L)
        assertTrue(prepared.estimatedPeakBitmapBytes in 1 until 64L * 1_024L * 1_024L)
        assertTrue(prepared.peakTemporaryBytes >= prepared.bytes)
        assertTrue(prepared.file.isFile)
        val directory = prepared.workingDirectory
        composer.discard(prepared)
        assertFalse(directory.exists())
    }

    private fun source(
        side: CardCopySide,
        file: File,
        rotation: DocumentPageRotation = DocumentPageRotation.DEGREES_0,
    ): CardCopySource {
        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.path, options)
        return CardCopySource(
            side = side,
            acquisition = CardCopyAcquisition.IMPORT,
            file = file,
            ownedDirectory = testRoot,
            metadata = DocumentImageMetadata(file.length(), options.outWidth, options.outHeight),
            rotation = rotation,
        )
    }

    private fun fixture(name: String, width: Int, height: Int, kind: Fixture): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            when (kind) {
                Fixture.FRONT_RED -> canvas.drawColor(Color.rgb(220, 35, 35))
                Fixture.BACK_BLUE -> canvas.drawColor(Color.rgb(35, 65, 220))
                Fixture.PHOTO -> {
                    for (y in 0 until height step 12) for (x in 0 until width step 12) {
                        paint.color = Color.rgb((x * 7 + y * 3) and 255, (x + y * 5) and 255, (x * 3 + y) and 255)
                        canvas.drawRect(x.toFloat(), y.toFloat(), (x + 12).toFloat(), (y + 12).toFloat(), paint)
                    }
                }
                Fixture.EDGE_TO_EDGE -> {
                    canvas.drawColor(Color.BLACK)
                    paint.color = Color.YELLOW
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 12f
                    canvas.drawRect(6f, 6f, width - 6f, height - 6f, paint)
                    paint.style = Paint.Style.FILL
                }
                else -> {
                    canvas.drawColor(if (kind == Fixture.LOW_RES) Color.LTGRAY else Color.WHITE)
                    paint.color = Color.rgb(30, 50, 90)
                    paint.textSize = if (kind == Fixture.HIGH_RES_TEXT) 28f else 42f
                    val text = when (kind) {
                        Fixture.UNICODE -> "Synthetic card - RME - ecole - 123"
                        Fixture.PORTRAIT -> "PORTRAIT CARD"
                        Fixture.ROTATED_FRONT -> "ROTATED FRONT"
                        Fixture.ROTATED_BACK -> "ROTATED BACK"
                        else -> "SYNTHETIC CARD"
                    }
                    repeat(12) { line ->
                        canvas.drawText("$text $line", 24f, 54f + line * paint.textSize * 1.3f, paint)
                    }
                }
            }
            val file = File(testRoot, "$name.jpg")
            FileOutputStream(file).use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 94, output))
                output.flush()
                output.fd.sync()
            }
            return file
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertRenderedColors(file: File, geometry: CardCopyGeometry) {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                assertEquals(1, renderer.pageCount)
                renderer.openPage(0).use { page ->
                    val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        assertCardColor(bitmap, geometry.front.bounds, Color.RED)
                        assertCardColor(bitmap, geometry.back.bounds, Color.BLUE)
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        }
    }

    private fun assertCardColor(bitmap: Bitmap, bounds: CardCopyRect, expected: Int) {
        val x = ((bounds.left + bounds.right) / 2f).toInt().coerceIn(0, bitmap.width - 1)
        val y = ((bounds.top + bounds.bottom) / 2f).toInt().coerceIn(0, bitmap.height - 1)
        val actual = bitmap.getPixel(x, y)
        if (expected == Color.RED) assertTrue(Color.red(actual) > Color.blue(actual) + 80)
        else assertTrue(Color.blue(actual) > Color.red(actual) + 80)
    }

    private fun assertOnePageRenders(file: File) {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                assertEquals(1, renderer.pageCount)
                renderer.openPage(0).use { page ->
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

    private fun renderToJpeg(pdf: File, name: String): File {
        val output = File(testRoot, "$name.jpg")
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                renderer.openPage(0).use { page ->
                    val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        FileOutputStream(output).use { stream ->
                            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream))
                        }
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        }
        return output
    }

    private fun sha256(file: File): ByteArray = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())

    private enum class Fixture {
        LANDSCAPE,
        PORTRAIT,
        HIGH_RES_TEXT,
        LOW_RES,
        ROTATED_FRONT,
        ROTATED_BACK,
        UNICODE,
        PHOTO,
        EDGE_TO_EDGE,
        FRONT_RED,
        BACK_BLUE,
    }
}
