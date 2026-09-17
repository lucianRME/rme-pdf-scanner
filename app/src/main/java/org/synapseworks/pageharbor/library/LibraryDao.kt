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
        WHERE (:folderId IS NULL OR d.folder_id = :folderId)
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
        WHERE (:folderId IS NULL OR d.folder_id = :folderId)
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
        WHERE (:folderId IS NULL OR d.folder_id = :folderId)
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
        WHERE library_document_search MATCH :ftsQuery
        ORDER BY title_match DESC, d.modified_at DESC, d.title COLLATE NOCASE ASC
        """,
    )
    abstract fun observeSearch(ftsQuery: String, rawQuery: String): Flow<List<LibrarySearchRow>>

    @Query(
        """
        SELECT f.folder_id, f.name, COUNT(d.document_id) AS document_count
        FROM library_folders AS f
        LEFT JOIN library_documents AS d ON d.folder_id = f.folder_id
        GROUP BY f.folder_id
        ORDER BY f.name COLLATE NOCASE ASC
        """,
    )
    abstract fun observeFolders(): Flow<List<LibraryFolderRow>>

    @Query("SELECT * FROM library_documents WHERE document_id = :documentId LIMIT 1")
    abstract suspend fun document(documentId: String): LibraryDocumentEntity?

    @Query("SELECT * FROM library_pages WHERE document_id = :documentId ORDER BY page_position ASC")
    abstract suspend fun pages(documentId: String): List<LibraryPageEntity>

    @Query("SELECT * FROM library_folders WHERE folder_id = :folderId LIMIT 1")
    abstract suspend fun folder(folderId: String): LibraryFolderEntity?

    @Query("SELECT * FROM library_folders WHERE normalized_name = :normalizedName LIMIT 1")
    abstract suspend fun folderByNormalizedName(normalizedName: String): LibraryFolderEntity?

    @Insert
    protected abstract suspend fun insertDocument(entity: LibraryDocumentEntity): Long

    @Update
    protected abstract suspend fun updateDocument(entity: LibraryDocumentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertPages(entities: List<LibraryPageEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertSearch(entity: LibraryDocumentSearchEntity)

    @Insert
    abstract suspend fun insertFolder(entity: LibraryFolderEntity)

    @Update
    abstract suspend fun updateFolder(entity: LibraryFolderEntity)

    @Query("DELETE FROM library_pages WHERE document_id = :documentId")
    protected abstract suspend fun deletePages(documentId: String)

    @Query("DELETE FROM library_document_search WHERE rowid = :rowId")
    protected abstract suspend fun deleteSearch(rowId: Long)

    @Query("DELETE FROM library_documents WHERE document_id = :documentId")
    protected abstract suspend fun deleteDocumentRow(documentId: String)

    @Query("UPDATE library_documents SET folder_id = NULL, modified_at = :modifiedAt WHERE folder_id = :folderId")
    protected abstract suspend fun moveFolderDocumentsToRoot(folderId: String, modifiedAt: Long)

    @Query("DELETE FROM library_folders WHERE folder_id = :folderId")
    protected abstract suspend fun deleteFolderRow(folderId: String)

    @Query("UPDATE library_documents SET folder_id = :folderId, modified_at = :modifiedAt WHERE document_id = :documentId")
    abstract suspend fun moveDocument(documentId: String, folderId: String?, modifiedAt: Long): Int

    @Query("UPDATE library_documents SET title = :title, modified_at = :modifiedAt WHERE document_id = :documentId")
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
        WHERE document_id = :documentId
        """,
    )
    protected abstract suspend fun updateDocumentOcrStatus(
        documentId: String,
        ocrStatus: String,
        modifiedAt: Long,
    )

    @Transaction
    open suspend fun replaceDocument(
        document: LibraryDocumentEntity,
        pages: List<LibraryPageEntity>,
        ocrText: String,
    ): LibraryDocumentEntity {
        val stored = document(document.documentId)
        val rowId = if (stored == null) {
            insertDocument(document)
        } else {
            updateDocument(document.copy(rowId = stored.rowId))
            stored.rowId
        }
        deletePages(document.documentId)
        insertPages(pages)
        deleteSearch(rowId)
        insertSearch(
            LibraryDocumentSearchEntity(
                rowId = rowId,
                title = document.title,
                ocrText = ocrText,
            ),
        )
        return document.copy(rowId = rowId)
    }

    @Transaction
    open suspend fun deleteDocument(documentId: String): LibraryDocumentEntity? {
        val existing = document(documentId) ?: return null
        deleteSearch(existing.rowId)
        deleteDocumentRow(documentId)
        return existing
    }

    @Transaction
    open suspend fun deleteFolder(folderId: String, modifiedAt: Long): Boolean {
        if (folder(folderId) == null) return false
        moveFolderDocumentsToRoot(folderId, modifiedAt)
        deleteFolderRow(folderId)
        return true
    }

    @Transaction
    open suspend fun renameDocument(documentId: String, title: String, modifiedAt: Long): Boolean {
        val existing = document(documentId) ?: return false
        if (updateDocumentTitle(documentId, title, modifiedAt) != 1) return false
        updateSearchTitle(existing.rowId, title)
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
        updateDocumentOcrStatus(documentId, ocrStatus, modifiedAt)
        deleteSearch(existing.rowId)
        insertSearch(
            LibraryDocumentSearchEntity(
                rowId = existing.rowId,
                title = existing.title,
                ocrText = pages.joinToString("\n\n") { (_, values) -> values.first.orEmpty() },
            ),
        )
        return true
    }
}
