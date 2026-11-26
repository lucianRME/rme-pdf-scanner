package org.synapseworks.pageharbor.ocr

import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrSpellingReviewTest {
    private val words = setOf("document", "receive", "relieve", "review", "spelling", "the", "with", "both", "booth")

    @Test
    fun selectedOccurrencesLeaveRawTextAndProtectedTokensIntact() {
        val raw = "recieve, teh documemt.\r\nspellling\t0012\nDOCUMEMT Jane 0zero @recieve https://test/recieve café.\nrecieve"
        val review = reviewEnglishOcrSpelling(raw, words::contains)
        assertEquals(listOf("receive", "the", "document", "receive"), review.suggestions.map { it.replacement })
        assertEquals(raw, review.applyTo(raw, emptySet()))
        assertEquals(raw.replaceFirst("recieve", "receive"), review.applyTo(raw, setOf(0)))
    }

    @Test
    fun ambiguousMatchesAreOmittedAndTheResultIsBounded() {
        assertTrue(reviewEnglishOcrSpelling("borth", words::contains).suggestions.isEmpty())
        val review = reviewEnglishOcrSpelling("recieve ".repeat(500), words::contains)
        assertTrue(review.reachedReviewLimit)
        assertEquals(24, review.suggestions.size)
        assertTrue(reviewEnglishOcrSpelling("x".repeat(OCR_SPELLING_PAGE_LIMIT + 1), words::contains).reachedReviewLimit)
    }

    @Test
    fun cancellationStopsBeforePublishingSuggestions() {
        assertThrows(CancellationException::class.java) {
            reviewEnglishOcrSpelling("recieve", words::contains) { throw CancellationException() }
        }
    }
}
