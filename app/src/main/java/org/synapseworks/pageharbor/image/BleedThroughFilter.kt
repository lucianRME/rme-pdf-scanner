package org.synapseworks.pageharbor.image

import kotlin.math.*

/** Single-sided neutral-paper v1. Soft neutral tones only; sharp/colored neighborhoods pass through.
 * No front/back truth is inferred. Caller discards partial output on cancel/error.
 * Shared two-pass paper estimator +11 source rows/9 seed rows/one column sum; no full-page mask.
 */
internal object BleedThroughFilter {
    fun process(width:Int,height:Int,amount:Float,readRow:(Int,IntArray)->Unit,writeRow:(Int,IntArray)->Unit,ensureActive:()->Unit={}) {
        require(amount in 0f..1f);require(ArgbImage.isSupportedDimensions(width,height))
        val hist=Array(4){IntArray(256)};var observed=0L;var neutral=0L;var reads=0
        val model=PaperInkEstimate.read(width,height,{y,row->readRow(y,row)
            if(reads++<height)for(p in row)if(p ushr 24==255){val r=p ushr 16 and 255;val g=p ushr 8 and 255;val b=p and 255
                hist[0][r]++;hist[1][g]++;hist[2][b]++;hist[3][DocumentImageFilterEngine.pixelLuminance(p)]++;observed++
                if(maxOf(r,g,b)-minOf(r,g,b)<=8)neutral++
            }
        },ensureActive)
        fun quantile(h:IntArray):Int {val target=(observed*.95).toLong();var n=0L;for(i in 0..255){n+=h[i];if(n>target)return i};return 255}
        val paper=IntArray(3){quantile(hist[it])};val light=quantile(hist[3]);val dark=hist[3].take((light-85).coerceAtLeast(0)).sum().toLong();val bright=hist[3].drop((light-4).coerceAtLeast(0)).sum().toLong()
        val confident=width>=32&&height>=32&&observed>0&&light>=230&&paper.max()-paper.min()<=8&&neutral>=observed*.85&&bright>=observed*.55&&dark>=max(8L,observed/500)
        val source=Array(11){IntArray(width)};val seeds=Array(9){BooleanArray(width)};val columns=IntArray(width);val output=model.row
        var loaded=-1;var seeded=-1
        fun seed(row:Int,dst:BooleanArray){
            val current=source[row%11];for(x in 0 until width){if(x%256==0)ensureActive();val p=current[x];val r=p ushr 16 and 255;val g=p ushr 8 and 255;val b=p and 255
                var low=255;var high=0
                for(sy in max(0,row-1)..min(height-1,row+1))for(sx in max(0,x-1)..min(width-1,x+1)){val q=source[sy%11][sx];val l=DocumentImageFilterEngine.pixelLuminance(q);low=min(low,l);high=max(high,l)}
                dst[x]=p ushr 24!=255||maxOf(r,g,b)-minOf(r,g,b)>8||high-low>=12||DocumentImageFilterEngine.pixelLuminance(p)<light-75
            }
        }
        for(y in 0 until height){ensureActive()
            while(loaded<min(height-1,y+5)){loaded++;readRow(loaded,source[loaded%11])}
            val remove=y-5;if(remove>=0){val old=seeds[remove%9];for(x in 0 until width)if(old[x])columns[x]--}
            while(seeded<min(height-1,y+4)){seeded++;val row=seeds[seeded%9];seed(seeded,row);for(x in 0 until width)if(row[x])columns[x]++}
            source[y%11].copyInto(output)
            if(confident){var guards=0;for(x in 0..min(width-1,4))guards+=columns[x]
                for(x in 0 until width){if(x%256==0)ensureActive()
                    val p=output[x];val lum=DocumentImageFilterEngine.pixelLuminance(p);val deficit=light-lum
                    val cell=(y.toLong()*model.rows/height).toInt()*model.cols+(x.toLong()*model.cols/width).toInt()
                    val localConfident=!model.valid[cell]||abs(model.white[cell]-light)<=14
                    if(guards==0&&localConfident&&p ushr 24==255&&deficit in 4..75){
                        fun channel(shift:Int,c:Int):Int {val value=p ushr shift and 255;return (value+max(0,paper[c]-value)*amount).roundToInt().coerceIn(value,255)}
                        output[x]=(p and -0x1000000)or(channel(16,0)shl 16)or(channel(8,1)shl 8)or channel(0,2)
                    }
                    val left=x-4;if(left>=0)guards-=columns[left];val right=x+5;if(right<width)guards+=columns[right]
                }
            }
            writeRow(y,output)
        }
        ensureActive()
    }
}

internal val DocumentFilter.bleedThroughAmount:Float?
    get()=when(this){DocumentFilter.BLEED_THROUGH_LOW_V1->.35f;DocumentFilter.BLEED_THROUGH_BALANCED_V1->.65f;DocumentFilter.BLEED_THROUGH_STRONG_V1->1f;else->null}
