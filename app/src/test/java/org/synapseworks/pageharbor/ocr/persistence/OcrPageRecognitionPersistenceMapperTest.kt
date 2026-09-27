package org.synapseworks.pageharbor.ocr.persistence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.synapseworks.pageharbor.library.LibraryOcrPageSnapshot
import org.synapseworks.pageharbor.ocr.OcrDurablePageCurrentness
import org.synapseworks.pageharbor.ocr.OcrFailureReason
import org.synapseworks.pageharbor.ocr.OcrModelDelivery
import org.synapseworks.pageharbor.ocr.OcrPageAddress
import org.synapseworks.pageharbor.ocr.OcrPageLayout
import org.synapseworks.pageharbor.ocr.OcrPageRecognitionDescriptor
import org.synapseworks.pageharbor.ocr.OcrPageRecognitionOutcome
import org.synapseworks.pageharbor.ocr.OcrRecognitionCurrentness
import org.synapseworks.pageharbor.ocr.OcrRecognizerProvenance
import org.synapseworks.pageharbor.ocr.OcrScript
import org.synapseworks.pageharbor.ocr.OcrTextBounds
import org.synapseworks.pageharbor.ocr.OcrTextLine

class OcrPageRecognitionPersistenceMapperTest {
    @Test
    fun multilingualSuccessFeedsExistingRevisionSafeCommitDraft() {
        val expected = snapshot()

        val mapping = OcrPageRecognitionPersistenceMapper.map(
            result = success(expected),
            expected = expected,
            recognizedAtMillis = 55L,
        ) as OcrPagePersistenceMapping.Ready
        val artifact = mapping.outcome.artifact!!

        assertSame(expected, mapping.outcome.expected)
        assertEquals("JAPANESE", artifact.actualScript)
        assertEquals("PLAY_SERVICES", artifact.delivery)
        assertEquals("play-services-16.0.1", artifact.clientVersion)
        assertEquals("synthetic", artifact.rawText)
        assertEquals(0.1, artifact.lines.single().topLeftX, 0.0)
        assertEquals(55L, artifact.recognizedAtMillis)
        assertNull(mapping.outcome.safeErrorCode)
    }

    @Test
    fun changedRevisionIsRejectedBeforeDaoCommit() {
        val expected = snapshot()
        val changed = expected.copy(pageVisualRevision = expected.pageVisualRevision + 1)

        assertSame(
            OcrPagePersistenceMapping.Stale,
            OcrPageRecognitionPersistenceMapper.map(success(expected), changed, 1L),
        )
    }

    @Test
    fun missingModelAndInstallBoundaryFailuresNeverCreatePersistenceDraft() {
        val expected = snapshot()
        listOf(
            OcrFailureReason.MODEL_UNAVAILABLE,
            OcrFailureReason.GOOGLE_PLAY_SERVICES_UNAVAILABLE,
            OcrFailureReason.SCRIPT_UNSUPPORTED,
            OcrFailureReason.CANCELLED,
        ).forEach { reason ->
            assertSame(
                OcrPagePersistenceMapping.NoChange,
                OcrPageRecognitionPersistenceMapper.map(
                    OcrPageRecognitionOutcome.Failure(descriptor(expected), reason),
                    expected,
                    1L,
                ),
            )
        }
    }

    @Test
    fun recognizerFailureUsesSafeExistingOutcomeContract() {
        val expected = snapshot()
        val mapping = OcrPageRecognitionPersistenceMapper.map(
            OcrPageRecognitionOutcome.Failure(
                descriptor(expected),
                OcrFailureReason.RECOGNITION_FAILED,
            ),
            expected,
            1L,
        ) as OcrPagePersistenceMapping.Ready

        assertEquals("RECOGNITION_FAILED", mapping.outcome.safeErrorCode)
        assertNull(mapping.outcome.artifact)
    }

    private fun success(expected: LibraryOcrPageSnapshot) = OcrPageRecognitionOutcome.Success(
        descriptor = descriptor(expected),
        rawText = "synthetic",
        layout = OcrPageLayout(
            imageWidthPx = 100,
            imageHeightPx = 200,
            lines = listOf(
                OcrTextLine(
                    text = "synthetic",
                    bounds = OcrTextBounds(10f, 20f, 90f, 60f),
                ),
            ),
        ),
        provenance = OcrRecognizerProvenance(
            actualScript = OcrScript.JAPANESE,
            recognizerId = "mlkit-text-recognition-v2-japanese",
            pipelineVersion = "mlkit-text-recognition-v2",
            clientVersion = "play-services-16.0.1",
            delivery = OcrModelDelivery.PLAY_SERVICES,
        ),
    )

    private fun descriptor(expected: LibraryOcrPageSnapshot) = OcrPageRecognitionDescriptor(
        address = OcrPageAddress(expected.documentId, expected.pageId),
        capturedPagePosition = expected.pagePosition,
        script = OcrScript.JAPANESE,
        currentness = OcrRecognitionCurrentness(
            inputFingerprintVersion = 1,
            inputFingerprint = "sha256:synthetic",
            durable = OcrDurablePageCurrentness(
                documentContentRevision = expected.documentContentRevision,
                pageVisualRevision = expected.pageVisualRevision,
                ocrStateRevision = expected.ocrStateRevision,
                activeArtifactRevision = expected.activeArtifactRevision,
            ),
        ),
    )

    private fun snapshot() = LibraryOcrPageSnapshot(
        documentId = "document",
        pageId = "page",
        pagePosition = 0,
        documentContentRevision = 1,
        pageVisualRevision = 2,
        ocrStateRevision = 3,
        activeArtifactRevision = null,
        contentSha256 = "a".repeat(64),
        rotationDegrees = 0,
        filterName = "ORIGINAL",
    )
}
