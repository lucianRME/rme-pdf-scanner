package org.synapseworks.pageharbor.library

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.util.UUID
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.synapseworks.pageharbor.document.session.DocumentImageMetadata
import org.synapseworks.pageharbor.document.session.DocumentPage
import org.synapseworks.pageharbor.document.session.DocumentPageId
import org.synapseworks.pageharbor.document.session.DocumentPageRotation
import org.synapseworks.pageharbor.document.session.DocumentSession
import org.synapseworks.pageharbor.document.session.DocumentSourceCategory
import org.synapseworks.pageharbor.document.session.LibraryDocumentReference
import org.synapseworks.pageharbor.document.session.createLibraryDocumentResource
import org.synapseworks.pageharbor.document.session.toAndroidUri
import org.synapseworks.pageharbor.image.DocumentFilter
import org.synapseworks.pageharbor.ocr.OcrResult

data class SavedLibraryDocument(
    val id: String,
    val title: String,
    val folderId: String?,
)

data class OpenedLibraryDocument(
    val session: DocumentSession,
    val summary: LibraryDocumentSummary,
)

class LibraryRepository internal constructor(
    context: Context,
    private val dao: LibraryDao = LibraryDatabase.get(context).libraryDao(),
    private val fileStore: LibraryFileStore = LibraryFileStore(context),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    constructor(context: Context) : this(
        context = context,
        dao = LibraryDatabase.get(context).libraryDao(),
        fileStore = LibraryFileStore(context),
        nowMillis = System::currentTimeMillis,
    )

    private val applicationContext = context.applicationContext
    private val nextRuntimePageId = AtomicLong(Long.MIN_VALUE)

    fun observeDocuments(
        folderId: String?,
        sortOrder: LibrarySortOrder,
    ): Flow<List<LibraryDocumentSummary>> = when (sortOrder) {
        LibrarySortOrder.MODIFIED_DESC -> dao.observeDocumentsByModified(folderId)
        LibrarySortOrder.CREATED_DESC -> dao.observeDocumentsByCreated(folderId)
        LibrarySortOrder.TITLE_ASC -> dao.observeDocumentsByTitle(folderId)
    }.map { rows -> rows.map(LibraryDocumentListingRow::toSummary) }

    fun observeFolders(): Flow<List<LibraryFolder>> = dao.observeFolders().map { rows ->
        rows.map { row -> LibraryFolder(row.folderId, row.name, row.documentCount) }
    }

    fun observeSearch(query: String): Flow<List<LibraryDocumentSummary>>? {
        val raw = query.trim()
        val fts = raw.toFtsPrefixQuery() ?: return null
        return dao.observeSearch(fts, raw).map { rows -> rows.map(LibrarySearchRow::toSummary) }
    }

    suspend fun saveSession(
        session: DocumentSession,
        title: String,
        folderId: String? = session.libraryDocument?.folderId,
        ocrResult: OcrResult? = null,
    ): LibraryResult<SavedLibraryDocument> {
        if (session.pages.isEmpty()) return LibraryResult.Failure(LibraryError.EMPTY_DOCUMENT)
        if (session.pages.size > org.synapseworks.pageharbor.MAX_DOCUMENT_PAGES) {
            return LibraryResult.Failure(LibraryError.PAGE_LIMIT_EXCEEDED)
        }
        val normalizedTitle = normalizeLibraryTitle(title)
        if (normalizedTitle.isBlank()) return LibraryResult.Failure(LibraryError.TITLE_REQUIRED)
        if (folderId != null && runDatabase { dao.folder(folderId) } == null) {
            return LibraryResult.Failure(LibraryError.FOLDER_NOT_FOUND)
        }

        val existingId = session.libraryDocument?.documentId
        val existingPages = existingId?.let { id -> runDatabase { dao.pages(id) } }
            ?.associateBy(LibraryPageEntity::pageId)
            .orEmpty()
        val sources = session.pages.mapIndexed { index, page ->
            val recognized = ocrResult?.pages?.firstOrNull { it.pageIndex == index }
            val stored = page.persistentId?.let(existingPages::get)
            LibraryPageSource(
                persistentId = page.persistentId,
                contentType = page.contentType,
                sourceCategory = page.sourceCategory.name,
                imageMetadata = page.imageMetadata,
                rotation = page.rotation,
                filter = page.filter,
                ocrText = if (ocrResult == null) stored?.ocrText else recognized?.text.orEmpty(),
                ocrError = if (ocrResult == null) stored?.ocrError else recognized?.error?.name,
                openStream = {
                    applicationContext.contentResolver.openInputStream(page.source.toAndroidUri())
                        ?: throw FileNotFoundException()
                },
            )
        }
        return saveSources(
            existingDocumentId = existingId,
            title = normalizedTitle,
            folderId = folderId,
            sources = sources,
        )
    }

    suspend fun openDocument(documentId: String): LibraryResult<OpenedLibraryDocument> {
        val entity = runDatabase { dao.document(documentId) }
            ?: return LibraryResult.Failure(LibraryError.DOCUMENT_NOT_FOUND)
        val pageEntities = runDatabase { dao.pages(documentId) }
            ?: return LibraryResult.Failure(LibraryError.DATABASE_UNAVAILABLE)
        if (pageEntities.isEmpty() || pageEntities.size != entity.pageCount) {
            return LibraryResult.Failure(LibraryError.CORRUPTED_RECORD)
        }
        val pages = mutableListOf<DocumentPage>()
        pageEntities.forEach { page ->
            val file = fileStore.resolve(page.relativePath)
            if (file == null || !file.isFile) {
                return LibraryResult.Failure(LibraryError.SOURCE_MISSING)
            }
            val uri = try {
                FileProvider.getUriForFile(
                    applicationContext,
                    "${applicationContext.packageName}.fileprovider",
                    file,
                )
            } catch (_: IllegalArgumentException) {
                return LibraryResult.Failure(LibraryError.CORRUPTED_RECORD)
            }
            val resource = createLibraryDocumentResource(
                reference = uri.toString(),
                path = file.path,
                rootPath = fileStore.root.path,
            ) ?: return LibraryResult.Failure(LibraryError.CORRUPTED_RECORD)
            val rotation = DocumentPageRotation.entries.firstOrNull {
                it.degrees == page.rotationDegrees
            } ?: return LibraryResult.Failure(LibraryError.CORRUPTED_RECORD)
            val filter = runCatching { DocumentFilter.valueOf(page.filterName) }.getOrNull()
                ?: return LibraryResult.Failure(LibraryError.CORRUPTED_RECORD)
            pages += DocumentPage(
                id = DocumentPageId(nextRuntimePageId.getAndIncrement()),
                source = resource,
                sourceCategory = DocumentSourceCategory.LIBRARY,
                contentType = page.contentType,
                imageMetadata = DocumentImageMetadata(
                    sourceByteCount = page.sourceByteCount,
                    width = page.width,
                    height = page.height,
                ),
                rotation = rotation,
                filter = filter,
                persistentId = page.pageId,
            )
        }
        val summary = entity.toSummary(folderName = entity.folderId?.let { id ->
            runDatabase { dao.folder(id) }?.name
        })
        return LibraryResult.Success(
            OpenedLibraryDocument(
                session = DocumentSession(
                    pages = pages,
                    libraryDocument = LibraryDocumentReference(
                        documentId = entity.documentId,
                        title = entity.title,
                        folderId = entity.folderId,
                    ),
                ),
                summary = summary,
            ),
        )
    }

    suspend fun renameDocument(documentId: String, title: String): LibraryResult<Unit> {
        val normalized = normalizeLibraryTitle(title)
        if (normalized.isBlank()) return LibraryResult.Failure(LibraryError.TITLE_REQUIRED)
        return try {
            if (dao.renameDocument(documentId, normalized, nowMillis())) {
                LibraryResult.Success(Unit)
            } else {
                LibraryResult.Failure(LibraryError.DOCUMENT_NOT_FOUND)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            LibraryResult.Failure(LibraryError.DATABASE_UNAVAILABLE)
        }
    }

    suspend fun indexOcr(
        documentId: String,
        pageIds: List<String?>,
        result: OcrResult,
    ): LibraryResult<Unit> {
        if (pageIds.size != result.pages.size || pageIds.any { it == null }) {
            return LibraryResult.Failure(LibraryError.SAVE_REQUIRED)
        }
        val storedPageIds = runDatabase { dao.pages(documentId) }
            ?.map(LibraryPageEntity::pageId)
            ?: return LibraryResult.Failure(LibraryError.DATABASE_UNAVAILABLE)
        if (storedPageIds != pageIds.filterNotNull()) {
            return LibraryResult.Failure(LibraryError.SAVE_REQUIRED)
        }
        val orderedResults = result.pages.sortedBy { it.pageIndex }
        if (orderedResults.map { it.pageIndex } != orderedResults.indices.toList()) {
            return LibraryResult.Failure(LibraryError.CORRUPTED_RECORD)
        }
        val values = orderedResults.mapIndexed { index, page ->
            requireNotNull(pageIds[index]) to (page.text to page.error?.name)
        }
        val status = ocrStatusFor(
            values.map { (_, value) -> value.first to value.second },
        )
        return try {
            if (dao.replaceOcr(documentId, values, status.name, nowMillis())) {
                LibraryResult.Success(Unit)
            } else {
                LibraryResult.Failure(LibraryError.CORRUPTED_RECORD)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            LibraryResult.Failure(LibraryError.DATABASE_UNAVAILABLE)
        }
    }

    suspend fun createFolder(name: String): LibraryResult<LibraryFolder> {
        val normalized = normalizeFolderName(name)
        if (normalized.isBlank()) return LibraryResult.Failure(LibraryError.TITLE_REQUIRED)
        val normalizedKey = normalized.lowercase(Locale.ROOT)
        return try {
            if (dao.folderByNormalizedName(normalizedKey) != null) {
                LibraryResult.Failure(LibraryError.DUPLICATE_FOLDER)
            } else {
                val now = nowMillis()
                val folder = LibraryFolderEntity(
                    folderId = UUID.randomUUID().toString(),
                    name = normalized,
                    normalizedName = normalizedKey,
                    createdAtMillis = now,
                    modifiedAtMillis = now,
                )
                dao.insertFolder(folder)
                LibraryResult.Success(LibraryFolder(folder.folderId, folder.name))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            LibraryResult.Failure(LibraryError.DATABASE_UNAVAILABLE)
        }
    }

    suspend fun renameFolder(folderId: String, name: String): LibraryResult<Unit> {
        val normalized = normalizeFolderName(name)
        if (normalized.isBlank()) return LibraryResult.Failure(LibraryError.TITLE_REQUIRED)
        return try {
            val folder = dao.folder(folderId)
                ?: return LibraryResult.Failure(LibraryError.FOLDER_NOT_FOUND)
            val duplicate = dao.folderByNormalizedName(normalized.lowercase(Locale.ROOT))
            if (duplicate != null && duplicate.folderId != folderId) {
                return LibraryResult.Failure(LibraryError.DUPLICATE_FOLDER)
            }
            dao.updateFolder(
                folder.copy(
                    name = normalized,
                    normalizedName = normalized.lowercase(Locale.ROOT),
                    modifiedAtMillis = nowMillis(),
                ),
            )
            LibraryResult.Success(Unit)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            LibraryResult.Failure(LibraryError.DATABASE_UNAVAILABLE)
        }
    }

    suspend fun deleteFolder(folderId: String): LibraryResult<Unit> = try {
        if (dao.deleteFolder(folderId, nowMillis())) {
            LibraryResult.Success(Unit)
        } else {
            LibraryResult.Failure(LibraryError.FOLDER_NOT_FOUND)
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        LibraryResult.Failure(LibraryError.DATABASE_UNAVAILABLE)
    }

    suspend fun moveDocument(documentId: String, folderId: String?): LibraryResult<Unit> {
        if (folderId != null && runDatabase { dao.folder(folderId) } == null) {
            return LibraryResult.Failure(LibraryError.FOLDER_NOT_FOUND)
        }
        return try {
            if (dao.moveDocument(documentId, folderId, nowMillis()) == 1) {
                LibraryResult.Success(Unit)
            } else {
                LibraryResult.Failure(LibraryError.DOCUMENT_NOT_FOUND)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            LibraryResult.Failure(LibraryError.DATABASE_UNAVAILABLE)
        }
    }

    suspend fun deleteDocument(documentId: String): LibraryResult<Unit> = try {
        if (dao.deleteDocument(documentId) == null) {
            LibraryResult.Failure(LibraryError.DOCUMENT_NOT_FOUND)
        } else {
            fileStore.deleteDocument(documentId)
            LibraryResult.Success(Unit)
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        LibraryResult.Failure(LibraryError.DATABASE_UNAVAILABLE)
    }

    suspend fun mergeDocuments(
        documentIds: List<String>,
        title: String,
    ): LibraryResult<SavedLibraryDocument> {
        if (documentIds.size < 2 || documentIds.distinct().size != documentIds.size) {
            return LibraryResult.Failure(LibraryError.INVALID_SELECTION)
        }
        val sources = mutableListOf<LibraryPageSource>()
        documentIds.forEach { documentId ->
            val document = runDatabase { dao.document(documentId) }
                ?: return LibraryResult.Failure(LibraryError.DOCUMENT_NOT_FOUND)
            val pages = runDatabase { dao.pages(documentId) }
                ?: return LibraryResult.Failure(LibraryError.DATABASE_UNAVAILABLE)
            if (pages.size != document.pageCount) {
                return LibraryResult.Failure(LibraryError.CORRUPTED_RECORD)
            }
            sources += pages.map { it.toSource() }
        }
        if (sources.size > org.synapseworks.pageharbor.MAX_DOCUMENT_PAGES) {
            return LibraryResult.Failure(LibraryError.PAGE_LIMIT_EXCEEDED)
        }
        return saveSources(null, normalizeLibraryTitle(title), null, sources)
    }

    suspend fun extractPages(
        documentId: String,
        pageIds: Set<String>,
        title: String,
        removeFromOriginal: Boolean,
    ): LibraryResult<SavedLibraryDocument> {
        val document = runDatabase { dao.document(documentId) }
            ?: return LibraryResult.Failure(LibraryError.DOCUMENT_NOT_FOUND)
        val pages = runDatabase { dao.pages(documentId) }
            ?: return LibraryResult.Failure(LibraryError.DATABASE_UNAVAILABLE)
        if (pageIds.isEmpty() || pages.none { it.pageId in pageIds } ||
            pages.count { it.pageId in pageIds } != pageIds.size
        ) {
            return LibraryResult.Failure(LibraryError.INVALID_SELECTION)
        }
        val selected = pages.filter { it.pageId in pageIds }
        val remaining = pages.filterNot { it.pageId in pageIds }
        if (removeFromOriginal && remaining.isEmpty()) {
            return LibraryResult.Failure(LibraryError.EMPTY_DOCUMENT)
        }
        val extracted = saveSources(
            existingDocumentId = null,
            title = normalizeLibraryTitle(title),
            folderId = document.folderId,
            sources = selected.map { it.toSource() },
        )
        if (extracted !is LibraryResult.Success || !removeFromOriginal) return extracted

        val originalUpdate = saveSources(
            existingDocumentId = document.documentId,
            title = document.title,
            folderId = document.folderId,
            sources = remaining.map { it.toSource(preservePersistentId = true) },
            createdAtOverride = document.createdAtMillis,
        )
        if (originalUpdate is LibraryResult.Failure) {
            deleteDocument(extracted.value.id)
            return originalUpdate
        }
        return extracted
    }

    fun thumbnailUri(relativePath: String?): Uri? {
        val file = relativePath?.let(fileStore::resolve)
        if (file == null || !file.isFile) return null
        return try {
            FileProvider.getUriForFile(
                applicationContext,
                "${applicationContext.packageName}.fileprovider",
                file,
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private suspend fun saveSources(
        existingDocumentId: String?,
        title: String,
        folderId: String?,
        sources: List<LibraryPageSource>,
        createdAtOverride: Long? = null,
    ): LibraryResult<SavedLibraryDocument> {
        if (title.isBlank()) return LibraryResult.Failure(LibraryError.TITLE_REQUIRED)
        if (sources.isEmpty()) return LibraryResult.Failure(LibraryError.EMPTY_DOCUMENT)
        if (sources.size > org.synapseworks.pageharbor.MAX_DOCUMENT_PAGES) {
            return LibraryResult.Failure(LibraryError.PAGE_LIMIT_EXCEEDED)
        }
        val documentId = existingDocumentId ?: UUID.randomUUID().toString()
        val existing = existingDocumentId?.let { runDatabase { dao.document(it) } }
        if (existingDocumentId != null && existing == null) {
            return LibraryResult.Failure(LibraryError.DOCUMENT_NOT_FOUND)
        }
        val prepared = when (val result = fileStore.prepareRevision(documentId, sources)) {
            is LibraryResult.Success -> result
            is LibraryResult.Failure -> return result
        }
        val now = nowMillis()
        val pages = prepared.value.pages.map { page ->
            LibraryPageEntity(
                pageId = page.pageId,
                documentId = documentId,
                position = page.position,
                relativePath = page.relativePath,
                contentType = page.contentType,
                sourceCategory = page.sourceCategory,
                width = page.imageMetadata.width,
                height = page.imageMetadata.height,
                sourceByteCount = page.imageMetadata.sourceByteCount,
                rotationDegrees = page.rotation.degrees,
                filterName = page.filter.name,
                ocrText = page.ocrText,
                ocrError = page.ocrError,
            )
        }
        val document = LibraryDocumentEntity(
            rowId = existing?.rowId ?: 0,
            documentId = documentId,
            title = title,
            createdAtMillis = existing?.createdAtMillis ?: createdAtOverride ?: now,
            modifiedAtMillis = now,
            pageCount = pages.size,
            folderId = folderId,
            thumbnailRelativePath = prepared.value.thumbnailRelativePath,
            ocrStatus = ocrStatusFor(pages.map { it.ocrText to it.ocrError }).name,
        )
        try {
            dao.replaceDocument(
                document = document,
                pages = pages,
                ocrText = pages.joinToString("\n\n") { it.ocrText.orEmpty() },
            )
        } catch (error: CancellationException) {
            fileStore.discardRevision(prepared.value.revisionDirectory)
            throw error
        } catch (_: Exception) {
            fileStore.discardRevision(prepared.value.revisionDirectory)
            return LibraryResult.Failure(LibraryError.DATABASE_UNAVAILABLE)
        }
        return LibraryResult.Success(
            SavedLibraryDocument(documentId, title, folderId),
            prepared.value.warning,
        )
    }

    suspend fun cleanupDocumentRevisions(documentId: String) {
        val pages = runDatabase { dao.pages(documentId) } ?: return
        val keepDirectory = pages.firstOrNull()?.relativePath
            ?.let(fileStore::resolve)
            ?.parentFile
            ?: return
        fileStore.cleanupOtherRevisions(documentId, keepDirectory)
    }

    private fun LibraryPageEntity.toSource(
        preservePersistentId: Boolean = false,
    ): LibraryPageSource {
        val file = fileStore.resolve(relativePath)
        return LibraryPageSource(
            persistentId = pageId.takeIf { preservePersistentId },
            contentType = contentType,
            sourceCategory = sourceCategory,
            imageMetadata = DocumentImageMetadata(sourceByteCount, width, height),
            rotation = DocumentPageRotation.entries.firstOrNull { it.degrees == rotationDegrees }
                ?: DocumentPageRotation.DEGREES_0,
            filter = runCatching { DocumentFilter.valueOf(filterName) }.getOrDefault(DocumentFilter.ORIGINAL),
            ocrText = ocrText,
            ocrError = ocrError,
            openStream = { FileInputStream(file ?: throw FileNotFoundException()) },
        )
    }

    private suspend fun <T> runDatabase(block: suspend () -> T): T? = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }
}

private fun LibraryDocumentListingRow.toSummary(): LibraryDocumentSummary = LibraryDocumentSummary(
    id = documentId,
    title = title,
    createdAtMillis = createdAtMillis,
    modifiedAtMillis = modifiedAtMillis,
    pageCount = pageCount,
    folderId = folderId,
    folderName = folderName,
    thumbnailRelativePath = thumbnailRelativePath,
    ocrStatus = runCatching { LibraryOcrStatus.valueOf(ocrStatus) }
        .getOrDefault(LibraryOcrStatus.NOT_INDEXED),
)

private fun LibrarySearchRow.toSummary(): LibraryDocumentSummary = LibraryDocumentSummary(
    id = documentId,
    title = title,
    createdAtMillis = createdAtMillis,
    modifiedAtMillis = modifiedAtMillis,
    pageCount = pageCount,
    folderId = folderId,
    folderName = folderName,
    thumbnailRelativePath = thumbnailRelativePath,
    ocrStatus = runCatching { LibraryOcrStatus.valueOf(ocrStatus) }
        .getOrDefault(LibraryOcrStatus.NOT_INDEXED),
    searchMatch = if (titleMatch == 1) LibrarySearchMatch.TITLE else LibrarySearchMatch.OCR,
    searchSnippet = matchSnippet?.trim()?.takeIf(String::isNotBlank),
)

private fun LibraryDocumentEntity.toSummary(folderName: String?): LibraryDocumentSummary =
    LibraryDocumentSummary(
        id = documentId,
        title = title,
        createdAtMillis = createdAtMillis,
        modifiedAtMillis = modifiedAtMillis,
        pageCount = pageCount,
        folderId = folderId,
        folderName = folderName,
        thumbnailRelativePath = thumbnailRelativePath,
        ocrStatus = runCatching { LibraryOcrStatus.valueOf(ocrStatus) }
            .getOrDefault(LibraryOcrStatus.NOT_INDEXED),
    )

private fun ocrStatusFor(values: List<Pair<String?, String?>>): LibraryOcrStatus {
    if (values.all { (text, error) -> text == null && error == null }) {
        return LibraryOcrStatus.NOT_INDEXED
    }
    if (values.all { (_, error) -> error != null }) return LibraryOcrStatus.FAILED
    if (values.any { (_, error) -> error != null }) return LibraryOcrStatus.PARTIAL
    return LibraryOcrStatus.INDEXED
}
