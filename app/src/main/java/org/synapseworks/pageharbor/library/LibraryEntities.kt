package org.synapseworks.pageharbor.library

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "library_folders",
    foreignKeys = [
        ForeignKey(
            entity = LibraryFolderEntity::class,
            parentColumns = ["folder_id"],
            childColumns = ["parent_folder_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["parent_scope", "normalized_name"], unique = true),
        Index(value = ["parent_folder_id"]),
    ],
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
    @ColumnInfo(name = "parent_folder_id")
    val parentFolderId: String? = null,
    @ColumnInfo(name = "parent_scope", defaultValue = "''")
    val parentScope: String = libraryFolderParentScope(parentFolderId),
)

internal fun libraryFolderParentScope(parentFolderId: String?): String = parentFolderId.orEmpty()

@Entity(
    tableName = "library_documents",
    foreignKeys = [
        ForeignKey(
            entity = LibraryFolderEntity::class,
            parentColumns = ["folder_id"],
            childColumns = ["folder_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = LibraryDataOperationEntity::class,
            parentColumns = ["operation_id"],
            childColumns = ["pending_operation_id"],
        ),
    ],
    indices = [
        Index(value = ["document_id"], unique = true),
        Index(value = ["folder_id"]),
        Index(value = ["modified_at"]),
        Index(value = ["pending_operation_id"]),
        Index(value = ["library_state", "modified_at"]),
        Index(value = ["library_state", "folder_id", "modified_at"]),
        Index(value = ["library_state", "content_hash_version", "content_sha256"]),
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
    @ColumnInfo(name = "library_state", defaultValue = "'ACTIVE'")
    val libraryState: String = LibraryDocumentState.ACTIVE.name,
    @ColumnInfo(name = "pending_operation_id")
    val pendingOperationId: String? = null,
    @ColumnInfo(name = "content_hash_version")
    val contentHashVersion: Int? = null,
    @ColumnInfo(name = "content_sha256")
    val contentSha256: String? = null,
    @ColumnInfo(name = "content_byte_count")
    val contentByteCount: Long? = null,
    @ColumnInfo(name = "source_modified_at")
    val sourceModifiedAtMillis: Long? = null,
    @ColumnInfo(name = "imported_at")
    val importedAtMillis: Long? = null,
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
    @ColumnInfo(name = "content_sha256")
    val contentSha256: String? = null,
)

@Entity(tableName = "library_metadata")
data class LibraryMetadataEntity(
    @PrimaryKey
    @ColumnInfo(name = "metadata_id")
    val metadataId: String = LIBRARY_METADATA_ID,
    @ColumnInfo(name = "library_revision")
    val libraryRevision: Long = 0,
    @ColumnInfo(name = "modified_at")
    val modifiedAtMillis: Long = 0,
)

@Entity(
    tableName = "library_source_assets",
    foreignKeys = [
        ForeignKey(
            entity = LibraryDocumentEntity::class,
            parentColumns = ["document_id"],
            childColumns = ["document_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["document_id", "role"]),
        Index(value = ["sha256", "byte_count"]),
    ],
)
data class LibrarySourceAssetEntity(
    @PrimaryKey
    @ColumnInfo(name = "asset_id")
    val assetId: String,
    @ColumnInfo(name = "document_id")
    val documentId: String,
    val role: String,
    @ColumnInfo(name = "relative_path")
    val relativePath: String,
    @ColumnInfo(name = "content_type")
    val contentType: String,
    @ColumnInfo(name = "byte_count")
    val byteCount: Long,
    val sha256: String,
    @ColumnInfo(name = "source_modified_at")
    val sourceModifiedAtMillis: Long?,
    @ColumnInfo(name = "created_at")
    val createdAtMillis: Long,
    @ColumnInfo(name = "matches_current_revision")
    val matchesCurrentRevision: Boolean,
)

@Entity(
    tableName = "library_data_operations",
    indices = [Index(value = ["phase", "updated_at"])],
)
data class LibraryDataOperationEntity(
    @PrimaryKey
    @ColumnInfo(name = "operation_id")
    val operationId: String,
    @ColumnInfo(name = "operation_type")
    val operationType: String,
    val phase: String,
    @ColumnInfo(name = "source_kind")
    val sourceKind: String,
    @ColumnInfo(name = "source_root_uri")
    val sourceRootUri: String?,
    @ColumnInfo(name = "source_grant_flags")
    val sourceGrantFlags: Int,
    @ColumnInfo(name = "created_at")
    val createdAtMillis: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAtMillis: Long,
    @ColumnInfo(name = "discovered_item_count")
    val discoveredItemCount: Int,
    @ColumnInfo(name = "planned_document_count")
    val plannedDocumentCount: Int,
    @ColumnInfo(name = "prepared_document_count")
    val preparedDocumentCount: Int,
    @ColumnInfo(name = "imported_document_count")
    val importedDocumentCount: Int,
    @ColumnInfo(name = "skipped_duplicate_count")
    val skippedDuplicateCount: Int,
    @ColumnInfo(name = "failed_item_count")
    val failedItemCount: Int,
    @ColumnInfo(name = "total_source_bytes")
    val totalSourceBytes: Long?,
    @ColumnInfo(name = "processed_source_bytes")
    val processedSourceBytes: Long,
    @ColumnInfo(name = "cancel_requested")
    val cancelRequested: Boolean,
    @ColumnInfo(name = "terminal_error_code")
    val terminalErrorCode: String?,
)

@Entity(
    tableName = "library_data_operation_items",
    foreignKeys = [
        ForeignKey(
            entity = LibraryDataOperationEntity::class,
            parentColumns = ["operation_id"],
            childColumns = ["operation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["operation_id", "ordinal"], unique = true),
        Index(value = ["operation_id", "target_document_id"], unique = true),
        Index(value = ["operation_id", "item_state"]),
    ],
)
data class LibraryDataOperationItemEntity(
    @PrimaryKey
    @ColumnInfo(name = "item_id")
    val itemId: String,
    @ColumnInfo(name = "operation_id")
    val operationId: String,
    val ordinal: Int,
    @ColumnInfo(name = "item_state")
    val itemState: String,
    @ColumnInfo(name = "target_document_id")
    val targetDocumentId: String,
    @ColumnInfo(name = "target_revision_id")
    val targetRevisionId: String?,
    @ColumnInfo(name = "proposed_title")
    val proposedTitle: String,
    @ColumnInfo(name = "proposed_folder_path")
    val proposedFolderPath: String?,
    @ColumnInfo(name = "source_modified_at")
    val sourceModifiedAtMillis: Long?,
    @ColumnInfo(name = "source_byte_count")
    val sourceByteCount: Long?,
    @ColumnInfo(name = "source_page_count")
    val sourcePageCount: Int?,
    @ColumnInfo(name = "logical_hash_version")
    val logicalHashVersion: Int?,
    @ColumnInfo(name = "logical_sha256")
    val logicalSha256: String?,
    @ColumnInfo(name = "duplicate_kind")
    val duplicateKind: String,
    @ColumnInfo(name = "duplicate_document_id")
    val duplicateDocumentId: String?,
    @ColumnInfo(name = "duplicate_decision")
    val duplicateDecision: String,
    @ColumnInfo(name = "failure_code")
    val failureCode: String?,
)

@Entity(
    tableName = "library_data_operation_sources",
    foreignKeys = [
        ForeignKey(
            entity = LibraryDataOperationItemEntity::class,
            parentColumns = ["item_id"],
            childColumns = ["item_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["item_id", "source_position"], unique = true)],
)
data class LibraryDataOperationSourceEntity(
    @PrimaryKey
    @ColumnInfo(name = "source_id")
    val sourceId: String,
    @ColumnInfo(name = "item_id")
    val itemId: String,
    @ColumnInfo(name = "source_position")
    val sourcePosition: Int,
    @ColumnInfo(name = "source_uri")
    val sourceUri: String?,
    @ColumnInfo(name = "relative_source_path")
    val relativeSourcePath: String?,
    @ColumnInfo(name = "display_name")
    val displayName: String?,
    @ColumnInfo(name = "declared_mime_type")
    val declaredMimeType: String?,
    @ColumnInfo(name = "detected_mime_type")
    val detectedMimeType: String?,
    @ColumnInfo(name = "source_byte_count")
    val sourceByteCount: Long?,
    @ColumnInfo(name = "source_modified_at")
    val sourceModifiedAtMillis: Long?,
    @ColumnInfo(name = "source_sha256")
    val sourceSha256: String?,
    @ColumnInfo(name = "source_state")
    val sourceState: String,
    @ColumnInfo(name = "failure_code")
    val failureCode: String?,
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
    @ColumnInfo(name = "parent_folder_id") val parentFolderId: String?,
)

const val LIBRARY_METADATA_ID = "library"
