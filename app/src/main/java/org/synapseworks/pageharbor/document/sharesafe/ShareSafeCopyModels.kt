package org.synapseworks.pageharbor.document.sharesafe

import android.net.Uri
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import org.synapseworks.pageharbor.document.redaction.RedactionRegion
import org.synapseworks.pageharbor.document.redaction.SecureRedactedPdfPage
import org.synapseworks.pageharbor.document.targetsize.TargetSizeBytes

enum class ShareSafeRecipe {
    UPLOAD_TO_PORTAL,
    SHARE_ID_COPY,
    EMAIL_DOCUMENT,
    ARCHIVE_DOCUMENT,
}

enum class ShareSafeWatermarkPlacement { DIAGONAL, FOOTER, REPEATED }

enum class ShareSafeWatermarkOpacity(val alpha: Int) {
    LIGHT(64),
    NORMAL(96),
    STRONG(144),
}

enum class ShareSafeSearchableTextStatus { PRESERVED, PARTIALLY_PRESERVED, NOT_PRESENT }

sealed interface ShareSafePurposeValidation {
    data class Valid(val normalized: String) : ShareSafePurposeValidation
    data class Invalid(val reason: ShareSafePurposeError) : ShareSafePurposeValidation
}

enum class ShareSafePurposeError { EMPTY, TOO_LONG, TOO_MANY_LINES, CONTROL_CHARACTER }

object ShareSafePurposeValidator {
    const val MAX_CODE_POINTS = 120
    const val MAX_LINES = 3

    fun validate(value: String): ShareSafePurposeValidation {
        val normalized = value.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (normalized.isEmpty()) return ShareSafePurposeValidation.Invalid(ShareSafePurposeError.EMPTY)
        if (normalized.lines().size > MAX_LINES) {
            return ShareSafePurposeValidation.Invalid(ShareSafePurposeError.TOO_MANY_LINES)
        }
        if (normalized.codePointCount(0, normalized.length) > MAX_CODE_POINTS) {
            return ShareSafePurposeValidation.Invalid(ShareSafePurposeError.TOO_LONG)
        }
        if (normalized.codePoints().anyMatch { codePoint ->
                Character.isISOControl(codePoint) && codePoint != '\n'.code && codePoint != '\t'.code
            }
        ) {
            return ShareSafePurposeValidation.Invalid(ShareSafePurposeError.CONTROL_CHARACTER)
        }
        return ShareSafePurposeValidation.Valid(normalized)
    }
}

object ShareSafeDateValidator {
    const val MAX_CODE_POINTS = 40

    fun isValid(value: String): Boolean {
        val trimmed = value.trim()
        return trimmed.isNotEmpty() &&
            trimmed.codePointCount(0, trimmed.length) <= MAX_CODE_POINTS &&
            trimmed.codePoints().noneMatch(Character::isISOControl)
    }
}

data class ShareSafeWatermarkConfig(
    val enabled: Boolean = false,
    val purposeText: String = "",
    val includeDate: Boolean = false,
    val dateText: String = "",
    val placement: ShareSafeWatermarkPlacement = ShareSafeWatermarkPlacement.DIAGONAL,
    val opacity: ShareSafeWatermarkOpacity = ShareSafeWatermarkOpacity.NORMAL,
) {
    val renderedText: String?
        get() {
            if (!enabled) return null
            val purpose = (ShareSafePurposeValidator.validate(purposeText) as? ShareSafePurposeValidation.Valid)
                ?.normalized ?: return null
            return if (includeDate) "$purpose\n${dateText.trim()}" else purpose
        }

    val isValid: Boolean
        get() = !enabled || (
            ShareSafePurposeValidator.validate(purposeText) is ShareSafePurposeValidation.Valid &&
                (!includeDate || ShareSafeDateValidator.isValid(dateText))
            )

    fun withSuggestedLocalDate(
        today: LocalDate = LocalDate.now(),
        locale: Locale = Locale.getDefault(),
    ): ShareSafeWatermarkConfig = copy(
        includeDate = true,
        dateText = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
            .withLocale(locale)
            .format(today),
    )

    fun digest(): String {
        val canonical = listOf(
            enabled.toString(),
            purposeText.replace("\r\n", "\n").replace('\r', '\n'),
            includeDate.toString(),
            dateText,
            placement.name,
            opacity.name,
        ).joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}

data class ShareSafeTargetSizeConfig(
    val enabled: Boolean = false,
    val maximumBytes: Long = TargetSizeBytes.MB_1,
) {
    init {
        require(maximumBytes >= 100_000L)
    }
}

data class ShareSafeRecipePreset(
    val recipe: ShareSafeRecipe,
    val targetSize: ShareSafeTargetSizeConfig,
    val watermark: ShareSafeWatermarkConfig = ShareSafeWatermarkConfig(),
    val preferShare: Boolean,
    val preserveSearchableText: Boolean,
)

object ShareSafeRecipes {
    fun preset(recipe: ShareSafeRecipe): ShareSafeRecipePreset = when (recipe) {
        ShareSafeRecipe.UPLOAD_TO_PORTAL -> ShareSafeRecipePreset(
            recipe = recipe,
            targetSize = ShareSafeTargetSizeConfig(enabled = true, maximumBytes = TargetSizeBytes.MB_1),
            preferShare = false,
            preserveSearchableText = true,
        )
        ShareSafeRecipe.SHARE_ID_COPY -> ShareSafeRecipePreset(
            recipe = recipe,
            targetSize = ShareSafeTargetSizeConfig(),
            preferShare = true,
            preserveSearchableText = false,
        )
        ShareSafeRecipe.EMAIL_DOCUMENT -> ShareSafeRecipePreset(
            recipe = recipe,
            targetSize = ShareSafeTargetSizeConfig(enabled = true, maximumBytes = TargetSizeBytes.MB_2),
            preferShare = true,
            preserveSearchableText = true,
        )
        ShareSafeRecipe.ARCHIVE_DOCUMENT -> ShareSafeRecipePreset(
            recipe = recipe,
            targetSize = ShareSafeTargetSizeConfig(),
            preferShare = false,
            preserveSearchableText = true,
        )
    }
}

internal data class ShareSafeDocumentPage(
    val source: SecureRedactedPdfPage,
    val previewUri: Uri,
    val redactions: List<RedactionRegion> = source.regions,
)

internal data class ShareSafeDocumentInput(
    val title: String,
    val pages: List<ShareSafeDocumentPage>,
    val sourceGeneration: Long,
) {
    init {
        require(title.isNotBlank())
        require(pages.isNotEmpty())
        require(sourceGeneration >= 0L)
    }
}

enum class ShareSafePipelineStage {
    SANITIZING,
    WATERMARKING,
    COMPRESSING,
    FINAL_VERIFYING,
}

object ShareSafePipelineOrder {
    val required: List<ShareSafePipelineStage> = listOf(
        ShareSafePipelineStage.SANITIZING,
        ShareSafePipelineStage.WATERMARKING,
        ShareSafePipelineStage.COMPRESSING,
        ShareSafePipelineStage.FINAL_VERIFYING,
    )
}

enum class ShareSafeFailure {
    INVALID_SOURCE,
    INVALID_REDACTION,
    INVALID_WATERMARK,
    UNSUPPORTED_WATERMARK_GLYPH,
    SOURCE_CHANGED,
    ATTESTATION_FAILED,
    TARGET_NOT_REACHABLE,
    PREPARATION_FAILED,
    FINAL_VERIFICATION_FAILED,
    DESTINATION_UNAVAILABLE,
    WRITE_FAILED,
    PROCESS_INTERRUPTED,
    CANCELED,
}

fun shareSafeFilename(title: String): String {
    val stem = title.removeSuffix(".pdf")
        .replace(Regex("[^A-Za-z0-9._ -]"), "_")
        .trim(' ', '.', '_')
        .take(72)
        .ifBlank { "Document" }
    return "$stem-share-safe.pdf"
}

sealed interface ShareSafeSaveStatus {
    data object Idle : ShareSafeSaveStatus
    data object ChoosingDestination : ShareSafeSaveStatus
    data object Saving : ShareSafeSaveStatus
    data class Saved(val bytes: Long) : ShareSafeSaveStatus
    data class Failed(val reason: ShareSafeFailure) : ShareSafeSaveStatus
}
