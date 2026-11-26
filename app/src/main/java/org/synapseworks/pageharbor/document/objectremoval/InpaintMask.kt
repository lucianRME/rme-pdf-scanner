package org.synapseworks.pageharbor.document.objectremoval

import kotlin.math.*
import org.synapseworks.pageharbor.document.annotation.AnnotationPoint

internal data class RemovalStroke(val points:List<AnnotationPoint>,val radius:Int,val erase:Boolean=false)
/** Immutable, bounded source-oriented mask. Copies at the boundary prevent late draft mutation. */
internal class InpaintMask(val width:Int,val height:Int,cells:BooleanArray) {
    private val data=cells.copyOf()
    init{require(width in 1..768&&height in 1..768&&data.size==width*height)}
    operator fun get(index:Int)=data[index]
    val size get()=data.size
    val hasSelection=data.any{it}
    fun copyCells()=data.copyOf()
    companion object {
        fun dimensions(width:Int,height:Int):Pair<Int,Int>{require(width>0&&height>0);val scale=min(1.0,768.0/max(width,height));return max(1,(width*scale).roundToInt()) to max(1,(height*scale).roundToInt())}
        fun paint(width:Int,height:Int,strokes:List<RemovalStroke>,base:InpaintMask?=null,active:()->Unit={}):InpaintMask {
            require(strokes.size<=128&&strokes.sumOf{it.points.size}<=16384)
            val (w,h)=dimensions(width,height);require(base==null||(base.width==w&&base.height==h));val cells=base?.copyCells()?:BooleanArray(w*h);var budget=8_000_000L
            for(stroke in strokes){require(stroke.radius in 2..32&&stroke.points.size in 1..2048)
                fun dab(x:Float,y:Float){active();val r=stroke.radius;budget-=(2*r+1L)*(2*r+1);require(budget>=0)
                    val cx=x*w-.5f;val cy=y*h-.5f
                    for(sy in max(0,floor(cy-r).toInt())..min(h-1,ceil(cy+r).toInt()))for(sx in max(0,floor(cx-r).toInt())..min(w-1,ceil(cx+r).toInt())){
                        if((sx-cx)*(sx-cx)+(sy-cy)*(sy-cy)<=r*r)cells[sy*w+sx]=!stroke.erase}}
                val first=stroke.points.first();dab(first.x,first.y)
                for((a,b)in stroke.points.zipWithNext()){val distance=hypot((b.x-a.x)*w,(b.y-a.y)*h);val steps=max(1,ceil(distance/max(1f,stroke.radius/2f)).toInt())
                    for(i in 1..steps){val t=i.toFloat()/steps;dab(a.x+(b.x-a.x)*t,a.y+(b.y-a.y)*t)}}
            };active();return InpaintMask(w,h,cells)
        }
    }
}
