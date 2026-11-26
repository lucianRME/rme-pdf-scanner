package org.synapseworks.pageharbor.document.signature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignatureModelsTest {
    private val full = SignaturePlacement(0f, 0f, 1f, 1f)
    private val stroke = SignatureStroke(listOf(SignaturePoint(0f, 0f), SignaturePoint(1f, 1f)))

    @Test
    fun rejectsNonFiniteAndOutOfBoundsInk() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -0.001f, 1.001f).forEach { bad ->
            expectInvalid { SignaturePoint(bad, 0.5f) }
            expectInvalid { SignaturePoint(0.5f, bad) }
        }
    }

    @Test
    fun rejectsEmptyAndOversizedDrawings() {
        expectInvalid { SignatureStroke(emptyList()) }
        expectInvalid { SignatureStroke(List(MAX_SIGNATURE_POINTS_PER_STROKE + 1) { SignaturePoint(0f, 0f) }) }
        expectInvalid { HandwrittenSignature(emptyList()) }
        expectInvalid { HandwrittenSignature(List(MAX_SIGNATURE_STROKES + 1) { stroke }) }
        val longStroke = SignatureStroke(List(MAX_SIGNATURE_POINTS_PER_STROKE) { SignaturePoint(0f, 0f) })
        expectInvalid { HandwrittenSignature(List(5) { longStroke }) }
        expectInvalid { HandwrittenSignature(listOf(stroke), Float.NaN) }
        expectInvalid { HandwrittenSignature(listOf(stroke), 0f) }
    }

    @Test
    fun rejectsInvalidPlacementAndPageGeometry() {
        expectInvalid { SignaturePlacement(0.5f, 0f, 0.5f, 1f) }
        expectInvalid { SignaturePlacement(0f, 1f, 1f, 0f) }
        expectInvalid { SignaturePlacement(0f, 0f, Float.POSITIVE_INFINITY, 1f) }
        expectInvalid { SignaturePageGeometry(0f, 0f, 0f, 100f, 0) }
        expectInvalid { SignaturePageGeometry(0f, 0f, 100f, 100f, 45) }
        expectInvalid { SignaturePdfRequest(-1, HandwrittenSignature(listOf(stroke)), full) }
    }

    @Test
    fun unrotatedPlacementUsesVisualTopLeftAndCropOrigin() {
        val geometry = SignaturePageGeometry(10f, 20f, 200f, 400f, 0)
        assertPoint(10f, 420f, geometry.map(SignaturePoint(0f, 0f), full))
        assertPoint(210f, 20f, geometry.map(SignaturePoint(1f, 1f), full))
        assertPoint(60f, 320f, geometry.map(SignaturePoint(0f, 0f), SignaturePlacement(.25f, .25f, .75f, .5f)))
    }

    @Test
    fun allQuarterTurnsMapTheDisplayedCornersCorrectly() {
        val cases = listOf(
            Triple(0, SignaturePdfPoint(10f, 420f), SignaturePdfPoint(210f, 20f)),
            Triple(90, SignaturePdfPoint(10f, 20f), SignaturePdfPoint(210f, 420f)),
            Triple(180, SignaturePdfPoint(210f, 20f), SignaturePdfPoint(10f, 420f)),
            Triple(270, SignaturePdfPoint(210f, 420f), SignaturePdfPoint(10f, 20f)),
            Triple(-90, SignaturePdfPoint(210f, 420f), SignaturePdfPoint(10f, 20f)),
            Triple(450, SignaturePdfPoint(10f, 20f), SignaturePdfPoint(210f, 420f)),
        )
        cases.forEach { (rotation, first, last) ->
            val geometry = SignaturePageGeometry(10f, 20f, 200f, 400f, rotation)
            assertEquals(first, geometry.map(SignaturePoint(0f, 0f), full))
            assertEquals(last, geometry.map(SignaturePoint(1f, 1f), full))
        }
    }

    @Test
    fun placementCornersStayInsideEveryRotatedCrop() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val geometry = SignaturePageGeometry(-50f, -100f, 800f, 600f, rotation)
            val placement = SignaturePlacement(.2f, .6f, .9f, .85f)
            for (x in listOf(0f, .5f, 1f)) for (y in listOf(0f, .5f, 1f)) {
                val point = geometry.map(SignaturePoint(x, y), placement)
                assertTrue(point.x in -50f..750f)
                assertTrue(point.y in -100f..500f)
            }
        }
    }

    @Test
    fun penWidthTracksDisplayedPlacementWidthAfterRotation() {
        val signature = HandwrittenSignature(listOf(stroke), .01f)
        val placement = SignaturePlacement(.25f, .25f, .75f, .75f)
        assertEquals(1f, SignaturePageGeometry(0f, 0f, 200f, 400f, 0).strokeWidth(signature, placement), .0001f)
        assertEquals(2f, SignaturePageGeometry(0f, 0f, 200f, 400f, 90).strokeWidth(signature, placement), .0001f)
    }

    private fun assertPoint(x: Float, y: Float, actual: SignaturePdfPoint) {
        assertEquals(x, actual.x, .0001f)
        assertEquals(y, actual.y, .0001f)
    }

    private fun expectInvalid(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected invalid signature input to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected rejection.
        }
    }
}
