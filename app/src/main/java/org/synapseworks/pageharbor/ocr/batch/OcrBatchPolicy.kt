package org.synapseworks.pageharbor.ocr.batch

import org.synapseworks.pageharbor.library.LibraryOcrReviewPage
import org.synapseworks.pageharbor.library.OcrBatchItemEntity
import org.synapseworks.pageharbor.library.OcrBatchItemState
import org.synapseworks.pageharbor.ocr.OcrModelState
import org.synapseworks.pageharbor.ocr.OcrScript

internal fun shouldRecognizeBatchPage(
    mode: OcrBatchMode,
    review: LibraryOcrReviewPage?,
): Boolean = mode == OcrBatchMode.RERUN || review?.effectiveText == null

internal fun isBatchModelReady(script: OcrScript, state: OcrModelState): Boolean =
    script == OcrScript.LATIN || state == OcrModelState.Installed

internal fun OcrBatchItemEntity.isRetryableBatchItem(): Boolean =
    state == OcrBatchItemState.FAILED.name ||
        (state == OcrBatchItemState.SKIPPED.name && safeErrorCode == "STALE_INPUT")
