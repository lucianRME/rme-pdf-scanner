package org.synapseworks.pageharbor.backup.restore

import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import org.synapseworks.pageharbor.backup.format.BackupDocumentRecord
import org.synapseworks.pageharbor.backup.format.BackupFolderRecord
import org.synapseworks.pageharbor.backup.format.BackupPageRecord
import org.synapseworks.pageharbor.backup.format.BackupSourceAssetRecord
import org.synapseworks.pageharbor.backup.format.VerifiedBackup
import org.synapseworks.pageharbor.library.duplicate.DocumentFingerprint
import org.synapseworks.pageharbor.library.duplicate.DuplicateKind

data class RestoreArchiveSource(
    val byteLength: Long?,
    val openStream: () -> InputStream,
) {
    init {
        require(byteLength == null || byteLength >= 0L)
    }
}

enum class RestoreMergePolicy {
    MERGE_SKIP_EXACT,
    MERGE_IMPORT_ANYWAY,
}

data class RestorePreviewDocument(
    val backupDocumentId: String,
    val title: String,
    val pageCount: Int,
    val sourceAssetCount: Int,
    val duplicateKind: DuplicateKind,
    val duplicateDocumentId: String?,
)

data class RestorePreview(
    val backupId: String,
    val createdAtEpochMillis: Long,
    val folderCount: Int,
    val documentCount: Int,
    val pageCount: Int,
    val sourceAssetCount: Int,
    val contentByteLength: Long,
    val documents: List<RestorePreviewDocument>,
)

enum class RestorePreparationFailure {
    INSUFFICIENT_STORAGE,
    INVALID_OR_CORRUPT_BACKUP,
    STAGING_UNAVAILABLE,
    LIBRARY_UNAVAILABLE,
}

sealed interface RestorePreparationResult {
    data class Ready(val prepared: PreparedRestore) : RestorePreparationResult

    data class Failed(
        val reason: RestorePreparationFailure,
        val formatFailure: String? = null,
    ) : RestorePreparationResult
}

enum class RestoreFailure {
    ALREADY_CONSUMED,
    INSUFFICIENT_STORAGE,
    SOURCE_MISSING,
    JOURNAL_UNAVAILABLE,
    PREPARATION_FAILED,
    ACTIVATION_FAILED,
}

sealed interface RestoreResult {
    data class Completed(
        val importedDocumentCount: Int,
        val skippedExactDocumentCount: Int,
        val stagingCleanupSucceeded: Boolean,
    ) : RestoreResult

    data class Cancelled(
        val cleanupSucceeded: Boolean,
    ) : RestoreResult

    data class Failed(
        val reason: RestoreFailure,
        val cleanupSucceeded: Boolean,
    ) : RestoreResult
}

fun interface RestoreCancellationSignal {
    fun isCancellationRequested(): Boolean
}

object NeverCancelRestore : RestoreCancellationSignal {
    override fun isCancellationRequested(): Boolean = false
}

fun interface RestoreProgressListener {
    fun onProgress(progress: RestoreProgress)
}

data class RestoreProgress(
    val preparedDocumentCount: Int,
    val totalDocumentCount: Int,
)

internal data class RestoreDocumentBundle(
    val document: BackupDocumentRecord,
    val pages: List<BackupPageRecord>,
    val sourceAssets: List<BackupSourceAssetRecord>,
    val fingerprint: DocumentFingerprint,
)

class PreparedRestore internal constructor(
    val preview: RestorePreview,
    internal val verifiedBackup: VerifiedBackup,
    internal val documents: List<RestoreDocumentBundle>,
    internal val stagingArea: RestoreStagingArea,
) : AutoCloseable {
    private val consumed = AtomicBoolean(false)

    internal fun claim(): Boolean = consumed.compareAndSet(false, true)

    val isConsumed: Boolean
        get() = consumed.get()

    override fun close() {
        if (consumed.compareAndSet(false, true)) stagingArea.discard()
    }
}

internal data class RestoreExistingFolder(
    val folderId: String,
    val name: String,
    val parentFolderId: String?,
)

internal data class RestoreFolderToCreate(
    val folderId: String,
    val originalFolderId: String,
    val name: String,
    val normalizedName: String,
    val parentFolderId: String?,
    val createdAtEpochMillis: Long,
    val modifiedAtEpochMillis: Long,
)

internal data class RestoreDocumentToPrepare(
    val itemId: String,
    val targetDocumentId: String,
    val targetFolderId: String?,
    val bundle: RestoreDocumentBundle,
    val duplicateKind: DuplicateKind,
)

internal data class RestoreJournalPlan(
    val operationId: String,
    val backupId: String,
    val createdAtEpochMillis: Long,
    val contentByteLength: Long,
    val discoveredDocumentCount: Int,
    val documentsToImport: List<RestoreDocumentToPrepare>,
    val skippedExactDocuments: List<RestoreDocumentBundle>,
)

internal data class RestoreActivationPlan(
    val operationId: String,
    val folders: List<RestoreFolderToCreate>,
    val documents: List<RestoreDocumentToPrepare>,
    val activatedAtEpochMillis: Long,
)

internal data class RestoreRecoveryOperation(
    val operationId: String,
)

internal interface RestoreLibraryStore {
    suspend fun duplicateCandidates(): List<org.synapseworks.pageharbor.library.duplicate.DuplicateCandidate>

    suspend fun existingFolders(): List<RestoreExistingFolder>

    suspend fun beginOperation(plan: RestoreJournalPlan)

    suspend fun preparePendingDocument(
        operationId: String,
        document: RestoreDocumentToPrepare,
        assets: RestoreStagedAssetSource,
    )

    suspend fun activate(plan: RestoreActivationPlan)

    /** True only after the complete activation transaction and journal commit are durable. */
    suspend fun isOperationCompleted(operationId: String): Boolean = false

    suspend fun terminate(
        operationId: String,
        cancelled: Boolean,
        failure: RestoreFailure?,
    ): Boolean

    suspend fun recoverableOperations(): List<RestoreRecoveryOperation>
}

internal fun interface RestoreIdSource {
    fun newId(): String
}

internal fun interface RestoreClock {
    fun nowEpochMillis(): Long
}

internal fun requiredBackupFolders(
    folders: List<BackupFolderRecord>,
    documents: List<RestoreDocumentBundle>,
): Set<String> {
    val byId = folders.associateBy(BackupFolderRecord::folderId)
    val required = linkedSetOf<String>()
    documents.forEach { bundle ->
        var current = bundle.document.folderId
        while (current != null && required.add(current)) current = byId[current]?.parentFolderId
    }
    return required
}
