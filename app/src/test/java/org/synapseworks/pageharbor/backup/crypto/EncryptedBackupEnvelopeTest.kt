package org.synapseworks.pageharbor.backup.crypto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EncryptedBackupEnvelopeTest {
    @Test
    fun productionParametersRemainStrongAndUseFourMibibyteFrames() {
        assertEquals(600_000, EncryptedBackupEnvelope.DEFAULT_KDF_ITERATIONS)
        assertEquals(4_194_304, EncryptedBackupEnvelope.DEFAULT_DATA_FRAME_SIZE_BYTES)
        assertEquals(1, EncryptedBackupEnvelope.ENVELOPE_VERSION)
    }

    @Test
    fun publicWriterEmitsTheProductionKdfAndFrameSize() {
        val encrypted = ByteArrayOutputStream()

        EncryptedBackupEnvelope.encrypt(
            plaintextZip = ByteArrayInputStream(ByteArray(0)),
            encryptedDestination = encrypted,
            password = "production-header".toCharArray(),
        )

        val header = ByteBuffer.wrap(encrypted.toByteArray(), 0, HEADER_SIZE)
            .order(ByteOrder.BIG_ENDIAN)
        assertEquals("RMEENC01", encrypted.toByteArray().copyOfRange(0, 8).toString(Charsets.US_ASCII))
        assertEquals(1, header.getShort(8).toInt() and 0xffff)
        assertEquals(HEADER_SIZE, header.getShort(10).toInt() and 0xffff)
        assertEquals(1, encrypted.toByteArray()[KDF_ID_OFFSET].toInt() and 0xff)
        assertEquals(1, encrypted.toByteArray()[KDF_VERSION_OFFSET].toInt() and 0xff)
        assertEquals(600_000, header.getInt(16))
        assertEquals(4_194_304, header.getInt(20))
    }

    @Test
    fun roundTripAuthenticatesMultipleFramesAndPortableUnicodePassword() {
        val plaintext = ByteArray(93) { index -> (index * 17).toByte() }
        val password = "pașaport-🔐-e\u0301".toCharArray()

        val encrypted = encryptForTest(plaintext, password, frameSize = 13)
        val output = ByteArrayOutputStream()
        val summary = decryptForTest(encrypted, password, output)

        assertArrayEquals(plaintext, output.toByteArray())
        assertEquals(plaintext.size.toLong(), summary.plaintextBytes)
        assertEquals(8L, summary.dataFrameCount)
        assertArrayEquals(sha256(plaintext), summary.plaintextSha256)
        assertArrayEquals("pașaport-🔐-e\u0301".toCharArray(), password)
    }

    @Test
    fun fixedEntropyMakesTheEnvelopeDeterministicForGoldenTesting() {
        val plaintext = "synthetic backup bytes".toByteArray()
        val password = "portable".toCharArray()

        val first = encryptForTest(plaintext, password, frameSize = 7)
        val second = encryptForTest(plaintext, password, frameSize = 7)

        assertArrayEquals(first, second)
    }

    @Test
    fun repeatedPlaintextFramesUseDifferentNonceKeystreams() {
        val repeatedFrame = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val encrypted = encryptForTest(repeatedFrame + repeatedFrame, frameSize = repeatedFrame.size)
        val frames = frameRanges(encrypted)
        val firstCiphertext = encrypted.copyOfRange(
            frames[1].first + FRAME_PREFIX_SIZE,
            frames[1].first + FRAME_PREFIX_SIZE + repeatedFrame.size,
        )
        val secondCiphertext = encrypted.copyOfRange(
            frames[2].first + FRAME_PREFIX_SIZE,
            frames[2].first + FRAME_PREFIX_SIZE + repeatedFrame.size,
        )

        assertNotEquals(firstCiphertext.toList(), secondCiphertext.toList())
    }

    @Test
    fun identityBearingMetadataSentinelsAreConfidential() {
        val sentinels = listOf(
            "TITLE_SENTINEL_6BFEA11F_private_tax_return",
            "FOLDER_SENTINEL_58C9A223_family_records",
            "OCR_SENTINEL_A2D7418E_account_number_12345678",
            "PATH_SENTINEL_905F9AC4_documents/private-tax-return/page-0001.jpg",
        )
        val plaintext = sentinels.joinToString(separator = "\n").toByteArray(Charsets.UTF_8)

        val encrypted = encryptForTest(
            plaintext = plaintext,
            frameSize = plaintext.size + 32,
        )

        sentinels.forEach { sentinel ->
            assertFalse(
                "Encrypted envelope exposed metadata sentinel: $sentinel",
                encrypted.containsSubsequence(sentinel.toByteArray(Charsets.UTF_8)),
            )
        }
        val restored = ByteArrayOutputStream()
        decryptForTest(
            encrypted = encrypted,
            output = restored,
            maximumFrameSize = plaintext.size + 32,
        )
        assertArrayEquals(plaintext, restored.toByteArray())
    }

    @Test
    fun passwordIsUsedExactlyAsEnteredWithoutUnicodeNormalization() {
        val encrypted = encryptForTest(
            plaintext = "zip".toByteArray(),
            password = "caf\u00e9".toCharArray(),
        )

        val error = assertEnvelopeFailure(EnvelopeFailure.AUTHENTICATION_FAILED) {
            decryptForTest(encrypted, "cafe\u0301".toCharArray())
        }

        assertEquals(
            "The password is incorrect or the encrypted backup is damaged.",
            error.message,
        )
    }

    @Test
    fun wrongPasswordFailsAtTheAuthenticatedKeyCheck() {
        val encrypted = encryptForTest(ByteArray(40) { it.toByte() }, "correct".toCharArray())

        assertEnvelopeFailure(EnvelopeFailure.AUTHENTICATION_FAILED) {
            decryptForTest(encrypted, "incorrect".toCharArray())
        }
    }

    @Test
    fun changedAuthenticatedHeaderFailsAuthentication() {
        val encrypted = encryptForTest(ByteArray(40) { it.toByte() })
        encrypted[BASE_NONCE_OFFSET + 3] = (encrypted[BASE_NONCE_OFFSET + 3].toInt() xor 0x40).toByte()

        assertEnvelopeFailure(EnvelopeFailure.AUTHENTICATION_FAILED) {
            decryptForTest(encrypted)
        }
    }

    @Test
    fun changedKeyCheckCiphertextFailsAuthentication() {
        val encrypted = encryptForTest(ByteArray(40) { it.toByte() })
        encrypted[HEADER_SIZE + FRAME_PREFIX_SIZE + 4] =
            (encrypted[HEADER_SIZE + FRAME_PREFIX_SIZE + 4].toInt() xor 0x01).toByte()

        assertEnvelopeFailure(EnvelopeFailure.AUTHENTICATION_FAILED) {
            decryptForTest(encrypted)
        }
    }

    @Test
    fun changedDataCiphertextFailsAuthentication() {
        val encrypted = encryptForTest(ByteArray(40) { it.toByte() }, frameSize = 8)
        val frames = frameRanges(encrypted)
        val firstDataCiphertext = frames[1].first + FRAME_PREFIX_SIZE
        encrypted[firstDataCiphertext] = (encrypted[firstDataCiphertext].toInt() xor 0x20).toByte()

        assertEnvelopeFailure(EnvelopeFailure.AUTHENTICATION_FAILED) {
            decryptForTest(encrypted)
        }
    }

    @Test
    fun truncationAtHeaderFrameAndTagBoundariesAlwaysFails() {
        val encrypted = encryptForTest(ByteArray(40) { it.toByte() }, frameSize = 8)
        val frames = frameRanges(encrypted)
        val truncationPoints = listOf(
            0,
            HEADER_SIZE - 1,
            HEADER_SIZE,
            frames[1].first + FRAME_PREFIX_SIZE - 1,
            frames.last().first,
            encrypted.size - 1,
        )

        truncationPoints.forEach { size ->
            assertThrows("size=$size", BackupEnvelopeException::class.java) {
                decryptForTest(encrypted.copyOf(size))
            }
        }
    }

    @Test
    fun missingDataFrameIsRejected() {
        val encrypted = encryptForTest(ByteArray(40) { it.toByte() }, frameSize = 8)
        val frames = frameRanges(encrypted)
        val missingSecondData = encrypted.removing(frames[2])

        assertEnvelopeFailure(EnvelopeFailure.INVALID_FRAME) {
            decryptForTest(missingSecondData)
        }
    }

    @Test
    fun reorderedDataFramesAreRejected() {
        val encrypted = encryptForTest(ByteArray(40) { it.toByte() }, frameSize = 8)
        val frames = frameRanges(encrypted)
        val reordered = encrypted.replacingFrames(frames[1], frames[2])

        assertEnvelopeFailure(EnvelopeFailure.INVALID_FRAME) {
            decryptForTest(reordered)
        }
    }

    @Test
    fun missingEndFrameIsRejected() {
        val encrypted = encryptForTest(ByteArray(40) { it.toByte() }, frameSize = 8)
        val endFrame = frameRanges(encrypted).last()

        assertEnvelopeFailure(EnvelopeFailure.TRUNCATED) {
            decryptForTest(encrypted.copyOf(endFrame.first))
        }
    }

    @Test
    fun changedEndFrameIsRejected() {
        val encrypted = encryptForTest(ByteArray(40) { it.toByte() }, frameSize = 8)
        val endFrame = frameRanges(encrypted).last()
        val endCiphertext = endFrame.first + FRAME_PREFIX_SIZE
        encrypted[endCiphertext + 7] = (encrypted[endCiphertext + 7].toInt() xor 0x01).toByte()

        assertEnvelopeFailure(EnvelopeFailure.AUTHENTICATION_FAILED) {
            decryptForTest(encrypted)
        }
    }

    @Test
    fun bytesAfterAuthenticatedEndFrameAreRejected() {
        val encrypted = encryptForTest(ByteArray(40) { it.toByte() }) + byteArrayOf(0x42)

        assertEnvelopeFailure(EnvelopeFailure.TRAILING_DATA) {
            decryptForTest(encrypted)
        }
    }

    @Test
    fun reservedFrameMetadataMustBeZero() {
        val encrypted = encryptForTest(ByteArray(40) { it.toByte() })
        encrypted[HEADER_SIZE + 1] = 1

        assertEnvelopeFailure(EnvelopeFailure.INVALID_FRAME) {
            decryptForTest(encrypted)
        }
    }

    @Test
    fun publicReaderRejectsAWeakenedKdfBeforeDerivation() {
        val encrypted = encryptForTest(ByteArray(8), "password".toCharArray())

        assertEnvelopeFailure(EnvelopeFailure.INVALID_HEADER) {
            EncryptedBackupEnvelope.decrypt(
                ByteArrayInputStream(encrypted),
                ByteArrayOutputStream(),
                "password".toCharArray(),
            )
        }
    }

    @Test
    fun readerRejectsUnsupportedKdfVersionBeforeDerivation() {
        val encrypted = encryptForTest(ByteArray(8), "password".toCharArray())
        encrypted[KDF_VERSION_OFFSET] = 2

        assertEnvelopeFailure(EnvelopeFailure.INVALID_HEADER) {
            decryptForTest(encrypted, "password".toCharArray())
        }
    }

    @Test
    fun readerRejectsHostileKdfAndFrameParametersBeforeAllocation() {
        val base = encryptForTest(ByteArray(8), "password".toCharArray())
        listOf(
            base.copyOf().settingInt(KDF_ITERATIONS_OFFSET, 0),
            base.copyOf().settingInt(KDF_ITERATIONS_OFFSET, Int.MAX_VALUE),
            base.copyOf().settingInt(FRAME_SIZE_OFFSET, 0),
            base.copyOf().settingInt(FRAME_SIZE_OFFSET, Int.MAX_VALUE),
        ).forEach { hostile ->
            assertEnvelopeFailure(EnvelopeFailure.INVALID_HEADER) {
                decryptForTest(hostile, "password".toCharArray())
            }
        }
    }

    @Test
    fun readerRejectsHostileFrameLengthBeforeAllocation() {
        val encrypted = encryptForTest(ByteArray(8), frameSize = 8)
        val firstDataPrefix = frameRanges(encrypted)[1].first
        encrypted.settingInt(firstDataPrefix + 12, Int.MAX_VALUE)

        assertEnvelopeFailure(EnvelopeFailure.INVALID_FRAME) {
            decryptForTest(encrypted)
        }
    }

    @Test
    fun invalidOrOversizedUtf8PasswordsAreRejectedWithoutChangingCallerBuffer() {
        val malformed = charArrayOf('\ud800')
        val malformedOriginal = malformed.copyOf()
        assertEnvelopeFailure(EnvelopeFailure.INVALID_PASSWORD) {
            encryptForTest(ByteArray(0), malformed)
        }
        assertArrayEquals(malformedOriginal, malformed)

        val oversized = CharArray(EncryptedBackupEnvelope.MAX_PASSWORD_UTF8_BYTES + 1) { 'x' }
        assertEnvelopeFailure(EnvelopeFailure.INVALID_PASSWORD) {
            encryptForTest(ByteArray(0), oversized)
        }
    }

    @Test
    fun randomSaltAndNonceMakeIndependentProductionEnvelopesDistinct() {
        val plaintext = ByteArray(0)
        val password = "same password".toCharArray()
        val first = ByteArrayOutputStream()
        val second = ByteArrayOutputStream()

        EncryptedBackupEnvelope.encrypt(ByteArrayInputStream(plaintext), first, password)
        EncryptedBackupEnvelope.encrypt(ByteArrayInputStream(plaintext), second, password)

        assertNotEquals(
            first.toByteArray().copyOfRange(SALT_OFFSET, BASE_NONCE_OFFSET + BASE_NONCE_SIZE).toList(),
            second.toByteArray().copyOfRange(SALT_OFFSET, BASE_NONCE_OFFSET + BASE_NONCE_SIZE).toList(),
        )
    }

    private fun encryptForTest(
        plaintext: ByteArray,
        password: CharArray = TEST_PASSWORD.toCharArray(),
        frameSize: Int = 11,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        EncryptedBackupEnvelope.encryptWithParameters(
            plaintextZip = ByteArrayInputStream(plaintext),
            encryptedDestination = output,
            password = password,
            parameters = EncryptedBackupEnvelope.EnvelopeParameters(
                kdfIterations = TEST_KDF_ITERATIONS,
                dataFrameSizeBytes = frameSize,
            ),
            policy = testPolicy(frameSize),
            entropy = CountingEntropy(),
        )
        return output.toByteArray()
    }

    private fun decryptForTest(
        encrypted: ByteArray,
        password: CharArray = TEST_PASSWORD.toCharArray(),
        output: ByteArrayOutputStream = ByteArrayOutputStream(),
        maximumFrameSize: Int = 128,
    ): EnvelopeSummary = EncryptedBackupEnvelope.decryptWithPolicy(
        encryptedSource = ByteArrayInputStream(encrypted),
        plaintextZipDestination = output,
        password = password,
        policy = testPolicy(maximumFrameSize = maximumFrameSize),
    )

    private fun testPolicy(maximumFrameSize: Int) = EncryptedBackupEnvelope.EnvelopePolicy(
        minimumKdfIterations = TEST_KDF_ITERATIONS,
        maximumKdfIterations = TEST_KDF_ITERATIONS,
        minimumDataFrameSizeBytes = 1,
        maximumDataFrameSizeBytes = maximumFrameSize,
    )

    private fun assertEnvelopeFailure(
        expected: EnvelopeFailure,
        block: () -> Unit,
    ): BackupEnvelopeException {
        val error = assertThrows(BackupEnvelopeException::class.java, block)
        assertEquals(expected, error.failure)
        return error
    }

    private fun frameRanges(encrypted: ByteArray): List<IntRange> {
        val result = mutableListOf<IntRange>()
        var offset = HEADER_SIZE
        while (offset < encrypted.size) {
            check(encrypted.size - offset >= FRAME_PREFIX_SIZE)
            val plaintextLength = ByteBuffer.wrap(encrypted, offset + 12, 4)
                .order(ByteOrder.BIG_ENDIAN)
                .int
            check(plaintextLength >= 0)
            val endExclusive = offset + FRAME_PREFIX_SIZE + plaintextLength + TAG_SIZE
            check(endExclusive <= encrypted.size)
            result += offset until endExclusive
            offset = endExclusive
        }
        check(offset == encrypted.size)
        return result
    }

    private fun ByteArray.removing(range: IntRange): ByteArray =
        copyOfRange(0, range.first) + copyOfRange(range.last + 1, size)

    private fun ByteArray.replacingFrames(first: IntRange, second: IntRange): ByteArray =
        copyOfRange(0, first.first) +
            copyOfRange(second.first, second.last + 1) +
            copyOfRange(first.first, first.last + 1) +
            copyOfRange(second.last + 1, size)

    private fun ByteArray.settingInt(offset: Int, value: Int): ByteArray = apply {
        ByteBuffer.wrap(this, offset, 4).order(ByteOrder.BIG_ENDIAN).putInt(value)
    }

    private fun ByteArray.containsSubsequence(needle: ByteArray): Boolean {
        if (needle.isEmpty()) return true
        if (needle.size > size) return false
        for (start in 0..size - needle.size) {
            var matches = true
            for (index in needle.indices) {
                if (this[start + index] != needle[index]) {
                    matches = false
                    break
                }
            }
            if (matches) return true
        }
        return false
    }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private class CountingEntropy : EncryptedBackupEnvelope.EnvelopeEntropy {
        private var next = 1

        override fun nextBytes(destination: ByteArray) {
            destination.indices.forEach { index ->
                destination[index] = next++.toByte()
            }
        }
    }

    private companion object {
        const val TEST_PASSWORD = "test password"
        const val TEST_KDF_ITERATIONS = 1_000
        const val HEADER_SIZE = 64
        const val FRAME_PREFIX_SIZE = 16
        const val TAG_SIZE = 16
        const val KDF_ITERATIONS_OFFSET = 16
        const val KDF_ID_OFFSET = 12
        const val KDF_VERSION_OFFSET = 13
        const val FRAME_SIZE_OFFSET = 20
        const val SALT_OFFSET = 28
        const val BASE_NONCE_OFFSET = 44
        const val BASE_NONCE_SIZE = 12
    }
}
