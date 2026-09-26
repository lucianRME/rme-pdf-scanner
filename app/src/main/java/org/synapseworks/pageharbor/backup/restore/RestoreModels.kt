package org.synapseworks.pageharbor.backup.restore

import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import org.synapseworks.pageharbor.backup.format.BackupDocumentRecord
import org.synapseworks.pageharbor.backup.format.BackupVerifiedRecordStore
import org.synapseworks.pageharbor.backup.format.IndexedVerifiedBackup
import org.synapseworks.pageharbor.library.duplicate.DocumentFingerprint
import org.synapseworks.pageharbor.library.duplicate.DuplicateDetector
import org.synapseworks.pageharbor.library.duplicate.DuplicateKind
import org.synapseworks.pageharbor.library.duplicate.DuplicateMatch
import org.synapseworks.pageharbor.library.duplicate.IncomingDocumentIdentity
import org.synapseworks.pageharbor.library.duplicate.OcrStateDigest

data class RestoreArchiveSource(
    val byteLength: Long?,
    val reclaimableByteLength: Long = 0L,
    val openStream: () -> InputStream,
) {
    init {
        require(byteLength == null || byteLength >= 0L)
        require(reclaimableByteLength >= 0L)
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
    val exactDuplicateCount: Int,
    val possibleDuplicateCount: Int,
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

internal data class RestoreIndexedDocument(
    val document: BackupDocumentRecord,
    val fingerprint: DocumentFingerprint,
    val ocrStateDigest: OcrStateDigest,
    val pageByteLength: Long,
    val sourceAssetByteLength: Long,
    val firstSourceModifiedAtEpochMillis: Long?,
)

internal data class RestorePlannedDocument(
    val source: RestoreIndexedDocument,
    val duplicateKind: DuplicateKind,
)

internal data class RestoreIndexedDocumentRow(
    val ordinal: Int,
    val source: RestoreIndexedDocument,
)

internal data class RestorePlannedDocumentRow(
    val ordinal: Int,
    val planned: RestorePlannedDocument,
)

class PreparedRestore internal constructor(
    val preview: RestorePreview,
    internal val backup: IndexedVerifiedBackup,
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
    val targetFolderSearchPath: String,
    val source: RestoreIndexedDocument,
    val duplicateKind: DuplicateKind,
)

internal data class RestoreJournalPlan(
    val operationId: String,
    val backupId: String,
    val createdAtEpochMillis: Long,
    val contentByteLength: Long,
    val discoveredDocumentCount: Int,
    val plannedDocumentCount: Int,
    val skippedExactDocumentCount: Int,
)

internal data class RestoreActivationPlan(
    val operationId: String,
    val folders: RestoreFolderPlanSource,
    val folderCount: Int,
    val importedDocumentCount: Int,
    val activatedAtEpochMillis: Long,
)

internal data class RestoreFolderPlanRow(
    val ordinal: Int,
    val folder: RestoreFolderToCreate,
)

internal interface RestoreFolderPlanSource {
    fun plannedFoldersPage(afterOrdinal: Int, limit: Int): List<RestoreFolderPlanRow>
}

internal data class RestoreRecoveryOperation(
    val operationId: String,
)

internal interface RestoreLibraryStore {
    suspend fun classifyDuplicate(identity: IncomingDocumentIdentity): DuplicateMatch

    suspend fun possibleSourceDuplicate(sourceSha256: Collection<String>): String?

    suspend fun folderNameExists(parentFolderId: String?, normalizedName: String): Boolean

    suspend fun beginOperation(plan: RestoreJournalPlan)

    suspend fun recordSkippedExactDocument(
        operationId: String,
        ordinal: Int,
        source: RestoreIndexedDocument,
    )

    suspend fun preparePendingDocument(
        operationId: String,
        ordinal: Int,
        document: RestoreDocumentToPrepare,
        records: BackupVerifiedRecordStore,
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
