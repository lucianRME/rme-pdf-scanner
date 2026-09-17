package org.synapseworks.pageharbor.library

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "library_folders",
    indices = [Index(value = ["normalized_name"], unique = true)],
)
data class LibraryFolderEntity(
    @PrimaryKey
    @ColumnInfo(name = "folder_id")
    val folderId: String,
    val name: String,
    @ColumnInfo(name = "normalized_name")
    val normalizedName: String,
    @ColumnInfo(name = "created_at")
    val createdAtMillis: Long,
    @ColumnInfo(name = "modified_at")
    val modifiedAtMillis: Long,
)

@Entity(
    tableName = "library_documents",
    foreignKeys = [
        ForeignKey(
            entity = LibraryFolderEntity::class,
            parentColumns = ["folder_id"],
            childColumns = ["folder_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["document_id"], unique = true),
        Index(value = ["folder_id"]),
        Index(value = ["modified_at"]),
    ],
)
data class LibraryDocumentEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "row_id")
    val rowId: Long = 0,
    @ColumnInfo(name = "document_id")
    val documentId: String,
    val title: String,
    @ColumnInfo(name = "created_at")
    val createdAtMillis: Long,
    @ColumnInfo(name = "modified_at")
    val modifiedAtMillis: Long,
    @ColumnInfo(name = "page_count")
    val pageCount: Int,
    @ColumnInfo(name = "folder_id")
    val folderId: String?,
    @ColumnInfo(name = "thumbnail_path")
    val thumbnailRelativePath: String?,
    @ColumnInfo(name = "ocr_status")
    val ocrStatus: String,
)

@Entity(
    tableName = "library_pages",
    foreignKeys = [
        ForeignKey(
            entity = LibraryDocumentEntity::class,
            parentColumns = ["document_id"],
            childColumns = ["document_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["document_id"]),
        Index(value = ["document_id", "page_position"], unique = true),
    ],
)
data class LibraryPageEntity(
    @PrimaryKey
    @ColumnInfo(name = "page_id")
    val pageId: String,
    @ColumnInfo(name = "document_id")
    val documentId: String,
    @ColumnInfo(name = "page_position")
    val position: Int,
    @ColumnInfo(name = "relative_path")
    val relativePath: String,
    @ColumnInfo(name = "content_type")
    val contentType: String,
    @ColumnInfo(name = "source_category")
    val sourceCategory: String,
    val width: Int?,
    val height: Int?,
    @ColumnInfo(name = "source_byte_count")
    val sourceByteCount: Long?,
    @ColumnInfo(name = "rotation_degrees")
    val rotationDegrees: Int,
    @ColumnInfo(name = "filter_name")
    val filterName: String,
    @ColumnInfo(name = "ocr_text")
    val ocrText: String?,
    @ColumnInfo(name = "ocr_error")
    val ocrError: String?,
)

@Fts4
@Entity(tableName = "library_document_search")
data class LibraryDocumentSearchEntity(
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    val rowId: Long,
    val title: String,
    @ColumnInfo(name = "ocr_text")
    val ocrText: String,
)

data class LibraryDocumentListingRow(
    @ColumnInfo(name = "document_id") val documentId: String,
    val title: String,
    @ColumnInfo(name = "created_at") val createdAtMillis: Long,
    @ColumnInfo(name = "modified_at") val modifiedAtMillis: Long,
    @ColumnInfo(name = "page_count") val pageCount: Int,
    @ColumnInfo(name = "folder_id") val folderId: String?,
    @ColumnInfo(name = "folder_name") val folderName: String?,
    @ColumnInfo(name = "thumbnail_path") val thumbnailRelativePath: String?,
    @ColumnInfo(name = "ocr_status") val ocrStatus: String,
)

data class LibrarySearchRow(
    @ColumnInfo(name = "document_id") val documentId: String,
    val title: String,
    @ColumnInfo(name = "created_at") val createdAtMillis: Long,
    @ColumnInfo(name = "modified_at") val modifiedAtMillis: Long,
    @ColumnInfo(name = "page_count") val pageCount: Int,
    @ColumnInfo(name = "folder_id") val folderId: String?,
    @ColumnInfo(name = "folder_name") val folderName: String?,
    @ColumnInfo(name = "thumbnail_path") val thumbnailRelativePath: String?,
    @ColumnInfo(name = "ocr_status") val ocrStatus: String,
    @ColumnInfo(name = "title_match") val titleMatch: Int,
    @ColumnInfo(name = "match_snippet") val matchSnippet: String?,
)

data class LibraryFolderRow(
    @ColumnInfo(name = "folder_id") val folderId: String,
    val name: String,
    @ColumnInfo(name = "document_count") val documentCount: Int,
)
