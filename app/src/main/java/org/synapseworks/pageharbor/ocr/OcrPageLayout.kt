package org.synapseworks.pageharbor.ocr

/**
 * Engine-neutral geometry retained only for the active OCR session.
 *
 * All values are in the visually upright OCR bitmap's top-left-origin pixel coordinate system.
 * This model deliberately contains no ML Kit types, URIs, paths, or document metadata.
 */
data class OcrTextBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite())
        require(right >= left) { "right must not precede left" }
        require(bottom >= top) { "bottom must not precede top" }
    }
}

/** A recognized word (or ML Kit element) with optional layout geometry. */
data class OcrTextElement(
    val text: String,
    val bounds: OcrTextBounds? = null,
)

data class OcrTextLine(
    val text: String,
    val bounds: OcrTextBounds? = null,
    val confidence: Float? = null,
    val elements: List<OcrTextElement> = emptyList(),
) {
    init {
        require(confidence == null || (confidence.isFinite() && confidence in 0f..1f))
    }
}

/** A recognized ML Kit block, retained to keep likely paragraph boundaries in the OCR result. */
data class OcrTextBlock(
    val lines: List<OcrTextLine>,
    val bounds: OcrTextBounds? = null,
)

/** Describes how precisely hidden PDF text may claim to match the visible page. */
enum class OcrTextPlacementMode {
    /** OCR or line-aligned correction retaining one verified rectangle per text line. */
    POSITIONED_LINES,

    /** Authoritative freeform text placed only within one conservative page text region. */
    FREEFORM_PAGE_REGION,
}

data class OcrPageLayout(
    val imageWidthPx: Int,
    val imageHeightPx: Int,
    val rotationDegrees: Int = 0,
    val lines: List<OcrTextLine>,
    val blocks: List<OcrTextBlock> = emptyList(),
    val placementMode: OcrTextPlacementMode = OcrTextPlacementMode.POSITIONED_LINES,
) {
    init {
        require(imageWidthPx > 0) { "imageWidthPx must be positive" }
        require(imageHeightPx > 0) { "imageHeightPx must be positive" }
        require(rotationDegrees in setOf(0, 90, 180, 270)) {
            "rotationDegrees must be a right angle"
        }
    }
}
