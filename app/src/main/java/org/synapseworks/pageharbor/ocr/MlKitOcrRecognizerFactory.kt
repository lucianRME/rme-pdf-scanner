package org.synapseworks.pageharbor.ocr

import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

internal object MlKitTextRecognizerClients {
    fun create(script: OcrScript): TextRecognizer = when (script) {
        OcrScript.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        OcrScript.CHINESE -> TextRecognition.getClient(
            ChineseTextRecognizerOptions.Builder().build(),
        )
        OcrScript.JAPANESE -> TextRecognition.getClient(
            JapaneseTextRecognizerOptions.Builder().build(),
        )
        OcrScript.KOREAN -> TextRecognition.getClient(
            KoreanTextRecognizerOptions.Builder().build(),
        )
        OcrScript.DEVANAGARI -> TextRecognition.getClient(
            DevanagariTextRecognizerOptions.Builder().build(),
        )
    }
}

/** Creates the exact recognizer requested by the application-level script. */
class MlKitOcrRecognizerFactory : OcrRecognizerFactory {
    override fun acquire(script: OcrScript): OcrRecognizerAcquisition = try {
        OcrRecognizerAcquisition.Available(
            MlKitOcrScriptRecognizer(
                script = script,
                recognizer = MlKitTextRecognizerClients.create(script),
            ),
        )
    } catch (_: Exception) {
        OcrRecognizerAcquisition.Unavailable(OcrRecognizerUnavailableReason.INITIALIZATION_FAILED)
    }
}

private class MlKitOcrScriptRecognizer(
    override val script: OcrScript,
    private val recognizer: TextRecognizer,
) : OcrScriptRecognizer {
    override suspend fun recognize(
        request: OcrPageRecognitionRequest,
    ): OcrPageRecognitionOutcome = when (
        val result = MlKitTextRecognitionPipeline.recognize(recognizer, request.source)
    ) {
        MlKitPageRecognitionResult.ImageUnreadable -> OcrPageRecognitionOutcome.Failure(
            descriptor = request.descriptor,
            reason = OcrFailureReason.IMAGE_UNREADABLE,
        )
        MlKitPageRecognitionResult.RecognitionFailed -> OcrPageRecognitionOutcome.Failure(
            descriptor = request.descriptor,
            reason = OcrFailureReason.RECOGNITION_FAILED,
        )
        is MlKitPageRecognitionResult.Success -> OcrPageRecognitionOutcome.Success(
            descriptor = request.descriptor,
            rawText = result.text,
            layout = result.layout,
            provenance = OcrRecognizerProvenance(
                actualScript = script,
                recognizerId = "mlkit-text-recognition-v2-${script.stableId.lowercase()}",
                pipelineVersion = "mlkit-text-recognition-v2",
                clientVersion = if (script == OcrScript.LATIN) {
                    "bundled-16.0.1"
                } else {
                    "play-services-16.0.1"
                },
                delivery = if (script == OcrScript.LATIN) {
                    OcrModelDelivery.BUNDLED
                } else {
                    OcrModelDelivery.PLAY_SERVICES
                },
            ),
        )
    }

    override fun close() {
        recognizer.close()
    }
}
