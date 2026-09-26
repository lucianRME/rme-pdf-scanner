package org.synapseworks.pageharbor.backup.engine

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import org.synapseworks.pageharbor.backup.format.BackupAssetStreamOpener
import org.synapseworks.pageharbor.backup.format.BackupDocumentRecord
import org.synapseworks.pageharbor.backup.format.BackupFolderRecord
import org.synapseworks.pageharbor.backup.format.BackupOcrArtifactRecord
import org.synapseworks.pageharbor.backup.format.BackupOcrCorrectionRecord
import org.synapseworks.pageharbor.backup.format.BackupOcrDocumentStateRecord
import org.synapseworks.pageharbor.backup.format.BackupOcrLineRecord
import org.synapseworks.pageharbor.backup.format.BackupRecordSource
import org.synapseworks.pageharbor.backup.format.BackupOcrPageStateRecord
import org.synapseworks.pageharbor.backup.format.BackupPageRecord
import org.synapseworks.pageharbor.backup.format.BackupSourceAssetRecord
import org.synapseworks.pageharbor.backup.format.BackupOcrManifest
import org.synapseworks.pageharbor.backup.format.BackupSummary
import org.synapseworks.pageharbor.backup.format.PreparedBackupArchiveSource

/**
 * An operation-owned, repeatable backup handle.
 *
 * Production snapshots spool metadata and asset descriptors to private storage instead of retaining
 * a whole-library object graph. Implementations must release that operation-owned storage in [close].
 */
interface LibraryBackupSnapshot : BackupRecordSource, BackupAssetStreamOpener, AutoCloseable {
    val boundedStats: LibraryBackupSnapshotStats

    fun installCancellationCheck(check: () -> Unit)
}

data class LibraryBackupSnapshotStats(
    val summary: BackupSummary,
    val ocrManifest: BackupOcrManifest,
    val serializedMetadataBytes: Long,
    val spoolBytes: Long,
    val zipEntryCount: Long,
)

internal class SpoolLibraryBackupSnapshot(
    private val prepared: PreparedBackupArchiveSource,
    override val boundedStats: LibraryBackupSnapshotStats,
    private val cleanup: () -> Boolean,
) : LibraryBackupSnapshot, PreparedBackupArchiveSource by prepared {
    private val closed = AtomicBoolean(false)

    override fun installCancellationCheck(check: () -> Unit) {
        (prepared as? CancellationAwarePreparedBackupSource)?.installCancellationCheck(check)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        var failure: Throwable? = null
        repeat(MAXIMUM_CLEANUP_ATTEMPTS) {
            try {
                if (cleanup()) return
            } catch (cleanupFailure: Throwable) {
                if (failure == null) failure = cleanupFailure else failure?.addSuppressed(cleanupFailure)
            }
        }
        closed.set(false)
        throw IOException("The private backup snapshot spool could not be removed.", failure)
    }

    override fun open(relativePath: String) =
        throw IllegalStateException("A prepared backup snapshot opens assets from its bounded index.")

    override fun folders(): Sequence<BackupFolderRecord> = emptySequence()

    override fun documents(): Sequence<BackupDocumentRecord> = emptySequence()

    override fun pages(): Sequence<BackupPageRecord> = emptySequence()

    override fun sourceAssets(): Sequence<BackupSourceAssetRecord> = emptySequence()

    override fun ocrDocumentStates(): Sequence<BackupOcrDocumentStateRecord> = emptySequence()

    override fun ocrPageStates(): Sequence<BackupOcrPageStateRecord> = emptySequence()

    override fun ocrArtifacts(): Sequence<BackupOcrArtifactRecord> = emptySequence()

    override fun ocrCorrections(): Sequence<BackupOcrCorrectionRecord> = emptySequence()

    override fun ocrLines(): Sequence<BackupOcrLineRecord> = emptySequence()
}

internal interface CancellationAwarePreparedBackupSource {
    fun installCancellationCheck(check: () -> Unit)
}

private const val MAXIMUM_CLEANUP_ATTEMPTS = 3

fun interface LibraryBackupSnapshotSource {
    suspend fun capture(): LibraryBackupSnapshot
}

enum class LibraryBackupSnapshotFailure {
    DATABASE_UNAVAILABLE,
    INCONSISTENT_REVISION,
    INVALID_RECORD,
    MISSING_ASSET,
    UNSUPPORTED_ASSET,
}

class LibraryBackupSnapshotException(
    val failure: LibraryBackupSnapshotFailure,
    message: String,
    cause: Throwable? = null,
    val cleanupSucceeded: Boolean = true,
) : Exception(message, cause)
