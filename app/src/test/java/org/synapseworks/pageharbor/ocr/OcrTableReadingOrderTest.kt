package org.synapseworks.pageharbor.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class OcrTableReadingOrderTest {
    @Test
    fun alignsFrozenTableElementsAndPreservesTheObservedSpelling() {
        val columns = listOf(
            listOf("CODE", "0012", "0100", "0200"),
            listOf("ITEM", "BLUE B0OK", "COFFEE", "PAPER"),
            listOf("QTY", "2", "10", "5"),
        )
        val blocks = columns.mapIndexed { col, data ->
            OcrTextBlock(data.mapIndexed { row, value ->
                val left = 82f + col * 550f
                cell(value, left, 60f + row * 200f, left + value.length * 29f, 40f)
            })
        }
        val originalCells = blocks.flatMap(OcrTextBlock::lines)
        val result = OcrReadingOrderResolver.resolve(page(blocks))
        val expected = "CODE\tITEM\tQTY\n0012\tBLUE B0OK\t2\n0100\tCOFFEE\t10\n0200\tPAPER\t5"
        val expectedCells = (0..3).flatMap { row -> blocks.map { it.lines[row] } }
        assertEquals(expected, result.canonicalText)
        assertEquals(expectedCells, result.layout.lines)
        assertEquals(result.layout.lines, result.layout.blocks.flatMap(OcrTextBlock::lines))
        assertSame(originalCells[5].bounds, result.layout.lines[4].bounds)
        assertSame(originalCells[5].elements, result.layout.lines[4].elements)
        assertEquals(expected, copyableOcrPreview(OcrResult(listOf(OcrPageResult(0, expected, layout = result.layout))),
            { "Page $it" }, "Empty"))
    }

    @Test
    fun deskewsReceiptRowsAndLeavesAMissingQuantitySlot() {
        // Positioned line bounds observed from the skewed receipt on the Samsung.
        fun l(text: String, x: Number, y: Number, r: Number, b: Number) =
            OcrTextLine(text, OcrTextBounds(x.toFloat(), y.toFloat(), r.toFloat(), b.toFloat()))
        val blocks = listOf(
            l("DOCUMENT QUALITY TEST",332,252,979,352),
            l("Synthetic receipt 042",323,330,685,399),
            l("DATE: 2026-11-24",316,418,592,471),
            l("TOTAL: EUR 17.42",307,484,608,540),
            l("ITEM",307,618,391,652), l("Paper",299,709,395,753),
            l("Pen",291,801,353,833), l("File",282,892,339,927),
            l("Approved",252,1111,467,1172),
            l("Fine print: read every digit and keep the bottom margin.",218,1471,855,1546),
            l("QTY",878,671,946,703), l("End of page 042",208,1584,412,1632),
            l("2",898,764,915,791), l("2",901,946,919,975),
            l("The blue and red marks must remain distinguishable.",237,1270,982,1361),
            l("TOTAL",997,680,1108,717), l("8.00",1035,776,1100,807),
            l("3.42",1036,864,1106,897), l("6.00",1040,956,1108,991),
            l("REVIEW COPY",886,1167,1133,1216),
        ).map { OcrTextBlock(listOf(it)) }
        val result = OcrReadingOrderResolver.resolve(page(blocks), 4.38f)
        val expectedLines = listOf(
            "DOCUMENT QUALITY TEST", "Synthetic receipt 042", "DATE: 2026-11-24", "TOTAL: EUR 17.42",
            "ITEM\tQTY\tTOTAL", "Paper\t2\t8.00", "Pen\t\t3.42", "File\t2\t6.00",
            "Approved", "REVIEW COPY", "The blue and red marks must remain distinguishable.",
            "Fine print: read every digit and keep the bottom margin.", "End of page 042",
        )
        assertEquals(expectedLines, result.canonicalText.lines().filter(String::isNotBlank))
        assertEquals(blocks.flatMap(OcrTextBlock::lines).toSet(), result.layout.lines.toSet())
        assertEquals(blocks.sumOf { it.lines.size }, result.layout.lines.size)
    }

    @Test
    fun preservesColumnsWhenTextIsProseOrNumbersDoNotRepeatInAColumn() {
        val blocks = listOf(
            OcrTextBlock(listOf(cell("History",20f,20f,150f),cell("1920",20f,52f,70f),cell("Overview",20f,84f,150f))),
            OcrTextBlock(listOf(cell("Context",300f,20f,430f),cell("Notes",300f,52f,410f),cell("References",300f,84f,480f))),
        )
        assertEquals("History\n1920\nOverview\nContext\nNotes\nReferences",
            OcrReadingOrderResolver.resolve(page(blocks)).canonicalText)
    }

    @Test
    fun preservesOriginalOrderForRightToLeftTables() {
        val blocks = listOf(
            OcrTextBlock(listOf(cell("الصنف",20f,20f,150f),cell("ورق",20f,52f,100f),cell("قلم",20f,84f,90f))),
            OcrTextBlock(listOf(cell("الكمية",300f,20f,420f),cell("2",300f,52f,330f),cell("3",300f,84f,330f))),
        )
        assertEquals("الصنف\nورق\nقلم\nالكمية\n2\n3", OcrReadingOrderResolver.resolve(page(blocks)).canonicalText)
    }

    private fun page(blocks: List<OcrTextBlock>) = OcrPageLayout(
        1600, 1920, lines = blocks.flatMap(OcrTextBlock::lines), blocks = blocks,
    )

    private fun cell(text: String, left: Float, top: Float, right: Float, height: Float = 17f): OcrTextLine {
        val bounds = OcrTextBounds(left, top, right, top + height)
        return OcrTextLine(text, bounds, elements = listOf(OcrTextElement(text, bounds)))
    }
}
