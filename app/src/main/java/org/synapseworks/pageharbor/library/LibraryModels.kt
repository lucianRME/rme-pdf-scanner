package org.synapseworks.pageharbor.library

import java.text.Normalizer
import java.util.Locale

enum class LibraryOcrStatus {
    NOT_INDEXED,
    PARTIAL,
    INDEXED,
    FAILED,
}

enum class LibraryDocumentState {
    ACTIVE,
    PENDING,
}

enum class LibrarySortOrder {
    MODIFIED_DESC,
    CREATED_DESC,
    TITLE_ASC,
}

enum class LibrarySearchMatch {
    TITLE,
    FOLDER,
    OCR,
}

enum class LibraryOcrArtifactVerification {
    CURRENT_VERIFIED,
    LEGACY_UNVERIFIED,
}

enum class LibraryOcrCorrectionAlignment {
    LINE_ALIGNED,
    FREEFORM,
}

enum class LibraryOcrCommitResult {
    APPLIED,
    STALE,
    NOT_FOUND,
}

enum class OcrBatchJobState {
    READY,
    PROCESSING,
    INTERRUPTED,
    CANCELLED,
    COMPLETED,
    FAILED,
}

enum class OcrBatchItemState {
    PENDING,
    RETRY_PENDING,
    WAITING_FOR_MODEL,
    PROCESSING,
    COMPLETED,
    FAILED,
    SKIPPED,
}

enum class OcrBatchCompletionResult {
    APPLIED,
    STALE_INPUT,
    STALE_CLAIM,
    NOT_FOUND,
}

data class LibraryFolder(
    val id: String,
    val name: String,
    val documentCount: Int = 0,
    val parentFolderId: String? = null,
)

data class LibraryDocumentSummary(
    val id: String,
    val title: String,
    val createdAtMillis: Long,
    val modifiedAtMillis: Long,
    val pageCount: Int,
    val folderId: String?,
    val folderName: String?,
    val thumbnailRelativePath: String?,
    val ocrStatus: LibraryOcrStatus,
    val searchMatch: LibrarySearchMatch? = null,
    val searchSnippet: String? = null,
    val matchingPageId: String? = null,
    val matchingPagePosition: Int? = null,
)

data class LibraryPageRecord(
    val id: String,
    val position: Int,
    val relativePath: String,
    val contentType: String,
    val sourceCategory: String,
    val width: Int?,
    val height: Int?,
    val sourceByteCount: Long?,
    val rotationDegrees: Int,
    val filterName: String,
    val ocrText: String?,
    val ocrError: String?,
    val contentSha256: String? = null,
    val visualRevision: Long = 0,
    val ocrStateRevision: Long = 0,
    val activeOcrArtifactRevision: Long? = null,
)

data class LibraryDocumentRecord(
    val summary: LibraryDocumentSummary,
    val pages: List<LibraryPageRecord>,
)

data class LibraryOcrPageSnapshot(
    val documentId: String,
    val pageId: String,
    val pagePosition: Int,
    val documentContentRevision: Long,
    val pageVisualRevision: Long,
    val ocrStateRevision: Long,
    val activeArtifactRevision: Long?,
    val contentSha256: String?,
    val rotationDegrees: Int,
    val filterName: String,
)

data class LibraryOcrArtifactDraft(
    val inputFingerprintVersion: Int,
    val inputFingerprint: String,
    val contentSha256: String,
    val rotationDegrees: Int,
    val filterName: String,
    val uprightWidth: Int,
    val uprightHeight: Int,
    val coordinateSystemVersion: Int,
    val transformVersion: Int,
    val actualScript: String,
    val recognizerId: String,
    val pipelineVersion: String,
    val clientVersion: String?,
    val delivery: String,
    val recognizedAtMillis: Long,
    val rawText: String,
    val lines: List<LibraryOcrLineDraft> = emptyList(),
) {
    init {
        require(inputFingerprintVersion > 0)
        require(inputFingerprint.isNotBlank())
        require(contentSha256.isNotBlank())
        require(rotationDegrees in setOf(0, 90, 180, 270))
        require(filterName.isNotBlank())
        require(uprightWidth > 0 && uprightHeight > 0)
        require(coordinateSystemVersion > 0 && transformVersion > 0)
        require(actualScript.isSupportedOcrScriptId())
        require(recognizerId.isNotBlank())
        require(pipelineVersion.isNotBlank())
        require(delivery.isNotBlank())
        require(recognizedAtMillis >= 0L)
        require(lines.map(LibraryOcrLineDraft::lineOrdinal) == lines.indices.toList())
        require(lines.size <= MAX_OCR_LINES_PER_PAGE)
    }
}

data class LibraryOcrLineDraft(
    val lineOrdinal: Int,
    val rawText: String,
    val topLeftX: Double,
    val topLeftY: Double,
    val topRightX: Double,
    val topRightY: Double,
    val bottomRightX: Double,
    val bottomRightY: Double,
    val bottomLeftX: Double,
    val bottomLeftY: Double,
    val baselineStartX: Double,
    val baselineStartY: Double,
    val baselineEndX: Double,
    val baselineEndY: Double,
    val baselineAngleDegrees: Double,
    val writingOrientation: String? = null,
) {
    init {
        require(lineOrdinal >= 0)
        val coordinates = listOf(
            topLeftX,
            topLeftY,
            topRightX,
            topRightY,
            bottomRightX,
            bottomRightY,
            bottomLeftX,
            bottomLeftY,
            baselineStartX,
            baselineStartY,
            baselineEndX,
            baselineEndY,
        )
        require(coordinates.all { it.isFinite() && it in 0.0..1.0 })
        require(baselineAngleDegrees.isFinite())
    }
}

data class LibraryOcrCorrectionDraft(
    val correctedText: String,
    val alignment: LibraryOcrCorrectionAlignment,
    val correctedLines: List<String> = emptyList(),
) {
    init {
        if (alignment == LibraryOcrCorrectionAlignment.FREEFORM) {
            require(correctedLines.isEmpty())
        }
    }
}

data class LibraryOcrPageOutcomeDraft(
    val expected: LibraryOcrPageSnapshot,
    val artifact: LibraryOcrArtifactDraft? = null,
    val safeErrorCode: String? = null,
) {
    init {
        require((artifact == null) != (safeErrorCode == null))
        require(safeErrorCode == null || safeErrorCode.isSafeOcrErrorCode())
    }
}

data class LibraryEffectiveOcrPage(
    val documentId: String,
    val pageId: String,
    val pagePosition: Int,
    val effectiveText: String,
    val rawText: String,
    val correctedText: String?,
    val alignment: LibraryOcrCorrectionAlignment?,
    val artifactRevision: Long,
    val activeArtifactRevision: Long,
    val correctionBaseArtifactRevision: Long?,
    val documentContentRevision: Long,
    val pageVisualRevision: Long,
    val ocrStateRevision: Long,
    val inputFingerprintVersion: Int?,
    val inputFingerprint: String?,
    val coordinateSystemVersion: Int?,
    val transformVersion: Int?,
    val uprightWidth: Int?,
    val uprightHeight: Int?,
    val verification: LibraryOcrArtifactVerification,
    val actualScript: String?,
    val recognizerId: String,
    val lines: List<LibraryEffectiveOcrLine>,
)

data class LibraryEffectiveOcrLine(
    val lineOrdinal: Int,
    val text: String,
    val topLeftX: Double,
    val topLeftY: Double,
    val topRightX: Double,
    val topRightY: Double,
    val bottomRightX: Double,
    val bottomRightY: Double,
    val bottomLeftX: Double,
    val bottomLeftY: Double,
    val baselineStartX: Double,
    val baselineStartY: Double,
    val baselineEndX: Double,
    val baselineEndY: Double,
    val baselineAngleDegrees: Double,
    val writingOrientation: String?,
)

/**
 * One bounded page snapshot for the OCR review UI.
 *
 * [rawText] and [actualScript] always describe the latest active recognition artifact. A
 * correction can remain pinned to an older artifact after re-recognition, so review must not use
 * [LibraryEffectiveOcrPage.rawText] as a synonym for the latest raw result.
 */
data class LibraryOcrReviewPage(
    val snapshot: LibraryOcrPageSnapshot,
    val rawText: String?,
    val effectiveText: String?,
    val correctedText: String?,
    val actualScript: String?,
    val recognizedAtMillis: Long?,
    val correctionBaseArtifactRevision: Long?,
    val rawLines: List<String>,
) {
    val hasCorrection: Boolean
        get() = correctedText != null
}

data class LibrarySearchHit(
    val documentId: String,
    val pageId: String?,
    val currentPagePosition: Int?,
    val matchType: LibrarySearchMatch,
    val snippet: String?,
)

data class OcrBatchTarget(
    val itemId: String,
    val documentId: String,
    val pageId: String,
    val ordinal: Int,
    val requestedScriptSelection: String,
    val resolvedScript: String,
    val expectedInputFingerprintVersion: Int? = null,
    val expectedInputFingerprint: String? = null,
) {
    init {
        require(requestedScriptSelection.isSupportedOcrSelectionId())
        require(resolvedScript.isSupportedOcrScriptId())
    }
}

data class OcrBatchClaim(
    val jobId: String,
    val itemId: String,
    val documentId: String,
    val pageId: String,
    val runGeneration: Long,
    val claimToken: String,
    val requestedScriptSelection: String,
    val resolvedScript: String,
    val expected: LibraryOcrPageSnapshot,
    val expectedInputFingerprintVersion: Int?,
    val expectedInputFingerprint: String?,
)

data class OcrBatchProgressSnapshot(
    val documentTotal: Int,
    val completedDocuments: Int,
    val retryableItems: Int,
)

data class LibraryOcrRestorePageState(
    val backupPageId: String,
    val ocrStateRevision: Long,
    val activeArtifactRevision: Long?,
    val ocrError: String?,
    val artifacts: List<LibraryPageOcrArtifactEntity>,
    val lines: List<LibraryPageOcrLineEntity>,
    val correction: LibraryPageOcrCorrectionEntity?,
    val correctionLines: List<LibraryPageOcrCorrectionLineEntity>,
)

sealed interface LibraryResult<out T> {
    data class Success<T>(val value: T, val warning: LibraryWarning? = null) : LibraryResult<T>
    data class Failure(val reason: LibraryError) : LibraryResult<Nothing>
}

enum class LibraryWarning {
    THUMBNAIL_UNAVAILABLE,
}

enum class LibraryError {
    EMPTY_DOCUMENT,
    PAGE_LIMIT_EXCEEDED,
    INVALID_SELECTION,
    TITLE_REQUIRED,
    DUPLICATE_FOLDER,
    DOCUMENT_NOT_FOUND,
    FOLDER_NOT_FOUND,
    SOURCE_MISSING,
    SOURCE_TOO_LARGE,
    STORAGE_UNAVAILABLE,
    DATABASE_UNAVAILABLE,
    CORRUPTED_RECORD,
    SAVE_REQUIRED,
    OPERATION_INTERRUPTED,
}

internal fun normalizeLibraryTitle(value: String): String =
    value.trim().replace(Regex("\\s+"), " ").take(MAX_LIBRARY_TITLE_LENGTH)

internal fun normalizeFolderName(value: String): String =
    value.trim().replace(Regex("\\s+"), " ").take(MAX_FOLDER_NAME_LENGTH)

internal fun String.toFtsPrefixQuery(): String? {
    val normalized = normalizeSearchText(this)
    val tokens = SEARCH_TOKEN_REGEX.findAll(normalized)
        .flatMap { match -> searchTermsForToken(match.value).asSequence() }
        .filter(String::isNotBlank)
        .take(MAX_SEARCH_TOKENS)
        .toList()
    if (tokens.isEmpty()) return null
    // Tokens contain only letters and numbers. Whitespace is FTS4's portable implicit-AND
    // syntax; the explicit AND operator is not enabled by every Android SQLite build.
    return tokens.joinToString(" ") { token -> "$token*" }
}

internal fun searchAuxiliaryTerms(value: String): String = buildList {
    SEARCH_TOKEN_REGEX.findAll(normalizeSearchText(value)).forEach { match ->
        addAll(cjkTerms(match.value, preferBigrams = false))
    }
}.joinToString(" ")

internal fun normalizeSearchText(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFC).lowercase(Locale.ROOT)

private fun searchTermsForToken(token: String): List<String> {
    val cjk = cjkTerms(token, preferBigrams = true)
    if (cjk.isEmpty()) return listOf(token.take(MAX_SEARCH_TOKEN_LENGTH))
    val nonCjk = buildString {
        token.codePoints().forEach { codePoint ->
            if (!isCjkSearchCodePoint(codePoint)) appendCodePoint(codePoint) else append(' ')
        }
    }.split(' ').filter(String::isNotBlank).map { it.take(MAX_SEARCH_TOKEN_LENGTH) }
    return nonCjk + cjk
}

private fun cjkTerms(value: String, preferBigrams: Boolean): List<String> {
    val result = mutableListOf<String>()
    val run = mutableListOf<Int>()
    fun flush() {
        if (run.isEmpty()) return
        if (!preferBigrams || run.size == 1) {
            run.forEach { codePoint -> result += "u1${codePoint.toString(16).padStart(6, '0')}" }
        }
        if (run.size > 1) {
            run.zipWithNext().forEach { (first, second) ->
                result += "u2${first.toString(16).padStart(6, '0')}${second.toString(16).padStart(6, '0')}"
            }
        }
        run.clear()
    }
    value.codePoints().forEach { codePoint ->
        if (isCjkSearchCodePoint(codePoint)) run += codePoint else flush()
    }
    flush()
    return result
}

private fun isCjkSearchCodePoint(codePoint: Int): Boolean = when (Character.UnicodeScript.of(codePoint)) {
    Character.UnicodeScript.HAN,
    Character.UnicodeScript.HIRAGANA,
    Character.UnicodeScript.KATAKANA,
    Character.UnicodeScript.HANGUL,
    -> true

    else -> false
}

const val MAX_LIBRARY_TITLE_LENGTH = 120
const val MAX_FOLDER_NAME_LENGTH = 80
private const val MAX_SEARCH_TOKEN_LENGTH = 64
private const val MAX_SEARCH_TOKENS = 8
const val MAX_OCR_LINES_PER_PAGE = 5_000
internal const val AUTOMATIC_OCR_SELECTION_ID = "AUTOMATIC"
internal val SUPPORTED_OCR_SCRIPT_IDS = setOf("LATIN", "CHINESE", "DEVANAGARI", "JAPANESE", "KOREAN")
internal fun String.isSupportedOcrScriptId(): Boolean = this in SUPPORTED_OCR_SCRIPT_IDS
internal fun String.isSupportedOcrSelectionId(): Boolean =
    this == AUTOMATIC_OCR_SELECTION_ID || isSupportedOcrScriptId()
internal fun String.isSafeOcrErrorCode(): Boolean =
    length in 1..64 && matches(Regex("[A-Z][A-Z0-9_]*"))
private val SEARCH_TOKEN_REGEX = Regex("[\\p{L}\\p{M}\\p{N}]+")
