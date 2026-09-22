package org.synapseworks.pageharbor.migration

import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.CancellationException
import org.synapseworks.pageharbor.document.importing.sniffSupportedContentType

private const val ESTIMATED_DOCUMENT_OVERHEAD_BYTES = 512L * 1024L

/** Signature-validates and groups a bounded stream of provider references without retaining data. */
class BulkMigrationPlanner(
    private val sourceAccess: MigrationSourceAccess,
) {
    fun preview(
        sources: List<MigrationSource>,
        cancellationSignal: MigrationCancellationSignal = NeverCancelMigration,
        progressListener: MigrationPreviewProgressListener = MigrationPreviewProgressListener { _, _ -> },
    ): MigrationPreview {
        val inspected = mutableListOf<InspectedMigrationSource>()
        val rejected = mutableListOf<RejectedMigrationSource>()
        var inspectedCount = 0
        for (source in sources) {
            if (cancellationSignal.isCancellationRequested()) break
            when (val inspection = inspect(source)) {
                is SourceInspection.Supported -> inspected += InspectedMigrationSource(
                    source,
                    inspection.contentType,
                )
                is SourceInspection.Rejected -> rejected += RejectedMigrationSource(
                    source,
                    inspection.issue,
                )
            }
            inspectedCount += 1
            progressListener.onProgress(inspectedCount, sources.size)
        }
        val documents = groupDocuments(inspected)
        return MigrationPreview(
            documents = documents,
            rejectedSources = rejected,
            storageEstimate = estimateStorage(inspected, documents.size),
            inspectedSourceCount = inspectedCount,
            requestedSourceCount = sources.size,
            wasCancelled = inspectedCount < sources.size,
        )
    }

    private fun inspect(source: MigrationSource): SourceInspection = try {
        sourceAccess.open(source).use { input ->
            when (sniffSupportedContentType(input)) {
                MigrationContentType.PDF.mimeType -> SourceInspection.Supported(MigrationContentType.PDF)
                MigrationContentType.JPEG.mimeType -> SourceInspection.Supported(MigrationContentType.JPEG)
                MigrationContentType.PNG.mimeType -> SourceInspection.Supported(MigrationContentType.PNG)
                MigrationContentType.WEBP.mimeType -> SourceInspection.Supported(MigrationContentType.WEBP)
                else -> SourceInspection.Rejected(MigrationSourceIssue.UNSUPPORTED_CONTENT)
            }
        }
    } catch (_: IOException) {
        SourceInspection.Rejected(MigrationSourceIssue.UNREADABLE)
    } catch (_: SecurityException) {
        SourceInspection.Rejected(MigrationSourceIssue.UNREADABLE)
    } catch (_: IllegalArgumentException) {
        SourceInspection.Rejected(MigrationSourceIssue.UNREADABLE)
    } catch (_: IllegalStateException) {
        SourceInspection.Rejected(MigrationSourceIssue.UNREADABLE)
    }

    private fun groupDocuments(
        inspected: List<InspectedMigrationSource>,
    ): List<MigrationDocumentPlan> {
        val ordered = inspected.sortedWith { left, right ->
            val folderComparison = comparePaths(
                left.source.relativeFolderPath,
                right.source.relativeFolderPath,
            )
            if (folderComparison != 0) {
                folderComparison
            } else {
                val nameComparison = NaturalFilenameComparator.compare(
                    left.source.sortName(),
                    right.source.sortName(),
                )
                if (nameComparison != 0) nameComparison else left.source.id.compareTo(right.source.id)
            }
        }
        val plans = mutableListOf<MigrationDocumentPlan>()
        val groupedImageIds = mutableSetOf<String>()
        val imageSequences = ordered.asSequence()
            .filter { it.contentType.isImage }
            .mapNotNull { inspectedSource ->
                inspectedSource.explicitPageSequence()?.let { match -> match to inspectedSource }
            }
            .groupBy({ it.first.groupKey }, { it.second })
            .values
            .filter(::isHighConfidenceSequence)
            .sortedWith { left, right ->
                compareSources(left.first().source, right.first().source)
            }

        imageSequences.forEach { sequence ->
            val naturallyOrdered = sequence.sortedWith { left, right ->
                NaturalFilenameComparator.compare(left.source.sortName(), right.source.sortName())
            }
            groupedImageIds += naturallyOrdered.map { it.source.id }
            val sequenceName = requireNotNull(naturallyOrdered.first().explicitPageSequence()).baseName
            plans += MigrationDocumentPlan(
                id = stablePlanId("images", naturallyOrdered.map { it.source.id }),
                suggestedTitle = sequenceName.toSuggestedTitle(),
                relativeFolderPath = naturallyOrdered.first().source.relativeFolderPath,
                sources = naturallyOrdered,
                grouping = MigrationDocumentGrouping.HIGH_CONFIDENCE_IMAGE_SEQUENCE,
            )
        }

        ordered.forEach { inspectedSource ->
            if (inspectedSource.source.id in groupedImageIds) return@forEach
            val isPdf = inspectedSource.contentType == MigrationContentType.PDF
            plans += MigrationDocumentPlan(
                id = stablePlanId(if (isPdf) "pdf" else "image", listOf(inspectedSource.source.id)),
                suggestedTitle = inspectedSource.source.baseFilename().toSuggestedTitle(),
                relativeFolderPath = inspectedSource.source.relativeFolderPath,
                sources = listOf(inspectedSource),
                grouping = if (isPdf) {
                    MigrationDocumentGrouping.SINGLE_PDF
                } else {
                    MigrationDocumentGrouping.SINGLE_IMAGE
                },
            )
        }
        return plans.sortedWith { left, right ->
            val folderComparison = comparePaths(left.relativeFolderPath, right.relativeFolderPath)
            if (folderComparison != 0) folderComparison
            else {
                val titleComparison = NaturalFilenameComparator.compare(
                    left.suggestedTitle,
                    right.suggestedTitle,
                )
                if (titleComparison != 0) titleComparison else left.id.compareTo(right.id)
            }
        }
    }

    private fun estimateStorage(
        inspected: List<InspectedMigrationSource>,
        documentCount: Int,
    ): MigrationStorageEstimate {
        var knownSourceBytes = 0L
        var estimatedBytes = documentCount.toLong().saturatingMultiply(
            ESTIMATED_DOCUMENT_OVERHEAD_BYTES,
        )
        var unknown = 0
        inspected.forEach { inspectedSource ->
            val sourceBytes = inspectedSource.source.sizeBytes
            if (sourceBytes == null) {
                unknown += 1
            } else {
                knownSourceBytes = knownSourceBytes.saturatingAdd(sourceBytes)
                val multiplier = if (inspectedSource.contentType == MigrationContentType.PDF) 3L else 2L
                estimatedBytes = estimatedBytes.saturatingAdd(sourceBytes.saturatingMultiply(multiplier))
            }
        }
        return MigrationStorageEstimate(
            knownSourceBytes = knownSourceBytes,
            unknownSizeSourceCount = unknown,
            estimatedRequiredBytes = estimatedBytes.takeIf { unknown == 0 },
            estimateIsIncomplete = unknown != 0,
        )
    }
}

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

private sealed interface SourceInspection {
    data class Supported(val contentType: MigrationContentType) : SourceInspection
    data class Rejected(val issue: MigrationSourceIssue) : SourceInspection
}

private data class PageSequenceGroupKey(
    val relativeFolderPath: List<String>,
    val normalizedBaseName: String,
)

private data class PageSequenceMatch(
    val groupKey: PageSequenceGroupKey,
    val baseName: String,
    val pageNumber: Int,
)

private fun InspectedMigrationSource.explicitPageSequence(): PageSequenceMatch? {
    val baseName = source.baseFilename()
    val match = EXPLICIT_PAGE_SEQUENCE.matchEntire(baseName) ?: return null
    val prefix = match.groupValues[1].trimEnd(' ', '.', '_', '-')
    if (prefix.isBlank()) return null
    val number = match.groupValues[2].toIntOrNull() ?: return null
    return PageSequenceMatch(
        groupKey = PageSequenceGroupKey(
            relativeFolderPath = source.relativeFolderPath,
            normalizedBaseName = prefix.lowercase(Locale.ROOT),
        ),
        baseName = prefix,
        pageNumber = number,
    )
}

private fun isHighConfidenceSequence(sequence: List<InspectedMigrationSource>): Boolean {
    if (sequence.size < 2) return false
    val numbers = sequence.mapNotNull { it.explicitPageSequence()?.pageNumber }.sorted()
    if (numbers.size != sequence.size || numbers.firstOrNull() != 1) return false
    return numbers.withIndex().all { (index, number) -> number == index + 1 }
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

private fun MigrationSource.sortName(): String = displayName?.takeIf(String::isNotBlank) ?: id

private fun MigrationSource.baseFilename(): String {
    val name = displayName?.takeIf(String::isNotBlank) ?: "Imported document"
    val lastDot = name.lastIndexOf('.')
    return if (lastDot > 0) name.substring(0, lastDot) else name
}

private fun String.toSuggestedTitle(): String = asSequence()
    .map { character ->
        if (character.isISOControl() || character == '/' || character == '\\') '_' else character
    }
    .joinToString(separator = "")
    .trim()
    .take(MAX_SUGGESTED_TITLE_LENGTH)
    .ifEmpty { "Imported document" }

private fun comparePaths(left: List<String>, right: List<String>): Int =
    NaturalFilenameComparator.compare(left.joinToString("/"), right.joinToString("/"))

private fun compareSources(left: MigrationSource, right: MigrationSource): Int {
    val folderComparison = comparePaths(left.relativeFolderPath, right.relativeFolderPath)
    return if (folderComparison != 0) folderComparison
    else {
        val nameComparison = NaturalFilenameComparator.compare(left.sortName(), right.sortName())
        if (nameComparison != 0) nameComparison else left.id.compareTo(right.id)
    }
}

private fun stablePlanId(prefix: String, sourceIds: List<String>): String =
    "$prefix:${MessageDigest.getInstance("SHA-256").run {
        update(prefix.encodeToByteArray())
        sourceIds.forEach { sourceId ->
            update(0.toByte())
            update(sourceId.length.toString().encodeToByteArray())
            update(0.toByte())
            update(sourceId.encodeToByteArray())
        }
        digest().joinToString("") { byte -> "%02x".format(Locale.ROOT, byte) }
    }}"

private fun Long.saturatingAdd(other: Long): Long =
    if (this > Long.MAX_VALUE - other) Long.MAX_VALUE else this + other

private fun Long.saturatingMultiply(other: Long): Long = when {
    this == 0L || other == 0L -> 0L
    this > Long.MAX_VALUE / other -> Long.MAX_VALUE
    else -> this * other
}

private val EXPLICIT_PAGE_SEQUENCE = Regex(
    pattern = "^(.*?)[\\s._-]+(?:page|pg)[\\s._-]*(\\d+)$",
    option = RegexOption.IGNORE_CASE,
)

private const val MAX_SUGGESTED_TITLE_LENGTH = 255
