package org.synapseworks.pageharbor.document.importing

import java.io.InputStream
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

const val MAX_PDF_IMPORT_BYTES = 128L * 1024L * 1024L
const val MAX_PDF_RENDER_DIMENSION = 4_096
const val MAX_PDF_RENDER_PIXELS = 12_500_000L
const val PDF_RENDER_SCALE = 2f

val SUPPORTED_IMPORT_MIME_TYPES = arrayOf(
    "image/jpeg",
    "image/png",
    "image/webp",
    "application/pdf",
)

enum class DocumentImportOrigin {
    PICKER,
    INBOUND_SHARE,
}

enum class DocumentImportError {
    BUSY,
    EMPTY_INPUT,
    UNSUPPORTED_TYPE,
    UNREADABLE_SOURCE,
    INVALID_IMAGE,
    PDF_UNREADABLE,
    PAGE_LIMIT_EXCEEDED,
    SOURCE_TOO_LARGE,
    TEMPORARY_FILE_FAILED,
    INTERRUPTED,
}

sealed interface DocumentImportUiState {
    data object Idle : DocumentImportUiState
    data object Selecting : DocumentImportUiState

    data class Processing(
        val completedItems: Int,
        val totalItems: Int,
        val preparedPages: Int,
    ) : DocumentImportUiState

    data class Completed(
        val importedPages: Int,
        val skippedItems: Int,
        val appended: Boolean,
    ) : DocumentImportUiState

    data object Cancelled : DocumentImportUiState
    data class Error(val reason: DocumentImportError) : DocumentImportUiState
}

data class PdfRenderSize(val width: Int, val height: Int)

/** Bounded 144-dpi-first rendering without ever exceeding the shared image working-set limits. */
fun calculatePdfRenderSize(
    sourceWidth: Int,
    sourceHeight: Int,
    preferredScale: Float = PDF_RENDER_SCALE,
    maxDimension: Int = MAX_PDF_RENDER_DIMENSION,
    maxPixels: Long = MAX_PDF_RENDER_PIXELS,
): PdfRenderSize? {
    if (sourceWidth <= 0 || sourceHeight <= 0 || preferredScale <= 0f) return null
    if (maxDimension <= 0 || maxPixels <= 0L) return null
    val dimensionScale = min(
        maxDimension.toDouble() / sourceWidth,
        maxDimension.toDouble() / sourceHeight,
    )
    val pixelScale = sqrt(maxPixels.toDouble() / (sourceWidth.toLong() * sourceHeight.toLong()))
    val scale = min(preferredScale.toDouble(), min(dimensionScale, pixelScale))
    if (!scale.isFinite() || scale <= 0.0) return null
    val width = floor(sourceWidth * scale).toInt().coerceAtLeast(1)
    val height = floor(sourceHeight * scale).toInt().coerceAtLeast(1)
    if (width > maxDimension || height > maxDimension) return null
    if (width.toLong() * height.toLong() > maxPixels) return null
    return PdfRenderSize(width, height)
}

fun isSupportedDeclaredShareType(contentType: String?): Boolean {
    val normalized = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return false
    return normalized == "image/*" || normalized in SUPPORTED_IMPORT_MIME_TYPES
}

/** Uses file signatures, not provider labels, to decide which production decoder may open input. */
fun sniffSupportedContentType(input: InputStream): String? {
    val header = ByteArray(12)
    var count = 0
    while (count < header.size) {
        val read = input.read(header, count, header.size - count)
        if (read < 0) break
        if (read == 0) continue
        count += read
    }
    if (count >= 4 &&
        header[0] == 0xFF.toByte() &&
        header[1] == 0xD8.toByte() &&
        header[2] == 0xFF.toByte()
    ) return "image/jpeg"
    if (count >= 8 && header.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE)) {
        return "image/png"
    }
    if (
        count >= 12 &&
        header.copyOfRange(0, 4).contentEquals(RIFF_SIGNATURE) &&
        header.copyOfRange(8, 12).contentEquals(WEBP_SIGNATURE)
    ) return "image/webp"
    if (count >= 5 && header.copyOfRange(0, 5).contentEquals(PDF_SIGNATURE)) {
        return "application/pdf"
    }
    return null
}

private val PNG_SIGNATURE = byteArrayOf(
    0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
)
private val RIFF_SIGNATURE = "RIFF".encodeToByteArray()
private val WEBP_SIGNATURE = "WEBP".encodeToByteArray()
private val PDF_SIGNATURE = "%PDF-".encodeToByteArray()
