package org.synapseworks.pageharbor.image

import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class ManualImageAdjustmentsTest {
    private fun gray(n: Int) = -0x1000000 or (n shl 16) or (n shl 8) or n
    @Test fun neutralIsExactForEveryChannelAndPreservesOriginalIdentity() {
        val pixels = IntArray(256) { gray(it) }
        val source = ArgbImage(256, 1, pixels)
        assertSame(source, DocumentImageFilterEngine.apply(source, DocumentFilter.ORIGINAL, ManualImageAdjustments()))
        for (filter in DocumentFilter.entries) assertArrayEquals(
            DocumentImageFilterEngine.apply(source, filter).pixels,
            DocumentImageFilterEngine.apply(source, filter, ManualImageAdjustments()).pixels,
        )
        assertArrayEquals(IntArray(256) { gray(it) }, pixels)
    }
    @Test fun brightnessEndpointsAndContrastMidpointAreObservable() {
        val values = listOf(0, 32, 64, 128, 192, 255)
        values.forEach {
            assertEquals(gray(255), ManualImageAdjustments(100).apply(gray(it)))
            assertEquals(gray(0), ManualImageAdjustments(-100).apply(gray(it)))
            assertEquals(gray(128), ManualImageAdjustments(contrast = -100).apply(gray(it)))
        }
        assertEquals(gray(0), ManualImageAdjustments(contrast = 100).apply(gray(64)))
        assertEquals(gray(255), ManualImageAdjustments(contrast = 100).apply(gray(192)))
        assertEquals(gray(99), ManualImageAdjustments(20, 25).apply(gray(64)))
    }
    @Test fun representativeColorAlphaAndTransparencyArePreserved() {
        assertEquals(0x80636567.toInt(), ManualImageAdjustments(20, 0).apply(0x80303234.toInt()))
        assertEquals(0x00112233, ManualImageAdjustments(100, 100).apply(0x00112233))
        assertEquals(0x80000000.toInt(), ManualImageAdjustments(-100).apply(0x80ffffff.toInt()).let { it })
    }
    @Test fun filterRunsBeforeManualAndEditingAlwaysStartsFromOriginal() {
        val source = ArgbImage(1, 1, intArrayOf(gray(64)))
        val before = source.pixels.copyOf()
        val first = DocumentImageFilterEngine.apply(source, DocumentFilter.HIGH_CONTRAST, ManualImageAdjustments(20))
        assertEquals(gray(80), first.pixels.single())
        val second = DocumentImageFilterEngine.apply(source, DocumentFilter.ORIGINAL, ManualImageAdjustments(20))
        assertEquals(gray(115), second.pixels.single())
        assertArrayEquals(before, source.pixels)
        assertArrayEquals(first.pixels, DocumentImageFilterEngine.apply(source, DocumentFilter.HIGH_CONTRAST, ManualImageAdjustments(20)).pixels)
    }
    @Test fun invalidParametersFailBeforeAnyProcessing() {
        listOf(-101,101,Int.MIN_VALUE,Int.MAX_VALUE).forEach {
            try { ManualImageAdjustments(it); fail() } catch (_: IllegalArgumentException) {}
            try { ManualImageAdjustments(contrast = it); fail() } catch (_: IllegalArgumentException) {}
        }
    }
    @Test fun cancelledTransformCannotMutateOriginalPixels() {
        val source = ArgbImage(200, 200, IntArray(40_000) { gray(64) })
        val before = source.pixels.copyOf()
        var checkpoints = 0
        try {
            DocumentImageFilterEngine.apply(source, DocumentFilter.ORIGINAL, ManualImageAdjustments(20)) {
                if (++checkpoints == 2) throw CancellationException()
            }
            fail()
        } catch (_: CancellationException) {}
        assertEquals(2, checkpoints)
        assertArrayEquals(before, source.pixels)
    }
}
