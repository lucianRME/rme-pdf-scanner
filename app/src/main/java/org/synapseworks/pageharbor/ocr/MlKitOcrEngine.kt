package org.synapseworks.pageharbor.ocr

import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * On-device OCR implementation backed by ML Kit's bundled Latin recognizer.
 *
 * This synchronous adapter must be called off the main thread, as required by [OcrEngine]. It
 * opens and processes exactly one session page at a time. ML Kit types and failures remain inside
 * this class; callers receive only RME PDF Scanner's in-memory OCR models.
 */
class MlKitOcrEngine : OcrEngine {
    override fun recognize(pages: List<OcrPage>): OcrResult {
        val recognizer = try {
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        } catch (_: Exception) {
            return OcrResult(
                pages = pages.mapIndexed { pageIndex, _ ->
                    OcrPageResult(
                        pageIndex = pageIndex,
                        text = "",
                        error = OcrPageError.RECOGNITION_FAILED,
                    )
                },
            )
        }

        return try {
            OcrResult(
                pages = pages.mapIndexed { pageIndex, page ->
                    recognizePage(recognizer, pageIndex, page)
                },
            )
        } finally {
            recognizer.close()
        }
    }

    private fun recognizePage(
        recognizer: com.google.mlkit.vision.text.TextRecognizer,
        pageIndex: Int,
        page: OcrPage,
    ): OcrPageResult {
        return when (val result = MlKitTextRecognitionPipeline.recognize(recognizer, page)) {
            MlKitPageRecognitionResult.ImageUnreadable -> OcrPageResult(
                pageIndex = pageIndex,
                text = "",
                error = OcrPageError.IMAGE_UNREADABLE,
            )
            MlKitPageRecognitionResult.RecognitionFailed -> OcrPageResult(
                pageIndex = pageIndex,
                text = "",
                error = OcrPageError.RECOGNITION_FAILED,
            )
            is MlKitPageRecognitionResult.Success -> OcrPageResult(
                pageIndex = pageIndex,
                text = result.text,
                layout = result.layout,
            )
        }
    }
}
