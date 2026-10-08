package org.synapseworks.pageharbor.searchprep

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.IOException
import java.io.Writer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class ExtractedPdfPage(
    val pageIndex: Int,
    val text: String?,
    val failure: TextUsefulnessReason? = null,
) {
    init {
        require(pageIndex >= 0)
        require((text == null) != (failure == null))
    }
}

sealed interface PdfEmbeddedTextExtraction {
    data class Completed(val pageCount: Int) : PdfEmbeddedTextExtraction
    data class Failed(val reason: TextUsefulnessReason) : PdfEmbeddedTextExtraction
}

fun interface PdfEmbeddedTextExtractor {
    suspend fun extract(
        sourcePdf: File,
        expectedPageCount: Int,
        isCancellationRequested: () -> Boolean,
        onPage: suspend (ExtractedPdfPage) -> Unit,
    ): PdfEmbeddedTextExtraction
}

/** Local PDFBox extraction with one bounded page text buffer at a time. */
class PdfBoxEmbeddedTextExtractor(context: Context) : PdfEmbeddedTextExtractor {
    init {
        PDFBoxResourceLoader.init(context.applicationContext)
    }

    override suspend fun extract(
        sourcePdf: File,
        expectedPageCount: Int,
        isCancellationRequested: () -> Boolean,
        onPage: suspend (ExtractedPdfPage) -> Unit,
    ): PdfEmbeddedTextExtraction = withContext(Dispatchers.IO) {
        if (!sourcePdf.isFile) return@withContext PdfEmbeddedTextExtraction.Failed(
            TextUsefulnessReason.EXTRACTION_FAILED,
        )
        val document = try {
            PDDocument.load(sourcePdf)
        } catch (_: InvalidPasswordException) {
            return@withContext PdfEmbeddedTextExtraction.Failed(TextUsefulnessReason.ENCRYPTED_PDF)
        } catch (_: IOException) {
            return@withContext PdfEmbeddedTextExtraction.Failed(TextUsefulnessReason.MALFORMED_PDF)
        } catch (_: RuntimeException) {
            return@withContext PdfEmbeddedTextExtraction.Failed(TextUsefulnessReason.MALFORMED_PDF)
        }
        document.use { pdf ->
            if (pdf.isEncrypted) {
                return@withContext PdfEmbeddedTextExtraction.Failed(TextUsefulnessReason.ENCRYPTED_PDF)
            }
            if (pdf.numberOfPages != expectedPageCount) {
                return@withContext PdfEmbeddedTextExtraction.Failed(
                    TextUsefulnessReason.EXTRACTION_FAILED,
                )
            }
            for (pageIndex in 0 until pdf.numberOfPages) {
                currentCoroutineContext().ensureActive()
                if (isCancellationRequested()) throw CancellationException("Search preparation cancelled")
                val writer = BoundedPageTextWriter(
                    TextUsefulnessEvaluator.MAX_PAGE_CHARACTERS,
                    isCancellationRequested,
                )
                try {
                    PDFTextStripper().apply {
                        sortByPosition = true
                        startPage = pageIndex + 1
                        endPage = pageIndex + 1
                    }.writeText(pdf, writer)
                    onPage(ExtractedPdfPage(pageIndex, writer.value()))
                } catch (_: PageTextLimitException) {
                    onPage(
                        ExtractedPdfPage(
                            pageIndex,
                            null,
                            TextUsefulnessReason.PAGE_TEXT_LIMIT_EXCEEDED,
                        ),
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: IOException) {
                    onPage(
                        ExtractedPdfPage(
                            pageIndex,
                            null,
                            TextUsefulnessReason.EXTRACTION_FAILED,
                        ),
                    )
                } catch (_: RuntimeException) {
                    onPage(
                        ExtractedPdfPage(
                            pageIndex,
                            null,
                            TextUsefulnessReason.EXTRACTION_FAILED,
                        ),
                    )
                }
            }
            PdfEmbeddedTextExtraction.Completed(pdf.numberOfPages)
        }
    }
}

private class BoundedPageTextWriter(
    private val maximumCharacters: Int,
    private val isCancellationRequested: () -> Boolean,
) : Writer() {
    private val text = StringBuilder(minOf(maximumCharacters, 8 * 1024))

    override fun write(buffer: CharArray, offset: Int, length: Int) {
        if (isCancellationRequested()) throw CancellationException("Search preparation cancelled")
        if (length < 0 || text.length + length > maximumCharacters) throw PageTextLimitException()
        text.append(buffer, offset, length)
    }

    override fun flush() = Unit
    override fun close() = Unit
    fun value(): String = text.toString()
}

private class PageTextLimitException : IOException()
