package org.synapseworks.pageharbor.document.redaction

import org.synapseworks.pageharbor.ocr.OcrPageLayout
import org.synapseworks.pageharbor.ocr.OcrPageResult
import org.synapseworks.pageharbor.ocr.OcrTextBounds
import org.synapseworks.pageharbor.ocr.OcrTextLine
import org.synapseworks.pageharbor.ocr.OcrTextPlacementMode
import org.synapseworks.pageharbor.document.session.DocumentPageRotation

sealed interface RedactionTextResult {
    data class Preserved(val page: OcrPageResult?) : RedactionTextResult
    data class Sanitized(val page: OcrPageResult?, val removedUnits: Int) : RedactionTextResult
    data object OmittedUnsafeGeometry : RedactionTextResult
    data object InvalidMapping : RedactionTextResult
}

object RedactionTextSanitizer {
    fun sanitize(
        page: OcrPageResult?,
        regions: List<RedactionRegion>,
        sourceTargetId: String,
        /** The same effective clockwise rotation used to create the upright OCR layout. */
        rotation: DocumentPageRotation = DocumentPageRotation.DEGREES_0,
        /** False when OCR and exported pixels cannot be proven to share one coordinate transform. */
        geometryMatchesEffectiveSource: Boolean = true,
        /** Final sanitized-raster dimensions; when present OCR follows the JPEG safety expansion. */
        rasterWidthPx: Int? = null,
        rasterHeightPx: Int? = null,
    ): RedactionTextResult {
        if ((rasterWidthPx == null) != (rasterHeightPx == null) ||
            rasterWidthPx != null && (rasterWidthPx <= 0 || requireNotNull(rasterHeightPx) <= 0)
        ) return RedactionTextResult.InvalidMapping
        if (regions.any { region ->
                region.target.stableId != sourceTargetId ||
                    region.target.kind != RedactionTargetKind.DOCUMENT_PAGE
            }
        ) return RedactionTextResult.InvalidMapping
        val relevant = regions.confirmedSourceRegionsFor(
            sourceTargetId,
            RedactionTargetKind.DOCUMENT_PAGE,
        )
            ?: return RedactionTextResult.InvalidMapping
        if (relevant.isEmpty()) return RedactionTextResult.Preserved(page)
        val source = page ?: return RedactionTextResult.Sanitized(null, removedUnits = 0)
        val layout = source.layout ?: return RedactionTextResult.OmittedUnsafeGeometry
        if (!geometryMatchesEffectiveSource || source.error != null || layout.rotationDegrees != 0 ||
            layout.placementMode != OcrTextPlacementMode.POSITIONED_LINES
        ) return RedactionTextResult.OmittedUnsafeGeometry

        val mappedRegions = relevant.map { region ->
            val rotated = runCatching { RedactionCoordinateMapper.rotate(region.bounds, rotation) }
                .getOrNull() ?: return RedactionTextResult.InvalidMapping
            if (rasterWidthPx == null) {
                rotated
            } else {
                RedactionCoordinateMapper.coveredNormalizedBounds(
                    rotated,
                    rasterWidthPx,
                    requireNotNull(rasterHeightPx),
                ) ?: return RedactionTextResult.InvalidMapping
            }
        }

        /*
         * A single nonblank unpositioned or invalid line makes the page layer unsafe. Skipping only
         * that line would still trust the remaining geometry even though the artifact has already
         * violated its positioned-layout contract. Security therefore wins over searchability.
         */
        val positionedLines = layout.lines.map { line ->
            if (line.text.isBlank()) return@map line to null
            val bounds = line.bounds?.toNormalizedStrict(layout)
                ?: return RedactionTextResult.OmittedUnsafeGeometry
            val meaningfulElements = line.elements.filter { it.text.isNotBlank() }
            if (meaningfulElements.isNotEmpty()) {
                val normalizedLineText = line.text.normalizedForGeometryContract()
                val normalizedElementText = meaningfulElements.joinToString(" ") { it.text }
                    .normalizedForGeometryContract()
                if (normalizedLineText != normalizedElementText ||
                    meaningfulElements.any { element ->
                        val rawElementBounds = element.bounds ?: return@any false
                        val elementBounds = rawElementBounds.toNormalizedStrict(layout)
                            ?: return RedactionTextResult.OmittedUnsafeGeometry
                        !bounds.contains(elementBounds)
                    }
                ) {
                    return RedactionTextResult.OmittedUnsafeGeometry
                }
            }
            line to bounds
        }

        val sanitizedLines = mutableListOf<OcrTextLine>()
        var removed = 0
        positionedLines.forEach { (line, lineBounds) ->
            if (line.text.isBlank()) return@forEach
            requireNotNull(lineBounds)
            if (mappedRegions.none { it.intersects(lineBounds) }) {
                sanitizedLines += line
                return@forEach
            }
            val meaningfulElements = line.elements.filter { it.text.isNotBlank() }
            val positionedElements = meaningfulElements.map { element ->
                val bounds = element.bounds?.toNormalizedStrict(layout)
                    ?: return@map null
                element to bounds
            }
            if (
                meaningfulElements.isEmpty() ||
                positionedElements.any { it == null }
            ) {
                removed++
                return@forEach
            }
            positionedElements.filterNotNull().forEach { (element, elementBounds) ->
                if (mappedRegions.any { it.intersects(elementBounds) }) {
                    removed++
                } else {
                    sanitizedLines += OcrTextLine(
                        text = element.text,
                        bounds = element.bounds,
                        confidence = line.confidence,
                        elements = listOf(element),
                    )
                }
            }
        }
        val sanitized = if (sanitizedLines.isEmpty()) null else source.copy(
            text = sanitizedLines.joinToString("\n") { it.text },
            layout = layout.copy(lines = sanitizedLines, blocks = emptyList()),
        )
        return RedactionTextResult.Sanitized(sanitized, removed)
    }

    private fun OcrTextBounds.toNormalizedStrict(layout: OcrPageLayout): RedactionBounds? {
        if (
            !listOf(left, top, right, bottom).all(Float::isFinite) ||
            left < 0f || top < 0f || right > layout.imageWidthPx.toFloat() ||
            bottom > layout.imageHeightPx.toFloat() || right <= left || bottom <= top
        ) return null
        return RedactionBounds.clamped(
            left = left / layout.imageWidthPx,
            top = top / layout.imageHeightPx,
            right = right / layout.imageWidthPx,
            bottom = bottom / layout.imageHeightPx,
            enforceMinimum = false,
        )
    }

    private fun RedactionBounds.contains(other: RedactionBounds): Boolean =
        other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom

    private fun String.normalizedForGeometryContract(): String = trim().replace(Regex("\\s+"), " ")
}
