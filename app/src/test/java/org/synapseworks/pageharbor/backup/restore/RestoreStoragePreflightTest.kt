package org.synapseworks.pageharbor.backup.restore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.synapseworks.pageharbor.backup.format.BackupFormatLimits
import org.synapseworks.pageharbor.backup.format.BackupIntegrity
import org.synapseworks.pageharbor.backup.format.BackupManifest
import org.synapseworks.pageharbor.backup.format.BackupMetadataPaths
import org.synapseworks.pageharbor.backup.format.BackupOcrManifest
import org.synapseworks.pageharbor.backup.format.BackupProducer
import org.synapseworks.pageharbor.backup.format.BackupSummary
import org.synapseworks.pageharbor.backup.format.RME_BACKUP_CHECKSUMS_PATH
import org.synapseworks.pageharbor.backup.format.RME_BACKUP_DOCUMENTS_PATH
import org.synapseworks.pageharbor.backup.format.RME_BACKUP_FOLDERS_PATH
import org.synapseworks.pageharbor.backup.format.RME_BACKUP_OCR_ARTIFACTS_PATH
import org.synapseworks.pageharbor.backup.format.RME_BACKUP_OCR_CORRECTIONS_PATH
import org.synapseworks.pageharbor.backup.format.RME_BACKUP_OCR_DOCUMENT_STATES_PATH
import org.synapseworks.pageharbor.backup.format.RME_BACKUP_OCR_PAGE_STATES_PATH
import org.synapseworks.pageharbor.backup.format.RME_BACKUP_PAGES_PATH
import org.synapseworks.pageharbor.backup.format.RME_BACKUP_SOURCE_ASSETS_PATH

class RestoreStoragePreflightTest {
    @Test
    fun tinyArchiveKeepsFixedReserveAndUsesInclusiveCapacityBoundary() {
        val estimate = RestoreStorageEstimator.beforeArchiveCopy(
            archiveByteLength = 1L * MIB,
            availableBytes = 65L * MIB,
        )

        assertEquals(65L * MIB, estimate.requiredAdditionalBytes)
        assertTrue(DefaultRestoreStoragePreflight.hasCapacity(estimate))
        assertTrue(
            DefaultRestoreStoragePreflight.hasCapacity(
                estimate.copy(availableBytes = estimate.requiredAdditionalBytes + 1L),
            ),
        )
        assertFalse(
            DefaultRestoreStoragePreflight.hasCapacity(
                estimate.copy(availableBytes = estimate.requiredAdditionalBytes - 1L),
            ),
        )
        assertEquals(
            64L * MIB,
            RestoreStorageEstimator.beforeStaging(availableBytes = null).requiredAdditionalBytes,
        )
    }

    @Test
    fun assetHeavyManifestReservesOneStagedContentCopyIndexAndFixedMargin() {
        val manifest = manifest(
            contentBytes = 450L * MIB,
            folders = 20,
            documents = 50,
            pages = 450,
            sources = 50,
        )

        val estimate = RestoreStorageEstimator.afterManifest(manifest, LIMITS, availableBytes = null)

        assertEquals(732_198_008L, estimate.requiredAdditionalBytes)
    }

    @Test
    fun ocrHeavyManifestDefersPermanentDatabaseFtsAndWalUntilMeasured() {
        val manifest = manifest(
            contentBytes = 32L * MIB,
            folders = 10,
            documents = 100,
            pages = 500,
            sources = 0,
            ocr = BackupOcrManifest(
                documentStatesPath = RME_BACKUP_OCR_DOCUMENT_STATES_PATH,
                pageStatesPath = RME_BACKUP_OCR_PAGE_STATES_PATH,
                artifactsPath = RME_BACKUP_OCR_ARTIFACTS_PATH,
                correctionsPath = RME_BACKUP_OCR_CORRECTIONS_PATH,
                documentStateCount = 100,
                pageStateCount = 500,
                artifactCount = 500,
                correctionCount = 50,
                lineCount = 5_000,
                lineByteLength = 2L * MIB,
                lineChunks = emptyList(),
            ),
        )

        val staging = RestoreStorageEstimator.afterManifest(manifest, LIMITS, availableBytes = null)
        val measuredActivation = RestoreStorageEstimator.beforeActivation(
            contentByteLength = manifest.summary.contentByteLength,
            metadataByteLength = 6L * MIB,
            ocrTextByteLength = 2L * MIB,
            rowCount = 6_760L,
            availableBytes = null,
        )

        assertEquals(504_680_508L, staging.requiredAdditionalBytes)
        assertEquals(144_901_632L, measuredActivation.requiredAdditionalBytes)
        assertTrue(staging.requiredAdditionalBytes > measuredActivation.requiredAdditionalBytes)
    }

    @Test
    fun moderateAndLargeAssetGrowthRemainsLinearOnceMetadataBoundsAreCapped() {
        val moderate = RestoreStorageEstimator.afterManifest(
            manifest(contentBytes = 900L * MIB, folders = 200, documents = 800, pages = 1_000, sources = 80),
            LIMITS,
            availableBytes = null,
        )
        val large = RestoreStorageEstimator.afterManifest(
            manifest(contentBytes = 4_500L * MIB, folders = 1_000, documents = 4_000, pages = 5_000, sources = 400),
            LIMITS,
            availableBytes = null,
        )

        assertEquals(1_280_327_680L, moderate.requiredAdditionalBytes)
        assertEquals(5_059_461_120L, large.requiredAdditionalBytes)
        assertEquals(3_779_133_440L, large.requiredAdditionalBytes - moderate.requiredAdditionalBytes)
    }

    @Test
    fun activationEstimateIncludesDatabaseFtsWalAndTwentyFivePercentMargin() {
        val estimate = RestoreStorageEstimator.beforeActivation(
            contentByteLength = 500L * MIB,
            metadataByteLength = 20L * MIB,
            ocrTextByteLength = 8L * MIB,
            rowCount = 10_000L,
            availableBytes = 700L * MIB,
            reclaimableBytes = 100L * MIB,
        )

        assertEquals(835_132_160L, estimate.requiredAdditionalBytes)
        assertEquals(800L * MIB, estimate.availableBytes)
    }

    @Test
    fun insufficientStorageRejectsImmediatelyBelowEveryComputedBoundary() {
        val staging = RestoreStorageEstimator.afterManifest(
            manifest(contentBytes = 100L * MIB, folders = 5, documents = 10, pages = 100, sources = 5),
            LIMITS,
            availableBytes = null,
        )
        val activation = RestoreStorageEstimator.beforeActivation(
            contentByteLength = 100L * MIB,
            metadataByteLength = 4L * MIB,
            ocrTextByteLength = 1L * MIB,
            rowCount = 1_000L,
            availableBytes = null,
        )

        listOf(staging, activation).forEach { estimate ->
            assertTrue(DefaultRestoreStoragePreflight.hasCapacity(estimate.copy(availableBytes = estimate.requiredAdditionalBytes)))
            assertTrue(DefaultRestoreStoragePreflight.hasCapacity(estimate.copy(availableBytes = estimate.requiredAdditionalBytes + 1L)))
            assertFalse(DefaultRestoreStoragePreflight.hasCapacity(estimate.copy(availableBytes = estimate.requiredAdditionalBytes - 1L)))
        }
    }

    @Test
    fun allEstimatorPhasesSaturateInsteadOfOverflowing() {
        assertEquals(
            Long.MAX_VALUE,
            RestoreStorageEstimator.beforeArchiveCopy(Long.MAX_VALUE, null).requiredAdditionalBytes,
        )
        assertEquals(
            Long.MAX_VALUE,
            RestoreStorageEstimator.afterManifest(
                manifest(contentBytes = Long.MAX_VALUE, folders = Int.MAX_VALUE, documents = Int.MAX_VALUE, pages = Int.MAX_VALUE, sources = Int.MAX_VALUE),
                LIMITS,
                availableBytes = null,
            ).requiredAdditionalBytes,
        )
        assertEquals(
            Long.MAX_VALUE,
            RestoreStorageEstimator.beforeActivation(
                contentByteLength = Long.MAX_VALUE,
                metadataByteLength = Long.MAX_VALUE,
                ocrTextByteLength = Long.MAX_VALUE,
                rowCount = Long.MAX_VALUE,
                availableBytes = Long.MAX_VALUE,
                reclaimableBytes = Long.MAX_VALUE,
            ).requiredAdditionalBytes,
        )
        assertEquals(
            Long.MAX_VALUE,
            RestoreStorageEstimator.beforeActivation(
                contentByteLength = 0L,
                metadataByteLength = 0L,
                ocrTextByteLength = 0L,
                rowCount = 0L,
                availableBytes = Long.MAX_VALUE,
                reclaimableBytes = Long.MAX_VALUE,
            ).availableBytes,
        )
    }

    private fun manifest(
        contentBytes: Long,
        folders: Int,
        documents: Int,
        pages: Int,
        sources: Int,
        ocr: BackupOcrManifest? = null,
    ): BackupManifest = BackupManifest(
        formatVersion = if (ocr == null) 1 else 2,
        minimumReaderVersion = if (ocr == null) 1 else 2,
        requiredFeatures = emptyList(),
        backupId = "00000000-0000-4000-8000-000000000099",
        createdAtEpochMillis = 1L,
        producer = BackupProducer("org.synapseworks.pageharbor", "1.6.0", 17),
        summary = BackupSummary(folders, documents, pages, sources, contentBytes),
        metadata = BackupMetadataPaths(
            folders = RME_BACKUP_FOLDERS_PATH,
            documents = RME_BACKUP_DOCUMENTS_PATH,
            pages = RME_BACKUP_PAGES_PATH,
            sourceAssets = RME_BACKUP_SOURCE_ASSETS_PATH,
        ),
        integrity = BackupIntegrity("SHA-256", RME_BACKUP_CHECKSUMS_PATH),
        ocr = ocr,
    )

    private companion object {
        const val MIB = 1024L * 1024L
        val LIMITS = BackupFormatLimits()
    }
}
