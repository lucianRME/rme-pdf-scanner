package org.synapseworks.pageharbor.document.objectremoval

import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

internal object RemovalCorpus {
 val profiles=listOf("dark-spot","faint-spot","colored-spot","rectangle","left-edge","top-edge","corner","gradient","tinted","two-spots","near-text","cross-line","touch-text","texture","ambiguous-front","ambiguous-artifact","empty","oversize","clean-painted")
 fun image(id:String,kind:String)=ImageIO.read(requireNotNull(javaClass.getResourceAsStream("/m28/$id-$kind.png")))
 fun pixels(id:String,kind:String):IntArray {val b=image(id,kind);return b.getRGB(0,0,b.width,b.height,null,0,b.width)}
 fun bits(id:String,kind:String)=pixels(id,kind).map{it and 255>0}.toBooleanArray()
 fun mask(id:String):InpaintMask {val b=image(id,"mask");return InpaintMask(b.width,b.height,bits(id,"mask"))}
}
class ObjectRemovalQualityTest {
 @Test fun frozenRemovalBoundaryAndOutsidePreservationHaveIndependentGates(){
  val root=File("build/reports/m28-quality").apply{mkdirs()};val rows=mutableListOf("case,status,maskMae,reduction,boundaryMae,boundaryMax,outsideMax");val failures=mutableListOf<String>()
  for(w in listOf(512,1024))for((index,profile)in RemovalCorpus.profiles.withIndex()){
   val id="$profile-$w";val src=RemovalCorpus.image(id,"source");val before=RemovalCorpus.pixels(id,"source");val reference=RemovalCorpus.pixels(id,"reference");val effective=RemovalCorpus.bits(id,"effective");val boundary=RemovalCorpus.bits(id,"boundary");val output=before.copyOf();var writes=0
   val status=PaperPlaneInpaint.process(src.width,src.height,RemovalCorpus.mask(id),{y,row->before.copyInto(row,0,y*src.width,(y+1)*src.width)},{y,row->writes++;row.copyInto(output,y*src.width)})
   var originalError=0L;var maskError=0L;var maskCount=0;var boundaryError=0L;var boundaryCount=0;var boundaryMax=0;var outsideMax=0
   for(i in before.indices)for(s in listOf(0,8,16)){val b=before[i] ushr s and 255;val a=output[i] ushr s and 255;val r=reference[i] ushr s and 255
    if(effective[i]){maskCount++;maskError+=abs(a-r);originalError+=abs(b-r)}else outsideMax=maxOf(outsideMax,abs(a-b))
    if(boundary[i]){boundaryCount++;boundaryError+=abs(a-r);boundaryMax=maxOf(boundaryMax,abs(a-r))}
   }
   val mae=maskError.toDouble()/maxOf(1,maskCount);val reduction=if(originalError>0)1-maskError.toDouble()/originalError else 1.0;val edge=boundaryError.toDouble()/maxOf(1,boundaryCount)
   fun gate(ok:Boolean,name:String){if(!ok)failures+="$id:$name"}
   gate(outsideMax==0,"outside exact")
   if(index<11){gate(status==RemovalStatus.READY,"accepted");gate(mae<=1.5,"removal MAE");gate(reduction>=.90,"removal reduction");gate(edge<=2&&boundaryMax<=4,"boundary")}
   else if(profile=="empty")gate(status==RemovalStatus.EMPTY&&writes==0,"empty")
   else if(profile=="clean-painted")gate(status==RemovalStatus.UNCHANGED&&before.contentEquals(output),"clean unchanged")
   else gate(status==RemovalStatus.UNSUPPORTED&&writes==0&&before.contentEquals(output),"decline unchanged")
   rows+="$id,$status,$mae,$reduction,$edge,$boundaryMax,$outsideMax";val b=java.awt.image.BufferedImage(src.width,src.height,java.awt.image.BufferedImage.TYPE_INT_ARGB);b.setRGB(0,0,src.width,src.height,output,0,src.width);ImageIO.write(b,"png",File(root,"$id.png"));assertArrayEquals(before,RemovalCorpus.pixels(id,"source"))
  }
  File(root,"candidate.csv").writeText(rows.joinToString("\n")+"\n");File(root,"failures.txt").writeText(failures.joinToString("\n"));assertTrue(failures.joinToString(","),failures.isEmpty())
 }
}
