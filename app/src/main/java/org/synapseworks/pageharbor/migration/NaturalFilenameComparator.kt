package org.synapseworks.pageharbor.migration

import java.util.Locale

/** Deterministic, locale-independent ordering for page-like filenames. */
object NaturalFilenameComparator : Comparator<String> {
    override fun compare(left: String, right: String): Int {
        if (left === right) return 0
        val leftFolded = left.lowercase(Locale.ROOT)
        val rightFolded = right.lowercase(Locale.ROOT)
        var leftIndex = 0
        var rightIndex = 0
        while (leftIndex < leftFolded.length && rightIndex < rightFolded.length) {
            val leftDigit = leftFolded[leftIndex].isDigit()
            val rightDigit = rightFolded[rightIndex].isDigit()
            if (leftDigit && rightDigit) {
                val leftEnd = leftFolded.endOfDigitRun(leftIndex)
                val rightEnd = rightFolded.endOfDigitRun(rightIndex)
                val numberComparison = compareDigitRuns(
                    leftFolded.substring(leftIndex, leftEnd),
                    rightFolded.substring(rightIndex, rightEnd),
                )
                if (numberComparison != 0) return numberComparison
                leftIndex = leftEnd
                rightIndex = rightEnd
            } else {
                if (leftDigit != rightDigit) {
                    return leftFolded[leftIndex].compareTo(rightFolded[rightIndex])
                }
                val leftEnd = leftFolded.endOfNonDigitRun(leftIndex)
                val rightEnd = rightFolded.endOfNonDigitRun(rightIndex)
                val textComparison = leftFolded.substring(leftIndex, leftEnd)
                    .compareTo(rightFolded.substring(rightIndex, rightEnd))
                if (textComparison != 0) return textComparison
                leftIndex = leftEnd
                rightIndex = rightEnd
            }
        }
        val foldedComparison = leftFolded.length.compareTo(rightFolded.length)
        return if (foldedComparison != 0) foldedComparison else left.compareTo(right)
    }

    private fun compareDigitRuns(left: String, right: String): Int {
        val leftSignificant = left.trimStart('0').ifEmpty { "0" }
        val rightSignificant = right.trimStart('0').ifEmpty { "0" }
        val magnitude = leftSignificant.length.compareTo(rightSignificant.length)
        if (magnitude != 0) return magnitude
        val value = leftSignificant.compareTo(rightSignificant)
        if (value != 0) return value
        // A shorter original run ("1") sorts before an equivalent zero-padded run ("01").
        return left.length.compareTo(right.length)
    }

    private fun String.endOfDigitRun(start: Int): Int {
        var index = start
        while (index < length && this[index].isDigit()) index += 1
        return index
    }

    private fun String.endOfNonDigitRun(start: Int): Int {
        var index = start
        while (index < length && !this[index].isDigit()) index += 1
        return index
    }
}
