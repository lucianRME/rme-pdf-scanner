package org.synapseworks.pageharbor.backup.format

import java.io.InputStream
import java.io.OutputStream

const val RME_BACKUP_FORMAT_VERSION_V1: Int = 1
const val RME_BACKUP_FORMAT_VERSION_V2: Int = 2
const val RME_BACKUP_FORMAT_VERSION: Int = RME_BACKUP_FORMAT_VERSION_V2
const val RME_BACKUP_READER_VERSION: Int = 2

const val RME_BACKUP_MANIFEST_PATH: String = "manifest.json"
const val RME_BACKUP_FOLDERS_PATH: String = "metadata/folders.jsonl"
const val RME_BACKUP_DOCUMENTS_PATH: String = "metadata/documents.jsonl"
const val RME_BACKUP_PAGES_PATH: String = "metadata/pages.jsonl"
const val RME_BACKUP_SOURCE_ASSETS_PATH: String = "metadata/source-assets.jsonl"
const val RME_BACKUP_OCR_DOCUMENT_STATES_PATH: String = "metadata/ocr-document-states.jsonl"
const val RME_BACKUP_OCR_PAGE_STATES_PATH: String = "metadata/ocr-page-states.jsonl"
const val RME_BACKUP_OCR_ARTIFACTS_PATH: String = "metadata/ocr-artifacts.jsonl"
const val RME_BACKUP_OCR_CORRECTIONS_PATH: String = "metadata/ocr-corrections.jsonl"
const val RME_BACKUP_OCR_LINES_DIRECTORY: String = "ocr-lines"
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
    val ocr: BackupOcrManifest? = null,
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

data class BackupOcrManifest(
    val documentStatesPath: String,
    val pageStatesPath: String,
    val artifactsPath: String,
    val correctionsPath: String,
    val documentStateCount: Int,
    val pageStateCount: Int,
    val artifactCount: Int,
    val correctionCount: Int,
    val lineCount: Int,
    val lineByteLength: Long,
    val lineChunks: List<BackupOcrLineChunkDescriptor>,
) {
    companion object {
        fun empty(): BackupOcrManifest = BackupOcrManifest(
            documentStatesPath = RME_BACKUP_OCR_DOCUMENT_STATES_PATH,
            pageStatesPath = RME_BACKUP_OCR_PAGE_STATES_PATH,
            artifactsPath = RME_BACKUP_OCR_ARTIFACTS_PATH,
            correctionsPath = RME_BACKUP_OCR_CORRECTIONS_PATH,
            documentStateCount = 0,
            pageStateCount = 0,
            artifactCount = 0,
            correctionCount = 0,
            lineCount = 0,
            lineByteLength = 0,
            lineChunks = emptyList(),
        )
    }
}

data class BackupOcrLineChunkDescriptor(
    val path: String,
    val recordCount: Int,
    val byteLength: Long,
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

    fun ocrDocumentStates(): Sequence<BackupOcrDocumentStateRecord> = emptySequence()

    fun ocrPageStates(): Sequence<BackupOcrPageStateRecord> = emptySequence()

    fun ocrArtifacts(): Sequence<BackupOcrArtifactRecord> = emptySequence()

    fun ocrCorrections(): Sequence<BackupOcrCorrectionRecord> = emptySequence()

    fun ocrLines(): Sequence<BackupOcrLineRecord> = emptySequence()
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

    /** Stores bounded v2 metadata chunks separately from document assets. */
    fun openMetadata(relativePath: String): OutputStream = open(relativePath)

    /** Reopens a verified metadata chunk. Verification-only sinks may return null. */
    fun openVerifiedMetadata(relativePath: String): InputStream? = null

    fun verified()

    fun abort()
}

data class VerifiedBackup(
    val manifest: BackupManifest,
    val folders: List<BackupFolderRecord>,
    val documents: List<BackupDocumentRecord>,
    val pages: List<BackupPageRecord>,
    val sourceAssets: List<BackupSourceAssetRecord>,
    val ocr: VerifiedBackupOcr? = null,
)

data class VerifiedBackupOcr(
    val documentStates: List<BackupOcrDocumentStateRecord>,
    val pageStates: List<BackupOcrPageStateRecord>,
    val artifacts: List<BackupOcrArtifactRecord>,
    val corrections: List<BackupOcrCorrectionRecord>,
    val lines: BackupOcrLineSource,
)

interface BackupOcrLineSource {
    val recordCount: Int

    fun records(): Sequence<BackupOcrLineRecord>
}

data class BackupWriteResult(
    val entryCount: Int,
    val contentByteLength: Long,
    val manifest: BackupManifest,
)
