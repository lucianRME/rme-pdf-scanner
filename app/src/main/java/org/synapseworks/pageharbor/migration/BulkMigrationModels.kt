package org.synapseworks.pageharbor.migration

import java.io.InputStream
import java.text.Normalizer
import java.util.Locale
import org.synapseworks.pageharbor.library.duplicate.DuplicateKind

/** Provider metadata is advisory. Null means the provider did not supply a trustworthy value. */
data class MigrationSource(
    val id: String,
    val displayName: String? = null,
    val relativeFolderPath: List<String> = emptyList(),
    val declaredContentType: String? = null,
    val platformAccessFlags: Int = 0,
    val sizeBytes: Long? = null,
    val modifiedAtMillis: Long? = null,
    val createdAtMillis: Long? = null,
    val origin: MigrationSourceOrigin = MigrationSourceOrigin.UNKNOWN,
    /** Stable only within an import-plan operation; never used to reopen the source. */
    val discoveryIdentity: String = id,
    val discoveryOrder: Int? = null,
    val discoveryOrderEvidence: MigrationDiscoveryOrderEvidence =
        MigrationDiscoveryOrderEvidence.CANONICAL_PROVIDER_INDEPENDENT,
) {
    init {
        require(id.isNotBlank())
        require(discoveryIdentity.isNotBlank())
        require(sizeBytes == null || sizeBytes >= 0L)
        require(modifiedAtMillis == null || modifiedAtMillis >= 0L)
        require(createdAtMillis == null || createdAtMillis >= 0L)
        require(discoveryOrder == null || discoveryOrder >= 0)
        require(relativeFolderPath.none(::isUnsafePathSegment))
    }

    /** Used only for deterministic comparisons; the original display name remains unchanged. */
    val normalizedDisplayName: String?
        get() = displayName?.let(::normalizeMigrationFilename)?.takeIf(String::isNotEmpty)
}

enum class MigrationContentType(val mimeType: String) {
    PDF("application/pdf"),
    JPEG("image/jpeg"),
    PNG("image/png"),
    WEBP("image/webp"),
    ;

    val isImage: Boolean
        get() = this != PDF
}

fun interface MigrationSourceAccess {
    /** Opens a new stream for this source. Callers always close it. */
    fun open(source: MigrationSource): InputStream
}

enum class MigrationSourceIssue {
    UNREADABLE,
    UNSUPPORTED_CONTENT,
}

data class RejectedMigrationSource(
    val source: MigrationSource,
    val issue: MigrationSourceIssue,
    val failureDetail: MigrationSourceFailureDetail = when (issue) {
        MigrationSourceIssue.UNREADABLE -> MigrationSourceFailureDetail.STREAM_UNREADABLE
        MigrationSourceIssue.UNSUPPORTED_CONTENT ->
            MigrationSourceFailureDetail.UNSUPPORTED_SIGNATURE
    },
    val warnings: List<MigrationAnalysisWarning> = emptyList(),
)

data class InspectedMigrationSource(
    val source: MigrationSource,
    val contentType: MigrationContentType,
    val pageCount: Int? = null,
    val contentSha256: String? = null,
    val evidence: Set<MigrationEvidenceSource> = setOf(MigrationEvidenceSource.STREAM_SIGNATURE),
    val warnings: List<MigrationAnalysisWarning> = emptyList(),
) {
    init {
        require(pageCount == null || pageCount > 0)
        require(contentSha256 == null || SHA_256_REGEX.matches(contentSha256))
        require(MigrationEvidenceSource.STREAM_SIGNATURE in evidence)
        if (contentType.isImage) require(pageCount == null || pageCount == 1)
    }
}

enum class MigrationDocumentGrouping {
    SINGLE_PDF,
    SINGLE_IMAGE,
    HIGH_CONFIDENCE_IMAGE_SEQUENCE,
}

/** One atomic library publication unit. A PDF is always its own unit. */
data class MigrationDocumentPlan(
    val id: String,
    val suggestedTitle: String,
    val relativeFolderPath: List<String>,
    val sources: List<InspectedMigrationSource>,
    val grouping: MigrationDocumentGrouping,
    val pages: List<MigrationPlannedPage> = emptyList(),
    val originalAssets: List<MigrationOriginalAssetPlan> = emptyList(),
    val dateCandidates: List<MigrationDateCandidate> = emptyList(),
    val evidence: Set<MigrationEvidenceSource> = emptySet(),
    val warningCodes: List<MigrationAnalysisWarning> = emptyList(),
    val ambiguityIds: List<String> = emptyList(),
) {
    init {
        require(id.isNotBlank())
        require(suggestedTitle.isNotBlank())
        require(sources.isNotEmpty())
        require(relativeFolderPath.none(::isUnsafePathSegment))
        require(sources.all { it.source.relativeFolderPath == relativeFolderPath })
        require(pages.map(MigrationPlannedPage::id).distinct().size == pages.size)
        require(pages.map(MigrationPlannedPage::plannedIndex) == pages.indices.toList())
        require(
            pages.all { page ->
                sources.any { it.source.discoveryIdentity == page.sourceDiscoveryId }
            },
        )
        require(
            originalAssets.all { asset ->
                sources.any { it.source.discoveryIdentity == asset.sourceDiscoveryId }
            },
        )
        when (grouping) {
            MigrationDocumentGrouping.SINGLE_PDF -> require(
                sources.size == 1 && sources.single().contentType == MigrationContentType.PDF,
            )
            MigrationDocumentGrouping.SINGLE_IMAGE -> require(
                sources.size == 1 && sources.single().contentType.isImage,
            )
            MigrationDocumentGrouping.HIGH_CONFIDENCE_IMAGE_SEQUENCE -> require(
                sources.size > 1 && sources.all { it.contentType.isImage },
            )
        }
    }
}

data class MigrationStorageEstimate(
    /** Bytes that are known to exist at the source, without inventing values for unknown sizes. */
    val knownSourceBytes: Long,
    val unknownSizeSourceCount: Int,
) {
    init {
        require(knownSourceBytes >= 0L)
        require(unknownSizeSourceCount >= 0)
    }

    val estimateIsIncomplete: Boolean
        get() = unknownSizeSourceCount > 0
}

data class MigrationDuplicatePreview(
    val documentId: String,
    val duplicateKind: DuplicateKind?,
    val analyzedPageCount: Int?,
    val existingDocumentId: String? = null,
    val analysisFailure: MigrationPublicationFailure? = null,
    /** Exact page bytes observed while the read-only preview staged this document. */
    val measuredPageBytes: Long? = null,
    /** Exact retained original-asset bytes observed during preview; zero for image imports. */
    val measuredOriginalAssetBytes: Long? = null,
) {
    init {
        require(documentId.isNotBlank())
        require((duplicateKind == null) == (analysisFailure != null))
        require((analyzedPageCount == null) == (analysisFailure != null))
        require(analyzedPageCount == null || analyzedPageCount >= 0)
        require(measuredPageBytes == null || measuredPageBytes >= 0L)
        require(measuredOriginalAssetBytes == null || measuredOriginalAssetBytes >= 0L)
        require((measuredPageBytes == null) == (measuredOriginalAssetBytes == null))
        require(analysisFailure == null || measuredPageBytes == null)
        require(
            existingDocumentId == null ||
                duplicateKind in setOf(DuplicateKind.EXACT, DuplicateKind.POSSIBLE),
        )
    }
}

data class MigrationPreview(
    val documents: List<MigrationDocumentPlan>,
    val rejectedSources: List<RejectedMigrationSource>,
    val storageEstimate: MigrationStorageEstimate,
    val inspectedSourceCount: Int,
    val requestedSourceCount: Int,
    val wasCancelled: Boolean,
    /** Populated by the Android/Room coordinator's read-only fingerprinting pass. */
    val duplicateDocuments: List<MigrationDuplicatePreview> = emptyList(),
    val duplicateAnalysisComplete: Boolean = false,
    val requestedSources: List<MigrationSource> = emptyList(),
    val plannedFolders: List<MigrationFolderCandidate> = emptyList(),
    val planWarnings: List<MigrationPlanWarning> = emptyList(),
    val ambiguities: List<MigrationPlanAmbiguity> = emptyList(),
    val duplicateCandidates: List<MigrationPlanDuplicateCandidate> = emptyList(),
) {
    init {
        require(requestedSourceCount >= inspectedSourceCount)
        require(requestedSources.isEmpty() || requestedSources.size == requestedSourceCount)
        require(
            requestedSources.isEmpty() ||
                requestedSources.map(MigrationSource::discoveryIdentity).distinct().size ==
                requestedSources.size,
        )
    }

    val exactDuplicateCount: Int
        get() = duplicateDocuments.count { it.duplicateKind == DuplicateKind.EXACT }

    val possibleDuplicateCount: Int
        get() = duplicateDocuments.count { it.duplicateKind == DuplicateKind.POSSIBLE }

    val differentDocumentCount: Int
        get() = duplicateDocuments.count { it.duplicateKind == DuplicateKind.DIFFERENT }

    /** Null until every planned document has been staged and counted successfully. */
    val pageCount: Long?
        get() {
            if (!duplicateAnalysisComplete || duplicateDocuments.size != documents.size) return null
            return duplicateDocuments.fold(0L) { total, document ->
                val pages = document.analyzedPageCount ?: return null
                if (Long.MAX_VALUE - total < pages) Long.MAX_VALUE else total + pages
            }
        }

    val unclassifiedDocumentIds: Set<String>
        get() {
            val classified = duplicateDocuments.asSequence()
                .filter { it.duplicateKind != null }
                .map(MigrationDuplicatePreview::documentId)
                .toSet()
            return documents.asSequence().map(MigrationDocumentPlan::id)
                .filterNot(classified::contains)
                .toSet()
        }

    /** Complete M1 accounting. No requested artifact is omitted, including cancellation tails. */
    val sourceAccounting: List<MigrationArtifactAccounting>
        get() {
            if (requestedSources.isEmpty()) return emptyList()
            val planIdsBySource = buildMap<String, MutableList<String>> {
                documents.forEach { document ->
                    document.sources.forEach { inspected ->
                        getOrPut(inspected.source.discoveryIdentity, ::mutableListOf) += document.id
                    }
                }
            }
            val rejectedBySource = rejectedSources.associateBy {
                it.source.discoveryIdentity
            }
            val ambiguitySourceIds = ambiguities.flatMapTo(linkedSetOf()) {
                it.sourceDiscoveryIds
            }
            val plannedDuplicateStrength = buildMap<String, MigrationPlanDuplicateStrength> {
                duplicateCandidates.forEach { candidate ->
                    candidate.sourceDiscoveryIds.forEach { sourceId ->
                        put(sourceId, candidate.strength)
                    }
                }
                duplicateDocuments.forEach { duplicate ->
                    val strength = duplicate.duplicateKind?.toPlanDuplicateStrengthOrNull()
                        ?: return@forEach
                    documents.firstOrNull { it.id == duplicate.documentId }
                        ?.sources
                        .orEmpty()
                        .forEach { put(it.source.discoveryIdentity, strength) }
                }
            }
            val sourceWarnings = buildMap<String, MutableList<MigrationAnalysisWarning>> {
                documents.flatMap(MigrationDocumentPlan::sources).forEach { inspected ->
                    getOrPut(inspected.source.discoveryIdentity, ::mutableListOf) += inspected.warnings
                }
                rejectedSources.forEach { rejected ->
                    getOrPut(rejected.source.discoveryIdentity, ::mutableListOf) += rejected.warnings
                }
                planWarnings.forEach { warning ->
                    warning.affectedSourceDiscoveryIds.forEach { sourceId ->
                        getOrPut(sourceId, ::mutableListOf) += warning.warning
                    }
                }
            }
            return requestedSources.map { source ->
                val sourceId = source.discoveryIdentity
                val rejected = rejectedBySource[sourceId]
                val duplicateStrength = plannedDuplicateStrength[sourceId]
                val disposition = when {
                    rejected?.issue == MigrationSourceIssue.UNSUPPORTED_CONTENT ->
                        MigrationArtifactDisposition.UNSUPPORTED
                    rejected != null -> MigrationArtifactDisposition.ANALYSIS_FAILED
                    duplicateStrength != null -> MigrationArtifactDisposition.DUPLICATE_CANDIDATE
                    sourceId in ambiguitySourceIds ->
                        MigrationArtifactDisposition.AMBIGUOUS_REQUIRES_REVIEW
                    sourceId in planIdsBySource -> MigrationArtifactDisposition.PLANNED_FOR_IMPORT
                    else -> MigrationArtifactDisposition.CANCELLED_UNPROCESSED
                }
                MigrationArtifactAccounting(
                    sourceDiscoveryId = sourceId,
                    disposition = disposition,
                    plannedDocumentIds = planIdsBySource[sourceId].orEmpty(),
                    duplicateStrength = duplicateStrength,
                    warnings = sourceWarnings[sourceId].orEmpty().distinct(),
                )
            }
        }

    val sourceSummary: MigrationSourceSummary
        get() {
            val accounting = sourceAccounting
            return MigrationSourceSummary(
                requestedCount = requestedSourceCount,
                inspectedCount = inspectedSourceCount,
                plannedCount = accounting.count {
                    it.disposition == MigrationArtifactDisposition.PLANNED_FOR_IMPORT
                },
                unsupportedCount = accounting.count {
                    it.disposition == MigrationArtifactDisposition.UNSUPPORTED
                },
                analysisFailedCount = accounting.count {
                    it.disposition == MigrationArtifactDisposition.ANALYSIS_FAILED
                },
                reviewRequiredCount = accounting.count {
                    it.disposition == MigrationArtifactDisposition.AMBIGUOUS_REQUIRES_REVIEW
                },
                duplicateCandidateCount = accounting.count {
                    it.disposition == MigrationArtifactDisposition.DUPLICATE_CANDIDATE
                },
                cancelledUnprocessedCount = accounting.count {
                    it.disposition == MigrationArtifactDisposition.CANCELLED_UNPROCESSED
                },
            )
        }
}

/** The existing preview is the deterministic M1 import plan; this alias avoids a second model. */
typealias MigrationImportPlan = MigrationPreview

fun interface MigrationPreviewProgressListener {
    fun onProgress(inspectedSources: Int, totalSources: Int)
}

fun interface MigrationCancellationSignal {
    fun isCancellationRequested(): Boolean
}

object NeverCancelMigration : MigrationCancellationSignal {
    override fun isCancellationRequested(): Boolean = false
}

enum class MigrationPublicationFailure {
    SOURCE_UNAVAILABLE,
    SOURCE_CHANGED,
    SOURCE_TOO_LARGE,
    INSUFFICIENT_STORAGE,
    VALIDATION_FAILED,
    STAGING_FAILED,
    PUBLICATION_FAILED,
    WRITE_FAILED,
    INVALID_DOCUMENT,
    INTERRUPTED,
}

sealed interface MigrationPublicationResult {
    data object Published : MigrationPublicationResult
    data object DuplicateSkipped : MigrationPublicationResult
    data class PublishedPossibleDuplicate(
        val existingDocumentId: String,
    ) : MigrationPublicationResult

    data class ExactDuplicateSkipped(
        val existingDocumentId: String,
    ) : MigrationPublicationResult

    /** POSSIBLE is advisory and cannot be skipped or imported without a caller decision. */
    data class PossibleDuplicateRequiresReview(
        val existingDocumentId: String,
    ) : MigrationPublicationResult

    data object Cancelled : MigrationPublicationResult
    data class Failed(
        val reason: MigrationPublicationFailure,
        val retryable: Boolean = true,
    ) : MigrationPublicationResult
}

data class MigrationPublicationContext(
    val cancellationSignal: MigrationCancellationSignal,
    val onBytesCopied: (copiedBytes: Long, totalBytes: Long?) -> Unit,
)

/**
 * Implementations must publish one document atomically and stream its sources. They must not use
 * the interactive acquisition/session coordinator, whose page limit and lifetime are UI-scoped.
 */
fun interface MigrationDocumentPublisher {
    suspend fun publish(
        document: MigrationDocumentPlan,
        context: MigrationPublicationContext,
    ): MigrationPublicationResult
}

enum class MigrationDocumentStatus {
    PUBLISHED,
    DUPLICATE_SKIPPED,
    POSSIBLE_DUPLICATE_REVIEW_REQUIRED,
    EXCLUDED,
    REVIEW_UNRESOLVED,
    FAILED,
    CANCELLED,
}

data class MigrationDocumentReport(
    val documentId: String,
    val suggestedTitle: String,
    val status: MigrationDocumentStatus,
    val failure: MigrationPublicationFailure? = null,
    val retryable: Boolean = false,
    val duplicateKind: DuplicateKind? = null,
    val duplicateDocumentId: String? = null,
)

data class MigrationBatchReport(
    val documents: List<MigrationDocumentReport>,
    val rejectedSources: List<RejectedMigrationSource>,
    val wasCancelled: Boolean,
    val requestedDocumentCount: Int,
    val operationId: String? = null,
    val durableCompletion: MigrationDurableCompletionReport? = null,
) {
    init {
        require(requestedDocumentCount >= documents.size)
        require(operationId == null || operationId.isNotBlank())
        require(durableCompletion == null || durableCompletion.operationId == operationId)
    }

    val publishedCount: Int get() = documents.count { it.status == MigrationDocumentStatus.PUBLISHED }
    val duplicateCount: Int get() = documents.count {
        it.status == MigrationDocumentStatus.DUPLICATE_SKIPPED
    }
    val failedCount: Int get() = documents.count { it.status == MigrationDocumentStatus.FAILED }
    val retryableDocumentIds: Set<String> get() = documents.asSequence()
        .filter { it.status == MigrationDocumentStatus.FAILED && it.retryable }
        .map { it.documentId }
        .toSet()
    val reviewRequiredDocumentIds: Set<String> get() = documents.asSequence()
        .filter {
            it.status == MigrationDocumentStatus.POSSIBLE_DUPLICATE_REVIEW_REQUIRED ||
                it.status == MigrationDocumentStatus.REVIEW_UNRESOLVED
        }
        .map { it.documentId }
        .toSet()
    val unprocessedDocumentCount: Int get() =
        (requestedDocumentCount - documents.size).coerceAtLeast(0)
}

data class MigrationBatchProgress(
    val completedDocuments: Int,
    val totalDocuments: Int,
    val currentDocumentId: String?,
    val currentDocumentBytesCopied: Long = 0L,
    val currentDocumentTotalBytes: Long? = null,
    val phase: MigrationBatchProgressPhase = MigrationBatchProgressPhase.MIGRATION,
    val searchProcessedPages: Int = 0,
    val searchTotalPages: Int = 0,
    val embeddedTextPages: Int = 0,
    val ocrPages: Int = 0,
    val failedSearchPages: Int = 0,
)

enum class MigrationBatchProgressPhase {
    MIGRATION,
    SEARCH_PREPARATION,
}

fun interface MigrationBatchProgressListener {
    fun onProgress(progress: MigrationBatchProgress)
}

/**
 * Duplicate decisions are keyed by stable preview plan IDs. Exact matches are skipped by default;
 * possible matches pause for review. Adding an ID is the caller's explicit import-anyway choice.
 */
data class MigrationDuplicateDecisions(
    val importDespiteDuplicateDocumentIds: Set<String> = emptySet(),
) {
    fun shouldImport(documentId: String): Boolean =
        documentId in importDespiteDuplicateDocumentIds
}

internal fun isUnsafePathSegment(segment: String): Boolean =
    segment.isBlank() || segment == "." || segment == ".." || '/' in segment || '\\' in segment

private val SHA_256_REGEX = Regex("^[0-9a-f]{64}$")

internal fun normalizeMigrationFilename(value: String): String = Normalizer
    .normalize(value.trim(), Normalizer.Form.NFC)
    .lowercase(Locale.ROOT)
