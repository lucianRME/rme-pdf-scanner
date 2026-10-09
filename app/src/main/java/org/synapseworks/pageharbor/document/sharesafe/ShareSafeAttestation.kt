package org.synapseworks.pageharbor.document.sharesafe

import java.io.File
import java.security.MessageDigest
import org.synapseworks.pageharbor.document.cardcopy.CardCopyPreparedPdf
import org.synapseworks.pageharbor.document.redaction.RedactedPdfVerificationEvidence
import org.synapseworks.pageharbor.document.redaction.RedactedPdfVerificationPolicy
import org.synapseworks.pageharbor.document.redaction.RedactedPdfVerificationResult
import org.synapseworks.pageharbor.document.redaction.RedactedPdfVerifier
import org.synapseworks.pageharbor.document.redaction.SecureRedactedPreparedPdf

internal enum class ShareSafeArtifactStage { SANITIZED, WATERMARKED, TARGET_SIZED, FINAL }

/**
 * Capability object for the only PDFs allowed to cross M8 stages. Construction is module-private,
 * so an arbitrary path cannot be passed to watermarking, compression, Save, or Share.
 */
@ConsistentCopyVisibility
internal data class ShareSafeAttestedArtifact internal constructor(
    val file: File,
    val ownedDirectory: File,
    val policy: RedactedPdfVerificationPolicy,
    val evidence: RedactedPdfVerificationEvidence,
    val sourceGeneration: Long,
    val stage: ShareSafeArtifactStage,
    val watermarkConfigDigest: String? = null,
    val targetBytes: Long? = null,
) {
    init {
        require(sourceGeneration >= 0L)
        require(file.isFile)
        require(evidence.bytes == file.length())
        require(targetBytes == null || targetBytes > 0L)
    }

    suspend fun revalidate(
        verifier: RedactedPdfVerifier,
        expectedGeneration: Long,
    ): Boolean {
        if (sourceGeneration != expectedGeneration || !file.isFile ||
            file.length() != evidence.bytes || sha256(file) != evidence.sha256 ||
            !verifier.stillMatches(file, evidence)
        ) return false
        val checked = verifier.verify(file, policy) as? RedactedPdfVerificationResult.Verified
            ?: return false
        return checked.evidence == evidence &&
            (targetBytes == null || evidence.bytes <= targetBytes)
    }
}

data class ShareSafePerformance(
    val sanitizationMillis: Long,
    val watermarkMillis: Long,
    val compressionMillis: Long,
    val finalVerificationMillis: Long,
    val peakTemporaryBytes: Long,
    val estimatedPeakBitmapBytes: Long,
) {
    init {
        require(
            listOf(
                sanitizationMillis,
                watermarkMillis,
                compressionMillis,
                finalVerificationMillis,
                peakTemporaryBytes,
                estimatedPeakBitmapBytes,
            ).all { it >= 0L },
        )
    }

    val totalMillis: Long
        get() = sanitizationMillis + watermarkMillis + compressionMillis + finalVerificationMillis
}

class ShareSafePreparedPdf internal constructor(
    internal val artifact: ShareSafeAttestedArtifact,
    val recipe: ShareSafeRecipe,
    val watermark: ShareSafeWatermarkConfig,
    val targetSize: ShareSafeTargetSizeConfig,
    val searchableTextStatus: ShareSafeSearchableTextStatus,
    val performance: ShareSafePerformance,
) {
    val bytes: Long get() = artifact.evidence.bytes
    val pageCount: Int get() = artifact.evidence.pageCount
    val sha256: String get() = artifact.evidence.sha256
}

internal sealed interface ShareSafePrepareResult {
    data class Success(val prepared: ShareSafePreparedPdf) : ShareSafePrepareResult
    data class Failure(val reason: ShareSafeFailure) : ShareSafePrepareResult
}

internal fun SecureRedactedPreparedPdf.toShareSafeAttestation(
    sourceGeneration: Long,
): ShareSafeAttestedArtifact? = takeIf {
    securelyVerified && sourceHashesVerified && verificationEvidence.bytes == file.length()
}?.let {
    ShareSafeAttestedArtifact(
        file = file,
        ownedDirectory = workingDirectory,
        policy = verificationPolicy,
        evidence = verificationEvidence,
        sourceGeneration = sourceGeneration,
        stage = ShareSafeArtifactStage.SANITIZED,
    )
}

internal fun CardCopyPreparedPdf.toShareSafeAttestation(
    sourceGeneration: Long,
): ShareSafeAttestedArtifact? {
    val policy = verificationPolicy ?: return null
    val evidence = verificationEvidence ?: return null
    if (!securelyVerified || evidence.bytes != bytes || file.length() != bytes) return null
    return ShareSafeAttestedArtifact(
        file = file,
        ownedDirectory = workingDirectory,
        policy = policy,
        evidence = evidence,
        sourceGeneration = sourceGeneration,
        stage = ShareSafeArtifactStage.SANITIZED,
    )
}

internal fun sha256(file: File): String? = try {
    file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(32 * 1_024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
} catch (_: Exception) {
    null
}
