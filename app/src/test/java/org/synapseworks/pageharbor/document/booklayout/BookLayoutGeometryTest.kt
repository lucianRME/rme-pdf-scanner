package org.synapseworks.pageharbor.document.booklayout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookLayoutGeometryTest {
    @Test
    fun cropAndQuarterTurnsMapTheDisplayedPageToTheOrigin() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val source = BookSourcePage(37f, -19f, 400f, 180f, rotation, userUnit = 2f)
            val matrix = source.displayTransform()
            val corners = listOf(
                matrix.map(37f, -19f), matrix.map(437f, -19f),
                matrix.map(37f, 161f), matrix.map(437f, 161f),
            )
            assertEquals(0f, corners.minOf { it.x }, 0.0001f)
            assertEquals(0f, corners.minOf { it.y }, 0.0001f)
            assertEquals(source.displayWidth, corners.maxOf { it.x }, 0.0001f)
            assertEquals(source.displayHeight, corners.maxOf { it.y }, 0.0001f)
        }
    }

    @Test
    fun clockwiseQuarterTurnPreservesVisualOrientation() {
        val matrix = BookSourcePage(10f, 20f, 200f, 100f, 90).displayTransform()
        assertEquals(BookPoint(0f, 200f), matrix.map(10f, 20f))
        assertEquals(BookPoint(100f, 0f), matrix.map(210f, 120f))
    }

    @Test
    fun asymmetricGutterAndReadingOrderKeepBothHalvesWithoutOverlap() {
        val page = BookSourcePage(0f, 0f, 1_000f, 700f)
        val leftFirst = BookLayoutGeometry.plan(listOf(page), BookLayoutSettings(splitFraction = 0.4f))
        assertEquals(2, leftFirst.size)
        assertEquals(400f, leftFirst[0].width, 0f)
        assertEquals(600f, leftFirst[1].width, 0f)
        assertEquals(BookPoint(0f, 0f), leftFirst[0].placements.single().transform.map(0f, 0f))
        assertEquals(BookPoint(0f, 0f), leftFirst[1].placements.single().transform.map(400f, 0f))
        val rightFirst = BookLayoutGeometry.plan(
            listOf(page), BookLayoutSettings(splitFraction = 0.4f, readingOrder = BookReadingOrder.RIGHT_TO_LEFT),
        )
        assertEquals(leftFirst.reversed(), rightFirst)
    }

    @Test
    fun splittingFollowsDisplayedWidthAfterRotation() {
        val source = BookSourcePage(50f, 20f, 200f, 100f, 90)
        val plan = BookLayoutGeometry.plan(listOf(source), BookLayoutSettings())
        assertEquals(50f, plan[0].width, 0f)
        assertEquals(200f, plan[0].height, 0f)
        assertEquals(BookPoint(0f, 0f), plan[1].placements.single().transform.map(250f, 70f))
    }

    @Test
    fun twoUpFitsDifferentAspectRatiosInsideNonoverlappingSlots() {
        val sources = listOf(BookSourcePage(20f, 30f, 2_000f, 400f), BookSourcePage(0f, 0f, 400f, 2_000f))
        for (paper in BookPaperSize.entries) {
            val sheet = BookLayoutGeometry.plan(sources, BookLayoutSettings(BookLayoutMode.TWO_UP, paperSize = paper)).single()
            assertEquals(paper.portraitHeightPoints, sheet.width, 0f)
            assertEquals(paper.portraitWidthPoints, sheet.height, 0f)
            val first = sheet.placements[0].clip
            val second = sheet.placements[1].clip
            assertEquals(12f, second.left - first.left - first.width, 0.001f)
            for (placement in sheet.placements) {
                val source = sources[placement.sourceIndex]
                val lowerLeft = placement.transform.map(source.left, source.bottom)
                val upperRight = placement.transform.map(source.left + source.width, source.bottom + source.height)
                assertTrue(lowerLeft.x >= placement.clip.left - 0.001f)
                assertTrue(lowerLeft.y >= placement.clip.bottom - 0.001f)
                assertTrue(upperRight.x <= placement.clip.left + placement.clip.width + 0.001f)
                assertTrue(upperRight.y <= placement.clip.bottom + placement.clip.height + 0.001f)
                val displayedRatio = (upperRight.x - lowerLeft.x) / (upperRight.y - lowerLeft.y)
                assertEquals(source.width / source.height, displayedRatio, 0.0001f)
            }
        }
    }

    @Test
    fun oddLastPageRetainsItsReadingOrderSlotAndAddsNoFakePage() {
        val pages = List(3) { BookSourcePage(0f, 0f, 300f, 400f) }
        val plan = BookLayoutGeometry.plan(pages, BookLayoutSettings(BookLayoutMode.TWO_UP, readingOrder = BookReadingOrder.RIGHT_TO_LEFT))
        assertEquals(2, plan.size)
        assertEquals(listOf(0, 1), plan[0].placements.map { it.sourceIndex })
        assertEquals(listOf(2), plan[1].placements.map { it.sourceIndex })
        assertTrue(plan[0].placements[0].clip.left > plan[0].placements[1].clip.left)
        assertEquals(plan[0].placements[0].clip.left, plan[1].placements.single().clip.left, 0f)
    }

    @Test
    fun unsafeSettingsAndSourceGeometryAreRejected() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f, 0f, 1f).forEach { invalid ->
            rejects { BookLayoutSettings(splitFraction = invalid) }
        }
        rejects { BookLayoutSettings(marginPoints = Float.NaN) }
        rejects { BookLayoutSettings(gapPoints = -1f) }
        rejects { BookSourcePage(0f, 0f, 0f, 100f) }
        rejects { BookSourcePage(0f, 0f, 100f, 100f, rotationDegrees = 45) }
        rejects { BookSourcePage(0f, 0f, 100f, 100f, userUnit = 0f) }
        rejects { BookSourcePage(Float.NaN, 0f, 100f, 100f) }
        rejects { BookSourcePage(0f, 0f, 15_000f, 100f) }
        rejects { BookSourcePage(Float.MAX_VALUE, 0f, 100f, 100f, userUnit = 2f) }
        rejects { BookLayoutGeometry.plan(listOf(BookSourcePage(0f, 0f, 1f, 100f)), BookLayoutSettings()) }
        rejects { BookLayoutGeometry.plan(emptyList(), BookLayoutSettings()) }
    }

    private fun rejects(block: () -> Unit) {
        var rejected = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }
}
