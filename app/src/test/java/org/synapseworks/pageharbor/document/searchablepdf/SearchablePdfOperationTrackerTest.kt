package org.synapseworks.pageharbor.document.searchablepdf

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchablePdfOperationTrackerTest {
    @Test
    fun activeTokenAcceptsProgressAndOneCompletion() {
        val tracker = SearchablePdfOperationTracker()
        val token = tracker.begin()

        assertTrue(tracker.acceptsProgress(token))
        assertEquals(SearchablePdfOperationTracker.CompletionClaim.CLAIMED, tracker.claimCompletion(token))
        assertFalse(tracker.acceptsProgress(token))
    }

    @Test
    fun supersededTokenCannotPublishProgressOrCompletion() {
        val tracker = SearchablePdfOperationTracker()
        val oldToken = tracker.begin()
        val activeToken = tracker.begin()

        assertFalse(tracker.acceptsProgress(oldToken))
        assertEquals(
            SearchablePdfOperationTracker.CompletionClaim.SUPERSEDED,
            tracker.claimCompletion(oldToken),
        )
        assertTrue(tracker.acceptsProgress(activeToken))
        assertEquals(
            SearchablePdfOperationTracker.CompletionClaim.CLAIMED,
            tracker.claimCompletion(activeToken),
        )
    }

    @Test
    fun repeatedCompletionAndOldCleanupLeaveTheNewOperationCurrent() {
        val tracker = SearchablePdfOperationTracker()
        val oldToken = tracker.begin()

        assertEquals(
            SearchablePdfOperationTracker.CompletionClaim.CLAIMED,
            tracker.claimCompletion(oldToken),
        )
        assertEquals(
            SearchablePdfOperationTracker.CompletionClaim.DUPLICATE,
            tracker.claimCompletion(oldToken),
        )

        val activeToken = tracker.begin()
        assertFalse(tracker.acceptsProgress(oldToken))
        assertTrue(tracker.acceptsProgress(activeToken))
        assertEquals(
            SearchablePdfOperationTracker.CompletionClaim.CLAIMED,
            tracker.claimCompletion(activeToken),
        )
    }

    @Test
    fun staleOrDuplicateCompletionCannotRemoveTheActivePreparedFile() {
        val tracker = SearchablePdfOperationTracker()
        val staleFile = File.createTempFile("stale-prepared-", ".pdf")
        val activeFile = File.createTempFile("active-prepared-", ".pdf")

        try {
            val staleToken = tracker.begin()
            val activeToken = tracker.begin()

            assertEquals(
                SearchablePdfOperationTracker.CompletionClaim.SUPERSEDED,
                tracker.claimCompletion(staleToken),
            )
            assertTrue(staleFile.delete())
            assertTrue(activeFile.exists())

            assertEquals(
                SearchablePdfOperationTracker.CompletionClaim.CLAIMED,
                tracker.claimCompletion(activeToken),
            )
            assertEquals(
                SearchablePdfOperationTracker.CompletionClaim.DUPLICATE,
                tracker.claimCompletion(activeToken),
            )
            assertTrue(activeFile.exists())
        } finally {
            staleFile.delete()
            activeFile.delete()
        }
    }

    @Test
    fun invalidationRejectsAnObsoletePickerOrCompletionCallback() {
        val tracker = SearchablePdfOperationTracker()
        val token = tracker.begin()

        tracker.invalidate()

        assertFalse(tracker.acceptsProgress(token))
        assertEquals(
            SearchablePdfOperationTracker.CompletionClaim.SUPERSEDED,
            tracker.claimCompletion(token),
        )
    }

    @Test
    fun revisionBoundPreparationRejectsProgressAndCompletionAfterMutation() {
        val tracker = SearchablePdfOperationTracker()
        val token = tracker.begin(documentRevision = 11L)

        assertTrue(tracker.acceptsProgress(token, documentRevision = 11L))
        assertFalse(tracker.acceptsProgress(token, documentRevision = 12L))
        assertEquals(
            SearchablePdfOperationTracker.CompletionClaim.SUPERSEDED,
            tracker.claimCompletion(token, documentRevision = 12L),
        )
    }

    @Test
    fun revisionInvalidationDoesNotCancelWorkForTheCurrentRevision() {
        val tracker = SearchablePdfOperationTracker()
        val token = tracker.begin(documentRevision = 20L)

        assertFalse(tracker.invalidateIfDocumentRevisionChanged(20L))
        assertTrue(tracker.isCurrent(token, 20L))
        assertTrue(tracker.invalidateIfDocumentRevisionChanged(21L))
        assertFalse(tracker.isCurrent(token, 21L))
    }
}
