package org.synapseworks.pageharbor.backup.format

/**
 * Disk-backed restore index populated while an archive is read. Implementations must not retain a
 * library-sized object graph: each accept call owns only that record, and every page method has a
 * caller-supplied upper bound.
 */
interface BackupIndexedStagingSink : BackupStagingSink, BackupVerifiedRecordStore {
    fun acceptManifest(manifest: BackupManifest)

    fun acceptFolder(ordinal: Int, record: BackupFolderRecord)

    fun acceptDocument(ordinal: Int, record: BackupDocumentRecord)

    fun acceptPage(ordinal: Int, record: BackupPageRecord)

    fun acceptSourceAsset(ordinal: Int, record: BackupSourceAssetRecord)

    fun acceptOcrDocumentState(ordinal: Int, record: BackupOcrDocumentStateRecord)

    fun acceptOcrPageState(ordinal: Int, record: BackupOcrPageStateRecord)

    fun acceptOcrArtifact(ordinal: Int, record: BackupOcrArtifactRecord)

    fun acceptOcrCorrection(ordinal: Int, record: BackupOcrCorrectionRecord)

    fun acceptOcrLine(ordinal: Int, record: BackupOcrLineRecord)

    fun acceptOcrLineChunk(ordinal: Int, descriptor: BackupOcrLineChunkDescriptor)

    fun acceptArchivePath(path: String)

    fun acceptObservedEntry(record: BackupObservedEntryRecord)

    fun acceptChecksum(record: BackupChecksum)

    /** Performs set-based cross-record verification and seals the index on success. */
    fun verifyIndexed(
        supportedRequiredFeatures: Set<String>,
        limits: BackupFormatLimits,
        checkCancellation: () -> Unit = {},
    ): IndexedVerifiedBackup
}

data class BackupObservedEntryRecord(
    val path: String,
    val sha256: String,
    val byteLength: Long,
    val inspection: BackupAssetInspectionSnapshot? = null,
)

data class BackupAssetInspectionSnapshot(
    val prefix: ByteArray,
    val jpegWidth: Int?,
    val jpegHeight: Int?,
) {
    override fun equals(other: Any?): Boolean = other is BackupAssetInspectionSnapshot &&
        prefix.contentEquals(other.prefix) && jpegWidth == other.jpegWidth && jpegHeight == other.jpegHeight

    override fun hashCode(): Int = 31 * (31 * prefix.contentHashCode() + (jpegWidth ?: 0)) + (jpegHeight ?: 0)
}

data class IndexedVerifiedBackup(
    val manifest: BackupManifest,
    val records: BackupVerifiedRecordStore,
)

/** Keyset-only access to a verified, immutable archive index. */
interface BackupVerifiedRecordStore {
    fun foldersPage(afterOrdinal: Int, limit: Int): List<BackupFolderRecord>

    fun documentsPage(afterOrdinal: Int, limit: Int): List<BackupDocumentRecord>

    fun pagesPage(documentId: String, afterPosition: Int, limit: Int): List<BackupPageRecord>

    fun sourceAssetsPage(documentId: String, afterSourceId: String?, limit: Int): List<BackupSourceAssetRecord>

    fun ocrDocumentState(documentId: String): BackupOcrDocumentStateRecord?

    fun ocrPageState(pageId: String): BackupOcrPageStateRecord?

    fun ocrArtifactsPage(pageId: String, afterRevision: Long, limit: Int): List<BackupOcrArtifactRecord>

    fun ocrArtifactCount(pageId: String): Int

    fun ocrCorrection(pageId: String): BackupOcrCorrectionRecord?

    fun ocrLinesPage(
        pageId: String,
        artifactRevision: Long,
        afterOrdinal: Int,
        limit: Int,
    ): List<BackupOcrLineRecord>

    fun totals(): BackupIndexedTotals
}

data class BackupIndexedTotals(
    val folderCount: Int,
    val documentCount: Int,
    val pageCount: Int,
    val sourceAssetCount: Int,
    val ocrDocumentStateCount: Int,
    val ocrPageStateCount: Int,
    val ocrArtifactCount: Int,
    val ocrCorrectionCount: Int,
    val ocrLineCount: Int,
    val metadataByteEstimate: Long,
    val ocrTextByteCount: Long,
)
