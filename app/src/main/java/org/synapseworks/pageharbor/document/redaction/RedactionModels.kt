package org.synapseworks.pageharbor.document.redaction

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import org.synapseworks.pageharbor.document.session.DocumentPageRotation

enum class RedactionCoordinateSpace {
    SOURCE_NORMALIZED,
    COMPOSITION_NORMALIZED,
    FINAL_PAGE,
}

enum class RedactionTargetKind { CARD_SOURCE, DOCUMENT_PAGE, COMPOSITION }

data class RedactionTarget(
    val kind: RedactionTargetKind,
    val stableId: String,
) {
    init {
        require(stableId.isNotBlank())
    }
}

@JvmInline
value class RedactionRegionId(val value: String) {
    init {
        require(value.isNotBlank())
    }
}

/** Top-left-origin normalized bounds. Export accepts only finite, non-empty, in-range regions. */
data class RedactionBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(listOf(left, top, right, bottom).all(Float::isFinite))
        require(left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f)
        require(right > left && bottom > top)
    }

    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun intersects(other: RedactionBounds): Boolean =
        left <= other.right && right >= other.left && top <= other.bottom && bottom >= other.top

    companion object {
        const val MINIMUM_NORMALIZED_EDGE = 0.004f

        fun clamped(
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            enforceMinimum: Boolean = true,
        ): RedactionBounds? {
            if (!listOf(left, top, right, bottom).all(Float::isFinite)) return null
            val safeLeft = min(left, right).coerceIn(0f, 1f)
            val safeTop = min(top, bottom).coerceIn(0f, 1f)
            val safeRight = max(left, right).coerceIn(0f, 1f)
            val safeBottom = max(top, bottom).coerceIn(0f, 1f)
            if (safeRight <= safeLeft || safeBottom <= safeTop) return null
            if (enforceMinimum &&
                (safeRight - safeLeft < MINIMUM_NORMALIZED_EDGE ||
                    safeBottom - safeTop < MINIMUM_NORMALIZED_EDGE)
            ) return null
            return RedactionBounds(safeLeft, safeTop, safeRight, safeBottom)
        }

        val FullPage = RedactionBounds(0f, 0f, 1f, 1f)
    }
}

data class RedactionRegion(
    val id: RedactionRegionId,
    val coordinateSpace: RedactionCoordinateSpace,
    val bounds: RedactionBounds,
    val target: RedactionTarget,
    val confirmed: Boolean,
    val order: Int,
) {
    init {
        require(order >= 0)
    }
}

data class RedactionPageRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(listOf(left, top, right, bottom).all(Float::isFinite))
        require(right >= left && bottom >= top)
    }
}

data class RedactionRasterRect(
    val left: Int,
    val top: Int,
    val rightExclusive: Int,
    val bottomExclusive: Int,
) {
    init {
        require(left >= 0 && top >= 0)
        require(rightExclusive > left && bottomExclusive > top)
    }
}

object RedactionCoordinateMapper {
    /** One JPEG 8x8 block protects requested pixels from lossy edge bleed after overwrite. */
    const val DEFAULT_RASTER_SAFETY_PIXELS = 8

    /** Maps an EXIF-upright source region into the page's explicit session rotation. */
    fun rotate(bounds: RedactionBounds, rotation: DocumentPageRotation): RedactionBounds {
        val corners = listOf(
            bounds.left to bounds.top,
            bounds.right to bounds.top,
            bounds.right to bounds.bottom,
            bounds.left to bounds.bottom,
        ).map { (x, y) ->
            when (rotation) {
                DocumentPageRotation.DEGREES_0 -> x to y
                DocumentPageRotation.DEGREES_90 -> (1f - y) to x
                DocumentPageRotation.DEGREES_180 -> (1f - x) to (1f - y)
                DocumentPageRotation.DEGREES_270 -> y to (1f - x)
            }
        }
        return RedactionBounds(
            corners.minOf { it.first }.coerceIn(0f, 1f),
            corners.minOf { it.second }.coerceIn(0f, 1f),
            corners.maxOf { it.first }.coerceIn(0f, 1f),
            corners.maxOf { it.second }.coerceIn(0f, 1f),
        )
    }

    fun inverseRotate(bounds: RedactionBounds, rotation: DocumentPageRotation): RedactionBounds =
        rotate(
            bounds,
            when (rotation) {
                DocumentPageRotation.DEGREES_0 -> DocumentPageRotation.DEGREES_0
                DocumentPageRotation.DEGREES_90 -> DocumentPageRotation.DEGREES_270
                DocumentPageRotation.DEGREES_180 -> DocumentPageRotation.DEGREES_180
                DocumentPageRotation.DEGREES_270 -> DocumentPageRotation.DEGREES_90
            },
        )

    fun mapIntoPage(bounds: RedactionBounds, target: RedactionPageRect): RedactionPageRect =
        RedactionPageRect(
            left = target.left + bounds.left * (target.right - target.left),
            top = target.top + bounds.top * (target.bottom - target.top),
            right = target.left + bounds.right * (target.right - target.left),
            bottom = target.top + bounds.bottom * (target.bottom - target.top),
        )

    /** Remaps source-normalized bounds into a new effective crop; null means review is required. */
    fun remapIntoCrop(bounds: RedactionBounds, crop: RedactionBounds): RedactionBounds? {
        val intersectionLeft = max(bounds.left, crop.left)
        val intersectionTop = max(bounds.top, crop.top)
        val intersectionRight = min(bounds.right, crop.right)
        val intersectionBottom = min(bounds.bottom, crop.bottom)
        if (intersectionRight <= intersectionLeft || intersectionBottom <= intersectionTop) return null
        return RedactionBounds.clamped(
            left = (intersectionLeft - crop.left) / crop.width,
            top = (intersectionTop - crop.top) / crop.height,
            right = (intersectionRight - crop.left) / crop.width,
            bottom = (intersectionBottom - crop.top) / crop.height,
            enforceMinimum = false,
        )
    }

    /** Conservative pixel conversion: floor/ceil outward, then expand by a JPEG-block margin. */
    fun toRasterOutward(
        bounds: RedactionBounds,
        width: Int,
        height: Int,
        safetyPixels: Int = DEFAULT_RASTER_SAFETY_PIXELS,
    ): RedactionRasterRect? {
        if (width <= 0 || height <= 0 || safetyPixels < 0) return null
        val left = (floor(bounds.left * width).toInt() - safetyPixels).coerceIn(0, width)
        val top = (floor(bounds.top * height).toInt() - safetyPixels).coerceIn(0, height)
        val right = (ceil(bounds.right * width).toInt() + safetyPixels).coerceIn(0, width)
        val bottom = (ceil(bounds.bottom * height).toInt() + safetyPixels).coerceIn(0, height)
        if (right <= left || bottom <= top) return null
        return RedactionRasterRect(left, top, right, bottom)
    }

    fun coveredNormalizedBounds(
        bounds: RedactionBounds,
        width: Int,
        height: Int,
        safetyPixels: Int = DEFAULT_RASTER_SAFETY_PIXELS,
    ): RedactionBounds? {
        val raster = toRasterOutward(bounds, width, height, safetyPixels) ?: return null
        return RedactionBounds(
            left = raster.left.toFloat() / width,
            top = raster.top.toFloat() / height,
            right = raster.rightExclusive.toFloat() / width,
            bottom = raster.bottomExclusive.toFloat() / height,
        )
    }
}

fun List<RedactionRegion>.confirmedSourceRegionsFor(
    targetId: String,
    targetKind: RedactionTargetKind? = null,
): List<RedactionRegion>? {
    if (targetId.isBlank()) return null
    if (any { !it.confirmed || it.coordinateSpace != RedactionCoordinateSpace.SOURCE_NORMALIZED }) {
        return null
    }
    return filter {
        it.target.stableId == targetId && (targetKind == null || it.target.kind == targetKind)
    }
        .sortedWith(compareBy(RedactionRegion::order, { it.id.value }))
}
