package org.synapseworks.pageharbor.image

import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDocumentCleanupFilterTest {
    @Test
    fun cleanupReducesPaperShadowVariationWithoutRemovingDarkText() {
        val width = 192
        val height = 96
        val source = ArgbImage(width, height, IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            if (y in 42..45 && x in 12 until width - 12) gray(0) else gray(160 + x * 80 / (width - 1))
        })
        val output = DocumentImageFilterEngine.apply(source, DocumentFilter.DOCUMENT_CLEANUP)
        val shadow = 16 * width + 16
        val light = 16 * width + 176
        val beforeDifference = abs(channel(source.pixels[shadow]) - channel(source.pixels[light]))
        val afterDifference = abs(channel(output.pixels[shadow]) - channel(output.pixels[light]))
        assertTrue(afterDifference < beforeDifference / 2)
        assertTrue(channel(output.pixels[shadow]) > channel(source.pixels[shadow]) + 20)
        assertEquals(0, channel(output.pixels[43 * width + 80]))
    }

    @Test
    fun outputPreservesDimensionsAlphaAndSourceAndDoesNotInventAnUpscale() {
        val source = ArgbImage(65, 35, IntArray(65 * 35) { index ->
            val alpha = when (index % 3) { 0 -> 0; 1 -> 127; else -> 255 }
            alpha shl 24 or ((index * 71) and 0x00ffffff)
        })
        val snapshot = source.pixels.copyOf()
        val output = DocumentImageFilterEngine.apply(source, DocumentFilter.DOCUMENT_CLEANUP)
        assertNotSame(source.pixels, output.pixels)
        assertEquals(source.width, output.width)
        assertEquals(source.height, output.height)
        source.pixels.indices.forEach { index ->
            assertEquals(source.pixels[index] ushr 24, output.pixels[index] ushr 24)
            if (source.pixels[index] ushr 24 == 0) assertEquals(source.pixels[index], output.pixels[index])
        }
        assertArrayEquals(snapshot, source.pixels)
        assertArrayEquals(output.pixels, DocumentImageFilterEngine.apply(source, DocumentFilter.DOCUMENT_CLEANUP).pixels)
    }

    @Test
    fun tinyAndOneDimensionalInputsRemainSafeAndBlackAndWhiteStayStable() {
        listOf(1 to 1, 1 to 37, 43 to 1).forEach { (width, height) ->
            listOf(0, 255).forEach { channel ->
                val source = ArgbImage(width, height, IntArray(width * height) { gray(channel) })
                assertArrayEquals(source.pixels, LocalDocumentCleanupFilter.apply(source).pixels)
            }
        }
    }

    @Test
    fun darkFlatRegionsAreNotTurnedIntoWhitePaper() {
        val source = ArgbImage(64, 64, IntArray(64 * 64) { gray(40) })
        val output = LocalDocumentCleanupFilter.apply(source)
        assertTrue(output.pixels.all { channel(it) in 40..80 })
    }

    @Test
    fun transparentNeighborsDoNotProduceSharpeningHalos() {
        val width = 64
        val plain = ArgbImage(width, 40, IntArray(width * 40) { gray(220) })
        val edge = ArgbImage(width, 40, IntArray(width * 40) { index ->
            if (index % width < 8) 0x00010203 else gray(220)
        })
        val baseline = LocalDocumentCleanupFilter.apply(plain)
        val output = LocalDocumentCleanupFilter.apply(edge)
        output.pixels.indices.forEach { index ->
            if (index % width < 8) assertEquals(0x00010203, output.pixels[index])
            else assertEquals(baseline.pixels[index], output.pixels[index])
        }
    }

    @Test
    fun streamingRowsAndWholeImageUseIdenticalProcessing() {
        val width = 70
        val height = 90
        val source = ArgbImage(width, height, IntArray(width * height) { index ->
            val alpha = if (index % 13 == 0) 127 else 255
            alpha shl 24 or ((index * 73) and 0x00ffffff)
        })
        val output = IntArray(source.pixels.size)
        var reads = 0
        var writes = 0
        LocalDocumentCleanupFilter.process(
            width, height,
            readRow = { y, row ->
                reads++
                source.pixels.copyInto(row, startIndex = y * width, endIndex = (y + 1) * width)
            },
            writeRow = { y, row ->
                assertEquals(writes++, y)
                row.copyInto(output, destinationOffset = y * width)
            },
        )
        assertEquals(height * 2, reads)
        assertEquals(height, writes)
        assertArrayEquals(LocalDocumentCleanupFilter.apply(source).pixels, output)
    }

    @Test
    fun cancellationDuringStatisticsPublishesNoRowsAndDoesNotModifySource() {
        val source = ArgbImage(40, 40, IntArray(40 * 40) { gray(180) })
        val snapshot = source.pixels.copyOf()
        var checks = 0
        var writes = 0
        assertThrows(CancellationException::class.java) {
            LocalDocumentCleanupFilter.process(
                source.width, source.height,
                readRow = { y, row -> source.pixels.copyInto(row, startIndex = y * 40, endIndex = (y + 1) * 40) },
                writeRow = { _, _ -> writes++ },
                ensureActive = { if (++checks == 5) throw CancellationException() },
            )
        }
        assertEquals(0, writes)
        assertArrayEquals(snapshot, source.pixels)
    }

    @Test
    fun cancellationDuringOutputStopsAtTheNextRow() {
        var writes = 0
        assertThrows(CancellationException::class.java) {
            LocalDocumentCleanupFilter.process(
                40, 40,
                readRow = { _, row -> row.fill(gray(180)) },
                writeRow = { _, _ -> writes++ },
                ensureActive = { if (writes == 3) throw CancellationException() },
            )
        }
        assertEquals(3, writes)
    }

    @Test
    fun readFailurePropagatesBeforeAnyOutput() {
        var writes = 0
        assertThrows(IOException::class.java) {
            LocalDocumentCleanupFilter.process(
                40, 40,
                readRow = { y, row -> if (y == 4) throw IOException() else row.fill(gray(180)) },
                writeRow = { _, _ -> writes++ },
            )
        }
        assertEquals(0, writes)
    }

    @Test
    fun invalidAndOverflowingDimensionsFailBeforeSourceAccess() {
        listOf(0 to 1, 1 to 0, -1 to 32, 46_341 to 46_341).forEach { (width, height) ->
            assertThrows(IllegalArgumentException::class.java) {
                LocalDocumentCleanupFilter.process(width, height,
                    readRow = { _, _ -> error("Must not read invalid input") },
                    writeRow = { _, _ -> error("Must not publish invalid input") })
            }
        }
    }

    private fun gray(channel: Int): Int = -0x1000000 or (channel shl 16) or (channel shl 8) or channel
    private fun channel(pixel: Int): Int = pixel and 255
}
