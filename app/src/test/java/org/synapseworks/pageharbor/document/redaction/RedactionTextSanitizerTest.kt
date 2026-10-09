package org.synapseworks.pageharbor.document.redaction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.synapseworks.pageharbor.document.session.DocumentPageRotation
import org.synapseworks.pageharbor.ocr.OcrPageLayout
import org.synapseworks.pageharbor.ocr.OcrPageResult
import org.synapseworks.pageharbor.ocr.OcrTextBounds
import org.synapseworks.pageharbor.ocr.OcrTextElement
import org.synapseworks.pageharbor.ocr.OcrTextLine
import org.synapseworks.pageharbor.ocr.OcrTextPlacementMode

class RedactionTextSanitizerTest {
    @Test
    fun noRelevantRedactionPreservesExistingBehavior() {
        val page = page(line("Visible", 100f, 100f, 300f, 180f))

        val result = RedactionTextSanitizer.sanitize(page, emptyList(), PAGE_ID)

        assertTrue(result is RedactionTextResult.Preserved)
        assertSame(page, (result as RedactionTextResult.Preserved).page)
    }

    @Test
    fun rotationMapsSourceRegionIntoTheUprightOcrCoordinateSystem() {
        val sourceRegion = RedactionBounds(0.1f, 0.2f, 0.3f, 0.4f)
        val expectedByRotation = mapOf(
            DocumentPageRotation.DEGREES_0 to OcrTextBounds(110f, 210f, 290f, 390f),
            DocumentPageRotation.DEGREES_90 to OcrTextBounds(610f, 110f, 790f, 290f),
            DocumentPageRotation.DEGREES_180 to OcrTextBounds(710f, 610f, 890f, 790f),
            DocumentPageRotation.DEGREES_270 to OcrTextBounds(210f, 710f, 390f, 890f),
        )

        expectedByRotation.forEach { (rotation, bounds) ->
            val page = page(OcrTextLine("RME_SECRET", bounds))
            val result = RedactionTextSanitizer.sanitize(
                page = page,
                regions = listOf(region(sourceRegion)),
                sourceTargetId = PAGE_ID,
                rotation = rotation,
            )

            assertTrue("rotation=${rotation.degrees}", result is RedactionTextResult.Sanitized)
            assertNull((result as RedactionTextResult.Sanitized).page)
            assertEquals(1, result.removedUnits)
        }
    }

    @Test
    fun usesElementGeometryOnlyWhenItIsTrustworthy() {
        val secret = OcrTextElement("SECRET", OcrTextBounds(100f, 100f, 300f, 180f))
        val safe = OcrTextElement("SAFE", OcrTextBounds(500f, 100f, 700f, 180f))
        val page = page(
            OcrTextLine(
                text = "SECRET SAFE",
                bounds = OcrTextBounds(100f, 100f, 700f, 180f),
                elements = listOf(secret, safe),
            ),
        )

        val result = RedactionTextSanitizer.sanitize(
            page,
            listOf(region(RedactionBounds(0.09f, 0.09f, 0.31f, 0.19f))),
            PAGE_ID,
        ) as RedactionTextResult.Sanitized

        assertEquals("SAFE", result.page?.text)
        assertEquals(listOf("SAFE"), result.page?.layout?.lines?.map(OcrTextLine::text))
        assertTrue(result.page?.layout?.blocks?.isEmpty() == true)
        assertEquals(1, result.removedUnits)
    }

    @Test
    fun missingElementGeometryFallsBackToRemovingTheIntersectingLine() {
        val page = page(
            OcrTextLine(
                text = "SECRET SAFE",
                bounds = OcrTextBounds(100f, 100f, 700f, 180f),
                elements = listOf(OcrTextElement("SECRET", null), OcrTextElement("SAFE", null)),
            ),
            line("Other", 100f, 400f, 300f, 480f),
        )

        val result = RedactionTextSanitizer.sanitize(
            page,
            listOf(region(RedactionBounds(0.09f, 0.09f, 0.31f, 0.19f))),
            PAGE_ID,
        ) as RedactionTextResult.Sanitized

        assertEquals("Other", result.page?.text)
        assertEquals(1, result.removedUnits)
    }

    @Test
    fun inconsistentOrOutOfLineElementGeometryOmitsTheWholePageLayer() {
        val inconsistentText = page(
            OcrTextLine(
                text = "SECRET SAFE",
                bounds = OcrTextBounds(100f, 100f, 700f, 180f),
                elements = listOf(
                    OcrTextElement("SAFE", OcrTextBounds(500f, 100f, 700f, 180f)),
                ),
            ),
        )
        val escapedBounds = page(
            OcrTextLine(
                text = "SECRET SAFE",
                bounds = OcrTextBounds(100f, 100f, 700f, 180f),
                elements = listOf(
                    OcrTextElement("SECRET", OcrTextBounds(800f, 100f, 950f, 180f)),
                    OcrTextElement("SAFE", OcrTextBounds(500f, 100f, 700f, 180f)),
                ),
            ),
        )
        val redactions = listOf(region(RedactionBounds(0.09f, 0.09f, 0.31f, 0.19f)))

        assertEquals(
            RedactionTextResult.OmittedUnsafeGeometry,
            RedactionTextSanitizer.sanitize(inconsistentText, redactions, PAGE_ID),
        )
        assertEquals(
            RedactionTextResult.OmittedUnsafeGeometry,
            RedactionTextSanitizer.sanitize(escapedBounds, redactions, PAGE_ID),
        )
    }

    @Test
    fun anyNonblankLineWithoutValidGeometryOmitsTheWholePageLayer() {
        val missing = page(
            OcrTextLine("Unpositioned secret", null),
            line("Apparently safe", 100f, 400f, 300f, 480f),
        )
        val outside = page(
            OcrTextLine("Invalid secret", OcrTextBounds(-1f, 20f, 200f, 80f)),
            line("Apparently safe", 100f, 400f, 300f, 480f),
        )
        val redactions = listOf(region(RedactionBounds(0.1f, 0.1f, 0.2f, 0.2f)))

        assertEquals(
            RedactionTextResult.OmittedUnsafeGeometry,
            RedactionTextSanitizer.sanitize(missing, redactions, PAGE_ID),
        )
        assertEquals(
            RedactionTextResult.OmittedUnsafeGeometry,
            RedactionTextSanitizer.sanitize(outside, redactions, PAGE_ID),
        )
    }

    @Test
    fun freeformAndCorrectedTextWithoutGeometryAreOmittedForARedactedPage() {
        val freeform = page(
            line("Authoritative corrected secret", 50f, 50f, 950f, 950f),
            placementMode = OcrTextPlacementMode.FREEFORM_PAGE_REGION,
        )
        val correctedOnly = OcrPageResult(
            pageIndex = 0,
            text = "Authoritative corrected secret",
            layout = null,
        )
        val redactions = listOf(region(RedactionBounds(0.1f, 0.1f, 0.2f, 0.2f)))

        assertEquals(
            RedactionTextResult.OmittedUnsafeGeometry,
            RedactionTextSanitizer.sanitize(freeform, redactions, PAGE_ID),
        )
        assertEquals(
            RedactionTextResult.OmittedUnsafeGeometry,
            RedactionTextSanitizer.sanitize(correctedOnly, redactions, PAGE_ID),
        )
    }

    @Test
    fun transformMismatchOmitsTheWholeTextLayer() {
        val result = RedactionTextSanitizer.sanitize(
            page = page(line("SECRET", 100f, 100f, 300f, 180f)),
            regions = listOf(region(RedactionBounds(0.1f, 0.1f, 0.3f, 0.2f))),
            sourceTargetId = PAGE_ID,
            geometryMatchesEffectiveSource = false,
        )

        assertEquals(RedactionTextResult.OmittedUnsafeGeometry, result)
    }

    @Test
    fun fullPageRedactionRemovesAllSearchableText() {
        val result = RedactionTextSanitizer.sanitize(
            page(line("SECRET", 100f, 100f, 300f, 180f)),
            listOf(region(RedactionBounds.FullPage)),
            PAGE_ID,
        ) as RedactionTextResult.Sanitized

        assertNull(result.page)
    }

    @Test
    fun searchableRemovalIncludesTheFinalJpegSafetyMargin() {
        val result = RedactionTextSanitizer.sanitize(
            page = page(line("Adjacent secret", 305f, 100f, 500f, 180f)),
            regions = listOf(region(RedactionBounds(0.1f, 0.1f, 0.3f, 0.2f))),
            sourceTargetId = PAGE_ID,
            rasterWidthPx = 1_000,
            rasterHeightPx = 1_000,
        ) as RedactionTextResult.Sanitized

        assertNull(result.page)
        assertEquals(1, result.removedUnits)
    }

    @Test
    fun unconfirmedOrUntypedRegionFailsClosed() {
        val invalid = region(RedactionBounds(0.1f, 0.1f, 0.2f, 0.2f)).copy(confirmed = false)

        assertEquals(
            RedactionTextResult.InvalidMapping,
            RedactionTextSanitizer.sanitize(
                page(line("SECRET", 100f, 100f, 300f, 180f)),
                listOf(invalid),
                PAGE_ID,
            ),
        )
    }

    private fun page(
        vararg lines: OcrTextLine,
        placementMode: OcrTextPlacementMode = OcrTextPlacementMode.POSITIONED_LINES,
    ) = OcrPageResult(
        pageIndex = 0,
        text = lines.joinToString("\n", transform = OcrTextLine::text),
        layout = OcrPageLayout(
            imageWidthPx = 1_000,
            imageHeightPx = 1_000,
            lines = lines.toList(),
            placementMode = placementMode,
        ),
    )

    private fun line(text: String, left: Float, top: Float, right: Float, bottom: Float) =
        OcrTextLine(text, OcrTextBounds(left, top, right, bottom))

    private fun region(bounds: RedactionBounds) = RedactionRegion(
        id = RedactionRegionId("region-1"),
        coordinateSpace = RedactionCoordinateSpace.SOURCE_NORMALIZED,
        bounds = bounds,
        target = RedactionTarget(RedactionTargetKind.DOCUMENT_PAGE, PAGE_ID),
        confirmed = true,
        order = 0,
    )

    private companion object {
        const val PAGE_ID = "page-1"
    }
}
