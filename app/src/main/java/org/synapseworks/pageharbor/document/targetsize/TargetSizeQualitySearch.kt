package org.synapseworks.pageharbor.document.targetsize

import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Deterministic, bounded controller used by calibration and the single allowed refinement. */
object TargetSizeQualitySearch {
    const val MAXIMUM_TRIALS_PER_CALIBRATION_PAGE = 3

    fun calibrated(
        plan: TargetSizePagePlan,
        actualBytes: Long,
    ): TargetSizePagePlan {
        require(actualBytes > 0L)
        if (actualBytes <= plan.allocatedBytes || plan.allocatedBytes <= 0L) return plan
        return reduce(
            plan = plan,
            byteRatio = plan.allocatedBytes.toDouble() / actualBytes.toDouble(),
            qualityStep = 4,
        )
    }

    fun refineDocument(
        pages: List<TargetSizePagePlan>,
        actualBytes: Long,
        targetBytes: Long,
        mandatoryOverheadBytes: Long,
    ): List<TargetSizePagePlan> {
        require(actualBytes > 0L && targetBytes > 0L)
        require(mandatoryOverheadBytes >= 0L)
        if (actualBytes <= targetBytes) return pages
        // Only one whole-document refinement is allowed. Writing the floor candidate makes a
        // subsequent over-target result authoritative rather than merely another estimate.
        return pages.map { plan ->
            plan.copy(
                targetWidthPx = plan.minimumWidthPx,
                targetHeightPx = plan.minimumHeightPx,
                jpegQuality = plan.minimumJpegQuality,
            )
        }
    }

    fun isAtFloor(plan: TargetSizePagePlan): Boolean =
        plan.targetWidthPx == plan.minimumWidthPx &&
            plan.targetHeightPx == plan.minimumHeightPx &&
            plan.jpegQuality == plan.minimumJpegQuality

    fun representativePageIndices(pages: List<TargetSizePageProfile>): List<Int> {
        if (pages.size <= MAXIMUM_REPRESENTATIVE_PAGES) return pages.indices.toList()
        val mostDemanding = pages.indices.maxWithOrNull(
            compareBy<Int> { pages[it].contentProfile.budgetWeightPermille }
                .thenBy { pages[it].pixelCount }
                .thenByDescending { it },
        ) ?: 0
        return listOf(mostDemanding, 0, pages.lastIndex)
            .distinct()
            .take(MAXIMUM_REPRESENTATIVE_PAGES)
    }

    private fun reduce(
        plan: TargetSizePagePlan,
        byteRatio: Double,
        qualityStep: Int,
    ): TargetSizePagePlan {
        val dimensionScale = sqrt(byteRatio).coerceIn(MINIMUM_SINGLE_STEP_SCALE, 1.0)
        val width = (plan.targetWidthPx * dimensionScale).roundToInt()
            .coerceIn(plan.minimumWidthPx, plan.targetWidthPx)
        val height = (plan.targetHeightPx * dimensionScale).roundToInt()
            .coerceIn(plan.minimumHeightPx, plan.targetHeightPx)
        val qualityReduction = if (byteRatio < 0.8) qualityStep else maxOf(2, qualityStep / 2)
        val quality = (plan.jpegQuality - qualityReduction)
            .coerceAtLeast(plan.minimumJpegQuality)
        return plan.copy(
            targetWidthPx = width,
            targetHeightPx = height,
            jpegQuality = quality,
        )
    }

    private const val MAXIMUM_REPRESENTATIVE_PAGES = 3
    private const val MINIMUM_SINGLE_STEP_SCALE = 0.5
}

fun isTargetSizeReached(finalBytes: Long, targetBytes: Long): Boolean {
    require(finalBytes >= 0L)
    require(targetBytes > 0L)
    return finalBytes <= targetBytes
}
