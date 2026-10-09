package org.synapseworks.pageharbor.document.cardcopy

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.InputStream
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.synapseworks.pageharbor.document.redaction.RedactedPdfVerificationResult
import org.synapseworks.pageharbor.document.redaction.RedactedPdfVerifier

internal sealed interface CardCopyDestinationResult {
    data class Success(val bytes: Long) : CardCopyDestinationResult
    data class Failure(val reason: CardCopyFailure) : CardCopyDestinationResult
}

internal class CardCopyDestinationWriter(private val context: Context) {
    private val verifier = RedactedPdfVerifier(context)

    suspend fun write(
        prepared: CardCopyPreparedPdf,
        destination: Uri,
    ): CardCopyDestinationResult = withContext(Dispatchers.IO) {
        val policy = prepared.verificationPolicy
        val evidence = prepared.verificationEvidence
        if (!prepared.securelyVerified || policy == null || evidence == null ||
            !prepared.file.isFile ||
            prepared.file.length() != prepared.bytes ||
            !verifier.stillMatches(prepared.file, evidence)
        ) {
            return@withContext CardCopyDestinationResult.Failure(CardCopyFailure.VALIDATION_FAILED)
        }
        val output = try {
            context.contentResolver.openOutputStream(destination, "wt")
        } catch (_: Exception) {
            null
        } ?: return@withContext CardCopyDestinationResult.Failure(
            CardCopyFailure.DESTINATION_UNAVAILABLE,
        )
        try {
            prepared.file.inputStream().use { input ->
                output.use { target ->
                    val buffer = ByteArray(32 * 1_024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        target.write(buffer, 0, count)
                    }
                    target.flush()
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return@withContext CardCopyDestinationResult.Failure(CardCopyFailure.WRITE_FAILED)
        }
        if (!verify(prepared, destination)) {
            return@withContext CardCopyDestinationResult.Failure(CardCopyFailure.VALIDATION_FAILED)
        }
        CardCopyDestinationResult.Success(prepared.bytes)
    }

    private suspend fun verify(prepared: CardCopyPreparedPdf, destination: Uri): Boolean {
        val policy = prepared.verificationPolicy ?: return false
        val expectedEvidence = prepared.verificationEvidence ?: return false
        val sourceDigest = digest(prepared.file.inputStream()) ?: return false
        if (sourceDigest.first != prepared.bytes ||
            sourceDigest.second.toHexString() != prepared.outputSha256
        ) return false
        val destinationDigest = try {
            context.contentResolver.openInputStream(destination)?.let { digest(it) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        } ?: return false
        if (sourceDigest.first != destinationDigest.first ||
            !sourceDigest.second.contentEquals(destinationDigest.second)
        ) return false
        val verified = verifier.verify(destination, policy)
        val destinationEvidence =
            (verified as? RedactedPdfVerificationResult.Verified)?.evidence ?: return false
        return destinationEvidence.sha256 == expectedEvidence.sha256 &&
            destinationEvidence.bytes == expectedEvidence.bytes &&
            destinationEvidence.policySha256 == expectedEvidence.policySha256
    }

    private suspend fun digest(input: InputStream): Pair<Long, ByteArray>? = try {
        input.use { source ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(32 * 1_024)
            var bytes = 0L
            while (true) {
                coroutineContext.ensureActive()
                val count = source.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
                bytes += count
            }
            bytes to digest.digest()
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }
}

fun cardCopyShareUri(context: Context, prepared: CardCopyPreparedPdf): Uri? = try {
    val evidence = prepared.verificationEvidence
    if (!prepared.securelyVerified || evidence == null || !prepared.file.isFile ||
        prepared.file.length() != prepared.bytes ||
        prepared.bytes != evidence.bytes ||
        cardCopySha256(prepared.file) != evidence.sha256
    ) null else {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", prepared.file)
    }
} catch (_: RuntimeException) {
    null
}

private fun ByteArray.toHexString(): String =
    joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
