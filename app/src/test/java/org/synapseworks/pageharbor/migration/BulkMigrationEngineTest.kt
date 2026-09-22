package org.synapseworks.pageharbor.migration

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BulkMigrationEngineTest {
    @Test
    fun thousandDocumentsPublishSequentiallyWithProgress() = runBlocking {
        val preview = preview(1_000)
        var activePublishers = 0
        var peakPublishers = 0
        val published = mutableListOf<String>()
        val progress = mutableListOf<MigrationBatchProgress>()
        val engine = BulkMigrationEngine(
            MigrationDocumentPublisher { document, context ->
                activePublishers += 1
                peakPublishers = maxOf(peakPublishers, activePublishers)
                context.onBytesCopied(5L, 10L)
                published += document.id
                activePublishers -= 1
                MigrationPublicationResult.Published
            },
        )

        val report = engine.execute(preview) { progress += it }

        assertEquals(1_000, report.publishedCount)
        assertEquals(1_000, published.distinct().size)
        assertEquals(1, peakPublishers)
        assertEquals(1_000, progress.last().completedDocuments)
        assertFalse(report.wasCancelled)
    }

    @Test
    fun cancellationReturnsCompletedPrefixWithoutStartingMoreDocuments() = runBlocking {
        val preview = preview(20)
        var published = 0
        val cancellation = MigrationCancellationSignal { published >= 4 }
        val engine = BulkMigrationEngine(
            MigrationDocumentPublisher { _, _ ->
                published += 1
                MigrationPublicationResult.Published
            },
        )

        val report = engine.execute(preview, cancellation)

        assertEquals(4, report.publishedCount)
        assertEquals(4, report.documents.size)
        assertEquals(16, report.unprocessedDocumentCount)
        assertTrue(report.wasCancelled)
    }

    @Test
    fun retryRunsOnlyRetryableFailuresAndKeepsAUserAccessibleReport() = runBlocking {
        val preview = preview(4)
        val attempts = mutableMapOf<String, Int>()
        val engine = BulkMigrationEngine(
            MigrationDocumentPublisher { document, _ ->
                attempts[document.id] = attempts.getOrDefault(document.id, 0) + 1
                when {
                    document.suggestedTitle == "Document 1" && attempts.getValue(document.id) == 1 ->
                        MigrationPublicationResult.Failed(MigrationPublicationFailure.WRITE_FAILED)
                    document.suggestedTitle == "Document 2" ->
                        MigrationPublicationResult.Failed(
                            MigrationPublicationFailure.INVALID_DOCUMENT,
                            retryable = false,
                        )
                    document.suggestedTitle == "Document 3" ->
                        MigrationPublicationResult.DuplicateSkipped
                    else -> MigrationPublicationResult.Published
                }
            },
        )

        val first = engine.execute(preview)
        val retried = engine.retryFailures(preview, first)

        assertEquals(2, first.failedCount)
        assertEquals(1, first.duplicateCount)
        assertEquals(1, retried.documents.size)
        assertEquals(MigrationDocumentStatus.PUBLISHED, retried.documents.single().status)
        assertEquals(2, attempts.getValue(first.documents[1].documentId))
        assertEquals(1, attempts.getValue(first.documents[2].documentId))
    }

    @Test
    fun retryIncompleteResumesCancelledAndUnprocessedDocuments() = runBlocking {
        val preview = preview(6)
        var firstRunCount = 0
        val firstEngine = BulkMigrationEngine(
            MigrationDocumentPublisher { _, _ ->
                firstRunCount += 1
                if (firstRunCount == 3) MigrationPublicationResult.Cancelled
                else MigrationPublicationResult.Published
            },
        )
        val first = firstEngine.execute(preview)
        val resumedIds = mutableListOf<String>()
        val resumeEngine = BulkMigrationEngine(
            MigrationDocumentPublisher { document, _ ->
                resumedIds += document.id
                MigrationPublicationResult.Published
            },
        )

        val resumed = resumeEngine.retryIncomplete(preview, first)

        assertEquals(listOf("document-2", "document-3", "document-4", "document-5"), resumedIds)
        assertEquals(4, resumed.publishedCount)
    }

    @Test
    fun possibleDuplicateRequiresReviewAndCanBeRetriedAfterExplicitApproval() = runBlocking {
        val preview = preview(2)
        val first = BulkMigrationEngine(
            MigrationDocumentPublisher { document, _ ->
                if (document.id == "document-0") {
                    MigrationPublicationResult.PossibleDuplicateRequiresReview("existing")
                } else {
                    MigrationPublicationResult.Published
                }
            },
        ).execute(preview)

        assertEquals(setOf("document-0"), first.reviewRequiredDocumentIds)
        assertEquals(
            "existing",
            first.documents.first().duplicateDocumentId,
        )

        val resumedIds = mutableListOf<String>()
        val resumed = BulkMigrationEngine(
            MigrationDocumentPublisher { document, _ ->
                resumedIds += document.id
                MigrationPublicationResult.PublishedPossibleDuplicate("existing")
            },
        ).retryIncomplete(preview, first)

        assertEquals(listOf("document-0"), resumedIds)
        assertEquals(1, resumed.publishedCount)
    }

    private fun preview(count: Int): MigrationPreview {
        val documents = List(count) { index ->
            val source = MigrationSource(
                id = "source-$index",
                displayName = "Document $index.pdf",
                sizeBytes = 100L,
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
                knownSourceBytes = count * 100L,
                unknownSizeSourceCount = 0,
                estimatedRequiredBytes = count * 1000L,
                estimateIsIncomplete = false,
            ),
            inspectedSourceCount = count,
            requestedSourceCount = count,
            wasCancelled = false,
        )
    }
}
