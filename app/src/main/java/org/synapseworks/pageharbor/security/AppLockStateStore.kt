package org.synapseworks.pageharbor.security

import android.content.Context
import android.content.SharedPreferences
import java.util.Base64

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
        AppLockPersistentState(
            config = AppLockConfig(
                enabled = preferences.getBoolean(AppLockPreferenceSchema.ENABLED, false),
                autoLockTimeout = AutoLockTimeout.fromStorageValue(
                    preferences.getString(AppLockPreferenceSchema.AUTO_LOCK_TIMEOUT, null),
                ),
                biometricEnabled = preferences.getBoolean(
                    AppLockPreferenceSchema.BIOMETRIC_ENABLED,
                    false,
                ),
            ),
            credential = readCredential(preferences),
            throttle = PinRetryThrottleState(
                failedAttemptCount = preferences.getInt(
                    AppLockPreferenceSchema.FAILED_ATTEMPT_COUNT,
                    0,
                ).coerceAtLeast(0),
                retryNotBeforeWallTimeMillis = preferences.getLong(
                    AppLockPreferenceSchema.RETRY_NOT_BEFORE_WALL_TIME,
                    0L,
                ).coerceAtLeast(0L),
                lastObservedWallTimeMillis = preferences.getLong(
                    AppLockPreferenceSchema.LAST_OBSERVED_WALL_TIME,
                    0L,
                ).coerceAtLeast(0L),
            ),
        )
    }

    override fun write(state: AppLockPersistentState) = synchronized(preferences) {
        val editor = preferences.edit()
            .putBoolean(AppLockPreferenceSchema.ENABLED, state.config.enabled)
            .putString(
                AppLockPreferenceSchema.AUTO_LOCK_TIMEOUT,
                state.config.autoLockTimeout.storageValue,
            )
            .putBoolean(
                AppLockPreferenceSchema.BIOMETRIC_ENABLED,
                state.config.biometricEnabled,
            )
            .putInt(
                AppLockPreferenceSchema.FAILED_ATTEMPT_COUNT,
                state.throttle.failedAttemptCount.coerceAtLeast(0),
            )
            .putLong(
                AppLockPreferenceSchema.RETRY_NOT_BEFORE_WALL_TIME,
                state.throttle.retryNotBeforeWallTimeMillis.coerceAtLeast(0L),
            )
            .putLong(
                AppLockPreferenceSchema.LAST_OBSERVED_WALL_TIME,
                state.throttle.lastObservedWallTimeMillis.coerceAtLeast(0L),
            )

        val credential = state.credential
        if (credential == null) {
            AppLockPreferenceSchema.CREDENTIAL_KEYS.forEach { key -> editor.remove(key) }
        } else {
            val kdf = credential.kdfParameters
            editor
                .putString(AppLockPreferenceSchema.KDF_ALGORITHM, kdf.algorithm)
                .putInt(AppLockPreferenceSchema.KDF_VERSION, kdf.version)
                .putInt(AppLockPreferenceSchema.KDF_ITERATIONS, kdf.iterations)
                .putInt(AppLockPreferenceSchema.KDF_DERIVED_KEY_BYTES, kdf.derivedKeyLengthBytes)
                .putString(
                    AppLockPreferenceSchema.SALT,
                    Base64.getEncoder().encodeToString(credential.salt),
                )
                .putString(
                    AppLockPreferenceSchema.VERIFIER,
                    Base64.getEncoder().encodeToString(credential.verifier),
                )
        }

        check(editor.commit()) { "Unable to persist app-lock state" }
    }

    private fun readCredential(preferences: SharedPreferences): PinCredentialRecord? = runCatching {
        val portableKdf = mapOf(
            PinKdfParameters.PORTABLE_ALGORITHM to
                (preferences.getString(AppLockPreferenceSchema.KDF_ALGORITHM, null)
                    ?: return null),
            PinKdfParameters.PORTABLE_VERSION to
                preferences.getInt(AppLockPreferenceSchema.KDF_VERSION, 0).toString(),
            PinKdfParameters.PORTABLE_ITERATIONS to
                preferences.getInt(AppLockPreferenceSchema.KDF_ITERATIONS, 0).toString(),
            PinKdfParameters.PORTABLE_DERIVED_KEY_BYTES to
                preferences.getInt(AppLockPreferenceSchema.KDF_DERIVED_KEY_BYTES, 0).toString(),
        )
        val parameters = PinKdfParameters.fromPortableMap(portableKdf)
            ?.takeIf(PinKdfParameters::isSupported)
            ?: return null
        val salt = Base64.getDecoder().decode(
            preferences.getString(AppLockPreferenceSchema.SALT, null) ?: return null,
        )
        val verifier = Base64.getDecoder().decode(
            preferences.getString(AppLockPreferenceSchema.VERIFIER, null) ?: return null,
        )
        PinCredentialRecord(salt, parameters, verifier)
    }.getOrNull()

    private companion object {
        const val PREFERENCES_NAME = "rme_app_lock_v1"
    }
}

/** Explicit allow-list: neither the PIN nor process-local unlocked state has a storage key. */
internal object AppLockPreferenceSchema {
    const val ENABLED = "enabled"
    const val AUTO_LOCK_TIMEOUT = "auto_lock_timeout"
    const val BIOMETRIC_ENABLED = "biometric_enabled"
    const val KDF_ALGORITHM = "kdf_algorithm"
    const val KDF_VERSION = "kdf_version"
    const val KDF_ITERATIONS = "kdf_iterations"
    const val KDF_DERIVED_KEY_BYTES = "kdf_derived_key_bytes"
    const val SALT = "salt_base64"
    const val VERIFIER = "verifier_base64"
    const val FAILED_ATTEMPT_COUNT = "failed_attempt_count"
    const val RETRY_NOT_BEFORE_WALL_TIME = "retry_not_before_wall_time"
    const val LAST_OBSERVED_WALL_TIME = "last_observed_wall_time"

    val CREDENTIAL_KEYS = setOf(
        KDF_ALGORITHM,
        KDF_VERSION,
        KDF_ITERATIONS,
        KDF_DERIVED_KEY_BYTES,
        SALT,
        VERIFIER,
    )

    val ALL_KEYS = CREDENTIAL_KEYS + setOf(
        ENABLED,
        AUTO_LOCK_TIMEOUT,
        BIOMETRIC_ENABLED,
        FAILED_ATTEMPT_COUNT,
        RETRY_NOT_BEFORE_WALL_TIME,
        LAST_OBSERVED_WALL_TIME,
    )
}
