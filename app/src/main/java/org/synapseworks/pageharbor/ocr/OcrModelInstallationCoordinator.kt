package org.synapseworks.pageharbor.ocr

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal sealed interface OptionalOcrModelAvailability {
    data object Installed : OptionalOcrModelAvailability
    data object NotInstalled : OptionalOcrModelAvailability
    data object Unsupported : OptionalOcrModelAvailability
    data class Failed(
        val reason: OcrModelFailure,
        val retryable: Boolean,
    ) : OptionalOcrModelAvailability
}

internal sealed interface OptionalOcrModelInstallResult {
    data object Installed : OptionalOcrModelInstallResult
    data object Canceled : OptionalOcrModelInstallResult
    data object Unsupported : OptionalOcrModelInstallResult
    data class Failed(
        val reason: OcrModelFailure,
        val retryable: Boolean,
    ) : OptionalOcrModelInstallResult
}

internal interface OptionalOcrModelBackend {
    suspend fun availability(script: OcrScript): OptionalOcrModelAvailability

    suspend fun install(
        script: OcrScript,
        onState: (OcrModelState) -> Unit,
    ): OptionalOcrModelInstallResult
}

/**
 * Process-scoped model manager. One backend install is shared by all concurrent callers for the
 * same script, and caller cancellation never pretends to cancel work already owned by Play
 * services. A later caller can retry after any terminal failure.
 */
internal class CoalescingOcrModelInstaller(
    private val backend: OptionalOcrModelBackend,
    private val processScope: CoroutineScope,
) : OcrModelInstaller {
    private val lock = Any()
    private val inFlight = mutableMapOf<OcrScript, CompletableDeferred<OcrModelState>>()
    private val mutableStates = MutableStateFlow(
        OcrScript.entries.associateWith { script ->
            if (script == OcrScript.LATIN) OcrModelState.Bundled else OcrModelState.StatusUnknown
        },
    )

    override val states: StateFlow<Map<OcrScript, OcrModelState>> = mutableStates.asStateFlow()

    override suspend fun stateFor(script: OcrScript): OcrModelState {
        if (script == OcrScript.LATIN) return OcrModelState.Bundled

        synchronized(lock) {
            if (inFlight.containsKey(script)) {
                return mutableStates.value.getValue(script)
            }
        }

        updateState(script, OcrModelState.Checking)
        val resolved = try {
            backend.availability(script).toModelState()
        } catch (_: Exception) {
            OcrModelState.RetryableFailure(OcrModelFailure.AVAILABILITY_CHECK_FAILED)
        }
        updateState(script, resolved)
        return resolved
    }

    override suspend fun requestInstall(script: OcrScript): OcrModelState {
        if (script == OcrScript.LATIN) return OcrModelState.Bundled

        val (request, startsRequest) = synchronized(lock) {
            if (mutableStates.value[script] == OcrModelState.Installed) {
                return OcrModelState.Installed
            }
            inFlight[script]?.let { existing -> existing to false }
                ?: CompletableDeferred<OcrModelState>().also { created ->
                    inFlight[script] = created
                    updateState(script, OcrModelState.Installing)
                } to true
        }

        if (startsRequest) {
            processScope.launch {
                val terminalState = try {
                    backend.install(script) { state ->
                        if (state.isInstallProgressState()) updateState(script, state)
                    }.toModelState()
                } catch (_: Exception) {
                    OcrModelState.RetryableFailure(OcrModelFailure.INSTALLATION_FAILED)
                }
                updateState(script, terminalState)
                request.complete(terminalState)
                synchronized(lock) {
                    if (inFlight[script] === request) inFlight.remove(script)
                }
            }
        }

        return request.await()
    }

    private fun updateState(script: OcrScript, state: OcrModelState) {
        mutableStates.update { current -> current + (script to state) }
    }

    private fun OcrModelState.isInstallProgressState(): Boolean = when (this) {
        OcrModelState.Pending,
        is OcrModelState.Downloading,
        OcrModelState.Paused,
        OcrModelState.Installing,
        -> true

        else -> false
    }

    private fun OptionalOcrModelAvailability.toModelState(): OcrModelState = when (this) {
        OptionalOcrModelAvailability.Installed -> OcrModelState.Installed
        OptionalOcrModelAvailability.NotInstalled -> OcrModelState.NotInstalled
        OptionalOcrModelAvailability.Unsupported -> OcrModelState.Unsupported
        is OptionalOcrModelAvailability.Failed -> if (retryable) {
            OcrModelState.RetryableFailure(reason)
        } else {
            OcrModelState.Failed(reason)
        }
    }

    private fun OptionalOcrModelInstallResult.toModelState(): OcrModelState = when (this) {
        OptionalOcrModelInstallResult.Installed -> OcrModelState.Installed
        OptionalOcrModelInstallResult.Canceled -> OcrModelState.Canceled
        OptionalOcrModelInstallResult.Unsupported -> OcrModelState.Unsupported
        is OptionalOcrModelInstallResult.Failed -> if (retryable) {
            OcrModelState.RetryableFailure(reason)
        } else {
            OcrModelState.Failed(reason)
        }
    }
}
