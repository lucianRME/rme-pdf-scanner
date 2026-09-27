package org.synapseworks.pageharbor.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.text.Normalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end checks against the bundled model using generated, non-sensitive image fixtures.
 * These must run on a device or emulator with the debug APK installed.
 */
class MlKitOcrEngineTest {
    private val engine = MlKitOcrEngine()

    @Test
    fun recognizesOneEnglishPage() {
        val result = engine.recognize(listOf(pageWithText("Harbor")))

        assertEquals(listOf(0), result.pages.map { it.pageIndex })
        assertNull(result.pages.single().error)
        assertTrue(result.pages.single().text.contains("Harbor", ignoreCase = true))
    }

    @Test
    fun recognizesMultiplePagesInInputOrder() {
        val result = engine.recognize(
            listOf(
                pageWithText("First marker"),
                pageWithText("Second marker"),
            ),
        )

        assertEquals(listOf(0, 1), result.pages.map { it.pageIndex })
        assertTrue(result.pages[0].text.contains("First", ignoreCase = true))
        assertTrue(result.pages[1].text.contains("Second", ignoreCase = true))
        assertEquals("First marker\n\nSecond marker", result.plainText)
    }

    @Test
    fun preservesAnEmptyPage() {
        val result = engine.recognize(listOf(pageWithText(null)))

        assertEquals(listOf(0), result.pages.map { it.pageIndex })
        assertNull(result.pages.single().error)
        assertEquals("", result.pages.single().text)
    }

    @Test
    fun recognizesGermanLatinText() {
        val result = engine.recognize(listOf(pageWithText("Fünf große Bücher")))

        assertNull(result.pages.single().error)
        assertTrue(result.pages.single().text.contains("Fünf", ignoreCase = true))
        assertTrue(result.pages.single().text.contains("Bücher", ignoreCase = true))
    }

    @Test
    fun recognizesRomanianLatinText() {
        val result = engine.recognize(listOf(pageWithText("Șase țări și țărani")))

        assertNull(result.pages.single().error)
        val recognizedText = foldDiacritics(result.pages.single().text)
        assertTrue(recognizedText.contains("Sase", ignoreCase = true))
        assertTrue(recognizedText.contains("tari", ignoreCase = true))
    }

    @Test
    fun recognizesFrenchLatinText() {
        assertRecognizesFolded("École française", "Ecole")
    }

    @Test
    fun recognizesItalianLatinText() {
        assertRecognizesFolded("Città italiana", "Citta")
    }

    @Test
    fun recognizesSpanishLatinText() {
        assertRecognizesFolded("España mañana", "Espana")
    }

    @Test
    fun invalidImageReturnsSafeFailureInsteadOfThrowing() {
        val result = engine.recognize(
            listOf(OcrPage { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }),
        )

        assertEquals(0, result.pages.single().pageIndex)
        assertEquals(OcrPageError.IMAGE_UNREADABLE, result.pages.single().error)
        assertFalse(result.pages.single().text.isNotEmpty())
    }

    @Test
    fun streamsCloseAfterSuccessfulTwoPassDecode() {
        val streams = mutableListOf<TrackingInputStream>()
        val page = trackingPage(pageWithTextBytes("Stream marker"), streams)

        val result = engine.recognize(listOf(page))

        assertNull(result.pages.single().error)
        assertEquals(2, streams.size)
        assertTrue(streams.all { it.wasClosed })
    }

    @Test
    fun streamClosesAfterBoundsDecodeFailure() {
        val streams = mutableListOf<TrackingInputStream>()
        val page = trackingPage(byteArrayOf(1, 2, 3), streams)

        val result = engine.recognize(listOf(page))

        assertEquals(OcrPageError.IMAGE_UNREADABLE, result.pages.single().error)
        assertEquals(1, streams.size)
        assertTrue(streams.single().wasClosed)
    }

    @Test
    fun failedPageDoesNotPreventLaterPageRecognition() {
        val result = engine.recognize(
            listOf(
                OcrPage { ByteArrayInputStream(byteArrayOf(1, 2, 3)) },
                pageWithText("Later marker"),
            ),
        )

        assertEquals(listOf(0, 1), result.pages.map { it.pageIndex })
        assertEquals(OcrPageError.IMAGE_UNREADABLE, result.pages[0].error)
        assertNull(result.pages[1].error)
        assertTrue(result.pages[1].text.contains("Later", ignoreCase = true))
    }

    private fun pageWithText(text: String?): OcrPage {
        val bytes = pageWithTextBytes(text)
        return OcrPage { ByteArrayInputStream(bytes) }
    }

    private fun pageWithTextBytes(text: String?): ByteArray {
        val bitmap = Bitmap.createBitmap(1800, 500, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            if (text != null) {
                drawText(
                    text,
                    80f,
                    290f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.BLACK
                        textSize = 112f
                        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
                    },
                )
            }
        }
        return try {
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun trackingPage(
        bytes: ByteArray,
        streams: MutableList<TrackingInputStream>,
    ): OcrPage = OcrPage {
        TrackingInputStream(bytes).also(streams::add)
    }

    private fun foldDiacritics(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace("\\p{M}+".toRegex(), "")

    private fun assertRecognizesFolded(fixture: String, expected: String) {
        val result = engine.recognize(listOf(pageWithText(fixture)))

        assertNull(result.pages.single().error)
        assertTrue(foldDiacritics(result.pages.single().text).contains(expected, ignoreCase = true))
    }

    private class TrackingInputStream(bytes: ByteArray) : InputStream() {
        private val delegate = ByteArrayInputStream(bytes)
        var wasClosed = false
            private set

        override fun read(): Int = delegate.read()

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            delegate.read(buffer, offset, length)

        override fun close() {
            wasClosed = true
            delegate.close()
        }
    }
}
