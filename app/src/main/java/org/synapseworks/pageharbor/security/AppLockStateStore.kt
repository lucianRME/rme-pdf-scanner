package org.synapseworks.pageharbor.security

import android.content.Context
import android.content.SharedPreferences

interface AppLockStateStore {
    fun read(): AppLockPersistentState

    /** Security-critical writes are synchronous and complete before this call returns. */
    fun write(state: AppLockPersistentState)
}

class SharedPreferencesAppLockStateStore(context: Context) : AppLockStateStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun read(): AppLockPersistentState = synchronized(preferences) {
        val state = AppLockPersistentState(
            config = AppLockConfig(
                enabled = preferences.getBoolean(AppLockPreferenceSchema.ENABLED, false),
                autoLockTimeout = AutoLockTimeout.fromStorageValue(
                    preferences.getString(AppLockPreferenceSchema.AUTO_LOCK_TIMEOUT, null),
                ),
            ),
        )
        if (AppLockPreferenceSchema.LEGACY_KEYS.any(preferences::contains)) {
            val editor = preferences.edit()
            AppLockPreferenceSchema.LEGACY_KEYS.forEach(editor::remove)
            check(editor.commit()) { "Unable to remove legacy app-lock credential state" }
        }
        state
    }

    override fun write(state: AppLockPersistentState) = synchronized(preferences) {
        // Remove legacy RME-PIN verifier/throttle fields as part of the migration to Android
        // device authentication. Portable backup passwords are stored by their own subsystem.
        val editor = preferences.edit()
            .putBoolean(AppLockPreferenceSchema.ENABLED, state.config.enabled)
            .putString(
                AppLockPreferenceSchema.AUTO_LOCK_TIMEOUT,
                state.config.autoLockTimeout.storageValue,
            )
        AppLockPreferenceSchema.LEGACY_KEYS.forEach(editor::remove)
        check(editor.commit()) { "Unable to persist app-lock state" }
    }

    private companion object {
        const val PREFERENCES_NAME = "rme_app_lock_v1"
    }
}

/** Explicit allow-list: device credentials and unlocked state never enter app preferences. */
internal object AppLockPreferenceSchema {
    const val ENABLED = "enabled"
    const val AUTO_LOCK_TIMEOUT = "auto_lock_timeout"

    val LEGACY_KEYS = setOf(
        "biometric_enabled",
        "kdf_algorithm",
        "kdf_version",
        "kdf_iterations",
        "kdf_derived_key_bytes",
        "salt_base64",
        "verifier_base64",
        "failed_attempt_count",
        "retry_not_before_wall_time",
        "last_observed_wall_time",
    )
}

data class AppLockPersistentState(
    val config: AppLockConfig = AppLockConfig(),
)
