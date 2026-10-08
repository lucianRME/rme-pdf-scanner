package org.synapseworks.pageharbor.document.targetsize

import android.net.Uri
import org.synapseworks.pageharbor.document.session.DocumentImageMetadata
import org.synapseworks.pageharbor.document.session.DocumentPageRotation
import org.synapseworks.pageharbor.document.session.DocumentSourceCategory
import org.synapseworks.pageharbor.image.DocumentFilter
import org.synapseworks.pageharbor.ocr.OcrPageResult

/** Exact decimal upload limits. No binary KB/MiB conversion is used by this feature. */
object TargetSizeBytes {
    const val KB_200: Long = 200_000L
    const val KB_500: Long = 500_000L
    const val MB_1: Long = 1_000_000L
    const val MB_2: Long = 2_000_000L

    fun kilobytes(value: Long): Long = decimalBytes(value, 1_000L)

    fun megabytes(value: Long): Long = decimalBytes(value, 1_000_000L)

    private fun decimalBytes(value: Long, multiplier: Long): Long {
        require(value > 0L)
        require(value <= Long.MAX_VALUE / multiplier)
        return value * multiplier
    }
}

enum class TargetSizeInputClass {
    IMAGE_DOCUMENT_SUPPORTED,
    IMAGE_BASED_PDF_SUPPORTED,
    MIXED_PDF_SUPPORTED_WITH_RECONSTRUCTION,
    UNSUPPORTED_COMPLEX_PDF,
    ENCRYPTED_PDF,
    INVALID_SOURCE,
}

enum class TargetSizePageContentProfile(val budgetWeightPermille: Int) {
    BLACK_AND_WHITE(700),
    GRAYSCALE(850),
    TEXT_HEAVY(1_000),
    MIXED_TEXT_PHOTO(1_300),
    PHOTO_HEAVY(1_600),
    TINY_TEXT(1_450),
}

enum class TargetSizeTextStrategy {
    NONE,
    PRESERVE_POSITIONED_UNICODE,
    OMIT_UNPOSITIONED_WITH_WARNING,
}

enum class TargetSizeWarning {
    SEARCHABLE_TEXT_OMITTED_UNTRUSTWORTHY_GEOMETRY,
    SOURCE_RESOLUTION_BELOW_QUALITY_FLOOR,
    TARGET_REQUIRES_QUALITY_FLOOR,
}

enum class TargetSizeUnsupportedReason {
    INTERACTIVE_FORM,
    SIGNATURE,
    ANNOTATION,
    EMBEDDED_FILE,
    VECTOR_OR_NON_IMAGE_PAGE,
    INCOMPLETE_RECONSTRUCTION,
    EMPTY_OR_INVALID_SOURCE,
}

data class TargetSizePdfSourceFacts(
    val pageCount: Int,
    val allPagesContainRasterImage: Boolean,
    val hasTextOrVectorContent: Boolean,
    val encrypted: Boolean = false,
    val malformed: Boolean = false,
    val hasInteractiveForm: Boolean = false,
    val hasSignature: Boolean = false,
    val hasAnnotation: Boolean = false,
    val hasEmbeddedFile: Boolean = false,
) {
    init {
        require(pageCount >= 0)
    }
}

data class TargetSizeInputFacts(
    val pageCount: Int,
    val originalPdfSources: List<TargetSizePdfSourceFacts> = emptyList(),
    val containsNonPdfPages: Boolean = originalPdfSources.isEmpty(),
    val reconstructionComplete: Boolean = true,
) {
    init {
        require(pageCount >= 0)
    }
}

data class TargetSizeClassification(
    val inputClass: TargetSizeInputClass,
    val unsupportedReason: TargetSizeUnsupportedReason? = null,
)

data class TargetSizePageProfile(
    val pageId: Long,
    val widthPx: Int,
    val heightPx: Int,
    val sourceBytes: Long?,
    val contentProfile: TargetSizePageContentProfile,
    val textStrategy: TargetSizeTextStrategy,
    val searchableCharacterCount: Int = 0,
) {
    init {
        require(widthPx > 0)
        require(heightPx > 0)
        require(sourceBytes == null || sourceBytes > 0L)
        require(searchableCharacterCount >= 0)
    }

    val pixelCount: Long = widthPx.toLong() * heightPx.toLong()
}

data class TargetSizeQualityFloor(
    val minimumShortEdgePx: Int = 960,
    val tinyTextMinimumShortEdgePx: Int = 1_200,
    val minimumJpegQuality: Int = 55,
    val tinyTextMinimumJpegQuality: Int = 65,
) {
    init {
        require(minimumShortEdgePx > 0)
        require(tinyTextMinimumShortEdgePx >= minimumShortEdgePx)
        require(minimumJpegQuality in 1..100)
        require(tinyTextMinimumJpegQuality in minimumJpegQuality..100)
    }
}

data class TargetSizePagePlan(
    val pageId: Long,
    val targetWidthPx: Int,
    val targetHeightPx: Int,
    val minimumWidthPx: Int,
    val minimumHeightPx: Int,
    val jpegQuality: Int,
    val minimumJpegQuality: Int,
    val allocatedBytes: Long,
    val textStrategy: TargetSizeTextStrategy,
) {
    init {
        require(targetWidthPx > 0 && targetHeightPx > 0)
        require(minimumWidthPx > 0 && minimumHeightPx > 0)
        require(targetWidthPx >= minimumWidthPx)
        require(targetHeightPx >= minimumHeightPx)
        require(jpegQuality in minimumJpegQuality..100)
        require(minimumJpegQuality in 1..100)
        require(allocatedBytes >= 0L)
    }
}

data class TargetSizePlan(
    val targetBytes: Long,
    val inputClass: TargetSizeInputClass,
    val pages: List<TargetSizePagePlan>,
    val mandatoryPdfAndTextOverheadBytes: Long,
    val estimatedFullDocumentPasses: Int,
    val maximumFullDocumentPasses: Int,
    val warnings: Set<TargetSizeWarning>,
    val unsupportedReason: TargetSizeUnsupportedReason? = null,
    val estimatedMinimumAcceptableBytes: Long,
) {
    init {
        require(targetBytes > 0L)
        require(mandatoryPdfAndTextOverheadBytes >= 0L)
        require(estimatedFullDocumentPasses in 0..maximumFullDocumentPasses)
        require(maximumFullDocumentPasses in 1..3)
        require(estimatedMinimumAcceptableBytes >= 0L)
    }

    val isSupported: Boolean
        get() = inputClass in setOf(
            TargetSizeInputClass.IMAGE_DOCUMENT_SUPPORTED,
            TargetSizeInputClass.IMAGE_BASED_PDF_SUPPORTED,
            TargetSizeInputClass.MIXED_PDF_SUPPORTED_WITH_RECONSTRUCTION,
        )

    val isFeasibleAtQualityFloor: Boolean
        get() = isSupported && targetBytes >= estimatedMinimumAcceptableBytes
}

data class TargetSizePdfPage(
    val pageId: Long,
    val sourceUri: Uri,
    val sourceCategory: DocumentSourceCategory,
    val contentType: String,
    val imageMetadata: DocumentImageMetadata,
    val filter: DocumentFilter = DocumentFilter.ORIGINAL,
    val rotation: DocumentPageRotation = DocumentPageRotation.DEGREES_0,
    /** Must already be the effective corrected/imported OCR result for this page. */
    val effectiveOcr: OcrPageResult? = null,
    val contentProfile: TargetSizePageContentProfile = TargetSizePageContentProfile.MIXED_TEXT_PHOTO,
)

data class TargetSizePdfRequest(
    val pages: List<TargetSizePdfPage>,
    val originalPdfUris: List<Uri> = emptyList(),
    val targetBytes: Long,
    val qualityFloor: TargetSizeQualityFloor = TargetSizeQualityFloor(),
    val cancellationSignal: TargetSizeCancellationSignal = TargetSizeCancellationSignal { false },
) {
    init {
        require(targetBytes > 0L)
        require(pages.map(TargetSizePdfPage::pageId).distinct().size == pages.size)
    }
}

fun interface TargetSizeCancellationSignal {
    fun isCancellationRequested(): Boolean
}

enum class TargetSizeProgress {
    PLANNING,
    CALIBRATING,
    ENCODING,
    VALIDATING,
}

fun interface TargetSizeProgressListener {
    fun onProgress(progress: TargetSizeProgress)
}

data class TargetSizePerformance(
    val planningMillis: Long,
    val calibrationMillis: Long,
    val encodingMillis: Long,
    val calibrationTrialCount: Int,
    val fullDocumentPassCount: Int,
    val peakTemporaryBytes: Long,
) {
    init {
        require(planningMillis >= 0L && calibrationMillis >= 0L && encodingMillis >= 0L)
        require(calibrationTrialCount >= 0)
        require(fullDocumentPassCount in 0..2)
        require(peakTemporaryBytes >= 0L)
    }
}

class TargetSizePreparedPdf internal constructor(
    internal val file: java.io.File,
    internal val workingDirectory: java.io.File,
    val finalBytes: Long,
    val pageCount: Int,
    val textLayerPageCount: Int,
    val plan: TargetSizePlan,
    val performance: TargetSizePerformance,
)

sealed interface TargetSizePdfResult {
    data class TargetReached(val prepared: TargetSizePreparedPdf) : TargetSizePdfResult

    data class TargetNotReachableAtAcceptableQuality(
        val smallestAcceptable: TargetSizePreparedPdf?,
        val smallestAcceptableBytes: Long,
        val bytesAreMeasured: Boolean,
        val reason: TargetSizeNotReachableReason,
        val plan: TargetSizePlan,
    ) : TargetSizePdfResult

    data class Failure(val reason: TargetSizePdfFailure) : TargetSizePdfResult
}

enum class TargetSizeNotReachableReason {
    MATHEMATICALLY_UNREALISTIC_FOR_PAGE_COUNT,
    QUALITY_FLOOR_REACHED,
}

enum class TargetSizePdfFailure {
    UNSUPPORTED_INPUT,
    ENCRYPTED_PDF,
    MALFORMED_SOURCE,
    INVALID_SOURCE,
    INSUFFICIENT_TEMPORARY_STORAGE,
    SOURCE_READ_FAILED,
    ENCODE_FAILED,
    OUTPUT_VALIDATION_FAILED,
    CANCELED,
}
