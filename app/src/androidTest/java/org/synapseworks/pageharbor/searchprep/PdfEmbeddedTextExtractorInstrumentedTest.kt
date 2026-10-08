package org.synapseworks.pageharbor.searchprep

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.synapseworks.pageharbor.document.searchablepdf.PdfBoxSearchablePdfGenerator
import org.synapseworks.pageharbor.document.searchablepdf.SearchablePdfGenerationResult
import org.synapseworks.pageharbor.document.searchablepdf.SearchablePdfPage
import org.synapseworks.pageharbor.document.searchablepdf.SearchablePdfRequest
import org.synapseworks.pageharbor.ocr.OcrPageLayout
import org.synapseworks.pageharbor.ocr.OcrPageResult
import org.synapseworks.pageharbor.ocr.OcrTextBounds
import org.synapseworks.pageharbor.ocr.OcrTextLine

@RunWith(AndroidJUnit4::class)
class PdfEmbeddedTextExtractorInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val extractor = PdfBoxEmbeddedTextExtractor(context)

    @Test
    fun extractsAndClassifiesEachMixedPdfPageIndependently() = runBlocking {
        val output = temporaryPdf("mixed")
        try {
            val image = jpegFixture()
            val texts = listOf(
                "Invoice number RME-17",
                null,
                "A".repeat(80),
                "Română ș ț · 中文 · 日本語 · 한국어 · देवनागरी",
            )
            val pages = texts.mapIndexed { index, text ->
                SearchablePdfPage(
                    openJpegStream = { ByteArrayInputStream(image) },
                    ocrResult = if (text == null) {
                        OcrPageResult(index, "image only")
                    } else {
                        positioned(index, text)
                    },
                )
            }
            assertEquals(
                SearchablePdfGenerationResult.Success(pageCount = 4, textLayerPageCount = 3),
                PdfBoxSearchablePdfGenerator(context).generate(SearchablePdfRequest(pages, output)),
            )

            val extracted = mutableListOf<ExtractedPdfPage>()
            val extraction = extractor.extract(output, expectedPageCount = 4, { false }) {
                extracted += it
            }
            assertTrue(extraction is PdfEmbeddedTextExtraction.Completed)
            assertEquals(4, (extraction as PdfEmbeddedTextExtraction.Completed).pageCount)
            assertEquals(listOf(0, 1, 2, 3), extracted.map(ExtractedPdfPage::pageIndex))
            assertTrue(TextUsefulnessEvaluator.evaluate(requireNotNull(extracted[0].text)) is TextUsefulness.Useful)
            assertEquals(
                TextUsefulness.NeedsOcr(TextUsefulnessReason.EMPTY),
                TextUsefulnessEvaluator.evaluate(requireNotNull(extracted[1].text)),
            )
            assertEquals(
                TextUsefulness.NeedsOcr(TextUsefulnessReason.PATHOLOGICAL_REPETITION),
                TextUsefulnessEvaluator.evaluate(requireNotNull(extracted[2].text)),
            )
            val unicode = TextUsefulnessEvaluator.evaluate(requireNotNull(extracted[3].text))
            assertTrue(unicode is TextUsefulness.Useful)
            assertTrue((unicode as TextUsefulness.Useful).normalizedText.contains("देवनागरी"))
        } finally {
            output.delete()
        }
    }

    @Test
    fun malformedAndEncryptedPdfsFailWithSafeNonContentReasons() = runBlocking {
        val malformed = temporaryPdf("malformed").apply { writeText("not a pdf") }
        val encrypted = temporaryPdf("encrypted")
        try {
            assertEquals(
                PdfEmbeddedTextExtraction.Failed(TextUsefulnessReason.MALFORMED_PDF),
                extractor.extract(malformed, expectedPageCount = 1, { false }) { },
            )
            PDDocument().use { document ->
                document.addPage(PDPage())
                document.protect(
                    StandardProtectionPolicy("owner-password", "user-password", AccessPermission()),
                )
                document.save(encrypted)
            }
            assertEquals(
                PdfEmbeddedTextExtraction.Failed(TextUsefulnessReason.ENCRYPTED_PDF),
                extractor.extract(encrypted, expectedPageCount = 1, { false }) { },
            )
        } finally {
            malformed.delete()
            encrypted.delete()
        }
    }

    @Test
    fun cancellationStopsBeforePageBuffersAreAccumulated() = runBlocking {
        val output = temporaryPdf("cancel")
        try {
            val image = jpegFixture()
            PdfBoxSearchablePdfGenerator(context).generate(
                SearchablePdfRequest(
                    pages = listOf(
                        SearchablePdfPage(
                            openJpegStream = { ByteArrayInputStream(image) },
                            ocrResult = positioned(0, "Cancellation fixture"),
                        ),
                    ),
                    outputFile = output,
                ),
            )

            val failure = runCatching {
                extractor.extract(output, expectedPageCount = 1, { true }) { }
            }.exceptionOrNull()
            assertTrue(failure is CancellationException)
        } finally {
            output.delete()
        }
    }

    private fun positioned(pageIndex: Int, text: String) = OcrPageResult(
        pageIndex = pageIndex,
        text = text,
        layout = OcrPageLayout(
            imageWidthPx = 120,
            imageHeightPx = 160,
            lines = listOf(
                OcrTextLine(
                    text = text,
                    bounds = OcrTextBounds(10f, 20f, 110f, 45f),
                ),
            ),
        ),
    )

    private fun jpegFixture(): ByteArray {
        val bitmap = Bitmap.createBitmap(120, 160, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(Color.WHITE)
            ByteArrayOutputStream().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun temporaryPdf(label: String): File =
        File(context.cacheDir, "m3-$label-${System.nanoTime()}.pdf")
}
