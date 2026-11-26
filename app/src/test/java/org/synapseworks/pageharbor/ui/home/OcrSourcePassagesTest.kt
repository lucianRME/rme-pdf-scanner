package org.synapseworks.pageharbor.ui.home

import org.junit.Assert.*
import org.junit.Test
import org.synapseworks.pageharbor.ocr.*

class OcrSourcePassagesTest {
    private fun page(vararg text: String) = OcrPageLayout(100, 100, lines = text.mapIndexed { i, s ->
        OcrTextLine(s, OcrTextBounds(12f, i * 6f, 50f, i * 6f + 5f))
    })

    @Test fun repeatedWordsInTableMapToTheirOwnSourceRows() {
        val blocks = ocrReadoutBlocks("Item\tQty\nPen\t2\nPen\t7")
        val layout = page("Item", "Qty", "Pen", "2", "Pen", "7")
        val links = sourcePassages(blocks, layout)
        assertEquals(layout.lines.map { it.text }, links.map { blocks[it.block].text.substring(it.start, it.end) })
        assertEquals(.12f, links[2].bounds.top, .0001f)
        assertEquals(.24f, links[4].bounds.top, .0001f)
    }

    @Test fun unmatchedOrFreeformTextHasNoPositionLinks() {
        val blocks = ocrReadoutBlocks("A\nB")
        assertTrue(sourcePassages(blocks, page("A", "C")).isEmpty())
        assertTrue(sourcePassages(blocks, page("A", "B", "C")).isEmpty())
        assertTrue(sourcePassages(blocks, page("A", "B").copy(placementMode = OcrTextPlacementMode.FREEFORM_PAGE_REGION)).isEmpty())
    }

    @Test fun missingGeometryDoesNotShiftFollowingLinks() {
        val p = page("Yes", "Yes")
        val links = sourcePassages(ocrReadoutBlocks("Yes\nYes"), p.copy(lines = listOf(p.lines[0].copy(bounds = null), p.lines[1])))
        assertEquals(4,links.single().start)
        assertEquals(.06f, links.single().bounds.top, .0001f)
    }
}
