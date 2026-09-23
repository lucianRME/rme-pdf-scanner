package org.synapseworks.pageharbor.migration.workflow

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.synapseworks.pageharbor.library.duplicate.DuplicateKind
import org.synapseworks.pageharbor.migration.InspectedMigrationSource
import org.synapseworks.pageharbor.migration.MigrationBatchProgress
import org.synapseworks.pageharbor.migration.MigrationBatchProgressListener
import org.synapseworks.pageharbor.migration.MigrationBatchReport
import org.synapseworks.pageharbor.migration.MigrationBatchSourceKind
import org.synapseworks.pageharbor.migration.MigrationCancellationSignal
import org.synapseworks.pageharbor.migration.MigrationContentType
import org.synapseworks.pageharbor.migration.MigrationDocumentGrouping
import org.synapseworks.pageharbor.migration.MigrationDocumentPlan
import org.synapseworks.pageharbor.migration.MigrationDocumentReport
import org.synapseworks.pageharbor.migration.MigrationDocumentStatus
import org.synapseworks.pageharbor.migration.MigrationDuplicateDecisions
import org.synapseworks.pageharbor.migration.MigrationDuplicatePreview
import org.synapseworks.pageharbor.migration.MigrationExecutionResult
import org.synapseworks.pageharbor.migration.MigrationPublicationFailure
import org.synapseworks.pageharbor.migration.MigrationSource
import org.synapseworks.pageharbor.migration.MigrationSourceIssue
import org.synapseworks.pageharbor.migration.MigrationStorageEstimate
import org.synapseworks.pageharbor.migration.MigrationTreeDiscoveryResult
import org.synapseworks.pageharbor.migration.PreparedMigrationBatch
import org.synapseworks.pageharbor.migration.RejectedMigrationSource
import org.synapseworks.pageharbor.portability.StorageEstimate

class MigrationWorkflowCoordinatorTest {
    @Test
    fun `tree picker produces an accurate sanitized preview without retaining URI in state`() =
        withFixture { fixture ->
            fixture.service.prepared = preparedBatch(includeRejectedSources = true)

            assertTrue(fixture.coordinator.selectSource(MigrationSourceApp.CAMSCANNER))
            assertTrue(fixture.coordinator.requestDocumentTree())
            val request = fixture.pickerRequests.single() as MigrationPickerRequest.OpenDocumentTree
            val secretUri = "content://scanner/private/customer-folder"

            assertTrue(
                fixture.coordinator.onDocumentTreeResult(
                    request.requestId,
                    MigrationSafReference.from(secretUri),
                    grantFlags = 5,
                ),
            )

            val ready = fixture.coordinator.state.value.operation as
                MigrationOperationStatus.PreviewReady
            assertEquals(MigrationSourceApp.CAMSCANNER, ready.preview.source.app)
            assertEquals(MigrationSourceRoute.DOCUMENT_TREE, ready.preview.source.route)
            assertEquals(3, ready.preview.documentCount)
            assertEquals(6L, ready.preview.pageCount)
            assertEquals(2, ready.preview.folderCount)
            assertEquals(1, ready.preview.exactDuplicateCount)
            assertEquals(1, ready.preview.possibleDuplicateCount)
            assertEquals(1, ready.preview.differentDocumentCount)
            assertEquals(1, ready.preview.unsupportedSourceCount)
            assertEquals(1, ready.preview.unreadableSourceCount)
            assertEquals(2, ready.preview.skippedTreeEntryCount)
            assertEquals(1, ready.preview.unreadableTreeDirectoryCount)
            assertTrue(ready.preview.treeWasTruncated)
            assertEquals(1, ready.preview.possibleDuplicates.size)
            assertFalse(fixture.coordinator.state.value.toString().contains(secretUri))
            assertFalse(requireNotNull(fixture.service.capturedInput).toString().contains(secretUri))
        }

    @Test
    fun `possible duplicate requires an explicit selection and verified completion emits event`() =
        withFixture { fixture ->
            val prepared = preparedBatch()
            fixture.service.prepared = prepared
            fixture.service.executeHandler = { _, decisions, _, listener ->
                fixture.service.capturedDecisions = decisions
                listener.onProgress(MigrationBatchProgress(3, 3, null))
                MigrationExecutionResult.Completed(
                    report(
                        documentReports = listOf(
                            documentReport(0, MigrationDocumentStatus.DUPLICATE_SKIPPED, DuplicateKind.EXACT),
                            documentReport(1, MigrationDocumentStatus.PUBLISHED, DuplicateKind.POSSIBLE),
                            documentReport(2, MigrationDocumentStatus.PUBLISHED, DuplicateKind.DIFFERENT),
                        ),
                    ),
                )
            }
            prepareFiles(fixture)
            val preview = (fixture.coordinator.state.value.operation as
                MigrationOperationStatus.PreviewReady).preview
            val possible = preview.possibleDuplicates.single()

            assertTrue(fixture.coordinator.setImportAnyway(possible.selectionId, true))
            assertTrue(fixture.coordinator.importDocuments())

            assertEquals(
                setOf(prepared.preview.documents[1].id),
                fixture.service.capturedDecisions?.importDespiteDuplicateDocumentIds,
            )
            val completed = fixture.coordinator.state.value.operation as
                MigrationOperationStatus.Completed
            assertEquals(2, completed.completion.publishedDocumentCount)
            assertEquals(1, completed.completion.exactDuplicateSkippedCount)
            assertEquals(1, completed.completion.possibleDuplicateImportedCount)
            assertTrue(completed.completion.completedWithoutIssues)
            assertEquals(
                MigrationPossibleDuplicateOutcome.IMPORTED,
                completed.preview.possibleDuplicates.single().outcome,
            )
            assertEquals(
                listOf(MigrationWorkflowEvent.VerifiedMigrationCompleted),
                fixture.events,
            )
        }

    @Test
    fun `cooperative cancel lets core return a detailed partial report without cancelling job`() =
        withFixture { fixture ->
            fixture.service.prepared = preparedBatch()
            val executionGate = CompletableDeferred<Unit>()
            fixture.service.executeHandler = { prepared, _, cancellation, _ ->
                fixture.service.executionStarted.complete(Unit)
                executionGate.await()
                assertTrue(cancellation.isCancellationRequested())
                MigrationExecutionResult.Completed(
                    report(
                        documentReports = listOf(
                            documentReport(0, MigrationDocumentStatus.CANCELLED),
                        ),
                        requestedCount = prepared.preview.documents.size,
                        wasCancelled = true,
                    ),
                )
            }
            prepareFiles(fixture)

            assertTrue(fixture.coordinator.importDocuments())
            assertTrue(fixture.service.executionStarted.isCompleted)
            assertTrue(fixture.coordinator.cancelCurrentOperation())
            assertTrue(
                fixture.coordinator.state.value.operation is MigrationOperationStatus.Cancelling,
            )

            executionGate.complete(Unit)

            val completed = fixture.coordinator.state.value.operation as
                MigrationOperationStatus.Completed
            assertTrue(completed.completion.wasCancelled)
            assertEquals(1, completed.completion.cancelledDocumentCount)
            assertEquals(2, completed.completion.unprocessedDocumentCount)
            assertTrue(completed.completion.canRetryIncomplete)
            assertTrue(fixture.events.isEmpty())
        }

    @Test
    fun `retry incomplete imports approved possible duplicate and merges cumulative report`() =
        withFixture { fixture ->
            val prepared = preparedBatch()
            fixture.service.prepared = prepared
            fixture.service.executeHandler = { _, _, _, _ ->
                MigrationExecutionResult.Completed(
                    report(
                        documentReports = listOf(
                            documentReport(0, MigrationDocumentStatus.PUBLISHED, DuplicateKind.DIFFERENT),
                            documentReport(
                                1,
                                MigrationDocumentStatus.POSSIBLE_DUPLICATE_REVIEW_REQUIRED,
                                DuplicateKind.POSSIBLE,
                            ),
                        ),
                        requestedCount = 3,
                    ),
                )
            }
            fixture.service.retryIncompleteHandler = { _, _, decisions, _, _ ->
                assertEquals(
                    setOf(prepared.preview.documents[1].id),
                    decisions.importDespiteDuplicateDocumentIds,
                )
                MigrationExecutionResult.Completed(
                    report(
                        documentReports = listOf(
                            documentReport(1, MigrationDocumentStatus.PUBLISHED, DuplicateKind.POSSIBLE),
                            documentReport(2, MigrationDocumentStatus.PUBLISHED, DuplicateKind.DIFFERENT),
                        ),
                        requestedCount = 2,
                    ),
                )
            }
            prepareFiles(fixture)
            fixture.coordinator.importDocuments()
            val first = fixture.coordinator.state.value.operation as MigrationOperationStatus.Completed
            assertEquals(1, first.completion.possibleDuplicateReviewRequiredCount)
            assertEquals(1, first.completion.unprocessedDocumentCount)

            val possible = first.preview.possibleDuplicates.single()
            assertTrue(fixture.coordinator.setImportAnyway(possible.selectionId, true))
            assertTrue(fixture.coordinator.retryIncomplete())

            val completed = fixture.coordinator.state.value.operation as
                MigrationOperationStatus.Completed
            assertEquals(3, completed.completion.publishedDocumentCount)
            assertEquals(0, completed.completion.unprocessedDocumentCount)
            assertEquals(0, completed.completion.possibleDuplicateReviewRequiredCount)
            assertFalse(completed.completion.canRetryIncomplete)
            assertTrue(completed.completion.completedWithoutIssues)
            assertEquals(1, fixture.service.retryIncompleteCalls)
            assertEquals(
                listOf(MigrationWorkflowEvent.VerifiedMigrationCompleted),
                fixture.events,
            )
        }

    @Test
    fun `interrupted migration recovery runs before every preview preparation`() =
        withFixture { fixture ->
            fixture.service.prepared = preparedBatch()

            prepareFiles(fixture)

            assertEquals(listOf("recover", "prepare"), fixture.service.callOrder.take(2))
            assertEquals(1, fixture.service.recoveryCalls)
            assertEquals(1, fixture.service.prepareCalls)
        }

    @Test
    fun `insufficient storage retry reattempts initial import without requiring a report`() =
        withFixture { fixture ->
            fixture.service.prepared = preparedBatch()
            var firstAttempt = true
            fixture.service.executeHandler = { prepared, _, _, _ ->
                if (firstAttempt) {
                    firstAttempt = false
                    MigrationExecutionResult.InsufficientStorage(
                        estimate = StorageEstimate(
                            requiredBytesLowerBound = 100_000L,
                            unknownSourceCount = 0,
                            safetyMarginBytes = 10_000L,
                        ),
                        availableBytes = 50_000L,
                    )
                } else {
                    MigrationExecutionResult.Completed(
                        report(
                            prepared.preview.documents.mapIndexed { index, _ ->
                                documentReport(index, MigrationDocumentStatus.PUBLISHED)
                            },
                        ),
                    )
                }
            }
            prepareFiles(fixture)

            assertTrue(fixture.coordinator.importDocuments())
            val failed = fixture.coordinator.state.value.operation as MigrationOperationStatus.Failed
            assertEquals(MigrationWorkflowFailureReason.INSUFFICIENT_STORAGE, failed.failure.reason)

            assertTrue(fixture.coordinator.retryIncomplete())

            assertTrue(
                fixture.coordinator.state.value.operation is MigrationOperationStatus.Completed,
            )
            assertEquals(2, fixture.service.executeCalls)
            assertEquals(1, fixture.service.prepareCalls)
        }

    @Test
    fun `incomplete execution preview retry reruns discovery and duplicate analysis`() =
        withFixture { fixture ->
            fixture.service.prepared = preparedBatch()
            fixture.service.executeHandler = { prepared, _, _, _ ->
                MigrationExecutionResult.PreviewIncomplete(
                    setOf(prepared.preview.documents.first().id),
                )
            }
            prepareFiles(fixture)

            assertTrue(fixture.coordinator.importDocuments())
            val failed = fixture.coordinator.state.value.operation as MigrationOperationStatus.Failed
            assertEquals(MigrationWorkflowFailureReason.PREVIEW_INCOMPLETE, failed.failure.reason)

            assertTrue(fixture.coordinator.retryIncomplete())

            assertTrue(
                fixture.coordinator.state.value.operation is MigrationOperationStatus.PreviewReady,
            )
            assertEquals(2, fixture.service.prepareCalls)
            assertEquals(2, fixture.service.recoveryCalls)
            assertEquals(1, fixture.service.executeCalls)
        }

    @Test
    fun `retry failures replaces retryable outcome while preserving other results`() =
        withFixture { fixture ->
            fixture.service.prepared = preparedBatch()
            fixture.service.executeHandler = { _, _, _, _ ->
                MigrationExecutionResult.Completed(
                    report(
                        documentReports = listOf(
                            documentReport(0, MigrationDocumentStatus.PUBLISHED),
                            documentReport(
                                1,
                                MigrationDocumentStatus.FAILED,
                                failure = MigrationPublicationFailure.SOURCE_UNAVAILABLE,
                                retryable = true,
                            ),
                            documentReport(2, MigrationDocumentStatus.DUPLICATE_SKIPPED),
                        ),
                    ),
                )
            }
            fixture.service.retryFailuresHandler = { _, _, _, _, _ ->
                MigrationExecutionResult.Completed(
                    report(
                        documentReports = listOf(
                            documentReport(1, MigrationDocumentStatus.PUBLISHED),
                        ),
                        requestedCount = 1,
                    ),
                )
            }
            prepareFiles(fixture)
            fixture.coordinator.importDocuments()
            assertTrue(fixture.coordinator.retryFailures())

            val completed = fixture.coordinator.state.value.operation as
                MigrationOperationStatus.Completed
            assertEquals(2, completed.completion.publishedDocumentCount)
            assertEquals(1, completed.completion.exactDuplicateSkippedCount)
            assertEquals(0, completed.completion.failedDocumentCount)
            assertFalse(completed.completion.canRetryFailures)
            assertEquals(1, fixture.service.retryFailureCalls)
        }

    @Test
    fun `inbound share enters preparation directly and never exposes provider reference`() =
        withFixture { fixture ->
            fixture.service.prepared = preparedBatch()
            val secretUri = "content://sender/private/shared-document"

            assertTrue(
                fixture.coordinator.startInboundShare(
                    resources = listOf(
                        MigrationShareInput(
                            MigrationSafReference.from(secretUri),
                            "application/pdf",
                            1,
                        ),
                    ),
                    sourceApp = MigrationSourceApp.ADOBE_SCAN,
                ),
            )

            val ready = fixture.coordinator.state.value.operation as
                MigrationOperationStatus.PreviewReady
            assertEquals(MigrationSourceRoute.ANDROID_SHARE, ready.preview.source.route)
            assertFalse(fixture.coordinator.state.value.toString().contains(secretUri))
            assertFalse(requireNotNull(fixture.service.capturedInput).toString().contains(secretUri))
        }

    private fun prepareFiles(fixture: Fixture) {
        assertTrue(fixture.coordinator.selectSource(MigrationSourceApp.OTHER))
        assertTrue(fixture.coordinator.requestMultipleFiles())
        val request = fixture.pickerRequests.single() as
            MigrationPickerRequest.OpenMultipleDocuments
        assertTrue(
            fixture.coordinator.onMultipleFilesResult(
                request.requestId,
                listOf(
                    MigrationFileInput(
                        MigrationSafReference.from("content://provider/private/input"),
                    ),
                ),
            ),
        )
    }

    private fun withFixture(block: (Fixture) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val service = FakeMigrationWorkflowService()
        val coordinator = MigrationWorkflowCoordinator(
            scope = scope,
            service = service,
            operationContext = Dispatchers.Unconfined,
        )
        val pickerRequests = mutableListOf<MigrationPickerRequest>()
        val events = mutableListOf<MigrationWorkflowEvent>()
        scope.launch { coordinator.pickerRequests.collect(pickerRequests::add) }
        scope.launch { coordinator.events.collect(events::add) }
        val fixture = Fixture(coordinator, service, pickerRequests, events)
        try {
            block(fixture)
        } finally {
            coordinator.close()
            scope.cancel()
        }
    }
}

private data class Fixture(
    val coordinator: MigrationWorkflowCoordinator,
    val service: FakeMigrationWorkflowService,
    val pickerRequests: MutableList<MigrationPickerRequest>,
    val events: MutableList<MigrationWorkflowEvent>,
)

private class FakeMigrationWorkflowService : MigrationWorkflowService {
    lateinit var prepared: PreparedMigrationBatch
    var capturedInput: MigrationPreparationInput? = null
    var capturedDecisions: MigrationDuplicateDecisions? = null
    var retryFailureCalls = 0
    var retryIncompleteCalls = 0
    var recoveryCalls = 0
    var prepareCalls = 0
    var executeCalls = 0
    val callOrder = mutableListOf<String>()
    val executionStarted = CompletableDeferred<Unit>()

    var executeHandler: suspend (
        PreparedMigrationBatch,
        MigrationDuplicateDecisions,
        MigrationCancellationSignal,
        MigrationBatchProgressListener,
    ) -> MigrationExecutionResult = { prepared, _, _, _ ->
        MigrationExecutionResult.Completed(
            report(
                prepared.preview.documents.mapIndexed { index, _ ->
                    documentReport(index, MigrationDocumentStatus.PUBLISHED)
                },
            ),
        )
    }
    var retryFailuresHandler: suspend (
        PreparedMigrationBatch,
        MigrationBatchReport,
        MigrationDuplicateDecisions,
        MigrationCancellationSignal,
        MigrationBatchProgressListener,
    ) -> MigrationExecutionResult = { _, _, _, _, _ -> error("Unexpected retry failures") }
    var retryIncompleteHandler: suspend (
        PreparedMigrationBatch,
        MigrationBatchReport,
        MigrationDuplicateDecisions,
        MigrationCancellationSignal,
        MigrationBatchProgressListener,
    ) -> MigrationExecutionResult = { _, _, _, _, _ -> error("Unexpected retry incomplete") }

    override suspend fun recoverInterruptedOperations(): Int {
        recoveryCalls += 1
        callOrder += "recover"
        return recoveryCalls
    }

    override suspend fun prepare(
        input: MigrationPreparationInput,
        cancellationSignal: MigrationCancellationSignal,
        onTreeDiscoveryProgress: (discoveredFiles: Int, visitedFolders: Int) -> Unit,
        onSourceInspectionProgress: (inspectedSources: Int, totalSources: Int) -> Unit,
        onDuplicateProgress: MigrationBatchProgressListener,
    ): PreparedMigrationBatch {
        prepareCalls += 1
        callOrder += "prepare"
        capturedInput = input
        if (input is MigrationPreparationInput.Tree) onTreeDiscoveryProgress(5, 3)
        onSourceInspectionProgress(5, 5)
        onDuplicateProgress.onProgress(MigrationBatchProgress(3, 3, null))
        return prepared
    }

    override suspend fun execute(
        prepared: PreparedMigrationBatch,
        decisions: MigrationDuplicateDecisions,
        cancellationSignal: MigrationCancellationSignal,
        progressListener: MigrationBatchProgressListener,
    ): MigrationExecutionResult {
        executeCalls += 1
        return executeHandler(
            prepared,
            decisions,
            cancellationSignal,
            progressListener,
        )
    }

    override suspend fun retryFailures(
        prepared: PreparedMigrationBatch,
        previousReport: MigrationBatchReport,
        decisions: MigrationDuplicateDecisions,
        cancellationSignal: MigrationCancellationSignal,
        progressListener: MigrationBatchProgressListener,
    ): MigrationExecutionResult {
        retryFailureCalls += 1
        return retryFailuresHandler(
            prepared,
            previousReport,
            decisions,
            cancellationSignal,
            progressListener,
        )
    }

    override suspend fun retryIncomplete(
        prepared: PreparedMigrationBatch,
        previousReport: MigrationBatchReport,
        decisions: MigrationDuplicateDecisions,
        cancellationSignal: MigrationCancellationSignal,
        progressListener: MigrationBatchProgressListener,
    ): MigrationExecutionResult {
        retryIncompleteCalls += 1
        return retryIncompleteHandler(
            prepared,
            previousReport,
            decisions,
            cancellationSignal,
            progressListener,
        )
    }
}

private fun preparedBatch(includeRejectedSources: Boolean = false): PreparedMigrationBatch {
    val documents = listOf(
        documentPlan(0, listOf("Clients")),
        documentPlan(1, listOf("Clients", "2026")),
        documentPlan(2, emptyList()),
    )
    val rejected = if (includeRejectedSources) {
        listOf(
            RejectedMigrationSource(
                MigrationSource(
                    id = "content://private/unsupported",
                    displayName = "unsupported.txt",
                    relativeFolderPath = listOf("Clients"),
                ),
                MigrationSourceIssue.UNSUPPORTED_CONTENT,
            ),
            RejectedMigrationSource(
                MigrationSource(
                    id = "content://private/unreadable",
                    displayName = "unreadable.pdf",
                    relativeFolderPath = listOf("Clients", "2026"),
                ),
                MigrationSourceIssue.UNREADABLE,
            ),
        )
    } else {
        emptyList()
    }
    val preview = org.synapseworks.pageharbor.migration.MigrationPreview(
        documents = documents,
        rejectedSources = rejected,
        storageEstimate = MigrationStorageEstimate(
            knownSourceBytes = 6_000L,
            unknownSizeSourceCount = 0,
            estimatedRequiredBytes = 18_000L,
            estimateIsIncomplete = false,
        ),
        inspectedSourceCount = documents.size + rejected.size,
        requestedSourceCount = documents.size + rejected.size,
        wasCancelled = false,
        duplicateDocuments = listOf(
            MigrationDuplicatePreview(
                documentId = documents[0].id,
                duplicateKind = DuplicateKind.EXACT,
                analyzedPageCount = 1,
                existingDocumentId = "existing-exact",
            ),
            MigrationDuplicatePreview(
                documentId = documents[1].id,
                duplicateKind = DuplicateKind.POSSIBLE,
                analyzedPageCount = 2,
                existingDocumentId = "existing-possible",
            ),
            MigrationDuplicatePreview(
                documentId = documents[2].id,
                duplicateKind = DuplicateKind.DIFFERENT,
                analyzedPageCount = 3,
            ),
        ),
        duplicateAnalysisComplete = true,
    )
    return PreparedMigrationBatch(
        preview = preview,
        sourceKind = MigrationBatchSourceKind.DOCUMENT_TREE,
        discovery = MigrationTreeDiscoveryResult(
            sources = documents.flatMap { plan -> plan.sources.map { it.source } } +
                rejected.map { it.source },
            visitedDirectoryCount = 3,
            unreadableDirectoryCount = if (includeRejectedSources) 1 else 0,
            skippedEntryCount = if (includeRejectedSources) 2 else 0,
            wasCancelled = false,
            wasTruncated = includeRejectedSources,
        ),
        sourceRootUri = "content://private/source-root",
        sourceGrantFlags = 5,
    )
}

private fun documentPlan(index: Int, path: List<String>): MigrationDocumentPlan {
    val source = MigrationSource(
        id = "content://private/document-$index",
        displayName = "Document $index.pdf",
        relativeFolderPath = path,
        declaredContentType = "application/pdf",
        sizeBytes = (index + 1) * 1_000L,
    )
    return MigrationDocumentPlan(
        id = "document-$index",
        suggestedTitle = "Document $index",
        relativeFolderPath = path,
        sources = listOf(InspectedMigrationSource(source, MigrationContentType.PDF)),
        grouping = MigrationDocumentGrouping.SINGLE_PDF,
    )
}

private fun documentReport(
    index: Int,
    status: MigrationDocumentStatus,
    duplicateKind: DuplicateKind? = null,
    failure: MigrationPublicationFailure? = null,
    retryable: Boolean = false,
): MigrationDocumentReport = MigrationDocumentReport(
    documentId = "document-$index",
    suggestedTitle = "Document $index",
    status = status,
    failure = failure,
    retryable = retryable,
    duplicateKind = duplicateKind,
    duplicateDocumentId = duplicateKind?.let { "existing-$index" },
)

private fun report(
    documentReports: List<MigrationDocumentReport>,
    requestedCount: Int = 3,
    wasCancelled: Boolean = false,
): MigrationBatchReport = MigrationBatchReport(
    documents = documentReports,
    rejectedSources = emptyList(),
    wasCancelled = wasCancelled,
    requestedDocumentCount = requestedCount,
)
