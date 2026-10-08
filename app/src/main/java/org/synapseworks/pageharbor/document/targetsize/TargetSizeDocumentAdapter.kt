package org.synapseworks.pageharbor.document.targetsize

import org.synapseworks.pageharbor.document.session.DocumentSession
import org.synapseworks.pageharbor.document.session.toAndroidUri
import org.synapseworks.pageharbor.ocr.OcrResult

/** Maps the existing ordered effective document pipeline into the M4 engine without persistence. */
fun targetSizePdfRequest(
    session: DocumentSession,
    targetBytes: Long,
    effectiveOcr: OcrResult? = null,
    contentProfiles: Map<Long, TargetSizePageContentProfile> = emptyMap(),
    qualityFloor: TargetSizeQualityFloor = TargetSizeQualityFloor(),
    cancellationSignal: TargetSizeCancellationSignal = TargetSizeCancellationSignal { false },
): TargetSizePdfRequest {
    val ocrByIndex = effectiveOcr?.pages?.associateBy { it.pageIndex }.orEmpty()
    return TargetSizePdfRequest(
        pages = session.pages.mapIndexed { index, page ->
            TargetSizePdfPage(
                pageId = page.id.value,
                sourceUri = page.source.toAndroidUri(),
                sourceCategory = page.sourceCategory,
                contentType = page.contentType,
                imageMetadata = page.imageMetadata,
                filter = page.filter,
                rotation = page.rotation,
                effectiveOcr = ocrByIndex[index],
                contentProfile = contentProfiles[page.id.value]
                    ?: TargetSizePageContentProfile.MIXED_TEXT_PHOTO,
            )
        },
        originalPdfUris = session.originalPdfSources.map { it.toAndroidUri() },
        targetBytes = targetBytes,
        qualityFloor = qualityFloor,
        cancellationSignal = cancellationSignal,
    )
}
