package org.synapseworks.pageharbor.document

import androidx.exifinterface.media.ExifInterface
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream

/** Keep each TIFF byte order local to its own Exif block. Some parsers retain the
 * last APP1 byte order when interpreting attributes from an earlier APP1.
 * TIFF/tag parsing remains in the integrated AndroidX implementation. No source writes.
 * JPEG header <=1MiB/128 markers; one segment <=64KiB. Conflicts are refused. */
internal class UnsupportedImageMetadata: IOException()
internal fun readImageOrientation(open:()->InputStream?):Int {
    val original=open()?:return ExifInterface.ORIENTATION_NORMAL
    return original.use { raw ->
        val buffered=BufferedInputStream(raw);buffered.mark(2)
        val a=buffered.read();val b=buffered.read();buffered.reset()
        if(a!=255||b!=216) return@use runCatching { ExifInterface(buffered).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val input=DataInputStream(buffered);input.readUnsignedShort();var bytes=2;var orientation:Int?=null
        repeat(128) {
            if(input.readUnsignedByte()!=255)throw UnsupportedImageMetadata()
            var marker=input.readUnsignedByte();bytes+=2
            while(marker==255){if(++bytes>1048576)throw UnsupportedImageMetadata();marker=input.readUnsignedByte()}
            if(marker==218||marker==217)return@use orientation?:ExifInterface.ORIENTATION_NORMAL
            if(marker==0||marker==216)throw UnsupportedImageMetadata()
            if(marker==1||marker in 208..215)return@repeat
            val size=input.readUnsignedShort();bytes+=size
            if(size<2||bytes>1048576)throw UnsupportedImageMetadata()
            if(marker==225) {
                val segment=ByteArray(size-2);input.readFully(segment)
                if(segment.size>=6&&segment.copyOfRange(0,6).contentEquals(byteArrayOf(69,120,105,102,0,0))) {
                    val jpeg=byteArrayOf(-1,-40,-1,-31,(size shr 8).toByte(),size.toByte())+segment+byteArrayOf(-1,-39)
                    val value=ExifInterface(ByteArrayInputStream(jpeg)).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_UNDEFINED)
                    if(value!=ExifInterface.ORIENTATION_UNDEFINED){if(value !in 1..8||(orientation!=null&&orientation!=value))throw UnsupportedImageMetadata();orientation=value}
                }
            } else {
                var remaining=size-2
                val buffer=ByteArray(minOf(8192,remaining))
                while(remaining>0){val n=input.read(buffer,0,minOf(buffer.size,remaining));if(n<=0)throw UnsupportedImageMetadata();remaining-=n}
            }
        }
        throw UnsupportedImageMetadata()
    }
}
