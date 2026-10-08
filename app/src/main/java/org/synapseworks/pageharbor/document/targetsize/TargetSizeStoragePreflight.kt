package org.synapseworks.pageharbor.document.targetsize

data class TargetSizeStorageEstimate(
    val sourceBytes: Long,
    val unknownSourceCount: Int,
    val estimatedOutputBytes: Long,
    val temporaryWorkspaceBytes: Long,
    val safetyReserveBytes: Long,
    val requiredAvailableBytes: Long,
) {
    init {
        require(sourceBytes >= 0L)
        require(unknownSourceCount >= 0)
        require(estimatedOutputBytes >= 0L)
        require(temporaryWorkspaceBytes >= 0L)
        require(safetyReserveBytes >= 0L)
        require(requiredAvailableBytes >= 0L)
    }
}

object TargetSizeStoragePreflight {
    const val DEFAULT_SAFETY_RESERVE_BYTES = 32L * 1_024L * 1_024L

    /**
     * Source bytes are reported separately and are not counted as new library/output storage.
     * Workspace covers one retained candidate, all sequentially produced page JPEGs, the active
     * PdfBox scratch store, and one replacement candidate. [candidateUpperBoundBytes] is a
     * conservative encoded-pixel bound, not the user-visible expected output.
     */
    fun estimate(
        sourceSizes: Iterable<Long?>,
        targetBytes: Long,
        candidateUpperBoundBytes: Long,
        safetyReserveBytes: Long = DEFAULT_SAFETY_RESERVE_BYTES,
    ): TargetSizeStorageEstimate {
        require(targetBytes > 0L)
        require(candidateUpperBoundBytes > 0L)
        require(safetyReserveBytes >= 0L)
        var sourceBytes = 0L
        var unknown = 0
        sourceSizes.forEach { size ->
            when {
                size == null -> if (unknown < Int.MAX_VALUE) unknown++
                size < 0L -> throw IllegalArgumentException("Source sizes cannot be negative.")
                else -> sourceBytes = saturatedAdd(sourceBytes, size)
            }
        }
        val workspace = saturatedMultiply(
            maxOf(targetBytes, candidateUpperBoundBytes),
            MAXIMUM_SIMULTANEOUS_CANDIDATE_EQUIVALENTS,
        )
        return TargetSizeStorageEstimate(
            sourceBytes = sourceBytes,
            unknownSourceCount = unknown,
            estimatedOutputBytes = targetBytes,
            temporaryWorkspaceBytes = workspace,
            safetyReserveBytes = safetyReserveBytes,
            requiredAvailableBytes = saturatedAdd(workspace, safetyReserveBytes),
        )
    }

    fun isSufficient(estimate: TargetSizeStorageEstimate, availableBytes: Long?): Boolean? {
        require(availableBytes == null || availableBytes >= 0L)
        return availableBytes?.let { it >= estimate.requiredAvailableBytes }
    }

    private const val MAXIMUM_SIMULTANEOUS_CANDIDATE_EQUIVALENTS = 4L
}
