package org.synapseworks.pageharbor.migration

import org.synapseworks.pageharbor.library.duplicate.DuplicateKind

/** Where a source reference entered the user-mediated migration flow. */
enum class MigrationSourceOrigin {
    FILE_SELECTION,
    DOCUMENT_TREE,
    INBOUND_SHARE,
    UNKNOWN,
}

/** Evidence attached to a factual plan field; absence is represented by a missing candidate. */
enum class MigrationEvidenceSource {
    USER_SELECTED_SOURCE,
    INBOUND_SHARE_ORDER,
    SAF_TREE_PATH,
    PROVIDER_METADATA,
    STREAM_SIGNATURE,
    PDF_PAGE_TREE,
    EXPLICIT_PAGE_FILENAME,
    FILENAME_CANDIDATE,
    CRYPTOGRAPHIC_HASH,
}

enum class MigrationDiscoveryOrderEvidence {
    CANONICAL_PROVIDER_INDEPENDENT,
    SOURCE_DECLARED,
}

enum class MigrationAnalysisWarning {
    DECLARED_MIME_MISMATCH,
    PDF_PAGE_COUNT_UNAVAILABLE,
    FILENAME_DATE_CANDIDATE_ONLY,
    AMBIGUOUS_FILENAME_DATE_IGNORED,
    POSSIBLE_IMAGE_SEQUENCE,
    DUPLICATE_SOURCE_REFERENCE,
    DISCOVERY_LIMIT_REACHED,
    TREE_TRUNCATED,
    TREE_UNREADABLE_DIRECTORY,
    TREE_ENTRY_SKIPPED,
}

enum class MigrationSourceFailureDetail {
    STREAM_UNREADABLE,
    ZERO_BYTE_SOURCE,
    UNSUPPORTED_SIGNATURE,
    MALFORMED_PDF,
    PDF_PAGE_LIMIT_EXCEEDED,
    SOURCE_LIMIT_EXCEEDED,
}

/** Optional Android/source-specific PDF validation without rasterizing document pages. */
fun interface MigrationPdfInspector {
    fun inspectPdf(source: MigrationSource): MigrationPdfInspection
}

sealed interface MigrationPdfInspection {
    data class Readable(val pageCount: Int) : MigrationPdfInspection {
        init {
            require(pageCount > 0)
        }
    }

    data object Unavailable : MigrationPdfInspection
    data object Malformed : MigrationPdfInspection
    data object PageLimitExceeded : MigrationPdfInspection
}

data class MigrationPlannedPage(
    val id: String,
    val sourceDiscoveryId: String,
    /** Zero-based page index within the source. Images always use zero. */
    val sourcePageIndex: Int,
    /** Zero-based order within the proposed RME document. */
    val plannedIndex: Int,
    /** One-based source page number only when the evidence explicitly supplies one. */
    val factualPageNumber: Int?,
    val orderEvidence: MigrationEvidenceSource,
) {
    init {
        require(id.isNotBlank())
        require(sourceDiscoveryId.isNotBlank())
        require(sourcePageIndex >= 0)
        require(plannedIndex >= 0)
        require(factualPageNumber == null || factualPageNumber > 0)
    }
}

enum class MigrationOriginalAssetRole {
    ORIGINAL_PDF,
    ORIGINAL_IMAGE,
}

data class MigrationOriginalAssetPlan(
    val sourceDiscoveryId: String,
    val role: MigrationOriginalAssetRole,
    val contentType: MigrationContentType,
    val preserveUnchanged: Boolean = true,
) {
    init {
        require(sourceDiscoveryId.isNotBlank())
        require(
            (role == MigrationOriginalAssetRole.ORIGINAL_PDF) ==
                (contentType == MigrationContentType.PDF),
        )
    }
}

enum class MigrationDateCandidateKind {
    SOURCE_DOCUMENT_DATE,
    ORIGINAL_FILE_CREATED,
    ORIGINAL_FILE_MODIFIED,
}

/** Date-only and instant values remain distinct so timezone conversion cannot invent a date. */
data class MigrationDateCandidate(
    val kind: MigrationDateCandidateKind,
    val epochDay: Long? = null,
    val instantMillis: Long? = null,
    val evidence: MigrationEvidenceSource,
    val sourceDiscoveryId: String,
    val authoritativeForKind: Boolean,
) {
    init {
        require((epochDay == null) != (instantMillis == null))
        require(instantMillis == null || instantMillis >= 0L)
        require(sourceDiscoveryId.isNotBlank())
        if (evidence == MigrationEvidenceSource.FILENAME_CANDIDATE) {
            require(!authoritativeForKind)
            require(kind == MigrationDateCandidateKind.SOURCE_DOCUMENT_DATE)
        }
    }
}

data class MigrationFolderCandidate(
    val relativePath: List<String>,
    val evidence: MigrationEvidenceSource,
    val sourceDiscoveryIds: List<String>,
) {
    init {
        require(relativePath.isNotEmpty())
        require(relativePath.none(::isUnsafePathSegment))
        require(sourceDiscoveryIds.isNotEmpty())
        require(sourceDiscoveryIds.none(String::isBlank))
    }
}

enum class MigrationAmbiguityKind {
    POSSIBLE_IMAGE_SEQUENCE,
    AMBIGUOUS_FILENAME_DATE,
}

data class MigrationPlanAmbiguity(
    val id: String,
    val kind: MigrationAmbiguityKind,
    val sourceDiscoveryIds: List<String>,
    val plannedDocumentIds: List<String>,
) {
    init {
        require(id.isNotBlank())
        require(sourceDiscoveryIds.isNotEmpty())
        require(sourceDiscoveryIds.none(String::isBlank))
    }
}

data class MigrationPlanWarning(
    val warning: MigrationAnalysisWarning,
    val affectedSourceDiscoveryIds: List<String> = emptyList(),
    val occurrenceCount: Int = 1,
) {
    init {
        require(affectedSourceDiscoveryIds.none(String::isBlank))
        require(occurrenceCount > 0)
    }
}

enum class MigrationPlanDuplicateStrength {
    EXACT,
    POSSIBLE,
}

data class MigrationPlanDuplicateCandidate(
    val sourceDiscoveryIds: List<String>,
    val strength: MigrationPlanDuplicateStrength,
    val evidence: MigrationEvidenceSource,
) {
    init {
        require(sourceDiscoveryIds.size >= 2)
        require(sourceDiscoveryIds.distinct().size == sourceDiscoveryIds.size)
        if (strength == MigrationPlanDuplicateStrength.EXACT) {
            require(evidence == MigrationEvidenceSource.CRYPTOGRAPHIC_HASH)
        }
    }
}

enum class MigrationArtifactDisposition {
    PLANNED_FOR_IMPORT,
    UNSUPPORTED,
    ANALYSIS_FAILED,
    AMBIGUOUS_REQUIRES_REVIEW,
    DUPLICATE_CANDIDATE,
    CANCELLED_UNPROCESSED,
}

data class MigrationArtifactAccounting(
    val sourceDiscoveryId: String,
    val disposition: MigrationArtifactDisposition,
    val plannedDocumentIds: List<String> = emptyList(),
    val duplicateStrength: MigrationPlanDuplicateStrength? = null,
    val warnings: List<MigrationAnalysisWarning> = emptyList(),
) {
    init {
        require(sourceDiscoveryId.isNotBlank())
        require(plannedDocumentIds.none(String::isBlank))
        require(
            (disposition == MigrationArtifactDisposition.DUPLICATE_CANDIDATE) ==
                (duplicateStrength != null),
        )
    }
}

data class MigrationSourceSummary(
    val requestedCount: Int,
    val inspectedCount: Int,
    val plannedCount: Int,
    val unsupportedCount: Int,
    val analysisFailedCount: Int,
    val reviewRequiredCount: Int,
    val duplicateCandidateCount: Int,
    val cancelledUnprocessedCount: Int,
) {
    init {
        require(requestedCount >= 0)
        require(inspectedCount in 0..requestedCount)
        require(
            listOf(
                plannedCount,
                unsupportedCount,
                analysisFailedCount,
                reviewRequiredCount,
                duplicateCandidateCount,
                cancelledUnprocessedCount,
            ).all { it >= 0 },
        )
        require(
            requestedCount == plannedCount + unsupportedCount + analysisFailedCount +
                reviewRequiredCount + duplicateCandidateCount + cancelledUnprocessedCount,
        )
    }
}

internal fun DuplicateKind.toPlanDuplicateStrengthOrNull(): MigrationPlanDuplicateStrength? = when (this) {
    DuplicateKind.EXACT -> MigrationPlanDuplicateStrength.EXACT
    DuplicateKind.POSSIBLE -> MigrationPlanDuplicateStrength.POSSIBLE
    DuplicateKind.DIFFERENT -> null
}
