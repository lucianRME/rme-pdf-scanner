package org.synapseworks.pageharbor.document.objectremoval

import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.Collections
import java.util.IdentityHashMap
import org.junit.Assert.*
import org.junit.Test
import org.synapseworks.pageharbor.document.annotation.AnnotationPoint

class ObjectRemovalSafetyTest {
 @Test fun cancellationDuringEachPassAndFinalRowCannotReturnACompletedResult(){
  val src=RemovalCorpus.image("dark-spot-512","source");val input=RemovalCorpus.pixels("dark-spot-512","source");val original=input.copyOf();val mask=RemovalCorpus.mask("dark-spot-512")
  for(stage in 0..3){var reads=0;var writes=0;var cancelled=false
   try{PaperPlaneInpaint.process(src.width,src.height,mask,{y,row->reads++;input.copyInto(row,0,y*src.width,(y+1)*src.width)},{y,_->writes++;if(y==src.height-1)cancelled=true},{if(stage==0&&reads==3||stage==1&&reads==src.height+3||stage==2&&writes==3||stage==3&&cancelled)throw CancellationException()});fail()}catch(_:CancellationException){}
   assertEquals(when(stage){2->3;3->src.height;else->0},writes)
  };assertArrayEquals(original,input)
 }
 @Test fun readWriteFailuresPropagateAndUnsupportedBoundariesNeverWrite(){
  val mask=RemovalCorpus.mask("dark-spot-512");val input=RemovalCorpus.pixels("dark-spot-512","source")
  for(readFailure in listOf(true,false))try{PaperPlaneInpaint.process(512,384,mask,{y,row->if(readFailure)throw IOException()else input.copyInto(row,0,y*512,(y+1)*512)},{_,_->throw IOException()});fail()}catch(_:IOException){}
  var writes=0;assertEquals(RemovalStatus.UNSUPPORTED,PaperPlaneInpaint.process(512,384,mask,{_,row->row.fill(0x7fe8e8e8)},{_,_->writes++}));assertEquals(0,writes)
 }
 @Test fun maximumAdmittedRasterUsesOneSourceRowAndBoundedMaskAndCancels(){
  val buffers=Collections.newSetFromMap(IdentityHashMap<IntArray,Boolean>());var writes=0
  val mask=InpaintMask.paint(4000,3000,listOf(RemovalStroke(listOf(AnnotationPoint(.5f,.6f)),10)))
  assertTrue(mask.size<=768*768)
  try{PaperPlaneInpaint.process(4000,3000,mask,{y,row->buffers.add(row);row.fill(0xffe8e8e8.toInt());if(y in 1780..1820)for(x in 1980..2020)row[x]=0xff202020.toInt()},{_,_->writes++},{if(writes==4)throw CancellationException()});fail()}catch(_:CancellationException){}
  assertEquals(4,writes);assertEquals(1,buffers.size)
 }
 @Test fun invalidDimensionsMasksAndTransparentOutsidePixelsStaySafe(){
  for((w,h)in listOf(0 to 1,1 to 1,6001 to 2,4000 to 4000))try{PaperPlaneInpaint.process(w,h,InpaintMask(1,1,booleanArrayOf(true)),{_,_->fail()},{_,_->fail()});fail()}catch(_:IllegalArgumentException){}
  val input=RemovalCorpus.pixels("dark-spot-512","source");input[5]=0x7f402010;val output=input.copyOf();assertEquals(RemovalStatus.READY,PaperPlaneInpaint.process(512,384,RemovalCorpus.mask("dark-spot-512"),{y,row->input.copyInto(row,0,y*512,(y+1)*512)},{y,row->row.copyInto(output,y*512)}));assertEquals(input[5],output[5])
 }
}
class InpaintMaskTest {
 @Test fun immutablePaintEraseUndoAndClearUseSourceOrientedEffectiveCells(){
  val dot=RemovalStroke(listOf(AnnotationPoint(.5f,.6f)),14);val erase=RemovalStroke(listOf(AnnotationPoint(.5f,.6f)),5,true)
  val first=InpaintMask.paint(512,384,listOf(dot));val corrected=InpaintMask.paint(512,384,listOf(dot,erase));val undo=InpaintMask.paint(512,384,listOf(dot));assertArrayEquals(first.copyCells(),undo.copyCells());assertTrue(first[230*512+256]);assertFalse(corrected[230*512+256]);assertTrue(corrected.copyCells().count{it}<first.copyCells().count{it});assertFalse(InpaintMask.paint(512,384,emptyList()).copyCells().any{it})
  val copy=first.copyCells();copy.fill(false);assertTrue(first.copyCells().any{it})
 }
 @Test fun strokeLimitsAndCancellationRejectBeforeUnboundedWork(){
  val point=AnnotationPoint(.5f,.5f)
  for(strokes in listOf(List(129){RemovalStroke(listOf(point),2)},listOf(RemovalStroke(List(2049){point},2)),listOf(RemovalStroke(listOf(point),33))))try{InpaintMask.paint(512,384,strokes);fail()}catch(_:IllegalArgumentException){}
  try{InpaintMask.paint(512,384,listOf(RemovalStroke(listOf(point),14))){throw CancellationException()};fail()}catch(_:CancellationException){}
  assertEquals(768 to 576,InpaintMask.dimensions(6000,4500));assertEquals(384 to 768,InpaintMask.dimensions(2000,4000))
 }
 @Test fun clippingAndQuarterTurnMappingUseExistingAnnotationCoordinates(){
  for(turn in listOf(0,90,180,270)){val p=AnnotationPoint(.2f,.7f);val restored=p.rotated(turn).rotated((360-turn)%360);assertEquals(p.x,restored.x,.000001f);assertEquals(p.y,restored.y,.000001f)}
  val edge=InpaintMask.paint(512,384,listOf(RemovalStroke(listOf(AnnotationPoint(0f,0f)),14)));assertTrue(edge[0]);assertFalse(edge[383*512+511])
 }
}
