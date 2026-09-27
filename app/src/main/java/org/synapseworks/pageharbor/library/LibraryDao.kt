package org.synapseworks.pageharbor.library

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

data class LibraryBackupDocumentRow(
    @Embedded val document: LibraryDocumentEntity,
    @ColumnInfo(name = "backup_page_count") val actualPageCount: Int,
    @ColumnInfo(name = "backup_source_asset_count") val actualSourceAssetCount: Int,
)

data class LibraryBackupPageRow(
    @Embedded val page: LibraryPageEntity,
    @ColumnInfo(name = "backup_document_row_id") val documentRowId: Long,
)

data class LibraryBackupOcrArtifactRow(
    @Embedded val artifact: LibraryPageOcrArtifactEntity,
    @ColumnInfo(name = "backup_document_row_id") val documentRowId: Long,
    @ColumnInfo(name = "backup_page_position") val pagePosition: Int,
    @ColumnInfo(name = "backup_line_count") val lineCount: Int,
)

data class LibraryBackupOcrLineRow(
    @Embedded val line: LibraryPageOcrLineEntity,
    @ColumnInfo(name = "backup_document_row_id") val documentRowId: Long,
    @ColumnInfo(name = "backup_page_position") val pagePosition: Int,
)

data class LibraryBackupOcrCorrectionRow(
    @Embedded val correction: LibraryPageOcrCorrectionEntity,
    @ColumnInfo(name = "backup_document_row_id") val documentRowId: Long,
    @ColumnInfo(name = "backup_page_position") val pagePosition: Int,
)

data class LibraryBackupOcrCorrectionLineRow(
    @Embedded val line: LibraryPageOcrCorrectionLineEntity,
    @ColumnInfo(name = "backup_document_row_id") val documentRowId: Long,
    @ColumnInfo(name = "backup_page_position") val pagePosition: Int,
)

@Dao
abstract class LibraryDao {
    @Query(
        """
        SELECT d.document_id, d.title, d.created_at, d.modified_at, d.page_count,
               d.folder_id, f.name AS folder_name, d.thumbnail_path, d.ocr_status
        FROM library_documents AS d
        LEFT JOIN library_folders AS f ON f.folder_id = d.folder_id
        WHERE d.library_state = 'ACTIVE'
          AND (:folderId IS NULL OR d.folder_id = :folderId)
        ORDER BY d.modified_at DESC, d.title COLLATE NOCASE ASC
        """,
    )
    abstract fun observeDocumentsByModified(folderId: String?): Flow<List<LibraryDocumentListingRow>>

    @Query(
        """
        SELECT d.document_id, d.title, d.created_at, d.modified_at, d.page_count,
               d.folder_id, f.name AS folder_name, d.thumbnail_path, d.ocr_status
        FROM library_documents AS d
        LEFT JOIN library_folders AS f ON f.folder_id = d.folder_id
        WHERE d.library_state = 'ACTIVE'
          AND (:folderId IS NULL OR d.folder_id = :folderId)
        ORDER BY d.created_at DESC, d.title COLLATE NOCASE ASC
        """,
    )
    abstract fun observeDocumentsByCreated(folderId: String?): Flow<List<LibraryDocumentListingRow>>

    @Query(
        """
        SELECT d.document_id, d.title, d.created_at, d.modified_at, d.page_count,
               d.folder_id, f.name AS folder_name, d.thumbnail_path, d.ocr_status
        FROM library_documents AS d
        LEFT JOIN library_folders AS f ON f.folder_id = d.folder_id
        WHERE d.library_state = 'ACTIVE'
          AND (:folderId IS NULL OR d.folder_id = :folderId)
        ORDER BY d.title COLLATE NOCASE ASC, d.modified_at DESC
        """,
    )
    abstract fun observeDocumentsByTitle(folderId: String?): Flow<List<LibraryDocumentListingRow>>

    @Query(
        """
        SELECT d.document_id, d.title, d.created_at, d.modified_at, d.page_count,
               d.folder_id, f.name AS folder_name, d.thumbnail_path, d.ocr_status,
               NULL AS page_id, NULL AS page_position,
               CASE WHEN lower(c.title) LIKE '%' || lower(:rawQuery) || '%'
                    THEN 'TITLE' ELSE 'FOLDER' END AS match_type,
               NULL AS match_snippet
        FROM library_document_search_v3 AS search
        JOIN library_document_search_content_v3 AS c ON c.rowid = search.rowid
        JOIN library_documents AS d ON d.row_id = c.rowid
        LEFT JOIN library_folders AS f ON f.folder_id = d.folder_id
        WHERE d.library_state = 'ACTIVE'
          AND library_document_search_v3 MATCH :ftsQuery
        ORDER BY d.modified_at DESC, d.title COLLATE NOCASE ASC
        LIMIT :limit
        """,
    )
    abstract fun observeDocumentSearch(
        ftsQuery: String,
        rawQuery: String,
        limit: Int,
    ): Flow<List<LibrarySearchRow>>

    @Query(
        """
        SELECT d.document_id, d.title, d.created_at, d.modified_at, d.page_count,
               d.folder_id, f.name AS folder_name, d.thumbnail_path, d.ocr_status,
               p.page_id, p.page_position,
               'OCR' AS match_type,
               snippet(library_page_search_v3, '', '', ' … ', 1, 12) AS match_snippet
        FROM library_page_search_v3 AS search
        JOIN library_page_search_content_v3 AS c ON c.rowid = search.rowid
        JOIN library_pages AS p ON p.page_id = c.page_id
        JOIN library_documents AS d ON d.document_id = p.document_id
        LEFT JOIN library_folders AS f ON f.folder_id = d.folder_id
        WHERE d.library_state = 'ACTIVE'
          AND library_page_search_v3 MATCH :ftsQuery
        ORDER BY d.modified_at DESC, d.title COLLATE NOCASE ASC, p.page_position ASC
        LIMIT :limit
        """,
    )
    abstract fun observePageSearch(
        ftsQuery: String,
        limit: Int,
    ): Flow<List<LibrarySearchRow>>

    @Query(
        """
        SELECT d.document_id, d.title, d.created_at, d.modified_at, d.page_count,
               d.folder_id, f.name AS folder_name, d.thumbnail_path, d.ocr_status,
               NULL AS page_id, NULL AS page_position,
               CASE WHEN lower(c.title) LIKE '%' || lower(:rawQuery) || '%'
                    THEN 'TITLE' ELSE 'FOLDER' END AS match_type,
               NULL AS match_snippet
        FROM library_document_search_v3 AS search
        JOIN library_document_search_content_v3 AS c ON c.rowid = search.rowid
        JOIN library_documents AS d ON d.row_id = c.rowid
        LEFT JOIN library_folders AS f ON f.folder_id = d.folder_id
        WHERE d.library_state = 'ACTIVE'
          AND library_document_search_v3 MATCH :ftsQuery
          AND (d.modified_at < :beforeModifiedAt OR
               (d.modified_at = :beforeModifiedAt AND d.document_id > :afterDocumentId))
        ORDER BY d.modified_at DESC, d.document_id ASC
        LIMIT :limit
        """,
    )
    abstract suspend fun documentSearchPage(
        ftsQuery: String,
        rawQuery: String,
        beforeModifiedAt: Long,
        afterDocumentId: String,
        limit: Int,
    ): List<LibrarySearchRow>

    @Query(
        """
        SELECT d.document_id, d.title, d.created_at, d.modified_at, d.page_count,
               d.folder_id, f.name AS folder_name, d.thumbnail_path, d.ocr_status,
               p.page_id, p.page_position,
               'OCR' AS match_type,
               snippet(library_page_search_v3, '', '', ' … ', 1, 12) AS match_snippet
        FROM library_page_search_v3 AS search
        JOIN library_page_search_content_v3 AS c ON c.rowid = search.rowid
        JOIN library_pages AS p ON p.page_id = c.page_id
        JOIN library_documents AS d ON d.document_id = p.document_id
        LEFT JOIN library_folders AS f ON f.folder_id = d.folder_id
        WHERE d.library_state = 'ACTIVE'
          AND library_page_search_v3 MATCH :ftsQuery
          AND (d.modified_at < :beforeModifiedAt OR
               (d.modified_at = :beforeModifiedAt AND p.page_id > :afterPageId))
        ORDER BY d.modified_at DESC, p.page_id ASC
        LIMIT :limit
        """,
    )
    abstract suspend fun pageSearchPage(
        ftsQuery: String,
        beforeModifiedAt: Long,
        afterPageId: String,
        limit: Int,
    ): List<LibrarySearchRow>

    @Query(
        """
        SELECT f.folder_id, f.name, f.parent_folder_id,
               COUNT(d.document_id) AS document_count
        FROM library_folders AS f
        LEFT JOIN library_documents AS d
          ON d.folder_id = f.folder_id AND d.library_state = 'ACTIVE'
        GROUP BY f.folder_id
        ORDER BY f.name COLLATE NOCASE ASC
        """,
    )
    abstract fun observeFolders(): Flow<List<LibraryFolderRow>>

    @Query("SELECT * FROM library_documents WHERE document_id = :documentId AND library_state = 'ACTIVE' LIMIT 1")
    abstract suspend fun document(documentId: String): LibraryDocumentEntity?

    @Query("SELECT * FROM library_documents WHERE document_id = :documentId LIMIT 1")
    abstract suspend fun documentAnyState(documentId: String): LibraryDocumentEntity?

    @Query(
        """
        SELECT p.* FROM library_pages AS p
        JOIN library_documents AS d ON d.document_id = p.document_id
        WHERE p.document_id = :documentId AND d.library_state = 'ACTIVE'
        ORDER BY p.page_position ASC
        """,
    )
    abstract suspend fun pages(documentId: String): List<LibraryPageEntity>

    @Query("SELECT * FROM library_pages WHERE document_id = :documentId ORDER BY page_position ASC")
    abstract suspend fun pagesAnyState(documentId: String): List<LibraryPageEntity>

    @Query("SELECT * FROM library_pages WHERE page_id = :pageId LIMIT 1")
    abstract suspend fun pageAnyState(pageId: String): LibraryPageEntity?

    @Query(
        """
        SELECT d.document_id AS documentId,
               p.page_id AS pageId,
               p.page_position AS pagePosition,
               d.content_revision AS documentContentRevision,
               p.visual_revision AS pageVisualRevision,
               p.ocr_state_revision AS ocrStateRevision,
               p.active_ocr_artifact_revision AS activeArtifactRevision,
               p.content_sha256 AS contentSha256,
               p.rotation_degrees AS rotationDegrees,
               p.filter_name AS filterName
        FROM library_pages AS p
        JOIN library_documents AS d ON d.document_id = p.document_id
        WHERE d.document_id = :documentId
          AND p.page_id = :pageId
          AND d.library_state = 'ACTIVE'
        LIMIT 1
        """,
    )
    abstract suspend fun ocrPageSnapshot(
        documentId: String,
        pageId: String,
    ): LibraryOcrPageSnapshot?

    @Query(
        """
        SELECT d.document_id AS documentId,
               p.page_id AS pageId,
               p.page_position AS pagePosition,
               d.content_revision AS documentContentRevision,
               p.visual_revision AS pageVisualRevision,
               p.ocr_state_revision AS ocrStateRevision,
               p.active_ocr_artifact_revision AS activeArtifactRevision,
               p.content_sha256 AS contentSha256,
               p.rotation_degrees AS rotationDegrees,
               p.filter_name AS filterName
        FROM library_pages AS p
        JOIN library_documents AS d ON d.document_id = p.document_id
        WHERE d.document_id = :documentId AND d.library_state = 'ACTIVE'
        ORDER BY p.page_position
        """,
    )
    abstract suspend fun ocrPageSnapshots(documentId: String): List<LibraryOcrPageSnapshot>

    @Query(
        """
        SELECT d.document_id, p.page_id, p.page_position,
               COALESCE(c.corrected_text, active.raw_text) AS effective_text,
               selected.raw_text,
               c.corrected_text, c.alignment_state,
               selected.artifact_revision,
               p.active_ocr_artifact_revision AS active_artifact_revision,
               c.base_artifact_revision AS correction_base_artifact_revision,
               d.content_revision AS document_content_revision,
               p.visual_revision AS page_visual_revision,
               p.ocr_state_revision,
               selected.input_fingerprint_version, selected.input_fingerprint,
               selected.coordinate_system_version, selected.transform_version
               , selected.upright_width, selected.upright_height,
               selected.verification_state, selected.actual_script, selected.recognizer_id
        FROM library_pages AS p
        JOIN library_documents AS d ON d.document_id = p.document_id
        JOIN library_page_ocr_artifacts AS active
          ON active.page_id = p.page_id
         AND active.artifact_revision = p.active_ocr_artifact_revision
        LEFT JOIN library_page_ocr_corrections AS c ON c.page_id = p.page_id
        JOIN library_page_ocr_artifacts AS selected
          ON selected.page_id = p.page_id
         AND selected.artifact_revision = COALESCE(
             c.base_artifact_revision,
             p.active_ocr_artifact_revision
         )
        WHERE d.document_id = :documentId
          AND p.page_id = :pageId
          AND d.library_state = 'ACTIVE'
        LIMIT 1
        """,
    )
    protected abstract suspend fun effectiveOcrPageRow(
        documentId: String,
        pageId: String,
    ): LibraryEffectiveOcrPageRow?

    @Transaction
    open suspend fun effectiveOcrPage(
        documentId: String,
        pageId: String,
    ): LibraryEffectiveOcrPage? {
        val row = effectiveOcrPageRow(documentId, pageId) ?: return null
        val rawLines = ocrLines(pageId, row.artifactRevision)
        val alignment = row.alignmentState?.let(LibraryOcrCorrectionAlignment::valueOf)
        val effectiveLines = when (alignment) {
            LibraryOcrCorrectionAlignment.FREEFORM -> emptyList()
            LibraryOcrCorrectionAlignment.LINE_ALIGNED -> {
                val corrected = ocrCorrectionLines(pageId)
                check(corrected.size == rawLines.size)
                check(corrected.map { it.lineOrdinal } == rawLines.map { it.lineOrdinal })
                rawLines.zip(corrected).map { (raw, edited) -> raw.toEffectiveLine(edited.correctedText) }
            }
            null -> rawLines.map { raw -> raw.toEffectiveLine(raw.rawText) }
        }
        return row.toEffectiveOcrPage(
            effectiveLines = effectiveLines,
            sourceGeometryLines = rawLines.map { raw -> raw.toEffectiveLine(raw.rawText) },
        )
    }

    @Query(
        """
        SELECT p.page_position,
               substr(COALESCE(c.corrected_text, active.raw_text), 1, :maxCharactersPerPage)
                   AS effective_text
        FROM library_pages AS p
        JOIN library_documents AS d ON d.document_id = p.document_id
        JOIN library_page_ocr_artifacts AS active
          ON active.page_id = p.page_id
         AND active.artifact_revision = p.active_ocr_artifact_revision
        LEFT JOIN library_page_ocr_corrections AS c ON c.page_id = p.page_id
        WHERE d.document_id = :documentId
          AND d.library_state = 'ACTIVE'
        ORDER BY p.page_position ASC
        LIMIT :pageLimit
        """,
    )
    abstract suspend fun smartNamingOcrPages(
        documentId: String,
        pageLimit: Int,
        maxCharactersPerPage: Int,
    ): List<LibrarySmartNamingOcrPageRow>

    /** Loads only one page for review and keeps latest-raw provenance separate from correction. */
    @Transaction
    open suspend fun ocrReviewPage(
        documentId: String,
        pageId: String,
    ): LibraryOcrReviewPage? {
        val snapshot = ocrPageSnapshot(documentId, pageId) ?: return null
        val activeRevision = snapshot.activeArtifactRevision
        val active = activeRevision?.let { revision -> ocrArtifact(pageId, revision) }
        val correction = ocrCorrection(pageId)
        val effective = effectiveOcrPage(documentId, pageId)
        return LibraryOcrReviewPage(
            snapshot = snapshot,
            rawText = active?.rawText,
            effectiveText = correction?.correctedText ?: active?.rawText,
            correctedText = correction?.correctedText,
            actualScript = active?.actualScript,
            recognizedAtMillis = active?.recognizedAtMillis,
            correctionBaseArtifactRevision = correction?.baseArtifactRevision,
            rawLines = if (activeRevision == null) {
                emptyList()
            } else {
                ocrLines(pageId, activeRevision).map(LibraryPageOcrLineEntity::rawText)
            },
        ).also {
            check(effective?.effectiveText == it.effectiveText)
        }
    }

    @Query("SELECT * FROM library_source_assets WHERE document_id = :documentId ORDER BY created_at, asset_id")
    abstract suspend fun sourceAssets(documentId: String): List<LibrarySourceAssetEntity>

    @Query("SELECT * FROM library_folders WHERE folder_id = :folderId LIMIT 1")
    abstract suspend fun folder(folderId: String): LibraryFolderEntity?

    @Query(
        """
        SELECT * FROM library_folders
        WHERE parent_scope = :parentScope AND normalized_name = :normalizedName
        LIMIT 1
        """,
    )
    abstract suspend fun folderByNormalizedName(
        normalizedName: String,
        parentScope: String,
    ): LibraryFolderEntity?

    @Query("SELECT * FROM library_folders ORDER BY folder_id LIMIT :limit OFFSET :offset")
    abstract suspend fun foldersPage(offset: Int, limit: Int): List<LibraryFolderEntity>

    @Query(
        """
        SELECT * FROM library_folders
        WHERE folder_id > :afterFolderId
        ORDER BY folder_id ASC
        LIMIT :limit
        """,
    )
    abstract suspend fun backupFoldersPage(
        afterFolderId: String,
        limit: Int,
    ): List<LibraryFolderEntity>

    @Query("SELECT * FROM library_folders WHERE parent_folder_id IS :parentFolderId ORDER BY name COLLATE NOCASE")
    abstract suspend fun childFolders(parentFolderId: String?): List<LibraryFolderEntity>

    @Query(
        """
        SELECT * FROM library_documents
        WHERE library_state = 'ACTIVE' AND row_id > :afterRowId
        ORDER BY row_id ASC
        LIMIT :limit
        """,
    )
    abstract suspend fun activeDocumentsPage(
        afterRowId: Long,
        limit: Int,
    ): List<LibraryDocumentEntity>

    @Query(
        """
        SELECT d.*,
               (SELECT COUNT(*) FROM library_pages AS p WHERE p.document_id = d.document_id)
                   AS backup_page_count,
               (SELECT COUNT(*) FROM library_source_assets AS s WHERE s.document_id = d.document_id)
                   AS backup_source_asset_count
        FROM library_documents AS d
        WHERE d.library_state = 'ACTIVE' AND d.row_id > :afterRowId
        ORDER BY d.row_id ASC
        LIMIT :limit
        """,
    )
    abstract suspend fun activeBackupDocumentsPage(
        afterRowId: Long,
        limit: Int,
    ): List<LibraryBackupDocumentRow>

    @Query(
        """
        SELECT * FROM library_pages
        WHERE document_id = :documentId AND page_position > :afterPosition
        ORDER BY page_position ASC
        LIMIT :limit
        """,
    )
    abstract suspend fun pagesPage(
        documentId: String,
        afterPosition: Int,
        limit: Int,
    ): List<LibraryPageEntity>

    @Query(
        """
        SELECT p.*, d.row_id AS backup_document_row_id
        FROM library_pages AS p
        JOIN library_documents AS d ON d.document_id = p.document_id
        WHERE d.library_state = 'ACTIVE'
          AND (d.row_id > :afterDocumentRowId OR
               (d.row_id = :afterDocumentRowId AND p.page_position > :afterPagePosition))
        ORDER BY d.row_id ASC, p.page_position ASC
        LIMIT :limit
        """,
    )
    abstract suspend fun activeBackupPagesPage(
        afterDocumentRowId: Long,
        afterPagePosition: Int,
        limit: Int,
    ): List<LibraryBackupPageRow>

    @Query(
        """
        SELECT s.* FROM library_source_assets AS s
        WHERE s.asset_id > :afterAssetId
          AND EXISTS (
              SELECT 1 FROM library_documents AS d
              WHERE d.document_id = s.document_id AND d.library_state = 'ACTIVE'
          )
        ORDER BY s.asset_id ASC
        LIMIT :limit
        """,
    )
    abstract suspend fun activeBackupSourceAssetsPage(
        afterAssetId: String,
        limit: Int,
    ): List<LibrarySourceAssetEntity>

    @Query(
        """
        SELECT a.*, d.row_id AS backup_document_row_id, p.page_position AS backup_page_position,
               (SELECT COUNT(*) FROM library_page_ocr_lines AS l
                WHERE l.page_id = a.page_id AND l.artifact_revision = a.artifact_revision)
                   AS backup_line_count
        FROM library_page_ocr_artifacts AS a
        JOIN library_pages AS p ON p.page_id = a.page_id
        JOIN library_documents AS d ON d.document_id = p.document_id
        WHERE d.library_state = 'ACTIVE'
          AND (d.row_id > :afterDocumentRowId OR
               (d.row_id = :afterDocumentRowId AND
                (p.page_position > :afterPagePosition OR
                 (p.page_position = :afterPagePosition AND a.artifact_revision > :afterArtifactRevision))))
        ORDER BY d.row_id ASC, p.page_position ASC, a.artifact_revision ASC
        LIMIT :limit
        """,
    )
    abstract suspend fun activeBackupOcrArtifactsPage(
        afterDocumentRowId: Long,
        afterPagePosition: Int,
        afterArtifactRevision: Long,
        limit: Int,
    ): List<LibraryBackupOcrArtifactRow>

    @Query(
        """
        SELECT l.*, d.row_id AS backup_document_row_id, p.page_position AS backup_page_position
        FROM library_page_ocr_lines AS l
        JOIN library_pages AS p ON p.page_id = l.page_id
        JOIN library_documents AS d ON d.document_id = p.document_id
        WHERE d.library_state = 'ACTIVE'
          AND (d.row_id > :afterDocumentRowId OR
               (d.row_id = :afterDocumentRowId AND
                (p.page_position > :afterPagePosition OR
                 (p.page_position = :afterPagePosition AND
                  (l.artifact_revision > :afterArtifactRevision OR
                   (l.artifact_revision = :afterArtifactRevision AND l.line_ordinal > :afterLineOrdinal))))))
        ORDER BY d.row_id ASC, p.page_position ASC, l.artifact_revision ASC, l.line_ordinal ASC
        LIMIT :limit
        """,
    )
    abstract suspend fun activeBackupOcrLinesPage(
        afterDocumentRowId: Long,
        afterPagePosition: Int,
        afterArtifactRevision: Long,
        afterLineOrdinal: Int,
        limit: Int,
    ): List<LibraryBackupOcrLineRow>

    @Query(
        """
        SELECT c.*, d.row_id AS backup_document_row_id, p.page_position AS backup_page_position
        FROM library_page_ocr_corrections AS c
        JOIN library_pages AS p ON p.page_id = c.page_id
        JOIN library_documents AS d ON d.document_id = p.document_id
        WHERE d.library_state = 'ACTIVE'
          AND (d.row_id > :afterDocumentRowId OR
               (d.row_id = :afterDocumentRowId AND p.page_position > :afterPagePosition))
        ORDER BY d.row_id ASC, p.page_position ASC
        LIMIT :limit
        """,
    )
    abstract suspend fun activeBackupOcrCorrectionsPage(
        afterDocumentRowId: Long,
        afterPagePosition: Int,
        limit: Int,
    ): List<LibraryBackupOcrCorrectionRow>

    @Query(
        """
        SELECT l.*, d.row_id AS backup_document_row_id, p.page_position AS backup_page_position
        FROM library_page_ocr_correction_lines AS l
        JOIN library_pages AS p ON p.page_id = l.page_id
        JOIN library_documents AS d ON d.document_id = p.document_id
        WHERE d.library_state = 'ACTIVE'
          AND (d.row_id > :afterDocumentRowId OR
               (d.row_id = :afterDocumentRowId AND
                (p.page_position > :afterPagePosition OR
                 (p.page_position = :afterPagePosition AND
                  (l.base_artifact_revision > :afterArtifactRevision OR
                   (l.base_artifact_revision = :afterArtifactRevision AND l.line_ordinal > :afterLineOrdinal))))))
        ORDER BY d.row_id ASC, p.page_position ASC, l.base_artifact_revision ASC, l.line_ordinal ASC
        LIMIT :limit
        """,
    )
    abstract suspend fun activeBackupOcrCorrectionLinesPage(
        afterDocumentRowId: Long,
        afterPagePosition: Int,
        afterArtifactRevision: Long,
        afterLineOrdinal: Int,
        limit: Int,
    ): List<LibraryBackupOcrCorrectionLineRow>

    @Query(
        """
        SELECT * FROM library_documents
        WHERE library_state = 'ACTIVE'
          AND content_hash_version = :hashVersion
          AND content_sha256 = :sha256
        ORDER BY row_id ASC
        """,
    )
    abstract suspend fun exactDuplicateCandidates(
        hashVersion: Int,
        sha256: String,
    ): List<LibraryDocumentEntity>

    @Query(
        """
        SELECT * FROM library_documents
        WHERE library_state = 'ACTIVE'
          AND content_hash_version = :hashVersion
          AND content_sha256 = :sha256
        ORDER BY row_id ASC
        LIMIT :limit
        """,
    )
    abstract suspend fun exactRestoreDuplicateCandidates(
        hashVersion: Int,
        sha256: String,
        limit: Int,
    ): List<LibraryDocumentEntity>

    @Query(
        """
        SELECT document_id
        FROM library_documents
            INDEXED BY index_library_documents_library_state_page_count_content_byte_count_row_id
        WHERE library_state = 'ACTIVE'
          AND page_count = :pageCount
        ORDER BY content_byte_count ASC, row_id ASC
        LIMIT 1
        """,
    )
    abstract suspend fun possibleRestoreShapeDuplicateByPageCount(pageCount: Int): String?

    @Query(
        """
        SELECT document_id
        FROM library_documents
            INDEXED BY index_library_documents_library_state_page_count_content_byte_count_row_id
        WHERE library_state = 'ACTIVE'
          AND page_count = :pageCount
          AND content_byte_count BETWEEN :minimumByteCount AND :maximumByteCount
        ORDER BY content_byte_count ASC, row_id ASC
        LIMIT 1
        """,
    )
    abstract suspend fun possibleRestoreShapeDuplicateInByteRange(
        pageCount: Int,
        minimumByteCount: Long,
        maximumByteCount: Long,
    ): String?

    @Query(
        """
        SELECT document_id
        FROM library_documents
            INDEXED BY index_library_documents_library_state_page_count_content_byte_count_row_id
        WHERE library_state = 'ACTIVE'
          AND page_count = :pageCount
          AND content_byte_count IS NULL
        ORDER BY row_id ASC
        LIMIT 1
        """,
    )
    abstract suspend fun possibleRestoreShapeDuplicateWithUnknownByteCount(pageCount: Int): String?

    @Query(
        """
        SELECT d.document_id
        FROM library_source_assets AS s INDEXED BY index_library_source_assets_sha256_byte_count
        CROSS JOIN library_documents AS d ON d.document_id = s.document_id
        WHERE s.sha256 IN (:sha256Values) AND d.library_state = 'ACTIVE'
        LIMIT 1
        """,
    )
    abstract suspend fun possibleRestoreSourceDuplicate(sha256Values: List<String>): String?

    @Query("SELECT COUNT(*) FROM library_page_ocr_artifacts WHERE page_id = :pageId")
    abstract suspend fun restoreOcrArtifactCount(pageId: String): Int

    @Query(
        """
        SELECT * FROM library_page_ocr_artifacts
        WHERE page_id = :pageId AND artifact_revision > :afterRevision
        ORDER BY artifact_revision ASC
        LIMIT :limit
        """,
    )
    abstract suspend fun restoreOcrArtifactsPage(
        pageId: String,
        afterRevision: Long,
        limit: Int,
    ): List<LibraryPageOcrArtifactEntity>

    @Query(
        """
        SELECT COUNT(*) FROM library_page_ocr_lines
        WHERE page_id = :pageId AND artifact_revision = :artifactRevision
        """,
    )
    abstract suspend fun restoreOcrLineCount(pageId: String, artifactRevision: Long): Int

    @Query(
        """
        SELECT * FROM library_page_ocr_lines
        WHERE page_id = :pageId AND artifact_revision = :artifactRevision
          AND line_ordinal > :afterOrdinal
        ORDER BY line_ordinal ASC
        LIMIT :limit
        """,
    )
    abstract suspend fun restoreOcrLinesPage(
        pageId: String,
        artifactRevision: Long,
        afterOrdinal: Int,
        limit: Int,
    ): List<LibraryPageOcrLineEntity>

    @Query("SELECT * FROM library_metadata WHERE metadata_id = :metadataId LIMIT 1")
    abstract suspend fun metadata(metadataId: String = LIBRARY_METADATA_ID): LibraryMetadataEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertMetadata(entity: LibraryMetadataEntity): Long

    @Query(
        """
        UPDATE library_metadata
        SET library_revision = library_revision + 1, modified_at = :modifiedAt
        WHERE metadata_id = :metadataId
        """,
    )
    protected abstract suspend fun incrementLibraryRevision(
        modifiedAt: Long,
        metadataId: String = LIBRARY_METADATA_ID,
    ): Int

    @Transaction
    open suspend fun bumpLibraryRevision(modifiedAt: Long) {
        insertMetadata(LibraryMetadataEntity(modifiedAtMillis = modifiedAt))
        check(incrementLibraryRevision(modifiedAt) == 1)
    }

    @Insert
    protected abstract suspend fun insertDocument(entity: LibraryDocumentEntity): Long

    @Update
    protected abstract suspend fun updateDocument(entity: LibraryDocumentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertPages(entities: List<LibraryPageEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertPageIfAbsent(entity: LibraryPageEntity): Long

    @Update
    protected abstract suspend fun updatePage(entity: LibraryPageEntity): Int

    @Insert
    protected abstract suspend fun insertSourceAssetsInternal(entities: List<LibrarySourceAssetEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun upsertDocumentSearchContent(
        entity: LibraryDocumentSearchContentEntity,
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun upsertPageSearchContent(entity: LibraryPageSearchContentEntity): Long

    @Query("SELECT * FROM library_page_search_content_v3 WHERE page_id = :pageId LIMIT 1")
    protected abstract suspend fun pageSearchContent(pageId: String): LibraryPageSearchContentEntity?

    @Insert
    protected abstract suspend fun insertOcrArtifact(entity: LibraryPageOcrArtifactEntity)

    @Insert
    protected abstract suspend fun insertOcrLines(entities: List<LibraryPageOcrLineEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun upsertOcrCorrection(entity: LibraryPageOcrCorrectionEntity)

    @Insert
    protected abstract suspend fun insertOcrCorrectionLines(
        entities: List<LibraryPageOcrCorrectionLineEntity>,
    )

    @Query("DELETE FROM library_page_ocr_correction_lines WHERE page_id = :pageId")
    protected abstract suspend fun deleteOcrCorrectionLines(pageId: String)

    @Query("DELETE FROM library_page_ocr_corrections WHERE page_id = :pageId")
    protected abstract suspend fun deleteOcrCorrection(pageId: String): Int

    @Query(
        """
        SELECT * FROM library_page_ocr_corrections
        WHERE page_id = :pageId
        LIMIT 1
        """,
    )
    abstract suspend fun ocrCorrection(pageId: String): LibraryPageOcrCorrectionEntity?

    @Query(
        """
        SELECT * FROM library_page_ocr_artifacts
        WHERE page_id = :pageId AND artifact_revision = :artifactRevision
        LIMIT 1
        """,
    )
    abstract suspend fun ocrArtifact(
        pageId: String,
        artifactRevision: Long,
    ): LibraryPageOcrArtifactEntity?

    @Query(
        """
        SELECT * FROM library_page_ocr_artifacts
        WHERE page_id = :pageId
        ORDER BY artifact_revision
        """,
    )
    abstract suspend fun ocrArtifacts(pageId: String): List<LibraryPageOcrArtifactEntity>

    @Query(
        """
        SELECT * FROM library_page_ocr_lines
        WHERE page_id = :pageId AND artifact_revision = :artifactRevision
        ORDER BY line_ordinal
        """,
    )
    abstract suspend fun ocrLines(
        pageId: String,
        artifactRevision: Long,
    ): List<LibraryPageOcrLineEntity>

    @Query(
        """
        SELECT * FROM library_page_ocr_correction_lines
        WHERE page_id = :pageId
        ORDER BY line_ordinal
        """,
    )
    abstract suspend fun ocrCorrectionLines(pageId: String): List<LibraryPageOcrCorrectionLineEntity>

    @Query(
        """
        SELECT a.* FROM library_page_ocr_artifacts AS a
        JOIN library_pages AS p ON p.page_id = a.page_id
        WHERE p.document_id = :documentId AND p.page_id IN (:pageIds)
        ORDER BY p.page_position, a.artifact_revision
        """,
    )
    protected abstract suspend fun ocrArtifactsForPages(
        documentId: String,
        pageIds: List<String>,
    ): List<LibraryPageOcrArtifactEntity>

    @Query(
        """
        SELECT l.* FROM library_page_ocr_lines AS l
        JOIN library_pages AS p ON p.page_id = l.page_id
        WHERE p.document_id = :documentId AND p.page_id IN (:pageIds)
        ORDER BY p.page_position, l.artifact_revision, l.line_ordinal
        """,
    )
    protected abstract suspend fun ocrLinesForPages(
        documentId: String,
        pageIds: List<String>,
    ): List<LibraryPageOcrLineEntity>

    @Query(
        """
        SELECT c.* FROM library_page_ocr_corrections AS c
        JOIN library_pages AS p ON p.page_id = c.page_id
        WHERE p.document_id = :documentId AND p.page_id IN (:pageIds)
        ORDER BY p.page_position
        """,
    )
    protected abstract suspend fun ocrCorrectionsForPages(
        documentId: String,
        pageIds: List<String>,
    ): List<LibraryPageOcrCorrectionEntity>

    @Query(
        """
        SELECT l.* FROM library_page_ocr_correction_lines AS l
        JOIN library_pages AS p ON p.page_id = l.page_id
        WHERE p.document_id = :documentId AND p.page_id IN (:pageIds)
        ORDER BY p.page_position, l.base_artifact_revision, l.line_ordinal
        """,
    )
    protected abstract suspend fun ocrCorrectionLinesForPages(
        documentId: String,
        pageIds: List<String>,
    ): List<LibraryPageOcrCorrectionLineEntity>

    @Transaction
    open suspend fun ocrBackupPage(
        documentId: String,
        afterPosition: Int,
        limit: Int,
    ): List<LibraryOcrRestorePageState> {
        require(limit in 1..MAX_OCR_BACKUP_PAGE_SIZE)
        val pages = pagesPage(documentId, afterPosition, limit)
        if (pages.isEmpty()) return emptyList()
        val pageIds = pages.map(LibraryPageEntity::pageId)
        val artifacts = ocrArtifactsForPages(documentId, pageIds).groupBy { it.pageId }
        val lines = ocrLinesForPages(documentId, pageIds).groupBy { it.pageId }
        val corrections = ocrCorrectionsForPages(documentId, pageIds).associateBy { it.pageId }
        val correctionLines = ocrCorrectionLinesForPages(documentId, pageIds).groupBy { it.pageId }
        return pages.map { page ->
            LibraryOcrRestorePageState(
                backupPageId = page.pageId,
                ocrStateRevision = page.ocrStateRevision,
                activeArtifactRevision = page.activeOcrArtifactRevision,
                ocrError = page.ocrError,
                artifacts = artifacts[page.pageId].orEmpty(),
                lines = lines[page.pageId].orEmpty(),
                correction = corrections[page.pageId],
                correctionLines = correctionLines[page.pageId].orEmpty(),
            )
        }
    }

    @Query(
        """
        SELECT COALESCE(MAX(artifact_revision), 0)
        FROM library_page_ocr_artifacts
        WHERE page_id = :pageId
        """,
    )
    protected abstract suspend fun maximumArtifactRevision(pageId: String): Long

    @Query(
        """
        UPDATE library_pages
        SET active_ocr_artifact_revision = :artifactRevision,
            ocr_state_revision = ocr_state_revision + 1,
            ocr_text = :rawText,
            ocr_error = NULL
        WHERE page_id = :pageId AND document_id = :documentId
        """,
    )
    protected abstract suspend fun activateOcrArtifact(
        documentId: String,
        pageId: String,
        artifactRevision: Long,
        rawText: String,
    ): Int

    @Query(
        """
        UPDATE library_pages
        SET ocr_state_revision = ocr_state_revision + 1
        WHERE page_id = :pageId AND document_id = :documentId
        """,
    )
    protected abstract suspend fun incrementPageOcrState(documentId: String, pageId: String): Int

    @Query(
        """
        UPDATE library_pages
        SET active_ocr_artifact_revision = :activeArtifactRevision,
            ocr_state_revision = :ocrStateRevision,
            ocr_text = :ocrText,
            ocr_error = :ocrError
        WHERE page_id = :pageId AND document_id = :documentId
        """,
    )
    protected abstract suspend fun setClonedOcrState(
        documentId: String,
        pageId: String,
        activeArtifactRevision: Long?,
        ocrStateRevision: Long,
        ocrText: String?,
        ocrError: String?,
    ): Int

    @Query(
        """
        UPDATE library_pages
        SET ocr_state_revision = ocr_state_revision + 1,
            ocr_error = :safeErrorCode
        WHERE page_id = :pageId AND document_id = :documentId
        """,
    )
    protected abstract suspend fun recordPageOcrFailure(
        documentId: String,
        pageId: String,
        safeErrorCode: String,
    ): Int

    @Transaction
    open suspend fun commitOcrArtifact(
        expected: LibraryOcrPageSnapshot,
        draft: LibraryOcrArtifactDraft,
        modifiedAt: Long,
    ): LibraryOcrCommitResult {
        val current = ocrPageSnapshot(expected.documentId, expected.pageId)
            ?: return LibraryOcrCommitResult.NOT_FOUND
        if (!current.matches(expected) ||
            current.contentSha256 != draft.contentSha256 ||
            current.rotationDegrees != draft.rotationDegrees ||
            current.filterName != draft.filterName
        ) {
            return LibraryOcrCommitResult.STALE
        }
        insertAndActivateVerifiedArtifact(expected, draft)
        refreshPageSearchContent(expected.pageId)
        updateStoredOcrStatus(expected.documentId, modifiedAt)
        bumpLibraryRevision(modifiedAt)
        return LibraryOcrCommitResult.APPLIED
    }

    @Transaction
    open suspend fun commitOcrArtifacts(
        documentId: String,
        outcomes: List<LibraryOcrPageOutcomeDraft>,
        modifiedAt: Long,
    ): LibraryOcrCommitResult {
        if (outcomes.isEmpty()) return LibraryOcrCommitResult.NOT_FOUND
        require(outcomes.all { it.expected.documentId == documentId })
        val current = ocrPageSnapshots(documentId)
        if (current.isEmpty()) return LibraryOcrCommitResult.NOT_FOUND
        if (current.size != outcomes.size ||
            current.map(LibraryOcrPageSnapshot::pageId) !=
            outcomes.map { outcome -> outcome.expected.pageId } ||
            current.zip(outcomes).any { (snapshot, outcome) -> !snapshot.matches(outcome.expected) } ||
            current.zip(outcomes).any { (snapshot, outcome) ->
                outcome.artifact?.let { draft ->
                    snapshot.contentSha256 != draft.contentSha256 ||
                        snapshot.rotationDegrees != draft.rotationDegrees ||
                        snapshot.filterName != draft.filterName
                } == true
            }
        ) {
            return LibraryOcrCommitResult.STALE
        }
        outcomes.forEach { outcome ->
            val draft = outcome.artifact
            if (draft != null) {
                insertAndActivateVerifiedArtifact(outcome.expected, draft)
            } else {
                check(
                    recordPageOcrFailure(
                        documentId,
                        outcome.expected.pageId,
                        requireNotNull(outcome.safeErrorCode),
                    ) == 1,
                )
            }
            refreshPageSearchContent(outcome.expected.pageId)
        }
        updateStoredOcrStatus(documentId, modifiedAt)
        bumpLibraryRevision(modifiedAt)
        return LibraryOcrCommitResult.APPLIED
    }

    private suspend fun insertAndActivateVerifiedArtifact(
        expected: LibraryOcrPageSnapshot,
        draft: LibraryOcrArtifactDraft,
    ) {
        val revision = maximumArtifactRevision(expected.pageId) + 1L
        insertOcrArtifact(
            LibraryPageOcrArtifactEntity(
                pageId = expected.pageId,
                artifactRevision = revision,
                capturedDocumentContentRevision = expected.documentContentRevision,
                capturedPageVisualRevision = expected.pageVisualRevision,
                inputFingerprintVersion = draft.inputFingerprintVersion,
                inputFingerprint = draft.inputFingerprint,
                verificationState = LibraryOcrArtifactVerification.CURRENT_VERIFIED.name,
                contentSha256 = draft.contentSha256,
                rotationDegrees = draft.rotationDegrees,
                filterName = draft.filterName,
                uprightWidth = draft.uprightWidth,
                uprightHeight = draft.uprightHeight,
                coordinateSystemVersion = draft.coordinateSystemVersion,
                transformVersion = draft.transformVersion,
                actualScript = draft.actualScript,
                recognizerId = draft.recognizerId,
                pipelineVersion = draft.pipelineVersion,
                clientVersion = draft.clientVersion,
                delivery = draft.delivery,
                recognizedAtMillis = draft.recognizedAtMillis,
                rawText = draft.rawText,
            ),
        )
        if (draft.lines.isNotEmpty()) {
            insertOcrLines(draft.lines.map { line -> line.toEntity(expected.pageId, revision) })
        }
        check(activateOcrArtifact(expected.documentId, expected.pageId, revision, draft.rawText) == 1)
    }

    @Transaction
    open suspend fun saveOcrCorrection(
        expected: LibraryOcrPageSnapshot,
        correction: LibraryOcrCorrectionDraft,
        modifiedAt: Long,
    ): LibraryOcrCommitResult {
        val current = ocrPageSnapshot(expected.documentId, expected.pageId)
            ?: return LibraryOcrCommitResult.NOT_FOUND
        if (!current.matches(expected)) return LibraryOcrCommitResult.STALE
        val artifactRevision = current.activeArtifactRevision
            ?: return LibraryOcrCommitResult.NOT_FOUND
        val rawLines = ocrLines(current.pageId, artifactRevision)
        if (correction.alignment == LibraryOcrCorrectionAlignment.LINE_ALIGNED) {
            require(correction.correctedLines.size == rawLines.size)
            require(correction.correctedLines.joinToString("\n") == correction.correctedText)
        }
        deleteOcrCorrectionLines(current.pageId)
        deleteOcrCorrection(current.pageId)
        upsertOcrCorrection(
            LibraryPageOcrCorrectionEntity(
                pageId = current.pageId,
                baseArtifactRevision = artifactRevision,
                correctedText = correction.correctedText,
                correctedAtMillis = modifiedAt,
                alignmentState = correction.alignment.name,
            ),
        )
        if (correction.alignment == LibraryOcrCorrectionAlignment.LINE_ALIGNED) {
            insertOcrCorrectionLines(
                correction.correctedLines.mapIndexed { index, text ->
                    LibraryPageOcrCorrectionLineEntity(
                        pageId = current.pageId,
                        baseArtifactRevision = artifactRevision,
                        lineOrdinal = index,
                        correctedText = text,
                    )
                },
            )
        }
        check(incrementPageOcrState(current.documentId, current.pageId) == 1)
        refreshPageSearchContent(current.pageId)
        bumpLibraryRevision(modifiedAt)
        return LibraryOcrCommitResult.APPLIED
    }

    @Transaction
    open suspend fun revertOcrCorrection(
        expected: LibraryOcrPageSnapshot,
        modifiedAt: Long,
    ): LibraryOcrCommitResult {
        val current = ocrPageSnapshot(expected.documentId, expected.pageId)
            ?: return LibraryOcrCommitResult.NOT_FOUND
        if (!current.matches(expected)) return LibraryOcrCommitResult.STALE
        if (deleteOcrCorrection(current.pageId) == 0) return LibraryOcrCommitResult.NOT_FOUND
        check(incrementPageOcrState(current.documentId, current.pageId) == 1)
        refreshPageSearchContent(current.pageId)
        bumpLibraryRevision(modifiedAt)
        return LibraryOcrCommitResult.APPLIED
    }

    @Insert
    protected abstract suspend fun insertFolderInternal(entity: LibraryFolderEntity)

    @Update
    protected abstract suspend fun updateFolderInternal(entity: LibraryFolderEntity)

    @Transaction
    open suspend fun insertFolder(entity: LibraryFolderEntity) {
        insertFolderInternal(
            entity.copy(parentScope = libraryFolderParentScope(entity.parentFolderId)),
        )
        bumpLibraryRevision(entity.modifiedAtMillis)
    }

    @Transaction
    open suspend fun updateFolder(entity: LibraryFolderEntity) {
        updateFolderInternal(
            entity.copy(parentScope = libraryFolderParentScope(entity.parentFolderId)),
        )
        refreshAllDocumentSearchContent()
        bumpLibraryRevision(entity.modifiedAtMillis)
    }

    @Query("DELETE FROM library_pages WHERE page_id = :pageId AND document_id = :documentId")
    protected abstract suspend fun deletePage(documentId: String, pageId: String): Int

    @Query(
        """
        UPDATE library_pages
        SET page_position = -page_position - 1
        WHERE document_id = :documentId
        """,
    )
    protected abstract suspend fun movePagePositionsOutOfRange(documentId: String)

    @Query("DELETE FROM library_source_assets WHERE document_id = :documentId")
    protected abstract suspend fun deleteSourceAssets(documentId: String)

    @Query("DELETE FROM library_document_search_content_v3 WHERE rowid = :rowId")
    protected abstract suspend fun deleteSearch(rowId: Long)

    @Query("DELETE FROM library_documents WHERE document_id = :documentId AND library_state = 'ACTIVE'")
    protected abstract suspend fun deleteDocumentRow(documentId: String): Int

    @Query(
        """
        UPDATE library_documents
        SET folder_id = :parentFolderId, modified_at = :modifiedAt
        WHERE folder_id = :folderId AND library_state = 'ACTIVE'
        """,
    )
    protected abstract suspend fun moveFolderDocumentsToParent(
        folderId: String,
        parentFolderId: String?,
        modifiedAt: Long,
    )

    @Query(
        """
        UPDATE library_folders
        SET parent_folder_id = :parentFolderId,
            parent_scope = :parentScope,
            modified_at = :modifiedAt
        WHERE parent_scope = :folderId
        """,
    )
    protected abstract suspend fun reparentChildFolders(
        folderId: String,
        parentFolderId: String?,
        parentScope: String,
        modifiedAt: Long,
    )

    @Query("DELETE FROM library_folders WHERE folder_id = :folderId")
    protected abstract suspend fun deleteFolderRow(folderId: String)

    @Query(
        """
        SELECT EXISTS(
            SELECT 1
            FROM library_folders AS child
            JOIN library_folders AS sibling
              ON sibling.parent_scope = :targetParentScope
             AND sibling.normalized_name = child.normalized_name
             AND sibling.folder_id != :folderId
            WHERE child.parent_folder_id = :folderId
        )
        """,
    )
    abstract suspend fun folderDeletionWouldConflict(
        folderId: String,
        targetParentScope: String,
    ): Boolean

    @Query(
        """
        UPDATE library_documents
        SET folder_id = :folderId, modified_at = :modifiedAt
        WHERE document_id = :documentId AND library_state = 'ACTIVE'
        """,
    )
    protected abstract suspend fun moveDocumentRow(
        documentId: String,
        folderId: String?,
        modifiedAt: Long,
    ): Int

    @Transaction
    open suspend fun moveDocument(documentId: String, folderId: String?, modifiedAt: Long): Int {
        val changed = moveDocumentRow(documentId, folderId, modifiedAt)
        if (changed == 1) {
            refreshDocumentSearchContent(requireNotNull(document(documentId)))
            bumpLibraryRevision(modifiedAt)
        }
        return changed
    }

    @Query(
        """
        UPDATE library_documents
        SET title = :title, modified_at = :modifiedAt
        WHERE document_id = :documentId AND library_state = 'ACTIVE'
        """,
    )
    protected abstract suspend fun updateDocumentTitle(
        documentId: String,
        title: String,
        modifiedAt: Long,
    ): Int

    @Query("UPDATE library_document_search_content_v3 SET title = :title WHERE rowid = :rowId")
    protected abstract suspend fun updateSearchTitle(rowId: Long, title: String)

    @Query(
        """
        UPDATE library_pages
        SET ocr_text = :ocrText, ocr_error = :ocrError
        WHERE document_id = :documentId AND page_id = :pageId
        """,
    )
    protected abstract suspend fun updatePageOcr(
        documentId: String,
        pageId: String,
        ocrText: String?,
        ocrError: String?,
    ): Int

    @Query(
        """
        UPDATE library_documents
        SET ocr_status = :ocrStatus, modified_at = :modifiedAt
        WHERE document_id = :documentId AND library_state = 'ACTIVE'
        """,
    )
    protected abstract suspend fun updateDocumentOcrStatus(
        documentId: String,
        ocrStatus: String,
        modifiedAt: Long,
    ): Int

    @Transaction
    open suspend fun replaceDocument(
        document: LibraryDocumentEntity,
        pages: List<LibraryPageEntity>,
        @Suppress("UNUSED_PARAMETER")
        ocrText: String,
        sourceAssets: List<LibrarySourceAssetEntity> = emptyList(),
        ocrCloneSources: Map<String, String> = emptyMap(),
    ): LibraryDocumentEntity {
        require(document.libraryState == LibraryDocumentState.ACTIVE.name)
        require(document.pendingOperationId == null)
        require(pages.isNotEmpty())
        require(pages.all { it.documentId == document.documentId })
        require(pages.map(LibraryPageEntity::pageId).distinct().size == pages.size)
        require(pages.map(LibraryPageEntity::position) == pages.indices.toList())
        require(ocrCloneSources.keys.all { targetId -> pages.any { it.pageId == targetId } })
        require(ocrCloneSources.values.distinct().size == ocrCloneSources.size)
        val stored = documentAnyState(document.documentId)
        check(stored == null || stored.libraryState == LibraryDocumentState.ACTIVE.name)
        val storedPages = pagesAnyState(document.documentId)
        val storedPageById = storedPages.associateBy(LibraryPageEntity::pageId)
        val storedAssets = this.sourceAssets(document.documentId)
        val contentChanged = stored != null && (
            storedPages.map(LibraryPageEntity::pageId) != pages.map(LibraryPageEntity::pageId) ||
                pages.any { incoming ->
                    val previous = storedPageById[incoming.pageId]
                    previous == null || previous.contentSha256 != incoming.contentSha256 ||
                        previous.rotationDegrees != incoming.rotationDegrees ||
                        previous.filterName != incoming.filterName
                } || sourceAssetIdentity(storedAssets) != sourceAssetIdentity(sourceAssets)
            )
        val storedDocument = document.copy(
            rowId = stored?.rowId ?: document.rowId,
            contentRevision = when {
                stored == null -> document.contentRevision
                contentChanged -> stored.contentRevision + 1
                else -> stored.contentRevision
            },
            ocrScriptPreference = document.ocrScriptPreference ?: stored?.ocrScriptPreference,
        )
        val rowId = if (stored == null) {
            insertDocument(storedDocument)
        } else {
            updateDocument(storedDocument)
            stored.rowId
        }

        if (storedPages.isNotEmpty()) movePagePositionsOutOfRange(document.documentId)
        val incomingPageIds = pages.mapTo(hashSetOf(), LibraryPageEntity::pageId)
        storedPages.filterNot { it.pageId in incomingPageIds }.forEach { removed ->
            check(deletePage(document.documentId, removed.pageId) == 1)
        }
        pages.forEach { incoming ->
            val previous = storedPageById[incoming.pageId]
            val cloneSourceId = ocrCloneSources[incoming.pageId]
            require(previous == null || cloneSourceId == null)
            val visualChanged = previous != null && (
                previous.contentSha256 != incoming.contentSha256 ||
                    previous.rotationDegrees != incoming.rotationDegrees ||
                    previous.filterName != incoming.filterName
                )
            val successfulRawReplacement = incoming.ocrError == null && incoming.ocrText != null &&
                cloneSourceId == null &&
                (previous == null || previous.activeOcrArtifactRevision == null ||
                    previous.ocrText != incoming.ocrText)
            val storedPage = if (previous == null) {
                incoming.copy(
                    visualRevision = 0,
                    ocrStateRevision = 0,
                    activeOcrArtifactRevision = null,
                    ocrText = null,
                )
            } else {
                incoming.copy(
                    visualRevision = previous.visualRevision + if (visualChanged) 1 else 0,
                    ocrStateRevision = previous.ocrStateRevision + if (visualChanged) 1 else 0,
                    activeOcrArtifactRevision = if (visualChanged) {
                        null
                    } else {
                        previous.activeOcrArtifactRevision
                    },
                    ocrText = if (visualChanged) null else previous.ocrText,
                    ocrError = if (visualChanged) incoming.ocrError else incoming.ocrError ?: previous.ocrError,
                )
            }
            if (previous == null) {
                check(insertPageIfAbsent(storedPage) != -1L)
            } else {
                check(updatePage(storedPage) == 1)
            }
            if (visualChanged) {
                deleteOcrCorrectionLines(incoming.pageId)
                deleteOcrCorrection(incoming.pageId)
            }
            if (cloneSourceId != null) {
                cloneOcrState(
                    sourcePageId = cloneSourceId,
                    targetPage = requireNotNull(pageAnyState(incoming.pageId)),
                    targetDocumentContentRevision = storedDocument.contentRevision,
                )
            } else if (successfulRawReplacement) {
                createLegacyArtifact(
                    page = requireNotNull(pageAnyState(incoming.pageId)),
                    rawText = requireNotNull(incoming.ocrText),
                )
            } else if (previous == null && incoming.ocrError != null) {
                check(updatePage(storedPage.copy(ocrError = incoming.ocrError)) == 1)
            }
        }

        deleteSourceAssets(document.documentId)
        if (sourceAssets.isNotEmpty()) insertSourceAssetsInternal(sourceAssets)
        deleteSearch(rowId)
        val finalDocument = storedDocument.copy(
            rowId = rowId,
            ocrStatus = ocrStatusForStoredPages(pagesAnyState(document.documentId)),
        )
        updateDocument(finalDocument)
        refreshDocumentSearchContent(finalDocument)
        pages.forEach { page -> refreshPageSearchContent(page.pageId) }
        bumpLibraryRevision(document.modifiedAtMillis)
        return finalDocument
    }

    @Transaction
    open suspend fun deleteDocument(documentId: String): LibraryDocumentEntity? {
        val existing = document(documentId) ?: return null
        deleteSearch(existing.rowId)
        if (deleteDocumentRow(documentId) != 1) return null
        bumpLibraryRevision(existing.modifiedAtMillis)
        return existing
    }

    @Transaction
    open suspend fun deleteFolder(folderId: String, modifiedAt: Long): Boolean {
        val existing = folder(folderId) ?: return false
        moveFolderDocumentsToParent(folderId, existing.parentFolderId, modifiedAt)
        deleteFolderRow(folderId)
        reparentChildFolders(
            folderId = folderId,
            parentFolderId = existing.parentFolderId,
            parentScope = libraryFolderParentScope(existing.parentFolderId),
            modifiedAt = modifiedAt,
        )
        refreshAllDocumentSearchContent()
        bumpLibraryRevision(modifiedAt)
        return true
    }

    @Transaction
    open suspend fun renameDocument(documentId: String, title: String, modifiedAt: Long): Boolean {
        val existing = document(documentId) ?: return false
        if (updateDocumentTitle(documentId, title, modifiedAt) != 1) return false
        updateSearchTitle(existing.rowId, title)
        bumpLibraryRevision(modifiedAt)
        return true
    }

    @Transaction
    open suspend fun replaceOcr(
        documentId: String,
        pages: List<Pair<String, Pair<String?, String?>>>,
        ocrStatus: String,
        modifiedAt: Long,
    ): Boolean {
        val existing = document(documentId) ?: return false
        val storedPages = pages(documentId)
        if (storedPages.size != pages.size) return false
        if (storedPages.map(LibraryPageEntity::pageId) != pages.map { it.first }) return false
        pages.forEach { (pageId, values) ->
            val storedPage = requireNotNull(pageAnyState(pageId))
            val (rawText, error) = values
            if (error == null && rawText != null) {
                createLegacyArtifact(storedPage, rawText)
            } else {
                check(
                    updatePageOcr(
                        documentId = documentId,
                        pageId = pageId,
                        ocrText = storedPage.ocrText,
                        ocrError = error,
                    ) == 1,
                )
            }
            refreshPageSearchContent(pageId)
        }
        @Suppress("UNUSED_VARIABLE")
        val ignoredLegacyStatus = ocrStatus
        updateStoredOcrStatus(documentId, modifiedAt)
        bumpLibraryRevision(modifiedAt)
        return true
    }

    private suspend fun createLegacyArtifact(page: LibraryPageEntity, rawText: String) {
        val revision = maximumArtifactRevision(page.pageId) + 1L
        insertOcrArtifact(
            LibraryPageOcrArtifactEntity(
                pageId = page.pageId,
                artifactRevision = revision,
                capturedDocumentContentRevision = null,
                capturedPageVisualRevision = null,
                inputFingerprintVersion = null,
                inputFingerprint = null,
                verificationState = LibraryOcrArtifactVerification.LEGACY_UNVERIFIED.name,
                contentSha256 = null,
                rotationDegrees = null,
                filterName = null,
                uprightWidth = null,
                uprightHeight = null,
                coordinateSystemVersion = null,
                transformVersion = null,
                actualScript = "LATIN",
                recognizerId = LEGACY_OCR_RECOGNIZER_ID,
                pipelineVersion = null,
                clientVersion = null,
                delivery = null,
                recognizedAtMillis = null,
                rawText = rawText,
            ),
        )
        check(activateOcrArtifact(page.documentId, page.pageId, revision, rawText) == 1)
    }

    private suspend fun cloneOcrState(
        sourcePageId: String,
        targetPage: LibraryPageEntity,
        targetDocumentContentRevision: Long,
    ) {
        val source = requireNotNull(pageAnyState(sourcePageId))
        require(sourcePageId != targetPage.pageId)
        require(source.contentSha256 == targetPage.contentSha256)
        require(source.rotationDegrees == targetPage.rotationDegrees)
        require(source.filterName == targetPage.filterName)
        val artifacts = ocrArtifacts(sourcePageId)
        val artifactRevisions = artifacts.mapTo(hashSetOf()) { it.artifactRevision }
        check(source.activeOcrArtifactRevision == null || source.activeOcrArtifactRevision in artifactRevisions)
        artifacts.forEach { artifact ->
            insertOcrArtifact(
                artifact.copy(
                    pageId = targetPage.pageId,
                    capturedDocumentContentRevision = artifact.capturedDocumentContentRevision?.let {
                        targetDocumentContentRevision
                    },
                    capturedPageVisualRevision = artifact.capturedPageVisualRevision?.let {
                        targetPage.visualRevision
                    },
                ),
            )
            val lines = ocrLines(sourcePageId, artifact.artifactRevision)
            if (lines.isNotEmpty()) {
                insertOcrLines(lines.map { line -> line.copy(pageId = targetPage.pageId) })
            }
        }
        val correction = ocrCorrection(sourcePageId)
        if (correction != null) {
            check(correction.baseArtifactRevision in artifactRevisions)
            upsertOcrCorrection(correction.copy(pageId = targetPage.pageId))
            val correctionLines = ocrCorrectionLines(sourcePageId)
            if (correctionLines.isNotEmpty()) {
                insertOcrCorrectionLines(
                    correctionLines.map { line -> line.copy(pageId = targetPage.pageId) },
                )
            }
        }
        val activeRaw = source.activeOcrArtifactRevision?.let { revision ->
            requireNotNull(artifacts.firstOrNull { it.artifactRevision == revision }).rawText
        }
        check(
            setClonedOcrState(
                documentId = targetPage.documentId,
                pageId = targetPage.pageId,
                activeArtifactRevision = source.activeOcrArtifactRevision,
                ocrStateRevision = source.ocrStateRevision,
                ocrText = activeRaw,
                ocrError = source.ocrError,
            ) == 1,
        )
    }

    private suspend fun refreshPageSearchContent(pageId: String) {
        val page = pageAnyState(pageId) ?: return
        val activeRevision = page.activeOcrArtifactRevision
        if (activeRevision == null) {
            pageSearchContent(pageId)?.let { existing ->
                upsertPageSearchContent(
                    existing.copy(effectiveText = "", auxiliaryTerms = ""),
                )
            }
            return
        }
        val active = requireNotNull(ocrArtifact(pageId, activeRevision))
        val effectiveText = ocrCorrection(pageId)?.correctedText ?: active.rawText
        val existing = pageSearchContent(pageId)
        upsertPageSearchContent(
            LibraryPageSearchContentEntity(
                rowId = existing?.rowId ?: 0,
                pageId = pageId,
                effectiveText = effectiveText,
                auxiliaryTerms = searchAuxiliaryTerms(effectiveText),
            ),
        )
    }

    private suspend fun refreshDocumentSearchContent(document: LibraryDocumentEntity) {
        val folderPath = canonicalFolderPath(document.folderId)
        upsertDocumentSearchContent(
            LibraryDocumentSearchContentEntity(
                rowId = document.rowId,
                documentId = document.documentId,
                title = document.title,
                folderPath = folderPath,
                auxiliaryTerms = searchAuxiliaryTerms("${document.title} $folderPath"),
            ),
        )
    }

    private suspend fun refreshAllDocumentSearchContent() {
        var afterRowId = 0L
        while (true) {
            val batch = activeDocumentsPage(afterRowId, SEARCH_REFRESH_BATCH_SIZE)
            if (batch.isEmpty()) return
            batch.forEach { refreshDocumentSearchContent(it) }
            afterRowId = batch.last().rowId
        }
    }

    private suspend fun canonicalFolderPath(folderId: String?): String {
        val names = mutableListOf<String>()
        val visited = mutableSetOf<String>()
        var current = folderId
        while (current != null && visited.add(current)) {
            val stored = folder(current) ?: break
            names += stored.name
            current = stored.parentFolderId
        }
        return names.asReversed().joinToString("/")
    }

    private suspend fun updateStoredOcrStatus(documentId: String, modifiedAt: Long) {
        check(
            updateDocumentOcrStatus(
                documentId,
                ocrStatusForStoredPages(pagesAnyState(documentId)),
                modifiedAt,
            ) == 1,
        )
    }

    @Insert
    protected abstract suspend fun insertOcrBatchJobInternal(entity: OcrBatchJobEntity)

    @Insert
    protected abstract suspend fun insertOcrBatchItemsInternal(entities: List<OcrBatchItemEntity>)

    @Update
    protected abstract suspend fun updateOcrBatchJobInternal(entity: OcrBatchJobEntity): Int

    @Query("SELECT * FROM ocr_batch_jobs WHERE job_id = :jobId LIMIT 1")
    abstract suspend fun ocrBatchJob(jobId: String): OcrBatchJobEntity?

    @Query("SELECT * FROM ocr_batch_items WHERE job_id = :jobId ORDER BY ordinal")
    abstract suspend fun ocrBatchItems(jobId: String): List<OcrBatchItemEntity>

    @Query(
        """
        SELECT * FROM ocr_batch_items
        WHERE job_id = :jobId
          AND state IN ('PENDING', 'RETRY_PENDING', 'WAITING_FOR_MODEL')
        ORDER BY ordinal
        LIMIT 1
        """,
    )
    abstract suspend fun nextOcrBatchItem(jobId: String): OcrBatchItemEntity?

    @Query(
        """
        SELECT
          COUNT(DISTINCT document_id) AS documentTotal,
          COUNT(DISTINCT CASE WHEN NOT EXISTS (
            SELECT 1 FROM ocr_batch_items AS pending
            WHERE pending.job_id = items.job_id
              AND pending.document_id = items.document_id
              AND pending.state NOT IN ('COMPLETED', 'FAILED', 'SKIPPED')
          ) THEN document_id END) AS completedDocuments,
          COALESCE(SUM(CASE WHEN (state = 'FAILED' OR
            (state = 'SKIPPED' AND safe_error_code = 'STALE_INPUT')) AND EXISTS (
              SELECT 1 FROM library_pages AS retry_page
              JOIN library_documents AS retry_document
                ON retry_document.document_id = retry_page.document_id
              WHERE retry_page.page_id = items.page_id
                AND retry_page.document_id = items.document_id
                AND retry_document.library_state = 'ACTIVE'
            ) THEN 1 ELSE 0 END), 0) AS retryableItems
        FROM ocr_batch_items AS items
        WHERE job_id = :jobId
        """,
    )
    abstract suspend fun ocrBatchProgress(jobId: String): OcrBatchProgressSnapshot

    @Query(
        """
        SELECT * FROM ocr_batch_items
        WHERE job_id = :jobId
          AND (state = 'FAILED' OR (state = 'SKIPPED' AND safe_error_code = 'STALE_INPUT'))
          AND EXISTS (
            SELECT 1 FROM library_pages AS retry_page
            JOIN library_documents AS retry_document
              ON retry_document.document_id = retry_page.document_id
            WHERE retry_page.page_id = ocr_batch_items.page_id
              AND retry_page.document_id = ocr_batch_items.document_id
              AND retry_document.library_state = 'ACTIVE'
          )
        ORDER BY ordinal
        LIMIT :limit
        """,
    )
    abstract suspend fun retryableOcrBatchItems(
        jobId: String,
        limit: Int,
    ): List<OcrBatchItemEntity>

    @Query(
        """
        SELECT * FROM ocr_batch_jobs
        WHERE target_population_complete = 1
          AND cancel_requested = 0
          AND state IN ('READY', 'PROCESSING', 'INTERRUPTED')
        ORDER BY updated_at DESC
        LIMIT 1
        """,
    )
    abstract suspend fun latestRecoverableOcrBatchJob(): OcrBatchJobEntity?

    @Query("SELECT title FROM library_documents WHERE document_id = :documentId LIMIT 1")
    abstract suspend fun documentTitle(documentId: String): String?

    @Query("SELECT * FROM ocr_batch_items WHERE item_id = :itemId AND job_id = :jobId LIMIT 1")
    protected abstract suspend fun ocrBatchItem(jobId: String, itemId: String): OcrBatchItemEntity?

    @Query("SELECT COUNT(*) FROM ocr_batch_items WHERE job_id = :jobId")
    protected abstract suspend fun ocrBatchItemCount(jobId: String): Int

    @Query(
        """
        DELETE FROM ocr_batch_jobs
        WHERE job_id = :jobId AND target_population_complete = 0
        """,
    )
    protected abstract suspend fun deleteIncompleteOcrBatchJob(jobId: String): Int

    @Transaction
    open suspend fun beginOcrBatchTargetPopulation(job: OcrBatchJobEntity): Boolean {
        require(job.state == OcrBatchJobState.READY.name)
        require(!job.cancelRequested && !job.targetPopulationComplete)
        if (ocrBatchJob(job.jobId) != null) return false
        insertOcrBatchJobInternal(
            job.copy(
                totalItemCount = 0,
                completedItemCount = 0,
                failedItemCount = 0,
                skippedItemCount = 0,
                targetPopulationComplete = false,
            ),
        )
        return true
    }

    /**
     * Adds one bounded, contiguous target page to an incomplete durable population. Claims remain
     * disabled until [finishOcrBatchTargetPopulation] commits the population-complete marker.
     */
    @Transaction
    open suspend fun appendOcrBatchTargets(
        jobId: String,
        targets: List<OcrBatchTarget>,
        updatedAt: Long,
    ): Boolean {
        require(targets.size in 1..MAX_OCR_BATCH_TARGET_CHUNK_SIZE)
        require(targets.map(OcrBatchTarget::itemId).distinct().size == targets.size)
        require(targets.map(OcrBatchTarget::pageId).distinct().size == targets.size)
        val job = ocrBatchJob(jobId) ?: return false
        if (job.state != OcrBatchJobState.READY.name || job.cancelRequested ||
            job.targetPopulationComplete
        ) {
            return false
        }
        val storedCount = ocrBatchItemCount(jobId)
        check(storedCount == job.totalItemCount)
        require(targets.map(OcrBatchTarget::ordinal) ==
            (storedCount until storedCount + targets.size).toList())

        val items = ArrayList<OcrBatchItemEntity>(targets.size)
        targets.forEach { target ->
            val snapshot = ocrPageSnapshot(target.documentId, target.pageId) ?: return false
            items += target.toBatchItem(jobId, snapshot)
        }
        insertOcrBatchItemsInternal(items)
        check(
            updateOcrBatchJobInternal(
                job.copy(
                    totalItemCount = storedCount + items.size,
                    updatedAtMillis = updatedAt,
                ),
            ) == 1,
        )
        return true
    }

    @Transaction
    open suspend fun finishOcrBatchTargetPopulation(jobId: String, updatedAt: Long): Boolean {
        val job = ocrBatchJob(jobId) ?: return false
        if (job.state != OcrBatchJobState.READY.name || job.cancelRequested ||
            job.targetPopulationComplete
        ) {
            return false
        }
        check(ocrBatchItemCount(jobId) == job.totalItemCount)
        check(
            updateOcrBatchJobInternal(
                job.copy(
                    state = if (job.totalItemCount == 0) {
                        OcrBatchJobState.COMPLETED.name
                    } else {
                        OcrBatchJobState.READY.name
                    },
                    targetPopulationComplete = true,
                    updatedAtMillis = updatedAt,
                ),
            ) == 1,
        )
        return true
    }

    suspend fun discardIncompleteOcrBatchTargetPopulation(jobId: String): Boolean =
        deleteIncompleteOcrBatchJob(jobId) == 1

    @Transaction
    open suspend fun freezeOcrBatchTargets(
        job: OcrBatchJobEntity,
        targets: List<OcrBatchTarget>,
    ): Boolean {
        require(targets.map(OcrBatchTarget::itemId).distinct().size == targets.size)
        require(targets.map(OcrBatchTarget::pageId).distinct().size == targets.size)
        require(targets.map(OcrBatchTarget::ordinal) == targets.indices.toList())
        if (!beginOcrBatchTargetPopulation(job)) return false
        var offset = 0
        while (offset < targets.size) {
            val chunk = targets.subList(
                offset,
                minOf(offset + MAX_OCR_BATCH_TARGET_CHUNK_SIZE, targets.size),
            )
            if (!appendOcrBatchTargets(job.jobId, chunk, job.updatedAtMillis)) {
                check(deleteIncompleteOcrBatchJob(job.jobId) == 1)
                return false
            }
            offset += chunk.size
        }
        if (!finishOcrBatchTargetPopulation(job.jobId, job.updatedAtMillis)) {
            check(deleteIncompleteOcrBatchJob(job.jobId) == 1)
            return false
        }
        return true
    }

    @Query(
        """
        UPDATE ocr_batch_items
        SET state = 'PROCESSING',
            attempt_number = attempt_number + 1,
            claim_generation = :generation,
            claim_token = :claimToken,
            safe_error_code = NULL
        WHERE item_id = :itemId
          AND job_id = :jobId
          AND state IN ('PENDING', 'RETRY_PENDING', 'WAITING_FOR_MODEL')
          AND EXISTS (
              SELECT 1
              FROM ocr_batch_jobs AS j
              JOIN library_pages AS p ON p.page_id = ocr_batch_items.page_id
              JOIN library_documents AS d ON d.document_id = p.document_id
              WHERE j.job_id = ocr_batch_items.job_id
                AND j.run_generation = :generation
                AND j.cancel_requested = 0
                AND j.target_population_complete = 1
                AND j.state IN ('READY', 'PROCESSING')
                AND d.library_state = 'ACTIVE'
                AND d.document_id = ocr_batch_items.document_id
                AND d.content_revision = ocr_batch_items.expected_document_content_revision
                AND p.visual_revision = ocr_batch_items.expected_page_visual_revision
                AND p.ocr_state_revision = ocr_batch_items.expected_ocr_state_revision
                AND p.active_ocr_artifact_revision IS ocr_batch_items.expected_active_artifact_revision
          )
        """,
    )
    protected abstract suspend fun claimOcrBatchItemRow(
        jobId: String,
        itemId: String,
        generation: Long,
        claimToken: String,
    ): Int

    @Query(
        """
        UPDATE ocr_batch_jobs
        SET state = 'PROCESSING', updated_at = :updatedAt
        WHERE job_id = :jobId AND run_generation = :generation
          AND cancel_requested = 0 AND state IN ('READY', 'PROCESSING')
        """,
    )
    protected abstract suspend fun markOcrBatchProcessing(
        jobId: String,
        generation: Long,
        updatedAt: Long,
    ): Int

    @Query(
        """
        UPDATE ocr_batch_items
        SET state = 'SKIPPED', claim_generation = NULL, claim_token = NULL,
            safe_error_code = 'STALE_INPUT'
        WHERE item_id = :itemId AND job_id = :jobId
          AND state IN ('PENDING', 'RETRY_PENDING', 'WAITING_FOR_MODEL')
          AND EXISTS (
              SELECT 1 FROM ocr_batch_jobs AS j
              WHERE j.job_id = :jobId AND j.run_generation = :generation
                AND j.cancel_requested = 0 AND j.target_population_complete = 1
                AND j.state IN ('READY', 'PROCESSING')
          )
        """,
    )
    protected abstract suspend fun skipUnclaimableOcrBatchItem(
        jobId: String,
        itemId: String,
        generation: Long,
    ): Int

    @Transaction
    open suspend fun claimOcrBatchItem(
        jobId: String,
        itemId: String,
        generation: Long,
        claimToken: String,
        updatedAt: Long,
    ): OcrBatchClaim? {
        require(claimToken.isNotBlank())
        val job = ocrBatchJob(jobId) ?: return null
        if (job.runGeneration != generation || job.cancelRequested ||
            !job.targetPopulationComplete ||
            job.state !in setOf(OcrBatchJobState.READY.name, OcrBatchJobState.PROCESSING.name)
        ) {
            return null
        }
        val pendingItem = ocrBatchItem(jobId, itemId) ?: return null
        if (pendingItem.state !in setOf(
                OcrBatchItemState.PENDING.name,
                OcrBatchItemState.RETRY_PENDING.name,
                OcrBatchItemState.WAITING_FOR_MODEL.name,
            )
        ) {
            return null
        }
        val snapshot = ocrPageSnapshot(pendingItem.documentId, pendingItem.pageId)
        if (snapshot == null || !pendingItem.matches(snapshot)) {
            check(skipUnclaimableOcrBatchItem(jobId, itemId, generation) == 1)
            check(settleOcrBatchJob(jobId, generation, updatedAt) == 1)
            return null
        }
        if (claimOcrBatchItemRow(jobId, itemId, generation, claimToken) != 1) return null
        check(markOcrBatchProcessing(jobId, generation, updatedAt) == 1)
        val item = requireNotNull(ocrBatchItem(jobId, itemId))
        return OcrBatchClaim(
            jobId = jobId,
            itemId = itemId,
            documentId = item.documentId,
            pageId = item.pageId,
            runGeneration = generation,
            claimToken = claimToken,
            requestedScriptSelection = item.requestedScriptSelection,
            resolvedScript = item.resolvedScript,
            expected = snapshot,
            expectedInputFingerprintVersion = item.expectedInputFingerprintVersion,
            expectedInputFingerprint = item.expectedInputFingerprint,
        )
    }

    @Query(
        """
        UPDATE ocr_batch_items
        SET state = :state, claim_generation = NULL, claim_token = NULL,
            safe_error_code = :safeErrorCode
        WHERE job_id = :jobId AND item_id = :itemId
          AND state = 'PROCESSING'
          AND claim_generation = :generation AND claim_token = :claimToken
          AND EXISTS (
              SELECT 1 FROM ocr_batch_jobs AS j
              WHERE j.job_id = :jobId AND j.run_generation = :generation
                AND j.cancel_requested = 0 AND j.state = 'PROCESSING'
          )
        """,
    )
    protected abstract suspend fun finishClaimedOcrBatchItem(
        jobId: String,
        itemId: String,
        generation: Long,
        claimToken: String,
        state: String,
        safeErrorCode: String?,
    ): Int

    @Query(
        """
        UPDATE ocr_batch_jobs
        SET completed_item_count = (
                SELECT COUNT(*) FROM ocr_batch_items
                WHERE job_id = :jobId AND state = 'COMPLETED'
            ),
            failed_item_count = (
                SELECT COUNT(*) FROM ocr_batch_items
                WHERE job_id = :jobId AND state = 'FAILED'
            ),
            skipped_item_count = (
                SELECT COUNT(*) FROM ocr_batch_items
                WHERE job_id = :jobId AND state = 'SKIPPED'
            ),
            state = CASE WHEN total_item_count = (
                SELECT COUNT(*) FROM ocr_batch_items
                WHERE job_id = :jobId AND state IN ('COMPLETED', 'FAILED', 'SKIPPED')
            ) THEN 'COMPLETED' ELSE state END,
            updated_at = :updatedAt
        WHERE job_id = :jobId AND run_generation = :generation
        """,
    )
    protected abstract suspend fun settleOcrBatchJob(
        jobId: String,
        generation: Long,
        updatedAt: Long,
    ): Int

    @Transaction
    open suspend fun completeOcrBatchItem(
        claim: OcrBatchClaim,
        draft: LibraryOcrArtifactDraft,
        modifiedAt: Long,
    ): OcrBatchCompletionResult {
        val item = ocrBatchItem(claim.jobId, claim.itemId)
            ?: return OcrBatchCompletionResult.NOT_FOUND
        if (item.state != OcrBatchItemState.PROCESSING.name ||
            item.claimGeneration != claim.runGeneration || item.claimToken != claim.claimToken
        ) {
            return OcrBatchCompletionResult.STALE_CLAIM
        }
        val result = commitOcrArtifact(claim.expected, draft, modifiedAt)
        val terminalState = if (result == LibraryOcrCommitResult.APPLIED) {
            OcrBatchItemState.COMPLETED.name
        } else {
            OcrBatchItemState.SKIPPED.name
        }
        val error = if (result == LibraryOcrCommitResult.APPLIED) null else OCR_BATCH_STALE_INPUT
        if (
            finishClaimedOcrBatchItem(
                claim.jobId,
                claim.itemId,
                claim.runGeneration,
                claim.claimToken,
                terminalState,
                error,
            ) != 1
        ) {
            error("OCR batch claim became stale")
        }
        check(settleOcrBatchJob(claim.jobId, claim.runGeneration, modifiedAt) == 1)
        return if (result == LibraryOcrCommitResult.APPLIED) {
            OcrBatchCompletionResult.APPLIED
        } else {
            OcrBatchCompletionResult.STALE_INPUT
        }
    }

    @Transaction
    open suspend fun failOcrBatchItem(
        claim: OcrBatchClaim,
        safeErrorCode: String,
        modifiedAt: Long,
    ): OcrBatchCompletionResult {
        require(safeErrorCode.isSafeOcrErrorCode())
        if (
            finishClaimedOcrBatchItem(
                claim.jobId,
                claim.itemId,
                claim.runGeneration,
                claim.claimToken,
                OcrBatchItemState.FAILED.name,
                safeErrorCode,
            ) != 1
        ) {
            return OcrBatchCompletionResult.STALE_CLAIM
        }
        check(settleOcrBatchJob(claim.jobId, claim.runGeneration, modifiedAt) == 1)
        return OcrBatchCompletionResult.APPLIED
    }

    @Transaction
    open suspend fun skipOcrBatchItem(
        claim: OcrBatchClaim,
        safeReasonCode: String,
        modifiedAt: Long,
    ): OcrBatchCompletionResult {
        require(safeReasonCode.isSafeOcrErrorCode())
        if (
            finishClaimedOcrBatchItem(
                claim.jobId,
                claim.itemId,
                claim.runGeneration,
                claim.claimToken,
                OcrBatchItemState.SKIPPED.name,
                safeReasonCode,
            ) != 1
        ) {
            return OcrBatchCompletionResult.STALE_CLAIM
        }
        check(settleOcrBatchJob(claim.jobId, claim.runGeneration, modifiedAt) == 1)
        return OcrBatchCompletionResult.APPLIED
    }

    @Query(
        """
        UPDATE ocr_batch_jobs
        SET state = 'READY', updated_at = :updatedAt
        WHERE job_id = :jobId AND run_generation = :generation
          AND cancel_requested = 0 AND state = 'PROCESSING'
        """,
    )
    protected abstract suspend fun pauseOcrBatchJobForModel(
        jobId: String,
        generation: Long,
        updatedAt: Long,
    ): Int

    @Transaction
    open suspend fun waitForOcrModel(
        claim: OcrBatchClaim,
        safeErrorCode: String,
        updatedAt: Long,
    ): OcrBatchCompletionResult {
        require(safeErrorCode.isSafeOcrErrorCode())
        if (
            finishClaimedOcrBatchItem(
                claim.jobId,
                claim.itemId,
                claim.runGeneration,
                claim.claimToken,
                OcrBatchItemState.WAITING_FOR_MODEL.name,
                safeErrorCode,
            ) != 1
        ) {
            return OcrBatchCompletionResult.STALE_CLAIM
        }
        check(pauseOcrBatchJobForModel(claim.jobId, claim.runGeneration, updatedAt) == 1)
        return OcrBatchCompletionResult.APPLIED
    }

    @Query(
        """
        UPDATE ocr_batch_items
        SET state = 'RETRY_PENDING', safe_error_code = NULL,
            claim_generation = NULL, claim_token = NULL
        WHERE job_id = :jobId AND item_id = :itemId
          AND state IN ('FAILED', 'WAITING_FOR_MODEL')
        """,
    )
    protected abstract suspend fun requestOcrBatchItemRetryRow(jobId: String, itemId: String): Int

    @Query(
        """
        UPDATE ocr_batch_items
        SET state = 'RETRY_PENDING', safe_error_code = NULL,
            claim_generation = NULL, claim_token = NULL,
            expected_document_content_revision = :documentContentRevision,
            expected_page_visual_revision = :pageVisualRevision,
            expected_ocr_state_revision = :ocrStateRevision,
            expected_active_artifact_revision = :activeArtifactRevision
        WHERE job_id = :jobId AND item_id = :itemId
          AND (state = 'FAILED' OR (state = 'SKIPPED' AND safe_error_code = 'STALE_INPUT'))
        """,
    )
    protected abstract suspend fun refreshOcrBatchItemForRetryRow(
        jobId: String,
        itemId: String,
        documentContentRevision: Long,
        pageVisualRevision: Long,
        ocrStateRevision: Long,
        activeArtifactRevision: Long?,
    ): Int

    @Query(
        """
        UPDATE ocr_batch_jobs
        SET state = 'READY',
            failed_item_count = (
                SELECT COUNT(*) FROM ocr_batch_items
                WHERE job_id = :jobId AND state = 'FAILED'
            ),
            skipped_item_count = (
                SELECT COUNT(*) FROM ocr_batch_items
                WHERE job_id = :jobId AND state = 'SKIPPED'
            ),
            completed_item_count = (
                SELECT COUNT(*) FROM ocr_batch_items
                WHERE job_id = :jobId AND state = 'COMPLETED'
            ),
            updated_at = :updatedAt
        WHERE job_id = :jobId AND cancel_requested = 0
          AND state IN ('READY', 'PROCESSING', 'COMPLETED', 'FAILED')
        """,
    )
    protected abstract suspend fun reopenOcrBatchJob(jobId: String, updatedAt: Long): Int

    @Transaction
    open suspend fun requestOcrBatchItemRetry(
        jobId: String,
        itemId: String,
        updatedAt: Long,
    ): Boolean {
        val job = ocrBatchJob(jobId) ?: return false
        if (job.cancelRequested || job.state == OcrBatchJobState.CANCELLED.name) return false
        if (requestOcrBatchItemRetryRow(jobId, itemId) != 1) return false
        check(reopenOcrBatchJob(jobId, updatedAt) == 1)
        return true
    }

    /** Retries only failed/stale work and refreshes its compare-and-set snapshot to current input. */
    @Transaction
    open suspend fun refreshOcrBatchItemForRetry(
        jobId: String,
        itemId: String,
        updatedAt: Long,
    ): Boolean {
        val job = ocrBatchJob(jobId) ?: return false
        if (job.cancelRequested || job.state == OcrBatchJobState.CANCELLED.name) return false
        val item = ocrBatchItem(jobId, itemId) ?: return false
        val snapshot = ocrPageSnapshot(item.documentId, item.pageId) ?: return false
        if (
            refreshOcrBatchItemForRetryRow(
                jobId = jobId,
                itemId = itemId,
                documentContentRevision = snapshot.documentContentRevision,
                pageVisualRevision = snapshot.pageVisualRevision,
                ocrStateRevision = snapshot.ocrStateRevision,
                activeArtifactRevision = snapshot.activeArtifactRevision,
            ) != 1
        ) return false
        check(reopenOcrBatchJob(jobId, updatedAt) == 1)
        return true
    }

    @Query(
        """
        UPDATE ocr_batch_items
        SET state = 'SKIPPED', claim_generation = NULL, claim_token = NULL,
            safe_error_code = 'CANCELLED'
        WHERE job_id = :jobId
          AND state IN ('PENDING', 'RETRY_PENDING', 'WAITING_FOR_MODEL', 'PROCESSING')
        """,
    )
    protected abstract suspend fun cancelOcrBatchItems(jobId: String): Int

    @Query(
        """
        UPDATE ocr_batch_jobs
        SET cancel_requested = 1, run_generation = run_generation + 1,
            state = 'CANCELLED',
            skipped_item_count = (
                SELECT COUNT(*) FROM ocr_batch_items
                WHERE job_id = :jobId AND state = 'SKIPPED'
            ),
            updated_at = :updatedAt
        WHERE job_id = :jobId AND state IN ('READY', 'PROCESSING')
        """,
    )
    protected abstract suspend fun cancelOcrBatchJobRow(jobId: String, updatedAt: Long): Int

    @Transaction
    open suspend fun cancelOcrBatchJob(jobId: String, updatedAt: Long): Boolean {
        if (ocrBatchJob(jobId)?.state !in setOf(
                OcrBatchJobState.READY.name,
                OcrBatchJobState.PROCESSING.name,
            )
        ) {
            return false
        }
        cancelOcrBatchItems(jobId)
        return cancelOcrBatchJobRow(jobId, updatedAt) == 1
    }

    @Query(
        """
        UPDATE ocr_batch_items
        SET state = 'RETRY_PENDING', claim_generation = NULL, claim_token = NULL
        WHERE job_id = :jobId AND state = 'PROCESSING'
        """,
    )
    protected abstract suspend fun resetInterruptedOcrBatchItems(jobId: String): Int

    @Query(
        """
        UPDATE ocr_batch_jobs
        SET run_generation = run_generation + 1, state = 'READY', updated_at = :updatedAt
        WHERE job_id = :jobId AND state IN ('PROCESSING', 'INTERRUPTED')
          AND cancel_requested = 0 AND target_population_complete = 1
        """,
    )
    protected abstract suspend fun recoverOcrBatchJobRow(jobId: String, updatedAt: Long): Int

    @Transaction
    open suspend fun recoverInterruptedOcrBatchJob(jobId: String, updatedAt: Long): OcrBatchJobEntity? {
        val job = ocrBatchJob(jobId) ?: return null
        if (job.state !in setOf(
                OcrBatchJobState.PROCESSING.name,
                OcrBatchJobState.INTERRUPTED.name,
            ) || job.cancelRequested ||
            !job.targetPopulationComplete
        ) {
            return null
        }
        resetInterruptedOcrBatchItems(jobId)
        check(recoverOcrBatchJobRow(jobId, updatedAt) == 1)
        return requireNotNull(ocrBatchJob(jobId))
    }

    @Query(
        """
        UPDATE ocr_batch_jobs
        SET state = 'INTERRUPTED', updated_at = :updatedAt
        WHERE state = 'PROCESSING' AND cancel_requested = 0
        """,
    )
    abstract suspend fun markInterruptedOcrBatchJobs(updatedAt: Long): Int

    @Insert
    abstract suspend fun insertOperation(entity: LibraryDataOperationEntity)

    @Update
    abstract suspend fun updateOperation(entity: LibraryDataOperationEntity)

    @Query("SELECT * FROM library_data_operations WHERE operation_id = :operationId LIMIT 1")
    abstract suspend fun operation(operationId: String): LibraryDataOperationEntity?

    @Query("SELECT * FROM library_data_operations WHERE operation_id = :operationId LIMIT 1")
    abstract fun observeOperation(operationId: String): Flow<LibraryDataOperationEntity?>

    @Query(
        """
        SELECT * FROM library_data_operations
        WHERE phase NOT IN ('COMPLETED', 'CANCELLED', 'FAILED')
        ORDER BY updated_at ASC
        """,
    )
    abstract suspend fun recoverableOperations(): List<LibraryDataOperationEntity>

    @Query("UPDATE library_data_operations SET cancel_requested = 1, updated_at = :updatedAt WHERE operation_id = :operationId")
    abstract suspend fun requestOperationCancellation(operationId: String, updatedAt: Long): Int

    @Insert
    abstract suspend fun insertOperationItems(entities: List<LibraryDataOperationItemEntity>)

    @Insert
    abstract suspend fun insertPendingRestoreFolders(entities: List<LibraryPendingRestoreFolderEntity>)

    @Insert
    abstract suspend fun insertOperationSources(entities: List<LibraryDataOperationSourceEntity>)

    @Query("SELECT * FROM library_data_operation_items WHERE operation_id = :operationId ORDER BY ordinal")
    abstract suspend fun operationItems(operationId: String): List<LibraryDataOperationItemEntity>

    @Query(
        """
        SELECT * FROM library_data_operation_items
        WHERE operation_id = :operationId AND ordinal > :afterOrdinal
        ORDER BY ordinal ASC
        LIMIT :limit
        """,
    )
    abstract suspend fun restoreOperationItemsPage(
        operationId: String,
        afterOrdinal: Int,
        limit: Int,
    ): List<LibraryDataOperationItemEntity>

    @Query(
        """
        SELECT COUNT(*) FROM library_documents
        WHERE pending_operation_id = :operationId AND library_state = 'PENDING'
        """,
    )
    protected abstract suspend fun pendingRestoreDocumentCount(operationId: String): Int

    @Query("SELECT COUNT(*) FROM library_pending_restore_folders WHERE operation_id = :operationId")
    protected abstract suspend fun pendingRestoreFolderCount(operationId: String): Int

    @Query("SELECT * FROM library_data_operation_sources WHERE item_id = :itemId ORDER BY source_position")
    abstract suspend fun operationSources(itemId: String): List<LibraryDataOperationSourceEntity>

    @Query("SELECT * FROM library_documents WHERE pending_operation_id = :operationId ORDER BY row_id")
    abstract suspend fun pendingDocuments(operationId: String): List<LibraryDocumentEntity>

    @Query("DELETE FROM library_documents WHERE pending_operation_id = :operationId AND library_state = 'PENDING'")
    protected abstract suspend fun deletePendingDocuments(operationId: String)

    @Query("DELETE FROM library_pending_restore_folders WHERE operation_id = :operationId")
    protected abstract suspend fun deletePendingRestoreFolders(operationId: String)

    @Query("DELETE FROM library_data_operations WHERE operation_id = :operationId")
    protected abstract suspend fun deleteOperationRow(operationId: String)

    @Transaction
    open suspend fun deleteCleanedPendingOperation(operationId: String) {
        deletePendingDocuments(operationId)
        deletePendingRestoreFolders(operationId)
        deleteOperationRow(operationId)
    }

    @Insert
    protected abstract suspend fun insertRestorePages(entities: List<LibraryPageEntity>)

    @Insert
    protected abstract suspend fun insertRestoreSourceAssets(entities: List<LibrarySourceAssetEntity>)

    @Update
    protected abstract suspend fun updateOperationItemInternal(entity: LibraryDataOperationItemEntity)

    @Query(
        """
        UPDATE library_data_operation_items
        SET item_state = 'ACTIVE'
        WHERE operation_id = :operationId AND item_state = 'PREPARED'
        """,
    )
    protected abstract suspend fun activatePreparedRestoreItems(operationId: String)

    @Query(
        """
        INSERT INTO library_folders (
            folder_id, name, normalized_name, created_at, modified_at,
            parent_folder_id, parent_scope
        )
        SELECT folder_id, name, normalized_name, created_at, modified_at,
               parent_folder_id, parent_scope
        FROM library_pending_restore_folders
        WHERE operation_id = :operationId
        ORDER BY ordinal
        """,
    )
    protected abstract suspend fun activatePendingRestoreFolders(operationId: String)

    @Query(
        """
        UPDATE library_documents
        SET folder_id = (
                SELECT item.proposed_folder_path
                FROM library_data_operation_items AS item
                WHERE item.operation_id = :operationId
                  AND item.target_document_id = library_documents.document_id
                  AND item.item_state = 'PREPARED'
                LIMIT 1
            ),
            library_state = 'ACTIVE',
            pending_operation_id = NULL
        WHERE pending_operation_id = :operationId
          AND library_state = 'PENDING'
          AND EXISTS (
                SELECT 1 FROM library_data_operation_items AS item
                WHERE item.operation_id = :operationId
                  AND item.target_document_id = library_documents.document_id
                  AND item.item_state = 'PREPARED'
            )
        """,
    )
    protected abstract suspend fun activatePendingRestoreDocuments(operationId: String): Int

    @Transaction
    open suspend fun insertRestoreOperation(
        operation: LibraryDataOperationEntity,
        items: List<LibraryDataOperationItemEntity>,
    ) {
        require(items.all { it.operationId == operation.operationId })
        insertOperation(operation)
        if (items.isNotEmpty()) insertOperationItems(items)
    }

    @Transaction
    open suspend fun insertPendingRestoreDocument(
        document: LibraryDocumentEntity,
        pages: List<LibraryPageEntity>,
        sourceAssets: List<LibrarySourceAssetEntity>,
        updatedItem: LibraryDataOperationItemEntity,
        updatedOperation: LibraryDataOperationEntity,
        ocrPageStates: List<LibraryOcrRestorePageState> = emptyList(),
        pageIdRemap: Map<String, String> = emptyMap(),
        folderSearchPath: String = "",
    ) {
        require(document.libraryState == LibraryDocumentState.PENDING.name)
        val operationId = requireNotNull(document.pendingOperationId)
        require(updatedOperation.operationId == operationId)
        require(updatedItem.operationId == operationId)
        require(updatedItem.targetDocumentId == document.documentId)
        require(pages.isNotEmpty() && pages.all { it.documentId == document.documentId })
        require(sourceAssets.all { it.documentId == document.documentId })
        check(documentAnyState(document.documentId) == null)
        insertDocument(document)
        insertRestorePages(
            pages.map { page ->
                page.copy(
                    ocrStateRevision = 0,
                    activeOcrArtifactRevision = null,
                    ocrText = null,
                )
            },
        )
        if (ocrPageStates.isEmpty()) {
            require(pageIdRemap.isEmpty())
            pages.forEach { page ->
                if (page.ocrText != null) {
                    createLegacyArtifact(requireNotNull(pageAnyState(page.pageId)), page.ocrText)
                }
            }
        } else {
            restoreOcrPageStates(
                documentId = document.documentId,
                states = ocrPageStates,
                pageIdRemap = pageIdRemap,
            )
        }
        if (sourceAssets.isNotEmpty()) insertRestoreSourceAssets(sourceAssets)
        pages.forEach { page -> refreshPageSearchContent(page.pageId) }
        val storedDocument = requireNotNull(documentAnyState(document.documentId))
        upsertDocumentSearchContent(
            LibraryDocumentSearchContentEntity(
                rowId = storedDocument.rowId,
                documentId = storedDocument.documentId,
                title = storedDocument.title,
                folderPath = folderSearchPath,
                auxiliaryTerms = searchAuxiliaryTerms("${storedDocument.title} $folderSearchPath"),
            ),
        )
        updateOperationItemInternal(updatedItem)
        updateOperation(updatedOperation)
    }

    @Transaction
    open suspend fun beginStreamingPendingRestoreDocument(document: LibraryDocumentEntity) {
        require(document.libraryState == LibraryDocumentState.PENDING.name)
        requireNotNull(document.pendingOperationId)
        check(documentAnyState(document.documentId) == null)
        insertDocument(document)
    }

    @Transaction
    open suspend fun appendStreamingPendingRestorePage(page: LibraryPageEntity) {
        val document = requireNotNull(documentAnyState(page.documentId))
        require(document.libraryState == LibraryDocumentState.PENDING.name)
        insertRestorePages(
            listOf(
                page.copy(
                    ocrStateRevision = 0L,
                    activeOcrArtifactRevision = null,
                    ocrText = null,
                ),
            ),
        )
    }

    @Transaction
    open suspend fun appendStreamingPendingRestoreSource(asset: LibrarySourceAssetEntity) {
        val document = requireNotNull(documentAnyState(asset.documentId))
        require(document.libraryState == LibraryDocumentState.PENDING.name)
        insertRestoreSourceAssets(listOf(asset))
    }

    @Transaction
    open suspend fun appendStreamingPendingRestoreArtifact(artifact: LibraryPageOcrArtifactEntity) {
        insertOcrArtifact(artifact)
    }

    @Transaction
    open suspend fun appendStreamingPendingRestoreLines(lines: List<LibraryPageOcrLineEntity>) {
        if (lines.isNotEmpty()) insertOcrLines(lines)
    }

    @Transaction
    open suspend fun appendStreamingPendingRestoreCorrection(
        correction: LibraryPageOcrCorrectionEntity,
        lines: List<LibraryPageOcrCorrectionLineEntity>,
    ) {
        upsertOcrCorrection(correction)
        if (lines.isNotEmpty()) insertOcrCorrectionLines(lines)
    }

    @Transaction
    open suspend fun finishStreamingPendingRestorePage(
        documentId: String,
        pageId: String,
        activeArtifactRevision: Long?,
        ocrStateRevision: Long,
        ocrText: String?,
        ocrError: String?,
    ) {
        check(
            setClonedOcrState(
                documentId = documentId,
                pageId = pageId,
                activeArtifactRevision = activeArtifactRevision,
                ocrStateRevision = ocrStateRevision,
                ocrText = ocrText,
                ocrError = ocrError,
            ) == 1,
        )
        refreshPageSearchContent(pageId)
    }

    @Transaction
    open suspend fun finishStreamingPendingRestoreDocument(
        documentId: String,
        ocrStatus: String,
        thumbnailRelativePath: String?,
        folderSearchPath: String,
        updatedItem: LibraryDataOperationItemEntity,
        updatedOperation: LibraryDataOperationEntity,
    ) {
        val document = requireNotNull(documentAnyState(documentId))
        require(document.libraryState == LibraryDocumentState.PENDING.name)
        val finalizedDocument = document.copy(
            ocrStatus = ocrStatus,
            thumbnailRelativePath = thumbnailRelativePath,
        )
        updateDocument(finalizedDocument)
        upsertDocumentSearchContent(
            LibraryDocumentSearchContentEntity(
                rowId = finalizedDocument.rowId,
                documentId = finalizedDocument.documentId,
                title = finalizedDocument.title,
                folderPath = folderSearchPath,
                auxiliaryTerms = searchAuxiliaryTerms("${finalizedDocument.title} $folderSearchPath"),
            ),
        )
        updateOperationItemInternal(updatedItem)
        updateOperation(updatedOperation)
    }

    private suspend fun restoreOcrPageStates(
        documentId: String,
        states: List<LibraryOcrRestorePageState>,
        pageIdRemap: Map<String, String>,
    ) {
        require(states.map(LibraryOcrRestorePageState::backupPageId).distinct().size == states.size)
        require(pageIdRemap.keys == states.mapTo(linkedSetOf(), LibraryOcrRestorePageState::backupPageId))
        require(pageIdRemap.values.distinct().size == pageIdRemap.size)
        states.forEach { state ->
            val targetPageId = requireNotNull(pageIdRemap[state.backupPageId])
            val targetPage = requireNotNull(pageAnyState(targetPageId))
            require(targetPage.documentId == documentId)
            require(state.ocrStateRevision >= 0)
            require(state.artifacts.all { it.pageId == state.backupPageId })
            require(state.lines.all { it.pageId == state.backupPageId })
            require(state.correction?.pageId in setOf(null, state.backupPageId))
            require(state.correctionLines.all { it.pageId == state.backupPageId })
            require(state.artifacts.map { it.artifactRevision }.distinct().size == state.artifacts.size)
            require(state.artifacts.all { artifact ->
                artifact.artifactRevision > 0 && artifact.recognizerId.isNotBlank() &&
                    artifact.verificationState in LibraryOcrArtifactVerification.entries.map(
                        LibraryOcrArtifactVerification::name,
                    )
            })
            val artifactRevisions = state.artifacts.mapTo(hashSetOf()) { it.artifactRevision }
            require(state.activeArtifactRevision == null || state.activeArtifactRevision in artifactRevisions)
            require(
                state.lines.groupBy(LibraryPageOcrLineEntity::artifactRevision).values.all { lines ->
                    lines.size <= MAX_OCR_LINES_PER_PAGE &&
                        lines.sortedBy(LibraryPageOcrLineEntity::lineOrdinal)
                            .map(LibraryPageOcrLineEntity::lineOrdinal) == lines.indices.toList()
                },
            )
            require(state.lines.all { line ->
                line.artifactRevision in artifactRevisions && line.hasValidNormalizedCoordinates()
            })
            val correction = state.correction
            if (correction != null) {
                require(correction.baseArtifactRevision in artifactRevisions)
                require(
                    correction.alignmentState in LibraryOcrCorrectionAlignment.entries.map(
                        LibraryOcrCorrectionAlignment::name,
                    ),
                )
            } else {
                require(state.correctionLines.isEmpty())
            }
            val lineKeys = state.lines.mapTo(hashSetOf()) { it.artifactRevision to it.lineOrdinal }
            require(state.correctionLines.all { line ->
                correction != null && line.baseArtifactRevision == correction.baseArtifactRevision &&
                    (line.baseArtifactRevision to line.lineOrdinal) in lineKeys
            })

            state.artifacts.sortedBy(LibraryPageOcrArtifactEntity::artifactRevision).forEach { artifact ->
                insertOcrArtifact(artifact.copy(pageId = targetPageId))
            }
            if (state.lines.isNotEmpty()) {
                insertOcrLines(state.lines.map { line -> line.copy(pageId = targetPageId) })
            }
            if (correction != null) {
                upsertOcrCorrection(correction.copy(pageId = targetPageId))
                if (state.correctionLines.isNotEmpty()) {
                    insertOcrCorrectionLines(
                        state.correctionLines.map { line -> line.copy(pageId = targetPageId) },
                    )
                }
            }
            val rawText = state.activeArtifactRevision?.let { activeRevision ->
                requireNotNull(state.artifacts.firstOrNull { it.artifactRevision == activeRevision }).rawText
            }
            check(
                setClonedOcrState(
                    documentId = documentId,
                    pageId = targetPageId,
                    activeArtifactRevision = state.activeArtifactRevision,
                    ocrStateRevision = state.ocrStateRevision,
                    ocrText = rawText,
                    ocrError = state.ocrError,
                ) == 1,
            )
        }
    }

    @Transaction
    open suspend fun activateRestoreOperation(
        operationId: String,
        expectedFolderCount: Int,
        completedOperation: LibraryDataOperationEntity,
        modifiedAt: Long,
    ) {
        val storedOperation = requireNotNull(operation(operationId))
        require(completedOperation.operationId == operationId)
        require(completedOperation.phase == "COMPLETED")
        require(storedOperation.phase !in setOf("COMPLETED", "CANCELLED", "FAILED"))
        require(pendingRestoreDocumentCount(operationId) == completedOperation.importedDocumentCount)
        require(pendingRestoreFolderCount(operationId) == expectedFolderCount)
        activatePendingRestoreFolders(operationId)
        val activatedDocumentCount = activatePendingRestoreDocuments(operationId)
        require(activatedDocumentCount == completedOperation.importedDocumentCount)
        activatePreparedRestoreItems(operationId)
        deletePendingRestoreFolders(operationId)
        if (activatedDocumentCount != 0 || expectedFolderCount != 0) bumpLibraryRevision(modifiedAt)
        updateOperation(completedOperation)
    }

    @Transaction
    open suspend fun discardPendingRestoreDocuments(operationId: String) {
        deletePendingDocuments(operationId)
        deletePendingRestoreFolders(operationId)
    }
}

private fun OcrBatchTarget.toBatchItem(
    jobId: String,
    snapshot: LibraryOcrPageSnapshot,
): OcrBatchItemEntity = OcrBatchItemEntity(
    itemId = itemId,
    jobId = jobId,
    documentId = documentId,
    pageId = pageId,
    ordinal = ordinal,
    requestedScriptSelection = requestedScriptSelection,
    resolvedScript = resolvedScript,
    expectedDocumentContentRevision = snapshot.documentContentRevision,
    expectedPageVisualRevision = snapshot.pageVisualRevision,
    expectedInputFingerprintVersion = expectedInputFingerprintVersion,
    expectedInputFingerprint = expectedInputFingerprint,
    expectedActiveArtifactRevision = snapshot.activeArtifactRevision,
    expectedOcrStateRevision = snapshot.ocrStateRevision,
    state = OcrBatchItemState.PENDING.name,
    attemptNumber = 0,
    claimGeneration = null,
    claimToken = null,
    safeErrorCode = null,
)

private fun OcrBatchItemEntity.matches(snapshot: LibraryOcrPageSnapshot): Boolean =
    documentId == snapshot.documentId &&
        pageId == snapshot.pageId &&
        expectedDocumentContentRevision == snapshot.documentContentRevision &&
        expectedPageVisualRevision == snapshot.pageVisualRevision &&
        expectedOcrStateRevision == snapshot.ocrStateRevision &&
        expectedActiveArtifactRevision == snapshot.activeArtifactRevision

private fun LibraryOcrPageSnapshot.matches(other: LibraryOcrPageSnapshot): Boolean =
    documentId == other.documentId &&
        pageId == other.pageId &&
        documentContentRevision == other.documentContentRevision &&
        pageVisualRevision == other.pageVisualRevision &&
        ocrStateRevision == other.ocrStateRevision &&
        activeArtifactRevision == other.activeArtifactRevision &&
        contentSha256 == other.contentSha256 &&
        rotationDegrees == other.rotationDegrees &&
        filterName == other.filterName

private fun LibraryOcrLineDraft.toEntity(
    pageId: String,
    artifactRevision: Long,
): LibraryPageOcrLineEntity = LibraryPageOcrLineEntity(
    pageId = pageId,
    artifactRevision = artifactRevision,
    lineOrdinal = lineOrdinal,
    rawText = rawText,
    topLeftX = topLeftX,
    topLeftY = topLeftY,
    topRightX = topRightX,
    topRightY = topRightY,
    bottomRightX = bottomRightX,
    bottomRightY = bottomRightY,
    bottomLeftX = bottomLeftX,
    bottomLeftY = bottomLeftY,
    baselineStartX = baselineStartX,
    baselineStartY = baselineStartY,
    baselineEndX = baselineEndX,
    baselineEndY = baselineEndY,
    baselineAngleDegrees = baselineAngleDegrees,
    writingOrientation = writingOrientation,
)

private fun LibraryPageOcrLineEntity.hasValidNormalizedCoordinates(): Boolean {
    val coordinates = listOf(
        topLeftX,
        topLeftY,
        topRightX,
        topRightY,
        bottomRightX,
        bottomRightY,
        bottomLeftX,
        bottomLeftY,
        baselineStartX,
        baselineStartY,
        baselineEndX,
        baselineEndY,
    )
    return coordinates.all { it.isFinite() && it in 0.0..1.0 } &&
        baselineAngleDegrees.isFinite()
}

private fun LibraryPageOcrLineEntity.toEffectiveLine(text: String): LibraryEffectiveOcrLine =
    LibraryEffectiveOcrLine(
        lineOrdinal = lineOrdinal,
        text = text,
        topLeftX = topLeftX,
        topLeftY = topLeftY,
        topRightX = topRightX,
        topRightY = topRightY,
        bottomRightX = bottomRightX,
        bottomRightY = bottomRightY,
        bottomLeftX = bottomLeftX,
        bottomLeftY = bottomLeftY,
        baselineStartX = baselineStartX,
        baselineStartY = baselineStartY,
        baselineEndX = baselineEndX,
        baselineEndY = baselineEndY,
        baselineAngleDegrees = baselineAngleDegrees,
        writingOrientation = writingOrientation,
    )

private fun LibraryEffectiveOcrPageRow.toEffectiveOcrPage(
    effectiveLines: List<LibraryEffectiveOcrLine>,
    sourceGeometryLines: List<LibraryEffectiveOcrLine>,
): LibraryEffectiveOcrPage = LibraryEffectiveOcrPage(
    documentId = documentId,
    pageId = pageId,
    pagePosition = pagePosition,
    effectiveText = effectiveText,
    rawText = rawText,
    correctedText = correctedText,
    alignment = alignmentState?.let(LibraryOcrCorrectionAlignment::valueOf),
    artifactRevision = artifactRevision,
    activeArtifactRevision = activeArtifactRevision,
    correctionBaseArtifactRevision = correctionBaseArtifactRevision,
    documentContentRevision = documentContentRevision,
    pageVisualRevision = pageVisualRevision,
    ocrStateRevision = ocrStateRevision,
    inputFingerprintVersion = inputFingerprintVersion,
    inputFingerprint = inputFingerprint,
    coordinateSystemVersion = coordinateSystemVersion,
    transformVersion = transformVersion,
    uprightWidth = uprightWidth,
    uprightHeight = uprightHeight,
    verification = LibraryOcrArtifactVerification.valueOf(verificationState),
    actualScript = actualScript,
    recognizerId = recognizerId,
    lines = effectiveLines,
    sourceGeometryLines = sourceGeometryLines,
)

private fun sourceAssetIdentity(assets: List<LibrarySourceAssetEntity>): List<List<Any?>> =
    assets.sortedWith(compareBy(LibrarySourceAssetEntity::role, LibrarySourceAssetEntity::assetId))
        .map { asset ->
            listOf(
                asset.role,
                asset.contentType,
                asset.byteCount,
                asset.sha256,
                asset.sourceModifiedAtMillis,
                asset.matchesCurrentRevision,
            )
        }

private fun ocrStatusForStoredPages(pages: List<LibraryPageEntity>): String {
    if (pages.all { it.activeOcrArtifactRevision == null && it.ocrError == null }) {
        return LibraryOcrStatus.NOT_INDEXED.name
    }
    if (pages.all { it.activeOcrArtifactRevision == null && it.ocrError != null }) {
        return LibraryOcrStatus.FAILED.name
    }
    if (pages.any { it.activeOcrArtifactRevision == null || it.ocrError != null }) {
        return LibraryOcrStatus.PARTIAL.name
    }
    return LibraryOcrStatus.INDEXED.name
}

internal const val LEGACY_OCR_RECOGNIZER_ID = "RME_LEGACY_LATIN_V1"
private const val OCR_BATCH_STALE_INPUT = "STALE_INPUT"
private const val SEARCH_REFRESH_BATCH_SIZE = 100
private const val MAX_OCR_BACKUP_PAGE_SIZE = 100
internal const val MAX_OCR_BATCH_TARGET_CHUNK_SIZE = 100
