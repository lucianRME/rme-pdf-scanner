package org.synapseworks.pageharbor.document.cardcopy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.synapseworks.pageharbor.document.redaction.RedactionBounds
import org.synapseworks.pageharbor.document.redaction.RedactionCoordinateSpace
import org.synapseworks.pageharbor.document.redaction.RedactionRegion
import org.synapseworks.pageharbor.document.redaction.RedactionRegionId
import org.synapseworks.pageharbor.document.redaction.RedactionTarget
import org.synapseworks.pageharbor.document.redaction.RedactionTargetKind

class CardCopyRedactionEditorTest {
    @Test
    fun editHistoryTruncatesRedoBranchAndKeepsStableSelection() {
        val first = region(1, RedactionBounds(0.1f, 0.1f, 0.4f, 0.3f))
        val second = region(2, RedactionBounds(0.5f, 0.5f, 0.9f, 0.8f))
        val editor = CardCopyRedactionEditor(
            side = CardCopySide.FRONT,
            history = listOf(emptyList(), listOf(first), listOf(first, second)),
            historyIndex = 1,
            selectedId = first.id,
            nextOrdinal = 3,
        )

        val branched = editor.withEdit(listOf(first.copy(bounds = defaultRedactionBounds())))

        assertEquals(3, branched.history.size)
        assertEquals(2, branched.historyIndex)
        assertEquals(defaultRedactionBounds(), branched.regions.single().bounds)
        assertEquals(first.id, branched.selectedRegion()?.id)
        assertTrue(branched.canUndo)
        assertFalse(branched.canRedo)
    }

    @Test
    fun selectionReturnsNullWhenHistoryNoLongerContainsRegion() {
        val editor = CardCopyRedactionEditor(
            side = CardCopySide.BACK,
            history = listOf(emptyList()),
            historyIndex = 0,
            selectedId = RedactionRegionId("removed"),
        )

        assertNull(editor.selectedRegion())
        assertFalse(editor.canUndo)
        assertFalse(editor.canRedo)
    }

    private fun region(order: Int, bounds: RedactionBounds) = RedactionRegion(
        id = RedactionRegionId("source-redaction-$order"),
        coordinateSpace = RedactionCoordinateSpace.SOURCE_NORMALIZED,
        bounds = bounds,
        target = RedactionTarget(RedactionTargetKind.CARD_SOURCE, "source"),
        confirmed = false,
        order = order,
    )
}
