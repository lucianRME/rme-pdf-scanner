package org.synapseworks.pageharbor.document.booklayout

import java.io.Reader
import kotlin.math.*

/** Original CC0-1.0 test scenes. Truth is scene geometry, never detector output. */
object BookSpreadCorpus {
    data class Case(val id:String,val kind:String,val top:Float,val bottom:Float,val leftTint:Int,val rightTint:Int,
        val shadow:Int,val illustration:Boolean,val faint:Boolean,val rawTurns:Int)
    data class ProtectedPixel(val x:Int,val y:Int,val left:Boolean)
    data class Scene(val width:Int,val height:Int,val pixels:IntArray,val protectedPixels:List<ProtectedPixel>)
    fun read(reader:Reader):List<Case> = reader.readLines().drop(1).filter{it.isNotBlank()}.map{line->
        val v=line.split(',');Case(v[0],v[1],v[2].toFloat(),v[3].toFloat(),v[4].toInt(),v[5].toInt(),v[6].toInt(),v[7]=="1",v[8]=="1",v[9].toInt())
    }
    fun rotate(scene:Scene,turns:Int):Scene {
        var s=scene
        repeat((turns%4+4)%4){
            val data=IntArray(s.pixels.size)
            for(y in 0 until s.height)for(x in 0 until s.width)data[x*s.height+(s.height-1-y)]=s.pixels[y*s.width+x]
            s=Scene(s.height,s.width,data,s.protectedPixels.map{ProtectedPixel(s.height-1-it.y,it.x,it.left)})
        }
        return s
    }
    fun render(c:Case):Scene {
        val w=when(c.kind){"SINGLE_PORTRAIT"->240;"PANORAMA"->640;"SMALL_INPUT"->64;else->480}
        val h=when(c.kind){"SINGLE_PORTRAIT"->360;"PANORAMA"->160;"SMALL_INPUT"->40;else->320}
        val pixels=IntArray(w*h){rgb(68,73,78)};val protectedPixels=mutableListOf<ProtectedPixel>()
        fun line(y:Int)=((c.top+(c.bottom-c.top)*y/(h-1))*w).roundToInt()
        fun rect(l:Int,t:Int,r:Int,b:Int,color:Int,left:Boolean?=null){
            for(y in t.coerceAtLeast(0)until b.coerceAtMost(h))for(x in l.coerceAtLeast(0)until r.coerceAtMost(w)){
                pixels[y*w+x]=color;if(left!=null)protectedPixels+=ProtectedPixel(x,y,left)
            }
        }
        val palette=intArrayOf(rgb(248,242,226),rgb(225,242,248),rgb(246,226,232),rgb(239,239,231))
        rect(34,22,w-34,h-22,palette[c.leftTint])
        for(y in 22 until h-22)for(x in line(y)until w-34)pixels[y*w+x]=palette[c.rightTint]
        val drawLeft=c.kind!in listOf("BLANK_SPREAD","BLANK_LEFT","WIDE_BLANK")
        val drawRight=c.kind!in listOf("BLANK_SPREAD","WIDE_BLANK")
        if(c.kind!in listOf("WIDE_PHOTO","PHOTO_POLE","PANORAMA","SMALL_INPUT")){
            for(y in 54 until h-40 step 17){
                val gutter=line(y)
                if(drawLeft)rect(55,y,(gutter-24-(y%3)*5).coerceAtLeast(56),y+2,rgb(48,48,48),if(c.kind=="SPREAD")true else null)
                if(drawRight)rect(gutter+24,y,w-55-(y%3)*4,y+2,rgb(55,55,55),if(c.kind=="SPREAD")false else null)
            }
        }
        if(c.illustration){rect(62,108,min(line(130)-30,148),158,rgb(65,112,184),true);rect(max(line(180)+30,w-142),178,w-62,222,rgb(183,91,72),false)}
        if(c.faint)for(y in listOf(84,152,238)){
            val x=line(y);rect(x-12,y,x-8,y+4,rgb(191,188,183),true);rect(x+8,y,x+12,y+4,rgb(194,190,185),false)
        }
        if(c.shadow>0)for(y in 22 until h-22)for(x in 34 until w-34){
            val distance=abs(x-line(y)).toFloat();val amount=(max(0f,1f-distance/110f)*if(c.shadow==1)36 else 60).toInt()
            val old=pixels[y*w+x];pixels[y*w+x]=rgb(((old shr 16)and 255)-amount,((old shr 8)and 255)-amount,(old and 255)-amount)
        }
        if(c.kind in listOf("SPREAD","BLANK_SPREAD","BLANK_LEFT","OCCLUDED_SEAM")){
            val top=if(c.kind=="OCCLUDED_SEAM")105 else 22;val bottom=if(c.kind=="OCCLUDED_SEAM")210 else h-22
            for(y in top until bottom)rect(line(y)-3,y,line(y)+3,y+1,rgb(48,45,43))
        }
        when(c.kind){
            "COLUMN_RULE"->rect(w/2-2,62,w/2+2,h-62,rgb(40,40,40))
            "THREE_COLUMNS"->{rect(34,22,w-34,h-22,rgb(243,240,232));for(y in 54 until h-40 step 17)for(x in listOf(55,190,320))rect(x,y,x+92,y+2,rgb(50,50,50))}
            "DOUBLE_SEAM"->{for(x in listOf((w*.4).toInt(),(w*.6).toInt()))rect(x-3,22,x+3,h-22,rgb(42,42,42))}
            "LARGE_SHADOW"->rect(w/2-40,22,w/2+40,h-22,rgb(70,70,70))
            "WIDE_PHOTO","PHOTO_POLE"->{rect(34,22,w-34,h/2,rgb(139,198,232));rect(34,h/2,w-34,h-22,rgb(65,112,73));if(c.kind=="PHOTO_POLE")rect(w/2-3,22,w/2+3,h-22,rgb(40,40,40))}
            "PANORAMA"->rect(0,0,w,h,rgb(120,173,192))
            else->Unit
        }
        return Scene(w,h,pixels,protectedPixels)
    }
    private fun rgb(r:Int,g:Int,b:Int):Int=(255 shl 24)or(r.coerceIn(0,255)shl 16)or(g.coerceIn(0,255)shl 8)or b.coerceIn(0,255)
}
