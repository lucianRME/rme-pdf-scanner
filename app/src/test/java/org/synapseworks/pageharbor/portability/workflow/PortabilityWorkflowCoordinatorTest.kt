package org.synapseworks.pageharbor.portability.workflow

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.synapseworks.pageharbor.backup.restore.RestoreMergePolicy
import org.synapseworks.pageharbor.backup.restore.RestorePreview
import org.synapseworks.pageharbor.backup.restore.RestorePreviewDocument
import org.synapseworks.pageharbor.backup.restore.RestoreProgress
import org.synapseworks.pageharbor.library.duplicate.DuplicateKind
import org.synapseworks.pageharbor.review.ReviewMilestone

class PortabilityWorkflowCoordinatorTest {
    @Test
    fun `authoritative active-library source drives preview signal and cleans up on read failure`() {
        val populatedPrepared = FakePreparedRestore(PREVIEW)
        val populated = runBlocking {
            attachAuthoritativeLibraryContent(
                prepared = populatedPrepared,
                source = ActiveLibraryContentSource { true },
            )
        } as PortabilityRestorePreparationResult.Ready
        assertTrue(populated.existingLibraryHasContent)
        assertFalse(populatedPrepared.closed)

        val emptyPrepared = FakePreparedRestore(PREVIEW)
        val empty = runBlocking {
            attachAuthoritativeLibraryContent(
                prepared = emptyPrepared,
                source = ActiveLibraryContentSource { false },
            )
        } as PortabilityRestorePreparationResult.Ready
        assertFalse(empty.existingLibraryHasContent)
        assertFalse(emptyPrepared.closed)

        val failedPrepared = FakePreparedRestore(PREVIEW)
        val failed = runBlocking {
            attachAuthoritativeLibraryContent(
                prepared = failedPrepared,
                source = ActiveLibraryContentSource { error("database unavailable") },
            )
        } as PortabilityRestorePreparationResult.Failed
        assertEquals(PortabilityWorkflowFailure.RESTORE_LIBRARY_UNAVAILABLE, failed.reason)
        assertTrue(failedPrepared.closed)
    }

    @Test
    fun `verified backup is published before reminder checkpoint and review event`() =
        withFixture { fixture ->
            val artifact = FakeBackupArtifact(SUMMARY)
            fixture.backup.creation = PortabilityBackupCreationResult.Verified(artifact)

            assertTrue(fixture.coordinator.createBackup(PortabilityBackupProtection.PLAIN))

            val request = fixture.pickerRequests.single() as
                PortabilityPickerRequest.CreateBackupDocument
            assertEquals("application/zip", request.mimeType)
            assertTrue(
                fixture.coordinator.onBackupDestinationResult(
                    request.requestId,
                    "content://backup/plain",
                ),
            )

            assertTrue(artifact.closed)
            assertEquals(1, fixture.checkpoints.recordCount)
            assertEquals(SUMMARY, fixture.checkpoints.lastSummary)
            assertEquals(CHECKPOINT, fixture.coordinator.state.value.lastVerifiedBackup)
            assertEquals(
                PortabilityOperationStatus.BackupVerified(
                    PortabilityBackupProtection.PLAIN,
                    SUMMARY,
                ),
                fixture.coordinator.state.value.operation,
            )
            assertEquals(
                listOf(
                    PortabilityWorkflowEvent.VerifiedReviewMilestone(
                        ReviewMilestone.BACKUP_VERIFIED,
                    ),
                ),
                fixture.events,
            )
        }

    @Test
    fun `failed publication closes artifact without recording verified backup`() =
        withFixture { fixture ->
            val artifact = FakeBackupArtifact(SUMMARY)
            fixture.backup.creation = PortabilityBackupCreationResult.Verified(artifact)
            fixture.backup.publication = PortabilityBackupPublicationResult.Failed(
                PortabilityWorkflowFailure.BACKUP_DESTINATION_VERIFICATION_FAILED,
            )

            fixture.coordinator.createBackup(PortabilityBackupProtection.PLAIN)
            val request = fixture.pickerRequests.single()
            fixture.coordinator.onBackupDestinationResult(
                request.requestId,
                "content://backup/tampered",
            )

            assertTrue(artifact.closed)
            assertEquals(0, fixture.checkpoints.recordCount)
            assertTrue(fixture.events.isEmpty())
            assertEquals(
                PortabilityOperationStatus.Failed(
                    PortabilityWorkflowKind.BACKUP,
                    PortabilityWorkflowFailure.BACKUP_DESTINATION_VERIFICATION_FAILED,
                ),
                fixture.coordinator.state.value.operation,
            )
        }

    @Test
    fun `encrypted backup copies caller password and wipes owned copy after verified publish`() =
        withFixture { fixture ->
            val artifact = FakeBackupArtifact(SUMMARY)
            fixture.backup.creation = PortabilityBackupCreationResult.Verified(artifact)
            val callerPassword = "do not retain this".toCharArray()
            val original = callerPassword.copyOf()

            fixture.coordinator.createBackup(
                PortabilityBackupProtection.ENCRYPTED,
                callerPassword,
            )
            assertArrayEquals(original, callerPassword)
            assertFalse(fixture.coordinator.state.value.toString().contains(String(original)))
            val request = fixture.pickerRequests.single() as
                PortabilityPickerRequest.CreateBackupDocument
            assertEquals("application/octet-stream", request.mimeType)

            fixture.coordinator.onBackupDestinationResult(
                request.requestId,
                "content://backup/encrypted",
            )

            assertArrayEquals(original, callerPassword)
            assertTrue(fixture.backup.capturedPassword?.all { it == '\u0000' } == true)
            assertTrue(artifact.closed)
        }

    @Test
    fun `cancelling active publication wipes password closes artifact and reports cancellation`() =
        withFixture { fixture ->
            val artifact = FakeBackupArtifact(SUMMARY)
            fixture.backup.creation = PortabilityBackupCreationResult.Verified(artifact)
            fixture.backup.publicationGate = CompletableDeferred()
            val callerPassword = "temporary publication password".toCharArray()

            fixture.coordinator.createBackup(
                PortabilityBackupProtection.ENCRYPTED,
                callerPassword,
            )
            val request = fixture.pickerRequests.single()
            fixture.coordinator.onBackupDestinationResult(
                request.requestId,
                "content://backup/cancelled",
            )

            assertTrue(fixture.coordinator.cancelCurrentOperation())

            assertTrue(artifact.closed)
            assertTrue(fixture.backup.capturedPassword?.all { it == '\u0000' } == true)
            assertEquals(
                PortabilityOperationStatus.Cancelled(PortabilityWorkflowKind.BACKUP),
                fixture.coordinator.state.value.operation,
            )
            assertEquals(0, fixture.checkpoints.recordCount)
            assertTrue(fixture.events.isEmpty())
        }

    @Test
    fun `encrypted restore supports retry retains preview and reports progress before success`() =
        withFixture { fixture ->
            fixture.restore.inspection = PortabilityRestoreInspection.Supported(
                PortabilityRestoreInputKind.ENCRYPTED,
            )
            val prepared = FakePreparedRestore(PREVIEW)
            fixture.restore.preparations.add(
                PortabilityRestorePreparationResult.Failed(
                    PortabilityWorkflowFailure.RESTORE_WRONG_PASSWORD_OR_DAMAGED,
                ),
            )
            fixture.restore.preparations.add(
                PortabilityRestorePreparationResult.Ready(
                    prepared = prepared,
                    existingLibraryHasContent = true,
                ),
            )

            fixture.coordinator.requestRestoreSource()
            val request = fixture.pickerRequests.single()
            fixture.coordinator.onRestoreSourceResult(
                request.requestId,
                "content://restore/encrypted",
            )
            assertTrue(
                fixture.coordinator.state.value.operation is
                    PortabilityOperationStatus.AwaitingRestorePassword,
            )

            fixture.coordinator.submitRestorePassword("wrong password".toCharArray())
            val retry = fixture.coordinator.state.value.operation as
                PortabilityOperationStatus.AwaitingRestorePassword
            assertEquals(
                PortabilityWorkflowFailure.RESTORE_WRONG_PASSWORD_OR_DAMAGED,
                retry.previousFailure,
            )

            val callerPassword = "correct password".toCharArray()
            val original = callerPassword.copyOf()
            fixture.coordinator.submitRestorePassword(callerPassword)

            assertArrayEquals(original, callerPassword)
            assertTrue(fixture.restore.capturedPasswords.last().all { it == '\u0000' })
            assertEquals(
                PortabilityOperationStatus.RestoreReady(
                    preview = PREVIEW,
                    existingLibraryHasContent = true,
                ),
                fixture.coordinator.state.value.operation,
            )
            assertFalse(prepared.closed)

            fixture.coordinator.restore(RestoreMergePolicy.MERGE_IMPORT_ANYWAY)

            assertEquals(RestoreMergePolicy.MERGE_IMPORT_ANYWAY, fixture.restore.policy)
            assertTrue(prepared.closed)
            assertEquals(
                PortabilityOperationStatus.RestoreCompleted(
                    importedDocumentCount = 1,
                    skippedExactDocumentCount = 0,
                ),
                fixture.coordinator.state.value.operation,
            )
            assertTrue(fixture.restore.progressDelivered)
            assertTrue(
                fixture.events.contains(
                    PortabilityWorkflowEvent.VerifiedReviewMilestone(
                        ReviewMilestone.RESTORE_COMPLETED,
                    ),
                ),
            )
        }

    @Test
    fun `interrupted restore recovery runs before source inspection`() =
        withFixture { fixture ->
            fixture.restore.inspection = PortabilityRestoreInspection.Supported(
                PortabilityRestoreInputKind.PLAIN,
            )
            fixture.restore.preparations.add(
                PortabilityRestorePreparationResult.Ready(
                    prepared = FakePreparedRestore(PREVIEW),
                    existingLibraryHasContent = false,
                ),
            )

            assertTrue(fixture.coordinator.requestRestoreSource())
            val request = fixture.pickerRequests.single()
            assertTrue(
                fixture.coordinator.onRestoreSourceResult(
                    request.requestId,
                    "content://restore/interrupted",
                ),
            )

            assertEquals(
                listOf("recover", "inspect", "prepare"),
                fixture.restore.callOrder,
            )
            assertEquals(1, fixture.restore.recoveryCalls)
            assertTrue(
                fixture.coordinator.state.value.operation is PortabilityOperationStatus.RestoreReady,
            )
        }

    @Test
    fun `ordinary whole library export reports completion without new phone milestone`() =
        withFixture { fixture ->
            fixture.export.result = PortabilityExportResult(
                exportedDocumentCount = 4,
                failedDocumentCount = 0,
            )

            fixture.coordinator.requestWholeLibraryExport()
            val request = fixture.pickerRequests.single()
            fixture.coordinator.onWholeLibraryExportTreeResult(
                request.requestId,
                "content://tree/export",
            )

            assertTrue(fixture.export.progressDelivered)
            assertEquals(
                PortabilityOperationStatus.ExportCompleted(4, 0),
                fixture.coordinator.state.value.operation,
            )
            assertTrue(fixture.events.isEmpty())
        }

    @Test
    fun `close and replacement clean retained artifacts and reject stale picker results`() {
        val firstArtifact = FakeBackupArtifact(SUMMARY)
        withFixture { fixture ->
            fixture.backup.creation = PortabilityBackupCreationResult.Verified(firstArtifact)
            fixture.coordinator.createBackup(PortabilityBackupProtection.PLAIN)
            val staleRequest = fixture.pickerRequests.single()

            assertTrue(fixture.coordinator.requestRestoreSource())
            assertTrue(firstArtifact.closed)
            assertFalse(
                fixture.coordinator.onBackupDestinationResult(
                    staleRequest.requestId,
                    "content://backup/stale",
                ),
            )
        }

        val prepared = FakePreparedRestore(PREVIEW)
        val fixture = newFixture()
        try {
            fixture.restore.inspection = PortabilityRestoreInspection.Supported(
                PortabilityRestoreInputKind.PLAIN,
            )
            fixture.restore.preparations.add(
                PortabilityRestorePreparationResult.Ready(
                    prepared = prepared,
                    existingLibraryHasContent = false,
                ),
            )
            fixture.coordinator.requestRestoreSource()
            val request = fixture.pickerRequests.single()
            fixture.coordinator.onRestoreSourceResult(request.requestId, "content://restore/plain")

            val ready = fixture.coordinator.state.value.operation as
                PortabilityOperationStatus.RestoreReady
            assertFalse(ready.existingLibraryHasContent)

            fixture.coordinator.close()

            assertTrue(prepared.closed)
            assertFalse(fixture.coordinator.requestWholeLibraryExport())
        } finally {
            fixture.close()
        }
    }

    private fun withFixture(block: (Fixture) -> Unit) {
        val fixture = newFixture()
        try {
            block(fixture)
        } finally {
            fixture.close()
        }
    }

    private fun newFixture(): Fixture {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val backup = FakeBackupService()
        val restore = FakeRestoreService()
        val export = FakeExportService()
        val checkpoints = FakeCheckpointRecorder()
        val coordinator = PortabilityWorkflowCoordinator(
            scope = scope,
            services = PortabilityWorkflowServices(backup, restore, export, checkpoints),
            nowMillis = { CHECKPOINT.timestampMillis },
            operationContext = Dispatchers.Unconfined,
        )
        val pickerRequests = mutableListOf<PortabilityPickerRequest>()
        val events = mutableListOf<PortabilityWorkflowEvent>()
        scope.launch { coordinator.pickerRequests.collect { pickerRequests += it } }
        scope.launch { coordinator.events.collect { events += it } }
        return Fixture(
            coordinator,
            scope,
            backup,
            restore,
            export,
            checkpoints,
            pickerRequests,
            events,
        )
    }

    private companion object {
        val SUMMARY = PortabilityBackupSummary(
            folderCount = 2,
            documentCount = 3,
            pageCount = 21,
            contentBytes = 12_000L,
            artifactBytes = 8_000L,
            libraryRevision = 42L,
        )
        val CHECKPOINT = VerifiedBackupCheckpoint(
            timestampMillis = 1_790_121_600_000L,
            libraryRevision = 42L,
        )
        val PREVIEW = RestorePreview(
            backupId = "00000000-0000-4000-8000-000000000001",
            createdAtEpochMillis = 1_790_035_200_000L,
            folderCount = 1,
            documentCount = 1,
            pageCount = 21,
            sourceAssetCount = 1,
            contentByteLength = 12_000L,
            documents = listOf(
                RestorePreviewDocument(
                    backupDocumentId = "document-1",
                    title = "Synthetic document",
                    pageCount = 21,
                    sourceAssetCount = 1,
                    duplicateKind = DuplicateKind.DIFFERENT,
                    duplicateDocumentId = null,
                ),
            ),
        )
    }
}

private data class Fixture(
    val coordinator: PortabilityWorkflowCoordinator,
    val scope: CoroutineScope,
    val backup: FakeBackupService,
    val restore: FakeRestoreService,
    val export: FakeExportService,
    val checkpoints: FakeCheckpointRecorder,
    val pickerRequests: MutableList<PortabilityPickerRequest>,
    val events: MutableList<PortabilityWorkflowEvent>,
) : AutoCloseable {
    override fun close() {
        coordinator.close()
        scope.cancel()
    }
}

private class FakeBackupArtifact(
    override val summary: PortabilityBackupSummary,
) : PortabilityBackupArtifact {
    var closed = false

    override fun close() {
        closed = true
    }
}

private class FakeBackupService : PortabilityBackupService {
    var creation: PortabilityBackupCreationResult = PortabilityBackupCreationResult.Failed(
        PortabilityWorkflowFailure.BACKUP_CREATION_FAILED,
    )
    var publication: PortabilityBackupPublicationResult = PortabilityBackupPublicationResult.Verified
    var publicationGate: CompletableDeferred<PortabilityBackupPublicationResult>? = null
    var capturedPassword: CharArray? = null

    override suspend fun create(): PortabilityBackupCreationResult = creation

    override suspend fun publish(
        artifact: PortabilityBackupArtifact,
        destination: PortabilitySafReference,
        protection: PortabilityBackupProtection,
        password: CharArray?,
    ): PortabilityBackupPublicationResult {
        capturedPassword = password
        return publicationGate?.await() ?: publication
    }
}

private class FakePreparedRestore(
    override val preview: RestorePreview,
) : PortabilityPreparedRestore {
    var closed = false

    override fun close() {
        closed = true
    }
}

private class FakeRestoreService : PortabilityRestoreService {
    var inspection: PortabilityRestoreInspection = PortabilityRestoreInspection.Unsupported
    val preparations = ArrayDeque<PortabilityRestorePreparationResult>()
    val capturedPasswords = mutableListOf<CharArray>()
    var execution: PortabilityRestoreExecutionResult = PortabilityRestoreExecutionResult.Completed(1, 0)
    var policy: RestoreMergePolicy? = null
    var progressDelivered = false
    var recoveryCalls = 0
    val callOrder = mutableListOf<String>()

    override suspend fun recoverInterruptedOperations(): Int {
        recoveryCalls += 1
        callOrder += "recover"
        return recoveryCalls
    }

    override suspend fun inspect(source: PortabilitySafReference): PortabilityRestoreInspection {
        callOrder += "inspect"
        return inspection
    }

    override suspend fun prepare(
        source: PortabilitySafReference,
        password: CharArray?,
    ): PortabilityRestorePreparationResult {
        callOrder += "prepare"
        password?.let(capturedPasswords::add)
        return preparations.removeFirst()
    }

    override suspend fun restore(
        prepared: PortabilityPreparedRestore,
        policy: RestoreMergePolicy,
        onProgress: (RestoreProgress) -> Unit,
    ): PortabilityRestoreExecutionResult {
        this.policy = policy
        onProgress(RestoreProgress(1, 1))
        progressDelivered = true
        return execution
    }
}

private class FakeExportService : PortabilityExportService {
    var result = PortabilityExportResult(0, 0)
    var progressDelivered = false

    override suspend fun export(
        destination: PortabilitySafReference,
        onProgress: (completedDocuments: Int, totalDocuments: Int) -> Unit,
    ): PortabilityExportResult {
        onProgress(result.exportedDocumentCount, result.exportedDocumentCount + result.failedDocumentCount)
        progressDelivered = true
        return result
    }
}

private class FakeCheckpointRecorder : VerifiedBackupCheckpointRecorder {
    var recordCount = 0
    var lastSummary: PortabilityBackupSummary? = null

    override fun current(): VerifiedBackupCheckpoint? = null

    override fun recordVerifiedBackup(summary: PortabilityBackupSummary): VerifiedBackupCheckpoint {
        recordCount += 1
        lastSummary = summary
        return VerifiedBackupCheckpoint(
            timestampMillis = 1_790_121_600_000L,
            libraryRevision = summary.libraryRevision,
        )
    }
}
