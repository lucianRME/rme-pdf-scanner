package org.synapseworks.pageharbor.backup.engine

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.synapseworks.pageharbor.backup.format.BackupArchiveReader
import org.synapseworks.pageharbor.backup.format.BackupAssetStreamOpener
import org.synapseworks.pageharbor.backup.format.BackupDocumentRecord
import org.synapseworks.pageharbor.backup.format.BackupFolderRecord
import org.synapseworks.pageharbor.backup.format.BackupPageRecord
import org.synapseworks.pageharbor.backup.format.BackupProducer
import org.synapseworks.pageharbor.backup.format.BackupSourceAssetRecord
import org.synapseworks.pageharbor.backup.format.BackupStagingSink
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

        val staging = CollectingStagingSink()
        val verified = artifact.file.inputStream().use { input ->
            BackupArchiveReader.readAndVerify(input, staging)
        }
        assertEquals("folder-root", verified.folders.single { it.folderId == "folder-child" }.parentFolderId)
        assertEquals("Synthetic migration archive", verified.documents.single().title)
        assertEquals(21, verified.pages.size)
        assertEquals(90, verified.pages[1].rotationDegrees)
        assertEquals("GRAYSCALE", verified.pages[1].filterName)
        assertEquals("Synthetic OCR", verified.pages.first().ocrText)
        assertEquals("application/pdf", verified.sourceAssets.single().mimeType)
        assertTrue(staging.verified)
        assertEquals(22, staging.assets.size)

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
        )

        val result = runBlocking { engine.create(PRODUCER) }

        result as LibraryBackupCreationResult.Failed
        assertEquals(LibraryBackupCreationFailure.INSUFFICIENT_TEMPORARY_STORAGE, result.failure)
        assertNotNull(result.storageEstimate)
        assertTrue(requireNotNull(result.storageEstimate).contentBytes > 0L)
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
    }

    @Test
    fun storageEstimateAccountsForContentMetadataAndZipOverhead() {
        val gate = LibraryOperationGate()
        val fixture = EngineFixture(pageCount = 21, gate = gate)

        val estimate = LibraryBackupStorageEstimator.estimate(
            fixture.snapshot,
            availableTemporaryBytes = 99_000_000L,
        )

        assertEquals(22L, estimate.zipEntryCount - 6L)
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

private class EngineFixture(
    pageCount: Int,
    private val gate: LibraryOperationGate,
) {
    val capturedWhileGateHeld = AtomicBoolean(false)
    val streamedWhileGateHeld = AtomicBoolean(true)
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
        LibraryBackupSnapshot(folders, documents, pages, sources, opener)

    private fun syntheticPng(marker: Int): ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        0x00, 0x00, 0x00, 0x0d, 0x49, 0x48, 0x44, 0x52,
        0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
        marker.toByte(),
    )
}

private class CollectingStagingSink : BackupStagingSink {
    val assets = LinkedHashMap<String, ByteArrayOutputStream>()
    var verified = false

    override fun open(relativePath: String): OutputStream = ByteArrayOutputStream().also {
        assets[relativePath] = it
    }

    override fun verified() {
        verified = true
    }

    override fun abort() {
        verified = false
        assets.clear()
    }
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(separator = "") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
