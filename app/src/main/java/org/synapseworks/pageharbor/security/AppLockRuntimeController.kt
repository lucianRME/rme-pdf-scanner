package org.synapseworks.pageharbor.security

enum class AppLockPhase {
    DISABLED,
    LOCKED,
    UNLOCKED,
}

enum class AppLockIssue {
    INCORRECT_PIN,
    PIN_THROTTLED,
    CREDENTIAL_UNAVAILABLE,
    BIOMETRIC_CANCELLED,
    BIOMETRIC_FAILED,
    BIOMETRIC_UNAVAILABLE,
    BIOMETRIC_KEY_INVALIDATED,
    STORAGE_UNAVAILABLE,
}

data class AppLockState(
    val phase: AppLockPhase,
    val config: AppLockConfig,
    val issue: AppLockIssue? = null,
    val retryAfterMillis: Long = 0L,
    val activeBiometricAttempt: AppLockBiometricAttemptId? = null,
    /** Process-local proof that PIN replacement and settings changes are authenticated. */
    val hasAuthenticatedSession: Boolean = false,
) {
    val shouldComposeProtectedContent: Boolean
        get() = phase != AppLockPhase.LOCKED
}

enum class AppLockProtectedEntryPoint {
    APP_CONTENT,
    DEEP_LINK,
    INBOUND_SHARE,
    ACTIVITY_RESULT_CALLBACK,
}

enum class AppLockAccessDecision {
    ALLOW,
    HOLD_UNTIL_UNLOCKED,
}

data class AppLockAccessGate(
    val entryPoint: AppLockProtectedEntryPoint,
    val decision: AppLockAccessDecision,
)

sealed interface AppLockSetupResult {
    data class Success(val state: AppLockState) : AppLockSetupResult
    data class InvalidPin(val reason: PinValidationResult) : AppLockSetupResult
    data object ConfirmationMismatch : AppLockSetupResult
    data object AuthenticationRequired : AppLockSetupResult
    data object CredentialUnavailable : AppLockSetupResult
    data object StorageUnavailable : AppLockSetupResult
}

enum class AppLockUnlockResult {
    ACCEPTED,
    ALREADY_UNLOCKED,
    NOT_CONFIGURED,
    REJECTED,
    THROTTLED,
    CREDENTIAL_UNAVAILABLE,
    STORAGE_UNAVAILABLE,
}

enum class AppLockAuthenticatedChangeResult {
    APPLIED,
    AUTHENTICATION_REQUIRED,
    INVALID_PIN,
    CONFIRMATION_MISMATCH,
    CREDENTIAL_UNAVAILABLE,
    BIOMETRIC_UNAVAILABLE,
    STORAGE_UNAVAILABLE,
}

/**
 * Pure process-local app-lock state machine. It never stores PIN characters, document metadata, or
 * pending deep-link/share/result payloads. Android callers own those payloads and must consult
 * [gate] before delivering them to protected content.
 */
class AppLockRuntimeController(
    private val stateStore: AppLockStateStore,
    private val credentialService: PinCredentialService,
    private val pinAuthenticator: AppLockPinAttemptAuthenticator,
    private val wallTimeMillis: () -> Long = System::currentTimeMillis,
) {
    private var persistentState: AppLockPersistentState
    private var backgroundedAtElapsedRealtimeMillis: Long? = null
    private var nextBiometricAttemptId = 1L

    var state: AppLockState
        private set

    init {
        val loaded = runCatching(stateStore::read)
        persistentState = loaded.getOrElse {
            AppLockPersistentState(config = AppLockConfig(enabled = true))
        }
        val coldStart = AppLockSessionPolicy.coldStart(persistentState.config)
        state = AppLockState(
            phase = when {
                !persistentState.config.enabled -> AppLockPhase.DISABLED
                coldStart.isLocked -> AppLockPhase.LOCKED
                else -> AppLockPhase.UNLOCKED
            },
            config = persistentState.config,
            issue = if (loaded.isFailure) AppLockIssue.STORAGE_UNAVAILABLE else null,
        )
    }

    fun setup(
        pin: CharArray,
        confirmation: CharArray,
        autoLockTimeout: AutoLockTimeout = AutoLockTimeout.DEFAULT,
    ): AppLockSetupResult {
        if (persistentState.config.enabled) return AppLockSetupResult.AuthenticationRequired
        val validation = PinPolicy.validate(pin)
        if (validation != PinValidationResult.Valid) return AppLockSetupResult.InvalidPin(validation)
        if (!pinsEqual(pin, confirmation)) return AppLockSetupResult.ConfirmationMismatch

        val workingPin = pin.copyOf()
        val credential = try {
            credentialService.createCredential(workingPin)
        } catch (_: Exception) {
            return AppLockSetupResult.CredentialUnavailable
        } finally {
            workingPin.fill('\u0000')
        }
        val updated = AppLockPersistentState(
            config = AppLockConfig(
                enabled = true,
                autoLockTimeout = autoLockTimeout,
                biometricEnabled = false,
            ),
            credential = credential,
            throttle = PinRetryThrottleState(),
        )
        if (!persist(updated)) return AppLockSetupResult.StorageUnavailable
        state = AppLockState(
            phase = AppLockPhase.UNLOCKED,
            config = updated.config,
            hasAuthenticatedSession = true,
        )
        return AppLockSetupResult.Success(state)
    }

    fun unlockWithPin(pin: CharArray): AppLockUnlockResult {
        if (!persistentState.config.enabled) return AppLockUnlockResult.NOT_CONFIGURED
        if (state.phase == AppLockPhase.UNLOCKED) return AppLockUnlockResult.ALREADY_UNLOCKED

        val workingPin = pin.copyOf()
        val attempt = try {
            pinAuthenticator.attempt(workingPin, persistentState, wallTimeMillis())
        } catch (_: Exception) {
            state = lockedState(AppLockIssue.CREDENTIAL_UNAVAILABLE)
            return AppLockUnlockResult.CREDENTIAL_UNAVAILABLE
        } finally {
            workingPin.fill('\u0000')
        }
        if (!persist(attempt.state)) {
            state = lockedState(AppLockIssue.STORAGE_UNAVAILABLE)
            return AppLockUnlockResult.STORAGE_UNAVAILABLE
        }
        return when (attempt) {
            is PinAttemptResult.Accepted -> {
                state = AppLockState(
                    phase = AppLockPhase.UNLOCKED,
                    config = persistentState.config,
                    hasAuthenticatedSession = true,
                )
                AppLockUnlockResult.ACCEPTED
            }

            is PinAttemptResult.Rejected -> {
                state = lockedState(
                    issue = AppLockIssue.INCORRECT_PIN,
                    retryAfterMillis = attempt.retryAfterMillis,
                )
                AppLockUnlockResult.REJECTED
            }

            is PinAttemptResult.Throttled -> {
                state = lockedState(
                    issue = AppLockIssue.PIN_THROTTLED,
                    retryAfterMillis = attempt.retryAfterMillis,
                )
                AppLockUnlockResult.THROTTLED
            }

            is PinAttemptResult.Unavailable -> {
                state = lockedState(AppLockIssue.CREDENTIAL_UNAVAILABLE)
                AppLockUnlockResult.CREDENTIAL_UNAVAILABLE
            }
        }
    }

    fun beginBiometricAuthentication(
        availability: AppLockBiometricAvailability,
    ): AppLockBiometricAttempt? {
        if (
            state.phase != AppLockPhase.LOCKED ||
            !persistentState.config.biometricEnabled ||
            state.activeBiometricAttempt != null
        ) {
            return null
        }
        if (availability != AppLockBiometricAvailability.AVAILABLE) {
            state = lockedState(AppLockIssue.BIOMETRIC_UNAVAILABLE)
            return null
        }
        val attempt = AppLockBiometricAttempt(AppLockBiometricAttemptId(nextAttemptId()))
        state = state.copy(
            issue = null,
            retryAfterMillis = 0L,
            activeBiometricAttempt = attempt.id,
        )
        return attempt
    }

    /** Stale callbacks are ignored and can never unlock a newer process/session attempt. */
    fun onBiometricResult(
        attemptId: AppLockBiometricAttemptId,
        result: AppLockBiometricResult,
    ): Boolean {
        if (state.activeBiometricAttempt != attemptId || state.phase != AppLockPhase.LOCKED) {
            return false
        }
        when (result) {
            AppLockBiometricResult.Success -> state = AppLockState(
                phase = AppLockPhase.UNLOCKED,
                config = persistentState.config,
                hasAuthenticatedSession = true,
            )

            AppLockBiometricResult.Failed -> state = state.copy(
                issue = AppLockIssue.BIOMETRIC_FAILED,
            )

            AppLockBiometricResult.Cancelled -> state = lockedState(
                AppLockIssue.BIOMETRIC_CANCELLED,
            )

            is AppLockBiometricResult.Unavailable -> state = lockedState(
                AppLockIssue.BIOMETRIC_UNAVAILABLE,
            )

            AppLockBiometricResult.KeyInvalidated -> {
                val disabledBiometric = persistentState.copy(
                    config = persistentState.config.copy(biometricEnabled = false),
                )
                persistentState = disabledBiometric
                try {
                    stateStore.write(disabledBiometric)
                } catch (_: Exception) {
                    // Remain locked with PIN fallback; a later successful write can repair config.
                }
                state = lockedState(AppLockIssue.BIOMETRIC_KEY_INVALIDATED)
            }
        }
        return true
    }

    fun replacePinAfterAuthentication(
        newPin: CharArray,
        confirmation: CharArray,
    ): AppLockAuthenticatedChangeResult {
        if (!canMakeAuthenticatedChange()) {
            return AppLockAuthenticatedChangeResult.AUTHENTICATION_REQUIRED
        }
        val validation = PinPolicy.validate(newPin)
        if (validation != PinValidationResult.Valid) {
            return AppLockAuthenticatedChangeResult.INVALID_PIN
        }
        if (!pinsEqual(newPin, confirmation)) {
            return AppLockAuthenticatedChangeResult.CONFIRMATION_MISMATCH
        }

        val workingPin = newPin.copyOf()
        val credential = try {
            credentialService.createCredential(workingPin)
        } catch (_: Exception) {
            return AppLockAuthenticatedChangeResult.CREDENTIAL_UNAVAILABLE
        } finally {
            workingPin.fill('\u0000')
        }
        val updated = persistentState.copy(
            credential = credential,
            throttle = PinRetryThrottleState(),
        )
        if (!persist(updated)) return AppLockAuthenticatedChangeResult.STORAGE_UNAVAILABLE
        state = state.copy(config = updated.config, issue = null, retryAfterMillis = 0L)
        return AppLockAuthenticatedChangeResult.APPLIED
    }

    fun setAutoLockTimeoutAfterAuthentication(
        timeout: AutoLockTimeout,
    ): AppLockAuthenticatedChangeResult = updateConfigAfterAuthentication(
        persistentState.config.copy(autoLockTimeout = timeout),
    )

    fun enableBiometricAfterAuthentication(
        biometricController: AppLockBiometricController,
    ): AppLockAuthenticatedChangeResult {
        if (!canMakeAuthenticatedChange()) {
            return AppLockAuthenticatedChangeResult.AUTHENTICATION_REQUIRED
        }
        if (
            biometricController.availability() != AppLockBiometricAvailability.AVAILABLE ||
            !biometricController.recreateKeyAfterPinAuthentication()
        ) {
            return AppLockAuthenticatedChangeResult.BIOMETRIC_UNAVAILABLE
        }
        return updateConfigAfterAuthentication(
            persistentState.config.copy(biometricEnabled = true),
        )
    }

    fun disableBiometricAfterAuthentication(): AppLockAuthenticatedChangeResult =
        updateConfigAfterAuthentication(
            persistentState.config.copy(biometricEnabled = false),
        )

    fun disableAfterAuthentication(): AppLockAuthenticatedChangeResult {
        if (!canMakeAuthenticatedChange()) {
            return AppLockAuthenticatedChangeResult.AUTHENTICATION_REQUIRED
        }
        val disabled = AppLockPersistentState()
        if (!persist(disabled)) return AppLockAuthenticatedChangeResult.STORAGE_UNAVAILABLE
        backgroundedAtElapsedRealtimeMillis = null
        state = AppLockState(AppLockPhase.DISABLED, disabled.config)
        return AppLockAuthenticatedChangeResult.APPLIED
    }

    fun onAppBackgrounded(elapsedRealtimeMillis: Long) {
        backgroundedAtElapsedRealtimeMillis = elapsedRealtimeMillis
        if (state.phase == AppLockPhase.LOCKED && state.activeBiometricAttempt != null) {
            // A prompt callback delivered after backgrounding must not unlock protected content.
            state = lockedState()
        }
        if (
            persistentState.config.enabled &&
            persistentState.config.autoLockTimeout == AutoLockTimeout.IMMEDIATELY
        ) {
            lockNow()
        }
    }

    fun onAppForegrounded(elapsedRealtimeMillis: Long) {
        val backgroundedAt = backgroundedAtElapsedRealtimeMillis ?: return
        backgroundedAtElapsedRealtimeMillis = null
        if (
            state.phase == AppLockPhase.UNLOCKED &&
            AppLockSessionPolicy.shouldLockAfterBackground(
                config = persistentState.config,
                backgroundedAtElapsedRealtimeMillis = backgroundedAt,
                nowElapsedRealtimeMillis = elapsedRealtimeMillis,
            )
        ) {
            lockNow()
        }
    }

    fun lockNow() {
        if (!persistentState.config.enabled) return
        state = lockedState()
    }

    fun gate(entryPoint: AppLockProtectedEntryPoint): AppLockAccessGate = AppLockAccessGate(
        entryPoint = entryPoint,
        decision = if (state.phase == AppLockPhase.LOCKED) {
            AppLockAccessDecision.HOLD_UNTIL_UNLOCKED
        } else {
            AppLockAccessDecision.ALLOW
        },
    )

    private fun updateConfigAfterAuthentication(
        config: AppLockConfig,
    ): AppLockAuthenticatedChangeResult {
        if (!canMakeAuthenticatedChange()) {
            return AppLockAuthenticatedChangeResult.AUTHENTICATION_REQUIRED
        }
        val updated = persistentState.copy(config = config)
        if (!persist(updated)) return AppLockAuthenticatedChangeResult.STORAGE_UNAVAILABLE
        state = state.copy(config = config, issue = null)
        return AppLockAuthenticatedChangeResult.APPLIED
    }

    private fun canMakeAuthenticatedChange(): Boolean =
        state.phase == AppLockPhase.UNLOCKED && state.hasAuthenticatedSession

    private fun persist(updated: AppLockPersistentState): Boolean = try {
        stateStore.write(updated)
        persistentState = updated
        true
    } catch (_: Exception) {
        false
    }

    private fun lockedState(
        issue: AppLockIssue? = null,
        retryAfterMillis: Long = 0L,
    ): AppLockState = AppLockState(
        phase = AppLockPhase.LOCKED,
        config = persistentState.config,
        issue = issue,
        retryAfterMillis = retryAfterMillis,
    )

    private fun nextAttemptId(): Long {
        val id = nextBiometricAttemptId
        nextBiometricAttemptId = if (id == Long.MAX_VALUE) 1L else id + 1L
        return id
    }

    private fun pinsEqual(first: CharArray, second: CharArray): Boolean {
        var difference = first.size xor second.size
        val maximumLength = maxOf(first.size, second.size)
        for (index in 0 until maximumLength) {
            val left = first.getOrElse(index) { '\u0000' }
            val right = second.getOrElse(index) { '\u0000' }
            difference = difference or (left.code xor right.code)
        }
        return difference == 0
    }
}
