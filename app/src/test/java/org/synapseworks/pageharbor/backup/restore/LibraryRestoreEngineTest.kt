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
import org.synapseworks.pageharbor.backup.format.BackupFormatTestFixture
import org.synapseworks.pageharbor.library.LibraryOperationGate
import org.synapseworks.pageharbor.library.duplicate.DocumentFingerprintV1
import org.synapseworks.pageharbor.library.duplicate.DuplicateCandidate
import org.synapseworks.pageharbor.library.duplicate.DuplicateKind
import org.synapseworks.pageharbor.library.duplicate.FingerprintPage

class LibraryRestoreEngineTest {
    @Test
    fun nestedTwentyOnePageRoundTripPreservesPortableContentAndActivatesOnce() = runBlocking {
        val fixture = BackupFormatTestFixture(pageCount = 21)
        val archive = fixture.writeArchive()
        val workspace = MemoryRestoreWorkspace()
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
        assertEquals(DuplicateKind.DIFFERENT, prepared.preview.documents.single().duplicateKind)
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
        assertEquals(2, activation.folders.size)
        val root = activation.folders.single { it.originalFolderId == "folder-root" }
        val child = activation.folders.single { it.originalFolderId == "folder-child" }
        assertEquals(root.folderId, child.parentFolderId)
        val restored = activation.documents.single().bundle
        assertEquals(21, restored.pages.size)
        assertEquals(90, restored.pages[1].rotationDegrees)
        assertEquals("GRAYSCALE", restored.pages[1].filterName)
        assertEquals("Synthetic text", restored.pages.first().ocrText)
        assertEquals(1, restored.sourceAssets.size)
        assertEquals("application/pdf", restored.sourceAssets.single().mimeType)
        assertEquals(1_000L, restored.document.createdAtEpochMillis)
        assertEquals(3_000L, restored.document.modifiedAtEpochMillis)
        assertTrue(workspace.operationIds().isEmpty())
    }

    @Test
    fun corruptArchiveAndMissingVerifiedSourceFailWithoutActivatingLibrary() = runBlocking {
        val corruptWorkspace = MemoryRestoreWorkspace()
        val corruptStore = FakeRestoreStore()
        val corruptResult = engine(corruptStore, corruptWorkspace).prepare(
            byteArrayOf(1, 2, 3, 4).source(),
        )

        corruptResult as RestorePreparationResult.Failed
        assertEquals(RestorePreparationFailure.INVALID_OR_CORRUPT_BACKUP, corruptResult.reason)
        assertFalse(corruptStore.journalStarted)
        assertTrue(corruptWorkspace.operationIds().isEmpty())

        val fixture = BackupFormatTestFixture(pageCount = 2)
        val missingWorkspace = MemoryRestoreWorkspace()
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
    fun exactDuplicatesCanBeSkippedOrExplicitlyImportedAndPossibleMatchesAreNotSkipped() = runBlocking {
        val fixture = BackupFormatTestFixture(pageCount = 3)
        val exactCandidate = fixture.exactCandidate()

        val skipStore = FakeRestoreStore(candidates = mutableListOf(exactCandidate))
        val skipEngine = engine(skipStore, MemoryRestoreWorkspace())
        val skipPrepared = (skipEngine.prepare(fixture.writeArchive().source()) as
            RestorePreparationResult.Ready).prepared
        assertEquals(DuplicateKind.EXACT, skipPrepared.preview.documents.single().duplicateKind)

        val skipped = skipEngine.restore(skipPrepared, RestoreMergePolicy.MERGE_SKIP_EXACT)

        skipped as RestoreResult.Completed
        assertEquals(0, skipped.importedDocumentCount)
        assertEquals(1, skipped.skippedExactDocumentCount)
        assertEquals(0, skipStore.prepareCount)

        val importStore = FakeRestoreStore(candidates = mutableListOf(exactCandidate))
        val importEngine = engine(importStore, MemoryRestoreWorkspace())
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
        val possibleEngine = engine(possibleStore, MemoryRestoreWorkspace())
        val possiblePrepared = (possibleEngine.prepare(fixture.writeArchive().source()) as
            RestorePreparationResult.Ready).prepared
        assertEquals(DuplicateKind.POSSIBLE, possiblePrepared.preview.documents.single().duplicateKind)

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
            val cancellationWorkspace = MemoryRestoreWorkspace()
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
            val failingWorkspace = MemoryRestoreWorkspace()
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
            val workspace = MemoryRestoreWorkspace()
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
        val rejectedWorkspace = MemoryRestoreWorkspace()
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

        val recoveryWorkspace = MemoryRestoreWorkspace()
        val recoveryId = SequentialRestoreIds().newId()
        recoveryWorkspace.create(recoveryId)
        val recoveryStore = FakeRestoreStore(
            recoverable = mutableListOf(RestoreRecoveryOperation(recoveryId)),
        )
        val recoveryEngine = engine(recoveryStore, recoveryWorkspace)

        val recovered = recoveryEngine.recoverInterruptedOperations()

        assertEquals(1, recovered)
        assertEquals(listOf(recoveryId), recoveryStore.terminatedOperationIds)
        assertTrue(recoveryWorkspace.operationIds().isEmpty())
    }

    private fun engine(
        store: FakeRestoreStore,
        workspace: MemoryRestoreWorkspace,
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

    private companion object {
        const val NOW = 1_790_035_200_000L
    }
}

private class FakeRestoreStore(
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
    val terminatedOperationIds = mutableListOf<String>()
    private val completedOperationIds = mutableSetOf<String>()

    override suspend fun duplicateCandidates(): List<DuplicateCandidate> = candidates.toList()

    override suspend fun existingFolders(): List<RestoreExistingFolder> = emptyList()

    override suspend fun beginOperation(plan: RestoreJournalPlan) {
        journalStarted = true
    }

    override suspend fun preparePendingDocument(
        operationId: String,
        document: RestoreDocumentToPrepare,
        assets: RestoreStagedAssetSource,
    ) {
        prepareCount += 1
        preparedOnlyWhileGateHeld = preparedOnlyWhileGateHeld && gate?.isOperationActive == true
        document.bundle.pages.forEach { page -> assets.openAsset(page.relativePath).use(InputStream::readBytes) }
        document.bundle.sourceAssets.forEach { source ->
            assets.openAsset(source.relativePath).use(InputStream::readBytes)
        }
        pending += document
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

private class MemoryRestoreWorkspace(
    private val available: Long = Long.MAX_VALUE,
) : RestoreStagingWorkspace {
    private val areas = linkedMapOf<String, MemoryRestoreStagingArea>()
    var createCount: Int = 0

    override fun availableBytes(): Long = available

    override fun create(operationId: String): RestoreStagingArea {
        createCount += 1
        check(operationId !in areas)
        return MemoryRestoreStagingArea(operationId) { areas.remove(operationId) }.also {
            areas[operationId] = it
        }
    }

    override fun discard(operationId: String): Boolean = areas.remove(operationId) != null || operationId !in areas

    fun removeAsset(operationId: String, path: String) {
        areas.getValue(operationId).assets.remove(path)
    }

    fun operationIds(): Set<String> = areas.keys.toSet()
}

private class MemoryRestoreStagingArea(
    override val operationId: String,
    private val onDiscard: () -> Unit,
) : RestoreStagingArea {
    val assets = linkedMapOf<String, ByteArrayOutputStream>()
    private var isVerified = false
    private var isDiscarded = false

    override fun open(relativePath: String): OutputStream {
        check(!isVerified && !isDiscarded)
        return ByteArrayOutputStream().also { assets[relativePath] = it }
    }

    override fun openAsset(relativePath: String): InputStream {
        check(isVerified && !isDiscarded)
        val bytes = assets[relativePath]?.toByteArray() ?: throw IOException("missing staged asset")
        return ByteArrayInputStream(bytes)
    }

    override fun verified() {
        isVerified = true
    }

    override fun abort() {
        discard()
    }

    override fun discard(): Boolean {
        if (!isDiscarded) {
            isDiscarded = true
            assets.clear()
            onDiscard()
        }
        return true
    }
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
