package org.synapseworks.pageharbor.document.searchablepdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.synapseworks.pageharbor.ocr.OcrPageLayout
import org.synapseworks.pageharbor.ocr.OcrPageResult
import org.synapseworks.pageharbor.ocr.OcrTextBounds
import org.synapseworks.pageharbor.ocr.OcrTextLine
import org.synapseworks.pageharbor.document.PageExportFailureException
import org.synapseworks.pageharbor.document.PageExportResult

class PdfBoxSearchablePdfGeneratorTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val generator = PdfBoxSearchablePdfGenerator(context)

    @Test
    fun generatesOrderedMultiPagePdfWithImageBackgroundsAndUnicodeText() = runBlocking {
        val firstImage = jpegFixture(width = 320, height = 480, color = Color.rgb(210, 230, 250))
        val secondImage = jpegFixture(width = 480, height = 320, color = Color.rgb(240, 225, 200))
        val thirdImage = jpegFixture(width = 360, height = 360, color = Color.rgb(220, 245, 215))
        val fourthImage = jpegFixture(width = 240, height = 400, color = Color.rgb(245, 220, 225))
        val output = outputFile()
        try {
            val result = generator.generate(
                SearchablePdfRequest(
                    pages = listOf(
                        SearchablePdfPage(
                            openJpegStream = { ByteArrayInputStream(firstImage) },
                            ocrResult = pageResult(
                                pageIndex = 0,
                                width = 320,
                                height = 480,
                                text = "Română: ă â î ș ț",
                            ),
                        ),
                        SearchablePdfPage(
                            openJpegStream = { ByteArrayInputStream(secondImage) },
                            ocrResult = pageResult(
                                pageIndex = 1,
                                width = 480,
                                height = 320,
                                text = "Deutsch: ä ö ü ß",
                            ),
                        ),
                        SearchablePdfPage(
                            openJpegStream = { ByteArrayInputStream(thirdImage) },
                            ocrResult = pageResult(
                                pageIndex = 2,
                                width = 360,
                                height = 360,
                                text = "English: searchable text",
                            ),
                        ),
                        // OCR text without geometry intentionally remains an image-only PDF page.
                        SearchablePdfPage(
                            openJpegStream = { ByteArrayInputStream(fourthImage) },
                            ocrResult = OcrPageResult(pageIndex = 3, text = "No layout"),
                        ),
                    ),
                    outputFile = output,
                ),
            )

            assertEquals(
                SearchablePdfGenerationResult.Success(pageCount = 4, textLayerPageCount = 3),
                result,
            )
            PDDocument.load(output).use { document ->
                assertEquals(4, document.numberOfPages)
                assertTrue(document.getPage(0).resources.xObjectNames.iterator().hasNext())
                assertTrue(document.getPage(1).resources.xObjectNames.iterator().hasNext())
                assertTrue(document.getPage(2).resources.xObjectNames.iterator().hasNext())
                assertTrue(document.getPage(3).resources.xObjectNames.iterator().hasNext())
                assertEquals(
                    "Română: ă â î ș ț\nDeutsch: ä ö ü ß\nEnglish: searchable text",
                    PDFTextStripper().getText(document).trim(),
                )
            }
        } finally {
            output.delete()
        }
    }

    @Test
    fun generatesTwentyOrderedPagesFromReusableFixtureStreams() = runBlocking {
        val fixture = jpegFixture(width = 240, height = 320, color = Color.LTGRAY)
        val output = outputFile()

        try {
            val result = generator.generate(
                SearchablePdfRequest(
                    pages = (0 until 20).map { index ->
                        SearchablePdfPage(
                            openJpegStream = { ByteArrayInputStream(fixture) },
                            ocrResult = pageResult(
                                pageIndex = index,
                                width = 240,
                                height = 320,
                                text = "Page ${index + 1}",
                            ),
                        )
                    },
                    outputFile = output,
                ),
            )

            assertEquals(SearchablePdfGenerationResult.Success(20, 20), result)
            PDDocument.load(output).use { document ->
                assertEquals(20, document.numberOfPages)
                val extracted = PDFTextStripper().getText(document)
                assertTrue(extracted.indexOf("Page 1") < extracted.indexOf("Page 20"))
            }
        } finally {
            output.delete()
        }
    }

    @Test
    fun removesPartialOutputWhenJpegCannotBeRead() = runBlocking {
        val output = outputFile().apply { writeText("partial") }

        val result = generator.generate(
            SearchablePdfRequest(
                pages = listOf(
                    SearchablePdfPage(
                        openJpegStream = { ByteArrayInputStream(byteArrayOf(1, 2, 3)) },
                    ),
                ),
                outputFile = output,
            ),
        )

        assertEquals(
            SearchablePdfGenerationResult.Failure(
                SearchablePdfGenerationError.PAGE_IMAGE_UNREADABLE,
            ),
            result,
        )
        assertFalse(output.exists())
    }

    @Test
    fun removesPartialOutputWhenALaterJpegCannotBeRead() = runBlocking {
        val firstImage = jpegFixture(width = 240, height = 320, color = Color.LTGRAY)
        val output = outputFile().apply { writeText("partial") }

        val result = generator.generate(
            SearchablePdfRequest(
                pages = listOf(
                    SearchablePdfPage(
                        openJpegStream = { ByteArrayInputStream(firstImage) },
                        ocrResult = pageResult(0, 240, 320, "First page"),
                    ),
                    SearchablePdfPage(
                        openJpegStream = { ByteArrayInputStream(byteArrayOf(1, 2, 3)) },
                        ocrResult = pageResult(1, 240, 320, "Later page"),
                    ),
                ),
                outputFile = output,
            ),
        )

        assertEquals(
            SearchablePdfGenerationResult.Failure(
                SearchablePdfGenerationError.PAGE_IMAGE_UNREADABLE,
            ),
            result,
        )
        assertFalse(output.exists())
    }

    @Test
    fun preservesTypedOversizedPageFailureAndRemovesPartialOutput() = runBlocking {
        val output = outputFile().apply { writeText("partial") }

        val result = generator.generate(
            SearchablePdfRequest(
                pages = listOf(
                    SearchablePdfPage(
                        openJpegStream = {
                            throw PageExportFailureException(PageExportResult.SourceTooLarge)
                        },
                    ),
                ),
                outputFile = output,
            ),
        )

        assertEquals(
            SearchablePdfGenerationResult.Failure(
                SearchablePdfGenerationError.PAGE_IMAGE_TOO_LARGE,
            ),
            result,
        )
        assertFalse(output.exists())
    }

    @Test
    fun removesExistingTemporaryOutputForAnEmptyRequest() = runBlocking {
        val output = outputFile().apply { writeText("partial") }

        val result = generator.generate(SearchablePdfRequest(pages = emptyList(), outputFile = output))

        assertEquals(
            SearchablePdfGenerationResult.Failure(SearchablePdfGenerationError.EMPTY_REQUEST),
            result,
        )
        assertFalse(output.exists())
    }

    @Test
    fun removesOutputWhenGenerationIsCancelled() = runBlocking {
        val output = outputFile().apply { writeText("partial") }

        val cancellation = runCatching {
            generator.generate(
                SearchablePdfRequest(
                    pages = listOf(
                        SearchablePdfPage(
                            openJpegStream = { throw CancellationException() },
                        ),
                    ),
                    outputFile = output,
                ),
            )
        }.exceptionOrNull()

        assertTrue(cancellation is CancellationException)
        assertFalse(output.exists())
    }

    private fun pageResult(
        pageIndex: Int,
        width: Int,
        height: Int,
        text: String,
    ): OcrPageResult = OcrPageResult(
        pageIndex = pageIndex,
        text = text,
        layout = OcrPageLayout(
            imageWidthPx = width,
            imageHeightPx = height,
            lines = listOf(
                OcrTextLine(
                    text = text,
                    bounds = OcrTextBounds(left = 24f, top = 40f, right = width - 24f, bottom = 72f),
                ),
            ),
        ),
    )

    private fun jpegFixture(width: Int, height: Int, color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            Canvas(bitmap).apply {
                drawColor(color)
                drawRect(12f, 12f, width - 12f, height - 12f, Paint().apply { this.color = Color.DKGRAY })
            }
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun outputFile(): File = File.createTempFile(
        "searchable-pdf-generator-",
        ".pdf",
        context.cacheDir,
    )
}
