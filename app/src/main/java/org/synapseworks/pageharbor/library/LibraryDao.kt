package org.synapseworks.pageharbor.library

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

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
               CASE WHEN lower(d.title) LIKE '%' || lower(:rawQuery) || '%' THEN 1 ELSE 0 END AS title_match,
               snippet(library_document_search, '', '', ' … ', 1, 12) AS match_snippet
        FROM library_document_search AS s
        JOIN library_documents AS d ON d.row_id = s.rowid
        LEFT JOIN library_folders AS f ON f.folder_id = d.folder_id
        WHERE d.library_state = 'ACTIVE'
          AND library_document_search MATCH :ftsQuery
        ORDER BY title_match DESC, d.modified_at DESC, d.title COLLATE NOCASE ASC
        """,
    )
    abstract fun observeSearch(ftsQuery: String, rawQuery: String): Flow<List<LibrarySearchRow>>

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

    @Query("SELECT * FROM library_source_assets WHERE document_id = :documentId ORDER BY created_at, asset_id")
    abstract suspend fun sourceAssets(documentId: String): List<LibrarySourceAssetEntity>

    @Query("SELECT * FROM library_folders WHERE folder_id = :folderId LIMIT 1")
    abstract suspend fun folder(folderId: String): LibraryFolderEntity?

    @Query("SELECT * FROM library_folders WHERE normalized_name = :normalizedName LIMIT 1")
    abstract suspend fun folderByNormalizedName(normalizedName: String): LibraryFolderEntity?

    @Query("SELECT * FROM library_folders ORDER BY folder_id LIMIT :limit OFFSET :offset")
    abstract suspend fun foldersPage(offset: Int, limit: Int): List<LibraryFolderEntity>

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

    @Insert
    protected abstract suspend fun insertSourceAssetsInternal(entities: List<LibrarySourceAssetEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertSearch(entity: LibraryDocumentSearchEntity)

    @Insert
    protected abstract suspend fun insertFolderInternal(entity: LibraryFolderEntity)

    @Update
    protected abstract suspend fun updateFolderInternal(entity: LibraryFolderEntity)

    @Transaction
    open suspend fun insertFolder(entity: LibraryFolderEntity) {
        insertFolderInternal(entity)
        bumpLibraryRevision(entity.modifiedAtMillis)
    }

    @Transaction
    open suspend fun updateFolder(entity: LibraryFolderEntity) {
        updateFolderInternal(entity)
        bumpLibraryRevision(entity.modifiedAtMillis)
    }

    @Query("DELETE FROM library_pages WHERE document_id = :documentId")
    protected abstract suspend fun deletePages(documentId: String)

    @Query("DELETE FROM library_source_assets WHERE document_id = :documentId")
    protected abstract suspend fun deleteSourceAssets(documentId: String)

    @Query("DELETE FROM library_document_search WHERE rowid = :rowId")
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
        SET parent_folder_id = :parentFolderId, modified_at = :modifiedAt
        WHERE parent_folder_id = :folderId
        """,
    )
    protected abstract suspend fun reparentChildFolders(
        folderId: String,
        parentFolderId: String?,
        modifiedAt: Long,
    )

    @Query("DELETE FROM library_folders WHERE folder_id = :folderId")
    protected abstract suspend fun deleteFolderRow(folderId: String)

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
        if (changed == 1) bumpLibraryRevision(modifiedAt)
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

    @Query("UPDATE library_document_search SET title = :title WHERE rowid = :rowId")
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
        ocrText: String,
        sourceAssets: List<LibrarySourceAssetEntity> = emptyList(),
    ): LibraryDocumentEntity {
        require(document.libraryState == LibraryDocumentState.ACTIVE.name)
        require(document.pendingOperationId == null)
        val stored = documentAnyState(document.documentId)
        check(stored == null || stored.libraryState == LibraryDocumentState.ACTIVE.name)
        val rowId = if (stored == null) {
            insertDocument(document)
        } else {
            updateDocument(document.copy(rowId = stored.rowId))
            stored.rowId
        }
        deletePages(document.documentId)
        deleteSourceAssets(document.documentId)
        insertPages(pages)
        if (sourceAssets.isNotEmpty()) insertSourceAssetsInternal(sourceAssets)
        deleteSearch(rowId)
        insertSearch(LibraryDocumentSearchEntity(rowId, document.title, ocrText))
        bumpLibraryRevision(document.modifiedAtMillis)
        return document.copy(rowId = rowId)
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
        reparentChildFolders(folderId, existing.parentFolderId, modifiedAt)
        deleteFolderRow(folderId)
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
        pages.forEach { (pageId, values) ->
            if (updatePageOcr(documentId, pageId, values.first, values.second) != 1) return false
        }
        if (updateDocumentOcrStatus(documentId, ocrStatus, modifiedAt) != 1) return false
        deleteSearch(existing.rowId)
        insertSearch(
            LibraryDocumentSearchEntity(
                rowId = existing.rowId,
                title = existing.title,
                ocrText = pages.joinToString("\n\n") { (_, values) -> values.first.orEmpty() },
            ),
        )
        bumpLibraryRevision(modifiedAt)
        return true
    }

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
    abstract suspend fun insertOperationSources(entities: List<LibraryDataOperationSourceEntity>)

    @Query("SELECT * FROM library_data_operation_items WHERE operation_id = :operationId ORDER BY ordinal")
    abstract suspend fun operationItems(operationId: String): List<LibraryDataOperationItemEntity>

    @Query("SELECT * FROM library_data_operation_sources WHERE item_id = :itemId ORDER BY source_position")
    abstract suspend fun operationSources(itemId: String): List<LibraryDataOperationSourceEntity>

    @Query("SELECT * FROM library_documents WHERE pending_operation_id = :operationId ORDER BY row_id")
    abstract suspend fun pendingDocuments(operationId: String): List<LibraryDocumentEntity>

    @Query("DELETE FROM library_documents WHERE pending_operation_id = :operationId AND library_state = 'PENDING'")
    protected abstract suspend fun deletePendingDocuments(operationId: String)

    @Query("DELETE FROM library_data_operations WHERE operation_id = :operationId")
    protected abstract suspend fun deleteOperationRow(operationId: String)

    @Transaction
    open suspend fun deleteCleanedPendingOperation(operationId: String) {
        deletePendingDocuments(operationId)
        deleteOperationRow(operationId)
    }
}
