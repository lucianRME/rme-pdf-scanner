package org.synapseworks.pageharbor.document

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.synapseworks.pageharbor.image.DocumentFilter
import org.synapseworks.pageharbor.document.session.DocumentResource
import org.synapseworks.pageharbor.document.session.DocumentResourceOwnership
import org.synapseworks.pageharbor.document.session.DEFAULT_MAX_IMAGE_SOURCE_BYTES
import org.synapseworks.pageharbor.document.session.DocumentImageMetadata

class NormalPdfRecomposerIntegrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun recomposesOrderedFilteredVisualsWithoutAnOcrTextLayerAndPreparesForSharing() = runBlocking {
        val original = sourceFile("original.jpg")
        val filtered = sourceFile("filtered.jpg")
        writeJpeg(original, Color.rgb(30, 100, 220))
        writeJpeg(filtered, Color.rgb(220, 40, 20))
        val originalBytes = original.readBytes()
        val filteredBytes = filtered.readBytes()

        try {
            val recomposed = recomposeNormalPdf(
                context,
                listOf(
                    NormalPdfPage(1L, resource(original), DocumentFilter.ORIGINAL),
                    NormalPdfPage(2L, resource(filtered), DocumentFilter.GRAYSCALE),
                ),
            )

            assertTrue(recomposed is NormalPdfRecompositionResult.Ready)
            recomposed as NormalPdfRecompositionResult.Ready
            assertTrue(recomposed.file.exists())
            PDDocument.load(recomposed.file).use { document ->
                assertEquals(2, document.numberOfPages)
                assertEquals("", PDFTextStripper().getText(document).trim())
            }
            renderPixel(recomposed.file, pageIndex = 0).let { pixel ->
                assertTrue(Color.blue(pixel) > Color.red(pixel))
            }
            renderPixel(recomposed.file, pageIndex = 1).let { pixel ->
                assertTrue(abs(Color.red(pixel) - Color.green(pixel)) <= 2)
                assertTrue(abs(Color.green(pixel) - Color.blue(pixel)) <= 2)
            }
            assertArrayEquals(originalBytes, original.readBytes())
            assertArrayEquals(filteredBytes, filtered.readBytes())

            val shareResult = preparePdfForSharing(context, android.net.Uri.fromFile(recomposed.file))
            assertTrue(shareResult is PdfSharePreparationResult.Ready)
            assertTrue(
                context.contentResolver.openInputStream(
                    (shareResult as PdfSharePreparationResult.Ready).uri,
                )?.use { it.readBytes() }?.isNotEmpty() == true,
            )

            deleteNormalPdfRecomposition(recomposed.file)
            assertFalse(recomposed.file.exists())
        } finally {
            original.delete()
            filtered.delete()
        }
    }

    @Test
    fun knownOversizedOriginalJpegReturnsTypedFailureBeforeOpeningTheSource() = runBlocking {
        val missingSource = sourceFile("oversized-missing.jpg")

        val result = recomposeNormalPdf(
            context,
            listOf(
                NormalPdfPage(
                    pageId = 1L,
                    source = resource(missingSource),
                    filter = DocumentFilter.ORIGINAL,
                    imageMetadata = DocumentImageMetadata(
                        sourceByteCount = DEFAULT_MAX_IMAGE_SOURCE_BYTES + 1L,
                    ),
                ),
            ),
        )

        assertEquals(NormalPdfRecompositionResult.SourceTooLarge, result)
    }

    private fun renderPixel(pdf: File, pageIndex: Int): Int {
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                renderer.openPage(pageIndex).use { page ->
                    val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                    try {
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        return bitmap.getPixel(page.width / 2, page.height / 2)
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        }
    }

    private fun writeJpeg(file: File, color: Int) {
        val bitmap = Bitmap.createBitmap(240, 320, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(color)
            FileOutputStream(file).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun sourceFile(name: String): File =
        File(File(context.cacheDir, "shared-pdfs").apply { mkdirs() }, "normal-pdf-$name")

    private fun fileUri(file: File) = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )

    private fun resource(file: File) = DocumentResource(
        reference = fileUri(file).toString(),
        ownership = DocumentResourceOwnership.USER_OR_EXTERNAL,
    )
}
