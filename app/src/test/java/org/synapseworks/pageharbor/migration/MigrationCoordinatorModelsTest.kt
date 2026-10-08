package org.synapseworks.pageharbor.migration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.synapseworks.pageharbor.library.duplicate.DuplicateKind
import org.synapseworks.pageharbor.portability.StoragePreflight
import org.synapseworks.pageharbor.portability.StoragePreflightResult

class MigrationCoordinatorModelsTest {
    @Test
    fun storageEstimateRetainsUnknownSizesAndUsesConservativeWorkingCopies() {
        val preview = preview(listOf(100L, null))

        val estimate = migrationBatchStorageEstimate(preview)

        assertEquals(1, estimate.unknownSourceCount)
        assertTrue(estimate.requiredFreeSpace.requiredBytesLowerBound >= 300L)
        assertFalse(estimate.requiredFreeSpace.isComplete)
        assertTrue(estimate.persistentEstimateIsIncomplete)
    }

    @Test
    fun storageEstimateAddsOnlyOnePeakTransientDocumentForLargeBatches() {
        val twoGiB = 2L * 1024L * 1024L * 1024L
        val estimate = migrationBatchStorageEstimate(preview(listOf(twoGiB, twoGiB)))

        assertTrue(estimate.requiredFreeSpace.isComplete)
        assertEquals(twoGiB * 2L, estimate.largestSerialTransientBytes)
        assertEquals(twoGiB * 4L, estimate.estimatedPersistentBytes - 2L * 512L * 1024L)
    }

    @Test
    fun representativePdfSeparatesSourcePersistentTransientAndSafetyBytes() {
        val sourceBytes = 7_507_804L // 7.16 MiB
        val measuredPageBytes = 3_145_728L
        val base = preview(listOf(sourceBytes))
        val measured = base.copy(
            duplicateDocuments = listOf(
                MigrationDuplicatePreview(
                    documentId = "document-0",
                    duplicateKind = DuplicateKind.DIFFERENT,
                    analyzedPageCount = 8,
                    measuredPageBytes = measuredPageBytes,
                    measuredOriginalAssetBytes = sourceBytes,
                ),
            ),
            duplicateAnalysisComplete = true,
        )

        val estimate = migrationBatchStorageEstimate(measured)
        val expectedPersistent = sourceBytes + measuredPageBytes + 512L * 1024L
        val expectedTransient = sourceBytes + measuredPageBytes
        val expectedRequired = expectedPersistent + expectedTransient +
            StoragePreflight.DEFAULT_MINIMUM_SAFETY_MARGIN_BYTES

        assertEquals(sourceBytes, estimate.knownSourceBytes)
        assertEquals(sourceBytes, estimate.originalAssetBytes)
        assertEquals(measuredPageBytes, estimate.pageBytes)
        assertEquals(expectedPersistent, estimate.estimatedPersistentBytes)
        assertEquals(expectedTransient, estimate.largestSerialTransientBytes)
        assertEquals(
            StoragePreflight.DEFAULT_MINIMUM_SAFETY_MARGIN_BYTES,
            estimate.requiredFreeSpace.safetyMarginBytes,
        )
        assertEquals(expectedRequired, estimate.requiredFreeSpace.requiredBytesLowerBound)
        assertTrue(estimate.estimatedPersistentBytes < 150L * 1024L * 1024L)
        assertTrue(
            StoragePreflight.evaluate(
                estimate.requiredFreeSpace,
                expectedRequired - 1L,
            ) is StoragePreflightResult.Insufficient,
        )
    }

    @Test
    fun duplicateApprovalIsExplicitAndScopedToStablePlanId() {
        val decisions = MigrationDuplicateDecisions(setOf("approved"))

        assertTrue(decisions.shouldImport("approved"))
        assertFalse(decisions.shouldImport("another"))
    }

    @Test
    fun previewExposesExactPossibleAndUnclassifiedCountsBeforeExecution() {
        val base = preview(listOf(100L, 200L))
        val analyzed = base.copy(
            duplicateDocuments = listOf(
                MigrationDuplicatePreview(
                    documentId = "document-0",
                    duplicateKind = DuplicateKind.EXACT,
                    analyzedPageCount = 3,
                    existingDocumentId = "existing-exact",
                ),
                MigrationDuplicatePreview(
                    documentId = "document-1",
                    duplicateKind = DuplicateKind.POSSIBLE,
                    analyzedPageCount = 25,
                    existingDocumentId = "existing-possible",
                ),
            ),
            duplicateAnalysisComplete = true,
        )

        assertEquals(1, analyzed.exactDuplicateCount)
        assertEquals(1, analyzed.possibleDuplicateCount)
        assertEquals(0, analyzed.differentDocumentCount)
        assertEquals(28L, analyzed.pageCount)
        assertEquals(emptySet<String>(), analyzed.unclassifiedDocumentIds)
        assertEquals(
            listOf(MigrationPlanDuplicateStrength.EXACT, MigrationPlanDuplicateStrength.POSSIBLE),
            analyzed.sourceAccounting.map(MigrationArtifactAccounting::duplicateStrength),
        )
        assertTrue(analyzed.sourceAccounting.all {
            it.disposition == MigrationArtifactDisposition.DUPLICATE_CANDIDATE
        })
    }

    private fun preview(sizes: List<Long?>): MigrationPreview {
        val documents = sizes.mapIndexed { index, size ->
            val source = MigrationSource(
                id = "source-$index",
                displayName = "Document $index.pdf",
                sizeBytes = size,
            )
            MigrationDocumentPlan(
                id = "document-$index",
                suggestedTitle = "Document $index",
                relativeFolderPath = emptyList(),
                sources = listOf(InspectedMigrationSource(source, MigrationContentType.PDF)),
                grouping = MigrationDocumentGrouping.SINGLE_PDF,
            )
        }
        return MigrationPreview(
            documents = documents,
            rejectedSources = emptyList(),
            storageEstimate = MigrationStorageEstimate(
                knownSourceBytes = sizes.filterNotNull().sum(),
                unknownSizeSourceCount = sizes.count { it == null },
            ),
            inspectedSourceCount = sizes.size,
            requestedSourceCount = sizes.size,
            wasCancelled = false,
            requestedSources = documents.flatMap { document ->
                document.sources.map(InspectedMigrationSource::source)
            },
        )
    }
}
