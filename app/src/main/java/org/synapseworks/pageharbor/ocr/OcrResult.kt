package org.synapseworks.pageharbor.ocr

/**
 * In-memory text recognized from an OCR request, kept in the same order as its input pages.
 *
 * Recognized text is sensitive document content and must not be logged or retained by default.
 */
data class OcrResult(
    val pages: List<OcrPageResult>,
) {
    val plainText: String
        get() = pages.joinToString(separator = "\n\n") { it.text }
}

data class OcrPageResult(
    val pageIndex: Int,
    val text: String,
    val error: OcrPageError? = null,
    /** Null only for an engine result that cannot safely supply positioned text yet. */
    val layout: OcrPageLayout? = null,
    val actualScript: OcrScript? = null,
)

/**
 * A safe, engine-neutral description of an OCR failure for one page.
 *
 * It deliberately excludes exception messages and document metadata, which could contain
 * sensitive information. A failed page is retained in [OcrResult.pages] with its original index.
 */
enum class OcrPageError {
    IMAGE_UNREADABLE,
    RECOGNITION_FAILED,
}
