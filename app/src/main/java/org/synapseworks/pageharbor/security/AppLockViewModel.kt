package org.synapseworks.pageharbor.security

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android lifecycle holder for the process-local app-lock state machine. PIN arrays are forwarded
 * synchronously and are never retained in ViewModel state.
 */
class AppLockViewModel(application: Application) : AndroidViewModel(application) {
    private val controller: AppLockRuntimeController = run {
        val credentialService = PinVerifier(AndroidKeystoreAppLockPepper())
        AppLockRuntimeController(
            stateStore = SharedPreferencesAppLockStateStore(application),
            credentialService = credentialService,
            pinAuthenticator = AppLockPinAuthenticator(credentialService),
        )
    }

    private val mutableState = MutableStateFlow(controller.state)
    val state: StateFlow<AppLockState> = mutableState.asStateFlow()

    fun setup(
        pin: CharArray,
        confirmation: CharArray,
        timeout: AutoLockTimeout = AutoLockTimeout.DEFAULT,
    ): AppLockSetupResult = controller.setup(pin, confirmation, timeout).also { publish() }

    fun unlockWithPin(pin: CharArray): AppLockUnlockResult =
        controller.unlockWithPin(pin).also { publish() }

    fun beginBiometricAuthentication(
        availability: AppLockBiometricAvailability,
    ): AppLockBiometricAttempt? = controller.beginBiometricAuthentication(availability)
        .also { publish() }

    fun onBiometricResult(
        attemptId: AppLockBiometricAttemptId,
        result: AppLockBiometricResult,
    ): Boolean = controller.onBiometricResult(attemptId, result).also { publish() }

    /** Binds callbacks to the exact attempt token so stale prompt results cannot unlock content. */
    fun authenticateWithBiometric(
        biometricController: AppLockBiometricController,
    ): Boolean {
        val attempt = beginBiometricAuthentication(biometricController.availability())
            ?: return false
        return try {
            biometricController.authenticate { result ->
                onBiometricResult(attempt.id, result)
            }
            true
        } catch (_: Exception) {
            onBiometricResult(
                attempt.id,
                AppLockBiometricResult.Unavailable(
                    AppLockBiometricAvailability.TEMPORARILY_UNAVAILABLE,
                ),
            )
            false
        }
    }

    fun replacePinAfterAuthentication(
        newPin: CharArray,
        confirmation: CharArray,
    ): AppLockAuthenticatedChangeResult =
        controller.replacePinAfterAuthentication(newPin, confirmation).also { publish() }

    fun setAutoLockTimeoutAfterAuthentication(
        timeout: AutoLockTimeout,
    ): AppLockAuthenticatedChangeResult =
        controller.setAutoLockTimeoutAfterAuthentication(timeout).also { publish() }

    fun enableBiometricAfterAuthentication(
        biometricController: AppLockBiometricController,
    ): AppLockAuthenticatedChangeResult =
        controller.enableBiometricAfterAuthentication(biometricController).also { publish() }

    fun disableBiometricAfterAuthentication(): AppLockAuthenticatedChangeResult =
        controller.disableBiometricAfterAuthentication().also { publish() }

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
