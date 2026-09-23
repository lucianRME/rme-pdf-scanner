package org.synapseworks.pageharbor.backup.engine

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.synapseworks.pageharbor.backup.format.BackupArchiveReader
import org.synapseworks.pageharbor.backup.format.BackupArchiveWriter
import org.synapseworks.pageharbor.backup.format.BackupAssetStreamOpener
import org.synapseworks.pageharbor.backup.format.BackupFormatLimits
import org.synapseworks.pageharbor.backup.format.BackupIntegrity
import org.synapseworks.pageharbor.backup.format.BackupManifest
import org.synapseworks.pageharbor.backup.format.BackupMetadataPaths
import org.synapseworks.pageharbor.backup.format.BackupProducer
import org.synapseworks.pageharbor.backup.format.BackupStagingSink
import org.synapseworks.pageharbor.backup.format.BackupSummary
import org.synapseworks.pageharbor.backup.format.RME_BACKUP_CHECKSUMS_PATH
import org.synapseworks.pageharbor.backup.format.RME_BACKUP_DOCUMENTS_PATH
import org.synapseworks.pageharbor.backup.format.RME_BACKUP_FOLDERS_PATH
import org.synapseworks.pageharbor.backup.format.RME_BACKUP_FORMAT_VERSION
import org.synapseworks.pageharbor.backup.format.RME_BACKUP_PAGES_PATH
import org.synapseworks.pageharbor.backup.format.RME_BACKUP_READER_VERSION
import org.synapseworks.pageharbor.backup.format.RME_BACKUP_SOURCE_ASSETS_PATH
import org.synapseworks.pageharbor.library.LibraryOperationCoordinator
import org.synapseworks.pageharbor.library.LibraryOperationGate

class LibraryBackupEngine(
    private val snapshotSource: LibraryBackupSnapshotSource,
    private val workspace: LibraryBackupWorkspace,
    private val operationGate: LibraryOperationGate = LibraryOperationCoordinator.gate,
    private val storagePreflight: LibraryBackupStoragePreflight = DefaultLibraryBackupStoragePreflight,
    private val clock: LibraryBackupClock = LibraryBackupClock(System::currentTimeMillis),
    private val idSource: LibraryBackupIdSource = LibraryBackupIdSource { UUID.randomUUID().toString() },
    private val limits: BackupFormatLimits = BackupFormatLimits(),
) {
    suspend fun create(producer: BackupProducer): LibraryBackupCreationResult =
        operationGate.withStableSnapshot {
            createWithinStableSnapshot(producer)
        }

    private suspend fun createWithinStableSnapshot(producer: BackupProducer): LibraryBackupCreationResult {
        val coroutineContext = currentCoroutineContext()
        coroutineContext.ensureActive()
        val snapshot = try {
            snapshotSource.capture()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return LibraryBackupCreationResult.Failed(LibraryBackupCreationFailure.SNAPSHOT_UNAVAILABLE)
        }
        coroutineContext.ensureActive()

        val availableTemporaryBytes = runCatching(workspace::availableBytes).getOrNull()
        val estimate = LibraryBackupStorageEstimator.estimate(snapshot, availableTemporaryBytes)
        val canCreate = runCatching { storagePreflight.canCreate(estimate) }.getOrDefault(false)
        if (!canCreate) {
            return LibraryBackupCreationResult.Failed(
                failure = LibraryBackupCreationFailure.INSUFFICIENT_TEMPORARY_STORAGE,
                storageEstimate = estimate,
            )
        }

        val backupId: String
        val manifest: BackupManifest
        try {
            backupId = idSource.newBackupId()
            manifest = createManifest(backupId, clock.nowEpochMillis(), producer, snapshot)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return LibraryBackupCreationResult.Failed(
                failure = LibraryBackupCreationFailure.SNAPSHOT_UNAVAILABLE,
                storageEstimate = estimate,
            )
        }
        var temporaryArchive: File? = null
        var phase = CreationPhase.CREATE_TEMPORARY_FILE
        try {
            temporaryArchive = workspace.createTemporaryArchive(backupId)
            coroutineContext.ensureActive()
            phase = CreationPhase.WRITE_ARCHIVE
            FileOutputStream(temporaryArchive, false).use { output ->
                val cancellableAssets = BackupAssetStreamOpener { path ->
                    CancellationCheckingInputStream(snapshot.open(path), coroutineContext)
                }
                BackupArchiveWriter.write(
                    destination = output,
                    manifest = manifest,
                    records = snapshot,
                    assets = cancellableAssets,
                    limits = limits,
                )
                output.fd.sync()
            }
            coroutineContext.ensureActive()
            phase = CreationPhase.VERIFY_ARCHIVE
            val verified = reopenAndVerify(temporaryArchive, coroutineContext)
            check(verified.manifest == manifest)
            coroutineContext.ensureActive()
            val sizeBytes = temporaryArchive.length()
            if (!temporaryArchive.isFile || sizeBytes <= 0L) {
                throw IllegalStateException("The verified backup artifact is unavailable.")
            }
            return LibraryBackupCreationResult.Verified(
                VerifiedLibraryBackupArtifact(
                    file = temporaryArchive,
                    manifest = manifest,
                    sizeBytes = sizeBytes,
                    storageEstimate = estimate,
                    deleteArtifact = workspace::deleteTemporaryArchive,
                ),
            )
        } catch (cancelled: CancellationException) {
            temporaryArchive?.let(workspace::deleteTemporaryArchive)
            throw cancelled
        } catch (_: Exception) {
            val cleanupSucceeded = temporaryArchive?.let(workspace::deleteTemporaryArchive) ?: true
            val failure = when (phase) {
                CreationPhase.CREATE_TEMPORARY_FILE ->
                    LibraryBackupCreationFailure.TEMPORARY_FILE_UNAVAILABLE
                CreationPhase.WRITE_ARCHIVE -> LibraryBackupCreationFailure.ARCHIVE_WRITE_FAILED
                CreationPhase.VERIFY_ARCHIVE -> LibraryBackupCreationFailure.REOPEN_VERIFICATION_FAILED
            }
            return LibraryBackupCreationResult.Failed(failure, estimate, cleanupSucceeded)
        }
    }

    private fun createManifest(
        backupId: String,
        createdAtEpochMillis: Long,
        producer: BackupProducer,
        snapshot: LibraryBackupSnapshot,
    ): BackupManifest {
        val contentBytes = snapshot.pageRecords.fold(0L) { total, page -> Math.addExact(total, page.byteLength) }
        val allContentBytes = snapshot.sourceAssetRecords.fold(contentBytes) { total, source ->
            Math.addExact(total, source.byteLength)
        }
        return BackupManifest(
            formatVersion = RME_BACKUP_FORMAT_VERSION,
            minimumReaderVersion = RME_BACKUP_READER_VERSION,
            requiredFeatures = emptyList(),
            backupId = backupId,
            createdAtEpochMillis = createdAtEpochMillis,
            producer = producer,
            summary = BackupSummary(
                folderCount = snapshot.folderRecords.size,
                documentCount = snapshot.documentRecords.size,
                pageCount = snapshot.pageRecords.size,
                sourceAssetCount = snapshot.sourceAssetRecords.size,
                contentByteLength = allContentBytes,
            ),
            metadata = BackupMetadataPaths(
                folders = RME_BACKUP_FOLDERS_PATH,
                documents = RME_BACKUP_DOCUMENTS_PATH,
                pages = RME_BACKUP_PAGES_PATH,
                sourceAssets = RME_BACKUP_SOURCE_ASSETS_PATH,
            ),
            integrity = BackupIntegrity("SHA-256", RME_BACKUP_CHECKSUMS_PATH),
        )
    }

    private fun reopenAndVerify(
        archive: File,
        coroutineContext: CoroutineContext,
    ) = FileInputStream(archive).use { source ->
        BackupArchiveReader.readAndVerify(
            source = CancellationCheckingInputStream(source, coroutineContext),
            staging = VerificationOnlyStagingSink(coroutineContext),
            limits = limits,
        )
    }

    private enum class CreationPhase {
        CREATE_TEMPORARY_FILE,
        WRITE_ARCHIVE,
        VERIFY_ARCHIVE,
    }
}

private class CancellationCheckingInputStream(
    source: InputStream,
    private val coroutineContext: CoroutineContext,
) : FilterInputStream(source) {
    override fun read(): Int {
        coroutineContext.ensureActive()
        return super.read()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        coroutineContext.ensureActive()
        return super.read(buffer, offset, length)
    }
}

private class VerificationOnlyStagingSink(
    private val coroutineContext: CoroutineContext,
) : BackupStagingSink {
    override fun open(relativePath: String): OutputStream = object : OutputStream() {
        override fun write(value: Int) {
            coroutineContext.ensureActive()
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            coroutineContext.ensureActive()
        }
    }

    override fun verified() {
        coroutineContext.ensureActive()
    }

    override fun abort() = Unit
}
