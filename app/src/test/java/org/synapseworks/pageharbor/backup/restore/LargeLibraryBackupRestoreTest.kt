package org.synapseworks.pageharbor.backup.restore

import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.synapseworks.pageharbor.backup.format.BackupArchiveWriter
import org.synapseworks.pageharbor.backup.format.BackupAssetStreamOpener
import org.synapseworks.pageharbor.backup.format.BackupDocumentRecord
import org.synapseworks.pageharbor.backup.format.BackupFolderRecord
import org.synapseworks.pageharbor.backup.format.BackupIntegrity
import org.synapseworks.pageharbor.backup.format.BackupManifest
import org.synapseworks.pageharbor.backup.format.BackupMetadataPaths
import org.synapseworks.pageharbor.backup.format.BackupPageRecord
import org.synapseworks.pageharbor.backup.format.BackupProducer
import org.synapseworks.pageharbor.backup.format.BackupRecordSource
import org.synapseworks.pageharbor.backup.format.BackupSummary
import org.synapseworks.pageharbor.backup.format.RME_BACKUP_CHECKSUMS_PATH
import org.synapseworks.pageharbor.backup.format.RME_BACKUP_DOCUMENTS_PATH
import org.synapseworks.pageharbor.backup.format.RME_BACKUP_FOLDERS_PATH
import org.synapseworks.pageharbor.backup.format.RME_BACKUP_PAGES_PATH
import org.synapseworks.pageharbor.backup.format.RME_BACKUP_SOURCE_ASSETS_PATH
import org.synapseworks.pageharbor.backup.format.SnapshotBackupRecordSource
import org.synapseworks.pageharbor.library.LibraryOperationGate
import org.synapseworks.pageharbor.library.duplicate.DuplicateCandidate

class LargeLibraryBackupRestoreTest {
    @Test
    fun thousandDocumentsAndThreeThousandPagesRoundTripWithBoundedAssetStreaming() = runBlocking {
        val fixture = LargeBackupFixture(documentCount = 1_000, pagesPerDocument = 3)
        val archive = Files.createTempFile("rme-large-library-", ".zip").toFile()
        val writerStreams = ProbedAssetStreams(
            expectedPaths = fixture.pages.mapTo(hashSetOf(), BackupPageRecord::relativePath),
            assetBytes = SYNTHETIC_PNG,
        )

        try {
            val writeResult = archive.outputStream().buffered().use { output ->
                BackupArchiveWriter.write(
                    destination = output,
                    manifest = fixture.manifest,
                    records = fixture.records,
                    assets = writerStreams,
                )
            }

            assertEquals(3_006, writeResult.entryCount)
            assertEquals(3_000, writerStreams.openCount)
            assertEquals(3_000, writerStreams.openedPaths.size)
            assertEquals(1, writerStreams.maximumConcurrentStreams)
            assertTrue(writerStreams.maximumReadRequestBytes <= COPY_BUFFER_BYTES)
            assertEquals(0, writerStreams.activeStreams)
            assertTrue("The synthetic archive must remain small", archive.length() < 16L * 1024L * 1024L)

            val workspace = ProbedRestoreWorkspace()
            val engine = LibraryRestoreEngine(
                store = EmptyRestoreStore,
                stagingWorkspace = workspace,
                operationGate = LibraryOperationGate(),
                storagePreflight = RestoreStoragePreflight { true },
                idSource = RestoreIdSource { RESTORE_OPERATION_ID },
                clock = RestoreClock { CREATED_AT_EPOCH_MILLIS },
            )

            val preparation = engine.prepare(
                RestoreArchiveSource(byteLength = archive.length()) { FileInputStream(archive) },
            )

            val prepared = (preparation as RestorePreparationResult.Ready).prepared
            assertEquals(40, prepared.preview.folderCount)
            assertEquals(1_000, prepared.preview.documentCount)
            assertEquals(3_000, prepared.preview.pageCount)
            assertEquals(0, prepared.preview.sourceAssetCount)
            assertEquals(1_000, prepared.preview.documents.size)
            assertEquals("Document 0517", prepared.verifiedBackup.documents[517].title)
            assertEquals("OCR text 0517", prepared.verifiedBackup.pages[517 * 3].ocrText)
            assertEquals("folder-child-17", prepared.verifiedBackup.documents[517].folderId)
            assertEquals(
                "folder-root-17",
                prepared.verifiedBackup.folders.single { it.folderId == "folder-child-17" }.parentFolderId,
            )

            val staging = workspace.area
            assertTrue(staging.verified)
            assertFalse(staging.aborted)
            assertEquals(3_000, staging.openCount)
            assertEquals(1, staging.maximumConcurrentOutputs)
            assertTrue(staging.maximumWriteRequestBytes <= COPY_BUFFER_BYTES)
            assertEquals(3_000L * SYNTHETIC_PNG.size, staging.totalBytes)
            assertEquals(0, staging.activeOutputs)

            prepared.close()
            assertTrue(prepared.isConsumed)
            assertTrue(staging.discarded)
        } finally {
            archive.delete()
        }
    }

    private companion object {
        const val CREATED_AT_EPOCH_MILLIS = LARGE_LIBRARY_CREATED_AT_EPOCH_MILLIS
        const val RESTORE_OPERATION_ID = "00000000-0000-4000-8000-000000000016"
        const val COPY_BUFFER_BYTES = 32 * 1024
    }
}

private class LargeBackupFixture(
    documentCount: Int,
    pagesPerDocument: Int,
) {
    val folders: List<BackupFolderRecord>
    val documents: List<BackupDocumentRecord>
    val pages: List<BackupPageRecord>
    val manifest: BackupManifest
    val records: BackupRecordSource

    init {
        require(documentCount > 0)
        require(pagesPerDocument > 0)
        val roots = (0 until 20).map { index ->
            BackupFolderRecord(
                folderId = "folder-root-$index",
                name = "Root $index",
                parentFolderId = null,
                createdAtEpochMillis = 1_000L + index,
                modifiedAtEpochMillis = 2_000L + index,
            )
        }
        val children = (0 until 20).map { index ->
            BackupFolderRecord(
                folderId = "folder-child-$index",
                name = "Child $index",
                parentFolderId = "folder-root-$index",
                createdAtEpochMillis = 1_100L + index,
                modifiedAtEpochMillis = 2_100L + index,
            )
        }
        folders = roots + children

        val pageRecords = ArrayList<BackupPageRecord>(documentCount * pagesPerDocument)
        val documentRecords = ArrayList<BackupDocumentRecord>(documentCount)
        repeat(documentCount) { documentIndex ->
            val suffix = documentIndex.toString().padStart(4, '0')
            val documentId = "document-$suffix"
            repeat(pagesPerDocument) { position ->
                val pageId = "page-$suffix-$position"
                pageRecords += BackupPageRecord(
                    pageId = pageId,
                    documentId = documentId,
                    position = position,
                    relativePath = "documents/$documentId/pages/$position-$pageId.png",
                    mimeType = "image/png",
                    sha256 = SYNTHETIC_PNG_SHA256,
                    byteLength = SYNTHETIC_PNG.size.toLong(),
                    width = 1,
                    height = 1,
                    rotationDegrees = if (position == 1) 90 else 0,
                    filterName = if (position == 2) "GRAYSCALE" else "ORIGINAL",
                    ocrText = if (position == 0) "OCR text $suffix" else null,
                    ocrError = null,
                    sourcePageIndex = null,
                )
            }
            documentRecords += BackupDocumentRecord(
                documentId = documentId,
                folderId = "folder-child-${documentIndex % 20}",
                title = "Document $suffix",
                createdAtEpochMillis = 10_000L + documentIndex,
                modifiedAtEpochMillis = 20_000L + documentIndex,
                contentHashVersion = 1,
                contentSha256 = null,
                pageCount = pagesPerDocument,
                sourceAssetCount = 0,
            )
        }
        pages = pageRecords.toList()
        documents = documentRecords.toList()
        val contentBytes = pages.size.toLong() * SYNTHETIC_PNG.size
        manifest = BackupManifest(
            formatVersion = 1,
            minimumReaderVersion = 1,
            requiredFeatures = emptyList(),
            backupId = "00000000-0000-4000-8000-000000000015",
            createdAtEpochMillis = LARGE_LIBRARY_CREATED_AT_EPOCH_MILLIS,
            producer = BackupProducer("org.synapseworks.pageharbor", "1.5.0", 16),
            summary = BackupSummary(
                folderCount = folders.size,
                documentCount = documents.size,
                pageCount = pages.size,
                sourceAssetCount = 0,
                contentByteLength = contentBytes,
            ),
            metadata = BackupMetadataPaths(
                folders = RME_BACKUP_FOLDERS_PATH,
                documents = RME_BACKUP_DOCUMENTS_PATH,
                pages = RME_BACKUP_PAGES_PATH,
                sourceAssets = RME_BACKUP_SOURCE_ASSETS_PATH,
            ),
            integrity = BackupIntegrity("SHA-256", RME_BACKUP_CHECKSUMS_PATH),
        )
        records = SnapshotBackupRecordSource(
            folders = folders,
            documents = documents,
            pages = pages,
            sourceAssets = emptyList(),
        )
    }
}

private class ProbedAssetStreams(
    private val expectedPaths: Set<String>,
    private val assetBytes: ByteArray,
) : BackupAssetStreamOpener {
    val openedPaths = hashSetOf<String>()
    var openCount: Int = 0
        private set
    var activeStreams: Int = 0
        private set
    var maximumConcurrentStreams: Int = 0
        private set
    var maximumReadRequestBytes: Int = 0
        private set

    override fun open(relativePath: String): InputStream {
        check(relativePath in expectedPaths)
        check(openedPaths.add(relativePath))
        openCount += 1
        activeStreams += 1
        maximumConcurrentStreams = maxOf(maximumConcurrentStreams, activeStreams)
        return object : InputStream() {
            private var offset = 0
            private var closed = false

            override fun read(): Int {
                maximumReadRequestBytes = maxOf(maximumReadRequestBytes, 1)
                if (offset >= assetBytes.size) return -1
                return assetBytes[offset++].toInt() and 0xff
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                maximumReadRequestBytes = maxOf(maximumReadRequestBytes, length)
                if (this.offset >= assetBytes.size) return -1
                val count = minOf(length, assetBytes.size - this.offset)
                assetBytes.copyInto(buffer, offset, this.offset, this.offset + count)
                this.offset += count
                return count
            }

            override fun close() {
                if (!closed) {
                    closed = true
                    activeStreams -= 1
                }
            }
        }
    }
}

private object EmptyRestoreStore : RestoreLibraryStore {
    override suspend fun duplicateCandidates(): List<DuplicateCandidate> = emptyList()

    override suspend fun existingFolders(): List<RestoreExistingFolder> = emptyList()

    override suspend fun beginOperation(plan: RestoreJournalPlan) = error("Restore was not requested")

    override suspend fun preparePendingDocument(
        operationId: String,
        document: RestoreDocumentToPrepare,
        assets: RestoreStagedAssetSource,
    ) = error("Restore was not requested")

    override suspend fun activate(plan: RestoreActivationPlan) = error("Restore was not requested")

    override suspend fun terminate(
        operationId: String,
        cancelled: Boolean,
        failure: RestoreFailure?,
    ): Boolean = true

    override suspend fun recoverableOperations(): List<RestoreRecoveryOperation> = emptyList()
}

private class ProbedRestoreWorkspace : RestoreStagingWorkspace {
    lateinit var area: ProbedRestoreStagingArea
        private set

    override fun availableBytes(): Long = Long.MAX_VALUE

    override fun create(operationId: String): RestoreStagingArea =
        ProbedRestoreStagingArea(operationId).also { area = it }

    override fun discard(operationId: String): Boolean =
        if (::area.isInitialized && area.operationId == operationId) area.discard() else true
}

private class ProbedRestoreStagingArea(
    override val operationId: String,
) : RestoreStagingArea {
    private val openedPaths = hashSetOf<String>()
    var openCount: Int = 0
        private set
    var activeOutputs: Int = 0
        private set
    var maximumConcurrentOutputs: Int = 0
        private set
    var maximumWriteRequestBytes: Int = 0
        private set
    var totalBytes: Long = 0L
        private set
    var verified: Boolean = false
        private set
    var aborted: Boolean = false
        private set
    var discarded: Boolean = false
        private set

    override fun open(relativePath: String): OutputStream {
        check(!verified && !discarded)
        check(openedPaths.add(relativePath))
        openCount += 1
        activeOutputs += 1
        maximumConcurrentOutputs = maxOf(maximumConcurrentOutputs, activeOutputs)
        return object : OutputStream() {
            private var closed = false

            override fun write(value: Int) {
                check(!closed)
                maximumWriteRequestBytes = maxOf(maximumWriteRequestBytes, 1)
                totalBytes += 1
            }

            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                check(!closed)
                maximumWriteRequestBytes = maxOf(maximumWriteRequestBytes, length)
                totalBytes += length
            }

            override fun close() {
                if (!closed) {
                    closed = true
                    activeOutputs -= 1
                }
            }
        }
    }

    override fun openAsset(relativePath: String): InputStream =
        throw IOException("Restore activation was not requested")

    override fun verified() {
        check(activeOutputs == 0)
        verified = true
    }

    override fun abort() {
        aborted = true
        discard()
    }

    override fun discard(): Boolean {
        discarded = true
        return true
    }
}

private val SYNTHETIC_PNG = byteArrayOf(
    0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
    0x00, 0x00, 0x00, 0x0d, 0x49, 0x48, 0x44, 0x52,
    0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
    0x00,
)

private val SYNTHETIC_PNG_SHA256 = MessageDigest.getInstance("SHA-256")
    .digest(SYNTHETIC_PNG)
    .joinToString(separator = "") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }

private const val LARGE_LIBRARY_CREATED_AT_EPOCH_MILLIS = 1_790_035_200_000L
