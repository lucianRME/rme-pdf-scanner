package org.synapseworks.pageharbor.ocr

import java.io.File
import java.util.Locale
import java.util.zip.GZIPInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrMultilingualSpellingTest {
    @Test
    fun everyBundledLanguageSuggestsACorrectionWithoutChangingRawText() {
        val cases = mapOf(
            OcrSpellingLanguage.ENGLISH to ("recieve" to "receive"),
            OcrSpellingLanguage.FRENCH to ("numérisaiton" to "numérisation"),
            OcrSpellingLanguage.GERMAN to ("Dokumemt" to "Dokument"),
            OcrSpellingLanguage.IRISH to ("gaeigle" to "gaeilge"),
            OcrSpellingLanguage.ITALIAN to ("ricveuta" to "ricevuta"),
            OcrSpellingLanguage.ROMANIAN to ("înregisrtare" to "înregistrare"),
            OcrSpellingLanguage.SPANISH to ("documemto" to "documento"),
            OcrSpellingLanguage.SIMPLIFIED_CHINESE to ("末来" to "未来"),
            OcrSpellingLanguage.TRADITIONAL_CHINESE to ("末來" to "未來"),
            OcrSpellingLanguage.JAPANESE to ("申晴書" to "申請書"),
            OcrSpellingLanguage.KOREAN to ("문서뭉" to "문서"),
            OcrSpellingLanguage.HINDI to ("दस्तावज" to "दस्तावेज"),
            OcrSpellingLanguage.MARATHI to ("प्रमाणपतर" to "प्रमाणपत्र"),
            OcrSpellingLanguage.NEPALI to ("दस्तावज" to "दस्तावेज"),
        )
        assertEquals(OcrSpellingLanguage.entries.toSet(), cases.keys)
        for ((language, pair) in cases) {
            val (typo, correct) = pair
            val dictionary = dictionary(language)
            val raw = "🙂 ($typo).\n0012\t$typo\nhttps://test/$typo $correct"
            val result = reviewOcrSpelling(raw, language, dictionary)
            assertEquals(language.label, listOf(correct), result.suggestions.map { it.replacement })
            assertEquals(language.label, raw, result.applyTo(raw, emptySet()))
            assertEquals(language.label, raw.replaceFirst(typo, correct),
                result.applyTo(raw, setOf(result.suggestions.single().start)))
        }
    }

    @Test
    fun selectsLanguagesWithinTheRecognizedScriptAndKeepsUnknownScriptsReviewable() {
        for (script in OcrScript.entries) assertTrue(OcrSpellingLanguage.options(script).any())
        assertEquals(OcrSpellingLanguage.FRENCH, OcrSpellingLanguage.default(OcrScript.LATIN, Locale.CANADA_FRENCH))
        assertEquals(OcrSpellingLanguage.TRADITIONAL_CHINESE, OcrSpellingLanguage.default(OcrScript.CHINESE, Locale.TAIWAN))
        assertEquals(OcrSpellingLanguage.HINDI, OcrSpellingLanguage.default(OcrScript.DEVANAGARI, Locale.ENGLISH))
        assertEquals(OcrSpellingLanguage.entries.toList(), OcrSpellingLanguage.options(null))
    }

    @Test
    fun accentRecoveryUsesTheOriginalUnicodeSpanAndSkipsAmbiguousWords() {
        val result = reviewOcrSpelling("🙂 e\u0301le\u0301ve", OcrSpellingLanguage.FRENCH, setOf("élève"))
        assertEquals("🙂 élève", result.applyTo("🙂 e\u0301le\u0301ve", setOf(3)))
        val ambiguous = reviewOcrSpelling("documentt", OcrSpellingLanguage.FRENCH, setOf("document", "documents"))
        assertTrue(ambiguous.suggestions.isEmpty())
    }

    private fun dictionary(language: OcrSpellingLanguage): Set<String> =
        File("src/main/assets", language.asset).inputStream().use { raw ->
            val input = if (language.asset.endsWith(".gzip")) GZIPInputStream(raw) else raw
            readSpellingWords(input, language) {}
        }
}
