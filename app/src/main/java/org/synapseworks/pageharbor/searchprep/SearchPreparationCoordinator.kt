package org.synapseworks.pageharbor.searchprep

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.synapseworks.pageharbor.document.session.DocumentImageMetadata
import org.synapseworks.pageharbor.library.LibraryDao
import org.synapseworks.pageharbor.library.LibraryDataOperationEntity
import org.synapseworks.pageharbor.library.LibraryDataOperationItemEntity
import org.synapseworks.pageharbor.library.LibraryDatabase
import org.synapseworks.pageharbor.library.LibraryDocumentEntity
import org.synapseworks.pageharbor.library.LibraryFileStore
import org.synapseworks.pageharbor.library.LibraryOcrArtifactDraft
import org.synapseworks.pageharbor.library.LibraryOcrCommitResult
import org.synapseworks.pageharbor.library.LibraryOperationCoordinator
import org.synapseworks.pageharbor.library.LibraryOperationGate
import org.synapseworks.pageharbor.library.LibraryPageEntity
import org.synapseworks.pageharbor.library.LibraryRepository
import org.synapseworks.pageharbor.library.LibraryTextSourceKind
import org.synapseworks.pageharbor.library.OcrBatchJobEntity
import org.synapseworks.pageharbor.library.OcrBatchJobState
import org.synapseworks.pageharbor.library.OcrBatchItemState
import org.synapseworks.pageharbor.library.OcrBatchTarget
import org.synapseworks.pageharbor.migration.MigrationCancellationSignal
import org.synapseworks.pageharbor.migration.NeverCancelMigration
import org.synapseworks.pageharbor.ocr.MultilingualOcrRuntime
import org.synapseworks.pageharbor.ocr.OcrLocaleScriptMapper
import org.synapseworks.pageharbor.ocr.OcrPage
import org.synapseworks.pageharbor.ocr.OcrPageRecognitionOutcome
import org.synapseworks.pageharbor.ocr.OcrScript
import org.synapseworks.pageharbor.ocr.OcrScriptSelection
import org.synapseworks.pageharbor.ocr.SharedPreferencesOcrLanguagePreferenceStore
import org.synapseworks.pageharbor.ocr.persistence.OcrPagePersistenceMapping
import org.synapseworks.pageharbor.ocr.persistence.ScriptedOcrSessionRunner

enum class SearchPreparationOutcome {
    SEARCH_READY,
    PARTIAL,
    FAILED,
    CANCELLED,
}

data class SearchPreparationProgress(
    val processedDocuments: Int,
    val totalDocuments: Int,
    val processedPages: Int,
    val totalPages: Int,
    val embeddedTextPages: Int,
    val ocrPages: Int,
    val failedPages: Int,
    val currentDocumentId: String? = null,
    val currentPagePosition: Int? = null,
)

fun interface SearchPreparationProgressListener {
    fun onProgress(progress: SearchPreparationProgress)
}

data class SearchPreparationDocumentResult(
    val documentId: String,
    val outcome: SearchPreparationOutcome,
    val pageCount: Int,
    val embeddedTextPages: Int,
    val ocrPages: Int,
    val failedPages: Int,
)

data class SearchPreparationReport(
    val operationId: String,
    val documents: List<SearchPreparationDocumentResult>,
    val cancelled: Boolean,
) {
    val fullySearchReadyCount: Int
        get() = documents.count { it.outcome == SearchPreparationOutcome.SEARCH_READY }
    val partiallySearchReadyCount: Int
        get() = documents.count { it.outcome == SearchPreparationOutcome.PARTIAL }
    val failedCount: Int
        get() = documents.count { it.outcome == SearchPreparationOutcome.FAILED }
}

/**
 * Durable post-import preparation. The operation journal owns document progress; embedded text and
 * OCR both commit through the existing revision/CAS artifact and FTS path.
 */
internal class AndroidSearchPreparationCoordinator(
    context: Context,
    private val dao: LibraryDao = LibraryDatabase.get(context).libraryDao(),
    private val repository: LibraryRepository = LibraryRepository(context),
    private val fileStore: LibraryFileStore = LibraryFileStore(context),
    private val extractor: PdfEmbeddedTextExtractor = PdfBoxEmbeddedTextExtractor(context),
    private val runner: ScriptedOcrSessionRunner = ScriptedOcrSessionRunner(
        MultilingualOcrRuntime.get(context).recognitionEngine,
    ),
    private val operationGate: LibraryOperationGate = LibraryOperationCoordinator.gate,
    private val defaultOcrSelection: () -> OcrScriptSelection = {
        SharedPreferencesOcrLanguagePreferenceStore(context).read()
    },
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun prepare(
        documentIds: List<String>,
        cancellationSignal: MigrationCancellationSignal,
        progressListener: SearchPreparationProgressListener,
    ): SearchPreparationReport {
        val operationId = createOperation(documentIds.distinct())
        return runOperation(operationId, cancellationSignal, progressListener)
    }

    suspend fun retry(
        operationId: String,
        cancellationSignal: MigrationCancellationSignal,
        progressListener: SearchPreparationProgressListener,
    ): SearchPreparationReport {
        operationGate.withMutation {
            val operation = requireNotNull(dao.operation(operationId))
            require(operation.operationType == OPERATION_TYPE)
            dao.operationItems(operationId).forEach { item ->
                if (item.itemState in setOf(STATE_FAILED, STATE_PARTIAL, STATE_CANCELLED)) {
                    dao.updateOperationItem(
                        item.copy(
                            itemState = STATE_PENDING,
                            targetRevisionId = null,
                            failureCode = null,
                        ),
                    )
                }
            }
            dao.updateOperation(
                operation.copy(
                    phase = PHASE_PENDING,
                    cancelRequested = false,
                    terminalErrorCode = null,
                    updatedAtMillis = nowMillis(),
                ),
            )
        }
        return runOperation(operationId, cancellationSignal, progressListener)
    }

    suspend fun recoverInterruptedOperations(): Int {
        repository.markInterruptedOcrBatchJobs()
        val operations = operationGate.withStableSnapshot {
            dao.recoverableOperations().filter { it.operationType == OPERATION_TYPE }
        }
        operations.forEach { operation ->
            operationGate.withMutation {
                dao.operationItems(operation.operationId).forEach { item ->
                    if (item.itemState in setOf(STATE_EXTRACTING, STATE_OCR_PROCESSING, STATE_INDEXING)) {
                        dao.updateOperationItem(item.copy(itemState = STATE_PENDING))
                    }
                }
                dao.updateOperation(
                    operation.copy(
                        phase = PHASE_PENDING,
                        updatedAtMillis = nowMillis(),
                    ),
                )
            }
            runOperation(
                operation.operationId,
                NeverCancelMigration,
                SearchPreparationProgressListener { },
            )
        }
        return operations.size
    }

    suspend fun cancel(operationId: String): Boolean = operationGate.withMutation {
        dao.requestOperationCancellation(operationId, nowMillis()) == 1
    }

    private suspend fun createOperation(documentIds: List<String>): String =
        operationGate.withMutation {
            require(documentIds.isNotEmpty())
            val documents = documentIds.mapNotNull { dao.document(it) }
            require(documents.size == documentIds.size)
            val now = nowMillis()
            val operationId = newId()
            val items = documents.mapIndexed { ordinal, document ->
                LibraryDataOperationItemEntity(
                    itemId = "$operationId|${document.documentId}",
                    operationId = operationId,
                    ordinal = ordinal,
                    itemState = STATE_PENDING,
                    targetDocumentId = document.documentId,
                    targetRevisionId = null,
                    proposedTitle = document.title,
                    proposedFolderPath = null,
                    sourceModifiedAtMillis = document.sourceModifiedAtMillis,
                    sourceByteCount = document.contentByteCount,
                    sourcePageCount = document.pageCount,
                    logicalHashVersion = document.contentHashVersion,
                    logicalSha256 = document.contentSha256,
                    duplicateKind = "DIFFERENT",
                    duplicateDocumentId = null,
                    duplicateDecision = "SEARCH_PREPARATION",
                    failureCode = null,
                )
            }
            dao.insertRestoreOperation(
                LibraryDataOperationEntity(
                    operationId = operationId,
                    operationType = OPERATION_TYPE,
                    phase = PHASE_PENDING,
                    sourceKind = "LOCAL_LIBRARY",
                    sourceRootUri = null,
                    sourceGrantFlags = 0,
                    createdAtMillis = now,
                    updatedAtMillis = now,
                    discoveredItemCount = documents.size,
                    plannedDocumentCount = documents.sumOf(LibraryDocumentEntity::pageCount),
                    preparedDocumentCount = 0,
                    importedDocumentCount = 0,
                    skippedDuplicateCount = 0,
                    failedItemCount = 0,
                    totalSourceBytes = null,
                    processedSourceBytes = 0,
                    cancelRequested = false,
                    terminalErrorCode = null,
                ),
                items,
            )
            operationId
        }

    private suspend fun runOperation(
        operationId: String,
        cancellationSignal: MigrationCancellationSignal,
        progressListener: SearchPreparationProgressListener,
    ): SearchPreparationReport {
        var aggregate = operationProgress(operationId)
        progressListener.onProgress(aggregate)
        val items = operationGate.withStableSnapshot { dao.operationItems(operationId) }
        for (item in items) {
            currentCoroutineContext().ensureActive()
            if (item.itemState in TERMINAL_ITEM_STATES) continue
            if (isCancelled(operationId, cancellationSignal)) {
                markRemainingCancelled(operationId)
                break
            }
            try {
                processDocument(operationId, item, cancellationSignal) { pagePosition ->
                    aggregate = operationProgress(operationId).copy(
                        currentDocumentId = item.targetDocumentId,
                        currentPagePosition = pagePosition,
                    )
                    progressListener.onProgress(aggregate)
                }
            } catch (cancelled: CancellationException) {
                if (!isCancelled(operationId, cancellationSignal)) throw cancelled
                markRemainingCancelled(operationId)
                break
            } catch (_: Exception) {
                val current = operationGate.withStableSnapshot {
                    dao.operationItem(item.itemId)
                } ?: continue
                transition(operationId, current, STATE_FAILED, ERROR_PREPARATION_FAILED)
            }
            aggregate = operationProgress(operationId)
            progressListener.onProgress(aggregate)
        }
        settleOperation(operationId)
        return report(operationId)
    }

    private suspend fun processDocument(
        operationId: String,
        originalItem: LibraryDataOperationItemEntity,
        cancellationSignal: MigrationCancellationSignal,
        onPage: suspend (Int?) -> Unit,
    ) {
        var item = operationGate.withStableSnapshot {
            dao.operationItem(originalItem.itemId)
        } ?: return
        transition(operationId, item, STATE_EXTRACTING, null)
        val document = operationGate.withStableSnapshot { dao.document(item.targetDocumentId) }
        val pages = operationGate.withStableSnapshot { dao.pages(item.targetDocumentId) }
        if (document == null || pages.size != document.pageCount) {
            transition(operationId, item, STATE_FAILED, ERROR_SOURCE_MISSING)
            return
        }
        val originalPdf = operationGate.withStableSnapshot {
            dao.sourceAssets(document.documentId).singleOrNull { asset ->
                asset.role == ORIGINAL_DOCUMENT_ROLE &&
                    asset.contentType.equals(PDF_MIME_TYPE, ignoreCase = true) &&
                    asset.matchesCurrentRevision
            }
        }
        val ocrRequired = linkedSetOf<String>()
        if (originalPdf != null) {
            val sourceFile = fileStore.resolve(originalPdf.relativePath)?.takeIf { it.isFile }
            val extraction = if (sourceFile == null) {
                PdfEmbeddedTextExtraction.Failed(TextUsefulnessReason.EXTRACTION_FAILED)
            } else {
                extractor.extract(
                    sourcePdf = sourceFile,
                    expectedPageCount = pages.size,
                    isCancellationRequested = { cancellationSignal.isCancellationRequested() },
                ) { extracted ->
                    currentCoroutineContext().ensureActive()
                    if (isCancelled(operationId, cancellationSignal)) throw CancellationException()
                    val page = pages[extracted.pageIndex]
                    onPage(page.position)
                    val usefulness = extracted.text?.let(TextUsefulnessEvaluator::evaluate)
                        ?: TextUsefulness.NeedsOcr(
                            extracted.failure ?: TextUsefulnessReason.EXTRACTION_FAILED,
                        )
                    if (usefulness is TextUsefulness.Useful) {
                        val applied = commitEmbeddedText(
                            page,
                            originalPdf.sha256,
                            usefulness.normalizedText,
                        )
                        if (!applied) ocrRequired += page.pageId
                    } else {
                        ocrRequired += page.pageId
                    }
                }
            }
            when (extraction) {
                is PdfEmbeddedTextExtraction.Completed -> Unit
                is PdfEmbeddedTextExtraction.Failed ->
                    ocrRequired += pages.map(LibraryPageEntity::pageId)
            }
        } else {
            ocrRequired += pages.map(LibraryPageEntity::pageId)
        }

        if (ocrRequired.isNotEmpty()) {
            item = requireNotNull(operationGate.withStableSnapshot { dao.operationItem(item.itemId) })
            transition(operationId, item, STATE_OCR_PROCESSING, null)
            val jobId = ensureOcrJob(item, document, pages.filter { it.pageId in ocrRequired })
            if (jobId == null) {
                transition(operationId, item, STATE_FAILED, ERROR_OCR_JOB)
                return
            }
            item = requireNotNull(operationGate.withStableSnapshot { dao.operationItem(item.itemId) })
            runOcrJob(jobId, document.documentId, cancellationSignal, onPage)
        }

        item = requireNotNull(operationGate.withStableSnapshot { dao.operationItem(item.itemId) })
        if (isCancelled(operationId, cancellationSignal)) {
            item.targetRevisionId?.let { repository.cancelOcrBatchJob(it) }
            transition(operationId, item, STATE_CANCELLED, ERROR_CANCELLED)
            return
        }
        transition(operationId, item, STATE_INDEXING, null)
        val pageStates = operationGate.withStableSnapshot { dao.pages(document.documentId) }
        val ready = pageStates.count { it.activeOcrArtifactRevision != null }
        val failures = pageStates.count { it.activeOcrArtifactRevision == null && it.ocrError != null }
        val terminal = when {
            ready == pageStates.size -> STATE_READY
            ready > 0 -> STATE_PARTIAL
            failures > 0 -> STATE_FAILED
            else -> STATE_FAILED
        }
        transition(
            operationId,
            requireNotNull(operationGate.withStableSnapshot { dao.operationItem(item.itemId) }),
            terminal,
            if (terminal == STATE_READY) null else ERROR_PAGE_FAILURE,
        )
        onPage(null)
    }

    private suspend fun commitEmbeddedText(
        page: LibraryPageEntity,
        sourceSha256: String,
        text: String,
    ): Boolean {
        val descriptor = "pdf-sha256:$sourceSha256:page:${page.position}:extractor:$EXTRACTOR_VERSION"
        val current = repository.activeTextArtifact(page.pageId)
        if (current?.textSourceKind == LibraryTextSourceKind.PDF_EMBEDDED_TEXT.name &&
            current.sourceDescriptor == descriptor && current.rawText == text
        ) {
            return true
        }
        val snapshot = repository.captureOcrPageSnapshot(page.documentId, page.pageId) ?: return false
        val contentSha256 = snapshot.contentSha256 ?: return false
        val width = page.width?.takeIf { it > 0 } ?: return false
        val height = page.height?.takeIf { it > 0 } ?: return false
        val fingerprint = sha256("$descriptor\u0000$contentSha256\u0000${page.visualRevision}")
        return repository.commitOcrArtifact(
            snapshot,
            LibraryOcrArtifactDraft(
                inputFingerprintVersion = 1,
                inputFingerprint = "sha256:$fingerprint",
                contentSha256 = contentSha256,
                rotationDegrees = page.rotationDegrees,
                filterName = page.filterName,
                uprightWidth = width,
                uprightHeight = height,
                coordinateSystemVersion = 1,
                transformVersion = 1,
                actualScript = null,
                recognizerId = PDF_TEXT_RECOGNIZER_ID,
                pipelineVersion = EXTRACTOR_VERSION,
                clientVersion = null,
                delivery = "LOCAL_PDFBOX",
                recognizedAtMillis = nowMillis(),
                rawText = text,
                textSourceKind = LibraryTextSourceKind.PDF_EMBEDDED_TEXT,
                sourceDescriptor = descriptor,
            ),
        ) == LibraryOcrCommitResult.APPLIED
    }

    private suspend fun ensureOcrJob(
        item: LibraryDataOperationItemEntity,
        document: LibraryDocumentEntity,
        pages: List<LibraryPageEntity>,
    ): String? {
        item.targetRevisionId?.let { existing ->
            val job = repository.ocrBatchJob(existing)
            if (job != null) {
                if (job.state == OcrBatchJobState.INTERRUPTED.name) {
                    repository.recoverInterruptedOcrBatchJob(existing)
                }
                return existing
            }
        }
        if (pages.isEmpty()) return null
        val selection = OcrScriptSelection.fromStableId(document.ocrScriptPreference)
            ?: defaultOcrSelection()
        val recommendation = OcrLocaleScriptMapper.recommend(Locale.getDefault())
        val resolved = when (selection) {
            OcrScriptSelection.Automatic -> recommendation.script
            is OcrScriptSelection.Explicit -> selection.script
        }
        val now = nowMillis()
        val jobId = newId()
        val job = OcrBatchJobEntity(
            jobId = jobId,
            selectionPolicy = "MISSING_ONLY",
            requestedScriptSelection = selection.stableId,
            localeRecommendationSnapshot = recommendation.script.stableId,
            state = OcrBatchJobState.READY.name,
            totalItemCount = 0,
            completedItemCount = 0,
            failedItemCount = 0,
            skippedItemCount = 0,
            createdAtMillis = now,
            updatedAtMillis = now,
            runGeneration = 1,
            cancelRequested = false,
            targetPopulationComplete = false,
            terminalErrorCode = null,
        )
        val targets = pages.mapIndexed { ordinal, page ->
            OcrBatchTarget(
                itemId = newId(),
                documentId = document.documentId,
                pageId = page.pageId,
                ordinal = ordinal,
                requestedScriptSelection = selection.stableId,
                resolvedScript = resolved.stableId,
            )
        }
        if (!repository.freezeOcrBatchTargets(job, targets)) return null
        operationGate.withMutation {
            dao.updateOperationItem(item.copy(targetRevisionId = jobId, itemState = STATE_OCR_PROCESSING))
        }
        return jobId
    }

    private suspend fun runOcrJob(
        jobId: String,
        documentId: String,
        cancellationSignal: MigrationCancellationSignal,
        onPage: suspend (Int?) -> Unit,
    ) {
        val pages = operationGate.withStableSnapshot {
            dao.pages(documentId).associateBy(LibraryPageEntity::pageId)
        }
        while (true) {
            currentCoroutineContext().ensureActive()
            if (cancellationSignal.isCancellationRequested()) {
                repository.cancelOcrBatchJob(jobId)
                return
            }
            val job = repository.ocrBatchJob(jobId) ?: return
            if (job.cancelRequested || job.state == OcrBatchJobState.CANCELLED.name) return
            val item = repository.nextOcrBatchItem(jobId) ?: return
            val claim = repository.claimOcrBatchItem(jobId, item.itemId, job.runGeneration, newId())
                ?: continue
            onPage(claim.expected.pagePosition)
            val page = pages[claim.pageId]
            val pageFile = page?.let { fileStore.resolve(it.relativePath) }?.takeIf(File::isFile)
            val script = OcrScript.fromStableId(claim.resolvedScript)
            if (page == null || pageFile == null || script == null) {
                repository.failOcrBatchItem(claim, ERROR_SOURCE_MISSING)
                continue
            }
            try {
                val recognized = runner.recognizePage(
                    page = OcrPage(
                        rotationDegrees = page.rotationDegrees,
                        imageMetadata = DocumentImageMetadata(
                            sourceByteCount = page.sourceByteCount,
                            width = page.width,
                            height = page.height,
                        ),
                    ) {
                        FileInputStream(pageFile)
                    },
                    script = script,
                    pagePosition = claim.expected.pagePosition,
                    savedSnapshot = claim.expected,
                    recognizedAtMillis = nowMillis(),
                )
                val artifact = (recognized.persistence as? OcrPagePersistenceMapping.Ready)
                    ?.outcome?.artifact
                if (artifact != null && recognized.outcome is OcrPageRecognitionOutcome.Success) {
                    repository.completeOcrBatchItem(claim, artifact)
                } else {
                    repository.failOcrBatchItem(claim, ERROR_OCR_FAILED)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                repository.failOcrBatchItem(claim, ERROR_OCR_FAILED)
            }
        }
    }

    private suspend fun transition(
        operationId: String,
        item: LibraryDataOperationItemEntity,
        state: String,
        failure: String?,
    ) = operationGate.withMutation {
        dao.updateOperationItem(item.copy(itemState = state, failureCode = failure))
        val operation = requireNotNull(dao.operation(operationId))
        val items = dao.operationItems(operationId)
        dao.updateOperation(
            operation.copy(
                phase = when {
                    state == STATE_CANCELLED -> PHASE_CANCELLED
                    items.all { it.itemState in TERMINAL_ITEM_STATES } -> PHASE_COMPLETED
                    else -> PHASE_RUNNING
                },
                updatedAtMillis = nowMillis(),
                preparedDocumentCount = items.count { it.itemState in TERMINAL_ITEM_STATES },
                importedDocumentCount = items.count { it.itemState == STATE_READY },
                failedItemCount = items.count { it.itemState in setOf(STATE_PARTIAL, STATE_FAILED) },
                cancelRequested = operation.cancelRequested || state == STATE_CANCELLED,
            ),
        )
    }

    private suspend fun settleOperation(operationId: String) = operationGate.withMutation {
        val operation = requireNotNull(dao.operation(operationId))
        val items = dao.operationItems(operationId)
        val cancelled = operation.cancelRequested || items.any { it.itemState == STATE_CANCELLED }
        dao.updateOperation(
            operation.copy(
                phase = if (cancelled) PHASE_CANCELLED else PHASE_COMPLETED,
                updatedAtMillis = nowMillis(),
                preparedDocumentCount = items.count { it.itemState in TERMINAL_ITEM_STATES },
                importedDocumentCount = items.count { it.itemState == STATE_READY },
                failedItemCount = items.count { it.itemState in setOf(STATE_PARTIAL, STATE_FAILED) },
                terminalErrorCode = if (cancelled) ERROR_CANCELLED else null,
            ),
        )
    }

    private suspend fun markRemainingCancelled(operationId: String) {
        val jobIds = operationGate.withMutation {
            val jobs = mutableListOf<String>()
            dao.operationItems(operationId).forEach { item ->
                item.targetRevisionId?.let(jobs::add)
                if (item.itemState !in TERMINAL_ITEM_STATES) {
                    dao.updateOperationItem(item.copy(itemState = STATE_CANCELLED, failureCode = ERROR_CANCELLED))
                }
            }
            dao.operation(operationId)?.let { operation ->
                dao.updateOperation(
                    operation.copy(
                        phase = PHASE_CANCELLED,
                        cancelRequested = true,
                        updatedAtMillis = nowMillis(),
                        terminalErrorCode = ERROR_CANCELLED,
                    ),
                )
            }
            jobs
        }
        jobIds.forEach { repository.cancelOcrBatchJob(it) }
    }

    private suspend fun isCancelled(
        operationId: String,
        signal: MigrationCancellationSignal,
    ): Boolean = signal.isCancellationRequested() || operationGate.withStableSnapshot {
        dao.operation(operationId)?.cancelRequested == true
    }

    private suspend fun operationProgress(operationId: String): SearchPreparationProgress {
        val items = operationGate.withStableSnapshot { dao.operationItems(operationId) }
        var totalPages = 0
        var processedPages = 0
        var embedded = 0
        var ocr = 0
        var failed = 0
        items.forEach { item ->
            val pages = operationGate.withStableSnapshot { dao.pages(item.targetDocumentId) }
            val failedOcrPageIds = item.targetRevisionId
                ?.let { repository.ocrBatchItems(it) }
                .orEmpty()
                .filter { it.state in setOf(OcrBatchItemState.FAILED.name, OcrBatchItemState.SKIPPED.name) }
                .mapTo(hashSetOf()) { it.pageId }
            totalPages += pages.size
            pages.forEach { page ->
                val artifact = repository.activeTextArtifact(page.pageId)
                when (artifact?.textSourceKind) {
                    LibraryTextSourceKind.PDF_EMBEDDED_TEXT.name -> {
                        embedded += 1
                        processedPages += 1
                    }
                    LibraryTextSourceKind.LOCAL_OCR.name -> {
                        ocr += 1
                        processedPages += 1
                    }
                    else -> if (page.ocrError != null || page.pageId in failedOcrPageIds) {
                        failed += 1
                        processedPages += 1
                    }
                }
            }
        }
        return SearchPreparationProgress(
            processedDocuments = items.count { it.itemState in TERMINAL_ITEM_STATES },
            totalDocuments = items.size,
            processedPages = processedPages,
            totalPages = totalPages,
            embeddedTextPages = embedded,
            ocrPages = ocr,
            failedPages = failed,
        )
    }

    private suspend fun report(operationId: String): SearchPreparationReport {
        val operation = operationGate.withStableSnapshot { requireNotNull(dao.operation(operationId)) }
        val items = operationGate.withStableSnapshot { dao.operationItems(operationId) }
        val documents = items.map { item ->
            val pages = operationGate.withStableSnapshot { dao.pages(item.targetDocumentId) }
            val artifacts = pages.map { page -> repository.activeTextArtifact(page.pageId) }
            SearchPreparationDocumentResult(
                documentId = item.targetDocumentId,
                outcome = when (item.itemState) {
                    STATE_READY -> SearchPreparationOutcome.SEARCH_READY
                    STATE_PARTIAL -> SearchPreparationOutcome.PARTIAL
                    STATE_CANCELLED -> SearchPreparationOutcome.CANCELLED
                    else -> SearchPreparationOutcome.FAILED
                },
                pageCount = pages.size,
                embeddedTextPages = artifacts.count {
                    it?.textSourceKind == LibraryTextSourceKind.PDF_EMBEDDED_TEXT.name
                },
                ocrPages = artifacts.count {
                    it?.textSourceKind == LibraryTextSourceKind.LOCAL_OCR.name
                },
                failedPages = pages.count { it.activeOcrArtifactRevision == null },
            )
        }
        return SearchPreparationReport(
            operationId = operationId,
            documents = documents,
            cancelled = operation.cancelRequested || operation.phase == PHASE_CANCELLED,
        )
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val OPERATION_TYPE = "SEARCH_PREPARATION_V1"
        const val PHASE_PENDING = "TEXT_ANALYSIS_PENDING"
        const val PHASE_RUNNING = "PROCESSING"
        const val PHASE_COMPLETED = "COMPLETED"
        const val PHASE_CANCELLED = "CANCELLED"
        const val STATE_PENDING = "TEXT_ANALYSIS_PENDING"
        const val STATE_EXTRACTING = "EXTRACTING"
        const val STATE_OCR_PROCESSING = "OCR_PROCESSING"
        const val STATE_INDEXING = "INDEXING"
        const val STATE_READY = "SEARCH_READY"
        const val STATE_PARTIAL = "PARTIAL_SEARCH_READY"
        const val STATE_FAILED = "FAILED"
        const val STATE_CANCELLED = "CANCELLED"
        val TERMINAL_ITEM_STATES = setOf(STATE_READY, STATE_PARTIAL, STATE_FAILED, STATE_CANCELLED)
        const val ORIGINAL_DOCUMENT_ROLE = "ORIGINAL_DOCUMENT"
        const val PDF_MIME_TYPE = "application/pdf"
        const val PDF_TEXT_RECOGNIZER_ID = "PDF_TEXT_LAYER_V1"
        const val EXTRACTOR_VERSION = "PDF_TEXT_LAYER_V1"
        const val ERROR_SOURCE_MISSING = "SOURCE_MISSING"
        const val ERROR_OCR_JOB = "OCR_JOB_FAILED"
        const val ERROR_OCR_FAILED = "OCR_FAILED"
        const val ERROR_PAGE_FAILURE = "PAGE_PREPARATION_FAILED"
        const val ERROR_PREPARATION_FAILED = "SEARCH_PREPARATION_FAILED"
        const val ERROR_CANCELLED = "CANCELLED"
    }
}
