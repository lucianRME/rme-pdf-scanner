package org.synapseworks.pageharbor.backup.restore

import android.content.Context
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.synapseworks.pageharbor.document.session.DocumentImageMetadata
import org.synapseworks.pageharbor.document.session.DocumentPageRotation
import org.synapseworks.pageharbor.document.session.DocumentSourceCategory
import org.synapseworks.pageharbor.image.DocumentFilter
import org.synapseworks.pageharbor.library.LibraryDao
import org.synapseworks.pageharbor.library.LibraryDataOperationEntity
import org.synapseworks.pageharbor.library.LibraryDataOperationItemEntity
import org.synapseworks.pageharbor.library.LibraryDatabase
import org.synapseworks.pageharbor.library.LibraryDocumentEntity
import org.synapseworks.pageharbor.library.LibraryDocumentState
import org.synapseworks.pageharbor.library.LibraryError
import org.synapseworks.pageharbor.library.LibraryFileStore
import org.synapseworks.pageharbor.library.LibraryFolderEntity
import org.synapseworks.pageharbor.library.LibraryOcrStatus
import org.synapseworks.pageharbor.library.LibraryPageEntity
import org.synapseworks.pageharbor.library.LibraryPageSource
import org.synapseworks.pageharbor.library.LibraryRestoreDocumentActivation
import org.synapseworks.pageharbor.library.LibraryResult
import org.synapseworks.pageharbor.library.LibrarySourceAssetEntity
import org.synapseworks.pageharbor.library.LibrarySourceAssetSource
import org.synapseworks.pageharbor.library.duplicate.DuplicateCandidate

/** Room v2 and private-library adapter. Pending rows stay invisible until one final transaction. */
internal class RoomRestoreLibraryStore(
    context: Context,
    private val dao: LibraryDao = LibraryDatabase.get(context).libraryDao(),
    private val fileStore: LibraryFileStore = LibraryFileStore(context),
    private val clock: RestoreClock = RestoreClock { System.currentTimeMillis() },
) : RestoreLibraryStore {
    override suspend fun duplicateCandidates(): List<DuplicateCandidate> {
        val result = ArrayList<DuplicateCandidate>()
        var afterRowId = -1L
        while (true) {
            currentCoroutineContext().ensureActive()
            val documents = dao.activeDocumentsPage(afterRowId, DATABASE_PAGE_SIZE)
            if (documents.isEmpty()) break
            documents.forEach { document ->
                val pages = readAllPages(document.documentId)
                val sources = dao.sourceAssets(document.documentId)
                result += DuplicateCandidate(
                    documentId = document.documentId,
                    contentHashVersion = document.contentHashVersion,
                    contentSha256 = document.contentSha256,
                    sourceSha256 = sources.mapTo(linkedSetOf()) { it.sha256 },
                    pageCount = pages.size,
                    contentByteLength = pages.sumOf { it.sourceByteCount ?: 0L },
                    orderedMimeTypes = pages.map { it.contentType },
                )
            }
            afterRowId = documents.last().rowId
            if (documents.size < DATABASE_PAGE_SIZE) break
        }
        return result
    }

    override suspend fun existingFolders(): List<RestoreExistingFolder> {
        val result = ArrayList<RestoreExistingFolder>()
        var offset = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val folders = dao.foldersPage(offset, DATABASE_PAGE_SIZE)
            if (folders.isEmpty()) break
            result += folders.map { RestoreExistingFolder(it.folderId, it.name, it.parentFolderId) }
            offset += folders.size
            if (folders.size < DATABASE_PAGE_SIZE) break
        }
        return result
    }

    override suspend fun beginOperation(plan: RestoreJournalPlan) {
        val operation = LibraryDataOperationEntity(
            operationId = plan.operationId,
            operationType = OPERATION_TYPE,
            phase = PHASE_PREPARING,
            sourceKind = SOURCE_KIND,
            sourceRootUri = null,
            sourceGrantFlags = 0,
            createdAtMillis = plan.createdAtEpochMillis,
            updatedAtMillis = plan.createdAtEpochMillis,
            discoveredItemCount = plan.discoveredDocumentCount,
            plannedDocumentCount = plan.documentsToImport.size,
            preparedDocumentCount = 0,
            importedDocumentCount = 0,
            skippedDuplicateCount = plan.skippedExactDocuments.size,
            failedItemCount = 0,
            totalSourceBytes = plan.contentByteLength,
            processedSourceBytes = 0L,
            cancelRequested = false,
            terminalErrorCode = null,
        )
        val importItems = plan.documentsToImport.mapIndexed { ordinal, document ->
            document.toJournalItem(plan.operationId, ordinal, ITEM_PLANNED)
        }
        val skippedItems = plan.skippedExactDocuments.mapIndexed { offset, bundle ->
            LibraryDataOperationItemEntity(
                itemId = "${plan.operationId}-skip-${bundle.document.documentId}",
                operationId = plan.operationId,
                ordinal = importItems.size + offset,
                itemState = ITEM_DUPLICATE_SKIPPED,
                targetDocumentId = "skip:${bundle.document.documentId}",
                targetRevisionId = null,
                proposedTitle = bundle.document.title,
                proposedFolderPath = bundle.document.folderId,
                sourceModifiedAtMillis = bundle.sourceAssets.firstOrNull()?.sourceModifiedAtEpochMillis,
                sourceByteCount = bundle.pages.sumOf { it.byteLength } +
                    bundle.sourceAssets.sumOf { it.byteLength },
                sourcePageCount = bundle.pages.size,
                logicalHashVersion = bundle.fingerprint.version,
                logicalSha256 = bundle.fingerprint.sha256,
                duplicateKind = "EXACT",
                duplicateDocumentId = null,
                duplicateDecision = "SKIP_EXACT",
                failureCode = null,
            )
        }
        dao.insertRestoreOperation(operation, importItems + skippedItems)
    }

    override suspend fun preparePendingDocument(
        operationId: String,
        document: RestoreDocumentToPrepare,
        assets: RestoreStagedAssetSource,
    ) {
        val bundle = document.bundle
        val pageSources = bundle.pages.map { page ->
            LibraryPageSource(
                persistentId = null,
                contentType = page.mimeType,
                sourceCategory = DocumentSourceCategory.LIBRARY.name,
                imageMetadata = DocumentImageMetadata(
                    sourceByteCount = page.byteLength,
                    width = page.width,
                    height = page.height,
                ),
                rotation = DocumentPageRotation.entries.first { it.degrees == page.rotationDegrees },
                filter = DocumentFilter.valueOf(page.filterName),
                ocrText = page.ocrText,
                ocrError = page.ocrError,
                openStream = { assets.openAsset(page.relativePath) },
            )
        }
        val sourceAssets = bundle.sourceAssets.map { source ->
            LibrarySourceAssetSource(
                role = source.role,
                contentType = source.mimeType,
                sourceModifiedAtMillis = source.sourceModifiedAtEpochMillis,
                matchesCurrentRevision = source.matchesCurrentRevision,
                openStream = { assets.openAsset(source.relativePath) },
            )
        }
        val prepared = when (
            val result = fileStore.prepareRevision(
                documentId = document.targetDocumentId,
                sources = pageSources,
                sourceAssets = sourceAssets,
            )
        ) {
            is LibraryResult.Success -> result.value
            is LibraryResult.Failure -> throw RestoreStoreException(result.reason.toRestoreFailure())
        }
        try {
            require(prepared.pages.size == bundle.pages.size)
            prepared.pages.zip(bundle.pages).forEach { (copied, declared) ->
                require(copied.contentSha256 == declared.sha256)
                require(copied.imageMetadata.sourceByteCount == declared.byteLength)
            }
            require(prepared.sourceAssets.size == bundle.sourceAssets.size)
            prepared.sourceAssets.zip(bundle.sourceAssets).forEach { (copied, declared) ->
                require(copied.sha256 == declared.sha256 && copied.byteCount == declared.byteLength)
            }

            val pages = prepared.pages.mapIndexed { index, page ->
                val original = bundle.pages[index]
                LibraryPageEntity(
                    pageId = page.pageId,
                    documentId = document.targetDocumentId,
                    position = index,
                    relativePath = page.relativePath,
                    contentType = page.contentType,
                    sourceCategory = DocumentSourceCategory.LIBRARY.name,
                    width = page.imageMetadata.width,
                    height = page.imageMetadata.height,
                    sourceByteCount = page.imageMetadata.sourceByteCount,
                    rotationDegrees = original.rotationDegrees,
                    filterName = original.filterName,
                    ocrText = original.ocrText,
                    ocrError = original.ocrError,
                    contentSha256 = page.contentSha256,
                )
            }
            val storedSources = prepared.sourceAssets.mapIndexed { index, source ->
                val original = bundle.sourceAssets[index]
                LibrarySourceAssetEntity(
                    assetId = source.assetId,
                    documentId = document.targetDocumentId,
                    role = original.role,
                    relativePath = source.relativePath,
                    contentType = original.mimeType,
                    byteCount = source.byteCount,
                    sha256 = source.sha256,
                    sourceModifiedAtMillis = original.sourceModifiedAtEpochMillis,
                    createdAtMillis = bundle.document.createdAtEpochMillis,
                    matchesCurrentRevision = original.matchesCurrentRevision,
                )
            }
            val pending = LibraryDocumentEntity(
                documentId = document.targetDocumentId,
                title = bundle.document.title,
                createdAtMillis = bundle.document.createdAtEpochMillis,
                modifiedAtMillis = bundle.document.modifiedAtEpochMillis,
                pageCount = pages.size,
                folderId = null,
                thumbnailRelativePath = prepared.thumbnailRelativePath,
                ocrStatus = ocrStatusForRestore(pages).name,
                libraryState = LibraryDocumentState.PENDING.name,
                pendingOperationId = operationId,
                contentHashVersion = bundle.fingerprint.version,
                contentSha256 = bundle.fingerprint.sha256,
                contentByteCount = pages.sumOf { it.sourceByteCount ?: 0L },
                sourceModifiedAtMillis = storedSources.firstOrNull()?.sourceModifiedAtMillis,
                importedAtMillis = clock.nowEpochMillis(),
            )
            val operation = requireNotNull(dao.operation(operationId))
            val item = dao.operationItems(operationId).single { it.itemId == document.itemId }
            val processedBytes = pages.sumOf { it.sourceByteCount ?: 0L } +
                storedSources.sumOf { it.byteCount }
            dao.insertPendingRestoreDocument(
                document = pending,
                pages = pages,
                sourceAssets = storedSources,
                updatedItem = item.copy(itemState = ITEM_PREPARED),
                updatedOperation = operation.copy(
                    phase = PHASE_PREPARING,
                    updatedAtMillis = clock.nowEpochMillis(),
                    preparedDocumentCount = operation.preparedDocumentCount + 1,
                    processedSourceBytes = saturatingAdd(
                        operation.processedSourceBytes,
                        processedBytes,
                    ),
                ),
            )
        } catch (cancelled: CancellationException) {
            fileStore.discardRevision(prepared.revisionDirectory)
            throw cancelled
        } catch (failure: RestoreStoreException) {
            fileStore.discardRevision(prepared.revisionDirectory)
            throw failure
        } catch (failure: Exception) {
            fileStore.discardRevision(prepared.revisionDirectory)
            throw RestoreStoreException(RestoreFailure.PREPARATION_FAILED, failure)
        }
    }

    override suspend fun activate(plan: RestoreActivationPlan) {
        try {
            val operation = requireNotNull(dao.operation(plan.operationId))
            require(operation.preparedDocumentCount == plan.documents.size)
            val completed = operation.copy(
                phase = PHASE_COMPLETED,
                updatedAtMillis = plan.activatedAtEpochMillis,
                importedDocumentCount = plan.documents.size,
                terminalErrorCode = null,
            )
            dao.activateRestoreOperation(
                operationId = plan.operationId,
                folders = plan.folders.map { folder ->
                    LibraryFolderEntity(
                        folderId = folder.folderId,
                        name = folder.name,
                        normalizedName = folder.normalizedName,
                        createdAtMillis = folder.createdAtEpochMillis,
                        modifiedAtMillis = folder.modifiedAtEpochMillis,
                        parentFolderId = folder.parentFolderId,
                    )
                },
                assignments = plan.documents.map { document ->
                    LibraryRestoreDocumentActivation(
                        documentId = document.targetDocumentId,
                        folderId = document.targetFolderId,
                    )
                },
                completedOperation = completed,
                modifiedAt = plan.activatedAtEpochMillis,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw RestoreStoreException(RestoreFailure.ACTIVATION_FAILED, failure)
        }
    }

    override suspend fun isOperationCompleted(operationId: String): Boolean =
        dao.operation(operationId)?.phase == PHASE_COMPLETED

    override suspend fun terminate(
        operationId: String,
        cancelled: Boolean,
        failure: RestoreFailure?,
    ): Boolean {
        val operation = dao.operation(operationId) ?: return true
        // Activation is one Room transaction. A completed journal means every referenced document
        // is ACTIVE, so rollback cleanup must never delete its durable assets.
        if (operation.phase == PHASE_COMPLETED) return true
        val targetIds = dao.operationItems(operationId)
            .filterNot { it.itemState == ITEM_DUPLICATE_SKIPPED }
            .map { it.targetDocumentId }
            .distinct()
        return try {
            dao.discardPendingRestoreDocuments(operationId)
            targetIds.forEach(fileStore::deleteDocument)
            val filesRemoved = targetIds.none { File(fileStore.root, it).exists() }
            dao.updateOperation(
                operation.copy(
                    phase = if (filesRemoved) {
                        if (cancelled) PHASE_CANCELLED else PHASE_FAILED
                    } else {
                        PHASE_CLEANUP_REQUIRED
                    },
                    updatedAtMillis = clock.nowEpochMillis(),
                    failedItemCount = if (cancelled) 0 else operation.plannedDocumentCount,
                    terminalErrorCode = if (cancelled) null else failure?.name ?: "INTERRUPTED",
                ),
            )
            filesRemoved
        } catch (cancelledException: CancellationException) {
            throw cancelledException
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun recoverableOperations(): List<RestoreRecoveryOperation> =
        dao.recoverableOperations()
            .filter { it.operationType == OPERATION_TYPE }
            .map { RestoreRecoveryOperation(it.operationId) }

    private suspend fun readAllPages(documentId: String): List<LibraryPageEntity> {
        val result = ArrayList<LibraryPageEntity>()
        var afterPosition = -1
        while (true) {
            currentCoroutineContext().ensureActive()
            val pages = dao.pagesPage(documentId, afterPosition, DATABASE_PAGE_SIZE)
            if (pages.isEmpty()) break
            result += pages
            afterPosition = pages.last().position
            if (pages.size < DATABASE_PAGE_SIZE) break
        }
        return result
    }

    private fun RestoreDocumentToPrepare.toJournalItem(
        operationId: String,
        ordinal: Int,
        state: String,
    ): LibraryDataOperationItemEntity = LibraryDataOperationItemEntity(
        itemId = itemId,
        operationId = operationId,
        ordinal = ordinal,
        itemState = state,
        targetDocumentId = targetDocumentId,
        targetRevisionId = null,
        proposedTitle = bundle.document.title,
        proposedFolderPath = bundle.document.folderId,
        sourceModifiedAtMillis = bundle.sourceAssets.firstOrNull()?.sourceModifiedAtEpochMillis,
        sourceByteCount = bundle.pages.sumOf { it.byteLength } + bundle.sourceAssets.sumOf { it.byteLength },
        sourcePageCount = bundle.pages.size,
        logicalHashVersion = bundle.fingerprint.version,
        logicalSha256 = bundle.fingerprint.sha256,
        duplicateKind = duplicateKind.name,
        duplicateDocumentId = null,
        duplicateDecision = "IMPORT",
        failureCode = null,
    )

    private companion object {
        const val DATABASE_PAGE_SIZE = 256
        const val OPERATION_TYPE = "RESTORE"
        const val SOURCE_KIND = "RME_BACKUP"
        const val PHASE_PREPARING = "PREPARING"
        const val PHASE_COMPLETED = "COMPLETED"
        const val PHASE_CANCELLED = "CANCELLED"
        const val PHASE_FAILED = "FAILED"
        const val PHASE_CLEANUP_REQUIRED = "CLEANUP_REQUIRED"
        const val ITEM_PLANNED = "PLANNED"
        const val ITEM_PREPARED = "PREPARED"
        const val ITEM_DUPLICATE_SKIPPED = "DUPLICATE_SKIPPED"
    }
}

private fun LibraryError.toRestoreFailure(): RestoreFailure = when (this) {
    LibraryError.SOURCE_MISSING -> RestoreFailure.SOURCE_MISSING
    LibraryError.STORAGE_UNAVAILABLE,
    LibraryError.SOURCE_TOO_LARGE,
    -> RestoreFailure.INSUFFICIENT_STORAGE
    else -> RestoreFailure.PREPARATION_FAILED
}

private fun ocrStatusForRestore(pages: List<LibraryPageEntity>): LibraryOcrStatus {
    if (pages.all { it.ocrText == null && it.ocrError == null }) return LibraryOcrStatus.NOT_INDEXED
    if (pages.all { it.ocrError != null }) return LibraryOcrStatus.FAILED
    if (pages.any { it.ocrError != null }) return LibraryOcrStatus.PARTIAL
    return LibraryOcrStatus.INDEXED
}

private fun saturatingAdd(left: Long, right: Long): Long =
    if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
