package org.synapseworks.pageharbor.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrOperationTrackerTest {
    @Test
    fun currentOperationAcceptsExactlyOneCompletion() {
        val tracker = OcrOperationTracker()
        val token = tracker.begin()

        assertEquals(OcrOperationTracker.CompletionClaim.CLAIMED, tracker.claimCompletion(token))
        assertEquals(OcrOperationTracker.CompletionClaim.DUPLICATE, tracker.claimCompletion(token))
    }

    @Test
    fun invalidatedOrReplacedOperationCannotPublishLateCompletion() {
        val tracker = OcrOperationTracker()
        val staleToken = tracker.begin()
        tracker.invalidate()

        assertEquals(OcrOperationTracker.CompletionClaim.SUPERSEDED, tracker.claimCompletion(staleToken))

        val replacementToken = tracker.begin()
        assertEquals(
            OcrOperationTracker.CompletionClaim.CLAIMED,
            tracker.claimCompletion(replacementToken),
        )
    }

    @Test
    fun revisionBoundOperationRejectsCompletionAfterMutation() {
        val tracker = OcrOperationTracker()
        val token = tracker.begin(documentRevision = 4L)

        assertEquals(
            OcrOperationTracker.CompletionClaim.SUPERSEDED,
            tracker.claimCompletion(token, documentRevision = 5L),
        )
        assertEquals(false, tracker.isCurrent(token, documentRevision = 5L))
    }

    @Test
    fun revisionInvalidationPreservesCurrentWorkAndSupersedesStaleWork() {
        val tracker = OcrOperationTracker()
        val token = tracker.begin(documentRevision = 7L)

        assertEquals(false, tracker.invalidateIfDocumentRevisionChanged(7L))
        assertEquals(true, tracker.isCurrent(token, 7L))
        assertEquals(true, tracker.invalidateIfDocumentRevisionChanged(8L))
        assertEquals(
            OcrOperationTracker.CompletionClaim.SUPERSEDED,
            tracker.claimCompletion(token, 8L),
        )
    }
}
