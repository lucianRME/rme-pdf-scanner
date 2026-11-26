package org.synapseworks.pageharbor.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalToolsPickerBindingTest {
    @Test fun restoredOpaqueRequestKeepsAnOldProviderResultAwayFromANewCopy() {
        val oldProcess = ProtectedActionQueueViewModel()
        oldProcess.markLocalToolsSavePickerLaunched("copy-a")
        val restoredProcess = ProtectedActionQueueViewModel()
        restoredProcess.restoreLocalToolsSavePickerRequest(oldProcess.localToolsSavePickerRequestForRecreation())
        assertFalse(restoredProcess.markLocalToolsSavePickerLaunched("copy-b"))
        assertEquals("copy-a", restoredProcess.consumeLocalToolsSavePickerRequest())
        assertTrue(restoredProcess.markLocalToolsSavePickerLaunched("copy-b"))
    }

    @Test fun outstandingPickerCannotBeReboundToAnotherCopy() {
        val queue = ProtectedActionQueueViewModel()
        assertTrue(queue.markLocalToolsSavePickerLaunched("copy-a"))
        assertFalse(queue.markLocalToolsSavePickerLaunched("copy-b"))
        assertEquals("copy-a", queue.consumeLocalToolsSavePickerRequest())
        assertNull(queue.consumeLocalToolsSavePickerRequest())
        assertTrue(queue.markLocalToolsSavePickerLaunched("copy-b"))
        assertEquals("copy-b", queue.consumeLocalToolsSavePickerRequest())
    }

    @Test fun resultKeepsItsImmutableTicketWhileQueuedBehindAppLock() {
        val queue = ProtectedActionQueueViewModel()
        queue.markLocalToolsSavePickerLaunched("copy-a")
        val id = requireNotNull(queue.consumeLocalToolsSavePickerRequest())
        queue.enqueue(PendingProtectedAction.LocalToolsPdfDestination(null, id))
        queue.markLocalToolsSavePickerLaunched("copy-b")
        val result = queue.poll() as PendingProtectedAction.LocalToolsPdfDestination
        assertEquals("copy-a", result.requestId)
        assertEquals("copy-b", queue.consumeLocalToolsSavePickerRequest())
    }
}
