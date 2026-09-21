package org.synapseworks.pageharbor.security

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidXAppLockBiometricControllerTest {
    @Test
    fun availabilityMappingUsesStrongBiometricManagerOutcomes() {
        assertEquals(
            AppLockBiometricAvailability.AVAILABLE,
            biometricAvailabilityFor(BiometricManager.BIOMETRIC_SUCCESS),
        )
        assertEquals(
            AppLockBiometricAvailability.NO_HARDWARE,
            biometricAvailabilityFor(BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE),
        )
        assertEquals(
            AppLockBiometricAvailability.NONE_ENROLLED,
            biometricAvailabilityFor(BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED),
        )
        assertEquals(
            AppLockBiometricAvailability.TEMPORARILY_UNAVAILABLE,
            biometricAvailabilityFor(BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE),
        )
        assertEquals(AppLockBiometricAvailability.UNSUPPORTED, biometricAvailabilityFor(-999))
    }

    @Test
    fun promptCancellationAndPinFallbackRemainCancellationResults() {
        listOf(
            BiometricPrompt.ERROR_CANCELED,
            BiometricPrompt.ERROR_NEGATIVE_BUTTON,
            BiometricPrompt.ERROR_USER_CANCELED,
        ).forEach { error ->
            assertEquals(AppLockBiometricResult.Cancelled, biometricResultForPromptError(error))
        }
    }

    @Test
    fun promptHardwareAndEnrollmentErrorsMapWithoutUnlocking() {
        assertEquals(
            AppLockBiometricResult.Unavailable(AppLockBiometricAvailability.NONE_ENROLLED),
            biometricResultForPromptError(BiometricPrompt.ERROR_NO_BIOMETRICS),
        )
        assertEquals(
            AppLockBiometricResult.Unavailable(AppLockBiometricAvailability.NO_HARDWARE),
            biometricResultForPromptError(BiometricPrompt.ERROR_HW_NOT_PRESENT),
        )
        assertEquals(
            AppLockBiometricResult.Unavailable(
                AppLockBiometricAvailability.TEMPORARILY_UNAVAILABLE,
            ),
            biometricResultForPromptError(BiometricPrompt.ERROR_LOCKOUT),
        )
    }
}
