package org.synapseworks.pageharbor.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockModelsTest {
    @Test
    fun enabledAppLockAlwaysStartsLockedButDisabledAppDoesNot() {
        assertTrue(AppLockSessionPolicy.coldStart(AppLockConfig(enabled = true)).isLocked)
        assertFalse(AppLockSessionPolicy.coldStart(AppLockConfig(enabled = false)).isLocked)
    }

    @Test
    fun autoLockUsesConfiguredMonotonicTimeoutAndFailsClosedOnRollback() {
        assertTrue(
            AppLockSessionPolicy.shouldLockAfterBackground(
                AppLockConfig(enabled = true, autoLockTimeout = AutoLockTimeout.IMMEDIATELY),
                backgroundedAtElapsedRealtimeMillis = 1_000L,
                nowElapsedRealtimeMillis = 1_000L,
            ),
        )
        assertFalse(
            AppLockSessionPolicy.shouldLockAfterBackground(
                AppLockConfig(enabled = true, autoLockTimeout = AutoLockTimeout.ONE_MINUTE),
                backgroundedAtElapsedRealtimeMillis = 1_000L,
                nowElapsedRealtimeMillis = 60_999L,
            ),
        )
        assertTrue(
            AppLockSessionPolicy.shouldLockAfterBackground(
                AppLockConfig(enabled = true, autoLockTimeout = AutoLockTimeout.ONE_MINUTE),
                backgroundedAtElapsedRealtimeMillis = 1_000L,
                nowElapsedRealtimeMillis = 61_000L,
            ),
        )
        assertTrue(
            AppLockSessionPolicy.shouldLockAfterBackground(
                AppLockConfig(enabled = true, autoLockTimeout = AutoLockTimeout.FIVE_MINUTES),
                backgroundedAtElapsedRealtimeMillis = 10_000L,
                nowElapsedRealtimeMillis = 9_999L,
            ),
        )
        assertFalse(
            AppLockSessionPolicy.shouldLockAfterBackground(
                AppLockConfig(enabled = false),
                backgroundedAtElapsedRealtimeMillis = 10_000L,
                nowElapsedRealtimeMillis = Long.MAX_VALUE,
            ),
        )
    }

    @Test
    fun defaultTimeoutIsOneMinuteAndUnknownStoredValueFallsBackSafely() {
        assertEquals(AutoLockTimeout.ONE_MINUTE, AppLockConfig().autoLockTimeout)
        assertEquals(AutoLockTimeout.ONE_MINUTE, AutoLockTimeout.fromStorageValue("future_value"))
    }

    @Test
    fun disclosureAndRecoveryDoNotOverstateProtectionOrPromiseRecovery() {
        assertTrue(AppLockDisclosure.DEFAULT.atRestLimitation.contains("does not encrypt"))
        assertTrue(AppLockDisclosure.DEFAULT.recovery.contains("cannot recover"))

        val recovery = AppLockRecoveryModel(biometricUnlockAvailable = false)
        assertFalse(recovery.hasMasterRecoveryKey)
        assertFalse(recovery.deletesDocumentsAfterFailedAttempts)
        assertTrue(AppLockRecoveryPath.NO_REMOTE_RECOVERY in recovery.availablePaths)
        assertFalse(
            AppLockRecoveryPath.AUTHENTICATED_BIOMETRIC_PIN_REPLACEMENT in recovery.availablePaths,
        )
    }

    @Test
    fun persistenceSchemaHasNoPinOrUnlockedSessionField() {
        assertTrue("salt_base64" in AppLockPreferenceSchema.ALL_KEYS)
        assertTrue("verifier_base64" in AppLockPreferenceSchema.ALL_KEYS)
        assertTrue("kdf_iterations" in AppLockPreferenceSchema.ALL_KEYS)
        assertTrue("biometric_enabled" in AppLockPreferenceSchema.ALL_KEYS)
        assertTrue(AppLockPreferenceSchema.ALL_KEYS.none { it == "pin" })
        assertTrue(AppLockPreferenceSchema.ALL_KEYS.none { "unlocked" in it })
    }
}
