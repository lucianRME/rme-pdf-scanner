package org.synapseworks.pageharbor.library.duplicate

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs

/** Content identity excludes titles, folders, timestamps, OCR text, and derived thumbnails. */
data class FingerprintPage(
    val assetSha256: String,
    val mimeType: String,
    val byteLength: Long,
    val rotationDegrees: Int,
    val filterName: String,
)

data class DocumentFingerprint(
    val version: Int,
    val sha256: String,
)

object DocumentFingerprintV1 {
    const val VERSION = 1
    private val domain = "RME-DOCUMENT-FINGERPRINT-V1\u0000"
        .toByteArray(StandardCharsets.US_ASCII)
    private val lowercaseSha256 = Regex("[0-9a-f]{64}")

    fun calculate(pages: List<FingerprintPage>): DocumentFingerprint {
        require(pages.isNotEmpty()) { "A document fingerprint requires at least one page." }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(domain)
        digest.updateInt(pages.size)
        pages.forEachIndexed { position, page ->
            require(page.assetSha256.matches(lowercaseSha256)) {
                "Page hashes must be lowercase SHA-256 values."
            }
            require(page.byteLength >= 0L) { "Page byte lengths cannot be negative." }
            require(page.rotationDegrees in setOf(0, 90, 180, 270)) {
                "Unsupported page rotation."
            }
            digest.updateInt(position)
            digest.update(hexToBytes(page.assetSha256))
            digest.updateLengthPrefixed(page.mimeType.lowercase(Locale.ROOT))
            digest.updateLong(page.byteLength)
            digest.updateInt(page.rotationDegrees)
            digest.updateLengthPrefixed(page.filterName)
        }
        return DocumentFingerprint(VERSION, digest.digest().toHex())
    }
}

enum class DuplicateKind {
    EXACT,
    POSSIBLE,
    DIFFERENT,
}

data class DuplicateCandidate(
    val documentId: String,
    val contentHashVersion: Int?,
    val contentSha256: String?,
    val sourceSha256: Set<String> = emptySet(),
    val pageCount: Int,
    val contentByteLength: Long?,
    val orderedMimeTypes: List<String> = emptyList(),
)

data class IncomingDocumentIdentity(
    val fingerprint: DocumentFingerprint?,
    val sourceSha256: Set<String> = emptySet(),
    val pageCount: Int,
    val contentByteLength: Long?,
    val orderedMimeTypes: List<String> = emptyList(),
)

data class DuplicateMatch(
    val kind: DuplicateKind,
    val documentId: String?,
)

object DuplicateDetector {
    /**
     * EXACT matches may be skipped only after confirmation immediately before activation.
     * POSSIBLE is advisory and must always require an explicit user decision.
     */
    fun classify(
        incoming: IncomingDocumentIdentity,
        existing: Iterable<DuplicateCandidate>,
    ): DuplicateMatch {
        val candidates = existing.toList()
        candidates.firstOrNull { candidate ->
            val fingerprint = incoming.fingerprint
            fingerprint != null &&
                candidate.contentHashVersion == fingerprint.version &&
                candidate.contentSha256 == fingerprint.sha256
        }?.let { return DuplicateMatch(DuplicateKind.EXACT, it.documentId) }

        if (incoming.sourceSha256.isNotEmpty()) {
            candidates.firstOrNull { candidate ->
                candidate.sourceSha256.any(incoming.sourceSha256::contains)
            }?.let {
                // The same original PDF may have different current edits. A source match is useful
                // evidence, but only the logical page fingerprint can justify an exact skip.
                return DuplicateMatch(DuplicateKind.POSSIBLE, it.documentId)
            }
        }

        candidates.firstOrNull { candidate ->
            candidate.pageCount == incoming.pageCount &&
                mimeStructureMatches(incoming.orderedMimeTypes, candidate.orderedMimeTypes) &&
                approximateLengthMatches(incoming.contentByteLength, candidate.contentByteLength)
        }?.let { return DuplicateMatch(DuplicateKind.POSSIBLE, it.documentId) }

        return DuplicateMatch(DuplicateKind.DIFFERENT, null)
    }

    private fun mimeStructureMatches(first: List<String>, second: List<String>): Boolean =
        first.isEmpty() || second.isEmpty() ||
            first.map { it.lowercase(Locale.ROOT) } == second.map { it.lowercase(Locale.ROOT) }

    private fun approximateLengthMatches(first: Long?, second: Long?): Boolean {
        if (first == null || second == null || first < 0L || second < 0L) return true
        if (first == second) return true
        val larger = maxOf(first, second)
        if (larger == 0L) return true
        return abs(first - second).toDouble() / larger.toDouble() <= 0.05
    }
}

private fun MessageDigest.updateInt(value: Int) {
    update(ByteBuffer.allocate(Int.SIZE_BYTES).order(ByteOrder.BIG_ENDIAN).putInt(value).array())
}

private fun MessageDigest.updateLong(value: Long) {
    update(ByteBuffer.allocate(Long.SIZE_BYTES).order(ByteOrder.BIG_ENDIAN).putLong(value).array())
}

private fun MessageDigest.updateLengthPrefixed(value: String) {
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    updateInt(bytes.size)
    update(bytes)
}

private fun hexToBytes(value: String): ByteArray = ByteArray(value.length / 2) { index ->
    value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
}

private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }
