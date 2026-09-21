package org.synapseworks.pageharbor.security

import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PinCredentialTest {
    @Test
    fun pinPolicyRequiresSixOrMoreAsciiDigits() {
        assertEquals(PinValidationResult.TooShort, PinPolicy.validate("12345".toCharArray()))
        assertEquals(PinValidationResult.Valid, PinPolicy.validate("123456".toCharArray()))
        assertEquals(PinValidationResult.Valid, PinPolicy.validate("1234567890".toCharArray()))
        assertEquals(
            PinValidationResult.ContainsNonDigit,
            PinPolicy.validate("12345a".toCharArray()),
        )
        assertEquals(
            PinValidationResult.ContainsNonDigit,
            PinPolicy.validate("12345 6".toCharArray()),
        )
    }

    @Test
    fun pbkdf2HmacSha256MatchesPublishedVectors() {
        val password = "password".toCharArray()
        val salt = "salt".toByteArray(StandardCharsets.US_ASCII)

        assertEquals(
            "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b",
            PinKeyDerivation.derive(
                password,
                salt,
                PinKdfParameters(iterations = 1),
            ).toHex(),
        )
        assertEquals(
            "ae4d0c95af6b46d32d0adff928f06dd02a303f8ef3c251dfd6e2d85a95474c43",
            PinKeyDerivation.derive(
                password,
                salt,
                PinKdfParameters(iterations = 2),
            ).toHex(),
        )
        password.fill('\u0000')
    }

    @Test
    fun kdfParametersHaveStablePortableMappingAndRejectMalformedValues() {
        val parameters = PinKdfParameters(iterations = 450_000, derivedKeyLengthBytes = 48)

        assertEquals(parameters, PinKdfParameters.fromPortableMap(parameters.toPortableMap()))
        assertEquals(
            null,
            PinKdfParameters.fromPortableMap(
                parameters.toPortableMap() + (PinKdfParameters.PORTABLE_ITERATIONS to "NaN"),
            ),
        )
        assertFalse(
            checkNotNull(
                PinKdfParameters.fromPortableMap(
                    parameters.toPortableMap() +
                        (PinKdfParameters.PORTABLE_ALGORITHM to "unsupported"),
                ),
            ).isSupported(),
        )
    }

    @Test
    fun verifierUsesPepperAndConstantTimeCompatibleDigestComparison() {
        val pepper = TestPepper("device-only-test-key".toByteArray())
        val verifier = PinVerifier(pepper)
        val pin = "123456".toCharArray()
        val credential = verifier.createCredential(
            pin,
            PinKdfParameters(iterations = PinKdfParameters.MINIMUM_ENROLLMENT_ITERATIONS),
        )

        assertTrue(verifier.verify(pin, credential))
        assertFalse(verifier.verify("654321".toCharArray(), credential))
        assertEquals(
            listOf(
                PepperKeyAccess.CREATE_IF_MISSING,
                PepperKeyAccess.REQUIRE_EXISTING,
                PepperKeyAccess.REQUIRE_EXISTING,
            ),
            pepper.accesses,
        )

        val exposedSalt = credential.salt
        exposedSalt.fill(0)
        assertNotEquals(exposedSalt.toList(), credential.salt.toList())
        pin.fill('\u0000')
    }

    @Test
    fun enrollmentRejectsWeakPinsAndWeakKdfParameters() {
        val verifier = PinVerifier(TestPepper(ByteArray(32) { 7 }))

        assertThrows(IllegalArgumentException::class.java) {
            verifier.createCredential("12345".toCharArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            verifier.createCredential(
                "123456".toCharArray(),
                PinKdfParameters(iterations = 99_999),
            )
        }
    }

    @Test
    fun existingCredentialNeverSilentlyCreatesMissingPepper() {
        val enrollingPepper = TestPepper(ByteArray(32) { 9 })
        val credential = PinVerifier(enrollingPepper).createCredential(
            "123456".toCharArray(),
            PinKdfParameters(iterations = PinKdfParameters.MINIMUM_ENROLLMENT_ITERATIONS),
        )
        val missingPepper = NonExportableAppLockPepper { _, access ->
            assertEquals(PepperKeyAccess.REQUIRE_EXISTING, access)
            throw AppLockPepperUnavailableException("missing")
        }

        assertThrows(AppLockPepperUnavailableException::class.java) {
            PinVerifier(missingPepper).verify("123456".toCharArray(), credential)
        }
    }

    private class TestPepper(
        private val key: ByteArray,
    ) : NonExportableAppLockPepper {
        val accesses = mutableListOf<PepperKeyAccess>()

        override fun hmacSha256(
            message: ByteArray,
            keyAccess: PepperKeyAccess,
        ): ByteArray {
            accesses += keyAccess
            return Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(key, "HmacSHA256"))
                doFinal(message)
            }
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
