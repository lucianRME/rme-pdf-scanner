package org.synapseworks.pageharbor.document.collage

import org.junit.Assert.*
import org.junit.Test
import org.synapseworks.pageharbor.document.booklayout.BookDerivedPage
import org.synapseworks.pageharbor.document.session.*

class CollagePublicationTest {
 private val paper=DocumentPaperSize(612f,792f)
 private fun original(id:Long)=DocumentPage(DocumentPageId(id),DocumentResource("source:$id",DocumentResourceOwnership.USER_OR_EXTERNAL),DocumentSourceCategory.SCAN)
 private fun output(n:Int)=CollageOutput(List(n){BookDerivedPage(DocumentResource("derived:$it",DocumentResourceOwnership.RME_OWNED_TEMPORARY,OwnedTemporaryFile("/private/synthetic/$it.jpg","/private/synthetic")),DocumentImageMetadata(100,1530,1980))},paper)
 @Test fun stableOriginalsAndFreshNeutralAppendedIdsWithAllOrNothingValidation(){
  val before=DocumentSession(listOf(original(8),original(3)));val result=collageSession(before,output(2))!!
  assertSame(before.pages[0],result.pages[0]);assertSame(before.pages[1],result.pages[1]);assertEquals(listOf(8L,3L,9L,10L),result.pages.map{it.id.value})
  result.pages.drop(2).forEach{assertNull(it.persistentId);assertTrue(it.annotations.isEmpty&&it.adjustments.isNeutral);assertEquals(paper,it.paperSize);assertEquals(DocumentPageRotation.DEGREES_0,it.rotation)}
  val same=output(1).pages.single();assertNull(collageSession(before,CollageOutput(listOf(same,same),paper)))
  assertNull(collageSession(before,CollageOutput(listOf(same.copy(metadata=DocumentImageMetadata(100,1980,1530))),paper)))
  assertNull(collageSession(before,output(0)));assertNull(collageSession(before,output(13)))
 }
 @Test fun pageAndIdentityBoundsRejectWithoutPublishingAnyPart(){
  assertEquals(500,collageSession(DocumentSession(List(499){original(it.toLong())}),output(1))!!.pages.size)
  assertNull(collageSession(DocumentSession(List(499){original(it.toLong())}),output(2)))
  assertNull(collageSession(DocumentSession(listOf(original(Long.MAX_VALUE))),output(1)))
  val bad=output(1).pages.single().copy(source=DocumentResource("derived:1",DocumentResourceOwnership.RME_OWNED_TEMPORARY,OwnedTemporaryFile("/private/synthetic/../outside.jpg","/private/synthetic")))
  assertNull(collageSession(DocumentSession(listOf(original(1))),CollageOutput(listOf(bad),paper)))
 }
}
