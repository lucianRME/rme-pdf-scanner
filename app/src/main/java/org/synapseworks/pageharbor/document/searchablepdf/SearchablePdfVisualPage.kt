package org.synapseworks.pageharbor.document.searchablepdf

import android.net.Uri
import org.synapseworks.pageharbor.image.ManualImageAdjustments
import org.synapseworks.pageharbor.document.session.DocumentImageMetadata
import org.synapseworks.pageharbor.document.session.DocumentPageRotation
import org.synapseworks.pageharbor.image.DocumentFilter

/** One original OCR source and its independent, non-destructive visual PDF selection. */
data class SearchablePdfVisualPage(
    val pageId: Long,
    val originalUri: Uri,
    val filter: DocumentFilter = DocumentFilter.ORIGINAL,
    val rotation: DocumentPageRotation = DocumentPageRotation.DEGREES_0,
    val contentType: String = "image/jpeg",
    val imageMetadata: DocumentImageMetadata = DocumentImageMetadata(),
    val adjustments: ManualImageAdjustments = ManualImageAdjustments(),
    val annotations: org.synapseworks.pageharbor.document.annotation.AnnotationLayer = org.synapseworks.pageharbor.document.annotation.AnnotationLayer.EMPTY,
    val paperSize: org.synapseworks.pageharbor.document.session.DocumentPaperSize? = null,
)

sealed interface SearchablePdfVisualPlan {
    val pageId: Long
    val ocrSource: SearchablePdfOcrSource

    /** Preserves the existing direct JPEG embedding path for the visual PDF page. */
    data class Original(override val pageId: Long) : SearchablePdfVisualPlan {
        override val ocrSource = SearchablePdfOcrSource.ORIGINAL
    }

    /** Produces one full-resolution temporary JPEG for visual embedding only. */
    data class Filtered(
        override val pageId: Long,
        val filter: DocumentFilter,
        val rotation: DocumentPageRotation = DocumentPageRotation.DEGREES_0,
        val adjustments: ManualImageAdjustments = ManualImageAdjustments(),
    val annotations: org.synapseworks.pageharbor.document.annotation.AnnotationLayer = org.synapseworks.pageharbor.document.annotation.AnnotationLayer.EMPTY,
    ) : SearchablePdfVisualPlan {
        override val ocrSource = SearchablePdfOcrSource.ORIGINAL
    }
}

/** OCR recognition and its geometry always come from the original scanner image. */
enum class SearchablePdfOcrSource { ORIGINAL }

internal fun searchablePdfVisualPlan(
    pageId: Long,
    filter: DocumentFilter,
    rotation: DocumentPageRotation = DocumentPageRotation.DEGREES_0,
    contentType: String = "image/jpeg",
    adjustments: ManualImageAdjustments = ManualImageAdjustments(),
    annotations: org.synapseworks.pageharbor.document.annotation.AnnotationLayer = org.synapseworks.pageharbor.document.annotation.AnnotationLayer.EMPTY,
): SearchablePdfVisualPlan = if (
    annotations.isEmpty && adjustments.isNeutral && filter == DocumentFilter.ORIGINAL &&
    rotation == DocumentPageRotation.DEGREES_0 &&
    contentType == "image/jpeg"
) {
    SearchablePdfVisualPlan.Original(pageId)
} else {
    SearchablePdfVisualPlan.Filtered(pageId, filter, rotation, adjustments, annotations)
}

internal fun searchablePdfVisualPlan(page: SearchablePdfVisualPage): SearchablePdfVisualPlan =
    searchablePdfVisualPlan(page.pageId, page.filter, page.rotation, page.contentType, page.adjustments, page.annotations)
