package org.synapseworks.pageharbor.backup.restore

import android.content.Context
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID
import org.synapseworks.pageharbor.backup.format.BackupChecksum
import org.synapseworks.pageharbor.backup.format.BackupFolderRecord
import org.synapseworks.pageharbor.backup.format.BackupFormatLimits
import org.synapseworks.pageharbor.backup.format.BackupIndexedStagingSink
import org.synapseworks.pageharbor.backup.format.BackupManifest
import org.synapseworks.pageharbor.backup.format.BackupDocumentRecord
import org.synapseworks.pageharbor.backup.format.BackupObservedEntryRecord
import org.synapseworks.pageharbor.backup.format.BackupOcrArtifactRecord
import org.synapseworks.pageharbor.backup.format.BackupOcrCorrectionRecord
import org.synapseworks.pageharbor.backup.format.BackupOcrDocumentStateRecord
import org.synapseworks.pageharbor.backup.format.BackupOcrLineChunkDescriptor
import org.synapseworks.pageharbor.backup.format.BackupOcrLineRecord
import org.synapseworks.pageharbor.backup.format.BackupOcrPageStateRecord
import org.synapseworks.pageharbor.backup.format.BackupPageRecord
import org.synapseworks.pageharbor.backup.format.BackupPathValidator
import org.synapseworks.pageharbor.backup.format.BackupSourceAssetRecord
import org.synapseworks.pageharbor.backup.format.BackupStagingSink
import org.synapseworks.pageharbor.backup.format.BackupIndexedTotals
import org.synapseworks.pageharbor.backup.format.IndexedVerifiedBackup
import org.synapseworks.pageharbor.backup.format.RME_BACKUP_OCR_LINES_DIRECTORY

internal interface RestoreStagedAssetSource {
    fun openAsset(relativePath: String): InputStream
}

internal interface RestoreStagingArea : BackupStagingSink, RestoreStagedAssetSource {
    val operationId: String

    fun discard(): Boolean
}

internal interface IndexedRestoreStagingArea :
    RestoreStagingArea,
    BackupIndexedStagingSink,
    RestoreFolderPlanSource {
    fun unplannedReadyFoldersPage(limit: Int): List<BackupFolderRecord>

    fun targetFolderId(originalFolderId: String): String?

    fun targetFolderSearchPath(originalFolderId: String): String?

    fun plannedFolderNameExists(targetParentFolderId: String?, normalizedName: String): Boolean

    fun recordPlannedFolder(folder: RestoreFolderToCreate)

    fun plannedFolderCount(): Int

    fun recordDocumentIdentity(source: RestoreIndexedDocument)

    fun documentIdentitiesPage(afterOrdinal: Int, limit: Int): List<RestoreIndexedDocumentRow>

    fun recordRestoreDecision(documentId: String, duplicateKind: org.synapseworks.pageharbor.library.duplicate.DuplicateKind)

    fun restorePlanPage(afterOrdinal: Int, limit: Int): List<RestorePlannedDocumentRow>
}

internal interface RestoreStagingWorkspace {
    fun availableBytes(): Long?

    fun create(operationId: String): RestoreStagingArea

    fun discard(operationId: String): Boolean

    /** Removes only managed operation directories that have no durable restore journal. */
    fun discardOrphans(retainedOperationIds: Set<String>): Boolean = true
}

internal class FileRestoreStagingWorkspace(
    private val root: File,
    private val retainPayloads: Boolean = true,
) : RestoreStagingWorkspace {
    override fun availableBytes(): Long? {
        val storageRoot = when {
            root.exists() -> root
            root.parentFile != null -> root.parentFile
            else -> return null
        }
        return storageRoot.usableSpace.takeIf { it > 0L }
    }

    override fun create(operationId: String): RestoreStagingArea {
        require(SAFE_OPERATION_ID.matches(operationId)) { "Invalid restore operation ID" }
        if ((!root.exists() && !root.mkdirs()) || !root.isDirectory) {
            throw IOException("The private restore workspace is unavailable.")
        }
        val operationRoot = File(root, operationId)
        if (operationRoot.exists() || !operationRoot.mkdirs()) {
            throw IOException("The private restore staging area is unavailable.")
        }
        makeOwnerOnly(operationRoot)
        return FileRestoreStagingArea(operationId, root, operationRoot, retainPayloads)
    }

    override fun discard(operationId: String): Boolean {
        if (!SAFE_OPERATION_ID.matches(operationId)) return false
        return deleteTreeSafely(root, File(root, operationId))
    }

    override fun discardOrphans(retainedOperationIds: Set<String>): Boolean {
        if (retainedOperationIds.any { !SAFE_OPERATION_ID.matches(it) }) return false
        if (!root.exists()) return true
        if (!root.isDirectory) return false
        var cleaned = true
        return try {
            Files.newDirectoryStream(root.toPath()).use { entries ->
                entries.forEach { entry ->
                    val name = entry.fileName.toString()
                    if (SAFE_OPERATION_ID.matches(name) && name !in retainedOperationIds) {
                        cleaned = deleteTreeSafely(root, entry.toFile()) && cleaned
                    }
                }
            }
            cleaned
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private companion object {
        val SAFE_OPERATION_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}

internal class AndroidRestoreStagingWorkspace(context: Context) : RestoreStagingWorkspace by
    FileRestoreStagingWorkspace(
        File(context.applicationContext.noBackupFilesDir, PRIVATE_RESTORE_DIRECTORY),
    ) {
    private companion object {
        const val PRIVATE_RESTORE_DIRECTORY = "verified-restore-staging"
    }
}

private class FileRestoreStagingArea(
    override val operationId: String,
    private val workspaceRoot: File,
    private val operationRoot: File,
    private val retainPayloads: Boolean,
) : IndexedRestoreStagingArea {
    private val verified = AtomicBoolean(false)
    private val discarded = AtomicBoolean(false)
    private val index = RestoreStagingIndex(File(operationRoot, INDEX_FILE_NAME))

    override fun acceptManifest(manifest: BackupManifest) = index.acceptManifest(manifest)

    override fun acceptFolder(ordinal: Int, record: BackupFolderRecord) = index.acceptFolder(ordinal, record)

    override fun acceptDocument(ordinal: Int, record: BackupDocumentRecord) =
        index.acceptDocument(ordinal, record)

    override fun acceptPage(ordinal: Int, record: BackupPageRecord) = index.acceptPage(ordinal, record)

    override fun acceptSourceAsset(ordinal: Int, record: BackupSourceAssetRecord) =
        index.acceptSourceAsset(ordinal, record)

    override fun acceptOcrDocumentState(ordinal: Int, record: BackupOcrDocumentStateRecord) =
        index.acceptOcrDocumentState(ordinal, record)

    override fun acceptOcrPageState(ordinal: Int, record: BackupOcrPageStateRecord) =
        index.acceptOcrPageState(ordinal, record)

    override fun acceptOcrArtifact(ordinal: Int, record: BackupOcrArtifactRecord) =
        index.acceptOcrArtifact(ordinal, record)

    override fun acceptOcrCorrection(ordinal: Int, record: BackupOcrCorrectionRecord) =
        index.acceptOcrCorrection(ordinal, record)

    override fun acceptOcrLine(ordinal: Int, record: BackupOcrLineRecord) =
        index.acceptOcrLine(ordinal, record)

    override fun acceptOcrLineChunk(ordinal: Int, descriptor: BackupOcrLineChunkDescriptor) =
        index.acceptOcrLineChunk(ordinal, descriptor)

    override fun acceptArchivePath(path: String) = index.acceptArchivePath(path)

    override fun acceptObservedEntry(record: BackupObservedEntryRecord) = index.acceptObservedEntry(record)

    override fun acceptChecksum(record: BackupChecksum) = index.acceptChecksum(record)

    override fun verifyIndexed(
        supportedRequiredFeatures: Set<String>,
        limits: BackupFormatLimits,
        checkCancellation: () -> Unit,
    ): IndexedVerifiedBackup = index.verifyIndexed(supportedRequiredFeatures, limits, this, checkCancellation)

    override fun foldersPage(afterOrdinal: Int, limit: Int): List<BackupFolderRecord> =
        index.foldersPage(afterOrdinal, limit)

    override fun documentsPage(afterOrdinal: Int, limit: Int): List<BackupDocumentRecord> =
        index.documentsPage(afterOrdinal, limit)

    override fun pagesPage(documentId: String, afterPosition: Int, limit: Int): List<BackupPageRecord> =
        index.pagesPage(documentId, afterPosition, limit)

    override fun sourceAssetsPage(
        documentId: String,
        afterSourceId: String?,
        limit: Int,
    ): List<BackupSourceAssetRecord> = index.sourceAssetsPage(documentId, afterSourceId, limit)

    override fun ocrDocumentState(documentId: String): BackupOcrDocumentStateRecord? =
        index.ocrDocumentState(documentId)

    override fun ocrPageState(pageId: String): BackupOcrPageStateRecord? = index.ocrPageState(pageId)

    override fun ocrArtifactsPage(
        pageId: String,
        afterRevision: Long,
        limit: Int,
    ): List<BackupOcrArtifactRecord> = index.ocrArtifactsPage(pageId, afterRevision, limit)

    override fun ocrArtifactCount(pageId: String): Int = index.ocrArtifactCount(pageId)

    override fun ocrCorrection(pageId: String): BackupOcrCorrectionRecord? = index.ocrCorrection(pageId)

    override fun ocrLinesPage(
        pageId: String,
        artifactRevision: Long,
        afterOrdinal: Int,
        limit: Int,
    ): List<BackupOcrLineRecord> = index.ocrLinesPage(pageId, artifactRevision, afterOrdinal, limit)

    override fun totals(): BackupIndexedTotals = index.totals()

    override fun unplannedReadyFoldersPage(limit: Int): List<BackupFolderRecord> =
        index.unplannedReadyFoldersPage(limit)

    override fun targetFolderId(originalFolderId: String): String? =
        index.targetFolderId(originalFolderId)

    override fun targetFolderSearchPath(originalFolderId: String): String? =
        index.targetFolderSearchPath(originalFolderId)

    override fun plannedFolderNameExists(
        targetParentFolderId: String?,
        normalizedName: String,
    ): Boolean = index.plannedFolderNameExists(targetParentFolderId, normalizedName)

    override fun recordPlannedFolder(folder: RestoreFolderToCreate) =
        index.recordPlannedFolder(folder)

    override fun plannedFolderCount(): Int = index.plannedFolderCount()

    override fun plannedFoldersPage(afterOrdinal: Int, limit: Int): List<RestoreFolderPlanRow> =
        index.plannedFoldersPage(afterOrdinal, limit)

    override fun recordDocumentIdentity(source: RestoreIndexedDocument) =
        index.recordDocumentIdentity(source)

    override fun documentIdentitiesPage(afterOrdinal: Int, limit: Int): List<RestoreIndexedDocumentRow> =
        index.documentIdentitiesPage(afterOrdinal, limit)

    override fun recordRestoreDecision(
        documentId: String,
        duplicateKind: org.synapseworks.pageharbor.library.duplicate.DuplicateKind,
    ) = index.recordRestoreDecision(documentId, duplicateKind)

    override fun restorePlanPage(afterOrdinal: Int, limit: Int): List<RestorePlannedDocumentRow> =
        index.restorePlanPage(afterOrdinal, limit)

    override fun open(relativePath: String): OutputStream {
        check(!discarded.get()) { "Restore staging was discarded" }
        check(!verified.get()) { "Verified restore staging is immutable" }
        BackupPathValidator.requireValidArchivePath(relativePath)
        check(BackupPathValidator.isAssetPath(relativePath)) { "Only backup assets may be staged" }
        if (!retainPayloads) return DiscardingOutputStream
        val destination = resolve(relativePath)
        val parent = destination.parentFile ?: throw IOException("Invalid staging destination")
        if ((!parent.exists() && !parent.mkdirs()) || !parent.isDirectory) {
            throw IOException("Unable to create a private restore staging directory")
        }
        makeOwnerOnly(parent)
        if (destination.exists()) throw IOException("A staged asset already exists")
        return FileOutputStream(destination).also { makeOwnerOnly(destination) }
    }

    override fun openMetadata(relativePath: String): OutputStream {
        check(!discarded.get()) { "Restore staging was discarded" }
        check(!verified.get()) { "Verified restore staging is immutable" }
        requireOcrLineChunkPath(relativePath)
        // Indexed restore persists each decoded line in SQLite; retaining the raw JSONL would
        // duplicate the largest metadata payload on disk.
        return DiscardingOutputStream
    }

    override fun openVerifiedMetadata(relativePath: String): InputStream {
        requireOcrLineChunkPath(relativePath)
        throw IOException("Raw OCR line chunks are not retained by indexed restore staging")
    }

    override fun openAsset(relativePath: String): InputStream {
        check(verified.get() && !discarded.get()) { "Restore staging is not verified" }
        BackupPathValidator.requireValidArchivePath(relativePath)
        check(BackupPathValidator.isAssetPath(relativePath)) { "Only backup assets may be opened" }
        val source = resolve(relativePath)
        if (!source.isFile) throw IOException("A verified staged asset is unavailable")
        return FileInputStream(source)
    }

    override fun verified() {
        check(!discarded.get())
        index.seal()
        verified.set(true)
    }

    override fun abort() {
        discard()
    }

    override fun discard(): Boolean {
        if (discarded.get()) return true
        index.close()
        val deleted = deleteTreeSafely(workspaceRoot, operationRoot)
        if (deleted) discarded.set(true)
        return deleted
    }

    private fun resolve(relativePath: String): File {
        val canonicalRoot = operationRoot.canonicalFile
        val candidate = File(canonicalRoot, relativePath).canonicalFile
        if (candidate == canonicalRoot || !candidate.toPath().startsWith(canonicalRoot.toPath())) {
            throw IOException("Unsafe restore staging path")
        }
        return candidate
    }

    private fun openStagedFile(relativePath: String, duplicateMessage: String): OutputStream {
        val destination = resolve(relativePath)
        val parent = destination.parentFile ?: throw IOException("Invalid staging destination")
        if ((!parent.exists() && !parent.mkdirs()) || !parent.isDirectory) {
            throw IOException("Unable to create a private restore staging directory")
        }
        makeOwnerOnly(parent)
        if (destination.exists()) throw IOException(duplicateMessage)
        return FileOutputStream(destination).also { makeOwnerOnly(destination) }
    }

    private fun requireOcrLineChunkPath(relativePath: String) {
        BackupPathValidator.requireValidArchivePath(relativePath)
        check(OCR_LINE_CHUNK_PATH.matches(relativePath)) { "Only OCR line chunks may use metadata staging" }
    }

    private companion object {
        const val INDEX_FILE_NAME = "restore-index.sqlite"
    }
}

/** Operation-owned disk index used when reopening an archive only to verify it. */
internal class TemporaryBackupVerificationStaging private constructor(
    val sink: BackupIndexedStagingSink,
    private val area: RestoreStagingArea,
    private val temporaryRoot: File,
) : Closeable {
    override fun close() {
        var areaDeleted = false
        repeat(CLEANUP_ATTEMPTS) {
            if (!areaDeleted) areaDeleted = area.discard()
        }
        var rootDeleted = !temporaryRoot.exists()
        repeat(CLEANUP_ATTEMPTS) {
            if (!rootDeleted) rootDeleted = temporaryRoot.delete()
        }
        if (!areaDeleted || !rootDeleted) {
            throw IOException("The temporary backup verification workspace could not be removed.")
        }
    }

    companion object {
        private const val CLEANUP_ATTEMPTS = 3

        fun create(scratchDirectory: File): TemporaryBackupVerificationStaging {
            if ((!scratchDirectory.exists() && !scratchDirectory.mkdirs()) || !scratchDirectory.isDirectory) {
                throw IOException("The private backup verification workspace is unavailable.")
            }
            makeOwnerOnly(scratchDirectory)
            val marker = File.createTempFile(
                ".rme-backup-verification-",
                ".tmp",
                scratchDirectory,
            )
            if (!marker.delete() || !marker.mkdir()) {
                val failure = IOException("The private backup verification workspace is unavailable.")
                if (!deleteTreeSafely(scratchDirectory, marker)) {
                    failure.addSuppressed(
                        IOException("The failed backup verification workspace could not be removed."),
                    )
                }
                throw failure
            }
            makeOwnerOnly(marker)
            return try {
                val area = FileRestoreStagingWorkspace(marker, retainPayloads = false)
                    .create(UUID.randomUUID().toString())
                TemporaryBackupVerificationStaging(
                    sink = area as BackupIndexedStagingSink,
                    area = area,
                    temporaryRoot = marker,
                )
            } catch (failure: Throwable) {
                if (!deleteTreeSafely(scratchDirectory, marker)) {
                    failure.addSuppressed(
                        IOException("The failed backup verification workspace could not be removed."),
                    )
                }
                throw failure
            }
        }
    }
}

private object DiscardingOutputStream : OutputStream() {
    override fun write(value: Int) = Unit

    override fun write(buffer: ByteArray, offset: Int, length: Int) = Unit
}

private val OCR_LINE_CHUNK_PATH = Regex("$RME_BACKUP_OCR_LINES_DIRECTORY/[0-9]{6,}\\.jsonl")

private fun makeOwnerOnly(file: File) {
    file.setReadable(false, false)
    file.setWritable(false, false)
    file.setExecutable(false, false)
    file.setReadable(true, true)
    file.setWritable(true, true)
    if (file.isDirectory) file.setExecutable(true, true)
}

private fun deleteTreeSafely(root: File, target: File): Boolean = try {
    val canonicalRoot = root.canonicalFile
    val canonicalTarget = target.canonicalFile
    if (canonicalTarget == canonicalRoot || !canonicalTarget.toPath().startsWith(canonicalRoot.toPath())) {
        false
    } else {
        !canonicalTarget.exists() || canonicalTarget.deleteRecursively()
    }
} catch (_: IOException) {
    false
} catch (_: SecurityException) {
    false
}
