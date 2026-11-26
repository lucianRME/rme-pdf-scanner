package org.synapseworks.pageharbor.document.capture

import org.junit.Assert.*
import org.junit.Test
import org.synapseworks.pageharbor.PageHarborSessionViewModel
import org.synapseworks.pageharbor.document.session.*
import org.synapseworks.pageharbor.document.importing.DocumentImportOrigin
import org.synapseworks.pageharbor.image.*

class ContinuousSessionIntegrationTest {
    private val page=DocumentPage(DocumentPageId(10),DocumentResource("synthetic",DocumentResourceOwnership.USER_OR_EXTERNAL),DocumentSourceCategory.SCAN,
        filter=DocumentFilter.PAPER_WHITE_COLOUR_STRONG_V1,adjustments=ManualImageAdjustments(20,-25),rotation=DocumentPageRotation.DEGREES_90)
    private fun input()=DocumentAcquisitionInput(listOf(AcquiredDocumentPage(AcquiredResource("new-synthetic"),DocumentSourceCategory.SCAN,"image/jpeg")))
    @Test fun initialAndAppendUseTheExistingCoordinatorAndPreserveEditedPageIdentity() {
        val vm=PageHarborSessionViewModel();val t=vm.beginContinuousRequest()!!;assertTrue(vm.completeContinuousRequest(t,vm.documentRevision,input()))
        assertEquals(1,vm.documentPages.size)
        vm.installDocumentSessionForTest(DocumentSession(listOf(page)));val rev=vm.documentRevision;val append=vm.beginContinuousRequest()!!
        assertFalse(vm.beginImportRequest(DocumentImportOrigin.PICKER));assertNull(vm.beginScannerRequest())
        assertTrue(vm.completeContinuousRequest(append,rev,input()));assertSame(page,vm.documentPages.first());assertNotEquals(page.id,vm.documentPages.last().id)
        assertEquals(2,vm.documentPages.size);assertEquals(rev+1,vm.documentRevision)
        assertFalse(vm.completeContinuousRequest(append,rev,input()));assertEquals(2,vm.documentPages.size)
    }
    @Test fun legacyPdfOnlySessionIsPreservedWithoutStartingAnUnsupportedReplacement() {
        val vm=PageHarborSessionViewModel();val original=DocumentSession(directPdfSource=DocumentResource("synthetic-pdf",DocumentResourceOwnership.USER_OR_EXTERNAL))
        vm.installDocumentSessionForTest(original);val revision=vm.documentRevision
        assertNull(vm.beginContinuousRequest());assertSame(original,vm.documentSession);assertEquals(revision,vm.documentRevision)
    }
    @Test fun cancellationRecreationAndStaleResultsCannotReplaceTheOriginalDocument() {
        val vm=PageHarborSessionViewModel();val original=DocumentSession(listOf(page));vm.installDocumentSessionForTest(original)
        val t=vm.beginContinuousRequest()!!;val rev=vm.documentRevision
        vm.resetTransientStateForRecreation();vm.cancelImportRequest() // unrelated callbacks must not consume this owner
        assertTrue(vm.completeContinuousRequest(t,rev,input()))
        val keep=vm.documentSession;val pending=vm.beginContinuousRequest()!!;val old=vm.documentRevision
        vm.setPageFilter(page.id.value,DocumentFilter.SHADOW_GLARE_LOW_V1);val current=vm.documentSession
        assertFalse(vm.completeContinuousRequest(pending,old,input()));assertSame(current,vm.documentSession)
        val cancel=vm.beginContinuousRequest()!!;vm.cancelContinuousRequest(cancel);assertSame(current,vm.documentSession)
        assertFalse(vm.completeContinuousRequest(cancel,vm.documentRevision,input()));assertNotEquals(original,keep)
    }
}
