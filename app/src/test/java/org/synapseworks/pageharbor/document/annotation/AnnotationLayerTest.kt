package org.synapseworks.pageharbor.document.annotation

import org.junit.Assert.*
import org.junit.Test

class AnnotationLayerTest {
 @Test fun everyToolIsEditableWithoutChangingIdentityOrOrder() {
  val marks=AnnotationTool.entries.filter{it!=AnnotationTool.SIGNATURE}.mapIndexed { i,t -> AnnotationMark.create("m$i",t,AnnotationBounds(.1f,.2f,.6f,.7f),points=if(t==AnnotationTool.FREEHAND) listOf(AnnotationPoint(0f,0f),AnnotationPoint(1f,1f))else emptyList(),text=if(t==AnnotationTool.TEXT) "Café\nRésumé" else "") }
  val layer=AnnotationLayer.of(marks)
  val changed=marks.first().withBounds(AnnotationBounds(.2f,.3f,.7f,.8f))
  val edited=layer.put(changed)
  assertEquals("m0",edited.marks.first().id);assertEquals(.2f,edited.marks.first().bounds.left,0f)
  assertEquals(.1f,layer.marks.first().bounds.left,0f)
  assertEquals(6,edited.remove("m0").marks.size)
  assertEquals(edited,AnnotationLayer.decode(edited.encode()))
 }
 @Test fun independentQuarterTurnsAndLetterboxZoomAreInvertible() {
  val p=AnnotationPoint(.2f,.3f)
  assertEquals(AnnotationPoint(.7f,.2f),p.rotated(90))
  assertEquals(AnnotationPoint(.8f,.7f),p.rotated(180))
  assertEquals(AnnotationPoint(.3f,.8f),p.rotated(270))
  val viewport=AnnotationViewport(1000f,800f,400f,200f,2f,40f,-20f)
  // Fit is2.5; unzoomed content1000x500 at0,150; zoom around500,400 thenpan.
  val pixel=viewport.toDisplay(AnnotationPoint(.25f,.6f))
  assertEquals(40f,pixel.first,.01f);assertEquals(480f,pixel.second,.01f)
  assertEquals(.25f,viewport.toSource(pixel.first,pixel.second)!!.x,.0001f)
  assertNull(viewport.toSource(-999f,-999f))
 }
 @Test fun rejectsLimitsUnsupportedTextAndCorruptLayersWithoutTruncation() {
  fun rejected(block:()->Unit){try{block();fail("invalid annotation accepted")}catch(_:IllegalArgumentException){}}
  rejected { AnnotationMark.create("a",AnnotationTool.TEXT,AnnotationBounds(.1f,.1f,.9f,.9f),text="secret\u0000") }
  rejected { AnnotationMark.create("a",AnnotationTool.TEXT,AnnotationBounds(.1f,.1f,.9f,.9f),text="漢字") }
  rejected { AnnotationMark.create("a",AnnotationTool.TEXT,AnnotationBounds(.1f,.1f,.9f,.9f),text="x".repeat(4097)) }
  val a=AnnotationMark.create("a",AnnotationTool.RECTANGLE,AnnotationBounds(0f,0f,1f,1f))
  rejected { AnnotationLayer.of(listOf(a,a)) };rejected { AnnotationLayer.decode("invalid") }
  assertTrue(AnnotationLayer.of(listOf(a)).metadataBytes>AnnotationLayer.EMPTY.metadataBytes)
 } @Test fun mutableInputsCannotRetargetFrozenLayersAndAggregateLimitsRejectWholeEdits() {
  val edge=AnnotationBounds(.99005f,.99005f,1f,1f).resized(1.1f);assertEquals(1f,edge.right,0f);assertEquals(1f,edge.bottom,0f)
  val points=mutableListOf(AnnotationPoint(0f,0f),AnnotationPoint(1f,1f))
  val mark=AnnotationMark.create("immutable",AnnotationTool.FREEHAND,AnnotationBounds(0f,0f,1f,1f),points=points)
  val marks=mutableListOf(mark);val layer=AnnotationLayer.of(marks);val encoded=layer.encode();points.clear();marks.clear()
  assertEquals(encoded,layer.encode());assertEquals(2,layer.marks.single().points.size)
  val source=org.synapseworks.pageharbor.document.session.DocumentResource("source",org.synapseworks.pageharbor.document.session.DocumentResourceOwnership.USER_OR_EXTERNAL)
  val page=org.synapseworks.pageharbor.document.session.DocumentPage(org.synapseworks.pageharbor.document.session.DocumentPageId(1),source,org.synapseworks.pageharbor.document.session.DocumentSourceCategory.SCAN,annotations=layer)
  val plan=org.synapseworks.pageharbor.document.pageJpegExportPlan(page)as org.synapseworks.pageharbor.document.PageJpegExportPlan.Filtered
  assertEquals(layer,plan.annotations);assertTrue(page.copy(annotations=AnnotationLayer.EMPTY).annotations.isEmpty);assertEquals(layer,plan.annotations)

  try { AnnotationLayer.of((0..128).map{AnnotationMark.create("m$it",AnnotationTool.RECTANGLE,AnnotationBounds(0f,0f,1f,1f))});fail("Count bound ignored") }catch(_:IllegalArgumentException){}
  try { AnnotationMark.create("control",AnnotationTool.TEXT,AnnotationBounds(0f,0f,1f,1f),text="x\u0085");fail("Control accepted") }catch(_:IllegalArgumentException){}
 }
 @Test fun annotatedPageCannotBeSilentlySplitOrNonlinearlyCorrected() {
  val source=org.synapseworks.pageharbor.document.session.DocumentResource("source",org.synapseworks.pageharbor.document.session.DocumentResourceOwnership.USER_OR_EXTERNAL)
  val page=org.synapseworks.pageharbor.document.session.DocumentPage(org.synapseworks.pageharbor.document.session.DocumentPageId(1),source,org.synapseworks.pageharbor.document.session.DocumentSourceCategory.SCAN,
   annotations=AnnotationLayer.of(listOf(AnnotationMark.create("kept",AnnotationTool.RECTANGLE,AnnotationBounds(.1f,.1f,.9f,.9f)))))
  val session=org.synapseworks.pageharbor.document.session.DocumentSession(listOf(page))
  val derived=org.synapseworks.pageharbor.document.booklayout.BookDerivedPage(source,page.imageMetadata)
  assertNull(org.synapseworks.pageharbor.document.booklayout.bookSplitSession(session,listOf(org.synapseworks.pageharbor.document.booklayout.BookPageReplacement(page,listOf(derived,derived)))))
  assertNull(org.synapseworks.pageharbor.document.curvature.curvatureSession(session,org.synapseworks.pageharbor.document.curvature.CurvatureReplacement(page,derived)))
  assertEquals("kept",session.pages.single().annotations.marks.single().id)
 }

}
