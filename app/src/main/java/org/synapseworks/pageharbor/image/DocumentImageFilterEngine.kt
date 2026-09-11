package org.synapseworks.pageharbor.image

import kotlin.math.roundToInt

/**
 * A synchronous, Android-independent ARGB filter engine. The caller owns [ArgbImage.pixels].
 * [DocumentFilter.ORIGINAL] returns the same instance; every other filter creates a new array and
 * never mutates the source image. Callers choose the appropriate background dispatcher later.
 */
object DocumentImageFilterEngine {
    private const val RedLuminanceWeight = 0.2126f
    private const val GreenLuminanceWeight = 0.7152f
    private const val BlueLuminanceWeight = 0.0722f
    private const val HighContrastFactor = 1.55f
    private const val AutoBlackPointPercentile = 0.04f
    private const val AutoWhitePointPercentile = 0.96f

    fun apply(source: ArgbImage, filter: DocumentFilter): ArgbImage {
        if (filter == DocumentFilter.ORIGINAL) return source
        val output = source.pixels.copyOf()
        val histogram = if (requiresHistogram(filter)) {
            IntArray(256).also { accumulateLuminanceHistogram(source.pixels, it) }
        } else {
            null
        }
        applyRowFilterInPlace(
            pixels = output,
            plan = createRowFilterPlan(filter, histogram, source.pixels.size),
        )
        return ArgbImage(source.width, source.height, output)
    }

    internal fun requiresHistogram(filter: DocumentFilter): Boolean =
        filter == DocumentFilter.AUTO_ENHANCE || filter == DocumentFilter.BLACK_AND_WHITE

    internal fun accumulateLuminanceHistogram(pixels: IntArray, histogram: IntArray) {
        require(histogram.size == 256)
        pixels.forEach { pixel ->
            histogram[luminance(red(pixel), green(pixel), blue(pixel))]++
        }
    }

    internal fun createRowFilterPlan(
        filter: DocumentFilter,
        histogram: IntArray?,
        totalPixelCount: Int,
    ): DocumentRowFilterPlan {
        if (!requiresHistogram(filter)) return DocumentRowFilterPlan(filter)
        require(histogram?.size == 256)
        require(totalPixelCount > 0)
        return when (filter) {
            DocumentFilter.AUTO_ENHANCE -> {
                val blackPoint = percentile(histogram, totalPixelCount, AutoBlackPointPercentile)
                val whitePoint = percentile(histogram, totalPixelCount, AutoWhitePointPercentile)
                DocumentRowFilterPlan(
                    filter = filter,
                    blackPoint = blackPoint,
                    whitePoint = whitePoint,
                    useHighContrastFallback = whitePoint <= blackPoint,
                )
            }

            DocumentFilter.BLACK_AND_WHITE -> DocumentRowFilterPlan(
                filter = filter,
                threshold = otsuThreshold(histogram, totalPixelCount),
            )

            else -> error("Histogram is not required for this filter.")
        }
    }

    internal fun applyRowFilterInPlace(pixels: IntArray, plan: DocumentRowFilterPlan) {
        pixels.indices.forEach { index ->
            val value = pixels[index]
            val alpha = alpha(value)
            val red = red(value)
            val green = green(value)
            val blue = blue(value)
            pixels[index] = when (plan.filter) {
                DocumentFilter.ORIGINAL -> value
                DocumentFilter.GRAYSCALE -> {
                    val luminance = luminance(red, green, blue)
                    argb(alpha, luminance, luminance, luminance)
                }

                DocumentFilter.HIGH_CONTRAST -> argb(
                    alpha,
                    contrast(red),
                    contrast(green),
                    contrast(blue),
                )

                DocumentFilter.AUTO_ENHANCE -> if (plan.useHighContrastFallback) {
                    argb(alpha, contrast(red), contrast(green), contrast(blue))
                } else {
                    argb(
                        alpha,
                        stretch(red, plan.blackPoint, plan.whitePoint),
                        stretch(green, plan.blackPoint, plan.whitePoint),
                        stretch(blue, plan.blackPoint, plan.whitePoint),
                    )
                }

                DocumentFilter.BLACK_AND_WHITE -> {
                    val channel = if (luminance(red, green, blue) <= plan.threshold) 0 else 255
                    argb(alpha, channel, channel, channel)
                }
            }
        }
    }

    private fun percentile(histogram: IntArray, total: Int, percentile: Float): Int {
        val target = (total * percentile).roundToInt().coerceIn(0, total - 1)
        var count = 0
        histogram.forEachIndexed { value, frequency ->
            count += frequency
            if (count > target) return value
        }
        return 255
    }

    private fun otsuThreshold(histogram: IntArray, total: Int): Int {
        var totalLuminance = 0L
        histogram.forEachIndexed { luminance, count -> totalLuminance += luminance.toLong() * count }
        var backgroundCount = 0
        var backgroundLuminance = 0L
        var bestThreshold = 127
        var bestVariance = -1.0
        histogram.forEachIndexed { value, count ->
            backgroundCount += count
            if (backgroundCount == 0) return@forEachIndexed
            val foregroundCount = total - backgroundCount
            if (foregroundCount == 0) return@forEachIndexed
            backgroundLuminance += value.toLong() * count
            val backgroundMean = backgroundLuminance.toDouble() / backgroundCount
            val foregroundMean = (totalLuminance - backgroundLuminance).toDouble() / foregroundCount
            val variance = backgroundCount.toDouble() * foregroundCount *
                (backgroundMean - foregroundMean) * (backgroundMean - foregroundMean)
            if (variance > bestVariance) {
                bestVariance = variance
                bestThreshold = value
            }
        }
        return bestThreshold
    }

    private fun stretch(value: Int, low: Int, high: Int): Int =
        (((value - low) * 255f) / (high - low)).roundToInt().coerceIn(0, 255)

    private fun contrast(value: Int): Int =
        ((value - 128) * HighContrastFactor + 128).roundToInt().coerceIn(0, 255)

    private fun luminance(red: Int, green: Int, blue: Int): Int =
        (red * RedLuminanceWeight + green * GreenLuminanceWeight + blue * BlueLuminanceWeight)
            .roundToInt()
            .coerceIn(0, 255)

    private fun alpha(pixel: Int): Int = pixel ushr 24 and 0xff
    private fun red(pixel: Int): Int = pixel ushr 16 and 0xff
    private fun green(pixel: Int): Int = pixel ushr 8 and 0xff
    private fun blue(pixel: Int): Int = pixel and 0xff
    private fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
        alpha shl 24 or (red shl 16) or (green shl 8) or blue
}

internal data class DocumentRowFilterPlan(
    val filter: DocumentFilter,
    val blackPoint: Int = 0,
    val whitePoint: Int = 255,
    val threshold: Int = 127,
    val useHighContrastFallback: Boolean = false,
)

data class ArgbImage(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
) {
    init {
        require(isSupportedDimensions(width, height))
        require(pixels.size.toLong() == width.toLong() * height)
    }

    companion object {
        fun isSupportedDimensions(width: Int, height: Int): Boolean =
            width > 0 && height > 0 && width.toLong() * height <= Int.MAX_VALUE
    }
}
