package org.synapseworks.pageharbor.document

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Test
import org.synapseworks.pageharbor.document.session.DEFAULT_MAX_IMAGE_SOURCE_BYTES
import org.synapseworks.pageharbor.document.session.DocumentImageMetadata
import org.synapseworks.pageharbor.image.DocumentFilter

class FilteredJpegPageWriterInstrumentedTest {
    @Test
    fun jpegUsesTheBoundedTranscodePath() = assertBoundedTranscode(Bitmap.CompressFormat.JPEG)

    @Test
    fun pngUsesTheBoundedTranscodePath() = assertBoundedTranscode(Bitmap.CompressFormat.PNG)

    @Suppress("DEPRECATION")
    @Test
    fun webpUsesTheBoundedTranscodePath() = assertBoundedTranscode(Bitmap.CompressFormat.WEBP)

    @Test
    fun knownOversizedSourceIsRejectedBeforeAnySourceStreamIsOpened() {
        var openCount = 0

        val result = writeFilteredJpegToDestination(
            openSource = {
                openCount++
                ByteArrayInputStream(byteArrayOf(1, 2, 3))
            },
            destination = ByteArrayOutputStream(),
            filter = DocumentFilter.GRAYSCALE,
            imageMetadata = DocumentImageMetadata(
                sourceByteCount = DEFAULT_MAX_IMAGE_SOURCE_BYTES + 1L,
            ),
        )

        assertEquals(PageExportResult.SourceTooLarge, result)
        assertEquals(0, openCount)
    }

    private fun imageFixture(format: Bitmap.CompressFormat): ByteArray {
        val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(Color.rgb(40, 120, 220))
            ByteArrayOutputStream().also { output ->
                check(bitmap.compress(format, 100, output))
            }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertBoundedTranscode(format: Bitmap.CompressFormat) {
        val sourceBytes = imageFixture(format)
        var writerOpenCount = 0
        val sourceFixture = requireNotNull(
            BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size),
        )
        sourceFixture.recycle()
        assertEquals(
            32 to 24,
            readImageBounds { ByteArrayInputStream(sourceBytes) },
        )
        val fullResolution = requireNotNull(
            decodeFullResolution { ByteArrayInputStream(sourceBytes) },
        )
        assertEquals(32, fullResolution.width)
        assertEquals(24, fullResolution.height)
        fullResolution.recycle()
        val destination = ByteArrayOutputStream()

        val result = writeFilteredJpegToDestination(
            openSource = {
                writerOpenCount++
                ByteArrayInputStream(sourceBytes)
            },
            destination = destination,
            filter = DocumentFilter.GRAYSCALE,
            imageMetadata = DocumentImageMetadata(sourceByteCount = sourceBytes.size.toLong()),
        )

        assertEquals(PageExportResult.Success, result)
        assertEquals(3, writerOpenCount)
        val decoded = requireNotNull(
            BitmapFactory.decodeByteArray(destination.toByteArray(), 0, destination.size()),
        )
        assertEquals(32, decoded.width)
        assertEquals(24, decoded.height)
        decoded.recycle()
    }
}
