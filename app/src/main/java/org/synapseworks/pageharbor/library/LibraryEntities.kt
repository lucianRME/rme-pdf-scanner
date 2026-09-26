package org.synapseworks.pageharbor.library

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.FtsOptions
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
        Index(value = ["library_state", "row_id"]),
        Index(value = ["library_state", "folder_id", "modified_at"]),
        Index(value = ["library_state", "content_hash_version", "content_sha256"]),
        Index(value = ["library_state", "page_count", "content_byte_count", "row_id"]),
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
    @ColumnInfo(name = "content_revision", defaultValue = "0")
    val contentRevision: Long = 0,
    @ColumnInfo(name = "ocr_script_preference")
    val ocrScriptPreference: String? = null,
) {
    init {
        require(ocrScriptPreference == null || ocrScriptPreference.isSupportedOcrSelectionId())
    }
}

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
    @ColumnInfo(name = "visual_revision", defaultValue = "0")
    val visualRevision: Long = 0,
    @ColumnInfo(name = "ocr_state_revision", defaultValue = "0")
    val ocrStateRevision: Long = 0,
    @ColumnInfo(name = "active_ocr_artifact_revision")
    val activeOcrArtifactRevision: Long? = null,
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

/**
 * Operation-owned folder rows prepared outside the final visibility transaction. They are not
 * queried by the library UI and are removed atomically when restore activation succeeds.
 */
@Entity(
    tableName = "library_pending_restore_folders",
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
        Index(value = ["operation_id", "parent_folder_id"]),
    ],
)
data class LibraryPendingRestoreFolderEntity(
    @PrimaryKey
    @ColumnInfo(name = "folder_id")
    val folderId: String,
    @ColumnInfo(name = "operation_id")
    val operationId: String,
    val ordinal: Int,
    val name: String,
    @ColumnInfo(name = "normalized_name")
    val normalizedName: String,
    @ColumnInfo(name = "created_at")
    val createdAtMillis: Long,
    @ColumnInfo(name = "modified_at")
    val modifiedAtMillis: Long,
    @ColumnInfo(name = "parent_folder_id")
    val parentFolderId: String?,
    @ColumnInfo(name = "parent_scope")
    val parentScope: String,
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

@Entity(
    tableName = "library_page_ocr_artifacts",
    primaryKeys = ["page_id", "artifact_revision"],
    foreignKeys = [
        ForeignKey(
            entity = LibraryPageEntity::class,
            parentColumns = ["page_id"],
            childColumns = ["page_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["page_id", "verification_state"]),
        Index(value = ["actual_script"]),
    ],
)
data class LibraryPageOcrArtifactEntity(
    @ColumnInfo(name = "page_id")
    val pageId: String,
    @ColumnInfo(name = "artifact_revision")
    val artifactRevision: Long,
    @ColumnInfo(name = "captured_document_content_revision")
    val capturedDocumentContentRevision: Long?,
    @ColumnInfo(name = "captured_page_visual_revision")
    val capturedPageVisualRevision: Long?,
    @ColumnInfo(name = "input_fingerprint_version")
    val inputFingerprintVersion: Int?,
    @ColumnInfo(name = "input_fingerprint")
    val inputFingerprint: String?,
    @ColumnInfo(name = "verification_state")
    val verificationState: String,
    @ColumnInfo(name = "content_sha256")
    val contentSha256: String?,
    @ColumnInfo(name = "rotation_degrees")
    val rotationDegrees: Int?,
    @ColumnInfo(name = "filter_name")
    val filterName: String?,
    @ColumnInfo(name = "upright_width")
    val uprightWidth: Int?,
    @ColumnInfo(name = "upright_height")
    val uprightHeight: Int?,
    @ColumnInfo(name = "coordinate_system_version")
    val coordinateSystemVersion: Int?,
    @ColumnInfo(name = "transform_version")
    val transformVersion: Int?,
    @ColumnInfo(name = "actual_script")
    val actualScript: String?,
    @ColumnInfo(name = "recognizer_id")
    val recognizerId: String,
    @ColumnInfo(name = "pipeline_version")
    val pipelineVersion: String?,
    @ColumnInfo(name = "client_version")
    val clientVersion: String?,
    val delivery: String?,
    @ColumnInfo(name = "recognized_at")
    val recognizedAtMillis: Long?,
    @ColumnInfo(name = "raw_text")
    val rawText: String,
) {
    init {
        require(actualScript == null || actualScript.isSupportedOcrScriptId())
    }
}

@Entity(
    tableName = "library_page_ocr_lines",
    primaryKeys = ["page_id", "artifact_revision", "line_ordinal"],
    foreignKeys = [
        ForeignKey(
            entity = LibraryPageOcrArtifactEntity::class,
            parentColumns = ["page_id", "artifact_revision"],
            childColumns = ["page_id", "artifact_revision"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["page_id", "artifact_revision"])],
)
data class LibraryPageOcrLineEntity(
    @ColumnInfo(name = "page_id")
    val pageId: String,
    @ColumnInfo(name = "artifact_revision")
    val artifactRevision: Long,
    @ColumnInfo(name = "line_ordinal")
    val lineOrdinal: Int,
    @ColumnInfo(name = "raw_text")
    val rawText: String,
    @ColumnInfo(name = "top_left_x")
    val topLeftX: Double,
    @ColumnInfo(name = "top_left_y")
    val topLeftY: Double,
    @ColumnInfo(name = "top_right_x")
    val topRightX: Double,
    @ColumnInfo(name = "top_right_y")
    val topRightY: Double,
    @ColumnInfo(name = "bottom_right_x")
    val bottomRightX: Double,
    @ColumnInfo(name = "bottom_right_y")
    val bottomRightY: Double,
    @ColumnInfo(name = "bottom_left_x")
    val bottomLeftX: Double,
    @ColumnInfo(name = "bottom_left_y")
    val bottomLeftY: Double,
    @ColumnInfo(name = "baseline_start_x")
    val baselineStartX: Double,
    @ColumnInfo(name = "baseline_start_y")
    val baselineStartY: Double,
    @ColumnInfo(name = "baseline_end_x")
    val baselineEndX: Double,
    @ColumnInfo(name = "baseline_end_y")
    val baselineEndY: Double,
    @ColumnInfo(name = "baseline_angle_degrees")
    val baselineAngleDegrees: Double,
    @ColumnInfo(name = "writing_orientation")
    val writingOrientation: String?,
)

@Entity(
    tableName = "library_page_ocr_corrections",
    primaryKeys = ["page_id", "base_artifact_revision"],
    foreignKeys = [
        ForeignKey(
            entity = LibraryPageEntity::class,
            parentColumns = ["page_id"],
            childColumns = ["page_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = LibraryPageOcrArtifactEntity::class,
            parentColumns = ["page_id", "artifact_revision"],
            childColumns = ["page_id", "base_artifact_revision"],
            deferred = true,
        ),
    ],
    indices = [Index(value = ["page_id"], unique = true)],
)
data class LibraryPageOcrCorrectionEntity(
    @ColumnInfo(name = "page_id")
    val pageId: String,
    @ColumnInfo(name = "base_artifact_revision")
    val baseArtifactRevision: Long,
    @ColumnInfo(name = "corrected_text")
    val correctedText: String,
    @ColumnInfo(name = "corrected_at")
    val correctedAtMillis: Long,
    @ColumnInfo(name = "alignment_state")
    val alignmentState: String,
)

@Entity(
    tableName = "library_page_ocr_correction_lines",
    primaryKeys = ["page_id", "base_artifact_revision", "line_ordinal"],
    foreignKeys = [
        ForeignKey(
            entity = LibraryPageOcrCorrectionEntity::class,
            parentColumns = ["page_id", "base_artifact_revision"],
            childColumns = ["page_id", "base_artifact_revision"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = LibraryPageOcrLineEntity::class,
            parentColumns = ["page_id", "artifact_revision", "line_ordinal"],
            childColumns = ["page_id", "base_artifact_revision", "line_ordinal"],
            deferred = true,
        ),
    ],
    indices = [Index(value = ["page_id", "base_artifact_revision"])],
)
data class LibraryPageOcrCorrectionLineEntity(
    @ColumnInfo(name = "page_id")
    val pageId: String,
    @ColumnInfo(name = "base_artifact_revision")
    val baseArtifactRevision: Long,
    @ColumnInfo(name = "line_ordinal")
    val lineOrdinal: Int,
    @ColumnInfo(name = "corrected_text")
    val correctedText: String,
)

@Entity(
    tableName = "library_document_search_content_v3",
    foreignKeys = [
        ForeignKey(
            entity = LibraryDocumentEntity::class,
            parentColumns = ["document_id"],
            childColumns = ["document_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["document_id"], unique = true)],
)
data class LibraryDocumentSearchContentEntity(
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    val rowId: Long,
    @ColumnInfo(name = "document_id")
    val documentId: String,
    val title: String,
    @ColumnInfo(name = "folder_path")
    val folderPath: String,
    @ColumnInfo(name = "auxiliary_terms")
    val auxiliaryTerms: String,
)

@Fts4(
    contentEntity = LibraryDocumentSearchContentEntity::class,
    tokenizer = FtsOptions.TOKENIZER_UNICODE61,
    tokenizerArgs = ["remove_diacritics=0"],
    notIndexed = ["document_id"],
)
@Entity(tableName = "library_document_search_v3")
data class LibraryDocumentSearchEntity(
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    val rowId: Long,
    @ColumnInfo(name = "document_id")
    val documentId: String,
    val title: String,
    @ColumnInfo(name = "folder_path")
    val folderPath: String,
    @ColumnInfo(name = "auxiliary_terms")
    val auxiliaryTerms: String,
)

@Entity(
    tableName = "library_page_search_content_v3",
    foreignKeys = [
        ForeignKey(
            entity = LibraryPageEntity::class,
            parentColumns = ["page_id"],
            childColumns = ["page_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["page_id"], unique = true)],
)
data class LibraryPageSearchContentEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "rowid")
    val rowId: Long = 0,
    @ColumnInfo(name = "page_id")
    val pageId: String,
    @ColumnInfo(name = "effective_text")
    val effectiveText: String,
    @ColumnInfo(name = "auxiliary_terms")
    val auxiliaryTerms: String,
)

@Fts4(
    contentEntity = LibraryPageSearchContentEntity::class,
    tokenizer = FtsOptions.TOKENIZER_UNICODE61,
    tokenizerArgs = ["remove_diacritics=0"],
    notIndexed = ["page_id"],
)
@Entity(tableName = "library_page_search_v3")
data class LibraryPageSearchEntity(
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    val rowId: Long,
    @ColumnInfo(name = "page_id")
    val pageId: String,
    @ColumnInfo(name = "effective_text")
    val effectiveText: String,
    @ColumnInfo(name = "auxiliary_terms")
    val auxiliaryTerms: String,
)

@Entity(
    tableName = "ocr_batch_jobs",
    indices = [Index(value = ["state", "updated_at"])],
)
data class OcrBatchJobEntity(
    @PrimaryKey
    @ColumnInfo(name = "job_id")
    val jobId: String,
    @ColumnInfo(name = "selection_policy")
    val selectionPolicy: String,
    @ColumnInfo(name = "requested_script_selection")
    val requestedScriptSelection: String,
    @ColumnInfo(name = "locale_recommendation_snapshot")
    val localeRecommendationSnapshot: String,
    val state: String,
    @ColumnInfo(name = "total_item_count")
    val totalItemCount: Int,
    @ColumnInfo(name = "completed_item_count")
    val completedItemCount: Int,
    @ColumnInfo(name = "failed_item_count")
    val failedItemCount: Int,
    @ColumnInfo(name = "skipped_item_count")
    val skippedItemCount: Int,
    @ColumnInfo(name = "created_at")
    val createdAtMillis: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAtMillis: Long,
    @ColumnInfo(name = "run_generation")
    val runGeneration: Long,
    @ColumnInfo(name = "cancel_requested")
    val cancelRequested: Boolean,
    @ColumnInfo(name = "target_population_complete")
    val targetPopulationComplete: Boolean,
    @ColumnInfo(name = "terminal_error_code")
    val terminalErrorCode: String?,
) {
    init {
        require(requestedScriptSelection.isSupportedOcrSelectionId())
        require(state in OcrBatchJobState.entries.map(OcrBatchJobState::name))
    }
}

@Entity(
    tableName = "ocr_batch_items",
    foreignKeys = [
        ForeignKey(
            entity = OcrBatchJobEntity::class,
            parentColumns = ["job_id"],
            childColumns = ["job_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["job_id", "page_id"], unique = true),
        Index(value = ["job_id", "ordinal"], unique = true),
        Index(value = ["job_id", "state", "ordinal"]),
    ],
)
data class OcrBatchItemEntity(
    @PrimaryKey
    @ColumnInfo(name = "item_id")
    val itemId: String,
    @ColumnInfo(name = "job_id")
    val jobId: String,
    @ColumnInfo(name = "document_id")
    val documentId: String,
    @ColumnInfo(name = "page_id")
    val pageId: String,
    val ordinal: Int,
    @ColumnInfo(name = "requested_script_selection")
    val requestedScriptSelection: String,
    @ColumnInfo(name = "resolved_script")
    val resolvedScript: String,
    @ColumnInfo(name = "expected_document_content_revision")
    val expectedDocumentContentRevision: Long,
    @ColumnInfo(name = "expected_page_visual_revision")
    val expectedPageVisualRevision: Long,
    @ColumnInfo(name = "expected_input_fingerprint_version")
    val expectedInputFingerprintVersion: Int?,
    @ColumnInfo(name = "expected_input_fingerprint")
    val expectedInputFingerprint: String?,
    @ColumnInfo(name = "expected_active_artifact_revision")
    val expectedActiveArtifactRevision: Long?,
    @ColumnInfo(name = "expected_ocr_state_revision")
    val expectedOcrStateRevision: Long,
    val state: String,
    @ColumnInfo(name = "attempt_number")
    val attemptNumber: Int,
    @ColumnInfo(name = "claim_generation")
    val claimGeneration: Long?,
    @ColumnInfo(name = "claim_token")
    val claimToken: String?,
    @ColumnInfo(name = "safe_error_code")
    val safeErrorCode: String?,
) {
    init {
        require(requestedScriptSelection.isSupportedOcrSelectionId())
        require(resolvedScript.isSupportedOcrScriptId())
        require(state in OcrBatchItemState.entries.map(OcrBatchItemState::name))
    }
}

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
    @ColumnInfo(name = "page_id") val pageId: String?,
    @ColumnInfo(name = "page_position") val pagePosition: Int?,
    @ColumnInfo(name = "match_type") val matchType: String,
    @ColumnInfo(name = "match_snippet") val matchSnippet: String?,
)

data class LibraryEffectiveOcrPageRow(
    @ColumnInfo(name = "document_id") val documentId: String,
    @ColumnInfo(name = "page_id") val pageId: String,
    @ColumnInfo(name = "page_position") val pagePosition: Int,
    @ColumnInfo(name = "effective_text") val effectiveText: String,
    @ColumnInfo(name = "raw_text") val rawText: String,
    @ColumnInfo(name = "corrected_text") val correctedText: String?,
    @ColumnInfo(name = "alignment_state") val alignmentState: String?,
    @ColumnInfo(name = "artifact_revision") val artifactRevision: Long,
    @ColumnInfo(name = "active_artifact_revision") val activeArtifactRevision: Long,
    @ColumnInfo(name = "correction_base_artifact_revision") val correctionBaseArtifactRevision: Long?,
    @ColumnInfo(name = "document_content_revision") val documentContentRevision: Long,
    @ColumnInfo(name = "page_visual_revision") val pageVisualRevision: Long,
    @ColumnInfo(name = "ocr_state_revision") val ocrStateRevision: Long,
    @ColumnInfo(name = "input_fingerprint_version") val inputFingerprintVersion: Int?,
    @ColumnInfo(name = "input_fingerprint") val inputFingerprint: String?,
    @ColumnInfo(name = "coordinate_system_version") val coordinateSystemVersion: Int?,
    @ColumnInfo(name = "transform_version") val transformVersion: Int?,
    @ColumnInfo(name = "upright_width") val uprightWidth: Int?,
    @ColumnInfo(name = "upright_height") val uprightHeight: Int?,
    @ColumnInfo(name = "verification_state") val verificationState: String,
    @ColumnInfo(name = "actual_script") val actualScript: String?,
    @ColumnInfo(name = "recognizer_id") val recognizerId: String,
)

data class LibraryFolderRow(
    @ColumnInfo(name = "folder_id") val folderId: String,
    val name: String,
    @ColumnInfo(name = "document_count") val documentCount: Int,
    @ColumnInfo(name = "parent_folder_id") val parentFolderId: String?,
)

const val LIBRARY_METADATA_ID = "library"
