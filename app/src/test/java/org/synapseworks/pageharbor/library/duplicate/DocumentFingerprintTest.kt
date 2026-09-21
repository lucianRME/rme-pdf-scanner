package org.synapseworks.pageharbor.library.duplicate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DocumentFingerprintTest {
    private val firstPage = FingerprintPage(
        assetSha256 = "11".repeat(32),
        mimeType = "image/jpeg",
        byteLength = 1_024,
        rotationDegrees = 0,
        filterName = "ORIGINAL",
    )
    private val secondPage = FingerprintPage(
        assetSha256 = "22".repeat(32),
        mimeType = "image/png",
        byteLength = 2_048,
        rotationDegrees = 90,
        filterName = "GRAYSCALE",
    )

    @Test
    fun fingerprintIsDeterministicAndOrderSensitive() {
        val first = DocumentFingerprintV1.calculate(listOf(firstPage, secondPage))
        val repeated = DocumentFingerprintV1.calculate(listOf(firstPage, secondPage))
        val reversed = DocumentFingerprintV1.calculate(listOf(secondPage, firstPage))

        assertEquals(first, repeated)
        assertEquals(1, first.version)
        assertEquals(64, first.sha256.length)
        assertNotEquals(first, reversed)
    }

    @Test
    fun transformsAndMimeArePartOfIdentityButOcrAndNamesAreNotInputs() {
        val original = DocumentFingerprintV1.calculate(listOf(firstPage))

        assertNotEquals(
            original,
            DocumentFingerprintV1.calculate(listOf(firstPage.copy(rotationDegrees = 90))),
        )
        assertNotEquals(
            original,
            DocumentFingerprintV1.calculate(listOf(firstPage.copy(filterName = "ENHANCE"))),
        )
        assertNotEquals(
            original,
            DocumentFingerprintV1.calculate(listOf(firstPage.copy(mimeType = "image/png"))),
        )
    }

    @Test
    fun invalidInputsFailClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            DocumentFingerprintV1.calculate(emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            DocumentFingerprintV1.calculate(listOf(firstPage.copy(assetSha256 = "not-a-hash")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DocumentFingerprintV1.calculate(listOf(firstPage.copy(rotationDegrees = 45)))
        }
    }

    @Test
    fun detectorPrefersLogicalExactAndTreatsSourceOnlyMatchAsPossible() {
        val fingerprint = DocumentFingerprintV1.calculate(listOf(firstPage))
        val incoming = IncomingDocumentIdentity(
            fingerprint = fingerprint,
            sourceSha256 = setOf("aa".repeat(32)),
            pageCount = 1,
            contentByteLength = 1_024,
            orderedMimeTypes = listOf("image/jpeg"),
        )
        val sourceOnly = candidate("source", sourceHash = "aa".repeat(32))
        val logical = candidate(
            id = "logical",
            hashVersion = fingerprint.version,
            contentHash = fingerprint.sha256,
        )

        assertEquals(
            DuplicateMatch(DuplicateKind.EXACT, "logical"),
            DuplicateDetector.classify(incoming, listOf(sourceOnly, logical)),
        )
        assertEquals(
            DuplicateMatch(DuplicateKind.POSSIBLE, "source"),
            DuplicateDetector.classify(incoming.copy(fingerprint = null), listOf(sourceOnly)),
        )
    }

    @Test
    fun structuralSimilarityIsOnlyPossibleAndUnknownSizesStayUnknown() {
        val existing = candidate(id = "similar", bytes = null)
        val incoming = IncomingDocumentIdentity(
            fingerprint = null,
            pageCount = 1,
            contentByteLength = null,
            orderedMimeTypes = listOf("image/jpeg"),
        )

        assertEquals(
            DuplicateMatch(DuplicateKind.POSSIBLE, "similar"),
            DuplicateDetector.classify(incoming, listOf(existing)),
        )
    }

    @Test
    fun materiallyDifferentStructureIsDifferent() {
        val incoming = IncomingDocumentIdentity(
            fingerprint = null,
            pageCount = 2,
            contentByteLength = 100,
            orderedMimeTypes = listOf("image/jpeg", "image/jpeg"),
        )

        assertEquals(
            DuplicateMatch(DuplicateKind.DIFFERENT, null),
            DuplicateDetector.classify(incoming, listOf(candidate("one-page"))),
        )
    }

    private fun candidate(
        id: String,
        hashVersion: Int? = null,
        contentHash: String? = null,
        sourceHash: String? = null,
        bytes: Long? = 1_024,
    ) = DuplicateCandidate(
        documentId = id,
        contentHashVersion = hashVersion,
        contentSha256 = contentHash,
        sourceSha256 = sourceHash?.let(::setOf).orEmpty(),
        pageCount = 1,
        contentByteLength = bytes,
        orderedMimeTypes = listOf("image/jpeg"),
    )
}
