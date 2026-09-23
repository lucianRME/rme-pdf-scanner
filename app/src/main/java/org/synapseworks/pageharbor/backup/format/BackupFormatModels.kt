package org.synapseworks.pageharbor.backup.format

import java.io.InputStream
import java.io.OutputStream

const val RME_BACKUP_FORMAT_VERSION: Int = 1
const val RME_BACKUP_READER_VERSION: Int = 1

const val RME_BACKUP_MANIFEST_PATH: String = "manifest.json"
const val RME_BACKUP_FOLDERS_PATH: String = "metadata/folders.jsonl"
const val RME_BACKUP_DOCUMENTS_PATH: String = "metadata/documents.jsonl"
const val RME_BACKUP_PAGES_PATH: String = "metadata/pages.jsonl"
const val RME_BACKUP_SOURCE_ASSETS_PATH: String = "metadata/source-assets.jsonl"
const val RME_BACKUP_CHECKSUMS_PATH: String = "checksums.sha256"

data class BackupManifest(
    val formatVersion: Int,
    val minimumReaderVersion: Int,
    val requiredFeatures: List<String>,
    val backupId: String,
    val createdAtEpochMillis: Long,
    val producer: BackupProducer,
    val summary: BackupSummary,
    val metadata: BackupMetadataPaths,
    val integrity: BackupIntegrity,
)

data class BackupProducer(
    val applicationId: String,
    val versionName: String,
    val versionCode: Int,
)

data class BackupSummary(
    val folderCount: Int,
    val documentCount: Int,
    val pageCount: Int,
    val sourceAssetCount: Int,
    val contentByteLength: Long,
)

data class BackupMetadataPaths(
    val folders: String,
    val documents: String,
    val pages: String,
    val sourceAssets: String,
)

data class BackupIntegrity(
    val algorithm: String,
    val checksumsEntry: String,
)

data class BackupFolderRecord(
    val folderId: String,
    val name: String,
    val parentFolderId: String?,
    val createdAtEpochMillis: Long,
    val modifiedAtEpochMillis: Long,
)

data class BackupDocumentRecord(
    val documentId: String,
    val folderId: String?,
    val title: String,
    val createdAtEpochMillis: Long,
    val modifiedAtEpochMillis: Long,
    val contentHashVersion: Int,
    val contentSha256: String?,
    val pageCount: Int,
    val sourceAssetCount: Int,
)

data class BackupPageRecord(
    val pageId: String,
    val documentId: String,
    val position: Int,
    val relativePath: String,
    val mimeType: String,
    val sha256: String,
    val byteLength: Long,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val filterName: String,
    val ocrText: String?,
    val ocrError: String?,
    val sourcePageIndex: Int?,
)

data class BackupSourceAssetRecord(
    val sourceId: String,
    val documentId: String,
    val role: String,
    val relativePath: String,
    val mimeType: String,
    val sha256: String,
    val byteLength: Long,
    val sourceModifiedAtEpochMillis: Long?,
    val matchesCurrentRevision: Boolean,
)

/** Repeatable, scheduler-independent metadata source. Each sequence is consumed once per write. */
interface BackupRecordSource {
    fun folders(): Sequence<BackupFolderRecord>

    fun documents(): Sequence<BackupDocumentRecord>

    fun pages(): Sequence<BackupPageRecord>

    fun sourceAssets(): Sequence<BackupSourceAssetRecord>
}

class SnapshotBackupRecordSource(
    folders: Collection<BackupFolderRecord>,
    documents: Collection<BackupDocumentRecord>,
    pages: Collection<BackupPageRecord>,
    sourceAssets: Collection<BackupSourceAssetRecord>,
) : BackupRecordSource {
    private val folderSnapshot = folders.toList()
    private val documentSnapshot = documents.toList()
    private val pageSnapshot = pages.toList()
    private val sourceAssetSnapshot = sourceAssets.toList()

    override fun folders(): Sequence<BackupFolderRecord> = folderSnapshot.asSequence()

    override fun documents(): Sequence<BackupDocumentRecord> = documentSnapshot.asSequence()

    override fun pages(): Sequence<BackupPageRecord> = pageSnapshot.asSequence()

    override fun sourceAssets(): Sequence<BackupSourceAssetRecord> = sourceAssetSnapshot.asSequence()
}

/** Opens one declared page or source asset. The writer closes every returned stream. */
fun interface BackupAssetStreamOpener {
    fun open(relativePath: String): InputStream
}

/**
 * Receives streamed assets in an operation-owned private staging area.
 *
 * [abort] must remove every artifact created for this read. [verified] only marks that the complete
 * archive passed format verification; it must not activate or expose the staged library.
 */
interface BackupStagingSink {
    fun open(relativePath: String): OutputStream

    fun verified()

    fun abort()
}

data class VerifiedBackup(
    val manifest: BackupManifest,
    val folders: List<BackupFolderRecord>,
    val documents: List<BackupDocumentRecord>,
    val pages: List<BackupPageRecord>,
    val sourceAssets: List<BackupSourceAssetRecord>,
)

data class BackupWriteResult(
    val entryCount: Int,
    val contentByteLength: Long,
)
