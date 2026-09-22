package org.synapseworks.pageharbor.security

enum class AppLockPhase {
    DISABLED,
    LOCKED,
    UNLOCKED,
}

enum class AppLockIssue {
    AUTHENTICATION_CANCELLED,
    AUTHENTICATION_FAILED,
    AUTHENTICATION_UNAVAILABLE,
    NO_SECURE_DEVICE_LOCK,
    STORAGE_UNAVAILABLE,
}

data class AppLockState(
    val phase: AppLockPhase,
    val config: AppLockConfig,
    val issue: AppLockIssue? = null,
    val activeAuthenticationAttempt: AppLockAuthenticationAttemptId? = null,
    /** Process-local proof that settings changes were made from an authenticated session. */
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
    data object DeviceAuthenticationUnavailable : AppLockSetupResult
    data object StorageUnavailable : AppLockSetupResult
}

enum class AppLockUnlockResult {
    ACCEPTED,
    ALREADY_UNLOCKED,
    NOT_CONFIGURED,
    CANCELLED,
    FAILED,
    AUTHENTICATION_UNAVAILABLE,
    STORAGE_UNAVAILABLE,
}

enum class AppLockAuthenticatedChangeResult {
    APPLIED,
    AUTHENTICATION_REQUIRED,
    DEVICE_AUTHENTICATION_UNAVAILABLE,
    STORAGE_UNAVAILABLE,
}

/** Pure process-local state machine. Device authentication is performed only by AndroidX. */
class AppLockRuntimeController(
    private val stateStore: AppLockStateStore,
) {
    private var persistentState: AppLockPersistentState
    private var backgroundedAtElapsedRealtimeMillis: Long? = null
    private var nextAuthenticationAttemptId = 1L

    var state: AppLockState
        private set

    init {
        val loaded = runCatching(stateStore::read)
        persistentState = loaded.getOrElse { AppLockPersistentState() }
        val coldStart = AppLockSessionPolicy.coldStart(persistentState.config)
        state = AppLockState(
            phase = if (coldStart.isLocked) AppLockPhase.LOCKED else AppLockPhase.DISABLED,
            config = persistentState.config,
            issue = if (loaded.isFailure) AppLockIssue.STORAGE_UNAVAILABLE else null,
        )
    }

    fun setup(
        timeout: AutoLockTimeout = AutoLockTimeout.DEFAULT,
        availability: AppLockAuthenticationAvailability,
    ): AppLockSetupResult {
        if (persistentState.config.enabled) {
            return AppLockSetupResult.Success(state)
        }
        if (availability != AppLockAuthenticationAvailability.AVAILABLE) {
            state = state.copy(
                issue = if (availability == AppLockAuthenticationAvailability.NO_SECURE_DEVICE_LOCK) {
                    AppLockIssue.NO_SECURE_DEVICE_LOCK
                } else {
                    AppLockIssue.AUTHENTICATION_UNAVAILABLE
                },
            )
            return AppLockSetupResult.DeviceAuthenticationUnavailable
        }
        val updated = AppLockPersistentState(
            config = AppLockConfig(enabled = true, autoLockTimeout = timeout),
        )
        if (!persist(updated)) {
            state = state.copy(issue = AppLockIssue.STORAGE_UNAVAILABLE)
            return AppLockSetupResult.StorageUnavailable
        }
        state = AppLockState(
            phase = AppLockPhase.UNLOCKED,
            config = updated.config,
            hasAuthenticatedSession = true,
        )
        return AppLockSetupResult.Success(state)
    }

    fun beginAuthentication(
        availability: AppLockAuthenticationAvailability,
    ): AppLockAuthenticationAttempt? {
        if (state.phase != AppLockPhase.LOCKED || state.activeAuthenticationAttempt != null) {
            return null
        }
        if (availability != AppLockAuthenticationAvailability.AVAILABLE) {
            state = lockedState(
                if (availability == AppLockAuthenticationAvailability.NO_SECURE_DEVICE_LOCK) {
                    AppLockIssue.NO_SECURE_DEVICE_LOCK
                } else {
                    AppLockIssue.AUTHENTICATION_UNAVAILABLE
                },
            )
            return null
        }
        val attempt = AppLockAuthenticationAttempt(
            AppLockAuthenticationAttemptId(nextAttemptId()),
        )
        state = state.copy(
            issue = null,
            activeAuthenticationAttempt = attempt.id,
        )
        return attempt
    }

    /** Stale callbacks are ignored and can never unlock a newer process/session attempt. */
    fun onAuthenticationResult(
        attemptId: AppLockAuthenticationAttemptId,
        result: AppLockAuthenticationResult,
    ): Boolean {
        if (state.activeAuthenticationAttempt != attemptId || state.phase != AppLockPhase.LOCKED) {
            return false
        }
        state = when (result) {
            AppLockAuthenticationResult.Success -> AppLockState(
                phase = AppLockPhase.UNLOCKED,
                config = persistentState.config,
                hasAuthenticatedSession = true,
            )
            AppLockAuthenticationResult.Failed -> state.copy(
                issue = AppLockIssue.AUTHENTICATION_FAILED,
                activeAuthenticationAttempt = attemptId,
            )
            AppLockAuthenticationResult.Cancelled -> lockedState(
                AppLockIssue.AUTHENTICATION_CANCELLED,
            )
            is AppLockAuthenticationResult.Unavailable -> lockedState(
                if (result.availability == AppLockAuthenticationAvailability.NO_SECURE_DEVICE_LOCK) {
                    AppLockIssue.NO_SECURE_DEVICE_LOCK
                } else {
                    AppLockIssue.AUTHENTICATION_UNAVAILABLE
                },
            )
        }
        return true
    }

    fun setAutoLockTimeoutAfterAuthentication(
        timeout: AutoLockTimeout,
    ): AppLockAuthenticatedChangeResult = updateConfigAfterAuthentication(
        persistentState.config.copy(autoLockTimeout = timeout),
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
        if (state.phase == AppLockPhase.LOCKED && state.activeAuthenticationAttempt != null) {
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

    private fun lockedState(issue: AppLockIssue? = null): AppLockState = AppLockState(
        phase = AppLockPhase.LOCKED,
        config = persistentState.config,
        issue = issue,
    )

    private fun nextAttemptId(): Long {
        val id = nextAuthenticationAttemptId
        nextAuthenticationAttemptId = if (id == Long.MAX_VALUE) 1L else id + 1L
        return id
    }
}
