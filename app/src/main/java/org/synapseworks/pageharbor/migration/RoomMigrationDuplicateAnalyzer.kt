package org.synapseworks.pageharbor.migration

import android.content.Context
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.synapseworks.pageharbor.library.LibraryDao
import org.synapseworks.pageharbor.library.LibraryDatabase
import org.synapseworks.pageharbor.library.LibraryOperationCoordinator
import org.synapseworks.pageharbor.library.LibraryOperationGate
import org.synapseworks.pageharbor.library.LibraryPageEntity
import org.synapseworks.pageharbor.library.duplicate.DuplicateCandidate
import org.synapseworks.pageharbor.library.duplicate.DuplicateDetector
import org.synapseworks.pageharbor.portability.StoragePreflight
import org.synapseworks.pageharbor.portability.StoragePreflightResult

/** Reads a bounded identity-only snapshot; no document bytes or names leave local storage. */
internal fun interface MigrationDuplicateCandidateSnapshot {
    suspend fun snapshot(
        cancellationSignal: MigrationCancellationSignal,
    ): List<DuplicateCandidate>
}

internal class RoomMigrationDuplicateCandidateSource(
    private val dao: LibraryDao,
    private val operationGate: LibraryOperationGate = LibraryOperationCoordinator.gate,
) : MigrationDuplicateCandidateSnapshot {
    override suspend fun snapshot(
        cancellationSignal: MigrationCancellationSignal,
    ): List<DuplicateCandidate> = operationGate.withStableSnapshot {
        val result = mutableListOf<DuplicateCandidate>()
        var afterRowId = -1L
        while (true) {
            currentCoroutineContext().ensureActive()
            if (cancellationSignal.isCancellationRequested()) throw CancellationException()
            val documents = dao.activeDocumentsPage(afterRowId, DATABASE_PAGE_SIZE)
            if (documents.isEmpty()) break
            documents.forEach { document ->
                val pages = readAllPages(document.documentId, cancellationSignal)
                val sourceAssets = dao.sourceAssets(document.documentId)
                result += DuplicateCandidate(
                    documentId = document.documentId,
                    contentHashVersion = document.contentHashVersion,
                    contentSha256 = document.contentSha256,
                    sourceSha256 = sourceAssets.mapTo(linkedSetOf()) { it.sha256 },
                    pageCount = document.pageCount,
                    contentByteLength = pages.map { it.sourceByteCount }.sumIfAllKnownValues(),
                    orderedMimeTypes = pages.map { it.contentType },
                )
            }
            afterRowId = documents.last().rowId
            if (documents.size < DATABASE_PAGE_SIZE) break
        }
        result
    }

    private suspend fun readAllPages(
        documentId: String,
        cancellationSignal: MigrationCancellationSignal,
    ): List<LibraryPageEntity> {
        val pages = mutableListOf<LibraryPageEntity>()
        var afterPosition = -1
        while (true) {
            currentCoroutineContext().ensureActive()
            if (cancellationSignal.isCancellationRequested()) throw CancellationException()
            val next = dao.pagesPage(documentId, afterPosition, DATABASE_PAGE_SIZE)
            if (next.isEmpty()) break
            pages += next
            afterPosition = next.last().position
            if (next.size < DATABASE_PAGE_SIZE) break
        }
        return pages
    }

    private companion object {
        const val DATABASE_PAGE_SIZE = 256
    }
}

/**
 * Read-only duplicate preview. Full PDF identity requires rendering, so this intentionally stages
 * and deletes one document at a time; execution restages and re-checks immediately before commit.
 */
internal class RoomMigrationDuplicateAnalyzer(
    private val storageCapacity: MigrationStorageCapacity,
    private val candidates: MigrationDuplicateCandidateSnapshot,
    private val stager: MigrationDocumentStager,
) {
    constructor(
        context: Context,
        sourceAccess: MigrationSourceAccess,
        storageCapacity: MigrationStorageCapacity,
    ) : this(
        storageCapacity = storageCapacity,
        candidates = RoomMigrationDuplicateCandidateSource(
            LibraryDatabase.get(context).libraryDao(),
        ),
        stager = AndroidMigrationDocumentStager(
            File(context.applicationContext.cacheDir, PREVIEW_WORKSPACE_DIRECTORY),
            sourceAccess,
        ),
    )

    suspend fun analyze(
        preview: MigrationPreview,
        cancellationSignal: MigrationCancellationSignal,
        progressListener: MigrationBatchProgressListener,
    ): MigrationPreview {
        if (preview.wasCancelled || cancellationSignal.isCancellationRequested()) {
            return preview.copy(duplicateAnalysisComplete = false, wasCancelled = true)
        }
        val existing = try {
            candidates.snapshot(cancellationSignal)
        } catch (cancelled: CancellationException) {
            if (cancellationSignal.isCancellationRequested()) {
                return preview.copy(duplicateAnalysisComplete = false, wasCancelled = true)
            }
            throw cancelled
        } catch (_: Exception) {
            return preview.copy(
                duplicateDocuments = preview.documents.map { plan ->
                    MigrationDuplicatePreview(
                        documentId = plan.id,
                        duplicateKind = null,
                        analyzedPageCount = null,
                        analysisFailure = MigrationPublicationFailure.WRITE_FAILED,
                    )
                },
                duplicateAnalysisComplete = false,
            )
        }

        stager.cleanAbandonedWorkspaces()
        val results = mutableListOf<MigrationDuplicatePreview>()
        var cancelled = false
        progressListener.onProgress(MigrationBatchProgress(0, preview.documents.size, null))
        for (plan in preview.documents) {
            currentCoroutineContext().ensureActive()
            if (cancellationSignal.isCancellationRequested()) {
                cancelled = true
                break
            }
            if (!hasStorageForPreview(plan)) {
                results += MigrationDuplicatePreview(
                    documentId = plan.id,
                    duplicateKind = null,
                    analyzedPageCount = null,
                    analysisFailure = MigrationPublicationFailure.INSUFFICIENT_STORAGE,
                )
                progressListener.onProgress(
                    MigrationBatchProgress(results.size, preview.documents.size, null),
                )
                continue
            }
            progressListener.onProgress(
                MigrationBatchProgress(results.size, preview.documents.size, plan.id),
            )
            val stagingContext = MigrationPublicationContext(
                cancellationSignal = cancellationSignal,
                onBytesCopied = { copied, total ->
                    val progress = MigrationBatchProgress(
                        completedDocuments = results.size,
                        totalDocuments = preview.documents.size,
                        currentDocumentId = plan.id,
                        currentDocumentBytesCopied = copied,
                        currentDocumentTotalBytes = total,
                    )
                    progressListener.onProgress(progress)
                },
            )
            when (val staged = stager.stage(plan, stagingContext)) {
                MigrationStagingResult.Cancelled -> {
                    cancelled = true
                    break
                }
                is MigrationStagingResult.Failed -> results += MigrationDuplicatePreview(
                    documentId = plan.id,
                    duplicateKind = null,
                    analyzedPageCount = null,
                    analysisFailure = staged.reason,
                )
                is MigrationStagingResult.Ready -> staged.document.use { document ->
                    val fingerprint = document.fingerprintForMigration()
                    val duplicate = DuplicateDetector.classify(
                        document.incomingIdentityForMigration(fingerprint),
                        existing,
                    )
                    results += MigrationDuplicatePreview(
                        documentId = plan.id,
                        duplicateKind = duplicate.kind,
                        analyzedPageCount = document.pages.size,
                        existingDocumentId = duplicate.documentId,
                    )
                }
            }
            progressListener.onProgress(
                MigrationBatchProgress(results.size, preview.documents.size, null),
            )
        }
        val complete = !cancelled &&
            results.size == preview.documents.size &&
            results.all { it.duplicateKind != null }
        return preview.copy(
            duplicateDocuments = results,
            duplicateAnalysisComplete = complete,
            wasCancelled = preview.wasCancelled || cancelled,
        )
    }

    private fun hasStorageForPreview(plan: MigrationDocumentPlan): Boolean {
        val estimate = StoragePreflight.estimate(
            sourceSizes = plan.sources.map { it.source.sizeBytes },
            workingCopyCount = if (plan.grouping == MigrationDocumentGrouping.SINGLE_PDF) 2 else 1,
            fixedOverheadBytes = PREVIEW_STORAGE_OVERHEAD_BYTES,
        )
        return StoragePreflight.evaluate(estimate, storageCapacity.availableBytes()) !is
            StoragePreflightResult.Insufficient
    }

    private companion object {
        const val PREVIEW_WORKSPACE_DIRECTORY = "bulk-migration-preview"
        const val PREVIEW_STORAGE_OVERHEAD_BYTES = 512L * 1024L
    }
}

private fun List<Long?>.sumIfAllKnownValues(): Long? {
    if (any { it == null }) return null
    return fold(0L) { total, value ->
        val next = requireNotNull(value)
        if (Long.MAX_VALUE - total < next) Long.MAX_VALUE else total + next
    }
}
