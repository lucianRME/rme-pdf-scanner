package org.synapseworks.pageharbor.document.finger

import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test
import org.synapseworks.pageharbor.document.objectremoval.*

class FingerQualityTest {
 private fun image(id:String,kind:String)=ImageIO.read(requireNotNull(javaClass.getResourceAsStream("/m29/$id-$kind.png")))
 private fun pixels(id:String,kind:String):IntArray{val b=image(id,kind);return b.getRGB(0,0,b.width,b.height,null,0,b.width)}
 private fun bits(id:String,kind:String)=pixels(id,kind).map{it and 255>0}.toBooleanArray()
 @Test fun frozenAutomaticDetectionMasksFalseProposalsAndIndependentRepairGates(){
  val profiles=listOf("light-left","light-right","light-top","light-bottom","medium-left","medium-right","medium-top","medium-bottom","deep-left","deep-right","deep-top","deep-bottom","very-deep-left","very-deep-right","very-deep-top","very-deep-bottom","warm-light","dim-deep","tilted-left","small-top","two-fingers","gradient-right","near-text","cross-line","texture","cool-light","corner","oversize","clean","edge-text","edge-line","red-mark","blue-mark","green-mark","gray-fold","gray-shadow","neutral-box","skin-rectangle","interior-skin","detached-skin","narrow-stroke","wide-strip","tiny-speck","warm-paper","skin-circle","corner-graphic","intentional-finger","printed-finger")
  val out=File("build/reports/m29-quality").apply{mkdirs()};val rows=mutableListOf("case,decision,proposals,maskIou,coverage,repair,maskMae,reduction,boundaryMae,boundaryMax,outsideMax,foregroundChanged");val failures=mutableListOf<String>();var detected=0;var supported=0;var falseProposals=0;var controls=0
  for(size in listOf(512,1024))for((index,profile)in profiles.withIndex()){
   val id="$profile-$size";val src=image(id,"source");val before=pixels(id,"source");val ref=pixels(id,"reference");val truth=bits(id,"finger");val foreground=bits(id,"foreground");val detection=EdgeFingerDetector.detect(src.width,src.height,{y,row->before.copyInto(row,0,y*src.width,(y+1)*src.width)})
   fun gate(ok:Boolean,name:String){if(!ok)failures+="$id:$name"}
   if(index<23){supported++;if(detection.decision==FingerDecision.PROPOSED)detected++;gate(detection.decision==FingerDecision.PROPOSED,"automatic proposal")}
   else if(index<46)gate(detection.decision!=FingerDecision.PROPOSED,"no unsupported proposal")
   if(index>=28){controls++;if(detection.decision==FingerDecision.PROPOSED)falseProposals++}
   if(index>=46)gate(detection.decision==FingerDecision.PROPOSED,"intentional twin remains indistinguishable")
   val mask=detection.mask;val effective=BooleanArray(before.size){i->mask[(i/src.width).toLong().times(mask.height).div(src.height).toInt()*mask.width+(i%src.width).toLong().times(mask.width).div(src.width).toInt()]}
   val intersection=truth.indices.count{truth[it]&&effective[it]};val union=truth.indices.count{truth[it]||effective[it]};val total=truth.count{it};val iou=intersection.toDouble()/maxOf(1,union);val coverage=intersection.toDouble()/maxOf(1,total)
   if(index<23){gate(iou>=.80,"mask IoU");gate(coverage>=.99,"finger coverage")}
   val output=before.copyOf();val status=FingerPaperRepair.process(src.width,src.height,mask,{y,row->before.copyInto(row,0,y*src.width,(y+1)*src.width)},{y,row->row.copyInto(output,y*src.width)})
   var maskError=0L;var initialError=0L;var count=0;var edgeError=0L;var edgeCount=0;var edgeMax=0;var outsideMax=0;var fgChanged=0
   for(i in before.indices){if(foreground[i]&&before[i]!=output[i])fgChanged++
    val x=i%src.width;val y=i/src.width;val boundary=effective[i]&&(x==0||x==src.width-1||y==0||y==src.height-1||!effective[i-1]||!effective[i+1]||!effective[i-src.width]||!effective[i+src.width])
    for(c in listOf(0,8,16)){val d=abs((output[i] ushr c and 255)-(ref[i] ushr c and 255));if(effective[i]){maskError+=d;initialError+=abs((before[i] ushr c and 255)-(ref[i] ushr c and 255));count++}else outsideMax=maxOf(outsideMax,abs((before[i] ushr c and 255)-(output[i] ushr c and 255)));if(boundary){edgeError+=d;edgeCount++;edgeMax=maxOf(edgeMax,d)}}
   }
   val mae=maskError.toDouble()/maxOf(1,count);val reduction=if(initialError>0)1-maskError.toDouble()/initialError else 1.0;val edge= edgeError.toDouble()/maxOf(1,edgeCount)
   gate(outsideMax==0,"outside exact")
   if(index<22){gate(status==RemovalStatus.READY,"repair ready");gate(mae<=1.5&&reduction>=.90,"repair quality");gate(edge<=2&&edgeMax<=4,"boundary quality");gate(fgChanged==0,"visible foreground exact")}
   if(index==22)gate(status==RemovalStatus.UNSUPPORTED&&before.contentEquals(output),"nearby text declines unchanged")
   if(index in 23..45)gate(before.contentEquals(output),"unsupported/control unchanged")
   val actual=java.awt.image.BufferedImage(src.width,src.height,java.awt.image.BufferedImage.TYPE_INT_ARGB);actual.setRGB(0,0,src.width,src.height,output,0,src.width);ImageIO.write(actual,"png",File(out,"$id.png"))
   val m=java.awt.image.BufferedImage(src.width,src.height,java.awt.image.BufferedImage.TYPE_INT_RGB);m.setRGB(0,0,src.width,src.height,IntArray(effective.size){if(effective[it])-1 else -0x1000000},0,src.width);ImageIO.write(m,"png",File(out,"$id-mask.png"))
   rows+="$id,${detection.decision},${detection.proposals},$iou,$coverage,$status,$mae,$reduction,$edge,$edgeMax,$outsideMax,$fgChanged"
  }
  if(detected.toDouble()/supported<.90)failures+="automatic recall";if(falseProposals.toDouble()/controls>.10)failures+="false proposals"
  File(out,"candidate.csv").writeText(rows.joinToString("\n")+"\n");File(out,"failures.txt").writeText(failures.joinToString("\n"));assertTrue(failures.joinToString(","),failures.isEmpty())
 }
 @Test fun independentEdgeTouchingTruthMasksTestRepairWithoutDetectorOrManualCorrection(){
  for(size in listOf(512,1024))for(profile in listOf("light-left","deep-right","medium-top","very-deep-bottom","near-text","cross-line","texture")){
   val id="$profile-$size";val src=image(id,"source");val before=pixels(id,"source");val truth=bits(id,"finger");val (w,h)=InpaintMask.dimensions(src.width,src.height)
   val cells=BooleanArray(w*h);for(i in truth.indices)if(truth[i]){val x=(i%src.width)*w/src.width;val y=(i/src.width)*h/src.height;cells[y*w+x]=true}
   val output=before.copyOf();val status=FingerPaperRepair.process(src.width,src.height,InpaintMask(w,h,cells),{y,row->before.copyInto(row,0,y*src.width,(y+1)*src.width)},{y,row->row.copyInto(output,y*src.width)})
   if(profile in listOf("near-text","cross-line","texture")){assertEquals(id,RemovalStatus.UNSUPPORTED,status);assertArrayEquals(before,output)}else assertEquals(id,RemovalStatus.READY,status)
  }
 }
}
