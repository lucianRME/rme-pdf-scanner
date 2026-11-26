package org.synapseworks.pageharbor.document.booklayout

import kotlin.math.*

internal data class BookAnalysisImage(val width:Int,val height:Int,val pixels:IntArray) {
    init {require(width>0&&height>0&&width.toLong()*height==pixels.size.toLong()&&pixels.size<=600_000)}
    fun rotated(turns:Int):BookAnalysisImage {
        var image=this
        repeat((turns%4+4)%4){
            val out=IntArray(image.pixels.size)
            for(y in 0 until image.height)for(x in 0 until image.width)out[x*image.height+image.height-1-y]=image.pixels[y*image.width+x]
            image=BookAnalysisImage(image.height,image.width,out)
        }
        return image
    }
}
internal data class BookSplitLine(val top:Float,val bottom:Float) {
    init {BookLayoutSettings(splitFraction=top);BookLayoutSettings(splitFraction=bottom)}
    fun cut(width:Int,height:Int,row:Int):Int = floor((top+(bottom-top)*(row+.5f)/height)*width).toInt().coerceIn(1,width-1)
    fun halves(width:Int,height:Int,order:BookReadingOrder):List<BookHalf> {
        // Reuse the reviewed prototype's displayed split/order geometry; integer row cuts partition
        // every source pixel exactly once, including a sloping seam and fractional pixel boundaries.
        val source=BookSourcePage(0f,0f,width.toFloat(),height.toFloat())
        val ordered=BookLayoutGeometry.plan(listOf(source),BookLayoutSettings(splitFraction=top,readingOrder=order))
        val first=cut(width,height,0);val last=cut(width,height,height-1);val maximum=max(first,last);val minimum=min(first,last)
        return ordered.map{page->if(page.placements.single().transform.e==0f)BookHalf(true,maximum,0)else BookHalf(false,width-minimum,minimum)}
    }
}
internal data class BookHalf(val left:Boolean,val width:Int,val sourceOffset:Int)
internal enum class BookDetectionStatus { CONFIDENT, UNCERTAIN, UNSUPPORTED }
internal enum class BookDetectionReason { GEOMETRY, NO_CONTINUOUS_SEAM, COMPETING_SEAMS, PAGE_EVIDENCE }
internal data class BookDetection(val status:BookDetectionStatus,val line:BookSplitLine?=null,
    val reason:BookDetectionReason?=null,val evidenceStrength:Float=0f)

/** Original bounded pixel evidence heuristic. Strength is not a calibrated probability.
 * A narrow continuous bilateral valley must reach both page edges, have paper/ink on both sides
 * and be distinct from another seam. No classifier/model/network, midpoint fallback or shutter.
 */
internal object BookSpreadDetector {
    fun detect(image:BookAnalysisImage,ensureActive:()->Unit={}):BookDetection {
        val w=image.width;val h=image.height
        if(w<128||h<96||w>768||h>768||w.toFloat()/h !in 1.05f..2.8f)return BookDetection(BookDetectionStatus.UNSUPPORTED,reason=BookDetectionReason.GEOMETRY)
        val lum=IntArray(image.pixels.size){i->
            val p=image.pixels[i];val a=p ushr 24
            val gray=(77*((p ushr 16)and 255)+150*((p ushr 8)and 255)+29*(p and 255))ushr 8
            (gray*a+255*(255-a)+127)/255
        }
        fun at(x:Int,y:Int)=lum[y.coerceIn(0,h-1)*w+x.coerceIn(0,w-1)]
        val x0=(w*.08f).toInt();val x1=(w*.92f).toInt()
        val paperRows=(0 until h).filter{y->ensureActive();(x0 until x1 step 2).count{at(it,y)>160}>((x1-x0)/2)*.55}
        if(paperRows.isEmpty()||paperRows.last()-paperRows.first()<h*.7)return BookDetection(BookDetectionStatus.UNCERTAIN,reason=BookDetectionReason.PAGE_EVIDENCE)
        val top=paperRows.first();val bottom=paperRows.last();val ys=IntArray(49){i->top+2+((bottom-top-4)*i/48)}
        data class Candidate(val center:Float,val delta:Float,val score:Float)
        val candidates=mutableListOf<Candidate>();val shoulder=max(6,(w*.02f).roundToInt());val maxDelta=ceil(tan(Math.toRadians(3.0))*h).toInt()
        for(center in (w*.2f).roundToInt()..(w*.8f).roundToInt() step 2){
            ensureActive()
            for(delta in -maxDelta..maxDelta step 2){
                var sum=0f;var strong=0;var edges=true
                for((i,y)in ys.withIndex()){
                    val x=(center+delta*(y.toFloat()/(h-1)-.5f)).roundToInt()
                    val middle=(at(x-1,y)+at(x,y)+at(x+1,y))/3f
                    val left=(at(x-shoulder-1,y)+at(x-shoulder,y)+at(x-shoulder+1,y))/3f
                    val right=(at(x+shoulder-1,y)+at(x+shoulder,y)+at(x+shoulder+1,y))/3f
                    val contrast=min(left,right)-middle
                    sum+=max(0f,contrast);if(contrast>=35)strong++
                    if((i==0||i==ys.lastIndex)&&contrast<25)edges=false
                }
                val continuity=strong.toFloat()/ys.size;val score=sum/ys.size*continuity
                if(edges&&continuity>=.88f&&score>=45f)candidates+=Candidate(center.toFloat(),delta.toFloat(),score)
            }
        }
        val best=candidates.maxByOrNull{it.score}?:return BookDetection(BookDetectionStatus.UNCERTAIN,reason=BookDetectionReason.NO_CONTINUOUS_SEAM)
        if(candidates.any{abs(it.center-best.center)>w*.07f&&it.score>=best.score*.85f})return BookDetection(BookDetectionStatus.UNCERTAIN,reason=BookDetectionReason.COMPETING_SEAMS)
        val line=BookSplitLine((best.center-best.delta/2)/w,(best.center+best.delta/2)/w)
        if(line.top !in .2f.. .8f||line.bottom !in .2f.. .8f)return BookDetection(BookDetectionStatus.UNCERTAIN,reason=BookDetectionReason.GEOMETRY)
        val ink=IntArray(2);val count=IntArray(2)
        for(y in top+10 until bottom-10 step 2){
            ensureActive();val cut=line.cut(w,h,y)
            for(x in x0 until x1 step 2){
                if(abs(x-cut)<shoulder*2)continue
                val side=if(x<cut)0 else 1;count[side]++;if(at(x,y)<150)ink[side]++
            }
        }
        if((0..1).any{count[it]==0||ink[it].toFloat()/count[it] !in .008f.. .38f})return BookDetection(BookDetectionStatus.UNCERTAIN,reason=BookDetectionReason.PAGE_EVIDENCE)
        return BookDetection(BookDetectionStatus.CONFIDENT,line,evidenceStrength=best.score/255f)
    }
}
