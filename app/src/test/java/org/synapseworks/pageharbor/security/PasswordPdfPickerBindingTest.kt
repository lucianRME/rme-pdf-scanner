package org.synapseworks.pageharbor.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordPdfPickerBindingTest {
    @Test fun restoredOpaqueRequestKeepsAnOldProviderResultAwayFromANewCopy() {
        val oldProcess = ProtectedActionQueueViewModel()
        oldProcess.markPasswordPdfSavePickerLaunched("copy-a")
        val restoredProcess = ProtectedActionQueueViewModel()
        restoredProcess.restorePasswordPdfSavePickerRequest(oldProcess.passwordPdfSavePickerRequestForRecreation())
        assertFalse(restoredProcess.markPasswordPdfSavePickerLaunched("copy-b"))
        assertEquals("copy-a", restoredProcess.consumePasswordPdfSavePickerRequest())
        assertTrue(restoredProcess.markPasswordPdfSavePickerLaunched("copy-b"))
    }

    @Test fun outstandingPickerCannotBeReboundToAnotherCopy() {
        val queue = ProtectedActionQueueViewModel()
        assertTrue(queue.markPasswordPdfSavePickerLaunched("copy-a"))
        assertFalse(queue.markPasswordPdfSavePickerLaunched("copy-b"))
        assertEquals("copy-a", queue.consumePasswordPdfSavePickerRequest())
        assertNull(queue.consumePasswordPdfSavePickerRequest())
        assertTrue(queue.markPasswordPdfSavePickerLaunched("copy-b"))
        assertEquals("copy-b", queue.consumePasswordPdfSavePickerRequest())
    }

    @Test fun resultKeepsItsImmutableTicketWhileQueuedBehindAppLock() {
        val queue = ProtectedActionQueueViewModel()
        queue.markPasswordPdfSavePickerLaunched("copy-a")
        val id = requireNotNull(queue.consumePasswordPdfSavePickerRequest())
        queue.enqueue(PendingProtectedAction.PasswordPdfDestination(null, id))
        queue.markPasswordPdfSavePickerLaunched("copy-b")
        val result = queue.poll() as PendingProtectedAction.PasswordPdfDestination
        assertEquals("copy-a", result.requestId)
        assertEquals("copy-b", queue.consumePasswordPdfSavePickerRequest())
    }
}
