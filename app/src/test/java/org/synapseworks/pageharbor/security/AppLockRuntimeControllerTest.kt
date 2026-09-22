package org.synapseworks.pageharbor.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockRuntimeControllerTest {
    @Test
    fun setupRequiresSecureDeviceAuthenticationAndUsesDefaultTimeout() {
        val store = MemoryStore()
        val controller = AppLockRuntimeController(store)

        assertEquals(
            AppLockSetupResult.DeviceAuthenticationUnavailable,
            controller.setup(AutoLockTimeout.DEFAULT, AppLockAuthenticationAvailability.NO_SECURE_DEVICE_LOCK),
        )
        assertEquals(AppLockPhase.DISABLED, controller.state.phase)

        val result = controller.setup(
            AutoLockTimeout.DEFAULT,
            AppLockAuthenticationAvailability.AVAILABLE,
        )
        assertTrue(result is AppLockSetupResult.Success)
        assertEquals(AppLockPhase.UNLOCKED, controller.state.phase)
        assertEquals(AutoLockTimeout.ONE_MINUTE, store.state.config.autoLockTimeout)
    }

    @Test
    fun enabledLockStartsLockedAfterProcessRestart() {
        val store = MemoryStore(AppLockPersistentState(AppLockConfig(enabled = true)))
        val controller = AppLockRuntimeController(store)
        assertEquals(AppLockPhase.LOCKED, controller.state.phase)
        assertFalse(controller.state.hasAuthenticatedSession)
    }

    @Test
    fun systemAuthenticationSuccessUnlocksAndCancelOrFailureRemainLocked() {
        val controller = AppLockRuntimeController(
            MemoryStore(AppLockPersistentState(AppLockConfig(enabled = true))),
        )
        val attempt = checkNotNull(
            controller.beginAuthentication(AppLockAuthenticationAvailability.AVAILABLE),
        )
        assertTrue(controller.onAuthenticationResult(attempt.id, AppLockAuthenticationResult.Failed))
        assertEquals(AppLockPhase.LOCKED, controller.state.phase)
        assertEquals(AppLockIssue.AUTHENTICATION_FAILED, controller.state.issue)
        assertTrue(controller.onAuthenticationResult(attempt.id, AppLockAuthenticationResult.Success))
        assertEquals(AppLockPhase.UNLOCKED, controller.state.phase)

        val cancelledController = AppLockRuntimeController(
            MemoryStore(AppLockPersistentState(AppLockConfig(enabled = true))),
        )
        val cancelledAttempt = checkNotNull(
            cancelledController.beginAuthentication(AppLockAuthenticationAvailability.AVAILABLE),
        )
        assertTrue(
            cancelledController.onAuthenticationResult(
                cancelledAttempt.id,
                AppLockAuthenticationResult.Cancelled,
            ),
        )
        assertEquals(AppLockPhase.LOCKED, cancelledController.state.phase)
        assertEquals(AppLockIssue.AUTHENTICATION_CANCELLED, cancelledController.state.issue)
    }

    @Test
    fun staleAuthenticationCallbackCannotUnlockAnotherAttempt() {
        val controller = AppLockRuntimeController(
            MemoryStore(AppLockPersistentState(AppLockConfig(enabled = true))),
        )
        val first = checkNotNull(
            controller.beginAuthentication(AppLockAuthenticationAvailability.AVAILABLE),
        )
        controller.onAuthenticationResult(first.id, AppLockAuthenticationResult.Cancelled)
        val second = checkNotNull(
            controller.beginAuthentication(AppLockAuthenticationAvailability.AVAILABLE),
        )
        assertFalse(controller.onAuthenticationResult(first.id, AppLockAuthenticationResult.Success))
        assertTrue(controller.onAuthenticationResult(second.id, AppLockAuthenticationResult.Success))
        assertEquals(AppLockPhase.UNLOCKED, controller.state.phase)
    }

    @Test
    fun backgroundTimeoutAndProtectedEntryPointsRemainEnforced() {
        val controller = AppLockRuntimeController(
            MemoryStore(
                AppLockPersistentState(
                    AppLockConfig(enabled = true, autoLockTimeout = AutoLockTimeout.ONE_MINUTE),
                ),
            ),
        )
        val attempt = checkNotNull(
            controller.beginAuthentication(AppLockAuthenticationAvailability.AVAILABLE),
        )
        controller.onAuthenticationResult(attempt.id, AppLockAuthenticationResult.Success)
        controller.onAppBackgrounded(1_000L)
        controller.onAppForegrounded(60_999L)
        assertEquals(AppLockPhase.UNLOCKED, controller.state.phase)
        controller.onAppBackgrounded(100_000L)
        controller.onAppForegrounded(160_000L)
        assertEquals(AppLockPhase.LOCKED, controller.state.phase)
        assertEquals(
            AppLockAccessDecision.HOLD_UNTIL_UNLOCKED,
            controller.gate(AppLockProtectedEntryPoint.INBOUND_SHARE).decision,
        )
    }

    @Test
    fun settingsChangesRequireAuthenticatedSessionAndDisableRemovesLock() {
        val store = MemoryStore(AppLockPersistentState(AppLockConfig(enabled = true)))
        val controller = AppLockRuntimeController(store)
        assertEquals(
            AppLockAuthenticatedChangeResult.AUTHENTICATION_REQUIRED,
            controller.disableAfterAuthentication(),
        )
        val attempt = checkNotNull(
            controller.beginAuthentication(AppLockAuthenticationAvailability.AVAILABLE),
        )
        controller.onAuthenticationResult(attempt.id, AppLockAuthenticationResult.Success)
        assertEquals(
            AppLockAuthenticatedChangeResult.APPLIED,
            controller.setAutoLockTimeoutAfterAuthentication(AutoLockTimeout.FIVE_MINUTES),
        )
        assertEquals(
            AppLockAuthenticatedChangeResult.APPLIED,
            controller.disableAfterAuthentication(),
        )
        assertEquals(AppLockPhase.DISABLED, controller.state.phase)
        assertNull(store.state.config.enabled.takeIf { it })
    }

    private class MemoryStore(
        var state: AppLockPersistentState = AppLockPersistentState(),
    ) : AppLockStateStore {
        override fun read(): AppLockPersistentState = state
        override fun write(state: AppLockPersistentState) {
            this.state = state
        }
    }
}
