package org.synapseworks.pageharbor.document.objectremoval

import org.junit.Assert.*
import org.junit.Test
import org.synapseworks.pageharbor.document.session.*
import org.synapseworks.pageharbor.document.booklayout.BookDerivedPage
import org.synapseworks.pageharbor.document.history.*
import org.synapseworks.pageharbor.image.*

class ObjectRemovalPublicationTest {
 private fun resource(name:String)=DocumentResource("private:$name",DocumentResourceOwnership.RME_OWNED_TEMPORARY,OwnedTemporaryFile("/private/work/$name","/private/work"))
 private val page=DocumentPage(DocumentPageId(3),DocumentResource("original",DocumentResourceOwnership.USER_OR_EXTERNAL),DocumentSourceCategory.SELECTED_IMAGE,"image/png",DocumentImageMetadata(1234,512,384),DocumentPageRotation.DEGREES_90,DocumentFilter.MOIRE_STRONG_V1,persistentId="saved",adjustments=ManualImageAdjustments(10,20))
 private fun output(p:DocumentPage=page,n:Int=0)=RemovalPrepared(p,resource("source$n"),BookDerivedPage(resource("result$n"),DocumentImageMetadata(1234,512,384)),"a".repeat(64),"b".repeat(64))
 @Test fun publicationChangesOnlySelectedPixelsIdentityAndOcrSource(){
  val other=page.copy(id=DocumentPageId(9));val session=DocumentSession(listOf(page,other));val published=requireNotNull(removalPublication(session,output()))
  assertSame(other,published.before.pages[1]);assertSame(other,published.after.pages[1]);val accepted=published.after.pages[0]
  assertEquals(10L,accepted.id.value);assertEquals(null,accepted.persistentId);assertEquals(page.rotation,accepted.rotation);assertEquals(page.filter,accepted.filter);assertEquals(page.adjustments,accepted.adjustments);assertSame(page.annotations,accepted.annotations);assertEquals(page.paperSize,accepted.paperSize);assertEquals("image/png",accepted.contentType)
  assertEquals(page.copy(source=output().snapshot),published.before.pages[0]);assertNotEquals(page.source,accepted.source);assertSame(page,session.pages[0])
 }
 @Test fun oneTransactionReplaysExactSnapshotAndResultReferencesAndCleanBaseline(){
  val session=DocumentSession(listOf(page));val publication=requireNotNull(removalPublication(session,output()));val history=DocumentHistory();history.reset(session)
  history.record(session,publication.after,HistoryAction.OBJECT_REMOVAL,publication.before);assertEquals(1,history.undoCount);assertTrue(history.dirty);assertTrue(session in history.sessions())
  assertTrue(history.accept(requireNotNull(history.move(true))));assertSame(publication.before,history.present);assertEquals(output().snapshot,history.present.pages[0].source);assertFalse(history.dirty)
  assertTrue(history.accept(requireNotNull(history.move(false))));assertSame(publication.after,history.present);assertEquals(output().result.source,history.present.pages[0].source);assertTrue(history.dirty)
 }
 @Test fun branchingAfterSnapshotUndoRetainsCleanEquivalenceAndBoundedAliases(){
  val history=DocumentHistory();history.reset(DocumentSession(listOf(page)))
  repeat(100){n->val before=history.present;val pub=requireNotNull(removalPublication(before,output(before.pages[0],n)));history.record(before,pub.after,HistoryAction.OBJECT_REMOVAL,pub.before);assertTrue(history.accept(requireNotNull(history.move(true))));assertFalse(history.dirty);assertEquals(1,history.redoCount);assertTrue(history.metadataCharge()<20000)}
  val before=history.present;history.record(before,before.copy(pages=before.pages.map{it.copy(filter=DocumentFilter.GRAYSCALE)}),HistoryAction.FILTER);assertTrue(history.accept(requireNotNull(history.move(true))));assertFalse(history.dirty)
 }
 @Test fun staleAndUnsafePublicationsCannotRetargetAnEqualLookingPage(){
  assertNull(removalPublication(DocumentSession(listOf(page.copy())),output()))
  val unsafe=resource("../escape");assertNull(removalPublication(DocumentSession(listOf(page)),output().copy(snapshot=unsafe)))
  assertNull(removalPublication(DocumentSession(listOf(page)),output().copy(result=BookDerivedPage(output().snapshot,output().result.metadata))))
  val max=page.copy(id=DocumentPageId(Long.MAX_VALUE));assertNull(removalPublication(DocumentSession(listOf(max)),output(max)))
 }
 @Test fun metadataAndSourceCapsCountProvenanceAndEvictBoundedFrames(){
  val history=DocumentHistory(HistoryLimits(entries=2));var session=DocumentSession(listOf(page));history.reset(session)
  repeat(5){n->val pub=requireNotNull(removalPublication(session,output(session.pages[0],n)));history.record(session,pub.after,HistoryAction.OBJECT_REMOVAL,pub.before);session=pub.after}
  assertEquals(2,history.undoCount);assertTrue(history.limited);assertTrue(history.sessions().size<=7)
  val retired=history.evictToByteLimit{if(it.size>1)Long.MAX_VALUE else 0};assertTrue(retired.isNotEmpty());assertEquals(0,history.undoCount);assertSame(session,history.present)
 }
}
