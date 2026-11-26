package org.synapseworks.pageharbor.image

import kotlin.math.*

/** Exact M08 v1 first/second-pass statistics shared by versioned spatial recipes.
 * Owns bounded <=16x16x256 histogram bins, cell arrays and one row; no retained callbacks.
 */
internal data class PaperInkEstimate(val cols:Int,val rows:Int,val black:FloatArray,val white:FloatArray,
    val valid:BooleanArray,val background:FloatArray,val row:IntArray) {
    companion object {
        fun read(width:Int,height:Int,readRow:(Int,IntArray)->Unit,ensureActive:()->Unit):PaperInkEstimate {
        require(ArgbImage.isSupportedDimensions(width,height));ensureActive()
        val cols=(width/32).coerceIn(1,16);val rows=(height/32).coerceIn(1,16);val cells=cols*rows
        val histogram=IntArray(cells*256);val counts=IntArray(cells);val row=IntArray(width)
        fun usable(p:Int)=p ushr 24==255&&(p ushr 16 and 255)<254&&(p ushr 8 and 255)<254&&(p and 255)<254
        for(y in 0 until height) {
            ensureActive();readRow(y,row)
            for(x in 0 until width)if(usable(row[x])) {
                val cell=(y.toLong()*rows/height).toInt()*cols+(x.toLong()*cols/width).toInt()
                histogram[cell*256+DocumentImageFilterEngine.pixelLuminance(row[x])]++;counts[cell]++
            }
        }
        val black=FloatArray(cells);val white=FloatArray(cells);val valid=BooleanArray(cells)
        for(cell in 0 until cells) {
            ensureActive();if(counts[cell]<8)continue
            fun percentile(q:Float):Int {
                val target=(counts[cell]*q).toInt().coerceAtMost(counts[cell]-1);var sum=0
                for(i in 0..255){sum+=histogram[cell*256+i];if(sum>target)return i};return 255
            }
            black[cell]=percentile(0.01f).toFloat();white[cell]=percentile(0.90f).toFloat()
            val span=white[cell]-black[cell];if(span<20)continue
            // Use the median observed dark-ink cluster, rather than its extreme tail.
            // This reduces residual veiling in cells containing a reflection gradient.
            val darkLimit=(black[cell]+span*0.30f).toInt().coerceIn(0,255)
            var darkCount=0
            for(v in 0..darkLimit)darkCount+=histogram[cell*256+v]
            var cumulativeDark=0
            for(v in 0..darkLimit){cumulativeDark+=histogram[cell*256+v];if(cumulativeDark>darkCount/2){black[cell]=v.toFloat();break}}
            val band=(span*0.20f).coerceIn(6f,35f)
            var paper=0;var valley=0
            for(v in 0..255){
                val n=histogram[cell*256+v]
                if(v>=white[cell]-band)paper+=n
                if(v>black[cell]+span*0.25f&&v<white[cell]-span*0.25f)valley+=n
            }
            valid[cell]=paper>=counts[cell]*0.55f&&valley<=counts[cell]*0.12f
        }
        val sums=LongArray(cells*3);val paperCounts=IntArray(cells)
        for(y in 0 until height) {
            ensureActive();readRow(y,row)
            for(x in 0 until width) {
                val p=row[x];if(!usable(p))continue
                val cell=(y.toLong()*rows/height).toInt()*cols+(x.toLong()*cols/width).toInt()
                if(!valid[cell])continue
                val band=((white[cell]-black[cell])*0.20f).coerceIn(6f,35f)
                if(DocumentImageFilterEngine.pixelLuminance(p)<white[cell]-band)continue
                sums[cell*3]+=(p ushr 16 and 255).toLong();sums[cell*3+1]+=(p ushr 8 and 255).toLong();sums[cell*3+2]+=(p and 255).toLong();paperCounts[cell]++
            }
        }
        val background=FloatArray(cells*3)
        for(cell in 0 until cells) {
            if(!valid[cell]||paperCounts[cell]==0){valid[cell]=false;continue}
            for(c in 0..2)background[cell*3+c]=sums[cell*3+c].toFloat()/paperCounts[cell]
            white[cell]=(background[cell*3]*0.2126f+background[cell*3+1]*0.7152f+background[cell*3+2]*0.0722f)
        }
            return PaperInkEstimate(cols,rows,black,white,valid,background,row)
        }
    }
}
