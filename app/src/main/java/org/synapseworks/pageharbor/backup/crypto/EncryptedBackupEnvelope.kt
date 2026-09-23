package org.synapseworks.pageharbor.backup.crypto

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Streams a complete ordinary RME backup ZIP through the independently versioned encrypted envelope.
 *
 * Decryption authenticates every frame before writing that frame. Callers must nevertheless direct the
 * output to private staging and discard it on any failure: the mandatory END frame authenticates the
 * complete stream and is the only point at which the returned plaintext is complete.
 */
object EncryptedBackupEnvelope {
    const val ENVELOPE_VERSION: Int = 1
    const val DEFAULT_KDF_ITERATIONS: Int = 600_000
    const val DEFAULT_DATA_FRAME_SIZE_BYTES: Int = 4 * 1024 * 1024
    const val MAX_PASSWORD_UTF8_BYTES: Int = 1_024

    private const val HEADER_SIZE = 64
    private const val FRAME_PREFIX_SIZE = 16
    private const val SALT_SIZE = 16
    private const val NONCE_SIZE = 12
    private const val TAG_SIZE_BYTES = 16
    private const val KEY_SIZE_BITS = 256
    private const val KEY_CHECK_SIZE = 32
    private const val END_PLAINTEXT_SIZE = 64
    private const val MIN_READER_FRAME_SIZE_BYTES = 64 * 1024
    private const val MAX_READER_FRAME_SIZE_BYTES = 16 * 1024 * 1024
    private const val MAX_READER_KDF_ITERATIONS = 2_000_000

    private const val TYPE_KEY_CHECK = 0
    private const val TYPE_DATA = 1
    private const val TYPE_END = 127

    private const val AEAD_ID_AES_256_GCM = 1
    private const val NONCE_MODE_BASE_XOR_SEQUENCE = 1

    private val magic = "RMEENC01".toByteArray(StandardCharsets.US_ASCII)
    private val aadDomain = "RME-BACKUP-ENVELOPE-V1\u0000".toByteArray(StandardCharsets.US_ASCII)
    private val keyCheckPlaintext = sha256(
        "RME-BACKUP-KEY-CHECK-V1".toByteArray(StandardCharsets.US_ASCII),
    )
    private val endMagic = "RME-END1".toByteArray(StandardCharsets.US_ASCII)

    private val productionPolicy = EnvelopePolicy(
        minimumKdfIterations = DEFAULT_KDF_ITERATIONS,
        maximumKdfIterations = MAX_READER_KDF_ITERATIONS,
        minimumDataFrameSizeBytes = MIN_READER_FRAME_SIZE_BYTES,
        maximumDataFrameSizeBytes = MAX_READER_FRAME_SIZE_BYTES,
    )

    @Throws(IOException::class)
    fun encrypt(
        plaintextZip: InputStream,
        encryptedDestination: OutputStream,
        password: CharArray,
    ): EnvelopeSummary = encryptWithParameters(
        plaintextZip = plaintextZip,
        encryptedDestination = encryptedDestination,
        password = password,
        parameters = EnvelopeParameters(
            kdfIterations = DEFAULT_KDF_ITERATIONS,
            dataFrameSizeBytes = DEFAULT_DATA_FRAME_SIZE_BYTES,
        ),
        policy = productionPolicy,
        entropy = SecureRandomEntropy,
    )

    @Throws(IOException::class)
    fun decrypt(
        encryptedSource: InputStream,
        plaintextZipDestination: OutputStream,
        password: CharArray,
    ): EnvelopeSummary = decryptWithPolicy(
        encryptedSource = encryptedSource,
        plaintextZipDestination = plaintextZipDestination,
        password = password,
        policy = productionPolicy,
    )

    internal fun encryptWithParameters(
        plaintextZip: InputStream,
        encryptedDestination: OutputStream,
        password: CharArray,
        parameters: EnvelopeParameters,
        policy: EnvelopePolicy,
        entropy: EnvelopeEntropy,
    ): EnvelopeSummary {
        policy.validate(parameters)
        val salt = ByteArray(SALT_SIZE)
        val baseNonce = ByteArray(NONCE_SIZE)
        entropy.nextBytes(salt)
        entropy.nextBytes(baseNonce)
        val header = createHeader(parameters, salt, baseNonce)
        val passwordCopy = password.copyOf()

        try {
            encryptedDestination.write(header)
            return withDerivedKey(
                password = passwordCopy,
                salt = salt,
                iterations = parameters.kdfIterations,
                policy = policy,
                kdf = PasswordKdf.PBKDF2_HMAC_SHA256_V1,
            ) { key ->
                val keyCheckPrefix = createFramePrefix(
                    type = TYPE_KEY_CHECK,
                    sequence = 0L,
                    plaintextLength = KEY_CHECK_SIZE,
                )
                writeEncryptedFrame(
                    destination = encryptedDestination,
                    header = header,
                    prefix = keyCheckPrefix,
                    plaintext = keyCheckPlaintext,
                    key = key,
                    baseNonce = baseNonce,
                )

                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(parameters.dataFrameSizeBytes)
                var totalPlaintextBytes = 0L
                var dataFrameCount = 0L
                var sequence = 1L

                try {
                    while (true) {
                        val count = readChunk(plaintextZip, buffer)
                        if (count == 0) break
                        val framePlaintext = if (count == buffer.size) buffer else buffer.copyOf(count)
                        try {
                            val prefix = createFramePrefix(TYPE_DATA, sequence, count)
                            writeEncryptedFrame(
                                destination = encryptedDestination,
                                header = header,
                                prefix = prefix,
                                plaintext = framePlaintext,
                                key = key,
                                baseNonce = baseNonce,
                            )
                            digest.update(buffer, 0, count)
                            totalPlaintextBytes = checkedAdd(totalPlaintextBytes, count.toLong())
                            dataFrameCount = checkedIncrement(dataFrameCount)
                            sequence = checkedIncrement(sequence)
                        } finally {
                            if (framePlaintext !== buffer) framePlaintext.fill(0)
                        }
                    }

                    val plaintextSha256 = digest.digest()
                    val endPlaintext = createEndPlaintext(
                        totalPlaintextBytes = totalPlaintextBytes,
                        dataFrameCount = dataFrameCount,
                        plaintextSha256 = plaintextSha256,
                    )
                    try {
                        val endPrefix = createFramePrefix(TYPE_END, sequence, END_PLAINTEXT_SIZE)
                        writeEncryptedFrame(
                            destination = encryptedDestination,
                            header = header,
                            prefix = endPrefix,
                            plaintext = endPlaintext,
                            key = key,
                            baseNonce = baseNonce,
                        )
                    } finally {
                        endPlaintext.fill(0)
                    }
                    EnvelopeSummary(
                        plaintextBytes = totalPlaintextBytes,
                        dataFrameCount = dataFrameCount,
                        plaintextSha256 = plaintextSha256,
                    )
                } finally {
                    buffer.fill(0)
                }
            }
        } finally {
            passwordCopy.fill('\u0000')
            salt.fill(0)
            baseNonce.fill(0)
            header.fill(0)
        }
    }

    internal fun decryptWithPolicy(
        encryptedSource: InputStream,
        plaintextZipDestination: OutputStream,
        password: CharArray,
        policy: EnvelopePolicy,
    ): EnvelopeSummary {
        val header = readExact(encryptedSource, HEADER_SIZE, EnvelopeFailure.TRUNCATED)
        val parsedHeader = parseAndValidateHeader(header, policy)
        val passwordCopy = password.copyOf()
        try {
            return withDerivedKey(
                password = passwordCopy,
                salt = parsedHeader.salt,
                iterations = parsedHeader.parameters.kdfIterations,
                policy = policy,
                kdf = parsedHeader.kdf,
            ) { key ->
                val keyCheckFrame = readAndDecryptFrame(
                    source = encryptedSource,
                    header = header,
                    key = key,
                    baseNonce = parsedHeader.baseNonce,
                    maximumPlaintextLength = KEY_CHECK_SIZE,
                )
                try {
                    requireFrame(
                        prefix = keyCheckFrame.prefix,
                        expectedType = TYPE_KEY_CHECK,
                        expectedSequence = 0L,
                        expectedLength = KEY_CHECK_SIZE,
                    )
                    if (!MessageDigest.isEqual(keyCheckPlaintext, keyCheckFrame.plaintext)) {
                        throw envelopeFailure(EnvelopeFailure.AUTHENTICATION_FAILED)
                    }
                } finally {
                    keyCheckFrame.plaintext.fill(0)
                }

                val digest = MessageDigest.getInstance("SHA-256")
                var expectedSequence = 1L
                var totalPlaintextBytes = 0L
                var dataFrameCount = 0L

                while (true) {
                    val frame = readAndDecryptFrame(
                        source = encryptedSource,
                        header = header,
                        key = key,
                        baseNonce = parsedHeader.baseNonce,
                        maximumPlaintextLength = parsedHeader.parameters.dataFrameSizeBytes,
                        allowEndFrame = true,
                    )
                    try {
                        if (frame.prefix.sequence != expectedSequence) {
                            throw envelopeFailure(EnvelopeFailure.INVALID_FRAME)
                        }
                        when (frame.prefix.type) {
                            TYPE_DATA -> {
                                if (frame.prefix.plaintextLength !in 1..parsedHeader.parameters.dataFrameSizeBytes) {
                                    throw envelopeFailure(EnvelopeFailure.INVALID_FRAME)
                                }
                                plaintextZipDestination.write(frame.plaintext)
                                digest.update(frame.plaintext)
                                totalPlaintextBytes = checkedAdd(
                                    totalPlaintextBytes,
                                    frame.prefix.plaintextLength.toLong(),
                                )
                                dataFrameCount = checkedIncrement(dataFrameCount)
                                expectedSequence = checkedIncrement(expectedSequence)
                            }

                            TYPE_END -> {
                                if (frame.prefix.plaintextLength != END_PLAINTEXT_SIZE) {
                                    throw envelopeFailure(EnvelopeFailure.INVALID_FRAME)
                                }
                                val actualSha256 = digest.digest()
                                validateEndPlaintext(
                                    plaintext = frame.plaintext,
                                    expectedTotalPlaintextBytes = totalPlaintextBytes,
                                    expectedDataFrameCount = dataFrameCount,
                                    actualSha256 = actualSha256,
                                )
                                if (encryptedSource.read() != -1) {
                                    throw envelopeFailure(EnvelopeFailure.TRAILING_DATA)
                                }
                                return@withDerivedKey EnvelopeSummary(
                                    plaintextBytes = totalPlaintextBytes,
                                    dataFrameCount = dataFrameCount,
                                    plaintextSha256 = actualSha256,
                                )
                            }

                            else -> throw envelopeFailure(EnvelopeFailure.INVALID_FRAME)
                        }
                    } finally {
                        frame.plaintext.fill(0)
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                throw envelopeFailure(EnvelopeFailure.INVALID_FRAME)
            }
        } finally {
            passwordCopy.fill('\u0000')
            parsedHeader.salt.fill(0)
            parsedHeader.baseNonce.fill(0)
            header.fill(0)
        }
    }

    private fun readAndDecryptFrame(
        source: InputStream,
        header: ByteArray,
        key: SecretKeySpec,
        baseNonce: ByteArray,
        maximumPlaintextLength: Int,
        allowEndFrame: Boolean = false,
    ): DecryptedFrame {
        val prefixBytes = readExact(source, FRAME_PREFIX_SIZE, EnvelopeFailure.TRUNCATED)
        val prefix = parseFramePrefix(prefixBytes)
        val permittedMaximum = if (allowEndFrame) {
            maxOf(maximumPlaintextLength, END_PLAINTEXT_SIZE)
        } else {
            maximumPlaintextLength
        }
        if (prefix.plaintextLength < 0 || prefix.plaintextLength > permittedMaximum) {
            throw envelopeFailure(EnvelopeFailure.INVALID_FRAME)
        }
        val ciphertextLength = prefix.plaintextLength + TAG_SIZE_BYTES
        val ciphertext = readExact(source, ciphertextLength, EnvelopeFailure.TRUNCATED)
        val nonce = nonceForSequence(baseNonce, prefix.sequence)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_SIZE_BYTES * 8, nonce))
            cipher.updateAAD(aadDomain)
            cipher.updateAAD(header)
            cipher.updateAAD(prefixBytes)
            val plaintext = try {
                cipher.doFinal(ciphertext)
            } catch (error: GeneralSecurityException) {
                // Providers differ in whether a failed GCM tag is surfaced as AEADBadTagException,
                // BadPaddingException, or IllegalBlockSizeException. They are all untrusted-input
                // authentication failures once the cipher has initialized successfully.
                throw envelopeFailure(EnvelopeFailure.AUTHENTICATION_FAILED, error)
            }
            if (plaintext.size != prefix.plaintextLength) {
                plaintext.fill(0)
                throw envelopeFailure(EnvelopeFailure.AUTHENTICATION_FAILED)
            }
            return DecryptedFrame(prefix, plaintext)
        } catch (error: BackupEnvelopeException) {
            throw error
        } catch (error: GeneralSecurityException) {
            throw envelopeFailure(EnvelopeFailure.CRYPTO_UNAVAILABLE, error)
        } finally {
            prefixBytes.fill(0)
            ciphertext.fill(0)
            nonce.fill(0)
        }
    }

    private fun writeEncryptedFrame(
        destination: OutputStream,
        header: ByteArray,
        prefix: ByteArray,
        plaintext: ByteArray,
        key: SecretKeySpec,
        baseNonce: ByteArray,
    ) {
        val sequence = ByteBuffer.wrap(prefix, 4, 8).order(ByteOrder.BIG_ENDIAN).long
        val nonce = nonceForSequence(baseNonce, sequence)
        var ciphertext: ByteArray? = null
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_SIZE_BYTES * 8, nonce))
            cipher.updateAAD(aadDomain)
            cipher.updateAAD(header)
            cipher.updateAAD(prefix)
            ciphertext = cipher.doFinal(plaintext)
            if (ciphertext.size != plaintext.size + TAG_SIZE_BYTES) {
                throw envelopeFailure(EnvelopeFailure.CRYPTO_UNAVAILABLE)
            }
            destination.write(prefix)
            destination.write(ciphertext)
        } catch (error: BackupEnvelopeException) {
            throw error
        } catch (error: GeneralSecurityException) {
            throw envelopeFailure(EnvelopeFailure.CRYPTO_UNAVAILABLE, error)
        } finally {
            ciphertext?.fill(0)
            nonce.fill(0)
            prefix.fill(0)
        }
    }

    private inline fun <T> withDerivedKey(
        password: CharArray,
        salt: ByteArray,
        iterations: Int,
        policy: EnvelopePolicy,
        kdf: PasswordKdf,
        block: (SecretKeySpec) -> T,
    ): T {
        policy.validatePassword(password)
        var derivedKey: ByteArray? = null
        var key: SecretKeySpec? = null
        try {
            derivedKey = kdf.deriveKey(
                password = password,
                salt = salt,
                iterations = iterations,
                maximumUtf8Bytes = policy.maximumPasswordUtf8Bytes,
            )
            if (derivedKey.size != KEY_SIZE_BITS / 8) {
                throw envelopeFailure(EnvelopeFailure.CRYPTO_UNAVAILABLE)
            }
            key = SecretKeySpec(derivedKey, "AES")
            return block(key)
        } catch (error: BackupEnvelopeException) {
            throw error
        } catch (error: GeneralSecurityException) {
            throw envelopeFailure(EnvelopeFailure.CRYPTO_UNAVAILABLE, error)
        } finally {
            runCatching { key?.destroy() }
            derivedKey?.fill(0)
        }
    }

    private fun portablePbePassword(password: CharArray, maximumUtf8Bytes: Int): CharArray {
        val encoder = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val encodedBuffer = ByteBuffer.allocate(maximumUtf8Bytes + 1)
        var utf8: ByteArray? = null
        var base64: ByteArray? = null
        try {
            val input = CharBuffer.wrap(password)
            val encoded = encoder.encode(input, encodedBuffer, true)
            if (encoded.isError) encoded.throwException()
            if (encoded.isOverflow || input.hasRemaining()) {
                throw envelopeFailure(EnvelopeFailure.INVALID_PASSWORD)
            }
            val flushed = encoder.flush(encodedBuffer)
            if (flushed.isError) flushed.throwException()
            if (flushed.isOverflow || encodedBuffer.position() > maximumUtf8Bytes) {
                throw envelopeFailure(EnvelopeFailure.INVALID_PASSWORD)
            }
            if (encodedBuffer.position() == 0) {
                throw envelopeFailure(EnvelopeFailure.INVALID_PASSWORD)
            }
            utf8 = ByteArray(encodedBuffer.position())
            encodedBuffer.flip()
            encodedBuffer.get(utf8)
            base64 = Base64.getEncoder().withoutPadding().encode(utf8)
            return CharArray(base64.size) { index ->
                (base64[index].toInt() and 0xff).toChar()
            }
        } catch (error: BackupEnvelopeException) {
            throw error
        } catch (error: Exception) {
            throw envelopeFailure(EnvelopeFailure.INVALID_PASSWORD, error)
        } finally {
            encodedBuffer.array().fill(0)
            utf8?.fill(0)
            base64?.fill(0)
        }
    }

    private fun createHeader(
        parameters: EnvelopeParameters,
        salt: ByteArray,
        baseNonce: ByteArray,
    ): ByteArray = ByteBuffer.allocate(HEADER_SIZE)
        .order(ByteOrder.BIG_ENDIAN)
        .apply {
            put(magic)
            putShort(ENVELOPE_VERSION.toShort())
            putShort(HEADER_SIZE.toShort())
            put(PasswordKdf.PBKDF2_HMAC_SHA256_V1.id.toByte())
            put(PasswordKdf.PBKDF2_HMAC_SHA256_V1.version.toByte())
            put(AEAD_ID_AES_256_GCM.toByte())
            put(NONCE_MODE_BASE_XOR_SEQUENCE.toByte())
            putInt(parameters.kdfIterations)
            putInt(parameters.dataFrameSizeBytes)
            put(SALT_SIZE.toByte())
            put(NONCE_SIZE.toByte())
            put(TAG_SIZE_BYTES.toByte())
            put(0) // flags
            put(salt)
            put(baseNonce)
            put(ByteArray(8)) // reserved
        }
        .array()

    private fun parseAndValidateHeader(header: ByteArray, policy: EnvelopePolicy): ParsedHeader {
        if (header.size != HEADER_SIZE || !header.copyOfRange(0, magic.size).contentEquals(magic)) {
            throw envelopeFailure(EnvelopeFailure.INVALID_HEADER)
        }
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        buffer.position(magic.size)
        val version = buffer.short.toInt() and 0xffff
        val headerLength = buffer.short.toInt() and 0xffff
        val kdfId = buffer.get().toInt() and 0xff
        val kdfVersion = buffer.get().toInt() and 0xff
        val aeadId = buffer.get().toInt() and 0xff
        val nonceMode = buffer.get().toInt() and 0xff
        val iterations = buffer.int
        val frameSize = buffer.int
        val saltLength = buffer.get().toInt() and 0xff
        val nonceLength = buffer.get().toInt() and 0xff
        val tagLength = buffer.get().toInt() and 0xff
        val flags = buffer.get().toInt() and 0xff
        val salt = ByteArray(SALT_SIZE).also(buffer::get)
        val baseNonce = ByteArray(NONCE_SIZE).also(buffer::get)
        val reserved = ByteArray(8).also(buffer::get)
        val kdf = PasswordKdf.forHeader(kdfId, kdfVersion)

        val fixedFieldsValid = version == ENVELOPE_VERSION &&
            headerLength == HEADER_SIZE &&
            kdf != null &&
            aeadId == AEAD_ID_AES_256_GCM &&
            nonceMode == NONCE_MODE_BASE_XOR_SEQUENCE &&
            flags == 0 &&
            saltLength == SALT_SIZE &&
            nonceLength == NONCE_SIZE &&
            tagLength == TAG_SIZE_BYTES &&
            reserved.all { it == 0.toByte() }
        if (!fixedFieldsValid) {
            salt.fill(0)
            baseNonce.fill(0)
            throw envelopeFailure(EnvelopeFailure.INVALID_HEADER)
        }

        val parameters = EnvelopeParameters(iterations, frameSize)
        try {
            policy.validate(parameters)
        } catch (error: BackupEnvelopeException) {
            salt.fill(0)
            baseNonce.fill(0)
            throw error
        }
        return ParsedHeader(parameters, requireNotNull(kdf), salt, baseNonce)
    }

    private fun createFramePrefix(
        type: Int,
        sequence: Long,
        plaintextLength: Int,
    ): ByteArray {
        if (sequence < 0L || plaintextLength < 0) {
            throw envelopeFailure(EnvelopeFailure.LIMIT_EXCEEDED)
        }
        return ByteBuffer.allocate(FRAME_PREFIX_SIZE)
            .order(ByteOrder.BIG_ENDIAN)
            .apply {
                put(type.toByte())
                put(0)
                put(0)
                put(0)
                putLong(sequence)
                putInt(plaintextLength)
            }
            .array()
    }

    private fun parseFramePrefix(prefix: ByteArray): FramePrefix {
        if (prefix.size != FRAME_PREFIX_SIZE ||
            prefix[1] != 0.toByte() ||
            prefix[2] != 0.toByte() ||
            prefix[3] != 0.toByte()
        ) {
            throw envelopeFailure(EnvelopeFailure.INVALID_FRAME)
        }
        val buffer = ByteBuffer.wrap(prefix).order(ByteOrder.BIG_ENDIAN)
        val type = buffer.get().toInt() and 0xff
        buffer.position(4)
        val sequence = buffer.long
        val unsignedLength = buffer.int.toLong() and 0xffff_ffffL
        if (sequence < 0L || unsignedLength > Int.MAX_VALUE.toLong()) {
            throw envelopeFailure(EnvelopeFailure.INVALID_FRAME)
        }
        return FramePrefix(type, sequence, unsignedLength.toInt())
    }

    private fun requireFrame(
        prefix: FramePrefix,
        expectedType: Int,
        expectedSequence: Long,
        expectedLength: Int,
    ) {
        if (prefix.type != expectedType ||
            prefix.sequence != expectedSequence ||
            prefix.plaintextLength != expectedLength
        ) {
            throw envelopeFailure(EnvelopeFailure.INVALID_FRAME)
        }
    }

    private fun nonceForSequence(baseNonce: ByteArray, sequence: Long): ByteArray {
        if (baseNonce.size != NONCE_SIZE || sequence < 0L) {
            throw envelopeFailure(EnvelopeFailure.INVALID_FRAME)
        }
        return baseNonce.copyOf().also { nonce ->
            for (index in 0 until 8) {
                val shift = (7 - index) * 8
                val sequenceByte = ((sequence ushr shift) and 0xff).toInt()
                nonce[4 + index] = ((nonce[4 + index].toInt() and 0xff) xor sequenceByte).toByte()
            }
        }
    }

    private fun createEndPlaintext(
        totalPlaintextBytes: Long,
        dataFrameCount: Long,
        plaintextSha256: ByteArray,
    ): ByteArray = ByteBuffer.allocate(END_PLAINTEXT_SIZE)
        .order(ByteOrder.BIG_ENDIAN)
        .apply {
            put(endMagic)
            putLong(totalPlaintextBytes)
            putLong(dataFrameCount)
            put(plaintextSha256)
            putLong(0L)
        }
        .array()

    private fun validateEndPlaintext(
        plaintext: ByteArray,
        expectedTotalPlaintextBytes: Long,
        expectedDataFrameCount: Long,
        actualSha256: ByteArray,
    ) {
        if (plaintext.size != END_PLAINTEXT_SIZE) {
            throw envelopeFailure(EnvelopeFailure.INVALID_END_FRAME)
        }
        val buffer = ByteBuffer.wrap(plaintext).order(ByteOrder.BIG_ENDIAN)
        val actualMagic = ByteArray(endMagic.size).also(buffer::get)
        val totalPlaintextBytes = buffer.long
        val dataFrameCount = buffer.long
        val declaredSha256 = ByteArray(32).also(buffer::get)
        val reserved = buffer.long
        val valid = MessageDigest.isEqual(endMagic, actualMagic) &&
            totalPlaintextBytes == expectedTotalPlaintextBytes &&
            dataFrameCount == expectedDataFrameCount &&
            MessageDigest.isEqual(declaredSha256, actualSha256) &&
            reserved == 0L
        actualMagic.fill(0)
        declaredSha256.fill(0)
        if (!valid) throw envelopeFailure(EnvelopeFailure.INVALID_END_FRAME)
    }

    private fun readChunk(source: InputStream, buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val count = source.read(buffer, offset, buffer.size - offset)
            when {
                count < 0 -> break
                count == 0 -> {
                    val singleByte = source.read()
                    if (singleByte < 0) break
                    buffer[offset++] = singleByte.toByte()
                }

                else -> offset += count
            }
        }
        return offset
    }

    private fun readExact(
        source: InputStream,
        size: Int,
        failure: EnvelopeFailure,
    ): ByteArray {
        if (size < 0) throw envelopeFailure(EnvelopeFailure.LIMIT_EXCEEDED)
        val result = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = source.read(result, offset, size - offset)
            when {
                count < 0 -> {
                    result.fill(0)
                    throw envelopeFailure(failure)
                }

                count == 0 -> {
                    val singleByte = source.read()
                    if (singleByte < 0) {
                        result.fill(0)
                        throw envelopeFailure(failure)
                    }
                    result[offset++] = singleByte.toByte()
                }

                else -> offset += count
            }
        }
        return result
    }

    private fun checkedAdd(current: Long, added: Long): Long = try {
        Math.addExact(current, added)
    } catch (error: ArithmeticException) {
        throw envelopeFailure(EnvelopeFailure.LIMIT_EXCEEDED, error)
    }

    private fun checkedIncrement(current: Long): Long = checkedAdd(current, 1L)

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun envelopeFailure(
        failure: EnvelopeFailure,
        cause: Throwable? = null,
    ): BackupEnvelopeException = BackupEnvelopeException(failure, cause)

    internal data class EnvelopeParameters(
        val kdfIterations: Int,
        val dataFrameSizeBytes: Int,
    )

    internal data class EnvelopePolicy(
        val minimumKdfIterations: Int,
        val maximumKdfIterations: Int,
        val minimumDataFrameSizeBytes: Int,
        val maximumDataFrameSizeBytes: Int,
        val maximumPasswordUtf8Bytes: Int = MAX_PASSWORD_UTF8_BYTES,
    ) {
        fun validate(parameters: EnvelopeParameters) {
            if (minimumKdfIterations < 1 ||
                maximumKdfIterations < minimumKdfIterations ||
                minimumDataFrameSizeBytes < 1 ||
                maximumDataFrameSizeBytes < minimumDataFrameSizeBytes ||
                maximumPasswordUtf8Bytes < 1 ||
                parameters.kdfIterations !in minimumKdfIterations..maximumKdfIterations ||
                parameters.dataFrameSizeBytes !in minimumDataFrameSizeBytes..maximumDataFrameSizeBytes
            ) {
                throw envelopeFailure(EnvelopeFailure.INVALID_HEADER)
            }
        }

        fun validatePassword(password: CharArray) {
            if (password.isEmpty()) throw envelopeFailure(EnvelopeFailure.INVALID_PASSWORD)
        }
    }

    internal fun interface EnvelopeEntropy {
        fun nextBytes(destination: ByteArray)
    }

    /**
     * Header-addressable KDF semantics. The ID selects the KDF family while the version freezes
     * password encoding and derivation behavior within that family.
     */
    private enum class PasswordKdf(
        val id: Int,
        val version: Int,
    ) {
        PBKDF2_HMAC_SHA256_V1(id = 1, version = 1) {
            override fun deriveKey(
                password: CharArray,
                salt: ByteArray,
                iterations: Int,
                maximumUtf8Bytes: Int,
            ): ByteArray {
                val pbePassword = portablePbePassword(password, maximumUtf8Bytes)
                val keySpec = PBEKeySpec(pbePassword, salt, iterations, KEY_SIZE_BITS)
                return try {
                    SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                        .generateSecret(keySpec)
                        .encoded
                } finally {
                    keySpec.clearPassword()
                    pbePassword.fill('\u0000')
                }
            }
        },
        ;

        abstract fun deriveKey(
            password: CharArray,
            salt: ByteArray,
            iterations: Int,
            maximumUtf8Bytes: Int,
        ): ByteArray

        companion object {
            fun forHeader(id: Int, version: Int): PasswordKdf? =
                entries.singleOrNull { it.id == id && it.version == version }
        }
    }

    private object SecureRandomEntropy : EnvelopeEntropy {
        private val random = SecureRandom()

        override fun nextBytes(destination: ByteArray) {
            random.nextBytes(destination)
        }
    }

    private data class ParsedHeader(
        val parameters: EnvelopeParameters,
        val kdf: PasswordKdf,
        val salt: ByteArray,
        val baseNonce: ByteArray,
    )

    private data class FramePrefix(
        val type: Int,
        val sequence: Long,
        val plaintextLength: Int,
    )

    private data class DecryptedFrame(
        val prefix: FramePrefix,
        val plaintext: ByteArray,
    )
}

data class EnvelopeSummary(
    val plaintextBytes: Long,
    val dataFrameCount: Long,
    val plaintextSha256: ByteArray,
)

class BackupEnvelopeException internal constructor(
    val failure: EnvelopeFailure,
    cause: Throwable? = null,
) : IOException(failure.safeMessage, cause)

enum class EnvelopeFailure(internal val safeMessage: String) {
    INVALID_HEADER("The encrypted backup header is invalid or unsupported."),
    INVALID_FRAME("The encrypted backup frame sequence is invalid."),
    INVALID_END_FRAME("The encrypted backup completion record is invalid."),
    INVALID_PASSWORD("The backup password is invalid."),
    AUTHENTICATION_FAILED("The password is incorrect or the encrypted backup is damaged."),
    TRUNCATED("The encrypted backup is incomplete."),
    TRAILING_DATA("The encrypted backup has unexpected trailing data."),
    LIMIT_EXCEEDED("The encrypted backup exceeds a supported limit."),
    CRYPTO_UNAVAILABLE("Required encrypted-backup cryptography is unavailable."),
}
