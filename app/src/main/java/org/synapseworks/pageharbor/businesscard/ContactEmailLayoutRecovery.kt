package org.synapseworks.pageharbor.businesscard

import kotlin.math.abs
import org.synapseworks.pageharbor.ocr.*

/** Removes only an OCR-inserted ASCII space between tightly aligned email fragments.
 * It never changes letters, punctuation, reading order or the retained source text. */
internal object ContactEmailLayoutRecovery {
    private const val MAX_LINES = 256
    private const val MAX_ELEMENTS = 64
    private const val MAX_JOINED_ELEMENTS = 8
    private const val MAX_EMAIL_UNITS = 1024

    private data class Part(val text: String, val start: Int, val end: Int, val bounds: OcrTextBounds)
    private data class Join(val first: Int, val last: Int)

    fun lines(rawLines: List<String>, layout: OcrPageLayout?, check: () -> Unit): Map<String, String> {
        if(layout == null || layout.rotationDegrees != 0 || layout.placementMode != OcrTextPlacementMode.POSITIONED_LINES ||
            rawLines.size > MAX_LINES || layout.lines.size > MAX_LINES) return emptyMap()
        val rawCounts = rawLines.groupingBy { it.trim() }.eachCount()
        val layoutCounts = layout.lines.groupingBy { it.text.trim() }.eachCount()
        val result = mutableMapOf<String, String>()
        for(line in layout.lines) {
            check()
            val text = line.text.trim()
            if('@' !in text || rawCounts[text] != 1 || layoutCounts[text] != 1 || !safeContactValue(text, 32768)) continue
            val parts = align(text, line, layout) ?: continue
            val joins = mutableListOf<Join>()
            for(first in parts.indices) {
                check()
                val joined = StringBuilder(parts[first].text)
                for(last in first + 1 .. minOf(parts.lastIndex, first + MAX_JOINED_ELEMENTS - 1)) {
                    check()
                    if(!connect(parts[last - 1], parts[last], text)) break
                    joined.append(parts[last].text)
                    if(joined.length > MAX_EMAIL_UNITS) break
                    // A complete address is not extended; all characters must come from fragments.
                    if((first..last).any { complete(parts[it].text.trim(',', ';')) }) continue
                    if(complete(joined.toString().trim(',', ';')) &&
                        (first until last).any { parts[it + 1].start > parts[it].end }) joins += Join(first, last)
                }
            }
            // Competing overlapping interpretations are ambiguous, even if each looks valid alone.
            val unique = joins.filter { join -> joins.none { other -> other !== join &&
                other.first <= join.last && join.first <= other.last } }
            if(unique.isEmpty()) continue
            val remove = BooleanArray(text.length)
            for(join in unique) for(index in join.first until join.last)
                if(parts[index + 1].start > parts[index].end) remove[parts[index].end] = true
            val recovered = buildString(text.length) { text.forEachIndexed { index, char -> if(!remove[index]) append(char) } }
            if(recovered != text) result[text] = recovered
        }
        return result
    }

    private fun align(text: String, line: OcrTextLine, layout: OcrPageLayout): List<Part>? {
        if(line.elements.size !in 2..MAX_ELEMENTS) return null
        val row = line.bounds ?: return null
        if(!valid(row, layout)) return null
        val parts = ArrayList<Part>(line.elements.size)
        var cursor = 0
        for(element in line.elements) {
            val value = element.text
            val bounds = element.bounds ?: return null
            if(value.isEmpty() || value.length > MAX_EMAIL_UNITS || value.any(Char::isWhitespace) ||
                !valid(bounds, layout) || bounds.left < row.left || bounds.right > row.right ||
                bounds.top < row.top || bounds.bottom > row.bottom) return null
            while(cursor < text.length && text[cursor] == ' ') cursor++
            if(!text.startsWith(value, cursor)) return null
            parts += Part(value, cursor, cursor + value.length, bounds)
            cursor += value.length
        }
        if(text.substring(cursor).any { it != ' ' }) return null
        return parts
    }

    private fun valid(bounds: OcrTextBounds, layout: OcrPageLayout) = bounds.left >= 0f && bounds.top >= 0f &&
        bounds.right > bounds.left && bounds.bottom > bounds.top && bounds.right <= layout.imageWidthPx && bounds.bottom <= layout.imageHeightPx

    private fun connect(left: Part, right: Part, source: String): Boolean {
        val separator = source.substring(left.end, right.start)
        if(separator.isNotEmpty() && separator != " ") return false
        if(left.text.last() !in "@." && right.text.first() !in "@.") return false
        val a = left.bounds; val b = right.bounds
        val ah = a.bottom - a.top; val bh = b.bottom - b.top
        val h = minOf(ah, bh)
        val overlap = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        if(overlap < .85f * maxOf(ah, bh) || abs((a.top + a.bottom - b.top - b.bottom) / 2f) > .15f * h) return false
        val advance = minOf((a.right - a.left) / left.text.codePointCount(0, left.text.length),
            (b.right - b.left) / right.text.codePointCount(0, right.text.length))
        val gap = b.left - a.right
        return gap >= 0f && gap <= minOf(.25f * h, .5f * advance)
    }

    private fun complete(value: String): Boolean {
        if(!CONTACT_EMAIL_PATTERN.matches(value) || value.count { it == '@' } != 1) return false
        val local = value.substringBefore('@'); val domain = value.substringAfter('@')
        if(local.startsWith('.') || local.endsWith('.') || ".." in local || ".." in domain) return false
        return domain.split('.').all { it.isNotEmpty() && !it.startsWith('-') && !it.endsWith('-') }
    }
}
