package org.synapseworks.pageharbor.backup.restore

import android.content.Context
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.synapseworks.pageharbor.backup.format.BackupOcrArtifactRecord
import org.synapseworks.pageharbor.backup.format.BackupOcrCorrectionAlignment
import org.synapseworks.pageharbor.backup.format.BackupOcrCorrectionRecord
import org.synapseworks.pageharbor.backup.format.BackupOcrInputFingerprint
import org.synapseworks.pageharbor.backup.format.BackupOcrLineCorrectionRecord
import org.synapseworks.pageharbor.backup.format.BackupOcrLineRecord
import org.synapseworks.pageharbor.backup.format.BackupOcrPageStateRecord
import org.synapseworks.pageharbor.backup.format.BackupOcrPoint
import org.synapseworks.pageharbor.backup.format.BackupOcrVerificationState
import org.synapseworks.pageharbor.document.session.DocumentImageMetadata
import org.synapseworks.pageharbor.document.session.DocumentPageRotation
import org.synapseworks.pageharbor.document.session.DocumentSourceCategory
import org.synapseworks.pageharbor.image.DocumentFilter
import org.synapseworks.pageharbor.library.LibraryDao
import org.synapseworks.pageharbor.library.LibraryDataOperationEntity
import org.synapseworks.pageharbor.library.LibraryDataOperationItemEntity
import org.synapseworks.pageharbor.library.LibraryDatabase
import org.synapseworks.pageharbor.library.LibraryDocumentEntity
import org.synapseworks.pageharbor.library.LibraryDocumentState
import org.synapseworks.pageharbor.library.LibraryError
import org.synapseworks.pageharbor.library.LibraryFileStore
import org.synapseworks.pageharbor.library.LibraryFolderEntity
import org.synapseworks.pageharbor.library.LibraryOcrStatus
import org.synapseworks.pageharbor.library.LibraryPageEntity
import org.synapseworks.pageharbor.library.LibraryPageOcrArtifactEntity
import org.synapseworks.pageharbor.library.LibraryPageOcrCorrectionEntity
import org.synapseworks.pageharbor.library.LibraryPageOcrCorrectionLineEntity
import org.synapseworks.pageharbor.library.LibraryPageOcrLineEntity
import org.synapseworks.pageharbor.library.LibraryPendingRestoreFolderEntity
import org.synapseworks.pageharbor.library.LibraryPageSource
import org.synapseworks.pageharbor.library.LibraryOcrRestorePageState
import org.synapseworks.pageharbor.library.LibraryResult
import org.synapseworks.pageharbor.library.LibrarySourceAssetEntity
import org.synapseworks.pageharbor.library.LibrarySourceAssetSource
import org.synapseworks.pageharbor.library.libraryFolderParentScope
import org.synapseworks.pageharbor.library.duplicate.DuplicateKind
import org.synapseworks.pageharbor.library.duplicate.DuplicateMatch
import org.synapseworks.pageharbor.library.duplicate.IncomingDocumentIdentity
import org.synapseworks.pageharbor.library.duplicate.OcrStateDigest
import org.synapseworks.pageharbor.library.duplicate.OcrStateDigestV1

/** Room v3 adapter for the app-private library. Pending rows stay invisible until activation. */
internal class RoomRestoreLibraryStore(
    context: Context,
    private val dao: LibraryDao = LibraryDatabase.get(context).libraryDao(),
    private val fileStore: LibraryFileStore = LibraryFileStore(context),
    private val clock: RestoreClock = RestoreClock { System.currentTimeMillis() },
) : RestoreLibraryStore {
    /**
     * The production restore path never enumerates the library. The fingerprint index narrows the
     * expensive OCR-state comparison to a small, fixed candidate window. If no content match is
     * available, an indexed page-count/byte-count probe preserves the conservative image-only
     * POSSIBLE result without building an all-library candidate map. A truncated or missing OCR
     * comparison can only downgrade a match to POSSIBLE; it can never cause an unsafe skip.
     */
    override suspend fun classifyDuplicate(identity: IncomingDocumentIdentity): DuplicateMatch {
        val fingerprint = identity.fingerprint
        if (fingerprint != null) {
            val candidates = dao.exactRestoreDuplicateCandidates(
                hashVersion = fingerprint.version,
                sha256 = fingerprint.sha256,
                limit = MAX_EXACT_DUPLICATE_CANDIDATES,
            )
            if (candidates.isNotEmpty()) {
                val incomingDigest = identity.ocrStateDigestVersion?.let { version ->
                    identity.ocrStateSha256?.let { sha256 -> OcrStateDigest(version, sha256) }
                }
                if (incomingDigest != null) {
                    candidates.forEach { candidate ->
                        currentCoroutineContext().ensureActive()
                        if (existingOcrStateDigest(candidate) == incomingDigest) {
                            return DuplicateMatch(DuplicateKind.EXACT, candidate.documentId)
                        }
                    }
                }
                return DuplicateMatch(DuplicateKind.POSSIBLE, candidates.first().documentId)
            }
        }

        val shapeDocumentId = when (val byteCount = identity.contentByteLength) {
            null -> dao.possibleRestoreShapeDuplicateByPageCount(identity.pageCount)
            else -> {
                require(byteCount >= 0L)
                dao.possibleRestoreShapeDuplicateInByteRange(
                    pageCount = identity.pageCount,
                    minimumByteCount = minimumApproximateByteCount(byteCount),
                    maximumByteCount = maximumApproximateByteCount(byteCount),
                ) ?: dao.possibleRestoreShapeDuplicateWithUnknownByteCount(identity.pageCount)
            }
        }
        return shapeDocumentId?.let { DuplicateMatch(DuplicateKind.POSSIBLE, it) }
            ?: DuplicateMatch(DuplicateKind.DIFFERENT, null)
    }

    override suspend fun possibleSourceDuplicate(sourceSha256: Collection<String>): String? {
        require(sourceSha256.size <= SOURCE_HASH_QUERY_SIZE)
        if (sourceSha256.isEmpty()) return null
        return dao.possibleRestoreSourceDuplicate(sourceSha256.toList())
    }

    override suspend fun folderNameExists(
        parentFolderId: String?,
        normalizedName: String,
    ): Boolean = dao.folderByNormalizedName(
        normalizedName = normalizedName,
        parentScope = libraryFolderParentScope(parentFolderId),
    ) != null

    override suspend fun beginOperation(plan: RestoreJournalPlan) {
        val operation = LibraryDataOperationEntity(
            operationId = plan.operationId,
            operationType = OPERATION_TYPE,
            phase = PHASE_PREPARING,
            sourceKind = SOURCE_KIND,
            sourceRootUri = null,
            sourceGrantFlags = 0,
            createdAtMillis = plan.createdAtEpochMillis,
            updatedAtMillis = plan.createdAtEpochMillis,
            discoveredItemCount = plan.discoveredDocumentCount,
            plannedDocumentCount = plan.plannedDocumentCount,
            preparedDocumentCount = 0,
            importedDocumentCount = 0,
            skippedDuplicateCount = plan.skippedExactDocumentCount,
            failedItemCount = 0,
            totalSourceBytes = plan.contentByteLength,
            processedSourceBytes = 0L,
            cancelRequested = false,
            terminalErrorCode = null,
        )
        dao.insertRestoreOperation(operation, emptyList())
    }

    override suspend fun recordSkippedExactDocument(
        operationId: String,
        ordinal: Int,
        source: RestoreIndexedDocument,
    ) {
        dao.insertOperationItems(
            listOf(
                LibraryDataOperationItemEntity(
                    itemId = "$operationId-skip-${source.document.documentId}",
                    operationId = operationId,
                    ordinal = ordinal,
                    itemState = ITEM_DUPLICATE_SKIPPED,
                    targetDocumentId = "skip:${source.document.documentId}",
                    targetRevisionId = null,
                    proposedTitle = source.document.title,
                    proposedFolderPath = source.document.folderId,
                    sourceModifiedAtMillis = source.firstSourceModifiedAtEpochMillis,
                    sourceByteCount = saturatingAdd(
                        source.pageByteLength,
                        source.sourceAssetByteLength,
                    ),
                    sourcePageCount = source.document.pageCount,
                    logicalHashVersion = source.fingerprint.version,
                    logicalSha256 = source.fingerprint.sha256,
                    duplicateKind = "EXACT",
                    duplicateDocumentId = null,
                    duplicateDecision = "SKIP_EXACT",
                    failureCode = null,
                ),
            ),
        )
    }

    override suspend fun preparePendingDocument(
        operationId: String,
        ordinal: Int,
        document: RestoreDocumentToPrepare,
        records: org.synapseworks.pageharbor.backup.format.BackupVerifiedRecordStore,
        assets: RestoreStagedAssetSource,
    ) {
        val source = document.source
        val plannedItem = document.toJournalItem(operationId, ordinal, ITEM_PLANNED)
        dao.insertOperationItems(listOf(plannedItem))
        val documentState = records.ocrDocumentState(source.document.documentId)
        val pending = LibraryDocumentEntity(
            documentId = document.targetDocumentId,
            title = source.document.title,
            createdAtMillis = source.document.createdAtEpochMillis,
            modifiedAtMillis = source.document.modifiedAtEpochMillis,
            pageCount = source.document.pageCount,
            folderId = null,
            thumbnailRelativePath = null,
            ocrStatus = LibraryOcrStatus.NOT_INDEXED.name,
            libraryState = LibraryDocumentState.PENDING.name,
            pendingOperationId = operationId,
            contentHashVersion = source.fingerprint.version,
            contentSha256 = source.fingerprint.sha256,
            contentByteCount = source.pageByteLength,
            sourceModifiedAtMillis = source.firstSourceModifiedAtEpochMillis,
            importedAtMillis = clock.nowEpochMillis(),
            contentRevision = documentState?.contentRevision ?: 0L,
            ocrScriptPreference = documentState?.scriptPreference,
        )

        val revisionId = java.util.UUID.randomUUID().toString()
        val revisionsRoot = File(File(fileStore.root, document.targetDocumentId), "revisions")
        val stagingDirectory = File(revisionsRoot, ".$revisionId-staging")
        val completedDirectory = File(revisionsRoot, revisionId)
        if (!stagingDirectory.mkdirs()) {
            throw RestoreStoreException(RestoreFailure.INSUFFICIENT_STORAGE)
        }
        try {
            dao.beginStreamingPendingRestoreDocument(pending)
            var afterPosition = -1
            var pageCount = 0
            var pageWithAnyStateCount = 0
            var pageWithErrorCount = 0
            var thumbnailCreated = false
            while (true) {
                currentCoroutineContext().ensureActive()
                val pages = records.pagesPage(
                    source.document.documentId,
                    afterPosition,
                    OCR_TEXT_PAGE_SIZE,
                )
                if (pages.isEmpty()) break
                pages.forEach { backupPage ->
                    currentCoroutineContext().ensureActive()
                    val targetPageId = java.util.UUID.randomUUID().toString()
                    val fileName = "$targetPageId.${pageExtension(backupPage.mimeType)}"
                    val destination = File(stagingDirectory, fileName)
                    copyVerifiedAsset(
                        source = { assets.openAsset(backupPage.relativePath) },
                        destination = destination,
                        expectedByteLength = backupPage.byteLength,
                        expectedSha256 = backupPage.sha256,
                    )
                    if (backupPage.position == 0) {
                        thumbnailCreated = fileStore.createRestoreThumbnail(
                            source = destination,
                            destination = File(stagingDirectory, RESTORE_THUMBNAIL_FILE_NAME),
                            rotationDegrees = backupPage.rotationDegrees,
                            filterName = backupPage.filterName,
                        )
                    }
                    val targetRelativePath = relativeLibraryPath(File(completedDirectory, fileName))
                    val pageState = records.ocrPageState(backupPage.pageId)
                    dao.appendStreamingPendingRestorePage(
                        LibraryPageEntity(
                            pageId = targetPageId,
                            documentId = document.targetDocumentId,
                            position = backupPage.position,
                            relativePath = targetRelativePath,
                            contentType = backupPage.mimeType,
                            sourceCategory = DocumentSourceCategory.LIBRARY.name,
                            width = backupPage.width,
                            height = backupPage.height,
                            sourceByteCount = backupPage.byteLength,
                            rotationDegrees = backupPage.rotationDegrees,
                            filterName = backupPage.filterName,
                            ocrText = backupPage.ocrText,
                            ocrError = backupPage.ocrError,
                            contentSha256 = backupPage.sha256,
                            visualRevision = pageState?.visualRevision ?: 0L,
                        ),
                    )
                    if (documentState == null) {
                        val legacy = OcrStateDigestV1.legacyV1Page(
                            targetPageId,
                            backupPage.ocrText,
                            backupPage.ocrError,
                        )
                        legacy.artifacts.singleOrNull()?.let { artifact ->
                            dao.appendStreamingPendingRestoreArtifact(artifact.toLibraryEntity())
                        }
                        val legacyState = requireNotNull(legacy.pageState)
                        dao.finishStreamingPendingRestorePage(
                            documentId = document.targetDocumentId,
                            pageId = targetPageId,
                            activeArtifactRevision = legacyState.activeArtifactRevision,
                            ocrStateRevision = legacyState.ocrStateRevision,
                            ocrText = backupPage.ocrText,
                            ocrError = backupPage.ocrError,
                        )
                    } else {
                        restoreIndexedOcrPage(
                            records = records,
                            backupPageId = backupPage.pageId,
                            targetPageId = targetPageId,
                            targetDocumentId = document.targetDocumentId,
                            ocrText = backupPage.ocrText,
                            ocrError = backupPage.ocrError,
                        )
                    }
                    if (backupPage.ocrText != null || backupPage.ocrError != null) {
                        pageWithAnyStateCount += 1
                    }
                    if (backupPage.ocrError != null) pageWithErrorCount += 1
                    pageCount += 1
                }
                afterPosition = pages.last().position
                if (pages.size < OCR_TEXT_PAGE_SIZE) break
            }
            require(pageCount == source.document.pageCount)

            var afterSourceId: String? = null
            var sourceCount = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                val sourceAssets = records.sourceAssetsPage(
                    source.document.documentId,
                    afterSourceId,
                    DATABASE_PAGE_SIZE,
                )
                if (sourceAssets.isEmpty()) break
                sourceAssets.forEach { backupSource ->
                    currentCoroutineContext().ensureActive()
                    val targetAssetId = java.util.UUID.randomUUID().toString()
                    val fileName = "source-$targetAssetId.pdf"
                    copyVerifiedAsset(
                        source = { assets.openAsset(backupSource.relativePath) },
                        destination = File(stagingDirectory, fileName),
                        expectedByteLength = backupSource.byteLength,
                        expectedSha256 = backupSource.sha256,
                    )
                    dao.appendStreamingPendingRestoreSource(
                        LibrarySourceAssetEntity(
                            assetId = targetAssetId,
                            documentId = document.targetDocumentId,
                            role = backupSource.role,
                            relativePath = relativeLibraryPath(File(completedDirectory, fileName)),
                            contentType = backupSource.mimeType,
                            byteCount = backupSource.byteLength,
                            sha256 = backupSource.sha256,
                            sourceModifiedAtMillis = backupSource.sourceModifiedAtEpochMillis,
                            createdAtMillis = source.document.createdAtEpochMillis,
                            matchesCurrentRevision = backupSource.matchesCurrentRevision,
                        ),
                    )
                    sourceCount += 1
                }
                afterSourceId = sourceAssets.last().sourceId
                if (sourceAssets.size < DATABASE_PAGE_SIZE) break
            }
            require(sourceCount == source.document.sourceAssetCount)
            if (!stagingDirectory.renameTo(completedDirectory)) {
                throw RestoreStoreException(RestoreFailure.INSUFFICIENT_STORAGE)
            }

            val operation = requireNotNull(dao.operation(operationId))
            val ocrStatus = when {
                pageWithAnyStateCount == 0 -> LibraryOcrStatus.NOT_INDEXED
                pageWithErrorCount == pageCount -> LibraryOcrStatus.FAILED
                pageWithErrorCount > 0 -> LibraryOcrStatus.PARTIAL
                else -> LibraryOcrStatus.INDEXED
            }
            dao.finishStreamingPendingRestoreDocument(
                documentId = document.targetDocumentId,
                ocrStatus = ocrStatus.name,
                thumbnailRelativePath = if (thumbnailCreated) {
                    relativeLibraryPath(File(completedDirectory, RESTORE_THUMBNAIL_FILE_NAME))
                } else {
                    null
                },
                folderSearchPath = document.targetFolderSearchPath,
                updatedItem = plannedItem.copy(
                    itemState = ITEM_PREPARED,
                    targetRevisionId = revisionId,
                ),
                updatedOperation = operation.copy(
                    phase = PHASE_PREPARING,
                    updatedAtMillis = clock.nowEpochMillis(),
                    preparedDocumentCount = operation.preparedDocumentCount + 1,
                    processedSourceBytes = saturatingAdd(
                        operation.processedSourceBytes,
                        saturatingAdd(source.pageByteLength, source.sourceAssetByteLength),
                    ),
                ),
            )
        } catch (cancelled: CancellationException) {
            deleteRestoreTree(stagingDirectory)
            deleteRestoreTree(completedDirectory)
            throw cancelled
        } catch (failure: RestoreStoreException) {
            deleteRestoreTree(stagingDirectory)
            deleteRestoreTree(completedDirectory)
            throw failure
        } catch (failure: Exception) {
            deleteRestoreTree(stagingDirectory)
            deleteRestoreTree(completedDirectory)
            throw RestoreStoreException(RestoreFailure.PREPARATION_FAILED, failure)
        }
    }


    override suspend fun activate(plan: RestoreActivationPlan) {
        try {
            val operation = requireNotNull(dao.operation(plan.operationId))
            require(operation.preparedDocumentCount == plan.importedDocumentCount)
            var stagedFolderCount = 0
            var afterFolderOrdinal = -1
            while (true) {
                currentCoroutineContext().ensureActive()
                val rows = plan.folders.plannedFoldersPage(afterFolderOrdinal, DATABASE_PAGE_SIZE)
                if (rows.isEmpty()) break
                dao.insertPendingRestoreFolders(
                    rows.map { row ->
                        val folder = row.folder
                        LibraryPendingRestoreFolderEntity(
                            folderId = folder.folderId,
                            operationId = plan.operationId,
                            ordinal = row.ordinal,
                            name = folder.name,
                            normalizedName = folder.normalizedName,
                            createdAtMillis = folder.createdAtEpochMillis,
                            modifiedAtMillis = folder.modifiedAtEpochMillis,
                            parentFolderId = folder.parentFolderId,
                            parentScope = libraryFolderParentScope(folder.parentFolderId),
                        )
                    },
                )
                stagedFolderCount += rows.size
                afterFolderOrdinal = rows.last().ordinal
                if (rows.size < DATABASE_PAGE_SIZE) break
            }
            require(stagedFolderCount == plan.folderCount)
            val completed = operation.copy(
                phase = PHASE_COMPLETED,
                updatedAtMillis = plan.activatedAtEpochMillis,
                importedDocumentCount = plan.importedDocumentCount,
                terminalErrorCode = null,
            )
            dao.activateRestoreOperation(
                operationId = plan.operationId,
                expectedFolderCount = plan.folderCount,
                completedOperation = completed,
                modifiedAt = plan.activatedAtEpochMillis,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw RestoreStoreException(RestoreFailure.ACTIVATION_FAILED, failure)
        }
    }

    override suspend fun isOperationCompleted(operationId: String): Boolean =
        dao.operation(operationId)?.phase == PHASE_COMPLETED

    override suspend fun terminate(
        operationId: String,
        cancelled: Boolean,
        failure: RestoreFailure?,
    ): Boolean {
        val operation = dao.operation(operationId) ?: return true
        // Activation is one Room transaction. A completed journal means every referenced document
        // is ACTIVE, so rollback cleanup must never delete its durable assets.
        if (operation.phase == PHASE_COMPLETED) return true
        return try {
            dao.discardPendingRestoreDocuments(operationId)
            var filesRemoved = true
            var afterOrdinal = -1
            while (true) {
                currentCoroutineContext().ensureActive()
                val items = dao.restoreOperationItemsPage(operationId, afterOrdinal, DATABASE_PAGE_SIZE)
                if (items.isEmpty()) break
                items.filterNot { it.itemState == ITEM_DUPLICATE_SKIPPED }.forEach { item ->
                    fileStore.deleteDocument(item.targetDocumentId)
                    filesRemoved = filesRemoved && !File(fileStore.root, item.targetDocumentId).exists()
                }
                afterOrdinal = items.last().ordinal
                if (items.size < DATABASE_PAGE_SIZE) break
            }
            dao.updateOperation(
                operation.copy(
                    phase = if (filesRemoved) {
                        if (cancelled) PHASE_CANCELLED else PHASE_FAILED
                    } else {
                        PHASE_CLEANUP_REQUIRED
                    },
                    updatedAtMillis = clock.nowEpochMillis(),
                    failedItemCount = if (cancelled) 0 else operation.plannedDocumentCount,
                    terminalErrorCode = if (cancelled) null else failure?.name ?: "INTERRUPTED",
                ),
            )
            filesRemoved
        } catch (cancelledException: CancellationException) {
            throw cancelledException
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun recoverableOperations(): List<RestoreRecoveryOperation> =
        dao.recoverableOperations()
            .filter { it.operationType == OPERATION_TYPE }
            .map { RestoreRecoveryOperation(it.operationId) }

    private suspend fun restoreIndexedOcrPage(
        records: org.synapseworks.pageharbor.backup.format.BackupVerifiedRecordStore,
        backupPageId: String,
        targetPageId: String,
        targetDocumentId: String,
        ocrText: String?,
        ocrError: String?,
    ) {
        val state = requireNotNull(records.ocrPageState(backupPageId))
        var afterRevision = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val artifacts = records.ocrArtifactsPage(backupPageId, afterRevision, OCR_TEXT_PAGE_SIZE)
            if (artifacts.isEmpty()) break
            artifacts.forEach { artifact ->
                dao.appendStreamingPendingRestoreArtifact(
                    artifact.copy(pageId = targetPageId).toLibraryEntity(),
                )
                var afterLineOrdinal = -1
                while (true) {
                    val lines = records.ocrLinesPage(
                        backupPageId,
                        artifact.artifactRevision,
                        afterLineOrdinal,
                        OCR_TEXT_PAGE_SIZE,
                    )
                    if (lines.isEmpty()) break
                    dao.appendStreamingPendingRestoreLines(
                        lines.map { line -> line.copy(pageId = targetPageId).toLibraryEntity() },
                    )
                    afterLineOrdinal = lines.last().lineOrdinal
                    if (lines.size < OCR_TEXT_PAGE_SIZE) break
                }
            }
            afterRevision = artifacts.last().artifactRevision
            if (artifacts.size < OCR_TEXT_PAGE_SIZE) break
        }
        records.ocrCorrection(backupPageId)?.let { correction ->
            val target = correction.copy(pageId = targetPageId)
            dao.appendStreamingPendingRestoreCorrection(
                correction = target.toLibraryEntity(),
                lines = target.lineCorrections.map { line ->
                    LibraryPageOcrCorrectionLineEntity(
                        pageId = targetPageId,
                        baseArtifactRevision = target.baseArtifactRevision,
                        lineOrdinal = line.lineOrdinal,
                        correctedText = line.correctedText,
                    )
                },
            )
        }
        dao.finishStreamingPendingRestorePage(
            documentId = targetDocumentId,
            pageId = targetPageId,
            activeArtifactRevision = state.activeArtifactRevision,
            ocrStateRevision = state.ocrStateRevision,
            ocrText = ocrText,
            ocrError = ocrError,
        )
    }

    private suspend fun copyVerifiedAsset(
        source: () -> java.io.InputStream,
        destination: File,
        expectedByteLength: Long,
        expectedSha256: String,
    ) {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        var byteLength = 0L
        source().use { input ->
            java.io.FileOutputStream(destination).use { output ->
                val buffer = ByteArray(FILE_COPY_BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    byteLength = Math.addExact(byteLength, count.toLong())
                    if (byteLength > expectedByteLength) {
                        throw RestoreStoreException(RestoreFailure.PREPARATION_FAILED)
                    }
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
                output.flush()
            }
        }
        val sha256 = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        if (byteLength != expectedByteLength || sha256 != expectedSha256) {
            throw RestoreStoreException(RestoreFailure.PREPARATION_FAILED)
        }
    }

    private fun relativeLibraryPath(file: File): String =
        fileStore.root.canonicalFile.toPath().relativize(file.canonicalFile.toPath()).toString()

    private fun deleteRestoreTree(directory: File) {
        fileStore.discardRevision(directory)
    }

    private fun pageExtension(mimeType: String): String = when (mimeType) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        else -> error("Unsupported verified page type")
    }

    private suspend fun existingOcrStateDigest(document: LibraryDocumentEntity): OcrStateDigest {
        val digest = OcrStateDigestV1.Builder(document.ocrScriptPreference, document.pageCount)
        var afterPosition = -1
        while (true) {
            currentCoroutineContext().ensureActive()
            val pages = dao.pagesPage(document.documentId, afterPosition, OCR_DIGEST_PAGE_SIZE)
            if (pages.isEmpty()) break
            pages.forEach { page ->
                currentCoroutineContext().ensureActive()
                val artifactCount = dao.restoreOcrArtifactCount(page.pageId)
                val storedCorrection = dao.ocrCorrection(page.pageId)
                val correction = storedCorrection?.toBackupRecord(
                    dao.ocrCorrectionLines(page.pageId),
                )
                val pageDigest = digest.beginPage(
                    legacyText = page.ocrText,
                    legacyError = page.ocrError,
                    pageState = BackupOcrPageStateRecord(
                        pageId = page.pageId,
                        visualRevision = page.visualRevision,
                        ocrStateRevision = page.ocrStateRevision,
                        activeArtifactRevision = page.activeOcrArtifactRevision,
                    ),
                    correction = correction,
                    artifactCount = artifactCount,
                )
                var afterArtifactRevision = 0L
                while (true) {
                    val artifacts = dao.restoreOcrArtifactsPage(
                        page.pageId,
                        afterArtifactRevision,
                        OCR_DIGEST_ARTIFACT_BATCH_SIZE,
                    )
                    if (artifacts.isEmpty()) break
                    artifacts.forEach { artifact ->
                        currentCoroutineContext().ensureActive()
                        val lineCount = dao.restoreOcrLineCount(page.pageId, artifact.artifactRevision)
                        val artifactDigest = pageDigest.beginArtifact(
                            artifact.toBackupRecord(lineCount),
                        )
                        var afterLineOrdinal = -1
                        while (true) {
                            val lines = dao.restoreOcrLinesPage(
                                page.pageId,
                                artifact.artifactRevision,
                                afterLineOrdinal,
                                OCR_DIGEST_LINE_BATCH_SIZE,
                            )
                            if (lines.isEmpty()) break
                            lines.forEach { line -> artifactDigest.addLine(line.toBackupRecord()) }
                            afterLineOrdinal = lines.last().lineOrdinal
                            if (lines.size < OCR_DIGEST_LINE_BATCH_SIZE) break
                        }
                        artifactDigest.finish()
                    }
                    afterArtifactRevision = artifacts.last().artifactRevision
                    if (artifacts.size < OCR_DIGEST_ARTIFACT_BATCH_SIZE) break
                }
                pageDigest.finish()
            }
            afterPosition = pages.last().position
            if (pages.size < OCR_DIGEST_PAGE_SIZE) break
        }
        return digest.finish()
    }

    private fun RestoreDocumentToPrepare.toJournalItem(
        operationId: String,
        ordinal: Int,
        state: String,
    ): LibraryDataOperationItemEntity = LibraryDataOperationItemEntity(
        itemId = itemId,
        operationId = operationId,
        ordinal = ordinal,
        itemState = state,
        targetDocumentId = targetDocumentId,
        targetRevisionId = null,
        proposedTitle = source.document.title,
        proposedFolderPath = targetFolderId,
        sourceModifiedAtMillis = source.firstSourceModifiedAtEpochMillis,
        sourceByteCount = saturatingAdd(source.pageByteLength, source.sourceAssetByteLength),
        sourcePageCount = source.document.pageCount,
        logicalHashVersion = source.fingerprint.version,
        logicalSha256 = source.fingerprint.sha256,
        duplicateKind = duplicateKind.name,
        duplicateDocumentId = null,
        duplicateDecision = "IMPORT",
        failureCode = null,
    )

    private companion object {
        const val DATABASE_PAGE_SIZE = 256
        const val OPERATION_TYPE = "RESTORE"
        const val SOURCE_KIND = "RME_BACKUP"
        const val PHASE_PREPARING = "PREPARING"
        const val PHASE_COMPLETED = "COMPLETED"
        const val PHASE_CANCELLED = "CANCELLED"
        const val PHASE_FAILED = "FAILED"
        const val PHASE_CLEANUP_REQUIRED = "CLEANUP_REQUIRED"
        const val ITEM_PLANNED = "PLANNED"
        const val ITEM_PREPARED = "PREPARED"
        const val ITEM_DUPLICATE_SKIPPED = "DUPLICATE_SKIPPED"
        const val MAX_EXACT_DUPLICATE_CANDIDATES = 32
        const val SOURCE_HASH_QUERY_SIZE = 100
        const val OCR_DIGEST_PAGE_SIZE = 1
        const val OCR_DIGEST_ARTIFACT_BATCH_SIZE = 8
        const val OCR_DIGEST_LINE_BATCH_SIZE = 8
        const val OCR_TEXT_PAGE_SIZE = 8
        const val RESTORE_THUMBNAIL_FILE_NAME = "thumbnail.jpg"
        const val FILE_COPY_BUFFER_SIZE = 32 * 1024
    }
}

private fun saturatingAdd(left: Long, right: Long): Long =
    if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

/** Integer bounds equivalent to a maximum relative size delta of five percent. */
private fun minimumApproximateByteCount(byteCount: Long): Long = byteCount - (byteCount / 20L)

private fun maximumApproximateByteCount(byteCount: Long): Long =
    saturatingAdd(byteCount, byteCount / 19L)

private fun BackupOcrArtifactRecord.toLibraryEntity(): LibraryPageOcrArtifactEntity {
    val fingerprint = inputFingerprint
    return LibraryPageOcrArtifactEntity(
        pageId = pageId,
        artifactRevision = artifactRevision,
        capturedDocumentContentRevision = capturedDocumentContentRevision,
        capturedPageVisualRevision = capturedPageVisualRevision,
        inputFingerprintVersion = fingerprint?.version,
        inputFingerprint = fingerprint?.value,
        verificationState = verificationState.name,
        contentSha256 = fingerprint?.contentSha256,
        rotationDegrees = fingerprint?.rotationDegrees,
        filterName = fingerprint?.filterName,
        uprightWidth = fingerprint?.uprightWidth,
        uprightHeight = fingerprint?.uprightHeight,
        coordinateSystemVersion = fingerprint?.coordinateSystemVersion,
        transformVersion = fingerprint?.transformVersion,
        actualScript = actualScript,
        recognizerId = requireNotNull(recognizerId),
        pipelineVersion = pipelineVersion,
        clientVersion = clientVersion,
        delivery = delivery,
        recognizedAtMillis = recognizedAtEpochMillis,
        rawText = rawText,
    )
}

private fun BackupOcrLineRecord.toLibraryEntity(): LibraryPageOcrLineEntity {
    val topLeft = cornerPoints[0]
    val topRight = cornerPoints[1]
    val bottomRight = cornerPoints[2]
    val bottomLeft = cornerPoints[3]
    return LibraryPageOcrLineEntity(
        pageId = pageId,
        artifactRevision = artifactRevision,
        lineOrdinal = lineOrdinal,
        rawText = rawText,
        topLeftX = topLeft.x,
        topLeftY = topLeft.y,
        topRightX = topRight.x,
        topRightY = topRight.y,
        bottomRightX = bottomRight.x,
        bottomRightY = bottomRight.y,
        bottomLeftX = bottomLeft.x,
        bottomLeftY = bottomLeft.y,
        baselineStartX = baselineStart.x,
        baselineStartY = baselineStart.y,
        baselineEndX = baselineEnd.x,
        baselineEndY = baselineEnd.y,
        baselineAngleDegrees = baselineAngleDegrees,
        writingOrientation = writingOrientation,
    )
}

private fun BackupOcrCorrectionRecord.toLibraryEntity() = LibraryPageOcrCorrectionEntity(
    pageId = pageId,
    baseArtifactRevision = baseArtifactRevision,
    correctedText = correctedText,
    correctedAtMillis = correctedAtEpochMillis,
    alignmentState = alignment.name,
)

private fun LibraryPageOcrArtifactEntity.toBackupRecord(lineCount: Int): BackupOcrArtifactRecord {
    val fingerprintFields = listOf(
        capturedPageVisualRevision,
        inputFingerprintVersion,
        inputFingerprint,
        contentSha256,
        rotationDegrees,
        filterName,
        uprightWidth,
        uprightHeight,
        coordinateSystemVersion,
        transformVersion,
    )
    val fingerprint = when {
        fingerprintFields.all { it == null } -> null
        fingerprintFields.any { it == null } -> error("Stored OCR fingerprint provenance is incomplete")
        else -> BackupOcrInputFingerprint(
            version = requireNotNull(inputFingerprintVersion),
            value = requireNotNull(inputFingerprint),
            contentSha256 = requireNotNull(contentSha256),
            visualRevision = requireNotNull(capturedPageVisualRevision),
            rotationDegrees = requireNotNull(rotationDegrees),
            filterName = requireNotNull(filterName),
            uprightWidth = requireNotNull(uprightWidth),
            uprightHeight = requireNotNull(uprightHeight),
            coordinateSystemVersion = requireNotNull(coordinateSystemVersion),
            transformVersion = requireNotNull(transformVersion),
        )
    }
    return BackupOcrArtifactRecord(
        pageId = pageId,
        artifactRevision = artifactRevision,
        capturedPageVisualRevision = capturedPageVisualRevision,
        capturedDocumentContentRevision = capturedDocumentContentRevision,
        verificationState = BackupOcrVerificationState.valueOf(verificationState),
        inputFingerprint = fingerprint,
        actualScript = actualScript,
        recognizerId = recognizerId,
        pipelineVersion = pipelineVersion,
        clientVersion = clientVersion,
        delivery = delivery,
        recognizedAtEpochMillis = recognizedAtMillis,
        rawText = rawText,
        lineCount = lineCount,
    )
}

private fun LibraryPageOcrLineEntity.toBackupRecord(): BackupOcrLineRecord = BackupOcrLineRecord(
    pageId = pageId,
    artifactRevision = artifactRevision,
    lineOrdinal = lineOrdinal,
    rawText = rawText,
    cornerPoints = listOf(
        BackupOcrPoint(topLeftX, topLeftY),
        BackupOcrPoint(topRightX, topRightY),
        BackupOcrPoint(bottomRightX, bottomRightY),
        BackupOcrPoint(bottomLeftX, bottomLeftY),
    ),
    baselineStart = BackupOcrPoint(baselineStartX, baselineStartY),
    baselineEnd = BackupOcrPoint(baselineEndX, baselineEndY),
    baselineAngleDegrees = baselineAngleDegrees,
    writingOrientation = writingOrientation,
)

private fun LibraryPageOcrCorrectionEntity.toBackupRecord(
    lines: List<LibraryPageOcrCorrectionLineEntity>,
): BackupOcrCorrectionRecord = BackupOcrCorrectionRecord(
    pageId = pageId,
    baseArtifactRevision = baseArtifactRevision,
    correctedText = correctedText,
    correctedAtEpochMillis = correctedAtMillis,
    alignment = BackupOcrCorrectionAlignment.valueOf(alignmentState),
    lineCorrections = lines.map { line ->
        BackupOcrLineCorrectionRecord(line.lineOrdinal, line.correctedText)
    },
)
