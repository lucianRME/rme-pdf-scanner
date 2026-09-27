package org.synapseworks.pageharbor.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryModelsTest {
    @Test
    fun searchQueryUsesBoundedUnicodePrefixesAndPortableImplicitAnd() {
        assertEquals("factura* 2026*", " Factura, 2026! ".toFtsPrefixQuery())
        assertEquals("café*", "café".toFtsPrefixQuery())
        assertNull("---".toFtsPrefixQuery())
    }

    @Test
    fun searchQueryTreatsUserPunctuationAsDataInsteadOfFtsSyntax() {
        assertEquals("o* reilly*", "O'Reilly".toFtsPrefixQuery())
        assertEquals("invoice* date*", "\"invoice date\"".toFtsPrefixQuery())
        assertEquals("contract* number*", "contract-number".toFtsPrefixQuery())
        assertEquals("42* 16*", "€42.16".toFtsPrefixQuery())
        assertEquals("hello* example* com*", "hello@example.com".toFtsPrefixQuery())
        assertEquals("https* example* com* factură*", "https://example.com/factură".toFtsPrefixQuery())
        assertEquals("ședință*", "ȘEDINȚĂ".toFtsPrefixQuery())
        assertEquals("चालान*", "चालान".toFtsPrefixQuery())
    }

    @Test
    fun cjkQueriesUsePortableSyntheticTerms() {
        assertEquals("u20053d1007968*", "发票".toFtsPrefixQuery())
        assertEquals(
            "u2008acb006c42* u2006c420066f8*",
            "請求書".toFtsPrefixQuery(),
        )
        assertEquals("u200d55c00ad6d* u200ad6d00c5b4*", "한국어".toFtsPrefixQuery())
    }

    @Test
    fun veryShortLatinQueriesAreSuppressedButSingleCjkCharactersRemainUseful() {
        assertFalse("i".isUsefulLibrarySearchQuery())
        assertFalse("7".isUsefulLibrarySearchQuery())
        assertTrue("in".isUsefulLibrarySearchQuery())
        assertTrue("发".isUsefulLibrarySearchQuery())
        assertTrue("€42".isUsefulLibrarySearchQuery())
    }

    @Test
    fun titleClassificationUsesTheSamePrefixAndPunctuationSemantics() {
        assertTrue(matchesLibrarySearchText("Insurance certificate", "ins cert"))
        assertTrue(matchesLibrarySearchText("O'Reilly invoice", "o'rei"))
        assertTrue(matchesLibrarySearchText("hello@example.com", "hello@example"))
        assertTrue(matchesLibrarySearchText("中文测试发票金额", "发票"))
        assertFalse(matchesLibrarySearchText("Receipts folder", "invoice"))
    }

    @Test
    fun namesCollapseWhitespaceAndRespectStorageBounds() {
        assertEquals("Quarterly report", normalizeLibraryTitle("  Quarterly   report  "))
        assertEquals("Client files", normalizeFolderName(" Client\nfiles "))
        assertEquals(MAX_LIBRARY_TITLE_LENGTH, normalizeLibraryTitle("x".repeat(200)).length)
        assertEquals(MAX_FOLDER_NAME_LENGTH, normalizeFolderName("y".repeat(200)).length)
    }
}
