package org.synapseworks.pageharbor.image

import java.awt.image.BufferedImage
import java.io.File
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.math.*

/** Frozen before processor tuning. Masks are independent ground truth, never processor input. */
internal object BackgroundWhiteningCorpus {
    data class Case(val id:String,val source:ArgbImage,val protected:BooleanArray,val neutral:Boolean) {
        val reference=ArgbImage(source.width,source.height,IntArray(source.pixels.size){if(protected[it])source.pixels[it] else -1})
    }
    fun cases():List<Case> = listOf("gray","warm","yellow","blue","uneven","residual-shadow","hard-shadow","fine-detail","coloured-content","diagram","mixed-photo","white","alpha-clipped").map(::document) + listOf("crater","earthrise").map { id ->
        val b=ImageIO.read(requireNotNull(javaClass.getResourceAsStream("/m08/nasa-$id.jpg")))
        Case("photo-$id",ArgbImage(b.width,b.height,b.getRGB(0,0,b.width,b.height,null,0,b.width)),BooleanArray(b.width*b.height){true},false)
    }
    fun document(id:String):Case {
        val base=ShadowGlareCorpus.document(if(id=="fine-detail")"small-text" else "clean-warm")
        val w=base.source.width;val h=base.source.height;val mask=base.ink.copyOf()
        val paper=when(id){"warm"->intArrayOf(231,218,194);"yellow"->intArrayOf(219,205,146);"blue"->intArrayOf(180,211,228);"white"->intArrayOf(255,255,255);else->intArrayOf(202,202,202)}
        val pixels=IntArray(w*h){ i ->
            val x=i%w;val y=i/w
            val gain=when(id){"uneven"->0.55+0.45*x/(w-1);"residual-shadow"->0.65+0.35*(hypot((x-170).toDouble(),(y-125).toDouble())/150).coerceAtMost(1.0);"hard-shadow"->if(x<178&&y>45)0.65 else 1.0;else->1.0}
            var rgb=if(mask[i])intArrayOf(30,34,40)else paper
            if(id=="fine-detail" && x%31 in 20..23 && y%29 in 6..19){mask[i]=true;rgb=intArrayOf(188,188,188)} // faint text/pencil
            if(id=="fine-detail" && x==27){mask[i]=true;rgb=intArrayOf(174,174,174)} // one-pixel line
            if(id in listOf("coloured-content","diagram","mixed-photo")) {
                if(mask[i])rgb=if(x<w/2)intArrayOf(155,32,48)else intArrayOf(35,62,160)
                if(x in 80..130 && y in 100..135){mask[i]=true;rgb=intArrayOf(202,194,70)} // highlighter
                if(x in 245..280 && y in 50..85 && (x%8<2||y%8<2)){mask[i]=true;rgb=intArrayOf(126,45,112)} // stamp/signature/diagram lines
            }
            var pixel=ShadowGlareCorpus.rgb((rgb[0]*gain).roundToInt(),(rgb[1]*gain).roundToInt(),(rgb[2]*gain).roundToInt())
            if(id=="alpha-clipped"&&x in 120..200){mask[i]=true;pixel=if(y<h/2)(pixel and 0x00ffffff)or(128 shl 24)else -1}
            pixel
        }
        if(id=="mixed-photo") {
            val photo=ImageIO.read(requireNotNull(javaClass.getResourceAsStream("/m08/nasa-crater.jpg")))
            for(y in 128 until h)for(x in 192 until w){val i=y*w+x;mask[i]=true;pixels[i]=photo.getRGB((x-192)*photo.width/192,(y-128)*photo.height/128)}
        }
        return Case(id,ArgbImage(w,h,pixels),mask,id !in listOf("warm","yellow","blue"))
    }
    data class Metrics(val paperMae:Double,val nearWhite:Double,val protectedMae:Double,val protectedMax:Int,val alphaChanges:Int)
    fun metrics(c:Case,pixels:IntArray):Metrics {
        var paper=0;var protected=0;var error=0.0;var near=0;var contentError=0.0;var maxChange=0;var alpha=0
        for(i in pixels.indices) {
            val p=pixels[i];val source=c.source.pixels[i]
            if(p ushr 24!=source ushr 24)alpha++
            var delta=0;var white=true
            for(shift in listOf(0,8,16)) {
                val value=p ushr shift and 255;val original=source ushr shift and 255
                if(c.protected[i]){val change=abs(value-original);delta+=change;maxChange=max(maxChange,change)}else{error+=255-value;if(value<245)white=false}
            }
            if(c.protected[i]){protected++;contentError+=delta}else{paper++;if(white)near++}
        }
        return Metrics(error/max(1,paper*3),near.toDouble()/max(1,paper),contentError/max(1,protected*3),maxChange,alpha)
    }
    fun hash(pixels:IntArray):String {
        val digest=MessageDigest.getInstance("SHA-256")
        for(p in pixels)digest.update(byteArrayOf((p ushr 24).toByte(),(p ushr 16).toByte(),(p ushr 8).toByte(),p.toByte()))
        return digest.digest().joinToString(""){"%02x".format(it)}
    }
    fun save(name:String,image:ArgbImage) {
        val root=File("build/reports/m09-quality").apply{mkdirs()}
        val output=BufferedImage(image.width,image.height,BufferedImage.TYPE_INT_ARGB)
        output.setRGB(0,0,image.width,image.height,image.pixels,0,image.width)
        ImageIO.write(output,"png",File(root,"$name.png"))
    }
}
