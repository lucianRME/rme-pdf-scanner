package org.synapseworks.pageharbor.ocr.persistence

import kotlin.coroutines.cancellation.CancellationException
import org.synapseworks.pageharbor.document.searchablepdf.EffectiveOcrPage
import org.synapseworks.pageharbor.document.searchablepdf.EffectiveOcrPageProvider
import org.synapseworks.pageharbor.document.searchablepdf.EffectiveOcrPageProvision
import org.synapseworks.pageharbor.document.searchablepdf.EffectiveOcrPageRequest
import org.synapseworks.pageharbor.document.searchablepdf.EffectiveOcrPageUnavailableReason
import org.synapseworks.pageharbor.document.searchablepdf.EffectiveOcrTextSource
import org.synapseworks.pageharbor.library.LibraryEffectiveOcrLine
import org.synapseworks.pageharbor.library.LibraryEffectiveOcrPage
import org.synapseworks.pageharbor.library.LibraryOcrArtifactVerification
import org.synapseworks.pageharbor.library.LibraryOcrCorrectionAlignment
import org.synapseworks.pageharbor.library.LibraryRepository
import org.synapseworks.pageharbor.ocr.OcrPageLayout
import org.synapseworks.pageharbor.ocr.OcrTextBounds
import org.synapseworks.pageharbor.ocr.OcrTextLine

/** Adapts one atomic library snapshot into positioned effective text for searchable PDF export. */
class LibraryEffectiveOcrPageProvider internal constructor(
    private val loadPage: suspend (documentId: String, pageId: String) -> LibraryEffectiveOcrPage?,
) : EffectiveOcrPageProvider {
    constructor(repository: LibraryRepository) : this(
        loadPage = { documentId, pageId -> repository.effectiveOcrPage(documentId, pageId) },
    )

    override suspend fun provide(request: EffectiveOcrPageRequest): EffectiveOcrPageProvision =
        try {
            resolve(request, loadPage(request.address.documentId, request.address.pageId))
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            unavailable(EffectiveOcrPageUnavailableReason.PROVIDER_FAILURE)
        }

    private fun resolve(
        request: EffectiveOcrPageRequest,
        stored: LibraryEffectiveOcrPage?,
    ): EffectiveOcrPageProvision {
        stored ?: return unavailable(EffectiveOcrPageUnavailableReason.OCR_NOT_AVAILABLE)
        if (
            stored.documentId != request.address.documentId ||
            stored.pageId != request.address.pageId
        ) {
            return EffectiveOcrPageProvision.Stale
        }

        val expected = request.expectedCurrentness
        val durable = expected.durable ?: return EffectiveOcrPageProvision.Stale
        if (
            stored.documentContentRevision != durable.documentContentRevision ||
            stored.pageVisualRevision != durable.pageVisualRevision ||
            stored.ocrStateRevision != durable.ocrStateRevision ||
            stored.activeArtifactRevision != durable.activeArtifactRevision
        ) {
            return EffectiveOcrPageProvision.Stale
        }
        if (stored.verification != LibraryOcrArtifactVerification.CURRENT_VERIFIED) {
            return unavailable(EffectiveOcrPageUnavailableReason.POSITIONED_LAYOUT_NOT_AVAILABLE)
        }

        val fingerprintVersion = stored.inputFingerprintVersion
            ?: return unavailable(EffectiveOcrPageUnavailableReason.POSITIONED_LAYOUT_NOT_AVAILABLE)
        val fingerprint = stored.inputFingerprint
            ?.takeIf(String::isNotBlank)
            ?: return unavailable(EffectiveOcrPageUnavailableReason.POSITIONED_LAYOUT_NOT_AVAILABLE)
        if (
            fingerprintVersion != expected.inputFingerprintVersion ||
            fingerprint != expected.inputFingerprint
        ) {
            return EffectiveOcrPageProvision.Stale
        }

        val width = stored.uprightWidth?.takeIf { it > 0 }
            ?: return unavailable(EffectiveOcrPageUnavailableReason.POSITIONED_LAYOUT_NOT_AVAILABLE)
        val height = stored.uprightHeight?.takeIf { it > 0 }
            ?: return unavailable(EffectiveOcrPageUnavailableReason.POSITIONED_LAYOUT_NOT_AVAILABLE)
        val coordinateSystemVersion = stored.coordinateSystemVersion
            ?: return unavailable(EffectiveOcrPageUnavailableReason.POSITIONED_LAYOUT_NOT_AVAILABLE)
        val transformVersion = stored.transformVersion
            ?: return unavailable(EffectiveOcrPageUnavailableReason.POSITIONED_LAYOUT_NOT_AVAILABLE)
        if (
            coordinateSystemVersion != SupportedCoordinateSystemVersion ||
            transformVersion != SupportedTransformVersion
        ) {
            return unavailable(EffectiveOcrPageUnavailableReason.POSITIONED_LAYOUT_NOT_AVAILABLE)
        }

        val textSource = when (stored.alignment) {
            null -> {
                if (stored.correctedText != null || stored.correctionBaseArtifactRevision != null) {
                    return unavailable(EffectiveOcrPageUnavailableReason.PROVIDER_FAILURE)
                }
                if (stored.artifactRevision != stored.activeArtifactRevision) {
                    return unavailable(EffectiveOcrPageUnavailableReason.PROVIDER_FAILURE)
                }
                EffectiveOcrTextSource.RAW
            }

            LibraryOcrCorrectionAlignment.LINE_ALIGNED -> {
                if (
                    stored.correctedText == null ||
                    stored.correctionBaseArtifactRevision != stored.artifactRevision
                ) {
                    return unavailable(EffectiveOcrPageUnavailableReason.PROVIDER_FAILURE)
                }
                EffectiveOcrTextSource.CORRECTED
            }

            LibraryOcrCorrectionAlignment.FREEFORM ->
                return unavailable(
                    EffectiveOcrPageUnavailableReason.CORRECTION_RECONCILIATION_REQUIRED,
                )
        }

        if (stored.lines.isEmpty() && stored.effectiveText.isNotBlank()) {
            return unavailable(EffectiveOcrPageUnavailableReason.POSITIONED_LAYOUT_NOT_AVAILABLE)
        }
        if (stored.lines.map(LibraryEffectiveOcrLine::lineOrdinal) != stored.lines.indices.toList()) {
            return unavailable(EffectiveOcrPageUnavailableReason.PROVIDER_FAILURE)
        }
        val lines = stored.lines.map { line ->
            OcrTextLine(
                text = line.text,
                bounds = line.toPixelBounds(width, height)
                    ?: return unavailable(EffectiveOcrPageUnavailableReason.PROVIDER_FAILURE),
            )
        }

        return EffectiveOcrPageProvision.Available(
            EffectiveOcrPage(
                address = request.address,
                effectiveText = stored.effectiveText,
                layout = OcrPageLayout(
                    imageWidthPx = width,
                    imageHeightPx = height,
                    rotationDegrees = 0,
                    lines = lines,
                ),
                textSource = textSource,
                artifactRevision = stored.artifactRevision,
                ocrStateRevision = stored.ocrStateRevision,
                correctionBaseArtifactRevision = stored.correctionBaseArtifactRevision,
                currentness = expected,
                coordinateSystemVersion = coordinateSystemVersion,
                transformVersion = transformVersion,
            ),
        )
    }

    private fun LibraryEffectiveOcrLine.toPixelBounds(
        width: Int,
        height: Int,
    ): OcrTextBounds? {
        val xCoordinates = listOf(topLeftX, topRightX, bottomRightX, bottomLeftX)
        val yCoordinates = listOf(topLeftY, topRightY, bottomRightY, bottomLeftY)
        if (
            xCoordinates.any { value -> !value.isFinite() || value !in 0.0..1.0 } ||
            yCoordinates.any { value -> !value.isFinite() || value !in 0.0..1.0 }
        ) {
            return null
        }
        return OcrTextBounds(
            left = (xCoordinates.min() * width).toFloat(),
            top = (yCoordinates.min() * height).toFloat(),
            right = (xCoordinates.max() * width).toFloat(),
            bottom = (yCoordinates.max() * height).toFloat(),
        )
    }

    private fun unavailable(
        reason: EffectiveOcrPageUnavailableReason,
    ) = EffectiveOcrPageProvision.Unavailable(reason)

    private companion object {
        const val SupportedCoordinateSystemVersion = 1
        const val SupportedTransformVersion = 1
    }
}
