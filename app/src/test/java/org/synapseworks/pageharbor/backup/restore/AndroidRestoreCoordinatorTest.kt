package org.synapseworks.pageharbor.backup.restore

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.synapseworks.pageharbor.backup.crypto.EncryptedBackupEnvelope
import org.synapseworks.pageharbor.backup.format.BackupFormatTestFixture
import org.synapseworks.pageharbor.backup.format.BackupVerifiedRecordStore
import org.synapseworks.pageharbor.library.LibraryOperationGate
import org.synapseworks.pageharbor.library.duplicate.DuplicateKind
import org.synapseworks.pageharbor.library.duplicate.DuplicateMatch
import org.synapseworks.pageharbor.library.duplicate.IncomingDocumentIdentity

class AndroidRestoreCoordinatorTest {
    @Test
    fun inspectionReadsOnlyMagicAndRequiresPasswordOnlyForEncryptedInput() = withCoordinator { fixture ->
        val plainRef = RestoreSafReference("plain")
        val encryptedRef = RestoreSafReference("encrypted")
        val unsupportedRef = RestoreSafReference("unsupported")
        fixture.saf.put(plainRef, PLAIN_ARCHIVE)
        fixture.saf.put(encryptedRef, ENCRYPTED_ARCHIVE)
        fixture.saf.put(unsupportedRef, "not a backup".toByteArray())

        val plain = runBlocking { fixture.coordinator.inspect(plainRef) }
        val encrypted = runBlocking { fixture.coordinator.inspect(encryptedRef) }
        val unsupported = runBlocking { fixture.coordinator.inspect(unsupportedRef) }

        assertEquals(
            RestoreSourceInspection.Supported(RestoreInputKind.ZIP, requiresPassword = false),
            plain,
        )
        assertEquals(
            RestoreSourceInspection.Supported(RestoreInputKind.ENCRYPTED, requiresPassword = true),
            encrypted,
        )
        assertEquals(RestoreSourceInspection.Unsupported, unsupported)
        assertTrue(fixture.saf.completedReads.all { it <= 8 })

        val passwordRequired = runBlocking { fixture.coordinator.prepare(encryptedRef) }

        assertEquals(RestoreCoordinatorPrepareResult.PasswordRequired, passwordRequired)
        assertEquals(0, fixture.archiveWorkspace.createCount)
    }

    @Test
    fun plainAndEncryptedSourcesProducePreviewWithoutRetainingArchiveOrPassword() =
        withCoordinator { fixture ->
            val plainRef = RestoreSafReference("plain")
            val encryptedRef = RestoreSafReference("encrypted")
            fixture.saf.put(plainRef, PLAIN_ARCHIVE)
            fixture.saf.put(encryptedRef, ENCRYPTED_ARCHIVE)

            val ignoredPlainPassword = "not-needed".toCharArray()
            val plain = runBlocking {
                fixture.coordinator.prepare(plainRef, ignoredPlainPassword)
            } as RestoreCoordinatorPrepareResult.Ready

            assertEquals(21, plain.prepared.preview.pageCount)
            assertTrue(fixture.archiveWorkspace.isEmpty())
            assertArrayEquals("not-needed".toCharArray(), ignoredPlainPassword)
            val restored = runBlocking {
                fixture.coordinator.restore(
                    plain.prepared,
                    RestoreMergePolicy.MERGE_IMPORT_ANYWAY,
                )
            }
            restored as RestoreResult.Completed
            assertEquals(1, restored.importedDocumentCount)
            assertTrue(fixture.assetWorkspace.isEmpty())

            val password = PASSWORD.toCharArray()
            val encrypted = runBlocking {
                fixture.coordinator.prepare(encryptedRef, password)
            } as RestoreCoordinatorPrepareResult.Ready

            assertEquals(21, encrypted.prepared.preview.pageCount)
            assertArrayEquals(PASSWORD.toCharArray(), password)
            assertTrue(fixture.archiveWorkspace.isEmpty())
            encrypted.prepared.close()
            assertTrue(fixture.assetWorkspace.isEmpty())
        }

    @Test
    fun wrongPasswordAndCorruptZipDeleteEveryPrivateStagingArtifact() = withCoordinator { fixture ->
        val encryptedRef = RestoreSafReference("encrypted")
        fixture.saf.put(encryptedRef, ENCRYPTED_ARCHIVE)

        val wrongPassword = runBlocking {
            fixture.coordinator.prepare(encryptedRef, "wrong".toCharArray())
        }

        wrongPassword as RestoreCoordinatorPrepareResult.Failed
        assertEquals(
            RestoreCoordinatorFailure.WRONG_PASSWORD_OR_DAMAGED_ENCRYPTED_BACKUP,
            wrongPassword.reason,
        )
        assertTrue(fixture.archiveWorkspace.isEmpty())
        assertTrue(fixture.assetWorkspace.isEmpty())
        assertFalse(fixture.store.journalStarted)

        val corruptRef = RestoreSafReference("corrupt")
        fixture.saf.put(corruptRef, byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4, 1, 2, 3))
        val corrupt = runBlocking { fixture.coordinator.prepare(corruptRef) }

        corrupt as RestoreCoordinatorPrepareResult.Failed
        assertEquals(RestoreCoordinatorFailure.INVALID_OR_CORRUPT_BACKUP, corrupt.reason)
        assertTrue(fixture.archiveWorkspace.isEmpty())
        assertTrue(fixture.assetWorkspace.isEmpty())
        assertFalse(fixture.store.journalStarted)
    }

    @Test
    fun cancellationDuringSafCopyDeletesArchiveStagingAndPropagates() = withCoordinator { fixture ->
        val reference = RestoreSafReference("cancel")
        fixture.saf.putSequence(
            reference,
            listOf(
                { ByteArrayInputStream(PLAIN_ARCHIVE) },
                {
                    object : InputStream() {
                        override fun read(): Int = throw CancellationException("synthetic")

                        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                            throw CancellationException("synthetic")
                    }
                },
            ),
        )

        assertThrows(CancellationException::class.java) {
            runBlocking { fixture.coordinator.prepare(reference) }
        }

        assertTrue(fixture.archiveWorkspace.isEmpty())
        assertTrue(fixture.assetWorkspace.isEmpty())
        assertFalse(fixture.store.journalStarted)
    }

    @Test
    fun firstOperationAfterProcessRecreationSweepsUnjournaledPrivateScratch() =
        withCoordinator { fixture ->
            val reference = RestoreSafReference("after-recreation")
            fixture.saf.put(reference, PLAIN_ARCHIVE)
            val archiveOrphan = fixture.archiveWorkspace.createOrphan()
            val stagingOrphanId = "ffffffff-ffff-4fff-8fff-ffffffffffff"
            fixture.assetWorkspace.create(stagingOrphanId)

            val result = runBlocking { fixture.coordinator.prepare(reference) }

            result as RestoreCoordinatorPrepareResult.Ready
            assertFalse(archiveOrphan.exists())
            assertFalse(stagingOrphanId in fixture.assetWorkspace.operationIds())
            assertEquals(1, fixture.archiveWorkspace.orphanSweepCount)
            result.prepared.close()
            assertTrue(fixture.archiveWorkspace.isEmpty())
            assertTrue(fixture.assetWorkspace.isEmpty())
        }

    @Test
    fun knownSourceSizeFailsCapacityBeforePrivateArchiveCreation() = withCoordinator { fixture ->
        val reference = RestoreSafReference("known-too-large-for-space")
        fixture.saf.put(reference, PLAIN_ARCHIVE)
        fixture.archiveWorkspace.availableBytesProvider = { 1L }

        val result = runBlocking { fixture.coordinator.prepare(reference) }

        result as RestoreCoordinatorPrepareResult.Failed
        assertEquals(RestoreCoordinatorFailure.INSUFFICIENT_STORAGE, result.reason)
        assertEquals(0, fixture.archiveWorkspace.createCount)
        assertTrue(fixture.archiveWorkspace.isEmpty())
    }

    @Test
    fun unknownSourceSizeIsContinuouslyGuardedWhileCopying() = withCoordinator { fixture ->
        val reference = RestoreSafReference("unknown-size-capacity-drop")
        fixture.saf.put(reference, PLAIN_ARCHIVE, reportSize = false)
        var capacityChecks = 0
        fixture.archiveWorkspace.availableBytesProvider = {
            capacityChecks += 1
            if (capacityChecks == 1) 128L * 1024L * 1024L else 32L * 1024L * 1024L
        }

        val result = runBlocking { fixture.coordinator.prepare(reference) }

        result as RestoreCoordinatorPrepareResult.Failed
        assertEquals(RestoreCoordinatorFailure.INSUFFICIENT_STORAGE, result.reason)
        assertTrue(capacityChecks >= 2)
        assertTrue(fixture.archiveWorkspace.isEmpty())
        assertTrue(fixture.assetWorkspace.isEmpty())
    }

    private fun withCoordinator(block: (CoordinatorFixture) -> Unit) {
        val archiveRoot = Files.createTempDirectory("rme-restore-archive-coordinator").toFile()
        try {
            val store = CoordinatorRestoreStore()
            val assetWorkspace = TestIndexedRestoreWorkspace()
            val gate = LibraryOperationGate()
            store.gate = gate
            val engine = LibraryRestoreEngine(
                store = store,
                stagingWorkspace = assetWorkspace,
                operationGate = gate,
                idSource = CoordinatorIds(),
                clock = RestoreClock { 1_790_035_200_000L },
            )
            val saf = FakeRestoreSafAccess()
            val archiveWorkspace = TrackingArchiveWorkspace(archiveRoot)
            val coordinator = AndroidRestoreCoordinator(
                engine = engine,
                safAccess = saf,
                archiveWorkspace = archiveWorkspace,
                testing = Unit,
            )
            block(
                CoordinatorFixture(
                    coordinator,
                    saf,
                    archiveWorkspace,
                    assetWorkspace,
                    store,
                ),
            )
        } finally {
            archiveRoot.deleteRecursively()
        }
    }

    private companion object {
        const val PASSWORD = "correct horse battery staple"
        val PLAIN_ARCHIVE: ByteArray by lazy {
            BackupFormatTestFixture(pageCount = 21).writeArchive()
        }
        val ENCRYPTED_ARCHIVE: ByteArray by lazy {
            ByteArrayOutputStream().also { output ->
                EncryptedBackupEnvelope.encrypt(
                    plaintextZip = ByteArrayInputStream(PLAIN_ARCHIVE),
                    encryptedDestination = output,
                    password = PASSWORD.toCharArray(),
                )
            }.toByteArray()
        }
    }
}

private data class CoordinatorFixture(
    val coordinator: AndroidRestoreCoordinator,
    val saf: FakeRestoreSafAccess,
    val archiveWorkspace: TrackingArchiveWorkspace,
    val assetWorkspace: TestIndexedRestoreWorkspace,
    val store: CoordinatorRestoreStore,
)

private class FakeRestoreSafAccess : RestoreSafAccess {
    private val sources = linkedMapOf<RestoreSafReference, ArrayDeque<() -> InputStream>>()
    private val byteLengths = linkedMapOf<RestoreSafReference, Long?>()
    val completedReads = mutableListOf<Int>()

    fun put(reference: RestoreSafReference, bytes: ByteArray, reportSize: Boolean = true) {
        sources[reference] = ArrayDeque<() -> InputStream>().apply {
            repeat(8) { add { ByteArrayInputStream(bytes) } }
        }
        byteLengths[reference] = bytes.size.toLong().takeIf { reportSize }
    }

    fun putSequence(reference: RestoreSafReference, sequence: List<() -> InputStream>) {
        sources[reference] = ArrayDeque(sequence)
        byteLengths[reference] = null
    }

    override fun byteLength(reference: RestoreSafReference): Long? = byteLengths[reference]

    override fun open(reference: RestoreSafReference): InputStream {
        val source = sources[reference]?.removeFirstOrNull()?.invoke() ?: throw IOException()
        return object : FilterInputStream(source) {
            private var count = 0

            override fun read(): Int = super.read().also { if (it >= 0) count += 1 }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                super.read(buffer, offset, length).also { if (it > 0) count += it }

            override fun close() {
                completedReads += count
                super.close()
            }
        }
    }
}

private class TrackingArchiveWorkspace(root: java.io.File) : RestoreArchiveWorkspace {
    private val delegate = FileRestoreArchiveWorkspace(root)
    private val created = linkedSetOf<java.io.File>()
    var createCount = 0
    var orphanSweepCount = 0
    var availableBytesProvider: () -> Long? = { Long.MAX_VALUE }

    override fun availableBytes(): Long? = availableBytesProvider()

    override fun create(): java.io.File = delegate.create().also {
        createCount += 1
        created += it
    }

    override fun delete(file: java.io.File): Boolean = delegate.delete(file).also { deleted ->
        if (deleted) created -= file
    }

    override fun discardOrphans(): Boolean = delegate.discardOrphans().also { cleaned ->
        orphanSweepCount += 1
        if (cleaned) created.removeAll { !it.exists() }
    }

    fun createOrphan(): java.io.File = delegate.create().also(created::add)

    fun isEmpty(): Boolean = created.isEmpty()
}

private class CoordinatorRestoreStore : RestoreLibraryStore {
    var gate: LibraryOperationGate? = null
    var journalStarted = false
    private val pending = mutableListOf<RestoreDocumentToPrepare>()

    override suspend fun classifyDuplicate(identity: IncomingDocumentIdentity): DuplicateMatch =
        DuplicateMatch(DuplicateKind.DIFFERENT, null)

    override suspend fun possibleSourceDuplicate(sourceSha256: Collection<String>): String? = null

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
        check(gate?.isOperationActive == true)
        var afterPosition = -1
        while (true) {
            val pages = records.pagesPage(document.source.document.documentId, afterPosition, 8)
            if (pages.isEmpty()) break
            pages.forEach { page ->
                assets.openAsset(page.relativePath).use { stream -> stream.readBytes() }
            }
            afterPosition = pages.last().position
        }
        var afterSourceId: String? = null
        while (true) {
            val sources = records.sourceAssetsPage(document.source.document.documentId, afterSourceId, 8)
            if (sources.isEmpty()) break
            sources.forEach { source ->
                assets.openAsset(source.relativePath).use { stream -> stream.readBytes() }
            }
            afterSourceId = sources.last().sourceId
        }
        pending += document
    }

    override suspend fun activate(plan: RestoreActivationPlan) {
        check(gate?.isOperationActive == true)
        pending.clear()
    }

    override suspend fun terminate(
        operationId: String,
        cancelled: Boolean,
        failure: RestoreFailure?,
    ): Boolean {
        pending.clear()
        return true
    }

    override suspend fun recoverableOperations(): List<RestoreRecoveryOperation> = emptyList()
}

private class CoordinatorIds : RestoreIdSource {
    private val next = AtomicInteger(1)

    override fun newId(): String {
        val value = next.getAndIncrement()
        return "%08x-0000-4000-8000-%012x".format(value, value)
    }
}
