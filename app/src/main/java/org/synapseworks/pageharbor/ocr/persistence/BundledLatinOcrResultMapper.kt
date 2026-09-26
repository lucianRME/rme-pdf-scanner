package org.synapseworks.pageharbor.ocr.persistence

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import org.synapseworks.pageharbor.library.LibraryOcrArtifactDraft
import org.synapseworks.pageharbor.library.LibraryOcrLineDraft
import org.synapseworks.pageharbor.library.LibraryOcrPageOutcomeDraft
import org.synapseworks.pageharbor.library.LibraryOcrPageSnapshot
import org.synapseworks.pageharbor.library.MAX_OCR_LINES_PER_PAGE
import org.synapseworks.pageharbor.ocr.OcrModelDelivery
import org.synapseworks.pageharbor.ocr.OcrPageError
import org.synapseworks.pageharbor.ocr.OcrPageLayout
import org.synapseworks.pageharbor.ocr.OcrResult
import org.synapseworks.pageharbor.ocr.OcrScript
import org.synapseworks.pageharbor.ocr.OcrTextBounds

/** Stable provenance and geometry versions for the existing bundled-Latin recognition path. */
object BundledLatinOcrArtifactContract {
    const val INPUT_FINGERPRINT_VERSION = 1
    const val PAGE_ORDER_FINGERPRINT_VERSION = 1
    const val COORDINATE_SYSTEM_VERSION = 1
    const val TRANSFORM_VERSION = 1
    const val RECOGNIZER_ID = "ML_KIT_TEXT_RECOGNITION_LATIN"
    const val PIPELINE_VERSION = "RME_BUNDLED_LATIN_PIPELINE_V1"
    const val DECODE_VERSION = "RME_BOUNDED_BITMAP_DECODE_V1"
    const val CLIENT_VERSION = "16.0.1"
    const val DELIVERY = "BUNDLED"
}

sealed interface BundledLatinOcrMappingResult {
    data class Success(
        val documentPageOrderFingerprintVersion: Int,
        val documentPageOrderFingerprint: String,
        val outcomes: List<LibraryOcrPageOutcomeDraft>,
    ) : BundledLatinOcrMappingResult

    data class Failure(
        val reason: BundledLatinOcrMappingFailure,
        val pageIndex: Int? = null,
    ) : BundledLatinOcrMappingResult
}

/** Safe mapping failures contain no OCR text, paths, hashes, or exception details. */
enum class BundledLatinOcrMappingFailure {
    EMPTY_INPUT,
    PAGE_COUNT_MISMATCH,
    PAGE_INDEX_MISMATCH,
    SNAPSHOT_ORDER_MISMATCH,
    INVALID_SNAPSHOT_IDENTITY,
    CONTENT_HASH_MISSING,
    CONTENT_HASH_INVALID,
    SNAPSHOT_VISUAL_METADATA_INVALID,
    RECOGNIZED_AT_INVALID,
    POSITIONED_LAYOUT_MISSING,
    POSITIONED_LAYOUT_ROTATION_UNSUPPORTED,
    POSITIONED_LINE_LIMIT_EXCEEDED,
    POSITIONED_LINE_GEOMETRY_MISSING,
}

/**
 * Converts the existing bundled-Latin in-memory result into verified persistence drafts.
 *
 * The caller supplies its captured, ordered snapshots and clock value. This mapper performs no
 * I/O, reads no mutable state, and never substitutes missing provenance or positioned geometry.
 */
object BundledLatinOcrResultMapper {
    fun map(
        result: OcrResult,
        orderedSnapshots: List<LibraryOcrPageSnapshot>,
        recognizedAtMillis: Long,
    ): BundledLatinOcrMappingResult {
        if (recognizedAtMillis < 0L) {
            return failure(BundledLatinOcrMappingFailure.RECOGNIZED_AT_INVALID)
        }
        if (orderedSnapshots.isEmpty() || result.pages.isEmpty()) {
            return failure(BundledLatinOcrMappingFailure.EMPTY_INPUT)
        }
        if (orderedSnapshots.size != result.pages.size) {
            return failure(BundledLatinOcrMappingFailure.PAGE_COUNT_MISMATCH)
        }
        if (result.pages.map { page -> page.pageIndex } != result.pages.indices.toList()) {
            return failure(BundledLatinOcrMappingFailure.PAGE_INDEX_MISMATCH)
        }
        if (orderedSnapshots.map { snapshot -> snapshot.pagePosition } != orderedSnapshots.indices.toList()) {
            return failure(BundledLatinOcrMappingFailure.SNAPSHOT_ORDER_MISMATCH)
        }
        val documentId = orderedSnapshots.first().documentId
        if (
            documentId.isBlank() ||
            orderedSnapshots.any { snapshot ->
                snapshot.documentId != documentId || snapshot.pageId.isBlank()
            } ||
            orderedSnapshots.map(LibraryOcrPageSnapshot::pageId).distinct().size !=
            orderedSnapshots.size
        ) {
            return failure(BundledLatinOcrMappingFailure.INVALID_SNAPSHOT_IDENTITY)
        }

        val inputFingerprints = ArrayList<String>(orderedSnapshots.size)
        orderedSnapshots.forEachIndexed { index, snapshot ->
            val contentHash = snapshot.contentSha256
                ?: return failure(BundledLatinOcrMappingFailure.CONTENT_HASH_MISSING, index)
            if (!LowercaseSha256.matches(contentHash)) {
                return failure(BundledLatinOcrMappingFailure.CONTENT_HASH_INVALID, index)
            }
            if (
                snapshot.rotationDegrees !in SupportedRotations ||
                snapshot.filterName.isBlank()
            ) {
                return failure(
                    BundledLatinOcrMappingFailure.SNAPSHOT_VISUAL_METADATA_INVALID,
                    index,
                )
            }
            inputFingerprints += pageInputFingerprint(snapshot, contentHash)
        }

        val outcomes = ArrayList<LibraryOcrPageOutcomeDraft>(result.pages.size)
        result.pages.forEachIndexed { index, page ->
            val snapshot = orderedSnapshots[index]
            val pageError = page.error
            if (pageError != null) {
                outcomes += LibraryOcrPageOutcomeDraft(
                    expected = snapshot,
                    safeErrorCode = safeErrorCode(pageError),
                )
                return@forEachIndexed
            }

            val layout = page.layout
                ?: return failure(BundledLatinOcrMappingFailure.POSITIONED_LAYOUT_MISSING, index)
            if (layout.rotationDegrees != 0) {
                return failure(
                    BundledLatinOcrMappingFailure.POSITIONED_LAYOUT_ROTATION_UNSUPPORTED,
                    index,
                )
            }
            if (layout.lines.size > MAX_OCR_LINES_PER_PAGE) {
                return failure(
                    BundledLatinOcrMappingFailure.POSITIONED_LINE_LIMIT_EXCEEDED,
                    index,
                )
            }
            val lines = positionedLines(layout)
                ?: return failure(
                    BundledLatinOcrMappingFailure.POSITIONED_LINE_GEOMETRY_MISSING,
                    index,
                )
            outcomes += LibraryOcrPageOutcomeDraft(
                expected = snapshot,
                artifact = LibraryOcrArtifactDraft(
                    inputFingerprintVersion =
                        BundledLatinOcrArtifactContract.INPUT_FINGERPRINT_VERSION,
                    inputFingerprint = inputFingerprints[index],
                    contentSha256 = requireNotNull(snapshot.contentSha256),
                    rotationDegrees = snapshot.rotationDegrees,
                    filterName = snapshot.filterName,
                    uprightWidth = layout.imageWidthPx,
                    uprightHeight = layout.imageHeightPx,
                    coordinateSystemVersion =
                        BundledLatinOcrArtifactContract.COORDINATE_SYSTEM_VERSION,
                    transformVersion = BundledLatinOcrArtifactContract.TRANSFORM_VERSION,
                    actualScript = OcrScript.LATIN.stableId,
                    recognizerId = BundledLatinOcrArtifactContract.RECOGNIZER_ID,
                    pipelineVersion = BundledLatinOcrArtifactContract.PIPELINE_VERSION,
                    clientVersion = BundledLatinOcrArtifactContract.CLIENT_VERSION,
                    delivery = OcrModelDelivery.BUNDLED.name,
                    recognizedAtMillis = recognizedAtMillis,
                    rawText = page.text,
                    lines = lines,
                ),
            )
        }

        return BundledLatinOcrMappingResult.Success(
            documentPageOrderFingerprintVersion =
                BundledLatinOcrArtifactContract.PAGE_ORDER_FINGERPRINT_VERSION,
            documentPageOrderFingerprint = pageOrderFingerprint(
                documentId = documentId,
                snapshots = orderedSnapshots,
                inputFingerprints = inputFingerprints,
            ),
            outcomes = outcomes,
        )
    }

    private fun positionedLines(layout: OcrPageLayout): List<LibraryOcrLineDraft>? {
        val positioned = ArrayList<LibraryOcrLineDraft>(layout.lines.size)
        layout.lines.forEachIndexed { lineOrdinal, line ->
            val bounds = line.bounds ?: return null
            positioned += bounds.toNormalizedLine(
                lineOrdinal = lineOrdinal,
                text = line.text,
                width = layout.imageWidthPx,
                height = layout.imageHeightPx,
            ) ?: return null
        }
        return positioned
    }

    private fun OcrTextBounds.toNormalizedLine(
        lineOrdinal: Int,
        text: String,
        width: Int,
        height: Int,
    ): LibraryOcrLineDraft? {
        val coordinates = listOf(left, top, right, bottom)
        if (
            coordinates.any { coordinate -> !coordinate.isFinite() } ||
            left < 0f || top < 0f ||
            right > width.toFloat() || bottom > height.toFloat() ||
            left > right || top > bottom
        ) {
            return null
        }
        val normalizedLeft = (left / width).toDouble()
        val normalizedTop = (top / height).toDouble()
        val normalizedRight = (right / width).toDouble()
        val normalizedBottom = (bottom / height).toDouble()
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
            writingOrientation = null,
        )
    }

    private fun pageInputFingerprint(
        snapshot: LibraryOcrPageSnapshot,
        contentHash: String,
    ): String = stableSha256(
        domain = "RME-OCR-INPUT-FINGERPRINT-V1",
        fields = listOf(
            contentHash,
            snapshot.pageVisualRevision.toString(),
            snapshot.rotationDegrees.toString(),
            snapshot.filterName,
            OcrScript.LATIN.stableId,
            BundledLatinOcrArtifactContract.RECOGNIZER_ID,
            BundledLatinOcrArtifactContract.PIPELINE_VERSION,
            BundledLatinOcrArtifactContract.DECODE_VERSION,
            BundledLatinOcrArtifactContract.COORDINATE_SYSTEM_VERSION.toString(),
            BundledLatinOcrArtifactContract.TRANSFORM_VERSION.toString(),
        ),
    )

    private fun pageOrderFingerprint(
        documentId: String,
        snapshots: List<LibraryOcrPageSnapshot>,
        inputFingerprints: List<String>,
    ): String = stableSha256(
        domain = "RME-OCR-PAGE-ORDER-FINGERPRINT-V1",
        fields = buildList {
            add(documentId)
            add(snapshots.size.toString())
            snapshots.forEachIndexed { index, snapshot ->
                add(index.toString())
                add(snapshot.pageId)
                add(inputFingerprints[index])
            }
        },
    )

    private fun stableSha256(domain: String, fields: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.updateLengthPrefixed(domain)
        fields.forEach { field -> digest.updateLengthPrefixed(field) }
        return "sha256:${digest.digest().toLowercaseHex()}"
    }

    private fun MessageDigest.updateLengthPrefixed(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        update((bytes.size ushr 24).toByte())
        update((bytes.size ushr 16).toByte())
        update((bytes.size ushr 8).toByte())
        update(bytes.size.toByte())
        update(bytes)
    }

    private fun ByteArray.toLowercaseHex(): String = buildString(size * 2) {
        for (value in this@toLowercaseHex) {
            val unsigned = value.toInt() and 0xff
            append(HexDigits[unsigned ushr 4])
            append(HexDigits[unsigned and 0x0f])
        }
    }

    private fun safeErrorCode(error: OcrPageError): String =
        error.name.uppercase(Locale.ROOT)

    private fun failure(
        reason: BundledLatinOcrMappingFailure,
        pageIndex: Int? = null,
    ) = BundledLatinOcrMappingResult.Failure(reason, pageIndex)

    private val LowercaseSha256 = Regex("[0-9a-f]{64}")
    private val SupportedRotations = setOf(0, 90, 180, 270)
    private const val HexDigits = "0123456789abcdef"
}
