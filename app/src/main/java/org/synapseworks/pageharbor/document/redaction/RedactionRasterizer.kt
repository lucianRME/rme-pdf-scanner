package org.synapseworks.pageharbor.document.redaction

import android.graphics.Bitmap
import kotlin.coroutines.cancellation.CancellationException
import org.synapseworks.pageharbor.document.session.DocumentPageRotation

sealed interface RedactionRasterResult {
    data class Success(val appliedRegions: Int, val overwrittenPixels: Long) : RedactionRasterResult
    data object InvalidMapping : RedactionRasterResult
}

/** Destructively replaces every covered output pixel with opaque black; it never draws overlays. */
object RedactionRasterizer {
    const val OPAQUE_BLACK: Int = -0x1000000

    fun applyOpaqueBlack(
        bitmap: Bitmap,
        regions: List<RedactionRegion>,
        sourceTargetId: String,
        rotation: DocumentPageRotation,
        targetKind: RedactionTargetKind = RedactionTargetKind.CARD_SOURCE,
        isCancellationRequested: () -> Boolean = { false },
    ): RedactionRasterResult {
        if (bitmap.width <= 0 || bitmap.height <= 0) return RedactionRasterResult.InvalidMapping
        if (regions.any { region ->
                region.target.stableId != sourceTargetId || region.target.kind != targetKind
            }
        ) return RedactionRasterResult.InvalidMapping
        val relevant = regions.confirmedSourceRegionsFor(
            sourceTargetId,
            targetKind,
        )
            ?: return RedactionRasterResult.InvalidMapping
        var overwritten = 0L
        relevant.forEach { region ->
            if (isCancellationRequested()) throw CancellationException("Redaction cancelled")
            val rotated = runCatching {
                RedactionCoordinateMapper.rotate(region.bounds, rotation)
            }.getOrNull() ?: return RedactionRasterResult.InvalidMapping
            val raster = RedactionCoordinateMapper.toRasterOutward(
                rotated,
                bitmap.width,
                bitmap.height,
            ) ?: return RedactionRasterResult.InvalidMapping
            val row = IntArray(raster.rightExclusive - raster.left) { OPAQUE_BLACK }
            for (y in raster.top until raster.bottomExclusive) {
                if (isCancellationRequested()) throw CancellationException("Redaction cancelled")
                bitmap.setPixels(
                    row,
                    0,
                    row.size,
                    raster.left,
                    y,
                    row.size,
                    1,
                )
                overwritten += row.size
            }
            val verification = IntArray(row.size)
            for (y in raster.top until raster.bottomExclusive) {
                bitmap.getPixels(
                    verification,
                    0,
                    verification.size,
                    raster.left,
                    y,
                    verification.size,
                    1,
                )
                if (verification.any { it != OPAQUE_BLACK }) {
                    return RedactionRasterResult.InvalidMapping
                }
            }
        }
        return RedactionRasterResult.Success(relevant.size, overwritten)
    }
}
