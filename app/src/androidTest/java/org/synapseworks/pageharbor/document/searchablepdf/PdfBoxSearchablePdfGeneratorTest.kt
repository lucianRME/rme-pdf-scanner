package org.synapseworks.pageharbor.document.searchablepdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDType3Font
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
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

    @Before
    fun initializePdfBoxResources() {
        PDFBoxResourceLoader.init(context)
    }

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
    fun reopensAndExtractsEverySupportedScriptWithoutMojibakeOrMissingGlyphs() = runBlocking {
        val texts = listOf(
            "English searchable invoice",
            "Română factură ș ț ă â î",
            "Deutsch Rechnung ä ö ü ß",
            "Français facture é è ç",
            "Italiano fattura è à ò",
            "Español factura ñ á í",
            "中文可搜索文档",
            "日本語の検索文書",
            "한국어 검색 문서",
            "हिन्दी खोज योग्य दस्तावेज़",
            "Supplementary Unicode: 😀 𐐷",
        )
        val fixture = jpegFixture(width = 320, height = 480, color = Color.WHITE)
        val output = outputFile()

        try {
            val result = generator.generate(
                SearchablePdfRequest(
                    pages = texts.mapIndexed { index, text ->
                        SearchablePdfPage(
                            openJpegStream = { ByteArrayInputStream(fixture) },
                            ocrResult = pageResult(index, 320, 480, text),
                        )
                    },
                    outputFile = output,
                ),
            )

            assertEquals(SearchablePdfGenerationResult.Success(texts.size, texts.size), result)
            PDDocument.load(output).use { document ->
                assertEquals(texts.size, document.numberOfPages)
                val extracted = PDFTextStripper().getText(document)
                texts.forEach { text -> assertTrue("Missing extracted text: $text", extracted.contains(text)) }
                assertFalse(extracted.contains('\uFFFD'))
                document.pages.forEach { page ->
                    val fonts = page.resources.fontNames.map(page.resources::getFont)
                    assertTrue(fonts.isNotEmpty())
                    fonts.forEach { font ->
                        assertTrue(font is PDType3Font)
                        assertTrue(font.cosObject.containsKey(COSName.TO_UNICODE))
                    }
                }
            }
            ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    assertEquals(texts.size, renderer.pageCount)
                    repeat(renderer.pageCount) { pageIndex ->
                        renderer.openPage(pageIndex).use { page ->
                            val rendered = Bitmap.createBitmap(
                                page.width,
                                page.height,
                                Bitmap.Config.ARGB_8888,
                            )
                            try {
                                page.render(
                                    rendered,
                                    null,
                                    null,
                                    PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                                )
                            } finally {
                                rendered.recycle()
                            }
                        }
                    }
                }
            }
        } finally {
            output.delete()
        }
    }

    @Test
    fun unicodeFontMappingIsDeterministicAcrossSubsetBoundaryAndRepeatedGlyphs() {
        val text = buildString {
            repeat(256) { offset -> appendCodePoint(0x400 + offset) }
        }

        fun encodedRuns(): List<ByteArray> = PDDocument().use { document ->
            val fontSet = InvisibleUnicodePdfFontSet(document, sequenceOf(text, text))
            val runs = fontSet.encode(text).map(InvisibleUnicodeTextRun::encodedBytes)
            assertEquals(listOf(255, 1), runs.map(ByteArray::size))
            assertEquals(byteArrayOf(1, 1).toList(), fontSet.encode("\u0400\u0400").single().encodedBytes.toList())
            runs
        }

        val first = encodedRuns()
        val second = encodedRuns()
        assertEquals(first.size, second.size)
        first.zip(second).forEach { (left, right) ->
            assertEquals(left.toList(), right.toList())
        }
    }

    @Test
    fun maximumUniqueCodePointsReopenAndExtractExactly() = runBlocking {
        val fixture = jpegFixture(width = 240, height = 320, color = Color.WHITE)
        val text = buildString {
            repeat(8_192) { offset -> appendCodePoint(0x4E00 + offset) }
        }
        val output = outputFile()

        try {
            assertEquals(
                SearchablePdfGenerationResult.Success(pageCount = 1, textLayerPageCount = 1),
                generator.generate(
                    SearchablePdfRequest(
                        pages = listOf(
                            SearchablePdfPage(
                                openJpegStream = { ByteArrayInputStream(fixture) },
                                ocrResult = pageResult(0, 240, 320, text),
                            ),
                        ),
                        outputFile = output,
                    ),
                ),
            )
            PDDocument.load(output).use { document ->
                assertEquals(text, PDFTextStripper().getText(document).trim())
                assertFalse(PDFTextStripper().getText(document).contains('\uFFFD'))
            }
        } finally {
            output.delete()
        }
    }

    @Test
    fun transformedPageSizesKeepTextOnTheCorrectOrderedPage() = runBlocking {
        val texts = listOf("Rotation 0", "Rotation 90", "Rotation 180", "Rotation 270")
        val dimensions = listOf(320 to 480, 480 to 320, 320 to 480, 480 to 320)
        val output = outputFile()

        try {
            val result = generator.generate(
                SearchablePdfRequest(
                    pages = texts.mapIndexed { index, text ->
                        val (width, height) = dimensions[index]
                        val fixture = jpegFixture(width, height, Color.rgb(235, 240, 245))
                        SearchablePdfPage(
                            openJpegStream = { ByteArrayInputStream(fixture) },
                            ocrResult = pageResult(index, width, height, text),
                        )
                    },
                    outputFile = output,
                ),
            )

            assertEquals(SearchablePdfGenerationResult.Success(4, 4), result)
            PDDocument.load(output).use { document ->
                val stripper = PDFTextStripper()
                texts.forEachIndexed { index, text ->
                    stripper.startPage = index + 1
                    stripper.endPage = index + 1
                    assertEquals(text, stripper.getText(document).trim())
                    assertEquals(dimensions[index].first.toFloat(), document.getPage(index).mediaBox.width)
                    assertEquals(dimensions[index].second.toFloat(), document.getPage(index).mediaBox.height)
                }
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
    fun pageWiseGenerationRemainsBoundedForOneTwentyAndOneHundredPages() = runBlocking {
        val fixture = jpegFixture(width = 160, height = 220, color = Color.WHITE)

        listOf(1, 20, 100).forEach { pageCount ->
            val output = outputFile()
            var openedStreams = 0
            var approximatePeakManagedBytes = usedManagedHeap()
            val startedAt = SystemClock.elapsedRealtime()
            try {
                val result = generator.generate(
                    SearchablePdfRequest(
                        pages = (0 until pageCount).map { index ->
                            SearchablePdfPage(
                                openJpegStream = {
                                    openedStreams++
                                    approximatePeakManagedBytes = maxOf(
                                        approximatePeakManagedBytes,
                                        usedManagedHeap(),
                                    )
                                    ByteArrayInputStream(fixture)
                                },
                                ocrResult = pageResult(
                                    pageIndex = index,
                                    width = 160,
                                    height = 220,
                                    text = "Bounded page ${index + 1}",
                                ),
                            )
                        },
                        outputFile = output,
                    ),
                )
                val durationMillis = SystemClock.elapsedRealtime() - startedAt
                assertEquals(SearchablePdfGenerationResult.Success(pageCount, pageCount), result)
                assertEquals(pageCount, openedStreams)
                PDDocument.load(output).use { document -> assertEquals(pageCount, document.numberOfPages) }
                Log.i(
                    PERFORMANCE_LOG_TAG,
                    "pages=$pageCount durationMs=$durationMillis " +
                        "approxManagedBytes=$approximatePeakManagedBytes outputBytes=${output.length()}",
                )
            } finally {
                output.delete()
            }
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
    fun excessiveUniqueUnicodeFailsSafelyWithoutLeavingPartialOutput() = runBlocking {
        val fixture = jpegFixture(width = 240, height = 320, color = Color.WHITE)
        val text = buildString {
            repeat(8_193) { offset -> appendCodePoint(0x10_000 + offset) }
        }
        val output = outputFile().apply { writeText("partial") }

        val result = generator.generate(
            SearchablePdfRequest(
                pages = listOf(
                    SearchablePdfPage(
                        openJpegStream = { ByteArrayInputStream(fixture) },
                        ocrResult = pageResult(0, 240, 320, text),
                    ),
                ),
                outputFile = output,
            ),
        )

        assertEquals(
            SearchablePdfGenerationResult.Failure(SearchablePdfGenerationError.GENERATION_FAILED),
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

    private fun usedManagedHeap(): Long = Runtime.getRuntime().let { runtime ->
        runtime.totalMemory() - runtime.freeMemory()
    }

    private companion object {
        const val PERFORMANCE_LOG_TAG = "RME_PHASE7_PDF_PERF"
    }
}
