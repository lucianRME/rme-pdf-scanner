package org.synapseworks.pageharbor.ocr

import kotlin.system.measureNanoTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrReadingOrderResolverTest {
    @Test
    fun simpleMultiWordLinePreservesRecognizerTextDespiteElementCoordinateNoise() {
        val elements = listOf(
            word("How", 10f, 35f, top = 12f),
            word("to", 40f, 52f, top = 11f),
            word("order:", 57f, 92f, top = 10f),
        )

        assertEquals(
            "How to order:",
            resolve(blockOf(line("How to order:", top = 10f, elements = elements))).canonicalText,
        )
    }

    @Test
    fun severalLinesParagraphAndHeadingPreserveHierarchyOrder() {
        val resolution = resolve(
            blockOf(
                line("Heading", top = 40f),
                line("First paragraph line", top = 10f),
                line("Second paragraph line", top = 25f),
            ),
        )

        assertEquals("Heading\nFirst paragraph line\nSecond paragraph line", resolution.canonicalText)
        assertEquals(
            listOf("Heading", "First paragraph line", "Second paragraph line"),
            resolution.layout.lines.map(OcrTextLine::text),
        )
    }

    @Test
    fun separatedHierarchyBlocksUseAtMostOneBlankParagraphLine() {
        val resolution = resolve(
            blockOf(line("Heading", top = 10f)),
            blockOf(line("Paragraph", top = 70f)),
        )

        assertEquals("Heading\n\nParagraph", resolution.canonicalText)
        assertFalse(resolution.canonicalText.contains("\n\n\n"))
    }

    @Test
    fun indentedContinuationsAndNumberedStepsStayInVerticalSemanticSequence() {
        val resolution = resolve(
            blockOf(
                line("Option 1", top = 10f, left = 10f),
                line("Online orders", top = 30f, left = 36f),
                line("Go to shop.countyphotos.ie", top = 50f, left = 36f),
                line("Step 1", top = 80f, left = 10f),
                line("Complete the form", top = 100f, left = 42f),
                line("Step 2", top = 130f, left = 10f),
            ),
        )

        assertEquals(
            "Option 1\nOnline orders\nGo to shop.countyphotos.ie\nStep 1\nComplete the form\nStep 2",
            resolution.canonicalText,
        )
    }

    @Test
    fun twoColumnsRemainCoherentBlocksInsteadOfRowInterleaving() {
        val resolution = resolve(
            blockOf(
                line("A1", top = 10f, left = 10f),
                line("A2", top = 30f, left = 10f),
                line("A3", top = 50f, left = 10f),
            ),
            blockOf(
                line("B1", top = 10f, left = 120f),
                line("B2", top = 30f, left = 120f),
                line("B3", top = 50f, left = 120f),
            ),
        )

        assertEquals("A1\nA2\nA3\nB1\nB2\nB3", resolution.canonicalText)
    }

    @Test
    fun formLabelsValuesAndReceiptRowsKeepRecognizerLineText() {
        val form = resolve(
            blockOf(
                noisyElementLine("Name: John Smith", "Name:", "John", "Smith", top = 10f),
                noisyElementLine("Reference: 12345", "Reference:", "12345", top = 30f),
                noisyElementLine("Date: 27 Sep 2026", "Date:", "27", "Sep", "2026", top = 50f),
            ),
        )
        val receipt = resolve(
            blockOf(
                line("Merchant", top = 10f),
                line("Address", top = 30f),
                noisyElementLine("Item A €10", "Item", "A", "€10", top = 50f),
                noisyElementLine("Item B €20", "Item", "B", "€20", top = 70f),
                noisyElementLine("TOTAL €30", "TOTAL", "€30", top = 90f),
            ),
        )

        assertEquals("Name: John Smith\nReference: 12345\nDate: 27 Sep 2026", form.canonicalText)
        assertEquals("Merchant\nAddress\nItem A €10\nItem B €20\nTOTAL €30", receipt.canonicalText)
    }

    @Test
    fun overlappingLinesIrregularSizesAndEqualCoordinatesRemainStable() {
        val firstBounds = OcrTextBounds(10f, 10f, 180f, 42f)
        val secondBounds = OcrTextBounds(12f, 35f, 160f, 47f)
        val equalBounds = OcrTextBounds(10f, 60f, 100f, 72f)
        val resolution = OcrReadingOrderResolver.resolve(
            layoutOf(
                blockOf(
                    OcrTextLine("Large heading", firstBounds),
                    OcrTextLine("Overlapping body", secondBounds),
                    OcrTextLine("Stable first", equalBounds),
                    OcrTextLine("Stable second", equalBounds),
                ),
            ),
        )

        assertEquals(
            "Large heading\nOverlapping body\nStable first\nStable second",
            resolution.canonicalText,
        )
    }

    @Test
    fun clearlyReversedSingleColumnHierarchyIsCorrectedWithoutGlobalSorting() {
        val resolution = resolve(
            blockOf(line("Bottom", top = 210f)),
            blockOf(line("Middle", top = 110f)),
            blockOf(line("Top", top = 10f)),
        )
        val reversedLinesInOneBlock = resolve(
            blockOf(
                line("Third", top = 210f),
                line("Second", top = 110f),
                line("First", top = 10f),
            ),
        )

        assertEquals("Top\n\nMiddle\n\nBottom", resolution.canonicalText)
        assertEquals("First\nSecond\nThird", reversedLinesInOneBlock.canonicalText)
    }

    @Test
    fun normalizedRotatedAndSparsePageKeepsProvidedHierarchy() {
        val layout = layoutOf(
            blockOf(line("Rotation header", top = 20f, left = 20f)),
            blockOf(line("Sparse footer", top = 700f, left = 240f)),
            width = 800,
            height = 1_000,
        )

        val resolution = OcrReadingOrderResolver.resolve(layout)

        assertEquals("Rotation header\n\nSparse footer", resolution.canonicalText)
        assertEquals(0, resolution.layout.rotationDegrees)
        assertEquals(800, resolution.layout.imageWidthPx)
        assertEquals(1_000, resolution.layout.imageHeightPx)
    }

    @Test
    fun longLinePunctuationUrlAndRomanianTextRemainUnicodeSafe() {
        val longLine = (1..400).joinToString(" ") { "word$it" }
        val expected = listOf(
            longLine,
            "Go to shop.countyphotos.ie/path?q=1&lang=ro.",
            "Română: ă â î ș ț — factură.",
        ).joinToString("\n")

        assertEquals(
            expected,
            resolve(
                blockOf(
                    line(longLine, top = 10f),
                    line("Go to shop.countyphotos.ie/path?q=1&lang=ro.", top = 30f),
                    line("Română: ă â î ș ț — factură.", top = 50f),
                ),
            ).canonicalText,
        )
    }

    @Test
    fun cjkKoreanAndDevanagariLinesRemainInRecognizerSequence() {
        assertEquals(
            "中文可搜索文档\n日本語の検索文書\n한국어 검색 문서\nहिन्दी खोज योग्य दस्तावेज़",
            resolve(
                blockOf(
                    line("中文可搜索文档", top = 10f),
                    line("日本語の検索文書", top = 30f),
                    line("한국어 검색 문서", top = 50f),
                    line("हिन्दी खोज योग्य दस्तावेज़", top = 70f),
                ),
            ).canonicalText,
        )
    }

    @Test
    fun blankLineTextFallsBackToElementSequenceWithoutCoordinateSorting() {
        val elements = listOf(
            word("source", 80f, 120f, top = 12f),
            word("order", 10f, 50f, top = 10f),
        )

        assertEquals(
            "source order",
            resolve(blockOf(line("  ", top = 10f, elements = elements))).canonicalText,
        )
    }

    @Test
    fun manualFailureFixturePreservesLogicalSequenceAndRejectsKnownReversals() {
        val resolution = resolve(
            blockOf(noisyElementLine("How to order:", "How", "to", "order:", top = 10f)),
            blockOf(
                line("Option 1", top = 40f),
                noisyElementLine("Online orders", "Online", "orders", top = 60f),
                line("Go to shop.countyphotos.ie", top = 80f),
                line("and enter the unique access code", top = 100f),
            ),
            blockOf(
                noisyElementLine("Option 2", "Option", "2", top = 140f),
                noisyElementLine("Orders through the school", "Orders", "through", "the", "school", top = 160f),
            ),
            blockOf(
                line("Step 1", top = 200f),
                line("Step 2", top = 240f),
                line("Package Options", top = 260f),
                line("Choose your packages", top = 280f),
                line("from the enclosed flyer.", top = 300f),
                noisyElementLine(
                    "Complete the 'proof order form' by ticking",
                    "Complete",
                    "the",
                    "'proof",
                    "order",
                    "form'",
                    "by",
                    "ticking",
                    top = 320f,
                ),
                noisyElementLine("your package.", "your", "package.", top = 340f),
            ),
        )

        val text = resolution.canonicalText
        assertInOrder(
            text,
            "How to order:",
            "Option 1",
            "Online orders",
            "Option 2",
            "Orders through the school",
            "Step 1",
            "Step 2",
            "Complete the 'proof order form' by ticking",
            "your package.",
        )
        listOf(
            "order: to How",
            "orders Online",
            "school the through Orders Option2",
            "proof the Complete",
            "ticking by form' order",
            "package. your",
        ).forEach { regression -> assertFalse(text.contains(regression)) }
    }

    @Test
    fun resolutionRetainsOriginalGeometryAndElements() {
        val bounds = OcrTextBounds(10f, 20f, 180f, 40f)
        val elements = listOf(word("Geometry", 10f, 70f), word("retained", 80f, 140f))
        val sourceLine = OcrTextLine("Geometry retained", bounds, elements = elements)

        val resolvedLine = resolve(blockOf(sourceLine)).layout.lines.single()

        assertSame(bounds, resolvedLine.bounds)
        assertSame(elements, resolvedLine.elements)
    }

    @Test
    fun pageLocalResolverRemainsBoundedForOneTwentyAndHeavyPages() {
        val regularPage = layoutOf(
            blockOf(*(0 until 50).map { index -> line("Line $index", top = index * 12f) }.toTypedArray()),
            height = 1_000,
        )
        val heavyPage = layoutOf(
            blockOf(*(0 until 2_000).map { index -> line("Heavy $index", top = index.toFloat()) }.toTypedArray()),
            height = 3_000,
        )

        val onePageNanos = measureNanoTime { OcrReadingOrderResolver.resolve(regularPage) }
        val twentyPagesNanos = measureNanoTime {
            repeat(20) { OcrReadingOrderResolver.resolve(regularPage) }
        }
        val heavyPageNanos = measureNanoTime { OcrReadingOrderResolver.resolve(heavyPage) }

        println(
            "RME_READING_ORDER_PERF onePageUs=${onePageNanos / 1_000} " +
                "twentyPagesUs=${twentyPagesNanos / 1_000} heavyPageUs=${heavyPageNanos / 1_000}",
        )
        assertTrue(onePageNanos < 250_000_000L)
        assertTrue(twentyPagesNanos < 1_000_000_000L)
        assertTrue(heavyPageNanos < 1_000_000_000L)
    }

    @Test
    fun displayAndCopyUseTheSameCanonicalPageText() {
        val page = OcrPageResult(
            pageIndex = 0,
            text = "Canonical first\nCanonical second",
            layout = layoutOf(blockOf(line("Geometry only", top = 10f))),
        )
        val result = OcrResult(listOf(page))

        assertEquals(page.text, page.displayText())
        assertEquals(page.text, copyableOcrPreview(result, { "Page $it" }, "Empty"))
    }

    private fun resolve(vararg blocks: OcrTextBlock): OcrReadingOrderResolution =
        OcrReadingOrderResolver.resolve(layoutOf(*blocks))

    private fun layoutOf(
        vararg blocks: OcrTextBlock,
        width: Int = 400,
        height: Int = 800,
    ): OcrPageLayout = OcrPageLayout(
        imageWidthPx = width,
        imageHeightPx = height,
        lines = blocks.flatMap(OcrTextBlock::lines),
        blocks = blocks.toList(),
    )

    private fun blockOf(vararg lines: OcrTextLine): OcrTextBlock = OcrTextBlock(lines.toList())

    private fun line(
        text: String,
        top: Float,
        left: Float = 10f,
        elements: List<OcrTextElement> = emptyList(),
    ): OcrTextLine = OcrTextLine(
        text = text,
        bounds = OcrTextBounds(left, top, left + 180f, top + 16f),
        elements = elements,
    )

    private fun noisyElementLine(
        lineText: String,
        vararg words: String,
        top: Float,
    ): OcrTextLine {
        var left = 10f
        return line(
            text = lineText,
            top = top,
            elements = words.mapIndexed { index, text ->
                val width = text.length.coerceAtLeast(1) * 8f
                word(text, left, left + width, top = top + (words.size - index).toFloat())
                    .also { left += width + 6f }
            },
        )
    }

    private fun word(
        text: String,
        left: Float,
        right: Float,
        top: Float = 0f,
    ): OcrTextElement = OcrTextElement(
        text,
        OcrTextBounds(left, top, right, top + 10f),
    )

    private fun assertInOrder(text: String, vararg fragments: String) {
        var previous = -1
        fragments.forEach { fragment ->
            val index = text.indexOf(fragment)
            assertTrue("Missing or out-of-order fragment: $fragment", index > previous)
            previous = index
        }
    }
}
