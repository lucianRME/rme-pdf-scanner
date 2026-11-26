package org.synapseworks.pageharbor.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class OcrTableCellRecoveryTest {
    @Test
    fun rereadsOnlyTheAmbiguousWordAndRetainsElementPositions() {
        val blue = element("BLUE", 654, 261, 776, 301)
        val ambiguous = element("B0OK", 795, 261, 932, 301)
        val label = line("BLUE B0OK", 654, 261, 932, 301).copy(elements = listOf(blue, ambiguous))
        val code = line("0012", 82, 260, 198, 300)
        val calls = mutableListOf<Int>()
        val output = OcrTableCellRecovery(1600, 1200) { bounds, _, height ->
            calls += height
            assertEquals(785.5f, bounds.left)
            line("BOOK", 795, 261, 932, 301)
        }.recover(listOf(listOf(code, label, line("2", 1183, 261, 1208, 300))), 0f)

        val corrected = output.single()[1]!!
        assertEquals(listOf(48, 96), calls)
        assertEquals("BLUE BOOK", corrected.text)
        assertSame(code, output.single()[0])
        assertSame(label.bounds, corrected.bounds)
        assertSame(blue, corrected.elements.first())
        assertSame(ambiguous.bounds, corrected.elements.last().bounds)
        assertEquals("BOOK", corrected.elements.last().text)
    }

    @Test
    fun onlyAddsAnInteriorQuantityWhenBothReadsAgreeAndFitTheCell() {
        val rows = receiptRows()
        val scales = mutableListOf<Int>()
        val quantity = line("1", 902, 852, 912, 877)
        val output = OcrTableCellRecovery(1440, 1920) { bounds, _, scale ->
            scales += scale
            assertEquals(OcrTextBounds(884f, 841f, 932f, 897f), bounds)
            quantity
        }.recover(rows, .077f)

        assertEquals(listOf(40, 96), scales)
        assertSame(quantity, output[1][1])
        assertSame(rows[1][0], output[1][0])
        assertSame(rows[1][2], output[1][2])
    }

    @Test
    fun keepsEmptyCellsForBlankConflictingOrMisplacedReadings() {
        val rows = receiptRows()
        val responders: List<(Int) -> OcrTextLine?> = listOf(
            { null },
            { scale -> line(if (scale == 40) "1" else "2", 902, 852, 912, 877) },
            { line("1", 885, 841, 895, 865) },
            { line("O", 902, 852, 912, 877) },
        )
        for (respond in responders) {
            val output = OcrTableCellRecovery(1440, 1920) { _, _, scale -> respond(scale) }
                .recover(rows, .077f)
            assertNull(output[1][1])
            assertEquals(rows, output)
        }
    }

    @Test
    fun leavesLiteralTextWhenWordReadsDisagreeOrSuggestOtherChanges() {
        val label = line("BLUE B0OK", 654, 261, 932, 301).copy(
            elements = listOf(
                element("BLUE", 654, 261, 776, 301),
                element("B0OK", 795, 261, 932, 301),
            ),
        )
        val rows = listOf(listOf(label))
        val responders: List<(Int) -> String> = listOf(
            { "B0OK" },
            { "LOOK" },
            { scale -> if (scale == 48) "BOOK" else "B0OK" },
        )
        for (respond in responders) {
            val output = OcrTableCellRecovery(1600, 1200) { _, _, scale ->
                line(respond(scale), 795, 261, 932, 301)
            }.recover(rows, 0f)
            assertSame(label, output[0][0])
        }
    }

    @Test
    fun limitsRereadsAcrossGridsOnTheSamePage() {
        val rows = receiptRows()
        var calls = 0
        val recovery = OcrTableCellRecovery(1440, 1920) { _, _, _ ->
            calls++
            line("1", 902, 852, 912, 877)
        }
        repeat(10) { recovery.recover(rows, .077f) }
        assertEquals(8, calls)
    }

    private fun receiptRows() = listOf(
        listOf(line("Paper", 299, 709, 395, 753), line("2", 898, 764, 915, 791), line("8.00", 1035, 776, 1100, 807)),
        listOf(line("Pen", 291, 801, 353, 833), null, line("3.42", 1036, 864, 1106, 897)),
        listOf(line("File", 282, 892, 339, 927), line("2", 901, 946, 919, 975), line("6.00", 1040, 956, 1108, 991)),
    )

    private fun line(text: String, left: Int, top: Int, right: Int, bottom: Int) =
        OcrTextLine(text, element(text, left, top, right, bottom).bounds)

    private fun element(text: String, left: Int, top: Int, right: Int, bottom: Int) =
        OcrTextElement(text, OcrTextBounds(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat()))
}
