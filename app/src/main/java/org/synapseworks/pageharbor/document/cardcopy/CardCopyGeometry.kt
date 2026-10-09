package org.synapseworks.pageharbor.document.cardcopy

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import org.synapseworks.pageharbor.document.session.DocumentPageRotation
import org.synapseworks.pageharbor.document.redaction.RedactionBounds
import org.synapseworks.pageharbor.document.redaction.RedactionCoordinateMapper
import org.synapseworks.pageharbor.document.redaction.RedactionPageRect

data class CardCopyRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(left <= right && top <= bottom)
    }
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val area: Float get() = width * height
}

data class CardCopyNormalizedRegion(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f)
        require(left <= right && top <= bottom)
    }
}

data class CardCopyPlacement(
    val side: CardCopySide,
    val bounds: CardCopyRect,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val rotation: DocumentPageRotation,
) {
    fun mapSourceRegion(region: RedactionBounds): CardCopyRect {
        val transformed = RedactionCoordinateMapper.rotate(region, rotation)
        val mapped = RedactionCoordinateMapper.mapIntoPage(
            transformed,
            RedactionPageRect(bounds.left, bounds.top, bounds.right, bounds.bottom),
        )
        return CardCopyRect(mapped.left, mapped.top, mapped.right, mapped.bottom)
    }

    fun mapSourceRegion(region: CardCopyNormalizedRegion): CardCopyRect {
        val transformed = region.rotate(rotation)
        return CardCopyRect(
            left = bounds.left + transformed.left * bounds.width,
            top = bounds.top + transformed.top * bounds.height,
            right = bounds.left + transformed.right * bounds.width,
            bottom = bounds.top + transformed.bottom * bounds.height,
        )
    }

    /** Exact black coverage after output sizing, outward rounding, and JPEG safety expansion. */
    fun mapSecureRasterCoverage(region: RedactionBounds): CardCopyRect? {
        val rotated = RedactionCoordinateMapper.rotate(region, rotation)
        val orientedWidth = if (rotation.degrees % 180 == 0) sourceWidth else sourceHeight
        val orientedHeight = if (rotation.degrees % 180 == 0) sourceHeight else sourceWidth
        val maximumWidth = pointsToCardCopyPixels(bounds.width)
        val maximumHeight = pointsToCardCopyPixels(bounds.height)
        val scale = minOf(
            1.0,
            maximumWidth.toDouble() / orientedWidth,
            maximumHeight.toDouble() / orientedHeight,
        )
        val rasterWidth = (orientedWidth * scale).toInt().coerceAtLeast(1)
        val rasterHeight = (orientedHeight * scale).toInt().coerceAtLeast(1)
        val covered = RedactionCoordinateMapper.coveredNormalizedBounds(
            rotated,
            rasterWidth,
            rasterHeight,
        ) ?: return null
        val mapped = RedactionCoordinateMapper.mapIntoPage(
            covered,
            RedactionPageRect(bounds.left, bounds.top, bounds.right, bounds.bottom),
        )
        return CardCopyRect(mapped.left, mapped.top, mapped.right, mapped.bottom)
    }
}

internal const val CARD_COPY_COMPOSITION_PIXELS_PER_INCH = 180

internal fun pointsToCardCopyPixels(points: Float): Int =
    (points * CARD_COPY_COMPOSITION_PIXELS_PER_INCH / 72f).roundToInt().coerceAtLeast(1)

enum class CardCopyCompositionLayer {
    WHITE_BACKGROUND,
    CARD_SIDES,
    FUTURE_REDACTIONS,
    FUTURE_WATERMARK,
    FLATTENED_OUTPUT,
}

data class CardCopyGeometry(
    val pageSize: CardCopyPageSize,
    val resolvedLayout: CardCopyLayout,
    val marginPoints: Float,
    val spacingPoints: Float,
    val front: CardCopyPlacement,
    val back: CardCopyPlacement,
    val layers: List<CardCopyCompositionLayer> = CardCopyCompositionLayer.entries,
) {
    val pageBounds = CardCopyRect(0f, 0f, pageSize.widthPoints, pageSize.heightPoints)
}

object CardCopyGeometryEngine {
    const val SAFE_MARGIN_POINTS = 36f

    fun calculate(
        frontWidth: Int,
        frontHeight: Int,
        frontRotation: DocumentPageRotation,
        backWidth: Int,
        backHeight: Int,
        backRotation: DocumentPageRotation,
        settings: CardCopySettings,
    ): CardCopyGeometry {
        require(frontWidth > 0 && frontHeight > 0 && backWidth > 0 && backHeight > 0)
        val frontOriented = oriented(frontWidth, frontHeight, frontRotation)
        val backOriented = oriented(backWidth, backHeight, backRotation)
        val resolved = when (settings.layout) {
            CardCopyLayout.VERTICAL -> CardCopyLayout.VERTICAL
            CardCopyLayout.HORIZONTAL -> CardCopyLayout.HORIZONTAL
            CardCopyLayout.AUTO -> {
                val vertical = placements(
                    CardCopyLayout.VERTICAL, frontOriented, backOriented, settings,
                )
                val horizontal = placements(
                    CardCopyLayout.HORIZONTAL, frontOriented, backOriented, settings,
                )
                if (horizontal.sumOf { it.area.toDouble() } >
                    vertical.sumOf { it.area.toDouble() }
                ) CardCopyLayout.HORIZONTAL else CardCopyLayout.VERTICAL
            }
        }
        val bounds = placements(resolved, frontOriented, backOriented, settings)
        return CardCopyGeometry(
            pageSize = settings.pageSize,
            resolvedLayout = resolved,
            marginPoints = SAFE_MARGIN_POINTS,
            spacingPoints = settings.spacing.points,
            front = CardCopyPlacement(
                CardCopySide.FRONT, bounds[0], frontWidth, frontHeight, frontRotation,
            ),
            back = CardCopyPlacement(
                CardCopySide.BACK, bounds[1], backWidth, backHeight, backRotation,
            ),
        )
    }

    private fun placements(
        layout: CardCopyLayout,
        front: Pair<Int, Int>,
        back: Pair<Int, Int>,
        settings: CardCopySettings,
    ): List<CardCopyRect> {
        val pageWidth = settings.pageSize.widthPoints
        val pageHeight = settings.pageSize.heightPoints
        val usableWidth = pageWidth - SAFE_MARGIN_POINTS * 2f
        val usableHeight = pageHeight - SAFE_MARGIN_POINTS * 2f
        val spacing = settings.spacing.points.coerceAtMost(
            if (layout == CardCopyLayout.VERTICAL) usableHeight / 3f else usableWidth / 3f,
        )
        return if (layout == CardCopyLayout.VERTICAL) {
            val slotHeight = (usableHeight - spacing) / 2f
            listOf(
                fit(front, SAFE_MARGIN_POINTS, SAFE_MARGIN_POINTS, usableWidth, slotHeight, true, settings.alignment),
                fit(front = back, slotLeft = SAFE_MARGIN_POINTS, slotTop = SAFE_MARGIN_POINTS + slotHeight + spacing,
                    slotWidth = usableWidth, slotHeight = slotHeight, crossAxisHorizontal = true,
                    alignment = settings.alignment),
            )
        } else {
            val slotWidth = (usableWidth - spacing) / 2f
            listOf(
                fit(front, SAFE_MARGIN_POINTS, SAFE_MARGIN_POINTS, slotWidth, usableHeight, false, settings.alignment),
                fit(front = back, slotLeft = SAFE_MARGIN_POINTS + slotWidth + spacing, slotTop = SAFE_MARGIN_POINTS,
                    slotWidth = slotWidth, slotHeight = usableHeight, crossAxisHorizontal = false,
                    alignment = settings.alignment),
            )
        }
    }

    private fun fit(
        front: Pair<Int, Int>,
        slotLeft: Float,
        slotTop: Float,
        slotWidth: Float,
        slotHeight: Float,
        crossAxisHorizontal: Boolean,
        alignment: CardCopyAlignment,
    ): CardCopyRect {
        val scale = min(slotWidth / front.first, slotHeight / front.second)
        val width = front.first * scale
        val height = front.second * scale
        val horizontalOffset = if (crossAxisHorizontal) alignedOffset(slotWidth - width, alignment)
        else (slotWidth - width) / 2f
        val verticalOffset = if (crossAxisHorizontal) (slotHeight - height) / 2f
        else alignedOffset(slotHeight - height, alignment)
        return CardCopyRect(
            slotLeft + horizontalOffset,
            slotTop + verticalOffset,
            slotLeft + horizontalOffset + width,
            slotTop + verticalOffset + height,
        )
    }

    private fun alignedOffset(remaining: Float, alignment: CardCopyAlignment): Float = when (alignment) {
        CardCopyAlignment.START -> 0f
        CardCopyAlignment.CENTER -> remaining / 2f
        CardCopyAlignment.END -> remaining
    }

    private fun oriented(width: Int, height: Int, rotation: DocumentPageRotation): Pair<Int, Int> =
        if (rotation.degrees % 180 == 0) width to height else height to width
}

private fun CardCopyNormalizedRegion.rotate(rotation: DocumentPageRotation): CardCopyNormalizedRegion {
    val corners = listOf(left to top, right to top, right to bottom, left to bottom).map { (x, y) ->
        when (rotation) {
            DocumentPageRotation.DEGREES_0 -> x to y
            DocumentPageRotation.DEGREES_90 -> (1f - y) to x
            DocumentPageRotation.DEGREES_180 -> (1f - x) to (1f - y)
            DocumentPageRotation.DEGREES_270 -> y to (1f - x)
        }
    }
    return CardCopyNormalizedRegion(
        corners.minOf { it.first }, corners.minOf { it.second },
        corners.maxOf { it.first }, corners.maxOf { it.second },
    )
}

fun CardCopyGeometry.isClipped(tolerance: Float = 0.01f): Boolean {
    val usable = CardCopyRect(
        marginPoints,
        marginPoints,
        pageSize.widthPoints - marginPoints,
        pageSize.heightPoints - marginPoints,
    )
    return listOf(front.bounds, back.bounds).any { rect ->
        rect.left < usable.left - tolerance || rect.top < usable.top - tolerance ||
            rect.right > usable.right + tolerance || rect.bottom > usable.bottom + tolerance
    }
}

fun CardCopyGeometry.aspectRatiosPreserved(tolerance: Float = 0.001f): Boolean =
    listOf(front, back).all { placement ->
        val oriented = if (placement.rotation.degrees % 180 == 0) {
            placement.sourceWidth.toFloat() / placement.sourceHeight
        } else {
            placement.sourceHeight.toFloat() / placement.sourceWidth
        }
        abs(placement.bounds.width / placement.bounds.height - oriented) <= tolerance
    }
