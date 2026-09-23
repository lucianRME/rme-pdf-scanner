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
)

/** Process-local lock state; unlocked state is never persisted. */
data class AppLockRuntimeState(
    val isLocked: Boolean,
)

object AppLockSessionPolicy {
    fun coldStart(config: AppLockConfig): AppLockRuntimeState = AppLockRuntimeState(
        isLocked = config.enabled,
    )

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
            recovery = "Your device manages authentication. Keep a verified backup.",
        )
    }
}
