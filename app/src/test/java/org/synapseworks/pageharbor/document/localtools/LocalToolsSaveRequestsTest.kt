package org.synapseworks.pageharbor.document.localtools

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalToolsSaveRequestsTest {
    private val outputA = File("synthetic-a.pdf")
    private val outputB = File("synthetic-b.pdf")

    @Test
    fun matchingResponseReturnsTheCapturedOutputAndIsConsumedOnce() {
        val tracker = LocalToolsSaveRequests()
        val request = requireNotNull(tracker.create(outputA, 3))
        val result = tracker.consume(request.id, 3, outputA, cancelled = false)
        assertTrue(result is LocalToolsSaveResolution.Ready)
        assertSame(outputA, (result as LocalToolsSaveResolution.Ready).request.output)
        assertEquals(3L, result.request.generation)
        assertEquals(LocalToolsSaveResolution.Ignored, tracker.consume(request.id, 3, outputA, cancelled = false))
    }

    @Test
    fun secondPickerCannotReplaceAnOutstandingRequest() {
        val tracker = LocalToolsSaveRequests()
        val request = requireNotNull(tracker.create(outputA, 1))
        assertNull(tracker.create(outputB, 1))
        val ready = tracker.consume(request.id, 1, outputA, cancelled = false) as LocalToolsSaveResolution.Ready
        assertSame(outputA, ready.request.output)
    }

    @Test
    fun delayedAResponseCannotConsumeNewBRequest() {
        val tracker = LocalToolsSaveRequests()
        val a = requireNotNull(tracker.create(outputA, 1))
        assertTrue(tracker.consume(a.id, 1, outputA, cancelled = true) is LocalToolsSaveResolution.Cancelled)
        val b = requireNotNull(tracker.create(outputB, 1))
        assertNotEquals(a.id, b.id)
        assertEquals(LocalToolsSaveResolution.Ignored, tracker.consume(a.id, 1, outputB, cancelled = false))
        assertEquals(LocalToolsSaveResolution.Ignored, tracker.consume(a.id, 1, outputB, cancelled = true))
        val ready = tracker.consume(b.id, 1, outputB, cancelled = false) as LocalToolsSaveResolution.Ready
        assertSame(outputB, ready.request.output)
    }

    @Test
    fun closeAndReopenInvalidateTheOldPickerEvenWithTheSameFileObject() {
        val tracker = LocalToolsSaveRequests()
        val a = requireNotNull(tracker.create(outputA, 4))
        tracker.invalidate()
        assertEquals(LocalToolsSaveResolution.Ignored, tracker.consume(a.id, 5, outputA, cancelled = false))
        val b = requireNotNull(tracker.create(outputA, 5))
        assertNotEquals(a.id, b.id)
        assertEquals(LocalToolsSaveResolution.Ignored, tracker.consume(a.id, 5, outputA, cancelled = false))
        assertTrue(tracker.consume(b.id, 5, outputA, cancelled = false) is LocalToolsSaveResolution.Ready)
    }

    @Test
    fun changedWorkflowGenerationRejectsAndConsumesAMatchingResult() {
        val tracker = LocalToolsSaveRequests()
        val request = requireNotNull(tracker.create(outputA, 6))
        assertTrue(tracker.consume(request.id, 7, outputA, cancelled = false) is LocalToolsSaveResolution.Invalidated)
        assertEquals(LocalToolsSaveResolution.Ignored, tracker.consume(request.id, 6, outputA, cancelled = false))
    }

    @Test
    fun changedOutputIdentityRejectsInsteadOfSavingTheCurrentCopy() {
        val tracker = LocalToolsSaveRequests()
        val a = requireNotNull(tracker.create(outputA, 1))
        assertTrue(tracker.consume(a.id, 1, outputB, cancelled = false) is LocalToolsSaveResolution.Invalidated)
        val b = requireNotNull(tracker.create(outputB, 1))
        assertTrue(tracker.consume(b.id, 1, null, cancelled = false) is LocalToolsSaveResolution.Invalidated)
        val c = requireNotNull(tracker.create(outputA, 1))
        // Equality of paths is insufficient: the active output must still be the captured object.
        assertTrue(tracker.consume(c.id, 1, File(outputA.path), cancelled = false) is LocalToolsSaveResolution.Invalidated)
    }

    @Test
    fun cancelledChooserConsumesItsRequestAndAllowsANewOne() {
        val tracker = LocalToolsSaveRequests()
        val cancelled = requireNotNull(tracker.create(outputA, 1))
        assertTrue(tracker.consume(cancelled.id, 1, outputA, cancelled = true) is LocalToolsSaveResolution.Cancelled)
        assertEquals(LocalToolsSaveResolution.Ignored, tracker.consume(cancelled.id, 1, outputA, cancelled = false))
        val next = requireNotNull(tracker.create(outputB, 1))
        assertNotEquals(cancelled.id, next.id)
        assertTrue(tracker.consume(next.id, 1, outputB, cancelled = false) is LocalToolsSaveResolution.Ready)
    }

    @Test
    fun unknownResponseCannotCancelOrConsumeTheOutstandingChooser() {
        val tracker = LocalToolsSaveRequests()
        val request = requireNotNull(tracker.create(outputA, 1))
        assertEquals(LocalToolsSaveResolution.Ignored, tracker.consume("unknown", 1, outputA, cancelled = true))
        assertTrue(tracker.consume(request.id, 1, outputA, cancelled = false) is LocalToolsSaveResolution.Ready)
    }
}
