package org.synapseworks.pageharbor.migration

import org.synapseworks.pageharbor.library.duplicate.DuplicateKind

enum class MigrationPlannedDocumentDecision {
    IMPORT,
    EXCLUDE,
}

enum class MigrationDuplicateExecutionDecision {
    IMPORT_ANYWAY,
    EXCLUDE,
    DEFER,
    SKIP_EXACT,
}

enum class MigrationAmbiguityExecutionDecision {
    ACCEPT_PROPOSED,
    SPLIT_INDEPENDENT,
    EXCLUDE,
    DEFER,
}

/**
 * Immutable decisions recorded before execution. Defaults preserve the existing safe policy:
 * planned documents import, exact duplicates skip, and weak/possible evidence waits for review.
 */
data class MigrationExecutionDecisions(
    val documentDecisions: Map<String, MigrationPlannedDocumentDecision> = emptyMap(),
    val duplicateDecisions: Map<String, MigrationDuplicateExecutionDecision> = emptyMap(),
    val ambiguityDecisions: Map<String, MigrationAmbiguityExecutionDecision> = emptyMap(),
) {
    init {
        require(documentDecisions.keys.none(String::isBlank))
        require(duplicateDecisions.keys.none(String::isBlank))
        require(ambiguityDecisions.keys.none(String::isBlank))
    }

    fun documentDecision(documentId: String): MigrationPlannedDocumentDecision =
        documentDecisions[documentId] ?: MigrationPlannedDocumentDecision.IMPORT

    fun duplicateDecision(
        documentId: String,
        duplicateKind: DuplicateKind,
    ): MigrationDuplicateExecutionDecision = duplicateDecisions[documentId] ?: when (duplicateKind) {
        DuplicateKind.EXACT -> MigrationDuplicateExecutionDecision.SKIP_EXACT
        DuplicateKind.POSSIBLE -> MigrationDuplicateExecutionDecision.DEFER
        DuplicateKind.DIFFERENT -> MigrationDuplicateExecutionDecision.IMPORT_ANYWAY
    }

    fun ambiguityDecision(ambiguityId: String): MigrationAmbiguityExecutionDecision =
        ambiguityDecisions[ambiguityId] ?: MigrationAmbiguityExecutionDecision.DEFER
}

enum class MigrationSourceRevalidationStatus {
    UNCHANGED,
    CHANGED_REQUIRES_REANALYSIS,
    UNAVAILABLE,
    FAILED_VALIDATION,
}

enum class MigrationSourceRevalidationEvidence {
    STREAM_SIGNATURE,
    PROVIDER_SIZE,
    PROVIDER_MODIFIED_TIME,
    PDF_PAGE_COUNT,
    PREVIOUS_SHA256,
}

data class MigrationSourceRevalidation(
    val sourceDiscoveryId: String,
    val status: MigrationSourceRevalidationStatus,
    val evidence: Set<MigrationSourceRevalidationEvidence> = emptySet(),
    val currentSizeBytes: Long? = null,
    val currentModifiedAtMillis: Long? = null,
    val currentPageCount: Int? = null,
) {
    init {
        require(sourceDiscoveryId.isNotBlank())
        require(currentSizeBytes == null || currentSizeBytes >= 0L)
        require(currentModifiedAtMillis == null || currentModifiedAtMillis >= 0L)
        require(currentPageCount == null || currentPageCount > 0)
    }
}

fun interface MigrationSourceRevalidator {
    suspend fun revalidate(
        source: InspectedMigrationSource,
        cancellationSignal: MigrationCancellationSignal,
    ): MigrationSourceRevalidation
}

internal enum class MigrationExecutionCheckpoint {
    AFTER_JOURNAL_CREATED,
    AFTER_DOCUMENT_STAGED,
    BEFORE_ATOMIC_ACTIVATION,
    AFTER_ATOMIC_ACTIVATION,
    DURING_POST_ACTIVATION_CLEANUP,
}

internal fun interface MigrationExecutionFaultInjector {
    suspend fun checkpoint(
        checkpoint: MigrationExecutionCheckpoint,
        operationId: String,
        itemId: String?,
    )
}

internal object NoMigrationExecutionFaults : MigrationExecutionFaultInjector {
    override suspend fun checkpoint(
        checkpoint: MigrationExecutionCheckpoint,
        operationId: String,
        itemId: String?,
    ) = Unit
}

enum class MigrationTerminalSourceOutcome {
    IMPORTED,
    EXACT_DUPLICATE_SKIPPED,
    IMPORTED_DESPITE_DUPLICATE,
    EXCLUDED_BY_USER,
    UNSUPPORTED,
    AMBIGUOUS_REVIEW_UNRESOLVED,
    SOURCE_CHANGED,
    UNAVAILABLE,
    VALIDATION_FAILED,
    STAGING_FAILED,
    PUBLICATION_FAILED,
    CANCELLED_UNPROCESSED,
}

data class MigrationSourceCompletion(
    val accountingId: String,
    val outcome: MigrationTerminalSourceOutcome,
    val documentId: String?,
    val duplicateKind: DuplicateKind? = null,
    val failureCode: String? = null,
) {
    init {
        require(accountingId.isNotBlank())
        require(documentId == null || documentId.isNotBlank())
        require(failureCode == null || failureCode.isNotBlank())
    }
}

data class MigrationDurableCompletionReport(
    val operationId: String,
    val requestedSourceCount: Int,
    val sources: List<MigrationSourceCompletion>,
    val importedDocumentCount: Int,
    val importedPageCount: Int,
    val reconstructedFolderCount: Int,
    val preservedSourcePdfCount: Int,
    val isTerminal: Boolean,
) {
    init {
        require(operationId.isNotBlank())
        require(requestedSourceCount >= 0)
        require(sources.map(MigrationSourceCompletion::accountingId).distinct().size == sources.size)
        require(importedDocumentCount >= 0)
        require(importedPageCount >= 0)
        require(reconstructedFolderCount >= 0)
        require(preservedSourcePdfCount >= 0)
    }

    val selectedSourceCount: Int
        get() = sources.size

    val reconciles: Boolean
        get() = sources.size == requestedSourceCount &&
            sources.map(MigrationSourceCompletion::accountingId).distinct().size == sources.size
}

internal enum class MigrationResolvedDocumentAction {
    IMPORT,
    EXCLUDE,
    DEFER,
}

internal data class MigrationResolvedDocument(
    val plan: MigrationDocumentPlan,
    val action: MigrationResolvedDocumentAction,
    val recordedDecision: String,
)

internal fun MigrationImportPlan.resolveExecutionDecisions(
    decisions: MigrationExecutionDecisions,
): List<MigrationResolvedDocument> {
    val ambiguityByDocument = ambiguities.flatMap { ambiguity ->
        ambiguity.plannedDocumentIds.map { documentId -> documentId to ambiguity }
    }.groupBy({ it.first }, { it.second })
    return documents.flatMap { document ->
        val documentDecision = decisions.documentDecision(document.id)
        if (documentDecision == MigrationPlannedDocumentDecision.EXCLUDE) {
            return@flatMap listOf(
                MigrationResolvedDocument(
                    document,
                    MigrationResolvedDocumentAction.EXCLUDE,
                    DECISION_EXCLUDE_DOCUMENT,
                ),
            )
        }
        val ambiguityDecisions = ambiguityByDocument[document.id].orEmpty().map { ambiguity ->
            decisions.ambiguityDecision(ambiguity.id)
        }
        when {
            MigrationAmbiguityExecutionDecision.DEFER in ambiguityDecisions -> listOf(
                MigrationResolvedDocument(
                    document,
                    MigrationResolvedDocumentAction.DEFER,
                    DECISION_DEFER_AMBIGUITY,
                ),
            )
            MigrationAmbiguityExecutionDecision.EXCLUDE in ambiguityDecisions -> listOf(
                MigrationResolvedDocument(
                    document,
                    MigrationResolvedDocumentAction.EXCLUDE,
                    DECISION_EXCLUDE_AMBIGUITY,
                ),
            )
            MigrationAmbiguityExecutionDecision.SPLIT_INDEPENDENT in ambiguityDecisions ->
                document.splitIntoIndependentDocuments().map { split ->
                    MigrationResolvedDocument(
                        split,
                        MigrationResolvedDocumentAction.IMPORT,
                        DECISION_SPLIT_AMBIGUITY,
                    )
                }
            else -> listOf(
                MigrationResolvedDocument(
                    document,
                    MigrationResolvedDocumentAction.IMPORT,
                    if (ambiguityDecisions.isEmpty()) {
                        DECISION_IMPORT_DOCUMENT
                    } else {
                        DECISION_ACCEPT_AMBIGUITY
                    },
                ),
            )
        }
    }
}

private fun MigrationDocumentPlan.splitIntoIndependentDocuments(): List<MigrationDocumentPlan> {
    if (sources.size == 1) return listOf(this)
    return sources.mapIndexed { index, source ->
        val contentType = source.contentType
        copy(
            id = "$id:split:$index",
            suggestedTitle = source.source.displayName
                ?.substringBeforeLast('.', missingDelimiterValue = source.source.displayName)
                ?.takeIf(String::isNotBlank)
                ?: suggestedTitle,
            sources = listOf(source),
            grouping = if (contentType == MigrationContentType.PDF) {
                MigrationDocumentGrouping.SINGLE_PDF
            } else {
                MigrationDocumentGrouping.SINGLE_IMAGE
            },
            pages = pages.filter { it.sourceDiscoveryId == source.source.discoveryIdentity }
                .mapIndexed { pageIndex, page ->
                    page.copy(
                        id = "${page.id}:split:$index",
                        plannedIndex = pageIndex,
                    )
                },
            originalAssets = originalAssets.filter {
                it.sourceDiscoveryId == source.source.discoveryIdentity
            },
            dateCandidates = dateCandidates.filter {
                it.sourceDiscoveryId == source.source.discoveryIdentity
            },
            ambiguityIds = emptyList(),
        )
    }
}

internal const val DECISION_IMPORT_DOCUMENT = "IMPORT"
internal const val DECISION_EXCLUDE_DOCUMENT = "EXCLUDE"
internal const val DECISION_ACCEPT_AMBIGUITY = "ACCEPT_AMBIGUITY"
internal const val DECISION_SPLIT_AMBIGUITY = "SPLIT_AMBIGUITY"
internal const val DECISION_EXCLUDE_AMBIGUITY = "EXCLUDE_AMBIGUITY"
internal const val DECISION_DEFER_AMBIGUITY = "DEFER_AMBIGUITY"
internal const val DECISION_IMPORT_DUPLICATE = "IMPORT_DUPLICATE"
internal const val DECISION_EXCLUDE_DUPLICATE = "EXCLUDE_DUPLICATE"
internal const val DECISION_DEFER_DUPLICATE = "DEFER_DUPLICATE"
internal const val DECISION_SKIP_EXACT = "SKIP_EXACT"
internal const val DECISION_SAFE_DEFAULT = "SAFE_DEFAULT"
