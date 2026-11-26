package org.synapseworks.pageharbor.document

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.synapseworks.pageharbor.document.session.DocumentImageMetadata
import org.synapseworks.pageharbor.image.DocumentFilter

@RunWith(AndroidJUnit4::class)
class DocumentCleanupExportInstrumentedTest {
    @Test
    fun cleanupJpegExportUsesSpatialProcessingAndKeepsSourceDimensions() {
        val width = 192
        val height = 96
        val pixels = IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            val channel = if (y in 42..45 && x in 12 until width - 12) 0 else 160 + x * 80 / (width - 1)
            -0x1000000 or (channel shl 16) or (channel shl 8) or channel
        }
        val sourceBitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        val encodedSource = try {
            ByteArrayOutputStream().also { assertTrue(sourceBitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                .toByteArray()
        } finally {
            sourceBitmap.recycle()
        }
        val originalBytes = encodedSource.copyOf()
        val destination = ByteArrayOutputStream()
        val result = writeFilteredJpegToDestination(
            openSource = { ByteArrayInputStream(encodedSource) },
            destination = destination,
            filter = DocumentFilter.DOCUMENT_CLEANUP,
            imageMetadata = DocumentImageMetadata(width = width, height = height),
        )
        assertEquals(PageExportResult.Success, result)
        val exportedBytes = destination.toByteArray()
        val exported = requireNotNull(BitmapFactory.decodeByteArray(exportedBytes, 0, exportedBytes.size))
        try {
            assertEquals(width, exported.width)
            assertEquals(height, exported.height)
            val before = abs((pixels[16 * width + 16] and 255) - (pixels[16 * width + 176] and 255))
            val after = abs((exported.getPixel(16, 16) and 255) - (exported.getPixel(176, 16) and 255))
            assertTrue(after < before / 2)
            assertTrue((exported.getPixel(80, 43) and 255) < 20)
            assertArrayEquals(originalBytes, encodedSource)
        } finally {
            exported.recycle()
        }
    }
}
