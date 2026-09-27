package org.synapseworks.pageharbor.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognizer
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
    ): MlKitPageRecognitionResult {
        val bitmap = decodeBoundedBitmap(page) ?: return MlKitPageRecognitionResult.ImageUnreadable
        return try {
            val result = Tasks.await(
                recognizer.process(InputImage.fromBitmap(bitmap, page.rotationDegrees)),
            )
            val swapsDimensions = page.rotationDegrees == 90 || page.rotationDegrees == 270
            val layoutBlocks = result.textBlocks.map { block ->
                OcrTextBlock(
                    lines = block.lines.map { line ->
                        OcrTextLine(
                            text = line.text,
                            bounds = line.boundingBox.toOcrTextBounds(),
                            confidence = null,
                            elements = line.elements.map { element ->
                                OcrTextElement(
                                    text = element.text,
                                    bounds = element.boundingBox.toOcrTextBounds(),
                                )
                            },
                        )
                    },
                    bounds = block.boundingBox.toOcrTextBounds(),
                )
            }
            val resolved = OcrReadingOrderResolver.resolve(
                OcrPageLayout(
                    imageWidthPx = if (swapsDimensions) bitmap.height else bitmap.width,
                    imageHeightPx = if (swapsDimensions) bitmap.width else bitmap.height,
                    rotationDegrees = 0,
                    lines = layoutBlocks.flatMap { it.lines },
                    blocks = layoutBlocks,
                ),
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
        if (DEFAULT_DOCUMENT_INPUT_LIMITS.imageConstraintViolation(page.imageMetadata) != null) {
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
            DEFAULT_DOCUMENT_INPUT_LIMITS.imageConstraintViolation(
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
