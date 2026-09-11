package org.synapseworks.pageharbor.image

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentImageFilterEngineTest {
    @Test
    fun originalReturnsTheCallerOwnedSourceWithoutChangingPixels() {
        val source = image(0x7f123456, 0xffabcdef.toInt())
        val output = DocumentImageFilterEngine.apply(source, DocumentFilter.ORIGINAL)
        assertSame(source, output)
        assertArrayEquals(source.pixels, output.pixels)
    }

    @Test
    fun grayscaleUsesWeightedLuminanceAndPreservesAlpha() {
        val source = image(0x80ff0000.toInt())
        val output = DocumentImageFilterEngine.apply(source, DocumentFilter.GRAYSCALE)
        val pixel = output.pixels.single()
        assertEquals(0x80, pixel ushr 24)
        assertEquals(54, pixel ushr 16 and 0xff)
        assertEquals(pixel ushr 16 and 0xff, pixel ushr 8 and 0xff)
        assertEquals(pixel ushr 8 and 0xff, pixel and 0xff)
        assertArrayEquals(intArrayOf(0x80ff0000.toInt()), source.pixels)
    }

    @Test
    fun highContrastIncreasesControlledRangeAndClampsChannels() {
        val output = DocumentImageFilterEngine.apply(
            image(0xff606060.toInt(), 0xffa0a0a0.toInt()),
            DocumentFilter.HIGH_CONTRAST,
        )
        assertEquals(78, output.pixels[0] and 0xff)
        assertEquals(178, output.pixels[1] and 0xff)
    }

    @Test
    fun autoEnhanceExpandsLowContrastWithoutOutlierDominance() {
        val source = ArgbImage(
            width = 100,
            height = 1,
            pixels = IntArray(100) { index ->
                when {
                    index < 50 -> 0xff707070.toInt()
                    index < 99 -> 0xff787878.toInt()
                    else -> 0xffffffff.toInt()
                }
            },
        )
        val output = DocumentImageFilterEngine.apply(source, DocumentFilter.AUTO_ENHANCE)
        assertTrue((output.pixels[50] and 0xff) - (output.pixels[0] and 0xff) >= 200)
    }

    @Test
    fun autoEnhanceDoesNotDestroyAlreadyHighContrastPixels() {
        val source = image(0xff000000.toInt(), 0xffffffff.toInt())
        assertArrayEquals(source.pixels, DocumentImageFilterEngine.apply(source, DocumentFilter.AUTO_ENHANCE).pixels)
    }

    @Test
    fun blackAndWhiteUsesOnlyBlackOrWhiteAndSeparatesDocumentFixture() {
        val output = DocumentImageFilterEngine.apply(
            image(0xff202020.toInt(), 0xff404040.toInt(), 0xffd0d0d0.toInt(), 0xffffffff.toInt()),
            DocumentFilter.BLACK_AND_WHITE,
        )
        assertTrue(output.pixels.all { it and 0x00ffffff == 0 || it and 0x00ffffff == 0x00ffffff })
        assertEquals(0, output.pixels.first() and 0xff)
        assertEquals(255, output.pixels.last() and 0xff)
    }

    @Test
    fun uniformAndTinyImagesAreSafeAndDeterministic() {
        val source = image(0x7f808080)
        DocumentFilter.entries.forEach { filter ->
            val first = DocumentImageFilterEngine.apply(source, filter)
            val second = DocumentImageFilterEngine.apply(source, filter)
            assertArrayEquals(first.pixels, second.pixels)
            assertEquals(0x7f, first.pixels.single() ushr 24)
        }
    }

    @Test
    fun largeDimensionValidationDoesNotOverflow() {
        assertTrue(ArgbImage.isSupportedDimensions(46_340, 46_340))
        assertEquals(false, ArgbImage.isSupportedDimensions(46_341, 46_341))
    }

    @Test
    fun scanlineFilteringMatchesWholeImageFilteringForEveryNonOriginalFilter() {
        val source = ArgbImage(
            width = 4,
            height = 2,
            pixels = intArrayOf(
                0xff101820.toInt(), 0xff405060.toInt(), 0xff708090.toInt(), 0xffa0b0c0.toInt(),
                0xff203040.toInt(), 0xff506070.toInt(), 0xff8090a0.toInt(), 0xffd0e0f0.toInt(),
            ),
        )

        DocumentFilter.entries.filterNot { it == DocumentFilter.ORIGINAL }.forEach { filter ->
            val histogram = if (DocumentImageFilterEngine.requiresHistogram(filter)) {
                IntArray(256).also { counts ->
                    source.pixels.asList().chunked(source.width).forEach { values ->
                        DocumentImageFilterEngine.accumulateLuminanceHistogram(
                            values.toIntArray(),
                            counts,
                        )
                    }
                }
            } else {
                null
            }
            val plan = DocumentImageFilterEngine.createRowFilterPlan(
                filter,
                histogram,
                source.pixels.size,
            )
            val rowOutput = source.pixels.copyOf()
            rowOutput.indices.chunked(source.width).forEach { indices ->
                val row = indices.map(rowOutput::get).toIntArray()
                DocumentImageFilterEngine.applyRowFilterInPlace(row, plan)
                indices.forEachIndexed { rowIndex, outputIndex ->
                    rowOutput[outputIndex] = row[rowIndex]
                }
            }

            assertArrayEquals(DocumentImageFilterEngine.apply(source, filter).pixels, rowOutput)
        }
    }

    private fun image(vararg pixels: Int): ArgbImage = ArgbImage(pixels.size, 1, pixels)
}
