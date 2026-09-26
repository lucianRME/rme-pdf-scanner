package org.synapseworks.pageharbor.ocr

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class OcrLocaleScriptMapperTest {
    @Test
    fun explicitBcp47ScriptsTakePriority() {
        mapOf(
            "zh-Hans-CN" to OcrScript.CHINESE,
            "zh-Hant-TW" to OcrScript.CHINESE,
            "ja-Jpan-JP" to OcrScript.JAPANESE,
            "ko-Kore-KR" to OcrScript.KOREAN,
            "hi-Deva-IN" to OcrScript.DEVANAGARI,
            "sr-Latn-RS" to OcrScript.LATIN,
        ).forEach { (tag, expected) ->
            val recommendation = OcrLocaleScriptMapper.recommend(Locale.forLanguageTag(tag))

            assertEquals(expected, recommendation.script)
            assertEquals(
                OcrScriptRecommendation.Basis.EXPLICIT_LOCALE_SCRIPT,
                recommendation.basis,
            )
        }
    }

    @Test
    fun languageFallbackMapsSupportedOptionalScripts() {
        mapOf(
            "zh" to OcrScript.CHINESE,
            "ja" to OcrScript.JAPANESE,
            "ko" to OcrScript.KOREAN,
            "hi" to OcrScript.DEVANAGARI,
            "mr" to OcrScript.DEVANAGARI,
            "ne" to OcrScript.DEVANAGARI,
        ).forEach { (language, expected) ->
            val recommendation = OcrLocaleScriptMapper.recommend(Locale.forLanguageTag(language))

            assertEquals(expected, recommendation.script)
            assertEquals(OcrScriptRecommendation.Basis.LOCALE_LANGUAGE, recommendation.basis)
        }
    }

    @Test
    fun latinAndUnsupportedLocalesUseExplicitlyMarkedLatinFallback() {
        listOf("en", "ro", "de", "fr", "it", "es", "ar", "ru").forEach { language ->
            val recommendation = OcrLocaleScriptMapper.recommend(Locale.forLanguageTag(language))

            assertEquals(OcrScript.LATIN, recommendation.script)
            assertEquals(OcrScriptRecommendation.Basis.LATIN_FALLBACK, recommendation.basis)
        }
        assertEquals(
            OcrScriptRecommendation(
                OcrScript.LATIN,
                OcrScriptRecommendation.Basis.LATIN_FALLBACK,
            ),
            OcrLocaleScriptMapper.recommend(null),
        )
    }

    @Test
    fun unsupportedExplicitScriptDoesNotImplySupportFromItsLanguage() {
        val recommendation = OcrLocaleScriptMapper.recommend(Locale.forLanguageTag("ar-Arab"))

        assertEquals(OcrScript.LATIN, recommendation.script)
        assertEquals(OcrScriptRecommendation.Basis.LATIN_FALLBACK, recommendation.basis)
    }
}
