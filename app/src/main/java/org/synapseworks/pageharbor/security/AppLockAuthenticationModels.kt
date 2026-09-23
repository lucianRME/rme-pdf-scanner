package org.synapseworks.pageharbor.security

/** Availability of Android's existing device authentication. */
enum class AppLockAuthenticationAvailability {
    AVAILABLE,
    NO_SECURE_DEVICE_LOCK,
    TEMPORARILY_UNAVAILABLE,
    UNSUPPORTED,
}

sealed interface AppLockAuthenticationResult {
    data object Success : AppLockAuthenticationResult
    data object Failed : AppLockAuthenticationResult
    data object Cancelled : AppLockAuthenticationResult
    data class Unavailable(
        val availability: AppLockAuthenticationAvailability,
    ) : AppLockAuthenticationResult
}

fun interface AppLockAuthenticationResultCallback {
    fun onResult(result: AppLockAuthenticationResult)
}

/** Android system-authentication adapter; no biometric templates or app credential enter RME. */
interface AppLockAuthenticationController {
    fun availability(): AppLockAuthenticationAvailability

    fun authenticate(callback: AppLockAuthenticationResultCallback)

    fun cancel()
}

@JvmInline
value class AppLockAuthenticationAttemptId(val value: Long)

data class AppLockAuthenticationAttempt(
    val id: AppLockAuthenticationAttemptId,
)
