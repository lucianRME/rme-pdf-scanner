package org.synapseworks.pageharbor.ocr

import kotlin.math.max

/** One canonical page-local text sequence plus the same geometry in that logical sequence. */
internal data class OcrReadingOrderResolution(
    val canonicalText: String,
    val layout: OcrPageLayout,
)

/**
 * Keeps the recognizer's prose hierarchy and within-line text. A supported grid can read
 * across each row while retaining the bounds of each cell. Clearly reversed vertical sequences
 * also retain the existing bottom-to-top correction.
 */
internal object OcrReadingOrderResolver {
    private val inlineWhitespace = "\\s+".toRegex()

    fun resolve(
        layout: OcrPageLayout,
        angleDegrees: Float = 0f,
        tableRecovery: OcrTableCellRecovery? = null,
    ): OcrReadingOrderResolution {
        val hasBlockHierarchy = layout.blocks.isNotEmpty()
        val sourceBlocks = if (hasBlockHierarchy) {
            layout.blocks
        } else {
            listOf(OcrTextBlock(lines = layout.lines))
        }
        val resolvedBlocks = correctClearlyReversedVerticalSequence(
            sourceBlocks,
            { block -> block.positionBounds() },
        ).mapNotNull { block ->
            val lines = correctClearlyReversedVerticalSequence(
                block.lines,
                OcrTextLine::bounds,
            ).mapNotNull { line ->
                canonicalLineText(line)?.let { canonical -> line.copy(text = canonical) }
            }
            if (lines.isEmpty()) null else block.copy(lines = lines)
        }
        val spatialOrder = if (layout.rotationDegrees == 0 && layout.placementMode == OcrTextPlacementMode.POSITIONED_LINES) {
            OcrTableReadingOrder.order(resolvedBlocks, angleDegrees, tableRecovery)
                ?: OcrDisplayReadingOrder.order(resolvedBlocks, layout.imageHeightPx, angleDegrees)
        } else null
        val rendered = spatialOrder ?: resolvedBlocks.map { OrderedOcrBlock(it) }
        val orderedBlocks = rendered.map(OrderedOcrBlock::geometry)
        val orderedLines = orderedBlocks.flatMap(OcrTextBlock::lines)
        val canonicalText = renderBlocks(rendered)
        return OcrReadingOrderResolution(
            canonicalText = canonicalText,
            layout = layout.copy(
                lines = orderedLines,
                blocks = if (hasBlockHierarchy) orderedBlocks else emptyList(),
            ),
        )
    }

    private fun canonicalLineText(line: OcrTextLine): String? {
        line.text.normalizeInlineWhitespace()?.let { return it }
        return line.elements.asSequence()
            .mapNotNull { element -> element.text.normalizeInlineWhitespace() }
            .joinToString(" ")
            .takeIf(String::isNotBlank)
    }

    private fun renderBlocks(blocks: List<OrderedOcrBlock>): String {
        if (blocks.isEmpty()) return ""
        val typicalLineHeight = median(
            blocks.asSequence()
                .flatMap { block -> block.geometry.lines.asSequence() }
                .mapNotNull { line -> line.bounds?.height?.takeIf { it > 0f } }
                .toList(),
        )
        return buildString {
            blocks.forEachIndexed { index, block ->
                if (index > 0) {
                    append(if (isParagraphGap(blocks[index - 1].geometry, block.geometry, typicalLineHeight)) "\n\n" else "\n")
                }
                append(block.tableText ?: block.geometry.lines.joinToString("\n", transform = OcrTextLine::text))
            }
        }
    }

    private fun isParagraphGap(
        previous: OcrTextBlock,
        current: OcrTextBlock,
        typicalLineHeight: Float?,
    ): Boolean {
        val previousBounds = previous.positionBounds() ?: return false
        val currentBounds = current.positionBounds() ?: return false
        val threshold = max((typicalLineHeight ?: 0f) * 1.5f, 12f)
        return currentBounds.top - previousBounds.bottom >= threshold
    }

    private fun <T> correctClearlyReversedVerticalSequence(
        values: List<T>,
        bounds: (T) -> OcrTextBounds?,
    ): List<T> {
        if (values.size < 2) return values
        val positioned = values.map(bounds)
        if (positioned.any { it == null }) return values
        val boxes = positioned.filterNotNull()
        val clearlyDescending = boxes.zipWithNext().all { (first, second) ->
            val strongVerticalStep = first.top - second.top >= max(
                4f,
                minOf(first.height, second.height) * 0.5f,
            )
            strongVerticalStep && first.isHorizontallyCoherentWith(second)
        }
        return if (clearlyDescending) values.asReversed() else values
    }

    private fun OcrTextBounds.isHorizontallyCoherentWith(other: OcrTextBounds): Boolean {
        val overlap = minOf(right, other.right) - maxOf(left, other.left)
        val narrowerWidth = minOf(width, other.width)
        if (narrowerWidth > 0f && overlap / narrowerWidth >= MIN_HORIZONTAL_OVERLAP_RATIO) {
            return true
        }
        return kotlin.math.abs(left - other.left) <= max(height, other.height) * 2f
    }

    private fun OcrTextBlock.positionBounds(): OcrTextBounds? = bounds ?: lines
        .mapNotNull(OcrTextLine::bounds)
        .takeIf(List<OcrTextBounds>::isNotEmpty)
        ?.let { lineBounds ->
            OcrTextBounds(
                left = lineBounds.minOf(OcrTextBounds::left),
                top = lineBounds.minOf(OcrTextBounds::top),
                right = lineBounds.maxOf(OcrTextBounds::right),
                bottom = lineBounds.maxOf(OcrTextBounds::bottom),
            )
        }

    private fun String.normalizeInlineWhitespace(): String? = trim()
        .replace(inlineWhitespace, " ")
        .takeIf(String::isNotBlank)

    private fun median(values: List<Float>): Float? = values.sorted().let { sorted ->
        when {
            sorted.isEmpty() -> null
            sorted.size % 2 == 1 -> sorted[sorted.size / 2]
            else -> (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2f
        }
    }

    private val OcrTextBounds.height: Float get() = bottom - top
    private val OcrTextBounds.width: Float get() = right - left

    private const val MIN_HORIZONTAL_OVERLAP_RATIO = 0.2f
}

/** In-memory and persisted OCR already use canonical text; layout resolution is only a fallback. */
internal fun OcrPageResult.displayText(): String = text.takeIf(String::isNotBlank)
    ?: layout?.let { OcrReadingOrderResolver.resolve(it).canonicalText }.orEmpty()
