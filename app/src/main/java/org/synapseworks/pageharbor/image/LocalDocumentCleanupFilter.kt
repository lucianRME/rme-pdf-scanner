package org.synapseworks.pageharbor.image

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Original local document cleanup: estimate paper illumination, normalize restrained shadows,
 * then apply a small, bounded luminance sharpening pass. It does not create detail or resize.
 *
 * A maximum 16 x 16 histogram grid uses at most 256 KiB. Processing then needs four width-sized
 * ARGB scanlines and a small gain grid, in addition to caller-owned source/destination storage.
 * Preview and full-resolution export use the same algorithm; preview sampling can change the
 * estimates slightly. Alpha is preserved and fully transparent pixels are left unchanged.
 */
internal object LocalDocumentCleanupFilter {
    private const val MaximumGridAxis = 16
    private const val MinimumCellEdge = 32
    private const val WhitePercentile = 0.90
    private const val PaperWhite = 245f
    private const val MaximumGain = 1.6f
    private const val ContrastBlackPoint = 12f
    private const val SharpeningAmount = 0.45f
    private const val MaximumSharpeningDelta = 12

    fun apply(source: ArgbImage): ArgbImage {
        val output = IntArray(source.pixels.size)
        process(
            width = source.width,
            height = source.height,
            readRow = { y, row ->
                source.pixels.copyInto(
                    row,
                    startIndex = y * source.width,
                    endIndex = (y + 1) * source.width,
                )
            },
            writeRow = { y, row -> row.copyInto(output, destinationOffset = y * source.width) },
        )
        return ArgbImage(source.width, source.height, output)
    }

    /** Borrowed row buffers must be filled/read synchronously and must not be retained. */
    fun process(
        width: Int,
        height: Int,
        readRow: (Int, IntArray) -> Unit,
        writeRow: (Int, IntArray) -> Unit,
        ensureActive: () -> Unit = {},
    ) {
        require(ArgbImage.isSupportedDimensions(width, height))
        val columns = (width / MinimumCellEdge).coerceIn(1, MaximumGridAxis)
        val rows = (height / MinimumCellEdge).coerceIn(1, MaximumGridAxis)
        val histograms = IntArray(columns * rows * 256)
        val counts = IntArray(columns * rows)
        var previous = IntArray(width)
        repeat(height) { y ->
            ensureActive()
            readRow(y, previous)
            val gridY = (y.toLong() * rows / height).toInt()
            previous.forEachIndexed { x, pixel ->
                if (pixel ushr 24 != 0) {
                    val gridX = (x.toLong() * columns / width).toInt()
                    val cell = gridY * columns + gridX
                    histograms[cell * 256 + luminance(pixel)]++
                    counts[cell]++
                }
            }
        }
        val gains = FloatArray(counts.size) { cell ->
            ensureActive()
            if (counts[cell] == 0) {
                1f
            } else {
                val target = (counts[cell] * WhitePercentile).toInt().coerceAtMost(counts[cell] - 1)
                var cumulative = 0
                var paper = 255
                for (value in 0..255) {
                    cumulative += histograms[cell * 256 + value]
                    if (cumulative > target) {
                        paper = value
                        break
                    }
                }
                (PaperWhite / max(1, paper)).coerceIn(1f, MaximumGain)
            }
        }
        var current = IntArray(width)
        var next = IntArray(width)
        val output = IntArray(width)
        fun readNormalizedRow(y: Int, row: IntArray) {
            ensureActive()
            readRow(y, row)
            val gridY = ((y + 0.5f) * rows / height - 0.5f).coerceIn(0f, rows - 1f)
            val top = gridY.toInt()
            val bottom = min(top + 1, rows - 1)
            val fy = gridY - top
            row.forEachIndexed { x, pixel ->
                if (pixel ushr 24 == 0) return@forEachIndexed
                val gridX = ((x + 0.5f) * columns / width - 0.5f).coerceIn(0f, columns - 1f)
                val left = gridX.toInt()
                val right = min(left + 1, columns - 1)
                val fx = gridX - left
                val upper = interpolate(gains[top * columns + left], gains[top * columns + right], fx)
                val lower = interpolate(gains[bottom * columns + left], gains[bottom * columns + right], fx)
                val gain = interpolate(upper, lower, fy)
                row[x] = pixel and -0x1000000 or
                    (normalize(pixel ushr 16 and 255, gain) shl 16) or
                    (normalize(pixel ushr 8 and 255, gain) shl 8) or
                    normalize(pixel and 255, gain)
            }
        }
        readNormalizedRow(0, current)
        current.copyInto(previous)
        if (height > 1) readNormalizedRow(1, next) else current.copyInto(next)
        repeat(height) { y ->
            ensureActive()
            current.forEachIndexed { x, pixel ->
                if (pixel ushr 24 == 0) {
                    output[x] = pixel
                } else {
                    val center = luminance(pixel)
                    val alpha = pixel ushr 24
                    val surrounding = neighbor(previous[x], alpha, center) +
                        neighbor(next[x], alpha, center) +
                        neighbor(current[max(0, x - 1)], alpha, center) +
                        neighbor(current[min(width - 1, x + 1)], alpha, center)
                    val localMean = (4 * center + surrounding) / 8f
                    val delta = ((center - localMean) * SharpeningAmount).roundToInt()
                        .coerceIn(-MaximumSharpeningDelta, MaximumSharpeningDelta)
                    output[x] = pixel and -0x1000000 or
                        (((pixel ushr 16 and 255) + delta).coerceIn(0, 255) shl 16) or
                        (((pixel ushr 8 and 255) + delta).coerceIn(0, 255) shl 8) or
                        ((pixel and 255) + delta).coerceIn(0, 255)
                }
            }
            writeRow(y, output)
            if (y + 1 < height) {
                val reusable = previous
                previous = current
                current = next
                next = reusable
                if (y + 2 < height) readNormalizedRow(y + 2, next) else current.copyInto(next)
            }
        }
    }

    private fun normalize(channel: Int, gain: Float): Int =
        ((channel * gain - ContrastBlackPoint) * 255f / (255f - ContrastBlackPoint))
            .roundToInt().coerceIn(0, 255)

    private fun interpolate(a: Float, b: Float, weight: Float): Float = a + (b - a) * weight

    private fun neighbor(pixel: Int, alpha: Int, fallback: Int): Int =
        if (pixel ushr 24 == alpha) luminance(pixel) else fallback

    private fun luminance(pixel: Int): Int =
        ((pixel ushr 16 and 255) * 0.2126f + (pixel ushr 8 and 255) * 0.7152f +
            (pixel and 255) * 0.0722f).roundToInt().coerceIn(0, 255)
}
