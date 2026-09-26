package org.synapseworks.pageharbor.backup.restore

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.synapseworks.pageharbor.backup.format.BackupVerifiedRecordStore
import org.synapseworks.pageharbor.backup.format.BackupFormatTestFixture
import org.synapseworks.pageharbor.backup.format.BackupOcrArtifactRecord
import org.synapseworks.pageharbor.backup.format.BackupOcrDocumentStateRecord
import org.synapseworks.pageharbor.backup.format.BackupOcrInputFingerprint
import org.synapseworks.pageharbor.backup.format.BackupOcrLineRecord
import org.synapseworks.pageharbor.backup.format.BackupOcrManifest
import org.synapseworks.pageharbor.backup.format.BackupOcrPageStateRecord
import org.synapseworks.pageharbor.backup.format.BackupOcrPoint
import org.synapseworks.pageharbor.backup.format.BackupOcrVerificationState
import org.synapseworks.pageharbor.backup.format.BackupPageRecord
import org.synapseworks.pageharbor.backup.format.BackupSourceAssetRecord
import org.synapseworks.pageharbor.backup.format.RME_BACKUP_FORMAT_VERSION_V2
import org.synapseworks.pageharbor.backup.format.RME_BACKUP_READER_VERSION
import org.synapseworks.pageharbor.backup.format.SnapshotBackupRecordSource
import org.synapseworks.pageharbor.backup.format.writeBackupArchiveForTest
import org.synapseworks.pageharbor.library.LibraryOperationGate
import org.synapseworks.pageharbor.library.duplicate.DocumentFingerprintV1
import org.synapseworks.pageharbor.library.duplicate.DuplicateCandidate
import org.synapseworks.pageharbor.library.duplicate.DuplicateDetector
import org.synapseworks.pageharbor.library.duplicate.DuplicateKind
import org.synapseworks.pageharbor.library.duplicate.DuplicateMatch
import org.synapseworks.pageharbor.library.duplicate.FingerprintPage
import org.synapseworks.pageharbor.library.duplicate.IncomingDocumentIdentity

class LibraryRestoreEngineTest {
    @Test
    fun nestedTwentyOnePageRoundTripPreservesPortableContentAndActivatesOnce() = runBlocking {
        val fixture = BackupFormatTestFixture(pageCount = 21)
        val archive = fixture.writeArchive()
        val workspace = TestIndexedRestoreWorkspace()
        val store = FakeRestoreStore()
        val gate = LibraryOperationGate()
        val engine = engine(store, workspace, gate)

        val preparation = engine.prepare(archive.source())

        val prepared = (preparation as RestorePreparationResult.Ready).prepared
        assertEquals(4_000L, prepared.preview.createdAtEpochMillis)
        assertEquals(2, prepared.preview.folderCount)
        assertEquals(1, prepared.preview.documentCount)
        assertEquals(21, prepared.preview.pageCount)
        assertEquals(1, prepared.preview.sourceAssetCount)
        assertEquals(0, prepared.preview.exactDuplicateCount)
        assertEquals(0, prepared.preview.possibleDuplicateCount)
        assertFalse(store.journalStarted)

        val result = engine.restore(prepared, RestoreMergePolicy.MERGE_IMPORT_ANYWAY)

        result as RestoreResult.Completed
        assertEquals(1, result.importedDocumentCount)
        assertEquals(0, result.skippedExactDocumentCount)
        assertTrue(result.stagingCleanupSucceeded)
        assertTrue(store.journalStarted)
        assertTrue(store.preparedOnlyWhileGateHeld)
        assertTrue(store.activatedOnlyWhileGateHeld)
        assertEquals(1, store.visibleDocumentCount)
        val activation = requireNotNull(store.activation)
        val activatedFolders = activation.folders.plannedFoldersPage(-1, 10).map { it.folder }
        assertEquals(2, activatedFolders.size)
        val root = activatedFolders.single { it.originalFolderId == "folder-root" }
        val child = activatedFolders.single { it.originalFolderId == "folder-child" }
        assertEquals(root.folderId, child.parentFolderId)
        assertEquals(21, store.observedPages.size)
        assertEquals(90, store.observedPages[1].rotationDegrees)
        assertEquals("GRAYSCALE", store.observedPages[1].filterName)
        assertEquals("Synthetic text", store.observedPages.first().ocrText)
        assertEquals(1, store.observedSources.size)
        assertEquals("application/pdf", store.observedSources.single().mimeType)
        assertEquals(1_000L, store.pendingSource.single().document.createdAtEpochMillis)
        assertEquals(3_000L, store.pendingSource.single().document.modifiedAtEpochMillis)
        assertTrue(workspace.operationIds().isEmpty())
    }

    @Test
    fun corruptArchiveAndMissingVerifiedSourceFailWithoutActivatingLibrary() = runBlocking {
        val corruptWorkspace = TestIndexedRestoreWorkspace()
        val corruptStore = FakeRestoreStore()
        val corruptResult = engine(corruptStore, corruptWorkspace).prepare(
            byteArrayOf(1, 2, 3, 4).source(),
        )

        corruptResult as RestorePreparationResult.Failed
        assertEquals(RestorePreparationFailure.INVALID_OR_CORRUPT_BACKUP, corruptResult.reason)
        assertFalse(corruptStore.journalStarted)
        assertTrue(corruptWorkspace.operationIds().isEmpty())

        val fixture = BackupFormatTestFixture(pageCount = 2)
        val missingWorkspace = TestIndexedRestoreWorkspace()
        val missingStore = FakeRestoreStore()
        val missingEngine = engine(missingStore, missingWorkspace)
        val prepared = (missingEngine.prepare(fixture.writeArchive().source()) as
            RestorePreparationResult.Ready).prepared
        missingWorkspace.removeAsset(
            prepared.stagingArea.operationId,
            fixture.sourceAssets.single().relativePath,
        )

        val missingResult = missingEngine.restore(
            prepared,
            RestoreMergePolicy.MERGE_IMPORT_ANYWAY,
        )

        missingResult as RestoreResult.Failed
        assertEquals(RestoreFailure.SOURCE_MISSING, missingResult.reason)
        assertTrue(missingResult.cleanupSucceeded)
        assertNull(missingStore.activation)
        assertEquals(0, missingStore.visibleDocumentCount)
        assertTrue(missingStore.terminated)
        assertTrue(missingWorkspace.operationIds().isEmpty())
    }

    @Test
    fun contentOnlyDuplicatesRemainPossibleAndAreNeverSilentlySkipped() = runBlocking {
        val fixture = BackupFormatTestFixture(pageCount = 3)
        val exactCandidate = fixture.exactCandidate()

        val skipStore = FakeRestoreStore(candidates = mutableListOf(exactCandidate))
        val skipEngine = engine(skipStore, TestIndexedRestoreWorkspace())
        val skipPrepared = (skipEngine.prepare(fixture.writeArchive().source()) as
            RestorePreparationResult.Ready).prepared
        assertEquals(1, skipPrepared.preview.possibleDuplicateCount)

        val skipped = skipEngine.restore(skipPrepared, RestoreMergePolicy.MERGE_SKIP_EXACT)

        skipped as RestoreResult.Completed
        assertEquals(1, skipped.importedDocumentCount)
        assertEquals(0, skipped.skippedExactDocumentCount)
        assertEquals(1, skipStore.prepareCount)

        val importStore = FakeRestoreStore(candidates = mutableListOf(exactCandidate))
        val importEngine = engine(importStore, TestIndexedRestoreWorkspace())
        val importPrepared = (importEngine.prepare(fixture.writeArchive().source()) as
            RestorePreparationResult.Ready).prepared

        val imported = importEngine.restore(
            importPrepared,
            RestoreMergePolicy.MERGE_IMPORT_ANYWAY,
        )

        imported as RestoreResult.Completed
        assertEquals(1, imported.importedDocumentCount)
        assertEquals(1, importStore.prepareCount)

        val possibleCandidate = exactCandidate.copy(
            contentSha256 = "0".repeat(64),
            sourceSha256 = setOf(fixture.sourceAssets.single().sha256),
        )
        val possibleStore = FakeRestoreStore(candidates = mutableListOf(possibleCandidate))
        val possibleEngine = engine(possibleStore, TestIndexedRestoreWorkspace())
        val possiblePrepared = (possibleEngine.prepare(fixture.writeArchive().source()) as
            RestorePreparationResult.Ready).prepared
        assertEquals(1, possiblePrepared.preview.possibleDuplicateCount)

        val possibleResult = possibleEngine.restore(
            possiblePrepared,
            RestoreMergePolicy.MERGE_SKIP_EXACT,
        )

        possibleResult as RestoreResult.Completed
        assertEquals(1, possibleResult.importedDocumentCount)
    }

    @Test
    fun cancellationAndActivationInterruptionRemovePendingStateAndLeaveExistingLibraryUntouched() =
        runBlocking {
            val fixture = BackupFormatTestFixture(pageCount = 21)
            val cancellationStore = FakeRestoreStore(initialVisibleDocumentCount = 4)
            val cancellationWorkspace = TestIndexedRestoreWorkspace()
            val cancellationEngine = engine(cancellationStore, cancellationWorkspace)
            val cancellationPrepared = (
                cancellationEngine.prepare(fixture.writeArchive().source()) as
                    RestorePreparationResult.Ready
                ).prepared
            val cancelled = cancellationEngine.restore(
                prepared = cancellationPrepared,
                policy = RestoreMergePolicy.MERGE_IMPORT_ANYWAY,
                cancellationSignal = RestoreCancellationSignal {
                    cancellationStore.prepareCount > 0
                },
            )

            cancelled as RestoreResult.Cancelled
            assertTrue(cancelled.cleanupSucceeded)
            assertEquals(4, cancellationStore.visibleDocumentCount)
            assertTrue(cancellationStore.pending.isEmpty())
            assertNull(cancellationStore.activation)
            assertTrue(cancellationWorkspace.operationIds().isEmpty())

            val failingStore = FakeRestoreStore(
                initialVisibleDocumentCount = 7,
                failActivation = true,
            )
            val failingWorkspace = TestIndexedRestoreWorkspace()
            val failingEngine = engine(failingStore, failingWorkspace)
            val failingPrepared = (
                failingEngine.prepare(fixture.writeArchive().source()) as
                    RestorePreparationResult.Ready
                ).prepared

            val failed = failingEngine.restore(
                failingPrepared,
                RestoreMergePolicy.MERGE_IMPORT_ANYWAY,
            )

            failed as RestoreResult.Failed
            assertEquals(RestoreFailure.ACTIVATION_FAILED, failed.reason)
            assertTrue(failed.cleanupSucceeded)
            assertEquals(7, failingStore.visibleDocumentCount)
            assertTrue(failingStore.pending.isEmpty())
            assertTrue(failingWorkspace.operationIds().isEmpty())
        }

    @Test
    fun cancellationObservedAfterActivationCommitPreservesActiveDocumentsAndReportsSuccess() =
        runBlocking {
            val fixture = BackupFormatTestFixture(pageCount = 3)
            val store = FakeRestoreStore(throwCancellationAfterActivationCommit = true)
            val workspace = TestIndexedRestoreWorkspace()
            val restoreEngine = engine(store, workspace)
            val prepared = (
                restoreEngine.prepare(fixture.writeArchive().source()) as
                    RestorePreparationResult.Ready
                ).prepared

            val result = restoreEngine.restore(
                prepared,
                RestoreMergePolicy.MERGE_IMPORT_ANYWAY,
            )

            result as RestoreResult.Completed
            assertEquals(1, result.importedDocumentCount)
            assertEquals(1, store.visibleDocumentCount)
            assertFalse(store.terminated)
            assertNotNull(store.activation)
            assertTrue(workspace.operationIds().isEmpty())
        }

    @Test
    fun storagePreflightAndJournalRecoveryAreSchedulerIndependent() = runBlocking {
        val fixture = BackupFormatTestFixture()
        val rejectedWorkspace = TestIndexedRestoreWorkspace()
        val rejectedEngine = LibraryRestoreEngine(
            store = FakeRestoreStore(),
            stagingWorkspace = rejectedWorkspace,
            operationGate = LibraryOperationGate(),
            storagePreflight = RestoreStoragePreflight { false },
            idSource = SequentialRestoreIds(),
            clock = RestoreClock { NOW },
        )

        val rejected = rejectedEngine.prepare(fixture.writeArchive().source())

        rejected as RestorePreparationResult.Failed
        assertEquals(RestorePreparationFailure.INSUFFICIENT_STORAGE, rejected.reason)
        assertEquals(0, rejectedWorkspace.createCount)

        val recoveryWorkspace = TestIndexedRestoreWorkspace()
        val recoveryIds = SequentialRestoreIds()
        val recoveryId = recoveryIds.newId()
        val orphanedPreviewId = recoveryIds.newId()
        recoveryWorkspace.create(recoveryId)
        recoveryWorkspace.create(orphanedPreviewId)
        val recoveryStore = FakeRestoreStore(
            recoverable = mutableListOf(RestoreRecoveryOperation(recoveryId)),
        )
        val recoveryEngine = engine(recoveryStore, recoveryWorkspace)

        val recovered = recoveryEngine.recoverInterruptedOperations()

        assertEquals(1, recovered)
        assertEquals(listOf(recoveryId), recoveryStore.terminatedOperationIds)
        assertTrue(recoveryWorkspace.operationIds().isEmpty())
        assertEquals(2, recoveryWorkspace.discardCount)
    }

    private fun engine(
        store: FakeRestoreStore,
        workspace: TestIndexedRestoreWorkspace,
        gate: LibraryOperationGate = LibraryOperationGate(),
    ): LibraryRestoreEngine {
        store.gate = gate
        return LibraryRestoreEngine(
            store = store,
            stagingWorkspace = workspace,
            operationGate = gate,
            idSource = SequentialRestoreIds(),
            clock = RestoreClock { NOW },
        )
    }

    private fun v2ArchiveWithOcrLine(): ByteArray {
        val fixture = BackupFormatTestFixture()
        val activePage = fixture.pages.first()
        val rawText = "Synthetic staged OCR"
        val line = BackupOcrLineRecord(
            pageId = activePage.pageId,
            artifactRevision = 1,
            lineOrdinal = 0,
            rawText = rawText,
            cornerPoints = listOf(
                BackupOcrPoint(0.1, 0.1),
                BackupOcrPoint(0.9, 0.1),
                BackupOcrPoint(0.9, 0.2),
                BackupOcrPoint(0.1, 0.2),
            ),
            baselineStart = BackupOcrPoint(0.1, 0.18),
            baselineEnd = BackupOcrPoint(0.9, 0.18),
            baselineAngleDegrees = 0.0,
            writingOrientation = "HORIZONTAL_LTR",
        )
        val artifact = BackupOcrArtifactRecord(
            pageId = activePage.pageId,
            artifactRevision = 1,
            capturedPageVisualRevision = 0,
            capturedDocumentContentRevision = 1,
            verificationState = BackupOcrVerificationState.CURRENT_VERIFIED,
            inputFingerprint = BackupOcrInputFingerprint(
                version = 1,
                value = "synthetic-input-fingerprint",
                contentSha256 = activePage.sha256,
                visualRevision = 0,
                rotationDegrees = activePage.rotationDegrees,
                filterName = activePage.filterName,
                uprightWidth = activePage.width,
                uprightHeight = activePage.height,
                coordinateSystemVersion = 1,
                transformVersion = 1,
            ),
            actualScript = "LATIN",
            recognizerId = "synthetic-latin",
            pipelineVersion = "1",
            clientVersion = "17",
            delivery = "BUNDLED",
            recognizedAtEpochMillis = 1L,
            rawText = rawText,
            lineCount = 1,
        )
        val pages = fixture.pages.map { page ->
            page.copy(ocrText = if (page.pageId == activePage.pageId) rawText else null)
        }
        val records = SnapshotBackupRecordSource(
            folders = fixture.folders,
            documents = fixture.documents,
            pages = pages,
            sourceAssets = fixture.sourceAssets,
            ocrDocumentStates = fixture.documents.map { document ->
                BackupOcrDocumentStateRecord(document.documentId, 1, "LATIN")
            },
            ocrPageStates = pages.map { page ->
                if (page.pageId == activePage.pageId) {
                    BackupOcrPageStateRecord(page.pageId, 0, 1, 1)
                } else {
                    BackupOcrPageStateRecord(page.pageId, 0, 0, null)
                }
            },
            ocrArtifacts = listOf(artifact),
            ocrLines = listOf(line),
        )
        val output = ByteArrayOutputStream()
        writeBackupArchiveForTest(
            destination = output,
            manifest = fixture.manifest.copy(
                formatVersion = RME_BACKUP_FORMAT_VERSION_V2,
                minimumReaderVersion = RME_BACKUP_READER_VERSION,
                ocr = BackupOcrManifest.empty(),
            ),
            records = records,
            assets = fixture.assets,
        )
        return output.toByteArray()
    }

    private companion object {
        const val NOW = 1_790_035_200_000L
    }
}

internal class FakeRestoreStore(
    private val candidates: MutableList<DuplicateCandidate> = mutableListOf(),
    private val initialVisibleDocumentCount: Int = 0,
    private val failActivation: Boolean = false,
    private val throwCancellationAfterActivationCommit: Boolean = false,
    private val recoverable: MutableList<RestoreRecoveryOperation> = mutableListOf(),
) : RestoreLibraryStore {
    var gate: LibraryOperationGate? = null
    var journalStarted = false
    var prepareCount = 0
    var preparedOnlyWhileGateHeld = true
    var activatedOnlyWhileGateHeld = true
    var activation: RestoreActivationPlan? = null
    var terminated = false
    var visibleDocumentCount = initialVisibleDocumentCount
    val pending = mutableListOf<RestoreDocumentToPrepare>()
    val pendingSource = mutableListOf<RestoreIndexedDocument>()
    val observedPages = mutableListOf<BackupPageRecord>()
    val observedSources = mutableListOf<BackupSourceAssetRecord>()
    val terminatedOperationIds = mutableListOf<String>()
    private val completedOperationIds = mutableSetOf<String>()

    override suspend fun classifyDuplicate(identity: IncomingDocumentIdentity): DuplicateMatch =
        DuplicateDetector.classify(identity, candidates)

    override suspend fun possibleSourceDuplicate(sourceSha256: Collection<String>): String? =
        candidates.firstOrNull { candidate -> candidate.sourceSha256.any(sourceSha256::contains) }?.documentId

    override suspend fun folderNameExists(parentFolderId: String?, normalizedName: String): Boolean = false

    override suspend fun beginOperation(plan: RestoreJournalPlan) {
        journalStarted = true
    }

    override suspend fun recordSkippedExactDocument(
        operationId: String,
        ordinal: Int,
        source: RestoreIndexedDocument,
    ) = Unit

    override suspend fun preparePendingDocument(
        operationId: String,
        ordinal: Int,
        document: RestoreDocumentToPrepare,
        records: BackupVerifiedRecordStore,
        assets: RestoreStagedAssetSource,
    ) {
        prepareCount += 1
        preparedOnlyWhileGateHeld = preparedOnlyWhileGateHeld && gate?.isOperationActive == true
        var afterPosition = -1
        while (true) {
            val page = records.pagesPage(document.source.document.documentId, afterPosition, 8)
            if (page.isEmpty()) break
            page.forEach {
                assets.openAsset(it.relativePath).use(InputStream::readBytes)
                observedPages += it
            }
            afterPosition = page.last().position
        }
        var afterSourceId: String? = null
        while (true) {
            val source = records.sourceAssetsPage(document.source.document.documentId, afterSourceId, 8)
            if (source.isEmpty()) break
            source.forEach {
                assets.openAsset(it.relativePath).use(InputStream::readBytes)
                observedSources += it
            }
            afterSourceId = source.last().sourceId
        }
        pending += document
        pendingSource += document.source
    }

    override suspend fun activate(plan: RestoreActivationPlan) {
        activatedOnlyWhileGateHeld = activatedOnlyWhileGateHeld && gate?.isOperationActive == true
        if (failActivation) throw RestoreStoreException(RestoreFailure.ACTIVATION_FAILED)
        activation = plan
        visibleDocumentCount += pending.size
        pending.clear()
        completedOperationIds += plan.operationId
        if (throwCancellationAfterActivationCommit) {
            throw CancellationException("synthetic post-commit cancellation")
        }
    }

    override suspend fun isOperationCompleted(operationId: String): Boolean =
        operationId in completedOperationIds

    override suspend fun terminate(
        operationId: String,
        cancelled: Boolean,
        failure: RestoreFailure?,
    ): Boolean {
        terminated = true
        terminatedOperationIds += operationId
        pending.clear()
        recoverable.removeAll { it.operationId == operationId }
        return true
    }

    override suspend fun recoverableOperations(): List<RestoreRecoveryOperation> = recoverable.toList()
}

private class SequentialRestoreIds : RestoreIdSource {
    private val next = AtomicInteger(1)

    override fun newId(): String {
        val value = next.getAndIncrement()
        return "%08x-0000-4000-8000-%012x".format(value, value)
    }
}

private fun ByteArray.source(): RestoreArchiveSource = RestoreArchiveSource(size.toLong()) {
    ByteArrayInputStream(this)
}

private fun BackupFormatTestFixture.exactCandidate(): DuplicateCandidate {
    val fingerprint = DocumentFingerprintV1.calculate(
        pages.sortedBy { it.position }.map { page ->
            FingerprintPage(
                assetSha256 = page.sha256,
                mimeType = page.mimeType,
                byteLength = page.byteLength,
                rotationDegrees = page.rotationDegrees,
                filterName = page.filterName,
            )
        },
    )
    return DuplicateCandidate(
        documentId = "existing-document",
        contentHashVersion = fingerprint.version,
        contentSha256 = fingerprint.sha256,
        sourceSha256 = emptySet(),
        pageCount = pages.size,
        contentByteLength = pages.sumOf { it.byteLength },
        orderedMimeTypes = pages.map { it.mimeType },
    )
}
