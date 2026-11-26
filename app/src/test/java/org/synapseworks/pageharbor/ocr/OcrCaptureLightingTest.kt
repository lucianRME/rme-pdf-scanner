package org.synapseworks.pageharbor.ocr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrCaptureLightingTest {
    @Test
    fun dimWarmPaperWarnsAndBrightPaperWithDarkBordersDoesNot() {
        val width = 64
        val height = 80
        fun page(paper: Int) = IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            if (x < 6 || x > 57 || y < 8 || y > 71 || (y % 9 == 0 && x in 14..48)) {
                0xff282320.toInt()
            } else paper
        }

        assertTrue(looksDimForOcr(width, height, page(0xff956d49.toInt())))
        assertFalse(looksDimForOcr(width, height, page(0xffeae4dd.toInt())))
    }

    @Test
    fun transparentOrUnreadableSamplesDoNotWarn() {
        assertFalse(looksDimForOcr(16, 16, IntArray(256)))
        assertFalse(looksDimForOcr(16, 16, IntArray(1)))
        assertFalse(looksDimForOcr(1, 1, intArrayOf(0xff000000.toInt())))
    }
}
