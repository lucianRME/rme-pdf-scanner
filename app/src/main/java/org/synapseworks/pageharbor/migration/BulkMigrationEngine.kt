package org.synapseworks.pageharbor.migration

import java.io.IOException
import kotlinx.coroutines.CancellationException

/** Sequential execution keeps open streams and rendered working sets bounded to one document. */
class BulkMigrationEngine(
    private val publisher: MigrationDocumentPublisher,
) {
    suspend fun execute(
        preview: MigrationPreview,
        cancellationSignal: MigrationCancellationSignal = NeverCancelMigration,
        progressListener: MigrationBatchProgressListener = MigrationBatchProgressListener { },
    ): MigrationBatchReport = executeDocuments(
        documents = preview.documents,
        rejectedSources = preview.rejectedSources,
        cancellationSignal = cancellationSignal,
        progressListener = progressListener,
    )

    suspend fun retryFailures(
        preview: MigrationPreview,
        previousReport: MigrationBatchReport,
        cancellationSignal: MigrationCancellationSignal = NeverCancelMigration,
        progressListener: MigrationBatchProgressListener = MigrationBatchProgressListener { },
    ): MigrationBatchReport {
        val retryIds = previousReport.retryableDocumentIds
        return executeDocuments(
            documents = preview.documents.filter { it.id in retryIds },
            rejectedSources = emptyList(),
            cancellationSignal = cancellationSignal,
            progressListener = progressListener,
        )
    }

    /** Retries retryable failures and resumes documents left cancelled or not yet attempted. */
    suspend fun retryIncomplete(
        preview: MigrationPreview,
        previousReport: MigrationBatchReport,
        cancellationSignal: MigrationCancellationSignal = NeverCancelMigration,
        progressListener: MigrationBatchProgressListener = MigrationBatchProgressListener { },
    ): MigrationBatchReport {
        val completedIds = previousReport.documents.asSequence()
            .filter {
                it.status == MigrationDocumentStatus.PUBLISHED ||
                    it.status == MigrationDocumentStatus.DUPLICATE_SKIPPED ||
                    (it.status == MigrationDocumentStatus.FAILED && !it.retryable)
            }
            .map(MigrationDocumentReport::documentId)
            .toSet()
        return executeDocuments(
            documents = preview.documents.filterNot { it.id in completedIds },
            rejectedSources = emptyList(),
            cancellationSignal = cancellationSignal,
            progressListener = progressListener,
        )
    }

    private suspend fun executeDocuments(
        documents: List<MigrationDocumentPlan>,
        rejectedSources: List<RejectedMigrationSource>,
        cancellationSignal: MigrationCancellationSignal,
        progressListener: MigrationBatchProgressListener,
    ): MigrationBatchReport {
        val reports = mutableListOf<MigrationDocumentReport>()
        var cancelled = false
        progressListener.onProgress(MigrationBatchProgress(0, documents.size, null))
        for (document in documents) {
            if (cancellationSignal.isCancellationRequested()) {
                cancelled = true
                break
            }
            progressListener.onProgress(
                MigrationBatchProgress(reports.size, documents.size, document.id),
            )
            val context = MigrationPublicationContext(
                cancellationSignal = cancellationSignal,
                onBytesCopied = { copied, total ->
                    progressListener.onProgress(
                        MigrationBatchProgress(
                            completedDocuments = reports.size,
                            totalDocuments = documents.size,
                            currentDocumentId = document.id,
                            currentDocumentBytesCopied = copied.coerceAtLeast(0L),
                            currentDocumentTotalBytes = total?.coerceAtLeast(0L),
                        ),
                    )
                },
            )
            val result = try {
                publisher.publish(document, context)
            } catch (cancelledException: CancellationException) {
                throw cancelledException
            } catch (_: IOException) {
                MigrationPublicationResult.Failed(MigrationPublicationFailure.SOURCE_UNAVAILABLE)
            } catch (_: SecurityException) {
                MigrationPublicationResult.Failed(MigrationPublicationFailure.SOURCE_UNAVAILABLE)
            } catch (_: IllegalArgumentException) {
                MigrationPublicationResult.Failed(
                    MigrationPublicationFailure.INVALID_DOCUMENT,
                    retryable = false,
                )
            } catch (_: IllegalStateException) {
                MigrationPublicationResult.Failed(MigrationPublicationFailure.WRITE_FAILED)
            }
            when (result) {
                MigrationPublicationResult.Published -> reports += document.report(
                    MigrationDocumentStatus.PUBLISHED,
                )
                MigrationPublicationResult.DuplicateSkipped -> reports += document.report(
                    MigrationDocumentStatus.DUPLICATE_SKIPPED,
                )
                is MigrationPublicationResult.PublishedPossibleDuplicate -> reports +=
                    document.report(
                        status = MigrationDocumentStatus.PUBLISHED,
                        duplicateKind = org.synapseworks.pageharbor.library.duplicate.DuplicateKind.POSSIBLE,
                        duplicateDocumentId = result.existingDocumentId,
                    )
                is MigrationPublicationResult.ExactDuplicateSkipped -> reports +=
                    document.report(
                        status = MigrationDocumentStatus.DUPLICATE_SKIPPED,
                        duplicateKind = org.synapseworks.pageharbor.library.duplicate.DuplicateKind.EXACT,
                        duplicateDocumentId = result.existingDocumentId,
                    )
                is MigrationPublicationResult.PossibleDuplicateRequiresReview -> reports +=
                    document.report(
                        status = MigrationDocumentStatus.POSSIBLE_DUPLICATE_REVIEW_REQUIRED,
                        duplicateKind = org.synapseworks.pageharbor.library.duplicate.DuplicateKind.POSSIBLE,
                        duplicateDocumentId = result.existingDocumentId,
                    )
                MigrationPublicationResult.Cancelled -> {
                    reports += document.report(MigrationDocumentStatus.CANCELLED)
                    cancelled = true
                }
                is MigrationPublicationResult.Failed -> reports += document.report(
                    status = MigrationDocumentStatus.FAILED,
                    failure = result.reason,
                    retryable = result.retryable,
                )
            }
            progressListener.onProgress(
                MigrationBatchProgress(reports.size, documents.size, null),
            )
            if (cancelled) break
        }
        return MigrationBatchReport(
            documents = reports,
            rejectedSources = rejectedSources,
            wasCancelled = cancelled ||
                (reports.size < documents.size && cancellationSignal.isCancellationRequested()),
            requestedDocumentCount = documents.size,
        )
    }
}

private fun MigrationDocumentPlan.report(
    status: MigrationDocumentStatus,
    failure: MigrationPublicationFailure? = null,
    retryable: Boolean = false,
    duplicateKind: org.synapseworks.pageharbor.library.duplicate.DuplicateKind? = null,
    duplicateDocumentId: String? = null,
): MigrationDocumentReport = MigrationDocumentReport(
    documentId = id,
    suggestedTitle = suggestedTitle,
    status = status,
    failure = failure,
    retryable = retryable,
    duplicateKind = duplicateKind,
    duplicateDocumentId = duplicateDocumentId,
)
