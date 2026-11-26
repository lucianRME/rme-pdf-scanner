package org.synapseworks.pageharbor.image

import kotlin.math.roundToInt

/** Non-destructive, source-relative manual visual settings; never an OCR input transform. */
data class ManualImageAdjustments(val brightness: Int = 0, val contrast: Int = 0) {
    init { require(brightness in -100..100 && contrast in -100..100) }
    val isNeutral: Boolean get() = brightness == 0 && contrast == 0
    fun apply(pixel: Int): Int {
        if (isNeutral || pixel ushr 24 == 0) return pixel
        fun channel(value: Int) = ((value - 128) * (1f + contrast / 100f) + 128f + brightness * 255f / 100f)
            .roundToInt().coerceIn(0, 255)
        return (pixel and -0x1000000) or (channel(pixel ushr 16 and 255) shl 16) or
            (channel(pixel ushr 8 and 255) shl 8) or channel(pixel and 255)
    }
}
