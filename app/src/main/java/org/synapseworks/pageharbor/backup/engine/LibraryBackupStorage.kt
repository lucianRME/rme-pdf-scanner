package org.synapseworks.pageharbor.backup.engine

import android.content.Context
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Collections

interface LibraryBackupWorkspace {
    fun createTemporaryArchive(backupId: String): File

    fun scratchDirectoryFor(file: File): File

    fun availableBytes(): Long?

    fun deleteTemporaryArchive(file: File): Boolean
}

class FileLibraryBackupWorkspace(
    private val root: File,
) : LibraryBackupWorkspace {
    init {
        recoverStaleBackupSessions(root)
    }

    override fun createTemporaryArchive(backupId: String): File {
        if (!SAFE_BACKUP_ID.matches(backupId)) throw IOException("The backup ID is invalid.")
        requirePrivateDirectory(root, "The private backup workspace is unavailable.")
        recoverStaleBackupSessions(root)
        val session = File.createTempFile("$SESSION_PREFIX$backupId-", SESSION_SUFFIX, root)
        if (!session.delete() || !session.mkdir()) {
            throw IOException("The private backup session is unavailable.")
        }
        makeOwnerOnlyDirectory(session)
        ActiveBackupSessions.register(session)
        try {
            val scratch = File(session, SCRATCH_DIRECTORY)
            requirePrivateDirectory(scratch, "The private backup scratch directory is unavailable.")
            val archive = File(session, ARCHIVE_FILE)
            if (!archive.createNewFile()) throw IOException("The private backup archive is unavailable.")
            makeOwnerOnlyFile(archive)
            return archive
        } catch (failure: Throwable) {
            ActiveBackupSessions.unregister(session)
            if (!deleteDirectChild(root, session)) {
                failure.addSuppressed(IOException("The failed private backup session could not be removed."))
            }
            throw failure
        }
    }

    override fun scratchDirectoryFor(file: File): File {
        val session = requireSessionForArchive(root, file)
        val scratch = File(session, SCRATCH_DIRECTORY)
        requirePrivateDirectory(scratch, "The private backup scratch directory is unavailable.")
        return scratch
    }

    override fun availableBytes(): Long? {
        val storageRoot = when {
            root.exists() -> root
            root.parentFile != null -> root.parentFile
            else -> return null
        }
        val bytes = storageRoot.usableSpace
        return bytes.takeIf { it > 0L }
    }

    override fun deleteTemporaryArchive(file: File): Boolean {
        val session = try {
            requireSessionForArchive(root, file)
        } catch (_: IOException) {
            return false
        } catch (_: SecurityException) {
            return false
        }
        repeat(CLEANUP_ATTEMPTS) {
            if (deleteDirectChild(root, session)) {
                ActiveBackupSessions.unregister(session)
                return true
            }
        }
        return false
    }

    private companion object {
        val SAFE_BACKUP_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}

/** Removes only recognized, direct-child backup sessions left by a dead process. */
internal fun recoverStaleBackupSessions(root: File) {
    if (!root.exists()) return
    if (!root.isDirectory) throw IOException("The private backup workspace is unavailable.")
    val children = root.listFiles() ?: throw IOException("The private backup workspace cannot be inspected.")
    children.forEach { child ->
        val recognizedSession = SESSION_NAME.matches(child.name) && child.isDirectory &&
            !Files.isSymbolicLink(child.toPath())
        val recognizedLegacyArchive = LEGACY_ARCHIVE_NAME.matches(child.name) && child.isFile &&
            !Files.isSymbolicLink(child.toPath())
        if ((!recognizedSession && !recognizedLegacyArchive) || ActiveBackupSessions.contains(child)) {
            return@forEach
        }
        var deleted = false
        repeat(CLEANUP_ATTEMPTS) {
            if (!deleted) deleted = deleteDirectChild(root, child)
        }
        if (!deleted) throw IOException("A stale private backup session could not be removed.")
    }
}

private fun requireSessionForArchive(root: File, archive: File): File {
    val canonicalRoot = root.canonicalFile
    val canonicalArchive = archive.canonicalFile
    val session = canonicalArchive.parentFile
        ?: throw IOException("The private backup archive is outside its workspace.")
    if (canonicalArchive.name != ARCHIVE_FILE || session.parentFile != canonicalRoot ||
        !SESSION_NAME.matches(session.name) || Files.isSymbolicLink(session.toPath())
    ) {
        throw IOException("The private backup archive is outside its workspace.")
    }
    return session
}

private fun deleteDirectChild(parent: File, child: File): Boolean = try {
    val canonicalParent = parent.canonicalFile
    val canonicalChild = child.canonicalFile
    canonicalChild.parentFile == canonicalParent &&
        (!canonicalChild.exists() || canonicalChild.deleteRecursively())
} catch (_: IOException) {
    false
} catch (_: SecurityException) {
    false
}

private fun requirePrivateDirectory(directory: File, message: String) {
    if ((!directory.exists() && !directory.mkdirs()) || !directory.isDirectory) throw IOException(message)
    makeOwnerOnlyDirectory(directory)
}

private fun makeOwnerOnlyDirectory(directory: File) {
    directory.setReadable(false, false)
    directory.setWritable(false, false)
    directory.setExecutable(false, false)
    directory.setReadable(true, true)
    directory.setWritable(true, true)
    directory.setExecutable(true, true)
}

private fun makeOwnerOnlyFile(file: File) {
    file.setReadable(false, false)
    file.setWritable(false, false)
    file.setExecutable(false, false)
    file.setReadable(true, true)
    file.setWritable(true, true)
}

private object ActiveBackupSessions {
    private val paths = Collections.synchronizedSet(mutableSetOf<String>())

    fun register(session: File) {
        paths += session.canonicalPath
    }

    fun unregister(session: File) {
        runCatching { paths -= session.canonicalPath }
    }

    fun contains(session: File): Boolean = runCatching { session.canonicalPath in paths }.getOrDefault(true)
}

private const val SESSION_PREFIX = ".rme-backup-session-"
private const val SESSION_SUFFIX = ".tmp"
private const val ARCHIVE_FILE = "archive.zip"
private const val SCRATCH_DIRECTORY = "scratch"
private const val CLEANUP_ATTEMPTS = 3
private val SESSION_NAME = Regex(
    "\\.rme-backup-session-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}-[0-9]+\\.tmp",
)
private val LEGACY_ARCHIVE_NAME = Regex(
    "\\.rme-backup-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}-[0-9]+\\.zip",
)

class AndroidLibraryBackupWorkspace(
    context: Context,
) : LibraryBackupWorkspace by FileLibraryBackupWorkspace(
    File(context.applicationContext.cacheDir, PRIVATE_BACKUP_DIRECTORY),
) {
    private companion object {
        const val PRIVATE_BACKUP_DIRECTORY = "verified-library-backups"
    }
}

object LibraryBackupStorageEstimator {
    fun estimate(
        snapshot: LibraryBackupSnapshot,
        availableTemporaryBytes: Long?,
    ): LibraryBackupStorageEstimate {
        val stats = snapshot.boundedStats
        val contentBytes = stats.summary.contentByteLength
        val entryCount = stats.zipEntryCount
        val ledgerBytes = saturatingMultiply(entryCount, LEDGER_BYTES_PER_ENTRY)
        val metadataBytes = saturatingAdd(
            saturatingAdd(BASE_METADATA_BYTES, stats.serializedMetadataBytes),
            ledgerBytes,
        )
        val zipOverhead = saturatingAdd(
            MINIMUM_ZIP_OVERHEAD_BYTES,
            saturatingAdd(
                saturatingMultiply(entryCount, ZIP_OVERHEAD_PER_ENTRY_BYTES),
                contentBytes / 50L,
            ),
        )
        val archiveUpperBound = saturatingAdd(saturatingAdd(contentBytes, metadataBytes), zipOverhead)
        val checksumScratch = saturatingMultiply(entryCount, CHECKSUM_SORT_BYTES_PER_ENTRY)
        val requiredAdditionalBytes = saturatingAdd(archiveUpperBound, checksumScratch)
        return LibraryBackupStorageEstimate(
            contentBytes = contentBytes,
            metadataUpperBoundBytes = metadataBytes,
            zipEntryCount = entryCount,
            temporaryArchiveUpperBoundBytes = requiredAdditionalBytes,
            availableTemporaryBytes = availableTemporaryBytes,
            existingSnapshotSpoolBytes = stats.spoolBytes,
        )
    }

    private fun saturatingMultiply(left: Long, right: Long): Long = when {
        left == 0L || right == 0L -> 0L
        left > Long.MAX_VALUE / right -> Long.MAX_VALUE
        else -> left * right
    }

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private const val BASE_METADATA_BYTES = 2L * 1024L * 1024L
    private const val MINIMUM_ZIP_OVERHEAD_BYTES = 1L * 1024L * 1024L
    private const val ZIP_OVERHEAD_PER_ENTRY_BYTES = 512L
    private const val LEDGER_BYTES_PER_ENTRY = 2_200L
    private const val CHECKSUM_SORT_BYTES_PER_ENTRY = 8_192L
}
