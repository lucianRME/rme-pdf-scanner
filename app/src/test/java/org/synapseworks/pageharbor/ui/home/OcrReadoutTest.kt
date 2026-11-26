package org.synapseworks.pageharbor.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrReadoutTest {
    @Test
    fun tablePresentationAlignsColumnsAndKeepsEmptyAndLiteralCells() {
        val text = "Receipt\n\nITEM\tQTY\tTOTAL\nPaper\t2\t8.00\nPen\t\t3.42\n\nFine print"
        val blocks = ocrReadoutBlocks(text)
        assertEquals(listOf(false, true, false), blocks.map { it.table })
        val rows = blocks[1].text.lines()
        assertEquals(rows.map { it.indexOfLast { char -> char == ' ' } }.distinct().size, 1)
        assertEquals("Pen         3.42", rows.last())
        assertEquals(true, text.contains("Pen\t\t3.42"))
    }

    @Test
    fun searchIsCaseInsensitiveAndKeepsCodeDigitsLiteral() {
        val text = "0012 BLUE BOOK\n0100 GREEN book\n"
        assertEquals(listOf(10..13, 26..29), ocrMatchRanges(text, " book "))
        assertEquals(listOf(0..3), ocrMatchRanges(text, "0012"))
        assertEquals(emptyList<IntRange>(), ocrMatchRanges(text, "OO12"))
    }

    @Test
    fun documentSearchTracksPageAndTableBlockInReadingOrder() {
        val result = org.synapseworks.pageharbor.ocr.OcrResult(listOf(
            org.synapseworks.pageharbor.ocr.OcrPageResult(0, "BOOK\n\nCODE\tITEM\n0012\tBLUE BOOK"),
            org.synapseworks.pageharbor.ocr.OcrPageResult(1, ""),
            org.synapseworks.pageharbor.ocr.OcrPageResult(2, "Next book"),
        ))
        assertEquals(listOf(
            OcrDocumentMatch(0, 0, 0..3),
            OcrDocumentMatch(0, 1, 22..25),
            OcrDocumentMatch(2, 0, 5..8),
        ), ocrDocumentMatches(result, "book"))
        assertEquals(emptyList<OcrDocumentMatch>(), ocrDocumentMatches(result, " "))
    }

}
