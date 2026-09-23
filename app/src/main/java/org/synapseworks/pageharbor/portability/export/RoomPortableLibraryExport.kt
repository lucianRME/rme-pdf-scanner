package org.synapseworks.pageharbor.portability.export

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.synapseworks.pageharbor.document.NormalPdfPage
import org.synapseworks.pageharbor.document.NormalPdfRecompositionResult
import org.synapseworks.pageharbor.document.deleteNormalPdfRecomposition
import org.synapseworks.pageharbor.document.recomposeNormalPdf
import org.synapseworks.pageharbor.document.session.DocumentImageMetadata
import org.synapseworks.pageharbor.document.session.DocumentPageRotation
import org.synapseworks.pageharbor.document.session.createLibraryDocumentResource
import org.synapseworks.pageharbor.image.DocumentFilter
import org.synapseworks.pageharbor.library.LibraryDao
import org.synapseworks.pageharbor.library.LibraryDatabase
import org.synapseworks.pageharbor.library.LibraryDocumentEntity
import org.synapseworks.pageharbor.library.LibraryFileStore
import org.synapseworks.pageharbor.library.LibraryFolderEntity
import org.synapseworks.pageharbor.library.LibraryOperationCoordinator
import org.synapseworks.pageharbor.library.LibraryOperationGate
import org.synapseworks.pageharbor.library.LibraryPageEntity

internal data class PortableExportDocumentRow(
    val rowId: Long,
    val documentId: String,
    val title: String,
    val folderId: String?,
)

/** Small paging boundary that keeps Room out of the pure snapshot-plan tests. */
internal interface PortableExportRecordSource {
    suspend fun libraryRevision(): Long

    suspend fun foldersPage(offset: Int, limit: Int): List<PortableExportFolder>

    suspend fun documentsPage(afterRowId: Long, limit: Int): List<PortableExportDocumentRow>
}

internal class RoomPortableExportRecordSource(
    private val dao: LibraryDao,
) : PortableExportRecordSource {
    override suspend fun libraryRevision(): Long = dao.metadata()?.libraryRevision ?: 0L

    override suspend fun foldersPage(offset: Int, limit: Int): List<PortableExportFolder> =
        dao.foldersPage(offset, limit).map(LibraryFolderEntity::toPortableFolder)

    override suspend fun documentsPage(
        afterRowId: Long,
        limit: Int,
    ): List<PortableExportDocumentRow> = dao.activeDocumentsPage(afterRowId, limit).map {
        document -> document.toPortableRow()
    }
}

internal fun interface PortableExportPlanSource {
    suspend fun capturePlan(): PortableExportPlan
}

/** Reads only ACTIVE documents through the DAO's bounded paging queries. */
internal class PagedPortableExportPlanSource(
    private val records: PortableExportRecordSource,
    private val pageSize: Int = EXPORT_DATABASE_PAGE_SIZE,
) : PortableExportPlanSource {
    init {
        require(pageSize > 0)
    }

    override suspend fun capturePlan(): PortableExportPlan {
        val startingRevision = records.libraryRevision()
        val folders = readFolders()
        val documents = readDocuments()
        val endingRevision = records.libraryRevision()
        if (startingRevision != endingRevision) {
            throw PortableExportException("The local library changed during export preparation.")
        }
        return PortableExportPlan(folders = folders, documents = documents)
    }

    private suspend fun readFolders(): List<PortableExportFolder> {
        val folders = ArrayList<PortableExportFolder>()
        var offset = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = records.foldersPage(offset, pageSize)
            if (page.isEmpty()) break
            folders += page
            offset += page.size
            if (page.size < pageSize) break
        }
        return folders
    }

    private suspend fun readDocuments(): List<PortableExportDocument> {
        val documents = ArrayList<PortableExportDocument>()
        var afterRowId = -1L
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = records.documentsPage(afterRowId, pageSize)
            if (page.isEmpty()) break
            page.forEach { document ->
                documents += PortableExportDocument(
                    id = document.documentId,
                    title = document.title,
                    folderId = document.folderId,
                )
            }
            val nextRowId = page.last().rowId
            if (nextRowId <= afterRowId) {
                throw PortableExportException("The local library returned an invalid export page.")
            }
            afterRowId = nextRowId
            if (page.size < pageSize) break
        }
        return documents
    }
}

/**
 * Produces the current edited document. A retained PDF is copied only while its persisted
 * current-revision marker and integrity metadata still match; every other document is recomposed
 * from its current ordered page records.
 */
internal class RoomPortableExportPdfSource(
    context: Context,
    private val dao: LibraryDao,
    private val fileStore: LibraryFileStore,
) : PortableExportPdfSource {
    private val applicationContext = context.applicationContext

    override suspend fun writePdf(
        documentId: String,
        destination: OutputStream,
    ): PortableExportPdfWriteResult = withContext(Dispatchers.IO) {
        val document = try {
            dao.document(documentId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return@withContext PortableExportPdfWriteResult.SOURCE_UNAVAILABLE
        } ?: return@withContext PortableExportPdfWriteResult.SOURCE_UNAVAILABLE

        val pages = readAllPages(document)
            ?: return@withContext PortableExportPdfWriteResult.SOURCE_UNAVAILABLE
        val directPdf = findExactDirectPdf(documentId)
        if (directPdf != null) {
            return@withContext copyFileToDestination(directPdf, destination)
        }
        writeRecomposedPdf(pages, destination)
    }

    private suspend fun readAllPages(document: LibraryDocumentEntity): List<LibraryPageEntity>? {
        val pages = ArrayList<LibraryPageEntity>()
        var afterPosition = -1
        return try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val page = dao.pagesPage(document.documentId, afterPosition, EXPORT_DATABASE_PAGE_SIZE)
                if (page.isEmpty()) break
                pages += page
                val nextPosition = page.last().position
                if (nextPosition <= afterPosition) return null
                afterPosition = nextPosition
                if (page.size < EXPORT_DATABASE_PAGE_SIZE) break
            }
            pages.takeIf { candidates ->
                candidates.size == document.pageCount &&
                    candidates.isNotEmpty() &&
                    candidates.map(LibraryPageEntity::position) == candidates.indices.toList()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun findExactDirectPdf(documentId: String): File? {
        val source = try {
            dao.sourceAssets(documentId).singleOrNull { asset ->
                asset.role == ORIGINAL_DOCUMENT_ASSET_ROLE &&
                    asset.contentType.equals(PDF_MIME_TYPE, ignoreCase = true) &&
                    asset.matchesCurrentRevision
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return null
        if (source.byteCount <= 0L || !SHA_256.matches(source.sha256)) return null
        val file = fileStore.resolve(source.relativePath)?.takeIf(File::isFile) ?: return null
        if (file.length() != source.byteCount) return null
        if (!hasPdfSignature(file)) return null
        val actualHash = hashFile(file) ?: return null
        return file.takeIf { actualHash.equals(source.sha256, ignoreCase = true) }
    }

    private fun hasPdfSignature(file: File): Boolean = try {
        FileInputStream(file).use { source ->
            val signature = ByteArray(PDF_SIGNATURE.size)
            var offset = 0
            while (offset < signature.size) {
                val count = source.read(signature, offset, signature.size - offset)
                if (count < 0) return false
                if (count > 0) offset += count
            }
            signature.contentEquals(PDF_SIGNATURE)
        }
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private suspend fun hashFile(file: File): String? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { source ->
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = source.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        digest.digest().joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private suspend fun writeRecomposedPdf(
        pageEntities: List<LibraryPageEntity>,
        destination: OutputStream,
    ): PortableExportPdfWriteResult {
        val pages = pageEntities.mapIndexed { index, page ->
            page.toNormalPdfPage(index.toLong())
                ?: return PortableExportPdfWriteResult.SOURCE_UNAVAILABLE
        }
        return when (val recomposed = recomposeNormalPdf(applicationContext, pages)) {
            is NormalPdfRecompositionResult.Ready -> try {
                copyFileToDestination(recomposed.file, destination)
            } finally {
                deleteNormalPdfRecomposition(recomposed.file)
            }
            NormalPdfRecompositionResult.SourceMissing,
            NormalPdfRecompositionResult.SourceTooLarge,
            -> PortableExportPdfWriteResult.SOURCE_UNAVAILABLE
            NormalPdfRecompositionResult.Failed -> PortableExportPdfWriteResult.WRITE_FAILED
        }
    }

    private fun LibraryPageEntity.toNormalPdfPage(runtimeId: Long): NormalPdfPage? {
        val file = fileStore.resolve(relativePath)?.takeIf(File::isFile) ?: return null
        if (file.length() <= 0L || sourceByteCount?.let { it != file.length() } == true) return null
        val rotation = DocumentPageRotation.entries.firstOrNull { it.degrees == rotationDegrees }
            ?: return null
        val filter = runCatching { DocumentFilter.valueOf(filterName) }.getOrNull() ?: return null
        val uri = try {
            FileProvider.getUriForFile(
                applicationContext,
                "${applicationContext.packageName}.fileprovider",
                file,
            )
        } catch (_: IllegalArgumentException) {
            return null
        } catch (_: SecurityException) {
            return null
        }
        val resource = createLibraryDocumentResource(
            reference = uri.toString(),
            path = file.path,
            rootPath = fileStore.root.path,
        ) ?: return null
        return NormalPdfPage(
            pageId = runtimeId,
            source = resource,
            filter = filter,
            rotation = rotation,
            contentType = contentType,
            imageMetadata = DocumentImageMetadata(sourceByteCount, width, height),
        )
    }

    private suspend fun copyFileToDestination(
        file: File,
        destination: OutputStream,
    ): PortableExportPdfWriteResult {
        val source = try {
            FileInputStream(file)
        } catch (_: FileNotFoundException) {
            return PortableExportPdfWriteResult.SOURCE_UNAVAILABLE
        } catch (_: SecurityException) {
            return PortableExportPdfWriteResult.SOURCE_UNAVAILABLE
        }
        return source.use { input ->
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = try {
                    input.read(buffer)
                } catch (_: IOException) {
                    return@use PortableExportPdfWriteResult.SOURCE_UNAVAILABLE
                }
                if (count < 0) break
                if (count > 0) {
                    try {
                        destination.write(buffer, 0, count)
                    } catch (_: IOException) {
                        return@use PortableExportPdfWriteResult.WRITE_FAILED
                    } catch (_: SecurityException) {
                        return@use PortableExportPdfWriteResult.WRITE_FAILED
                    }
                }
            }
            try {
                currentCoroutineContext().ensureActive()
                destination.flush()
                PortableExportPdfWriteResult.WRITTEN
            } catch (_: IOException) {
                PortableExportPdfWriteResult.WRITE_FAILED
            } catch (_: SecurityException) {
                PortableExportPdfWriteResult.WRITE_FAILED
            }
        }
    }
}

/** Pure coordinator used by Android and JVM fakes; the gate remains held through all file writes. */
internal class StablePortableLibraryExportCoordinator(
    private val planSource: PortableExportPlanSource,
    pdfSource: PortableExportPdfSource,
    private val operationGate: LibraryOperationGate = LibraryOperationCoordinator.gate,
) {
    private val engine = PortableLibraryExportEngine(pdfSource)

    suspend fun export(
        destination: PortableExportDestination,
        progress: PortableExportProgressListener = PortableExportProgressListener { _, _, _ -> },
    ): PortableExportResult = operationGate.withStableSnapshot {
        val plan = planSource.capturePlan()
        engine.export(plan, destination, progress)
    }
}

/** Android/Room entry point. The caller supplies only the user-selected SAF tree. */
class AndroidRoomPortableLibraryExportCoordinator internal constructor(
    private val contentResolver: android.content.ContentResolver,
    private val coordinator: StablePortableLibraryExportCoordinator,
) {
    constructor(context: Context) : this(
        contentResolver = context.applicationContext.contentResolver,
        coordinator = context.applicationContext.createStableExportCoordinator(),
    )

    suspend fun export(
        treeUri: Uri,
        progress: PortableExportProgressListener = PortableExportProgressListener { _, _, _ -> },
    ): PortableExportResult = coordinator.export(
        destination = AndroidSafTreeExportDestination(contentResolver, treeUri),
        progress = progress,
    )
}

private fun Context.createStableExportCoordinator(): StablePortableLibraryExportCoordinator {
    val dao = LibraryDatabase.get(this).libraryDao()
    val fileStore = LibraryFileStore(this)
    return StablePortableLibraryExportCoordinator(
        planSource = PagedPortableExportPlanSource(RoomPortableExportRecordSource(dao)),
        pdfSource = RoomPortableExportPdfSource(this, dao, fileStore),
    )
}

private fun LibraryFolderEntity.toPortableFolder() = PortableExportFolder(
    id = folderId,
    name = name,
    parentId = parentFolderId,
)

private fun LibraryDocumentEntity.toPortableRow() = PortableExportDocumentRow(
    rowId = rowId,
    documentId = documentId,
    title = title,
    folderId = folderId,
)

private const val EXPORT_DATABASE_PAGE_SIZE = 256
private const val COPY_BUFFER_SIZE = 64 * 1024
private const val ORIGINAL_DOCUMENT_ASSET_ROLE = "ORIGINAL_DOCUMENT"
private const val PDF_MIME_TYPE = "application/pdf"
private val SHA_256 = Regex("[0-9a-fA-F]{64}")
private val PDF_SIGNATURE = "%PDF-".encodeToByteArray()
