package org.synapseworks.pageharbor.migration

import android.content.Context
import android.os.StatFs
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.synapseworks.pageharbor.document.session.DocumentImageMetadata
import org.synapseworks.pageharbor.document.session.DocumentPageRotation
import org.synapseworks.pageharbor.document.session.DocumentSourceCategory
import org.synapseworks.pageharbor.image.DocumentFilter
import org.synapseworks.pageharbor.library.LibraryDao
import org.synapseworks.pageharbor.library.LibraryDataOperationEntity
import org.synapseworks.pageharbor.library.LibraryDataOperationItemEntity
import org.synapseworks.pageharbor.library.LibraryDataOperationSourceEntity
import org.synapseworks.pageharbor.library.LibraryDatabase
import org.synapseworks.pageharbor.library.LibraryDocumentEntity
import org.synapseworks.pageharbor.library.LibraryDocumentState
import org.synapseworks.pageharbor.library.LibraryError
import org.synapseworks.pageharbor.library.LibraryFileStore
import org.synapseworks.pageharbor.library.LibraryOcrStatus
import org.synapseworks.pageharbor.library.LibraryOperationCoordinator
import org.synapseworks.pageharbor.library.LibraryOperationGate
import org.synapseworks.pageharbor.library.LibraryPageEntity
import org.synapseworks.pageharbor.library.LibraryPageSource
import org.synapseworks.pageharbor.library.LibraryRestoreDocumentActivation
import org.synapseworks.pageharbor.library.LibraryResult
import org.synapseworks.pageharbor.library.LibrarySourceAssetEntity
import org.synapseworks.pageharbor.library.LibrarySourceAssetSource
import org.synapseworks.pageharbor.library.completeAtomicActivation
import org.synapseworks.pageharbor.library.normalizeLibraryTitle
import org.synapseworks.pageharbor.library.duplicate.DocumentFingerprint
import org.synapseworks.pageharbor.library.duplicate.DocumentFingerprintV1
import org.synapseworks.pageharbor.library.duplicate.DuplicateDetector
import org.synapseworks.pageharbor.library.duplicate.DuplicateKind
import org.synapseworks.pageharbor.library.duplicate.DuplicateMatch
import org.synapseworks.pageharbor.library.duplicate.FingerprintPage
import org.synapseworks.pageharbor.library.duplicate.IncomingDocumentIdentity
import org.synapseworks.pageharbor.portability.StoragePreflight
import org.synapseworks.pageharbor.portability.StoragePreflightResult

fun interface MigrationStorageCapacity {
    /** Null means free space could not be measured, not zero. */
    fun availableBytes(): Long?
}

internal class AndroidMigrationStorageCapacity(context: Context) : MigrationStorageCapacity {
    private val filesDirectory = context.applicationContext.filesDir

    override fun availableBytes(): Long? = try {
        StatFs(filesDirectory.path).availableBytes.takeIf { it >= 0L }
    } catch (_: RuntimeException) {
        null
    }
}

internal data class MigrationJournalMetadata(
    val sourceKind: String,
    val sourceRootUri: String?,
    val sourceGrantFlags: Int,
)

/**
 * Publishes one plan under the global library mutation gate. Sources are staged before duplicate
 * classification; approved documents then move PENDING -> ACTIVE in a single Room transaction.
 */
internal class RoomMigrationDocumentPublisher(
    context: Context,
    private val sourceAccess: MigrationSourceAccess,
    private val duplicateDecisions: MigrationDuplicateDecisions,
    private val journalMetadata: MigrationJournalMetadata,
    private val dao: LibraryDao = LibraryDatabase.get(context).libraryDao(),
    private val fileStore: LibraryFileStore = LibraryFileStore(context),
    private val storageCapacity: MigrationStorageCapacity = AndroidMigrationStorageCapacity(context),
    private val operationGate: LibraryOperationGate = LibraryOperationCoordinator.gate,
    private val duplicateCandidates: MigrationDuplicateCandidateSnapshot =
        RoomMigrationDuplicateCandidateSource(dao, operationGate),
    private val idSource: () -> String = { UUID.randomUUID().toString() },
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val stager: MigrationDocumentStager = AndroidMigrationDocumentStager(
        workspaceRoot = File(context.applicationContext.cacheDir, MIGRATION_WORKSPACE_DIRECTORY),
        sourceAccess = sourceAccess,
    ),
) : MigrationDocumentPublisher {
    override suspend fun publish(
        document: MigrationDocumentPlan,
        context: MigrationPublicationContext,
    ): MigrationPublicationResult = try {
        operationGate.withMutation {
            publishLocked(document, context)
        }
    } catch (cancelled: CancellationException) {
        if (context.cancellationSignal.isCancellationRequested()) {
            MigrationPublicationResult.Cancelled
        } else {
            throw cancelled
        }
    } catch (_: SecurityException) {
        MigrationPublicationResult.Failed(MigrationPublicationFailure.SOURCE_UNAVAILABLE)
    } catch (_: IllegalArgumentException) {
        MigrationPublicationResult.Failed(
            MigrationPublicationFailure.INVALID_DOCUMENT,
            retryable = false,
        )
    } catch (_: Exception) {
        MigrationPublicationResult.Failed(MigrationPublicationFailure.WRITE_FAILED)
    }

    private suspend fun publishLocked(
        plan: MigrationDocumentPlan,
        context: MigrationPublicationContext,
    ): MigrationPublicationResult {
        if (context.cancellationSignal.isCancellationRequested()) {
            return MigrationPublicationResult.Cancelled
        }
        if (!hasStorageFor(plan)) {
            return MigrationPublicationResult.Failed(
                MigrationPublicationFailure.INSUFFICIENT_STORAGE,
                retryable = true,
            )
        }
        stager.cleanAbandonedWorkspaces()
        val staged = when (val result = stager.stage(plan, context)) {
            MigrationStagingResult.Cancelled -> return MigrationPublicationResult.Cancelled
            is MigrationStagingResult.Failed -> return MigrationPublicationResult.Failed(
                result.reason,
                result.retryable,
            )
            is MigrationStagingResult.Ready -> result.document
        }
        staged.use {
            if (context.cancellationSignal.isCancellationRequested()) {
                return MigrationPublicationResult.Cancelled
            }
            if (!hasStorageFor(staged)) {
                return MigrationPublicationResult.Failed(
                    MigrationPublicationFailure.INSUFFICIENT_STORAGE,
                    retryable = true,
                )
            }
            val fingerprint = staged.fingerprintForMigration()
            val duplicate = DuplicateDetector.classify(
                incoming = staged.incomingIdentityForMigration(fingerprint),
                existing = duplicateCandidates.snapshot(context.cancellationSignal),
            )
            val explicitlyApproved = duplicateDecisions.shouldImport(plan.id)
            if (duplicate.kind == DuplicateKind.EXACT && !explicitlyApproved) {
                val existingId = requireNotNull(duplicate.documentId)
                if (!recordTerminalDuplicate(plan, staged, fingerprint, duplicate, exact = true)) {
                    return MigrationPublicationResult.Failed(MigrationPublicationFailure.WRITE_FAILED)
                }
                return MigrationPublicationResult.ExactDuplicateSkipped(existingId)
            }
            if (duplicate.kind == DuplicateKind.POSSIBLE && !explicitlyApproved) {
                val existingId = requireNotNull(duplicate.documentId)
                if (!recordTerminalDuplicate(plan, staged, fingerprint, duplicate, exact = false)) {
                    return MigrationPublicationResult.Failed(MigrationPublicationFailure.WRITE_FAILED)
                }
                return MigrationPublicationResult.PossibleDuplicateRequiresReview(existingId)
            }
            if (context.cancellationSignal.isCancellationRequested()) {
                return MigrationPublicationResult.Cancelled
            }
            val folderPlan = MigrationFolderPlanner.plan(
                relativePath = plan.relativeFolderPath,
                existingFolders = readFolders(context.cancellationSignal),
                newId = idSource,
                nowMillis = nowMillis(),
            )
            val published = publishPrepared(
                plan = plan,
                staged = staged,
                fingerprint = fingerprint,
                duplicate = duplicate,
                folderPlan = folderPlan,
                context = context,
            )
            return when {
                published != MigrationPublicationResult.Published -> published
                duplicate.kind == DuplicateKind.POSSIBLE ->
                    MigrationPublicationResult.PublishedPossibleDuplicate(
                        requireNotNull(duplicate.documentId),
                    )
                else -> MigrationPublicationResult.Published
            }
        }
    }

    private fun hasStorageFor(plan: MigrationDocumentPlan): Boolean {
        val estimate = StoragePreflight.estimate(
            sourceSizes = plan.sources.map { it.source.sizeBytes },
            workingCopyCount = if (plan.grouping == MigrationDocumentGrouping.SINGLE_PDF) 3 else 2,
            fixedOverheadBytes = PER_DOCUMENT_STORAGE_OVERHEAD_BYTES,
        )
        return StoragePreflight.evaluate(estimate, storageCapacity.availableBytes()) !is
            StoragePreflightResult.Insufficient
    }

    private fun hasStorageFor(staged: StagedMigrationDocument): Boolean {
        val estimate = StoragePreflight.estimate(
            sourceSizes = buildList {
                addAll(staged.pages.map { it.byteCount })
                staged.originalPdf?.let { add(it.byteCount) }
            },
            // Staging already exists; this check covers the additional durable library copy.
            workingCopyCount = 1,
            fixedOverheadBytes = PER_DOCUMENT_STORAGE_OVERHEAD_BYTES,
        )
        return StoragePreflight.evaluate(estimate, storageCapacity.availableBytes()) !is
            StoragePreflightResult.Insufficient
    }

    suspend fun recoverInterruptedOperations(): Int = operationGate.withMutation {
        stager.cleanAbandonedWorkspaces()
        var recovered = 0
        dao.recoverableOperations()
            .filter { it.operationType == OPERATION_TYPE }
            .forEach { operation ->
                val documentIds = dao.operationItems(operation.operationId)
                    .map { it.targetDocumentId }
                    .filterNot { it.startsWith("duplicate:") }
                    .distinct()
                val databaseCleaned = runCatching {
                    dao.discardPendingRestoreDocuments(operation.operationId)
                    dao.pendingDocuments(operation.operationId).isEmpty()
                }.getOrDefault(false)
                documentIds.forEach(fileStore::deleteDocument)
                val filesCleaned = documentIds.none { documentId ->
                    File(fileStore.root, documentId).exists()
                }
                val journalUpdated = runCatching {
                    dao.updateOperation(
                        operation.copy(
                            phase = if (databaseCleaned && filesCleaned) {
                                PHASE_FAILED
                            } else {
                                PHASE_CLEANUP_REQUIRED
                            },
                            updatedAtMillis = nowMillis(),
                            failedItemCount = operation.plannedDocumentCount,
                            terminalErrorCode = FAILURE_INTERRUPTED,
                        ),
                    )
                }.isSuccess
                if (databaseCleaned && filesCleaned && journalUpdated) recovered += 1
            }
        recovered
    }

    private suspend fun publishPrepared(
        plan: MigrationDocumentPlan,
        staged: StagedMigrationDocument,
        fingerprint: DocumentFingerprint,
        duplicate: DuplicateMatch,
        folderPlan: MigrationFolderPlan,
        context: MigrationPublicationContext,
    ): MigrationPublicationResult {
        val now = nowMillis()
        val operationId = idSource()
        val itemId = idSource()
        val targetDocumentId = idSource()
        val initialOperation = operation(
            operationId = operationId,
            phase = PHASE_PREPARING,
            plan = plan,
            staged = staged,
            now = now,
            preparedCount = 0,
            importedCount = 0,
            skippedCount = 0,
        )
        val initialItem = item(
            itemId = itemId,
            operationId = operationId,
            targetDocumentId = targetDocumentId,
            plan = plan,
            staged = staged,
            fingerprint = fingerprint,
            duplicate = duplicate,
            state = ITEM_PLANNED,
            decision = if (duplicate.kind == DuplicateKind.DIFFERENT) {
                DECISION_IMPORT
            } else {
                DECISION_IMPORT_CONFIRMED
            },
        )
        try {
            dao.insertRestoreOperation(initialOperation, listOf(initialItem))
            dao.insertOperationSources(
                sourceJournalEntities(plan, staged, itemId, SOURCE_STATE_VERIFIED),
            )
        } catch (_: Exception) {
            runCatching { dao.deleteCleanedPendingOperation(operationId) }
            return MigrationPublicationResult.Failed(MigrationPublicationFailure.WRITE_FAILED)
        }

        if (context.cancellationSignal.isCancellationRequested()) {
            terminateOperation(operationId, targetDocumentId, cancelled = true, failure = null)
            return MigrationPublicationResult.Cancelled
        }
        val pageSources = staged.pages.map { page ->
            LibraryPageSource(
                persistentId = null,
                contentType = page.contentType,
                sourceCategory = if (staged.originalPdf == null) {
                    DocumentSourceCategory.SELECTED_IMAGE.name
                } else {
                    DocumentSourceCategory.RENDERED_PDF_PAGE.name
                },
                imageMetadata = DocumentImageMetadata(page.byteCount, page.width, page.height),
                rotation = DocumentPageRotation.DEGREES_0,
                filter = DocumentFilter.ORIGINAL,
                ocrText = null,
                ocrError = null,
                openStream = {
                    CancellationCheckingInputStream(
                        FileInputStream(page.file),
                        context.cancellationSignal,
                    )
                },
            )
        }
        val sourceAssets = staged.originalPdf?.let { source ->
            listOf(
                LibrarySourceAssetSource(
                    role = ORIGINAL_DOCUMENT_ASSET_ROLE,
                    contentType = MigrationContentType.PDF.mimeType,
                    sourceModifiedAtMillis = source.sourceModifiedAtMillis,
                    matchesCurrentRevision = true,
                    openStream = {
                        CancellationCheckingInputStream(
                            FileInputStream(source.file),
                            context.cancellationSignal,
                        )
                    },
                ),
            )
        }.orEmpty()
        val prepared = try {
            when (
                val result = fileStore.prepareRevision(
                    documentId = targetDocumentId,
                    sources = pageSources,
                    sourceAssets = sourceAssets,
                )
            ) {
                is LibraryResult.Success -> result.value
                is LibraryResult.Failure -> {
                    terminateOperation(
                        operationId,
                        targetDocumentId,
                        cancelled = false,
                        failure = result.reason,
                    )
                    return MigrationPublicationResult.Failed(result.reason.toMigrationFailure())
                }
            }
        } catch (cancelled: CancellationException) {
            terminateOperation(operationId, targetDocumentId, cancelled = true, failure = null)
            if (context.cancellationSignal.isCancellationRequested()) {
                return MigrationPublicationResult.Cancelled
            }
            throw cancelled
        }
        try {
            check(prepared.pages.size == staged.pages.size)
            prepared.pages.zip(staged.pages).forEach { (stored, source) ->
                check(stored.contentSha256 == source.sha256)
                check(stored.imageMetadata.sourceByteCount == source.byteCount)
            }
            val original = staged.originalPdf
            if (original != null) {
                val storedOriginal = prepared.sourceAssets.single()
                check(storedOriginal.sha256 == original.sha256)
                check(storedOriginal.byteCount == original.byteCount)
            } else {
                check(prepared.sourceAssets.isEmpty())
            }
            val pages = prepared.pages.map { page ->
                LibraryPageEntity(
                    pageId = page.pageId,
                    documentId = targetDocumentId,
                    position = page.position,
                    relativePath = page.relativePath,
                    contentType = page.contentType,
                    sourceCategory = page.sourceCategory,
                    width = page.imageMetadata.width,
                    height = page.imageMetadata.height,
                    sourceByteCount = page.imageMetadata.sourceByteCount,
                    rotationDegrees = page.rotation.degrees,
                    filterName = page.filter.name,
                    ocrText = null,
                    ocrError = null,
                    contentSha256 = page.contentSha256,
                )
            }
            val sourceAssetEntities = prepared.sourceAssets.map { asset ->
                LibrarySourceAssetEntity(
                    assetId = asset.assetId,
                    documentId = targetDocumentId,
                    role = asset.role,
                    relativePath = asset.relativePath,
                    contentType = asset.contentType,
                    byteCount = asset.byteCount,
                    sha256 = asset.sha256,
                    sourceModifiedAtMillis = asset.sourceModifiedAtMillis,
                    createdAtMillis = now,
                    matchesCurrentRevision = asset.matchesCurrentRevision,
                )
            }
            val pendingDocument = LibraryDocumentEntity(
                documentId = targetDocumentId,
                title = normalizeLibraryTitle(plan.suggestedTitle).ifBlank { "Imported document" },
                createdAtMillis = now,
                modifiedAtMillis = now,
                pageCount = pages.size,
                folderId = null,
                thumbnailRelativePath = prepared.thumbnailRelativePath,
                ocrStatus = LibraryOcrStatus.NOT_INDEXED.name,
                libraryState = LibraryDocumentState.PENDING.name,
                pendingOperationId = operationId,
                contentHashVersion = fingerprint.version,
                contentSha256 = fingerprint.sha256,
                contentByteCount = staged.pages.sumKnownBytes(),
                sourceModifiedAtMillis = plan.sources
                    .mapNotNull { it.source.modifiedAtMillis }
                    .maxOrNull(),
                importedAtMillis = now,
            )
            val preparedOperation = initialOperation.copy(
                updatedAtMillis = nowMillis(),
                preparedDocumentCount = 1,
                processedSourceBytes = staged.copiedSourceBytes,
            )
            dao.insertPendingRestoreDocument(
                document = pendingDocument,
                pages = pages,
                sourceAssets = sourceAssetEntities,
                updatedItem = initialItem.copy(
                    itemState = ITEM_PREPARED,
                    targetRevisionId = prepared.revisionDirectory.name,
                ),
                updatedOperation = preparedOperation,
            )
            if (context.cancellationSignal.isCancellationRequested()) {
                terminateOperation(operationId, targetDocumentId, cancelled = true, failure = null)
                return MigrationPublicationResult.Cancelled
            }
            val activatedAt = nowMillis()
            return completeMigrationActivation(
                activate = {
                    dao.activateRestoreOperation(
                        operationId = operationId,
                        folders = folderPlan.foldersToCreate,
                        assignments = listOf(
                            LibraryRestoreDocumentActivation(targetDocumentId, folderPlan.targetFolderId),
                        ),
                        completedOperation = preparedOperation.copy(
                            phase = PHASE_COMPLETED,
                            updatedAtMillis = activatedAt,
                            importedDocumentCount = 1,
                        ),
                        modifiedAt = activatedAt,
                    )
                },
                isCommitted = { dao.operation(operationId)?.phase == PHASE_COMPLETED },
            )
        } catch (cancelled: CancellationException) {
            terminateOperation(operationId, targetDocumentId, cancelled = true, failure = null)
            if (context.cancellationSignal.isCancellationRequested()) {
                return MigrationPublicationResult.Cancelled
            }
            throw cancelled
        } catch (_: Exception) {
            terminateOperation(
                operationId,
                targetDocumentId,
                cancelled = false,
                failure = LibraryError.DATABASE_UNAVAILABLE,
            )
            return MigrationPublicationResult.Failed(MigrationPublicationFailure.WRITE_FAILED)
        }
    }

    private suspend fun recordTerminalDuplicate(
        plan: MigrationDocumentPlan,
        staged: StagedMigrationDocument,
        fingerprint: DocumentFingerprint,
        duplicate: DuplicateMatch,
        exact: Boolean,
    ): Boolean {
        val now = nowMillis()
        val operationId = idSource()
        val itemId = idSource()
        return try {
            dao.insertRestoreOperation(
                operation(
                    operationId = operationId,
                    phase = PHASE_COMPLETED,
                    plan = plan,
                    staged = staged,
                    now = now,
                    preparedCount = 0,
                    importedCount = 0,
                    skippedCount = if (exact) 1 else 0,
                ),
                listOf(
                    item(
                        itemId = itemId,
                        operationId = operationId,
                        targetDocumentId = "duplicate:${idSource()}",
                        plan = plan,
                        staged = staged,
                        fingerprint = fingerprint,
                        duplicate = duplicate,
                        state = if (exact) ITEM_DUPLICATE_SKIPPED else ITEM_REVIEW_REQUIRED,
                        decision = if (exact) DECISION_SKIP_EXACT else DECISION_REQUIRE_REVIEW,
                    ),
                ),
            )
            dao.insertOperationSources(
                sourceJournalEntities(plan, staged, itemId, SOURCE_STATE_VERIFIED),
            )
            true
        } catch (_: Exception) {
            runCatching { dao.deleteCleanedPendingOperation(operationId) }
            false
        }
    }

    private suspend fun readFolders(
        cancellationSignal: MigrationCancellationSignal,
    ): List<MigrationExistingFolder> {
        val result = mutableListOf<MigrationExistingFolder>()
        var offset = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            if (cancellationSignal.isCancellationRequested()) throw CancellationException()
            val folders = dao.foldersPage(offset, DATABASE_PAGE_SIZE)
            if (folders.isEmpty()) break
            result += folders.map { folder ->
                MigrationExistingFolder(
                    folder.folderId,
                    folder.name,
                    folder.normalizedName,
                    folder.parentFolderId,
                )
            }
            offset += folders.size
            if (folders.size < DATABASE_PAGE_SIZE) break
        }
        return result
    }

    private suspend fun terminateOperation(
        operationId: String,
        targetDocumentId: String,
        cancelled: Boolean,
        failure: LibraryError?,
    ) = withContext(NonCancellable) {
        val operationLookup = runCatching { dao.operation(operationId) }
        // If the journal cannot be read, leave the revision in place for later recovery. Deleting
        // on an unknown state could remove assets already referenced by an ACTIVE document.
        if (operationLookup.isFailure) return@withContext
        if (operationLookup.getOrNull()?.phase == PHASE_COMPLETED) {
            return@withContext
        }
        val databaseCleaned = runCatching {
            dao.discardPendingRestoreDocuments(operationId)
            dao.pendingDocuments(operationId).isEmpty()
        }.getOrDefault(false)
        runCatching { fileStore.deleteDocument(targetDocumentId) }
        val filesCleaned = !File(fileStore.root, targetDocumentId).exists()
        runCatching {
            dao.operation(operationId)?.let { operation ->
                dao.updateOperation(
                    operation.copy(
                        phase = if (databaseCleaned && filesCleaned) {
                            if (cancelled) PHASE_CANCELLED else PHASE_FAILED
                        } else {
                            PHASE_CLEANUP_REQUIRED
                        },
                        updatedAtMillis = nowMillis(),
                        failedItemCount = if (cancelled) 0 else 1,
                        terminalErrorCode = failure?.name,
                    ),
                )
            }
        }
    }

    private fun operation(
        operationId: String,
        phase: String,
        plan: MigrationDocumentPlan,
        staged: StagedMigrationDocument,
        now: Long,
        preparedCount: Int,
        importedCount: Int,
        skippedCount: Int,
    ) = LibraryDataOperationEntity(
        operationId = operationId,
        operationType = OPERATION_TYPE,
        phase = phase,
        sourceKind = journalMetadata.sourceKind,
        sourceRootUri = journalMetadata.sourceRootUri,
        sourceGrantFlags = journalMetadata.sourceGrantFlags,
        createdAtMillis = now,
        updatedAtMillis = now,
        discoveredItemCount = plan.sources.size,
        plannedDocumentCount = 1,
        preparedDocumentCount = preparedCount,
        importedDocumentCount = importedCount,
        skippedDuplicateCount = skippedCount,
        failedItemCount = 0,
        totalSourceBytes = plan.sources.map { it.source.sizeBytes }.sumIfAllKnown(),
        processedSourceBytes = staged.copiedSourceBytes,
        cancelRequested = false,
        terminalErrorCode = null,
    )

    private fun item(
        itemId: String,
        operationId: String,
        targetDocumentId: String,
        plan: MigrationDocumentPlan,
        staged: StagedMigrationDocument,
        fingerprint: DocumentFingerprint,
        duplicate: DuplicateMatch,
        state: String,
        decision: String,
    ) = LibraryDataOperationItemEntity(
        itemId = itemId,
        operationId = operationId,
        ordinal = 0,
        itemState = state,
        targetDocumentId = targetDocumentId,
        targetRevisionId = null,
        proposedTitle = plan.suggestedTitle,
        proposedFolderPath = plan.relativeFolderPath.takeIf(List<String>::isNotEmpty)
            ?.joinToString("/"),
        sourceModifiedAtMillis = plan.sources.mapNotNull { it.source.modifiedAtMillis }.maxOrNull(),
        sourceByteCount = plan.sources.map { it.source.sizeBytes }.sumIfAllKnown(),
        sourcePageCount = staged.pages.size,
        logicalHashVersion = fingerprint.version,
        logicalSha256 = fingerprint.sha256,
        duplicateKind = duplicate.kind.name,
        duplicateDocumentId = duplicate.documentId,
        duplicateDecision = decision,
        failureCode = null,
    )

    private fun sourceJournalEntities(
        plan: MigrationDocumentPlan,
        staged: StagedMigrationDocument,
        itemId: String,
        state: String,
    ): List<LibraryDataOperationSourceEntity> = plan.sources.mapIndexed { index, inspected ->
        val source = inspected.source
        val sha256 = if (plan.grouping == MigrationDocumentGrouping.SINGLE_PDF) {
            staged.originalPdf?.sha256
        } else {
            staged.pages.getOrNull(index)?.sha256
        }
        LibraryDataOperationSourceEntity(
            sourceId = idSource(),
            itemId = itemId,
            sourcePosition = index,
            sourceUri = source.id,
            relativeSourcePath = (source.relativeFolderPath + listOfNotNull(source.displayName))
                .takeIf(List<String>::isNotEmpty)
                ?.joinToString("/"),
            displayName = source.displayName,
            declaredMimeType = source.declaredContentType,
            detectedMimeType = inspected.contentType.mimeType,
            sourceByteCount = source.sizeBytes,
            sourceModifiedAtMillis = source.modifiedAtMillis,
            sourceSha256 = sha256,
            sourceState = state,
            failureCode = null,
        )
    }

    private companion object {
        const val DATABASE_PAGE_SIZE = 256
        const val MIGRATION_WORKSPACE_DIRECTORY = "bulk-migration"
        const val ORIGINAL_DOCUMENT_ASSET_ROLE = "ORIGINAL_DOCUMENT"
        const val OPERATION_TYPE = "MIGRATION"
        const val PHASE_PREPARING = "PREPARING"
        const val PHASE_COMPLETED = "COMPLETED"
        const val PHASE_CANCELLED = "CANCELLED"
        const val PHASE_FAILED = "FAILED"
        const val PHASE_CLEANUP_REQUIRED = "CLEANUP_REQUIRED"
        const val ITEM_PLANNED = "PLANNED"
        const val ITEM_PREPARED = "PREPARED"
        const val ITEM_DUPLICATE_SKIPPED = "DUPLICATE_SKIPPED"
        const val ITEM_REVIEW_REQUIRED = "REVIEW_REQUIRED"
        const val DECISION_IMPORT = "IMPORT"
        const val DECISION_IMPORT_CONFIRMED = "IMPORT_CONFIRMED"
        const val DECISION_SKIP_EXACT = "SKIP_EXACT"
        const val DECISION_REQUIRE_REVIEW = "REQUIRE_REVIEW"
        const val SOURCE_STATE_VERIFIED = "VERIFIED"
        const val FAILURE_INTERRUPTED = "INTERRUPTED"
        const val PER_DOCUMENT_STORAGE_OVERHEAD_BYTES = 512L * 1024L
    }
}

private class CancellationCheckingInputStream(
    input: InputStream,
    private val cancellationSignal: MigrationCancellationSignal,
) : FilterInputStream(input) {
    override fun read(): Int {
        checkCancellation()
        return super.read()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        checkCancellation()
        return super.read(buffer, offset, length)
    }

    private fun checkCancellation() {
        if (cancellationSignal.isCancellationRequested()) throw CancellationException()
    }
}

internal suspend fun completeMigrationActivation(
    activate: suspend () -> Unit,
    isCommitted: suspend () -> Boolean,
): MigrationPublicationResult {
    completeAtomicActivation(activate, isCommitted)
    return MigrationPublicationResult.Published
}

internal fun StagedMigrationDocument.fingerprintForMigration(): DocumentFingerprint =
    DocumentFingerprintV1.calculate(
        pages.map { page ->
            FingerprintPage(
                assetSha256 = page.sha256,
                mimeType = page.contentType,
                byteLength = page.byteCount,
                rotationDegrees = 0,
                filterName = DocumentFilter.ORIGINAL.name,
            )
        },
    )

internal fun StagedMigrationDocument.incomingIdentityForMigration(
    fingerprint: DocumentFingerprint,
) = IncomingDocumentIdentity(
    fingerprint = fingerprint,
    sourceSha256 = originalPdf?.let { setOf(it.sha256) }.orEmpty(),
    pageCount = pages.size,
    contentByteLength = pages.sumKnownBytes(),
    orderedMimeTypes = pages.map { it.contentType },
)

private fun List<StagedMigrationPage>.sumKnownBytes(): Long =
    fold(0L) { total, page -> saturatedAdd(total, page.byteCount) }

private fun List<Long?>.sumIfAllKnown(): Long? {
    if (any { it == null }) return null
    return fold(0L) { total, value -> saturatedAdd(total, requireNotNull(value)) }
}

private fun LibraryError.toMigrationFailure(): MigrationPublicationFailure = when (this) {
    LibraryError.SOURCE_MISSING -> MigrationPublicationFailure.SOURCE_UNAVAILABLE
    LibraryError.SOURCE_TOO_LARGE -> MigrationPublicationFailure.SOURCE_TOO_LARGE
    LibraryError.STORAGE_UNAVAILABLE -> MigrationPublicationFailure.INSUFFICIENT_STORAGE
    LibraryError.OPERATION_INTERRUPTED -> MigrationPublicationFailure.INTERRUPTED
    LibraryError.EMPTY_DOCUMENT,
    LibraryError.PAGE_LIMIT_EXCEEDED,
    LibraryError.INVALID_SELECTION,
    LibraryError.CORRUPTED_RECORD,
    -> MigrationPublicationFailure.INVALID_DOCUMENT
    else -> MigrationPublicationFailure.WRITE_FAILED
}

private fun saturatedAdd(left: Long, right: Long): Long =
    if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
