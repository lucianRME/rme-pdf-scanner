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
        val sources = resources.map { resource -> sourceAdapter.describe(resource) }
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
            )
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
    ): MigrationExecutionResult {
        incompletePreview(prepared.preview)?.let { return it }
        preflight(prepared.preview)?.let { return it }
        val engine = BulkMigrationEngine(publisher(prepared, duplicateDecisions))
        return MigrationExecutionResult.Completed(
            engine.execute(prepared.preview, cancellationSignal, progressListener),
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
        val engine = BulkMigrationEngine(publisher(prepared, duplicateDecisions))
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
        val engine = BulkMigrationEngine(publisher(prepared, duplicateDecisions))
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
    suspend fun recoverInterruptedOperations(): Int = RoomMigrationDocumentPublisher(
        context = applicationContext,
        sourceAccess = sourceAdapter,
        duplicateDecisions = MigrationDuplicateDecisions(),
        journalMetadata = MigrationJournalMetadata(
            sourceKind = MigrationBatchSourceKind.FILE_URIS.name,
            sourceRootUri = null,
            sourceGrantFlags = 0,
        ),
        storageCapacity = storageCapacity,
    ).recoverInterruptedOperations()

    private fun publisher(
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

internal fun migrationBatchStorageEstimate(preview: MigrationPreview): StorageEstimate =
    StoragePreflight.estimate(
        sourceSizes = preview.documents.flatMap { plan ->
            plan.sources.map { inspected -> inspected.source.sizeBytes }
        },
        // A PDF may coexist as provider source, private staging copy, rendered pages, and library copy.
        workingCopyCount = 3,
        fixedOverheadBytes = saturatingMultiply(
            preview.documents.size.toLong(),
            PER_DOCUMENT_STORAGE_OVERHEAD_BYTES,
        ),
    )

private fun saturatingMultiply(left: Long, right: Long): Long = when {
    left == 0L || right == 0L -> 0L
    left > Long.MAX_VALUE / right -> Long.MAX_VALUE
    else -> left * right
}

private const val PER_DOCUMENT_STORAGE_OVERHEAD_BYTES = 512L * 1024L
