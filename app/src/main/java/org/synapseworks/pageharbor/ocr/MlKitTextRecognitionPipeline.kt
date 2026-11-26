package org.synapseworks.pageharbor.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognizer
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import org.synapseworks.pageharbor.document.session.DEFAULT_DOCUMENT_INPUT_LIMITS
import org.synapseworks.pageharbor.document.session.imageConstraintViolation

internal sealed interface MlKitPageRecognitionResult {
    data class Success(
        val text: String,
        val layout: OcrPageLayout,
    ) : MlKitPageRecognitionResult

    data object ImageUnreadable : MlKitPageRecognitionResult
    data object RecognitionFailed : MlKitPageRecognitionResult
}

/** Shared bitmap/decode/layout pipeline for bundled Latin and optional script recognizers. */
internal object MlKitTextRecognitionPipeline {
    fun recognize(
        recognizer: TextRecognizer,
        page: OcrPage,
        recoverTableCells: Boolean,
    ): MlKitPageRecognitionResult {
        val bitmap = decodeBoundedBitmap(page) ?: return MlKitPageRecognitionResult.ImageUnreadable
        return try {
            val result = Tasks.await(
                recognizer.process(InputImage.fromBitmap(bitmap, page.rotationDegrees)),
            )
            val swapsDimensions = page.rotationDegrees == 90 || page.rotationDegrees == 270
            val layoutBlocks = result.textBlocks.map { block ->
                OcrTextBlock(
                    lines = block.lines.map { it.toOcrLine() },
                    bounds = block.boundingBox.toOcrTextBounds(),
                )
            }
            // A short isolated digit often has an unreliable angle. Use text lines with
            // enough horizontal extent when computing the page slope for row grouping.
            val lineAngles = result.textBlocks.asSequence().flatMap { it.lines.asSequence() }
                .filter { line -> line.boundingBox?.let { it.width() >= 2 * it.height() && it.height() > 0 } == true }
                .map { it.angle }
                .filter(Float::isFinite)
                .sorted()
                .toList()
            val tableRecovery = if (recoverTableCells && page.rotationDegrees == 0) {
                OcrTableCellRecovery(bitmap.width, bitmap.height) { bounds, height, target ->
                    readCrop(recognizer, bitmap, bounds, height, target)
                }
            } else null
            val resolved = OcrReadingOrderResolver.resolve(
                OcrPageLayout(
                    imageWidthPx = if (swapsDimensions) bitmap.height else bitmap.width,
                    imageHeightPx = if (swapsDimensions) bitmap.width else bitmap.height,
                    rotationDegrees = 0,
                    lines = layoutBlocks.flatMap { it.lines },
                    blocks = layoutBlocks,
                ),
                angleDegrees = lineAngles.getOrElse(lineAngles.size / 2) { 0f },
                tableRecovery = tableRecovery,
            )
            MlKitPageRecognitionResult.Success(
                text = resolved.canonicalText,
                layout = resolved.layout,
            )
        } catch (_: Exception) {
            MlKitPageRecognitionResult.RecognitionFailed
        } finally {
            bitmap.recycle()
        }
    }

    private fun readCrop(
        recognizer: TextRecognizer,
        bitmap: Bitmap,
        bounds: OcrTextBounds,
        textHeight: Float,
        targetHeight: Int,
    ): OcrTextLine? {
        val source = Rect(bounds.left.roundToInt(), bounds.top.roundToInt(), bounds.right.roundToInt(), bounds.bottom.roundToInt())
        val width = (source.width() * targetHeight / textHeight).toInt()
        val height = (source.height() * targetHeight / textHeight).toInt()
        if (width !in 1..1024 || height !in 1..1024 || width * height > 262_144) return null
        return try {
            val crop = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                Canvas(crop).drawBitmap(bitmap, source, Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
                val result = Tasks.await(recognizer.process(InputImage.fromBitmap(crop, 0)))
                val line = result.textBlocks.singleOrNull()?.lines?.singleOrNull() ?: return null
                val scaleX = source.width().toFloat() / width
                val scaleY = source.height().toFloat() / height
                line.toOcrLine { box ->
                    OcrTextBounds(
                        source.left + box.left * scaleX,
                        source.top + box.top * scaleY,
                        source.left + box.right * scaleX,
                        source.top + box.bottom * scaleY,
                    )
                }
            } finally {
                crop.recycle()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw error
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    private fun Text.Line.toOcrLine(mapBounds: (OcrTextBounds) -> OcrTextBounds = { it }) = OcrTextLine(
        text = text,
        bounds = boundingBox.toOcrTextBounds()?.let(mapBounds),
        elements = elements.map { element ->
            OcrTextElement(element.text, element.boundingBox.toOcrTextBounds()?.let(mapBounds))
        },
    )

    private fun android.graphics.Rect?.toOcrTextBounds(): OcrTextBounds? = this?.let { bounds ->
        OcrTextBounds(
            left = bounds.left.toFloat(),
            top = bounds.top.toFloat(),
            right = bounds.right.toFloat(),
            bottom = bounds.bottom.toFloat(),
        )
    }

    /** Two-pass bounded decode shared by every script; source streams are always closed. */
    private fun decodeBoundedBitmap(page: OcrPage): Bitmap? {
        if (org.synapseworks.pageharbor.document.session.DEFAULT_DOCUMENT_SOURCE_LIMITS.imageConstraintViolation(page.imageMetadata) != null) {
            return null
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            page.openJpegStream().use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            }
        } catch (_: Exception) {
            return null
        }

        if (
            org.synapseworks.pageharbor.document.session.DEFAULT_DOCUMENT_SOURCE_LIMITS.imageConstraintViolation(
                page.imageMetadata.copy(width = bounds.outWidth, height = bounds.outHeight),
            ) != null
        ) {
            return null
        }

        val sampleSize = OcrBitmapDecodePolicy.calculateInSampleSize(
            width = bounds.outWidth,
            height = bounds.outHeight,
        ) ?: return null
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return try {
            page.openJpegStream().use { stream ->
                try {
                    BitmapFactory.decodeStream(stream, null, decodeOptions)
                } catch (_: OutOfMemoryError) {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}
