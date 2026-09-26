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
        return Builder(pages.size).apply { pages.forEach(::addPage) }.finish()
    }

    class Builder(private val expectedPageCount: Int) {
        val digest = MessageDigest.getInstance("SHA-256")
        private var pageCount = 0
        private var finished = false

        init {
            require(expectedPageCount > 0) { "A document fingerprint requires at least one page." }
            digest.update(domain)
            digest.updateInt(expectedPageCount)
        }

        fun addPage(page: FingerprintPage) {
            check(!finished && pageCount < expectedPageCount)
            require(page.assetSha256.matches(lowercaseSha256)) {
                "Page hashes must be lowercase SHA-256 values."
            }
            require(page.byteLength >= 0L) { "Page byte lengths cannot be negative." }
            require(page.rotationDegrees in setOf(0, 90, 180, 270)) {
                "Unsupported page rotation."
            }
            digest.updateInt(pageCount)
            digest.update(hexToBytes(page.assetSha256))
            digest.updateLengthPrefixed(page.mimeType.lowercase(Locale.ROOT))
            digest.updateLong(page.byteLength)
            digest.updateInt(page.rotationDegrees)
            digest.updateLengthPrefixed(page.filterName)
            pageCount += 1
        }

        fun finish(): DocumentFingerprint {
            check(!finished && pageCount == expectedPageCount)
            finished = true
            return DocumentFingerprint(VERSION, digest.digest().toHex())
        }
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
    val ocrStateDigestVersion: Int? = null,
    val ocrStateSha256: String? = null,
)

data class IncomingDocumentIdentity(
    val fingerprint: DocumentFingerprint?,
    val sourceSha256: Set<String> = emptySet(),
    val pageCount: Int,
    val contentByteLength: Long?,
    val orderedMimeTypes: List<String> = emptyList(),
    val ocrStateDigestVersion: Int? = null,
    val ocrStateSha256: String? = null,
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
        var sameContentDocumentId: String? = null
        var sameSourceDocumentId: String? = null
        var similarShapeDocumentId: String? = null
        for (candidate in existing) {
            val fingerprint = incoming.fingerprint
            val sameContent = fingerprint != null &&
                candidate.contentHashVersion == fingerprint.version &&
                candidate.contentSha256 == fingerprint.sha256
            if (sameContent) {
                val sameOcrState = incoming.ocrStateDigestVersion != null &&
                    incoming.ocrStateSha256 != null &&
                    candidate.ocrStateDigestVersion == incoming.ocrStateDigestVersion &&
                    candidate.ocrStateSha256 == incoming.ocrStateSha256
                if (sameOcrState) return DuplicateMatch(DuplicateKind.EXACT, candidate.documentId)
                if (sameContentDocumentId == null) sameContentDocumentId = candidate.documentId
            } else if (sameSourceDocumentId == null && incoming.sourceSha256.isNotEmpty() &&
                candidate.sourceSha256.any(incoming.sourceSha256::contains)
            ) {
                sameSourceDocumentId = candidate.documentId
            } else if (similarShapeDocumentId == null &&
                candidate.pageCount == incoming.pageCount &&
                mimeStructureMatches(incoming.orderedMimeTypes, candidate.orderedMimeTypes) &&
                approximateLengthMatches(incoming.contentByteLength, candidate.contentByteLength)
            ) {
                similarShapeDocumentId = candidate.documentId
            }
        }
        sameContentDocumentId?.let { return DuplicateMatch(DuplicateKind.POSSIBLE, it) }
        // The same original PDF may have different current edits. This is advisory only.
        sameSourceDocumentId?.let { return DuplicateMatch(DuplicateKind.POSSIBLE, it) }
        similarShapeDocumentId?.let { return DuplicateMatch(DuplicateKind.POSSIBLE, it) }
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
