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
    fun disclosureDoesNotOverstateProtectionOrPromiseAppCredentialRecovery() {
        assertTrue(AppLockDisclosure.DEFAULT.atRestLimitation.contains("does not encrypt"))
        assertTrue(AppLockDisclosure.DEFAULT.recovery.contains("device"))
    }

    @Test
    fun persistenceSchemaHasNoPinOrUnlockedSessionField() {
        assertFalse(AppLockPreferenceSchema.ENABLED in AppLockPreferenceSchema.LEGACY_KEYS)
        assertFalse(AppLockPreferenceSchema.AUTO_LOCK_TIMEOUT in AppLockPreferenceSchema.LEGACY_KEYS)
        assertTrue("salt_base64" in AppLockPreferenceSchema.LEGACY_KEYS)
    }
}
