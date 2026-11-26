package org.synapseworks.pageharbor.image

internal data class BitmapDisplaySize(val width: Int, val height: Int)

/** Quantize measured pixels to avoid restarting decodes for insignificant layout changes. */
internal fun bitmapDisplaySize(width: Int, height: Int, inspectionScale: Int = 1): BitmapDisplaySize? {
    if (width <= 0 || height <= 0 || inspectionScale <= 0) return null
    fun edge(value: Int) = (((value.toLong() * inspectionScale + 63L) / 64L) * 64L).coerceAtMost(1600L).toInt()
    return BitmapDisplaySize(edge(width), edge(height))
}

/** Display-only sampling. Never use this policy for document assets, OCR or export. */
internal fun displayBitmapSampleSize(
    width: Int,
    height: Int,
    target: BitmapDisplaySize,
    bitmapBudgetBytes: Long = 10_000_000L,
): Int? {
    if (width <= 0 || height <= 0 || target.width <= 0 || target.height <= 0 || bitmapBudgetBytes < 4L) return null
    val maxWidth = minOf(target.width, 1600)
    val maxHeight = minOf(target.height, 1600)
    val maxPixels = minOf(bitmapBudgetBytes / 4L, 2_500_000L)
    fun ceil(value: Int, sample: Int) = (value.toLong() + sample - 1L) / sample
    var sample = 1
    while (ceil(width,sample) > maxWidth || ceil(height,sample) > maxHeight ||
        ceil(width,sample) * ceil(height,sample) > maxPixels) {
        if (sample > Int.MAX_VALUE / 2) return null
        sample *= 2
    }
    return sample
}
