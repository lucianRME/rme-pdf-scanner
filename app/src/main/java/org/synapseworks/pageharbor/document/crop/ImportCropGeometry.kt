package org.synapseworks.pageharbor.document.crop

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal data class CropPoint(val x: Float, val y: Float)

internal data class PageCrop(val corners: List<CropPoint>) {
    init {
        require(corners.size == 4 && corners.all { it.x in 0f..1f && it.y in 0f..1f })
        // Ordered clockwise, beginning at the upper-left corner. Reject crossing edges and slivers.
        require(corners.indices.all { index ->
            val a = corners[index]
            val b = corners[(index + 1) % 4]
            val c = corners[(index + 2) % 4]
            (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x) >= .01f
        })
    }

    fun moveCorner(index: Int, dx: Float, dy: Float): PageCrop {
        val point = corners[index]
        val moved = CropPoint((point.x + dx).coerceIn(0f, 1f), (point.y + dy).coerceIn(0f, 1f))
        val updated = corners.toMutableList().apply { set(index, moved) }
        return runCatching { PageCrop(updated) }.getOrDefault(this)
    }

    fun outputSize(width: Int, height: Int): Pair<Int, Int> {
        fun edge(a: Int, b: Int): Float = hypot(
            (corners[a].x - corners[b].x) * width,
            (corners[a].y - corners[b].y) * height,
        )
        val w = (edge(0, 1) + edge(2, 3)) / 2
        val h = (edge(1, 2) + edge(3, 0)) / 2
        val scale = minOf(1f, 4096f / maxOf(w, h), sqrt(12_000_000f / (w * h)))
        return (w * scale).roundToInt().coerceAtLeast(2) to (h * scale).roundToInt().coerceAtLeast(2)
    }

    val boundsCoverFrame: Boolean
        get() = corners.zip(FULL.corners).all { (a, b) -> abs(a.x - b.x) < .003f && abs(a.y - b.y) < .003f }

    companion object {
        val FULL = PageCrop(listOf(CropPoint(0f, 0f), CropPoint(1f, 0f), CropPoint(1f, 1f), CropPoint(0f, 1f)))
    }
}

/** Conservative light-paper proposal. The largest bright region must outline four straight,
 * visible edges. The reviewed manual tool also works when this detector abstains.
 */
internal object ImportCropDetector {
    fun detect(width: Int, height: Int, pixels: IntArray, checkActive: () -> Unit = {}): PageCrop? {
        require(width in 1..1024 && height in 1..1024 && pixels.size == width * height)
        if (minOf(width, height) < 64) return null
        val perimeter = IntArray(256)
        val center = IntArray(256)
        val light = ByteArray(pixels.size)
        for (y in 0 until height) {
            checkActive()
            for (x in 0 until width) {
                val index = y * width + x
                val pixel = pixels[index]
                val gray = ((pixel shr 16 and 255) * 77 + (pixel shr 8 and 255) * 150 + (pixel and 255) * 29) shr 8
                light[index] = gray.toByte()
                if (x < 2 || x >= width - 2 || y < 2 || y >= height - 2) perimeter[gray]++
                if (x in width / 3 until width * 2 / 3 && y in height / 3 until height * 2 / 3) center[gray]++
            }
        }
        val background = percentile(perimeter, .75f)
        val paper = percentile(center, .65f)
        if (paper - background < 26) return null
        val threshold = background + maxOf(14, (paper - background) / 3)
        val component = largestRegion(light, width, height, threshold, checkActive) ?: return null
        val left = IntArray(height) { width }
        val right = IntArray(height) { -1 }
        val top = IntArray(width) { height }
        val bottom = IntArray(width) { -1 }
        for (index in component) {
            val y = index / width
            val x = index % width
            left[y] = minOf(left[y], x)
            right[y] = maxOf(right[y], x)
            top[x] = minOf(top[x], y)
            bottom[x] = maxOf(bottom[x], y)
        }
        val xRange = supportedMiddle(top, bottom, height) ?: return null
        val yRange = supportedMiddle(left, right, width) ?: return null
        val topEdge = fit(top, xRange, height) ?: return null
        val bottomEdge = fit(bottom, xRange, height) ?: return null
        val leftEdge = fit(left, yRange, width) ?: return null
        val rightEdge = fit(right, yRange, width) ?: return null
        fun corner(vertical: Line, horizontal: Line): CropPoint {
            val x = (vertical.offset + vertical.slope * horizontal.offset) / (1 - vertical.slope * horizontal.slope)
            return CropPoint(x / (width - 1), horizontal.at(x) / (height - 1))
        }
        val corners = listOf(
            corner(leftEdge, topEdge), corner(rightEdge, topEdge),
            corner(rightEdge, bottomEdge), corner(leftEdge, bottomEdge),
        )
        if (corners.any { it.x !in .004f.. .996f || it.y !in .004f.. .996f }) return null
        return runCatching { PageCrop(corners) }.getOrNull()
    }

    private fun percentile(histogram: IntArray, fraction: Float): Int {
        val target = (histogram.sum() * fraction).toInt()
        var seen = 0
        return histogram.indexOfFirst { count -> seen += count; seen > target }.coerceAtLeast(0)
    }

    private fun largestRegion(
        light: ByteArray,
        width: Int,
        height: Int,
        threshold: Int,
        checkActive: () -> Unit,
    ): IntArray? {
        val bright = BooleanArray(light.size) { (light[it].toInt() and 255) >= threshold }
        val queue = IntArray(light.size)
        var largest: IntArray? = null
        val minimumArea = width * height / 4
        for (start in bright.indices) {
            if (start % 8192 == 0) checkActive()
            if (!bright[start]) continue
            var head = 0
            var tail = 0
            fun add(index: Int) {
                if (bright[index]) {
                    bright[index] = false
                    queue[tail++] = index
                }
            }
            add(start)
            while (head < tail) {
                if (head % 8192 == 0) checkActive()
                val index = queue[head++]
                val x = index % width
                if (x > 0) add(index - 1)
                if (x < width - 1) add(index + 1)
                if (index >= width) add(index - width)
                if (index < light.size - width) add(index + width)
            }
            if (tail >= minimumArea && tail > (largest?.size ?: 0)) largest = queue.copyOf(tail)
        }
        return largest
    }

    private fun supportedMiddle(first: IntArray, last: IntArray, depth: Int): IntRange? {
        val start = first.indices.firstOrNull { last[it] - first[it] >= depth / 3 } ?: return null
        val end = first.indices.last { last[it] - first[it] >= depth / 3 }
        if (end - start < first.size * .42f) return null
        val trim = ((end - start) * .2f).toInt()
        return (start + trim)..(end - trim)
    }

    private data class Line(val slope: Float, val offset: Float) {
        fun at(x: Float): Float = offset + slope * x
    }

    private fun fit(edges: IntArray, middle: IntRange, depth: Int): Line? {
        val samples = middle.filter { edges[it] in 0 until depth }
        if (samples.size < middle.count() * .90f) return null

        fun regression(indices: List<Int>): Line? {
            val x = indices.average().toFloat()
            val y = indices.map { edges[it] }.average().toFloat()
            var variance = 0f
            var covariance = 0f
            for (i in indices) {
                variance += (i - x) * (i - x)
                covariance += (i - x) * (edges[i] - y)
            }
            if (variance <= 0f) return null
            return Line(covariance / variance, y - covariance / variance * x)
        }

        val initial = regression(samples) ?: return null
        val tolerance = maxOf(2f, depth * .012f)
        val inliers = samples.filter { abs(edges[it] - initial.at(it.toFloat())) <= tolerance }
        if (inliers.size < samples.size * .85f) return null
        return regression(inliers)?.takeIf { abs(it.slope) <= .6f }
    }
}
