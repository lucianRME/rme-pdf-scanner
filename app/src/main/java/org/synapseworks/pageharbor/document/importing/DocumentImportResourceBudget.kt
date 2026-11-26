package org.synapseworks.pageharbor.document.importing

/** Leave room for SQLite, journals and other applications; this is a byte reserve, not a page cap. */
internal const val IMPORT_STORAGE_RESERVE_BYTES = 64L * 1024L * 1024L

internal fun hasImportStorage(availableBytes: Long, additionalBytes: Long): Boolean =
    availableBytes >= IMPORT_STORAGE_RESERVE_BYTES && additionalBytes >= 0L &&
        additionalBytes <= availableBytes - IMPORT_STORAGE_RESERVE_BYTES

/** Conservative admission for descriptors/URIs and downstream per-page metadata, not decoded images.
 * Retain half of currently available Java heap for the active page, UI and PDF object graph.
 * This cannot predict native codec memory; existing pixel limits and decode failure handling remain.
 */
internal fun hasImportMetadataMemory(availableHeapBytes: Long, pages: Long): Boolean =
    pages >= 0L && availableHeapBytes >= 0L && pages <= (availableHeapBytes / 2L) / 16_384L

/** Decode enough pixels to verify the encoded image. Export chooses its own resolution. */
internal fun importValidationSampleSize(width: Int, height: Int, availableHeapBytes: Long): Int? {
    val reserve = 8L * 1024 * 1024
    if (width <= 0 || height <= 0 || availableHeapBytes <= reserve) return null
    val pixelBudget = minOf(2_500_000L, (availableHeapBytes - reserve) / 4)
    if (pixelBudget == 0L) return null
    var sample = 1
    while (true) {
        val decodedWidth = (width.toLong() + sample - 1) / sample
        val decodedHeight = (height.toLong() + sample - 1) / sample
        if (decodedWidth * decodedHeight <= pixelBudget) return sample
        if (sample > Int.MAX_VALUE / 2) return null
        sample *= 2
    }
}
