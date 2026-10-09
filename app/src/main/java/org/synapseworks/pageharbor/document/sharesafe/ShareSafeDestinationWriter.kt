package org.synapseworks.pageharbor.document.sharesafe

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.synapseworks.pageharbor.document.redaction.RedactedPdfVerificationPolicy
import org.synapseworks.pageharbor.document.redaction.RedactedPdfVerificationResult
import org.synapseworks.pageharbor.document.redaction.RedactedPdfVerifier

internal sealed interface ShareSafeDestinationResult {
    data class Success(val bytes: Long) : ShareSafeDestinationResult
    data class Failure(val reason: ShareSafeFailure) : ShareSafeDestinationResult
}

/** Writes one verified final capability, then reopens and security-verifies the destination URI. */
internal class ShareSafeDestinationWriter(
    context: Context,
    private val openOutput: (Uri) -> OutputStream? = { uri ->
        context.contentResolver.openOutputStream(uri, "wt")
    },
    private val openInput: (Uri) -> InputStream? = context.contentResolver::openInputStream,
    private val verifyDestination: suspend (Uri, RedactedPdfVerificationPolicy) ->
        RedactedPdfVerificationResult = { uri, policy ->
            RedactedPdfVerifier(context).verify(uri, policy)
        },
    /** Cleanup is opt-in because deleting an existing user-owned document is not generally safe. */
    private val cleanupPartialIfSafe: (Uri) -> Boolean = { false },
) {
    private val verifier = RedactedPdfVerifier(context)

    suspend fun write(
        prepared: ShareSafePreparedPdf,
        expectedGeneration: Long,
        destination: Uri,
    ): ShareSafeDestinationResult = withContext(Dispatchers.IO) {
        if (!prepared.artifact.revalidate(verifier, expectedGeneration) ||
            prepared.artifact.stage != ShareSafeArtifactStage.FINAL
        ) {
            return@withContext ShareSafeDestinationResult.Failure(
                ShareSafeFailure.FINAL_VERIFICATION_FAILED,
            )
        }
        val output = try {
            openOutput(destination)
        } catch (_: FileNotFoundException) {
            null
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } ?: return@withContext ShareSafeDestinationResult.Failure(
            ShareSafeFailure.DESTINATION_UNAVAILABLE,
        )
        try {
            prepared.artifact.file.inputStream().use { input ->
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
            runCatching { cleanupPartialIfSafe(destination) }
            throw error
        } catch (_: Exception) {
            runCatching { cleanupPartialIfSafe(destination) }
            return@withContext ShareSafeDestinationResult.Failure(ShareSafeFailure.WRITE_FAILED)
        }
        val destinationDigest = try {
            openInput(destination)?.let { digest(it) }
        } catch (error: CancellationException) {
            runCatching { cleanupPartialIfSafe(destination) }
            throw error
        } catch (_: Exception) {
            null
        }
        val expected = prepared.artifact.evidence
        if (destinationDigest == null || destinationDigest.bytes != expected.bytes ||
            destinationDigest.sha256 != expected.sha256
        ) {
            runCatching { cleanupPartialIfSafe(destination) }
            return@withContext ShareSafeDestinationResult.Failure(
                ShareSafeFailure.FINAL_VERIFICATION_FAILED,
            )
        }
        val verified = try {
            verifyDestination(destination, prepared.artifact.policy)
                as? RedactedPdfVerificationResult.Verified
        } catch (error: CancellationException) {
            runCatching { cleanupPartialIfSafe(destination) }
            throw error
        } catch (_: Exception) {
            null
        }
        if (verified?.evidence != expected) {
            runCatching { cleanupPartialIfSafe(destination) }
            return@withContext ShareSafeDestinationResult.Failure(
                ShareSafeFailure.FINAL_VERIFICATION_FAILED,
            )
        }
        ShareSafeDestinationResult.Success(expected.bytes)
    }

    private suspend fun digest(input: InputStream): StreamDigest? = try {
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
            StreamDigest(
                bytes,
                digest.digest().joinToString("") { byte ->
                    "%02x".format(byte.toInt() and 0xff)
                },
            )
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    private data class StreamDigest(val bytes: Long, val sha256: String)
}

internal suspend fun shareSafeShareUri(
    context: Context,
    prepared: ShareSafePreparedPdf,
    expectedGeneration: Long,
): Uri? {
    val artifact = prepared.artifact
    if (artifact.stage != ShareSafeArtifactStage.FINAL ||
        !artifact.revalidate(RedactedPdfVerifier(context), expectedGeneration)
    ) return null
    return try {
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            artifact.file,
        )
    } catch (_: RuntimeException) {
        null
    }
}
