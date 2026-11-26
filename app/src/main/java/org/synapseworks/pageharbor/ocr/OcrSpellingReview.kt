package org.synapseworks.pageharbor.ocr

import java.text.Normalizer
import java.util.Locale

internal const val OCR_SPELLING_PAGE_LIMIT = 24_000
private const val SUGGESTION_LIMIT = 24
private const val UNKNOWN_WORD_LIMIT = 180
private const val MIN_WORD_LENGTH = 3
private const val MAX_WORD_LENGTH = 24
private const val EDGE_PUNCTUATION = ".,;:!?()[]{}\"“”‘’"

internal data class OcrSpellingSuggestion(
    val start: Int,
    val original: String,
    val replacement: String,
)

internal data class OcrSpellingReview(
    val suggestions: List<OcrSpellingSuggestion>,
    val reachedReviewLimit: Boolean = false,
) {
    fun applyTo(text: String, selectedStarts: Set<Int>): String =
        StringBuilder(text).apply {
            for (suggestion in suggestions.asReversed()) {
                if (suggestion.start !in selectedStarts) continue
                val end = suggestion.start + suggestion.original.length
                if (text.regionMatches(suggestion.start, suggestion.original, 0, suggestion.original.length)) {
                    replace(suggestion.start, end, suggestion.replacement)
                }
            }
        }.toString()
}

internal fun reviewEnglishOcrSpelling(
    text: String,
    containsWord: (String) -> Boolean,
    checkActive: () -> Unit = {},
): OcrSpellingReview {
    if (text.length > OCR_SPELLING_PAGE_LIMIT) return OcrSpellingReview(emptyList(), reachedReviewLimit = true)
    val suggestions = mutableListOf<OcrSpellingSuggestion>()
    val examined = mutableMapOf<String, String?>()
    var lineStart = 0
    for (line in text.splitToSequence('\n')) {
        checkActive()
        // Structured OCR has stronger fidelity requirements than prose.
        if ('\t' !in line && '|' !in line) {
            for (match in nonSpaceTokens.findAll(line)) {
                val word = match.value.trim { it in EDGE_PUNCTUATION }
                if (word.length !in MIN_WORD_LENGTH..MAX_WORD_LENGTH || !word.all { it in 'a'..'z' }) continue
                if (containsWord(word)) continue
                if (word !in examined) {
                    if (examined.size >= UNKNOWN_WORD_LIMIT) return OcrSpellingReview(suggestions, reachedReviewLimit = true)
                    examined[word] = singleEditSuggestion(word, containsWord, checkActive)
                }
                val replacement = examined[word] ?: continue
                if (suggestions.size >= SUGGESTION_LIMIT) return OcrSpellingReview(suggestions, reachedReviewLimit = true)
                val start = lineStart + match.range.first + match.value.indexOf(word)
                suggestions += OcrSpellingSuggestion(start, word, replacement)
            }
        }
        lineStart += line.length + 1
    }
    return OcrSpellingReview(suggestions)
}

private val nonSpaceTokens = Regex("\\S+")

private fun singleEditSuggestion(
    word: String,
    containsWord: (String) -> Boolean,
    checkActive: () -> Unit,
): String? {
    fun Sequence<String>.matches() = filter { it != word && containsWord(it) }.distinct().take(2).toList()

    val letters = word.toCharArray()
    val transpositions = sequence {
        for (position in 0 until word.lastIndex) {
            checkActive()
            letters[position] = word[position + 1]
            letters[position + 1] = word[position]
            yield(letters.concatToString())
            letters[position] = word[position]
            letters[position + 1] = word[position + 1]
        }
    }.matches()
    if (transpositions.isNotEmpty() || word.length == MIN_WORD_LENGTH) return transpositions.singleOrNull()

    // An ambiguous correction gives the reader no useful signal. Omit it.
    return sequence {
        for (position in word.indices) {
            checkActive()
            yield(word.removeRange(position, position + 1))
            for (letter in 'a'..'z') {
                if (letter == word[position]) continue
                letters[position] = letter
                yield(letters.concatToString())
            }
            letters[position] = word[position]
        }
        for (position in 0..word.length) {
            checkActive()
            for (letter in 'a'..'z') yield(word.substring(0, position) + letter + word.substring(position))
        }
    }.matches().singleOrNull()
}

internal fun reviewOcrSpelling(
    text: String,
    language: OcrSpellingLanguage,
    words: Set<String>,
    checkActive: () -> Unit = {},
): OcrSpellingReview {
    if (language == OcrSpellingLanguage.ENGLISH) return reviewEnglishOcrSpelling(text, words::contains, checkActive)
    if (text.length > OCR_SPELLING_PAGE_LIMIT) return OcrSpellingReview(emptyList(), reachedReviewLimit = true)

    val cjk = language.script == OcrScript.CHINESE || language.script == OcrScript.JAPANESE
    val byLength = if (cjk) emptyMap() else words.groupBy(String::length)
    val examined = mutableMapOf<String, String?>()
    val suggestions = mutableListOf<OcrSpellingSuggestion>()
    var lineStart = 0
    for (line in text.splitToSequence('\n')) {
        checkActive()
        if ('\t' !in line && '|' !in line) {
            for (match in nonSpaceTokens.findAll(line)) {
                val token = match.value
                val start = lineStart + match.range.first
                if (token.any { it.isDigit() || it in "/@_\\+" }) continue
                if (cjk) {
                    for (suggestion in cjkSuggestions(token, start, language, words, checkActive)) {
                        if (suggestions.size >= SUGGESTION_LIMIT) return OcrSpellingReview(suggestions, true)
                        suggestions += suggestion
                    }
                    continue
                }
                val original = token.trim { it in EDGE_PUNCTUATION || it == '।' || it == '॥' }
                val normalized = Normalizer.normalize(original, Normalizer.Form.NFC)
                if (normalized.length !in MIN_WORD_LENGTH..MAX_WORD_LENGTH || !language.accepts(normalized)) continue
                val word = normalized.lowercase(Locale.ROOT)
                if (word in words) continue
                if (word !in examined) {
                    if (examined.size >= UNKNOWN_WORD_LIMIT) return OcrSpellingReview(suggestions, true)
                    examined[word] = closestSpelling(word, byLength, language.script == OcrScript.LATIN, checkActive)
                }
                val correction = examined[word] ?: continue
                if (suggestions.size >= SUGGESTION_LIMIT) return OcrSpellingReview(suggestions, true)
                val replacement = if (normalized.first().isUpperCase()) correction.replaceFirstChar { it.titlecase(Locale.ROOT) } else correction
                suggestions += OcrSpellingSuggestion(start + token.indexOf(original), original, replacement)
            }
        }
        lineStart += line.length + 1
    }
    return OcrSpellingReview(suggestions)
}

private fun OcrSpellingLanguage.accepts(word: String): Boolean = when (script) {
    OcrScript.LATIN -> word.withIndex().all { (index, c) ->
        Character.UnicodeScript.of(c.code) == Character.UnicodeScript.LATIN &&
            (c.isLowerCase() || this == OcrSpellingLanguage.GERMAN && index == 0 && c.isUpperCase())
    }
    OcrScript.DEVANAGARI -> word.all {
        Character.UnicodeScript.of(it.code) == Character.UnicodeScript.DEVANAGARI &&
            (it.isLetter() || it.category in devanagariMarks)
    }
    OcrScript.KOREAN -> word.all { it in '\uAC00'..'\uD7A3' }
    OcrScript.CHINESE, OcrScript.JAPANESE -> false
}

private val devanagariMarks = setOf(CharCategory.NON_SPACING_MARK, CharCategory.COMBINING_SPACING_MARK)

private fun closestSpelling(
    word: String,
    wordsByLength: Map<Int, List<String>>,
    preferAccents: Boolean,
    checkActive: () -> Unit,
): String? {
    var best: String? = null
    var bestRank = 0
    var ambiguous = false
    for (length in word.length - 1..word.length + 1) {
        for ((index, candidate) in wordsByLength[length].orEmpty().withIndex()) {
            if (index % 256 == 0) checkActive()
            val rank = spellingEditRank(word, candidate, preferAccents)
            when {
                rank > bestRank -> {
                    best = candidate
                    bestRank = rank
                    ambiguous = false
                }
                rank == bestRank && rank > 0 -> ambiguous = true
            }
        }
    }
    return best.takeUnless { ambiguous }
}

private fun spellingEditRank(word: String, candidate: String, preferAccents: Boolean): Int {
    var first = 0
    while (first < minOf(word.length, candidate.length) && word[first] == candidate[first]) first++
    if (word.length != candidate.length) {
        val (shorter, longer) = if (word.length < candidate.length) word to candidate else candidate to word
        return if (shorter.regionMatches(first, longer, first + 1, shorter.length - first)) 1 else 0
    }
    if (first == word.length) return 0
    val rest = word.length - first - 1
    if (word.regionMatches(first + 1, candidate, first + 1, rest)) {
        return if (preferAccents && accentBase(word[first]) == accentBase(candidate[first])) 3 else 1
    }
    return if (rest > 0 && word[first] == candidate[first + 1] && word[first + 1] == candidate[first] &&
        word.regionMatches(first + 2, candidate, first + 2, rest - 1)
    ) 2 else 0
}

private val accentedLetters = listOf("aàáâãäåă", "cç", "eèéêë", "iìíîï", "nñ", "oòóôõö", "sșş", "tțţ", "uùúûü", "yýÿ")
    .flatMap { group -> group.map { it to group.first() } }.toMap()

private fun accentBase(char: Char): Char = accentedLetters[char] ?: char

private val hanLookalikes = listOf("未末", "日目", "己已巳", "土士", "白百", "請晴情", "清晴", "请情晴", "入人", "子了")
private val japaneseLookalikes = hanLookalikes + listOf("シツ", "ソン", "ロ口", "カ力", "タ夕", "ヘへ", "ベべ")
private val hanAlternatives = alternatives(hanLookalikes)
private val japaneseAlternatives = alternatives(japaneseLookalikes)

private fun alternatives(groups: List<String>): Map<Char, Set<Char>> =
    groups.flatMap { group -> group.map { char -> char to group.filter { it != char }.toList() } }
        .groupBy({ it.first }, { it.second }).mapValues { (_, matches) -> matches.flatten().toSet() }

private fun cjkSuggestions(
    text: String,
    start: Int,
    language: OcrSpellingLanguage,
    words: Set<String>,
    checkActive: () -> Unit,
): Sequence<OcrSpellingSuggestion> = sequence {
    val japanese = language == OcrSpellingLanguage.JAPANESE
    val replacements = if (japanese) japaneseAlternatives else hanAlternatives
    var offset = 0
    while (offset < text.length) {
        checkActive()
        var knownLength = 1
        for (length in minOf(5, text.length - offset) downTo 2) {
            val original = text.substring(offset, offset + length)
            if (!original.all { isCjkLetter(it, japanese) }) continue
            if (original in words) {
                knownLength = length
                break
            }
            val candidates = sequence {
                for ((index, char) in original.withIndex()) {
                    for (replacement in replacements[char].orEmpty()) {
                        yield(original.replaceRange(index, index + 1, replacement.toString()))
                    }
                }
            }.filter { it in words }.distinct().take(2).toList()
            if (candidates.size == 1) {
                yield(OcrSpellingSuggestion(start + offset, original, candidates.single()))
                knownLength = length
                break
            }
        }
        offset += knownLength
    }
}

private fun isCjkLetter(char: Char, japanese: Boolean): Boolean =
    char in '\u3400'..'\u9FFF' || char in '\uF900'..'\uFAFF' ||
        japanese && (char in '\u3041'..'\u3096' || char in '\u30A1'..'\u30FA' || char == 'ー')
