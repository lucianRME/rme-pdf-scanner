package org.synapseworks.pageharbor.migration

import java.io.InputStream
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
) {
    init {
        require(id.isNotBlank())
        require(sizeBytes == null || sizeBytes >= 0L)
        require(modifiedAtMillis == null || modifiedAtMillis >= 0L)
        require(relativeFolderPath.none(::isUnsafePathSegment))
    }
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
)

data class InspectedMigrationSource(
    val source: MigrationSource,
    val contentType: MigrationContentType,
)

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
) {
    init {
        require(id.isNotBlank())
        require(suggestedTitle.isNotBlank())
        require(sources.isNotEmpty())
        require(relativeFolderPath.none(::isUnsafePathSegment))
        require(sources.all { it.source.relativeFolderPath == relativeFolderPath })
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
    /** Planning estimate for working copies. Null when any source size is unknown. */
    val estimatedRequiredBytes: Long?,
    val estimateIsIncomplete: Boolean,
) {
    init {
        require(knownSourceBytes >= 0L)
        require(unknownSizeSourceCount >= 0)
        require(estimatedRequiredBytes == null || estimatedRequiredBytes >= knownSourceBytes)
        require(estimateIsIncomplete == (unknownSizeSourceCount > 0))
    }
}

data class MigrationDuplicatePreview(
    val documentId: String,
    val duplicateKind: DuplicateKind?,
    val analyzedPageCount: Int?,
    val existingDocumentId: String? = null,
    val analysisFailure: MigrationPublicationFailure? = null,
) {
    init {
        require(documentId.isNotBlank())
        require((duplicateKind == null) == (analysisFailure != null))
        require((analyzedPageCount == null) == (analysisFailure != null))
        require(analyzedPageCount == null || analyzedPageCount >= 0)
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
) {
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
}

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
    SOURCE_TOO_LARGE,
    INSUFFICIENT_STORAGE,
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
) {
    init {
        require(requestedDocumentCount >= documents.size)
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
        .filter { it.status == MigrationDocumentStatus.POSSIBLE_DUPLICATE_REVIEW_REQUIRED }
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
)

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
