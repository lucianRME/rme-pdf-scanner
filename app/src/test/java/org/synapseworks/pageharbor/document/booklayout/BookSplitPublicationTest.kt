package org.synapseworks.pageharbor.document.booklayout

import org.junit.Test
import org.junit.Assert.*
import org.synapseworks.pageharbor.PageHarborSessionViewModel
import org.synapseworks.pageharbor.document.session.*
import org.synapseworks.pageharbor.image.*
import org.synapseworks.pageharbor.ocr.OcrUiState

class BookSplitPublicationTest {
    private fun page(id:Long)=DocumentPage(DocumentPageId(id),DocumentResource("external:$id",DocumentResourceOwnership.USER_OR_EXTERNAL),
        DocumentSourceCategory.SCAN,rotation=DocumentPageRotation.DEGREES_90,filter=DocumentFilter.PAPER_WHITE_COLOUR_STRONG_V1,
        persistentId="saved-$id",adjustments=ManualImageAdjustments(20,-25))
    private fun replacement(page:DocumentPage,offset:Int=0)=BookPageReplacement(page,(0..1).map{BookDerivedPage(
        DocumentResource("derived:${page.id.value}:${it+offset}",DocumentResourceOwnership.RME_OWNED_TEMPORARY,OwnedTemporaryFile("/private/tmp/book-test/${page.id.value}-${it+offset}.jpg","/private/tmp/book-test")),DocumentImageMetadata(100,200,300))})
    @Test fun replacementKeepsUnselectedObjectsEditsAndProvenanceButGivesHalvesFreshStableIdentities() {
        val pages=(1L..3L).map(::page);val pdf=DocumentResource("external:pdf",DocumentResourceOwnership.USER_OR_EXTERNAL)
        val before=DocumentSession(pages,directPdfSource=pdf,directPdfPageIds=pages.map{it.id})
        val after=bookSplitSession(before,listOf(replacement(pages[1])))!!
        assertSame(pages[0],after.pages[0]);assertSame(pages[2],after.pages[3]);assertEquals(listOf(1L,4L,5L,3L),after.pages.map{it.id.value})
        for(half in after.pages.subList(1,3)){assertEquals(pages[1].filter,half.filter);assertEquals(pages[1].adjustments,half.adjustments);assertEquals(DocumentPageRotation.DEGREES_0,half.rotation);assertNull(half.persistentId)}
        assertNull(after.directPdfSource);assertEquals(listOf(pdf),after.originalPdfSources);assertEquals(3,before.pages.size)
    }
    @Test fun duplicateImpostorAndSharedOutputReferencesCannotPublish() {
        val p=page(1);val s=DocumentSession(listOf(p));val r=replacement(p)
        assertNull(bookSplitSession(s,listOf(r,r)))
        assertNull(bookSplitSession(s,listOf(r.copy(original=p.copy()))))
        assertNull(bookSplitSession(s,listOf(r.copy(halves=listOf(r.halves[0],r.halves[0])))))
        assertNull(bookSplitSession(s,listOf(r.copy(halves=listOf(r.halves[0])))))
    }
    @Test fun deliberatelyRepeatedSourcesRemainTwoIndependentSpreads() {
        val a=page(1);val b=page(2).copy(source=a.source);val before=DocumentSession(listOf(a,b))
        val after=bookSplitSession(before,listOf(replacement(a),replacement(b)))!!
        assertEquals(4,after.pages.size);assertEquals(4,after.pages.map{it.id}.distinct().size);assertEquals(4,after.pages.map{it.source.reference}.distinct().size)
    }
    @Test fun sessionPublicationRejectsRevisionAndAcquisitionRacesAndDuplicateHandoff() {
        val owner=PageHarborSessionViewModel();val p=page(1);owner.installDocumentSessionForTest(DocumentSession(listOf(p)));val before=owner.documentSession;val rev=owner.documentRevision
        assertFalse(owner.applyBookSplit(before,rev-1,listOf(replacement(p))))
        val token=owner.beginContinuousRequest()!!;assertFalse(owner.applyBookSplit(before,rev,listOf(replacement(p))));owner.cancelContinuousRequest(token)
        owner.ocrUiState=OcrUiState.Recognizing;assertTrue(owner.applyBookSplit(before,rev,listOf(replacement(p))));assertEquals(rev+1,owner.documentRevision);assertEquals(OcrUiState.Idle,owner.ocrUiState)
        assertFalse(owner.applyBookSplit(before,rev,listOf(replacement(p))));assertEquals(2,owner.documentPages.size)
    }
    @Test fun unchangedAndIdentityOverflowNeverCreatePartialSessions() {
        val original=DocumentSession(listOf(page(Long.MAX_VALUE)));assertSame(original,bookSplitSession(original,emptyList()));assertNull(bookSplitSession(original,listOf(replacement(original.pages[0]))))
    }
}
