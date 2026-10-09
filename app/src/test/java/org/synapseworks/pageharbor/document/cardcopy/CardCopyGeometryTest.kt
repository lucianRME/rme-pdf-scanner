package org.synapseworks.pageharbor.document.cardcopy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.synapseworks.pageharbor.document.session.DocumentPageRotation
import org.synapseworks.pageharbor.document.redaction.RedactionBounds

class CardCopyGeometryTest {
    private val cardWidth = 1_012
    private val cardHeight = 638

    @Test
    fun a4UsesExactPhysicalPdfGeometry() {
        assertEquals(595.2756f, CardCopyPageSize.A4.widthPoints, 0.0001f)
        assertEquals(841.8898f, CardCopyPageSize.A4.heightPoints, 0.0001f)
    }

    @Test
    fun letterUsesExactPhysicalPdfGeometry() {
        assertEquals(612f, CardCopyPageSize.LETTER.widthPoints, 0f)
        assertEquals(792f, CardCopyPageSize.LETTER.heightPoints, 0f)
    }

    @Test
    fun autoIsDeterministicAndIndependentOfScreenGeometry() {
        val settings = CardCopySettings(layout = CardCopyLayout.AUTO)
        val first = geometry(settings = settings)

        repeat(20) { assertEquals(first, geometry(settings = settings)) }
        assertEquals(CardCopyLayout.VERTICAL, first.resolvedLayout)
    }

    @Test
    fun verticalPlacesFrontAboveBackWithRequestedSpacing() {
        val result = geometry(
            settings = CardCopySettings(
                layout = CardCopyLayout.VERTICAL,
                spacing = CardCopySpacing.WIDE,
            ),
        )

        assertTrue(result.front.bounds.bottom <= result.back.bounds.top)
        assertTrue(result.back.bounds.top - result.front.bounds.bottom >= CardCopySpacing.WIDE.points)
        assertFalse(result.isClipped())
    }

    @Test
    fun horizontalPlacesFrontBesideBackWithRequestedSpacing() {
        val result = geometry(
            settings = CardCopySettings(
                pageSize = CardCopyPageSize.LETTER,
                layout = CardCopyLayout.HORIZONTAL,
                spacing = CardCopySpacing.COMPACT,
            ),
        )

        assertTrue(result.front.bounds.right <= result.back.bounds.left)
        assertTrue(result.back.bounds.left - result.front.bounds.right >= CardCopySpacing.COMPACT.points)
        assertFalse(result.isClipped())
    }

    @Test
    fun preservesAspectRatiosForMatchingAndDifferentCrops() {
        assertTrue(geometry().aspectRatiosPreserved())
        assertTrue(
            geometry(
                frontWidth = 900,
                frontHeight = 1_400,
                backWidth = 1_700,
                backHeight = 700,
            ).aspectRatiosPreserved(),
        )
    }

    @Test
    fun matchingSidesUseSameDisplayedDimensions() {
        val result = geometry(settings = CardCopySettings(layout = CardCopyLayout.VERTICAL))

        assertEquals(result.front.bounds.width, result.back.bounds.width, 0.001f)
        assertEquals(result.front.bounds.height, result.back.bounds.height, 0.001f)
    }

    @Test
    fun allSpacingOptionsRemainInsideMargins() {
        CardCopyPageSize.entries.forEach { page ->
            CardCopyLayout.entries.forEach { layout ->
                CardCopySpacing.entries.forEach { spacing ->
                    val result = geometry(
                        settings = CardCopySettings(pageSize = page, layout = layout, spacing = spacing),
                    )
                    assertEquals(CardCopyGeometryEngine.SAFE_MARGIN_POINTS, result.marginPoints, 0f)
                    assertEquals(spacing.points, result.spacingPoints, 0f)
                    assertFalse("$page $layout $spacing", result.isClipped())
                }
            }
        }
    }

    @Test
    fun verticalAlignmentMovesCardsLeftCenterRight() {
        val starts = CardCopyAlignment.entries.map { alignment ->
            geometry(
                frontWidth = 700,
                frontHeight = 1_000,
                settings = CardCopySettings(
                    layout = CardCopyLayout.VERTICAL,
                    alignment = alignment,
                ),
            ).front.bounds.left
        }

        assertTrue(starts[0] < starts[1])
        assertTrue(starts[1] < starts[2])
    }

    @Test
    fun horizontalAlignmentMovesCardsTopCenterBottom() {
        val tops = CardCopyAlignment.entries.map { alignment ->
            geometry(
                frontWidth = 1_000,
                frontHeight = 400,
                settings = CardCopySettings(
                    layout = CardCopyLayout.HORIZONTAL,
                    alignment = alignment,
                ),
            ).front.bounds.top
        }

        assertTrue(tops[0] < tops[1])
        assertTrue(tops[1] < tops[2])
    }

    @Test
    fun quarterTurnsSwapEffectiveAspectRatioWithoutClipping() {
        listOf(
            DocumentPageRotation.DEGREES_90,
            DocumentPageRotation.DEGREES_270,
        ).forEach { rotation ->
            val result = geometry(frontRotation = rotation, backRotation = rotation)
            assertTrue(result.aspectRatiosPreserved())
            assertFalse(result.isClipped())
        }
    }

    @Test
    fun sourceRegionMapsToCompositionCoordinatesForEveryRotation() {
        val region = CardCopyNormalizedRegion(0.1f, 0.2f, 0.4f, 0.6f)
        DocumentPageRotation.entries.forEach { rotation ->
            val placement = geometry(frontRotation = rotation).front
            val mapped = placement.mapSourceRegion(region)
            assertTrue(mapped.left >= placement.bounds.left)
            assertTrue(mapped.top >= placement.bounds.top)
            assertTrue(mapped.right <= placement.bounds.right)
            assertTrue(mapped.bottom <= placement.bounds.bottom)
        }
    }

    @Test
    fun fullSourceRegionMapsExactlyToPlacedCard() {
        val placement = geometry(frontRotation = DocumentPageRotation.DEGREES_270).front

        assertEquals(
            placement.bounds,
            placement.mapSourceRegion(CardCopyNormalizedRegion(0f, 0f, 1f, 1f)),
        )
    }

    @Test
    fun securePreviewCoverageUsesTheSameOutwardRasterMarginAsExport() {
        val region = RedactionBounds(0.2f, 0.25f, 0.7f, 0.55f)
        DocumentPageRotation.entries.forEach { rotation ->
            val placement = geometry(frontRotation = rotation).front
            val requested = placement.mapSourceRegion(region)
            val covered = requireNotNull(placement.mapSecureRasterCoverage(region))
            assertTrue(covered.left <= requested.left)
            assertTrue(covered.top <= requested.top)
            assertTrue(covered.right >= requested.right)
            assertTrue(covered.bottom >= requested.bottom)
            assertTrue(covered.left >= placement.bounds.left)
            assertTrue(covered.top >= placement.bounds.top)
            assertTrue(covered.right <= placement.bounds.right)
            assertTrue(covered.bottom <= placement.bounds.bottom)
        }
    }

    @Test
    fun compositionLayerOrderLeavesExplicitSecuritySeams() {
        assertEquals(
            listOf(
                CardCopyCompositionLayer.WHITE_BACKGROUND,
                CardCopyCompositionLayer.CARD_SIDES,
                CardCopyCompositionLayer.FUTURE_REDACTIONS,
                CardCopyCompositionLayer.FUTURE_WATERMARK,
                CardCopyCompositionLayer.FLATTENED_OUTPUT,
            ),
            geometry().layers,
        )
    }

    @Test
    fun filenameUsesSafeFallbackAndSanitizesTitles() {
        assertEquals("card-copy.pdf", cardCopyFilename())
        assertEquals("My Card-front-back.pdf", cardCopyFilename("My Card.pdf"))
        assertEquals("ID-front-back.pdf", cardCopyFilename("ID/名?:"))
    }

    private fun geometry(
        frontWidth: Int = cardWidth,
        frontHeight: Int = cardHeight,
        frontRotation: DocumentPageRotation = DocumentPageRotation.DEGREES_0,
        backWidth: Int = cardWidth,
        backHeight: Int = cardHeight,
        backRotation: DocumentPageRotation = DocumentPageRotation.DEGREES_0,
        settings: CardCopySettings = CardCopySettings(),
    ): CardCopyGeometry = CardCopyGeometryEngine.calculate(
        frontWidth = frontWidth,
        frontHeight = frontHeight,
        frontRotation = frontRotation,
        backWidth = backWidth,
        backHeight = backHeight,
        backRotation = backRotation,
        settings = settings,
    )
}
