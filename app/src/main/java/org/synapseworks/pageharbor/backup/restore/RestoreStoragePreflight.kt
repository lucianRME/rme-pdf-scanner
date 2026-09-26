package org.synapseworks.pageharbor.backup.restore

import org.synapseworks.pageharbor.backup.format.BackupFormatLimits
import org.synapseworks.pageharbor.backup.format.BackupManifest

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
    fun beforeArchiveCopy(archiveByteLength: Long?, availableBytes: Long?): RestoreStorageEstimate {
        val knownArchiveBytes = archiveByteLength ?: 0L
        return RestoreStorageEstimate(
            requiredAdditionalBytes = saturatingAdd(knownArchiveBytes, MINIMUM_SAFETY_MARGIN_BYTES),
            availableBytes = availableBytes,
        )
    }

    fun beforeStaging(availableBytes: Long?): RestoreStorageEstimate = RestoreStorageEstimate(
        requiredAdditionalBytes = MINIMUM_SAFETY_MARGIN_BYTES,
        availableBytes = availableBytes,
    )

    /**
     * Runs immediately after the bounded manifest is decoded, before document assets are staged.
     * It covers the staged content copy, the operation index, and a fixed free-space reserve. The
     * permanent database/FTS/WAL estimate is intentionally deferred until staging has measured the
     * actual metadata sizes; activation cannot begin until that later preflight succeeds.
     */
    fun afterManifest(
        manifest: BackupManifest,
        limits: BackupFormatLimits,
        availableBytes: Long?,
    ): RestoreStorageEstimate {
        val summary = manifest.summary
        val ocr = manifest.ocr
        val baseMetadata = listOf(
            summary.folderCount.toLong(),
            summary.documentCount.toLong(),
            summary.pageCount.toLong(),
            summary.sourceAssetCount.toLong(),
        ).fold(0L) { total, count ->
            saturatingAdd(total, boundedJsonEntryEstimate(count, limits.maximumMetadataEntryBytes, limits))
        }
        val ocrMetadata = if (ocr == null) {
            0L
        } else {
            listOf(
                ocr.documentStateCount.toLong(),
                ocr.pageStateCount.toLong(),
                ocr.artifactCount.toLong(),
                ocr.correctionCount.toLong(),
            ).fold(ocr.lineByteLength) { total, count ->
                saturatingAdd(
                    total,
                    boundedJsonEntryEstimate(count, limits.maximumOcrMetadataEntryBytes, limits),
                )
            }
        }
        val metadataUpperBound = saturatingAdd(baseMetadata, ocrMetadata)
        val rowCount = listOf(
            summary.folderCount.toLong(),
            summary.documentCount.toLong(),
            summary.pageCount.toLong(),
            summary.sourceAssetCount.toLong(),
            ocr?.documentStateCount?.toLong() ?: 0L,
            ocr?.pageStateCount?.toLong() ?: 0L,
            ocr?.artifactCount?.toLong() ?: 0L,
            ocr?.correctionCount?.toLong() ?: 0L,
            ocr?.lineCount?.toLong() ?: 0L,
        ).fold(0L, ::saturatingAdd)
        val stagingIndex = saturatingAdd(
            metadataUpperBound,
            saturatingMultiply(rowCount, STAGING_INDEX_BYTES_PER_ROW),
        )
        val stagingGrowth = saturatingAdd(summary.contentByteLength, stagingIndex)
        return RestoreStorageEstimate(
            requiredAdditionalBytes = saturatingAdd(
                stagingGrowth,
                MINIMUM_SAFETY_MARGIN_BYTES,
            ),
            availableBytes = availableBytes,
        )
    }

    /**
     * Conservative additional-space estimate while verified staging still exists. SQLite layout is
     * not exactly predictable, so logical metadata/text sizes are expanded for table/index/FTS
     * pages and again for a bounded WAL checkpoint window. Per-row overhead covers geometry and
     * index cells that are larger than their JSON representation.
     */
    fun beforeActivation(
        contentByteLength: Long,
        metadataByteLength: Long,
        ocrTextByteLength: Long,
        rowCount: Long,
        availableBytes: Long?,
        reclaimableBytes: Long = 0L,
    ): RestoreStorageEstimate {
        val rowOverhead = saturatingMultiply(rowCount, ESTIMATED_BYTES_PER_ROW)
        val databaseGrowth = saturatingAdd(
            saturatingMultiply(metadataByteLength, DATABASE_EXPANSION_FACTOR),
            saturatingAdd(saturatingMultiply(ocrTextByteLength, FTS_EXPANSION_FACTOR), rowOverhead),
        )
        val walAllowance = saturatingAdd(databaseGrowth, saturatingMultiply(rowCount, WAL_BYTES_PER_ROW))
        val base = saturatingAdd(contentByteLength, saturatingAdd(databaseGrowth, walAllowance))
        val proportionalMargin = base / 4L
        return RestoreStorageEstimate(
            requiredAdditionalBytes = saturatingAdd(
                base,
                maxOf(MINIMUM_SAFETY_MARGIN_BYTES, proportionalMargin),
            ),
            availableBytes = availableBytes?.let { saturatingAdd(it, reclaimableBytes) },
        )
    }

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private fun saturatingMultiply(value: Long, multiplier: Long): Long =
        if (value < 0L || multiplier < 0L || (multiplier != 0L && value > Long.MAX_VALUE / multiplier)) {
            Long.MAX_VALUE
        } else {
            value * multiplier
        }

    private fun boundedJsonEntryEstimate(
        recordCount: Long,
        maximumEntryBytes: Int,
        limits: BackupFormatLimits,
    ): Long = minOf(
        maximumEntryBytes.toLong(),
        saturatingMultiply(recordCount, limits.maximumJsonLineBytes.toLong() + 1L),
    )

    private const val DATABASE_EXPANSION_FACTOR = 2L
    private const val FTS_EXPANSION_FACTOR = 3L
    private const val ESTIMATED_BYTES_PER_ROW = 384L
    private const val WAL_BYTES_PER_ROW = 192L
    private const val STAGING_INDEX_BYTES_PER_ROW = 512L
    private const val MINIMUM_SAFETY_MARGIN_BYTES = 64L * 1024L * 1024L
}
