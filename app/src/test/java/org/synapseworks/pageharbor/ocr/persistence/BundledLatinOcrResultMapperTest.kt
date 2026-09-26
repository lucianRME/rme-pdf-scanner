package org.synapseworks.pageharbor.ocr.persistence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.synapseworks.pageharbor.library.LibraryOcrPageSnapshot
import org.synapseworks.pageharbor.ocr.OcrPageError
import org.synapseworks.pageharbor.ocr.OcrPageLayout
import org.synapseworks.pageharbor.ocr.OcrPageResult
import org.synapseworks.pageharbor.ocr.OcrResult
import org.synapseworks.pageharbor.ocr.OcrTextBounds
import org.synapseworks.pageharbor.ocr.OcrTextLine

class BundledLatinOcrResultMapperTest {
    @Test
    fun successCreatesVerifiedDraftWithStableProvenanceAndNormalizedRectangle() {
        val mapping = map(
            result = successResult(),
            snapshots = listOf(snapshot()),
            recognizedAtMillis = 123L,
        ) as BundledLatinOcrMappingResult.Success
        val artifact = mapping.outcomes.single().artifact!!
        val line = artifact.lines.single()

        assertEquals(1, mapping.documentPageOrderFingerprintVersion)
        assertTrue(mapping.documentPageOrderFingerprint.matches(FingerprintPattern))
        assertEquals(1, artifact.inputFingerprintVersion)
        assertTrue(artifact.inputFingerprint.matches(FingerprintPattern))
        assertEquals("LATIN", artifact.actualScript)
        assertEquals(BundledLatinOcrArtifactContract.RECOGNIZER_ID, artifact.recognizerId)
        assertEquals(BundledLatinOcrArtifactContract.PIPELINE_VERSION, artifact.pipelineVersion)
        assertEquals(BundledLatinOcrArtifactContract.CLIENT_VERSION, artifact.clientVersion)
        assertEquals(BundledLatinOcrArtifactContract.DELIVERY, artifact.delivery)
        assertEquals(123L, artifact.recognizedAtMillis)
        assertEquals(0.1, line.topLeftX, 0.000_001)
        assertEquals(0.1, line.topLeftY, 0.000_001)
        assertEquals(0.9, line.topRightX, 0.000_001)
        assertEquals(0.3, line.bottomRightY, 0.000_001)
        assertEquals(line.bottomLeftX, line.baselineStartX, 0.0)
        assertEquals(line.bottomLeftY, line.baselineStartY, 0.0)
        assertEquals(line.bottomRightX, line.baselineEndX, 0.0)
        assertEquals(line.bottomRightY, line.baselineEndY, 0.0)
        assertEquals(0.0, line.baselineAngleDegrees, 0.0)
        assertNull(line.writingOrientation)
    }

    @Test
    fun fingerprintsAreStableButPageOrderAndVisualInputRemainDistinct() {
        val first = map(successResult(), listOf(snapshot()), 1L).success()
        val repeated = map(successResult(), listOf(snapshot()), 2L).success()
        val visuallyChanged = map(
            successResult(),
            listOf(snapshot(filterName = "GRAYSCALE")),
            1L,
        ).success()
        val revisionChanged = map(
            successResult(),
            listOf(snapshot(pageVisualRevision = 4L)),
            1L,
        ).success()
        val reordered = map(
            result = OcrResult(listOf(pageResult(0), pageResult(1))),
            snapshots = listOf(
                snapshot(pageId = "page-2", pagePosition = 0, hash = HashB),
                snapshot(pageId = "page-1", pagePosition = 1, hash = HashA),
            ),
            recognizedAtMillis = 1L,
        ).success()
        val forward = map(
            result = OcrResult(listOf(pageResult(0), pageResult(1))),
            snapshots = listOf(
                snapshot(pageId = "page-1", pagePosition = 0, hash = HashA),
                snapshot(pageId = "page-2", pagePosition = 1, hash = HashB),
            ),
            recognizedAtMillis = 1L,
        ).success()

        assertEquals(
            first.outcomes.single().artifact!!.inputFingerprint,
            repeated.outcomes.single().artifact!!.inputFingerprint,
        )
        assertEquals(
            first.documentPageOrderFingerprint,
            repeated.documentPageOrderFingerprint,
        )
        assertNotEquals(
            first.outcomes.single().artifact!!.inputFingerprint,
            visuallyChanged.outcomes.single().artifact!!.inputFingerprint,
        )
        assertNotEquals(
            first.outcomes.single().artifact!!.inputFingerprint,
            revisionChanged.outcomes.single().artifact!!.inputFingerprint,
        )
        assertNotEquals(
            forward.documentPageOrderFingerprint,
            reordered.documentPageOrderFingerprint,
        )
    }

    @Test
    fun pageRecognitionErrorBecomesSafeOutcomeWithoutArtifact() {
        val result = OcrResult(
            listOf(
                OcrPageResult(
                    pageIndex = 0,
                    text = "",
                    error = OcrPageError.IMAGE_UNREADABLE,
                ),
            ),
        )

        val mapping = map(result, listOf(snapshot()), 10L).success()
        val outcome = mapping.outcomes.single()

        assertNull(outcome.artifact)
        assertEquals("IMAGE_UNREADABLE", outcome.safeErrorCode)
    }

    @Test
    fun missingContentHashFailsWithoutInventingInputIdentity() {
        assertEquals(
            BundledLatinOcrMappingResult.Failure(
                BundledLatinOcrMappingFailure.CONTENT_HASH_MISSING,
                pageIndex = 0,
            ),
            map(successResult(), listOf(snapshot(hash = null)), 1L),
        )
    }

    @Test
    fun successfulPageWithoutPositionedLayoutFailsInsteadOfInventingGeometry() {
        val result = OcrResult(listOf(OcrPageResult(pageIndex = 0, text = "Text")))

        assertEquals(
            BundledLatinOcrMappingResult.Failure(
                BundledLatinOcrMappingFailure.POSITIONED_LAYOUT_MISSING,
                pageIndex = 0,
            ),
            map(result, listOf(snapshot()), 1L),
        )
    }

    @Test
    fun lineWithoutBoundsFailsInsteadOfInventingPositionedGeometry() {
        val result = OcrResult(
            listOf(
                OcrPageResult(
                    pageIndex = 0,
                    text = "Text",
                    layout = OcrPageLayout(
                        imageWidthPx = 100,
                        imageHeightPx = 200,
                        lines = listOf(OcrTextLine(text = "Text", bounds = null)),
                    ),
                ),
            ),
        )

        assertEquals(
            BundledLatinOcrMappingResult.Failure(
                BundledLatinOcrMappingFailure.POSITIONED_LINE_GEOMETRY_MISSING,
                pageIndex = 0,
            ),
            map(result, listOf(snapshot()), 1L),
        )
    }

    @Test
    fun outOfBoundsLineFailsInsteadOfClampingPositionedGeometry() {
        val result = OcrResult(
            listOf(
                OcrPageResult(
                    pageIndex = 0,
                    text = "Text",
                    layout = OcrPageLayout(
                        imageWidthPx = 100,
                        imageHeightPx = 200,
                        lines = listOf(
                            OcrTextLine(
                                text = "Text",
                                bounds = OcrTextBounds(
                                    left = -1f,
                                    top = 20f,
                                    right = 101f,
                                    bottom = 60f,
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(
            BundledLatinOcrMappingResult.Failure(
                BundledLatinOcrMappingFailure.POSITIONED_LINE_GEOMETRY_MISSING,
                pageIndex = 0,
            ),
            map(result, listOf(snapshot()), 1L),
        )
    }

    @Test
    fun countIndexAndSnapshotOrderMismatchesAreTypedFailures() {
        assertEquals(
            BundledLatinOcrMappingFailure.PAGE_COUNT_MISMATCH,
            (map(successResult(), listOf(snapshot(), snapshot(pageId = "page-2")), 1L)
                as BundledLatinOcrMappingResult.Failure).reason,
        )
        assertEquals(
            BundledLatinOcrMappingFailure.PAGE_INDEX_MISMATCH,
            (
                map(
                    OcrResult(listOf(pageResult(pageIndex = 1))),
                    listOf(snapshot()),
                    1L,
                ) as BundledLatinOcrMappingResult.Failure
                ).reason,
        )
        assertEquals(
            BundledLatinOcrMappingFailure.SNAPSHOT_ORDER_MISMATCH,
            (
                map(
                    successResult(),
                    listOf(snapshot(pagePosition = 1)),
                    1L,
                ) as BundledLatinOcrMappingResult.Failure
                ).reason,
        )
    }

    private fun map(
        result: OcrResult,
        snapshots: List<LibraryOcrPageSnapshot>,
        recognizedAtMillis: Long,
    ) = BundledLatinOcrResultMapper.map(result, snapshots, recognizedAtMillis)

    private fun BundledLatinOcrMappingResult.success() =
        this as BundledLatinOcrMappingResult.Success

    private fun successResult() = OcrResult(listOf(pageResult(0)))

    private fun pageResult(pageIndex: Int) = OcrPageResult(
        pageIndex = pageIndex,
        text = "Synthetic text",
        layout = OcrPageLayout(
            imageWidthPx = 100,
            imageHeightPx = 200,
            lines = listOf(
                OcrTextLine(
                    text = "Synthetic text",
                    bounds = OcrTextBounds(left = 10f, top = 20f, right = 90f, bottom = 60f),
                ),
            ),
        ),
    )

    private fun snapshot(
        pageId: String = "page-1",
        pagePosition: Int = 0,
        hash: String? = HashA,
        filterName: String = "ORIGINAL",
        pageVisualRevision: Long = 3L,
    ) = LibraryOcrPageSnapshot(
        documentId = "document-1",
        pageId = pageId,
        pagePosition = pagePosition,
        documentContentRevision = 2L,
        pageVisualRevision = pageVisualRevision,
        ocrStateRevision = 4L,
        activeArtifactRevision = null,
        contentSha256 = hash,
        rotationDegrees = 0,
        filterName = filterName,
    )

    private companion object {
        val FingerprintPattern = Regex("sha256:[0-9a-f]{64}")
        val HashA = "a".repeat(64)
        val HashB = "b".repeat(64)
    }
}
