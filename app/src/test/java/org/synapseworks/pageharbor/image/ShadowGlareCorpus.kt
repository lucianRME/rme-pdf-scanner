package org.synapseworks.pageharbor.image

import java.io.File
import javax.imageio.ImageIO
import java.awt.image.BufferedImage
import kotlin.math.*

/** Fixed before tuning. Test-only known clean references and NASA negative-control photographs. */
internal object ShadowGlareCorpus {
    data class Case(val id: String, val source: ArgbImage, val reference: ArgbImage,
        val ink: BooleanArray, val clipped: BooleanArray)
    private val glyphs = listOf(
        listOf("11110","10001","10001","11110","10100","10010","10001"),
        listOf("10001","11011","10101","10101","10001","10001","10001"),
        listOf("11111","10000","10000","11110","10000","10000","11111"),
        listOf("01110","10001","10011","10101","11001","10001","01110"),
        listOf("00100","01100","00100","00100","00100","00100","01110"))
    fun cases(): List<Case> {
        val ids=listOf("uneven","soft-shadow","hard-shadow","recoverable-glare","clipped","small-text","coloured-shadow","clean-warm","clean-coloured")
        return ids.map(::document)+listOf(photo("crater"),photo("earthrise"))
    }
    fun document(id:String):Case {
        val w=384;val h=256;val mask=BooleanArray(w*h);val clipped=BooleanArray(w*h)
        val coloured=id in listOf("coloured-shadow","clean-coloured")
        val paper=if(coloured)intArrayOf(214,195,145)else intArrayOf(232,228,219)
        val dark=intArrayOf(30,34,40);val scale=if(id=="small-text")1 else 2
        val reference=IntArray(w*h){i ->
            val x=i%w;val y=i/w;val column=x/31;val line=y/29
            val localX=(x%31)-3;val localY=(y%29)-6
            val letter=glyphs[(column+line)%glyphs.size]
            val ink=localX>=0&&localY>=0&&localX/scale<5&&localY/scale<7&&letter[localY/scale][localX/scale]=='1'
            mask[i]=ink;val c=if(ink)dark else paper
            rgb(c[0],c[1],c[2])
        }
        val input=IntArray(w*h){i ->
            val x=i%w;val y=i/w;val distance=hypot((x-230).toDouble(),(y-128).toDouble())
            val gain=when(id){
                "uneven","small-text","coloured-shadow"->0.45+0.55*x/(w-1)
                "soft-shadow"->0.5+0.5*(hypot((x-170).toDouble(),(y-125).toDouble())/150).coerceAtMost(1.0)
                "hard-shadow"->if(x<178&&y>45)0.4 else 1.0
                else->1.0
            }
            val veil=if(id in listOf("recoverable-glare","clipped"))0.68*(1-distance/110).coerceAtLeast(0.0) else 0.0
            if(id=="clipped"&&x in 170..250&&y in 85..170){clipped[i]=true;rgb(255,255,255)}
            else {
                fun c(shift:Int)=(((reference[i] ushr shift and 255)*gain)*(1-veil)+255*veil).roundToInt().coerceIn(0,255)
                rgb(c(16),c(8),c(0))
            }
        }
        return Case(id,ArgbImage(w,h,input),ArgbImage(w,h,reference),mask,clipped)
    }
    private fun photo(id:String):Case {
        val image=ImageIO.read(requireNotNull(javaClass.getResourceAsStream("/m08/nasa-$id.jpg")))
        val pixels=image.getRGB(0,0,image.width,image.height,null,0,image.width)
        return Case("photo-$id",ArgbImage(image.width,image.height,pixels),ArgbImage(image.width,image.height,pixels.copyOf()),BooleanArray(pixels.size),BooleanArray(pixels.size))
    }
    data class Metrics(val mae:Double,val inkMae:Double,val backgroundMae:Double,val falseInk:Double,val contrast:Double,val clippedChanges:Int)
    fun metrics(c:Case,pixels:IntArray,glareRegionOnly:Boolean=false):Metrics {
        var sum=0.0;var inkError=0.0;var bgError=0.0;var inkLight=0.0;var bgLight=0.0;var ink=0;var bg=0;var falseInk=0;var changed=0;var measured=0
        pixels.indices.forEach { i ->
            if(glareRegionOnly && 0.68*(1-hypot((i%c.source.width-230).toDouble(),(i/c.source.width-128).toDouble())/110).coerceAtLeast(0.0)<0.45)return@forEach
            if(c.clipped[i]){if(pixels[i]!=c.source.pixels[i])changed++;return@forEach}
            val error=(abs((pixels[i] ushr 16 and 255)-(c.reference.pixels[i] ushr 16 and 255))+abs((pixels[i] ushr 8 and 255)-(c.reference.pixels[i] ushr 8 and 255))+abs((pixels[i]and 255)-(c.reference.pixels[i]and 255)))/3.0
            val light=((pixels[i] ushr 16 and 255)+(pixels[i] ushr 8 and 255)+(pixels[i]and 255))/3.0
            sum+=error;measured++
            if(c.ink[i]){ink++;inkError+=error;inkLight+=light}else{bg++;bgError+=error;bgLight+=light;if(light<128)falseInk++}
        }
        return Metrics(sum/max(1,measured),inkError/max(1,ink),bgError/max(1,bg),falseInk.toDouble()/max(1,bg),bgLight/max(1,bg)-inkLight/max(1,ink),changed)
    }
    fun rgb(r:Int,g:Int,b:Int)= -0x1000000 or(r shl 16)or(g shl 8)or b
    fun save(name:String,image:ArgbImage) {
        val root=File("build/reports/m08-quality").apply{mkdirs()}
        val output=BufferedImage(image.width,image.height,BufferedImage.TYPE_INT_ARGB)
        output.setRGB(0,0,image.width,image.height,image.pixels,0,image.width)
        ImageIO.write(output,"png",File(root,"$name.png"))
    }
}
