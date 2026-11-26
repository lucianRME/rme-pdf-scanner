package org.synapseworks.pageharbor.document.batch

import org.synapseworks.pageharbor.document.session.*
import org.synapseworks.pageharbor.image.DocumentFilter
import org.synapseworks.pageharbor.image.ManualImageAdjustments

/** Null fields preserve each page's value. Rotation is relative; all other fields are absolute. */
data class BatchPageEdit(
    val filter: DocumentFilter? = null,
    val brightness: Int? = null,
    val contrast: Int? = null,
    val clockwiseQuarterTurns: Int = 0,
) {
    init {
        require(brightness == null || brightness in -100..100)
        require(contrast == null || contrast in -100..100)
        require(clockwiseQuarterTurns in 0..3)
        require(filter == null || filter in supportedBatchFilters)
    }
    fun preview(page: DocumentPage): DocumentPage {
        var rotation = page.rotation
        repeat(clockwiseQuarterTurns) { rotation = rotation.clockwise() }
        val result = page.copy(rotation = rotation, filter = filter ?: page.filter,
            adjustments = ManualImageAdjustments(brightness ?: page.adjustments.brightness,
                contrast ?: page.adjustments.contrast))
        return if (result == page) page else result
    }
}

val supportedBatchFilters = listOf(DocumentFilter.ORIGINAL, DocumentFilter.AUTO_ENHANCE,
    DocumentFilter.GRAYSCALE, DocumentFilter.BLACK_AND_WHITE, DocumentFilter.HIGH_CONTRAST,
    DocumentFilter.DOCUMENT_CLEANUP,DocumentFilter.SHADOW_GLARE_LOW_V1,
    DocumentFilter.SHADOW_GLARE_BALANCED_V1,DocumentFilter.SHADOW_GLARE_STRONG_V1,
    DocumentFilter.PAPER_WHITE_NEUTRAL_LOW_V1,DocumentFilter.PAPER_WHITE_NEUTRAL_BALANCED_V1,
    DocumentFilter.PAPER_WHITE_NEUTRAL_STRONG_V1,DocumentFilter.PAPER_WHITE_COLOUR_LOW_V1,
    DocumentFilter.PAPER_WHITE_COLOUR_BALANCED_V1,DocumentFilter.PAPER_WHITE_COLOUR_STRONG_V1,DocumentFilter.BLEED_THROUGH_LOW_V1,
    DocumentFilter.BLEED_THROUGH_BALANCED_V1,DocumentFilter.BLEED_THROUGH_STRONG_V1,DocumentFilter.CREASE_STAIN_LOW_V1,DocumentFilter.CREASE_STAIN_BALANCED_V1,DocumentFilter.CREASE_STAIN_STRONG_V1,
    DocumentFilter.MOIRE_LOW_V1,DocumentFilter.MOIRE_BALANCED_V1,DocumentFilter.MOIRE_STRONG_V1)

internal data class PreparedBatchEdit(val session: DocumentSession, val changedPages: Int)

/** Metadata only. Never opens a resource; publication belongs to the guarded main-thread owner. */
internal fun prepareBatchEdit(
    source: DocumentSession,
    selectedIds: Set<Long>,
    edit: BatchPageEdit,
    ensureActive: () -> Unit = {},
    onProgress: (Int) -> Unit = {},
): PreparedBatchEdit {
    require(selectedIds.isNotEmpty())
    require(source.pages.count { it.id.value in selectedIds } == selectedIds.size)
    var processed = 0
    var changed = 0
    val pages = ArrayList<DocumentPage>(source.pages.size)
    source.pages.forEach { page ->
        ensureActive()
        val result = if (page.id.value in selectedIds) {
            edit.preview(page).also {
                processed++
                if (it !== page) changed++
                if (processed % 32 == 0 || processed == selectedIds.size) onProgress(processed)
            }
        } else page
        pages.add(result)
    }
    ensureActive()
    return PreparedBatchEdit(if (changed == 0) source else source.copy(pages = pages), changed)
}

/** Bounded syntax; numeric positions are resolved once to this capture's stable IDs. */
internal fun batchRangeIds(text: String, pages: List<DocumentPage>): Set<Long>? {
    if (text.isBlank() || text.length > 200) return null
    val ranges = text.split(',').map { part ->
        val ends = part.trim().replace('–', '-').split('-')
        if (ends.size !in 1..2) return null
        val start = ends[0].trim().toIntOrNull() ?: return null
        val end = if (ends.size == 1) start else ends[1].trim().toIntOrNull() ?: return null
        if (start < 1 || end < start || end > pages.size) return null
        start..end
    }
    return pages.mapIndexedNotNull { index, page -> page.id.value.takeIf { ranges.any { index + 1 in it } } }.toSet()
}
