package org.synapseworks.pageharbor.document.session

/** Encoded camera sources are not working bitmaps. Original bytes stay intact;
 * every general preview/recognition/export decoder retains its own smaller budget. */
val DEFAULT_DOCUMENT_SOURCE_LIMITS = DEFAULT_DOCUMENT_INPUT_LIMITS.copy(
    maxDecodedPixelCount = 100_000_000L,
    maxImageDimension = 16_384,
)

internal fun sourceLimitsForWorking(limits: DocumentInputLimits): DocumentInputLimits =
    if (limits == DEFAULT_DOCUMENT_INPUT_LIMITS) DEFAULT_DOCUMENT_SOURCE_LIMITS else limits

/** Ceil division matches codec sampling; never allocate the full large source first. */
fun documentWorkingSampleSize(
    width: Int,
    height: Int,
    limits: DocumentInputLimits = DEFAULT_DOCUMENT_INPUT_LIMITS,
): Int? {
    if (sourceLimitsForWorking(limits).imageConstraintViolation(DocumentImageMetadata(width=width,height=height)) != null) return null
    var sample = 1
    while (true) {
        val w = (width.toLong() + sample - 1) / sample
        val h = (height.toLong() + sample - 1) / sample
        if (w <= limits.maxImageDimension && h <= limits.maxImageDimension && w*h <= limits.maxDecodedPixelCount) return sample
        if (sample > Int.MAX_VALUE/2) return null
        sample *= 2
    }
}
