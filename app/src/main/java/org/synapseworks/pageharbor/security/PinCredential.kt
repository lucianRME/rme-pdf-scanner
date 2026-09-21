package org.synapseworks.pageharbor.security

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

sealed interface PinValidationResult {
    data object Valid : PinValidationResult
    data object TooShort : PinValidationResult
    data object ContainsNonDigit : PinValidationResult
}

object PinPolicy {
    const val MINIMUM_LENGTH = 6

    fun validate(pin: CharArray): PinValidationResult = when {
        pin.size < MINIMUM_LENGTH -> PinValidationResult.TooShort
        pin.any { it !in '0'..'9' } -> PinValidationResult.ContainsNonDigit
        else -> PinValidationResult.Valid
    }
}

data class PinKdfParameters(
    val algorithm: String = ALGORITHM_ID,
    val version: Int = CURRENT_VERSION,
    val iterations: Int = DEFAULT_ITERATIONS,
    val derivedKeyLengthBytes: Int = DEFAULT_DERIVED_KEY_LENGTH_BYTES,
) {
    init {
        require(algorithm.isNotBlank()) { "KDF algorithm must not be blank" }
        require(version > 0) { "KDF version must be positive" }
        require(iterations > 0) { "KDF iterations must be positive" }
        require(derivedKeyLengthBytes in 16..64) { "Derived key length must be 16..64 bytes" }
    }

    fun isSupported(): Boolean = algorithm == ALGORITHM_ID && version == CURRENT_VERSION

    /** Stable string mapping suitable for preferences or a future portable metadata format. */
    fun toPortableMap(): Map<String, String> = linkedMapOf(
        PORTABLE_ALGORITHM to algorithm,
        PORTABLE_VERSION to version.toString(),
        PORTABLE_ITERATIONS to iterations.toString(),
        PORTABLE_DERIVED_KEY_BYTES to derivedKeyLengthBytes.toString(),
    )

    companion object {
        const val ALGORITHM_ID = "PBKDF2-HMAC-SHA256"
        const val CURRENT_VERSION = 1
        const val DEFAULT_ITERATIONS = 310_000
        const val DEFAULT_DERIVED_KEY_LENGTH_BYTES = 32
        const val MINIMUM_ENROLLMENT_ITERATIONS = 100_000

        const val PORTABLE_ALGORITHM = "algorithm"
        const val PORTABLE_VERSION = "version"
        const val PORTABLE_ITERATIONS = "iterations"
        const val PORTABLE_DERIVED_KEY_BYTES = "derivedKeyBytes"

        fun fromPortableMap(values: Map<String, String>): PinKdfParameters? {
            val algorithm = values[PORTABLE_ALGORITHM] ?: return null
            val version = values[PORTABLE_VERSION]?.toIntOrNull() ?: return null
            val iterations = values[PORTABLE_ITERATIONS]?.toIntOrNull() ?: return null
            val derivedKeyBytes = values[PORTABLE_DERIVED_KEY_BYTES]?.toIntOrNull() ?: return null

            return runCatching {
                PinKdfParameters(
                    algorithm = algorithm,
                    version = version,
                    iterations = iterations,
                    derivedKeyLengthBytes = derivedKeyBytes,
                )
            }.getOrNull()
        }
    }
}

object PinKeyDerivation {
    fun derive(
        pin: CharArray,
        salt: ByteArray,
        parameters: PinKdfParameters,
    ): ByteArray {
        require(parameters.isSupported()) { "Unsupported PIN KDF" }
        require(salt.isNotEmpty()) { "KDF salt must not be empty" }

        val specification = PBEKeySpec(
            pin,
            salt,
            parameters.iterations,
            parameters.derivedKeyLengthBytes * Byte.SIZE_BITS,
        )
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(specification)
                .encoded
        } finally {
            specification.clearPassword()
        }
    }
}

enum class PepperKeyAccess {
    CREATE_IF_MISSING,
    REQUIRE_EXISTING,
}

/** Performs HMAC without exposing or exporting the pepper key. */
fun interface NonExportableAppLockPepper {
    fun hmacSha256(message: ByteArray, keyAccess: PepperKeyAccess): ByteArray
}

class AppLockPepperUnavailableException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

class PinCredentialRecord(
    salt: ByteArray,
    val kdfParameters: PinKdfParameters,
    verifier: ByteArray,
) {
    private val saltValue = salt.copyOf()
    private val verifierValue = verifier.copyOf()

    init {
        require(saltValue.size >= MINIMUM_SALT_BYTES) { "PIN salt is too short" }
        require(verifierValue.size == SHA_256_BYTES) { "PIN verifier must be SHA-256 length" }
    }

    val salt: ByteArray
        get() = saltValue.copyOf()

    val verifier: ByteArray
        get() = verifierValue.copyOf()

    override fun equals(other: Any?): Boolean =
        other is PinCredentialRecord &&
            saltValue.contentEquals(other.saltValue) &&
            kdfParameters == other.kdfParameters &&
            verifierValue.contentEquals(other.verifierValue)

    override fun hashCode(): Int {
        var result = saltValue.contentHashCode()
        result = 31 * result + kdfParameters.hashCode()
        result = 31 * result + verifierValue.contentHashCode()
        return result
    }

    companion object {
        const val MINIMUM_SALT_BYTES = 16
        const val DEFAULT_SALT_BYTES = 32
        const val SHA_256_BYTES = 32
    }
}

interface PinCredentialService {
    fun createCredential(
        pin: CharArray,
        parameters: PinKdfParameters = PinKdfParameters(),
    ): PinCredentialRecord

    fun verify(pin: CharArray, credential: PinCredentialRecord): Boolean
}

class PinVerifier(
    private val pepper: NonExportableAppLockPepper,
    private val secureRandom: SecureRandom = SecureRandom(),
) : PinCredentialService {
    override fun createCredential(
        pin: CharArray,
        parameters: PinKdfParameters,
    ): PinCredentialRecord {
        require(PinPolicy.validate(pin) == PinValidationResult.Valid) {
            "PIN must contain at least ${PinPolicy.MINIMUM_LENGTH} digits"
        }
        require(parameters.isSupported()) { "Unsupported PIN KDF" }
        require(parameters.iterations >= PinKdfParameters.MINIMUM_ENROLLMENT_ITERATIONS) {
            "PIN KDF iteration count is below the enrollment minimum"
        }

        val salt = ByteArray(PinCredentialRecord.DEFAULT_SALT_BYTES).also(secureRandom::nextBytes)
        val verifier = calculateVerifier(
            pin = pin,
            salt = salt,
            parameters = parameters,
            keyAccess = PepperKeyAccess.CREATE_IF_MISSING,
        )
        return PinCredentialRecord(salt, parameters, verifier)
    }

    override fun verify(pin: CharArray, credential: PinCredentialRecord): Boolean {
        if (PinPolicy.validate(pin) != PinValidationResult.Valid) return false
        if (!credential.kdfParameters.isSupported()) return false

        val candidate = calculateVerifier(
            pin = pin,
            salt = credential.salt,
            parameters = credential.kdfParameters,
            keyAccess = PepperKeyAccess.REQUIRE_EXISTING,
        )
        return try {
            MessageDigest.isEqual(credential.verifier, candidate)
        } finally {
            candidate.fill(0)
        }
    }

    private fun calculateVerifier(
        pin: CharArray,
        salt: ByteArray,
        parameters: PinKdfParameters,
        keyAccess: PepperKeyAccess,
    ): ByteArray {
        val derivedKey = PinKeyDerivation.derive(pin, salt, parameters)
        val verifierInput = ByteBuffer.allocate(VERIFIER_DOMAIN.size + Int.SIZE_BYTES + derivedKey.size)
            .put(VERIFIER_DOMAIN)
            .putInt(parameters.version)
            .put(derivedKey)
            .array()
        return try {
            pepper.hmacSha256(verifierInput, keyAccess)
        } finally {
            derivedKey.fill(0)
            verifierInput.fill(0)
        }
    }

    private companion object {
        val VERIFIER_DOMAIN = "RME_APP_LOCK_PIN_VERIFIER"
            .toByteArray(StandardCharsets.UTF_8)
    }
}
