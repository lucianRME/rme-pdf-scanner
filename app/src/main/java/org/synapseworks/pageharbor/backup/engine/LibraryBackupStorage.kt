package org.synapseworks.pageharbor.backup.engine

import android.content.Context
import java.io.File
import java.io.IOException

interface LibraryBackupWorkspace {
    fun createTemporaryArchive(backupId: String): File

    fun availableBytes(): Long?

    fun deleteTemporaryArchive(file: File): Boolean
}

class FileLibraryBackupWorkspace(
    private val root: File,
) : LibraryBackupWorkspace {
    override fun createTemporaryArchive(backupId: String): File {
        if (!SAFE_BACKUP_ID.matches(backupId)) throw IOException("The backup ID is invalid.")
        if ((!root.exists() && !root.mkdirs()) || !root.isDirectory) {
            throw IOException("The private backup workspace is unavailable.")
        }
        val file = File.createTempFile(".rme-backup-$backupId-", ".zip", root)
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setReadable(true, true)
        file.setWritable(true, true)
        return file
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
        val safe = try {
            val canonicalRoot = root.canonicalFile.toPath()
            val canonicalFile = file.canonicalFile.toPath()
            canonicalFile.parent == canonicalRoot
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
        return safe && (!file.exists() || file.delete())
    }

    private companion object {
        val SAFE_BACKUP_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}

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
        var contentBytes = 0L
        snapshot.pageRecords.forEach { contentBytes = saturatingAdd(contentBytes, it.byteLength) }
        snapshot.sourceAssetRecords.forEach { contentBytes = saturatingAdd(contentBytes, it.byteLength) }

        var metadataBytes = BASE_METADATA_BYTES
        metadataBytes = saturatingAdd(metadataBytes, snapshot.folderRecords.size.toLong() * FOLDER_RECORD_BYTES)
        metadataBytes = saturatingAdd(metadataBytes, snapshot.documentRecords.size.toLong() * DOCUMENT_RECORD_BYTES)
        metadataBytes = saturatingAdd(metadataBytes, snapshot.pageRecords.size.toLong() * PAGE_RECORD_BYTES)
        metadataBytes = saturatingAdd(
            metadataBytes,
            snapshot.sourceAssetRecords.size.toLong() * SOURCE_RECORD_BYTES,
        )
        snapshot.folderRecords.forEach { metadataBytes = addStringUpperBound(metadataBytes, it.name) }
        snapshot.documentRecords.forEach { metadataBytes = addStringUpperBound(metadataBytes, it.title) }
        snapshot.pageRecords.forEach { page ->
            page.ocrText?.let { metadataBytes = addStringUpperBound(metadataBytes, it) }
            page.ocrError?.let { metadataBytes = addStringUpperBound(metadataBytes, it) }
        }

        val entryCount = 6L + snapshot.pageRecords.size + snapshot.sourceAssetRecords.size
        val zipOverhead = saturatingAdd(
            MINIMUM_ZIP_OVERHEAD_BYTES,
            saturatingAdd(
                saturatingMultiply(entryCount, ZIP_OVERHEAD_PER_ENTRY_BYTES),
                contentBytes / 50L,
            ),
        )
        val archiveUpperBound = saturatingAdd(saturatingAdd(contentBytes, metadataBytes), zipOverhead)
        return LibraryBackupStorageEstimate(
            contentBytes = contentBytes,
            metadataUpperBoundBytes = metadataBytes,
            zipEntryCount = entryCount,
            temporaryArchiveUpperBoundBytes = archiveUpperBound,
            availableTemporaryBytes = availableTemporaryBytes,
        )
    }

    private fun addStringUpperBound(total: Long, value: String): Long =
        saturatingAdd(total, saturatingMultiply(value.length.toLong(), MAX_UTF8_BYTES_PER_UTF16_CODE_UNIT))

    private fun saturatingMultiply(left: Long, right: Long): Long = when {
        left == 0L || right == 0L -> 0L
        left > Long.MAX_VALUE / right -> Long.MAX_VALUE
        else -> left * right
    }

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private const val BASE_METADATA_BYTES = 2L * 1024L * 1024L
    private const val FOLDER_RECORD_BYTES = 512L
    private const val DOCUMENT_RECORD_BYTES = 1_024L
    private const val PAGE_RECORD_BYTES = 1_024L
    private const val SOURCE_RECORD_BYTES = 768L
    private const val MINIMUM_ZIP_OVERHEAD_BYTES = 1L * 1024L * 1024L
    private const val ZIP_OVERHEAD_PER_ENTRY_BYTES = 512L
    private const val MAX_UTF8_BYTES_PER_UTF16_CODE_UNIT = 3L
}
