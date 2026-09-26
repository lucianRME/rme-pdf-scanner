package org.synapseworks.pageharbor.backup.restore

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import java.io.Closeable
import java.io.File
import java.nio.charset.StandardCharsets
import org.synapseworks.pageharbor.backup.format.BackupAssetInspection
import org.synapseworks.pageharbor.backup.format.BackupChecksum
import org.synapseworks.pageharbor.backup.format.BackupDocumentRecord
import org.synapseworks.pageharbor.backup.format.BackupFolderRecord
import org.synapseworks.pageharbor.backup.format.BackupFormatFailure
import org.synapseworks.pageharbor.backup.format.BackupFormatLimits
import org.synapseworks.pageharbor.backup.format.BackupFormatValidator
import org.synapseworks.pageharbor.backup.format.BackupIndexedTotals
import org.synapseworks.pageharbor.backup.format.BackupManifest
import org.synapseworks.pageharbor.backup.format.BackupObservedEntryRecord
import org.synapseworks.pageharbor.backup.format.BackupOcrArtifactRecord
import org.synapseworks.pageharbor.backup.format.BackupOcrCorrectionAlignment
import org.synapseworks.pageharbor.backup.format.BackupOcrCorrectionRecord
import org.synapseworks.pageharbor.backup.format.BackupOcrDocumentStateRecord
import org.synapseworks.pageharbor.backup.format.BackupOcrJsonCodec
import org.synapseworks.pageharbor.backup.format.BackupOcrLineChunkDescriptor
import org.synapseworks.pageharbor.backup.format.BackupOcrLineRecord
import org.synapseworks.pageharbor.backup.format.BackupOcrPageStateRecord
import org.synapseworks.pageharbor.backup.format.BackupOcrVerificationState
import org.synapseworks.pageharbor.backup.format.BackupPageRecord
import org.synapseworks.pageharbor.backup.format.BackupPathValidator
import org.synapseworks.pageharbor.backup.format.BackupSourceAssetRecord
import org.synapseworks.pageharbor.backup.format.BackupJsonCodec
import org.synapseworks.pageharbor.backup.format.BackupVerifiedRecordStore
import org.synapseworks.pageharbor.backup.format.IndexedVerifiedBackup
import org.synapseworks.pageharbor.backup.format.RME_BACKUP_CHECKSUMS_PATH
import org.synapseworks.pageharbor.backup.format.RME_BACKUP_DOCUMENTS_PATH
import org.synapseworks.pageharbor.backup.format.RME_BACKUP_FOLDERS_PATH
import org.synapseworks.pageharbor.backup.format.RME_BACKUP_FORMAT_VERSION_V1
import org.synapseworks.pageharbor.backup.format.RME_BACKUP_FORMAT_VERSION_V2
import org.synapseworks.pageharbor.backup.format.RME_BACKUP_MANIFEST_PATH
import org.synapseworks.pageharbor.backup.format.RME_BACKUP_OCR_ARTIFACTS_PATH
import org.synapseworks.pageharbor.backup.format.RME_BACKUP_OCR_CORRECTIONS_PATH
import org.synapseworks.pageharbor.backup.format.RME_BACKUP_OCR_DOCUMENT_STATES_PATH
import org.synapseworks.pageharbor.backup.format.RME_BACKUP_OCR_PAGE_STATES_PATH
import org.synapseworks.pageharbor.backup.format.RME_BACKUP_PAGES_PATH
import org.synapseworks.pageharbor.backup.format.RME_BACKUP_SOURCE_ASSETS_PATH
import org.synapseworks.pageharbor.backup.format.backupFailure
import org.synapseworks.pageharbor.library.duplicate.DocumentFingerprint
import org.synapseworks.pageharbor.library.duplicate.DuplicateKind
import org.synapseworks.pageharbor.library.duplicate.OcrStateDigest

private data class IndexedDocumentDatabaseRow(
    val ordinal: Int,
    val source: RestoreIndexedDocument,
    val kind: DuplicateKind?,
)

/** Disposable, operation-owned SQLite index for one untrusted restore archive. */
internal class RestoreStagingIndex(
    file: File,
) : BackupVerifiedRecordStore, Closeable {
    private val database = SQLiteDatabase.openOrCreateDatabase(file, null)
    private var manifest: BackupManifest? = null
    private var verifiedLimits: BackupFormatLimits? = null
    private var sealed = false
    private var pendingWrites = 0
    private var previousChecksumPath: String? = null
    private var checkCancellation: () -> Unit = {}
    private var folderPlanSeeded = false

    init {
        database.rawQuery("PRAGMA journal_mode=TRUNCATE", null).use { it.moveToFirst() }
        database.execSQL("PRAGMA synchronous=NORMAL")
        createSchema()
    }

    fun acceptManifest(value: BackupManifest) {
        requireWritable()
        if (manifest != null) duplicate("The backup contains more than one manifest.")
        manifest = value
    }

    fun acceptFolder(ordinal: Int, record: BackupFolderRecord) = insert(
        TABLE_FOLDERS,
        valuesOf(
            "ordinal" to ordinal,
            "record_id" to record.folderId,
            "parent_id" to record.parentFolderId,
            "json" to BackupJsonCodec.encodeFolder(record),
        ),
    )

    fun acceptDocument(ordinal: Int, record: BackupDocumentRecord) = insert(
        TABLE_DOCUMENTS,
        valuesOf(
            "ordinal" to ordinal,
            "record_id" to record.documentId,
            "folder_id" to record.folderId,
            "page_count" to record.pageCount,
            "source_count" to record.sourceAssetCount,
            "json" to BackupJsonCodec.encodeDocument(record),
        ),
    )

    fun acceptPage(ordinal: Int, record: BackupPageRecord) = insert(
        TABLE_PAGES,
        valuesOf(
            "ordinal" to ordinal,
            "record_id" to record.pageId,
            "document_id" to record.documentId,
            "page_position" to record.position,
            "relative_path" to record.relativePath,
            "sha256" to record.sha256,
            "byte_length" to record.byteLength,
            "ocr_text" to record.ocrText,
            "json" to BackupJsonCodec.encodePage(record),
        ),
    )

    fun acceptSourceAsset(ordinal: Int, record: BackupSourceAssetRecord) = insert(
        TABLE_SOURCES,
        valuesOf(
            "ordinal" to ordinal,
            "record_id" to record.sourceId,
            "document_id" to record.documentId,
            "relative_path" to record.relativePath,
            "sha256" to record.sha256,
            "byte_length" to record.byteLength,
            "json" to BackupJsonCodec.encodeSourceAsset(record),
        ),
    )

    fun acceptOcrDocumentState(ordinal: Int, record: BackupOcrDocumentStateRecord) = insert(
        TABLE_OCR_DOCUMENTS,
        valuesOf(
            "ordinal" to ordinal,
            "document_id" to record.documentId,
            "json" to BackupOcrJsonCodec.encodeDocumentState(record),
        ),
    )

    fun acceptOcrPageState(ordinal: Int, record: BackupOcrPageStateRecord) = insert(
        TABLE_OCR_PAGES,
        valuesOf(
            "ordinal" to ordinal,
            "page_id" to record.pageId,
            "active_revision" to record.activeArtifactRevision,
            "json" to BackupOcrJsonCodec.encodePageState(record),
        ),
    )

    fun acceptOcrArtifact(ordinal: Int, record: BackupOcrArtifactRecord) = insert(
        TABLE_OCR_ARTIFACTS,
        valuesOf(
            "ordinal" to ordinal,
            "page_id" to record.pageId,
            "artifact_revision" to record.artifactRevision,
            "line_count" to record.lineCount,
            "raw_text" to record.rawText,
            "json" to BackupOcrJsonCodec.encodeArtifact(record),
        ),
    )

    fun acceptOcrCorrection(ordinal: Int, record: BackupOcrCorrectionRecord) = insert(
        TABLE_OCR_CORRECTIONS,
        valuesOf(
            "ordinal" to ordinal,
            "page_id" to record.pageId,
            "base_revision" to record.baseArtifactRevision,
            "corrected_text" to record.correctedText,
            "json" to BackupOcrJsonCodec.encodeCorrection(record),
        ),
    )

    fun acceptOcrLine(ordinal: Int, record: BackupOcrLineRecord) = insert(
        TABLE_OCR_LINES,
        valuesOf(
            "ordinal" to ordinal,
            "page_id" to record.pageId,
            "artifact_revision" to record.artifactRevision,
            "line_ordinal" to record.lineOrdinal,
            "json" to BackupOcrJsonCodec.encodeLine(record),
        ),
    )

    fun acceptOcrLineChunk(ordinal: Int, descriptor: BackupOcrLineChunkDescriptor) = insert(
        TABLE_OCR_CHUNKS,
        valuesOf(
            "ordinal" to ordinal,
            "relative_path" to descriptor.path,
            "record_count" to descriptor.recordCount,
            "byte_length" to descriptor.byteLength,
        ),
    )

    fun acceptArchivePath(path: String) {
        BackupPathValidator.requireSupportedEntryPath(path)
        insert(
            TABLE_ARCHIVE_PATHS,
            valuesOf(
                "relative_path" to path,
                "canonical_path" to BackupPathValidator.canonicalCollisionKey(path),
            ),
            BackupFormatFailure.DUPLICATE_ENTRY,
        )
    }

    fun acceptObservedEntry(record: BackupObservedEntryRecord) {
        insert(
            TABLE_OBSERVED,
            valuesOf(
                "relative_path" to record.path,
                "sha256" to record.sha256,
                "byte_length" to record.byteLength,
                "inspection_prefix" to record.inspection?.prefix,
                "jpeg_width" to record.inspection?.jpegWidth,
                "jpeg_height" to record.inspection?.jpegHeight,
            ),
            BackupFormatFailure.DUPLICATE_ENTRY,
        )
    }

    fun acceptChecksum(record: BackupChecksum) {
        requireWritable()
        if (!SHA256.matches(record.sha256) || record.byteLength < 0L) invalidLedger()
        BackupPathValidator.requireSupportedEntryPath(record.path)
        if (record.path == RME_BACKUP_CHECKSUMS_PATH) invalidLedger()
        val previous = previousChecksumPath
        if (previous != null && previous >= record.path) invalidLedger()
        previousChecksumPath = record.path
        insert(
            TABLE_CHECKSUMS,
            valuesOf(
                "relative_path" to record.path,
                "canonical_path" to BackupPathValidator.canonicalCollisionKey(record.path),
                "sha256" to record.sha256,
                "byte_length" to record.byteLength,
            ),
            BackupFormatFailure.INVALID_LEDGER,
        )
    }

    fun verifyIndexed(
        supportedRequiredFeatures: Set<String>,
        limits: BackupFormatLimits,
        records: BackupVerifiedRecordStore,
        cancellationCheck: () -> Unit,
    ): IndexedVerifiedBackup {
        checkCancellation = cancellationCheck
        checkCancelled()
        flushWrites()
        val actualManifest = manifest ?: missing("The backup manifest is missing.")
        verifiedLimits = limits
        BackupFormatValidator.validateCompatibility(actualManifest, supportedRequiredFeatures, limits)
        verifyRequiredEntries(actualManifest)
        verifyLedger()
        verifyBaseMetadata(actualManifest, limits)
        verifyAssets()
        verifyOcr(actualManifest, limits)
        verifiedLimits = limits
        return IndexedVerifiedBackup(actualManifest, records)
    }

    fun seal() {
        check(verifiedLimits != null) { "The restore index has not been verified" }
        flushWrites()
        // Archive verification can run in a different coroutine Job from the later user-approved
        // restore. Never retain that completed Job's ensureActive closure across PreparedRestore.
        checkCancellation = {}
        sealed = true
    }

    override fun foldersPage(afterOrdinal: Int, limit: Int): List<BackupFolderRecord> = pageJson(
        "SELECT json FROM $TABLE_FOLDERS WHERE ordinal > ? ORDER BY ordinal LIMIT ?",
        arrayOf(afterOrdinal.toString(), boundedLimit(limit).toString()),
        BackupJsonCodec::decodeFolder,
    )

    override fun documentsPage(afterOrdinal: Int, limit: Int): List<BackupDocumentRecord> = pageJson(
        "SELECT json FROM $TABLE_DOCUMENTS WHERE ordinal > ? ORDER BY ordinal LIMIT ?",
        arrayOf(afterOrdinal.toString(), boundedLimit(limit).toString()),
        BackupJsonCodec::decodeDocument,
    )

    override fun pagesPage(documentId: String, afterPosition: Int, limit: Int): List<BackupPageRecord> = pageJson(
        """
        SELECT json FROM $TABLE_PAGES
        WHERE document_id = ? AND page_position > ?
        ORDER BY page_position LIMIT ?
        """.trimIndent(),
        arrayOf(documentId, afterPosition.toString(), boundedLimit(limit).toString()),
        BackupJsonCodec::decodePage,
    )

    override fun sourceAssetsPage(
        documentId: String,
        afterSourceId: String?,
        limit: Int,
    ): List<BackupSourceAssetRecord> = pageJson(
        """
        SELECT json FROM $TABLE_SOURCES
        WHERE document_id = ? AND record_id > ?
        ORDER BY record_id LIMIT ?
        """.trimIndent(),
        arrayOf(
            documentId,
            afterSourceId.orEmpty(),
            boundedLimit(limit).toString(),
        ),
        BackupJsonCodec::decodeSourceAsset,
    )

    override fun ocrDocumentState(documentId: String): BackupOcrDocumentStateRecord? = singleJson(
        "SELECT json FROM $TABLE_OCR_DOCUMENTS WHERE document_id = ?",
        arrayOf(documentId),
        BackupOcrJsonCodec::decodeDocumentState,
    )

    override fun ocrPageState(pageId: String): BackupOcrPageStateRecord? = singleJson(
        "SELECT json FROM $TABLE_OCR_PAGES WHERE page_id = ?",
        arrayOf(pageId),
        BackupOcrJsonCodec::decodePageState,
    )

    override fun ocrArtifactsPage(
        pageId: String,
        afterRevision: Long,
        limit: Int,
    ): List<BackupOcrArtifactRecord> = pageJson(
        """
        SELECT json FROM $TABLE_OCR_ARTIFACTS
        WHERE page_id = ? AND artifact_revision > ?
        ORDER BY artifact_revision LIMIT ?
        """.trimIndent(),
        arrayOf(pageId, afterRevision.toString(), boundedLimit(limit).toString()),
        BackupOcrJsonCodec::decodeArtifact,
    )

    override fun ocrArtifactCount(pageId: String): Int {
        requireReadable()
        return database.rawQuery(
            "SELECT COUNT(*) FROM $TABLE_OCR_ARTIFACTS WHERE page_id=?",
            arrayOf(pageId),
        ).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }
    }

    override fun ocrCorrection(pageId: String): BackupOcrCorrectionRecord? = singleJson(
        "SELECT json FROM $TABLE_OCR_CORRECTIONS WHERE page_id = ?",
        arrayOf(pageId),
        BackupOcrJsonCodec::decodeCorrection,
    )

    override fun ocrLinesPage(
        pageId: String,
        artifactRevision: Long,
        afterOrdinal: Int,
        limit: Int,
    ): List<BackupOcrLineRecord> = pageJson(
        """
        SELECT json FROM $TABLE_OCR_LINES
        WHERE page_id = ? AND artifact_revision = ? AND line_ordinal > ?
        ORDER BY line_ordinal LIMIT ?
        """.trimIndent(),
        arrayOf(
            pageId,
            artifactRevision.toString(),
            afterOrdinal.toString(),
            boundedLimit(limit).toString(),
        ),
        BackupOcrJsonCodec::decodeLine,
    )

    override fun totals(): BackupIndexedTotals {
        requireReadable()
        val metadataBytes = listOf(
            TABLE_FOLDERS,
            TABLE_DOCUMENTS,
            TABLE_PAGES,
            TABLE_SOURCES,
            TABLE_OCR_DOCUMENTS,
            TABLE_OCR_PAGES,
            TABLE_OCR_ARTIFACTS,
            TABLE_OCR_CORRECTIONS,
            TABLE_OCR_LINES,
        ).fold(0L) { total, table -> saturatingAdd(total, queryLong("SELECT COALESCE(SUM(length(CAST(json AS BLOB))), 0) FROM $table")) }
        return BackupIndexedTotals(
            folderCount = count(TABLE_FOLDERS),
            documentCount = count(TABLE_DOCUMENTS),
            pageCount = count(TABLE_PAGES),
            sourceAssetCount = count(TABLE_SOURCES),
            ocrDocumentStateCount = count(TABLE_OCR_DOCUMENTS),
            ocrPageStateCount = count(TABLE_OCR_PAGES),
            ocrArtifactCount = count(TABLE_OCR_ARTIFACTS),
            ocrCorrectionCount = count(TABLE_OCR_CORRECTIONS),
            ocrLineCount = count(TABLE_OCR_LINES),
            metadataByteEstimate = metadataBytes,
            ocrTextByteCount = saturatingAdd(
                queryLong("SELECT COALESCE(SUM(length(CAST(raw_text AS BLOB))), 0) FROM $TABLE_OCR_ARTIFACTS"),
                queryLong("SELECT COALESCE(SUM(length(CAST(corrected_text AS BLOB))), 0) FROM $TABLE_OCR_CORRECTIONS"),
            ),
        )
    }

    fun unplannedReadyFoldersPage(limit: Int): List<BackupFolderRecord> {
        requireReadable()
        if (!folderPlanSeeded) {
            beginWritesIfNeeded()
            database.execSQL(
                "UPDATE $TABLE_FOLDERS SET plan_ready=1 WHERE parent_id IS NULL AND target_id IS NULL",
            )
            folderPlanSeeded = true
            pendingWrites += 1
            flushWrites()
        }
        return pageJson(
            "SELECT json FROM $TABLE_FOLDERS WHERE plan_ready=1 ORDER BY ordinal LIMIT ?",
            arrayOf(boundedLimit(limit).toString()),
            BackupJsonCodec::decodeFolder,
        )
    }

    fun targetFolderId(originalFolderId: String): String? {
        requireReadable()
        return database.rawQuery(
            "SELECT target_id FROM $TABLE_FOLDERS WHERE record_id=? AND target_id IS NOT NULL",
            arrayOf(originalFolderId),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }

    fun targetFolderSearchPath(originalFolderId: String): String? {
        requireReadable()
        return database.rawQuery(
            """
            WITH RECURSIVE ancestors(
              target_id, target_parent_id, planned_name, depth, expected_path_characters
            ) AS (
              SELECT target_id, target_parent_id, planned_name, 0, planned_path_characters
              FROM $TABLE_FOLDERS
              WHERE record_id=? AND target_id IS NOT NULL AND planned_name IS NOT NULL
                AND planned_path_characters IS NOT NULL
              UNION ALL
              SELECT parent.target_id, parent.target_parent_id, parent.planned_name, child.depth + 1,
                     child.expected_path_characters
              FROM $TABLE_FOLDERS parent
              JOIN ancestors child ON parent.target_id=child.target_parent_id
              WHERE parent.target_id IS NOT NULL AND parent.planned_name IS NOT NULL
            )
            SELECT target_id, target_parent_id, planned_name, depth, expected_path_characters
            FROM ancestors
            """.trimIndent(),
            arrayOf(originalFolderId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val builder = RestoreFolderSearchPathBuilder()
            val expectedCharacters = cursor.getInt(4)
            do {
                check(cursor.getInt(4) == expectedCharacters)
                builder.add(
                    targetId = cursor.getString(0),
                    targetParentId = cursor.getString(1),
                    name = cursor.getString(2),
                    depthFromLeaf = cursor.getInt(3),
                )
            } while (cursor.moveToNext())
            builder.build(expectedCharacters)
        }
    }

    fun plannedFolderNameExists(targetParentFolderId: String?, normalizedName: String): Boolean {
        requireReadable()
        val parentPredicate = if (targetParentFolderId == null) {
            "target_parent_id IS NULL"
        } else {
            "target_parent_id=?"
        }
        val args = if (targetParentFolderId == null) {
            arrayOf(normalizedName)
        } else {
            arrayOf(targetParentFolderId, normalizedName)
        }
        return exists(
            "SELECT 1 FROM $TABLE_FOLDERS WHERE $parentPredicate AND normalized_name=? LIMIT 1",
            args,
        )
    }

    fun recordPlannedFolder(folder: RestoreFolderToCreate) {
        requireReadable()
        beginWritesIfNeeded()
        val parentPathCharacters = folder.parentFolderId?.let { parentId ->
            database.rawQuery(
                "SELECT planned_path_characters FROM $TABLE_FOLDERS WHERE target_id=?",
                arrayOf(parentId),
            ).use { cursor ->
                check(cursor.moveToFirst() && !cursor.isNull(0)) { "A restore folder parent is not planned" }
                cursor.getInt(0)
            }
        }
        val pathCharacters = restoreFolderSearchPathCharacters(parentPathCharacters, folder.name)
        val updated = database.update(
            TABLE_FOLDERS,
            valuesOf(
                "target_id" to folder.folderId,
                "target_parent_id" to folder.parentFolderId,
                "planned_name" to folder.name,
                "normalized_name" to folder.normalizedName,
                "planned_path_characters" to pathCharacters,
                "plan_ready" to 0,
            ),
            "record_id=? AND target_id IS NULL",
            arrayOf(folder.originalFolderId),
        )
        check(updated == 1) { "A restore folder was planned more than once" }
        database.execSQL(
            "UPDATE $TABLE_FOLDERS SET plan_ready=1 WHERE parent_id=? AND target_id IS NULL",
            arrayOf(folder.originalFolderId),
        )
        pendingWrites += 1
        flushWrites()
    }

    fun plannedFolderCount(): Int = queryLong(
        "SELECT COUNT(*) FROM $TABLE_FOLDERS WHERE target_id IS NOT NULL",
    ).toInt()

    fun plannedFoldersPage(afterOrdinal: Int, limit: Int): List<RestoreFolderPlanRow> {
        requireReadable()
        val result = ArrayList<RestoreFolderPlanRow>()
        database.rawQuery(
            """
            SELECT ordinal, target_id, record_id, planned_name, normalized_name, target_parent_id, json
            FROM $TABLE_FOLDERS
            WHERE ordinal > ? AND target_id IS NOT NULL
            ORDER BY ordinal LIMIT ?
            """.trimIndent(),
            arrayOf(afterOrdinal.toString(), boundedLimit(limit).toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val original = BackupJsonCodec.decodeFolder(cursor.getString(6), requireLimitsForVerification())
                result += RestoreFolderPlanRow(
                    ordinal = cursor.getInt(0),
                    folder = RestoreFolderToCreate(
                        folderId = cursor.getString(1),
                        originalFolderId = cursor.getString(2),
                        name = cursor.getString(3),
                        normalizedName = cursor.getString(4),
                        parentFolderId = if (cursor.isNull(5)) null else cursor.getString(5),
                        createdAtEpochMillis = original.createdAtEpochMillis,
                        modifiedAtEpochMillis = original.modifiedAtEpochMillis,
                    ),
                )
            }
        }
        return result
    }

    fun recordDocumentIdentity(source: RestoreIndexedDocument) {
        requireReadable()
        beginWritesIfNeeded()
        val updated = database.update(
            TABLE_DOCUMENTS,
            valuesOf(
                "fingerprint_version" to source.fingerprint.version,
                "fingerprint_sha256" to source.fingerprint.sha256,
                "ocr_digest_version" to source.ocrStateDigest.version,
                "ocr_digest_sha256" to source.ocrStateDigest.sha256,
                "page_bytes" to source.pageByteLength,
                "source_bytes" to source.sourceAssetByteLength,
                "source_modified_at" to source.firstSourceModifiedAtEpochMillis,
            ),
            "record_id=? AND fingerprint_sha256 IS NULL",
            arrayOf(source.document.documentId),
        )
        check(updated == 1) { "A restore document identity was recorded more than once" }
        pendingWrites += 1
        flushWrites()
    }

    fun documentIdentitiesPage(afterOrdinal: Int, limit: Int): List<RestoreIndexedDocumentRow> =
        documentIdentityRows(afterOrdinal, limit, requireDecision = false).map { row ->
            RestoreIndexedDocumentRow(row.ordinal, row.source)
        }

    fun recordRestoreDecision(documentId: String, duplicateKind: DuplicateKind) {
        requireReadable()
        beginWritesIfNeeded()
        val updated = database.update(
            TABLE_DOCUMENTS,
            valuesOf("duplicate_kind" to duplicateKind.name),
            "record_id=? AND fingerprint_sha256 IS NOT NULL AND duplicate_kind IS NULL",
            arrayOf(documentId),
        )
        check(updated == 1) { "A restore decision was recorded more than once" }
        pendingWrites += 1
        flushWrites()
    }

    fun restorePlanPage(afterOrdinal: Int, limit: Int): List<RestorePlannedDocumentRow> =
        documentIdentityRows(afterOrdinal, limit, requireDecision = true).map { row ->
            RestorePlannedDocumentRow(
                row.ordinal,
                RestorePlannedDocument(row.source, requireNotNull(row.kind)),
            )
        }

    private fun documentIdentityRows(
        afterOrdinal: Int,
        limit: Int,
        requireDecision: Boolean,
    ): List<IndexedDocumentDatabaseRow> {
        requireReadable()
        val decisionPredicate = if (requireDecision) "AND duplicate_kind IS NOT NULL" else ""
        val result = ArrayList<IndexedDocumentDatabaseRow>()
        database.rawQuery(
            """
            SELECT ordinal, json, fingerprint_version, fingerprint_sha256,
                   ocr_digest_version, ocr_digest_sha256, page_bytes, source_bytes,
                   source_modified_at, duplicate_kind
            FROM $TABLE_DOCUMENTS
            WHERE ordinal > ? AND fingerprint_sha256 IS NOT NULL $decisionPredicate
            ORDER BY ordinal LIMIT ?
            """.trimIndent(),
            arrayOf(afterOrdinal.toString(), boundedLimit(limit).toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val document = BackupJsonCodec.decodeDocument(
                    cursor.getString(1),
                    requireLimitsForVerification(),
                )
                val source = RestoreIndexedDocument(
                    document = document,
                    fingerprint = DocumentFingerprint(cursor.getInt(2), cursor.getString(3)),
                    ocrStateDigest = OcrStateDigest(cursor.getInt(4), cursor.getString(5)),
                    pageByteLength = cursor.getLong(6),
                    sourceAssetByteLength = cursor.getLong(7),
                    firstSourceModifiedAtEpochMillis = if (cursor.isNull(8)) null else cursor.getLong(8),
                )
                result += IndexedDocumentDatabaseRow(
                    ordinal = cursor.getInt(0),
                    source = source,
                    kind = if (cursor.isNull(9)) null else DuplicateKind.valueOf(cursor.getString(9)),
                )
            }
        }
        return result
    }

    override fun close() {
        if (!database.isOpen) return
        if (database.inTransaction()) database.endTransaction()
        database.close()
    }

    private fun verifyRequiredEntries(manifest: BackupManifest) {
        val required = when (manifest.formatVersion) {
            RME_BACKUP_FORMAT_VERSION_V1 -> BASE_REQUIRED_ENTRIES
            RME_BACKUP_FORMAT_VERSION_V2 -> BASE_REQUIRED_ENTRIES + setOf(
                RME_BACKUP_OCR_DOCUMENT_STATES_PATH,
                RME_BACKUP_OCR_PAGE_STATES_PATH,
                RME_BACKUP_OCR_ARTIFACTS_PATH,
                RME_BACKUP_OCR_CORRECTIONS_PATH,
            ) + requireNotNull(manifest.ocr).lineChunks.map(BackupOcrLineChunkDescriptor::path)
            else -> emptySet()
        }
        required.forEach { path ->
            checkCancelled()
            if (!exists("SELECT 1 FROM $TABLE_OBSERVED WHERE relative_path = ? LIMIT 1", arrayOf(path))) {
                missing("A required metadata entry is missing.")
            }
        }
    }

    private fun verifyLedger() {
        if (count(TABLE_CHECKSUMS) == 0) invalidLedger()
        if (exists(
                """
                SELECT 1 FROM $TABLE_CHECKSUMS c
                LEFT JOIN $TABLE_OBSERVED o ON o.relative_path = c.relative_path
                WHERE o.relative_path IS NULL LIMIT 1
                """.trimIndent(),
            ) || exists(
                """
                SELECT 1 FROM $TABLE_OBSERVED o
                LEFT JOIN $TABLE_CHECKSUMS c ON c.relative_path = o.relative_path
                WHERE c.relative_path IS NULL LIMIT 1
                """.trimIndent(),
            )
        ) {
            invalidLedger()
        }
        if (exists(
                """
                SELECT 1 FROM $TABLE_CHECKSUMS c JOIN $TABLE_OBSERVED o USING(relative_path)
                WHERE c.byte_length != o.byte_length LIMIT 1
                """.trimIndent(),
            )
        ) {
            throw backupFailure(BackupFormatFailure.SIZE_MISMATCH, "A ZIP entry does not match its ledger byte length.")
        }
        if (exists(
                """
                SELECT 1 FROM $TABLE_CHECKSUMS c JOIN $TABLE_OBSERVED o USING(relative_path)
                WHERE c.sha256 != o.sha256 LIMIT 1
                """.trimIndent(),
            )
        ) {
            throw backupFailure(BackupFormatFailure.HASH_MISMATCH, "A ZIP entry does not match its ledger SHA-256.")
        }
    }

    private fun verifyBaseMetadata(manifest: BackupManifest, limits: BackupFormatLimits) {
        scanJson(TABLE_FOLDERS, BackupJsonCodec::decodeFolder) { record: BackupFolderRecord ->
            stableId(record.folderId, "folderId")
            record.parentFolderId?.let { stableId(it, "parentFolderId") }
            boundedText(record.name, 80)
            timestamps(record.createdAtEpochMillis, record.modifiedAtEpochMillis)
        }
        scanJson(TABLE_DOCUMENTS, BackupJsonCodec::decodeDocument) { record: BackupDocumentRecord ->
            stableId(record.documentId, "documentId")
            record.folderId?.let { stableId(it, "folderId") }
            boundedText(record.title, 120)
            timestamps(record.createdAtEpochMillis, record.modifiedAtEpochMillis)
            if (record.contentHashVersion <= 0 || record.pageCount < 0 || record.sourceAssetCount < 0) invalidMetadata()
            record.contentSha256?.let(::sha256)
        }
        scanJson(TABLE_PAGES, BackupJsonCodec::decodePage) { record: BackupPageRecord ->
            stableId(record.pageId, "pageId")
            stableId(record.documentId, "documentId")
            if (record.position < 0 || record.width <= 0 || record.height <= 0 ||
                record.rotationDegrees !in VALID_ROTATIONS || record.filterName !in SUPPORTED_FILTERS ||
                record.byteLength !in 1..limits.maximumSingleAssetBytes || record.sourcePageIndex?.let { it < 0 } == true
            ) invalidMetadata()
            if (record.mimeType !in PAGE_MIME_TYPES) invalidMetadata()
            sha256(record.sha256)
            BackupPathValidator.requirePageAssetPath(record)
        }
        scanJson(TABLE_SOURCES, BackupJsonCodec::decodeSourceAsset) { record: BackupSourceAssetRecord ->
            stableId(record.sourceId, "sourceId")
            stableId(record.documentId, "documentId")
            if (record.role != "ORIGINAL_DOCUMENT" || record.mimeType != "application/pdf" ||
                record.byteLength !in 1..limits.maximumSingleAssetBytes ||
                record.sourceModifiedAtEpochMillis?.let { it < 0L } == true
            ) invalidMetadata()
            sha256(record.sha256)
            BackupPathValidator.requireSourceAssetPath(record)
        }

        if (exists("SELECT 1 FROM $TABLE_FOLDERS f LEFT JOIN $TABLE_FOLDERS p ON p.record_id=f.parent_id WHERE f.parent_id IS NOT NULL AND (p.record_id IS NULL OR p.record_id=f.record_id) LIMIT 1")) relationship()
        if (exists(
                """
                WITH RECURSIVE reachable(record_id) AS (
                  SELECT record_id FROM $TABLE_FOLDERS WHERE parent_id IS NULL
                  UNION ALL
                  SELECT child.record_id
                  FROM $TABLE_FOLDERS child
                  JOIN reachable parent ON child.parent_id = parent.record_id
                )
                SELECT 1
                WHERE (SELECT COUNT(*) FROM reachable) != (SELECT COUNT(*) FROM $TABLE_FOLDERS)
                LIMIT 1
                """.trimIndent(),
            )
        ) relationship()
        if (exists("SELECT 1 FROM $TABLE_DOCUMENTS d LEFT JOIN $TABLE_FOLDERS f ON f.record_id=d.folder_id WHERE d.folder_id IS NOT NULL AND f.record_id IS NULL LIMIT 1")) relationship()
        if (exists("SELECT 1 FROM $TABLE_PAGES p LEFT JOIN $TABLE_DOCUMENTS d ON d.record_id=p.document_id WHERE d.record_id IS NULL LIMIT 1")) relationship()
        if (exists("SELECT 1 FROM $TABLE_SOURCES s LEFT JOIN $TABLE_DOCUMENTS d ON d.record_id=s.document_id WHERE d.record_id IS NULL LIMIT 1")) relationship()
        if (exists(
                """
                SELECT 1 FROM $TABLE_DOCUMENTS d
                WHERE (SELECT COUNT(*) FROM $TABLE_PAGES p WHERE p.document_id=d.record_id) != d.page_count OR
                      (d.page_count > 0 AND
                       ((SELECT MIN(p.page_position) FROM $TABLE_PAGES p WHERE p.document_id=d.record_id) != 0 OR
                        (SELECT MAX(p.page_position) FROM $TABLE_PAGES p WHERE p.document_id=d.record_id) !=
                            d.page_count - 1))
                LIMIT 1
                """.trimIndent(),
            )
        ) relationship()
        if (exists(
                """
                SELECT 1 FROM $TABLE_DOCUMENTS d
                WHERE (SELECT COUNT(*) FROM $TABLE_SOURCES s WHERE s.document_id=d.record_id) != d.source_count
                LIMIT 1
                """.trimIndent(),
            )
        ) relationship()
        if (manifest.summary.folderCount != count(TABLE_FOLDERS) ||
            manifest.summary.documentCount != count(TABLE_DOCUMENTS) ||
            manifest.summary.pageCount != count(TABLE_PAGES) ||
            manifest.summary.sourceAssetCount != count(TABLE_SOURCES) ||
            manifest.summary.contentByteLength != queryLong(
                "SELECT COALESCE((SELECT SUM(byte_length) FROM $TABLE_PAGES),0) + COALESCE((SELECT SUM(byte_length) FROM $TABLE_SOURCES),0)",
            )
        ) relationship()
    }

    private fun verifyAssets() {
        if (exists(
                """
                SELECT 1 FROM $TABLE_OBSERVED o
                WHERE o.relative_path LIKE 'documents/%'
                  AND NOT EXISTS(SELECT 1 FROM $TABLE_PAGES p WHERE p.relative_path=o.relative_path)
                  AND NOT EXISTS(SELECT 1 FROM $TABLE_SOURCES s WHERE s.relative_path=o.relative_path)
                LIMIT 1
                """.trimIndent(),
            )
        ) {
            throw backupFailure(BackupFormatFailure.UNDECLARED_ENTRY, "The backup contains an asset not declared by metadata.")
        }
        if (exists(
                """
                SELECT 1 FROM (
                  SELECT relative_path FROM $TABLE_PAGES UNION ALL SELECT relative_path FROM $TABLE_SOURCES
                ) declared LEFT JOIN $TABLE_OBSERVED o USING(relative_path)
                WHERE o.relative_path IS NULL LIMIT 1
                """.trimIndent(),
            )
        ) missing("A declared asset is missing from the backup.")

        database.rawQuery(
            """
            SELECT 'PAGE', p.json, o.sha256, o.byte_length, o.inspection_prefix, o.jpeg_width, o.jpeg_height
            FROM $TABLE_PAGES p JOIN $TABLE_OBSERVED o ON o.relative_path=p.relative_path
            UNION ALL
            SELECT 'SOURCE', s.json, o.sha256, o.byte_length, o.inspection_prefix, o.jpeg_width, o.jpeg_height
            FROM $TABLE_SOURCES s JOIN $TABLE_OBSERVED o ON o.relative_path=s.relative_path
            """.trimIndent(),
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                checkCancelled()
                val kind = cursor.getString(0)
                val json = cursor.getString(1)
                val declaredSha: String
                val declaredLength: Long
                val mimeType: String
                val width: Int?
                val height: Int?
                if (kind == "PAGE") {
                    val page = BackupJsonCodec.decodePage(json, requireLimitsForVerification())
                    declaredSha = page.sha256
                    declaredLength = page.byteLength
                    mimeType = page.mimeType
                    width = page.width
                    height = page.height
                } else {
                    val source = BackupJsonCodec.decodeSourceAsset(json, requireLimitsForVerification())
                    declaredSha = source.sha256
                    declaredLength = source.byteLength
                    mimeType = source.mimeType
                    width = null
                    height = null
                }
                if (declaredLength != cursor.getLong(3)) {
                    throw backupFailure(BackupFormatFailure.SIZE_MISMATCH, "An asset does not match its metadata byte length.")
                }
                if (declaredSha != cursor.getString(2)) {
                    throw backupFailure(BackupFormatFailure.HASH_MISMATCH, "An asset does not match its metadata SHA-256.")
                }
                val prefix = cursor.getBlob(4) ?: ByteArray(0)
                val snapshot = org.synapseworks.pageharbor.backup.format.BackupAssetInspectionSnapshot(
                    prefix,
                    cursor.nullableInt(5),
                    cursor.nullableInt(6),
                )
                BackupAssetInspection.fromSnapshot(snapshot).requireMatches(mimeType, width, height)
            }
        }
    }

    private fun verifyOcr(manifest: BackupManifest, limits: BackupFormatLimits) {
        verifiedLimits = limits
        if (manifest.formatVersion == RME_BACKUP_FORMAT_VERSION_V1) {
            if (listOf(
                    TABLE_OCR_DOCUMENTS,
                    TABLE_OCR_PAGES,
                    TABLE_OCR_ARTIFACTS,
                    TABLE_OCR_CORRECTIONS,
                    TABLE_OCR_LINES,
                    TABLE_OCR_CHUNKS,
                ).any { count(it) != 0 }
            ) {
                throw backupFailure(BackupFormatFailure.UNDECLARED_ENTRY, "A v1 backup contains v2 OCR metadata.")
            }
            return
        }
        val ocr = requireNotNull(manifest.ocr)
        if (ocr.documentStateCount != count(TABLE_OCR_DOCUMENTS) ||
            ocr.pageStateCount != count(TABLE_OCR_PAGES) ||
            ocr.artifactCount != count(TABLE_OCR_ARTIFACTS) ||
            ocr.correctionCount != count(TABLE_OCR_CORRECTIONS) ||
            ocr.lineCount != count(TABLE_OCR_LINES) ||
            ocr.lineCount != queryLong("SELECT COALESCE(SUM(record_count),0) FROM $TABLE_OCR_CHUNKS").toInt() ||
            ocr.lineByteLength != queryLong("SELECT COALESCE(SUM(byte_length),0) FROM $TABLE_OCR_CHUNKS")
        ) relationship()
        verifyChunkDescriptors(ocr.lineChunks)
        if (exists("SELECT 1 FROM $TABLE_DOCUMENTS d LEFT JOIN $TABLE_OCR_DOCUMENTS o ON o.document_id=d.record_id WHERE o.document_id IS NULL LIMIT 1") ||
            exists("SELECT 1 FROM $TABLE_OCR_DOCUMENTS o LEFT JOIN $TABLE_DOCUMENTS d ON d.record_id=o.document_id WHERE d.record_id IS NULL LIMIT 1") ||
            exists("SELECT 1 FROM $TABLE_PAGES p LEFT JOIN $TABLE_OCR_PAGES o ON o.page_id=p.record_id WHERE o.page_id IS NULL LIMIT 1") ||
            exists("SELECT 1 FROM $TABLE_OCR_PAGES o LEFT JOIN $TABLE_PAGES p ON p.record_id=o.page_id WHERE p.record_id IS NULL LIMIT 1")
        ) relationship()

        scanJson(TABLE_OCR_DOCUMENTS, BackupOcrJsonCodec::decodeDocumentState) { state: BackupOcrDocumentStateRecord ->
            stableId(state.documentId, "documentId")
            if (state.contentRevision < 0L || state.scriptPreference?.let { it !in SCRIPT_PREFERENCES } == true) invalidMetadata()
        }
        scanJson(TABLE_OCR_PAGES, BackupOcrJsonCodec::decodePageState) { state: BackupOcrPageStateRecord ->
            stableId(state.pageId, "pageId")
            if (state.visualRevision < 0L || state.ocrStateRevision < 0L ||
                state.activeArtifactRevision?.let { it <= 0L } == true
            ) invalidMetadata()
        }
        scanJson(TABLE_OCR_ARTIFACTS, BackupOcrJsonCodec::decodeArtifact) { artifact: BackupOcrArtifactRecord ->
            validateArtifact(artifact, limits)
        }
        if (exists("SELECT 1 FROM $TABLE_OCR_ARTIFACTS a LEFT JOIN $TABLE_PAGES p ON p.record_id=a.page_id WHERE p.record_id IS NULL LIMIT 1")) relationship()
        if (exists(
                """
                SELECT 1 FROM $TABLE_OCR_PAGES s
                LEFT JOIN $TABLE_OCR_ARTIFACTS a
                  ON a.page_id=s.page_id AND a.artifact_revision=s.active_revision
                JOIN $TABLE_PAGES p ON p.record_id=s.page_id
                WHERE (s.active_revision IS NULL AND p.ocr_text IS NOT NULL) OR
                      (s.active_revision IS NOT NULL AND (a.page_id IS NULL OR a.raw_text != p.ocr_text))
                LIMIT 1
                """.trimIndent(),
            )
        ) relationship()
        verifyActiveArtifactCurrentness(limits)
        verifyCorrections(limits)
        verifyLines(limits)
    }

    private fun verifyChunkDescriptors(expected: List<BackupOcrLineChunkDescriptor>) {
        database.rawQuery(
            "SELECT relative_path, record_count, byte_length FROM $TABLE_OCR_CHUNKS ORDER BY ordinal",
            null,
        ).use { cursor ->
            var index = 0
            while (cursor.moveToNext()) {
                checkCancelled()
                if (index >= expected.size) relationship()
                val descriptor = expected[index++]
                if (descriptor.path != cursor.getString(0) || descriptor.recordCount != cursor.getInt(1) ||
                    descriptor.byteLength != cursor.getLong(2)
                ) relationship()
            }
            if (index != expected.size) relationship()
        }
    }

    private fun verifyActiveArtifactCurrentness(limits: BackupFormatLimits) {
        database.rawQuery(
            """
            SELECT a.json, s.json, p.json FROM $TABLE_OCR_PAGES s
            JOIN $TABLE_OCR_ARTIFACTS a ON a.page_id=s.page_id AND a.artifact_revision=s.active_revision
            JOIN $TABLE_PAGES p ON p.record_id=s.page_id
            WHERE s.active_revision IS NOT NULL
            """.trimIndent(),
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                checkCancelled()
                val artifact = BackupOcrJsonCodec.decodeArtifact(cursor.getString(0), limits)
                if (artifact.verificationState != BackupOcrVerificationState.CURRENT_VERIFIED) continue
                val state = BackupOcrJsonCodec.decodePageState(cursor.getString(1), limits)
                val page = BackupJsonCodec.decodePage(cursor.getString(2), limits)
                val fingerprint = artifact.inputFingerprint ?: relationship()
                if (artifact.capturedPageVisualRevision != state.visualRevision ||
                    fingerprint.visualRevision != state.visualRevision || fingerprint.contentSha256 != page.sha256 ||
                    fingerprint.rotationDegrees != page.rotationDegrees || fingerprint.filterName != page.filterName
                ) relationship()
            }
        }
    }

    private fun verifyCorrections(limits: BackupFormatLimits) {
        database.rawQuery(
            """
            SELECT c.json, a.line_count, s.json FROM $TABLE_OCR_CORRECTIONS c
            LEFT JOIN $TABLE_OCR_ARTIFACTS a
              ON a.page_id=c.page_id AND a.artifact_revision=c.base_revision
            LEFT JOIN $TABLE_OCR_PAGES s ON s.page_id=c.page_id
            """.trimIndent(),
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                checkCancelled()
                if (cursor.isNull(1) || cursor.isNull(2)) relationship()
                val correction = BackupOcrJsonCodec.decodeCorrection(cursor.getString(0), limits)
                stableId(correction.pageId, "pageId")
                if (correction.correctedAtEpochMillis < 0L || correction.correctedText.length > limits.maximumStringCharacters ||
                    correction.correctedText.any { it == '\u0000' }
                ) invalidMetadata()
                val state = BackupOcrJsonCodec.decodePageState(cursor.getString(2), limits)
                if (state.ocrStateRevision <= 0L) relationship()
                when (correction.alignment) {
                    BackupOcrCorrectionAlignment.FREEFORM -> if (correction.lineCorrections.isNotEmpty()) relationship()
                    BackupOcrCorrectionAlignment.LINE_ALIGNED -> {
                        val expectedCount = cursor.getInt(1)
                        if (correction.lineCorrections.size != expectedCount ||
                            correction.lineCorrections.map { it.lineOrdinal } != correction.lineCorrections.indices.toList() ||
                            correction.lineCorrections.joinToString("\n") { it.correctedText } != correction.correctedText
                        ) relationship()
                        if (correction.lineCorrections.any {
                                it.correctedText.length > limits.maximumStringCharacters || it.correctedText.any { c -> c == '\u0000' }
                            }
                        ) invalidMetadata()
                    }
                }
            }
        }
    }

    private fun verifyLines(limits: BackupFormatLimits) {
        if (exists(
                """
                SELECT 1 FROM $TABLE_OCR_ARTIFACTS a
                WHERE a.line_count > 0 AND NOT EXISTS (
                  SELECT 1 FROM $TABLE_OCR_LINES l
                  WHERE l.page_id=a.page_id AND l.artifact_revision=a.artifact_revision
                ) LIMIT 1
                """.trimIndent(),
            ) || exists(
                """
                SELECT 1 FROM $TABLE_OCR_LINES l LEFT JOIN $TABLE_OCR_ARTIFACTS a
                  ON a.page_id=l.page_id AND a.artifact_revision=l.artifact_revision
                WHERE a.page_id IS NULL LIMIT 1
                """.trimIndent(),
            )
        ) relationship()

        database.rawQuery(
            """
            SELECT l.json, d.ordinal, p.page_position, l.artifact_revision, l.line_ordinal,
                   a.ordinal, a.line_count
            FROM $TABLE_OCR_LINES l
            JOIN $TABLE_PAGES p ON p.record_id=l.page_id
            JOIN $TABLE_DOCUMENTS d ON d.record_id=p.document_id
            JOIN $TABLE_OCR_ARTIFACTS a
              ON a.page_id=l.page_id AND a.artifact_revision=l.artifact_revision
            ORDER BY l.ordinal
            """.trimIndent(),
            null,
        ).use { cursor ->
            var previousDocument = -1
            var previousPage = -1
            var previousArtifact = -1L
            var previousLine = -1
            var currentArtifactOrdinal = -1
            var currentArtifactLineCount = 0
            var expectedArtifactLineCount = 0
            while (cursor.moveToNext()) {
                checkCancelled()
                val line = BackupOcrJsonCodec.decodeLine(cursor.getString(0), limits)
                val document = cursor.getInt(1)
                val page = cursor.getInt(2)
                val artifact = cursor.getLong(3)
                val ordinal = cursor.getInt(4)
                val artifactOrdinal = cursor.getInt(5)
                if (artifactOrdinal != currentArtifactOrdinal) {
                    if (currentArtifactOrdinal >= 0 && currentArtifactLineCount != expectedArtifactLineCount) {
                        relationship()
                    }
                    currentArtifactOrdinal = artifactOrdinal
                    currentArtifactLineCount = 0
                    expectedArtifactLineCount = cursor.getInt(6)
                }
                if (ordinal != currentArtifactLineCount ||
                    previousDocument > document ||
                    (previousDocument == document && previousPage > page) ||
                    (previousDocument == document && previousPage == page && previousArtifact > artifact) ||
                    (previousDocument == document && previousPage == page &&
                        previousArtifact == artifact && previousLine >= ordinal)
                ) relationship()
                currentArtifactLineCount += 1
                previousDocument = document
                previousPage = page
                previousArtifact = artifact
                previousLine = ordinal
                validateLine(line, limits)
            }
            if (currentArtifactOrdinal >= 0 && currentArtifactLineCount != expectedArtifactLineCount) {
                relationship()
            }
        }
    }

    private fun validateArtifact(artifact: BackupOcrArtifactRecord, limits: BackupFormatLimits) {
        stableId(artifact.pageId, "pageId")
        if (artifact.artifactRevision <= 0L || artifact.lineCount !in 0..limits.maximumOcrLinesPerArtifact ||
            artifact.rawText.length > limits.maximumStringCharacters || artifact.rawText.any { it == '\u0000' } ||
            artifact.actualScript !in ACTUAL_SCRIPTS || artifact.recognizerId.isNullOrBlank()
        ) invalidMetadata()
        if (artifact.verificationState == BackupOcrVerificationState.CURRENT_VERIFIED &&
            (artifact.capturedPageVisualRevision == null || artifact.capturedDocumentContentRevision == null ||
                artifact.inputFingerprint == null || artifact.pipelineVersion == null || artifact.delivery == null ||
                artifact.recognizedAtEpochMillis == null)
        ) invalidMetadata()
        if (artifact.capturedPageVisualRevision?.let { it < 0L } == true ||
            artifact.capturedDocumentContentRevision?.let { it < 0L } == true ||
            artifact.recognizedAtEpochMillis?.let { it < 0L } == true
        ) invalidMetadata()
        artifact.inputFingerprint?.let { fingerprint ->
            if (fingerprint.version <= 0 || fingerprint.visualRevision < 0L || fingerprint.uprightWidth <= 0 ||
                fingerprint.uprightHeight <= 0 || fingerprint.coordinateSystemVersion <= 0 ||
                fingerprint.transformVersion <= 0 || fingerprint.rotationDegrees !in VALID_ROTATIONS
            ) invalidMetadata()
            sha256(fingerprint.contentSha256)
            if (artifact.capturedPageVisualRevision != null &&
                artifact.capturedPageVisualRevision != fingerprint.visualRevision
            ) relationship()
        }
    }

    private fun validateLine(line: BackupOcrLineRecord, limits: BackupFormatLimits) {
        stableId(line.pageId, "pageId")
        if (line.rawText.length > limits.maximumStringCharacters || line.rawText.any { it == '\u0000' } ||
            line.cornerPoints.size != 4 || !line.baselineAngleDegrees.isFinite() ||
            line.baselineAngleDegrees !in -180.0..180.0
        ) invalidMetadata()
        (line.cornerPoints + line.baselineStart + line.baselineEnd).forEach { point ->
            if (!point.x.isFinite() || !point.y.isFinite() || point.x !in 0.0..1.0 || point.y !in 0.0..1.0) {
                invalidMetadata()
            }
        }
    }

    private fun createSchema() {
        database.execSQL("CREATE TABLE $TABLE_ARCHIVE_PATHS(relative_path TEXT PRIMARY KEY, canonical_path TEXT NOT NULL UNIQUE)")
        database.execSQL("CREATE TABLE $TABLE_OBSERVED(relative_path TEXT PRIMARY KEY, sha256 TEXT NOT NULL, byte_length INTEGER NOT NULL, inspection_prefix BLOB, jpeg_width INTEGER, jpeg_height INTEGER)")
        database.execSQL("CREATE TABLE $TABLE_CHECKSUMS(relative_path TEXT PRIMARY KEY, canonical_path TEXT NOT NULL UNIQUE, sha256 TEXT NOT NULL, byte_length INTEGER NOT NULL)")
        database.execSQL("CREATE TABLE $TABLE_FOLDERS(ordinal INTEGER PRIMARY KEY, record_id TEXT NOT NULL UNIQUE, parent_id TEXT, json TEXT NOT NULL, target_id TEXT UNIQUE, target_parent_id TEXT, planned_name TEXT, normalized_name TEXT, planned_path_characters INTEGER, plan_ready INTEGER NOT NULL DEFAULT 0)")
        database.execSQL("CREATE INDEX folders_parent ON $TABLE_FOLDERS(parent_id)")
        database.execSQL("CREATE INDEX folders_plan_ready_ordinal ON $TABLE_FOLDERS(plan_ready,ordinal)")
        database.execSQL("CREATE INDEX planned_folders_parent_name ON $TABLE_FOLDERS(target_parent_id,normalized_name)")
        database.execSQL("CREATE TABLE $TABLE_DOCUMENTS(ordinal INTEGER PRIMARY KEY, record_id TEXT NOT NULL UNIQUE, folder_id TEXT, page_count INTEGER NOT NULL, source_count INTEGER NOT NULL, json TEXT NOT NULL, fingerprint_version INTEGER, fingerprint_sha256 TEXT, ocr_digest_version INTEGER, ocr_digest_sha256 TEXT, page_bytes INTEGER, source_bytes INTEGER, source_modified_at INTEGER, duplicate_kind TEXT)")
        database.execSQL("CREATE INDEX documents_folder ON $TABLE_DOCUMENTS(folder_id)")
        database.execSQL("CREATE TABLE $TABLE_PAGES(ordinal INTEGER PRIMARY KEY, record_id TEXT NOT NULL UNIQUE, document_id TEXT NOT NULL, page_position INTEGER NOT NULL, relative_path TEXT NOT NULL UNIQUE, sha256 TEXT NOT NULL, byte_length INTEGER NOT NULL, ocr_text TEXT, json TEXT NOT NULL, UNIQUE(document_id,page_position))")
        database.execSQL("CREATE INDEX pages_document_position ON $TABLE_PAGES(document_id,page_position)")
        database.execSQL("CREATE TABLE $TABLE_SOURCES(ordinal INTEGER PRIMARY KEY, record_id TEXT NOT NULL UNIQUE, document_id TEXT NOT NULL, relative_path TEXT NOT NULL UNIQUE, sha256 TEXT NOT NULL, byte_length INTEGER NOT NULL, json TEXT NOT NULL)")
        database.execSQL("CREATE INDEX sources_document_id ON $TABLE_SOURCES(document_id,record_id)")
        database.execSQL("CREATE TABLE $TABLE_OCR_DOCUMENTS(ordinal INTEGER PRIMARY KEY, document_id TEXT NOT NULL UNIQUE, json TEXT NOT NULL)")
        database.execSQL("CREATE TABLE $TABLE_OCR_PAGES(ordinal INTEGER PRIMARY KEY, page_id TEXT NOT NULL UNIQUE, active_revision INTEGER, json TEXT NOT NULL)")
        database.execSQL("CREATE TABLE $TABLE_OCR_ARTIFACTS(ordinal INTEGER PRIMARY KEY, page_id TEXT NOT NULL, artifact_revision INTEGER NOT NULL, line_count INTEGER NOT NULL, raw_text TEXT NOT NULL, json TEXT NOT NULL, UNIQUE(page_id,artifact_revision))")
        database.execSQL("CREATE INDEX artifacts_page_revision ON $TABLE_OCR_ARTIFACTS(page_id,artifact_revision)")
        database.execSQL("CREATE TABLE $TABLE_OCR_CORRECTIONS(ordinal INTEGER PRIMARY KEY, page_id TEXT NOT NULL UNIQUE, base_revision INTEGER NOT NULL, corrected_text TEXT NOT NULL, json TEXT NOT NULL)")
        database.execSQL("CREATE TABLE $TABLE_OCR_LINES(ordinal INTEGER PRIMARY KEY, page_id TEXT NOT NULL, artifact_revision INTEGER NOT NULL, line_ordinal INTEGER NOT NULL, json TEXT NOT NULL, UNIQUE(page_id,artifact_revision,line_ordinal))")
        database.execSQL("CREATE INDEX lines_page_revision_ordinal ON $TABLE_OCR_LINES(page_id,artifact_revision,line_ordinal)")
        database.execSQL("CREATE TABLE $TABLE_OCR_CHUNKS(ordinal INTEGER PRIMARY KEY, relative_path TEXT NOT NULL UNIQUE, record_count INTEGER NOT NULL, byte_length INTEGER NOT NULL)")
    }

    private fun insert(
        table: String,
        values: ContentValues,
        constraintFailure: BackupFormatFailure = BackupFormatFailure.RELATIONSHIP_INVALID,
    ) {
        requireWritable()
        beginWritesIfNeeded()
        try {
            database.insertOrThrow(table, null, values)
        } catch (failure: SQLiteConstraintException) {
            throw backupFailure(constraintFailure, "The backup contains duplicate or inconsistent indexed records.", failure)
        }
        pendingWrites += 1
        if (pendingWrites >= WRITE_BATCH_SIZE) flushWrites()
    }

    private fun beginWritesIfNeeded() {
        if (!database.inTransaction()) database.beginTransaction()
    }

    private fun flushWrites() {
        if (!database.inTransaction()) return
        database.setTransactionSuccessful()
        database.endTransaction()
        pendingWrites = 0
    }

    private fun <T> pageJson(
        sql: String,
        args: Array<String>,
        decode: (String, BackupFormatLimits) -> T,
    ): List<T> {
        requireReadable()
        val limits = requireNotNull(verifiedLimits)
        val result = ArrayList<T>()
        database.rawQuery(sql, args).use { cursor ->
            while (cursor.moveToNext()) result += decode(cursor.getString(0), limits)
        }
        return result
    }

    private fun <T> singleJson(
        sql: String,
        args: Array<String>,
        decode: (String, BackupFormatLimits) -> T,
    ): T? {
        requireReadable()
        val limits = requireNotNull(verifiedLimits)
        return database.rawQuery(sql, args).use { cursor ->
            if (cursor.moveToFirst()) decode(cursor.getString(0), limits) else null
        }
    }

    private fun <T> scanJson(
        table: String,
        decode: (String, BackupFormatLimits) -> T,
        validate: (T) -> Unit,
    ) {
        val limits = requireLimitsForVerification()
        database.rawQuery("SELECT json FROM $table ORDER BY ordinal", null).use { cursor ->
            while (cursor.moveToNext()) {
                checkCancelled()
                validate(decode(cursor.getString(0), limits))
            }
        }
    }

    private fun requireLimitsForVerification(): BackupFormatLimits =
        verifiedLimits ?: BackupFormatLimits()

    private fun exists(sql: String, args: Array<String>? = null): Boolean =
        database.rawQuery(sql, args).use {
            checkCancelled()
            it.moveToFirst()
        }

    private fun queryLong(sql: String): Long = database.rawQuery(sql, null).use { cursor ->
        checkCancelled()
        check(cursor.moveToFirst())
        cursor.getLong(0)
    }

    private fun count(table: String): Int = queryLong("SELECT COUNT(*) FROM $table").toInt()

    private fun requireWritable() {
        check(database.isOpen && !sealed) { "Verified restore staging is immutable" }
    }

    private fun requireReadable() {
        check(database.isOpen && verifiedLimits != null) { "Restore staging is not verified" }
    }

    private fun boundedLimit(limit: Int): Int {
        require(limit in 1..MAX_READ_PAGE_SIZE)
        return limit
    }

    private fun stableId(value: String, field: String) {
        BackupPathValidator.requireStableId(value, field)
    }

    private fun boundedText(value: String, maximum: Int) {
        if (value.isBlank() || value.length > maximum || value.any { it == '\u0000' }) invalidMetadata()
    }

    private fun timestamps(created: Long, modified: Long) {
        if (created < 0L || modified < created) invalidMetadata()
    }

    private fun sha256(value: String) {
        if (!SHA256.matches(value)) invalidMetadata()
    }

    private fun invalidMetadata(): Nothing = throw backupFailure(
        BackupFormatFailure.INVALID_METADATA,
        "The indexed backup metadata is invalid.",
    )

    private fun relationship(): Nothing = throw backupFailure(
        BackupFormatFailure.RELATIONSHIP_INVALID,
        "The indexed backup relationships are invalid.",
    )

    private fun duplicate(message: String): Nothing = throw backupFailure(
        BackupFormatFailure.DUPLICATE_ENTRY,
        message,
    )

    private fun invalidLedger(): Nothing = throw backupFailure(
        BackupFormatFailure.INVALID_LEDGER,
        "The checksum ledger is invalid.",
    )

    private fun missing(message: String): Nothing = throw backupFailure(BackupFormatFailure.MISSING_ENTRY, message)

    private fun valuesOf(vararg entries: Pair<String, Any?>): ContentValues = ContentValues(entries.size).apply {
        entries.forEach { (key, value) ->
            when (value) {
                null -> putNull(key)
                is String -> put(key, value)
                is Int -> put(key, value)
                is Long -> put(key, value)
                is ByteArray -> put(key, value)
                else -> error("Unsupported SQLite value")
            }
        }
    }

    private fun Cursor.nullableInt(column: Int): Int? = if (isNull(column)) null else getInt(column)

    private fun checkCancelled() = checkCancellation()

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private companion object {
        const val WRITE_BATCH_SIZE = 256
        const val MAX_READ_PAGE_SIZE = 512
        const val TABLE_ARCHIVE_PATHS = "archive_paths"
        const val TABLE_OBSERVED = "observed_entries"
        const val TABLE_CHECKSUMS = "checksum_entries"
        const val TABLE_FOLDERS = "backup_folders"
        const val TABLE_DOCUMENTS = "backup_documents"
        const val TABLE_PAGES = "backup_pages"
        const val TABLE_SOURCES = "backup_sources"
        const val TABLE_OCR_DOCUMENTS = "backup_ocr_documents"
        const val TABLE_OCR_PAGES = "backup_ocr_pages"
        const val TABLE_OCR_ARTIFACTS = "backup_ocr_artifacts"
        const val TABLE_OCR_CORRECTIONS = "backup_ocr_corrections"
        const val TABLE_OCR_LINES = "backup_ocr_lines"
        const val TABLE_OCR_CHUNKS = "backup_ocr_chunks"
        val SHA256 = Regex("[0-9a-f]{64}")
        val VALID_ROTATIONS = setOf(0, 90, 180, 270)
        val SUPPORTED_FILTERS = setOf("ORIGINAL", "AUTO_ENHANCE", "GRAYSCALE", "BLACK_AND_WHITE", "HIGH_CONTRAST")
        val PAGE_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp")
        val ACTUAL_SCRIPTS = setOf("LATIN", "CHINESE", "JAPANESE", "KOREAN", "DEVANAGARI")
        val SCRIPT_PREFERENCES = ACTUAL_SCRIPTS + "AUTOMATIC"
        val BASE_REQUIRED_ENTRIES = setOf(
            RME_BACKUP_MANIFEST_PATH,
            RME_BACKUP_FOLDERS_PATH,
            RME_BACKUP_DOCUMENTS_PATH,
            RME_BACKUP_PAGES_PATH,
            RME_BACKUP_SOURCE_ASSETS_PATH,
        )
    }
}
