package org.synapseworks.pageharbor.document.protectedpdf

import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import java.io.File
import java.io.IOException
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Base64
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** A stable RFC4013/PDF password subset: never silently normalize or truncate user input. */
fun isSupportedPdfPassword(password: CharArray): Boolean {
    if (password.size !in 8..127) return false
    val text = String(password)
    val points = text.codePoints().toArray()
    if (points.size < 8 || !Normalizer.isNormalized(text, Normalizer.Form.NFKC)) return false
    val prohibitedTypes = setOf(
        Character.CONTROL.toInt(), Character.FORMAT.toInt(), Character.PRIVATE_USE.toInt(),
        Character.SURROGATE.toInt(), Character.UNASSIGNED.toInt(),
        Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt(),
    )
    if (points.any { point ->
        !isAssignedPdfPasswordCodePoint(point) || Character.getType(point) in prohibitedTypes ||
            (Character.isSpaceChar(point) && point != 0x20) ||
            point == 0x034F || point == 0x1806 || point in 0x180B..0x180D ||
            point in 0xFE00..0xFE0F || point in 0x2FF0..0x2FFB ||
            point in 0xFFF9..0xFFFD || point in 0xFDD0..0xFDEF ||
            (point and 0xFFFF) >= 0xFFFE ||
            // PDFBox's profile also checks the low UTF-16 word in its space/control tables.
            (point > 0xFFFF && (Character.isISOControl(point.toChar()) || Character.isSpaceChar(point.toChar())))
    }) return false
    fun rtl(point: Int) = Character.getDirectionality(point) in setOf(
        Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
    )
    if (points.any(::rtl) && (
        points.any { Character.getDirectionality(it) == Character.DIRECTIONALITY_LEFT_TO_RIGHT } ||
            !rtl(points.first()) || !rtl(points.last())
    )) return false
    val encoded = try {
        Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(password))
    } catch (_: CharacterCodingException) {
        return false
    }
    return try {
        encoded.remaining() <= 127
    } finally {
        if (encoded.hasArray()) encoded.array().fill(0)
    }
}

sealed interface PasswordPdfResult {
    data class Success(val pageCount: Int) : PasswordPdfResult
    data object InvalidPassword : PasswordPdfResult
    data object InvalidSource : PasswordPdfResult
    data object StorageUnavailable : PasswordPdfResult
    data object ResourceLimit : PasswordPdfResult
    data object Failed : PasswordPdfResult
}

/** Creates a separate encrypted file. The caller owns the finished file and supplied password. */
class PasswordProtectedPdf(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val availableBytes: (File) -> Long = File::getUsableSpace,
    private val availableHeap: () -> Long = {
        Runtime.getRuntime().run {
            // R6's temporary KDF buffers may be unreachable but not collected between exports.
            // Retry the same admission threshold after one collection, never lower the budget.
            fun remaining() = maxMemory() - totalMemory() + freeMemory()
            if (remaining() < 32L * 1024 * 1024) gc()
            remaining()
        }
    },
) {
    suspend fun create(source: File, output: File, password: CharArray): PasswordPdfResult {
        // Freeze the caller-owned mutable password before switching dispatchers.
        val localPassword = password.copyOf()
        val ownerBytes = ByteArray(32)
        var created = false
        var delivered = false
        var scratch: File? = null
        try {
            val result = withContext(ioDispatcher) {
                try {
                    coroutineContext.ensureActive()
                    if (!isSupportedPdfPassword(localPassword)) return@withContext PasswordPdfResult.InvalidPassword
                    val parent = output.absoluteFile.parentFile
                    if (!source.isFile || source.length() !in 1..MAX_SOURCE_BYTES || output.exists() ||
                        source.canonicalFile == output.canonicalFile || parent?.isDirectory != true
                    ) return@withContext PasswordPdfResult.InvalidSource
                    if (availableBytes(parent) < source.length() * 2L + 64L * 1024 * 1024) {
                        return@withContext PasswordPdfResult.StorageUnavailable
                    }
                    if (availableHeap() < 32L * 1024 * 1024) {
                        return@withContext PasswordPdfResult.ResourceLimit
                    }
                    val inputDigest = fingerprint(source)
                    // Reserve our own destination; never delete or replace a pre-existing caller file.
                    if (!output.createNewFile()) return@withContext PasswordPdfResult.InvalidSource
                    created = true
                    val ownedScratch = File(parent, "password-work-${UUID.randomUUID()}")
                    check(ownedScratch.mkdir())
                    scratch = ownedScratch
                    fun memory() = MemoryUsageSetting.setupTempFileOnly().setTempDir(ownedScratch)
                    SecureRandom().nextBytes(ownerBytes)
                    // PDFBox requires immutable strings internally; neither value leaves this call.
                    val userPassword = String(localPassword)
                    val pages = PDDocument.load(source, memory()).use { pdf ->
                        if (pdf.isEncrypted || pdf.numberOfPages !in 1..1000 || pdf.signatureDictionaries.isNotEmpty()) {
                            return@withContext PasswordPdfResult.InvalidSource
                        }
                        val policy = StandardProtectionPolicy(
                            Base64.getEncoder().withoutPadding().encodeToString(ownerBytes),
                            userPassword,
                            AccessPermission(),
                        ).apply {
                            setEncryptionKeyLength(256)
                            setPreferAES(true)
                        }
                        pdf.protect(policy)
                        coroutineContext.ensureActive()
                        pdf.save(output)
                        pdf.numberOfPages
                    }
                    coroutineContext.ensureActive()
                    // Opening without a password must fail, and the supplied password must recover every page.
                    try {
                        PDDocument.load(output, memory()).use { }
                        return@withContext PasswordPdfResult.Failed
                    } catch (_: InvalidPasswordException) {
                        // Expected: the PDF is protected against opening without its password.
                    }
                    PDDocument.load(output, userPassword, memory()).use { verified ->
                        if (!verified.isEncrypted || verified.numberOfPages != pages ||
                            verified.encryption?.length != 256 || verified.encryption?.version != 5 ||
                            verified.encryption?.revision != 6
                        ) return@withContext PasswordPdfResult.Failed
                    }
                    coroutineContext.ensureActive()
                    if (!inputDigest.contentEquals(fingerprint(source))) {
                        return@withContext PasswordPdfResult.InvalidSource
                    }
                    PasswordPdfResult.Success(pages)
                } finally {
                    // This runs on the IO worker before its result crosses a cancellation boundary.
                    scratch?.let { directory ->
                        if (!directory.deleteRecursively()) throw IOException("PDF scratch cleanup failed")
                        scratch = null
                    }
                }
            }
            // Keep only a result actually delivered across withContext's cancellation boundary.
            delivered = result is PasswordPdfResult.Success
            return result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: InvalidPasswordException) {
            return PasswordPdfResult.InvalidSource
        } catch (_: OutOfMemoryError) {
            return PasswordPdfResult.ResourceLimit
        } catch (_: Exception) {
            return PasswordPdfResult.Failed
        } finally {
            localPassword.fill('\u0000')
            ownerBytes.fill(0)
            if (!delivered && (created || scratch != null)) {
                withContext(NonCancellable + Dispatchers.IO) {
                    if (created) runCatching { output.delete() }
                    scratch?.let { runCatching { it.deleteRecursively() } }
                }
            }
        }
    }

    private suspend fun fingerprint(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        file.inputStream().use { input ->
            while (true) {
                coroutineContext.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest()
    }

    private companion object {
        const val MAX_SOURCE_BYTES = 256L * 1024 * 1024
    }
}
