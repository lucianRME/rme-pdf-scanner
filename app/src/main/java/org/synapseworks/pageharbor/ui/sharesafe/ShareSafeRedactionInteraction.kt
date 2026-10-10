package org.synapseworks.pageharbor.ui.sharesafe

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import kotlin.math.max
import kotlin.math.min
import org.synapseworks.pageharbor.document.redaction.RedactionBounds
import org.synapseworks.pageharbor.document.redaction.RedactionCoordinateMapper
import org.synapseworks.pageharbor.document.redaction.RedactionCoordinateSpace
import org.synapseworks.pageharbor.document.redaction.RedactionRegion
import org.synapseworks.pageharbor.document.redaction.RedactionRegionId
import org.synapseworks.pageharbor.document.session.DocumentPageRotation

internal data class RedactionPreviewGeometry(
    val leftPx: Float,
    val topPx: Float,
    val widthPx: Float,
    val heightPx: Float,
) {
    init {
        require(widthPx > 0f)
        require(heightPx > 0f)
    }

    fun normalizedPoint(position: Offset): Offset? {
        if (position.x !in leftPx..(leftPx + widthPx) ||
            position.y !in topPx..(topPx + heightPx)
        ) return null
        return Offset(
            x = ((position.x - leftPx) / widthPx).coerceIn(0f, 1f),
            y = ((position.y - topPx) / heightPx).coerceIn(0f, 1f),
        )
    }

    fun normalizedDelta(delta: Offset): Offset = Offset(
        x = delta.x / widthPx,
        y = delta.y / heightPx,
    )
}

internal fun redactionPreviewGeometry(
    viewportWidthPx: Float,
    viewportHeightPx: Float,
    imageWidthPx: Int,
    imageHeightPx: Int,
): RedactionPreviewGeometry {
    require(viewportWidthPx > 0f)
    require(viewportHeightPx > 0f)
    require(imageWidthPx > 0)
    require(imageHeightPx > 0)
    val fitScale = min(
        viewportWidthPx / imageWidthPx.toFloat(),
        viewportHeightPx / imageHeightPx.toFloat(),
    )
    val contentWidth = imageWidthPx * fitScale
    val contentHeight = imageHeightPx * fitScale
    return RedactionPreviewGeometry(
        leftPx = (viewportWidthPx - contentWidth) / 2f,
        topPx = (viewportHeightPx - contentHeight) / 2f,
        widthPx = contentWidth,
        heightPx = contentHeight,
    )
}

internal data class RedactionPreviewHit(
    val id: RedactionRegionId,
    val displayBounds: RedactionBounds,
    val order: Int,
)

internal fun findRedactionPreviewHit(
    regions: List<RedactionRegion>,
    selectedId: RedactionRegionId?,
    rotation: DocumentPageRotation,
    point: Offset,
    geometry: RedactionPreviewGeometry,
    minimumTargetPx: Float,
): RedactionPreviewHit? {
    val candidates = regions.mapNotNull { region ->
        if (region.coordinateSpace != RedactionCoordinateSpace.SOURCE_NORMALIZED) return@mapNotNull null
        RedactionPreviewHit(
            id = region.id,
            displayBounds = RedactionCoordinateMapper.rotate(region.bounds, rotation),
            order = region.order,
        )
    }
    fun choose(matches: List<RedactionPreviewHit>): RedactionPreviewHit? = matches.maxWithOrNull(
        compareBy<RedactionPreviewHit> { it.id == selectedId }.thenBy { it.order },
    )

    choose(candidates.filter { it.displayBounds.contains(point) })?.let { return it }
    return choose(candidates.filter { hit ->
        hit.displayBounds.containsWithMinimumTarget(point, geometry, minimumTargetPx)
    })
}

internal fun moveRedactionInDisplaySpace(
    sourceBounds: RedactionBounds,
    rotation: DocumentPageRotation,
    deltaX: Float,
    deltaY: Float,
): RedactionBounds? {
    val displayBounds = RedactionCoordinateMapper.rotate(sourceBounds, rotation)
    val moved = moveRedactionWithinBounds(displayBounds, deltaX, deltaY) ?: return null
    return RedactionCoordinateMapper.inverseRotate(moved, rotation)
}

internal fun resizeRedactionInDisplaySpace(
    sourceBounds: RedactionBounds,
    rotation: DocumentPageRotation,
    delta: Float,
): RedactionBounds? {
    val displayBounds = RedactionCoordinateMapper.rotate(sourceBounds, rotation)
    val resized = RedactionBounds.clamped(
        displayBounds.left - delta,
        displayBounds.top - delta,
        displayBounds.right + delta,
        displayBounds.bottom + delta,
    ) ?: return null
    return RedactionCoordinateMapper.inverseRotate(resized, rotation)
}

internal fun moveRedactionWithinBounds(
    bounds: RedactionBounds,
    deltaX: Float,
    deltaY: Float,
): RedactionBounds? {
    val appliedX = deltaX.coerceIn(-bounds.left, 1f - bounds.right)
    val appliedY = deltaY.coerceIn(-bounds.top, 1f - bounds.bottom)
    return RedactionBounds.clamped(
        bounds.left + appliedX,
        bounds.top + appliedY,
        bounds.right + appliedX,
        bounds.bottom + appliedY,
    )
}

@Composable
internal fun Modifier.shareSafeRedactionDragGestures(
    previewKey: Any?,
    imageWidthPx: Int,
    imageHeightPx: Int,
    rotation: DocumentPageRotation,
    regions: List<RedactionRegion>,
    selectedId: RedactionRegionId?,
    minimumTargetPx: Float,
    onSelect: (RedactionRegionId) -> Unit,
    onUpdate: (RedactionBounds) -> Unit,
): Modifier {
    val latestRegions by rememberUpdatedState(regions)
    val latestSelectedId by rememberUpdatedState(selectedId)
    val latestOnSelect by rememberUpdatedState(onSelect)
    val latestOnUpdate by rememberUpdatedState(onUpdate)
    return pointerInput(previewKey, imageWidthPx, imageHeightPx, rotation) {
        val touchSlop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val geometry = redactionPreviewGeometry(
                viewportWidthPx = size.width.toFloat(),
                viewportHeightPx = size.height.toFloat(),
                imageWidthPx = imageWidthPx,
                imageHeightPx = imageHeightPx,
            )
            val normalizedDown = geometry.normalizedPoint(down.position)
                ?: return@awaitEachGesture
            val hit = findRedactionPreviewHit(
                regions = latestRegions,
                selectedId = latestSelectedId,
                rotation = rotation,
                point = normalizedDown,
                geometry = geometry,
                minimumTargetPx = minimumTargetPx,
            ) ?: return@awaitEachGesture
            val sourceOrigin = latestRegions.firstOrNull { it.id == hit.id }?.bounds
                ?: return@awaitEachGesture
            var cumulative = Offset.Zero
            var dragging = false
            while (true) {
                val event = awaitPointerEvent()
                if (event.changes.count { it.pressed } > 1) break
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    if (!dragging) latestOnSelect(hit.id)
                    break
                }
                cumulative += change.positionChange()
                if (!dragging && cumulative.getDistance() >= touchSlop) {
                    dragging = true
                    latestOnSelect(hit.id)
                }
                if (dragging) {
                    change.consume()
                    val normalizedDelta = geometry.normalizedDelta(cumulative)
                    moveRedactionInDisplaySpace(
                        sourceBounds = sourceOrigin,
                        rotation = rotation,
                        deltaX = normalizedDelta.x,
                        deltaY = normalizedDelta.y,
                    )?.let(latestOnUpdate)
                }
            }
        }
    }
}

private fun RedactionBounds.contains(point: Offset): Boolean =
    point.x in left..right && point.y in top..bottom

private fun RedactionBounds.containsWithMinimumTarget(
    point: Offset,
    geometry: RedactionPreviewGeometry,
    minimumTargetPx: Float,
): Boolean {
    val needsExpansion = width * geometry.widthPx < minimumTargetPx ||
        height * geometry.heightPx < minimumTargetPx
    if (!needsExpansion) return false
    val centerX = (left + right) / 2f
    val centerY = (top + bottom) / 2f
    val halfWidth = max(width / 2f, minimumTargetPx / geometry.widthPx / 2f)
    val halfHeight = max(height / 2f, minimumTargetPx / geometry.heightPx / 2f)
    return point.x in (centerX - halfWidth)..(centerX + halfWidth) &&
        point.y in (centerY - halfHeight)..(centerY + halfHeight)
}
