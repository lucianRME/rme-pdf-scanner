package org.synapseworks.pageharbor.document

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentOperationTrackerTest {
    @Test
    fun invalidationMakesLateCompletionHarmless() {
        val tracker = DocumentOperationTracker()
        val stale = tracker.begin(documentRevision = 1L)

        tracker.invalidate()

        assertFalse(tracker.isCurrent(stale, documentRevision = 1L))
        assertFalse(tracker.finish(stale, documentRevision = 1L))
    }

    @Test
    fun oldGenerationCannotFinishNewGeneration() {
        val tracker = DocumentOperationTracker()
        val old = tracker.begin(documentRevision = 1L)
        val current = tracker.begin(documentRevision = 1L)

        assertFalse(tracker.finish(old, documentRevision = 1L))
        assertTrue(tracker.isCurrent(current, documentRevision = 1L))
        assertTrue(tracker.finish(current, documentRevision = 1L))
        assertFalse(tracker.finish(current, documentRevision = 1L))
    }

    @Test
    fun documentRevisionChangeMakesTheActiveGenerationStale() {
        val tracker = DocumentOperationTracker()
        val operation = tracker.begin(documentRevision = 7L)

        assertTrue(tracker.isCurrent(operation, documentRevision = 7L))
        assertFalse(tracker.isCurrent(operation, documentRevision = 8L))
        assertFalse(tracker.finish(operation, documentRevision = 8L))
    }

    @Test
    fun revisionInvalidationPreservesWorkStartedForTheCurrentDocument() {
        val tracker = DocumentOperationTracker()
        val operation = tracker.begin(documentRevision = 9L)

        assertFalse(tracker.invalidateIfDocumentRevisionChanged(documentRevision = 9L))
        assertTrue(tracker.isCurrent(operation, documentRevision = 9L))
        assertTrue(tracker.invalidateIfDocumentRevisionChanged(documentRevision = 10L))
        assertFalse(tracker.isCurrent(operation, documentRevision = 10L))
    }
}
