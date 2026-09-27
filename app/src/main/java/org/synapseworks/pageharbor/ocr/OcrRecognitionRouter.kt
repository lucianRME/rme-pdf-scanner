package org.synapseworks.pageharbor.ocr

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Availability-gated script router. It never substitutes another recognizer when the requested
 * model is missing or fails to initialize.
 */
class OcrRecognitionRouter(
    private val modelStatusProvider: OcrModelStatusProvider,
    private val recognizerFactory: OcrRecognizerFactory,
) : OcrPageRecognitionEngine {
    override suspend fun recognize(request: OcrPageRecognitionRequest): OcrPageRecognitionOutcome {
        currentCoroutineContext().ensureActive()
        val script = request.descriptor.script
        val state = modelStatusProvider.stateFor(script)
        val unavailableReason = state.unavailableReason(script)
        if (unavailableReason != null) return request.failure(unavailableReason)

        val acquisition = try {
            recognizerFactory.acquire(script)
        } catch (_: Exception) {
            return request.failure(OcrFailureReason.RECOGNITION_FAILED)
        }
        return when (acquisition) {
            is OcrRecognizerAcquisition.Unavailable -> request.failure(
                when (acquisition.reason) {
                    OcrRecognizerUnavailableReason.MODEL_UNAVAILABLE ->
                        OcrFailureReason.MODEL_UNAVAILABLE
                    OcrRecognizerUnavailableReason.GOOGLE_PLAY_SERVICES_UNAVAILABLE ->
                        OcrFailureReason.GOOGLE_PLAY_SERVICES_UNAVAILABLE
                    OcrRecognizerUnavailableReason.INITIALIZATION_FAILED ->
                        OcrFailureReason.RECOGNITION_FAILED
                },
            )

            is OcrRecognizerAcquisition.Available -> {
                val recognizer = acquisition.recognizer
                if (recognizer.script != script) {
                    recognizer.close()
                    return request.failure(OcrFailureReason.RECOGNITION_FAILED)
                }
                try {
                    val outcome = recognizer.recognize(request)
                    currentCoroutineContext().ensureActive()
                    outcome.takeIf { candidate ->
                        candidate.descriptor == request.descriptor &&
                            (candidate !is OcrPageRecognitionOutcome.Success ||
                                candidate.provenance.actualScript == script)
                    } ?: request.failure(OcrFailureReason.RECOGNITION_FAILED)
                } catch (_: CancellationException) {
                    request.failure(OcrFailureReason.CANCELLED)
                } catch (_: Exception) {
                    request.failure(OcrFailureReason.RECOGNITION_FAILED)
                } finally {
                    recognizer.close()
                }
            }
        }
    }

    private fun OcrModelState.unavailableReason(script: OcrScript): OcrFailureReason? = when (this) {
        OcrModelState.Bundled -> if (script == OcrScript.LATIN) null else {
            OcrFailureReason.MODEL_UNAVAILABLE
        }
        OcrModelState.Installed -> null
        OcrModelState.Unsupported -> OcrFailureReason.SCRIPT_UNSUPPORTED
        is OcrModelState.Failed -> reason.toRecognitionFailure()
        is OcrModelState.RetryableFailure -> reason.toRecognitionFailure()
        OcrModelState.Checking,
        OcrModelState.NotInstalled,
        OcrModelState.Pending,
        is OcrModelState.Downloading,
        OcrModelState.Paused,
        OcrModelState.Installing,
        OcrModelState.Canceled,
        OcrModelState.StatusUnknown,
        -> OcrFailureReason.MODEL_UNAVAILABLE
    }

    private fun OcrModelFailure.toRecognitionFailure(): OcrFailureReason = when (this) {
        OcrModelFailure.GOOGLE_PLAY_SERVICES_UNAVAILABLE ->
            OcrFailureReason.GOOGLE_PLAY_SERVICES_UNAVAILABLE
        OcrModelFailure.AVAILABILITY_CHECK_FAILED,
        OcrModelFailure.INSTALLATION_FAILED,
        -> OcrFailureReason.MODEL_UNAVAILABLE
    }

    private fun OcrPageRecognitionRequest.failure(
        reason: OcrFailureReason,
    ): OcrPageRecognitionOutcome.Failure = OcrPageRecognitionOutcome.Failure(
        descriptor = descriptor,
        reason = reason,
    )
}
