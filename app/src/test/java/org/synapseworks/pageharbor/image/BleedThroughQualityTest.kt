package org.synapseworks.pageharbor.image

import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

internal object BleedThroughCorpus {
    val ids=listOf("weak","medium","strong","overlap","clean","clean-white","colored-paper","ambiguous-front","ambiguous-ghost")
    fun image(id:String,kind:String):ArgbImage {val b=ImageIO.read(requireNotNull(javaClass.getResourceAsStream("/m25/$id-$kind.png")));return ArgbImage(b.width,b.height,b.getRGB(0,0,b.width,b.height,null,0,b.width))}
    fun mask(id:String,kind:String)=image(id,kind).pixels.map{it and 255>0}.toBooleanArray()
}
class BleedThroughQualityTest {
    @Test fun frozenGhostReductionAndForegroundPreservationAreSeparateGates(){
        val root=File("build/reports/m25-quality").apply{mkdirs()};val rows=mutableListOf("case,recipe,ghostMae,reduction,foregroundMae,foregroundMax,exact");val failures=mutableListOf<String>()
        for(id in BleedThroughCorpus.ids){val src=BleedThroughCorpus.image(id,"source");val ref=BleedThroughCorpus.image(id,"reference");val foreground=BleedThroughCorpus.mask(id,"foreground");val ghost=BleedThroughCorpus.mask(id,"ghost");val before=src.pixels.copyOf()
            fun ghostMae(p:IntArray):Double {var sum=0L;var n=0;for(i in p.indices)if(ghost[i]){n++;for(s in listOf(0,8,16))sum+=abs((p[i] ushr s and 255)-(ref.pixels[i] ushr s and 255))};return sum.toDouble()/maxOf(1,n*3)}
            val baseline=ghostMae(before)
            for(f in DocumentFilter.entries.filter{it.bleedThroughAmount!=null}){val out=DocumentImageFilterEngine.apply(src,f);var delta=0L;var count=0;var max=0
                for(i in before.indices)if(foreground[i]){count++;for(s in listOf(0,8,16)){val d=abs((out.pixels[i] ushr s and 255)-(before[i] ushr s and 255));delta+=d;max=maxOf(max,d)}}
                val mae=delta.toDouble()/maxOf(1,count*3);val residual=ghostMae(out.pixels);val reduction=if(baseline>0)1-residual/baseline else 0.0;val exact=before.contentEquals(out.pixels)
                fun gate(ok:Boolean,name:String){if(!ok)failures+="$id:$f:$name"}
                gate(mae<=.75,"foreground mean");gate(max<=4,"foreground max")
                if(id in listOf("weak","medium","strong")){if(f.bleedThroughAmount==1f)gate(reduction>=.40,"strong reduction");if(f.bleedThroughAmount==.65f)gate(reduction>=.25,"balanced reduction")}
                if(id=="overlap"&&f.bleedThroughAmount==1f)gate(reduction>=.15,"overlap reduction")
                if(id !in listOf("weak","medium","strong","overlap"))gate(exact,"control exact")
                rows+="$id,$f,$residual,$reduction,$mae,$max,$exact"
                val b=java.awt.image.BufferedImage(out.width,out.height,java.awt.image.BufferedImage.TYPE_INT_ARGB);b.setRGB(0,0,out.width,out.height,out.pixels,0,out.width);ImageIO.write(b,"png",File(root,"$id-$f.png"))
                assertArrayEquals(before,src.pixels)
            }
        };File(root,"candidate.csv").writeText(rows.joinToString("\n")+"\n");assertTrue(failures.joinToString(","),failures.isEmpty())
    }
}
