package org.synapseworks.pageharbor.document.cardcopy

import android.content.Context
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.core.graphics.createBitmap
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.roundToInt
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.synapseworks.pageharbor.document.PageExportResult
import org.synapseworks.pageharbor.document.SizedJpegEncoding
import org.synapseworks.pageharbor.document.writeSizedJpegToDestination
import org.synapseworks.pageharbor.image.DocumentFilter

internal sealed interface CardCopyPrepareResult {
    data class Success(val prepared: CardCopyPreparedPdf) : CardCopyPrepareResult
    data class Failure(val reason: CardCopyFailure) : CardCopyPrepareResult
}

/**
 * Produces a one-page flattened PDF. Geometry is expressed in physical PDF points while each side
 * is decoded sequentially to at most 180 pixels/inch at its final placement size. This preserves
 * fine card text without retaining two full-resolution source bitmaps or claiming printer DPI.
 */
internal class CardCopyPdfComposer(private val context: Context) {
    suspend fun prepare(
        front: CardCopySource,
        back: CardCopySource,
        settings: CardCopySettings,
    ): CardCopyPrepareResult = withContext(Dispatchers.IO) {
        val geometry = CardCopyGeometryEngine.calculate(
            requireNotNull(front.metadata.width), requireNotNull(front.metadata.height), front.rotation,
            requireNotNull(back.metadata.width), requireNotNull(back.metadata.height), back.rotation,
            settings,
        )
        val root = File(context.cacheDir, CARD_COPY_OUTPUT_DIRECTORY).apply { mkdirs() }
        val working = try {
            File.createTempFile("card-copy-", ".work", root).also { marker ->
                if (!marker.delete() || !marker.mkdir()) {
                    return@withContext CardCopyPrepareResult.Failure(CardCopyFailure.PREPARATION_FAILED)
                }
            }
        } catch (_: IOException) {
            return@withContext CardCopyPrepareResult.Failure(CardCopyFailure.PREPARATION_FAILED)
        }
        val output = File(working, "card-copy.pdf")
        val started = SystemClock.elapsedRealtime()
        var peakTemporaryBytes = 0L
        var estimatedPeakBitmapBytes = 0L
        try {
            PDFBoxResourceLoader.init(context)
            val memoryUsage = MemoryUsageSetting.setupMixed(8L * 1_024L * 1_024L)
                .setTempDir(working)
            PDDocument(memoryUsage).use { document ->
                val page = PDPage(
                    PDRectangle(settings.pageSize.widthPoints, settings.pageSize.heightPoints),
                )
                document.addPage(page)
                PDPageContentStream(document, page).use { content ->
                    content.setNonStrokingColor(255, 255, 255)
                    content.addRect(0f, 0f, settings.pageSize.widthPoints, settings.pageSize.heightPoints)
                    content.fill()
                    listOf(front to geometry.front, back to geometry.back).forEachIndexed { index, pair ->
                        coroutineContext.ensureActive()
                        val encoded = File(working, "side-$index.jpg")
                        val bounds = pair.second.bounds
                        val widthPx = pointsToPixels(bounds.width)
                        val heightPx = pointsToPixels(bounds.height)
                        estimatedPeakBitmapBytes = maxOf(
                            estimatedPeakBitmapBytes,
                            widthPx.toLong() * heightPx.toLong() * 4L * 2L,
                        )
                        val result = writeSizedJpegToDestination(
                            openSource = { pair.first.file.inputStream() },
                            destination = FileOutputStream(encoded),
                            filter = DocumentFilter.ORIGINAL,
                            rotation = pair.first.rotation,
                            imageMetadata = pair.first.metadata,
                            encoding = SizedJpegEncoding(widthPx, heightPx, COMPOSITION_JPEG_QUALITY),
                        )
                        if (result != PageExportResult.Success) {
                            throw CardCopyCompositionException(
                                if (result == PageExportResult.SourceTooLarge) {
                                    CardCopyFailure.SOURCE_TOO_LARGE
                                } else {
                                    CardCopyFailure.PREPARATION_FAILED
                                },
                            )
                        }
                        peakTemporaryBytes = maxOf(
                            peakTemporaryBytes,
                            working.walkTopDown().filter(File::isFile).sumOf(File::length),
                        )
                        val image = encoded.inputStream().use { JPEGFactory.createFromStream(document, it) }
                        content.drawImage(
                            image,
                            bounds.left,
                            settings.pageSize.heightPoints - bounds.bottom,
                            bounds.width,
                            bounds.height,
                        )
                        encoded.delete()
                    }
                }
                document.save(output)
            }
            if (!validate(output, settings.pageSize)) {
                working.deleteRecursively()
                return@withContext CardCopyPrepareResult.Failure(CardCopyFailure.VALIDATION_FAILED)
            }
            CardCopyPrepareResult.Success(
                CardCopyPreparedPdf(
                    file = output,
                    workingDirectory = working,
                    bytes = output.length(),
                    geometry = geometry,
                    preparationMillis = SystemClock.elapsedRealtime() - started,
                    peakTemporaryBytes = peakTemporaryBytes.coerceAtLeast(output.length()),
                    estimatedPeakBitmapBytes = estimatedPeakBitmapBytes,
                ),
            )
        } catch (error: CancellationException) {
            working.deleteRecursively()
            throw error
        } catch (error: CardCopyCompositionException) {
            working.deleteRecursively()
            CardCopyPrepareResult.Failure(error.reason)
        } catch (_: IOException) {
            working.deleteRecursively()
            CardCopyPrepareResult.Failure(CardCopyFailure.PREPARATION_FAILED)
        } catch (_: RuntimeException) {
            working.deleteRecursively()
            CardCopyPrepareResult.Failure(CardCopyFailure.PREPARATION_FAILED)
        } catch (_: OutOfMemoryError) {
            working.deleteRecursively()
            CardCopyPrepareResult.Failure(CardCopyFailure.PREPARATION_FAILED)
        }
    }

    fun discard(prepared: CardCopyPreparedPdf) {
        prepared.workingDirectory.deleteRecursively()
    }

    private fun validate(file: File, pageSize: CardCopyPageSize): Boolean {
        return try {
            if (!file.isFile || file.length() <= 0L) return false
            PDDocument.load(file).use { document ->
                if (document.numberOfPages != 1) return false
                val box = document.getPage(0).mediaBox
                if (kotlin.math.abs(box.width - pageSize.widthPoints) > 0.1f ||
                    kotlin.math.abs(box.height - pageSize.heightPoints) > 0.1f
                ) return false
            }
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    if (renderer.pageCount != 1) return false
                    renderer.openPage(0).use { page ->
                        val bitmap = createBitmap(96, 128)
                        try {
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
            }
            true
        } catch (_: Exception) {
            false
        } catch (_: OutOfMemoryError) {
            false
        }
    }

    private fun pointsToPixels(points: Float): Int =
        (points * COMPOSITION_PIXELS_PER_INCH / 72f).roundToInt().coerceAtLeast(1)

    private class CardCopyCompositionException(val reason: CardCopyFailure) : RuntimeException()

    private companion object {
        const val COMPOSITION_PIXELS_PER_INCH = 180
        const val COMPOSITION_JPEG_QUALITY = 94
    }
}
