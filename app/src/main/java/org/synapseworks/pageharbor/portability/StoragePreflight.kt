package org.synapseworks.pageharbor.portability

/** A conservative, overflow-safe storage estimate made before a portability operation starts. */
data class StorageEstimate(
    val requiredBytesLowerBound: Long,
    val unknownSourceCount: Int,
    val safetyMarginBytes: Long,
) {
    val isComplete: Boolean
        get() = unknownSourceCount == 0
}

sealed interface StoragePreflightResult {
    data class Sufficient(val estimate: StorageEstimate, val availableBytes: Long) :
        StoragePreflightResult

    data class Insufficient(val estimate: StorageEstimate, val availableBytes: Long) :
        StoragePreflightResult

    /** A provider omitted sizes or the destination did not expose usable free-space metadata. */
    data class Unknown(
        val estimate: StorageEstimate,
        val availableBytes: Long?,
    ) : StoragePreflightResult
}

object StoragePreflight {
    const val DEFAULT_MINIMUM_SAFETY_MARGIN_BYTES = 64L * 1024L * 1024L
    const val DEFAULT_SAFETY_PERCENT = 15

    /**
     * [workingCopyCount] is the number of complete source-sized copies expected to coexist while
     * staging. For example, restore normally needs an extracted staging copy plus the published
     * copy. Unknown SAF sizes remain unknown and are never invented.
     */
    fun estimate(
        sourceSizes: Iterable<Long?>,
        workingCopyCount: Int,
        fixedOverheadBytes: Long = 0L,
        minimumSafetyMarginBytes: Long = DEFAULT_MINIMUM_SAFETY_MARGIN_BYTES,
        safetyPercent: Int = DEFAULT_SAFETY_PERCENT,
    ): StorageEstimate {
        require(workingCopyCount > 0)
        require(fixedOverheadBytes >= 0L)
        require(minimumSafetyMarginBytes >= 0L)
        require(safetyPercent in 0..100)

        var knownBytes = 0L
        var unknownCount = 0
        sourceSizes.forEach { size ->
            when {
                size == null -> unknownCount = unknownCount.saturatedIncrement()
                size < 0L -> throw IllegalArgumentException("Source byte sizes cannot be negative.")
                else -> knownBytes = saturatedAdd(knownBytes, size)
            }
        }
        val stagedBytes = saturatedMultiply(knownBytes, workingCopyCount.toLong())
        val beforeMargin = saturatedAdd(stagedBytes, fixedOverheadBytes)
        val percentageMargin = saturatedMultiply(beforeMargin, safetyPercent.toLong()) / 100L
        val margin = maxOf(minimumSafetyMarginBytes, percentageMargin)
        return StorageEstimate(
            requiredBytesLowerBound = saturatedAdd(beforeMargin, margin),
            unknownSourceCount = unknownCount,
            safetyMarginBytes = margin,
        )
    }

    fun evaluate(estimate: StorageEstimate, availableBytes: Long?): StoragePreflightResult {
        require(availableBytes == null || availableBytes >= 0L)
        if (availableBytes != null && availableBytes < estimate.requiredBytesLowerBound) {
            return StoragePreflightResult.Insufficient(estimate, availableBytes)
        }
        if (availableBytes == null || !estimate.isComplete) {
            return StoragePreflightResult.Unknown(estimate, availableBytes)
        }
        return StoragePreflightResult.Sufficient(estimate, availableBytes)
    }
}

private fun saturatedAdd(left: Long, right: Long): Long =
    if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

private fun saturatedMultiply(left: Long, right: Long): Long = when {
    left == 0L || right == 0L -> 0L
    left > Long.MAX_VALUE / right -> Long.MAX_VALUE
    else -> left * right
}

private fun Int.saturatedIncrement(): Int = if (this == Int.MAX_VALUE) this else this + 1
