package org.synapseworks.pageharbor.document.targetsize

import java.math.BigInteger
import kotlin.math.roundToInt
import kotlin.math.sqrt

object TargetSizeInputClassifier {
    fun classify(facts: TargetSizeInputFacts): TargetSizeClassification {
        if (facts.pageCount <= 0 || facts.originalPdfSources.any { it.malformed || it.pageCount <= 0 }) {
            return TargetSizeClassification(TargetSizeInputClass.INVALID_SOURCE)
        }
        if (facts.originalPdfSources.any(TargetSizePdfSourceFacts::encrypted)) {
            return TargetSizeClassification(TargetSizeInputClass.ENCRYPTED_PDF)
        }
        val unsupportedReason = when {
            !facts.reconstructionComplete -> TargetSizeUnsupportedReason.INCOMPLETE_RECONSTRUCTION
            facts.originalPdfSources.any(TargetSizePdfSourceFacts::hasInteractiveForm) ->
                TargetSizeUnsupportedReason.INTERACTIVE_FORM
            facts.originalPdfSources.any(TargetSizePdfSourceFacts::hasSignature) ->
                TargetSizeUnsupportedReason.SIGNATURE
            facts.originalPdfSources.any(TargetSizePdfSourceFacts::hasAnnotation) ->
                TargetSizeUnsupportedReason.ANNOTATION
            facts.originalPdfSources.any(TargetSizePdfSourceFacts::hasEmbeddedFile) ->
                TargetSizeUnsupportedReason.EMBEDDED_FILE
            facts.originalPdfSources.any { !it.allPagesContainRasterImage } ->
                TargetSizeUnsupportedReason.VECTOR_OR_NON_IMAGE_PAGE
            else -> null
        }
        if (unsupportedReason != null) {
            return TargetSizeClassification(
                TargetSizeInputClass.UNSUPPORTED_COMPLEX_PDF,
                unsupportedReason,
            )
        }
        if (facts.originalPdfSources.isEmpty()) {
            return TargetSizeClassification(TargetSizeInputClass.IMAGE_DOCUMENT_SUPPORTED)
        }
        val isMixed = facts.containsNonPdfPages ||
            facts.originalPdfSources.any(TargetSizePdfSourceFacts::hasTextOrVectorContent)
        return TargetSizeClassification(
            if (isMixed) {
                TargetSizeInputClass.MIXED_PDF_SUPPORTED_WITH_RECONSTRUCTION
            } else {
                TargetSizeInputClass.IMAGE_BASED_PDF_SUPPORTED
            },
        )
    }
}

class TargetSizePdfPlanner {
    fun plan(
        targetBytes: Long,
        classification: TargetSizeClassification,
        pages: List<TargetSizePageProfile>,
        qualityFloor: TargetSizeQualityFloor = TargetSizeQualityFloor(),
    ): TargetSizePlan {
        require(targetBytes > 0L)
        val mandatoryOverhead = estimateMandatoryOverhead(pages)
        val minimumBytes = saturatedAdd(
            mandatoryOverhead,
            pages.fold(0L) { total, page -> saturatedAdd(total, minimumVisualBytes(page)) },
        )
        if (pages.isEmpty() || classification.inputClass !in SUPPORTED_CLASSES) {
            return TargetSizePlan(
                targetBytes = targetBytes,
                inputClass = classification.inputClass,
                pages = emptyList(),
                mandatoryPdfAndTextOverheadBytes = mandatoryOverhead,
                estimatedFullDocumentPasses = 0,
                maximumFullDocumentPasses = MAXIMUM_FULL_DOCUMENT_PASSES,
                warnings = emptySet(),
                unsupportedReason = classification.unsupportedReason,
                estimatedMinimumAcceptableBytes = minimumBytes,
            )
        }

        val visualBudget = (targetBytes - mandatoryOverhead).coerceAtLeast(0L)
        val allocations = allocatePageBudgets(visualBudget, pages)
        val warnings = linkedSetOf<TargetSizeWarning>()
        val pagePlans = pages.mapIndexed { index, page ->
            val minimumQuality = if (page.contentProfile == TargetSizePageContentProfile.TINY_TEXT) {
                qualityFloor.tinyTextMinimumJpegQuality
            } else {
                qualityFloor.minimumJpegQuality
            }
            val requestedShortEdge = if (page.contentProfile == TargetSizePageContentProfile.TINY_TEXT) {
                qualityFloor.tinyTextMinimumShortEdgePx
            } else {
                qualityFloor.minimumShortEdgePx
            }
            val minimumDimensions = minimumDimensions(page, requestedShortEdge)
            if (minOf(page.widthPx, page.heightPx) < requestedShortEdge) {
                warnings += TargetSizeWarning.SOURCE_RESOLUTION_BELOW_QUALITY_FLOOR
            }
            val allocated = allocations[index]
            val sourceEstimate = estimatedSourceJpegBytes(page)
            val pressure = if (sourceEstimate <= 0L) 1.0 else {
                (allocated.toDouble() / sourceEstimate.toDouble()).coerceIn(0.01, 1.0)
            }
            val initialScale = sqrt(pressure).coerceIn(0.45, 1.0)
            val desiredWidth = (page.widthPx * initialScale).roundToInt().coerceAtLeast(1)
            val desiredHeight = (page.heightPx * initialScale).roundToInt().coerceAtLeast(1)
            val dimensions = fitWithinFloor(
                sourceWidth = page.widthPx,
                sourceHeight = page.heightPx,
                desiredWidth = desiredWidth,
                desiredHeight = desiredHeight,
                minimumWidth = minimumDimensions.first,
                minimumHeight = minimumDimensions.second,
            )
            val quality = (minimumQuality + ((INITIAL_JPEG_QUALITY - minimumQuality) * pressure))
                .roundToInt()
                .coerceIn(minimumQuality, INITIAL_JPEG_QUALITY)
            if (dimensions == minimumDimensions || quality == minimumQuality) {
                warnings += TargetSizeWarning.TARGET_REQUIRES_QUALITY_FLOOR
            }
            TargetSizePagePlan(
                pageId = page.pageId,
                targetWidthPx = dimensions.first,
                targetHeightPx = dimensions.second,
                minimumWidthPx = minimumDimensions.first,
                minimumHeightPx = minimumDimensions.second,
                jpegQuality = quality,
                minimumJpegQuality = minimumQuality,
                allocatedBytes = allocated,
                textStrategy = page.textStrategy,
            )
        }
        if (pages.any { it.textStrategy == TargetSizeTextStrategy.OMIT_UNPOSITIONED_WITH_WARNING }) {
            warnings += TargetSizeWarning.SEARCHABLE_TEXT_OMITTED_UNTRUSTWORTHY_GEOMETRY
        }
        return TargetSizePlan(
            targetBytes = targetBytes,
            inputClass = classification.inputClass,
            pages = pagePlans,
            mandatoryPdfAndTextOverheadBytes = mandatoryOverhead,
            estimatedFullDocumentPasses = if (targetBytes >= minimumBytes) 2 else 0,
            maximumFullDocumentPasses = MAXIMUM_FULL_DOCUMENT_PASSES,
            warnings = warnings,
            unsupportedReason = classification.unsupportedReason,
            estimatedMinimumAcceptableBytes = minimumBytes,
        )
    }

    private fun estimateMandatoryOverhead(pages: List<TargetSizePageProfile>): Long {
        val searchableCharacters = pages.fold(0L) { total, page ->
            saturatedAdd(
                total,
                if (page.textStrategy == TargetSizeTextStrategy.PRESERVE_POSITIONED_UNICODE) {
                    page.searchableCharacterCount.toLong()
                } else {
                    0L
                },
            )
        }
        return saturatedAdd(
            saturatedAdd(
                PDF_BASE_OVERHEAD_BYTES,
                saturatedMultiply(pages.size.toLong(), PDF_PAGE_OVERHEAD_BYTES),
            ),
            saturatedMultiply(
                searchableCharacters.coerceAtMost(MAX_COSTED_SEARCHABLE_CHARACTERS),
                PDF_SEARCHABLE_CHARACTER_OVERHEAD_BYTES,
            ),
        )
    }

    private fun minimumVisualBytes(page: TargetSizePageProfile): Long = when (page.contentProfile) {
        TargetSizePageContentProfile.BLACK_AND_WHITE -> 4_000L
        TargetSizePageContentProfile.GRAYSCALE -> 5_000L
        TargetSizePageContentProfile.TEXT_HEAVY -> 6_000L
        TargetSizePageContentProfile.MIXED_TEXT_PHOTO -> 8_000L
        TargetSizePageContentProfile.PHOTO_HEAVY -> 10_000L
        TargetSizePageContentProfile.TINY_TEXT -> 9_000L
    }

    private fun allocatePageBudgets(
        totalBytes: Long,
        pages: List<TargetSizePageProfile>,
    ): List<Long> {
        if (pages.isEmpty()) return emptyList()
        val weights = pages.map { page ->
            val normalizedPixels = (page.pixelCount.coerceAtLeast(1L) + PIXEL_WEIGHT_DIVISOR - 1L) /
                PIXEL_WEIGHT_DIVISOR
            saturatedMultiply(normalizedPixels, page.contentProfile.budgetWeightPermille.toLong())
        }
        val totalWeight = weights.fold(BigInteger.ZERO) { total, weight ->
            total + BigInteger.valueOf(weight)
        }.max(BigInteger.ONE)
        val allocations = LongArray(pages.size)
        val remainders = Array(pages.size) { BigInteger.ZERO }
        var allocated = 0L
        weights.forEachIndexed { index, weight ->
            val numerator = BigInteger.valueOf(totalBytes) * BigInteger.valueOf(weight)
            val division = numerator.divideAndRemainder(totalWeight)
            // The quotient is a non-negative share of [totalBytes], so it is provably in Long.
            allocations[index] = division[0].toLong()
            remainders[index] = division[1]
            allocated = saturatedAdd(allocated, allocations[index])
        }
        var remaining = (totalBytes - allocated).coerceAtLeast(0L)
        val order = pages.indices.sortedWith(
            compareByDescending<Int> { remainders[it] }.thenBy { it },
        )
        var cursor = 0
        while (remaining > 0L && order.isNotEmpty()) {
            allocations[order[cursor]]++
            remaining--
            cursor = (cursor + 1) % order.size
        }
        return allocations.toList()
    }

    private fun estimatedSourceJpegBytes(page: TargetSizePageProfile): Long {
        page.sourceBytes?.let { return it }
        val bytesPerPixelPermille = when (page.contentProfile) {
            TargetSizePageContentProfile.BLACK_AND_WHITE -> 45L
            TargetSizePageContentProfile.GRAYSCALE -> 80L
            TargetSizePageContentProfile.TEXT_HEAVY -> 100L
            TargetSizePageContentProfile.MIXED_TEXT_PHOTO -> 180L
            TargetSizePageContentProfile.PHOTO_HEAVY -> 280L
            TargetSizePageContentProfile.TINY_TEXT -> 140L
        }
        return saturatedMultiply(page.pixelCount, bytesPerPixelPermille) / 1_000L
    }

    private fun minimumDimensions(
        page: TargetSizePageProfile,
        requestedShortEdge: Int,
    ): Pair<Int, Int> {
        val sourceShort = minOf(page.widthPx, page.heightPx)
        if (sourceShort <= requestedShortEdge) return page.widthPx to page.heightPx
        val scale = requestedShortEdge.toDouble() / sourceShort.toDouble()
        return (page.widthPx * scale).roundToInt().coerceAtLeast(1) to
            (page.heightPx * scale).roundToInt().coerceAtLeast(1)
    }

    private fun fitWithinFloor(
        sourceWidth: Int,
        sourceHeight: Int,
        desiredWidth: Int,
        desiredHeight: Int,
        minimumWidth: Int,
        minimumHeight: Int,
    ): Pair<Int, Int> {
        val width = desiredWidth.coerceIn(minimumWidth, sourceWidth)
        val height = desiredHeight.coerceIn(minimumHeight, sourceHeight)
        val scale = minOf(
            width.toDouble() / sourceWidth.toDouble(),
            height.toDouble() / sourceHeight.toDouble(),
        )
        return maxOf(minimumWidth, (sourceWidth * scale).roundToInt()) to
            maxOf(minimumHeight, (sourceHeight * scale).roundToInt())
    }

    companion object {
        const val MAXIMUM_FULL_DOCUMENT_PASSES = 3
        private const val INITIAL_JPEG_QUALITY = 86
        private const val PDF_BASE_OVERHEAD_BYTES = 8_000L
        private const val PDF_PAGE_OVERHEAD_BYTES = 1_200L
        private const val PDF_SEARCHABLE_CHARACTER_OVERHEAD_BYTES = 4L
        private const val MAX_COSTED_SEARCHABLE_CHARACTERS = 250_000L
        private const val PIXEL_WEIGHT_DIVISOR = 100_000L
        private val SUPPORTED_CLASSES = setOf(
            TargetSizeInputClass.IMAGE_DOCUMENT_SUPPORTED,
            TargetSizeInputClass.IMAGE_BASED_PDF_SUPPORTED,
            TargetSizeInputClass.MIXED_PDF_SUPPORTED_WITH_RECONSTRUCTION,
        )
    }
}

internal fun saturatedAdd(left: Long, right: Long): Long =
    if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

internal fun saturatedMultiply(left: Long, right: Long): Long = when {
    left == 0L || right == 0L -> 0L
    left > Long.MAX_VALUE / right -> Long.MAX_VALUE
    else -> left * right
}
