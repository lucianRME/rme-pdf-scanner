package org.synapseworks.pageharbor.backup.restore

import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.synapseworks.pageharbor.backup.format.BackupArchiveReader
import org.synapseworks.pageharbor.backup.format.BackupFormatException
import org.synapseworks.pageharbor.backup.format.BackupFormatLimits
import org.synapseworks.pageharbor.backup.format.BackupPageRecord
import org.synapseworks.pageharbor.library.LibraryOperationCoordinator
import org.synapseworks.pageharbor.library.LibraryOperationGate
import org.synapseworks.pageharbor.library.completeAtomicActivation
import org.synapseworks.pageharbor.library.duplicate.DocumentFingerprintV1
import org.synapseworks.pageharbor.library.duplicate.DuplicateDetector
import org.synapseworks.pageharbor.library.duplicate.DuplicateKind
import org.synapseworks.pageharbor.library.duplicate.FingerprintPage
import org.synapseworks.pageharbor.library.duplicate.IncomingDocumentIdentity

/**
 * Scheduler-independent staged restore. Archive validation and preview are complete before any
 * journal or pending library row is written. Publication is serialized with all library mutations.
 */
internal class LibraryRestoreEngine(
    private val store: RestoreLibraryStore,
    private val stagingWorkspace: RestoreStagingWorkspace,
    private val operationGate: LibraryOperationGate = LibraryOperationCoordinator.gate,
    private val storagePreflight: RestoreStoragePreflight = DefaultRestoreStoragePreflight,
    private val idSource: RestoreIdSource = RestoreIdSource { UUID.randomUUID().toString() },
    private val clock: RestoreClock = RestoreClock { System.currentTimeMillis() },
    private val supportedRequiredFeatures: Set<String> = emptySet(),
    private val limits: BackupFormatLimits = BackupFormatLimits(),
) {
    suspend fun prepare(source: RestoreArchiveSource): RestorePreparationResult {
        val initialEstimate = RestoreStorageEstimator.beforeStaging(
            source.byteLength,
            stagingWorkspace.availableBytes(),
        )
        if (!storagePreflight.hasCapacity(initialEstimate)) {
            return RestorePreparationResult.Failed(RestorePreparationFailure.INSUFFICIENT_STORAGE)
        }

        val operationId = idSource.newId()
        val staging = try {
            stagingWorkspace.create(operationId)
        } catch (_: Exception) {
            return RestorePreparationResult.Failed(RestorePreparationFailure.STAGING_UNAVAILABLE)
        }
        val verified = try {
            source.openStream().use { input ->
                BackupArchiveReader.readAndVerify(
                    source = input,
                    staging = staging,
                    supportedRequiredFeatures = supportedRequiredFeatures,
                    limits = limits,
                )
            }
        } catch (cancelled: CancellationException) {
            staging.discard()
            throw cancelled
        } catch (failure: BackupFormatException) {
            staging.discard()
            return RestorePreparationResult.Failed(
                RestorePreparationFailure.INVALID_OR_CORRUPT_BACKUP,
                failure.failure.name,
            )
        } catch (_: IOException) {
            staging.discard()
            return RestorePreparationResult.Failed(
                RestorePreparationFailure.INVALID_OR_CORRUPT_BACKUP,
            )
        } catch (_: Exception) {
            staging.discard()
            return RestorePreparationResult.Failed(RestorePreparationFailure.STAGING_UNAVAILABLE)
        }

        val activationEstimate = RestoreStorageEstimator.beforeActivation(
            verified.manifest.summary.contentByteLength,
            stagingWorkspace.availableBytes(),
        )
        if (!storagePreflight.hasCapacity(activationEstimate)) {
            staging.discard()
            return RestorePreparationResult.Failed(RestorePreparationFailure.INSUFFICIENT_STORAGE)
        }

        val documents = try {
            buildDocumentBundles(verified.documents, verified.pages, verified.sourceAssets)
        } catch (_: IllegalArgumentException) {
            staging.discard()
            return RestorePreparationResult.Failed(
                RestorePreparationFailure.INVALID_OR_CORRUPT_BACKUP,
            )
        }
        val candidates = try {
            store.duplicateCandidates()
        } catch (cancelled: CancellationException) {
            staging.discard()
            throw cancelled
        } catch (_: Exception) {
            staging.discard()
            return RestorePreparationResult.Failed(RestorePreparationFailure.LIBRARY_UNAVAILABLE)
        }
        val previewDocuments = documents.map { bundle ->
            val duplicate = DuplicateDetector.classify(bundle.incomingIdentity(), candidates)
            RestorePreviewDocument(
                backupDocumentId = bundle.document.documentId,
                title = bundle.document.title,
                pageCount = bundle.pages.size,
                sourceAssetCount = bundle.sourceAssets.size,
                duplicateKind = duplicate.kind,
                duplicateDocumentId = duplicate.documentId,
            )
        }
        val summary = verified.manifest.summary
        val preview = RestorePreview(
            backupId = verified.manifest.backupId,
            createdAtEpochMillis = verified.manifest.createdAtEpochMillis,
            folderCount = summary.folderCount,
            documentCount = summary.documentCount,
            pageCount = summary.pageCount,
            sourceAssetCount = summary.sourceAssetCount,
            contentByteLength = summary.contentByteLength,
            documents = previewDocuments,
        )
        return RestorePreparationResult.Ready(
            PreparedRestore(preview, verified, documents, staging),
        )
    }

    suspend fun restore(
        prepared: PreparedRestore,
        policy: RestoreMergePolicy,
        cancellationSignal: RestoreCancellationSignal = NeverCancelRestore,
        progressListener: RestoreProgressListener = RestoreProgressListener { },
    ): RestoreResult {
        if (!prepared.claim()) {
            return RestoreResult.Failed(RestoreFailure.ALREADY_CONSUMED, cleanupSucceeded = true)
        }
        if (cancellationSignal.isCancellationRequested()) {
            return RestoreResult.Cancelled(prepared.stagingArea.discard())
        }
        return try {
            operationGate.withMutation {
                restoreWhileLocked(prepared, policy, cancellationSignal, progressListener)
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                cleanupAfterFailure(prepared, cancelled = true, failure = null)
            }
            throw cancelled
        } catch (_: Exception) {
            val cleaned = cleanupAfterFailure(
                prepared,
                cancelled = false,
                failure = RestoreFailure.PREPARATION_FAILED,
            )
            RestoreResult.Failed(RestoreFailure.PREPARATION_FAILED, cleaned)
        }
    }

    suspend fun recoverInterruptedOperations(): Int {
        return operationGate.withMutation {
            val operations = store.recoverableOperations()
            var recovered = 0
            for (operation in operations) {
                val storeCleaned = try {
                    store.terminate(
                        operationId = operation.operationId,
                        cancelled = false,
                        failure = RestoreFailure.PREPARATION_FAILED,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
                val stagingCleaned = stagingWorkspace.discard(operation.operationId)
                if (storeCleaned && stagingCleaned) recovered += 1
            }
            recovered
        }
    }

    private suspend fun restoreWhileLocked(
        prepared: PreparedRestore,
        policy: RestoreMergePolicy,
        cancellationSignal: RestoreCancellationSignal,
        progressListener: RestoreProgressListener,
    ): RestoreResult {
        val candidates = try {
            store.duplicateCandidates()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            prepared.stagingArea.discard()
            return RestoreResult.Failed(RestoreFailure.JOURNAL_UNAVAILABLE, cleanupSucceeded = true)
        }
        val classified = prepared.documents.map { bundle ->
            bundle to DuplicateDetector.classify(bundle.incomingIdentity(), candidates)
        }
        val skipped = if (policy == RestoreMergePolicy.MERGE_SKIP_EXACT) {
            classified.filter { it.second.kind == DuplicateKind.EXACT }.map { it.first }
        } else {
            emptyList()
        }
        val selected = classified.filterNot { (bundle, _) -> bundle in skipped }
        val existingFolders = try {
            store.existingFolders()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            prepared.stagingArea.discard()
            return RestoreResult.Failed(RestoreFailure.JOURNAL_UNAVAILABLE, cleanupSucceeded = true)
        }
        val requiredFolders = requiredBackupFolders(
            prepared.verifiedBackup.folders,
            selected.map { it.first },
        )
        val folderPlan = RestoreFolderPlanner.plan(
            backupFolders = prepared.verifiedBackup.folders,
            requiredFolderIds = requiredFolders,
            existingFolders = existingFolders,
            idSource = idSource,
        )
        val documentsToImport = selected.map { (bundle, duplicate) ->
            RestoreDocumentToPrepare(
                itemId = idSource.newId(),
                targetDocumentId = idSource.newId(),
                targetFolderId = bundle.document.folderId?.let(folderPlan.targetIdByOriginalId::get),
                bundle = bundle,
                duplicateKind = duplicate.kind,
            )
        }
        val operationId = prepared.stagingArea.operationId
        val journal = RestoreJournalPlan(
            operationId = operationId,
            backupId = prepared.preview.backupId,
            createdAtEpochMillis = clock.nowEpochMillis(),
            contentByteLength = prepared.preview.contentByteLength,
            discoveredDocumentCount = prepared.documents.size,
            documentsToImport = documentsToImport,
            skippedExactDocuments = skipped,
        )
        try {
            store.beginOperation(journal)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            prepared.stagingArea.discard()
            return RestoreResult.Failed(RestoreFailure.JOURNAL_UNAVAILABLE, cleanupSucceeded = true)
        }

        notifyProgress(progressListener, RestoreProgress(0, documentsToImport.size))
        try {
            documentsToImport.forEachIndexed { index, document ->
                if (cancellationSignal.isCancellationRequested()) {
                    val cleaned = cleanupAfterFailure(prepared, cancelled = true, failure = null)
                    return RestoreResult.Cancelled(cleaned)
                }
                store.preparePendingDocument(operationId, document, prepared.stagingArea)
                notifyProgress(
                    progressListener,
                    RestoreProgress(index + 1, documentsToImport.size),
                )
            }
            if (cancellationSignal.isCancellationRequested()) {
                val cleaned = cleanupAfterFailure(prepared, cancelled = true, failure = null)
                return RestoreResult.Cancelled(cleaned)
            }
            completeAtomicActivation(
                activate = {
                    store.activate(
                        RestoreActivationPlan(
                            operationId = operationId,
                            folders = folderPlan.foldersToCreate,
                            documents = documentsToImport,
                            activatedAtEpochMillis = clock.nowEpochMillis(),
                        ),
                    )
                },
                isCommitted = { store.isOperationCompleted(operationId) },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: RestoreStoreException) {
            val cleaned = cleanupAfterFailure(prepared, cancelled = false, failure = failure.failure)
            return RestoreResult.Failed(failure.failure, cleaned)
        } catch (_: IOException) {
            val cleaned = cleanupAfterFailure(prepared, cancelled = false, failure = RestoreFailure.SOURCE_MISSING)
            return RestoreResult.Failed(RestoreFailure.SOURCE_MISSING, cleaned)
        } catch (_: Exception) {
            val cleaned = cleanupAfterFailure(prepared, cancelled = false, failure = RestoreFailure.PREPARATION_FAILED)
            return RestoreResult.Failed(RestoreFailure.PREPARATION_FAILED, cleaned)
        }
        return RestoreResult.Completed(
            importedDocumentCount = documentsToImport.size,
            skippedExactDocumentCount = skipped.size,
            stagingCleanupSucceeded = prepared.stagingArea.discard(),
        )
    }

    private suspend fun cleanupAfterFailure(
        prepared: PreparedRestore,
        cancelled: Boolean,
        failure: RestoreFailure?,
    ): Boolean {
        val storeCleaned = try {
            store.terminate(prepared.stagingArea.operationId, cancelled, failure)
        } catch (_: Exception) {
            false
        }
        return prepared.stagingArea.discard() && storeCleaned
    }

    private fun notifyProgress(
        listener: RestoreProgressListener,
        progress: RestoreProgress,
    ) {
        try {
            listener.onProgress(progress)
        } catch (_: RuntimeException) {
            // Progress is advisory and must never compromise restore atomicity or cleanup.
        }
    }
}

internal class RestoreStoreException(
    val failure: RestoreFailure,
    cause: Throwable? = null,
) : Exception(cause)

private fun buildDocumentBundles(
    documents: List<org.synapseworks.pageharbor.backup.format.BackupDocumentRecord>,
    pages: List<BackupPageRecord>,
    sourceAssets: List<org.synapseworks.pageharbor.backup.format.BackupSourceAssetRecord>,
): List<RestoreDocumentBundle> {
    val pagesByDocument = pages.groupBy(BackupPageRecord::documentId)
    val sourcesByDocument = sourceAssets.groupBy { it.documentId }
    return documents.map { document ->
        val orderedPages = pagesByDocument[document.documentId].orEmpty().sortedBy(BackupPageRecord::position)
        val fingerprint = DocumentFingerprintV1.calculate(
            orderedPages.map { page ->
                FingerprintPage(
                    assetSha256 = page.sha256,
                    mimeType = page.mimeType,
                    byteLength = page.byteLength,
                    rotationDegrees = page.rotationDegrees,
                    filterName = page.filterName,
                )
            },
        )
        if (
            document.contentHashVersion == DocumentFingerprintV1.VERSION &&
            document.contentSha256 != null &&
            document.contentSha256 != fingerprint.sha256
        ) {
            throw IllegalArgumentException("The declared document fingerprint is inconsistent")
        }
        RestoreDocumentBundle(
            document = document,
            pages = orderedPages,
            sourceAssets = sourcesByDocument[document.documentId].orEmpty(),
            fingerprint = fingerprint,
        )
    }
}

private fun RestoreDocumentBundle.incomingIdentity(): IncomingDocumentIdentity = IncomingDocumentIdentity(
    fingerprint = fingerprint,
    sourceSha256 = sourceAssets.mapTo(linkedSetOf()) { it.sha256 },
    pageCount = pages.size,
    contentByteLength = pages.sumOf(BackupPageRecord::byteLength),
    orderedMimeTypes = pages.map(BackupPageRecord::mimeType),
)
