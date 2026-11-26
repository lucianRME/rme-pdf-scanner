package org.synapseworks.pageharbor.table

import android.graphics.*
import java.io.*
import kotlinx.coroutines.CancellationException
import org.synapseworks.pageharbor.businesscard.CardRecognition
import org.synapseworks.pageharbor.document.transform
import org.synapseworks.pageharbor.ocr.*

/** Called under CardRecognition.work by the ViewModel. Source pixels/bytes never escape this call. */
internal object TableRecognition {
    const val CROP_EDGE=1024
    const val CROP_PIXELS=262144
    const val CROP_BYTES=1024*1024
    fun recognize(open:()->InputStream?,rotation:Int?=null,check:()->Unit={},engine:OcrEngine=MlKitOcrEngine(recoverTableCells=false)):TableExtraction? {
        var result:TableExtraction?=null
        CardRecognition.recognizePage(open,rotation,check,engine){page,source,orientation,turn->
            val raw=TableParser.parse(page.text,page.layout,check)?:return@recognizePage
            result=raw
            if(raw.grid.rows.flatten().none(TableRefinement::eligible))return@recognizePage
            val layout=page.layout?:return@recognizePage
            val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
            BitmapFactory.decodeByteArray(source,0,source.size,bounds)
            val sample=OcrBitmapDecodePolicy.calculateInSampleSize(bounds.outWidth,bounds.outHeight)?:return@recognizePage
            check()
            var bitmap:Bitmap?=null;var upright:Bitmap?=null
            try {
                bitmap=BitmapFactory.decodeByteArray(source,0,source.size,BitmapFactory.Options().apply{inSampleSize=sample;inPreferredConfig=Bitmap.Config.ARGB_8888})?:return@recognizePage
                check()
                if(bitmap.width>2800||bitmap.height>2800||bitmap.width.toLong()*bitmap.height>7_000_000)return@recognizePage
                upright=transform(bitmap,orientation,turn);check()
                if(upright!==bitmap){bitmap.recycle();bitmap=null}
                val pixels=upright
                if(pixels.width!=layout.imageWidthPx||pixels.height!=layout.imageHeightPx)return@recognizePage
                result=TableRefinement.apply(raw,pixels.width,pixels.height,check){unit,box,target->
                    val h=unit.bounds!!.bottom-unit.bounds.top
                    val w=((box.right-box.left)*target/h).toInt();val height=((box.bottom-box.top)*target/h).toInt()
                    if(w !in 1..CROP_EDGE||height !in 1..CROP_EDGE||w.toLong()*height>CROP_PIXELS)return@apply null
                    check();val crop=Bitmap.createBitmap(w,height,Bitmap.Config.ARGB_8888)
                    try {
                        Canvas(crop).drawBitmap(pixels,Rect(box.left.toInt(),box.top.toInt(),box.right.toInt(),box.bottom.toInt()),Rect(0,0,w,height),Paint(Paint.FILTER_BITMAP_FLAG))
                        check()
                        val output=ByteArrayOutputStream()
                        val bounded=object:OutputStream(){override fun write(value:Int){if(output.size()>=CROP_BYTES)throw IOException("Crop limit");output.write(value)}
                            override fun write(b:ByteArray,offset:Int,count:Int){if(output.size().toLong()+count>CROP_BYTES)throw IOException("Crop limit");output.write(b,offset,count)}}
                        if(!crop.compress(Bitmap.CompressFormat.PNG,100,bounded))return@apply null
                        check();val bytes=output.toByteArray()
                        // Release our crop before the existing engine's bounded decode.
                        crop.recycle()
                        val response=engine.recognize(listOf(OcrPage(0,org.synapseworks.pageharbor.document.session.DocumentImageMetadata(bytes.size.toLong(),w,height)){ByteArrayInputStream(bytes)})).pages.singleOrNull()
                        check()
                        response?.takeIf{it.error==null}?.layout?.lines?.singleOrNull()?.text
                    }finally{if(!crop.isRecycled)crop.recycle()}
                }
            }catch(e:CancellationException){throw e}catch(_:Exception){result=raw}catch(_:OutOfMemoryError){result=raw}
            finally{if(upright!==bitmap)upright?.let{if(!it.isRecycled)it.recycle()};bitmap?.let{if(!it.isRecycled)it.recycle()}}
        }
        check();return result
    }
}
