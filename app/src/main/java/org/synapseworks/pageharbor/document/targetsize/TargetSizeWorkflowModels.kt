package org.synapseworks.pageharbor.document.targetsize

import android.net.Uri
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.NumberFormat
import java.text.ParsePosition
import java.util.Locale

enum class TargetSizePreset(val bytes: Long) {
    KB_200(TargetSizeBytes.KB_200),
    KB_500(TargetSizeBytes.KB_500),
    MB_1(TargetSizeBytes.MB_1),
    MB_2(TargetSizeBytes.MB_2),
}

enum class TargetSizeCustomUnit(val multiplier: Long) {
    KB(1_000L),
    MB(1_000_000L),
}

enum class TargetSizeCustomError {
    EMPTY,
    INVALID_NUMBER,
    TOO_SMALL,
    TOO_LARGE,
}

sealed interface TargetSizeCustomValidation {
    data class Valid(val bytes: Long) : TargetSizeCustomValidation
    data class Invalid(val reason: TargetSizeCustomError) : TargetSizeCustomValidation
}

sealed interface TargetSizeLimitSelection {
    val targetBytes: Long?

    data class Preset(val preset: TargetSizePreset) : TargetSizeLimitSelection {
        override val targetBytes: Long = preset.bytes
    }

    data class Custom(
        val text: String,
        val unit: TargetSizeCustomUnit,
        val validation: TargetSizeCustomValidation,
    ) : TargetSizeLimitSelection {
        override val targetBytes: Long?
            get() = (validation as? TargetSizeCustomValidation.Valid)?.bytes
    }
}

data class TargetSizeSourceSummary(
    val title: String,
    val pageCount: Int,
    /** Exact source/input bytes when known. This is never workspace or safety-reserve storage. */
    val sourceBytes: Long?,
    val supportStatus: TargetSizeSourceSupport = TargetSizeSourceSupport.READY_TO_CHECK,
) {
    init {
        require(title.isNotBlank())
        require(pageCount > 0)
        require(sourceBytes == null || sourceBytes >= 0L)
    }
}

enum class TargetSizeSourceSupport {
    READY_TO_CHECK,
}

data class TargetSizeWorkflowInput(
    val summary: TargetSizeSourceSummary,
    val request: TargetSizePdfRequest,
    /** Present only when the current, unedited ordered document can safely reuse this PDF. */
    val safeOriginalPdfUri: Uri? = null,
    val safeOriginalPdfBytes: Long? = null,
) {
    init {
        require(request.pages.size == summary.pageCount)
        require(safeOriginalPdfBytes == null || safeOriginalPdfBytes >= 0L)
        require((safeOriginalPdfUri == null) == (safeOriginalPdfBytes == null))
    }
}

enum class TargetSizeSearchableTextStatus {
    PRESERVED,
    PARTIALLY_PRESERVED,
    NOT_PRESERVED,
    NOT_PRESENT,
}

sealed interface TargetSizeSaveStatus {
    data object Idle : TargetSizeSaveStatus
    data object ChoosingDestination : TargetSizeSaveStatus
    data object Saving : TargetSizeSaveStatus
    data class Saved(val bytes: Long) : TargetSizeSaveStatus
    data class Failed(val reason: TargetSizeDestinationError) : TargetSizeSaveStatus
}

sealed interface TargetSizeWorkflowState {
    data object Hidden : TargetSizeWorkflowState
    data object Opening : TargetSizeWorkflowState

    data class ChooseLimit(
        val input: TargetSizeWorkflowInput,
        val selection: TargetSizeLimitSelection = TargetSizeLimitSelection.Preset(
            TargetSizePreset.MB_1,
        ),
    ) : TargetSizeWorkflowState

    data class Preparing(
        val input: TargetSizeWorkflowInput,
        val selection: TargetSizeLimitSelection,
        val targetBytes: Long,
        val progress: TargetSizeProgress,
    ) : TargetSizeWorkflowState

    data class Prepared(
        val input: TargetSizeWorkflowInput,
        val selection: TargetSizeLimitSelection,
        val requestedTargetBytes: Long,
        val prepared: TargetSizePreparedPdf,
        val reusedOriginalQuality: Boolean,
        val acceptedAlternative: Boolean = false,
        val saveStatus: TargetSizeSaveStatus = TargetSizeSaveStatus.Idle,
        val shareInvoked: Boolean = false,
        val shareFailed: Boolean = false,
    ) : TargetSizeWorkflowState {
        val searchableTextStatus: TargetSizeSearchableTextStatus
            get() = searchableTextStatus(
                textLayerPageCount = prepared.textLayerPageCount,
                pageCount = prepared.pageCount,
                strategies = prepared.plan.pages.map(TargetSizePagePlan::textStrategy),
            )
    }

    data class ImpossibleTarget(
        val input: TargetSizeWorkflowInput,
        val selection: TargetSizeLimitSelection,
        val requestedTargetBytes: Long,
        val smallestRecommendedBytes: Long,
        val smallestRecommended: TargetSizePreparedPdf?,
        val bytesAreMeasured: Boolean,
    ) : TargetSizeWorkflowState

    data class Failed(
        val input: TargetSizeWorkflowInput?,
        val selection: TargetSizeLimitSelection?,
        val targetBytes: Long?,
        val reason: TargetSizePdfFailure,
    ) : TargetSizeWorkflowState
}

object TargetSizeCustomLimitParser {
    const val MINIMUM_CUSTOM_BYTES = 100_000L
    const val MAXIMUM_CUSTOM_BYTES = 1_000_000_000L

    fun parse(
        text: String,
        unit: TargetSizeCustomUnit,
        locale: Locale = Locale.getDefault(),
    ): TargetSizeCustomValidation {
        val value = text.trim()
        if (value.isEmpty()) return TargetSizeCustomValidation.Invalid(TargetSizeCustomError.EMPTY)
        val format = NumberFormat.getNumberInstance(locale) as? DecimalFormat
            ?: return TargetSizeCustomValidation.Invalid(TargetSizeCustomError.INVALID_NUMBER)
        format.isParseBigDecimal = true
        val position = ParsePosition(0)
        val parsed = format.parse(value, position) as? BigDecimal
            ?: return TargetSizeCustomValidation.Invalid(TargetSizeCustomError.INVALID_NUMBER)
        if (position.index != value.length || position.errorIndex >= 0 || parsed.signum() <= 0) {
            return TargetSizeCustomValidation.Invalid(TargetSizeCustomError.INVALID_NUMBER)
        }
        val bytes = try {
            parsed.multiply(BigDecimal.valueOf(unit.multiplier))
                .setScale(0, RoundingMode.UNNECESSARY)
                .longValueExact()
        } catch (_: ArithmeticException) {
            return TargetSizeCustomValidation.Invalid(TargetSizeCustomError.INVALID_NUMBER)
        }
        return when {
            bytes < MINIMUM_CUSTOM_BYTES ->
                TargetSizeCustomValidation.Invalid(TargetSizeCustomError.TOO_SMALL)
            bytes > MAXIMUM_CUSTOM_BYTES ->
                TargetSizeCustomValidation.Invalid(TargetSizeCustomError.TOO_LARGE)
            else -> TargetSizeCustomValidation.Valid(bytes)
        }
    }
}

fun formatTargetSize(bytes: Long): String = when (bytes) {
    TargetSizeBytes.KB_200 -> "200 KB"
    TargetSizeBytes.KB_500 -> "500 KB"
    TargetSizeBytes.MB_1 -> "1 MB"
    TargetSizeBytes.MB_2 -> "2 MB"
    else -> formatDecimalBytes(bytes)
}

fun formatDecimalBytes(bytes: Long, locale: Locale = Locale.getDefault()): String {
    require(bytes >= 0L)
    val unit = if (bytes >= 1_000_000L) "MB" else "KB"
    val divisor = if (unit == "MB") 1_000_000.0 else 1_000.0
    val format = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 0
        maximumFractionDigits = if (unit == "MB") 2 else 0
        isGroupingUsed = true
    }
    return "${format.format(bytes / divisor)} $unit"
}

fun reductionPercent(sourceBytes: Long?, resultBytes: Long): Int? {
    if (sourceBytes == null || sourceBytes <= 0L || resultBytes >= sourceBytes) return null
    return (((sourceBytes - resultBytes).toDouble() / sourceBytes.toDouble()) * 100.0)
        .toInt()
        .coerceIn(1, 100)
}

fun targetSizeFilename(title: String): String {
    val stem = title
        .removeSuffix(".pdf")
        .replace(Regex("[^A-Za-z0-9._ -]"), "_")
        .trim(' ', '.', '_')
        .take(80)
        .ifBlank { "Document" }
    return "$stem-smaller.pdf"
}

fun isObviouslyUnrealisticTarget(targetBytes: Long, pageCount: Int): Boolean {
    require(targetBytes > 0L)
    require(pageCount > 0)
    return targetBytes < 8_000L + pageCount * 5_200L
}

fun shouldReuseOriginalPdf(
    safeOriginalAvailable: Boolean,
    sourceBytes: Long?,
    targetBytes: Long,
): Boolean = safeOriginalAvailable && sourceBytes != null && sourceBytes <= targetBytes

fun searchableTextStatus(
    textLayerPageCount: Int,
    pageCount: Int,
    strategies: List<TargetSizeTextStrategy>,
): TargetSizeSearchableTextStatus {
    require(textLayerPageCount in 0..pageCount)
    return when {
        pageCount > 0 && textLayerPageCount == pageCount ->
            TargetSizeSearchableTextStatus.PRESERVED
        textLayerPageCount > 0 -> TargetSizeSearchableTextStatus.PARTIALLY_PRESERVED
        strategies.any { it == TargetSizeTextStrategy.OMIT_UNPOSITIONED_WITH_WARNING } ->
            TargetSizeSearchableTextStatus.NOT_PRESERVED
        else -> TargetSizeSearchableTextStatus.NOT_PRESENT
    }
}
