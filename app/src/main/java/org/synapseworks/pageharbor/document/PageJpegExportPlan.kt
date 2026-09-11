package org.synapseworks.pageharbor.document

import org.synapseworks.pageharbor.document.session.DocumentPage
import org.synapseworks.pageharbor.document.session.DocumentPageRotation
import org.synapseworks.pageharbor.image.DocumentFilter

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
    ) : PageJpegExportPlan
}

fun pageJpegExportPlan(page: DocumentPage): PageJpegExportPlan =
    pageJpegExportPlan(page.id.value, page.filter, page.rotation, page.contentType)

internal fun pageJpegExportPlan(
    pageId: Long,
    filter: DocumentFilter,
    rotation: DocumentPageRotation = DocumentPageRotation.DEGREES_0,
    contentType: String = "image/jpeg",
): PageJpegExportPlan = if (
    filter == DocumentFilter.ORIGINAL &&
    rotation == DocumentPageRotation.DEGREES_0 &&
    contentType == "image/jpeg"
) {
    PageJpegExportPlan.DirectCopy(pageId)
} else {
    PageJpegExportPlan.Filtered(pageId, filter, rotation)
}
