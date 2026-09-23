package org.synapseworks.pageharbor.security

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidXAppLockAuthenticationControllerTest {
    @Test
    fun deviceCredentialAndPromptErrorsRemainNonUnlockingResults() {
        assertEquals(
            AppLockAuthenticationResult.Cancelled,
            authenticationResultForPromptError(BiometricPrompt.ERROR_USER_CANCELED),
        )
        assertEquals(
            AppLockAuthenticationResult.Unavailable(
                AppLockAuthenticationAvailability.NO_SECURE_DEVICE_LOCK,
            ),
            authenticationResultForPromptError(BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL),
        )
        assertEquals(
            AppLockAuthenticationResult.Unavailable(
                AppLockAuthenticationAvailability.TEMPORARILY_UNAVAILABLE,
            ),
            authenticationResultForPromptError(BiometricPrompt.ERROR_LOCKOUT),
        )
    }

    @Test
    fun secureDeviceCredentialAvailabilityIsNotRejectedForMissingBiometricEnrollment() {
        assertEquals(
            AppLockAuthenticationAvailability.AVAILABLE,
            authenticationAvailabilityFor(
                secureDevice = true,
                sdkInt = 30,
                managerResult = BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED,
            ),
        )
        assertEquals(
            AppLockAuthenticationAvailability.NO_SECURE_DEVICE_LOCK,
            authenticationAvailabilityFor(
                secureDevice = false,
                sdkInt = 35,
                managerResult = BiometricManager.BIOMETRIC_SUCCESS,
            ),
        )
    }
}
