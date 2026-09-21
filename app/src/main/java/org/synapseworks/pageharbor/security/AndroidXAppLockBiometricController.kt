package org.synapseworks.pageharbor.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

internal sealed interface AppLockBiometricCipherPreparation {
    data class Ready(val cipher: Cipher) : AppLockBiometricCipherPreparation
    data object KeyInvalidated : AppLockBiometricCipherPreparation
    data object Unavailable : AppLockBiometricCipherPreparation
}

internal interface AppLockBiometricCryptoProvider {
    fun prepareCipher(): AppLockBiometricCipherPreparation

    fun recreateKey(): Boolean
}

/** Device-local Keystore key used only to bind a strong biometric prompt to a crypto operation. */
internal class AndroidKeystoreAppLockBiometricCryptoProvider(
    private val alias: String = DEFAULT_ALIAS,
) : AppLockBiometricCryptoProvider {
    private val monitor = Any()

    override fun prepareCipher(): AppLockBiometricCipherPreparation = synchronized(monitor) {
        try {
            val keyStore = loadKeyStore()
            // A missing key must never be silently enrolled during unlock. Creating/replacing the
            // key is allowed only through recreateKey(), after the user has authenticated by PIN.
            val key = existingKey(keyStore)
                ?: return@synchronized AppLockBiometricCipherPreparation.KeyInvalidated
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            AppLockBiometricCipherPreparation.Ready(cipher)
        } catch (_: KeyPermanentlyInvalidatedException) {
            AppLockBiometricCipherPreparation.KeyInvalidated
        } catch (_: Exception) {
            AppLockBiometricCipherPreparation.Unavailable
        }
    }

    override fun recreateKey(): Boolean = synchronized(monitor) {
        runCatching {
            val keyStore = loadKeyStore()
            if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
            createKey()
        }.isSuccess
    }

    private fun loadKeyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
        load(null)
    }

    private fun existingKey(keyStore: KeyStore): SecretKey? {
        if (!keyStore.containsAlias(alias)) return null
        return (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
    }

    private fun createKey(): SecretKey {
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_CBC)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_PKCS7)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(
                0,
                KeyProperties.AUTH_BIOMETRIC_STRONG,
            )
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(builder.build())
            generateKey()
        }
    }

    private companion object {
        const val DEFAULT_ALIAS = "rme_app_lock_biometric_crypto_v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/CBC/PKCS7Padding"
    }
}

/**
 * AndroidX BiometricPrompt adapter using only BIOMETRIC_STRONG and a Keystore-backed CryptoObject.
 * It receives success/failure categories only; biometric templates never enter the application.
 */
class AndroidXAppLockBiometricController internal constructor(
    activity: FragmentActivity,
    promptTitle: CharSequence,
    pinFallbackLabel: CharSequence,
    private val cryptoProvider: AppLockBiometricCryptoProvider,
) : AppLockBiometricController {
    constructor(
        activity: FragmentActivity,
        promptTitle: CharSequence,
        pinFallbackLabel: CharSequence,
    ) : this(
        activity = activity,
        promptTitle = promptTitle,
        pinFallbackLabel = pinFallbackLabel,
        cryptoProvider = AndroidKeystoreAppLockBiometricCryptoProvider(),
    )

    private val biometricManager = BiometricManager.from(activity)
    private val callbackMonitor = Any()
    private var activeCallback: AppLockBiometricResultCallback? = null
    private var authenticationInProgress = false

    private val promptInfo = BiometricPrompt.PromptInfo.Builder()
        .setTitle(promptTitle)
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        .setNegativeButtonText(pinFallbackLabel)
        .build()

    private val biometricPrompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(
                result: BiometricPrompt.AuthenticationResult,
            ) {
                val cipher = result.cryptoObject?.cipher
                if (cipher == null) {
                    finish(
                        AppLockBiometricResult.Unavailable(
                            AppLockBiometricAvailability.TEMPORARILY_UNAVAILABLE,
                        ),
                    )
                    return
                }
                try {
                    cipher.doFinal(AUTHENTICATION_PROOF).fill(0)
                    finish(AppLockBiometricResult.Success)
                } catch (_: KeyPermanentlyInvalidatedException) {
                    finish(AppLockBiometricResult.KeyInvalidated)
                } catch (_: Exception) {
                    finish(
                        AppLockBiometricResult.Unavailable(
                            AppLockBiometricAvailability.TEMPORARILY_UNAVAILABLE,
                        ),
                    )
                }
            }

            override fun onAuthenticationFailed() {
                synchronized(callbackMonitor) {
                    activeCallback
                }?.onResult(AppLockBiometricResult.Failed)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                finish(biometricResultForPromptError(errorCode))
            }
        },
    )

    override fun availability(): AppLockBiometricAvailability = biometricAvailabilityFor(
        biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG),
    )

    override fun authenticate(callback: AppLockBiometricResultCallback) {
        val alreadyInProgress = synchronized(callbackMonitor) {
            if (authenticationInProgress) {
                true
            } else {
                authenticationInProgress = true
                false
            }
        }
        if (alreadyInProgress) {
            callback.onResult(
                AppLockBiometricResult.Unavailable(
                    AppLockBiometricAvailability.TEMPORARILY_UNAVAILABLE,
                ),
            )
            return
        }
        val availability = availability()
        if (availability != AppLockBiometricAvailability.AVAILABLE) {
            clearInProgress()
            callback.onResult(AppLockBiometricResult.Unavailable(availability))
            return
        }
        when (val preparation = cryptoProvider.prepareCipher()) {
            AppLockBiometricCipherPreparation.KeyInvalidated -> {
                clearInProgress()
                callback.onResult(AppLockBiometricResult.KeyInvalidated)
            }

            AppLockBiometricCipherPreparation.Unavailable -> {
                clearInProgress()
                callback.onResult(
                    AppLockBiometricResult.Unavailable(
                        AppLockBiometricAvailability.TEMPORARILY_UNAVAILABLE,
                    ),
                )
            }

            is AppLockBiometricCipherPreparation.Ready -> {
                synchronized(callbackMonitor) { activeCallback = callback }
                try {
                    biometricPrompt.authenticate(
                        promptInfo,
                        BiometricPrompt.CryptoObject(preparation.cipher),
                    )
                } catch (_: RuntimeException) {
                    finish(
                        AppLockBiometricResult.Unavailable(
                            AppLockBiometricAvailability.TEMPORARILY_UNAVAILABLE,
                        ),
                    )
                }
            }
        }
    }

    override fun cancel() {
        biometricPrompt.cancelAuthentication()
    }

    override fun recreateKeyAfterPinAuthentication(): Boolean = cryptoProvider.recreateKey()

    private fun finish(result: AppLockBiometricResult) {
        val callback = synchronized(callbackMonitor) {
            activeCallback.also {
                activeCallback = null
                authenticationInProgress = false
            }
        }
        callback?.onResult(result)
    }

    private fun clearInProgress() {
        synchronized(callbackMonitor) {
            authenticationInProgress = false
        }
    }

    private companion object {
        val AUTHENTICATION_PROOF = "RME_APP_LOCK_BIOMETRIC_PROOF".encodeToByteArray()
    }
}

internal fun biometricAvailabilityFor(managerResult: Int): AppLockBiometricAvailability =
    when (managerResult) {
        BiometricManager.BIOMETRIC_SUCCESS -> AppLockBiometricAvailability.AVAILABLE
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> AppLockBiometricAvailability.NO_HARDWARE
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> AppLockBiometricAvailability.NONE_ENROLLED
        BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE ->
            AppLockBiometricAvailability.TEMPORARILY_UNAVAILABLE

        else -> AppLockBiometricAvailability.UNSUPPORTED
    }

internal fun biometricResultForPromptError(errorCode: Int): AppLockBiometricResult =
    when (errorCode) {
        BiometricPrompt.ERROR_CANCELED,
        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
        BiometricPrompt.ERROR_USER_CANCELED,
        -> AppLockBiometricResult.Cancelled

        BiometricPrompt.ERROR_NO_BIOMETRICS -> AppLockBiometricResult.Unavailable(
            AppLockBiometricAvailability.NONE_ENROLLED,
        )

        BiometricPrompt.ERROR_HW_NOT_PRESENT -> AppLockBiometricResult.Unavailable(
            AppLockBiometricAvailability.NO_HARDWARE,
        )

        else -> AppLockBiometricResult.Unavailable(
            AppLockBiometricAvailability.TEMPORARILY_UNAVAILABLE,
        )
    }
