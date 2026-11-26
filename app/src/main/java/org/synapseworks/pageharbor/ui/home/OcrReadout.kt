package org.synapseworks.pageharbor.ui.home

import org.synapseworks.pageharbor.ocr.OcrResult
import org.synapseworks.pageharbor.ocr.displayText

internal data class OcrReadoutBlock(val text: String, val table: Boolean)

/** Aligns only runs of structured rows. The clipboard still uses the canonical OCR text. */
internal fun ocrReadoutBlocks(text: String): List<OcrReadoutBlock> = buildList {
    val lines = text.lines()
    var start = 0
    while (start < lines.size) {
        val columns = lines[start].count { it == '\t' } + 1
        if (columns in 2..8) {
            var end = start + 1
            while (end < lines.size && lines[end].count { it == '\t' } + 1 == columns) end++
            val rows = lines.subList(start, end).map { it.split('\t') }
            if (rows.size >= 2 && rows.all { row -> row.all { it.length <= 64 } }) {
                val widths = List(columns) { column -> rows.maxOf { it[column].length } }
                add(OcrReadoutBlock(rows.joinToString("\n") { row ->
                    row.mapIndexed { column, cell ->
                        if (column == columns - 1) cell else cell.padEnd(widths[column])
                    }.joinToString("  ")
                }, table = true))
                start = end
                continue
            }
        }
        val first = start++
        while (start < lines.size && '\t' !in lines[start]) start++
        val prose = lines.subList(first, start).joinToString("\n")
        if (prose.isNotBlank()) add(OcrReadoutBlock(prose, table = false))
    }
}

internal fun ocrMatchRanges(text: String, query: String): List<IntRange> {
    val needle = query.trim()
    if (needle.isEmpty()) return emptyList()
    return buildList {
        var start = text.indexOf(needle, ignoreCase = true)
        while (start >= 0 && size < 1000) {
            add(start until start + needle.length)
            start = text.indexOf(needle, start + needle.length, ignoreCase = true)
        }
    }
}

internal const val OCR_DOCUMENT_MATCH_LIMIT = 1000

internal data class OcrDocumentMatch(val page: Int, val block: Int, val range: IntRange)

internal fun ocrDocumentMatches(
    result: OcrResult,
    query: String,
    ensureActive: () -> Unit = {},
): List<OcrDocumentMatch> = buildList {
    if (query.isBlank()) return@buildList
    result.pages.forEachIndexed { pageIndex, page ->
        ensureActive()
        ocrReadoutBlocks(page.displayText()).forEachIndexed { blockIndex, block ->
            for (range in ocrMatchRanges(block.text, query)) {
                if (size == OCR_DOCUMENT_MATCH_LIMIT) return@buildList
                add(OcrDocumentMatch(pageIndex, blockIndex, range))
            }
        }
    }
}
