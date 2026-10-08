package org.synapseworks.pageharbor.migration

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.security.MessageDigest
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
import org.synapseworks.pageharbor.library.LibraryFileStore
import org.synapseworks.pageharbor.library.LibraryOcrStatus
import org.synapseworks.pageharbor.library.LibraryFileTimeProvenance
import org.synapseworks.pageharbor.library.LibrarySourceProvenanceKind
import org.synapseworks.pageharbor.library.LibraryOperationCoordinator
import org.synapseworks.pageharbor.library.LibraryOperationGate
import org.synapseworks.pageharbor.library.LibraryPageEntity
import org.synapseworks.pageharbor.library.LibraryPageSource
import org.synapseworks.pageharbor.library.LibraryPendingRestoreFolderEntity
import org.synapseworks.pageharbor.library.LibraryResult
import org.synapseworks.pageharbor.library.LibrarySourceAssetEntity
import org.synapseworks.pageharbor.library.LibrarySourceAssetSource
import org.synapseworks.pageharbor.library.completeAtomicActivation
import org.synapseworks.pageharbor.library.normalizeLibraryTitle
import org.synapseworks.pageharbor.library.duplicate.DocumentFingerprint
import org.synapseworks.pageharbor.library.duplicate.DuplicateCandidate
import org.synapseworks.pageharbor.library.duplicate.DuplicateKind
import org.synapseworks.pageharbor.library.duplicate.DuplicateMatch
import org.synapseworks.pageharbor.library.duplicate.IncomingDocumentIdentity
import org.synapseworks.pageharbor.portability.StoragePreflight
import org.synapseworks.pageharbor.portability.StoragePreflightResult

/**
 * Durable M2 batch execution over the existing Room operation journal and private library store.
 * File I/O completes before one final Room visibility transaction for the approved batch.
 */
internal class RoomVerifiedMigrationExecutor(
    context: Context,
    private val sourceAccess: MigrationSourceAccess,
    private val sourceRevalidator: MigrationSourceRevalidator,
    private val journalMetadata: MigrationJournalMetadata,
    private val dao: LibraryDao = LibraryDatabase.get(context).libraryDao(),
    private val fileStore: LibraryFileStore = LibraryFileStore(context),
    private val storageCapacity: MigrationStorageCapacity = AndroidMigrationStorageCapacity(context),
    private val operationGate: LibraryOperationGate = LibraryOperationCoordinator.gate,
    private val duplicateCandidates: MigrationDuplicateCandidateSnapshot =
        RoomMigrationDuplicateCandidateSource(dao, operationGate),
    private val idSource: () -> String = { UUID.randomUUID().toString() },
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val faultInjector: MigrationExecutionFaultInjector = NoMigrationExecutionFaults,
    private val stager: MigrationDocumentStager = AndroidMigrationDocumentStager(
        workspaceRoot = File(context.applicationContext.cacheDir, WORKSPACE_DIRECTORY),
        sourceAccess = sourceAccess,
    ),
) {
    suspend fun execute(
        plan: MigrationImportPlan,
        decisions: MigrationExecutionDecisions,
        cancellationSignal: MigrationCancellationSignal,
        progressListener: MigrationBatchProgressListener,
    ): MigrationBatchReport {
        val operationId = createOperation(plan, decisions)
        faultInjector.checkpoint(
            MigrationExecutionCheckpoint.AFTER_JOURNAL_CREATED,
            operationId,
            null,
        )
        return runOperation(
            operationId = operationId,
            planById = plan.resolveExecutionDecisions(decisions)
                .associate { it.plan.id to it.plan },
            rejectedSources = plan.rejectedSources,
            decisionOverrides = decisions,
            cancellationSignal = cancellationSignal,
            progressListener = progressListener,
        )
    }

    suspend fun resume(
        operationId: String,
        decisions: MigrationExecutionDecisions?,
        cancellationSignal: MigrationCancellationSignal,
        progressListener: MigrationBatchProgressListener,
    ): MigrationBatchReport {
        require(operationId.isNotBlank())
        return runOperation(
            operationId = operationId,
            planById = emptyMap(),
            rejectedSources = emptyList(),
            decisionOverrides = decisions,
            cancellationSignal = cancellationSignal,
            progressListener = progressListener,
        )
    }

    suspend fun recoverInterruptedOperations(): Int {
        stager.cleanAbandonedWorkspaces()
        val recoverable = operationGate.withMutation {
            dao.recoverableOperations().filter { it.operationType == OPERATION_TYPE }
        }
        var recovered = 0
        recoverable.forEach { operation ->
            if (operation.phase in setOf(PHASE_ACTIVATED, PHASE_CLEANUP_REQUIRED)) {
                if (finalizeActivatedOperation(operation.operationId)) recovered += 1
                return@forEach
            }
            repairInterruptedItems(operation.operationId)
            if (!operation.cancelRequested && operation.phase !in setOf(
                    PHASE_REVIEW_REQUIRED,
                    PHASE_FAILED,
                    PHASE_CANCELLED,
                )
            ) {
                runCatching {
                    resume(
                        operationId = operation.operationId,
                        decisions = null,
                        cancellationSignal = NeverCancelMigration,
                        progressListener = MigrationBatchProgressListener { },
                    )
                }.onSuccess { recovered += 1 }
            } else {
                recovered += 1
            }
        }
        return recovered
    }

    suspend fun completionReport(operationId: String): MigrationDurableCompletionReport =
        operationGate.withStableSnapshot { durableReport(operationId) }

    private suspend fun createOperation(
        plan: MigrationImportPlan,
        decisions: MigrationExecutionDecisions,
    ): String = operationGate.withMutation {
        val operationId = idSource()
        require(operationId.isNotBlank())
        val resolved = plan.resolveExecutionDecisions(decisions)
        val folderAssignments = planFolders(resolved.filter {
            it.action == MigrationResolvedDocumentAction.IMPORT
        }.map(MigrationResolvedDocument::plan))
        val now = nowMillis()
        val items = mutableListOf<LibraryDataOperationItemEntity>()
        val sources = mutableListOf<LibraryDataOperationSourceEntity>()
        var ordinal = 0
        resolved.forEach { resolvedDocument ->
            val document = resolvedDocument.plan
            val itemId = "$operationId|${document.id}"
            val duplicatePreview = plan.duplicateDocuments.firstOrNull {
                it.documentId == document.id
            }
            val duplicateKind = duplicatePreview?.duplicateKind ?: DuplicateKind.DIFFERENT
            val duplicateDecision = decisions.duplicateDecisions[document.id]?.name
                ?: DECISION_SAFE_DEFAULT
            val initialState = when (resolvedDocument.action) {
                MigrationResolvedDocumentAction.IMPORT -> ITEM_PLANNED
                MigrationResolvedDocumentAction.EXCLUDE -> ITEM_EXCLUDED
                MigrationResolvedDocumentAction.DEFER -> ITEM_REVIEW_UNRESOLVED
            }
            val assignment = folderAssignments.assignments[document.id]
            items += LibraryDataOperationItemEntity(
                itemId = itemId,
                operationId = operationId,
                ordinal = ordinal++,
                itemState = initialState,
                targetDocumentId = idSource(),
                targetRevisionId = null,
                proposedTitle = document.suggestedTitle,
                proposedFolderPath = assignment?.targetFolderId,
                sourceModifiedAtMillis = document.sources
                    .mapNotNull { it.source.modifiedAtMillis }
                    .maxOrNull(),
                sourceByteCount = document.sources.map { it.source.sizeBytes }.sumIfKnown(),
                sourcePageCount = document.pages.size.takeIf { it > 0 }
                    ?: document.sources.sumOf { it.pageCount ?: 0 }.takeIf { it > 0 },
                logicalHashVersion = null,
                logicalSha256 = null,
                duplicateKind = duplicateKind.name,
                duplicateDocumentId = duplicatePreview?.existingDocumentId,
                duplicateDecision = encodeDecisions(
                    resolvedDocument.recordedDecision,
                    duplicateDecision,
                ),
                failureCode = null,
                originalCreatedAtMillis = document.dateCandidates
                    .filter {
                        it.kind == MigrationDateCandidateKind.ORIGINAL_FILE_CREATED &&
                            it.authoritativeForKind
                    }
                    .mapNotNull(MigrationDateCandidate::instantMillis)
                    .distinct()
                    .singleOrNull(),
            )
            sources += document.sources.mapIndexed { sourcePosition, inspected ->
                inspected.toJournalSource(itemId, sourcePosition)
            }
        }
        plan.rejectedSources.forEach { rejected ->
            val itemId = "$operationId|$REJECTED_ITEM_MARKER${rejected.source.discoveryIdentity}"
            items += terminalItem(
                itemId = itemId,
                operationId = operationId,
                ordinal = ordinal++,
                title = rejected.source.displayName ?: "Unsupported source",
                state = if (rejected.issue == MigrationSourceIssue.UNSUPPORTED_CONTENT) {
                    ITEM_UNSUPPORTED
                } else {
                    ITEM_VALIDATION_FAILED
                },
                failureCode = rejected.failureDetail.name,
            )
            sources += rejected.source.toJournalSource(
                itemId = itemId,
                sourcePosition = 0,
                detectedMimeType = null,
                sha256 = null,
                state = if (rejected.issue == MigrationSourceIssue.UNSUPPORTED_CONTENT) {
                    SOURCE_UNSUPPORTED
                } else {
                    SOURCE_VALIDATION_FAILED
                },
            )
        }
        val represented = sources.mapTo(hashSetOf()) { it.sourceId.substringAfterLast('|') }
        plan.requestedSources.filterNot { it.discoveryIdentity in represented }.forEach { source ->
            val itemId = "$operationId|$UNPROCESSED_ITEM_MARKER${source.discoveryIdentity}"
            items += terminalItem(
                itemId = itemId,
                operationId = operationId,
                ordinal = ordinal++,
                title = source.displayName ?: "Unprocessed source",
                state = ITEM_CANCELLED,
                failureCode = FAILURE_NOT_ANALYZED,
            )
            sources += source.toJournalSource(
                itemId = itemId,
                sourcePosition = 0,
                detectedMimeType = null,
                sha256 = null,
                state = SOURCE_CANCELLED,
            )
        }
        val operation = LibraryDataOperationEntity(
            operationId = operationId,
            operationType = OPERATION_TYPE,
            phase = if (items.any { it.itemState == ITEM_REVIEW_UNRESOLVED }) {
                PHASE_REVIEW_REQUIRED
            } else {
                PHASE_PLANNED
            },
            sourceKind = journalMetadata.sourceKind,
            sourceRootUri = journalMetadata.sourceRootUri,
            sourceGrantFlags = journalMetadata.sourceGrantFlags,
            createdAtMillis = now,
            updatedAtMillis = now,
            discoveredItemCount = plan.requestedSourceCount,
            plannedDocumentCount = resolved.size,
            preparedDocumentCount = 0,
            importedDocumentCount = 0,
            skippedDuplicateCount = 0,
            failedItemCount = items.count { it.itemState.isFailureState() },
            totalSourceBytes = plan.requestedSources.map(MigrationSource::sizeBytes).sumIfKnown(),
            processedSourceBytes = 0L,
            cancelRequested = false,
            terminalErrorCode = null,
        )
        dao.insertMigrationOperation(
            operation = operation,
            items = items,
            sources = sources,
            folders = folderAssignments.folders.mapIndexed { folderOrdinal, folder ->
                LibraryPendingRestoreFolderEntity(
                    folderId = folder.folderId,
                    operationId = operationId,
                    ordinal = folderOrdinal,
                    name = folder.name,
                    normalizedName = folder.normalizedName,
                    createdAtMillis = folder.createdAtMillis,
                    modifiedAtMillis = folder.modifiedAtMillis,
                    parentFolderId = folder.parentFolderId,
                    parentScope = folder.parentScope,
                )
            },
        )
        operationId
    }

    private suspend fun runOperation(
        operationId: String,
        planById: Map<String, MigrationDocumentPlan>,
        rejectedSources: List<RejectedMigrationSource>,
        decisionOverrides: MigrationExecutionDecisions?,
        cancellationSignal: MigrationCancellationSignal,
        progressListener: MigrationBatchProgressListener,
    ): MigrationBatchReport {
        stager.cleanAbandonedWorkspaces()
        repairInterruptedItems(operationId)
        applyDecisionOverrides(operationId, decisionOverrides)
        var items = operationGate.withStableSnapshot { dao.operationItems(operationId) }
        val documentItems = items.filterNot { it.isSyntheticTerminalItem() }
        val activeCandidates = try {
            duplicateCandidates.snapshot(cancellationSignal)
        } catch (cancelled: CancellationException) {
            if (!cancellationSignal.isCancellationRequested()) throw cancelled
            requestCancellation(operationId)
            emptyList()
        }
        val operationCandidates = operationGate.withStableSnapshot {
            documentItems.filter { item ->
                item.itemState in setOf(ITEM_PREPARED, ITEM_EXACT_DUPLICATE_SKIPPED)
            }.map { it.toOperationDuplicateCandidate() }.toMutableList()
        }
        val actionable = documentItems.count { it.itemState.canStage() }
        progressListener.safeProgress(MigrationBatchProgress(0, actionable, null))
        var completed = 0
        for (item in items) {
            currentCoroutineContext().ensureActive()
            if (!item.itemState.canStage()) continue
            if (isCancellationRequested(operationId, cancellationSignal)) {
                markCancelled(operationId, item)
                break
            }
            val plan = planById[item.planId(operationId)] ?: reconstructPlan(operationId, item)
            progressListener.safeProgress(
                MigrationBatchProgress(completed, actionable, plan.id),
            )
            val stagedResult = try {
                stageOne(
                    operationId = operationId,
                    item = item,
                    plan = plan,
                    cancellationSignal = cancellationSignal,
                    progressListener = progressListener,
                    completed = completed,
                    total = actionable,
                    duplicateSnapshot = activeCandidates + operationCandidates,
                )
            } catch (cancelled: CancellationException) {
                if (!cancellationSignal.isCancellationRequested()) throw cancelled
                markCancelled(operationId, item)
                StageOneResult.CANCELLED
            }
            if (stagedResult == StageOneResult.CANCELLED) break
            faultInjector.checkpoint(
                MigrationExecutionCheckpoint.AFTER_DOCUMENT_STAGED,
                operationId,
                item.itemId,
            )
            operationGate.withStableSnapshot {
                dao.operationItem(item.itemId)
                    ?.takeIf { it.itemState in setOf(ITEM_PREPARED, ITEM_EXACT_DUPLICATE_SKIPPED) }
                    ?.let { updated -> operationCandidates += updated.toOperationDuplicateCandidate() }
            }
            completed += 1
            progressListener.safeProgress(MigrationBatchProgress(completed, actionable, null))
        }
        if (isCancellationRequested(operationId, cancellationSignal)) {
            requestCancellation(operationId)
        }
        items = operationGate.withStableSnapshot { dao.operationItems(operationId) }
        val operation = requireNotNull(operationGate.withStableSnapshot { dao.operation(operationId) })
        when {
            operation.phase == PHASE_ACTIVATED -> finalizeActivatedOperation(operationId)
            operation.cancelRequested || items.any { it.itemState == ITEM_CANCELLED } ->
                setOperationPhase(operationId, PHASE_CANCELLED, FAILURE_CANCELLED)
            items.any { it.itemState in REVIEW_STATES } ->
                setOperationPhase(operationId, PHASE_REVIEW_REQUIRED, FAILURE_REVIEW_REQUIRED)
            items.any { it.itemState.isFailureState() } ->
                setOperationPhase(operationId, PHASE_RECOVERABLE_FAILURE, FAILURE_ITEMS_FAILED)
            else -> try {
                activateIfReady(operationId, cancellationSignal)
            } catch (cancelled: CancellationException) {
                if (!cancellationSignal.isCancellationRequested()) throw cancelled
                requestCancellation(operationId)
                setOperationPhase(operationId, PHASE_CANCELLED, FAILURE_CANCELLED)
            } catch (_: Exception) {
                markPublicationFailure(operationId)
            }
        }
        val durable = completionReport(operationId)
        return legacyReport(operationId, durable, rejectedSources)
    }

    private suspend fun stageOne(
        operationId: String,
        item: LibraryDataOperationItemEntity,
        plan: MigrationDocumentPlan,
        cancellationSignal: MigrationCancellationSignal,
        progressListener: MigrationBatchProgressListener,
        completed: Int,
        total: Int,
        duplicateSnapshot: List<DuplicateCandidate>,
    ): StageOneResult {
        transitionItem(operationId, item, ITEM_STAGING, null)
        val sourceRows = operationGate.withStableSnapshot { dao.operationSources(item.itemId) }
        val validations = mutableListOf<MigrationSourceRevalidation>()
        plan.sources.forEach { source ->
            if (isCancellationRequested(operationId, cancellationSignal)) {
                markCancelled(operationId, item)
                return StageOneResult.CANCELLED
            }
            validations += sourceRevalidator.revalidate(source, cancellationSignal)
        }
        val validationFailure = validations.firstOrNull {
            it.status != MigrationSourceRevalidationStatus.UNCHANGED
        }
        if (validationFailure != null) {
            val (itemState, sourceState, failure) = when (validationFailure.status) {
                MigrationSourceRevalidationStatus.CHANGED_REQUIRES_REANALYSIS -> Triple(
                    ITEM_SOURCE_CHANGED,
                    SOURCE_CHANGED,
                    MigrationPublicationFailure.SOURCE_CHANGED,
                )
                MigrationSourceRevalidationStatus.UNAVAILABLE -> Triple(
                    ITEM_UNAVAILABLE,
                    SOURCE_UNAVAILABLE,
                    MigrationPublicationFailure.SOURCE_UNAVAILABLE,
                )
                MigrationSourceRevalidationStatus.FAILED_VALIDATION -> Triple(
                    ITEM_VALIDATION_FAILED,
                    SOURCE_VALIDATION_FAILED,
                    MigrationPublicationFailure.VALIDATION_FAILED,
                )
                MigrationSourceRevalidationStatus.UNCHANGED -> error("unreachable")
            }
            transitionItem(
                operationId,
                item,
                itemState,
                failure.name,
                sourceRows.map { row ->
                    row.copy(
                        sourceState = if (
                            row.sourceId.endsWith(validationFailure.sourceDiscoveryId)
                        ) {
                            sourceState
                        } else {
                            row.sourceState
                        },
                        failureCode = if (
                            row.sourceId.endsWith(validationFailure.sourceDiscoveryId)
                        ) {
                            failure.name
                        } else {
                            row.failureCode
                        },
                    )
                },
            )
            return StageOneResult.FINISHED
        }
        if (!hasStorageFor(plan)) {
            transitionItem(
                operationId,
                item,
                ITEM_STAGING_FAILED,
                MigrationPublicationFailure.INSUFFICIENT_STORAGE.name,
            )
            return StageOneResult.FINISHED
        }
        val context = MigrationPublicationContext(
            cancellationSignal = cancellationSignal,
            onBytesCopied = { copied, expected ->
                progressListener.safeProgress(
                    MigrationBatchProgress(
                        completedDocuments = completed,
                        totalDocuments = total,
                        currentDocumentId = plan.id,
                        currentDocumentBytesCopied = copied,
                        currentDocumentTotalBytes = expected,
                    ),
                )
            },
        )
        return when (val staging = stager.stage(plan, context)) {
            MigrationStagingResult.Cancelled -> {
                markCancelled(operationId, item)
                StageOneResult.CANCELLED
            }
            is MigrationStagingResult.Failed -> {
                transitionItem(
                    operationId,
                    item,
                    if (staging.retryable) ITEM_STAGING_FAILED else ITEM_VALIDATION_FAILED,
                    staging.reason.name,
                )
                StageOneResult.FINISHED
            }
            is MigrationStagingResult.Ready -> staging.document.use { staged ->
                if (!staged.isValidFor(plan, cancellationSignal)) {
                    transitionItem(
                        operationId,
                        item,
                        ITEM_VALIDATION_FAILED,
                        MigrationPublicationFailure.VALIDATION_FAILED.name,
                    )
                    return@use StageOneResult.FINISHED
                }
                val fingerprint = staged.fingerprintForMigration()
                val duplicate = try {
                    classifyMigrationDuplicate(
                        staged.incomingIdentityForMigration(fingerprint),
                        duplicateSnapshot,
                    )
                } catch (cancelled: CancellationException) {
                    markCancelled(operationId, item)
                    return@use StageOneResult.CANCELLED
                }
                val duplicateDecision = item.recordedDuplicateDecision(duplicate.kind)
                when (duplicate.kind) {
                    DuplicateKind.EXACT -> when (duplicateDecision) {
                        MigrationDuplicateExecutionDecision.IMPORT_ANYWAY -> preparePending(
                            operationId,
                            item,
                            plan,
                            staged,
                            fingerprint,
                            duplicate,
                            cancellationSignal,
                        )
                        MigrationDuplicateExecutionDecision.EXCLUDE -> recordDuplicateTerminal(
                            operationId,
                            item,
                            staged,
                            fingerprint,
                            duplicate,
                            ITEM_EXCLUDED,
                            DECISION_EXCLUDE_DUPLICATE,
                        )
                        MigrationDuplicateExecutionDecision.DEFER -> recordDuplicateTerminal(
                            operationId,
                            item,
                            staged,
                            fingerprint,
                            duplicate,
                            ITEM_REVIEW_UNRESOLVED,
                            DECISION_DEFER_DUPLICATE,
                        )
                        MigrationDuplicateExecutionDecision.SKIP_EXACT -> recordDuplicateTerminal(
                            operationId,
                            item,
                            staged,
                            fingerprint,
                            duplicate,
                            ITEM_EXACT_DUPLICATE_SKIPPED,
                            DECISION_SKIP_EXACT,
                        )
                    }
                    DuplicateKind.POSSIBLE -> when (duplicateDecision) {
                        MigrationDuplicateExecutionDecision.IMPORT_ANYWAY -> preparePending(
                            operationId,
                            item,
                            plan,
                            staged,
                            fingerprint,
                            duplicate,
                            cancellationSignal,
                        )
                        MigrationDuplicateExecutionDecision.EXCLUDE -> recordDuplicateTerminal(
                            operationId,
                            item,
                            staged,
                            fingerprint,
                            duplicate,
                            ITEM_EXCLUDED,
                            DECISION_EXCLUDE_DUPLICATE,
                        )
                        MigrationDuplicateExecutionDecision.DEFER,
                        MigrationDuplicateExecutionDecision.SKIP_EXACT,
                        -> recordDuplicateTerminal(
                            operationId,
                            item,
                            staged,
                            fingerprint,
                            duplicate,
                            ITEM_REVIEW_UNRESOLVED,
                            DECISION_DEFER_DUPLICATE,
                        )
                    }
                    DuplicateKind.DIFFERENT -> preparePending(
                        operationId,
                        item,
                        plan,
                        staged,
                        fingerprint,
                        duplicate,
                        cancellationSignal,
                    )
                }
                StageOneResult.FINISHED
            }
        }
    }

    private suspend fun preparePending(
        operationId: String,
        item: LibraryDataOperationItemEntity,
        plan: MigrationDocumentPlan,
        staged: StagedMigrationDocument,
        fingerprint: DocumentFingerprint,
        duplicate: DuplicateMatch,
        cancellationSignal: MigrationCancellationSignal,
    ) {
        if (!hasStorageFor(staged)) {
            transitionItem(
                operationId,
                item,
                ITEM_STAGING_FAILED,
                MigrationPublicationFailure.INSUFFICIENT_STORAGE.name,
            )
            return
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
                    MigrationCancellationInputStream(
                        FileInputStream(page.file),
                        cancellationSignal,
                    )
                },
            )
        }
        val sourceAssets = staged.originalPdf?.let { original ->
            listOf(
                LibrarySourceAssetSource(
                    role = ORIGINAL_DOCUMENT_ASSET_ROLE,
                    contentType = MigrationContentType.PDF.mimeType,
                    sourceModifiedAtMillis = original.sourceModifiedAtMillis,
                    matchesCurrentRevision = true,
                    openStream = {
                        MigrationCancellationInputStream(
                            FileInputStream(original.file),
                            cancellationSignal,
                        )
                    },
                ),
            )
        }.orEmpty()
        val prepared = when (
            val result = fileStore.prepareRevision(item.targetDocumentId, pageSources, sourceAssets)
        ) {
            is LibraryResult.Success -> result.value
            is LibraryResult.Failure -> {
                transitionItem(
                    operationId,
                    item,
                    ITEM_STAGING_FAILED,
                    result.reason.name,
                )
                return
            }
        }
        try {
            check(prepared.pages.size == staged.pages.size)
            prepared.pages.zip(staged.pages).forEach { (stored, source) ->
                check(stored.contentSha256 == source.sha256)
                check(stored.imageMetadata.sourceByteCount == source.byteCount)
            }
            if (staged.originalPdf == null) {
                check(prepared.sourceAssets.isEmpty())
            } else {
                val source = requireNotNull(staged.originalPdf)
                val stored = prepared.sourceAssets.single()
                check(stored.sha256 == source.sha256)
                check(stored.byteCount == source.byteCount)
            }
            val now = nowMillis()
            val pages = prepared.pages.map { page ->
                LibraryPageEntity(
                    pageId = page.pageId,
                    documentId = item.targetDocumentId,
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
                    documentId = item.targetDocumentId,
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
                documentId = item.targetDocumentId,
                title = normalizeLibraryTitle(item.proposedTitle).ifBlank { "Imported document" },
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
                contentByteCount = staged.pages.sumOf { it.byteCount },
                sourceModifiedAtMillis = item.sourceModifiedAtMillis,
                importedAtMillis = null,
                originalCreatedAtMillis = item.originalCreatedAtMillis,
                originalCreatedAtProvenance = item.originalCreatedAtMillis?.let {
                    LibraryFileTimeProvenance.FILE_METADATA.name
                },
                sourceModifiedAtProvenance = item.sourceModifiedAtMillis?.let {
                    LibraryFileTimeProvenance.FILE_METADATA.name
                },
                sourceProvenanceKind = if (
                    plan.grouping == MigrationDocumentGrouping.SINGLE_PDF
                ) {
                    LibrarySourceProvenanceKind.GENERIC_PDF.name
                } else {
                    LibrarySourceProvenanceKind.GENERIC_IMAGE.name
                },
                sourceProvenanceLabel = if (
                    plan.grouping == MigrationDocumentGrouping.SINGLE_PDF
                ) {
                    "PDF import"
                } else {
                    "Image import"
                },
            )
            val currentSources = operationGate.withStableSnapshot { dao.operationSources(item.itemId) }
            val verifiedSources = currentSources.mapIndexed { index, source ->
                source.copy(
                    sourceByteCount = plan.sources.getOrNull(index)?.source?.sizeBytes
                        ?: source.sourceByteCount,
                    sourceSha256 = if (staged.originalPdf != null) {
                        staged.originalPdf.sha256
                    } else {
                        staged.pages.getOrNull(index)?.sha256
                    },
                    sourceState = SOURCE_VERIFIED,
                    failureCode = null,
                )
            }
            operationGate.withMutation {
                val operation = requireNotNull(dao.operation(operationId))
                val preparedItem = item.copy(
                    itemState = ITEM_PREPARED,
                    targetRevisionId = prepared.revisionDirectory.name,
                    sourcePageCount = pages.size,
                    logicalHashVersion = fingerprint.version,
                    logicalSha256 = fingerprint.sha256,
                    duplicateKind = duplicate.kind.name,
                    duplicateDocumentId = duplicate.documentId,
                    failureCode = null,
                )
                val updatedOperation = operation.withItemTransition(
                    previousItem = item,
                    updatedItem = preparedItem,
                    previousSources = currentSources,
                    updatedSources = verifiedSources,
                    phase = PHASE_STAGING,
                    updatedAt = nowMillis(),
                )
                dao.insertPendingRestoreDocument(
                    document = pendingDocument,
                    pages = pages,
                    sourceAssets = sourceAssetEntities,
                    updatedItem = preparedItem,
                    updatedOperation = updatedOperation,
                    folderSearchPath = folderSearchPath(operationId, item.proposedFolderPath),
                )
                dao.updateOperationSources(verifiedSources)
            }
        } catch (cancelled: CancellationException) {
            fileStore.deleteDocument(item.targetDocumentId)
            throw cancelled
        } catch (_: Exception) {
            fileStore.deleteDocument(item.targetDocumentId)
            transitionItem(
                operationId,
                item,
                ITEM_PUBLICATION_FAILED,
                MigrationPublicationFailure.PUBLICATION_FAILED.name,
            )
        }
    }

    private suspend fun recordDuplicateTerminal(
        operationId: String,
        item: LibraryDataOperationItemEntity,
        staged: StagedMigrationDocument,
        fingerprint: DocumentFingerprint,
        duplicate: DuplicateMatch,
        state: String,
        decision: String,
    ) {
        val sources = operationGate.withStableSnapshot { dao.operationSources(item.itemId) }
            .mapIndexed { index, source ->
                source.copy(
                    sourceSha256 = if (staged.originalPdf != null) {
                        staged.originalPdf.sha256
                    } else {
                        staged.pages.getOrNull(index)?.sha256
                    },
                    sourceState = SOURCE_VERIFIED,
                    failureCode = null,
                )
            }
        transitionItem(
            operationId = operationId,
            item = item.copy(
                duplicateKind = duplicate.kind.name,
                duplicateDocumentId = duplicate.documentId,
                duplicateDecision = replaceDuplicateDecision(item.duplicateDecision, decision),
                sourcePageCount = staged.pages.size,
                logicalHashVersion = fingerprint.version,
                logicalSha256 = fingerprint.sha256,
            ),
            state = state,
            failureCode = null,
            sources = sources,
        )
    }

    private suspend fun activateIfReady(
        operationId: String,
        cancellationSignal: MigrationCancellationSignal,
    ) {
        operationGate.withMutation {
            val operation = requireNotNull(dao.operation(operationId))
            val items = dao.operationItems(operationId)
            if (operation.cancelRequested || cancellationSignal.isCancellationRequested()) {
                dao.requestOperationCancellation(operationId, nowMillis())
                dao.updateOperation(
                    operation.recount(items, PHASE_CANCELLED, nowMillis()).copy(
                        cancelRequested = true,
                        terminalErrorCode = FAILURE_CANCELLED,
                    ),
                )
                return@withMutation
            }
            val prepared = items.filter { it.itemState == ITEM_PREPARED }
            val exactSkipped = items.filter { it.itemState == ITEM_EXACT_DUPLICATE_SKIPPED }
            if (prepared.isEmpty()) {
                dao.discardPendingRestoreDocuments(operationId)
                dao.redactOperationSourceReferences(operationId)
                dao.updateOperation(
                    operation.recount(
                        items,
                        phase = PHASE_COMPLETED,
                        updatedAt = nowMillis(),
                    ).copy(terminalErrorCode = null),
                )
                return@withMutation
            }
            val existing = duplicateCandidates.snapshot(NeverCancelMigration)
            val preparedCandidates = prepared.map { it.toOperationDuplicateCandidate() }
            var reviewRequired = false
            prepared.forEach { item ->
                val identity = pendingIdentity(item)
                val current = classifyMigrationDuplicate(identity, existing)
                val recorded = item.recordedDuplicateDecision(current.kind)
                if (
                    current.kind == DuplicateKind.EXACT &&
                    recorded != MigrationDuplicateExecutionDecision.IMPORT_ANYWAY
                ) {
                    dao.updateOperationItem(
                        item.copy(
                            itemState = ITEM_PREPARED_REVIEW,
                            duplicateKind = current.kind.name,
                            duplicateDocumentId = current.documentId,
                            failureCode = FAILURE_DUPLICATE_CHANGED,
                        ),
                    )
                    reviewRequired = true
                } else if (
                    current.kind == DuplicateKind.POSSIBLE &&
                    recorded != MigrationDuplicateExecutionDecision.IMPORT_ANYWAY
                ) {
                    dao.updateOperationItem(
                        item.copy(
                            itemState = ITEM_PREPARED_REVIEW,
                            duplicateKind = current.kind.name,
                            duplicateDocumentId = current.documentId,
                            failureCode = FAILURE_DUPLICATE_CHANGED,
                        ),
                    )
                    reviewRequired = true
                }
            }
            exactSkipped.forEach { item ->
                val current = classifyMigrationDuplicate(
                    terminalIdentity(item, dao.operationSources(item.itemId)),
                    existing + preparedCandidates,
                )
                val stillExact = current.kind == DuplicateKind.EXACT
                if (!stillExact) {
                    dao.updateOperationItem(
                        item.copy(
                            itemState = ITEM_PLANNED,
                            failureCode = FAILURE_DUPLICATE_CHANGED,
                        ),
                    )
                    reviewRequired = true
                }
            }
            if (reviewRequired) {
                val refreshed = dao.operationItems(operationId)
                dao.updateOperation(
                    operation.recount(
                        refreshed,
                        phase = PHASE_REVIEW_REQUIRED,
                        updatedAt = nowMillis(),
                    ).copy(terminalErrorCode = FAILURE_REVIEW_REQUIRED),
                )
                return@withMutation
            }
            pruneUnusedPendingFolders(operationId, prepared)
            if (cancellationSignal.isCancellationRequested()) {
                dao.requestOperationCancellation(operationId, nowMillis())
                dao.updateOperation(
                    operation.recount(items, PHASE_CANCELLED, nowMillis()).copy(
                        cancelRequested = true,
                        terminalErrorCode = FAILURE_CANCELLED,
                    ),
                )
                return@withMutation
            }
            val activatedAt = nowMillis()
            val activated = operation.recount(
                items,
                phase = PHASE_ACTIVATED,
                updatedAt = activatedAt,
            ).copy(
                importedDocumentCount = prepared.size,
                terminalErrorCode = null,
            )
            completeAtomicActivation(
                activate = {
                    faultInjector.checkpoint(
                        MigrationExecutionCheckpoint.BEFORE_ATOMIC_ACTIVATION,
                        operationId,
                        null,
                    )
                    dao.activateMigrationOperation(
                        operationId = operationId,
                        expectedFolderCount = dao.pendingOperationFolders(operationId).size,
                        activatedOperation = activated,
                        modifiedAt = activatedAt,
                    )
                },
                isCommitted = { dao.operation(operationId)?.phase == PHASE_ACTIVATED },
            )
        }
        faultInjector.checkpoint(
            MigrationExecutionCheckpoint.AFTER_ATOMIC_ACTIVATION,
            operationId,
            null,
        )
        finalizeActivatedOperation(operationId)
    }

    private suspend fun pruneUnusedPendingFolders(
        operationId: String,
        preparedItems: List<LibraryDataOperationItemEntity>,
    ) {
        val pending = dao.pendingOperationFolders(operationId)
        if (pending.isEmpty()) return
        val byId = pending.associateBy(LibraryPendingRestoreFolderEntity::folderId)
        val required = linkedSetOf<String>()
        preparedItems.forEach { item ->
            var cursor = item.proposedFolderPath
            while (cursor != null && required.add(cursor)) {
                cursor = byId[cursor]?.parentFolderId
            }
        }
        pending.filterNot { it.folderId in required }.forEach { folder ->
            dao.deletePendingMigrationFolder(operationId, folder.folderId)
        }
    }

    private suspend fun finalizeActivatedOperation(operationId: String): Boolean =
        withContext(NonCancellable) {
            operationGate.withMutation {
                val operation = dao.operation(operationId) ?: return@withMutation false
                if (operation.phase == PHASE_COMPLETED) return@withMutation true
                if (operation.phase !in setOf(PHASE_ACTIVATED, PHASE_CLEANUP_REQUIRED)) {
                    return@withMutation false
                }
                return@withMutation try {
                    faultInjector.checkpoint(
                        MigrationExecutionCheckpoint.DURING_POST_ACTIVATION_CLEANUP,
                        operationId,
                        null,
                    )
                    dao.redactOperationSourceReferences(operationId)
                    dao.updateOperation(
                        operation.copy(
                            phase = PHASE_COMPLETED,
                            updatedAtMillis = nowMillis(),
                            terminalErrorCode = null,
                        ),
                    )
                    true
                } catch (_: Exception) {
                    runCatching {
                        dao.updateOperation(
                            operation.copy(
                                phase = PHASE_CLEANUP_REQUIRED,
                                updatedAtMillis = nowMillis(),
                                terminalErrorCode = FAILURE_CLEANUP,
                            ),
                        )
                    }
                    false
                }
            }
        }

    private suspend fun repairInterruptedItems(operationId: String) {
        operationGate.withMutation {
            val operation = dao.operation(operationId) ?: return@withMutation
            if (operation.phase == PHASE_ACTIVATED) return@withMutation
            val items = dao.operationItems(operationId)
            var changed = false
            val repaired = items.map { item ->
                if (item.itemState == ITEM_STAGING) {
                    fileStore.deleteDocument(item.targetDocumentId)
                    changed = true
                    item.copy(itemState = ITEM_PLANNED, failureCode = FAILURE_INTERRUPTED)
                } else {
                    item
                }
            }
            if (changed) {
                repaired.zip(items).forEach { (next, previous) ->
                    if (next != previous) dao.updateOperationItem(next)
                }
                dao.updateOperation(
                    operation.recount(
                        repaired,
                        phase = PHASE_STAGING,
                        updatedAt = nowMillis(),
                    ).copy(terminalErrorCode = FAILURE_INTERRUPTED),
                )
            }
        }
    }

    private suspend fun applyDecisionOverrides(
        operationId: String,
        decisions: MigrationExecutionDecisions?,
    ) {
        val discardedDocumentIds = mutableListOf<String>()
        operationGate.withMutation {
            val operation = dao.operation(operationId) ?: return@withMutation
            val current = dao.operationItems(operationId)
            val updated = current.map { item ->
                if (item.isSyntheticTerminalItem()) return@map item
                val planId = item.planId(operationId)
                val documentDecision = decisions?.documentDecisions?.get(planId)
                val duplicateDecision = decisions?.duplicateDecisions?.get(planId)
                var next = item
                if (duplicateDecision != null) {
                    next = next.copy(
                        duplicateDecision = encodeDecisions(
                            next.duplicateDecision.substringBefore('|'),
                            duplicateDecision,
                        ),
                    )
                    next = when {
                        next.itemState == ITEM_ACTIVE -> next
                        duplicateDecision == MigrationDuplicateExecutionDecision.IMPORT_ANYWAY &&
                            next.itemState == ITEM_PREPARED_REVIEW ->
                            next.copy(itemState = ITEM_PREPARED, failureCode = null)
                        duplicateDecision == MigrationDuplicateExecutionDecision.IMPORT_ANYWAY &&
                            next.itemState in setOf(
                                ITEM_REVIEW_UNRESOLVED,
                                ITEM_EXACT_DUPLICATE_SKIPPED,
                                ITEM_EXCLUDED,
                            ) -> next.copy(itemState = ITEM_PLANNED, failureCode = null)
                        duplicateDecision == MigrationDuplicateExecutionDecision.EXCLUDE ->
                            next.copy(itemState = ITEM_EXCLUDED, failureCode = null)
                        duplicateDecision == MigrationDuplicateExecutionDecision.DEFER &&
                            next.itemState in setOf(ITEM_PREPARED, ITEM_PREPARED_REVIEW) ->
                            next.copy(itemState = ITEM_PREPARED_REVIEW, failureCode = null)
                        duplicateDecision == MigrationDuplicateExecutionDecision.DEFER ->
                            next.copy(itemState = ITEM_REVIEW_UNRESOLVED, failureCode = null)
                        duplicateDecision == MigrationDuplicateExecutionDecision.SKIP_EXACT &&
                            next.duplicateKind == DuplicateKind.EXACT.name ->
                            next.copy(itemState = ITEM_EXACT_DUPLICATE_SKIPPED, failureCode = null)
                        duplicateDecision == MigrationDuplicateExecutionDecision.SKIP_EXACT ->
                            next.copy(
                                itemState = ITEM_REVIEW_UNRESOLVED,
                                failureCode = FAILURE_DUPLICATE_CHANGED,
                            )
                        else -> next
                    }
                }
                next = when (documentDecision) {
                    MigrationPlannedDocumentDecision.EXCLUDE -> if (next.itemState == ITEM_ACTIVE) {
                        next
                    } else {
                        next.copy(itemState = ITEM_EXCLUDED, failureCode = null)
                    }
                    MigrationPlannedDocumentDecision.IMPORT -> if (
                        next.itemState in setOf(ITEM_EXCLUDED, ITEM_REVIEW_UNRESOLVED) &&
                        item.duplicateDecision.substringBefore('|') in setOf(
                            DECISION_EXCLUDE_DOCUMENT,
                            DECISION_EXCLUDE_AMBIGUITY,
                            DECISION_DEFER_AMBIGUITY,
                        )
                    ) {
                        next.copy(itemState = ITEM_PLANNED, failureCode = null)
                    } else {
                        next
                    }
                    null -> next
                }
                if (next.itemState in RETRYABLE_STATES) {
                    next = next.copy(itemState = ITEM_PLANNED, failureCode = null)
                }
                if (
                    item.itemState in setOf(ITEM_PREPARED, ITEM_PREPARED_REVIEW) &&
                    next.itemState !in setOf(ITEM_PREPARED, ITEM_PREPARED_REVIEW, ITEM_ACTIVE)
                ) {
                    dao.deletePendingMigrationDocument(operationId, item.targetDocumentId)
                    discardedDocumentIds += item.targetDocumentId
                    next = next.copy(targetRevisionId = null)
                }
                next
            }
            updated.zip(current).forEach { (next, previous) ->
                if (next != previous) dao.updateOperationItem(next)
            }
            dao.updateOperation(
                operation.recount(
                    updated,
                    phase = if (updated.any { it.itemState in REVIEW_STATES }) {
                        PHASE_REVIEW_REQUIRED
                    } else {
                        PHASE_STAGING
                    },
                    updatedAt = nowMillis(),
                ).copy(
                    cancelRequested = false,
                    terminalErrorCode = null,
                ),
            )
        }
        discardedDocumentIds.forEach(fileStore::deleteDocument)
    }

    private suspend fun transitionItem(
        operationId: String,
        item: LibraryDataOperationItemEntity,
        state: String,
        failureCode: String?,
        sources: List<LibraryDataOperationSourceEntity>? = null,
    ) = operationGate.withMutation {
        val operation = requireNotNull(dao.operation(operationId))
        val updatedItem = item.copy(itemState = state, failureCode = failureCode)
        val previousSources = dao.operationSources(item.itemId)
        val updatedSources = sources ?: previousSources
        dao.updateMigrationItem(
            item = updatedItem,
            sources = updatedSources,
            operation = operation.withItemTransition(
                previousItem = item,
                updatedItem = updatedItem,
                previousSources = previousSources,
                updatedSources = updatedSources,
                phase = PHASE_STAGING,
                updatedAt = nowMillis(),
            ),
        )
    }

    private suspend fun markCancelled(
        operationId: String,
        item: LibraryDataOperationItemEntity,
    ) {
        requestCancellation(operationId)
        transitionItem(
            operationId,
            item,
            ITEM_CANCELLED,
            MigrationPublicationFailure.INTERRUPTED.name,
        )
    }

    private suspend fun requestCancellation(operationId: String) {
        operationGate.withMutation {
            dao.requestOperationCancellation(operationId, nowMillis())
        }
    }

    private suspend fun setOperationPhase(
        operationId: String,
        phase: String,
        terminalErrorCode: String?,
    ) = operationGate.withMutation {
        val operation = requireNotNull(dao.operation(operationId))
        val items = dao.operationItems(operationId)
        dao.updateOperation(
            operation.recount(items, phase, nowMillis()).copy(
                terminalErrorCode = terminalErrorCode,
            ),
        )
    }

    private suspend fun markPublicationFailure(operationId: String) {
        operationGate.withMutation {
            val operation = requireNotNull(dao.operation(operationId))
            val current = dao.operationItems(operationId)
            val updated = current.map { item ->
                if (item.itemState == ITEM_PREPARED) {
                    item.copy(failureCode = MigrationPublicationFailure.PUBLICATION_FAILED.name)
                } else {
                    item
                }
            }
            updated.zip(current).forEach { (next, previous) ->
                if (next != previous) dao.updateOperationItem(next)
            }
            dao.updateOperation(
                operation.recount(
                    updated,
                    PHASE_RECOVERABLE_FAILURE,
                    nowMillis(),
                ).copy(terminalErrorCode = MigrationPublicationFailure.PUBLICATION_FAILED.name),
            )
        }
    }

    private suspend fun isCancellationRequested(
        operationId: String,
        signal: MigrationCancellationSignal,
    ): Boolean = signal.isCancellationRequested() ||
        operationGate.withStableSnapshot { dao.operation(operationId)?.cancelRequested == true }

    private suspend fun pendingIdentity(item: LibraryDataOperationItemEntity): IncomingDocumentIdentity {
        val pages = dao.pagesAnyState(item.targetDocumentId).sortedBy(LibraryPageEntity::position)
        val sources = dao.sourceAssets(item.targetDocumentId)
        return IncomingDocumentIdentity(
            fingerprint = DocumentFingerprint(
                version = requireNotNull(item.logicalHashVersion),
                sha256 = requireNotNull(item.logicalSha256),
            ),
            sourceSha256 = sources.mapTo(linkedSetOf()) { it.sha256 },
            pageCount = pages.size,
            contentByteLength = pages.sumOf { it.sourceByteCount ?: 0L },
            orderedMimeTypes = pages.map { it.contentType },
        )
    }

    private suspend fun LibraryDataOperationItemEntity.toOperationDuplicateCandidate():
        DuplicateCandidate {
        val pages = dao.pagesAnyState(targetDocumentId).sortedBy(LibraryPageEntity::position)
        val sources = dao.operationSources(itemId)
        return DuplicateCandidate(
            documentId = targetDocumentId,
            contentHashVersion = logicalHashVersion,
            contentSha256 = logicalSha256,
            sourceSha256 = sources.mapNotNullTo(linkedSetOf()) { it.sourceSha256 },
            pageCount = sourcePageCount ?: pages.size,
            contentByteLength = pages.takeIf { it.isNotEmpty() }
                ?.sumOf { it.sourceByteCount ?: 0L }
                ?: sourceByteCount,
            orderedMimeTypes = pages.map(LibraryPageEntity::contentType),
        )
    }

    private fun terminalIdentity(
        item: LibraryDataOperationItemEntity,
        sources: List<LibraryDataOperationSourceEntity>,
    ): IncomingDocumentIdentity = IncomingDocumentIdentity(
        fingerprint = if (item.logicalHashVersion != null && item.logicalSha256 != null) {
            DocumentFingerprint(item.logicalHashVersion, item.logicalSha256)
        } else {
            null
        },
        sourceSha256 = sources.mapNotNullTo(linkedSetOf()) { it.sourceSha256 },
        pageCount = item.sourcePageCount ?: 0,
        contentByteLength = item.sourceByteCount,
        orderedMimeTypes = emptyList(),
    )

    private suspend fun reconstructPlan(
        operationId: String,
        item: LibraryDataOperationItemEntity,
    ): MigrationDocumentPlan {
        val rows = operationGate.withStableSnapshot { dao.operationSources(item.itemId) }
        val operation = requireNotNull(operationGate.withStableSnapshot { dao.operation(operationId) })
        val sources = rows.map { row ->
            val contentType = MigrationContentType.entries.singleOrNull {
                it.mimeType == row.detectedMimeType
            } ?: throw IllegalStateException("Unsupported durable migration source type")
            InspectedMigrationSource(
                source = MigrationSource(
                    id = requireNotNull(row.sourceUri),
                    displayName = row.displayName,
                    relativeFolderPath = row.relativeSourcePath
                        ?.split('/')
                        ?.filter(String::isNotBlank)
                        .orEmpty(),
                    declaredContentType = row.declaredMimeType,
                    platformAccessFlags = operation.sourceGrantFlags,
                    sizeBytes = row.sourceByteCount,
                    modifiedAtMillis = row.sourceModifiedAtMillis,
                    origin = operation.sourceKind.toSourceOrigin(),
                    discoveryIdentity = row.sourceId.substringAfterLast('|'),
                    discoveryOrder = row.sourcePosition,
                    discoveryOrderEvidence = if (
                        operation.sourceKind == MigrationBatchSourceKind.INBOUND_SHARE.name
                    ) {
                        MigrationDiscoveryOrderEvidence.SOURCE_DECLARED
                    } else {
                        MigrationDiscoveryOrderEvidence.CANONICAL_PROVIDER_INDEPENDENT
                    },
                ),
                contentType = contentType,
                pageCount = if (contentType == MigrationContentType.PDF) {
                    item.sourcePageCount
                } else {
                    1
                },
                contentSha256 = row.sourceSha256,
            )
        }
        val grouping = when {
            sources.singleOrNull()?.contentType == MigrationContentType.PDF ->
                MigrationDocumentGrouping.SINGLE_PDF
            sources.size == 1 -> MigrationDocumentGrouping.SINGLE_IMAGE
            else -> MigrationDocumentGrouping.HIGH_CONFIDENCE_IMAGE_SEQUENCE
        }
        return MigrationDocumentPlan(
            id = item.planId(operationId),
            suggestedTitle = item.proposedTitle,
            relativeFolderPath = sources.firstOrNull()?.source?.relativeFolderPath.orEmpty(),
            sources = sources,
            grouping = grouping,
        )
    }

    private suspend fun durableReport(operationId: String): MigrationDurableCompletionReport {
        val operation = requireNotNull(dao.operation(operationId))
        val items = dao.operationItems(operationId)
        val completions = buildList {
            items.forEach { item ->
                val sources = dao.operationSources(item.itemId)
                sources.forEach { source ->
                    add(
                        MigrationSourceCompletion(
                            accountingId = source.sourceId,
                            outcome = item.toTerminalOutcome(),
                            documentId = item.targetDocumentId.takeUnless {
                                item.isSyntheticTerminalItem()
                            },
                            duplicateKind = item.duplicateKind.toDuplicateKindOrNull(),
                            failureCode = item.failureCode,
                        ),
                    )
                }
            }
        }
        val active = items.filter { it.itemState == ITEM_ACTIVE }
        return MigrationDurableCompletionReport(
            operationId = operationId,
            requestedSourceCount = operation.discoveredItemCount,
            sources = completions,
            importedDocumentCount = active.size,
            importedPageCount = active.sumOf { it.sourcePageCount ?: 0 },
            reconstructedFolderCount = dao.pendingOperationFolders(operationId).size,
            preservedSourcePdfCount = active.sumOf { item ->
                dao.operationSources(item.itemId).count {
                    it.detectedMimeType == MigrationContentType.PDF.mimeType
                }
            },
            isTerminal = operation.phase in TERMINAL_PHASES,
        )
    }

    private suspend fun legacyReport(
        operationId: String,
        durable: MigrationDurableCompletionReport,
        rejectedSources: List<RejectedMigrationSource>,
    ): MigrationBatchReport = operationGate.withStableSnapshot {
        val operation = requireNotNull(dao.operation(operationId))
        val documents = dao.operationItems(operationId)
            .filterNot { it.isSyntheticTerminalItem() }
            .map { item ->
                val status = when (item.itemState) {
                    ITEM_ACTIVE -> MigrationDocumentStatus.PUBLISHED
                    ITEM_EXACT_DUPLICATE_SKIPPED -> MigrationDocumentStatus.DUPLICATE_SKIPPED
                    ITEM_EXCLUDED -> MigrationDocumentStatus.EXCLUDED
                    ITEM_REVIEW_UNRESOLVED,
                    ITEM_PREPARED_REVIEW,
                    -> MigrationDocumentStatus.REVIEW_UNRESOLVED
                    ITEM_PREPARED -> if (
                        item.failureCode == MigrationPublicationFailure.PUBLICATION_FAILED.name
                    ) {
                        MigrationDocumentStatus.FAILED
                    } else {
                        MigrationDocumentStatus.CANCELLED
                    }
                    ITEM_CANCELLED,
                    ITEM_PLANNED,
                    ITEM_STAGING,
                    -> MigrationDocumentStatus.CANCELLED
                    else -> MigrationDocumentStatus.FAILED
                }
                MigrationDocumentReport(
                    documentId = item.planId(operationId),
                    suggestedTitle = item.proposedTitle,
                    status = status,
                    failure = item.failureCode.toPublicationFailureOrNull(),
                    retryable = item.itemState in RETRYABLE_STATES ||
                        item.failureCode == MigrationPublicationFailure.PUBLICATION_FAILED.name,
                    duplicateKind = item.duplicateKind.toDuplicateKindOrNull(),
                    duplicateDocumentId = item.duplicateDocumentId,
                )
            }
        MigrationBatchReport(
            documents = documents,
            rejectedSources = rejectedSources,
            wasCancelled = operation.cancelRequested || operation.phase == PHASE_CANCELLED,
            requestedDocumentCount = operation.plannedDocumentCount,
            operationId = operationId,
            durableCompletion = durable,
        )
    }

    private suspend fun planFolders(documents: List<MigrationDocumentPlan>): BatchFolderPlan {
        val existing = readFolders().toMutableList()
        val folders = mutableListOf<org.synapseworks.pageharbor.library.LibraryFolderEntity>()
        val assignments = mutableMapOf<String, MigrationFolderPlan>()
        documents.forEach { document ->
            val planned = MigrationFolderPlanner.plan(
                relativePath = document.relativeFolderPath,
                existingFolders = existing,
                newId = idSource,
                nowMillis = nowMillis(),
            )
            assignments[document.id] = planned
            folders += planned.foldersToCreate
            existing += planned.foldersToCreate.map { folder ->
                MigrationExistingFolder(
                    folder.folderId,
                    folder.name,
                    folder.normalizedName,
                    folder.parentFolderId,
                )
            }
        }
        return BatchFolderPlan(assignments, folders.distinctBy { it.folderId })
    }

    private suspend fun readFolders(): List<MigrationExistingFolder> {
        val result = mutableListOf<MigrationExistingFolder>()
        var offset = 0
        while (true) {
            val page = dao.foldersPage(offset, DATABASE_PAGE_SIZE)
            if (page.isEmpty()) break
            result += page.map { folder ->
                MigrationExistingFolder(
                    folder.folderId,
                    folder.name,
                    folder.normalizedName,
                    folder.parentFolderId,
                )
            }
            offset += page.size
            if (page.size < DATABASE_PAGE_SIZE) break
        }
        return result
    }

    private suspend fun folderSearchPath(operationId: String, folderId: String?): String {
        if (folderId == null) return ""
        val all = readFolders().associateByTo(linkedMapOf()) { it.folderId }.toMutableMap()
        dao.pendingOperationFolders(operationId).forEach { folder ->
            all[folder.folderId] = MigrationExistingFolder(
                folder.folderId,
                folder.name,
                folder.normalizedName,
                folder.parentFolderId,
            )
        }
        val names = ArrayDeque<String>()
        val visited = hashSetOf<String>()
        var cursor: String? = folderId
        while (cursor != null && visited.add(cursor)) {
            val folder = all[cursor] ?: break
            names.addFirst(folder.name)
            cursor = folder.parentFolderId
        }
        return names.joinToString("/")
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
            workingCopyCount = 1,
            fixedOverheadBytes = PER_DOCUMENT_STORAGE_OVERHEAD_BYTES,
        )
        return StoragePreflight.evaluate(estimate, storageCapacity.availableBytes()) !is
            StoragePreflightResult.Insufficient
    }

    private data class BatchFolderPlan(
        val assignments: Map<String, MigrationFolderPlan>,
        val folders: List<org.synapseworks.pageharbor.library.LibraryFolderEntity>,
    )

    private enum class StageOneResult {
        FINISHED,
        CANCELLED,
    }

    private companion object {
        const val OPERATION_TYPE = "MIGRATION_V2"
        const val WORKSPACE_DIRECTORY = "verified-migration"
        const val ORIGINAL_DOCUMENT_ASSET_ROLE = "ORIGINAL_DOCUMENT"
        const val DATABASE_PAGE_SIZE = 256
        const val PER_DOCUMENT_STORAGE_OVERHEAD_BYTES = 512L * 1024L

        const val PHASE_PLANNED = "PLANNED"
        const val PHASE_STAGING = "STAGING"
        const val PHASE_REVIEW_REQUIRED = "REVIEW_REQUIRED"
        const val PHASE_RECOVERABLE_FAILURE = "RECOVERABLE_FAILURE"
        const val PHASE_ACTIVATED = "ACTIVATED"
        const val PHASE_COMPLETED = "COMPLETED"
        const val PHASE_CANCELLED = "CANCELLED"
        const val PHASE_FAILED = "FAILED"
        const val PHASE_CLEANUP_REQUIRED = "CLEANUP_REQUIRED"

        const val ITEM_PLANNED = "PLANNED"
        const val ITEM_STAGING = "STAGING"
        const val ITEM_PREPARED = "PREPARED"
        const val ITEM_PREPARED_REVIEW = "PREPARED_REVIEW"
        const val ITEM_ACTIVE = "ACTIVE"
        const val ITEM_EXACT_DUPLICATE_SKIPPED = "EXACT_DUPLICATE_SKIPPED"
        const val ITEM_EXCLUDED = "EXCLUDED"
        const val ITEM_REVIEW_UNRESOLVED = "REVIEW_UNRESOLVED"
        const val ITEM_UNSUPPORTED = "UNSUPPORTED"
        const val ITEM_SOURCE_CHANGED = "SOURCE_CHANGED"
        const val ITEM_UNAVAILABLE = "UNAVAILABLE"
        const val ITEM_VALIDATION_FAILED = "VALIDATION_FAILED"
        const val ITEM_STAGING_FAILED = "STAGING_FAILED"
        const val ITEM_PUBLICATION_FAILED = "PUBLICATION_FAILED"
        const val ITEM_CANCELLED = "CANCELLED"

        const val SOURCE_DISCOVERED = "DISCOVERED"
        const val SOURCE_VERIFIED = "VERIFIED"
        const val SOURCE_UNSUPPORTED = "UNSUPPORTED"
        const val SOURCE_CHANGED = "CHANGED"
        const val SOURCE_UNAVAILABLE = "UNAVAILABLE"
        const val SOURCE_VALIDATION_FAILED = "VALIDATION_FAILED"
        const val SOURCE_CANCELLED = "CANCELLED"

        const val FAILURE_NOT_ANALYZED = "NOT_ANALYZED"
        const val FAILURE_INTERRUPTED = "INTERRUPTED"
        const val FAILURE_CANCELLED = "CANCELLED"
        const val FAILURE_REVIEW_REQUIRED = "REVIEW_REQUIRED"
        const val FAILURE_ITEMS_FAILED = "ITEMS_FAILED"
        const val FAILURE_DUPLICATE_CHANGED = "DUPLICATE_CLASSIFICATION_CHANGED"
        const val FAILURE_CLEANUP = "CLEANUP_INCOMPLETE"

        const val REJECTED_ITEM_MARKER = "rejected:"
        const val UNPROCESSED_ITEM_MARKER = "unprocessed:"

        val REVIEW_STATES = setOf(ITEM_REVIEW_UNRESOLVED, ITEM_PREPARED_REVIEW)
        val RETRYABLE_STATES = setOf(
            ITEM_UNAVAILABLE,
            ITEM_STAGING_FAILED,
            ITEM_PUBLICATION_FAILED,
            ITEM_CANCELLED,
        )
        val TERMINAL_PHASES = setOf(PHASE_COMPLETED, PHASE_CANCELLED, PHASE_FAILED)
    }
}

private class MigrationCancellationInputStream(
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

private suspend fun StagedMigrationDocument.isValidFor(
    plan: MigrationDocumentPlan,
    cancellationSignal: MigrationCancellationSignal,
): Boolean {
    val expectedPageCount = plan.pages.size.takeIf { it > 0 }
        ?: plan.sources.sumOf { it.pageCount ?: if (it.contentType == MigrationContentType.PDF) 0 else 1 }
            .takeIf { it > 0 }
    if (pages.isEmpty() || expectedPageCount != null && pages.size != expectedPageCount) return false
    if ((plan.grouping == MigrationDocumentGrouping.SINGLE_PDF) != (originalPdf != null)) return false
    if (pages.any { page ->
            !page.file.isFile ||
                page.byteCount <= 0L ||
                page.file.length() != page.byteCount ||
                page.width <= 0 ||
                page.height <= 0
        }
    ) {
        return false
    }
    if (pages.any { page -> !page.file.matchesSha256(page.sha256, cancellationSignal) }) {
        return false
    }
    return originalPdf?.let { original ->
        original.file.isFile &&
            original.byteCount > 0L &&
            original.file.length() == original.byteCount &&
            original.file.matchesSha256(original.sha256, cancellationSignal)
    } ?: true
}

private suspend fun File.matchesSha256(
    expectedSha256: String,
    cancellationSignal: MigrationCancellationSignal,
): Boolean = try {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(this).use { input ->
        val buffer = ByteArray(STAGED_VALIDATION_BUFFER_SIZE)
        while (true) {
            currentCoroutineContext().ensureActive()
            if (cancellationSignal.isCancellationRequested()) throw CancellationException()
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
    }
    digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        .equals(expectedSha256, ignoreCase = true)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    false
}

private const val STAGED_VALIDATION_BUFFER_SIZE = 32 * 1024

private fun LibraryDataOperationEntity.recount(
    items: List<LibraryDataOperationItemEntity>,
    phase: String,
    updatedAt: Long,
    processedBytes: Long = processedSourceBytes,
): LibraryDataOperationEntity = copy(
    phase = phase,
    updatedAtMillis = updatedAt,
    preparedDocumentCount = items.count { it.itemState in setOf("PREPARED", "ACTIVE") },
    importedDocumentCount = items.count { it.itemState == "ACTIVE" },
    skippedDuplicateCount = items.count { it.itemState == "EXACT_DUPLICATE_SKIPPED" },
    failedItemCount = items.count { it.itemState.isFailureState() },
    processedSourceBytes = processedBytes,
)

private fun LibraryDataOperationEntity.withItemTransition(
    previousItem: LibraryDataOperationItemEntity,
    updatedItem: LibraryDataOperationItemEntity,
    previousSources: List<LibraryDataOperationSourceEntity>,
    updatedSources: List<LibraryDataOperationSourceEntity>,
    phase: String,
    updatedAt: Long,
): LibraryDataOperationEntity = copy(
    phase = phase,
    updatedAtMillis = updatedAt,
    preparedDocumentCount = preparedDocumentCount.adjustCount(
        previousItem.itemState in setOf("PREPARED", "ACTIVE"),
        updatedItem.itemState in setOf("PREPARED", "ACTIVE"),
    ),
    importedDocumentCount = importedDocumentCount.adjustCount(
        previousItem.itemState == "ACTIVE",
        updatedItem.itemState == "ACTIVE",
    ),
    skippedDuplicateCount = skippedDuplicateCount.adjustCount(
        previousItem.itemState == "EXACT_DUPLICATE_SKIPPED",
        updatedItem.itemState == "EXACT_DUPLICATE_SKIPPED",
    ),
    failedItemCount = failedItemCount.adjustCount(
        previousItem.itemState.isFailureState(),
        updatedItem.itemState.isFailureState(),
    ),
    processedSourceBytes = processedSourceBytes.adjustBytes(
        previousSources.verifiedByteCount(),
        updatedSources.verifiedByteCount(),
    ),
)

private fun Int.adjustCount(previouslyIncluded: Boolean, nowIncluded: Boolean): Int = when {
    previouslyIncluded == nowIncluded -> this
    nowIncluded -> if (this == Int.MAX_VALUE) this else this + 1
    else -> (this - 1).coerceAtLeast(0)
}

private fun Long.adjustBytes(previousBytes: Long, updatedBytes: Long): Long {
    if (this == Long.MAX_VALUE) return this
    return saturatedAdd((this - previousBytes).coerceAtLeast(0L), updatedBytes)
}

private fun List<LibraryDataOperationSourceEntity>.verifiedByteCount(): Long =
    filter { it.sourceState == "VERIFIED" }.fold(0L) { total, source ->
        saturatedAdd(total, source.sourceByteCount ?: 0L)
    }

private fun String.isFailureState(): Boolean = this in setOf(
    "SOURCE_CHANGED",
    "UNAVAILABLE",
    "VALIDATION_FAILED",
    "STAGING_FAILED",
    "PUBLICATION_FAILED",
)

private fun String.canStage(): Boolean = this == "PLANNED"

private fun InspectedMigrationSource.toJournalSource(
    itemId: String,
    sourcePosition: Int,
): LibraryDataOperationSourceEntity = source.toJournalSource(
    itemId = itemId,
    sourcePosition = sourcePosition,
    detectedMimeType = contentType.mimeType,
    sha256 = contentSha256,
    state = "DISCOVERED",
)

private fun MigrationSource.toJournalSource(
    itemId: String,
    sourcePosition: Int,
    detectedMimeType: String?,
    sha256: String?,
    state: String,
): LibraryDataOperationSourceEntity = LibraryDataOperationSourceEntity(
    sourceId = "$itemId|$discoveryIdentity",
    itemId = itemId,
    sourcePosition = sourcePosition,
    sourceUri = id,
    relativeSourcePath = relativeFolderPath.takeIf(List<String>::isNotEmpty)?.joinToString("/"),
    displayName = displayName,
    declaredMimeType = declaredContentType,
    detectedMimeType = detectedMimeType,
    sourceByteCount = sizeBytes,
    sourceModifiedAtMillis = modifiedAtMillis,
    sourceSha256 = sha256,
    sourceState = state,
    failureCode = null,
)

private fun terminalItem(
    itemId: String,
    operationId: String,
    ordinal: Int,
    title: String,
    state: String,
    failureCode: String,
) = LibraryDataOperationItemEntity(
    itemId = itemId,
    operationId = operationId,
    ordinal = ordinal,
    itemState = state,
    targetDocumentId = UUID.nameUUIDFromBytes(itemId.encodeToByteArray()).toString(),
    targetRevisionId = null,
    proposedTitle = title,
    proposedFolderPath = null,
    sourceModifiedAtMillis = null,
    sourceByteCount = null,
    sourcePageCount = null,
    logicalHashVersion = null,
    logicalSha256 = null,
    duplicateKind = DuplicateKind.DIFFERENT.name,
    duplicateDocumentId = null,
    duplicateDecision = DECISION_EXCLUDE_DOCUMENT,
    failureCode = failureCode,
)

private fun encodeDecisions(
    documentDecision: String,
    duplicateDecision: MigrationDuplicateExecutionDecision,
): String = encodeDecisions(documentDecision, duplicateDecision.name)

private fun encodeDecisions(
    documentDecision: String,
    duplicateDecision: String,
): String = "$documentDecision|$duplicateDecision"

private fun replaceDuplicateDecision(encoded: String, replacement: String): String =
    "${encoded.substringBefore('|')}|$replacement"

private fun LibraryDataOperationItemEntity.recordedDuplicateDecision(
    currentKind: DuplicateKind,
):
    MigrationDuplicateExecutionDecision {
    val value = duplicateDecision.substringAfter('|', missingDelimiterValue = duplicateDecision)
    return when (value) {
        DECISION_IMPORT_DUPLICATE,
        MigrationDuplicateExecutionDecision.IMPORT_ANYWAY.name,
        -> MigrationDuplicateExecutionDecision.IMPORT_ANYWAY
        DECISION_EXCLUDE_DUPLICATE,
        MigrationDuplicateExecutionDecision.EXCLUDE.name,
        -> MigrationDuplicateExecutionDecision.EXCLUDE
        DECISION_SKIP_EXACT,
        MigrationDuplicateExecutionDecision.SKIP_EXACT.name,
        -> MigrationDuplicateExecutionDecision.SKIP_EXACT
        DECISION_SAFE_DEFAULT -> when (currentKind) {
            DuplicateKind.EXACT -> MigrationDuplicateExecutionDecision.SKIP_EXACT
            DuplicateKind.POSSIBLE -> MigrationDuplicateExecutionDecision.DEFER
            DuplicateKind.DIFFERENT -> MigrationDuplicateExecutionDecision.IMPORT_ANYWAY
        }
        else -> MigrationDuplicateExecutionDecision.DEFER
    }
}

private fun LibraryDataOperationItemEntity.planId(operationId: String): String =
    itemId.removePrefix("$operationId|")

private fun LibraryDataOperationItemEntity.isSyntheticTerminalItem(): Boolean =
    itemId.contains("|rejected:") || itemId.contains("|unprocessed:")

private fun LibraryDataOperationItemEntity.toTerminalOutcome(): MigrationTerminalSourceOutcome =
    if (failureCode == MigrationPublicationFailure.PUBLICATION_FAILED.name) {
        MigrationTerminalSourceOutcome.PUBLICATION_FAILED
    } else when (itemState) {
        "ACTIVE" -> if (duplicateKind in setOf(DuplicateKind.EXACT.name, DuplicateKind.POSSIBLE.name)) {
            MigrationTerminalSourceOutcome.IMPORTED_DESPITE_DUPLICATE
        } else {
            MigrationTerminalSourceOutcome.IMPORTED
        }
        "EXACT_DUPLICATE_SKIPPED" -> MigrationTerminalSourceOutcome.EXACT_DUPLICATE_SKIPPED
        "EXCLUDED" -> MigrationTerminalSourceOutcome.EXCLUDED_BY_USER
        "UNSUPPORTED" -> MigrationTerminalSourceOutcome.UNSUPPORTED
        "REVIEW_UNRESOLVED",
        "PREPARED_REVIEW",
        -> MigrationTerminalSourceOutcome.AMBIGUOUS_REVIEW_UNRESOLVED
        "SOURCE_CHANGED" -> MigrationTerminalSourceOutcome.SOURCE_CHANGED
        "UNAVAILABLE" -> MigrationTerminalSourceOutcome.UNAVAILABLE
        "VALIDATION_FAILED" -> MigrationTerminalSourceOutcome.VALIDATION_FAILED
        "STAGING_FAILED" -> MigrationTerminalSourceOutcome.STAGING_FAILED
        "PUBLICATION_FAILED" -> MigrationTerminalSourceOutcome.PUBLICATION_FAILED
        else -> MigrationTerminalSourceOutcome.CANCELLED_UNPROCESSED
    }

private fun String.toDuplicateKindOrNull(): DuplicateKind? =
    DuplicateKind.entries.singleOrNull { it.name == this }

private fun String?.toPublicationFailureOrNull(): MigrationPublicationFailure? =
    this?.let { value -> MigrationPublicationFailure.entries.singleOrNull { it.name == value } }

private fun String.toSourceOrigin(): MigrationSourceOrigin = when (this) {
    MigrationBatchSourceKind.FILE_URIS.name -> MigrationSourceOrigin.FILE_SELECTION
    MigrationBatchSourceKind.DOCUMENT_TREE.name -> MigrationSourceOrigin.DOCUMENT_TREE
    MigrationBatchSourceKind.INBOUND_SHARE.name -> MigrationSourceOrigin.INBOUND_SHARE
    else -> MigrationSourceOrigin.UNKNOWN
}

private fun List<Long?>.sumIfKnown(): Long? {
    if (any { it == null }) return null
    return fold(0L) { total, value -> saturatedAdd(total, requireNotNull(value)) }
}

private fun MigrationBatchProgressListener.safeProgress(progress: MigrationBatchProgress) {
    try {
        onProgress(progress)
    } catch (_: RuntimeException) {
        // Progress is advisory and must not affect durable migration state.
    }
}

private fun saturatedAdd(left: Long, right: Long): Long =
    if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
