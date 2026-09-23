package org.synapseworks.pageharbor.backup.restore

data class RestoreStorageEstimate(
    val requiredAdditionalBytes: Long,
    val availableBytes: Long?,
)

fun interface RestoreStoragePreflight {
    fun hasCapacity(estimate: RestoreStorageEstimate): Boolean
}

object DefaultRestoreStoragePreflight : RestoreStoragePreflight {
    override fun hasCapacity(estimate: RestoreStorageEstimate): Boolean =
        estimate.availableBytes?.let { it >= estimate.requiredAdditionalBytes } ?: true
}

internal object RestoreStorageEstimator {
    fun beforeStaging(archiveByteLength: Long?, availableBytes: Long?): RestoreStorageEstimate {
        val knownArchiveBytes = archiveByteLength ?: 0L
        return RestoreStorageEstimate(
            requiredAdditionalBytes = saturatingAdd(knownArchiveBytes, MINIMUM_SAFETY_MARGIN_BYTES),
            availableBytes = availableBytes,
        )
    }

    fun beforeActivation(contentByteLength: Long, availableBytes: Long?): RestoreStorageEstimate {
        val proportionalMargin = contentByteLength / 10L
        return RestoreStorageEstimate(
            requiredAdditionalBytes = saturatingAdd(
                contentByteLength,
                maxOf(MINIMUM_SAFETY_MARGIN_BYTES, proportionalMargin),
            ),
            availableBytes = availableBytes,
        )
    }

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private const val MINIMUM_SAFETY_MARGIN_BYTES = 32L * 1024L * 1024L
}
