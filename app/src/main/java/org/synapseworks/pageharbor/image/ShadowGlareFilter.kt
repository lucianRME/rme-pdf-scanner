package org.synapseworks.pageharbor.image

import kotlin.math.*

internal enum class ShadowGlareIntensity(val amount: Float) { LOW(0.35f), BALANCED(0.65f), STRONG(1f) }

/** Original deterministic, non-generative paper/ink illumination compensation, algorithm v1.
 * Three row passes, <=16x16x256 histogram bins, one ARGB row; never retains source callbacks.
 * A stable paper/ink reference is required. Non-paper/ambiguous and clipped pixels are preserved.
 */
internal object ShadowGlareFilter {
    fun apply(source: ArgbImage, intensity: ShadowGlareIntensity, ensureActive: () -> Unit = {}): ArgbImage {
        ensureActive()
        val pixels=IntArray(source.pixels.size)
        process(source.width,source.height,intensity,
            {y,row->source.pixels.copyInto(row,0,y*source.width,(y+1)*source.width)},
            {y,row->row.copyInto(pixels,y*source.width)},ensureActive)
        return ArgbImage(source.width,source.height,pixels)
    }
    fun process(width:Int,height:Int,intensity:ShadowGlareIntensity,
        readRow:(Int,IntArray)->Unit,writeRow:(Int,IntArray)->Unit,ensureActive:()->Unit={}) {
        val (cols,rows,black,white,valid,background,row)=PaperInkEstimate.read(width,height,readRow,ensureActive)
        val cells=cols*rows
        val usableCells=valid.indices.filter{valid[it]}
        val maxSpan=usableCells.maxOfOrNull{white[it]-black[it]}?:0f
        val anchors=usableCells.filter{white[it]-black[it]>=maxSpan*0.95f}
        val target=FloatArray(3)
        fun median(values:List<Float>):Float=values.sorted()[values.size/2]
        val targetBlack=if(anchors.isEmpty())0f else median(anchors.map{black[it]})
        for(c in 0..2)if(anchors.isNotEmpty())target[c]=median(anchors.map{background[it*3+c]})
        val targetWhite=target[0]*0.2126f+target[1]*0.7152f+target[2]*0.0722f
        val hasReference=usableCells.size>=max(2,(cells*0.45f).toInt())&&anchors.isNotEmpty()&&
            (usableCells.maxOf{white[it]}-usableCells.minOf{white[it]}>8||usableCells.maxOf{black[it]}-usableCells.minOf{black[it]}>6)
        if(hasReference)for(cell in usableCells) {
            val chromaR=(background[cell*3]-background[cell*3+1])/max(1f,white[cell])
            val chromaB=(background[cell*3+2]-background[cell*3+1])/max(1f,white[cell])
            if(abs(chromaR-(target[0]-target[1])/max(1f,targetWhite))>0.12f||
                abs(chromaB-(target[2]-target[1])/max(1f,targetWhite))>0.16f)valid[cell]=false
        }
        for(y in 0 until height) {
            ensureActive();readRow(y,row)
            if(hasReference)for(x in 0 until width) {
                if(x%256==0)ensureActive()
                val p=row[x];if(p ushr 24==0||(p ushr 16 and 255)>=254||(p ushr 8 and 255)>=254||(p and 255)>=254)continue
                val gy=(y+0.5f)*rows/height-0.5f;val gx=(x+0.5f)*cols/width-0.5f
                val cy=gy.roundToInt().coerceIn(0,rows-1);val cx=gx.roundToInt().coerceIn(0,cols-1)
                val light=DocumentImageFilterEngine.pixelLuminance(p).toFloat()
                var weight=0f;var r=0f;var g=0f;var b=0f
                for(sy in max(0,cy-1)..min(rows-1,cy+1))for(sx in max(0,cx-1)..min(cols-1,cx+1)) {
                    val cell=sy*cols+sx;if(!valid[cell])continue
                    val distance=min(abs(light-white[cell]),abs(light-black[cell]))
                    val spatial=1f+(gx-sx)*(gx-sx)+(gy-sy)*(gy-sy)
                    val w=1f/(spatial*(1f+distance*distance/16f))
                    fun corrected(channel:Int,c:Int):Float {
                        if(abs(white[cell]-targetWhite)<4&&abs(black[cell]-targetBlack)<3)return channel.toFloat()
                        val gain=((target[c]-targetBlack)/max(12f,background[cell*3+c]-black[cell])).coerceIn(0.25f,4f)
                        return (channel-black[cell])*gain+targetBlack
                    }
                    r+=w*corrected(p ushr 16 and 255,0);g+=w*corrected(p ushr 8 and 255,1);b+=w*corrected(p and 255,2);weight+=w
                }
                if(weight>0) {
                    val totalWeight = weight
                    fun blend(value:Int,estimate:Float)=(value+(estimate/totalWeight-value)*intensity.amount).roundToInt().coerceIn(0,255)
                    row[x]=(p and -0x1000000)or(blend(p ushr 16 and 255,r)shl 16)or(blend(p ushr 8 and 255,g)shl 8)or blend(p and 255,b)
                }
            }
            writeRow(y,row)
        }
    }
}
