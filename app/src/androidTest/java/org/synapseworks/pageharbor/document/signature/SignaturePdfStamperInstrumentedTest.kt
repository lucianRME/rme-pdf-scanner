package org.synapseworks.pageharbor.document.signature

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
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SignaturePdfStamperInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private val signature = HandwrittenSignature(
        listOf(SignatureStroke(listOf(SignaturePoint(0f, 0f), SignaturePoint(0f, .8f), SignaturePoint(1f, .8f)))),
    )
    private val placement = SignaturePlacement(.2f, .25f, .8f, .75f)

    @Before
    fun setUp() {
        PDFBoxResourceLoader.init(context)
        root = File(context.cacheDir, "signature-test-${System.nanoTime()}")
        assertTrue(root.mkdirs())
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun stampsTheVisualPlacementForEveryQuarterTurnWithoutChangingSourceOrOtherPage() = runBlocking {
        for (rotation in listOf(0, 90, 180, 270)) {
            val source = source(rotation)
            val bytes = source.readBytes()
            val untouchedContent = PDDocument.load(source).use { it.getPage(1).contents.use { stream -> stream.readBytes() } }
            val output = File(root, "signed-$rotation.pdf")
            val result = SignaturePdfStamper.stamp(source, output, SignaturePdfRequest(0, signature, placement))

            assertEquals(SignaturePdfResult.Success(2), result)
            assertArrayEquals(bytes, source.readBytes())
            PDDocument.load(output).use { pdf ->
                assertEquals(2, pdf.numberOfPages)
                assertEquals(rotation, pdf.getPage(0).rotation)
                assertArrayEquals(untouchedContent, pdf.getPage(1).contents.use { it.readBytes() })
            }
            ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    renderer.openPage(0).use { page ->
                        val bitmap = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                        try {
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            assertTrue("Visual top-left signature point should contain ink", hasBlackNear(bitmap, .2f, .25f))
                            assertTrue("Visual bottom-right signature point should contain ink", hasBlackNear(bitmap, .8f, .65f))
                            assertFalse("Blank top-right area should stay blank", hasBlackNear(bitmap, .8f, .25f))
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
            }
        }
    }

    @Test
    fun rejectsExistingOutputAndSameSourceWithoutOverwritingEither() = runBlocking {
        val source = source(0)
        val before = source.readBytes()
        val output = File(root, "existing.pdf").apply { writeBytes(byteArrayOf(7, 8, 9)) }
        val result = SignaturePdfStamper.stamp(source, output, SignaturePdfRequest(0, signature, placement))
        assertEquals(SignaturePdfResult.Failure(SignaturePdfFailure.OUTPUT_EXISTS), result)
        assertArrayEquals(byteArrayOf(7, 8, 9), output.readBytes())
        assertEquals(
            SignaturePdfResult.Failure(SignaturePdfFailure.INVALID_REQUEST),
            SignaturePdfStamper.stamp(source, source, SignaturePdfRequest(0, signature, placement)),
        )
        assertArrayEquals(before, source.readBytes())
    }

    @Test
    fun removesClaimedOutputAfterInvalidPageOrUnreadablePdf() = runBlocking {
        val source = source(0)
        val invalidPage = File(root, "invalid-page.pdf")
        assertEquals(
            SignaturePdfResult.Failure(SignaturePdfFailure.INVALID_REQUEST),
            SignaturePdfStamper.stamp(source, invalidPage, SignaturePdfRequest(30, signature, placement)),
        )
        assertFalse(invalidPage.exists())
        val unreadable = File(root, "unreadable.pdf").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val output = File(root, "failed.pdf")
        assertTrue(SignaturePdfStamper.stamp(unreadable, output, SignaturePdfRequest(0, signature, placement)) is SignaturePdfResult.Failure)
        assertFalse(output.exists())
        assertArrayEquals(byteArrayOf(1, 2, 3), unreadable.readBytes())
    }

    @Test
    fun alreadyCancelledCallDoesNotCreateOutput() = runBlocking {
        val source = source(0)
        val bytes = source.readBytes()
        val output = File(root, "cancelled.pdf")
        try {
            withContext(Job().apply { cancel() }) {
                SignaturePdfStamper.stamp(source, output, SignaturePdfRequest(0, signature, placement))
            }
            throw AssertionError("Expected cancellation")
        } catch (_: CancellationException) {
            // No PDF should be published from a cancelled operation.
        }
        assertFalse(output.exists())
        assertArrayEquals(bytes, source.readBytes())
    }

    private fun source(rotation: Int): File = File(root, "source-$rotation.pdf").also { file ->
        PDDocument().use { document ->
            document.addPage(PDPage(PDRectangle(420f, 300f)).apply {
                cropBox = PDRectangle(20f, 30f, 300f, 200f)
                this.rotation = rotation
            })
            val second = PDPage(PDRectangle.A4)
            document.addPage(second)
            PDPageContentStream(document, second).use { content ->
                content.setLineWidth(2f)
                content.moveTo(40f, 40f)
                content.lineTo(80f, 80f)
                content.stroke()
            }
            document.save(file)
        }
    }

    private fun hasBlackNear(bitmap: Bitmap, x: Float, y: Float): Boolean {
        val cx = (x * bitmap.width).toInt()
        val cy = (y * bitmap.height).toInt()
        for (py in (cy - 6).coerceAtLeast(0)..(cy + 6).coerceAtMost(bitmap.height - 1)) {
            for (px in (cx - 6).coerceAtLeast(0)..(cx + 6).coerceAtMost(bitmap.width - 1)) {
                val color = bitmap.getPixel(px, py)
                if (Color.red(color) < 80 && Color.green(color) < 80 && Color.blue(color) < 80) return true
            }
        }
        return false
    }
}
