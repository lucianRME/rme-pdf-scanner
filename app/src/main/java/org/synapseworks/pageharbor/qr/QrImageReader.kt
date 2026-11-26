package org.synapseworks.pageharbor.qr

import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** One bounded read snapshot. No cache file, grant persistence or document/library mutation. */
internal object QrImageReader {
    const val MAX_BYTES = 16*1024*1024
    const val MAX_DIMENSION = 16384
    const val MAX_SOURCE_PIXELS = 64_000_000L
    fun read(open: ()->InputStream?, check: ()->Unit = {}): QrDecode {
        check()
        val bytes=open()?.use { input ->
            val out=ByteArrayOutputStream()
            val buffer=ByteArray(16*1024)
            while(true){
                check();val n=input.read(buffer);if(n<0)break
                if(n==0)throw IllegalStateException("Image unavailable")
                if(out.size().toLong()+n>MAX_BYTES)return QrDecode.Invalid
                out.write(buffer,0,n)
            }
            out.toByteArray()
        } ?: return QrDecode.Invalid
        check()
        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        val w=bounds.outWidth;val h=bounds.outHeight
        if(w !in 1..MAX_DIMENSION || h !in 1..MAX_DIMENSION || w.toLong()*h>MAX_SOURCE_PIXELS)return QrDecode.Invalid
        var sample=1
        while((w+sample-1)/sample>QR_MAX_EDGE || (h+sample-1)/sample>QR_MAX_EDGE ||
            ((w+sample-1)/sample).toLong()*((h+sample-1)/sample)>QR_MAX_PIXELS)sample*=2
        check()
        val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply{inSampleSize=sample}) ?: return QrDecode.Invalid
        try {
            check()
            if(bitmap.width !in 1..QR_MAX_EDGE || bitmap.height !in 1..QR_MAX_EDGE || bitmap.width.toLong()*bitmap.height>QR_MAX_PIXELS)return QrDecode.Invalid
            val row=IntArray(bitmap.width);val luma=ByteArray(bitmap.width*bitmap.height)
            for(y in 0 until bitmap.height){
                check();bitmap.getPixels(row,0,bitmap.width,0,y,bitmap.width,1)
                for(x in row.indices){val c=row[x];val alpha=c ushr 24
                    val gray=(306*(c ushr 16 and 255)+601*(c ushr 8 and 255)+117*(c and 255)+512) ushr 10
                    luma[y*bitmap.width+x]=((gray*alpha+255*(255-alpha)+127)/255).toByte()
                }
            }
            return QrDecoder().decode(luma,bitmap.width,bitmap.height,check)
        } finally { bitmap.recycle() }
    }
}
