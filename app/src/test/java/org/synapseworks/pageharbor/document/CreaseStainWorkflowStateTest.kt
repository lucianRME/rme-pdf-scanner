package org.synapseworks.pageharbor.document

import org.junit.Assert.*
import org.junit.Test
import org.synapseworks.pageharbor.PageHarborSessionViewModel
import org.synapseworks.pageharbor.document.session.*
import org.synapseworks.pageharbor.document.batch.*
import org.synapseworks.pageharbor.image.*
import org.synapseworks.pageharbor.library.duplicate.*
class CreaseStainWorkflowStateTest {
    private val page=DocumentPage(DocumentPageId(1),DocumentResource("synthetic",DocumentResourceOwnership.USER_OR_EXTERNAL),DocumentSourceCategory.SCAN,adjustments=ManualImageAdjustments(20,25))
    @Test fun reviewedFilterApplyIsGuardedEffectiveChangeOnlyAndKeepsAllOtherFields() {
        val vm=PageHarborSessionViewModel();vm.installDocumentSessionForTest(DocumentSession(listOf(page)));val rev=vm.documentRevision
        assertTrue(vm.applyReviewedCreaseStain(page,rev,DocumentFilter.CREASE_STAIN_BALANCED_V1,vm.documentOwnerToken));assertEquals(rev+1,vm.documentRevision)
        assertEquals(page.copy(filter=DocumentFilter.CREASE_STAIN_BALANCED_V1),vm.documentPages.single())
        val current=vm.documentPages.single();assertTrue(vm.applyReviewedCreaseStain(current,rev+1,current.filter,vm.documentOwnerToken));assertEquals(rev+1,vm.documentRevision)
        assertFalse(vm.applyReviewedCreaseStain(page,rev,DocumentFilter.CREASE_STAIN_STRONG_V1,vm.documentOwnerToken))
        assertFalse(vm.applyReviewedCreaseStain(current,rev+1,DocumentFilter.DOCUMENT_CLEANUP,vm.documentOwnerToken))
        assertTrue(vm.applyReviewedCreaseStain(current,rev+1,DocumentFilter.ORIGINAL,vm.documentOwnerToken));assertEquals(page,vm.documentPages.single())
    }
    @Test fun removalReplacementAndNewOwnerCannotRetargetCapturedIds() {
        val vm=PageHarborSessionViewModel();vm.installDocumentSessionForTest(DocumentSession(listOf(page,page.copy(id=DocumentPageId(2)))))
        val rev=vm.documentRevision;vm.reorderPages(listOf(2,1));assertFalse(vm.applyReviewedCreaseStain(page,rev,DocumentFilter.CREASE_STAIN_LOW_V1,vm.documentOwnerToken))
        vm.removePage(1);assertFalse(vm.applyReviewedCreaseStain(page,vm.documentRevision,DocumentFilter.CREASE_STAIN_LOW_V1,vm.documentOwnerToken))
        vm.installDocumentSessionForTest(DocumentSession(listOf(page.copy(source=DocumentResource("replacement",DocumentResourceOwnership.USER_OR_EXTERNAL)))))
        assertFalse(vm.applyReviewedCreaseStain(page,vm.documentRevision,DocumentFilter.CREASE_STAIN_LOW_V1,vm.documentOwnerToken))
        assertNotEquals(vm.documentOwnerToken,PageHarborSessionViewModel().documentOwnerToken)
    }
    @Test fun selectedBatchUsesVersionedRecipeAndLeavesUnselectedSourceManualAndIdentityExact() {
        val other=page.copy(id=DocumentPageId(2),filter=DocumentFilter.GRAYSCALE,rotation=DocumentPageRotation.DEGREES_90)
        val source=DocumentSession(listOf(page,other,page.copy(id=DocumentPageId(3))))
        for(filter in DocumentFilter.entries.filter{it.creaseStainAmount!=null}) {
            val result=prepareBatchEdit(source,setOf(1,3),BatchPageEdit(filter=filter))
            assertEquals(2,result.changedPages);assertSame(other,result.session.pages[1])
            assertEquals(source.pages.map{it.source},result.session.pages.map{it.source})
            assertEquals(source.pages.map{it.adjustments},result.session.pages.map{it.adjustments})
            assertEquals(source.pages.map{it.id},result.session.pages.map{it.id})
        }
    }
    @Test fun persistedNamesKeepFingerprintProfilesAndDistinguishEveryIntensity() {
        val names=listOf("ORIGINAL","DOCUMENT_CLEANUP")+DocumentFilter.entries.filter{it.creaseStainAmount!=null}.map{it.name}
        val hashes=names.map{DocumentFingerprintV1.calculate(listOf(FingerprintPage("1".repeat(64),"image/jpeg",100,0,it)))}
        assertTrue(hashes.all{it.version==1});assertEquals(names.size,hashes.map{it.sha256}.distinct().size)
        for(name in names)assertEquals(name,DocumentFilter.valueOf(name).name)
    }
    @Test fun reusedPageInstanceCannotAuthorizeAnOldOwnerReview() {
        val vm=PageHarborSessionViewModel();vm.installDocumentSessionForTest(DocumentSession(listOf(page)))
        val owner=PageHarborSessionViewModel().documentOwnerToken
        vm.installDocumentSessionForTest(DocumentSession(listOf(page)))
        assertFalse(vm.applyReviewedCreaseStain(page,vm.documentRevision,DocumentFilter.CREASE_STAIN_STRONG_V1,owner))
        assertEquals(DocumentFilter.ORIGINAL,vm.documentPages.single().filter)
    }

}
