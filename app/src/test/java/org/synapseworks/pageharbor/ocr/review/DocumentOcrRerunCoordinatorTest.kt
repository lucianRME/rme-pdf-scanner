package org.synapseworks.pageharbor.ocr.review

import java.io.ByteArrayInputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.synapseworks.pageharbor.library.LibraryOcrCommitResult
import org.synapseworks.pageharbor.library.LibraryOcrPageSnapshot
import org.synapseworks.pageharbor.ocr.OcrFailureReason
import org.synapseworks.pageharbor.ocr.OcrModelDelivery
import org.synapseworks.pageharbor.ocr.OcrPage
import org.synapseworks.pageharbor.ocr.OcrPageLayout
import org.synapseworks.pageharbor.ocr.OcrPageRecognitionEngine
import org.synapseworks.pageharbor.ocr.OcrPageRecognitionOutcome
import org.synapseworks.pageharbor.ocr.OcrRecognizerProvenance
import org.synapseworks.pageharbor.ocr.OcrScript
import org.synapseworks.pageharbor.ocr.OcrTextBounds
import org.synapseworks.pageharbor.ocr.OcrTextLine

class DocumentOcrRerunCoordinatorTest {
    @Test
    fun singlePageUsesExactOverrideAndCommitsNewRawArtifact() = runBlocking {
        var actualScript: OcrScript? = null
        var committedText: String? = null
        val coordinator = coordinator(
            engine = OcrPageRecognitionEngine { request ->
                actualScript = request.descriptor.script
                success(request, "日本語テスト")
            },
            commit = { _, artifact ->
                committedText = artifact.rawText
                LibraryOcrCommitResult.APPLIED
            },
        )

        val result = coordinator.run(listOf(target(0)), OcrScript.JAPANESE)

        assertEquals(OcrScript.JAPANESE, actualScript)
        assertEquals("日本語テスト", committedText)
        assertEquals(1, result.succeededPages)
    }

    @Test
    fun documentRunsIncrementallyAndReportsMixedSuccessWithoutDiscardingSuccesses() = runBlocking {
        val committed = mutableListOf<String>()
        val progress = mutableListOf<DocumentOcrRerunProgress>()
        val coordinator = coordinator(
            engine = OcrPageRecognitionEngine { request ->
                if (request.descriptor.capturedPagePosition == 1) {
                    OcrPageRecognitionOutcome.Failure(
                        request.descriptor,
                        OcrFailureReason.IMAGE_UNREADABLE,
                    )
                } else {
                    success(request, "page-${request.descriptor.capturedPagePosition}")
                }
            },
            commit = { snapshot, _ ->
                committed += snapshot.pageId
                LibraryOcrCommitResult.APPLIED
            },
        )

        val result = coordinator.run(
            targets = listOf(target(0), target(1), target(2)),
            script = OcrScript.LATIN,
            onProgress = progress::add,
        )

        assertEquals(listOf("page-0", "page-2"), committed)
        assertEquals(2, result.succeededPages)
        assertEquals(1, result.failedPages)
        assertEquals(DocumentOcrRerunFailure.RECOGNITION_FAILED, result.pages[1].failure)
        assertEquals(listOf(1, 2, 3), progress.map { it.completedPages })
    }

    @Test
    fun missingModelNeverCommitsOrFallsBack() = runBlocking {
        var commits = 0
        val coordinator = coordinator(
            engine = OcrPageRecognitionEngine { request ->
                OcrPageRecognitionOutcome.Failure(
                    request.descriptor,
                    OcrFailureReason.MODEL_UNAVAILABLE,
                )
            },
            commit = { _, _ -> commits++; LibraryOcrCommitResult.APPLIED },
        )

        val result = coordinator.run(listOf(target(0)), OcrScript.KOREAN)

        assertEquals(0, commits)
        assertEquals(DocumentOcrRerunFailure.MODEL_UNAVAILABLE, result.pages.single().failure)
    }

    @Test
    fun staleAndDeletedCommitsArePageFailures() = runBlocking {
        val results = listOf(LibraryOcrCommitResult.STALE, LibraryOcrCommitResult.NOT_FOUND)
        var index = 0
        val coordinator = coordinator(
            engine = OcrPageRecognitionEngine { request -> success(request, "new") },
            commit = { _, _ -> results[index++] },
        )

        val result = coordinator.run(listOf(target(0), target(1)), OcrScript.CHINESE)

        assertEquals(DocumentOcrRerunFailure.STALE, result.pages[0].failure)
        assertEquals(DocumentOcrRerunFailure.DELETED, result.pages[1].failure)
    }

    @Test
    fun successfulRerunReportsExistingCorrectionAsPreserved() = runBlocking {
        val result = coordinator(
            engine = OcrPageRecognitionEngine { request -> success(request, "new raw") },
            commit = { _, _ -> LibraryOcrCommitResult.APPLIED },
        ).run(listOf(target(0, hadCorrection = true)), OcrScript.DEVANAGARI)

        assertTrue(result.pages.single().succeeded)
        assertTrue(result.pages.single().correctionPreserved)
        assertEquals(1, result.preservedCorrections)
    }

    @Test
    fun cancellationStopsBeforeLaterPagesAndKeepsCompletedCommit() = runBlocking {
        val secondStarted = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        val committed = mutableListOf<String>()
        val coordinator = coordinator(
            engine = OcrPageRecognitionEngine { request ->
                if (request.descriptor.capturedPagePosition == 1) {
                    secondStarted.complete(Unit)
                    never.await()
                }
                success(request, "new")
            },
            commit = { snapshot, _ ->
                committed += snapshot.pageId
                LibraryOcrCommitResult.APPLIED
            },
        )

        val job = launch {
            coordinator.run(listOf(target(0), target(1), target(2)), OcrScript.LATIN)
        }
        secondStarted.await()
        job.cancelAndJoin()

        assertEquals(listOf("page-0"), committed)
        assertFalse(never.isCompleted)
    }

    @Test
    fun twentyPageDocumentRemainsStrictlyIncremental() = runBlocking {
        var inFlight = 0
        var maximumInFlight = 0
        var commits = 0
        val coordinator = coordinator(
            engine = OcrPageRecognitionEngine { request ->
                inFlight++
                maximumInFlight = maxOf(maximumInFlight, inFlight)
                val outcome = success(
                    request,
                    "Substantial OCR text ".repeat(1_000),
                )
                inFlight--
                outcome
            },
            commit = { _, _ ->
                commits++
                LibraryOcrCommitResult.APPLIED
            },
        )

        val result = coordinator.run(
            targets = List(20) { index -> target(index) },
            script = OcrScript.LATIN,
        )

        assertEquals(20, result.succeededPages)
        assertEquals(20, commits)
        assertEquals(1, maximumInFlight)
    }

    private fun coordinator(
        engine: OcrPageRecognitionEngine,
        commit: suspend (
            LibraryOcrPageSnapshot,
            org.synapseworks.pageharbor.library.LibraryOcrArtifactDraft,
        ) -> LibraryOcrCommitResult,
    ) = DocumentOcrRerunCoordinator(
        engine = engine,
        committer = OcrArtifactCommitter(commit),
        nowMillis = { 100L },
    )

    private fun target(
        position: Int,
        hadCorrection: Boolean = false,
    ) = DocumentOcrRerunTarget(
        snapshot = LibraryOcrPageSnapshot(
            documentId = "document",
            pageId = "page-$position",
            pagePosition = position,
            documentContentRevision = 1L,
            pageVisualRevision = 2L,
            ocrStateRevision = 3L,
            activeArtifactRevision = 1L,
            contentSha256 = "a".repeat(64),
            rotationDegrees = 0,
            filterName = "ORIGINAL",
        ),
        source = OcrPage { ByteArrayInputStream(byteArrayOf(1)) },
        hadCorrection = hadCorrection,
    )

    private fun success(
        request: org.synapseworks.pageharbor.ocr.OcrPageRecognitionRequest,
        text: String,
    ) = OcrPageRecognitionOutcome.Success(
        descriptor = request.descriptor,
        rawText = text,
        layout = OcrPageLayout(
            imageWidthPx = 100,
            imageHeightPx = 100,
            lines = listOf(
                OcrTextLine(text, OcrTextBounds(5f, 5f, 95f, 25f)),
            ),
        ),
        provenance = OcrRecognizerProvenance(
            actualScript = request.descriptor.script,
            recognizerId = "test-${request.descriptor.script.stableId.lowercase()}",
            pipelineVersion = "test-v1",
            delivery = if (request.descriptor.script == OcrScript.LATIN) {
                OcrModelDelivery.BUNDLED
            } else {
                OcrModelDelivery.PLAY_SERVICES
            },
        ),
    )
}
