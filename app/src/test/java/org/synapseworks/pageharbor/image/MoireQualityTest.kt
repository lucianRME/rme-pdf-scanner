package org.synapseworks.pageharbor.image

import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

internal object MoireCorpus {
    val profiles=listOf(16,24,32,48).flatMap{k->(0..3).map{"band-$k-$it"}}+listOf("cross-axis","cross-diagonal","clean","intentional-lines","colored-repeat","chromatic","off-bank","drift","tinted","dark","ambiguous-front","ambiguous-interference")
    val ids=listOf(512,1024).flatMap{w->profiles.map{"$it-$w"}}
    fun image(id:String,kind:String):ArgbImage {val b=ImageIO.read(requireNotNull(javaClass.getResourceAsStream("/m27/$id-$kind.png")));return ArgbImage(b.width,b.height,b.getRGB(0,0,b.width,b.height,null,0,b.width))}
    fun mask(id:String,kind:String)=image(id,kind).pixels.map{it and 255>0}.toBooleanArray()
}
class MoireQualityTest {
    @Test fun frozenArtifactDetailAndControlsAtBothResolutions(){
        val root=File("build/reports/m27-quality").apply{mkdirs()};val rows=mutableListOf("case,recipe,artifactMae,artifactReduction,foregroundMae,foregroundMax,detailContrastRatio,exact");val failures=mutableListOf<String>();val reductions=mutableMapOf<String,Double>()
        for(id in MoireCorpus.ids){val src=MoireCorpus.image(id,"source");val ref=MoireCorpus.image(id,"reference");val foreground=MoireCorpus.mask(id,"foreground");val artifact=MoireCorpus.mask(id,"artifact");val detail=MoireCorpus.mask(id,"detail");val before=src.pixels.copyOf()
            fun mae(p:IntArray):Double{var sum=0L;var n=0;for(i in p.indices)if(artifact[i]){n++;for(s in listOf(0,8,16))sum+=abs((p[i] ushr s and 255)-(ref.pixels[i] ushr s and 255))};return sum.toDouble()/maxOf(1,n*3)}
            fun contrast(p:IntArray):Double{var sum=0L;for(i in 0 until p.size-1)if(detail[i]&&i%src.width<src.width-1)for(s in listOf(0,8,16))sum+=abs((p[i] ushr s and 255)-(p[i+1] ushr s and 255));return sum.toDouble()}
            val baseline=mae(before);val baseContrast=contrast(before);val positive=id.startsWith("band-")||id.startsWith("cross-")
            for(f in DocumentFilter.entries.filter{it.moireAmount!=null}){val out=DocumentImageFilterEngine.apply(src,f);var delta=0L;var count=0;var max=0
                for(i in before.indices)if(foreground[i]){count++;for(s in listOf(0,8,16)){val d=abs((out.pixels[i] ushr s and 255)-(before[i] ushr s and 255));delta+=d;max=maxOf(max,d)}}
                val fm=delta.toDouble()/maxOf(1,count*3);val am=mae(out.pixels);val reduction=if(baseline>0)1-am/baseline else 0.0;val exact=before.contentEquals(out.pixels);val ratio=if(baseContrast>0)contrast(out.pixels)/baseContrast else 1.0
                fun gate(ok:Boolean,name:String){if(!ok)failures+="$id:$f:$name"}
                gate(fm<=.75,"foreground mean");gate(max<=4,"foreground max");gate(ratio>=.90,"detail contrast")
                if(positive){if(f.moireAmount==1f){gate(reduction>=.50,"artifact strong");reductions[id]=reduction};if(f.moireAmount==.65f)gate(reduction>=.30,"artifact balanced")}else gate(exact,"exact control")
                rows+="$id,$f,$am,$reduction,$fm,$max,$ratio,$exact"
                val b=java.awt.image.BufferedImage(out.width,out.height,java.awt.image.BufferedImage.TYPE_INT_ARGB);b.setRGB(0,0,out.width,out.height,out.pixels,0,out.width);ImageIO.write(b,"png",File(root,"$id-$f.png"));assertArrayEquals(before,src.pixels)
            }
        }
        for(profile in MoireCorpus.profiles.take(18))if(abs(requireNotNull(reductions["$profile-512"])-requireNotNull(reductions["$profile-1024"]))>.15)failures+="$profile:resolution difference"
        File(root,"candidate.csv").writeText(rows.joinToString("\n")+"\n");File(root,"failures.txt").writeText(failures.joinToString("\n"));assertTrue(failures.joinToString(","),failures.isEmpty())
    }
}
