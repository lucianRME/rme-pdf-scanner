package org.synapseworks.pageharbor.document

import org.synapseworks.pageharbor.document.session.DocumentPage
import org.synapseworks.pageharbor.image.ManualImageAdjustments
import org.synapseworks.pageharbor.document.session.DocumentPageRotation
import org.synapseworks.pageharbor.image.DocumentFilter
import org.synapseworks.pageharbor.document.session.imageConstraintViolation

/** Chooses the export operation without mutating the source scan or its session-only filter state. */
sealed interface PageJpegExportPlan {
    val pageId: Long

    /** Preserves the original scanner JPEG bytes exactly. */
    data class DirectCopy(override val pageId: Long) : PageJpegExportPlan

    /** Decodes within the shared document-input bounds and creates a new filtered JPEG. */
    data class Filtered(
        override val pageId: Long,
        val filter: DocumentFilter,
        val rotation: DocumentPageRotation = DocumentPageRotation.DEGREES_0,
        val adjustments: ManualImageAdjustments = ManualImageAdjustments(),
        val annotations: org.synapseworks.pageharbor.document.annotation.AnnotationLayer = org.synapseworks.pageharbor.document.annotation.AnnotationLayer.EMPTY,
    ) : PageJpegExportPlan
}

fun pageJpegExportPlan(page: DocumentPage): PageJpegExportPlan =
    pageJpegExportPlan(page.id.value, page.filter, page.rotation, page.contentType, page.adjustments, page.annotations).let { plan ->
        if(plan is PageJpegExportPlan.DirectCopy && org.synapseworks.pageharbor.document.session.DEFAULT_DOCUMENT_INPUT_LIMITS.imageConstraintViolation(page.imageMetadata)!=null)
            PageJpegExportPlan.Filtered(page.id.value,page.filter,page.rotation,page.adjustments,page.annotations) else plan
    }

internal fun pageJpegExportPlan(
    pageId: Long,
    filter: DocumentFilter,
    rotation: DocumentPageRotation = DocumentPageRotation.DEGREES_0,
    contentType: String = "image/jpeg",
    adjustments: ManualImageAdjustments = ManualImageAdjustments(),
    annotations: org.synapseworks.pageharbor.document.annotation.AnnotationLayer = org.synapseworks.pageharbor.document.annotation.AnnotationLayer.EMPTY,
): PageJpegExportPlan = if (
    annotations.isEmpty && adjustments.isNeutral && filter == DocumentFilter.ORIGINAL &&
    rotation == DocumentPageRotation.DEGREES_0 &&
    contentType == "image/jpeg"
) {
    PageJpegExportPlan.DirectCopy(pageId)
} else {
    PageJpegExportPlan.Filtered(pageId, filter, rotation, adjustments, annotations)
}
