package org.synapseworks.pageharbor.ocr

import java.io.InputStream
import java.util.Locale

internal enum class OcrSpellingLanguage(
    val tag: String,
    val script: OcrScript,
    // AAPT expands filenames ending in .gz. The .gzip suffix preserves the pinned compressed bytes.
    val asset: String = "ocr/$tag-words.txt.gzip",
) {
    ENGLISH("en", OcrScript.LATIN, "ocr/english-words.txt"),
    FRENCH("fr", OcrScript.LATIN),
    GERMAN("de", OcrScript.LATIN),
    IRISH("ga", OcrScript.LATIN, "ocr/ga-frequency.txt"),
    ITALIAN("it", OcrScript.LATIN),
    ROMANIAN("ro", OcrScript.LATIN),
    SPANISH("es", OcrScript.LATIN),
    SIMPLIFIED_CHINESE("zh-Hans", OcrScript.CHINESE, "ocr/zh-words.txt.gzip"),
    TRADITIONAL_CHINESE("zh-Hant", OcrScript.CHINESE),
    JAPANESE("ja", OcrScript.JAPANESE),
    KOREAN("ko", OcrScript.KOREAN),
    HINDI("hi", OcrScript.DEVANAGARI),
    MARATHI("mr", OcrScript.DEVANAGARI),
    NEPALI("ne", OcrScript.DEVANAGARI),
    ;

    val label: String
        get() = when (this) {
            SIMPLIFIED_CHINESE -> "Chinese (Simplified)"
            TRADITIONAL_CHINESE -> "Chinese (Traditional)"
            else -> Locale.forLanguageTag(tag).getDisplayLanguage(Locale.ENGLISH)
        }

    companion object {
        fun options(script: OcrScript?): List<OcrSpellingLanguage> =
            entries.filter { script == null || it.script == script }

        fun default(script: OcrScript?, locale: Locale): OcrSpellingLanguage {
            val choices = options(script)
            return choices.firstOrNull { it.tag == locale.toLanguageTag() }
                ?: if (locale.language == "zh" && (locale.script == "Hant" || locale.country in setOf("TW", "HK", "MO")) &&
                    TRADITIONAL_CHINESE in choices
                ) TRADITIONAL_CHINESE
                else choices.firstOrNull { it.tag.substringBefore('-') == locale.language } ?: choices.first()
        }
    }
}

internal fun readSpellingWords(
    input: InputStream,
    language: OcrSpellingLanguage,
    checkActive: () -> Unit,
): Set<String> = input.bufferedReader(Charsets.UTF_8).useLines { lines ->
    buildSet {
        lines.forEachIndexed { index, line ->
            if (index % 4096 == 0) checkActive()
            val word = if (language == OcrSpellingLanguage.IRISH) {
                if (line.firstOrNull()?.isDigit() != true) return@forEachIndexed
                line.substringAfter('\t').substringBefore('\t').lowercase(Locale.ROOT)
            } else line
            if (word.length in 2..25) add(word)
        }
    }
}
