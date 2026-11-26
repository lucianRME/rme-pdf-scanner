package org.synapseworks.pageharbor.document.booklayout

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BookLayoutPdfEngineInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File

    @Before
    fun setUp() {
        PDFBoxResourceLoader.init(context)
        root = File(context.cacheDir, "book-layout-synthetic-${System.nanoTime()}")
        assertTrue(root.mkdir())
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun splitMakesTwoCorrectlyOrderedPagesAndPreservesSourceBytes() = runBlocking {
        val source = spread()
        val before = sha256(source)
        val output = File(root, "split.pdf")
        assertEquals(BookLayoutResult.Success(2), BookLayoutPdfEngine().transform(source, output, BookLayoutSettings()))
        assertEquals(Color.RED, centerPixel(output, 0))
        assertEquals(Color.GREEN, centerPixel(output, 1))
        assertTrue(before.contentEquals(sha256(source)))
        assertNoScratch()
    }

    @Test
    fun rightToLeftSplittingUsesTheRightPageFirst() = runBlocking {
        val output = File(root, "right-first.pdf")
        val result = BookLayoutPdfEngine().transform(spread(), output, BookLayoutSettings(readingOrder = BookReadingOrder.RIGHT_TO_LEFT))
        assertEquals(BookLayoutResult.Success(2), result)
        assertEquals(Color.GREEN, centerPixel(output, 0))
        assertEquals(Color.RED, centerPixel(output, 1))
    }

    @Test
    fun nonzeroCropAndAllQuarterTurnsMatchTheOriginalDisplayedHalves() = runBlocking {
        for (rotation in listOf(0, 90, 180, 270)) {
            val source = spread(rotation, cropped = true)
            val original = render(source, 0, 400, 400)
            val expected = listOf(100 to 100, 100 to 300, 300 to 100, 300 to 300).map { (x, y) -> original.getPixel(x, y) }
            original.recycle()
            val output = File(root, "rotated-$rotation.pdf")
            assertEquals(BookLayoutResult.Success(2), BookLayoutPdfEngine().transform(source, output, BookLayoutSettings()))
            for (index in 0..1) {
                val half = render(output, index, 200, 400)
                try {
                    assertEquals(expected[index * 2], half.getPixel(100, 100))
                    assertEquals(expected[index * 2 + 1], half.getPixel(100, 300))
                } finally {
                    half.recycle()
                }
            }
        }
    }

    @Test
    fun twoUpKeepsOddLastPageAndBlankUnusedSlot() = runBlocking {
        val source = File(root, "three-pages.pdf")
        PDDocument().use { pdf ->
            for ((index, color) in listOf(Color.RED, Color.GREEN, Color.BLUE).withIndex()) {
                val page = PDPage(PDRectangle(200f, 200f))
                pdf.addPage(page)
                PDPageContentStream(pdf, page).use { content ->
                    content.setNonStrokingColor(Color.red(color), Color.green(color), Color.blue(color))
                    content.addRect(0f, 0f, 200f, 200f)
                    content.fill()
                    content.setNonStrokingColor(0, 0, 0)
                    content.beginText()
                    content.setFont(PDType1Font.HELVETICA, 10f)
                    content.newLineAtOffset(20f, 20f)
                    content.showText("RME BOOK TEST ${index + 1}")
                    content.endText()
                }
            }
            pdf.save(source)
        }
        val output = File(root, "two-up.pdf")
        assertEquals(BookLayoutResult.Success(2), BookLayoutPdfEngine().transform(source, output, BookLayoutSettings(BookLayoutMode.TWO_UP)))
        PDDocument.load(output).use { pdf ->
            val text = PDFTextStripper().getText(pdf)
            for (index in 1..3) assertTrue(text.contains("RME BOOK TEST $index"))
        }
        val first = render(output, 0, 800, 600)
        val last = render(output, 1, 800, 600)
        try {
            assertEquals(Color.RED, first.getPixel(200, 300))
            assertEquals(Color.GREEN, first.getPixel(600, 300))
            assertEquals(Color.BLUE, last.getPixel(200, 300))
            assertEquals(Color.WHITE, last.getPixel(600, 300))
        } finally {
            first.recycle()
            last.recycle()
        }
    }

    @Test
    fun existingDestinationProtectedInputAndInvalidPdfLeaveNoPartialOutput() = runBlocking {
        val source = spread()
        val output = File(root, "existing.bin").apply { writeBytes(byteArrayOf(7, 8, 9)) }
        assertEquals(BookLayoutResult.Failure(BookLayoutFailure.OUTPUT_EXISTS), BookLayoutPdfEngine().transform(source, output, BookLayoutSettings()))
        assertTrue(output.readBytes().contentEquals(byteArrayOf(7, 8, 9)))
        assertEquals(BookLayoutResult.Failure(BookLayoutFailure.OUTPUT_EXISTS), BookLayoutPdfEngine().transform(source, source, BookLayoutSettings()))

        val protected = File(root, "protected.pdf")
        PDDocument.load(source).use { pdf ->
            pdf.protect(StandardProtectionPolicy("synthetic-owner", "synthetic-reader", AccessPermission()))
            pdf.save(protected)
        }
        val protectedOutput = File(root, "protected-output.pdf")
        assertEquals(BookLayoutResult.Failure(BookLayoutFailure.PROTECTED_SOURCE), BookLayoutPdfEngine().transform(protected, protectedOutput, BookLayoutSettings()))
        assertFalse(protectedOutput.exists())

        val invalid = File(root, "invalid.pdf").apply { writeText("RME SYNTHETIC INVALID PDF") }
        val invalidOutput = File(root, "invalid-output.pdf")
        assertTrue(BookLayoutPdfEngine().transform(invalid, invalidOutput, BookLayoutSettings()) is BookLayoutResult.Failure)
        assertFalse(invalidOutput.exists())
        assertNoScratch()
    }

    @Test
    fun cancellationAtResultHandoffDeletesTheNewOutput() = runBlocking {
        val source = spread()
        val output = File(root, "cancelled.pdf")
        val ready = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                executor.execute {
                    block.run()
                    if (output.exists()) ready.countDown()
                }
            }
        }
        try {
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                BookLayoutPdfEngine(dispatcher).transform(source, output, BookLayoutSettings())
            }
            assertTrue(ready.await(30, TimeUnit.SECONDS))
            job.cancel()
            job.join()
            assertTrue(job.isCancelled)
            assertFalse(output.exists())
            assertNoScratch()
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun annotationsAreRejectedWithoutSilentlyLosingTheirContent() = runBlocking {
        val initial = spread()
        val source = File(root, "annotated.pdf")
        PDDocument.load(initial).use { document ->
            document.getPage(0).annotations.add(
                PDAnnotationText().apply {
                    rectangle = PDRectangle(20f, 20f, 10f, 10f)
                    contents = "RME SYNTHETIC ANNOTATION"
                },
            )
            document.save(source)
        }
        val before = sha256(source)
        val output = File(root, "annotation-layout.pdf")
        assertEquals(BookLayoutResult.Failure(BookLayoutFailure.UNSUPPORTED_PAGE), BookLayoutPdfEngine().transform(source, output, BookLayoutSettings()))
        assertFalse(output.exists())
        assertTrue(before.contentEquals(sha256(source)))
        assertNoScratch()
    }

    private fun spread(rotation: Int = 0, cropped: Boolean = false): File {
        val output = File(root, "spread-$rotation-$cropped.pdf")
        PDDocument().use { pdf ->
            val page = PDPage(PDRectangle(300f, 200f))
            val left = if (cropped) 50f else 0f
            val bottom = if (cropped) 30f else 0f
            page.cropBox = PDRectangle(left, bottom, 200f, 100f)
            page.rotation = rotation
            pdf.addPage(page)
            PDPageContentStream(pdf, page).use { content ->
                content.setNonStrokingColor(255, 0, 0)
                content.addRect(left, bottom, 100f, 100f)
                content.fill()
                content.setNonStrokingColor(0, 255, 0)
                content.addRect(left + 100f, bottom, 100f, 100f)
                content.fill()
            }
            pdf.save(output)
        }
        return output
    }

    private fun centerPixel(file: File, page: Int): Int = render(file, page, 200, 200).let { bitmap ->
        try { bitmap.getPixel(100, 100) } finally { bitmap.recycle() }
    }

    private fun render(file: File, pageIndex: Int, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                renderer.openPage(pageIndex).use { page ->
                    val matrix = android.graphics.Matrix().apply {
                        setScale(width.toFloat() / page.width, height.toFloat() / page.height)
                    }
                    page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        }
        return bitmap
    }

    private fun sha256(file: File): ByteArray = MessageDigest.getInstance("SHA-256").digest(file.readBytes())

    private fun assertNoScratch() {
        assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".book-layout-") })
    }
}
