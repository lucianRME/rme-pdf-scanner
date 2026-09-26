package org.synapseworks.pageharbor.ocr

import java.util.Locale

data class OcrScriptRecommendation(
    val script: OcrScript,
    val basis: Basis,
) {
    enum class Basis {
        EXPLICIT_LOCALE_SCRIPT,
        LOCALE_LANGUAGE,
        LATIN_FALLBACK,
    }
}

/** Pure primary-locale mapping. It recommends a script but never changes a manual selection. */
object OcrLocaleScriptMapper {
    fun recommend(primaryLocale: Locale?): OcrScriptRecommendation {
        if (primaryLocale == null) return latinFallback()

        val explicitScript = primaryLocale.script.takeIf(String::isNotBlank)
        if (explicitScript != null) {
            val mapped = when (explicitScript.lowercase(Locale.ROOT)) {
                "hans", "hant" -> OcrScript.CHINESE
                "jpan" -> OcrScript.JAPANESE
                "kore" -> OcrScript.KOREAN
                "deva" -> OcrScript.DEVANAGARI
                "latn" -> OcrScript.LATIN
                else -> null
            }
            return mapped?.let { script ->
                OcrScriptRecommendation(
                    script = script,
                    basis = OcrScriptRecommendation.Basis.EXPLICIT_LOCALE_SCRIPT,
                )
            } ?: latinFallback()
        }

        val mapped = when (primaryLocale.language.lowercase(Locale.ROOT)) {
            "zh" -> OcrScript.CHINESE
            "ja" -> OcrScript.JAPANESE
            "ko" -> OcrScript.KOREAN
            "hi", "mr", "ne" -> OcrScript.DEVANAGARI
            else -> OcrScript.LATIN
        }
        val basis = if (mapped == OcrScript.LATIN) {
            OcrScriptRecommendation.Basis.LATIN_FALLBACK
        } else {
            OcrScriptRecommendation.Basis.LOCALE_LANGUAGE
        }
        return OcrScriptRecommendation(mapped, basis)
    }

    private fun latinFallback() = OcrScriptRecommendation(
        script = OcrScript.LATIN,
        basis = OcrScriptRecommendation.Basis.LATIN_FALLBACK,
    )
}
