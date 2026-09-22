package org.synapseworks.pageharbor.security

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Lifecycle holder for the process-local app-lock state machine. */
class AppLockViewModel(application: Application) : AndroidViewModel(application) {
    private val controller = AppLockRuntimeController(
        stateStore = SharedPreferencesAppLockStateStore(application),
    )

    private val mutableState = MutableStateFlow(controller.state)
    val state: StateFlow<AppLockState> = mutableState.asStateFlow()

    fun setup(
        timeout: AutoLockTimeout,
        availability: AppLockAuthenticationAvailability,
    ): AppLockSetupResult = controller.setup(timeout, availability).also { publish() }

    fun beginAuthentication(
        availability: AppLockAuthenticationAvailability,
    ): AppLockAuthenticationAttempt? = controller.beginAuthentication(availability)
        .also { publish() }

    fun onAuthenticationResult(
        attemptId: AppLockAuthenticationAttemptId,
        result: AppLockAuthenticationResult,
    ): Boolean = controller.onAuthenticationResult(attemptId, result).also { publish() }

    /** Binds callbacks to the exact attempt token so stale system-prompt results cannot unlock content. */
    fun authenticate(
        authenticationController: AppLockAuthenticationController,
    ): Boolean {
        val attempt = beginAuthentication(authenticationController.availability()) ?: return false
        return try {
            authenticationController.authenticate { result ->
                onAuthenticationResult(attempt.id, result)
            }
            true
        } catch (_: Exception) {
            onAuthenticationResult(
                attempt.id,
                AppLockAuthenticationResult.Unavailable(
                    AppLockAuthenticationAvailability.TEMPORARILY_UNAVAILABLE,
                ),
            )
            false
        }
    }

    fun setAutoLockTimeoutAfterAuthentication(
        timeout: AutoLockTimeout,
    ): AppLockAuthenticatedChangeResult =
        controller.setAutoLockTimeoutAfterAuthentication(timeout).also { publish() }

    fun disableAfterAuthentication(): AppLockAuthenticatedChangeResult =
        controller.disableAfterAuthentication().also { publish() }

    fun onAppBackgrounded(elapsedRealtimeMillis: Long) {
        controller.onAppBackgrounded(elapsedRealtimeMillis)
        publish()
    }

    fun onAppForegrounded(elapsedRealtimeMillis: Long) {
        controller.onAppForegrounded(elapsedRealtimeMillis)
        publish()
    }

    fun lockNow() {
        controller.lockNow()
        publish()
    }

    fun gate(entryPoint: AppLockProtectedEntryPoint): AppLockAccessGate =
        controller.gate(entryPoint)

    private fun publish() {
        mutableState.value = controller.state
    }
}
