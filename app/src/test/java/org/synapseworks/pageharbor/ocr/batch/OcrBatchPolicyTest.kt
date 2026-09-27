package org.synapseworks.pageharbor.ocr.batch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.synapseworks.pageharbor.library.LibraryOcrPageSnapshot
import org.synapseworks.pageharbor.library.LibraryOcrReviewPage
import org.synapseworks.pageharbor.library.OcrBatchItemEntity
import org.synapseworks.pageharbor.library.OcrBatchItemState
import org.synapseworks.pageharbor.ocr.OcrModelFailure
import org.synapseworks.pageharbor.ocr.OcrModelState
import org.synapseworks.pageharbor.ocr.OcrScript

class OcrBatchPolicyTest {
    @Test
    fun missingOnlyRecognizesPageWithoutCurrentArtifact() {
        assertTrue(shouldRecognizeBatchPage(OcrBatchMode.MISSING_ONLY, review()))
    }

    @Test
    fun missingOnlySkipsCurrentRawTextIncludingEmptyRecognition() {
        assertFalse(shouldRecognizeBatchPage(OcrBatchMode.MISSING_ONLY, review(raw = "")))
    }

    @Test
    fun missingOnlySkipsCorrectionEvenWhenRawTextIsAbsent() {
        assertFalse(
            shouldRecognizeBatchPage(
                OcrBatchMode.MISSING_ONLY,
                review(corrected = "user correction"),
            ),
        )
    }

    @Test
    fun rerunRecognizesCorrectedPageWithoutDiscardingCorrectionPolicy() {
        assertTrue(
            shouldRecognizeBatchPage(
                OcrBatchMode.RERUN,
                review(raw = "old raw", corrected = "user correction"),
            ),
        )
    }

    @Test
    fun latinIsAlwaysReadyBecauseItIsBundled() {
        assertTrue(isBatchModelReady(OcrScript.LATIN, OcrModelState.StatusUnknown))
    }

    @Test
    fun optionalScriptRequiresInstalledState() {
        assertTrue(isBatchModelReady(OcrScript.JAPANESE, OcrModelState.Installed))
        assertFalse(isBatchModelReady(OcrScript.JAPANESE, OcrModelState.NotInstalled))
        assertFalse(
            isBatchModelReady(
                OcrScript.JAPANESE,
                OcrModelState.RetryableFailure(OcrModelFailure.INSTALLATION_FAILED),
            ),
        )
    }

    @Test
    fun retryPolicyAcceptsOnlyFailedAndStaleSkippedItems() {
        assertTrue(item(OcrBatchItemState.FAILED).isRetryableBatchItem())
        assertTrue(item(OcrBatchItemState.SKIPPED, "STALE_INPUT").isRetryableBatchItem())
        assertFalse(item(OcrBatchItemState.COMPLETED).isRetryableBatchItem())
        assertFalse(item(OcrBatchItemState.SKIPPED, "CURRENT_OCR").isRetryableBatchItem())
    }

    @Test
    fun summaryOffersRetryOnlyForRetryableItems() {
        assertFalse(summary(retryable = 0).hasRetryableWork)
        assertTrue(summary(retryable = 1).hasRetryableWork)
    }

    private fun review(raw: String? = null, corrected: String? = null) = LibraryOcrReviewPage(
        snapshot = LibraryOcrPageSnapshot(
            documentId = "document",
            pageId = "page",
            pagePosition = 0,
            documentContentRevision = 1,
            pageVisualRevision = 1,
            ocrStateRevision = 1,
            activeArtifactRevision = raw?.let { 1 },
            contentSha256 = "a".repeat(64),
            rotationDegrees = 0,
            filterName = "ORIGINAL",
        ),
        rawText = raw,
        effectiveText = corrected ?: raw,
        correctedText = corrected,
        actualScript = raw?.let { "LATIN" },
        recognizedAtMillis = raw?.let { 1 },
        correctionBaseArtifactRevision = corrected?.let { 1 },
        rawLines = emptyList(),
    )

    private fun item(state: OcrBatchItemState, error: String? = null) = OcrBatchItemEntity(
        itemId = "item-$state-$error",
        jobId = "job",
        documentId = "document",
        pageId = "page-$state-$error",
        ordinal = 0,
        requestedScriptSelection = "LATIN",
        resolvedScript = "LATIN",
        expectedDocumentContentRevision = 1,
        expectedPageVisualRevision = 1,
        expectedInputFingerprintVersion = null,
        expectedInputFingerprint = null,
        expectedActiveArtifactRevision = null,
        expectedOcrStateRevision = 1,
        state = state.name,
        attemptNumber = 0,
        claimGeneration = null,
        claimToken = null,
        safeErrorCode = error,
    )

    private fun summary(retryable: Int) = OcrBatchSummary(
        documentTotal = 1,
        pageTotal = 1,
        completedPages = 0,
        skippedPages = 0,
        failedPages = 0,
        cancelled = false,
        retryableItems = retryable,
    )
}
