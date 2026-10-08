package org.synapseworks.pageharbor.migration

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.synapseworks.pageharbor.document.importing.InboundShareResource
import org.synapseworks.pageharbor.portability.StorageEstimate
import org.synapseworks.pageharbor.portability.StoragePreflight
import org.synapseworks.pageharbor.portability.StoragePreflightResult

data class MigrationFileResource(
    val uri: Uri,
    val declaredContentType: String? = null,
    val grantFlags: Int = 0,
    val relativeFolderPath: List<String> = emptyList(),
) {
    init {
        require(relativeFolderPath.none(::isUnsafePathSegment))
    }
}

enum class MigrationBatchSourceKind {
    FILE_URIS,
    DOCUMENT_TREE,
    INBOUND_SHARE,
}

@ConsistentCopyVisibility
data class PreparedMigrationBatch internal constructor(
    val preview: MigrationPreview,
    val sourceKind: MigrationBatchSourceKind,
    val discovery: MigrationTreeDiscoveryResult? = null,
    internal val sourceRootUri: String? = null,
    internal val sourceGrantFlags: Int = 0,
)

sealed interface MigrationExecutionResult {
    data class Completed(val report: MigrationBatchReport) : MigrationExecutionResult

    data class InsufficientStorage(
        val estimate: StorageEstimate,
        val availableBytes: Long,
    ) : MigrationExecutionResult

    data class PreviewIncomplete(
        val unclassifiedDocumentIds: Set<String>,
    ) : MigrationExecutionResult
}

/** Android entry point for picker files, recursive SAF trees, and ACTION_SEND resources. */
class AndroidBulkMigrationCoordinator(
    context: Context,
    private val sourceAdapter: AndroidMigrationSourceAdapter =
        AndroidMigrationSourceAdapter(context),
    private val treeDiscovery: AndroidSafTreeMigrationDiscovery =
        AndroidSafTreeMigrationDiscovery(context),
    private val storageCapacity: MigrationStorageCapacity =
        AndroidMigrationStorageCapacity(context),
) {
    private val applicationContext = context.applicationContext
    private val duplicateAnalyzer = RoomMigrationDuplicateAnalyzer(
        context,
        sourceAdapter,
        storageCapacity,
    )

    suspend fun previewFiles(
        resources: List<MigrationFileResource>,
        cancellationSignal: MigrationCancellationSignal = NeverCancelMigration,
        progressListener: MigrationPreviewProgressListener =
            MigrationPreviewProgressListener { _, _ -> },
        duplicateProgressListener: MigrationBatchProgressListener =
            MigrationBatchProgressListener { },
    ): PreparedMigrationBatch = withContext(Dispatchers.IO) {
        resources.forEach { resource ->
            retainReadAccessBestEffort(resource.uri, resource.grantFlags)
        }
        val sources = resources.map { resource ->
            sourceAdapter.describe(
                uri = resource.uri,
                relativeFolderPath = resource.relativeFolderPath,
                declaredContentType = resource.declaredContentType,
                platformAccessFlags = resource.grantFlags,
                origin = MigrationSourceOrigin.FILE_SELECTION,
            )
        }
        val preview = BulkMigrationPlanner(sourceAdapter).preview(
            sources,
            cancellationSignal,
            progressListener,
        )
        PreparedMigrationBatch(
            preview = duplicateAnalyzer.analyze(
                preview,
                cancellationSignal,
                duplicateProgressListener,
            ),
            sourceKind = MigrationBatchSourceKind.FILE_URIS,
            sourceGrantFlags = resources.fold(0) { flags, resource ->
                flags or resource.grantFlags
            },
        )
    }

    suspend fun previewShares(
        resources: List<InboundShareResource>,
        cancellationSignal: MigrationCancellationSignal = NeverCancelMigration,
        progressListener: MigrationPreviewProgressListener =
            MigrationPreviewProgressListener { _, _ -> },
        duplicateProgressListener: MigrationBatchProgressListener =
            MigrationBatchProgressListener { },
    ): PreparedMigrationBatch = withContext(Dispatchers.IO) {
        val sources = resources.mapIndexed { index, resource ->
            sourceAdapter.describe(resource, discoveryOrder = index)
        }
        val preview = BulkMigrationPlanner(sourceAdapter).preview(
            sources,
            cancellationSignal,
            progressListener,
        )
        PreparedMigrationBatch(
            preview = duplicateAnalyzer.analyze(
                preview,
                cancellationSignal,
                duplicateProgressListener,
            ),
            sourceKind = MigrationBatchSourceKind.INBOUND_SHARE,
            sourceGrantFlags = resources.fold(0) { flags, resource ->
                flags or resource.grantFlags
            },
        )
    }

    suspend fun previewTree(
        treeUri: Uri,
        grantFlags: Int,
        cancellationSignal: MigrationCancellationSignal = NeverCancelMigration,
        discoveryProgressListener: MigrationTreeDiscoveryProgressListener =
            MigrationTreeDiscoveryProgressListener { _, _ -> },
        previewProgressListener: MigrationPreviewProgressListener =
            MigrationPreviewProgressListener { _, _ -> },
        duplicateProgressListener: MigrationBatchProgressListener =
            MigrationBatchProgressListener { },
    ): PreparedMigrationBatch {
        retainReadAccessBestEffort(treeUri, grantFlags)
        val discovery = treeDiscovery.discover(
            treeUri = treeUri,
            platformAccessFlags = grantFlags,
            cancellationSignal = cancellationSignal,
            progressListener = discoveryProgressListener,
        )
        val plannedPreview = withContext(Dispatchers.IO) {
            BulkMigrationPlanner(sourceAdapter).preview(
                sources = discovery.sources,
                cancellationSignal = cancellationSignal,
                progressListener = previewProgressListener,
            ).withDiscoveryWarnings(discovery)
        }
        val preview = duplicateAnalyzer.analyze(
            plannedPreview,
            cancellationSignal,
            duplicateProgressListener,
        )
        return PreparedMigrationBatch(
            preview = preview,
            sourceKind = MigrationBatchSourceKind.DOCUMENT_TREE,
            discovery = discovery,
            sourceRootUri = treeUri.toString(),
            sourceGrantFlags = grantFlags,
        )
    }

    suspend fun execute(
        prepared: PreparedMigrationBatch,
        duplicateDecisions: MigrationDuplicateDecisions = MigrationDuplicateDecisions(),
        cancellationSignal: MigrationCancellationSignal = NeverCancelMigration,
        progressListener: MigrationBatchProgressListener = MigrationBatchProgressListener { },
    ): MigrationExecutionResult = executeVerified(
        prepared = prepared,
        decisions = duplicateDecisions.toExecutionDecisions(),
        cancellationSignal = cancellationSignal,
        progressListener = progressListener,
    )

    /** Domain seam for a review UI without coupling execution to transient UI state. */
    suspend fun executeVerified(
        prepared: PreparedMigrationBatch,
        decisions: MigrationExecutionDecisions,
        cancellationSignal: MigrationCancellationSignal = NeverCancelMigration,
        progressListener: MigrationBatchProgressListener = MigrationBatchProgressListener { },
    ): MigrationExecutionResult {
        incompletePreview(prepared.preview)?.let { return it }
        preflight(prepared.preview)?.let { return it }
        return MigrationExecutionResult.Completed(
            verifiedExecutor(prepared).execute(
                plan = prepared.preview,
                decisions = decisions,
                cancellationSignal = cancellationSignal,
                progressListener = progressListener,
            ),
        )
    }

    suspend fun retryFailures(
        prepared: PreparedMigrationBatch,
        previousReport: MigrationBatchReport,
        duplicateDecisions: MigrationDuplicateDecisions = MigrationDuplicateDecisions(),
        cancellationSignal: MigrationCancellationSignal = NeverCancelMigration,
        progressListener: MigrationBatchProgressListener = MigrationBatchProgressListener { },
    ): MigrationExecutionResult {
        incompletePreview(prepared.preview)?.let { return it }
        preflight(prepared.preview)?.let { return it }
        previousReport.operationId?.let { operationId ->
            return MigrationExecutionResult.Completed(
                verifiedExecutor(prepared).resume(
                    operationId = operationId,
                    decisions = duplicateDecisions.toExecutionDecisions(),
                    cancellationSignal = cancellationSignal,
                    progressListener = progressListener,
                ),
            )
        }
        val engine = BulkMigrationEngine(legacyPublisher(prepared, duplicateDecisions))
        return MigrationExecutionResult.Completed(
            engine.retryFailures(
                prepared.preview,
                previousReport,
                cancellationSignal,
                progressListener,
            ),
        )
    }

    /** Includes cancelled, unattempted, and POSSIBLE-review documents not yet approved. */
    suspend fun retryIncomplete(
        prepared: PreparedMigrationBatch,
        previousReport: MigrationBatchReport,
        duplicateDecisions: MigrationDuplicateDecisions = MigrationDuplicateDecisions(),
        cancellationSignal: MigrationCancellationSignal = NeverCancelMigration,
        progressListener: MigrationBatchProgressListener = MigrationBatchProgressListener { },
    ): MigrationExecutionResult {
        incompletePreview(prepared.preview)?.let { return it }
        preflight(prepared.preview)?.let { return it }
        previousReport.operationId?.let { operationId ->
            return MigrationExecutionResult.Completed(
                verifiedExecutor(prepared).resume(
                    operationId = operationId,
                    decisions = duplicateDecisions.toExecutionDecisions(),
                    cancellationSignal = cancellationSignal,
                    progressListener = progressListener,
                ),
            )
        }
        val engine = BulkMigrationEngine(legacyPublisher(prepared, duplicateDecisions))
        return MigrationExecutionResult.Completed(
            engine.retryIncomplete(
                prepared.preview,
                previousReport,
                cancellationSignal,
                progressListener,
            ),
        )
    }

    /** Cleans invisible PENDING rows and private assets left by an interrupted migration process. */
    suspend fun recoverInterruptedOperations(): Int {
        val verifiedRecovered = verifiedExecutor().recoverInterruptedOperations()
        val legacyRecovered = RoomMigrationDocumentPublisher(
            context = applicationContext,
            sourceAccess = sourceAdapter,
            duplicateDecisions = MigrationDuplicateDecisions(),
            journalMetadata = defaultJournalMetadata(),
            storageCapacity = storageCapacity,
        ).recoverInterruptedOperations()
        return verifiedRecovered + legacyRecovered
    }

    private fun legacyPublisher(
        prepared: PreparedMigrationBatch,
        duplicateDecisions: MigrationDuplicateDecisions,
    ): MigrationDocumentPublisher = RoomMigrationDocumentPublisher(
        context = applicationContext,
        sourceAccess = sourceAdapter,
        duplicateDecisions = duplicateDecisions,
        journalMetadata = MigrationJournalMetadata(
            sourceKind = prepared.sourceKind.name,
            sourceRootUri = prepared.sourceRootUri,
            sourceGrantFlags = prepared.sourceGrantFlags,
        ),
        storageCapacity = storageCapacity,
    )

    private fun verifiedExecutor(
        prepared: PreparedMigrationBatch? = null,
    ): RoomVerifiedMigrationExecutor = RoomVerifiedMigrationExecutor(
        context = applicationContext,
        sourceAccess = sourceAdapter,
        sourceRevalidator = AndroidMigrationSourceRevalidator(sourceAdapter),
        journalMetadata = prepared?.let {
            MigrationJournalMetadata(
                sourceKind = it.sourceKind.name,
                sourceRootUri = it.sourceRootUri,
                sourceGrantFlags = it.sourceGrantFlags,
            )
        } ?: defaultJournalMetadata(),
        storageCapacity = storageCapacity,
    )

    private fun defaultJournalMetadata() = MigrationJournalMetadata(
        sourceKind = MigrationBatchSourceKind.FILE_URIS.name,
        sourceRootUri = null,
        sourceGrantFlags = 0,
    )

    private fun preflight(preview: MigrationPreview): MigrationExecutionResult.InsufficientStorage? {
        val estimate = migrationBatchStorageEstimate(preview)
        return when (val result = StoragePreflight.evaluate(estimate, storageCapacity.availableBytes())) {
            is StoragePreflightResult.Insufficient ->
                MigrationExecutionResult.InsufficientStorage(result.estimate, result.availableBytes)
            is StoragePreflightResult.Sufficient,
            is StoragePreflightResult.Unknown,
            -> null
        }
    }

    private fun incompletePreview(
        preview: MigrationPreview,
    ): MigrationExecutionResult.PreviewIncomplete? {
        if (preview.duplicateAnalysisComplete) return null
        val failed = preview.duplicateDocuments.asSequence()
            .filter { it.duplicateKind == null }
            .map(MigrationDuplicatePreview::documentId)
            .toSet()
        return MigrationExecutionResult.PreviewIncomplete(
            unclassifiedDocumentIds = preview.unclassifiedDocumentIds + failed,
        )
    }

    private fun retainReadAccessBestEffort(uri: Uri, grantFlags: Int) {
        if (
            grantFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0 ||
            grantFlags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION == 0
        ) {
            return
        }
        try {
            applicationContext.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            // Ephemeral share grants remain usable for the current operation.
        } catch (_: IllegalArgumentException) {
            // Not every provider implements persistable grants despite intent metadata.
        } catch (_: RuntimeException) {
            // Provider-specific persistence failures do not discard the current read grant.
        }
    }
}

private fun MigrationPreview.withDiscoveryWarnings(
    discovery: MigrationTreeDiscoveryResult,
): MigrationPreview {
    val discoveryWarnings = buildList {
        if (discovery.wasTruncated) {
            add(MigrationPlanWarning(MigrationAnalysisWarning.TREE_TRUNCATED))
        }
        if (discovery.unreadableDirectoryCount > 0) {
            add(
                MigrationPlanWarning(
                    warning = MigrationAnalysisWarning.TREE_UNREADABLE_DIRECTORY,
                    occurrenceCount = discovery.unreadableDirectoryCount,
                ),
            )
        }
        if (discovery.skippedEntryCount > 0) {
            add(
                MigrationPlanWarning(
                    warning = MigrationAnalysisWarning.TREE_ENTRY_SKIPPED,
                    occurrenceCount = discovery.skippedEntryCount,
                ),
            )
        }
        if (discovery.duplicateReferenceCount > 0) {
            add(
                MigrationPlanWarning(
                    warning = MigrationAnalysisWarning.DUPLICATE_SOURCE_REFERENCE,
                    occurrenceCount = discovery.duplicateReferenceCount,
                ),
            )
        }
    }
    return copy(planWarnings = planWarnings + discoveryWarnings)
}

internal fun migrationBatchStorageEstimate(preview: MigrationPreview): StorageEstimate {
    val durableAndDerivedSizes = buildList {
        preview.documents.forEach { plan ->
            plan.sources.forEach { inspected ->
                add(inspected.source.sizeBytes)
                // PDFs retain the original and produce private page assets. Source size is a
                // practical allowance for those derived assets; an unknown provider size stays
                // unknown once rather than pretending to be several independent unknown files.
                if (
                    plan.grouping == MigrationDocumentGrouping.SINGLE_PDF &&
                    inspected.source.sizeBytes != null
                ) {
                    add(inspected.source.sizeBytes)
                }
            }
        }
    }
    val peakKnownDocumentBytes = preview.documents.mapNotNull { plan ->
        val sizes = plan.sources.map { it.source.sizeBytes }
        if (sizes.any { it == null }) {
            null
        } else {
            sizes.filterNotNull().fold(0L, ::saturatingAdd)
        }
    }.maxOrNull()
    return StoragePreflight.estimate(
        // Documents are staged serially, so only the largest known document needs an additional
        // transient-copy allowance. This avoids multiplying a multi-gigabyte batch by the peak
        // per-document working-copy count.
        sourceSizes = durableAndDerivedSizes + listOfNotNull(peakKnownDocumentBytes),
        workingCopyCount = 1,
        fixedOverheadBytes = saturatingMultiply(
            preview.documents.size.toLong(),
            PER_DOCUMENT_STORAGE_OVERHEAD_BYTES,
        ),
    )
}

private fun saturatingAdd(left: Long, right: Long): Long =
    if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

private fun saturatingMultiply(left: Long, right: Long): Long = when {
    left == 0L || right == 0L -> 0L
    left > Long.MAX_VALUE / right -> Long.MAX_VALUE
    else -> left * right
}

private fun MigrationDuplicateDecisions.toExecutionDecisions() = MigrationExecutionDecisions(
    duplicateDecisions = importDespiteDuplicateDocumentIds.associateWith {
        MigrationDuplicateExecutionDecision.IMPORT_ANYWAY
    },
)

private const val PER_DOCUMENT_STORAGE_OVERHEAD_BYTES = 512L * 1024L
