package org.synapseworks.pageharbor.library

import android.content.Context
import org.synapseworks.pageharbor.image.ManualImageAdjustments
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.util.UUID
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
import org.synapseworks.pageharbor.library.recycle.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
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
import org.synapseworks.pageharbor.library.smartnaming.LocalSmartNamingEngine
import org.synapseworks.pageharbor.library.smartnaming.SmartNameSuggestion
import org.synapseworks.pageharbor.library.smartnaming.SmartNamingInput
import org.synapseworks.pageharbor.library.duplicate.DocumentFingerprintV1
import org.synapseworks.pageharbor.library.duplicate.FingerprintPage
import org.synapseworks.pageharbor.ocr.persistence.toEffectiveOcrRequestOrNull
import org.synapseworks.pageharbor.ocr.persistence.LibraryEffectiveOcrPageProvider
import org.synapseworks.pageharbor.document.searchablepdf.EffectiveOcrPageProvision
import org.synapseworks.pageharbor.ocr.OcrPageLayout
import org.synapseworks.pageharbor.ocr.OcrResult
import org.synapseworks.pageharbor.ocr.OcrScript
import org.synapseworks.pageharbor.ocr.persistence.BundledLatinOcrMappingResult
import org.synapseworks.pageharbor.ocr.persistence.BundledLatinOcrResultMapper

data class SavedLibraryDocument(
    val id: String,
    val title: String,
    val folderId: String?,
)

data class LibraryReviewScan(
    val uri: Uri,
    val metadata: DocumentImageMetadata,
    val rotation: DocumentPageRotation,
    val layout: OcrPageLayout?,
)

data class OpenedLibraryDocument(
    val session: DocumentSession,
    val summary: LibraryDocumentSummary,
)

internal data class DocumentDeletionRecoveryReport(
    val restored: Int,
    val deleted: Int,
    val stillPending: Int,
    val legacyQuarantined: Int = 0,
    val legacyPreservedOwned: Int = 0,
    val legacyPreservedUncertain: Int = 0,
    val filesystemScanUnavailable: Boolean = false,
    val databaseUnavailable: Boolean = false,
)

private data class PreparedDocumentDeletionRecovery(
    val restored: Int,
    val stillPending: Int,
    val cleanup: List<PendingDocumentDeletion>,
    val legacyQuarantined: Int,
    val legacyPreservedOwned: Int,
    val legacyPreservedUncertain: Int,
    val filesystemScanUnavailable: Boolean,
    val databaseUnavailable: Boolean,
)

class LibraryRepository internal constructor(
    context: Context,
    private val dao: LibraryDao = LibraryDatabase.get(context).libraryDao(),
    private val fileStore: LibraryFileStore = LibraryFileStore(context),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val operationGate: LibraryOperationGate = LibraryOperationCoordinator.gate,
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
        LibrarySortOrder.DOCUMENT_DATE_DESC -> dao.observeDocumentsByDocumentDate(folderId)
        LibrarySortOrder.TITLE_ASC -> dao.observeDocumentsByTitle(folderId)
    }.map { rows -> rows.map(LibraryDocumentListingRow::toSummary) }

    fun observeFolders(): Flow<List<LibraryFolder>> = dao.observeFolders().map { rows ->
        rows.map { row ->
            LibraryFolder(
                id = row.folderId,
                name = row.name,
                documentCount = row.documentCount,
                parentFolderId = row.parentFolderId,
            )
        }
    }

    fun observeSearch(query: String): Flow<List<LibraryDocumentSummary>>? {
        val raw = query.trim()
        if (raw.isEmpty()) return null
        val fts = raw.toFtsPrefixQuery()
        return combine(
            if (fts == null) kotlinx.coroutines.flow.flowOf(emptyList()) else dao.observeDocumentSearch(fts, raw, OBSERVED_SEARCH_LIMIT),
            if (fts == null) kotlinx.coroutines.flow.flowOf(emptyList()) else dao.observePageSearch(fts, OBSERVED_SEARCH_LIMIT),
            dao.observeTagSearch(org.synapseworks.pageharbor.library.organization.LibraryTagRules.like(raw),OBSERVED_SEARCH_LIMIT),
        ) { documentRows, pageRows, tagRows ->
            (documentRows + tagRows + pageRows)
                .sortedWith(
                    compareBy<LibrarySearchRow> { row ->
                        when (row.resolvedSearchMatch(raw)) {
                            LibrarySearchMatch.TAG -> 1
                            LibrarySearchMatch.TITLE -> 0
                            LibrarySearchMatch.FOLDER -> 2
                            LibrarySearchMatch.OCR -> 3
                        }
                    }
                        .thenByDescending { it.modifiedAtMillis }
                        .thenBy { it.title.lowercase(Locale.ROOT) }
                        .thenBy { it.pagePosition ?: -1 },
                )
                .distinctBy(LibrarySearchRow::documentId)
                .take(OBSERVED_SEARCH_LIMIT)
                .map { row -> row.toSummary(raw) }
        }
    }

    suspend fun searchHits(
        query: String,
        beforeModifiedAt: Long = Long.MAX_VALUE,
        afterDocumentId: String = "",
        afterPageId: String = "",
        limit: Int = DEFAULT_SEARCH_PAGE_SIZE,
    ): List<LibrarySearchHit> {
        val raw = query.trim()
        if (raw.isEmpty()) return emptyList()
        val fts = raw.toFtsPrefixQuery()
        val boundedLimit = limit.coerceIn(1, MAX_SEARCH_PAGE_SIZE)
        val documentRows = if (fts == null) emptyList() else dao.documentSearchPage(
            fts,
            raw,
            beforeModifiedAt,
            afterDocumentId,
            boundedLimit,
        )
        val pageRows = if (fts == null) emptyList() else dao.pageSearchPage(
            fts,
            beforeModifiedAt,
            afterPageId,
            boundedLimit,
        )
        return rankedSearchHits(raw, documentRows, pageRows, boundedLimit,dao.tagSearchPage(org.synapseworks.pageharbor.library.organization.LibraryTagRules.like(raw),beforeModifiedAt,afterDocumentId,boundedLimit))
    }

    fun observeSearchHits(
        query: String,
        limit: Int = DEFAULT_SEARCH_PAGE_SIZE,
    ): Flow<List<LibrarySearchHit>>? {
        val raw = query.trim()
        if (raw.isEmpty()) return null
        val fts = raw.toFtsPrefixQuery()
        val boundedLimit = limit.coerceIn(1, MAX_SEARCH_PAGE_SIZE)
        return combine(
            if (fts == null) kotlinx.coroutines.flow.flowOf(emptyList()) else dao.observeDocumentSearch(fts, raw, boundedLimit),
            if (fts == null) kotlinx.coroutines.flow.flowOf(emptyList()) else dao.observePageSearch(fts, boundedLimit),
            dao.observeTagSearch(org.synapseworks.pageharbor.library.organization.LibraryTagRules.like(raw),boundedLimit),
        ) { documentRows, pageRows, tagRows ->
            rankedSearchHits(raw, documentRows, pageRows, boundedLimit,tagRows)
        }
    }

    private fun rankedSearchHits(
        rawQuery: String,
        documentRows: List<LibrarySearchRow>,
        pageRows: List<LibrarySearchRow>,
        boundedLimit: Int,
        tagRows: List<LibrarySearchRow> = emptyList(),
    ): List<LibrarySearchHit> = (documentRows + tagRows + pageRows)
        .sortedWith(
            compareBy<LibrarySearchRow> { row ->
                when (row.resolvedSearchMatch(rawQuery)) {
                    LibrarySearchMatch.TAG -> 1
                            LibrarySearchMatch.TITLE -> 0
                    LibrarySearchMatch.FOLDER -> 2
                    LibrarySearchMatch.OCR -> 3
                }
            }
                .thenByDescending { it.modifiedAtMillis }
                .thenBy { it.title.lowercase(Locale.ROOT) }
                .thenBy(LibrarySearchRow::documentId)
                .thenBy { it.pagePosition ?: -1 },
        )
        .distinctBy { row -> Pair(row.documentId, row.pageId) }
        .take(boundedLimit)
        .map { row ->
            LibrarySearchHit(
                documentId = row.documentId,
                documentTitle = row.title,
                pageId = row.pageId,
                currentPagePosition = row.pagePosition,
                matchType = row.resolvedSearchMatch(rawQuery),
                snippet = row.matchSnippet?.trim()?.takeIf(String::isNotBlank),
            )
        }

    suspend fun saveSession(
        session: DocumentSession,
        title: String,
        folderId: String? = session.libraryDocument?.folderId,
        ocrResult: OcrResult? = null,
        ocrScript: OcrScript? = null,
    ): LibraryResult<SavedLibraryDocument> = operationGate.withMutation {
        saveSessionUnlocked(session, title, folderId, ocrResult, ocrScript)
    }

    private suspend fun saveSessionUnlocked(
        session: DocumentSession,
        title: String,
        folderId: String?,
        ocrResult: OcrResult?,
        ocrScript: OcrScript?,
    ): LibraryResult<SavedLibraryDocument> {
        if (session.pages.isEmpty()) return LibraryResult.Failure(LibraryError.EMPTY_DOCUMENT)
        if(!LibrarySourceReaders.canCapture(session))return LibraryResult.Failure(LibraryError.DOCUMENT_NOT_FOUND)
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
                adjustments = page.adjustments,
                annotations = page.annotations,
                paperSize = page.paperSize,
                ocrText = if (ocrResult == null) stored?.ocrText else recognized?.text.orEmpty(),
                ocrError = if (ocrResult == null) stored?.ocrError else recognized?.error?.name,
                openStream = {
                    applicationContext.contentResolver.openInputStream(page.source.toAndroidUri())
                        ?: throw FileNotFoundException()
                },
            )
        }
        val saved = saveSources(
            existingDocumentId = existingId,
            title = normalizedTitle,
            folderId = folderId,
            sources = sources,
            ocrScriptPreference = ocrScript?.stableId,
            sourceAssets = session.originalPdfSources.map { originalPdf ->
                LibrarySourceAssetSource(
                    role = ORIGINAL_DOCUMENT_ASSET_ROLE,
                    contentType = "application/pdf",
                    sourceModifiedAtMillis = null,
                    matchesCurrentRevision = session.canUseDirectPdf &&
                        originalPdf == session.directPdfSource,
                    openStream = {
                        applicationContext.contentResolver.openInputStream(originalPdf.toAndroidUri())
                            ?: throw FileNotFoundException()
                    },
                )
            },
        )
        if (
            existingId == null && ocrResult != null &&
            (ocrScript == null || ocrScript == OcrScript.LATIN) &&
            saved is LibraryResult.Success
        ) {
            promoteFirstSaveOcr(saved.value.id, ocrResult)
        }
        return saved
    }

    /**
     * Promotes the current bundled-Latin result after the first durable page identities exist.
     * The legacy artifact written by the base save remains the lossless fallback unless every
     * page maps and the revision-aware commit succeeds.
     */
    private suspend fun promoteFirstSaveOcr(documentId: String, result: OcrResult) {
        val snapshots = runDatabase { dao.ocrPageSnapshots(documentId) } ?: return
        val mapped = BundledLatinOcrResultMapper.map(
            result = result,
            orderedSnapshots = snapshots,
            recognizedAtMillis = nowMillis(),
        )
        if (mapped !is BundledLatinOcrMappingResult.Success) return
        runDatabase {
            dao.commitOcrArtifacts(
                documentId = documentId,
                outcomes = mapped.outcomes,
                modifiedAt = nowMillis(),
            )
        }
    }

    suspend fun openDocument(documentId:String):LibraryResult<OpenedLibraryDocument> = operationGate.withStableSnapshot { openDocumentUnlocked(documentId) }
    private suspend fun openDocumentUnlocked(documentId: String): LibraryResult<OpenedLibraryDocument> {
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
            val physical = withContext(kotlinx.coroutines.Dispatchers.Default) { runCatching { org.synapseworks.pageharbor.document.session.DocumentPaperSize.decode(page.paperSize) } }
            if(physical.isFailure||physical.getOrNull()?.matchesPixels(page.width,page.height)==false)return LibraryResult.Failure(LibraryError.CORRUPTED_RECORD)
            if (page.brightness !in -100..100 || page.contrast !in -100..100) return LibraryResult.Failure(LibraryError.CORRUPTED_RECORD)
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
                adjustments = ManualImageAdjustments(page.brightness, page.contrast),
                paperSize = physical.getOrNull(),
                annotations = withContext(kotlinx.coroutines.Dispatchers.Default) { runCatching { org.synapseworks.pageharbor.document.annotation.AnnotationLayer.decode(page.annotations) }.getOrNull() } ?: return LibraryResult.Failure(LibraryError.CORRUPTED_RECORD),
                persistentId = page.pageId,
            )
        }
        val summary = entity.toSummary(folderName = entity.folderId?.let { id ->
            runDatabase { dao.folder(id) }?.name
        })
        val storedOriginalPdfs = runDatabase { dao.sourceAssets(documentId) }
            ?.filter { asset ->
                asset.role == ORIGINAL_DOCUMENT_ASSET_ROLE &&
                    asset.contentType.equals("application/pdf", ignoreCase = true)
            }
            ?: return LibraryResult.Failure(LibraryError.DATABASE_UNAVAILABLE)
        val originalPdfResources = storedOriginalPdfs.map { asset ->
            val file = fileStore.resolve(asset.relativePath)?.takeIf(File::isFile)
                ?: return LibraryResult.Failure(LibraryError.SOURCE_MISSING)
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
            resource to asset
        }
        val singleOriginalPdf = originalPdfResources.singleOrNull()
        val directPdfSource = singleOriginalPdf?.first
        return LibraryResult.Success(
            OpenedLibraryDocument(
                session = DocumentSession(
                    pages = pages,
                    directPdfSource = directPdfSource,
                    originalPdfSources = originalPdfResources.map { it.first },
                    directPdfPageIds = if (singleOriginalPdf?.second?.matchesCurrentRevision == true) {
                        pages.map(DocumentPage::id)
                    } else {
                        emptyList()
                    },
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
        return operationGate.withMutation { renameDocumentUnlocked(documentId, title) }
    }

    suspend fun suggestDocumentName(documentId: String): LibraryResult<SmartNameSuggestion?> =
        operationGate.withStableSnapshot {
            try {
                val document = dao.document(documentId)
                    ?: return@withStableSnapshot LibraryResult.Failure(LibraryError.DOCUMENT_NOT_FOUND)
                val folderName = document.folderId?.let { dao.folder(it)?.name }
                val pages = dao.smartNamingOcrPages(
                    documentId = documentId,
                    pageLimit = SMART_NAMING_PAGE_LIMIT,
                    maxCharactersPerPage = SMART_NAMING_CHARACTERS_PER_PAGE,
                )
                LibraryResult.Success(
                    LocalSmartNamingEngine.suggest(
                        SmartNamingInput(
                            currentTitle = document.title,
                            effectiveOcrPages = pages.map(LibrarySmartNamingOcrPageRow::effectiveText),
                            folderName = folderName,
                        ),
                    ),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                LibraryResult.Failure(LibraryError.DATABASE_UNAVAILABLE)
            }
        }

    private suspend fun renameDocumentUnlocked(documentId: String, title: String): LibraryResult<Unit> {
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
    ): LibraryResult<Unit> = operationGate.withMutation {
        indexOcrUnlocked(documentId, pageIds, result)
    }

    private suspend fun indexOcrUnlocked(
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

    suspend fun captureOcrPageSnapshots(
        documentId: String,
        orderedPageIds: List<String>? = null,
    ): List<LibraryOcrPageSnapshot> = operationGate.withStableSnapshot {
        val snapshots = dao.ocrPageSnapshots(documentId)
        if (orderedPageIds != null && snapshots.map(LibraryOcrPageSnapshot::pageId) != orderedPageIds) {
            emptyList()
        } else {
            snapshots
        }
    }

    suspend fun captureOcrPageSnapshot(
        documentId: String,
        pageId: String,
    ): LibraryOcrPageSnapshot? = operationGate.withStableSnapshot {
        dao.ocrPageSnapshot(documentId, pageId)
    }

    suspend fun activeTextArtifact(pageId: String): LibraryPageOcrArtifactEntity? =
        operationGate.withStableSnapshot { dao.activeTextArtifact(pageId) }

    suspend fun commitOcrArtifact(
        expected: LibraryOcrPageSnapshot,
        draft: LibraryOcrArtifactDraft,
    ): LibraryOcrCommitResult = operationGate.withMutation {
        dao.commitOcrArtifact(expected, draft, nowMillis())
    }

    suspend fun indexOcr(
        documentId: String,
        outcomes: List<LibraryOcrPageOutcomeDraft>,
    ): LibraryOcrCommitResult = operationGate.withMutation {
        dao.commitOcrArtifacts(documentId, outcomes, nowMillis())
    }

    suspend fun saveOcrCorrection(
        expected: LibraryOcrPageSnapshot,
        correction: LibraryOcrCorrectionDraft,
    ): LibraryOcrCommitResult = operationGate.withMutation {
        dao.saveOcrCorrection(expected, correction, nowMillis())
    }

    suspend fun revertOcrCorrection(
        expected: LibraryOcrPageSnapshot,
    ): LibraryOcrCommitResult = operationGate.withMutation {
        dao.revertOcrCorrection(expected, nowMillis())
    }

    suspend fun effectiveOcrPage(
        documentId: String,
        pageId: String,
    ): LibraryEffectiveOcrPage? = operationGate.withStableSnapshot {
        dao.effectiveOcrPage(documentId, pageId)
    }

    /** Captures ordered effective pages under one repository snapshot for PDF request identity. */
    suspend fun captureEffectiveOcrPages(
        documentId: String,
        orderedPageIds: List<String>,
    ): List<LibraryEffectiveOcrPage?> = operationGate.withStableSnapshot {
        orderedPageIds.map { pageId -> dao.effectiveOcrPage(documentId, pageId) }
    }

    suspend fun ocrReviewPage(
        documentId: String,
        pageId: String,
    ): LibraryOcrReviewPage? = operationGate.withStableSnapshot {
        dao.ocrReviewPage(documentId, pageId)
    }

    internal suspend fun ocrReviewScan(snapshot: LibraryOcrPageSnapshot): LibraryReviewScan? =
        operationGate.withStableSnapshot {
            if (dao.ocrPageSnapshot(snapshot.documentId, snapshot.pageId) != snapshot) return@withStableSnapshot null
            val page = dao.pageAnyState(snapshot.pageId) ?: return@withStableSnapshot null
            val file = fileStore.resolve(page.relativePath)?.takeIf(File::isFile) ?: return@withStableSnapshot null
            val uri = FileProvider.getUriForFile(applicationContext, "${applicationContext.packageName}.fileprovider", file)
            val rotation = DocumentPageRotation.entries.firstOrNull { it.degrees == snapshot.rotationDegrees }
                ?: return@withStableSnapshot null
            val stored = dao.effectiveOcrPage(snapshot.documentId, snapshot.pageId)
            val layout = stored?.toEffectiveOcrRequestOrNull()?.let { request ->
                (LibraryEffectiveOcrPageProvider(this).resolve(request, stored) as? EffectiveOcrPageProvision.Available)?.page?.layout
            }
            LibraryReviewScan(uri, DocumentImageMetadata(page.sourceByteCount, page.width, page.height), rotation, layout)
        }

    suspend fun ocrBackupPage(
        documentId: String,
        afterPosition: Int,
        limit: Int,
    ): List<LibraryOcrRestorePageState> = operationGate.withStableSnapshot {
        dao.ocrBackupPage(documentId, afterPosition, limit)
    }

    suspend fun freezeOcrBatchTargets(
        job: OcrBatchJobEntity,
        targets: List<OcrBatchTarget>,
    ): Boolean = operationGate.withMutation {
        dao.freezeOcrBatchTargets(job, targets)
    }

    /**
     * Starts a durable target population. Callers may page their selection and append bounded
     * chunks without materializing the whole batch. No item can be claimed before finish succeeds.
     */
    suspend fun beginOcrBatchTargetPopulation(job: OcrBatchJobEntity): Boolean =
        operationGate.withMutation {
            dao.beginOcrBatchTargetPopulation(job)
        }

    suspend fun appendOcrBatchTargets(
        jobId: String,
        targets: List<OcrBatchTarget>,
    ): Boolean = operationGate.withMutation {
        dao.appendOcrBatchTargets(jobId, targets, nowMillis())
    }

    suspend fun finishOcrBatchTargetPopulation(jobId: String): Boolean =
        operationGate.withMutation {
            dao.finishOcrBatchTargetPopulation(jobId, nowMillis())
        }

    suspend fun discardIncompleteOcrBatchTargetPopulation(jobId: String): Boolean =
        operationGate.withMutation {
            dao.discardIncompleteOcrBatchTargetPopulation(jobId)
        }

    suspend fun claimOcrBatchItem(
        jobId: String,
        itemId: String,
        generation: Long,
        claimToken: String,
    ): OcrBatchClaim? = operationGate.withMutation {
        dao.claimOcrBatchItem(jobId, itemId, generation, claimToken, nowMillis())
    }

    suspend fun completeOcrBatchItem(
        claim: OcrBatchClaim,
        draft: LibraryOcrArtifactDraft,
    ): OcrBatchCompletionResult = operationGate.withMutation {
        dao.completeOcrBatchItem(claim, draft, nowMillis())
    }

    suspend fun skipOcrBatchItem(
        claim: OcrBatchClaim,
        safeReasonCode: String,
    ): OcrBatchCompletionResult = operationGate.withMutation {
        dao.skipOcrBatchItem(claim, safeReasonCode, nowMillis())
    }

    suspend fun failOcrBatchItem(
        claim: OcrBatchClaim,
        safeErrorCode: String,
    ): OcrBatchCompletionResult = operationGate.withMutation {
        dao.failOcrBatchItem(claim, safeErrorCode, nowMillis())
    }

    suspend fun waitForOcrModel(
        claim: OcrBatchClaim,
        safeErrorCode: String,
    ): OcrBatchCompletionResult = operationGate.withMutation {
        dao.waitForOcrModel(claim, safeErrorCode, nowMillis())
    }

    suspend fun requestOcrBatchItemRetry(jobId: String, itemId: String): Boolean =
        operationGate.withMutation {
            dao.requestOcrBatchItemRetry(jobId, itemId, nowMillis())
        }

    suspend fun refreshOcrBatchItemForRetry(jobId: String, itemId: String): Boolean =
        operationGate.withMutation {
            dao.refreshOcrBatchItemForRetry(jobId, itemId, nowMillis())
        }

    suspend fun ocrBatchJob(jobId: String): OcrBatchJobEntity? =
        operationGate.withStableSnapshot { dao.ocrBatchJob(jobId) }

    suspend fun ocrBatchItems(jobId: String): List<OcrBatchItemEntity> =
        operationGate.withStableSnapshot { dao.ocrBatchItems(jobId) }

    suspend fun nextOcrBatchItem(jobId: String): OcrBatchItemEntity? =
        operationGate.withStableSnapshot { dao.nextOcrBatchItem(jobId) }

    suspend fun ocrBatchProgress(jobId: String): OcrBatchProgressSnapshot =
        operationGate.withStableSnapshot { dao.ocrBatchProgress(jobId) }

    suspend fun retryableOcrBatchItems(
        jobId: String,
        limit: Int,
    ): List<OcrBatchItemEntity> = operationGate.withStableSnapshot {
        dao.retryableOcrBatchItems(jobId, limit)
    }

    suspend fun latestRecoverableOcrBatchJob(): OcrBatchJobEntity? =
        operationGate.withStableSnapshot { dao.latestRecoverableOcrBatchJob() }

    suspend fun markInterruptedOcrBatchJobs(): Int = operationGate.withMutation {
        dao.markInterruptedOcrBatchJobs(nowMillis())
    }

    suspend fun documentTitle(documentId: String): String? =
        operationGate.withStableSnapshot { dao.documentTitle(documentId) }

    suspend fun cancelOcrBatchJob(jobId: String): Boolean = operationGate.withMutation {
        dao.cancelOcrBatchJob(jobId, nowMillis())
    }

    suspend fun recoverInterruptedOcrBatchJob(jobId: String): OcrBatchJobEntity? =
        operationGate.withMutation {
            dao.recoverInterruptedOcrBatchJob(jobId, nowMillis())
        }

    suspend fun createFolder(
        name: String,
        parentFolderId: String? = null,
    ): LibraryResult<LibraryFolder> = operationGate.withMutation {
        createFolderUnlocked(name, parentFolderId)
    }

    private suspend fun createFolderUnlocked(
        name: String,
        parentFolderId: String?,
    ): LibraryResult<LibraryFolder> {
        val normalized = normalizeFolderName(name)
        if (normalized.isBlank()) return LibraryResult.Failure(LibraryError.TITLE_REQUIRED)
        val normalizedKey = normalized.lowercase(Locale.ROOT)
        return try {
            if (parentFolderId != null && dao.folder(parentFolderId) == null) {
                return LibraryResult.Failure(LibraryError.FOLDER_NOT_FOUND)
            }
            if (
                dao.folderByNormalizedName(
                    normalizedName = normalizedKey,
                    parentScope = libraryFolderParentScope(parentFolderId),
                ) != null
            ) {
                LibraryResult.Failure(LibraryError.DUPLICATE_FOLDER)
            } else {
                val now = nowMillis()
                val folder = LibraryFolderEntity(
                    folderId = UUID.randomUUID().toString(),
                    name = normalized,
                    normalizedName = normalizedKey,
                    createdAtMillis = now,
                    modifiedAtMillis = now,
                    parentFolderId = parentFolderId,
                )
                dao.insertFolder(folder)
                LibraryResult.Success(
                    LibraryFolder(
                        id = folder.folderId,
                        name = folder.name,
                        parentFolderId = folder.parentFolderId,
                    ),
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            LibraryResult.Failure(LibraryError.DATABASE_UNAVAILABLE)
        }
    }

    suspend fun renameFolder(folderId: String, name: String): LibraryResult<Unit> {
        return operationGate.withMutation { renameFolderUnlocked(folderId, name) }
    }

    private suspend fun renameFolderUnlocked(folderId: String, name: String): LibraryResult<Unit> {
        val normalized = normalizeFolderName(name)
        if (normalized.isBlank()) return LibraryResult.Failure(LibraryError.TITLE_REQUIRED)
        return try {
            val folder = dao.folder(folderId)
                ?: return LibraryResult.Failure(LibraryError.FOLDER_NOT_FOUND)
            val duplicate = dao.folderByNormalizedName(
                normalizedName = normalized.lowercase(Locale.ROOT),
                parentScope = folder.parentScope,
            )
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

    suspend fun moveFolder(folderId: String, parentFolderId: String?): LibraryResult<Unit> {
        return operationGate.withMutation { moveFolderUnlocked(folderId, parentFolderId) }
    }

    private suspend fun moveFolderUnlocked(
        folderId: String,
        parentFolderId: String?,
    ): LibraryResult<Unit> {
        return try {
            val folder = dao.folder(folderId)
                ?: return LibraryResult.Failure(LibraryError.FOLDER_NOT_FOUND)
            if (folderId == parentFolderId) {
                return LibraryResult.Failure(LibraryError.INVALID_SELECTION)
            }
            if (parentFolderId != null) {
                if (dao.folder(parentFolderId) == null) {
                    return LibraryResult.Failure(LibraryError.FOLDER_NOT_FOUND)
                }
                if (wouldCreateFolderCycle(folderId, parentFolderId)) {
                    return LibraryResult.Failure(LibraryError.INVALID_SELECTION)
                }
            }
            if (folder.parentFolderId != parentFolderId) {
                val duplicate = dao.folderByNormalizedName(
                    normalizedName = folder.normalizedName,
                    parentScope = libraryFolderParentScope(parentFolderId),
                )
                if (duplicate != null && duplicate.folderId != folderId) {
                    return LibraryResult.Failure(LibraryError.DUPLICATE_FOLDER)
                }
                dao.updateFolder(
                    folder.copy(
                        parentFolderId = parentFolderId,
                        parentScope = libraryFolderParentScope(parentFolderId),
                        modifiedAtMillis = nowMillis(),
                    ),
                )
            }
            LibraryResult.Success(Unit)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            LibraryResult.Failure(LibraryError.DATABASE_UNAVAILABLE)
        }
    }

    suspend fun deleteFolder(folderId: String): LibraryResult<Unit> = operationGate.withMutation {
        deleteFolderUnlocked(folderId)
    }

    private suspend fun deleteFolderUnlocked(folderId: String): LibraryResult<Unit> {
        return try {
            val folder = dao.folder(folderId)
                ?: return LibraryResult.Failure(LibraryError.FOLDER_NOT_FOUND)
            if (
                dao.folderDeletionWouldConflict(
                    folderId = folderId,
                    targetParentScope = libraryFolderParentScope(folder.parentFolderId),
                )
            ) {
                return LibraryResult.Failure(LibraryError.DUPLICATE_FOLDER)
            }
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
    }

    suspend fun moveDocument(documentId: String, folderId: String?): LibraryResult<Unit> {
        return operationGate.withMutation { moveDocumentUnlocked(documentId, folderId) }
    }

    private suspend fun moveDocumentUnlocked(
        documentId: String,
        folderId: String?,
    ): LibraryResult<Unit> {
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

    suspend fun deleteDocument(documentId: String): LibraryResult<Unit> = operationGate.withMutation {
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable){
            val directory=File(fileStore.root,documentId).absolutePath
            LibrarySourceReaders.block(documentId,directory)
            try {
                if(dao.trashSavedDocument(documentId,nowMillis())==null){
                    LibrarySourceReaders.unblock(documentId)
                    LibraryResult.Failure(LibraryError.DOCUMENT_NOT_FOUND)
                }else LibraryResult.Success(Unit)
            }catch(_:Exception){
                if(runCatching{dao.document(documentId)!=null}.getOrDefault(false))LibrarySourceReaders.unblock(documentId)
                LibraryResult.Failure(LibraryError.DATABASE_UNAVAILABLE)
            }
        }
    }
    internal fun observeRecyclePage(offset:Int):Flow<RecyclePage> {
        require(offset>=0&&offset<=Int.MAX_VALUE-30)
        return combine(dao.observeRecycleRows(offset),dao.observeRecycleCount()){rows,count->RecyclePage(rows,count,offset)}
    }
    internal suspend fun recyclePreview():RecyclePreview=operationGate.withStableSnapshot{dao.recyclePreview()}
    internal suspend fun restoreFromRecycle(id:String,revision:Long):RecycleResult=operationGate.withMutation {
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable){try {
            val row=dao.recycleDocument(id)
            if(row==null||row.libraryState !in setOf("ACTIVE","TRASHED")||(row.libraryState=="TRASHED"&&row.trashRevision!=revision))return@withContext RecycleResult.Failed(RecycleFailure.CHANGED)
            // A failed/interrupted purge may still own the bytes in quarantine. Put them back
            // before making the document active; an uncertain scan must not claim restoration.
            when(val scan=fileStore.pendingDocumentDeletions()){
                DocumentDirectoryScan.Unavailable->return@withContext RecycleResult.Failed(RecycleFailure.STATE_UNCERTAIN)
                is DocumentDirectoryScan.Available->for(pending in scan.entries.filter{it.documentId==id}){
                    if(!fileStore.restoreQuarantinedDocument(pending))return@withContext RecycleResult.Failed(RecycleFailure.STATE_UNCERTAIN)
                }
            }
            if(dao.restoreTrashedDocument(id,revision,nowMillis())==null)RecycleResult.Failed(RecycleFailure.CHANGED)
            else {LibrarySourceReaders.unblock(id);RecycleResult.Done()}
        }catch(_:Exception){RecycleResult.Failed(RecycleFailure.UNAVAILABLE)}}
    }
    internal suspend fun permanentlyDelete(id:String,revision:Long):RecycleResult=operationGate.withMutation {
        currentCoroutineContext().ensureActive()
        val row=try{dao.documentAnyState(id)}catch(_:Exception){return@withMutation RecycleResult.Failed(RecycleFailure.UNAVAILABLE)}
        if(row==null||row.libraryState!="TRASHED"||row.trashRevision!=revision)return@withMutation RecycleResult.Failed(RecycleFailure.CHANGED)
        LibrarySourceReaders.block(id,File(fileStore.root,id).absolutePath)
        if(LibrarySourceReaders.inUse(id,File(fileStore.root,id).absolutePath))return@withMutation RecycleResult.Failed(RecycleFailure.IN_USE)
        when(val result=deleteDocumentUnlocked(id,revision)){
            is LibraryResult.Success->RecycleResult.Done(result.warning==LibraryWarning.DELETION_CLEANUP_PENDING)
            is LibraryResult.Failure->RecycleResult.Failed(when(result.reason){LibraryError.STORAGE_UNAVAILABLE->RecycleFailure.STORAGE;LibraryError.DELETION_STATE_UNCERTAIN,LibraryError.DELETION_RECOVERY_PENDING->RecycleFailure.STATE_UNCERTAIN;else->RecycleFailure.UNAVAILABLE})
        }
    }
    internal suspend fun emptyRecycleBin(watermark:Long):EmptyBinResult {
        var after="";var deleted=0;var failed=0;var pending=0
        while(true){
            currentCoroutineContext().ensureActive()
            val rows=operationGate.withStableSnapshot{dao.recyclePurgePage(watermark,after)}
            if(rows.isEmpty())break
            for(row in rows){when(val result=permanentlyDelete(row.documentId,row.trashRevision)){
                is RecycleResult.Done->{deleted++;if(result.cleanupPending)pending++}
                is RecycleResult.Failed->failed++
            };after=row.documentId}
        }
        return EmptyBinResult(deleted,failed,pending)
    }

    /**
     * Reconciles durable filesystem quarantines after an interrupted deletion. A database row is
     * authoritative that the document is still in the library, so its files are restored. When no
     * row exists, physical deletion is retried. Unknown database state leaves the quarantine in
     * place rather than risking either document loss or an untracked private-file orphan.
     */
    internal suspend fun recoverPendingDocumentDeletions(): DocumentDeletionRecoveryReport {
        val prepared = operationGate.withMutation { prepareDocumentDeletionRecoveryUnlocked() }
        var deleted = 0
        var stillPending = prepared.stillPending
        prepared.cleanup.forEach { pending ->
            if (fileStore.completeDocumentDeletion(pending)) deleted++ else stillPending++
        }
        return DocumentDeletionRecoveryReport(
            restored = prepared.restored,
            deleted = deleted,
            stillPending = stillPending,
            legacyQuarantined = prepared.legacyQuarantined,
            legacyPreservedOwned = prepared.legacyPreservedOwned,
            legacyPreservedUncertain = prepared.legacyPreservedUncertain,
            filesystemScanUnavailable = prepared.filesystemScanUnavailable,
            databaseUnavailable = prepared.databaseUnavailable,
        )
    }

    /**
     * Decides ownership while the global mutation gate is held, but leaves recursive deletion for
     * after the gate is released. This keeps slow or persistently failing cleanup from blocking
     * unrelated library work.
     */
    private suspend fun prepareDocumentDeletionRecoveryUnlocked(): PreparedDocumentDeletionRecovery {
        val pendingScan = fileStore.pendingDocumentDeletions()
        if (pendingScan is DocumentDirectoryScan.Unavailable) {
            return PreparedDocumentDeletionRecovery(
                restored = 0,
                stillPending = 0,
                cleanup = emptyList(),
                legacyQuarantined = 0,
                legacyPreservedOwned = 0,
                legacyPreservedUncertain = 0,
                filesystemScanUnavailable = true,
                databaseUnavailable = false,
            )
        }

        var restored = 0
        var stillPending = 0
        var databaseUnavailable = false
        val cleanup = mutableListOf<PendingDocumentDeletion>()
        val pending = (pendingScan as DocumentDirectoryScan.Available).entries
        pending.forEach { deletion ->
            when (deletionRecordState(deletion.documentId)) {
                DeletionRecordState.PRESENT -> {
                    if (fileStore.restoreQuarantinedDocument(deletion)) restored++ else stillPending++
                }
                DeletionRecordState.ABSENT -> {
                    LibrarySourceReaders.block(deletion.documentId,File(fileStore.root,deletion.documentId).absolutePath)
                    if(LibrarySourceReaders.inUse(deletion.documentId,File(fileStore.root,deletion.documentId).absolutePath))stillPending++ else cleanup+=deletion
                }
                DeletionRecordState.UNKNOWN -> {
                    stillPending++
                    databaseUnavailable = true
                }
            }
        }

        var legacyQuarantined = 0
        var legacyPreservedOwned = 0
        var legacyPreservedUncertain = 0
        var filesystemScanUnavailable = false
        when (val legacyScan = fileStore.legacyDocumentDirectoryCandidates()) {
            DocumentDirectoryScan.Unavailable -> filesystemScanUnavailable = true
            is DocumentDirectoryScan.Available -> {
                val protectedDocumentIds = try {
                    dao.documentIdsProtectedFromLegacyDeletion()
                } catch (error: CancellationException) {
                    if (!currentCoroutineContext().isActive) throw error
                    null
                } catch (_: Exception) {
                    null
                }
                if (protectedDocumentIds == null) databaseUnavailable = true
                legacyScan.entries.forEach { documentId ->
                    when (legacyDirectoryOwnership(documentId, protectedDocumentIds)) {
                        LegacyDirectoryOwnership.OWNED -> legacyPreservedOwned++
                        LegacyDirectoryOwnership.UNKNOWN -> {
                            legacyPreservedUncertain++
                            databaseUnavailable = true
                        }
                        LegacyDirectoryOwnership.ABANDONED -> {
                            when (val preparation = fileStore.quarantineDocument(documentId)) {
                                is DocumentDeletionPreparation.Quarantined -> {
                                    cleanup += preparation.pending
                                    legacyQuarantined++
                                }
                                DocumentDeletionPreparation.NoPrivateFiles -> Unit
                                is DocumentDeletionPreparation.AlreadyQuarantined,
                                DocumentDeletionPreparation.QuarantineFailed,
                                DocumentDeletionPreparation.StateUncertain,
                                -> legacyPreservedUncertain++
                            }
                        }
                    }
                }
            }
        }
        return PreparedDocumentDeletionRecovery(
            restored = restored,
            stillPending = stillPending,
            cleanup = cleanup,
            legacyQuarantined = legacyQuarantined,
            legacyPreservedOwned = legacyPreservedOwned,
            legacyPreservedUncertain = legacyPreservedUncertain,
            filesystemScanUnavailable = filesystemScanUnavailable,
            databaseUnavailable = databaseUnavailable,
        )
    }

    private suspend fun deleteDocumentUnlocked(documentId: String,revision:Long): LibraryResult<Unit> {
        val exists = try {
            dao.documentAnyState(documentId)?.let{it.libraryState=="TRASHED"&&it.trashRevision==revision}==true
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return LibraryResult.Failure(LibraryError.DATABASE_UNAVAILABLE)
        }
        if (!exists) return LibraryResult.Failure(LibraryError.DOCUMENT_NOT_FOUND)

        val pending = when (val preparation = fileStore.quarantineDocument(documentId)) {
            DocumentDeletionPreparation.NoPrivateFiles -> null
            is DocumentDeletionPreparation.Quarantined -> preparation.pending
            is DocumentDeletionPreparation.AlreadyQuarantined -> preparation.pending
            DocumentDeletionPreparation.QuarantineFailed -> {
                return LibraryResult.Failure(LibraryError.STORAGE_UNAVAILABLE)
            }
            DocumentDeletionPreparation.StateUncertain ->
                return LibraryResult.Failure(LibraryError.DELETION_STATE_UNCERTAIN)
        }

        try {
            if (dao.purgeTrashedDocument(documentId,revision,nowMillis()) == null) {
                return resolveFailedDeletion(documentId, pending)
            }
        } catch (error: CancellationException) {
            return resolveInterruptedDeletion(documentId, pending, error)
        } catch (_: Exception) {
            return resolveFailedDeletion(documentId, pending)
        }

        return finishCommittedDeletion(pending)
    }

    private suspend fun resolveInterruptedDeletion(
        documentId: String,
        pending: PendingDocumentDeletion?,
        cancellation: CancellationException,
    ): LibraryResult<Unit> = withContext(NonCancellable) {
        when (deletionRecordState(documentId)) {
            DeletionRecordState.ABSENT -> finishCommittedDeletion(pending)
            DeletionRecordState.PRESENT -> {
                pending?.let(fileStore::restoreQuarantinedDocument)
                throw cancellation
            }
            DeletionRecordState.UNKNOWN -> throw cancellation
        }
    }

    private suspend fun resolveFailedDeletion(
        documentId: String,
        pending: PendingDocumentDeletion?,
    ): LibraryResult<Unit> = withContext(NonCancellable) {
        when (deletionRecordState(documentId)) {
            DeletionRecordState.ABSENT -> finishCommittedDeletion(pending)
            DeletionRecordState.PRESENT -> {
                if (pending == null || fileStore.restoreQuarantinedDocument(pending)) {
                    LibraryResult.Failure(LibraryError.DATABASE_UNAVAILABLE)
                } else {
                    LibraryResult.Failure(LibraryError.DELETION_RECOVERY_PENDING)
                }
            }
            DeletionRecordState.UNKNOWN -> LibraryResult.Failure(LibraryError.DATABASE_UNAVAILABLE)
        }
    }

    private fun finishCommittedDeletion(
        pending: PendingDocumentDeletion?,
    ): LibraryResult<Unit> {
        // Success means the Room row and live library path are gone. The warning distinguishes
        // physical deletion from durable, quarantined cleanup that will be retried after restart.
        val physicallyDeleted = pending == null || fileStore.completeDocumentDeletion(pending)
        return LibraryResult.Success(
            Unit,
            warning = if (physicallyDeleted) null else LibraryWarning.DELETION_CLEANUP_PENDING,
        )
    }

    private suspend fun deletionRecordState(documentId: String): DeletionRecordState = try {
        if (dao.documentAnyState(documentId) == null) {
            DeletionRecordState.ABSENT
        } else {
            DeletionRecordState.PRESENT
        }
    } catch (error: CancellationException) {
        if (!currentCoroutineContext().isActive) throw error
        DeletionRecordState.UNKNOWN
    } catch (_: Exception) {
        DeletionRecordState.UNKNOWN
    }

    private fun legacyDirectoryOwnership(
        documentId: String,
        protectedDocumentIds: Set<String>?,
    ): LegacyDirectoryOwnership = when {
        protectedDocumentIds == null -> LegacyDirectoryOwnership.UNKNOWN
        documentId in protectedDocumentIds || LibrarySourceReaders.inUse(documentId,File(fileStore.root,documentId).absolutePath) -> LegacyDirectoryOwnership.OWNED
        else -> LegacyDirectoryOwnership.ABANDONED
    }

    suspend fun mergeDocuments(
        documentIds: List<String>,
        title: String,
    ): LibraryResult<SavedLibraryDocument> = operationGate.withMutation {
        mergeDocumentsUnlocked(documentIds, title)
    }

    private suspend fun mergeDocumentsUnlocked(
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
            sources += pageSources(pages) ?: return LibraryResult.Failure(LibraryError.CORRUPTED_RECORD)
        }
        return saveSources(null, normalizeLibraryTitle(title), null, sources)
    }

    suspend fun extractPages(
        documentId: String,
        pageIds: Set<String>,
        title: String,
        removeFromOriginal: Boolean,
    ): LibraryResult<SavedLibraryDocument> = operationGate.withMutation {
        extractPagesUnlocked(documentId, pageIds, title, removeFromOriginal)
    }

    private suspend fun extractPagesUnlocked(
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
        val selectedSources=pageSources(selected) ?: return LibraryResult.Failure(LibraryError.CORRUPTED_RECORD)
        val remainingSources=if(removeFromOriginal)pageSources(remaining,preservePersistentId=true)
            ?: return LibraryResult.Failure(LibraryError.CORRUPTED_RECORD) else emptyList()
        val extracted = saveSources(
            existingDocumentId = null,
            title = normalizeLibraryTitle(title),
            folderId = document.folderId,
            sources = selectedSources,
        )
        if (extracted !is LibraryResult.Success || !removeFromOriginal) return extracted

        val originalUpdate = saveSources(
            existingDocumentId = document.documentId,
            title = document.title,
            folderId = document.folderId,
            sources = remainingSources,
            createdAtOverride = document.createdAtMillis,
        )
        if (originalUpdate is LibraryResult.Failure) {
            // Roll back this operation's newly created copy, rather than leave a failed split
            // visible as a user-deleted document. If cleanup fails, retain it in the bin for recovery.
            if(deleteDocument(extracted.value.id) is LibraryResult.Success){
                dao.recycleDocument(extracted.value.id)?.let{permanentlyDelete(it.documentId,it.trashRevision)}
            }
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
        sourceAssets: List<LibrarySourceAssetSource> = emptyList(),
        ocrScriptPreference: String? = null,
    ): LibraryResult<SavedLibraryDocument> {
        if (title.isBlank()) return LibraryResult.Failure(LibraryError.TITLE_REQUIRED)
        if (sources.isEmpty()) return LibraryResult.Failure(LibraryError.EMPTY_DOCUMENT)
        val documentId = existingDocumentId ?: UUID.randomUUID().toString()
        val existing = existingDocumentId?.let { runDatabase { dao.document(it) } }
        if (existingDocumentId != null && existing == null) {
            return LibraryResult.Failure(LibraryError.DOCUMENT_NOT_FOUND)
        }
        if(dao.isDeletedLocalIdentity(documentId))return LibraryResult.Failure(LibraryError.DOCUMENT_NOT_FOUND)
        val retainedSourceAssets = if (sourceAssets.isNotEmpty() || existing == null) {
            sourceAssets
        } else {
            runDatabase { dao.sourceAssets(documentId) }.orEmpty().mapNotNull { asset ->
                val file = fileStore.resolve(asset.relativePath)?.takeIf(File::isFile)
                    ?: return@mapNotNull null
                LibrarySourceAssetSource(
                    role = asset.role,
                    contentType = asset.contentType,
                    sourceModifiedAtMillis = asset.sourceModifiedAtMillis,
                    matchesCurrentRevision = false,
                    openStream = { FileInputStream(file) },
                )
            }
        }
        val prepared = when (
            val result = fileStore.prepareRevision(
                documentId = documentId,
                sources = sources,
                sourceAssets = retainedSourceAssets,
            )
        ) {
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
                brightness = page.adjustments.brightness, contrast = page.adjustments.contrast,
                annotations = page.annotations.encode(),
                paperSize = page.paperSize?.encode().orEmpty(),
                ocrText = page.ocrText,
                ocrError = page.ocrError,
                contentSha256 = page.contentSha256,
            )
        }
        val sourceAssetEntities = prepared.value.sourceAssets.map { asset ->
            LibrarySourceAssetEntity(
                assetId = asset.assetId,
                documentId = documentId,
                role = asset.role,
                relativePath = asset.relativePath,
                contentType = asset.contentType,
                byteCount = asset.byteCount,
                sha256 = asset.sha256,
                sourceModifiedAtMillis = asset.sourceModifiedAtMillis,
                createdAtMillis = now,
                matchesCurrentRevision = asset.matchesCurrentRevision,
            )
        }
        val contentByteCount = pages.sumOf { page -> page.sourceByteCount ?: 0L }
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
            libraryState = LibraryDocumentState.ACTIVE.name,
            contentHashVersion = if(pages.any { it.paperSize.isNotEmpty() }) 4 else if(pages.any { it.annotations.isNotEmpty() }) 3 else if (pages.any { it.brightness != 0 || it.contrast != 0 }) 2 else DocumentFingerprintV1.VERSION,
            contentSha256 = logicalDocumentSha256(pages),
            contentByteCount = contentByteCount,
            sourceModifiedAtMillis = sourceAssetEntities.firstOrNull()?.sourceModifiedAtMillis,
            importedAtMillis = existing?.importedAtMillis ?: now,
            ocrScriptPreference = ocrScriptPreference ?: existing?.ocrScriptPreference,
            documentDateEpochDay = existing?.documentDateEpochDay,
            documentDateProvenance = existing?.documentDateProvenance,
            documentDateConfidence = existing?.documentDateConfidence,
            originalCreatedAtMillis = existing?.originalCreatedAtMillis,
            originalCreatedAtProvenance = existing?.originalCreatedAtProvenance,
            sourceModifiedAtProvenance = existing?.sourceModifiedAtProvenance,
            sourceProvenanceKind = existing?.sourceProvenanceKind,
            sourceProvenanceLabel = existing?.sourceProvenanceLabel,
        )
        try {
            dao.replaceDocument(
                document = document,
                pages = pages,
                ocrText = pages.joinToString("\n\n") { it.ocrText.orEmpty() },
                sourceAssets = sourceAssetEntities,
                ocrCloneSources = prepared.value.pages.mapNotNull { page ->
                    page.ocrCloneSourcePageId?.let { sourcePageId -> page.pageId to sourcePageId }
                }.toMap(),
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

    suspend fun cleanupDocumentRevisions(documentId: String) = operationGate.withMutation {
        cleanupDocumentRevisionsUnlocked(documentId)
    }

    private suspend fun cleanupDocumentRevisionsUnlocked(documentId: String) {
        val pages = runDatabase { dao.pages(documentId) } ?: return
        val keepDirectory = pages.firstOrNull()?.relativePath
            ?.let(fileStore::resolve)
            ?.parentFile
            ?: return
        fileStore.cleanupOtherRevisions(documentId, keepDirectory)
    }

    private suspend fun pageSources(pages:List<LibraryPageEntity>,preservePersistentId:Boolean=false):List<LibraryPageSource>? =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            pages.map { it.toSource(preservePersistentId) ?: return@withContext null }
        }

    private fun LibraryPageEntity.toSource(
        preservePersistentId: Boolean = false,
    ): LibraryPageSource? {
        val physical=runCatching { org.synapseworks.pageharbor.document.session.DocumentPaperSize.decode(paperSize) };if(physical.isFailure||physical.getOrNull()?.matchesPixels(width,height)==false)return null
        val layer=runCatching { org.synapseworks.pageharbor.document.annotation.AnnotationLayer.decode(annotations) }.getOrNull() ?: return null
        val file = fileStore.resolve(relativePath)
        return LibraryPageSource(
            persistentId = pageId.takeIf { preservePersistentId },
            ocrCloneSourcePageId = pageId.takeUnless { preservePersistentId },
            contentType = contentType,
            sourceCategory = sourceCategory,
            imageMetadata = DocumentImageMetadata(sourceByteCount, width, height),
            rotation = DocumentPageRotation.entries.firstOrNull { it.degrees == rotationDegrees }
                ?: DocumentPageRotation.DEGREES_0,
            filter = runCatching { DocumentFilter.valueOf(filterName) }.getOrDefault(DocumentFilter.ORIGINAL),
            adjustments = ManualImageAdjustments(brightness, contrast),
            annotations = layer,
            paperSize = physical.getOrNull(),
            ocrText = ocrText,
            ocrError = ocrError,
            openStream = { FileInputStream(file ?: throw FileNotFoundException()) },
        )
    }

    private suspend fun wouldCreateFolderCycle(
        folderId: String,
        candidateParentId: String,
    ): Boolean {
        val visited = mutableSetOf<String>()
        var currentId: String? = candidateParentId
        while (currentId != null && visited.add(currentId)) {
            if (currentId == folderId) return true
            currentId = dao.folder(currentId)?.parentFolderId
        }
        return currentId != null
    }

    private suspend fun <T> runDatabase(block: suspend () -> T): T? = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }
}

private enum class DeletionRecordState {
    PRESENT,
    ABSENT,
    UNKNOWN,
}

private enum class LegacyDirectoryOwnership {
    OWNED,
    ABANDONED,
    UNKNOWN,
}

private fun LibraryDocumentListingRow.toSummary(): LibraryDocumentSummary = LibraryDocumentSummary(
    id = documentId,
    title = title,
    createdAtMillis = createdAtMillis,
    modifiedAtMillis = modifiedAtMillis,
    documentDateEpochDay = documentDateEpochDay,
    documentDateProvenance = documentDateProvenance?.let {
        runCatching { LibraryDocumentDateProvenance.valueOf(it) }.getOrNull()
    },
    importedAtMillis = importedAtMillis,
    pageCount = pageCount,
    folderId = folderId,
    folderName = folderName,
    thumbnailRelativePath = thumbnailRelativePath,
    isFavorite = isFavorite,
    ocrStatus = runCatching { LibraryOcrStatus.valueOf(ocrStatus) }
        .getOrDefault(LibraryOcrStatus.NOT_INDEXED),
)

private fun LibrarySearchRow.toSummary(query: String): LibraryDocumentSummary = LibraryDocumentSummary(
    id = documentId,
    title = title,
    createdAtMillis = createdAtMillis,
    modifiedAtMillis = modifiedAtMillis,
    documentDateEpochDay = documentDateEpochDay,
    documentDateProvenance = documentDateProvenance?.let {
        runCatching { LibraryDocumentDateProvenance.valueOf(it) }.getOrNull()
    },
    importedAtMillis = importedAtMillis,
    pageCount = pageCount,
    folderId = folderId,
    folderName = folderName,
    thumbnailRelativePath = thumbnailRelativePath,
    isFavorite = isFavorite,
    ocrStatus = runCatching { LibraryOcrStatus.valueOf(ocrStatus) }
        .getOrDefault(LibraryOcrStatus.NOT_INDEXED),
    searchMatch = resolvedSearchMatch(query),
    searchSnippet = matchSnippet?.trim()?.takeIf(String::isNotBlank),
    matchingPageId = pageId,
    matchingPagePosition = pagePosition,
)

private fun LibrarySearchRow.resolvedSearchMatch(query: String): LibrarySearchMatch {
    val stored = runCatching { LibrarySearchMatch.valueOf(matchType) }
        .getOrDefault(LibrarySearchMatch.OCR)
    if (stored == LibrarySearchMatch.OCR || stored == LibrarySearchMatch.TAG) return stored
    return if (matchesLibrarySearchText(title, query)) {
        LibrarySearchMatch.TITLE
    } else {
        LibrarySearchMatch.FOLDER
    }
}

private fun LibraryDocumentEntity.toSummary(folderName: String?): LibraryDocumentSummary =
    LibraryDocumentSummary(
        id = documentId,
        title = title,
        createdAtMillis = createdAtMillis,
        modifiedAtMillis = modifiedAtMillis,
        documentDateEpochDay = documentDateEpochDay,
        documentDateProvenance = documentDateProvenance?.let {
            runCatching { LibraryDocumentDateProvenance.valueOf(it) }.getOrNull()
        },
        importedAtMillis = importedAtMillis,
        pageCount = pageCount,
        folderId = folderId,
        folderName = folderName,
        thumbnailRelativePath = thumbnailRelativePath,
        isFavorite = isFavorite,
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

private fun logicalDocumentSha256(pages: List<LibraryPageEntity>): String {
    return DocumentFingerprintV1.calculate(
        pages.sortedBy(LibraryPageEntity::position).map { page ->
            FingerprintPage(
                assetSha256 = requireNotNull(page.contentSha256),
                mimeType = page.contentType,
                byteLength = requireNotNull(page.sourceByteCount),
                rotationDegrees = page.rotationDegrees,
                filterName = page.filterName,
                brightness = page.brightness, contrast = page.contrast,
                annotations = page.annotations,
                paperSize = page.paperSize,
            )
        },
    ).sha256
}
private const val ORIGINAL_DOCUMENT_ASSET_ROLE = "ORIGINAL_DOCUMENT"
private const val SMART_NAMING_PAGE_LIMIT = 3
private const val SMART_NAMING_CHARACTERS_PER_PAGE = 4_000
private const val OBSERVED_SEARCH_LIMIT = 150
private const val DEFAULT_SEARCH_PAGE_SIZE = 30
internal const val LIBRARY_SEARCH_INITIAL_LIMIT = 30
internal const val LIBRARY_SEARCH_MAX_LIMIT = 200
private const val MAX_SEARCH_PAGE_SIZE = LIBRARY_SEARCH_MAX_LIMIT + 1
