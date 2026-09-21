package org.synapseworks.pageharbor.security

/** User-selectable inactivity window before protected content is locked again. */
enum class AutoLockTimeout(
    val storageValue: String,
    val timeoutMillis: Long,
) {
    IMMEDIATELY("immediately", 0L),
    ONE_MINUTE("one_minute", 60_000L),
    FIVE_MINUTES("five_minutes", 5L * 60_000L),
    ;

    companion object {
        val DEFAULT: AutoLockTimeout = ONE_MINUTE

        fun fromStorageValue(value: String?): AutoLockTimeout =
            entries.firstOrNull { it.storageValue == value } ?: DEFAULT
    }
}

data class AppLockConfig(
    val enabled: Boolean = false,
    val autoLockTimeout: AutoLockTimeout = AutoLockTimeout.DEFAULT,
    val biometricEnabled: Boolean = false,
)

/**
 * Process-local lock state. This type is deliberately separate from [AppLockPersistentState] so
 * an unlocked session can never be written to disk by the app-lock state store.
 */
data class AppLockRuntimeState(
    val isLocked: Boolean,
)

object AppLockSessionPolicy {
    /** Every process start begins locked when app lock is enabled. */
    fun coldStart(config: AppLockConfig): AppLockRuntimeState = AppLockRuntimeState(
        isLocked = config.enabled,
    )

    /**
     * Uses a monotonic timestamp supplied by the Android layer. A backwards monotonic reading is
     * treated conservatively as requiring a lock.
     */
    fun shouldLockAfterBackground(
        config: AppLockConfig,
        backgroundedAtElapsedRealtimeMillis: Long,
        nowElapsedRealtimeMillis: Long,
    ): Boolean {
        if (!config.enabled) return false
        if (config.autoLockTimeout == AutoLockTimeout.IMMEDIATELY) return true
        if (backgroundedAtElapsedRealtimeMillis < 0L || nowElapsedRealtimeMillis < 0L) return true
        if (nowElapsedRealtimeMillis < backgroundedAtElapsedRealtimeMillis) return true

        return nowElapsedRealtimeMillis - backgroundedAtElapsedRealtimeMillis >=
            config.autoLockTimeout.timeoutMillis
    }
}

data class AppLockDisclosure(
    val accessProtection: String,
    val atRestLimitation: String,
    val recovery: String,
) {
    companion object {
        val DEFAULT = AppLockDisclosure(
            accessProtection = "App lock protects access through the RME app interface.",
            atRestLimitation = "It does not encrypt the local RME library at rest.",
            recovery = "RME cannot recover your PIN. Keep a verified backup.",
        )
    }
}

enum class AppLockRecoveryPath {
    AUTHENTICATED_PIN_CHANGE,
    AUTHENTICATED_BIOMETRIC_PIN_REPLACEMENT,
    NO_REMOTE_RECOVERY,
}

data class AppLockRecoveryModel(
    val biometricUnlockAvailable: Boolean,
) {
    val availablePaths: Set<AppLockRecoveryPath>
        get() = buildSet {
            add(AppLockRecoveryPath.AUTHENTICATED_PIN_CHANGE)
            if (biometricUnlockAvailable) {
                add(AppLockRecoveryPath.AUTHENTICATED_BIOMETRIC_PIN_REPLACEMENT)
            }
            add(AppLockRecoveryPath.NO_REMOTE_RECOVERY)
        }

    val deletesDocumentsAfterFailedAttempts: Boolean = false
    val hasMasterRecoveryKey: Boolean = false
}
