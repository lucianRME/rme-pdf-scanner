package org.synapseworks.pageharbor.document.searchablepdf

import org.synapseworks.pageharbor.ocr.OcrPageAddress
import org.synapseworks.pageharbor.ocr.OcrPageLayout
import org.synapseworks.pageharbor.ocr.OcrPageResult
import org.synapseworks.pageharbor.ocr.OcrRecognitionCurrentness
import org.synapseworks.pageharbor.ocr.OcrResult

/** Stable, snapshot-bound request for effective OCR belonging to one page. */
data class EffectiveOcrPageRequest(
    val address: OcrPageAddress,
    val expectedCurrentness: OcrRecognitionCurrentness,
)

/**
 * Supplies positioned effective text without coupling PDF code to Room entities or an OCR SDK.
 * Implementations must return [EffectiveOcrPageProvision.Stale] instead of mixing revisions.
 */
fun interface EffectiveOcrPageProvider {
    suspend fun provide(request: EffectiveOcrPageRequest): EffectiveOcrPageProvision
}

sealed interface EffectiveOcrPageProvision {
    data class Available(val page: EffectiveOcrPage) : EffectiveOcrPageProvision
    data class Unavailable(val reason: EffectiveOcrPageUnavailableReason) : EffectiveOcrPageProvision
    data object Stale : EffectiveOcrPageProvision
}

enum class EffectiveOcrPageUnavailableReason {
    PAGE_NOT_FOUND,
    OCR_NOT_AVAILABLE,
    POSITIONED_LAYOUT_NOT_AVAILABLE,
    CORRECTION_RECONCILIATION_REQUIRED,
    PROVIDER_FAILURE,
}

enum class EffectiveOcrTextSource {
    RAW,
    CORRECTED,
}

/** One internally consistent positioned text artifact ready for searchable-PDF preparation. */
data class EffectiveOcrPage(
    val address: OcrPageAddress,
    val effectiveText: String,
    val layout: OcrPageLayout,
    val textSource: EffectiveOcrTextSource,
    val artifactRevision: Long,
    val ocrStateRevision: Long,
    val correctionBaseArtifactRevision: Long? = null,
    val currentness: OcrRecognitionCurrentness,
    val coordinateSystemVersion: Int,
    val transformVersion: Int,
) {
    init {
        require(artifactRevision > 0L)
        require(ocrStateRevision >= 0L)
        require(coordinateSystemVersion > 0)
        require(transformVersion > 0)
        currentness.durable?.let { durable ->
            require(durable.ocrStateRevision == ocrStateRevision) {
                "Effective OCR must use the captured OCR-state revision"
            }
        }
        when (textSource) {
            EffectiveOcrTextSource.RAW -> {
                require(correctionBaseArtifactRevision == null)
                currentness.durable?.activeArtifactRevision?.let { activeRevision ->
                    require(activeRevision == artifactRevision) {
                        "Raw effective OCR must use the captured active artifact"
                    }
                }
            }
            EffectiveOcrTextSource.CORRECTED -> {
                require(correctionBaseArtifactRevision == artifactRevision) {
                    "Corrected effective OCR must identify its base artifact"
                }
            }
        }
    }
}

/** Pure preparation result shared by the coordinator and host unit tests. */
internal sealed interface SearchablePdfOcrInputResolution {
    data class Available(val result: OcrResult) : SearchablePdfOcrInputResolution
    data object NeedsRecognition : SearchablePdfOcrInputResolution
    data object Unavailable : SearchablePdfOcrInputResolution
    data object StaleOrMismatched : SearchablePdfOcrInputResolution
}

internal fun OcrResult.toSearchablePdfRecognitionResolution(): SearchablePdfOcrInputResolution =
    if (pages.isEmpty() || pages.all { page -> page.error != null }) {
        SearchablePdfOcrInputResolution.Unavailable
    } else {
        SearchablePdfOcrInputResolution.Available(this)
    }

/**
 * Resolves a persisted effective-text provider when present, otherwise preserves the existing
 * transient-result path. A null transient result means the caller still needs to run OCR.
 */
internal suspend fun resolveSearchablePdfOcrInput(
    provider: EffectiveOcrPageProvider?,
    pageRequests: List<EffectiveOcrPageRequest>,
    transientResult: OcrResult?,
): SearchablePdfOcrInputResolution {
    if (provider == null) {
        return transientResult?.let(SearchablePdfOcrInputResolution::Available)
            ?: SearchablePdfOcrInputResolution.NeedsRecognition
    }

    val pages = ArrayList<OcrPageResult>(pageRequests.size)
    pageRequests.forEachIndexed { pageIndex, pageRequest ->
        when (val provision = provider.provide(pageRequest)) {
            is EffectiveOcrPageProvision.Available -> {
                val page = provision.page
                if (
                    page.address != pageRequest.address ||
                    page.currentness != pageRequest.expectedCurrentness
                ) {
                    return SearchablePdfOcrInputResolution.StaleOrMismatched
                }
                pages += OcrPageResult(
                    pageIndex = pageIndex,
                    text = page.effectiveText,
                    layout = page.layout,
                )
            }

            EffectiveOcrPageProvision.Stale ->
                return SearchablePdfOcrInputResolution.StaleOrMismatched

            is EffectiveOcrPageProvision.Unavailable ->
                return SearchablePdfOcrInputResolution.Unavailable
        }
    }
    return SearchablePdfOcrInputResolution.Available(OcrResult(pages))
}
