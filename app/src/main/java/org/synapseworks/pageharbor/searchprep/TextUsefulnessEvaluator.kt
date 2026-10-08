package org.synapseworks.pageharbor.searchprep

import java.text.Normalizer

enum class TextUsefulnessReason {
    USEFUL,
    EMPTY,
    NO_LETTERS_OR_DIGITS,
    CONTROL_HEAVY,
    INVALID_UNICODE_HEAVY,
    PATHOLOGICAL_REPETITION,
    PAGE_TEXT_LIMIT_EXCEEDED,
    EXTRACTION_FAILED,
    ENCRYPTED_PDF,
    MALFORMED_PDF,
}

sealed interface TextUsefulness {
    data class Useful(val normalizedText: String) : TextUsefulness
    data class NeedsOcr(val reason: TextUsefulnessReason) : TextUsefulness
}

/**
 * Script-neutral gate for imported PDF text. It deliberately accepts short legitimate values and
 * makes no language, filename, or document-date inference.
 */
object TextUsefulnessEvaluator {
    fun evaluate(extractedText: String): TextUsefulness {
        if (extractedText.length > MAX_PAGE_CHARACTERS) {
            return TextUsefulness.NeedsOcr(TextUsefulnessReason.PAGE_TEXT_LIMIT_EXCEEDED)
        }
        val normalized = Normalizer.normalize(
            extractedText.replace("\r\n", "\n").replace('\r', '\n'),
            Normalizer.Form.NFC,
        ).trim()
        if (normalized.isBlank()) return TextUsefulness.NeedsOcr(TextUsefulnessReason.EMPTY)

        val codePoints = normalized.codePoints().toArray()
        if (codePoints.none { Character.isLetterOrDigit(it) }) {
            return TextUsefulness.NeedsOcr(TextUsefulnessReason.NO_LETTERS_OR_DIGITS)
        }
        val significantCount = codePoints.count { !Character.isWhitespace(it) }.coerceAtLeast(1)
        val controls = codePoints.count { codePoint ->
            Character.getType(codePoint) == Character.CONTROL.toInt() &&
                codePoint !in setOf('\n'.code, '\r'.code, '\t'.code)
        }
        if (controls.toDouble() / significantCount > MAX_CONTROL_RATIO) {
            return TextUsefulness.NeedsOcr(TextUsefulnessReason.CONTROL_HEAVY)
        }
        val invalid = codePoints.count { codePoint ->
            codePoint == REPLACEMENT_CHARACTER ||
                Character.getType(codePoint) in setOf(
                    Character.PRIVATE_USE.toInt(),
                    Character.UNASSIGNED.toInt(),
                    Character.SURROGATE.toInt(),
                )
        }
        if (invalid.toDouble() / significantCount > MAX_INVALID_RATIO) {
            return TextUsefulness.NeedsOcr(TextUsefulnessReason.INVALID_UNICODE_HEAVY)
        }
        val visible = codePoints.filter { !Character.isWhitespace(it) }
        if (visible.size >= MIN_REPETITION_LENGTH) {
            val dominant = visible.groupingBy { it }.eachCount().maxOf { it.value }
            if (dominant.toDouble() / visible.size >= MAX_DOMINANT_CHARACTER_RATIO) {
                return TextUsefulness.NeedsOcr(TextUsefulnessReason.PATHOLOGICAL_REPETITION)
            }
        }
        val tokens = normalized.split(Regex("\\s+")).filter(String::isNotBlank)
        if (tokens.size >= MIN_REPEATED_TOKEN_COUNT) {
            val repeated = tokens.groupingBy(String::lowercase).eachCount().maxOf { it.value }
            if (repeated.toDouble() / tokens.size >= MAX_REPEATED_TOKEN_RATIO) {
                return TextUsefulness.NeedsOcr(TextUsefulnessReason.PATHOLOGICAL_REPETITION)
            }
        }
        return TextUsefulness.Useful(normalized)
    }

    const val MAX_PAGE_CHARACTERS: Int = 250_000
    private const val MAX_CONTROL_RATIO = 0.05
    private const val MAX_INVALID_RATIO = 0.02
    private const val MIN_REPETITION_LENGTH = 16
    private const val MAX_DOMINANT_CHARACTER_RATIO = 0.90
    private const val MIN_REPEATED_TOKEN_COUNT = 8
    private const val MAX_REPEATED_TOKEN_RATIO = 0.875
    private const val REPLACEMENT_CHARACTER = 0xFFFD
}
