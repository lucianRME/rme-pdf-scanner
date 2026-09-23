package org.synapseworks.pageharbor.security

import android.app.KeyguardManager
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * AndroidX system-authentication adapter. On Android 11 and newer the prompt accepts a strong
 * biometric or the device credential. Older supported releases use the system credential prompt;
 * RME never substitutes a custom app PIN for either path.
 */
class AndroidXAppLockAuthenticationController(
    activity: FragmentActivity,
    promptTitle: CharSequence,
) : AppLockAuthenticationController {
    private val keyguardManager =
        activity.getSystemService(KeyguardManager::class.java)
    private val biometricManager = BiometricManager.from(activity)
    private val callbackMonitor = Any()
    private var activeCallback: AppLockAuthenticationResultCallback? = null
    private var authenticationInProgress = false

    private val promptInfo = BiometricPrompt.PromptInfo.Builder()
        .setTitle(promptTitle)
        .apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                )
            } else {
                @Suppress("DEPRECATION")
                setDeviceCredentialAllowed(true)
            }
        }
        .build()

    private val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(
                result: BiometricPrompt.AuthenticationResult,
            ) = finish(AppLockAuthenticationResult.Success)

            override fun onAuthenticationFailed() {
                synchronized(callbackMonitor) { activeCallback }
                    ?.onResult(AppLockAuthenticationResult.Failed)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                finish(authenticationResultForPromptError(errorCode))
            }
        },
    )

    override fun availability(): AppLockAuthenticationAvailability {
        if (!keyguardManager.isDeviceSecure) {
            return AppLockAuthenticationAvailability.NO_SECURE_DEVICE_LOCK
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return AppLockAuthenticationAvailability.AVAILABLE
        }

        return authenticationAvailabilityFor(
            secureDevice = true,
            sdkInt = Build.VERSION.SDK_INT,
            managerResult = biometricManager.canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            ),
        )
    }

    override fun authenticate(callback: AppLockAuthenticationResultCallback) {
        val alreadyInProgress = synchronized(callbackMonitor) {
            if (authenticationInProgress) {
                true
            } else {
                authenticationInProgress = true
                activeCallback = callback
                false
            }
        }
        if (alreadyInProgress) {
            callback.onResult(
                AppLockAuthenticationResult.Unavailable(
                    AppLockAuthenticationAvailability.TEMPORARILY_UNAVAILABLE,
                ),
            )
            return
        }

        val availability = availability()
        if (availability != AppLockAuthenticationAvailability.AVAILABLE) {
            finish(AppLockAuthenticationResult.Unavailable(availability))
            return
        }
        try {
            prompt.authenticate(promptInfo)
        } catch (_: RuntimeException) {
            finish(
                AppLockAuthenticationResult.Unavailable(
                    AppLockAuthenticationAvailability.TEMPORARILY_UNAVAILABLE,
                ),
            )
        }
    }

    override fun cancel() {
        prompt.cancelAuthentication()
    }

    private fun finish(result: AppLockAuthenticationResult) {
        val callback = synchronized(callbackMonitor) {
            activeCallback.also {
                activeCallback = null
                authenticationInProgress = false
            }
        }
        callback?.onResult(result)
    }

}

internal fun authenticationAvailabilityFor(
    secureDevice: Boolean,
    sdkInt: Int,
    managerResult: Int,
): AppLockAuthenticationAvailability {
    if (!secureDevice) return AppLockAuthenticationAvailability.NO_SECURE_DEVICE_LOCK
    if (sdkInt < Build.VERSION_CODES.R) return AppLockAuthenticationAvailability.AVAILABLE

    return when (managerResult) {
        BiometricManager.BIOMETRIC_SUCCESS,
        // A secure credential remains a valid path when biometric hardware is absent or not
        // enrolled; the combined prompt will use the device credential in those cases.
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED,
        -> AppLockAuthenticationAvailability.AVAILABLE

        BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE ->
            AppLockAuthenticationAvailability.TEMPORARILY_UNAVAILABLE

        else -> AppLockAuthenticationAvailability.UNSUPPORTED
    }
}

internal fun authenticationResultForPromptError(errorCode: Int): AppLockAuthenticationResult = when {
    errorCode == BiometricPrompt.ERROR_CANCELED ||
        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
        errorCode == BiometricPrompt.ERROR_USER_CANCELED ->
        AppLockAuthenticationResult.Cancelled

    errorCode == BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL ->
        AppLockAuthenticationResult.Unavailable(
            AppLockAuthenticationAvailability.NO_SECURE_DEVICE_LOCK,
        )

    else -> AppLockAuthenticationResult.Unavailable(
        AppLockAuthenticationAvailability.TEMPORARILY_UNAVAILABLE,
    )
}
