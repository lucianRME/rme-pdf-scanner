package org.synapseworks.pageharbor.document.targetsize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetSizePdfPlannerTest {
    private val planner = TargetSizePdfPlanner()
    private val supported = TargetSizeClassification(TargetSizeInputClass.IMAGE_DOCUMENT_SUPPORTED)

    @Test
    fun planningIsDeterministicAndAllocatesTheCompleteVisualBudget() {
        val pages = listOf(
            page(1, 2_400, 3_200, TargetSizePageContentProfile.TEXT_HEAVY),
            page(2, 3_200, 2_400, TargetSizePageContentProfile.PHOTO_HEAVY),
            page(3, 2_000, 2_000, TargetSizePageContentProfile.GRAYSCALE),
        )

        val first = planner.plan(TargetSizeBytes.MB_2, supported, pages)
        val second = planner.plan(TargetSizeBytes.MB_2, supported, pages)

        assertEquals(first, second)
        assertEquals(
            first.targetBytes - first.mandatoryPdfAndTextOverheadBytes,
            first.pages.sumOf(TargetSizePagePlan::allocatedBytes),
        )
        assertTrue(first.pages[1].allocatedBytes > first.pages[0].allocatedBytes)
        assertTrue(first.pages[0].allocatedBytes > first.pages[2].allocatedBytes)
        assertTrue(first.pages.all { it.targetWidthPx <= pages.firstOrNull { page -> page.pageId == it.pageId }!!.widthPx })
        assertTrue(first.pages.all { it.targetHeightPx <= pages.first { page -> page.pageId == it.pageId }.heightPx })
    }

    @Test
    fun tinyTextUsesTheConservativeFloorAndNeverUpscalesSmallSources() {
        val large = planner.plan(
            TargetSizeBytes.KB_500,
            supported,
            listOf(page(1, 2_400, 3_200, TargetSizePageContentProfile.TINY_TEXT)),
        ).pages.single()
        val small = planner.plan(
            TargetSizeBytes.KB_500,
            supported,
            listOf(page(2, 600, 800, TargetSizePageContentProfile.TINY_TEXT)),
        )

        assertEquals(1_200, minOf(large.minimumWidthPx, large.minimumHeightPx))
        assertEquals(65, large.minimumJpegQuality)
        assertEquals(600, small.pages.single().targetWidthPx)
        assertEquals(800, small.pages.single().targetHeightPx)
        assertTrue(TargetSizeWarning.SOURCE_RESOLUTION_BELOW_QUALITY_FLOOR in small.warnings)
    }

    @Test
    fun impossibleHundredPageTargetFailsBeforeEncoding() {
        val pages = List(100) { index ->
            page(index.toLong(), 1_200, 1_600, TargetSizePageContentProfile.TEXT_HEAVY)
        }

        val plan = planner.plan(TargetSizeBytes.KB_200, supported, pages)

        assertFalse(plan.isFeasibleAtQualityFloor)
        assertTrue(plan.estimatedMinimumAcceptableBytes > TargetSizeBytes.KB_200)
        assertEquals(0, plan.estimatedFullDocumentPasses)
    }

    @Test
    fun positionedTextIsBudgetedAndUnpositionedTextIsReported() {
        val pages = listOf(
            page(
                id = 1,
                width = 1_200,
                height = 1_600,
                profile = TargetSizePageContentProfile.TEXT_HEAVY,
                textStrategy = TargetSizeTextStrategy.PRESERVE_POSITIONED_UNICODE,
                characters = 400,
            ),
            page(
                id = 2,
                width = 1_200,
                height = 1_600,
                profile = TargetSizePageContentProfile.TEXT_HEAVY,
                textStrategy = TargetSizeTextStrategy.OMIT_UNPOSITIONED_WITH_WARNING,
            ),
        )

        val plan = planner.plan(TargetSizeBytes.MB_1, supported, pages)

        assertEquals(TargetSizeTextStrategy.PRESERVE_POSITIONED_UNICODE, plan.pages[0].textStrategy)
        assertEquals(TargetSizeTextStrategy.OMIT_UNPOSITIONED_WITH_WARNING, plan.pages[1].textStrategy)
        assertTrue(
            TargetSizeWarning.SEARCHABLE_TEXT_OMITTED_UNTRUSTWORTHY_GEOMETRY in plan.warnings,
        )
    }

    private fun page(
        id: Long,
        width: Int,
        height: Int,
        profile: TargetSizePageContentProfile,
        textStrategy: TargetSizeTextStrategy = TargetSizeTextStrategy.NONE,
        characters: Int = 0,
    ) = TargetSizePageProfile(
        pageId = id,
        widthPx = width,
        heightPx = height,
        sourceBytes = null,
        contentProfile = profile,
        textStrategy = textStrategy,
        searchableCharacterCount = characters,
    )
}
