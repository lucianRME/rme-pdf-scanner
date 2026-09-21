package org.synapseworks.pageharbor.security

enum class AppLockBiometricAvailability {
    AVAILABLE,
    NO_HARDWARE,
    NONE_ENROLLED,
    TEMPORARILY_UNAVAILABLE,
    UNSUPPORTED,
}

sealed interface AppLockBiometricResult {
    data object Success : AppLockBiometricResult

    /** The prompt remains active after a non-terminal biometric mismatch. */
    data object Failed : AppLockBiometricResult

    data object Cancelled : AppLockBiometricResult

    data class Unavailable(
        val availability: AppLockBiometricAvailability,
    ) : AppLockBiometricResult

    /** The device-local key can no longer be used; PIN remains the fallback. */
    data object KeyInvalidated : AppLockBiometricResult
}

fun interface AppLockBiometricResultCallback {
    fun onResult(result: AppLockBiometricResult)
}

/** Android UI adapter boundary. It never exposes templates or raw biometric data. */
interface AppLockBiometricController {
    fun availability(): AppLockBiometricAvailability

    fun authenticate(callback: AppLockBiometricResultCallback)

    fun cancel()

    /** Call only after successful PIN authentication when enabling or repairing biometrics. */
    fun recreateKeyAfterPinAuthentication(): Boolean
}

@JvmInline
value class AppLockBiometricAttemptId(val value: Long)

data class AppLockBiometricAttempt(
    val id: AppLockBiometricAttemptId,
)
