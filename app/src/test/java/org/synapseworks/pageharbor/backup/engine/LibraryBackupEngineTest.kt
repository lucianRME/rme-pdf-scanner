package org.synapseworks.pageharbor.backup.engine

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.synapseworks.pageharbor.backup.format.BackupAssetStreamOpener
import org.synapseworks.pageharbor.backup.format.BackupDocumentRecord
import org.synapseworks.pageharbor.backup.format.BackupFolderRecord
import org.synapseworks.pageharbor.backup.format.BackupFormatLimits
import org.synapseworks.pageharbor.backup.format.BackupJsonCodec
import org.synapseworks.pageharbor.backup.format.BackupOcrArtifactRecord
import org.synapseworks.pageharbor.backup.format.BackupOcrDocumentStateRecord
import org.synapseworks.pageharbor.backup.format.BackupOcrPageStateRecord
import org.synapseworks.pageharbor.backup.format.BackupOcrManifest
import org.synapseworks.pageharbor.backup.format.BackupOcrVerificationState
import org.synapseworks.pageharbor.backup.format.BackupPageRecord
import org.synapseworks.pageharbor.backup.format.BackupProducer
import org.synapseworks.pageharbor.backup.format.PreparedBackupArchiveSource
import org.synapseworks.pageharbor.backup.format.PreparedBackupAsset
import org.synapseworks.pageharbor.backup.format.PreparedBackupAssetSource
import org.synapseworks.pageharbor.backup.format.TestPreparedBackupArchiveSource
import org.synapseworks.pageharbor.backup.format.BackupIntegrity
import org.synapseworks.pageharbor.backup.format.BackupManifest
import org.synapseworks.pageharbor.backup.format.BackupMetadataPaths
import org.synapseworks.pageharbor.backup.format.RME_BACKUP_CHECKSUMS_PATH
import org.synapseworks.pageharbor.backup.format.RME_BACKUP_FORMAT_VERSION
import org.synapseworks.pageharbor.backup.format.RME_BACKUP_READER_VERSION
import org.synapseworks.pageharbor.backup.format.RME_BACKUP_DOCUMENTS_PATH
import org.synapseworks.pageharbor.backup.format.RME_BACKUP_FOLDERS_PATH
import org.synapseworks.pageharbor.backup.format.RME_BACKUP_PAGES_PATH
import org.synapseworks.pageharbor.backup.format.RME_BACKUP_SOURCE_ASSETS_PATH
import org.synapseworks.pageharbor.backup.format.BackupSourceAssetRecord
import org.synapseworks.pageharbor.backup.format.BackupSummary
import org.synapseworks.pageharbor.backup.format.SnapshotBackupRecordSource
import org.synapseworks.pageharbor.library.LibraryOperationGate

class LibraryBackupEngineTest {
    @Test
    fun createsAndReopensVerifiedNestedTwentyOnePageBackupWithOriginalPdf() = withWorkspace { root ->
        val gate = LibraryOperationGate()
        val fixture = EngineFixture(pageCount = 21, gate = gate)
        val engine = engine(fixture.source, root, gate)

        val result = runBlocking { engine.create(PRODUCER) }

        val artifact = (result as LibraryBackupCreationResult.Verified).artifact
        assertEquals(LibraryBackupArtifactState.VERIFIED, artifact.state)
        assertTrue(artifact.file.isFile)
        assertTrue(artifact.sizeBytes > 0L)
        assertEquals(21, artifact.manifest.summary.pageCount)
        assertEquals(1, artifact.manifest.summary.sourceAssetCount)
        assertTrue(fixture.capturedWhileGateHeld.get())
        assertTrue(fixture.streamedWhileGateHeld.get())
        assertTrue(fixture.snapshotClosed.get())
        assertTrue(artifact.verificationScratchDirectory.listFiles().orEmpty().isEmpty())

        val reopenedManifest = artifact.file.inputStream().use { input ->
            TestBackupArchiveVerifier.verify(
                source = input,
                limits = BackupFormatLimits(),
                checkCancellation = {},
                scratchDirectory = artifact.verificationScratchDirectory,
            )
        }
        assertEquals(artifact.manifest, reopenedManifest)
        val folders = readZipRecords(artifact.file, RME_BACKUP_FOLDERS_PATH, BackupJsonCodec::readFolders)
        val documents = readZipRecords(
            artifact.file,
            RME_BACKUP_DOCUMENTS_PATH,
            BackupJsonCodec::readDocuments,
        )
        val pages = readZipRecords(artifact.file, RME_BACKUP_PAGES_PATH, BackupJsonCodec::readPages)
        val sources = readZipRecords(
            artifact.file,
            RME_BACKUP_SOURCE_ASSETS_PATH,
            BackupJsonCodec::readSourceAssets,
        )
        assertEquals("folder-root", folders.single { it.folderId == "folder-child" }.parentFolderId)
        assertEquals("Synthetic migration archive", documents.single().title)
        assertEquals(21, pages.size)
        assertEquals(90, pages[1].rotationDegrees)
        assertEquals("GRAYSCALE", pages[1].filterName)
        assertEquals("Synthetic OCR", pages.first().ocrText)
        assertEquals("application/pdf", sources.single().mimeType)

        val artifactFile = artifact.file
        artifact.close()
        assertTrue(artifact.isClosed)
        assertFalse(artifactFile.exists())
    }

    @Test
    fun storagePreflightCanRejectBeforeAnyTemporaryFileIsCreated() = withWorkspace { root ->
        val gate = LibraryOperationGate()
        val fixture = EngineFixture(pageCount = 21, gate = gate)
        val engine = LibraryBackupEngine(
            snapshotSource = fixture.source,
            workspace = FileLibraryBackupWorkspace(root),
            operationGate = gate,
            storagePreflight = LibraryBackupStoragePreflight { false },
            clock = LibraryBackupClock { CREATED_AT },
            idSource = LibraryBackupIdSource { BACKUP_ID },
            archiveVerifier = TestBackupArchiveVerifier,
        )

        val result = runBlocking { engine.create(PRODUCER) }

        result as LibraryBackupCreationResult.Failed
        assertEquals(LibraryBackupCreationFailure.INSUFFICIENT_TEMPORARY_STORAGE, result.failure)
        assertNotNull(result.storageEstimate)
        assertTrue(requireNotNull(result.storageEstimate).contentBytes > 0L)
        assertTrue(root.listFiles().orEmpty().isEmpty())
        assertTrue(fixture.snapshotClosed.get())
    }

    @Test
    fun snapshotCaptureCleanupFailureIsReported() = withWorkspace { root ->
        val engine = LibraryBackupEngine(
            snapshotSource = LibraryBackupSnapshotSource {
                throw LibraryBackupSnapshotException(
                    failure = LibraryBackupSnapshotFailure.DATABASE_UNAVAILABLE,
                    message = "synthetic capture failure",
                    cleanupSucceeded = false,
                )
            },
            workspace = FileLibraryBackupWorkspace(root),
            operationGate = LibraryOperationGate(),
            archiveVerifier = TestBackupArchiveVerifier,
        )

        val result = runBlocking { engine.create(PRODUCER) }

        result as LibraryBackupCreationResult.Failed
        assertEquals(LibraryBackupCreationFailure.SNAPSHOT_UNAVAILABLE, result.failure)
        assertFalse(result.cleanupSucceeded)
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun assetFailureReturnsFailureAndRemovesPartialArchive() = withWorkspace { root ->
        val gate = LibraryOperationGate()
        val fixture = EngineFixture(pageCount = 2, gate = gate)
        val failedSnapshot = fixture.snapshotWithOpener(
            BackupAssetStreamOpener { ByteArrayInputStream(byteArrayOf(1, 2, 3)) },
        )
        val engine = engine(LibraryBackupSnapshotSource { failedSnapshot }, root, gate)

        val result = runBlocking { engine.create(PRODUCER) }

        result as LibraryBackupCreationResult.Failed
        assertEquals(LibraryBackupCreationFailure.ARCHIVE_WRITE_FAILED, result.failure)
        assertTrue(result.cleanupSucceeded)
        assertTrue(root.listFiles().orEmpty().isEmpty())
        assertTrue(fixture.snapshotClosed.get())
    }

    @Test
    fun cancellationDuringAssetStreamingPropagatesAndRemovesPartialArchive() = withWorkspace { root ->
        val gate = LibraryOperationGate()
        val fixture = EngineFixture(pageCount = 2, gate = gate)
        val cancellingSnapshot = fixture.snapshotWithOpener(
            BackupAssetStreamOpener {
                object : InputStream() {
                    override fun read(): Int = throw CancellationException("synthetic cancellation")

                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                        throw CancellationException("synthetic cancellation")
                }
            },
        )
        val engine = engine(LibraryBackupSnapshotSource { cancellingSnapshot }, root, gate)

        assertThrows(CancellationException::class.java) {
            runBlocking { engine.create(PRODUCER) }
        }

        assertTrue(root.listFiles().orEmpty().isEmpty())
        assertTrue(fixture.snapshotClosed.get())
    }

    @Test
    fun reopenVerificationFailureClosesSnapshotAndRemovesArchive() = withWorkspace { root ->
        val gate = LibraryOperationGate()
        val fixture = EngineFixture(pageCount = 2, gate = gate)
        val failingVerifier = BackupArchiveVerifier { _, _, _, _ ->
            throw IOException("synthetic reopen failure")
        }
        val engine = LibraryBackupEngine(
            snapshotSource = fixture.source,
            workspace = FileLibraryBackupWorkspace(root),
            operationGate = gate,
            storagePreflight = LibraryBackupStoragePreflight { true },
            clock = LibraryBackupClock { CREATED_AT },
            idSource = LibraryBackupIdSource { BACKUP_ID },
            archiveVerifier = failingVerifier,
        )

        val result = runBlocking { engine.create(PRODUCER) }

        result as LibraryBackupCreationResult.Failed
        assertEquals(LibraryBackupCreationFailure.REOPEN_VERIFICATION_FAILED, result.failure)
        assertTrue(result.cleanupSucceeded)
        assertTrue(root.listFiles().orEmpty().isEmpty())
        assertTrue(fixture.snapshotClosed.get())
    }

    @Test
    fun spoolSnapshotRetriesCleanupAndClosesIdempotently() {
        var attempts = 0
        val prepared = EmptyPreparedBackupArchiveSource
        val snapshot = SpoolLibraryBackupSnapshot(
            prepared = prepared,
            boundedStats = LibraryBackupSnapshotStats(
                summary = prepared.preparedSummary,
                ocrManifest = requireNotNull(prepared.preparedOcrManifest),
                serializedMetadataBytes = 0,
                spoolBytes = 0,
                zipEntryCount = 10,
            ),
            cleanup = { ++attempts >= 3 },
        )

        snapshot.close()
        snapshot.close()

        assertEquals(3, attempts)
    }

    @Test
    fun spoolSnapshotReportsCleanupFailureAndAllowsRetry() {
        var attempts = 0
        var allowCleanup = false
        val prepared = EmptyPreparedBackupArchiveSource
        val snapshot = SpoolLibraryBackupSnapshot(
            prepared = prepared,
            boundedStats = LibraryBackupSnapshotStats(
                summary = prepared.preparedSummary,
                ocrManifest = requireNotNull(prepared.preparedOcrManifest),
                serializedMetadataBytes = 0,
                spoolBytes = 0,
                zipEntryCount = 10,
            ),
            cleanup = {
                attempts += 1
                allowCleanup
            },
        )

        assertThrows(IOException::class.java, snapshot::close)
        assertEquals(3, attempts)
        allowCleanup = true
        snapshot.close()

        assertEquals(4, attempts)
    }

    @Test
    fun storageEstimateAccountsForContentMetadataAndZipOverhead() {
        val gate = LibraryOperationGate()
        val fixture = EngineFixture(pageCount = 21, gate = gate)

        val estimate = LibraryBackupStorageEstimator.estimate(
            fixture.snapshot,
            availableTemporaryBytes = 99_000_000L,
        )

        assertEquals(22L, estimate.zipEntryCount - 10L)
        assertEquals(99_000_000L, estimate.availableTemporaryBytes)
        assertTrue(estimate.contentBytes > 0L)
        assertTrue(estimate.metadataUpperBoundBytes > 0L)
        assertTrue(estimate.temporaryArchiveUpperBoundBytes > estimate.contentBytes)
    }

    private fun engine(
        source: LibraryBackupSnapshotSource,
        root: File,
        gate: LibraryOperationGate,
    ): LibraryBackupEngine = LibraryBackupEngine(
        snapshotSource = source,
        workspace = FileLibraryBackupWorkspace(root),
        operationGate = gate,
        storagePreflight = LibraryBackupStoragePreflight { true },
        clock = LibraryBackupClock { CREATED_AT },
        idSource = LibraryBackupIdSource { BACKUP_ID },
        archiveVerifier = TestBackupArchiveVerifier,
    )

    private fun withWorkspace(block: (File) -> Unit) {
        val root = Files.createTempDirectory("rme-backup-engine-test").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private companion object {
        const val BACKUP_ID = "00000000-0000-4000-8000-000000000015"
        const val CREATED_AT = 1_790_035_200_000L
        val PRODUCER = BackupProducer("org.synapseworks.pageharbor", "1.5.0", 16)
    }
}

private fun <T> readZipRecords(
    archive: File,
    path: String,
    read: (InputStream, BackupFormatLimits, (T) -> Unit) -> Int,
): List<T> = ZipFile(archive).use { zip ->
    val records = ArrayList<T>()
    zip.getInputStream(requireNotNull(zip.getEntry(path))).use { input ->
        read(input, BackupFormatLimits(), records::add)
    }
    records
}

private object EmptyPreparedBackupArchiveSource : PreparedBackupArchiveSource {
    override val preparedSummary = BackupSummary(0, 0, 0, 0, 0)
    override val preparedOcrManifest = BackupOcrManifest.empty()

    override fun openPreparedEntry(path: String): InputStream = throw UnsupportedOperationException()

    override fun preparedPageAssets(): PreparedBackupAssetSource = EmptyPreparedBackupAssetSource

    override fun preparedSourceAssets(): PreparedBackupAssetSource = EmptyPreparedBackupAssetSource

    override fun checkCancellation() = Unit
}

private object EmptyPreparedBackupAssetSource : PreparedBackupAssetSource {
    override fun iterator(): Iterator<PreparedBackupAsset> = emptyList<PreparedBackupAsset>().iterator()

    override fun close() = Unit
}

private class EngineFixture(
    pageCount: Int,
    private val gate: LibraryOperationGate,
) {
    val capturedWhileGateHeld = AtomicBoolean(false)
    val streamedWhileGateHeld = AtomicBoolean(true)
    val snapshotClosed = AtomicBoolean(false)
    private val bytesByPath = LinkedHashMap<String, ByteArray>()
    private val folders = listOf(
        BackupFolderRecord("folder-root", "Archive", null, 1_000L, 2_000L),
        BackupFolderRecord("folder-child", "Receipts", "folder-root", 1_100L, 2_100L),
    )
    private val pages = (0 until pageCount).map { position ->
        val pageId = "page-${position + 1}"
        val path = "documents/document-1/pages/${position.toString().padStart(6, '0')}-$pageId.png"
        val bytes = syntheticPng(position)
        bytesByPath[path] = bytes
        BackupPageRecord(
            pageId = pageId,
            documentId = "document-1",
            position = position,
            relativePath = path,
            mimeType = "image/png",
            sha256 = sha256(bytes),
            byteLength = bytes.size.toLong(),
            width = 1,
            height = 1,
            rotationDegrees = if (position == 1) 90 else 0,
            filterName = if (position == 1) "GRAYSCALE" else "ORIGINAL",
            ocrText = if (position == 0) "Synthetic OCR" else null,
            ocrError = null,
            sourcePageIndex = position,
        )
    }
    private val sourceBytes = "%PDF-1.4\n1 0 obj<</Type/Catalog>>endobj\n%%EOF\n".toByteArray()
    private val sourcePath = "documents/document-1/sources/source-1.pdf"
    private val sources = listOf(
        BackupSourceAssetRecord(
            sourceId = "source-1",
            documentId = "document-1",
            role = "ORIGINAL_DOCUMENT",
            relativePath = sourcePath,
            mimeType = "application/pdf",
            sha256 = sha256(sourceBytes),
            byteLength = sourceBytes.size.toLong(),
            sourceModifiedAtEpochMillis = 1_500L,
            matchesCurrentRevision = false,
        ),
    )
    private val documents = listOf(
        BackupDocumentRecord(
            documentId = "document-1",
            folderId = "folder-child",
            title = "Synthetic migration archive",
            createdAtEpochMillis = 1_000L,
            modifiedAtEpochMillis = 3_000L,
            contentHashVersion = 1,
            contentSha256 = null,
            pageCount = pageCount,
            sourceAssetCount = 1,
        ),
    )
    val snapshot: LibraryBackupSnapshot
    val source: LibraryBackupSnapshotSource

    init {
        bytesByPath[sourcePath] = sourceBytes
        snapshot = snapshotWithOpener(
            BackupAssetStreamOpener { path ->
                if (!gate.isOperationActive) streamedWhileGateHeld.set(false)
                ByteArrayInputStream(requireNotNull(bytesByPath[path]))
            },
        )
        source = LibraryBackupSnapshotSource {
            capturedWhileGateHeld.set(gate.isOperationActive)
            snapshot
        }
    }

    fun snapshotWithOpener(opener: BackupAssetStreamOpener): LibraryBackupSnapshot =
        TestLibraryBackupSnapshot(
            records = SnapshotBackupRecordSource(
                folders = folders,
                documents = documents,
                pages = pages,
                sourceAssets = sources,
                ocrDocumentStates = listOf(
                BackupOcrDocumentStateRecord(
                    documentId = "document-1",
                    contentRevision = 0,
                    scriptPreference = null,
                ),
                ),
                ocrPageStates = pages.mapIndexed { index, page ->
                    BackupOcrPageStateRecord(
                        pageId = page.pageId,
                        visualRevision = 0,
                        ocrStateRevision = if (index == 0) 1 else 0,
                        activeArtifactRevision = if (index == 0) 1 else null,
                    )
                },
                ocrArtifacts = listOf(
                    BackupOcrArtifactRecord(
                        pageId = pages.first().pageId,
                        artifactRevision = 1,
                        capturedPageVisualRevision = null,
                        capturedDocumentContentRevision = null,
                        verificationState = BackupOcrVerificationState.LEGACY_UNVERIFIED,
                        inputFingerprint = null,
                        actualScript = "LATIN",
                        recognizerId = "RME_LEGACY_LATIN_V1",
                        pipelineVersion = null,
                        clientVersion = null,
                        delivery = null,
                        recognizedAtEpochMillis = null,
                        rawText = "Synthetic OCR",
                        lineCount = 0,
                    ),
                ),
            ),
            assetStreams = opener,
            boundedStats = LibraryBackupSnapshotStats(
                summary = BackupSummary(
                    folderCount = folders.size,
                    documentCount = documents.size,
                    pageCount = pages.size,
                    sourceAssetCount = sources.size,
                    contentByteLength = pages.sumOf(BackupPageRecord::byteLength) +
                        sources.sumOf(BackupSourceAssetRecord::byteLength),
                ),
                ocrManifest = BackupOcrManifest.empty(),
                serializedMetadataBytes = 16_384L,
                spoolBytes = 0L,
                zipEntryCount = 10L + pages.size + sources.size,
            ),
            onClose = { snapshotClosed.set(true) },
        )

    private fun syntheticPng(marker: Int): ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        0x00, 0x00, 0x00, 0x0d, 0x49, 0x48, 0x44, 0x52,
        0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
        marker.toByte(),
    )
}

private class TestLibraryBackupSnapshot(
    records: SnapshotBackupRecordSource,
    assetStreams: BackupAssetStreamOpener,
    override val boundedStats: LibraryBackupSnapshotStats,
    private val onClose: () -> Unit,
    private val prepared: PreparedBackupArchiveSource = TestPreparedBackupArchiveSource(
        manifest = testPreparedManifest(boundedStats),
        records = records,
        assets = assetStreams,
    ),
) : LibraryBackupSnapshot,
    org.synapseworks.pageharbor.backup.format.BackupRecordSource by records,
    BackupAssetStreamOpener by assetStreams,
    PreparedBackupArchiveSource by prepared {
    override fun installCancellationCheck(check: () -> Unit) = Unit

    override fun close() = onClose()
}

private fun testPreparedManifest(stats: LibraryBackupSnapshotStats): BackupManifest = BackupManifest(
    formatVersion = RME_BACKUP_FORMAT_VERSION,
    minimumReaderVersion = RME_BACKUP_READER_VERSION,
    requiredFeatures = emptyList(),
    backupId = "00000000-0000-4000-8000-000000000015",
    createdAtEpochMillis = 1L,
    producer = BackupProducer("org.synapseworks.pageharbor", "1.6.0", 17),
    summary = stats.summary,
    metadata = BackupMetadataPaths(
        RME_BACKUP_FOLDERS_PATH,
        RME_BACKUP_DOCUMENTS_PATH,
        RME_BACKUP_PAGES_PATH,
        RME_BACKUP_SOURCE_ASSETS_PATH,
    ),
    integrity = BackupIntegrity("SHA-256", RME_BACKUP_CHECKSUMS_PATH),
    ocr = stats.ocrManifest,
)

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(separator = "") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
