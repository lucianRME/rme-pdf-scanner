package org.synapseworks.pageharbor.document.batch

import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test
import org.synapseworks.pageharbor.document.session.*
import org.synapseworks.pageharbor.image.*
import org.synapseworks.pageharbor.PageHarborSessionViewModel

class BatchPageEditTest {
    private fun page(id: Long) = DocumentPage(DocumentPageId(id), DocumentResource("synthetic",DocumentResourceOwnership.USER_OR_EXTERNAL),DocumentSourceCategory.SCAN)
    private fun session() = DocumentSession((1L..4L).map(::page))
    @Test fun emptyAndUnknownSelectionsCannotPartiallyApply() {
        val source=session()
        for(ids in listOf(emptySet(),setOf(1L,99L))) {
            try {prepareBatchEdit(source,ids,BatchPageEdit(brightness=20));fail()}catch(_:IllegalArgumentException){}
            assertTrue(source.pages.all{it.adjustments.isNeutral})
        }
    }
    @Test fun noncontiguousSelectionPreservesUnselectedIdentityAndSources() {
        val source=session();val result=prepareBatchEdit(source,setOf(1,3),BatchPageEdit(filter=DocumentFilter.GRAYSCALE,brightness=20))
        assertEquals(2,result.changedPages)
        for(i in source.pages.indices) {
            assertSame(source.pages[i].source,result.session.pages[i].source)
            if(i==0||i==2) assertEquals(ManualImageAdjustments(20),result.session.pages[i].adjustments)
            else assertSame(source.pages[i],result.session.pages[i])
        }
        assertEquals(source.pages.map{it.id},result.session.pages.map{it.id})
    }
    @Test fun fieldSpecificAbsoluteValuesPreserveMixedSettingsAndRelativeRotations() {
        val source=DocumentSession(listOf(page(1).copy(filter=DocumentFilter.GRAYSCALE,rotation=DocumentPageRotation.DEGREES_90,adjustments=ManualImageAdjustments(10,-20)),
            page(2).copy(filter=DocumentFilter.HIGH_CONTRAST,rotation=DocumentPageRotation.DEGREES_270,adjustments=ManualImageAdjustments(-30,40))))
        val changed=prepareBatchEdit(source,setOf(1,2),BatchPageEdit(brightness=0,clockwiseQuarterTurns=1)).session
        assertEquals(listOf(0,0),changed.pages.map{it.adjustments.brightness})
        assertEquals(listOf(-20,40),changed.pages.map{it.adjustments.contrast})
        assertEquals(source.pages.map{it.filter},changed.pages.map{it.filter})
        assertEquals(listOf(DocumentPageRotation.DEGREES_180,DocumentPageRotation.DEGREES_0),changed.pages.map{it.rotation})
    }
    @Test fun unchangedFieldsAreExactNoOpAndSingleSelectionIsIndependent() {
        val source=session();assertSame(source,prepareBatchEdit(source,setOf(1,2,3,4),BatchPageEdit()).session)
        val result=prepareBatchEdit(source,setOf(2),BatchPageEdit(contrast=-100))
        assertEquals(1,result.changedPages);assertEquals(ManualImageAdjustments(0,-100),result.session.pages[1].adjustments)
        assertTrue(source.pages.all{it.adjustments.isNeutral})
    }
    @Test fun cancellationDuringPreparationPublishesNothing() {
        val source=session();var checkpoints=0
        try {prepareBatchEdit(source,setOf(1,2,3,4),BatchPageEdit(brightness=100),{if(++checkpoints==3)throw CancellationException()});fail()}catch(_:CancellationException){}
        assertEquals(3,checkpoints);assertTrue(source.pages.all{it.adjustments.isNeutral})
    }
    @Test fun preparationFailureLeavesInputExact() {
        val source=session();var seen=0
        try {prepareBatchEdit(source,setOf(1,2,3,4),BatchPageEdit(contrast=20),{if(++seen==2)throw IllegalStateException()});fail()}catch(_:IllegalStateException){}
        assertEquals(session(),source)
    }
    @Test fun rangesResolveCapturedPositionsToStableIdsAndRejectInvalidSyntax() {
        val pages=listOf(page(9),page(2),page(7),page(4))
        assertEquals(setOf(9L,7L,4L),batchRangeIds("1,3-4",pages));assertEquals(setOf(9L,7L,4L),batchRangeIds("1,3–4",pages));assertEquals(setOf(9L,2L),batchRangeIds("1-2,2",pages))
        for(input in listOf("", "0", "5", "4-2", "1,", "1-2-3", "2147483648", "1-999999999"))assertNull(batchRangeIds(input,pages))
    }
    @Test fun largeDocumentIsMetadataOnlyWithBoundedProgressAndExactMembership() {
        val source=DocumentSession((1L..10_000L).map(::page));val ids=source.pages.filter{it.id.value%2==0L}.map{it.id.value}.toSet();val progress=mutableListOf<Int>()
        val result=prepareBatchEdit(source,ids,BatchPageEdit(contrast=25),onProgress={progress.add(it)})
        assertEquals(5000,result.changedPages);assertEquals(5000,progress.last());assertTrue(progress.size<=158)
        assertTrue(result.session.pages.all{(it.adjustments.contrast==25)==(it.id.value in ids)})
        assertTrue(source.pages.all{it.adjustments.isNeutral})
    }
    @Test fun recipePreviewMatchesPreparationWithoutCumulativeEdits() {
        val p=page(1).copy(adjustments=ManualImageAdjustments(10,30));val recipe=BatchPageEdit(brightness=20,clockwiseQuarterTurns=3)
        val expected=recipe.preview(p);assertEquals(expected,prepareBatchEdit(DocumentSession(listOf(p)),setOf(1),recipe).session.pages.single())
        assertEquals(expected,recipe.preview(p));assertEquals(10,p.adjustments.brightness)
    }
    @Test fun atomicOwnerAdvancesOnceAndRejectsReorderRemovalAndReplacementCaptures() {
        val vm=PageHarborSessionViewModel();vm.installDocumentSessionForTest(session());val capture=BatchDocumentCapture(vm.documentRevision,vm.documentSession)
        val prepared=prepareBatchEdit(capture.session,setOf(1,3),BatchPageEdit(brightness=20)).session
        assertTrue(vm.commitBatchEdit(capture,prepared));assertEquals(capture.revision+1,vm.documentRevision)
        assertFalse(vm.commitBatchEdit(capture,prepared));val current=BatchDocumentCapture(vm.documentRevision,vm.documentSession)
        assertFalse(vm.commitBatchEdit(current,current.session));assertEquals(current.revision,vm.documentRevision)
        vm.reorderPages(listOf(4,3,2,1));assertFalse(vm.commitBatchEdit(current,prepared))
        val reordered=BatchDocumentCapture(vm.documentRevision,vm.documentSession);vm.removePage(1);assertFalse(vm.commitBatchEdit(reordered,prepared))
        val removed=BatchDocumentCapture(vm.documentRevision,vm.documentSession);vm.installDocumentSessionForTest(DocumentSession(listOf(page(1).copy(source=DocumentResource("other-synthetic",DocumentResourceOwnership.USER_OR_EXTERNAL)))))
        assertFalse(vm.commitBatchEdit(removed,prepared))
    }
    @Test fun clearDropsCapturedSelectionAndReusedIdsStartEmpty() {
        val vm=PageHarborSessionViewModel();vm.installDocumentSessionForTest(session());vm.batchEditor.open();vm.batchEditor.select(setOf(1,3))
        vm.clearScan();assertNull(vm.batchEditor.state)
        vm.installDocumentSessionForTest(session());vm.batchEditor.open();assertTrue(vm.batchEditor.state!!.selectedIds.isEmpty());assertFalse(vm.batchEditor.state!!.canUndo)
    }
    @Test fun invalidRecipeBoundsFailBeforeProcessing() {
        for(n in listOf(-101,101)) {try{BatchPageEdit(brightness=n);fail()}catch(_:IllegalArgumentException){};try{BatchPageEdit(contrast=n);fail()}catch(_:IllegalArgumentException){}}
        for(n in listOf(-1,4))try{BatchPageEdit(clockwiseQuarterTurns=n);fail()}catch(_:IllegalArgumentException){}
        assertEquals(listOf(DocumentFilter.ORIGINAL,DocumentFilter.AUTO_ENHANCE,DocumentFilter.GRAYSCALE,DocumentFilter.BLACK_AND_WHITE,DocumentFilter.HIGH_CONTRAST,DocumentFilter.DOCUMENT_CLEANUP,DocumentFilter.SHADOW_GLARE_LOW_V1,DocumentFilter.SHADOW_GLARE_BALANCED_V1,DocumentFilter.SHADOW_GLARE_STRONG_V1,DocumentFilter.PAPER_WHITE_NEUTRAL_LOW_V1,DocumentFilter.PAPER_WHITE_NEUTRAL_BALANCED_V1,DocumentFilter.PAPER_WHITE_NEUTRAL_STRONG_V1,DocumentFilter.PAPER_WHITE_COLOUR_LOW_V1,DocumentFilter.PAPER_WHITE_COLOUR_BALANCED_V1,DocumentFilter.PAPER_WHITE_COLOUR_STRONG_V1,DocumentFilter.BLEED_THROUGH_LOW_V1,DocumentFilter.BLEED_THROUGH_BALANCED_V1,DocumentFilter.BLEED_THROUGH_STRONG_V1,DocumentFilter.CREASE_STAIN_LOW_V1,DocumentFilter.CREASE_STAIN_BALANCED_V1,DocumentFilter.CREASE_STAIN_STRONG_V1,DocumentFilter.MOIRE_LOW_V1,DocumentFilter.MOIRE_BALANCED_V1,DocumentFilter.MOIRE_STRONG_V1),supportedBatchFilters)
    }
}
