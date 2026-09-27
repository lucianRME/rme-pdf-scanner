package org.synapseworks.pageharbor.ocr.persistence

import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.synapseworks.pageharbor.library.LibraryOcrPageSnapshot
import org.synapseworks.pageharbor.ocr.OcrFailureReason
import org.synapseworks.pageharbor.ocr.OcrModelDelivery
import org.synapseworks.pageharbor.ocr.OcrPage
import org.synapseworks.pageharbor.ocr.OcrPageError
import org.synapseworks.pageharbor.ocr.OcrPageLayout
import org.synapseworks.pageharbor.ocr.OcrPageRecognitionEngine
import org.synapseworks.pageharbor.ocr.OcrPageRecognitionOutcome
import org.synapseworks.pageharbor.ocr.OcrRecognizerProvenance
import org.synapseworks.pageharbor.ocr.OcrScript
import org.synapseworks.pageharbor.ocr.OcrTextBounds
import org.synapseworks.pageharbor.ocr.OcrTextLine

class ScriptedOcrSessionRunnerTest {
    @Test
    fun savedPageUsesExactScriptAndProducesRevisionSafePersistenceDraft() = runBlocking {
        val snapshot = snapshot(position = 0)
        val runner = ScriptedOcrSessionRunner(successEngine())

        val result = runner.recognize(
            pages = listOf(page()),
            script = OcrScript.JAPANESE,
            sessionDocumentRevision = 9L,
            savedSnapshots = listOf(snapshot),
            recognizedAtMillis = 44L,
        )

        assertEquals("recognized-0", result.result.pages.single().text)
        assertNull(result.result.pages.single().error)
        assertNotNull(result.persistenceOutcomes)
        val outcome = requireNotNull(result.persistenceOutcomes).single()
        assertEquals(snapshot, outcome.expected)
        assertEquals("JAPANESE", outcome.artifact?.actualScript)
        assertEquals("PLAY_SERVICES", outcome.artifact?.delivery)
        assertEquals(44L, outcome.artifact?.recognizedAtMillis)
    }

    @Test
    fun unavailableModelPreventsPartialPersistenceAndNeverFallsBack() = runBlocking {
        val engine = OcrPageRecognitionEngine { request ->
            if (request.descriptor.capturedPagePosition == 0) {
                success(request.descriptor.capturedPagePosition, request.descriptor)
            } else {
                OcrPageRecognitionOutcome.Failure(
                    descriptor = request.descriptor,
                    reason = OcrFailureReason.MODEL_UNAVAILABLE,
                )
            }
        }

        val result = ScriptedOcrSessionRunner(engine).recognize(
            pages = listOf(page(), page()),
            script = OcrScript.KOREAN,
            sessionDocumentRevision = 10L,
            savedSnapshots = listOf(snapshot(0), snapshot(1)),
            recognizedAtMillis = 45L,
        )

        assertNull(result.persistenceOutcomes)
        assertEquals("recognized-0", result.result.pages[0].text)
        assertEquals("", result.result.pages[1].text)
        assertEquals(OcrPageError.RECOGNITION_FAILED, result.result.pages[1].error)
    }

    @Test
    fun activeUnsavedSessionReturnsRecognitionWithoutInventingDurableWrite() = runBlocking {
        val result = ScriptedOcrSessionRunner(successEngine()).recognize(
            pages = listOf(page()),
            script = OcrScript.DEVANAGARI,
            sessionDocumentRevision = 11L,
            savedSnapshots = null,
            recognizedAtMillis = 46L,
        )

        assertEquals("recognized-0", result.result.pages.single().text)
        assertNull(result.persistenceOutcomes)
    }

    private fun successEngine() = OcrPageRecognitionEngine { request ->
        success(request.descriptor.capturedPagePosition, request.descriptor)
    }

    private fun success(
        pageIndex: Int,
        descriptor: org.synapseworks.pageharbor.ocr.OcrPageRecognitionDescriptor,
    ) = OcrPageRecognitionOutcome.Success(
        descriptor = descriptor,
        rawText = "recognized-$pageIndex",
        layout = OcrPageLayout(
            imageWidthPx = 100,
            imageHeightPx = 200,
            lines = listOf(
                OcrTextLine(
                    text = "recognized-$pageIndex",
                    bounds = OcrTextBounds(10f, 20f, 90f, 60f),
                ),
            ),
        ),
        provenance = OcrRecognizerProvenance(
            actualScript = descriptor.script,
            recognizerId = "test-${descriptor.script.stableId.lowercase()}",
            pipelineVersion = "test-v1",
            clientVersion = "test-client",
            delivery = OcrModelDelivery.PLAY_SERVICES,
        ),
    )

    private fun snapshot(position: Int) = LibraryOcrPageSnapshot(
        documentId = "document",
        pageId = "page-$position",
        pagePosition = position,
        documentContentRevision = 1L,
        pageVisualRevision = 2L,
        ocrStateRevision = 3L,
        activeArtifactRevision = null,
        contentSha256 = "a".repeat(64),
        rotationDegrees = 0,
        filterName = "ORIGINAL",
    )

    private fun page() = OcrPage { ByteArrayInputStream(byteArrayOf(1)) }
}
