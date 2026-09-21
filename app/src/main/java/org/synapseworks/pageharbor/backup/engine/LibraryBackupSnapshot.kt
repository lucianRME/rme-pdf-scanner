package org.synapseworks.pageharbor.backup.engine

import java.io.InputStream
import org.synapseworks.pageharbor.backup.format.BackupAssetStreamOpener
import org.synapseworks.pageharbor.backup.format.BackupDocumentRecord
import org.synapseworks.pageharbor.backup.format.BackupFolderRecord
import org.synapseworks.pageharbor.backup.format.BackupPageRecord
import org.synapseworks.pageharbor.backup.format.BackupRecordSource
import org.synapseworks.pageharbor.backup.format.BackupSourceAssetRecord

class LibraryBackupSnapshot(
    folders: Collection<BackupFolderRecord>,
    documents: Collection<BackupDocumentRecord>,
    pages: Collection<BackupPageRecord>,
    sourceAssets: Collection<BackupSourceAssetRecord>,
    private val assetStreams: BackupAssetStreamOpener,
) : BackupRecordSource, BackupAssetStreamOpener {
    val folderRecords: List<BackupFolderRecord> = folders.toList()
    val documentRecords: List<BackupDocumentRecord> = documents.toList()
    val pageRecords: List<BackupPageRecord> = pages.toList()
    val sourceAssetRecords: List<BackupSourceAssetRecord> = sourceAssets.toList()

    override fun folders(): Sequence<BackupFolderRecord> = folderRecords.asSequence()

    override fun documents(): Sequence<BackupDocumentRecord> = documentRecords.asSequence()

    override fun pages(): Sequence<BackupPageRecord> = pageRecords.asSequence()

    override fun sourceAssets(): Sequence<BackupSourceAssetRecord> = sourceAssetRecords.asSequence()

    override fun open(relativePath: String): InputStream = assetStreams.open(relativePath)
}

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
) : Exception(message, cause)
