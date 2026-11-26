package org.synapseworks.pageharbor.ocr

import kotlin.math.abs
import kotlin.math.tan

internal data class OrderedOcrBlock(val geometry: OcrTextBlock, val tableText: String? = null)

/**
 * Reorders a grid only when several consecutive horizontal bands agree on column boundaries,
 * row spacing and a numeric column. The output still contains the original positioned fragments.
 * Plain text joins those fragments one row at a time, including gaps for empty columns.
 */
internal object OcrTableReadingOrder {
    private class Fragment(val block: Int, val line: OcrTextLine, val bounds: OcrTextBounds, val y: Float)
    private data class Table(val lastY: Float, val rows: List<List<Fragment?>>) {
        val members = rows.flatten().filterNotNull()
        fun asBlock(recovery: OcrTableCellRecovery?, slope: Float): OrderedOcrBlock {
            val lines = rows.map { row -> row.map { it?.line } }
            val recovered = recovery?.recover(lines, slope) ?: lines
            return OrderedOcrBlock(
                geometry = OcrTextBlock(recovered.flatten().filterNotNull()),
                tableText = recovered.joinToString("\n") { row -> row.joinToString("\t") { it?.text.orEmpty() } },
            )
        }
    }

    private val numeric = Regex("""[+−-]?(?:\p{Sc}\s*)?\p{Nd}+(?:[.,:/\s]\p{Nd}+)*(?:\s*(?:\p{Sc}|%|[A-Z]{3}))?""")

    fun order(
        blocks: List<OcrTextBlock>,
        angleDegrees: Float,
        recovery: OcrTableCellRecovery?,
    ): List<OrderedOcrBlock>? {
        val count = blocks.sumOf { it.lines.size }
        if (count !in 6..256 || !angleDegrees.isFinite() || abs(angleDegrees) > 15f) return null

        val slope = tan(Math.toRadians(angleDegrees.toDouble())).toFloat()
        val fragments = blocks.flatMapIndexed { index, block ->
            block.lines.map { line ->
                val box = line.bounds?.takeIf { it.right > it.left && it.bottom > it.top } ?: return null
                Fragment(index, line, box, (box.top + box.bottom - slope * (box.left + box.right)) / 2)
            }
        }
        val heights = fragments.map { it.bounds.bottom - it.bounds.top }.sorted()
        val height = heights[heights.size / 2]
        val bands = rowBands(fragments, maxOf(3f, height * .55f))
        val tables = mutableListOf<Table>()
        var index = 0
        while (index + 2 < bands.size) {
            val table = tableAt(bands, index, height)
            if (table == null) index++
            else {
                tables += table
                index += table.rows.size
            }
        }
        if (tables.isEmpty()) return null

        val tableMembers = tables.flatMap(Table::members).toHashSet()
        val plainRegions = fragments.filterNot { it in tableMembers }.groupBy { fragment ->
            tables.count { it.lastY < fragment.y }
        }
        return buildList {
            tables.forEachIndexed { i, table ->
                addAll(proseBlocks(plainRegions[i].orEmpty(), height))
                add(table.asBlock(recovery, slope))
            }
            addAll(proseBlocks(plainRegions[tables.size].orEmpty(), height))
        }
    }

    private fun rowBands(fragments: List<Fragment>, tolerance: Float): List<List<Fragment>> {
        val bands = mutableListOf<MutableList<Fragment>>()
        for (fragment in fragments.sortedBy(Fragment::y)) {
            val band = bands.lastOrNull()
            if (band == null || fragment.y - band.first().y > tolerance) bands += mutableListOf(fragment)
            else band += fragment
        }
        return bands.map { row -> row.sortedBy { it.bounds.left } }
    }

    private fun tableAt(bands: List<List<Fragment>>, start: Int, height: Float): Table? {
        val first = bands[start]
        val second = bands[start + 1]
        val columns = first.size
        if (columns !in 2..8 || second.size != columns) return null
        if (!hasGutters(first, height) || !hasGutters(second, height)) return null
        val spacing = second.baseline - first.baseline
        if (spacing !in (height * 1.15f)..(height * 7f)) return null

        val tolerance = maxOf(5f, height * .80f)
        if (second.mapIndexed { index, cell -> alignment(cell, first[index]) }.any { it > tolerance }) return null
        val rows = mutableListOf<List<Fragment?>>(first, second)
        var previousY = second.baseline
        var fullRows = 2
        for (index in start + 2 until bands.size) {
            val band = bands[index]
            val step = band.baseline - previousY
            if (band.size !in 2..columns || step !in (spacing * .67f)..(spacing * 1.45f) || !hasGutters(band, height)) break
            val slots = place(band, first, second, tolerance) ?: break
            rows += slots
            if (band.size == columns) fullRows++
            previousY = band.baseline
        }
        if (fullRows < 3) return null
        if ((0 until columns).none { column ->
            rows.count { row -> row[column]?.line?.text?.let { it.length <= 64 && numeric.matches(it) } == true } >= 2
        }) return null
        // Keep the recognizer's order for bidirectional regions.
        if (rows.flatten().filterNotNull().any { fragment ->
            fragment.line.text.any { ch ->
                val direction = Character.getDirectionality(ch)
                direction == Character.DIRECTIONALITY_RIGHT_TO_LEFT ||
                    direction == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
            }
        }) return null
        return Table(previousY, rows)
    }

    private fun place(
        band: List<Fragment>,
        first: List<Fragment>,
        second: List<Fragment>,
        tolerance: Float,
    ): List<Fragment?>? {
        val cells = MutableList<Fragment?>(first.size) { null }
        var previous = -1
        for (fragment in band) {
            val matching = first.indices.filter { column ->
                minOf(alignment(fragment, first[column]), alignment(fragment, second[column])) <= tolerance
            }
            val slot = matching.singleOrNull() ?: return null
            if (slot <= previous) return null
            cells[slot] = fragment
            previous = slot
        }
        return cells
    }

    private fun hasGutters(row: List<Fragment>, height: Float) =
        row.zipWithNext().all { (a, b) -> b.bounds.left - a.bounds.right >= height * .35f }

    private fun alignment(a: Fragment, b: Fragment) =
        minOf(abs(a.bounds.left - b.bounds.left), abs(a.bounds.right - b.bounds.right))

    private val List<Fragment>.baseline get() = map(Fragment::y).average().toFloat()

    private fun proseBlocks(fragments: List<Fragment>, height: Float): List<OrderedOcrBlock> {
        val groups = fragments.groupBy(Fragment::block).values.toList()
        // If separate multi-line blocks overlap vertically, there may be prose columns. Keep
        // their hierarchy. A single shared baseline (e.g. two stamps) can read left to right.
        val envelopes = groups.map { lines ->
            lines.minOf(Fragment::y) to lines.maxOf(Fragment::y)
        }
        val multiColumn = envelopes.indices.any { i ->
            (i + 1 until envelopes.size).any { j ->
                (groups[i].size > 1 || groups[j].size > 1) &&
                    maxOf(envelopes[i].first, envelopes[j].first) <= minOf(envelopes[i].second, envelopes[j].second)
            }
        }
        val representatives = groups.map { it.minBy(Fragment::y) }
        val bands = rowBands(representatives, height * .5f)
        val ordered = if (multiColumn || bands.count { it.size > 1 } > 1) groups else {
            val byBlock = groups.associateBy { it.first().block }
            bands.flatten().map { byBlock.getValue(it.block) }
        }
        return ordered.map { OrderedOcrBlock(OcrTextBlock(it.map(Fragment::line))) }
    }
}
