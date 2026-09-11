package org.synapseworks.pageharbor.document.searchablepdf

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import java.io.File
import java.io.InputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.synapseworks.pageharbor.document.PageExportFailureException
import org.synapseworks.pageharbor.document.PageExportResult
import org.synapseworks.pageharbor.ocr.OcrPageResult

/** Creates a local PDF with scanned JPEG pages and an optional invisible OCR text layer. */
interface SearchablePdfGenerator {
    /**
     * Generates only at [SearchablePdfRequest.outputFile]. The caller owns that temporary file
     * after success; this generator deletes it after failure or cancellation.
     */
    suspend fun generate(request: SearchablePdfRequest): SearchablePdfGenerationResult
}

/**
 * Engine-neutral input for one page in its required output order.
 *
 * The caller owns the source and supplies a fresh JPEG stream for this one generation. No URI,
 * file path, scanner type, or PDF-library type crosses the boundary.
 */
data class SearchablePdfPage(
    val openJpegStream: () -> InputStream,
    val ocrResult: OcrPageResult? = null,
)

/** A caller-provided, private temporary destination and its ordered local page sources. */
data class SearchablePdfRequest(
    val pages: List<SearchablePdfPage>,
    val outputFile: File,
)

sealed interface SearchablePdfGenerationResult {
    data class Success(
        val pageCount: Int,
        val textLayerPageCount: Int,
    ) : SearchablePdfGenerationResult

    data class Failure(val reason: SearchablePdfGenerationError) : SearchablePdfGenerationResult
}

/** Safe failure categories that deliberately exclude document content, paths, and exception text. */
enum class SearchablePdfGenerationError {
    EMPTY_REQUEST,
    OUTPUT_UNAVAILABLE,
    PAGE_IMAGE_UNREADABLE,
    PAGE_IMAGE_TOO_LARGE,
    GENERATION_FAILED,
}

/**
 * PdfBox-Android implementation. It embeds JPEG streams directly rather than decoding/reencoding
 * page backgrounds, and embeds a Unicode-capable OFL font supplied by PdfBox-Android's assets.
 */
class PdfBoxSearchablePdfGenerator(context: Context) : SearchablePdfGenerator {
    private val applicationContext: Context = context.applicationContext ?: context

    override suspend fun generate(request: SearchablePdfRequest): SearchablePdfGenerationResult =
        withContext(Dispatchers.IO) {
            if (request.pages.isEmpty()) {
                cleanupOutput(request.outputFile)
                return@withContext SearchablePdfGenerationResult.Failure(
                    SearchablePdfGenerationError.EMPTY_REQUEST,
                )
            }
            if (request.outputFile.isDirectory || request.outputFile.parentFile?.isDirectory == false) {
                cleanupOutput(request.outputFile)
                return@withContext SearchablePdfGenerationResult.Failure(
                    SearchablePdfGenerationError.OUTPUT_UNAVAILABLE,
                )
            }

            var pageImageFailure: SearchablePdfGenerationError? = null
            try {
                coroutineContext.ensureActive()
                PDFBoxResourceLoader.init(applicationContext)
                var textLayerPageCount = 0
                PDDocument().use { document ->
                    val font = loadEmbeddedLatinFont(document)
                    request.pages.forEach { page ->
                        try {
                            coroutineContext.ensureActive()
                            val image = page.openJpegStream().use { input ->
                                JPEGFactory.createFromStream(document, input)
                            }
                            val pageSize = PdfPageSize(
                                widthPoints = image.width.toFloat(),
                                heightPoints = image.height.toFloat(),
                            )
                            val pdfPage = PDPage(PDRectangle(pageSize.widthPoints, pageSize.heightPoints))
                            document.addPage(pdfPage)
                            PDPageContentStream(document, pdfPage).use { content ->
                                content.drawImage(image, 0f, 0f, pageSize.widthPoints, pageSize.heightPoints)
                                if (writeTextLayer(content, font, page.ocrResult, pageSize)) {
                                    textLayerPageCount++
                                }
                            }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: PageExportFailureException) {
                            pageImageFailure = when (error.result) {
                                PageExportResult.SourceTooLarge ->
                                    SearchablePdfGenerationError.PAGE_IMAGE_TOO_LARGE

                                else -> SearchablePdfGenerationError.PAGE_IMAGE_UNREADABLE
                            }
                            throw PageImageException
                        } catch (_: Exception) {
                            pageImageFailure = SearchablePdfGenerationError.PAGE_IMAGE_UNREADABLE
                            throw PageImageException
                        }
                    }
                    coroutineContext.ensureActive()
                    request.outputFile.outputStream().use(document::save)
                }
                SearchablePdfGenerationResult.Success(
                    pageCount = request.pages.size,
                    textLayerPageCount = textLayerPageCount,
                )
            } catch (error: CancellationException) {
                cleanupOutput(request.outputFile)
                throw error
            } catch (_: SecurityException) {
                cleanupOutput(request.outputFile)
                SearchablePdfGenerationResult.Failure(
                    pageImageFailure ?: SearchablePdfGenerationError.OUTPUT_UNAVAILABLE,
                )
            } catch (_: Exception) {
                cleanupOutput(request.outputFile)
                SearchablePdfGenerationResult.Failure(
                    pageImageFailure ?: SearchablePdfGenerationError.GENERATION_FAILED,
                )
            }
        }

    private fun loadEmbeddedLatinFont(document: PDDocument): PDType0Font =
        applicationContext.assets.open(LiberationSansAssetPath).use { input ->
            PDType0Font.load(document, input, true)
        }

    /** Only delete files; a malformed request must never turn a directory into a cleanup target. */
    private fun cleanupOutput(outputFile: File) {
        if (outputFile.isFile) outputFile.delete()
    }

    private fun writeTextLayer(
        content: PDPageContentStream,
        font: PDType0Font,
        ocrResult: OcrPageResult?,
        pageSize: PdfPageSize,
    ): Boolean {
        val layout = ocrResult?.layout ?: return false
        if (ocrResult.error != null || layout.rotationDegrees != 0) return false

        var wroteText = false
        layout.lines.forEach { line ->
            if (line.text.isBlank()) return@forEach
            val mappedBounds = mapOcrBoundsToPdf(
                bounds = line.bounds ?: return@forEach,
                sourceWidthPx = layout.imageWidthPx,
                sourceHeightPx = layout.imageHeightPx,
                pageSize = pageSize,
            ) ?: return@forEach
            val fontSize = mappedBounds.top - mappedBounds.bottom
            if (fontSize <= 0f) return@forEach
            val unscaledWidth = font.getStringWidth(line.text) / 1000f * fontSize
            if (unscaledWidth <= 0f) return@forEach

            content.beginText()
            content.setRenderingMode(RenderingMode.NEITHER)
            content.setFont(font, fontSize)
            content.setHorizontalScaling((mappedBounds.right - mappedBounds.left) / unscaledWidth * 100f)
            content.newLineAtOffset(mappedBounds.left, mappedBounds.bottom)
            content.showText(line.text)
            content.endText()
            wroteText = true
        }
        return wroteText
    }

    private data object PageImageException : Exception()

    private companion object {
        const val LiberationSansAssetPath =
            "com/tom_roush/pdfbox/resources/ttf/LiberationSans-Regular.ttf"
    }
}
