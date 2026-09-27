package org.synapseworks.pageharbor.ocr

import kotlinx.coroutines.flow.StateFlow

/** Closeable, script-specific recognizer with no dependency on an SDK client type. */
interface OcrScriptRecognizer : OcrPageRecognitionEngine, AutoCloseable {
    val script: OcrScript

    override fun close()
}

/** Factory result that cannot leak vendor exceptions or accidentally trigger model installation. */
sealed interface OcrRecognizerAcquisition {
    data class Available(val recognizer: OcrScriptRecognizer) : OcrRecognizerAcquisition
    data class Unavailable(
        val reason: OcrRecognizerUnavailableReason,
    ) : OcrRecognizerAcquisition
}

enum class OcrRecognizerUnavailableReason {
    MODEL_UNAVAILABLE,
    GOOGLE_PLAY_SERVICES_UNAVAILABLE,
    INITIALIZATION_FAILED,
}

/** Selects bundled or optional implementations by stable application-level script. */
fun interface OcrRecognizerFactory {
    fun acquire(script: OcrScript): OcrRecognizerAcquisition
}

/** Read-only model availability boundary; querying it never installs a model. */
fun interface OcrModelStatusProvider {
    suspend fun stateFor(script: OcrScript): OcrModelState
}

/**
 * Explicit, user-triggerable optional-model delivery boundary.
 *
 * Cancelling a caller only stops that caller waiting. It does not claim to cancel a request that
 * Google Play services has already accepted. There is deliberately no model-removal contract.
 */
interface OcrModelInstaller : OcrModelStatusProvider {
    val states: StateFlow<Map<OcrScript, OcrModelState>>

    suspend fun requestInstall(script: OcrScript): OcrModelState
}
