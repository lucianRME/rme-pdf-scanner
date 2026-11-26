package org.synapseworks.pageharbor.businesscard

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.synapseworks.pageharbor.document.readOrientation
import org.synapseworks.pageharbor.document.transform
import org.synapseworks.pageharbor.document.session.DocumentPageRotation
import androidx.camera.core.ImageProxy
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import org.synapseworks.pageharbor.document.session.DocumentImageMetadata
import org.synapseworks.pageharbor.ocr.*

internal object CardRecognition {
    val work = kotlinx.coroutines.sync.Mutex()
    const val MAX_BYTES = 16 * 1024 * 1024
    /** Snapshot bounds the provider once; the existing OCR engine may safely reopen this memory. */
    fun recognize(open: () -> InputStream?, rotation: Int? = null, check: () -> Unit = {}, engine: OcrEngine = MlKitOcrEngine()): ContactSuggestions? {
        val result = recognizePage(open, rotation, check, engine) ?: return null
        return ContactSuggestionsParser.parse(result.text, result.layout, check)
    }

    /** Shared bounded acquisition/EXIF/OCR path; callers interpret the engine-neutral page. */
    fun recognizePage(open: () -> InputStream?, rotation: Int? = null, check: () -> Unit = {}, engine: OcrEngine = MlKitOcrEngine(),
        withSource: ((OcrPageResult, ByteArray, Int, DocumentPageRotation) -> Unit)? = null): OcrPageResult? {
        if(rotation != null && rotation !in setOf(0, 90, 180, 270)) return null
        check()
        val bytes = open()?.use { stream ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16384)
            while(true) {
                check(); val count = stream.read(buffer); if(count < 0) break
                if(count == 0 || out.size().toLong() + count > MAX_BYTES) return null
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        } ?: return null
        check()
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        val width = options.outWidth; val height = options.outHeight
        if(width !in 1..16384 || height !in 1..16384 || width.toLong() * height > 64_000_000) return null
        check()
        val sample = OcrBitmapDecodePolicy.calculateInSampleSize(width, height) ?: return null
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: return null
        var upright: Bitmap? = null
        try {
            check()
            if(bitmap.width > 2800 || bitmap.height > 2800 || bitmap.width.toLong() * bitmap.height > 7_000_000) return null
            // CameraX provides buffer rotation explicitly; imported photos use all eight EXIF cases.
            val orientation = if(rotation == null) readOrientation { ByteArrayInputStream(bytes) } else 1
            val turn = when(rotation) { 90 -> DocumentPageRotation.DEGREES_90; 180 -> DocumentPageRotation.DEGREES_180;
                    270 -> DocumentPageRotation.DEGREES_270; else -> DocumentPageRotation.DEGREES_0 }
            upright = transform(bitmap, orientation, turn)
            check()
            val output = ByteArrayOutputStream()
            val bounded = object : java.io.OutputStream() {
                override fun write(value: Int) { if(output.size() >= MAX_BYTES) throw java.io.IOException("Card image limit"); output.write(value) }
                override fun write(buffer: ByteArray, offset: Int, count: Int) {
                    if(output.size().toLong() + count > MAX_BYTES) throw java.io.IOException("Card image limit")
                    output.write(buffer, offset, count)
                }
            }
            if(!upright.compress(Bitmap.CompressFormat.JPEG, 95, bounded)) return null
            check()
            val normalized = output.toByteArray()
            val page = OcrPage(0, DocumentImageMetadata(normalized.size.toLong(), upright.width, upright.height)) { ByteArrayInputStream(normalized) }
            // Release image allocations before ML Kit's existing bounded decode.
            if(upright !== bitmap) upright.recycle()
            upright = null; bitmap.recycle()
            val result = engine.recognize(listOf(page)).pages.singleOrNull() ?: return null
            check()
            if(result.error != null) return null
            // Internal synchronous consumer may read this owned snapshot; it must not retain or mutate it.
            // Full-page image allocations have already been released; default card/receipt paths are unchanged.
            withSource?.invoke(result, bytes, orientation, turn)
            check()
            return result
        } finally {
            if(upright != null && upright !== bitmap && !upright.isRecycled) upright.recycle()
            if(!bitmap.isRecycled) bitmap.recycle()
        }
    }
}

/** CameraX JPEG stays owned until the worker's finally, including rejected/stale callbacks. */
internal interface CardImage : AutoCloseable {
    val rotation: Int
    fun open(): InputStream?
}
internal class CameraCardImage(private val image: ImageProxy) : CardImage {
    override val rotation get() = image.imageInfo.rotationDegrees
    override fun open(): InputStream? {
        if(image.format != android.graphics.ImageFormat.JPEG || image.width !in 1..2000 || image.height !in 1..2000 ||
            image.width.toLong() * image.height > 3_000_000) return null
        val buffer = image.planes.firstOrNull()?.buffer?.duplicate() ?: return null
        if(buffer.remaining() !in 1..CardRecognition.MAX_BYTES) return null
        val bytes = ByteArray(buffer.remaining()); buffer.get(bytes)
        return ByteArrayInputStream(bytes)
    }
    override fun close() = image.close()
}
