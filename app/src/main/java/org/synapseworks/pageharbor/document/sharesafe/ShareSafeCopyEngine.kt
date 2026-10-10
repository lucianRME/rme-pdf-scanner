package org.synapseworks.pageharbor.document.sharesafe

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.synapseworks.pageharbor.document.redaction.RedactedPdfVerificationResult
import org.synapseworks.pageharbor.document.redaction.RedactedPdfVerifier
import org.synapseworks.pageharbor.document.redaction.SecureRedactedPdfEngine
import org.synapseworks.pageharbor.document.redaction.SecureRedactedPdfFailure
import org.synapseworks.pageharbor.document.redaction.SecureRedactedPdfRequest
import org.synapseworks.pageharbor.document.redaction.SecureRedactedPdfResult

/** One orchestration pipeline shared by all recipes and both general-document/card entry paths. */
internal class ShareSafeCopyEngine(context: Context) {
    private val applicationContext = context.applicationContext ?: context
    private val sanitizer = SecureRedactedPdfEngine(applicationContext)
    private val watermarkEngine = ShareSafeWatermarkEngine(applicationContext)
    private val targetAdapter = ShareSafeTargetSizeAdapter(applicationContext)
    private val verifier = RedactedPdfVerifier(applicationContext)
    private val publishedRoot = File(applicationContext.cacheDir, SHARE_SAFE_PUBLISHED_DIRECTORY)

    suspend fun prepareDocument(
        input: ShareSafeDocumentInput,
        recipe: ShareSafeRecipe,
        watermark: ShareSafeWatermarkConfig,
        targetSize: ShareSafeTargetSizeConfig,
        expectedGeneration: Long,
        onProgress: (ShareSafePipelineStage) -> Unit = {},
    ): ShareSafePrepareResult {
        if (input.sourceGeneration != expectedGeneration) {
            return ShareSafePrepareResult.Failure(ShareSafeFailure.SOURCE_CHANGED)
        }
        onProgress(ShareSafePipelineStage.SANITIZING)
        val sanitized = sanitizer.prepare(
            SecureRedactedPdfRequest(
                pages = input.pages.map { page -> page.source.copy(regions = page.redactions) },
                requireAtLeastOneRedaction = false,
            ),
        )
        val prepared = when (sanitized) {
            is SecureRedactedPdfResult.Success -> sanitized.prepared
            is SecureRedactedPdfResult.Failure -> return ShareSafePrepareResult.Failure(
                sanitized.reason.toShareSafeFailure(),
            )
        }
        val artifact = prepared.toShareSafeAttestation(expectedGeneration)
        if (artifact == null) {
            sanitizer.discard(prepared)
            return ShareSafePrepareResult.Failure(ShareSafeFailure.ATTESTATION_FAILED)
        }
        val textStatus = when {
            prepared.textLayerPageCount == prepared.pageCount ->
                ShareSafeSearchableTextStatus.PRESERVED
            prepared.textLayerPageCount > 0 -> ShareSafeSearchableTextStatus.PARTIALLY_PRESERVED
            else -> ShareSafeSearchableTextStatus.NOT_PRESENT
        }
        return processSanitized(
            initial = artifact,
            recipe = recipe,
            watermark = watermark,
            targetSize = targetSize,
            expectedGeneration = expectedGeneration,
            sanitizationMillis = prepared.performance.sourceRenderAndRedactionMillis +
                prepared.performance.pdfRebuildMillis + prepared.performance.verificationMillis,
            initialPeakTemporaryBytes = prepared.performance.peakTemporaryBytes,
            initialPeakBitmapBytes = prepared.performance.estimatedPeakBitmapBytes,
            initialTextStatus = textStatus,
            onProgress = onProgress,
        )
    }

    suspend fun prepareAttestedCardCopy(
        initial: ShareSafeAttestedArtifact,
        recipe: ShareSafeRecipe,
        watermark: ShareSafeWatermarkConfig,
        targetSize: ShareSafeTargetSizeConfig,
        expectedGeneration: Long,
        onProgress: (ShareSafePipelineStage) -> Unit = {},
    ): ShareSafePrepareResult = processSanitized(
        initial = initial,
        recipe = recipe,
        watermark = watermark,
        targetSize = targetSize,
        expectedGeneration = expectedGeneration,
        sanitizationMillis = 0L,
        initialPeakTemporaryBytes = initial.evidence.bytes,
        initialPeakBitmapBytes = 0L,
        initialTextStatus = if (initial.evidence.searchableTextPageCount > 0) {
            ShareSafeSearchableTextStatus.PARTIALLY_PRESERVED
        } else {
            ShareSafeSearchableTextStatus.NOT_PRESENT
        },
        onProgress = onProgress,
    )

    private suspend fun processSanitized(
        initial: ShareSafeAttestedArtifact,
        recipe: ShareSafeRecipe,
        watermark: ShareSafeWatermarkConfig,
        targetSize: ShareSafeTargetSizeConfig,
        expectedGeneration: Long,
        sanitizationMillis: Long,
        initialPeakTemporaryBytes: Long,
        initialPeakBitmapBytes: Long,
        initialTextStatus: ShareSafeSearchableTextStatus,
        onProgress: (ShareSafePipelineStage) -> Unit,
    ): ShareSafePrepareResult {
        if (!watermark.isValid) {
            discardArtifact(initial)
            return ShareSafePrepareResult.Failure(ShareSafeFailure.INVALID_WATERMARK)
        }
        var current = initial
        var watermarkMillis = 0L
        var compressionMillis = 0L
        var peakTemporaryBytes = initialPeakTemporaryBytes
        var peakBitmapBytes = initialPeakBitmapBytes
        var searchableTextStatus = initialTextStatus
        try {
            if (watermark.enabled) {
                onProgress(ShareSafePipelineStage.WATERMARKING)
                when (val result = watermarkEngine.apply(current, watermark, expectedGeneration)) {
                    is ShareSafeWatermarkResult.Success -> {
                        val previous = current
                        current = result.artifact
                        discardArtifact(previous)
                        watermarkMillis = result.elapsedMillis
                        peakTemporaryBytes = maxOf(peakTemporaryBytes, result.peakTemporaryBytes)
                        peakBitmapBytes = maxOf(peakBitmapBytes, result.estimatedPeakBitmapBytes)
                        searchableTextStatus = ShareSafeSearchableTextStatus.NOT_PRESENT
                    }
                    is ShareSafeWatermarkResult.Failure -> {
                        discardArtifact(current)
                        return ShareSafePrepareResult.Failure(result.reason)
                    }
                }
            }
            if (targetSize.enabled) {
                onProgress(ShareSafePipelineStage.COMPRESSING)
                when (val result = targetAdapter.compress(
                    source = current,
                    targetBytes = targetSize.maximumBytes,
                    expectedGeneration = expectedGeneration,
                )) {
                    is ShareSafeTargetSizeResult.Success -> {
                        val previous = current
                        current = result.artifact
                        if (result.outcome == ShareSafeTargetSizeResult.Outcome.COMPRESSED_TO_TARGET) {
                            discardArtifact(previous)
                        }
                        compressionMillis = result.elapsedMillis
                        peakTemporaryBytes = maxOf(peakTemporaryBytes, result.peakTemporaryBytes)
                        peakBitmapBytes = maxOf(peakBitmapBytes, result.estimatedPeakBitmapBytes)
                        if (result.outcome == ShareSafeTargetSizeResult.Outcome.COMPRESSED_TO_TARGET) {
                            searchableTextStatus = ShareSafeSearchableTextStatus.NOT_PRESENT
                        }
                    }
                    is ShareSafeTargetSizeResult.Failure -> {
                        discardArtifact(current)
                        return ShareSafePrepareResult.Failure(result.reason)
                    }
                }
            }
            onProgress(ShareSafePipelineStage.FINAL_VERIFYING)
            val verificationStarted = SystemClock.elapsedRealtime()
            val finalArtifact = publishAndVerify(current, expectedGeneration)
            val verificationMillis = SystemClock.elapsedRealtime() - verificationStarted
            if (finalArtifact == null) {
                discardArtifact(current)
                return ShareSafePrepareResult.Failure(ShareSafeFailure.FINAL_VERIFICATION_FAILED)
            }
            discardArtifact(current)
            return ShareSafePrepareResult.Success(
                ShareSafePreparedPdf(
                    artifact = finalArtifact,
                    recipe = recipe,
                    watermark = watermark,
                    targetSize = targetSize,
                    searchableTextStatus = searchableTextStatus,
                    performance = ShareSafePerformance(
                        sanitizationMillis = sanitizationMillis,
                        watermarkMillis = watermarkMillis,
                        compressionMillis = compressionMillis,
                        finalVerificationMillis = verificationMillis,
                        peakTemporaryBytes = peakTemporaryBytes.coerceAtLeast(finalArtifact.evidence.bytes),
                        estimatedPeakBitmapBytes = peakBitmapBytes,
                    ),
                ),
            )
        } catch (error: CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) { discardArtifact(current) }
            throw error
        } catch (_: Exception) {
            discardArtifact(current)
            return ShareSafePrepareResult.Failure(ShareSafeFailure.PREPARATION_FAILED)
        }
    }

    suspend fun revalidateFinal(
        prepared: ShareSafePreparedPdf,
        expectedGeneration: Long,
    ): Boolean = prepared.artifact.stage == ShareSafeArtifactStage.FINAL &&
        prepared.artifact.watermarkConfigDigest == prepared.watermark
            .takeIf(ShareSafeWatermarkConfig::enabled)?.digest() &&
        (!prepared.targetSize.enabled ||
            prepared.bytes <= prepared.targetSize.maximumBytes &&
            prepared.artifact.targetBytes == prepared.targetSize.maximumBytes) &&
        prepared.artifact.revalidate(verifier, expectedGeneration)

    fun discard(prepared: ShareSafePreparedPdf) = discardArtifact(prepared.artifact)

    private suspend fun publishAndVerify(
        source: ShareSafeAttestedArtifact,
        expectedGeneration: Long,
    ): ShareSafeAttestedArtifact? {
        if (!source.revalidate(verifier, expectedGeneration)) return null
        val directory = createPublishedDirectory() ?: return null
        val destination = File(directory, SHARE_SAFE_FINAL_NAME)
        try {
            source.file.inputStream().use { input ->
                FileOutputStream(destination).use { output ->
                    input.copyTo(output, 32 * 1_024)
                    output.fd.sync()
                }
            }
            val verification = verifier.verify(destination, source.policy)
                as? RedactedPdfVerificationResult.Verified ?: run {
                directory.deleteRecursively()
                return null
            }
            if (verification.evidence != source.evidence ||
                sha256(destination) != source.evidence.sha256
            ) {
                directory.deleteRecursively()
                return null
            }
            return ShareSafeAttestedArtifact(
                file = destination,
                ownedDirectory = directory,
                policy = source.policy,
                evidence = verification.evidence,
                sourceGeneration = expectedGeneration,
                stage = ShareSafeArtifactStage.FINAL,
                watermarkConfigDigest = source.watermarkConfigDigest,
                targetBytes = source.targetBytes,
            )
        } catch (_: IOException) {
            directory.deleteRecursively()
            return null
        } catch (_: SecurityException) {
            directory.deleteRecursively()
            return null
        }
    }

    private fun createPublishedDirectory(): File? {
        return try {
            if (!publishedRoot.exists() && !publishedRoot.mkdirs()) return null
            File.createTempFile("verified-share-safe-", ".work", publishedRoot).also { marker ->
                if (!marker.delete() || !marker.mkdir()) return null
            }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private fun discardArtifact(artifact: ShareSafeAttestedArtifact) {
        artifact.ownedDirectory.deleteRecursively()
    }
}

internal fun deleteStaleShareSafeFiles(
    cacheDirectory: File,
    nowMillis: Long = System.currentTimeMillis(),
    minimumAgeMillis: Long = 24L * 60L * 60L * 1_000L,
) {
    require(minimumAgeMillis >= 0L)
    listOf(
        File(cacheDirectory, SHARE_SAFE_WORK_DIRECTORY),
        File(cacheDirectory, SHARE_SAFE_PUBLISHED_DIRECTORY),
    ).forEach { root ->
        root.listFiles()?.forEach { file ->
            if (file.lastModified() <= nowMillis &&
                nowMillis - file.lastModified() >= minimumAgeMillis
            ) file.deleteRecursively()
        }
    }
}

private fun SecureRedactedPdfFailure.toShareSafeFailure(): ShareSafeFailure = when (this) {
    SecureRedactedPdfFailure.INVALID_REDACTION -> ShareSafeFailure.INVALID_REDACTION
    SecureRedactedPdfFailure.SOURCE_CHANGED -> ShareSafeFailure.SOURCE_CHANGED
    SecureRedactedPdfFailure.EMPTY_REQUEST,
    SecureRedactedPdfFailure.SOURCE_MISSING,
    SecureRedactedPdfFailure.SOURCE_TOO_LARGE,
    -> ShareSafeFailure.INVALID_SOURCE
    SecureRedactedPdfFailure.SECURITY_VERIFICATION_FAILED ->
        ShareSafeFailure.FINAL_VERIFICATION_FAILED
    SecureRedactedPdfFailure.RASTERIZATION_FAILED,
    SecureRedactedPdfFailure.TEXT_SANITIZATION_FAILED,
    SecureRedactedPdfFailure.TEMPORARY_STORAGE_UNAVAILABLE,
    SecureRedactedPdfFailure.GENERATION_FAILED,
    -> ShareSafeFailure.PREPARATION_FAILED
}

internal const val SHARE_SAFE_PUBLISHED_DIRECTORY = "share-safe-pdfs/verified"
private const val SHARE_SAFE_FINAL_NAME = "share-safe-copy.pdf"
