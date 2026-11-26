package org.synapseworks.pageharbor.ui.home

import org.synapseworks.pageharbor.image.BitmapDisplaySize
import org.synapseworks.pageharbor.image.displayBitmapSampleSize

/** Compatibility entry point for the existing 1,600px inspection-preview contract. */
internal object DocumentPreviewDecodePolicy {
    fun calculateInSampleSize(width: Int, height: Int): Int? =
        displayBitmapSampleSize(width, height, BitmapDisplaySize(1600, 1600))
}
