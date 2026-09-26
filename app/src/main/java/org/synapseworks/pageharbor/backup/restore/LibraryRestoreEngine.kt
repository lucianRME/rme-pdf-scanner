package org.synapseworks.pageharbor.backup.restore

import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.synapseworks.pageharbor.backup.format.BackupArchiveReader
import org.synapseworks.pageharbor.backup.format.BackupFormatException
import org.synapseworks.pageharbor.backup.format.BackupFormatFailure
import org.synapseworks.pageharbor.backup.format.BackupFormatLimits
import org.synapseworks.pageharbor.backup.format.BackupDocumentRecord
import org.synapseworks.pageharbor.backup.format.BackupIndexedStagingSink
import org.synapseworks.pageharbor.backup.format.BackupOcrArtifactRecord
import org.synapseworks.pageharbor.backup.format.BackupOcrCorrectionRecord
import org.synapseworks.pageharbor.backup.format.BackupOcrLineRecord
import org.synapseworks.pageharbor.backup.format.BackupOcrPageStateRecord
import org.synapseworks.pageharbor.backup.format.BackupPageRecord
import org.synapseworks.pageharbor.backup.format.BackupSourceAssetRecord
import org.synapseworks.pageharbor.backup.format.BackupVerifiedRecordStore
import org.synapseworks.pageharbor.backup.format.IndexedVerifiedBackup
import org.synapseworks.pageharbor.library.LibraryOperationCoordinator
import org.synapseworks.pageharbor.library.LibraryOperationGate
import org.synapseworks.pageharbor.library.completeAtomicActivation
import org.synapseworks.pageharbor.library.duplicate.DocumentFingerprintV1
import org.synapseworks.pageharbor.library.duplicate.DuplicateDetector
import org.synapseworks.pageharbor.library.duplicate.DuplicateKind
import org.synapseworks.pageharbor.library.duplicate.FingerprintPage
import org.synapseworks.pageharbor.library.duplicate.IncomingDocumentIdentity
import org.synapseworks.pageharbor.library.duplicate.OcrDigestPage
import org.synapseworks.pageharbor.library.duplicate.OcrStateDigest
import org.synapseworks.pageharbor.library.duplicate.OcrStateDigestV1

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
        val coroutineContext = currentCoroutineContext()
        // The source archive already exists (the Android boundary copied it into private storage),
        // so staging needs only the free-space reserve here. Counting the archive again would treat
        // already-consumed bytes as future growth.
        val initialEstimate = RestoreStorageEstimator.beforeStaging(stagingWorkspace.availableBytes())
        if (!storagePreflight.hasCapacity(initialEstimate)) {
            return RestorePreparationResult.Failed(RestorePreparationFailure.INSUFFICIENT_STORAGE)
        }

        val operationId = idSource.newId()
        val staging = try {
            stagingWorkspace.create(operationId)
        } catch (_: Exception) {
            return RestorePreparationResult.Failed(RestorePreparationFailure.STAGING_UNAVAILABLE)
        }
        val backup = try {
            source.openStream().use { input ->
                val indexed = staging as? BackupIndexedStagingSink
                    ?: throw IllegalStateException("Restore staging must provide a bounded index")
                BackupArchiveReader.readAndVerify(
                    source = input,
                    staging = indexed,
                    supportedRequiredFeatures = supportedRequiredFeatures,
                    limits = limits,
                    checkCancellation = coroutineContext::ensureActive,
                    onManifest = { manifest ->
                        val estimate = RestoreStorageEstimator.afterManifest(
                            manifest,
                            limits,
                            stagingWorkspace.availableBytes(),
                        )
                        if (!storagePreflight.hasCapacity(estimate)) {
                            throw RestoreManifestCapacityExceeded
                        }
                    },
                )
            }
        } catch (cancelled: CancellationException) {
            staging.discard()
            throw cancelled
        } catch (_: RestoreManifestCapacityExceeded) {
            staging.discard()
            return RestorePreparationResult.Failed(RestorePreparationFailure.INSUFFICIENT_STORAGE)
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

        val manifest = backup.manifest
        val indexedTotals = backup.records.totals()
        val activationEstimate = RestoreStorageEstimator.beforeActivation(
            contentByteLength = manifest.summary.contentByteLength,
            metadataByteLength = indexedTotals?.metadataByteEstimate ?: 0L,
            ocrTextByteLength = indexedTotals?.ocrTextByteCount ?: 0L,
            rowCount = indexedTotals?.let {
                it.folderCount.toLong() + it.documentCount + it.pageCount + it.sourceAssetCount +
                    it.ocrDocumentStateCount + it.ocrPageStateCount + it.ocrArtifactCount +
                    it.ocrCorrectionCount + it.ocrLineCount
            } ?: 0L,
            availableBytes = stagingWorkspace.availableBytes(),
            reclaimableBytes = source.reclaimableByteLength,
        )
        if (!storagePreflight.hasCapacity(activationEstimate)) {
            staging.discard()
            return RestorePreparationResult.Failed(RestorePreparationFailure.INSUFFICIENT_STORAGE)
        }

        val duplicateCounts = try {
            duplicateCounts(
                backup,
                staging as? IndexedRestoreStagingArea
                    ?: throw IllegalStateException("Restore staging lost its bounded index"),
            )
        } catch (cancelled: CancellationException) {
            staging.discardAfterPreparationFailure(cancelled)
            throw cancelled
        } catch (failure: Exception) {
            staging.discardAfterPreparationFailure(failure)
            return RestorePreparationResult.Failed(RestorePreparationFailure.LIBRARY_UNAVAILABLE)
        }
        val summary = manifest.summary
        val preview = RestorePreview(
            backupId = manifest.backupId,
            createdAtEpochMillis = manifest.createdAtEpochMillis,
            folderCount = summary.folderCount,
            documentCount = summary.documentCount,
            pageCount = summary.pageCount,
            sourceAssetCount = summary.sourceAssetCount,
            contentByteLength = summary.contentByteLength,
            exactDuplicateCount = duplicateCounts.first,
            possibleDuplicateCount = duplicateCounts.second,
        )
        return RestorePreparationResult.Ready(
            PreparedRestore(preview, backup, staging),
        )
    }

    private suspend fun duplicateCounts(
        backup: IndexedVerifiedBackup,
        staging: IndexedRestoreStagingArea,
    ): Pair<Int, Int> {
        var exact = 0
        var possible = 0
        var processed = 0
        forEachIndexedDocument(backup) { _, source ->
            staging.recordDocumentIdentity(source)
            processed += 1
            when (classifyIndexedDocument(source, backup.records).kind) {
                DuplicateKind.EXACT -> exact += 1
                DuplicateKind.POSSIBLE -> possible += 1
                DuplicateKind.DIFFERENT -> Unit
            }
        }
        check(processed == backup.manifest.summary.documentCount)
        return exact to possible
    }

    private suspend fun forEachStoredIdentity(
        staging: IndexedRestoreStagingArea,
        accept: suspend (Int, RestoreIndexedDocument) -> Unit,
    ) {
        var afterOrdinal = -1
        while (true) {
            val documents = staging.documentIdentitiesPage(afterOrdinal, RECORD_PAGE_SIZE)
            if (documents.isEmpty()) return
            documents.forEach { row -> accept(row.ordinal, row.source) }
            afterOrdinal = documents.last().ordinal
            if (documents.size < RECORD_PAGE_SIZE) return
        }
    }

    private suspend fun forEachRestorePlan(
        staging: IndexedRestoreStagingArea,
        accept: suspend (Int, RestorePlannedDocument) -> Unit,
    ) {
        var afterOrdinal = -1
        while (true) {
            val documents = staging.restorePlanPage(afterOrdinal, RECORD_PAGE_SIZE)
            if (documents.isEmpty()) return
            documents.forEach { row -> accept(row.ordinal, row.planned) }
            afterOrdinal = documents.last().ordinal
            if (documents.size < RECORD_PAGE_SIZE) return
        }
    }

    private suspend fun forEachIndexedDocument(
        backup: IndexedVerifiedBackup,
        accept: suspend (Int, RestoreIndexedDocument) -> Unit,
    ) {
        var afterOrdinal = -1
        while (true) {
            val documents = backup.records.documentsPage(afterOrdinal, RECORD_PAGE_SIZE)
            if (documents.isEmpty()) return
            documents.forEachIndexed { offset, document ->
                accept(afterOrdinal + offset + 1, buildIndexedDocument(backup.records, document))
            }
            afterOrdinal += documents.size
            if (documents.size < RECORD_PAGE_SIZE) return
        }
    }

    private suspend fun classifyIndexedDocument(
        source: RestoreIndexedDocument,
        records: BackupVerifiedRecordStore,
    ): org.synapseworks.pageharbor.library.duplicate.DuplicateMatch {
        val fingerprintMatch = store.classifyDuplicate(source.incomingIdentity())
        if (fingerprintMatch.kind != DuplicateKind.DIFFERENT) return fingerprintMatch
        var afterSourceId: String? = null
        while (true) {
            val assets = records.sourceAssetsPage(
                source.document.documentId,
                afterSourceId,
                SOURCE_DUPLICATE_HASH_BATCH_SIZE,
            )
            if (assets.isEmpty()) break
            store.possibleSourceDuplicate(assets.map(BackupSourceAssetRecord::sha256))?.let { documentId ->
                return org.synapseworks.pageharbor.library.duplicate.DuplicateMatch(
                    DuplicateKind.POSSIBLE,
                    documentId,
                )
            }
            afterSourceId = assets.last().sourceId
            if (assets.size < SOURCE_DUPLICATE_HASH_BATCH_SIZE) break
        }
        return fingerprintMatch
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
            val retainedOperationIds = operations.mapTo(linkedSetOf()) { it.operationId }
            if (!stagingWorkspace.discardOrphans(retainedOperationIds)) {
                throw IOException("Orphaned restore staging could not be removed")
            }
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
    ): RestoreResult = restoreIndexedWhileLocked(
            prepared,
            prepared.backup,
            policy,
            cancellationSignal,
            progressListener,
        )

    private suspend fun restoreIndexedWhileLocked(
        prepared: PreparedRestore,
        backup: IndexedVerifiedBackup,
        policy: RestoreMergePolicy,
        cancellationSignal: RestoreCancellationSignal,
        progressListener: RestoreProgressListener,
    ): RestoreResult {
        val indexedStaging = prepared.stagingArea as? IndexedRestoreStagingArea
            ?: return RestoreResult.Failed(
                RestoreFailure.PREPARATION_FAILED,
                prepared.stagingArea.discard(),
            )
        var importCount = 0
        var skippedCount = 0
        var classifiedCount = 0
        try {
            forEachStoredIdentity(indexedStaging) { _, source ->
                if (cancellationSignal.isCancellationRequested()) throw RestoreCancelledSignal
                val match = classifyIndexedDocument(source, backup.records)
                indexedStaging.recordRestoreDecision(source.document.documentId, match.kind)
                classifiedCount += 1
                if (policy == RestoreMergePolicy.MERGE_SKIP_EXACT && match.kind == DuplicateKind.EXACT) {
                    skippedCount += 1
                } else {
                    importCount += 1
                }
            }
            check(classifiedCount == backup.manifest.summary.documentCount)
        } catch (_: RestoreCancelledSignal) {
            return RestoreResult.Cancelled(prepared.stagingArea.discard())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            prepared.stagingArea.discard()
            return RestoreResult.Failed(RestoreFailure.JOURNAL_UNAVAILABLE, cleanupSucceeded = true)
        }

        val folderPlan = try {
            RestoreIndexedFolderPlanner.plan(
                staging = indexedStaging,
                expectedFolderCount = backup.manifest.summary.folderCount,
                store = store,
                idSource = idSource,
                cancellationSignal = cancellationSignal,
            )
        } catch (_: RestoreFolderPlanningCancelled) {
            return RestoreResult.Cancelled(prepared.stagingArea.discard())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            prepared.stagingArea.discard()
            return RestoreResult.Failed(RestoreFailure.JOURNAL_UNAVAILABLE, cleanupSucceeded = true)
        }
        val operationId = prepared.stagingArea.operationId
        try {
            store.beginOperation(
                RestoreJournalPlan(
                    operationId = operationId,
                    backupId = backup.manifest.backupId,
                    createdAtEpochMillis = clock.nowEpochMillis(),
                    contentByteLength = backup.manifest.summary.contentByteLength,
                    discoveredDocumentCount = backup.manifest.summary.documentCount,
                    plannedDocumentCount = importCount,
                    skippedExactDocumentCount = skippedCount,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            prepared.stagingArea.discard()
            return RestoreResult.Failed(RestoreFailure.JOURNAL_UNAVAILABLE, cleanupSucceeded = true)
        }

        notifyProgress(progressListener, RestoreProgress(0, importCount))
        var preparedCount = 0
        var journalOrdinal = 0
        try {
            forEachRestorePlan(indexedStaging) { _, planned ->
                val source = planned.source
                if (cancellationSignal.isCancellationRequested()) throw RestoreCancelledSignal
                if (policy == RestoreMergePolicy.MERGE_SKIP_EXACT &&
                    planned.duplicateKind == DuplicateKind.EXACT
                ) {
                    val finalMatch = store.classifyDuplicate(source.incomingIdentity())
                    if (finalMatch.kind != DuplicateKind.EXACT) {
                        throw RestoreStoreException(RestoreFailure.PREPARATION_FAILED)
                    }
                    store.recordSkippedExactDocument(operationId, journalOrdinal++, source)
                    return@forEachRestorePlan
                }
                run {
                    val document = RestoreDocumentToPrepare(
                        itemId = idSource.newId(),
                        targetDocumentId = idSource.newId(),
                        targetFolderId = source.document.folderId?.let(indexedStaging::targetFolderId),
                        targetFolderSearchPath = source.document.folderId
                            ?.let(indexedStaging::targetFolderSearchPath)
                            .orEmpty(),
                        source = source,
                        duplicateKind = planned.duplicateKind,
                    )
                    store.preparePendingDocument(
                        operationId,
                        journalOrdinal++,
                        document,
                        backup.records,
                        prepared.stagingArea,
                    )
                    preparedCount += 1
                    notifyProgress(progressListener, RestoreProgress(preparedCount, importCount))
                }
            }
            check(journalOrdinal == backup.manifest.summary.documentCount)
            check(preparedCount == importCount)
            if (cancellationSignal.isCancellationRequested()) throw RestoreCancelledSignal
            completeAtomicActivation(
                activate = {
                    store.activate(
                        RestoreActivationPlan(
                            operationId = operationId,
                            folders = folderPlan,
                            folderCount = backup.manifest.summary.folderCount,
                            importedDocumentCount = importCount,
                            activatedAtEpochMillis = clock.nowEpochMillis(),
                        ),
                    )
                },
                isCommitted = { store.isOperationCompleted(operationId) },
            )
        } catch (_: RestoreCancelledSignal) {
            val cleaned = cleanupAfterFailure(prepared, cancelled = true, failure = null)
            return RestoreResult.Cancelled(cleaned)
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
        return RestoreResult.Completed(importCount, skippedCount, prepared.stagingArea.discard())
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

private fun RestoreStagingArea.discardAfterPreparationFailure(failure: Throwable) {
    try {
        discard()
    } catch (cleanupFailure: Exception) {
        failure.addSuppressed(cleanupFailure)
    }
}

private suspend fun buildIndexedDocument(
    records: BackupVerifiedRecordStore,
    document: BackupDocumentRecord,
): RestoreIndexedDocument {
    val fingerprintBuilder = DocumentFingerprintV1.Builder(document.pageCount)
    val documentState = records.ocrDocumentState(document.documentId)
    val digestBuilder = OcrStateDigestV1.Builder(documentState?.scriptPreference, document.pageCount)
    var pageByteLength = 0L
    var afterPosition = -1
    while (true) {
        currentCoroutineContext().ensureActive()
        val pages = records.pagesPage(document.documentId, afterPosition, OCR_TEXT_PAGE_SIZE)
        if (pages.isEmpty()) break
        pages.forEach { page ->
            currentCoroutineContext().ensureActive()
            fingerprintBuilder.addPage(
                FingerprintPage(
                    assetSha256 = page.sha256,
                    mimeType = page.mimeType,
                    byteLength = page.byteLength,
                    rotationDegrees = page.rotationDegrees,
                    filterName = page.filterName,
                ),
            )
            pageByteLength = Math.addExact(pageByteLength, page.byteLength)
            if (documentState == null) {
                digestBuilder.addPage(
                    OcrStateDigestV1.legacyV1Page(page.pageId, page.ocrText, page.ocrError),
                )
            } else {
                val pageState = requireNotNull(records.ocrPageState(page.pageId))
                val pageDigest = digestBuilder.beginPage(
                    legacyText = page.ocrText,
                    legacyError = page.ocrError,
                    pageState = pageState,
                    correction = records.ocrCorrection(page.pageId),
                    artifactCount = records.ocrArtifactCount(page.pageId),
                )
                var afterRevision = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val artifacts = records.ocrArtifactsPage(
                        page.pageId,
                        afterRevision,
                        OCR_TEXT_PAGE_SIZE,
                    )
                    if (artifacts.isEmpty()) break
                    artifacts.forEach { artifact ->
                        currentCoroutineContext().ensureActive()
                        val artifactDigest = pageDigest.beginArtifact(artifact)
                        var afterLineOrdinal = -1
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val lines = records.ocrLinesPage(
                                page.pageId,
                                artifact.artifactRevision,
                                afterLineOrdinal,
                                OCR_TEXT_PAGE_SIZE,
                            )
                            if (lines.isEmpty()) break
                            lines.forEach(artifactDigest::addLine)
                            afterLineOrdinal = lines.last().lineOrdinal
                            if (lines.size < OCR_TEXT_PAGE_SIZE) break
                        }
                        artifactDigest.finish()
                    }
                    afterRevision = artifacts.last().artifactRevision
                    if (artifacts.size < OCR_TEXT_PAGE_SIZE) break
                }
                pageDigest.finish()
            }
        }
        afterPosition = pages.last().position
        if (pages.size < OCR_TEXT_PAGE_SIZE) break
    }
    val fingerprint = fingerprintBuilder.finish()
    if (document.contentHashVersion == DocumentFingerprintV1.VERSION &&
        document.contentSha256 != null && document.contentSha256 != fingerprint.sha256
    ) {
        throw IllegalArgumentException("The declared document fingerprint is inconsistent")
    }

    var sourceAssetByteLength = 0L
    var firstSourceModifiedAtEpochMillis: Long? = null
    var afterSourceId: String? = null
    while (true) {
        currentCoroutineContext().ensureActive()
        val sources = records.sourceAssetsPage(document.documentId, afterSourceId, RECORD_PAGE_SIZE)
        if (sources.isEmpty()) break
        sources.forEach { source ->
            sourceAssetByteLength = Math.addExact(sourceAssetByteLength, source.byteLength)
            if (firstSourceModifiedAtEpochMillis == null) {
                firstSourceModifiedAtEpochMillis = source.sourceModifiedAtEpochMillis
            }
        }
        afterSourceId = sources.last().sourceId
        if (sources.size < RECORD_PAGE_SIZE) break
    }
    return RestoreIndexedDocument(
        document = document,
        fingerprint = fingerprint,
        ocrStateDigest = digestBuilder.finish(),
        pageByteLength = pageByteLength,
        sourceAssetByteLength = sourceAssetByteLength,
        firstSourceModifiedAtEpochMillis = firstSourceModifiedAtEpochMillis,
    )
}

private fun RestoreIndexedDocument.incomingIdentity(): IncomingDocumentIdentity = IncomingDocumentIdentity(
    fingerprint = fingerprint,
    pageCount = document.pageCount,
    contentByteLength = pageByteLength,
    ocrStateDigestVersion = ocrStateDigest.version,
    ocrStateSha256 = ocrStateDigest.sha256,
)


private const val RECORD_PAGE_SIZE = 256
private const val SOURCE_DUPLICATE_HASH_BATCH_SIZE = 100
private const val OCR_TEXT_PAGE_SIZE = 8

private object RestoreCancelledSignal : RuntimeException(null, null, false, false)

private object RestoreManifestCapacityExceeded : RuntimeException(null, null, false, false)
