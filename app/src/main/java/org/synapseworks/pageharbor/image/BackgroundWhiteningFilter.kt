package org.synapseworks.pageharbor.image

import kotlin.math.*

/** Background-only whitening v1. Ambiguous cells/content, transparency and clipped pixels pass through.
 * Two shared estimation passes then a five-row rolling neighbourhood; no image-size mask/copy.
 * The caller owns output and must discard partial output on cancellation/failure.
 */
internal object BackgroundWhiteningFilter {
    fun process(width:Int,height:Int,recipe:BackgroundWhiteningRecipe,
        readRow:(Int,IntArray)->Unit,writeRow:(Int,IntArray)->Unit,ensureActive:()->Unit={}) {
        val model=PaperInkEstimate.read(width,height,readRow,ensureActive)
        val (cols,rows,black,white,valid,background,output)=model
        val cells=cols*rows
        val usable=valid.indices.filter{valid[it]}
        val confident=usable.size>=max(2,(cells*0.45f).toInt())
        fun neutral(cell:Int)=max(background[cell*3],max(background[cell*3+1],background[cell*3+2]))-
            min(background[cell*3],min(background[cell*3+1],background[cell*3+2]))<=8f
        // Dominant reference chroma protects large coloured marks in otherwise neutral documents.
        fun median(values:List<Float>)=values.sorted()[values.size/2]
        val referenceR=if(usable.isEmpty())0f else median(usable.map{(background[it*3]-background[it*3+1])/max(1f,white[it])})
        val referenceB=if(usable.isEmpty())0f else median(usable.map{(background[it*3+2]-background[it*3+1])/max(1f,white[it])})
        for(cell in usable) {
            ensureActive()
            val r=(background[cell*3]-background[cell*3+1])/max(1f,white[cell])
            val b=(background[cell*3+2]-background[cell*3+1])/max(1f,white[cell])
            if(abs(r-referenceR)>0.035f||abs(b-referenceB)>0.035f||
                (recipe.paper==WhiteningPaper.NEUTRAL&&!neutral(cell)))valid[cell]=false
        }
        val neighbours=Array(5){IntArray(width)}
        // A ring keeps at most five source rows. Rows beyond either edge clamp to its source row.
        var loadedThrough=-1
        for(y in 0 until height) {
            ensureActive()
            while(loadedThrough<min(height-1,y+2)) {
                loadedThrough++;readRow(loadedThrough,neighbours[loadedThrough%5])
            }
            neighbours[y%5].copyInto(output)
            if(confident)for(x in 0 until width) {
                if(x%256==0)ensureActive()
                val p=output[x]
                if(p ushr 24!=255)continue
                val r=p ushr 16 and 255;val g=p ushr 8 and 255;val b=p and 255
                if(r>=254||g>=254||b>=254)continue
                val cell=(y.toLong()*rows/height).toInt()*cols+(x.toLong()*cols/width).toInt()
                if(!valid[cell])continue
                val light=DocumentImageFilterEngine.pixelLuminance(p).toFloat()
                if(light<white[cell]-18||light>white[cell]+18)continue
                val chromaR=(r-g)/max(1f,light);val chromaB=(b-g)/max(1f,light)
                if(abs(chromaR-referenceR)>0.025f||abs(chromaB-referenceB)>0.025f)continue
                // Preserve local dark strokes, including faint/pencil/one-pixel detail. A local
                // paper match is required; flat background near hard boundaries may remain unchanged.
                var brightest=light
                for(sy in max(0,y-2)..min(height-1,y+2)) {
                    val near=neighbours[sy%5]
                    for(sx in max(0,x-2)..min(width-1,x+2)) {
                        val q=near[sx]
                        if(q ushr 24==255)brightest=max(brightest,DocumentImageFilterEngine.pixelLuminance(q).toFloat())
                    }
                }
                if(brightest-light>2f)continue
                fun whiten(channel:Int)=(channel+(255-channel)*recipe.amount).roundToInt().coerceIn(0,255)
                output[x]=(p and -0x1000000)or(whiten(r)shl 16)or(whiten(g)shl 8)or whiten(b)
            }
            writeRow(y,output)
        }
        // A caller may cancel while accepting the last row. Never publish its completed result.
        ensureActive()
    }
}
