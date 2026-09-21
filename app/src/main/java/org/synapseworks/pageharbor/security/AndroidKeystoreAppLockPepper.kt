package org.synapseworks.pageharbor.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * Device-local, non-exportable HMAC pepper. Biometric-gated keys are intentionally separate and
 * will be introduced with the BiometricPrompt integration.
 */
class AndroidKeystoreAppLockPepper(
    private val alias: String = DEFAULT_ALIAS,
) : NonExportableAppLockPepper {
    private val monitor = Any()

    override fun hmacSha256(
        message: ByteArray,
        keyAccess: PepperKeyAccess,
    ): ByteArray = synchronized(monitor) {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val key = existingKey(keyStore) ?: when (keyAccess) {
                PepperKeyAccess.CREATE_IF_MISSING -> createKey()
                PepperKeyAccess.REQUIRE_EXISTING -> throw AppLockPepperUnavailableException(
                    "The app-lock Keystore key is unavailable",
                )
            }
            Mac.getInstance(HMAC_SHA_256).run {
                init(key)
                doFinal(message)
            }
        } catch (error: AppLockPepperUnavailableException) {
            throw error
        } catch (error: Exception) {
            throw AppLockPepperUnavailableException(
                "The app-lock Keystore operation failed",
                error,
            )
        }
    }

    private fun existingKey(keyStore: KeyStore): SecretKey? {
        if (!keyStore.containsAlias(alias)) return null
        return (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
            ?: throw AppLockPepperUnavailableException(
                "The app-lock Keystore alias does not contain an HMAC key",
            )
    }

    private fun createKey(): SecretKey {
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_HMAC_SHA256,
            ANDROID_KEYSTORE,
        )
        val specification = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
        )
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setKeySize(KEY_SIZE_BITS)
            .build()
        generator.init(specification)
        return generator.generateKey()
    }

    companion object {
        const val DEFAULT_ALIAS = "rme_app_lock_pin_pepper_v1"

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val HMAC_SHA_256 = "HmacSHA256"
        private const val KEY_SIZE_BITS = 256
    }
}
