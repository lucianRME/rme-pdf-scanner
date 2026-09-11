package org.synapseworks.pageharbor.document

import org.synapseworks.pageharbor.document.session.DocumentPageRotation
import org.synapseworks.pageharbor.document.session.DocumentImageMetadata
import org.synapseworks.pageharbor.document.session.DocumentResource
import org.synapseworks.pageharbor.document.session.DocumentSession
import org.synapseworks.pageharbor.image.DocumentFilter

/** One source page for a normal image-only PDF recomposition. */
data class NormalPdfPage(
    val pageId: Long,
    val source: DocumentResource,
    val filter: DocumentFilter,
    val rotation: DocumentPageRotation = DocumentPageRotation.DEGREES_0,
    val contentType: String = "image/jpeg",
    val imageMetadata: DocumentImageMetadata = DocumentImageMetadata(),
)

/** The single source decision shared by normal PDF save and share. */
sealed interface NormalPdfExportPlan {
    /** Existing scanner PDF, retained without decoding or recomposition. */
    data class DirectScannerPdf(val source: DocumentResource?) : NormalPdfExportPlan

    /** Ordered page images that must be rendered into a new normal image-only PDF. */
    data class RecomposeFromPages(val pages: List<NormalPdfPage>) : NormalPdfExportPlan
}

/** Save/share are available only when the active session has processable page sources. */
fun canExportNormalPdf(session: DocumentSession): Boolean = session.pages.isNotEmpty()

fun normalPdfExportPlan(session: DocumentSession): NormalPdfExportPlan = if (session.canUseDirectPdf) {
    NormalPdfExportPlan.DirectScannerPdf(session.directPdfSource)
} else {
    NormalPdfExportPlan.RecomposeFromPages(
        session.pages.map { page ->
            NormalPdfPage(
                pageId = page.id.value,
                source = page.source,
                filter = page.filter,
                rotation = page.rotation,
                contentType = page.contentType,
                imageMetadata = page.imageMetadata,
            )
        },
    )
}
