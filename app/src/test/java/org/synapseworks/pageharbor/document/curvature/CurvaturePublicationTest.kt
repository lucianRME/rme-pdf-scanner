package org.synapseworks.pageharbor.document.curvature

import org.junit.Test
import org.junit.Assert.*
import org.synapseworks.pageharbor.PageHarborSessionViewModel
import org.synapseworks.pageharbor.document.booklayout.BookDerivedPage
import org.synapseworks.pageharbor.document.session.*
import org.synapseworks.pageharbor.image.*
import org.synapseworks.pageharbor.ocr.OcrUiState

class CurvaturePublicationTest {
    private fun page(id:Long)=DocumentPage(DocumentPageId(id),DocumentResource("external:$id",DocumentResourceOwnership.USER_OR_EXTERNAL),DocumentSourceCategory.SCAN,
        rotation=DocumentPageRotation.DEGREES_90,filter=DocumentFilter.PAPER_WHITE_NEUTRAL_BALANCED_V1,persistentId="saved-$id",adjustments=ManualImageAdjustments(15,-20))
    private fun replacement(p:DocumentPage)=CurvatureReplacement(p,BookDerivedPage(DocumentResource("corrected:${p.id.value}",DocumentResourceOwnership.RME_OWNED_TEMPORARY,
        OwnedTemporaryFile("/private/tmp/curve-fixture/page.jpg","/private/tmp/curve-fixture")),DocumentImageMetadata(100,480,720)))
    @Test fun changedGeometryUsesNewIdentityWhileUnaffectedObjectsAndRecipesStayExact() {
        val pages=listOf(page(1),page(2));val pdf=DocumentResource("external:pdf",DocumentResourceOwnership.USER_OR_EXTERNAL)
        val before=DocumentSession(pages,pdf,directPdfPageIds=pages.map{it.id});val after=curvatureSession(before,replacement(pages[0]))!!
        assertEquals(3L,after.pages[0].id.value);assertNull(after.pages[0].persistentId);assertSame(pages[1],after.pages[1]);assertEquals(pages[0].filter,after.pages[0].filter)
        assertEquals(pages[0].adjustments,after.pages[0].adjustments);assertEquals(DocumentPageRotation.DEGREES_0,after.pages[0].rotation);assertNull(after.directPdfSource);assertEquals(listOf(pdf),after.originalPdfSources)
        assertSame(pages[0],before.pages[0])
    }
    @Test fun impostorsSharedSourcesUnownedOutputsAndOverflowCannotPublish() {
        val p=page(1);val s=DocumentSession(listOf(p));val r=replacement(p)
        assertNull(curvatureSession(s,r.copy(original=p.copy())))
        assertNull(curvatureSession(s,r.copy(derived=r.derived.copy(source=p.source))))
        val last=page(Long.MAX_VALUE);assertNull(curvatureSession(DocumentSession(listOf(last)),replacement(last)))
    }
    @Test fun staleAcquisitionAndDuplicatePublicationAreRejectedWithoutRetargeting() {
        val vm=PageHarborSessionViewModel();val p=page(1);vm.installDocumentSessionForTest(DocumentSession(listOf(p)));val original=vm.documentSession;val rev=vm.documentRevision
        assertFalse(vm.applyCurvature(original,rev-1,replacement(p)));val capture=vm.beginContinuousRequest()!!
        assertFalse(vm.applyCurvature(original,rev,replacement(p)));vm.cancelContinuousRequest(capture)
        vm.ocrUiState=OcrUiState.Recognizing;assertTrue(vm.applyCurvature(original,rev,replacement(p)));assertEquals(OcrUiState.Idle,vm.ocrUiState)
        assertEquals(rev+1,vm.documentRevision);assertFalse(vm.applyCurvature(original,rev,replacement(p)));assertEquals(1,vm.documentPages.size)
    }
    @Test fun repeatedSourcePagesRemainDistinctAndOnlyReviewedPageChanges() {
        val p=page(1);val other=page(2).copy(source=p.source);val before=DocumentSession(listOf(p,other));val after=curvatureSession(before,replacement(p))!!
        assertSame(other,after.pages[1]);assertNotEquals(after.pages[0].source.reference,other.source.reference)
    }
}
