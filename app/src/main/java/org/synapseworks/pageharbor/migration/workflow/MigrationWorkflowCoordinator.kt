package org.synapseworks.pageharbor.migration.workflow

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import org.synapseworks.pageharbor.library.duplicate.DuplicateKind
import org.synapseworks.pageharbor.migration.MigrationBatchProgress
import org.synapseworks.pageharbor.migration.MigrationBatchProgressListener
import org.synapseworks.pageharbor.migration.MigrationBatchReport
import org.synapseworks.pageharbor.migration.MigrationCancellationSignal
import org.synapseworks.pageharbor.migration.MigrationDocumentPlan
import org.synapseworks.pageharbor.migration.MigrationDocumentReport
import org.synapseworks.pageharbor.migration.MigrationDocumentStatus
import org.synapseworks.pageharbor.migration.MigrationDuplicateDecisions
import org.synapseworks.pageharbor.migration.MigrationExecutionResult
import org.synapseworks.pageharbor.migration.MigrationPublicationFailure
import org.synapseworks.pageharbor.migration.MigrationSourceIssue
import org.synapseworks.pageharbor.migration.PreparedMigrationBatch
import org.synapseworks.pageharbor.migration.migrationBatchStorageEstimate

/**
 * Configuration-safe, UI-neutral owner for bulk migration. Provider references and the prepared
 * batch never enter [state]; they remain private to this coordinator for retry and resume.
 */
internal class MigrationWorkflowCoordinator(
    private val scope: CoroutineScope,
    private val service: MigrationWorkflowService,
    private val operationContext: CoroutineContext = Dispatchers.IO,
) : AutoCloseable {
    private val lock = Any()
    private val requestIds = AtomicLong(0L)
    private val duplicateSelectionIds = AtomicLong(0L)
    private val pickerChannel = Channel<MigrationPickerRequest>(Channel.UNLIMITED)
    private val eventChannel = Channel<MigrationWorkflowEvent>(Channel.UNLIMITED)
    private val mutableState = MutableStateFlow(MigrationWorkflowState())

    val state: StateFlow<MigrationWorkflowState> = mutableState.asStateFlow()
    val pickerRequests: Flow<MigrationPickerRequest> = pickerChannel.receiveAsFlow()
    val events: Flow<MigrationWorkflowEvent> = eventChannel.receiveAsFlow()

    private var activeRun: ActiveRun? = null
    private var activeJob: Job? = null
    private var lastPreparationInput: MigrationPreparationInput? = null
    private var preparedBatch: PreparedMigrationBatch? = null
    private var previewSummary: MigrationPreviewSummary? = null
    private var previousReport: MigrationBatchReport? = null
    private var possibleSelectionToDocumentId = emptyMap<MigrationDuplicateSelectionId, String>()
    private var approvedPossibleDocumentIds = emptySet<String>()
    private var migrationMilestoneEmitted = false
    private var closed = false

    fun selectSource(app: MigrationSourceApp): Boolean = synchronized(lock) {
        if (closed || activeJob != null) return@synchronized false
        clearRetainedOperationLocked()
        drainPickerRequestsLocked()
        mutableState.value = MigrationWorkflowState(
            MigrationOperationStatus.SourceSelected(MigrationSourceSelection(app)),
        )
        true
    }

    fun requestMultipleFiles(): Boolean = requestPicker(MigrationSourceRoute.MULTIPLE_FILES)

    fun requestDocumentTree(): Boolean = requestPicker(MigrationSourceRoute.DOCUMENT_TREE)

    fun onMultipleFilesResult(
        requestId: MigrationPickerRequestId,
        files: List<MigrationFileInput>?,
    ): Boolean {
        val source = synchronized(lock) {
            val status = mutableState.value.operation as? MigrationOperationStatus.AwaitingMultipleFiles
            status?.source?.takeIf { status.requestId == requestId && activeJob == null && !closed }
        } ?: return false
        if (files.isNullOrEmpty()) {
            publishStatus(MigrationOperationStatus.Cancelled(source))
            return true
        }
        return startPreparation(MigrationPreparationInput.Files(source, files))
    }

    fun onDocumentTreeResult(
        requestId: MigrationPickerRequestId,
        tree: MigrationSafReference?,
        grantFlags: Int,
    ): Boolean {
        val source = synchronized(lock) {
            val status = mutableState.value.operation as? MigrationOperationStatus.AwaitingDocumentTree
            status?.source?.takeIf { status.requestId == requestId && activeJob == null && !closed }
        } ?: return false
        if (tree == null) {
            publishStatus(MigrationOperationStatus.Cancelled(source))
            return true
        }
        return startPreparation(MigrationPreparationInput.Tree(source, tree, grantFlags))
    }

    fun startInboundShare(
        resources: List<MigrationShareInput>,
        sourceApp: MigrationSourceApp = MigrationSourceApp.OTHER,
    ): Boolean {
        if (resources.isEmpty()) return false
        return startPreparation(
            MigrationPreparationInput.Shares(
                selection = MigrationSourceSelection(sourceApp, MigrationSourceRoute.ANDROID_SHARE),
                resources = resources,
            ),
        )
    }

    fun retryPreparation(): Boolean {
        val input = synchronized(lock) {
            if (closed || activeJob != null) return false
            val retained = lastPreparationInput ?: return false
            clearPreparedLocked()
            retained
        }
        return startPreparation(input)
    }

    fun setImportAnyway(
        selectionId: MigrationDuplicateSelectionId,
        importAnyway: Boolean,
    ): Boolean = synchronized(lock) {
        if (closed || activeJob != null || preparedBatch == null) return@synchronized false
        val documentId = possibleSelectionToDocumentId[selectionId] ?: return@synchronized false
        approvedPossibleDocumentIds = if (importAnyway) {
            approvedPossibleDocumentIds + documentId
        } else {
            approvedPossibleDocumentIds - documentId
        }
        val updated = requireNotNull(previewSummary).withDuplicateSelection(
            selectionId,
            importAnyway,
        )
        previewSummary = updated
        mutableState.value = when (val status = mutableState.value.operation) {
            is MigrationOperationStatus.PreviewReady ->
                MigrationWorkflowState(MigrationOperationStatus.PreviewReady(updated))
            is MigrationOperationStatus.Completed -> MigrationWorkflowState(
                MigrationOperationStatus.Completed(updated, status.completion),
            )
            is MigrationOperationStatus.Failed -> MigrationWorkflowState(
                status.copy(preview = updated),
            )
            else -> return@synchronized false
        }
        true
    }

    fun importDocuments(): Boolean = startImport(MigrationImportMode.INITIAL)

    fun retryFailures(): Boolean = startImport(MigrationImportMode.RETRY_FAILURES)

    fun retryIncomplete(): Boolean {
        val failedReason = synchronized(lock) {
            (mutableState.value.operation as? MigrationOperationStatus.Failed)?.failure?.reason
        }
        return when (failedReason) {
            MigrationWorkflowFailureReason.PREVIEW_INCOMPLETE -> retryPreparation()
            MigrationWorkflowFailureReason.INSUFFICIENT_STORAGE -> {
                if (synchronized(lock) { previousReport != null }) {
                    startImport(MigrationImportMode.RETRY_INCOMPLETE)
                } else {
                    startImport(MigrationImportMode.INITIAL)
                }
            }
            else -> startImport(MigrationImportMode.RETRY_INCOMPLETE)
        }
    }

    /**
     * Requests cooperative cancellation. The coroutine is deliberately not cancelled here: the
     * migration publisher is allowed to finish or roll back its current atomic document first.
     */
    fun cancelCurrentOperation(): Boolean {
        val source: MigrationSourceSelection
        val preview: MigrationPreviewSummary?
        synchronized(lock) {
            if (closed) return false
            val run = activeRun
            if (run != null) {
                if (!run.cancellation.cancel()) return false
                source = run.source
                preview = previewSummary
                mutableState.value = MigrationWorkflowState(
                    MigrationOperationStatus.Cancelling(source, preview),
                )
                return true
            }
            source = when (val status = mutableState.value.operation) {
                is MigrationOperationStatus.AwaitingMultipleFiles -> status.source
                is MigrationOperationStatus.AwaitingDocumentTree -> status.source
                else -> return false
            }
            preview = null
            drainPickerRequestsLocked()
            mutableState.value = MigrationWorkflowState(
                MigrationOperationStatus.Cancelled(source),
            )
        }
        return true
    }

    fun dismissResult() {
        synchronized(lock) {
            if (closed || activeJob != null) return
            clearRetainedOperationLocked()
            drainPickerRequestsLocked()
            mutableState.value = MigrationWorkflowState()
        }
    }

    override fun close() {
        val job: Job?
        synchronized(lock) {
            if (closed) return
            closed = true
            activeRun?.cancellation?.cancel()
            job = activeJob
            clearRetainedOperationLocked()
            drainPickerRequestsLocked()
            pickerChannel.close()
            eventChannel.close()
        }
        job?.cancel()
    }

    private fun requestPicker(route: MigrationSourceRoute): Boolean {
        val request: MigrationPickerRequest
        val pickerSource: MigrationSourceSelection
        synchronized(lock) {
            if (closed || activeJob != null) return false
            val selected = when (val status = mutableState.value.operation) {
                is MigrationOperationStatus.SourceSelected -> status.source
                is MigrationOperationStatus.Cancelled -> status.source
                else -> return false
            }
            clearRetainedOperationLocked()
            drainPickerRequestsLocked()
            val source = selected.copy(route = route)
            pickerSource = source
            val requestId = nextRequestId()
            request = when (route) {
                MigrationSourceRoute.MULTIPLE_FILES -> {
                    mutableState.value = MigrationWorkflowState(
                        MigrationOperationStatus.AwaitingMultipleFiles(requestId, source),
                    )
                    MigrationPickerRequest.OpenMultipleDocuments(requestId)
                }
                MigrationSourceRoute.DOCUMENT_TREE -> {
                    mutableState.value = MigrationWorkflowState(
                        MigrationOperationStatus.AwaitingDocumentTree(requestId, source),
                    )
                    MigrationPickerRequest.OpenDocumentTree(requestId)
                }
                MigrationSourceRoute.ANDROID_SHARE -> return false
            }
        }
        if (pickerChannel.trySend(request).isSuccess) return true
        publishStatus(
            MigrationOperationStatus.Failed(
                source = pickerSource,
                failure = MigrationWorkflowFailure(
                    MigrationWorkflowFailureReason.PICKER_REQUEST_UNAVAILABLE,
                ),
                canRetry = false,
            ),
        )
        return false
    }

    private fun startPreparation(input: MigrationPreparationInput): Boolean {
        val initial = MigrationOperationStatus.Preparing(
            source = input.selection,
            progress = MigrationPreparationProgress(
                phase = if (input is MigrationPreparationInput.Tree) {
                    MigrationPreparationPhase.DISCOVERING_TREE
                } else {
                    MigrationPreparationPhase.INSPECTING_SOURCES
                },
                completedItems = 0,
                totalItems = when (input) {
                    is MigrationPreparationInput.Files -> input.files.size
                    is MigrationPreparationInput.Shares -> input.resources.size
                    is MigrationPreparationInput.Tree -> null
                },
            ),
        )
        return launchRun(input.selection, initial) { run ->
            synchronized(lock) {
                clearPreparedLocked()
                lastPreparationInput = input
                migrationMilestoneEmitted = false
            }
            service.recoverInterruptedOperations()
            val prepared = service.prepare(
                input = input,
                cancellationSignal = run.cancellation,
                onTreeDiscoveryProgress = { discovered, folders ->
                    publishPreparationProgress(
                        run,
                        MigrationPreparationProgress(
                            MigrationPreparationPhase.DISCOVERING_TREE,
                            completedItems = discovered.coerceAtLeast(0),
                            totalItems = null,
                            visitedFolders = folders.coerceAtLeast(0),
                        ),
                    )
                },
                onSourceInspectionProgress = { inspected, total ->
                    publishPreparationProgress(
                        run,
                        MigrationPreparationProgress(
                            MigrationPreparationPhase.INSPECTING_SOURCES,
                            completedItems = inspected.coerceAtLeast(0),
                            totalItems = total.coerceAtLeast(0),
                        ),
                    )
                },
                onDuplicateProgress = MigrationBatchProgressListener { progress ->
                    publishPreparationProgress(
                        run,
                        MigrationPreparationProgress(
                            MigrationPreparationPhase.ANALYZING_DUPLICATES,
                            completedItems = progress.completedDocuments.coerceAtLeast(0),
                            totalItems = progress.totalDocuments.coerceAtLeast(0),
                        ),
                    )
                },
            )
            acceptPrepared(run, input.selection, prepared)
        }
    }

    private fun acceptPrepared(
        run: ActiveRun,
        selection: MigrationSourceSelection,
        prepared: PreparedMigrationBatch,
    ) {
        if (run.cancellation.isCancellationRequested() || prepared.preview.wasCancelled) {
            publishIfActive(run, MigrationOperationStatus.Cancelled(selection))
            return
        }
        val pageCount = prepared.preview.pageCount
        if (!prepared.preview.duplicateAnalysisComplete || pageCount == null) {
            val affected = prepared.preview.unclassifiedDocumentIds.size
            publishIfActive(
                run,
                MigrationOperationStatus.Failed(
                    source = selection,
                    failure = MigrationWorkflowFailure(
                        MigrationWorkflowFailureReason.PREVIEW_INCOMPLETE,
                        affectedDocumentCount = affected,
                    ),
                    canRetry = true,
                ),
            )
            return
        }
        synchronized(lock) {
            if (closed || activeRun !== run) return
            preparedBatch = prepared
            previousReport = null
            approvedPossibleDocumentIds = emptySet()
            possibleSelectionToDocumentId = prepared.preview.duplicateDocuments
                .asSequence()
                .filter { duplicate -> duplicate.duplicateKind == DuplicateKind.POSSIBLE }
                .associate { duplicate -> nextDuplicateSelectionId() to duplicate.documentId }
            val summary = prepared.toSummary(
                selection = selection,
                pageCount = pageCount,
                selections = possibleSelectionToDocumentId,
                approvedIds = approvedPossibleDocumentIds,
                report = null,
            )
            previewSummary = summary
            mutableState.value = MigrationWorkflowState(
                MigrationOperationStatus.PreviewReady(summary),
            )
        }
    }

    private fun startImport(mode: MigrationImportMode): Boolean {
        val retained = synchronized(lock) {
            if (closed || activeJob != null) return false
            val prepared = preparedBatch ?: return false
            val summary = previewSummary ?: return false
            val report = previousReport
            when (mode) {
                MigrationImportMode.INITIAL -> if (report != null) return false
                MigrationImportMode.RETRY_FAILURES -> if (
                    report == null || report.retryableDocumentIds.isEmpty()
                ) return false
                MigrationImportMode.RETRY_INCOMPLETE -> if (
                    report == null || !report.hasIncompleteWork()
                ) return false
            }
            RetainedImport(prepared, summary, report, approvedPossibleDocumentIds)
        }
        val initialProgress = MigrationImportProgress(
            completedDocuments = 0,
            totalDocuments = when (mode) {
                MigrationImportMode.INITIAL -> retained.prepared.preview.documents.size
                MigrationImportMode.RETRY_FAILURES ->
                    requireNotNull(retained.previousReport).retryableDocumentIds.size
                MigrationImportMode.RETRY_INCOMPLETE ->
                    retained.prepared.preview.documents.size -
                        requireNotNull(retained.previousReport).terminalDocumentCount()
            }.coerceAtLeast(0),
            isCopyingDocument = false,
            currentDocumentBytesCopied = 0L,
            currentDocumentTotalBytes = null,
        )
        return launchRun(
            retained.summary.source,
            MigrationOperationStatus.Importing(retained.summary, initialProgress),
        ) { run ->
            val decisions = MigrationDuplicateDecisions(retained.approvedDocumentIds)
            val listener = MigrationBatchProgressListener { progress ->
                publishImportProgress(run, progress)
            }
            val result = when (mode) {
                MigrationImportMode.INITIAL -> service.execute(
                    retained.prepared,
                    decisions,
                    run.cancellation,
                    listener,
                )
                MigrationImportMode.RETRY_FAILURES -> service.retryFailures(
                    retained.prepared,
                    requireNotNull(retained.previousReport),
                    decisions,
                    run.cancellation,
                    listener,
                )
                MigrationImportMode.RETRY_INCOMPLETE -> service.retryIncomplete(
                    retained.prepared,
                    requireNotNull(retained.previousReport),
                    decisions,
                    run.cancellation,
                    listener,
                )
            }
            acceptExecution(run, retained, mode, result)
        }
    }

    private fun acceptExecution(
        run: ActiveRun,
        retained: RetainedImport,
        mode: MigrationImportMode,
        result: MigrationExecutionResult,
    ) {
        when (result) {
            is MigrationExecutionResult.Completed -> synchronized(lock) {
                if (closed || activeRun !== run) return
                val cumulative = if (retained.previousReport == null) {
                    result.report
                } else {
                    mergeReports(
                        retained.prepared,
                        retained.previousReport,
                        result.report,
                        preservePriorCancellation = mode == MigrationImportMode.RETRY_FAILURES,
                    )
                }
                previousReport = cumulative
                val updatedPreview = retained.prepared.toSummary(
                    selection = retained.summary.source,
                    pageCount = retained.prepared.preview.pageCount ?: retained.summary.pageCount,
                    selections = possibleSelectionToDocumentId,
                    approvedIds = approvedPossibleDocumentIds,
                    report = cumulative,
                )
                previewSummary = updatedPreview
                val completion = cumulative.toCompletion(retained.prepared)
                mutableState.value = MigrationWorkflowState(
                    MigrationOperationStatus.Completed(updatedPreview, completion),
                )
                if (
                    !migrationMilestoneEmitted &&
                    completion.completedWithoutIssues &&
                    completion.publishedDocumentCount > 0
                ) {
                    migrationMilestoneEmitted = true
                    eventChannel.trySend(MigrationWorkflowEvent.VerifiedMigrationCompleted)
                }
            }
            is MigrationExecutionResult.InsufficientStorage -> publishIfActive(
                run,
                MigrationOperationStatus.Failed(
                    source = retained.summary.source,
                    failure = MigrationWorkflowFailure(
                        reason = MigrationWorkflowFailureReason.INSUFFICIENT_STORAGE,
                        requiredBytesLowerBound = result.estimate.requiredBytesLowerBound,
                        availableBytes = result.availableBytes,
                    ),
                    preview = retained.summary,
                    canRetry = true,
                ),
            )
            is MigrationExecutionResult.PreviewIncomplete -> publishIfActive(
                run,
                MigrationOperationStatus.Failed(
                    source = retained.summary.source,
                    failure = MigrationWorkflowFailure(
                        reason = MigrationWorkflowFailureReason.PREVIEW_INCOMPLETE,
                        affectedDocumentCount = result.unclassifiedDocumentIds.size,
                    ),
                    preview = retained.summary,
                    canRetry = true,
                ),
            )
        }
    }

    private fun launchRun(
        source: MigrationSourceSelection,
        initialStatus: MigrationOperationStatus,
        block: suspend (ActiveRun) -> Unit,
    ): Boolean {
        val run = ActiveRun(source)
        val job: Job
        synchronized(lock) {
            if (closed || activeJob != null) return false
            drainPickerRequestsLocked()
            mutableState.value = MigrationWorkflowState(initialStatus)
            job = scope.launch(context = operationContext, start = CoroutineStart.LAZY) {
                try {
                    block(run)
                } catch (_: CancellationException) {
                    publishIfActive(
                        run,
                        MigrationOperationStatus.Cancelled(source, previewSummary),
                    )
                } catch (_: Exception) {
                    val isPreparation = preparedBatch == null
                    publishIfActive(
                        run,
                        MigrationOperationStatus.Failed(
                            source = source,
                            failure = MigrationWorkflowFailure(
                                if (isPreparation) {
                                    MigrationWorkflowFailureReason.PREVIEW_FAILED
                                } else {
                                    MigrationWorkflowFailureReason.IMPORT_FAILED
                                },
                            ),
                            preview = previewSummary,
                            canRetry = true,
                        ),
                    )
                } finally {
                    synchronized(lock) {
                        if (activeRun === run) {
                            activeRun = null
                            activeJob = null
                        }
                    }
                }
            }
            activeRun = run
            activeJob = job
        }
        job.start()
        return true
    }

    private fun publishPreparationProgress(
        run: ActiveRun,
        progress: MigrationPreparationProgress,
    ) {
        if (run.cancellation.isCancellationRequested()) return
        publishIfActive(run, MigrationOperationStatus.Preparing(run.source, progress))
    }

    private fun publishImportProgress(run: ActiveRun, progress: MigrationBatchProgress) {
        if (run.cancellation.isCancellationRequested()) return
        val preview = synchronized(lock) { previewSummary } ?: return
        publishIfActive(
            run,
            MigrationOperationStatus.Importing(preview, progress.toWorkflowProgress()),
        )
    }

    private fun publishIfActive(run: ActiveRun, status: MigrationOperationStatus) {
        synchronized(lock) {
            if (!closed && activeRun === run) {
                mutableState.value = MigrationWorkflowState(status)
            }
        }
    }

    private fun publishStatus(status: MigrationOperationStatus) {
        synchronized(lock) {
            if (!closed) mutableState.value = MigrationWorkflowState(status)
        }
    }

    private fun clearRetainedOperationLocked() {
        lastPreparationInput = null
        clearPreparedLocked()
        migrationMilestoneEmitted = false
    }

    private fun clearPreparedLocked() {
        preparedBatch = null
        previewSummary = null
        previousReport = null
        possibleSelectionToDocumentId = emptyMap()
        approvedPossibleDocumentIds = emptySet()
    }

    private fun drainPickerRequestsLocked() {
        while (pickerChannel.tryReceive().isSuccess) {
            // Request IDs also reject late results already launched by an old UI owner.
        }
    }

    private fun nextRequestId(): MigrationPickerRequestId {
        val next = requestIds.updateAndGet { current ->
            if (current == Long.MAX_VALUE) 1L else current + 1L
        }
        return MigrationPickerRequestId(next)
    }

    private fun nextDuplicateSelectionId(): MigrationDuplicateSelectionId {
        val next = duplicateSelectionIds.updateAndGet { current ->
            if (current == Long.MAX_VALUE) 1L else current + 1L
        }
        return MigrationDuplicateSelectionId(next)
    }

    private class CooperativeCancellation : MigrationCancellationSignal {
        private val cancelled = AtomicBoolean(false)

        fun cancel(): Boolean = cancelled.compareAndSet(false, true)

        override fun isCancellationRequested(): Boolean = cancelled.get()
    }

    private class ActiveRun(val source: MigrationSourceSelection) {
        val cancellation = CooperativeCancellation()
    }

    private data class RetainedImport(
        val prepared: PreparedMigrationBatch,
        val summary: MigrationPreviewSummary,
        val previousReport: MigrationBatchReport?,
        val approvedDocumentIds: Set<String>,
    )

    private enum class MigrationImportMode {
        INITIAL,
        RETRY_FAILURES,
        RETRY_INCOMPLETE,
    }
}

private fun PreparedMigrationBatch.toSummary(
    selection: MigrationSourceSelection,
    pageCount: Long,
    selections: Map<MigrationDuplicateSelectionId, String>,
    approvedIds: Set<String>,
    report: MigrationBatchReport?,
): MigrationPreviewSummary {
    val storageEstimate = migrationBatchStorageEstimate(preview)
    val documentById = preview.documents.associateBy(MigrationDocumentPlan::id)
    val reportById = report?.documents.orEmpty().associateBy(MigrationDocumentReport::documentId)
    val selectionByDocument = selections.entries.associate { (selectionId, documentId) ->
        documentId to selectionId
    }
    val possibleDuplicates = preview.duplicateDocuments
        .asSequence()
        .filter { duplicate -> duplicate.duplicateKind == DuplicateKind.POSSIBLE }
        .map { duplicate ->
            val document = requireNotNull(documentById[duplicate.documentId])
            val documentReport = reportById[duplicate.documentId]
            MigrationPossibleDuplicate(
                selectionId = requireNotNull(selectionByDocument[duplicate.documentId]),
                suggestedTitle = document.suggestedTitle,
                relativeFolderPath = document.relativeFolderPath,
                pageCount = requireNotNull(duplicate.analyzedPageCount),
                importAnyway = duplicate.documentId in approvedIds,
                outcome = when {
                    documentReport?.status == MigrationDocumentStatus.PUBLISHED ->
                        MigrationPossibleDuplicateOutcome.IMPORTED
                    documentReport?.status ==
                        MigrationDocumentStatus.POSSIBLE_DUPLICATE_REVIEW_REQUIRED ->
                        MigrationPossibleDuplicateOutcome.REVIEW_REQUIRED
                    else -> MigrationPossibleDuplicateOutcome.PENDING
                },
            )
        }
        .toList()
    return MigrationPreviewSummary(
        source = selection,
        documentCount = preview.documents.size,
        pageCount = pageCount,
        folderCount = folderCount(),
        exactDuplicateCount = preview.exactDuplicateCount,
        possibleDuplicateCount = preview.possibleDuplicateCount,
        differentDocumentCount = preview.differentDocumentCount,
        unsupportedSourceCount = preview.rejectedSources.count {
            it.issue == MigrationSourceIssue.UNSUPPORTED_CONTENT
        },
        unreadableSourceCount = preview.rejectedSources.count {
            it.issue == MigrationSourceIssue.UNREADABLE
        },
        requestedSourceCount = preview.requestedSourceCount,
        inspectedSourceCount = preview.inspectedSourceCount,
        skippedTreeEntryCount = discovery?.skippedEntryCount ?: 0,
        unreadableTreeDirectoryCount = discovery?.unreadableDirectoryCount ?: 0,
        treeWasTruncated = discovery?.wasTruncated == true,
        storage = MigrationStorageSummary(
            requiredBytesLowerBound = storageEstimate.requiredBytesLowerBound,
            unknownSourceCount = storageEstimate.unknownSourceCount,
            safetyMarginBytes = storageEstimate.safetyMarginBytes,
        ),
        possibleDuplicates = possibleDuplicates,
    )
}

private fun PreparedMigrationBatch.folderCount(): Int {
    discovery?.let { result -> return (result.visitedDirectoryCount - 1).coerceAtLeast(0) }
    val paths = preview.documents.asSequence().flatMap { it.relativeFolderPath.prefixes() } +
        preview.rejectedSources.asSequence().flatMap { it.source.relativeFolderPath.prefixes() }
    return paths.distinct().count()
}

private fun List<String>.prefixes(): Sequence<List<String>> = indices.asSequence().map { index ->
    take(index + 1)
}

private fun MigrationPreviewSummary.withDuplicateSelection(
    selectionId: MigrationDuplicateSelectionId,
    importAnyway: Boolean,
): MigrationPreviewSummary = copy(
    possibleDuplicates = possibleDuplicates.map { duplicate ->
        if (duplicate.selectionId == selectionId) {
            duplicate.copy(
                importAnyway = importAnyway,
                outcome = if (importAnyway) {
                    MigrationPossibleDuplicateOutcome.PENDING
                } else {
                    duplicate.outcome
                },
            )
        } else {
            duplicate
        }
    },
)

private fun MigrationBatchReport.hasIncompleteWork(): Boolean =
    retryableDocumentIds.isNotEmpty() ||
        reviewRequiredDocumentIds.isNotEmpty() ||
        wasCancelled ||
        unprocessedDocumentCount > 0 ||
        documents.any { it.status == MigrationDocumentStatus.CANCELLED }

private fun MigrationBatchReport.terminalDocumentCount(): Int = documents.count { document ->
    document.status == MigrationDocumentStatus.PUBLISHED ||
        document.status == MigrationDocumentStatus.DUPLICATE_SKIPPED ||
        (document.status == MigrationDocumentStatus.FAILED && !document.retryable)
}

private fun mergeReports(
    prepared: PreparedMigrationBatch,
    previous: MigrationBatchReport,
    retry: MigrationBatchReport,
    preservePriorCancellation: Boolean,
): MigrationBatchReport {
    val byDocumentId = previous.documents.associateByTo(linkedMapOf()) { it.documentId }
    retry.documents.forEach { report -> byDocumentId[report.documentId] = report }
    val ordered = prepared.preview.documents.mapNotNull { plan -> byDocumentId[plan.id] }
    val stillIncomplete = ordered.size < prepared.preview.documents.size || ordered.any {
        it.status == MigrationDocumentStatus.CANCELLED
    }
    return MigrationBatchReport(
        documents = ordered,
        rejectedSources = prepared.preview.rejectedSources,
        wasCancelled = retry.wasCancelled ||
            (preservePriorCancellation && previous.wasCancelled && stillIncomplete),
        requestedDocumentCount = prepared.preview.documents.size,
    )
}

private fun MigrationBatchReport.toCompletion(
    prepared: PreparedMigrationBatch,
): MigrationCompletionSummary {
    val plans = prepared.preview.documents.associateBy(MigrationDocumentPlan::id)
    val reportedIds = documents.mapTo(linkedSetOf(), MigrationDocumentReport::documentId)
    val unsupported = rejectedSources.count { it.issue == MigrationSourceIssue.UNSUPPORTED_CONTENT }
    val unreadable = rejectedSources.count { it.issue == MigrationSourceIssue.UNREADABLE }
    val issues = buildList {
        rejectedSources.forEach { rejected ->
            add(
                MigrationCompletionIssue(
                    itemName = rejected.source.displayName,
                    relativeFolderPath = rejected.source.relativeFolderPath,
                    reason = if (rejected.issue == MigrationSourceIssue.UNSUPPORTED_CONTENT) {
                        MigrationIssueReason.UNSUPPORTED_SOURCE
                    } else {
                        MigrationIssueReason.UNREADABLE_SOURCE
                    },
                    retryable = rejected.issue == MigrationSourceIssue.UNREADABLE,
                ),
            )
        }
        documents.forEach { document ->
            val plan = plans[document.documentId]
            val reason = when (document.status) {
                MigrationDocumentStatus.POSSIBLE_DUPLICATE_REVIEW_REQUIRED ->
                    MigrationIssueReason.POSSIBLE_DUPLICATE_REVIEW_REQUIRED
                MigrationDocumentStatus.CANCELLED -> MigrationIssueReason.CANCELLED
                MigrationDocumentStatus.FAILED -> document.failure.toIssueReason()
                MigrationDocumentStatus.PUBLISHED,
                MigrationDocumentStatus.DUPLICATE_SKIPPED,
                -> null
            }
            if (reason != null) {
                add(
                    MigrationCompletionIssue(
                        itemName = document.suggestedTitle,
                        relativeFolderPath = plan?.relativeFolderPath.orEmpty(),
                        reason = reason,
                        retryable = document.retryable ||
                            reason == MigrationIssueReason.POSSIBLE_DUPLICATE_REVIEW_REQUIRED ||
                            reason == MigrationIssueReason.CANCELLED,
                    ),
                )
            }
        }
        prepared.preview.documents
            .filterNot { plan -> plan.id in reportedIds }
            .forEach { plan ->
                add(
                    MigrationCompletionIssue(
                        itemName = plan.suggestedTitle,
                        relativeFolderPath = plan.relativeFolderPath,
                        reason = MigrationIssueReason.NOT_ATTEMPTED,
                        retryable = true,
                    ),
                )
            }
    }
    val cancelledCount = documents.count { it.status == MigrationDocumentStatus.CANCELLED }
    val reviewRequiredCount = documents.count {
        it.status == MigrationDocumentStatus.POSSIBLE_DUPLICATE_REVIEW_REQUIRED
    }
    return MigrationCompletionSummary(
        requestedDocumentCount = requestedDocumentCount,
        publishedDocumentCount = publishedCount,
        exactDuplicateSkippedCount = duplicateCount,
        possibleDuplicateImportedCount = documents.count {
            it.status == MigrationDocumentStatus.PUBLISHED && it.duplicateKind == DuplicateKind.POSSIBLE
        },
        possibleDuplicateReviewRequiredCount = reviewRequiredCount,
        failedDocumentCount = failedCount,
        cancelledDocumentCount = cancelledCount,
        unprocessedDocumentCount = unprocessedDocumentCount,
        unsupportedSourceCount = unsupported,
        unreadableSourceCount = unreadable,
        wasCancelled = wasCancelled,
        canRetryFailures = retryableDocumentIds.isNotEmpty(),
        canRetryIncomplete = hasIncompleteWork(),
        issues = issues,
    )
}

private fun MigrationPublicationFailure?.toIssueReason(): MigrationIssueReason = when (this) {
    MigrationPublicationFailure.SOURCE_UNAVAILABLE -> MigrationIssueReason.SOURCE_UNAVAILABLE
    MigrationPublicationFailure.SOURCE_TOO_LARGE -> MigrationIssueReason.SOURCE_TOO_LARGE
    MigrationPublicationFailure.INSUFFICIENT_STORAGE -> MigrationIssueReason.INSUFFICIENT_STORAGE
    MigrationPublicationFailure.WRITE_FAILED -> MigrationIssueReason.WRITE_FAILED
    MigrationPublicationFailure.INVALID_DOCUMENT -> MigrationIssueReason.INVALID_DOCUMENT
    MigrationPublicationFailure.INTERRUPTED -> MigrationIssueReason.INTERRUPTED
    null -> MigrationIssueReason.WRITE_FAILED
}
