package org.synapseworks.pageharbor.migration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.synapseworks.pageharbor.library.duplicate.DuplicateKind

class MigrationCoordinatorModelsTest {
    @Test
    fun storageEstimateRetainsUnknownSizesAndUsesConservativeWorkingCopies() {
        val preview = preview(listOf(100L, null))

        val estimate = migrationBatchStorageEstimate(preview)

        assertEquals(1, estimate.unknownSourceCount)
        assertTrue(estimate.requiredBytesLowerBound >= 300L)
        assertFalse(estimate.isComplete)
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
                estimatedRequiredBytes = if (sizes.any { it == null }) null else sizes.filterNotNull().sum(),
                estimateIsIncomplete = sizes.any { it == null },
            ),
            inspectedSourceCount = sizes.size,
            requestedSourceCount = sizes.size,
            wasCancelled = false,
        )
    }
}
