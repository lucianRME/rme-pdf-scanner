package org.synapseworks.pageharbor.searchprep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextUsefulnessEvaluatorTest {
    @Test
    fun acceptsShortMeaningfulAndUnicodeTextWithoutLanguageSpecificRules() {
        val samples = listOf(
            "ID 7",
            "Total 12.50",
            "Țară și înștiințare",
            "Überprüfung français",
            "中文收据",
            "日本語の領収書",
            "한국어 영수증",
            "देवनागरी रसीद",
        )

        samples.forEach { sample ->
            val result = TextUsefulnessEvaluator.evaluate(sample)
            assertTrue("Expected useful text for $sample", result is TextUsefulness.Useful)
        }
    }

    @Test
    fun normalizesLineEndingsAndUnicodeDeterministically() {
        val result = TextUsefulnessEvaluator.evaluate("  Cafe\u0301\r\n123  ")

        assertEquals(TextUsefulness.Useful("Café\n123"), result)
    }

    @Test
    fun rejectsEmptyControlOnlyAndPunctuationOnlyExtraction() {
        assertNeedsOcr(" \n\t ", TextUsefulnessReason.EMPTY)
        assertNeedsOcr("\u0001\u0002\u0003", TextUsefulnessReason.NO_LETTERS_OR_DIGITS)
        assertNeedsOcr("--- … !!!", TextUsefulnessReason.NO_LETTERS_OR_DIGITS)
    }

    @Test
    fun rejectsControlHeavyInvalidUnicodeAndPathologicalRepetition() {
        assertNeedsOcr("A\u0001\u0002\u0003\u0004", TextUsefulnessReason.CONTROL_HEAVY)
        assertNeedsOcr("A\uFFFDB\uFFFDC\uFFFDD", TextUsefulnessReason.INVALID_UNICODE_HEAVY)
        assertNeedsOcr("A".repeat(64), TextUsefulnessReason.PATHOLOGICAL_REPETITION)
        assertNeedsOcr(
            List(16) { "garbage" }.joinToString(" "),
            TextUsefulnessReason.PATHOLOGICAL_REPETITION,
        )
    }

    @Test
    fun rejectsAPageThatExceedsTheBoundBeforeNormalization() {
        val oversized = "x".repeat(TextUsefulnessEvaluator.MAX_PAGE_CHARACTERS + 1)

        assertNeedsOcr(oversized, TextUsefulnessReason.PAGE_TEXT_LIMIT_EXCEEDED)
    }

    private fun assertNeedsOcr(text: String, reason: TextUsefulnessReason) {
        assertEquals(TextUsefulness.NeedsOcr(reason), TextUsefulnessEvaluator.evaluate(text))
    }
}
