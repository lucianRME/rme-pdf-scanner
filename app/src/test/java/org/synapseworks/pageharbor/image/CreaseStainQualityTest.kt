package org.synapseworks.pageharbor.image

import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

internal object CreaseStainCorpus {
    val ids=listOf("crease-weak","crease-medium","crease-strong","stain-weak","stain-medium","stain-strong","combined","crossing","clean","clean-white","colored-paper","sharp-crease","punctuation","ambiguous-front","ambiguous-stain","dark-photo")
    fun image(id:String,kind:String):ArgbImage {val b=ImageIO.read(requireNotNull(javaClass.getResourceAsStream("/m26/$id-$kind.png")));return ArgbImage(b.width,b.height,b.getRGB(0,0,b.width,b.height,null,0,b.width))}
    fun mask(id:String,kind:String)=image(id,kind).pixels.map{it and 255>0}.toBooleanArray()
}
class CreaseStainQualityTest {
    @Test fun frozenCreaseStainAndForegroundHaveIndependentGates(){
        val root=File("build/reports/m26-quality").apply{mkdirs()};val rows=mutableListOf("case,recipe,creaseMae,creaseReduction,stainMae,stainReduction,foregroundMae,foregroundMax,exact");val failures=mutableListOf<String>()
        for(id in CreaseStainCorpus.ids){val src=CreaseStainCorpus.image(id,"source");val ref=CreaseStainCorpus.image(id,"reference");val foreground=CreaseStainCorpus.mask(id,"foreground");val crease=CreaseStainCorpus.mask(id,"crease");val stain=CreaseStainCorpus.mask(id,"stain");val before=src.pixels.copyOf()
            fun mae(p:IntArray,mask:BooleanArray):Double{var sum=0L;var n=0;for(i in p.indices)if(mask[i]){n++;for(s in listOf(0,8,16))sum+=abs((p[i] ushr s and 255)-(ref.pixels[i] ushr s and 255))};return sum.toDouble()/maxOf(1,n*3)}
            val cb=mae(before,crease);val sb=mae(before,stain)
            for(f in DocumentFilter.entries.filter{it.creaseStainAmount!=null}){val out=DocumentImageFilterEngine.apply(src,f);var delta=0L;var count=0;var max=0
                for(i in before.indices)if(foreground[i]){count++;for(s in listOf(0,8,16)){val d=abs((out.pixels[i] ushr s and 255)-(before[i] ushr s and 255));delta+=d;max=maxOf(max,d)}}
                val fm=delta.toDouble()/maxOf(1,count*3);val cm=mae(out.pixels,crease);val sm=mae(out.pixels,stain);val cr=if(cb>0)1-cm/cb else 0.0;val sr=if(sb>0)1-sm/sb else 0.0;val exact=before.contentEquals(out.pixels)
                fun gate(ok:Boolean,name:String){if(!ok)failures+="$id:$f:$name"}
                gate(fm<=.75,"foreground mean");gate(max<=4,"foreground max")
                if(id.startsWith("crease-")){if(f.creaseStainAmount==1f)gate(cr>=.40,"crease strong");if(f.creaseStainAmount==.65f)gate(cr>=.25,"crease balanced")}
                if(id.startsWith("stain-")){if(f.creaseStainAmount==1f)gate(sr>=.45,"stain strong");if(f.creaseStainAmount==.65f)gate(sr>=.25,"stain balanced")}
                if(id in listOf("combined","crossing")&&f.creaseStainAmount==1f){gate(cr>=.30,"combined crease");gate(sr>=.35,"combined stain")}
                if(id !in CreaseStainCorpus.ids.take(8))gate(exact,"exact control")
                rows+="$id,$f,$cm,$cr,$sm,$sr,$fm,$max,$exact"
                val b=java.awt.image.BufferedImage(out.width,out.height,java.awt.image.BufferedImage.TYPE_INT_ARGB);b.setRGB(0,0,out.width,out.height,out.pixels,0,out.width);ImageIO.write(b,"png",File(root,"$id-$f.png"));assertArrayEquals(before,src.pixels)
            }
        };File(root,"candidate.csv").writeText(rows.joinToString("\n")+"\n");assertTrue(failures.joinToString(","),failures.isEmpty())
    }
}
