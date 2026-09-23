package org.synapseworks.pageharbor.library

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
    OCR,
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
)

data class LibraryDocumentRecord(
    val summary: LibraryDocumentSummary,
    val pages: List<LibraryPageRecord>,
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
    val tokens = Regex("[\\p{L}\\p{N}]+").findAll(lowercase(Locale.ROOT))
        .map { match -> match.value.take(MAX_SEARCH_TOKEN_LENGTH) }
        .filter(String::isNotBlank)
        .take(MAX_SEARCH_TOKENS)
        .toList()
    if (tokens.isEmpty()) return null
    // Tokens contain only letters and numbers. Whitespace is FTS4's portable implicit-AND
    // syntax; the explicit AND operator is not enabled by every Android SQLite build.
    return tokens.joinToString(" ") { token -> "$token*" }
}

const val MAX_LIBRARY_TITLE_LENGTH = 120
const val MAX_FOLDER_NAME_LENGTH = 80
private const val MAX_SEARCH_TOKEN_LENGTH = 64
private const val MAX_SEARCH_TOKENS = 8
