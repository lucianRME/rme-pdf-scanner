package org.synapseworks.pageharbor.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
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
import org.synapseworks.pageharbor.library.LibraryOcrPageSnapshot
import org.synapseworks.pageharbor.ocr.persistence.BundledLatinOcrMappingResult
import org.synapseworks.pageharbor.ocr.persistence.BundledLatinOcrResultMapper

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

    @Test
    fun controlledEnglishStepReceiptAndColumnPagesKeepSensibleCanonicalOrder() {
        val fixtures = listOf(
            ControlledFixture(
                name = "normal",
                lines = listOf(
                    DrawnLine("HEADER", 80f, 140f, 82f),
                    DrawnLine("PARAGRAPH ALPHA", 80f, 300f),
                    DrawnLine("SECOND LINE", 80f, 400f),
                    DrawnLine("FOOTER", 80f, 700f),
                ),
                expectedTokens = listOf("HEADER", "PARAGRAPH", "SECOND", "FOOTER"),
            ),
            ControlledFixture(
                name = "steps",
                lines = listOf(
                    DrawnLine("HOW TO ORDER", 80f, 140f, 82f),
                    DrawnLine("OPTION ONE", 80f, 300f),
                    DrawnLine("ONLINE ORDERS", 150f, 400f),
                    DrawnLine("GO TO SHOP COUNTY PHOTOS", 150f, 500f),
                    DrawnLine("ENTER UNIQUE ACCESS CODE", 150f, 600f),
                    DrawnLine("OPTION TWO", 80f, 780f),
                    DrawnLine("ORDERS THROUGH SCHOOL", 150f, 880f),
                    DrawnLine("STEP ONE", 80f, 1_060f),
                    DrawnLine("STEP TWO", 80f, 1_240f),
                    DrawnLine("PACKAGE OPTIONS", 150f, 1_340f),
                    DrawnLine("CHOOSE YOUR PACKAGES", 150f, 1_440f),
                    DrawnLine("COMPLETE PROOF ORDER FORM", 150f, 1_540f),
                ),
                expectedTokens = listOf(
                    "HOW", "OPTION", "ONLINE", "SHOP", "UNIQUE", "OPTION",
                    "SCHOOL", "STEP", "STEP", "PACKAGE", "CHOOSE", "COMPLETE",
                ),
            ),
            ControlledFixture(
                name = "receipt",
                lines = listOf(
                    DrawnLine("MERCHANT", 80f, 140f, 82f),
                    DrawnLine("ADDRESS", 80f, 250f),
                    DrawnLine("APPLE 10 EUR", 80f, 440f),
                    DrawnLine("BREAD 20 EUR", 80f, 540f),
                    DrawnLine("TOTAL 30 EUR", 80f, 700f, 82f),
                ),
                expectedTokens = listOf("MERCHANT", "ADDRESS", "APPLE", "BREAD", "TOTAL"),
            ),
            ControlledFixture(
                name = "columns",
                lines = listOf(
                    DrawnLine("LEFTONE", 80f, 180f),
                    DrawnLine("LEFTTWO", 80f, 320f),
                    DrawnLine("LEFTTHREE", 80f, 460f),
                    DrawnLine("RIGHTONE", 1_050f, 180f),
                    DrawnLine("RIGHTTWO", 1_050f, 320f),
                    DrawnLine("RIGHTTHREE", 1_050f, 460f),
                ),
                expectedTokens = emptyList(),
                twoColumns = true,
            ),
        )

        fixtures.forEach { fixture ->
            val result = engine.recognize(listOf(controlledPage(fixture.lines)))
            val page = result.pages.single()
            assertNull("${fixture.name} OCR failed", page.error)
            assertTrue("${fixture.name} produced no hierarchy", page.layout?.blocks?.isNotEmpty() == true)
            assertEquals(
                requireNotNull(page.layout).blocks.flatMap(OcrTextBlock::lines).map(OcrTextLine::text),
                page.layout.lines.map(OcrTextLine::text),
            )
            if (fixture.twoColumns) {
                assertCoherentColumns(page.text)
            } else {
                assertTokensInOrder(page.text, fixture.expectedTokens)
            }
            assertCanonicalPersistence(result)
        }
    }

    @Test
    fun controlledPageKeepsReadingOrderAtEveryRightAngleRotation() {
        listOf(0, 90, 180, 270).forEach { rotation ->
            val result = engine.recognize(
                listOf(
                    controlledPage(
                        lines = listOf(
                            DrawnLine("ROTATION HEADER", 80f, 180f, 82f),
                            DrawnLine("FIRST MARKER", 80f, 360f),
                            DrawnLine("SECOND MARKER", 80f, 500f),
                        ),
                        rotationDegrees = rotation,
                    ),
                ),
            )
            val page = result.pages.single()
            assertNull("Rotation $rotation OCR failed", page.error)
            val rotationTokens = listOf("ROTATION", "FIRST", "SECOND")
            val tokenIndexes = rotationTokens.map { token -> page.text.uppercase().indexOf(token) }
            val tokenTops = rotationTokens.map { token ->
                page.layout?.lines?.firstOrNull { line -> line.text.contains(token, ignoreCase = true) }
                    ?.bounds?.top
            }
            assertTrue(
                "Rotation $rotation tokens were missing or out of order: indexes=$tokenIndexes " +
                    "tops=$tokenTops lines=${page.layout?.lines?.size}",
                tokensInOrder(page.text.uppercase(), rotationTokens),
            )
            assertEquals(0, requireNotNull(page.layout).rotationDegrees)
        }
    }

    private fun controlledPage(
        lines: List<DrawnLine>,
        rotationDegrees: Int = 0,
    ): OcrPage {
        val upright = Bitmap.createBitmap(2_000, 1_800, Bitmap.Config.ARGB_8888)
        val raw = try {
            Canvas(upright).apply {
                drawColor(Color.WHITE)
                lines.forEach { line ->
                    drawText(
                        line.text,
                        line.x,
                        line.baselineY,
                        Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = Color.BLACK
                            textSize = line.textSize
                            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
                        },
                    )
                }
                if (lines.any { it.x > upright.width / 2f }) {
                    drawLine(
                        upright.width / 2f,
                        80f,
                        upright.width / 2f,
                        650f,
                        Paint().apply {
                            color = Color.LTGRAY
                            strokeWidth = 4f
                        },
                    )
                }
            }
            if (rotationDegrees == 0) {
                upright.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                Bitmap.createBitmap(
                    upright,
                    0,
                    0,
                    upright.width,
                    upright.height,
                    Matrix().apply { postRotate(-rotationDegrees.toFloat()) },
                    true,
                )
            }
        } finally {
            upright.recycle()
        }
        val bytes = try {
            ByteArrayOutputStream().use { output ->
                check(raw.compress(Bitmap.CompressFormat.JPEG, 100, output))
                output.toByteArray()
            }
        } finally {
            raw.recycle()
        }
        return OcrPage(rotationDegrees = rotationDegrees) { ByteArrayInputStream(bytes) }
    }

    private fun assertCanonicalPersistence(result: OcrResult) {
        val mapping = BundledLatinOcrResultMapper.map(
            result = result,
            orderedSnapshots = listOf(
                LibraryOcrPageSnapshot(
                    documentId = "controlled-document",
                    pageId = "controlled-page",
                    pagePosition = 0,
                    documentContentRevision = 1,
                    pageVisualRevision = 1,
                    ocrStateRevision = 0,
                    activeArtifactRevision = null,
                    contentSha256 = "a".repeat(64),
                    rotationDegrees = 0,
                    filterName = "ORIGINAL",
                ),
            ),
            recognizedAtMillis = 1,
        ) as BundledLatinOcrMappingResult.Success
        val artifact = requireNotNull(mapping.outcomes.single().artifact)
        val page = result.pages.single()
        assertEquals(page.text, artifact.rawText)
        assertEquals(
            requireNotNull(page.layout).lines.map(OcrTextLine::text),
            artifact.lines.map { it.rawText },
        )
    }

    private fun assertCoherentColumns(text: String) {
        val normalized = text.uppercase().replace(" ", "")
        val leftThenRight = tokensInOrder(
            normalized,
            listOf("LEFTONE", "LEFTTWO", "LEFTTHREE", "RIGHTONE", "RIGHTTWO", "RIGHTTHREE"),
        )
        val rightThenLeft = tokensInOrder(
            normalized,
            listOf("RIGHTONE", "RIGHTTWO", "RIGHTTHREE", "LEFTONE", "LEFTTWO", "LEFTTHREE"),
        )
        assertTrue("ML Kit hierarchy interleaved the two controlled columns", leftThenRight || rightThenLeft)
    }

    private fun assertTokensInOrder(text: String, tokens: List<String>) {
        assertTrue("Tokens were missing or out of order", tokensInOrder(text.uppercase(), tokens))
    }

    private fun tokensInOrder(text: String, tokens: List<String>): Boolean {
        var nextOffset = 0
        tokens.forEach { token ->
            val index = text.indexOf(token, startIndex = nextOffset, ignoreCase = true)
            if (index < 0) return false
            nextOffset = index + token.length
        }
        return true
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

    private data class DrawnLine(
        val text: String,
        val x: Float,
        val baselineY: Float,
        val textSize: Float = 70f,
    )

    private data class ControlledFixture(
        val name: String,
        val lines: List<DrawnLine>,
        val expectedTokens: List<String>,
        val twoColumns: Boolean = false,
    )
}
