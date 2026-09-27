package org.synapseworks.pageharbor.ocr.persistence

import org.synapseworks.pageharbor.library.LibraryOcrArtifactDraft
import org.synapseworks.pageharbor.library.LibraryOcrLineDraft
import org.synapseworks.pageharbor.library.LibraryOcrPageOutcomeDraft
import org.synapseworks.pageharbor.library.LibraryOcrPageSnapshot
import org.synapseworks.pageharbor.library.MAX_OCR_LINES_PER_PAGE
import org.synapseworks.pageharbor.ocr.OcrFailureReason
import org.synapseworks.pageharbor.ocr.OcrPageRecognitionOutcome
import org.synapseworks.pageharbor.ocr.OcrTextBounds

sealed interface OcrPagePersistenceMapping {
    data class Ready(val outcome: LibraryOcrPageOutcomeDraft) : OcrPagePersistenceMapping
    data object NoChange : OcrPagePersistenceMapping
    data object Stale : OcrPagePersistenceMapping
    data object InvalidResult : OcrPagePersistenceMapping
}

/**
 * Adapts one routed recognition outcome into the existing revision-safe DAO commit contract.
 * Missing models, install failures, cancellation, and stale input intentionally produce no write.
 */
object OcrPageRecognitionPersistenceMapper {
    fun map(
        result: OcrPageRecognitionOutcome,
        expected: LibraryOcrPageSnapshot,
        recognizedAtMillis: Long,
    ): OcrPagePersistenceMapping {
        if (recognizedAtMillis < 0L) return OcrPagePersistenceMapping.InvalidResult
        if (!result.matches(expected)) return OcrPagePersistenceMapping.Stale

        return when (result) {
            is OcrPageRecognitionOutcome.Failure -> result.toPersistenceFailure(expected)
            is OcrPageRecognitionOutcome.Success -> result.toPersistenceDraft(
                expected = expected,
                recognizedAtMillis = recognizedAtMillis,
            )
        }
    }

    private fun OcrPageRecognitionOutcome.Failure.toPersistenceFailure(
        expected: LibraryOcrPageSnapshot,
    ): OcrPagePersistenceMapping = when (reason) {
        OcrFailureReason.IMAGE_UNREADABLE,
        OcrFailureReason.RECOGNITION_FAILED,
        -> OcrPagePersistenceMapping.Ready(
            LibraryOcrPageOutcomeDraft(
                expected = expected,
                safeErrorCode = reason.name,
            ),
        )

        OcrFailureReason.MODEL_UNAVAILABLE,
        OcrFailureReason.GOOGLE_PLAY_SERVICES_UNAVAILABLE,
        OcrFailureReason.SCRIPT_UNSUPPORTED,
        OcrFailureReason.STALE_INPUT,
        OcrFailureReason.CANCELLED,
        OcrFailureReason.PERSISTENCE_FAILED,
        -> OcrPagePersistenceMapping.NoChange
    }

    private fun OcrPageRecognitionOutcome.Success.toPersistenceDraft(
        expected: LibraryOcrPageSnapshot,
        recognizedAtMillis: Long,
    ): OcrPagePersistenceMapping {
        if (provenance.actualScript != descriptor.script) {
            return OcrPagePersistenceMapping.InvalidResult
        }
        val contentHash = expected.contentSha256
            ?.takeIf(LowercaseSha256::matches)
            ?: return OcrPagePersistenceMapping.InvalidResult
        if (expected.rotationDegrees !in SupportedRotations || expected.filterName.isBlank()) {
            return OcrPagePersistenceMapping.InvalidResult
        }
        val positionedLayout = layout ?: return OcrPagePersistenceMapping.InvalidResult
        if (positionedLayout.rotationDegrees != 0 ||
            positionedLayout.lines.size > MAX_OCR_LINES_PER_PAGE
        ) {
            return OcrPagePersistenceMapping.InvalidResult
        }
        val lines = positionedLayout.lines.mapIndexed { index, line ->
            line.bounds?.toNormalizedLine(
                lineOrdinal = index,
                text = line.text,
                width = positionedLayout.imageWidthPx,
                height = positionedLayout.imageHeightPx,
            ) ?: return OcrPagePersistenceMapping.InvalidResult
        }

        return OcrPagePersistenceMapping.Ready(
            LibraryOcrPageOutcomeDraft(
                expected = expected,
                artifact = LibraryOcrArtifactDraft(
                    inputFingerprintVersion = descriptor.currentness.inputFingerprintVersion,
                    inputFingerprint = descriptor.currentness.inputFingerprint,
                    contentSha256 = contentHash,
                    rotationDegrees = expected.rotationDegrees,
                    filterName = expected.filterName,
                    uprightWidth = positionedLayout.imageWidthPx,
                    uprightHeight = positionedLayout.imageHeightPx,
                    coordinateSystemVersion = COORDINATE_SYSTEM_VERSION,
                    transformVersion = TRANSFORM_VERSION,
                    actualScript = provenance.actualScript.stableId,
                    recognizerId = provenance.recognizerId,
                    pipelineVersion = provenance.pipelineVersion,
                    clientVersion = provenance.clientVersion,
                    delivery = provenance.delivery.name,
                    recognizedAtMillis = recognizedAtMillis,
                    rawText = rawText,
                    lines = lines,
                ),
            ),
        )
    }

    private fun OcrPageRecognitionOutcome.matches(expected: LibraryOcrPageSnapshot): Boolean {
        val durable = descriptor.currentness.durable ?: return false
        return descriptor.address.documentId == expected.documentId &&
            descriptor.address.pageId == expected.pageId &&
            descriptor.capturedPagePosition == expected.pagePosition &&
            durable.documentContentRevision == expected.documentContentRevision &&
            durable.pageVisualRevision == expected.pageVisualRevision &&
            durable.ocrStateRevision == expected.ocrStateRevision &&
            durable.activeArtifactRevision == expected.activeArtifactRevision
    }

    private fun OcrTextBounds.toNormalizedLine(
        lineOrdinal: Int,
        text: String,
        width: Int,
        height: Int,
    ): LibraryOcrLineDraft? {
        if (width <= 0 || height <= 0) return null
        val coordinates = listOf(left, top, right, bottom)
        if (coordinates.any { !it.isFinite() } ||
            left < 0f || top < 0f || right < left || bottom < top ||
            right > width.toFloat() || bottom > height.toFloat()
        ) {
            return null
        }
        val normalizedLeft = left.toDouble() / width
        val normalizedTop = top.toDouble() / height
        val normalizedRight = right.toDouble() / width
        val normalizedBottom = bottom.toDouble() / height
        return LibraryOcrLineDraft(
            lineOrdinal = lineOrdinal,
            rawText = text,
            topLeftX = normalizedLeft,
            topLeftY = normalizedTop,
            topRightX = normalizedRight,
            topRightY = normalizedTop,
            bottomRightX = normalizedRight,
            bottomRightY = normalizedBottom,
            bottomLeftX = normalizedLeft,
            bottomLeftY = normalizedBottom,
            baselineStartX = normalizedLeft,
            baselineStartY = normalizedBottom,
            baselineEndX = normalizedRight,
            baselineEndY = normalizedBottom,
            baselineAngleDegrees = 0.0,
        )
    }

    private val LowercaseSha256 = Regex("[0-9a-f]{64}")
    private val SupportedRotations = setOf(0, 90, 180, 270)
    private const val COORDINATE_SYSTEM_VERSION = 1
    private const val TRANSFORM_VERSION = 1
}
