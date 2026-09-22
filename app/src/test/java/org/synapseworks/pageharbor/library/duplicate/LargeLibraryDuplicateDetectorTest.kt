package org.synapseworks.pageharbor.library.duplicate

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Test

class LargeLibraryDuplicateDetectorTest {
    @Test
    fun thousandThreePageDocumentsClassifyExactPossibleAndDifferentDeterministically() {
        val existingFingerprints = List(DOCUMENT_COUNT) { index -> fingerprintFor(index) }
        val candidates = List(DOCUMENT_COUNT) { index ->
            when {
                index < EXACT_COUNT -> DuplicateCandidate(
                    documentId = documentId(index),
                    contentHashVersion = existingFingerprints[index].version,
                    contentSha256 = existingFingerprints[index].sha256,
                    pageCount = PAGES_PER_DOCUMENT,
                    contentByteLength = documentBytes(index),
                    orderedMimeTypes = MIME_TYPES,
                )

                index < EXACT_COUNT + POSSIBLE_COUNT -> DuplicateCandidate(
                    documentId = documentId(index),
                    contentHashVersion = null,
                    contentSha256 = null,
                    sourceSha256 = setOf(sourceHash(index)),
                    pageCount = PAGES_PER_DOCUMENT,
                    contentByteLength = documentBytes(index),
                    orderedMimeTypes = MIME_TYPES,
                )

                else -> DuplicateCandidate(
                    documentId = documentId(index),
                    contentHashVersion = null,
                    contentSha256 = null,
                    pageCount = 2,
                    contentByteLength = 2_000L + index,
                    orderedMimeTypes = listOf("image/jpeg", "image/jpeg"),
                )
            }
        }
        val counts = mutableMapOf(
            DuplicateKind.EXACT to 0,
            DuplicateKind.POSSIBLE to 0,
            DuplicateKind.DIFFERENT to 0,
        )

        repeat(DOCUMENT_COUNT) { index ->
            val incoming = when {
                index < EXACT_COUNT -> IncomingDocumentIdentity(
                    fingerprint = existingFingerprints[index],
                    pageCount = PAGES_PER_DOCUMENT,
                    contentByteLength = documentBytes(index),
                    orderedMimeTypes = MIME_TYPES,
                )

                index < EXACT_COUNT + POSSIBLE_COUNT -> IncomingDocumentIdentity(
                    fingerprint = fingerprintFor(index + DOCUMENT_COUNT),
                    sourceSha256 = setOf(sourceHash(index)),
                    pageCount = PAGES_PER_DOCUMENT,
                    contentByteLength = documentBytes(index),
                    orderedMimeTypes = MIME_TYPES,
                )

                else -> IncomingDocumentIdentity(
                    fingerprint = fingerprintFor(index + DOCUMENT_COUNT),
                    pageCount = 7,
                    contentByteLength = 70_000L + index,
                    orderedMimeTypes = List(7) { "image/png" },
                )
            }

            val match = DuplicateDetector.classify(incoming, candidates)
            counts[match.kind] = counts.getValue(match.kind) + 1
            if (match.kind == DuplicateKind.DIFFERENT) {
                assertEquals(null, match.documentId)
            } else {
                assertEquals(documentId(index), match.documentId)
            }
        }

        assertEquals(EXACT_COUNT, counts.getValue(DuplicateKind.EXACT))
        assertEquals(POSSIBLE_COUNT, counts.getValue(DuplicateKind.POSSIBLE))
        assertEquals(DIFFERENT_COUNT, counts.getValue(DuplicateKind.DIFFERENT))
    }

    private fun fingerprintFor(documentIndex: Int): DocumentFingerprint =
        DocumentFingerprintV1.calculate(
            List(PAGES_PER_DOCUMENT) { pageIndex ->
                FingerprintPage(
                    assetSha256 = hashOf("asset:$documentIndex:$pageIndex"),
                    mimeType = MIME_TYPES[pageIndex],
                    byteLength = 1_000L + documentIndex + pageIndex,
                    rotationDegrees = pageIndex * 90,
                    filterName = if (pageIndex == 2) "GRAYSCALE" else "ORIGINAL",
                )
            },
        )

    private fun sourceHash(index: Int): String = hashOf("source:$index")

    private fun documentBytes(index: Int): Long =
        (0 until PAGES_PER_DOCUMENT).sumOf { pageIndex -> 1_000L + index + pageIndex }

    private fun documentId(index: Int): String = "document-${index.toString().padStart(4, '0')}"

    private fun hashOf(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }

    private companion object {
        const val DOCUMENT_COUNT = 1_000
        const val PAGES_PER_DOCUMENT = 3
        const val EXACT_COUNT = 500
        const val POSSIBLE_COUNT = 250
        const val DIFFERENT_COUNT = DOCUMENT_COUNT - EXACT_COUNT - POSSIBLE_COUNT
        val MIME_TYPES = listOf("image/jpeg", "image/png", "image/webp")
    }
}
