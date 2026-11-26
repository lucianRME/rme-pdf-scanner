package org.synapseworks.pageharbor.document.curvature

import kotlin.math.*

internal data class CurvatureImage(val width:Int,val height:Int,val pixels:IntArray) {
    init{require(width>1&&height>1&&width.toLong()*height==pixels.size.toLong())}
}
internal enum class CurvatureStatus { PROPOSED, FLAT, UNCERTAIN, UNSUPPORTED }
internal data class CurvatureEstimate(val status:CurvatureStatus,val field:CurvatureField?=null,
    val tracks:Int=0,val spacingVariation:Float=0f)

/** Vertical flow and an optional user-controlled horizontal stretch. Each mapping is
 * monotone and spans both source edges. The expanded vertical domain preserves source support.
 */
internal data class CurvatureField(
    val offsets:List<Float>,
    val scales:List<Float>,
    val horizontalStretch: Float = 0f,
) {
    init {
        require(offsets.size>=3&&offsets.size==scales.size&&offsets.all{it.isFinite()&&abs(it)<=.16f}&&scales.all{it.isFinite()&&it in (2f/3f)..(1f/.65f)})
        require(horizontalStretch in -.40f.. .40f)
    }

    fun sourceX(x: Float): Float = (x + horizontalStretch * x * (1f - x)).coerceIn(0f, 1f)
    fun offset(x:Float)=interpolate(offsets,x)
    fun scale(x:Float)=interpolate(scales,x)
    fun correctedY(x:Float,sourceY:Float)=(sourceY-offset(x))/scale(x)
    val minimumY:Float get()=offsets.indices.minOf{-offsets[it]/scales[it]}
    val maximumY:Float get()=offsets.indices.maxOf{(1f-offsets[it])/scales[it]}
    fun dimensions(width:Int,height:Int):Pair<Int,Int> {
        val ratio=maximumY-minimumY;require(ratio.isFinite()&&ratio<=1.5f&&ratio>=.65f)
        val out=ceil(ratio*(height-1)).toInt()+1
        require(width in 2..6000&&out in 2..6000&&width.toLong()*out<=18_000_000L)
        return width to out
    }
    fun withAnchor(index:Int,value:Float):CurvatureField {
        require(index in 0..4&&value.isFinite()&&value in -.14f.. .14f)
        return CurvatureField(List(5){if(it==index)value else offset(it/4f)},List(5){scale(it/4f)},horizontalStretch)
    }
    fun isIdentity()=abs(horizontalStretch)<.00001f&&offsets.all{abs(it)<.00001f}&&scales.all{abs(it-1)<.00001f}
    companion object {
        fun identity()=CurvatureField(List(5){0f},List(5){1f})
        fun manual(offsets:List<Float>,taper:Float):CurvatureField {
            require(offsets.size==5&&taper.isFinite()&&taper in -.16f.. .16f)
            return CurvatureField(offsets,List(5){1f+taper*(it/4f-.5f)})
        }
        private fun interpolate(values:List<Float>,x:Float):Float {
            require(x.isFinite());val p=x.coerceIn(0f,1f)*(values.size-1);val i=floor(p).toInt().coerceAtMost(values.lastIndex-1)
            return values[i]+(values[i+1]-values[i])*(p-i)
        }
    }
}

internal object CurvatureSampling {
    fun white(pixel:Int):Int {val a=pixel ushr 24;if(a==255)return pixel
        fun c(s:Int)=(((pixel ushr s)and 255)*a+255*(255-a)+127)/255
        return (255 shl 24)or(c(16)shl 16)or(c(8)shl 8)or c(0)}
    fun bilinear(a:Int,b:Int,c:Int,d:Int,x:Float,y:Float):Int {
        require(x.isFinite()&&y.isFinite()&&x in 0f..1f&&y in 0f..1f)
        val aa=white(a);val bb=white(b);val cc=white(c);val dd=white(d);var out=0xff000000.toInt()
        var s=16
        while(s>=0){
            val top=((aa ushr s)and 255)*(1-x)+((bb ushr s)and 255)*x
            val bottom=((cc ushr s)and 255)*(1-x)+((dd ushr s)and 255)*x
            out=out or((top*(1-y)+bottom*y).roundToInt().coerceIn(0,255)shl s);s-=8
        };return out
    }
}
