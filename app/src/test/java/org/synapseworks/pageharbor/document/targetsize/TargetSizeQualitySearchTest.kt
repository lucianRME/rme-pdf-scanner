package org.synapseworks.pageharbor.document.targetsize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetSizeQualitySearchTest {
    @Test
    fun calibrationReducesQualityAndDimensionsWithoutCrossingFloor() {
        val plan = pagePlan()

        val reduced = TargetSizeQualitySearch.calibrated(plan, actualBytes = 400_000L)

        assertTrue(reduced.targetWidthPx < plan.targetWidthPx)
        assertTrue(reduced.targetHeightPx < plan.targetHeightPx)
        assertTrue(reduced.targetWidthPx >= plan.minimumWidthPx)
        assertTrue(reduced.targetHeightPx >= plan.minimumHeightPx)
        assertTrue(reduced.jpegQuality >= plan.minimumJpegQuality)
    }

    @Test
    fun oneWholeDocumentRefinementUsesTheExplicitAcceptableFloor() {
        val plan = pagePlan()

        val refined = TargetSizeQualitySearch.refineDocument(
            pages = listOf(plan),
            actualBytes = 700_000L,
            targetBytes = 500_000L,
            mandatoryOverheadBytes = 20_000L,
        ).single()

        assertEquals(plan.minimumWidthPx, refined.targetWidthPx)
        assertEquals(plan.minimumHeightPx, refined.targetHeightPx)
        assertEquals(plan.minimumJpegQuality, refined.jpegQuality)
        assertTrue(TargetSizeQualitySearch.isAtFloor(refined))
    }

    @Test
    fun representativeSelectionIsBoundedAndStable() {
        val profiles = List(50) { index ->
            TargetSizePageProfile(
                pageId = index.toLong(),
                widthPx = if (index == 20) 4_000 else 1_200,
                heightPx = if (index == 20) 3_000 else 1_600,
                sourceBytes = null,
                contentProfile = if (index == 20) {
                    TargetSizePageContentProfile.PHOTO_HEAVY
                } else {
                    TargetSizePageContentProfile.TEXT_HEAVY
                },
                textStrategy = TargetSizeTextStrategy.NONE,
            )
        }

        assertEquals(listOf(20, 0, 49), TargetSizeQualitySearch.representativePageIndices(profiles))
    }

    private fun pagePlan() = TargetSizePagePlan(
        pageId = 1,
        targetWidthPx = 2_000,
        targetHeightPx = 3_000,
        minimumWidthPx = 960,
        minimumHeightPx = 1_440,
        jpegQuality = 86,
        minimumJpegQuality = 55,
        allocatedBytes = 200_000L,
        textStrategy = TargetSizeTextStrategy.NONE,
    )
}
