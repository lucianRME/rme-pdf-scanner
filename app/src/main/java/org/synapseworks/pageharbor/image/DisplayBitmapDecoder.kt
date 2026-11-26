package org.synapseworks.pageharbor.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import java.io.InputStream

/** Region header dimensions avoid known stream-bounds failures on some Samsung codecs.
 * One open stream/decoder, software ARGB output, no source writes and no retained decoded cache.
 * A codec OOM gets one smaller retry; callers keep the source and may retry a later request.
 */
@Suppress("DEPRECATION")
internal fun decodeDisplayBitmap(
    openSource: () -> InputStream?,
    target: BitmapDisplaySize,
    bitmapBudgetBytes: Long = 10_000_000L,
    decodeRegion: (BitmapRegionDecoder, BitmapFactory.Options) -> Bitmap? = { decoder, options ->
        decoder.decodeRegion(Rect(0, 0, decoder.width, decoder.height), options)
    },
): Bitmap? = try {
    openSource()?.use { source ->
        val decoder = BitmapRegionDecoder.newInstance(source, false) ?: return@use null
        try {
            var sample = displayBitmapSampleSize(decoder.width, decoder.height, target, bitmapBudgetBytes)
                ?: return@use null
            repeat(2) { attempt ->
                try {
                    return@use decodeRegion(decoder, BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                        inScaled = false
                    })
                } catch (_: OutOfMemoryError) {
                    if (attempt == 1 || sample > Int.MAX_VALUE / 2) return@use null
                    sample *= 2
                }
            }
            null
        } finally { decoder.recycle() }
    }
} catch (_: Exception) {
    null
} catch (_: OutOfMemoryError) {
    null
}

/** Own a PDF preview allocation until rendering succeeds; failures never strand a pixel buffer. */
internal fun renderDisplayBitmap(width: Int, height: Int, draw: (Bitmap) -> Unit): Bitmap? {
    if (width <= 0 || height <= 0 || width.toLong() * height > 4_000_000L) return null
    var bitmap: Bitmap? = null
    return try {
        bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        draw(bitmap)
        bitmap
    } catch (_: Exception) {
        bitmap?.recycle()
        null
    } catch (_: OutOfMemoryError) {
        bitmap?.recycle()
        null
    }
}
