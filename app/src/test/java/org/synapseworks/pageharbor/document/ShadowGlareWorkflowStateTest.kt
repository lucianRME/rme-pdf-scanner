package org.synapseworks.pageharbor.document

import org.junit.Assert.*
import org.junit.Test
import org.synapseworks.pageharbor.PageHarborSessionViewModel
import org.synapseworks.pageharbor.document.session.*
import org.synapseworks.pageharbor.document.batch.*
import org.synapseworks.pageharbor.image.*
import org.synapseworks.pageharbor.library.duplicate.*
class ShadowGlareWorkflowStateTest {
    private val page=DocumentPage(DocumentPageId(1),DocumentResource("synthetic",DocumentResourceOwnership.USER_OR_EXTERNAL),DocumentSourceCategory.SCAN,adjustments=ManualImageAdjustments(20,25))
    @Test fun reviewedFilterApplyIsGuardedEffectiveChangeOnlyAndKeepsAllOtherFields() {
        val vm=PageHarborSessionViewModel();vm.installDocumentSessionForTest(DocumentSession(listOf(page)));val rev=vm.documentRevision
        assertTrue(vm.applyReviewedShadowGlare(page,rev,DocumentFilter.SHADOW_GLARE_BALANCED_V1));assertEquals(rev+1,vm.documentRevision)
        assertEquals(page.copy(filter=DocumentFilter.SHADOW_GLARE_BALANCED_V1),vm.documentPages.single())
        val current=vm.documentPages.single();assertTrue(vm.applyReviewedShadowGlare(current,rev+1,current.filter));assertEquals(rev+1,vm.documentRevision)
        assertFalse(vm.applyReviewedShadowGlare(page,rev,DocumentFilter.SHADOW_GLARE_STRONG_V1))
        assertFalse(vm.applyReviewedShadowGlare(current,rev+1,DocumentFilter.DOCUMENT_CLEANUP))
        assertTrue(vm.applyReviewedShadowGlare(current,rev+1,DocumentFilter.ORIGINAL));assertEquals(page,vm.documentPages.single())
    }
    @Test fun removalReplacementAndNewOwnerCannotRetargetCapturedIds() {
        val vm=PageHarborSessionViewModel();vm.installDocumentSessionForTest(DocumentSession(listOf(page,page.copy(id=DocumentPageId(2)))))
        val rev=vm.documentRevision;vm.reorderPages(listOf(2,1));assertFalse(vm.applyReviewedShadowGlare(page,rev,DocumentFilter.SHADOW_GLARE_LOW_V1))
        vm.removePage(1);assertFalse(vm.applyReviewedShadowGlare(page,vm.documentRevision,DocumentFilter.SHADOW_GLARE_LOW_V1))
        vm.installDocumentSessionForTest(DocumentSession(listOf(page.copy(source=DocumentResource("replacement",DocumentResourceOwnership.USER_OR_EXTERNAL)))))
        assertFalse(vm.applyReviewedShadowGlare(page,vm.documentRevision,DocumentFilter.SHADOW_GLARE_LOW_V1))
        assertNotEquals(vm.documentOwnerToken,PageHarborSessionViewModel().documentOwnerToken)
    }
    @Test fun selectedBatchUsesVersionedRecipeAndLeavesUnselectedSourceManualAndIdentityExact() {
        val other=page.copy(id=DocumentPageId(2),filter=DocumentFilter.GRAYSCALE,rotation=DocumentPageRotation.DEGREES_90)
        val source=DocumentSession(listOf(page,other,page.copy(id=DocumentPageId(3))))
        for(filter in listOf(DocumentFilter.SHADOW_GLARE_LOW_V1,DocumentFilter.SHADOW_GLARE_BALANCED_V1,DocumentFilter.SHADOW_GLARE_STRONG_V1)) {
            val result=prepareBatchEdit(source,setOf(1,3),BatchPageEdit(filter=filter))
            assertEquals(2,result.changedPages);assertSame(other,result.session.pages[1])
            assertEquals(source.pages.map{it.source},result.session.pages.map{it.source})
            assertEquals(source.pages.map{it.adjustments},result.session.pages.map{it.adjustments})
            assertEquals(source.pages.map{it.id},result.session.pages.map{it.id})
        }
    }
    @Test fun persistedNamesKeepFingerprintProfilesAndDistinguishEveryIntensity() {
        val names=listOf("ORIGINAL","DOCUMENT_CLEANUP","SHADOW_GLARE_LOW_V1","SHADOW_GLARE_BALANCED_V1","SHADOW_GLARE_STRONG_V1")
        val hashes=names.map{DocumentFingerprintV1.calculate(listOf(FingerprintPage("1".repeat(64),"image/jpeg",100,0,it)))}
        assertTrue(hashes.all{it.version==1});assertEquals(names.size,hashes.map{it.sha256}.distinct().size)
        for(name in names)assertEquals(name,DocumentFilter.valueOf(name).name)
    }
}
