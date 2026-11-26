package org.synapseworks.pageharbor.document.crop

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportCropGeometryTest {
    private val width = 240
    private val height = 320
    private val paper = PageCrop(listOf(
        CropPoint(.19f, .085f), CropPoint(.83f, .13f),
        CropPoint(.91f, .93f), CropPoint(.07f, .89f),
    ))

    @Test
    fun fitsAnIlluminatedTrapezoidWithoutCroppingTheContent() {
        val proposal = requireNotNull(ImportCropDetector.detect(width, height, scene(paper)))
        paper.corners.zip(proposal.corners).forEach { (expected, actual) ->
            assertTrue(abs(expected.x - actual.x) < .012f)
            assertTrue(abs(expected.y - actual.y) < .012f)
        }
    }

    @Test
    fun abstainsOnAFullBleedScanAndLowContrastPaper() {
        assertNull(ImportCropDetector.detect(width, height, scene(PageCrop.FULL)))
        assertNull(ImportCropDetector.detect(width, height, scene(paper, weakContrast = true)))
    }

    @Test
    fun abstainsWhenTwoPaperEdgesAreOutsideTheFrame() {
        val clipped = PageCrop(listOf(
            CropPoint(0f, 0f), CropPoint(.82f, 0f), CropPoint(.88f, .91f), CropPoint(0f, .91f),
        ))
        assertNull(ImportCropDetector.detect(width, height, scene(clipped)))
    }

    @Test
    fun boundsManualCornersAndOutputSize() {
        val valid = paper.moveCorner(0, .04f, .03f)
        assertTrue(valid != paper)
        assertEquals(paper, paper.moveCorner(0, .95f, .95f))
        val (w, h) = valid.outputSize(16_000, 21_000)
        assertTrue(w <= 4096 && h <= 4096 && w.toLong() * h <= 12_000_000)
    }

    @Test
    fun checksForCancellationDuringAnalysis() {
        var checks = 0
        val cancelled = runCatching {
            ImportCropDetector.detect(width, height, scene(paper)) {
                if (++checks == 12) error("Stop")
            }
        }.exceptionOrNull()
        assertEquals("Stop", cancelled?.message)
    }

    private fun scene(page: PageCrop, weakContrast: Boolean = false): IntArray =
        IntArray(width * height) { index ->
            val px = (index % width).toFloat() / (width - 1)
            val py = (index / width).toFloat() / (height - 1)
            val inside = page.corners.indices.all { i ->
                val a = page.corners[i]
                val b = page.corners[(i + 1) % page.corners.size]
                (b.x - a.x) * (py - a.y) - (b.y - a.y) * (px - a.x) >= 0
            }
            val value = when {
                !inside -> if (weakContrast) 193 else 69 + (px * 16).toInt()
                weakContrast -> 208
                px in .33f.. .68f && py in .26f.. .61f && (index / width) % 27 in 0..2 -> 38
                else -> 173 + (px * 63).toInt()
            }
            0xff000000.toInt() or (value * 0x010101)
        }
}
