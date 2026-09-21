package org.synapseworks.pageharbor.backup.engine

import android.graphics.BitmapFactory
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.synapseworks.pageharbor.backup.format.BackupAssetStreamOpener
import org.synapseworks.pageharbor.backup.format.BackupDocumentRecord
import org.synapseworks.pageharbor.backup.format.BackupFolderRecord
import org.synapseworks.pageharbor.backup.format.BackupPageRecord
import org.synapseworks.pageharbor.backup.format.BackupSourceAssetRecord
import org.synapseworks.pageharbor.library.LibraryDao
import org.synapseworks.pageharbor.library.LibraryDocumentEntity
import org.synapseworks.pageharbor.library.LibraryFileStore
import org.synapseworks.pageharbor.library.LibraryPageEntity
import org.synapseworks.pageharbor.library.LibrarySourceAssetEntity

/** Maps the active Room v2 library to the portable format while its operation gate is held. */
internal class RoomLibraryBackupSnapshotSource(
    private val dao: LibraryDao,
    private val fileStore: LibraryFileStore,
) : LibraryBackupSnapshotSource {
    override suspend fun capture(): LibraryBackupSnapshot = try {
        captureChecked()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: LibraryBackupSnapshotException) {
        throw failure
    } catch (exception: Exception) {
        throw LibraryBackupSnapshotException(
            LibraryBackupSnapshotFailure.DATABASE_UNAVAILABLE,
            "The local library snapshot could not be read.",
            exception,
        )
    }

    private suspend fun captureChecked(): LibraryBackupSnapshot {
        val startingRevision = dao.metadata()?.libraryRevision ?: 0L
        val folders = readAllFolders()
        val documents = ArrayList<BackupDocumentRecord>()
        val pages = ArrayList<BackupPageRecord>()
        val sourceAssets = ArrayList<BackupSourceAssetRecord>()
        val filesByArchivePath = LinkedHashMap<String, File>()
        var afterRowId = -1L

        while (true) {
            currentCoroutineContext().ensureActive()
            val documentPage = dao.activeDocumentsPage(afterRowId, DATABASE_PAGE_SIZE)
            if (documentPage.isEmpty()) break
            documentPage.forEach { document ->
                currentCoroutineContext().ensureActive()
                val documentPages = readAllPages(document)
                val documentSources = dao.sourceAssets(document.documentId)
                pages += documentPages.map { page ->
                    page.toBackupRecord(filesByArchivePath)
                }
                sourceAssets += documentSources.map { source ->
                    source.toBackupRecord(filesByArchivePath)
                }
                documents += document.toBackupRecord(documentPages.size, documentSources.size)
            }
            afterRowId = documentPage.last().rowId
            if (documentPage.size < DATABASE_PAGE_SIZE) break
        }

        val endingRevision = dao.metadata()?.libraryRevision ?: 0L
        if (startingRevision != endingRevision) {
            snapshotFailure(
                LibraryBackupSnapshotFailure.INCONSISTENT_REVISION,
                "The local library changed while its backup snapshot was captured.",
            )
        }
        return LibraryBackupSnapshot(
            folders = folders,
            documents = documents,
            pages = pages,
            sourceAssets = sourceAssets,
            assetStreams = BackupAssetStreamOpener { archivePath ->
                val file = filesByArchivePath[archivePath]
                    ?.takeIf(File::isFile)
                    ?: throw IOException("A snapshotted library asset is unavailable.")
                FileInputStream(file)
            },
        )
    }

    private suspend fun readAllFolders(): List<BackupFolderRecord> {
        val result = ArrayList<BackupFolderRecord>()
        var offset = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = dao.foldersPage(offset, DATABASE_PAGE_SIZE)
            if (page.isEmpty()) break
            page.forEach { folder ->
                result += BackupFolderRecord(
                    folderId = folder.folderId,
                    name = folder.name,
                    parentFolderId = folder.parentFolderId,
                    createdAtEpochMillis = folder.createdAtMillis,
                    modifiedAtEpochMillis = folder.modifiedAtMillis,
                )
            }
            offset += page.size
            if (page.size < DATABASE_PAGE_SIZE) break
        }
        return result
    }

    private suspend fun readAllPages(document: LibraryDocumentEntity): List<LibraryPageEntity> {
        val result = ArrayList<LibraryPageEntity>()
        var afterPosition = -1
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = dao.pagesPage(document.documentId, afterPosition, DATABASE_PAGE_SIZE)
            if (page.isEmpty()) break
            result += page
            afterPosition = page.last().position
            if (page.size < DATABASE_PAGE_SIZE) break
        }
        if (result.size != document.pageCount) {
            snapshotFailure(
                LibraryBackupSnapshotFailure.INVALID_RECORD,
                "A document page count is inconsistent.",
            )
        }
        return result
    }

    private suspend fun LibraryPageEntity.toBackupRecord(
        filesByArchivePath: MutableMap<String, File>,
    ): BackupPageRecord {
        val file = resolveAsset(relativePath)
        val actualByteLength = file.length()
        if (actualByteLength <= 0L || (sourceByteCount != null && sourceByteCount != actualByteLength)) {
            snapshotFailure(
                LibraryBackupSnapshotFailure.INVALID_RECORD,
                "A page byte count is inconsistent.",
            )
        }
        val dimensions = when {
            width != null && width > 0 && height != null && height > 0 -> width to height
            else -> measureImage(file)
        }
        val hash = when {
            contentSha256 == null -> hashFile(file)
            SHA_256.matches(contentSha256) -> contentSha256
            else -> snapshotFailure(
                LibraryBackupSnapshotFailure.INVALID_RECORD,
                "A page content hash is invalid.",
            )
        }
        val extension = when (contentType) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> snapshotFailure(
                LibraryBackupSnapshotFailure.UNSUPPORTED_ASSET,
                "A page content type is unsupported by Backup Format v1.",
            )
        }
        val archivePath = "documents/$documentId/pages/${position.toString().padStart(6, '0')}-$pageId.$extension"
        if (filesByArchivePath.put(archivePath, file) != null) {
            snapshotFailure(
                LibraryBackupSnapshotFailure.INVALID_RECORD,
                "Two library assets map to the same backup path.",
            )
        }
        return BackupPageRecord(
            pageId = pageId,
            documentId = documentId,
            position = position,
            relativePath = archivePath,
            mimeType = contentType,
            sha256 = hash,
            byteLength = actualByteLength,
            width = dimensions.first,
            height = dimensions.second,
            rotationDegrees = rotationDegrees,
            filterName = filterName,
            ocrText = ocrText,
            ocrError = ocrError,
            sourcePageIndex = null,
        )
    }

    private fun LibrarySourceAssetEntity.toBackupRecord(
        filesByArchivePath: MutableMap<String, File>,
    ): BackupSourceAssetRecord {
        if (contentType != "application/pdf") {
            snapshotFailure(
                LibraryBackupSnapshotFailure.UNSUPPORTED_ASSET,
                "A source asset is unsupported by Backup Format v1.",
            )
        }
        val file = resolveAsset(relativePath)
        if (byteCount <= 0L || file.length() != byteCount || !SHA_256.matches(sha256)) {
            snapshotFailure(
                LibraryBackupSnapshotFailure.INVALID_RECORD,
                "A source asset has inconsistent integrity metadata.",
            )
        }
        val archivePath = "documents/$documentId/sources/$assetId.pdf"
        if (filesByArchivePath.put(archivePath, file) != null) {
            snapshotFailure(
                LibraryBackupSnapshotFailure.INVALID_RECORD,
                "Two library assets map to the same backup path.",
            )
        }
        return BackupSourceAssetRecord(
            sourceId = assetId,
            documentId = documentId,
            role = role,
            relativePath = archivePath,
            mimeType = contentType,
            sha256 = sha256,
            byteLength = byteCount,
            sourceModifiedAtEpochMillis = sourceModifiedAtMillis,
            matchesCurrentRevision = matchesCurrentRevision,
        )
    }

    private fun LibraryDocumentEntity.toBackupRecord(
        actualPageCount: Int,
        actualSourceAssetCount: Int,
    ): BackupDocumentRecord = BackupDocumentRecord(
        documentId = documentId,
        folderId = folderId,
        title = title,
        createdAtEpochMillis = createdAtMillis,
        modifiedAtEpochMillis = modifiedAtMillis,
        contentHashVersion = contentHashVersion ?: DEFAULT_CONTENT_HASH_VERSION,
        contentSha256 = contentSha256,
        pageCount = actualPageCount,
        sourceAssetCount = actualSourceAssetCount,
    )

    private fun resolveAsset(relativePath: String): File =
        fileStore.resolve(relativePath)?.takeIf(File::isFile)
            ?: snapshotFailure(
                LibraryBackupSnapshotFailure.MISSING_ASSET,
                "A library asset required for backup is missing.",
            )

    private fun measureImage(file: File): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            snapshotFailure(
                LibraryBackupSnapshotFailure.INVALID_RECORD,
                "A page image has invalid dimensions.",
            )
        }
        return options.outWidth to options.outHeight
    }

    private suspend fun hashFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { source ->
            val buffer = ByteArray(HASH_BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = source.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    private fun snapshotFailure(
        failure: LibraryBackupSnapshotFailure,
        message: String,
    ): Nothing = throw LibraryBackupSnapshotException(failure, message)

    private companion object {
        const val DATABASE_PAGE_SIZE = 256
        const val HASH_BUFFER_SIZE = 32 * 1024
        const val DEFAULT_CONTENT_HASH_VERSION = 1
        val SHA_256 = Regex("[0-9a-f]{64}")
    }
}
