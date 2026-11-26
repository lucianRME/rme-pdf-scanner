package org.synapseworks.pageharbor.qr

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.ResultMetadataType
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.nio.ByteBuffer

internal const val QR_MAX_EDGE = 1280
internal const val QR_MAX_PIXELS = 1_300_000
internal sealed interface QrDecode {
    class Found(val payload: QrPayload) : QrDecode
    data object NoCode : QrDecode
    data object Invalid : QrDecode
}

/** No Android, model, network, persistence, logging or multi-format reader. */
internal class QrDecoder {
    fun decode(luma: ByteArray, width: Int, height: Int, check: () -> Unit = {}): QrDecode {
        if (width !in 1..QR_MAX_EDGE || height !in 1..QR_MAX_EDGE ||
            width.toLong() * height > QR_MAX_PIXELS || luma.size != width * height) return QrDecode.Invalid
        val reader = QRCodeReader()
        try {
            // Detector first. A bounded pure-symbol fallback handles sharp, isolated/dense QR
            // images whose finder geometry is rejected by the general detector.
            for (rotation in listOf(-1,0,90,180,270)) {
                check()
                val plane=if(rotation<=0)Triple(luma,width,height) else
                    QrLuma.plane(ByteBuffer.wrap(luma),width,height,width,1,0,0,width,height,rotation,check)!!
                val source=PlanarYUVLuminanceSource(plane.first,plane.second,plane.third,0,0,plane.second,plane.third,false)
                val hints=mutableMapOf<DecodeHintType,Any>(DecodeHintType.CHARACTER_SET to "UTF-8",DecodeHintType.TRY_HARDER to true)
                if(rotation>=0)hints[DecodeHintType.PURE_BARCODE]=true
                for(inverted in listOf(false,true)){
                    check()
                    try {
                        val result=reader.decode(BinaryBitmap(HybridBinarizer(if(inverted)source.invert()else source)),hints)
                        check()
                        if(result.resultMetadata?.containsKey(ResultMetadataType.STRUCTURED_APPEND_SEQUENCE)==true)return QrDecode.Invalid
                        return QrPayload.accept(result.text)?.let(QrDecode::Found) ?: QrDecode.Invalid
                    }catch(_:ReaderException){ /* Silent miss; no exception/payload diagnostics. */ }
                    finally{reader.reset()}
                }
            }
            return QrDecode.NoCode
        } finally { reader.reset() }
    }
}

internal object QrLuma {
    /** Reads only the Y plane, honoring crop/strides and the current buffer position. */
    fun plane(buffer: ByteBuffer, imageWidth: Int, imageHeight: Int, rowStride: Int, pixelStride: Int,
        left: Int, top: Int, width: Int, height: Int, rotation: Int, check: () -> Unit = {}): Triple<ByteArray, Int, Int>? {
        if (imageWidth !in 1..QR_MAX_EDGE || imageHeight !in 1..QR_MAX_EDGE ||
            imageWidth.toLong() * imageHeight > QR_MAX_PIXELS || width <= 0 || height <= 0 ||
            left < 0 || top < 0 || left.toLong()+width > imageWidth || top.toLong()+height > imageHeight ||
            rowStride <= 0 || pixelStride <= 0 || rotation !in setOf(0,90,180,270)) return null
        if ((imageWidth-1L)*pixelStride+1 > rowStride) return null
        val data = buffer.duplicate()
        val base = data.position().toLong()
        val last = base + (top+height-1L)*rowStride + (left+width-1L)*pixelStride
        if (last >= data.limit() || last > Int.MAX_VALUE) return null
        val outWidth = if (rotation % 180 == 0) width else height
        val outHeight = if (rotation % 180 == 0) height else width
        val bytes = ByteArray(width*height)
        for (y in 0 until height) {
            check()
            for (x in 0 until width) {
                val index = when (rotation) {
                    90 -> x*outWidth + height-1-y
                    180 -> (height-1-y)*outWidth + width-1-x
                    270 -> (width-1-x)*outWidth+y
                    else -> y*outWidth+x
                }
                bytes[index] = data.get((base+(top+y).toLong()*rowStride+(left+x).toLong()*pixelStride).toInt())
            }
        }
        return Triple(bytes,outWidth,outHeight)
    }
}
