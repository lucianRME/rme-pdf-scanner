package org.synapseworks.pageharbor.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockRuntimeControllerTest {
    @Test
    fun setupRequiresMatchingSixDigitPinAndDoesNotRetainWorkingPin() {
        val store = MemoryStore()
        val credentials = FakeCredentialService()
        val controller = controller(store, credentials)

        assertTrue(
            controller.setup("12345".toCharArray(), "12345".toCharArray()) is
                AppLockSetupResult.InvalidPin,
        )
        assertEquals(
            AppLockSetupResult.ConfirmationMismatch,
            controller.setup("123456".toCharArray(), "654321".toCharArray()),
        )

        val result = controller.setup(
            "123456".toCharArray(),
            "123456".toCharArray(),
            AutoLockTimeout.FIVE_MINUTES,
        )

        assertTrue(result is AppLockSetupResult.Success)
        assertEquals(AppLockPhase.UNLOCKED, controller.state.phase)
        assertTrue(controller.state.hasAuthenticatedSession)
        assertEquals(AutoLockTimeout.FIVE_MINUTES, store.state.config.autoLockTimeout)
        assertFalse(store.state.config.biometricEnabled)
        assertNotNull(store.state.credential)
        assertTrue(checkNotNull(credentials.lastCreateInput).all { it == '\u0000' })
    }

    @Test
    fun enabledLockAlwaysStartsLockedAfterProcessRestart() {
        val store = MemoryStore()
        val credentials = FakeCredentialService()
        val firstProcess = controller(store, credentials)
        firstProcess.setup("123456".toCharArray(), "123456".toCharArray())
        assertEquals(AppLockPhase.UNLOCKED, firstProcess.state.phase)

        val restartedProcess = controller(store, credentials)

        assertEquals(AppLockPhase.LOCKED, restartedProcess.state.phase)
        assertFalse(restartedProcess.state.hasAuthenticatedSession)
        assertFalse(restartedProcess.state.shouldComposeProtectedContent)
    }

    @Test
    fun acceptedPinUnlocksWhileRejectedAndThrottledPinsRemainLocked() {
        val acceptedStore = configuredStore()
        val accepted = controller(
            acceptedStore,
            pinAttempt = { _, state, _ -> PinAttemptResult.Accepted(state) },
        )

        assertEquals(AppLockUnlockResult.ACCEPTED, accepted.unlockWithPin("123456".toCharArray()))
        assertEquals(AppLockPhase.UNLOCKED, accepted.state.phase)

        val rejectedStore = configuredStore()
        val rejected = controller(
            rejectedStore,
            pinAttempt = { _, state, _ -> PinAttemptResult.Rejected(state, 30_000L) },
        )
        assertEquals(AppLockUnlockResult.REJECTED, rejected.unlockWithPin("000000".toCharArray()))
        assertEquals(AppLockPhase.LOCKED, rejected.state.phase)
        assertEquals(AppLockIssue.INCORRECT_PIN, rejected.state.issue)
        assertEquals(30_000L, rejected.state.retryAfterMillis)

        val throttled = controller(
            configuredStore(),
            pinAttempt = { _, state, _ -> PinAttemptResult.Throttled(state, 60_000L) },
        )
        assertEquals(AppLockUnlockResult.THROTTLED, throttled.unlockWithPin("000000".toCharArray()))
        assertEquals(AppLockPhase.LOCKED, throttled.state.phase)
        assertEquals(AppLockIssue.PIN_THROTTLED, throttled.state.issue)
    }

    @Test
    fun internalPinCopyIsZeroedAfterAuthenticationAttempt() {
        var receivedPin: CharArray? = null
        val controller = controller(
            configuredStore(),
            pinAttempt = { pin, state, _ ->
                receivedPin = pin
                PinAttemptResult.Rejected(state, 0L)
            },
        )

        controller.unlockWithPin("123456".toCharArray())

        assertTrue(checkNotNull(receivedPin).all { it == '\u0000' })
    }

    @Test
    fun immediateOneMinuteAndFiveMinuteTimeoutsUseMonotonicBackgroundTime() {
        val immediate = unlockedController(AutoLockTimeout.IMMEDIATELY)
        immediate.onAppBackgrounded(1_000L)
        assertEquals(AppLockPhase.LOCKED, immediate.state.phase)

        val oneMinute = unlockedController(AutoLockTimeout.ONE_MINUTE)
        oneMinute.onAppBackgrounded(1_000L)
        oneMinute.onAppForegrounded(60_999L)
        assertEquals(AppLockPhase.UNLOCKED, oneMinute.state.phase)
        oneMinute.onAppBackgrounded(100_000L)
        oneMinute.onAppForegrounded(160_000L)
        assertEquals(AppLockPhase.LOCKED, oneMinute.state.phase)

        val fiveMinutes = unlockedController(AutoLockTimeout.FIVE_MINUTES)
        fiveMinutes.onAppBackgrounded(10_000L)
        fiveMinutes.onAppForegrounded(309_999L)
        assertEquals(AppLockPhase.UNLOCKED, fiveMinutes.state.phase)
        fiveMinutes.onAppBackgrounded(400_000L)
        fiveMinutes.onAppForegrounded(700_000L)
        assertEquals(AppLockPhase.LOCKED, fiveMinutes.state.phase)
    }

    @Test
    fun biometricSuccessUnlocksButFailureCancelAndUnavailableRemainLocked() {
        val failed = biometricController()
        val failedAttempt = checkNotNull(
            failed.beginBiometricAuthentication(AppLockBiometricAvailability.AVAILABLE),
        )
        assertTrue(failed.onBiometricResult(failedAttempt.id, AppLockBiometricResult.Failed))
        assertEquals(AppLockPhase.LOCKED, failed.state.phase)
        assertEquals(failedAttempt.id, failed.state.activeBiometricAttempt)
        assertTrue(failed.onBiometricResult(failedAttempt.id, AppLockBiometricResult.Success))
        assertEquals(AppLockPhase.UNLOCKED, failed.state.phase)

        val cancelled = biometricController()
        val cancelledAttempt = checkNotNull(
            cancelled.beginBiometricAuthentication(AppLockBiometricAvailability.AVAILABLE),
        )
        cancelled.onBiometricResult(cancelledAttempt.id, AppLockBiometricResult.Cancelled)
        assertEquals(AppLockPhase.LOCKED, cancelled.state.phase)
        assertEquals(AppLockIssue.BIOMETRIC_CANCELLED, cancelled.state.issue)
        assertNull(cancelled.state.activeBiometricAttempt)

        val unavailable = biometricController()
        assertNull(
            unavailable.beginBiometricAuthentication(
                AppLockBiometricAvailability.NONE_ENROLLED,
            ),
        )
        assertEquals(AppLockPhase.LOCKED, unavailable.state.phase)
        assertEquals(AppLockIssue.BIOMETRIC_UNAVAILABLE, unavailable.state.issue)
    }

    @Test
    fun invalidatedBiometricKeyDisablesBiometricAndLeavesPinFallbackLocked() {
        val store = configuredStore(biometricEnabled = true)
        val controller = controller(store)
        val attempt = checkNotNull(
            controller.beginBiometricAuthentication(AppLockBiometricAvailability.AVAILABLE),
        )

        controller.onBiometricResult(attempt.id, AppLockBiometricResult.KeyInvalidated)

        assertEquals(AppLockPhase.LOCKED, controller.state.phase)
        assertEquals(AppLockIssue.BIOMETRIC_KEY_INVALIDATED, controller.state.issue)
        assertFalse(controller.state.config.biometricEnabled)
        assertFalse(store.state.config.biometricEnabled)
        assertEquals(
            AppLockUnlockResult.ACCEPTED,
            controller.unlockWithPin("123456".toCharArray()),
        )
    }

    @Test
    fun staleBiometricCallbackCannotUnlockAnotherAttemptOrSession() {
        val controller = biometricController()
        val first = checkNotNull(
            controller.beginBiometricAuthentication(AppLockBiometricAvailability.AVAILABLE),
        )
        controller.onBiometricResult(first.id, AppLockBiometricResult.Cancelled)
        val second = checkNotNull(
            controller.beginBiometricAuthentication(AppLockBiometricAvailability.AVAILABLE),
        )

        assertFalse(controller.onBiometricResult(first.id, AppLockBiometricResult.Success))
        assertEquals(AppLockPhase.LOCKED, controller.state.phase)
        assertTrue(controller.onBiometricResult(second.id, AppLockBiometricResult.Success))
        assertEquals(AppLockPhase.UNLOCKED, controller.state.phase)
    }

    @Test
    fun biometricCallbackDeliveredAfterBackgroundingCannotUnlock() {
        val controller = biometricController()
        val attempt = checkNotNull(
            controller.beginBiometricAuthentication(AppLockBiometricAvailability.AVAILABLE),
        )

        controller.onAppBackgrounded(1_000L)

        assertNull(controller.state.activeBiometricAttempt)
        assertFalse(controller.onBiometricResult(attempt.id, AppLockBiometricResult.Success))
        assertEquals(AppLockPhase.LOCKED, controller.state.phase)
    }

    @Test
    fun pinReplacementAndSettingsChangesRequireAuthenticatedSession() {
        val store = configuredStore(biometricEnabled = true)
        val credentials = FakeCredentialService()
        val controller = controller(store, credentials)

        assertEquals(
            AppLockAuthenticatedChangeResult.AUTHENTICATION_REQUIRED,
            controller.replacePinAfterAuthentication(
                "654321".toCharArray(),
                "654321".toCharArray(),
            ),
        )

        val attempt = checkNotNull(
            controller.beginBiometricAuthentication(AppLockBiometricAvailability.AVAILABLE),
        )
        controller.onBiometricResult(attempt.id, AppLockBiometricResult.Success)

        assertEquals(
            AppLockAuthenticatedChangeResult.APPLIED,
            controller.replacePinAfterAuthentication(
                "654321".toCharArray(),
                "654321".toCharArray(),
            ),
        )
        assertEquals(
            AppLockAuthenticatedChangeResult.APPLIED,
            controller.setAutoLockTimeoutAfterAuthentication(AutoLockTimeout.FIVE_MINUTES),
        )
        assertEquals(AutoLockTimeout.FIVE_MINUTES, store.state.config.autoLockTimeout)
        assertTrue(checkNotNull(credentials.lastCreateInput).all { it == '\u0000' })
    }

    @Test
    fun biometricCanBeEnabledOnlyAfterAuthenticationAndSuccessfulKeyProvisioning() {
        val store = configuredStore()
        val controller = controller(store)
        val available = FakeBiometricController()

        assertEquals(
            AppLockAuthenticatedChangeResult.AUTHENTICATION_REQUIRED,
            controller.enableBiometricAfterAuthentication(available),
        )
        controller.unlockWithPin("123456".toCharArray())

        val unavailable = FakeBiometricController(
            availability = AppLockBiometricAvailability.NONE_ENROLLED,
        )
        assertEquals(
            AppLockAuthenticatedChangeResult.BIOMETRIC_UNAVAILABLE,
            controller.enableBiometricAfterAuthentication(unavailable),
        )
        assertFalse(store.state.config.biometricEnabled)

        assertEquals(
            AppLockAuthenticatedChangeResult.APPLIED,
            controller.enableBiometricAfterAuthentication(available),
        )
        assertTrue(available.recreateCalled)
        assertTrue(store.state.config.biometricEnabled)
    }

    @Test
    fun disablingLockRequiresAuthenticationAndOnlyClearsLockCredentialState() {
        val store = configuredStore(biometricEnabled = true)
        val controller = controller(store)

        assertEquals(
            AppLockAuthenticatedChangeResult.AUTHENTICATION_REQUIRED,
            controller.disableAfterAuthentication(),
        )
        assertNotNull(store.state.credential)

        controller.unlockWithPin("123456".toCharArray())
        assertEquals(
            AppLockAuthenticatedChangeResult.APPLIED,
            controller.disableAfterAuthentication(),
        )
        assertEquals(AppLockPhase.DISABLED, controller.state.phase)
        assertFalse(store.state.config.enabled)
        assertFalse(store.state.config.biometricEnabled)
        assertNull(store.state.credential)
        assertEquals(PinRetryThrottleState(), store.state.throttle)
    }

    @Test
    fun protectedEntryPointsAreHeldWhileLockedAndAllowedOnlyAfterUnlock() {
        val controller = controller(configuredStore())
        val entryPoints = listOf(
            AppLockProtectedEntryPoint.APP_CONTENT,
            AppLockProtectedEntryPoint.DEEP_LINK,
            AppLockProtectedEntryPoint.INBOUND_SHARE,
            AppLockProtectedEntryPoint.ACTIVITY_RESULT_CALLBACK,
        )

        entryPoints.forEach { entryPoint ->
            assertEquals(
                AppLockAccessDecision.HOLD_UNTIL_UNLOCKED,
                controller.gate(entryPoint).decision,
            )
        }

        controller.unlockWithPin("123456".toCharArray())
        entryPoints.forEach { entryPoint ->
            assertEquals(AppLockAccessDecision.ALLOW, controller.gate(entryPoint).decision)
        }
    }

    @Test
    fun storageReadFailureFailsClosedAndNeverComposesProtectedContent() {
        val store = MemoryStore().apply { failReads = true }

        val controller = controller(store)

        assertEquals(AppLockPhase.LOCKED, controller.state.phase)
        assertEquals(AppLockIssue.STORAGE_UNAVAILABLE, controller.state.issue)
        assertFalse(controller.state.shouldComposeProtectedContent)
    }

    private fun biometricController(): AppLockRuntimeController =
        controller(configuredStore(biometricEnabled = true))

    private fun unlockedController(timeout: AutoLockTimeout): AppLockRuntimeController {
        val controller = controller(configuredStore(timeout = timeout))
        controller.unlockWithPin("123456".toCharArray())
        return controller
    }

    private fun configuredStore(
        timeout: AutoLockTimeout = AutoLockTimeout.ONE_MINUTE,
        biometricEnabled: Boolean = false,
    ): MemoryStore = MemoryStore(
        AppLockPersistentState(
            config = AppLockConfig(
                enabled = true,
                autoLockTimeout = timeout,
                biometricEnabled = biometricEnabled,
            ),
            credential = TEST_CREDENTIAL,
        ),
    )

    private fun controller(
        store: MemoryStore,
        credentials: FakeCredentialService = FakeCredentialService(),
        pinAttempt: AppLockPinAttemptAuthenticator = AppLockPinAttemptAuthenticator { _, state, _ ->
            PinAttemptResult.Accepted(state.copy(throttle = PinRetryThrottleState()))
        },
    ): AppLockRuntimeController = AppLockRuntimeController(
        stateStore = store,
        credentialService = credentials,
        pinAuthenticator = pinAttempt,
        wallTimeMillis = { 50_000L },
    )

    private class MemoryStore(
        initial: AppLockPersistentState = AppLockPersistentState(),
    ) : AppLockStateStore {
        var state = initial
        var failReads = false
        var failWrites = false

        override fun read(): AppLockPersistentState {
            if (failReads) error("unavailable")
            return state
        }

        override fun write(state: AppLockPersistentState) {
            if (failWrites) error("unavailable")
            this.state = state
        }
    }

    private class FakeCredentialService : PinCredentialService {
        var lastCreateInput: CharArray? = null

        override fun createCredential(
            pin: CharArray,
            parameters: PinKdfParameters,
        ): PinCredentialRecord {
            lastCreateInput = pin
            return TEST_CREDENTIAL
        }

        override fun verify(pin: CharArray, credential: PinCredentialRecord): Boolean = true
    }

    private class FakeBiometricController(
        private val availability: AppLockBiometricAvailability =
            AppLockBiometricAvailability.AVAILABLE,
        private val recreateSucceeds: Boolean = true,
    ) : AppLockBiometricController {
        var recreateCalled = false

        override fun availability(): AppLockBiometricAvailability = availability

        override fun authenticate(callback: AppLockBiometricResultCallback) = Unit

        override fun cancel() = Unit

        override fun recreateKeyAfterPinAuthentication(): Boolean {
            recreateCalled = true
            return recreateSucceeds
        }
    }

    private companion object {
        val TEST_CREDENTIAL = PinCredentialRecord(
            salt = ByteArray(PinCredentialRecord.MINIMUM_SALT_BYTES) { 1 },
            kdfParameters = PinKdfParameters(
                iterations = PinKdfParameters.MINIMUM_ENROLLMENT_ITERATIONS,
            ),
            verifier = ByteArray(PinCredentialRecord.SHA_256_BYTES) { 2 },
        )
    }
}
