package org.synapseworks.pageharbor.document.redaction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.synapseworks.pageharbor.document.session.DocumentPageRotation

class RedactionCoordinateMapperTest {
    private val region = RedactionBounds(0.1f, 0.2f, 0.4f, 0.6f)

    @Test
    fun quarterTurnMappingsAreDeterministic() {
        assertBounds(region, RedactionCoordinateMapper.rotate(region, DocumentPageRotation.DEGREES_0))
        assertBounds(
            RedactionBounds(0.4f, 0.1f, 0.8f, 0.4f),
            RedactionCoordinateMapper.rotate(region, DocumentPageRotation.DEGREES_90),
        )
        assertBounds(
            RedactionBounds(0.6f, 0.4f, 0.9f, 0.8f),
            RedactionCoordinateMapper.rotate(region, DocumentPageRotation.DEGREES_180),
        )
        assertBounds(
            RedactionBounds(0.2f, 0.6f, 0.6f, 0.9f),
            RedactionCoordinateMapper.rotate(region, DocumentPageRotation.DEGREES_270),
        )
    }

    @Test
    fun everyRotationRoundTripsThroughInverse() {
        DocumentPageRotation.entries.forEach { rotation ->
            val mapped = RedactionCoordinateMapper.rotate(region, rotation)
            assertBounds(region, RedactionCoordinateMapper.inverseRotate(mapped, rotation))
        }
    }

    @Test
    fun cropRemappingClipsAndNormalizesConservatively() {
        val crop = RedactionBounds(0.2f, 0.1f, 0.8f, 0.9f)
        val mapped = requireNotNull(RedactionCoordinateMapper.remapIntoCrop(region, crop))
        assertBounds(
            RedactionBounds(0f, 0.125f, 1f / 3f, 0.625f),
            mapped,
        )
        assertNull(
            RedactionCoordinateMapper.remapIntoCrop(
                RedactionBounds(0f, 0f, 0.1f, 0.1f),
                RedactionBounds(0.2f, 0.2f, 1f, 1f),
            ),
        )
    }

    @Test
    fun pageMappingUsesTypedFinalCoordinates() {
        val mapped = RedactionCoordinateMapper.mapIntoPage(
            region,
            RedactionPageRect(100f, 200f, 500f, 700f),
        )
        assertEquals(140f, mapped.left, 0.0001f)
        assertEquals(300f, mapped.top, 0.0001f)
        assertEquals(260f, mapped.right, 0.0001f)
        assertEquals(500f, mapped.bottom, 0.0001f)
    }

    @Test
    fun rasterConversionNeverRoundsInwardAndAddsSafetyPixel() {
        val mapped = requireNotNull(
            RedactionCoordinateMapper.toRasterOutward(
                RedactionBounds(0.101f, 0.202f, 0.401f, 0.602f),
                width = 100,
                height = 100,
            ),
        )
        assertEquals(RedactionRasterRect(2, 12, 49, 69), mapped)
    }

    @Test
    fun edgeCornerAndFullPageMappingsAreClamped() {
        assertEquals(
            RedactionRasterRect(0, 0, 100, 50),
            RedactionCoordinateMapper.toRasterOutward(RedactionBounds.FullPage, 100, 50),
        )
        assertEquals(
            RedactionRasterRect(0, 0, 9, 9),
            RedactionCoordinateMapper.toRasterOutward(
                RedactionBounds(0f, 0f, 0.01f, 0.01f),
                100,
                100,
            ),
        )
    }

    @Test
    fun minimumRegionRejectsTapButAllowsSmallText() {
        assertNull(RedactionBounds.clamped(0.2f, 0.2f, 0.201f, 0.4f))
        assertNotNull(RedactionBounds.clamped(0.2f, 0.2f, 0.205f, 0.205f))
    }

    @Test
    fun conservativeIntersectionIncludesTouchingNestedAndOverlappingRegions() {
        val base = RedactionBounds(0.2f, 0.2f, 0.6f, 0.6f)
        assertTrue(base.intersects(RedactionBounds(0.6f, 0.3f, 0.8f, 0.4f)))
        assertTrue(base.intersects(RedactionBounds(0.3f, 0.3f, 0.4f, 0.4f)))
        assertTrue(base.intersects(RedactionBounds(0.5f, 0.5f, 0.8f, 0.8f)))
        assertFalse(base.intersects(RedactionBounds(0.7f, 0.7f, 0.9f, 0.9f)))
    }

    @Test
    fun sourceRegionSelectionFailsClosedAndSortsDeterministically() {
        val target = RedactionTarget(RedactionTargetKind.CARD_SOURCE, "source")
        val regions = listOf(
            RedactionRegion(
                RedactionRegionId("b"), RedactionCoordinateSpace.SOURCE_NORMALIZED,
                region, target, confirmed = true, order = 2,
            ),
            RedactionRegion(
                RedactionRegionId("a"), RedactionCoordinateSpace.SOURCE_NORMALIZED,
                region, target, confirmed = true, order = 1,
            ),
        )
        assertEquals(
            listOf("a", "b"),
            regions.confirmedSourceRegionsFor("source", RedactionTargetKind.CARD_SOURCE)
                ?.map { it.id.value },
        )
        assertNull(regions.map { it.copy(confirmed = false) }.confirmedSourceRegionsFor("source"))
        assertEquals(
            emptyList<RedactionRegion>(),
            regions.confirmedSourceRegionsFor("source", RedactionTargetKind.DOCUMENT_PAGE),
        )
    }

    private fun assertBounds(expected: RedactionBounds, actual: RedactionBounds) {
        assertEquals(expected.left, actual.left, 0.0001f)
        assertEquals(expected.top, actual.top, 0.0001f)
        assertEquals(expected.right, actual.right, 0.0001f)
        assertEquals(expected.bottom, actual.bottom, 0.0001f)
    }
}
