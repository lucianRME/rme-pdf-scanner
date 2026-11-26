package org.synapseworks.pageharbor.document.finger

import javax.imageio.ImageIO
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test
import org.synapseworks.pageharbor.document.objectremoval.*
import org.synapseworks.pageharbor.document.annotation.AnnotationPoint

class FingerSafetyTest {
 private fun source():IntArray{val b=ImageIO.read(javaClass.getResourceAsStream("/m29/medium-left-512-source.png"));return b.getRGB(0,0,b.width,b.height,null,0,b.width)}
 @Test fun cancellationDuringSamplingComponentAndGuardPassesNeverCompletesOrWrites(){
  val pixels=source()
  for(limit in listOf(1,600,1200,2400)){var polls=0;try{EdgeFingerDetector.detect(512,384,{y,row->pixels.copyInto(row,0,y*512,(y+1)*512)}){if(++polls==limit)throw CancellationException()};fail("must cancel")}catch(_:CancellationException){assertEquals(limit,polls)}}
  val detection=EdgeFingerDetector.detect(512,384,{y,row->pixels.copyInto(row,0,y*512,(y+1)*512)})
  for(limit in listOf(1,1200,3600,6000)){var polls=0;var writes=0;try{FingerPaperRepair.process(512,384,detection.mask,{y,row->pixels.copyInto(row,0,y*512,(y+1)*512)},{_,_->writes++}){if(++polls==limit)throw CancellationException()};fail("must cancel")}catch(_:CancellationException){assertEquals(0,writes)}}
 }
 @Test fun sourceReadWriteFailureAndUnsupportedInputsDoNotBecomePreparedResults(){
  try{EdgeFingerDetector.detect(512,384,{_,_->throw java.io.IOException()});fail()}catch(_:java.io.IOException){}
  val p=source();val d=EdgeFingerDetector.detect(512,384,{y,row->p.copyInto(row,0,y*512,(y+1)*512)})
  try{FingerPaperRepair.process(512,384,d.mask,{y,row->p.copyInto(row,0,y*512,(y+1)*512)},{_,_->throw java.io.IOException()});fail()}catch(_:java.io.IOException){}
  val tiny=EdgeFingerDetector.detect(200,100,{_,_->fail("no decode for small grid")});assertEquals(FingerDecision.UNSUPPORTED,tiny.decision)
  val alpha=EdgeFingerDetector.detect(512,384,{_,row->row.fill(0x70e0b090)});assertEquals(FingerDecision.UNSUPPORTED,alpha.decision)
  try{EdgeFingerDetector.detect(6000,6000,{_,_->fail()});fail()}catch(_:IllegalArgumentException){}
 }
 @Test fun maximumInputSamplesOnlyBoundedGridRowsAndPollsBeforeLargeTraversal(){
  var rows=0;var polls=0
  try{EdgeFingerDetector.detect(4000,3000,{_,row->assertEquals(4000,row.size);rows++;row.fill(-1)}){if(++polls==100)throw CancellationException()};fail()}catch(_:CancellationException){}
  assertTrue(rows in 1..768);assertEquals(100,polls)
 }
 @Test fun accumulatedShapeBudgetRejectsBeforeAdditionalFloodFillAllocation(){
  val p=source();val result=EdgeFingerDetector.detect(512,384,{y,row->p.copyInto(row,0,y*512,(y+1)*512)},shapeCellLimit=1)
  assertEquals(FingerDecision.UNSUPPORTED,result.decision);assertEquals(0,result.proposals);assertFalse(result.mask.hasSelection)
 }
 @Test fun correctionsPreserveOriginalAutomaticMaskAndUndoCanRestoreIt(){
  val p=source();val d=EdgeFingerDetector.detect(512,384,{y,row->p.copyInto(row,0,y*512,(y+1)*512)})
  val before=d.mask.copyCells();val erase=RemovalStroke(listOf(AnnotationPoint(.03f,.65f)),5,true)
  val corrected=InpaintMask.paint(512,384,listOf(erase),d.mask);assertTrue(corrected.copyCells().count{it}<before.count{it});assertArrayEquals(before,d.mask.copyCells())
  assertArrayEquals(before,InpaintMask.paint(512,384,emptyList(),d.mask).copyCells());assertEquals(1,d.proposals)
 }
}
