package org.synapseworks.pageharbor.ocr

import kotlin.math.abs

/** Recover stacked display text when the recognizer has mixed separate parts of a page.
 * Horizontal whitespace separates regions. A vertical cut through parallel text never
 * changes its column order, and no words or coordinates are reconstructed here.
 */
internal object OcrDisplayReadingOrder {
    private data class Row(val ordinal: Int, val block: Int, val line: OcrTextLine, val box: OcrTextBounds) {
        val height: Float get() = box.bottom - box.top
    }

    fun order(blocks: List<OcrTextBlock>, pageHeight: Int, angleDegrees: Float): List<OrderedOcrBlock>? {
        val count = blocks.sumOf { it.lines.size }
        if (count !in 4..128 || !angleDegrees.isFinite() || abs(angleDegrees) > 10f) return null

        val rows = buildList {
            blocks.forEachIndexed { block, group ->
                for (line in group.lines) {
                    val box = line.bounds ?: return null
                    if (box.right <= box.left || box.bottom <= box.top) return null
                    add(Row(size, block, line, box))
                }
            }
        }
        val heights = rows.map(Row::height).sorted()
        val gap = maxOf(heights[heights.size / 2] * 1.75f, pageHeight * .035f)
        val bands = mutableListOf<MutableList<Row>>()
        var bottom = Float.NEGATIVE_INFINITY
        for (row in rows.sortedBy { it.box.top }) {
            if (bands.isEmpty() || row.box.top - bottom > gap) bands.add(mutableListOf())
            bands.last().add(row)
            bottom = maxOf(bottom, row.box.bottom)
        }
        if (bands.size < 2) return null

        // Require two prominent, aligned rows relative to the smaller print on this page.
        // A single heading or scattered annotations cannot trigger a page reorder.
        val bodyHeight = heights[heights.size / 4]
        val stacks = bands.map(::isAlignedStack)
        val displayBands = bands.indices.filter { i ->
            stacks[i] && bands[i].count { it.height >= bodyHeight * 1.6f } >= 2
        }
        if (displayBands.isEmpty()) return null

        val groups = mutableListOf<List<Row>>()
        var start = 0
        for (display in displayBands + bands.size) {
            val body = bands.subList(start, display)
            if ((start until display).any { !stacks[it] && bands[it].size > 1 }) {
                // A gutter or floating note makes this region ambiguous. Across paragraph
                // gaps a reader continues down one column. Keep the whole body's hierarchy.
                groups.addAll(body.flatten().sortedBy(Row::ordinal).groupBy(Row::block).values)
            } else {
                groups.addAll(body)
            }
            if (display < bands.size) groups.add(bands[display])
            start = display + 1
        }
        if (groups.flatten().map(Row::ordinal) == rows.indices.toList()) return null
        return groups.map { group -> OrderedOcrBlock(OcrTextBlock(group.map(Row::line))) }
    }

    private fun isAlignedStack(rows: List<Row>): Boolean {
        if (rows.size < 2) return false
        return rows.zipWithNext().all { (upper, lower) ->
            val first = upper.box
            val second = lower.box
            val unit = minOf(upper.height, lower.height)
            val shared = minOf(first.right, second.right) - maxOf(first.left, second.left)
            val narrow = minOf(first.right - first.left, second.right - second.left)
            val alignment = minOf(
                abs(first.left - second.left), abs(first.right - second.right),
                abs((first.left + first.right) - (second.left + second.right)) / 2f,
            )
            first.bottom - second.top <= unit * .2f && shared >= narrow * .6f && alignment <= unit * 1.5f
        }
    }
}
