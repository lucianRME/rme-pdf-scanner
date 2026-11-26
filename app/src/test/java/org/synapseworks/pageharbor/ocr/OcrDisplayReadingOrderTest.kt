package org.synapseworks.pageharbor.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrDisplayReadingOrderTest {
    private fun line(text: String, x: Int, y: Int, width: Int, height: Int): OcrTextLine {
        val box = OcrTextBounds(x.toFloat(), y.toFloat(), (x + width).toFloat(), (y + height).toFloat())
        return OcrTextLine(text, box, .9f, listOf(OcrTextElement(text, box)))
    }

    private fun resolve(groups: List<List<OcrTextLine>>, rotation: Int = 0, angle: Float = 0f): OcrReadingOrderResolution =
        OcrReadingOrderResolver.resolve(
            OcrPageLayout(1000, 1400, rotation, groups.flatten(), groups.map { OcrTextBlock(it) }), angle,
        )

    @Test fun disjointTitleCaptionAndCreditFollowTheirVisualPositions() {
        val a = line("FIELD", 350, 60, 300, 72)
        val b = line("at", 470, 147, 60, 27)
        val c = line("DUSK", 270, 194, 460, 110)
        val d = line("One observation", 50, 690, 209, 22)
        val e = line("from the river", 50, 725, 190, 22)
        val f = line("Team North", 300, 1230, 400, 67)
        val expected = listOf(a, b, c, d, e, f)
        val resolved = resolve(listOf(listOf(d, e, a), listOf(c), listOf(b), listOf(f)))
        assertEquals(expected, resolved.layout.lines)
        assertEquals(expected, resolved.layout.blocks.flatMap { it.lines })
        assertEquals(expected.map { it.text }, resolved.canonicalText.lines().filter { it.isNotEmpty() })
    }

    @Test fun repeatsAndLogicalTextSurviveJitteredRightAlignedHeadings() {
        val a = line("عودة", 220, 105, 650, 72)
        val b = line("دفتر", 520, 194, 345, 48)
        val c = line("عودة", 370, 259, 502, 57)
        val d = line("Note 42", 75, 945, 224, 25)
        val e = line("عودة", 74, 982, 140, 24)
        assertEquals(listOf(a,b,c,d,e), resolve(listOf(listOf(d,e,b),listOf(c,a))).layout.lines)
    }

    @Test fun displayTitleAboveParallelColumnsPreservesRecognizerColumnOrder() {
        val a = line("Open", 285, 55, 410, 60)
        val b = line("Roads", 264, 148, 472, 70)
        val l1 = line("Left introduction", 55, 530, 290, 23)
        val l2 = line("Left continuation", 55, 571, 301, 23)
        val r1 = line("Right introduction", 580, 538, 310, 23)
        val r2 = line("Right continuation", 580, 579, 323, 23)
        val groups = listOf(listOf(l1,l2),listOf(r1,r2),listOf(b),listOf(a))
        assertEquals(listOf(a,b,l1,l2,r1,r2), resolve(groups).layout.lines)
    }

    @Test fun paragraphWhitespaceAcrossColumnsDoesNotInterleaveTheirParagraphs() {
        val title = listOf(line("Across", 340, 50, 310, 70), line("Fields", 300, 140, 390, 70))
        val ys = listOf(480, 515, 810, 845)
        val left = ys.mapIndexed { i, y -> line("Left $i", 60, y, 280, 22) }
        val right = ys.mapIndexed { i, y -> line("Right $i", 550, y, 280, 22) }
        val expected = title + left + right
        assertEquals(expected, resolve(listOf(left, right, title)).layout.lines)
        assertEquals(expected, resolve(listOf(title, left, right)).layout.lines)
    }

    @Test fun ambiguousProseOverlapsAndSourceRotationKeepTheirOriginalOrder() {
        val a = line("First side", 40, 15, 320, 30)
        val b = line("Second side", 520, 23, 340, 30)
        val c = line("Narrow disclaimer", 70, 900, 205, 18)
        val d = line("Next detail", 70, 935, 205, 18)
        val groups = listOf(listOf(c,d,a),listOf(b))
        for (angle in listOf(0f, 23f)) assertEquals(groups.flatten(), resolve(groups, angle = angle).layout.lines)
        assertEquals(groups.flatten(), resolve(groups, rotation = 90).layout.lines)
    }
}
