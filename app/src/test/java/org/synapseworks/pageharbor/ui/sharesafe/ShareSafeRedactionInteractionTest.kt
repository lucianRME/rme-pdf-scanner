package org.synapseworks.pageharbor.ui.sharesafe

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.synapseworks.pageharbor.document.redaction.RedactionBounds
import org.synapseworks.pageharbor.document.redaction.RedactionCoordinateMapper
import org.synapseworks.pageharbor.document.redaction.RedactionCoordinateSpace
import org.synapseworks.pageharbor.document.redaction.RedactionRegion
import org.synapseworks.pageharbor.document.redaction.RedactionRegionId
import org.synapseworks.pageharbor.document.redaction.RedactionTarget
import org.synapseworks.pageharbor.document.redaction.RedactionTargetKind
import org.synapseworks.pageharbor.document.session.DocumentPageRotation

class ShareSafeRedactionInteractionTest {
    @Test
    fun oneXDragMapsPixelsToNormalizedCoordinatesWithoutChangingDimensions() {
        val geometry = redactionPreviewGeometry(1_000f, 500f, 2_000, 1_000)
        val original = RedactionBounds(0.2f, 0.3f, 0.5f, 0.5f)
        val delta = geometry.normalizedDelta(Offset(100f, 50f))
        val moved = requireNotNull(
            moveRedactionInDisplaySpace(
                original,
                DocumentPageRotation.DEGREES_0,
                delta.x,
                delta.y,
            ),
        )

        assertBounds(RedactionBounds(0.3f, 0.4f, 0.6f, 0.6f), moved)
        assertEquals(original.width, moved.width, EPSILON)
        assertEquals(original.height, moved.height, EPSILON)
    }

    @Test
    fun zoomLocalDragAtTwoXAndMaximumScaleAndPanOffsetKeepTheSameDocumentMapping() {
        val geometry = redactionPreviewGeometry(1_000f, 500f, 2_000, 1_000)
        val original = RedactionBounds(0.2f, 0.3f, 0.5f, 0.5f)
        // A 100 px screen drag at 2x is delivered to the transformed child as 50 local px.
        val localDeltaAtTwoX = geometry.normalizedDelta(Offset(50f, 0f))
        val moved = requireNotNull(
            moveRedactionInDisplaySpace(
                original,
                DocumentPageRotation.DEGREES_0,
                localDeltaAtTwoX.x,
                localDeltaAtTwoX.y,
            ),
        )

        assertBounds(RedactionBounds(0.25f, 0.3f, 0.55f, 0.5f), moved)
        // At the 4x preview limit the same 100 px screen drag is 25 local px.
        val localDeltaAtFourX = geometry.normalizedDelta(Offset(25f, 0f))
        val movedAtFourX = requireNotNull(
            moveRedactionInDisplaySpace(
                original,
                DocumentPageRotation.DEGREES_0,
                localDeltaAtFourX.x,
                localDeltaAtFourX.y,
            ),
        )
        assertBounds(RedactionBounds(0.225f, 0.3f, 0.525f, 0.5f), movedAtFourX)
        // Translation changes where the child is hit, not its content-local delta.
        assertEquals(localDeltaAtTwoX, geometry.normalizedDelta(Offset(50f, 0f)))
    }

    @Test
    fun visuallyRightDragMapsCorrectlyAcrossEverySupportedRotation() {
        val original = RedactionBounds(0.2f, 0.25f, 0.45f, 0.55f)
        DocumentPageRotation.entries.forEach { rotation ->
            val before = RedactionCoordinateMapper.rotate(original, rotation)
            val movedSource = requireNotNull(
                moveRedactionInDisplaySpace(original, rotation, 0.1f, 0f),
            )
            val after = RedactionCoordinateMapper.rotate(movedSource, rotation)
            assertEquals("rotation=$rotation", before.left + 0.1f, after.left, EPSILON)
            assertEquals("rotation=$rotation", before.top, after.top, EPSILON)
            assertEquals("rotation=$rotation", before.width, after.width, EPSILON)
            assertEquals("rotation=$rotation", before.height, after.height, EPSILON)
        }
    }

    @Test
    fun completeRegionClampsAtEveryDisplayEdge() {
        val original = RedactionBounds(0.2f, 0.3f, 0.5f, 0.6f)
        assertBounds(
            RedactionBounds(0f, 0.3f, 0.3f, 0.6f),
            requireNotNull(moveRedactionWithinBounds(original, -1f, 0f)),
        )
        assertBounds(
            RedactionBounds(0.7f, 0.3f, 1f, 0.6f),
            requireNotNull(moveRedactionWithinBounds(original, 1f, 0f)),
        )
        assertBounds(
            RedactionBounds(0.2f, 0f, 0.5f, 0.3f),
            requireNotNull(moveRedactionWithinBounds(original, 0f, -1f)),
        )
        assertBounds(
            RedactionBounds(0.2f, 0.7f, 0.5f, 1f),
            requireNotNull(moveRedactionWithinBounds(original, 0f, 1f)),
        )
    }

    @Test
    fun hitTestingMovesOnlyTouchedRegionAndSelectedWinsOverlap() {
        val first = region("first", RedactionBounds(0.1f, 0.1f, 0.5f, 0.5f), order = 0)
        val second = region("second", RedactionBounds(0.3f, 0.3f, 0.7f, 0.7f), order = 1)
        val geometry = redactionPreviewGeometry(1_000f, 1_000f, 1_000, 1_000)

        val selectedHit = findRedactionPreviewHit(
            listOf(first, second), first.id, DocumentPageRotation.DEGREES_0,
            Offset(0.4f, 0.4f), geometry, minimumTargetPx = 48f,
        )
        assertEquals(first.id, selectedHit?.id)

        val topmostHit = findRedactionPreviewHit(
            listOf(first, second), null, DocumentPageRotation.DEGREES_0,
            Offset(0.4f, 0.4f), geometry, minimumTargetPx = 48f,
        )
        assertEquals(second.id, topmostHit?.id)
    }

    @Test
    fun smallRegionGetsInvisibleTouchTargetWithoutChangingGeometry() {
        val small = region("small", RedactionBounds(0.49f, 0.49f, 0.51f, 0.51f), order = 0)
        val geometry = redactionPreviewGeometry(1_000f, 1_000f, 1_000, 1_000)
        val hit = findRedactionPreviewHit(
            listOf(small), null, DocumentPageRotation.DEGREES_0,
            Offset(0.52f, 0.5f), geometry, minimumTargetPx = 48f,
        )

        assertNotNull(hit)
        assertBounds(small.bounds, requireNotNull(hit).displayBounds)
    }

    @Test
    fun arrowNudgeUsesTheSameRotationAndBoundsRulesAsDirectDrag() {
        val original = RedactionBounds(0.2f, 0.25f, 0.45f, 0.55f)
        val moved = requireNotNull(
            moveRedactionInDisplaySpace(
                original,
                DocumentPageRotation.DEGREES_90,
                0.025f,
                0f,
            ),
        )
        val beforeDisplay = RedactionCoordinateMapper.rotate(
            original,
            DocumentPageRotation.DEGREES_90,
        )
        val afterDisplay = RedactionCoordinateMapper.rotate(
            moved,
            DocumentPageRotation.DEGREES_90,
        )

        assertNotEquals(original, moved)
        assertEquals(beforeDisplay.left + 0.025f, afterDisplay.left, EPSILON)
        assertEquals(beforeDisplay.top, afterDisplay.top, EPSILON)
    }

    private fun region(id: String, bounds: RedactionBounds, order: Int) = RedactionRegion(
        id = RedactionRegionId(id),
        coordinateSpace = RedactionCoordinateSpace.SOURCE_NORMALIZED,
        bounds = bounds,
        target = RedactionTarget(RedactionTargetKind.DOCUMENT_PAGE, "page"),
        confirmed = true,
        order = order,
    )

    private fun assertBounds(expected: RedactionBounds, actual: RedactionBounds) {
        assertEquals(expected.left, actual.left, EPSILON)
        assertEquals(expected.top, actual.top, EPSILON)
        assertEquals(expected.right, actual.right, EPSILON)
        assertEquals(expected.bottom, actual.bottom, EPSILON)
    }

    private companion object {
        const val EPSILON = 0.0001f
    }
}
