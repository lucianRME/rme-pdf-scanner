package org.synapseworks.pageharbor.backup.restore

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.synapseworks.pageharbor.backup.engine.FileLibraryBackupWorkspace
import org.synapseworks.pageharbor.backup.engine.LibraryBackupArtifactState
import org.synapseworks.pageharbor.backup.engine.LibraryBackupClock
import org.synapseworks.pageharbor.backup.engine.LibraryBackupCreationResult
import org.synapseworks.pageharbor.backup.engine.LibraryBackupEngine
import org.synapseworks.pageharbor.backup.engine.LibraryBackupIdSource
import org.synapseworks.pageharbor.backup.engine.LibraryBackupWorkspace
import org.synapseworks.pageharbor.backup.engine.RoomLibraryBackupSnapshotSource
import org.synapseworks.pageharbor.backup.engine.VerifiedLibraryBackupArtifact
import org.synapseworks.pageharbor.backup.format.BackupArchiveReader
import org.synapseworks.pageharbor.backup.format.BackupFormatLimits
import org.synapseworks.pageharbor.backup.format.BackupIndexedStagingSink
import org.synapseworks.pageharbor.backup.format.BackupProducer
import org.synapseworks.pageharbor.document.session.DocumentSourceCategory
import org.synapseworks.pageharbor.image.DocumentFilter
import org.synapseworks.pageharbor.library.LibraryDao
import org.synapseworks.pageharbor.library.LibraryDatabase
import org.synapseworks.pageharbor.library.LibraryDocumentEntity
import org.synapseworks.pageharbor.library.LibraryEffectiveOcrPage
import org.synapseworks.pageharbor.library.LibraryFileStore
import org.synapseworks.pageharbor.library.LibraryFolderEntity
import org.synapseworks.pageharbor.library.LibraryOcrArtifactDraft
import org.synapseworks.pageharbor.library.LibraryOcrCommitResult
import org.synapseworks.pageharbor.library.LibraryOcrCorrectionAlignment
import org.synapseworks.pageharbor.library.LibraryOcrCorrectionDraft
import org.synapseworks.pageharbor.library.LibraryOcrLineDraft
import org.synapseworks.pageharbor.library.LibraryOcrStatus
import org.synapseworks.pageharbor.library.LibraryOperationGate
import org.synapseworks.pageharbor.library.LibraryPageEntity
import org.synapseworks.pageharbor.library.LibrarySourceAssetEntity
import org.synapseworks.pageharbor.library.duplicate.DocumentFingerprintV1
import org.synapseworks.pageharbor.library.duplicate.FingerprintPage

/**
 * Device-only Phase 2B scale gate. The fixture deliberately uses persistent Room databases and
 * private files so backup and restore exercise the same bounded adapters as the application.
 * It never needs external storage, a network, a camera, or a real user document.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class Phase2bBackupRestoreStressInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun portableBackupAndRestoreRemainBoundedAcrossLargeSyntheticLibraries() = runBlocking {
        val reports = PROFILES.map { profile -> runProfile(profile) }

        reports.zipWithNext().forEach { (smaller, larger) ->
            val scale = ceilDiv(larger.profile.documentCount, smaller.profile.documentCount)
            assertTrue(
                "Backup query growth was super-linear: ${smaller.backup.queries.queryCount} -> " +
                    larger.backup.queries.queryCount,
                larger.backup.queries.queryCount <=
                    (smaller.backup.queries.queryCount * scale) + QUERY_SCALING_ALLOWANCE,
            )
            assertTrue(
                "Restore query growth was super-linear: ${smaller.activation.queries.queryCount} -> " +
                    larger.activation.queries.queryCount,
                larger.activation.queries.queryCount <=
                    (smaller.activation.queries.queryCount * scale) + QUERY_SCALING_ALLOWANCE,
            )
            assertTrue(
                "Restore-preview query growth was super-linear: ${smaller.prepare.queries.queryCount} -> " +
                    larger.prepare.queries.queryCount,
                larger.prepare.queries.queryCount <=
                    (smaller.prepare.queries.queryCount * scale) + QUERY_SCALING_ALLOWANCE,
            )
            assertManagedGrowthDoesNotTrackLibrarySize("backup", smaller.backup, larger.backup)
            assertManagedGrowthDoesNotTrackLibrarySize(
                "archive verification",
                smaller.verification,
                larger.verification,
            )
            assertManagedGrowthDoesNotTrackLibrarySize("restore preview", smaller.prepare, larger.prepare)
            assertManagedGrowthDoesNotTrackLibrarySize("restore activation", smaller.activation, larger.activation)
        }
    }

    @Test
    fun backupAndRestoreCancellationCleanUpUnderLoad() = runBlocking {
        val profile = StressProfile("cancellation-documents-0500", 500)
        val runId = UUID.randomUUID().toString()
        val profileRoot = File(context.cacheDir, "phase2b-backup-restore/${profile.name}-$runId")
        val sourceRoot = File(profileRoot, "source/library")
        val destinationRoot = File(profileRoot, "destination/library")
        val backupRoot = File(profileRoot, "backup-workspace")
        val restoreRoot = File(profileRoot, "restore-staging")
        val source = StressLibraryRig.create(
            context = context,
            libraryRoot = sourceRoot,
            databaseName = "phase2b-cancel-source-$runId.db",
        )
        val destination = StressLibraryRig.create(
            context = context,
            libraryRoot = destinationRoot,
            databaseName = "phase2b-cancel-destination-$runId.db",
        )
        var artifact: VerifiedLibraryBackupArtifact? = null

        try {
            seedFolders(source)
            seedDocuments(source, profile.documentCount)
            seedDocument(
                rig = destination,
                sourceDocumentIndex = 0,
                documentId = EXISTING_DUPLICATE_ID,
                title = EXISTING_DUPLICATE_TITLE,
                folderId = null,
                includeSourceAsset = false,
            )
            assertSeededSource(source, profile)
            source.reopen()
            destination.reopen()

            val blockingWorkspace = BlockingBackupWorkspace(FileLibraryBackupWorkspace(backupRoot))
            val cancelledBackup = async(Dispatchers.Default) {
                LibraryBackupEngine(
                    snapshotSource = RoomLibraryBackupSnapshotSource(source.dao, source.fileStore),
                    workspace = blockingWorkspace,
                    operationGate = LibraryOperationGate(),
                    clock = LibraryBackupClock { BACKUP_CREATED_AT },
                    idSource = LibraryBackupIdSource { CANCELLATION_BACKUP_ID },
                ).create(PRODUCER)
            }
            val archiveCreated = withContext(Dispatchers.IO) {
                blockingWorkspace.awaitArchiveCreation(CANCELLATION_TIMEOUT_SECONDS)
            }
            cancelledBackup.cancel()
            blockingWorkspace.releaseArchiveCreation()
            cancelledBackup.cancelAndJoin()
            assertTrue("Backup did not reach temporary-archive creation", archiveCreated)
            assertEquals(0L, diskBytes(backupRoot))
            assertTrue(backupRoot.listFiles().isNullOrEmpty())
            assertEquals(0L, diskBytes(File(sourceRoot.parentFile, PRIVATE_BACKUP_SPOOL_DIRECTORY)))
            source.reopen()
            assertEquals(profile.documentCount.toLong(), source.sqlCount(ACTIVE_DOCUMENT_COUNT_SQL))

            val successfulBackup = LibraryBackupEngine(
                snapshotSource = RoomLibraryBackupSnapshotSource(source.dao, source.fileStore),
                workspace = FileLibraryBackupWorkspace(backupRoot),
                operationGate = LibraryOperationGate(),
                clock = LibraryBackupClock { BACKUP_CREATED_AT },
                idSource = LibraryBackupIdSource { backupId(profile) },
            ).create(PRODUCER)
            val verifiedArtifact = (successfulBackup as? LibraryBackupCreationResult.Verified)?.artifact
                ?: error("Backup after cancellation did not succeed: $successfulBackup")
            artifact = verifiedArtifact

            val cancellationRequested = AtomicBoolean(false)
            val cancellationPreflight = ObservedProductionRestorePreflight()
            val cancellingRestore = restoreEngine(
                destination = destination,
                restoreRoot = restoreRoot,
                firstId = profile.documentCount.toLong() * 20_000L,
                storagePreflight = cancellationPreflight,
            )
            val cancelledPreparationResult = cancellingRestore.prepare(
                RestoreArchiveSource(verifiedArtifact.sizeBytes) { FileInputStream(verifiedArtifact.file) },
            )
            val cancelledPreparation = cancelledPreparationResult as? RestorePreparationResult.Ready
                ?: error("Restore preparation for cancellation did not succeed: $cancelledPreparationResult")
            val cancelledResult = cancellingRestore.restore(
                prepared = cancelledPreparation.prepared,
                policy = RestoreMergePolicy.MERGE_IMPORT_ANYWAY,
                cancellationSignal = RestoreCancellationSignal(cancellationRequested::get),
                progressListener = RestoreProgressListener { progress ->
                    if (progress.preparedDocumentCount >= RESTORE_CANCEL_AFTER_DOCUMENTS) {
                        cancellationRequested.set(true)
                    }
                },
            )
            val cancellation = cancelledResult as? RestoreResult.Cancelled
                ?: error("Restore under load was not cancelled: $cancelledResult")
            assertTrue(cancellation.cleanupSucceeded)
            assertTrue(cancellationRequested.get())
            assertEquals(0L, diskBytes(restoreRoot))
            assertTrue(restoreRoot.listFiles().isNullOrEmpty())
            assertEquals(1L, destination.sqlCount(ACTIVE_DOCUMENT_COUNT_SQL))
            assertEquals(0L, destination.sqlCount(PENDING_DOCUMENT_COUNT_SQL))
            assertEquals(0L, destination.foreignKeyViolationCount())

            destination.reopen()
            assertEquals(1L, destination.sqlCount(ACTIVE_DOCUMENT_COUNT_SQL))
            assertEquals(0L, destination.sqlCount(PENDING_DOCUMENT_COUNT_SQL))
            val retryPreflight = ObservedProductionRestorePreflight()
            val successfulRestore = restoreEngine(
                destination = destination,
                restoreRoot = restoreRoot,
                firstId = profile.documentCount.toLong() * 30_000L,
                storagePreflight = retryPreflight,
            )
            val successfulPreparation = successfulRestore.prepare(
                RestoreArchiveSource(verifiedArtifact.sizeBytes) { FileInputStream(verifiedArtifact.file) },
            ) as? RestorePreparationResult.Ready
                ?: error("Restore preparation after cancellation did not succeed")
            val completed = successfulRestore.restore(
                successfulPreparation.prepared,
                RestoreMergePolicy.MERGE_IMPORT_ANYWAY,
            ) as? RestoreResult.Completed
                ?: error("Restore after cancellation did not complete")
            assertEquals(profile.documentCount, completed.importedDocumentCount)
            assertTrue(completed.stagingCleanupSucceeded)
            destination.reopen()
            verifyRestoredLibrary(destination, profile)
            assertEquals(0L, diskBytes(restoreRoot))
            assertTrue(restoreRoot.listFiles().isNullOrEmpty())

            verifiedArtifact.close()
            assertTrue(verifiedArtifact.isClosed)
            assertEquals(0L, diskBytes(backupRoot))
            Log.i(
                LOG_TAG,
                "cancellationProfile=${profile.name} backupCancelled=true " +
                    "restoreCancelledAfter=$RESTORE_CANCEL_AFTER_DOCUMENTS " +
                    "subsequentBackup=true subsequentRestore=true databaseReopen=true cleanup=true " +
                    "cancelPreflight=${formatPreflight(cancellationPreflight.observations())} " +
                    "retryPreflight=${formatPreflight(retryPreflight.observations())}",
            )
        } finally {
            artifact?.close()
            source.closeAndDelete()
            destination.closeAndDelete()
            profileRoot.deleteRecursively()
        }
        Unit
    }

    private fun restoreEngine(
        destination: StressLibraryRig,
        restoreRoot: File,
        firstId: Long,
        storagePreflight: RestoreStoragePreflight,
    ) = LibraryRestoreEngine(
        store = RoomRestoreLibraryStore(
            context = context,
            dao = destination.dao,
            fileStore = destination.fileStore,
            clock = RestoreClock { RESTORED_AT },
        ),
        stagingWorkspace = FileRestoreStagingWorkspace(restoreRoot),
        operationGate = LibraryOperationGate(),
        storagePreflight = storagePreflight,
        idSource = SequentialStressRestoreIdSource(firstId),
        clock = RestoreClock { RESTORED_AT },
    )

    private suspend fun runProfile(profile: StressProfile): ProfileReport {
        val runId = UUID.randomUUID().toString()
        val profileRoot = File(context.cacheDir, "phase2b-backup-restore/${profile.name}-$runId")
        val sourceRoot = File(profileRoot, "source/library")
        val destinationRoot = File(profileRoot, "destination/library")
        val backupRoot = File(profileRoot, "backup-workspace")
        val verificationRoot = File(profileRoot, "verification-staging")
        val restoreRoot = File(profileRoot, "restore-staging")
        val source = StressLibraryRig.create(
            context = context,
            libraryRoot = sourceRoot,
            databaseName = "phase2b-source-$runId.db",
        )
        val destination = StressLibraryRig.create(
            context = context,
            libraryRoot = destinationRoot,
            databaseName = "phase2b-destination-$runId.db",
        )
        var artifact: VerifiedLibraryBackupArtifact? = null
        var prepared: PreparedRestore? = null

        try {
            seedFolders(source)
            seedDocuments(source, profile.documentCount)
            seedDocument(
                rig = destination,
                sourceDocumentIndex = 0,
                documentId = EXISTING_DUPLICATE_ID,
                title = EXISTING_DUPLICATE_TITLE,
                folderId = null,
                includeSourceAsset = false,
            )
            assertSeededSource(source, profile)

            // Reopen both databases before the measured workflow so the test proves persistence and
            // excludes fixture-generation statements and caches from the operational query counts.
            source.reopen()
            destination.reopen()
            assertEquals(profile.documentCount.toLong(), source.sqlCount(ACTIVE_DOCUMENT_COUNT_SQL))
            assertEquals(1L, destination.sqlCount(ACTIVE_DOCUMENT_COUNT_SQL))
            val destinationDatabaseBefore = destination.databaseFootprint()

            val backupMeasurement = measurePhase(
                queryCounter = source.queries,
                diskTargets = source.databaseDiskTargets("backupSource") + listOf(
                    StressDiskTarget("backupArchiveTemp", backupRoot),
                    StressDiskTarget(
                        "backupSnapshotSpool",
                        File(sourceRoot.parentFile, PRIVATE_BACKUP_SPOOL_DIRECTORY),
                    ),
                ),
            ) {
                LibraryBackupEngine(
                    snapshotSource = RoomLibraryBackupSnapshotSource(source.dao, source.fileStore),
                    workspace = FileLibraryBackupWorkspace(backupRoot),
                    operationGate = LibraryOperationGate(),
                    clock = LibraryBackupClock { BACKUP_CREATED_AT },
                    idSource = LibraryBackupIdSource { backupId(profile) },
                ).create(PRODUCER)
            }
            val creation = backupMeasurement.value
            val verifiedArtifact = (creation as? LibraryBackupCreationResult.Verified)?.artifact
                ?: error("${profile.name} backup was not verified: $creation")
            artifact = verifiedArtifact
            assertEquals(LibraryBackupArtifactState.VERIFIED, verifiedArtifact.state)
            assertTrue(verifiedArtifact.file.isFile)
            assertTrue(verifiedArtifact.sizeBytes > 0L)
            assertBackupManifest(verifiedArtifact, profile)
            assertBackupBounds(profile, backupMeasurement.metrics)
            assertTrue(verifiedArtifact.storageEstimate.existingSnapshotSpoolBytes > 0L)
            assertEquals(0L, diskBytes(File(sourceRoot.parentFile, PRIVATE_BACKUP_SPOOL_DIRECTORY)))
            val archiveBytes = verifiedArtifact.sizeBytes
            val backupTemporaryBytes = diskBytes(backupRoot)
            assertTrue(backupTemporaryBytes >= archiveBytes)
            assertTrue(
                backupMeasurement.metrics.diskPeaks.getValue("backupArchiveTemp") >= archiveBytes,
            )

            val verificationWorkspace = FileRestoreStagingWorkspace(verificationRoot)
            val verificationMeasurement = measurePhase(
                queryCounter = source.queries,
                diskTargets = listOf(StressDiskTarget("verificationTemp", verificationRoot)),
            ) {
                val verificationArea = verificationWorkspace.create(UUID.randomUUID().toString())
                try {
                    val verified = FileInputStream(verifiedArtifact.file).use { input ->
                        BackupArchiveReader.readAndVerify(
                            source = input,
                            staging = verificationArea as BackupIndexedStagingSink,
                            limits = FORMAT_LIMITS,
                        )
                    }
                    assertEquals(verifiedArtifact.manifest, verified.manifest)
                    assertEquals(profile.documentCount, verified.records.totals().documentCount)
                    verificationArea
                } catch (failure: Throwable) {
                    verificationArea.discard()
                    throw failure
                }
            }
            val verificationArea = verificationMeasurement.value
            val verificationStagingBytes = diskBytes(verificationRoot)
            assertTrue(verificationStagingBytes > 0L)
            assertTrue(
                verificationMeasurement.metrics.diskPeaks.getValue("verificationTemp") >=
                    verificationStagingBytes,
            )
            assertMemoryBounded("archive reopen verification", verificationMeasurement.metrics.memoryGrowth)
            assertTrue(verificationArea.discard())
            assertEquals(0L, diskBytes(verificationRoot))
            assertTrue(verificationRoot.listFiles().isNullOrEmpty())

            val restorePreflight = ObservedProductionRestorePreflight()
            val restoreEngine = LibraryRestoreEngine(
                store = RoomRestoreLibraryStore(
                    context = context,
                    dao = destination.dao,
                    fileStore = destination.fileStore,
                    clock = RestoreClock { RESTORED_AT },
                ),
                stagingWorkspace = FileRestoreStagingWorkspace(restoreRoot),
                operationGate = LibraryOperationGate(),
                storagePreflight = restorePreflight,
                idSource = SequentialStressRestoreIdSource(profile.documentCount.toLong() * 10_000L),
                clock = RestoreClock { RESTORED_AT },
            )
            val prepareMeasurement = measurePhase(
                queryCounter = destination.queries,
                diskTargets = destination.databaseDiskTargets("prepareDestination") +
                    StressDiskTarget("prepareRestoreTemp", restoreRoot),
            ) {
                restoreEngine.prepare(
                    RestoreArchiveSource(verifiedArtifact.sizeBytes) { FileInputStream(verifiedArtifact.file) },
                )
            }
            val preparation = prepareMeasurement.value
            val readyRestore = (preparation as? RestorePreparationResult.Ready)?.prepared
                ?: error("${profile.name} restore was not prepared: $preparation")
            prepared = readyRestore
            assertRestorePreview(readyRestore.preview, profile)
            assertPrepareBounds(profile, prepareMeasurement.metrics)
            val restoreStagingBytes = diskBytes(restoreRoot)
            assertTrue("Restore staging was empty after preparation", restoreStagingBytes > 0L)
            assertTrue(
                prepareMeasurement.metrics.diskPeaks.getValue("prepareRestoreTemp") >= restoreStagingBytes,
            )

            val activationMeasurement = measurePhase(
                queryCounter = destination.queries,
                diskTargets = destination.databaseDiskTargets("activateDestination") +
                    StressDiskTarget("activateRestoreTemp", restoreRoot),
            ) {
                restoreEngine.restore(readyRestore, RestoreMergePolicy.MERGE_IMPORT_ANYWAY)
            }
            val restoreResult = activationMeasurement.value as? RestoreResult.Completed
                ?: error("${profile.name} restore did not complete: ${activationMeasurement.value}")
            assertEquals(profile.documentCount, restoreResult.importedDocumentCount)
            assertEquals(0, restoreResult.skippedExactDocumentCount)
            assertTrue(restoreResult.stagingCleanupSucceeded)
            assertActivationBounds(profile, activationMeasurement.metrics)
            assertEquals(0L, diskBytes(restoreRoot))
            assertTrue(restoreRoot.listFiles().isNullOrEmpty())

            destination.reopen()
            verifyRestoredLibrary(destination, profile)
            val sourceDatabase = source.databaseFootprint()
            val destinationDatabase = destination.databaseFootprint()
            val restoredAssetBytes = diskBytes(destination.fileStore.root)
            assertTrue(restoredAssetBytes > 0L)
            val report = ProfileReport(
                profile = profile,
                backup = backupMeasurement.metrics,
                verification = verificationMeasurement.metrics,
                prepare = prepareMeasurement.metrics,
                activation = activationMeasurement.metrics,
                sourceDatabase = sourceDatabase,
                destinationDatabaseBefore = destinationDatabaseBefore,
                destinationDatabase = destinationDatabase,
                archiveBytes = archiveBytes,
                backupTemporaryBytes = backupTemporaryBytes,
                verificationStagingBytes = verificationStagingBytes,
                restoreStagingBytes = restoreStagingBytes,
                restoredAssetBytes = restoredAssetBytes,
                restorePreflight = restorePreflight.observations(),
            )
            logReport(report)

            verifiedArtifact.close()
            assertTrue(verifiedArtifact.isClosed)
            assertFalse(verifiedArtifact.file.exists())
            assertEquals(0L, diskBytes(backupRoot))
            assertTrue(backupRoot.listFiles().isNullOrEmpty())
            return report
        } finally {
            prepared?.close()
            artifact?.close()
            source.closeAndDelete()
            destination.closeAndDelete()
            profileRoot.deleteRecursively()
        }
    }

    private suspend fun seedFolders(rig: StressLibraryRig) {
        rig.database.withTransaction {
            repeat(ROOT_FOLDER_COUNT) { rootIndex ->
                val rootId = rootFolderId(rootIndex)
                rig.dao.insertFolder(
                    LibraryFolderEntity(
                        folderId = rootId,
                        name = "Stress root ${rootIndex.toString().padStart(2, '0')}",
                        normalizedName = "stress root ${rootIndex.toString().padStart(2, '0')}",
                        createdAtMillis = FIXTURE_EPOCH + rootIndex,
                        modifiedAtMillis = FIXTURE_EPOCH + 100 + rootIndex,
                    ),
                )
                repeat(CHILDREN_PER_ROOT) { childIndex ->
                    rig.dao.insertFolder(
                        LibraryFolderEntity(
                            folderId = childFolderId(rootIndex, childIndex),
                            name = "Collection ${childIndex.toString().padStart(2, '0')}",
                            normalizedName = "collection ${childIndex.toString().padStart(2, '0')}",
                            createdAtMillis = FIXTURE_EPOCH + 1_000 + (rootIndex * 10) + childIndex,
                            modifiedAtMillis = FIXTURE_EPOCH + 2_000 + (rootIndex * 10) + childIndex,
                            parentFolderId = rootId,
                        ),
                    )
                }
            }
        }
    }

    private suspend fun seedDocuments(rig: StressLibraryRig, documentCount: Int) {
        rig.database.withTransaction {
            repeat(documentCount) { documentIndex ->
                seedDocument(
                    rig = rig,
                    sourceDocumentIndex = documentIndex,
                    documentId = sourceDocumentId(documentIndex),
                    title = sourceDocumentTitle(documentIndex),
                    folderId = if (documentIndex % UNFILED_DOCUMENT_INTERVAL == 0) {
                        null
                    } else {
                        val childOrdinal = documentIndex % (ROOT_FOLDER_COUNT * CHILDREN_PER_ROOT)
                        childFolderId(
                            rootIndex = childOrdinal / CHILDREN_PER_ROOT,
                            childIndex = childOrdinal % CHILDREN_PER_ROOT,
                        )
                    },
                    includeSourceAsset = documentIndex % SOURCE_ASSET_INTERVAL == 0,
                )
            }
        }
    }

    private suspend fun seedDocument(
        rig: StressLibraryRig,
        sourceDocumentIndex: Int,
        documentId: String,
        title: String,
        folderId: String?,
        includeSourceAsset: Boolean,
    ) {
        val pages = List(pageCountForDocument(sourceDocumentIndex)) { position ->
            val pageId = "$documentId-page-$position"
            val bytes = syntheticPng(sourceDocumentIndex, position)
            val relativePath = "$documentId/revisions/stress/$pageId.png"
            val file = File(rig.fileStore.root, relativePath)
            val parent = requireNotNull(file.parentFile)
            check(parent.isDirectory || parent.mkdirs())
            file.writeBytes(bytes)
            val sha256 = sha256(bytes)
            LibraryPageEntity(
                pageId = pageId,
                documentId = documentId,
                position = position,
                relativePath = relativePath,
                contentType = "image/png",
                sourceCategory = SOURCE_CATEGORIES[(sourceDocumentIndex + position) % SOURCE_CATEGORIES.size].name,
                width = PAGE_WIDTH,
                height = PAGE_HEIGHT,
                sourceByteCount = bytes.size.toLong(),
                rotationDegrees = ROTATIONS[(sourceDocumentIndex + position) % ROTATIONS.size],
                filterName = FILTERS[(sourceDocumentIndex + position) % FILTERS.size].name,
                ocrText = if (position == LEGACY_OCR_PAGE && sourceDocumentIndex % 2 == 0) {
                    legacyOcrText(sourceDocumentIndex)
                } else {
                    null
                },
                ocrError = if (position == FAILURE_PAGE && sourceDocumentIndex % OCR_FAILURE_INTERVAL == 0) {
                    SAFE_OCR_ERROR
                } else {
                    null
                },
                contentSha256 = sha256,
            )
        }
        val fingerprint = DocumentFingerprintV1.calculate(
            pages.map { page ->
                FingerprintPage(
                    assetSha256 = requireNotNull(page.contentSha256),
                    mimeType = page.contentType,
                    byteLength = requireNotNull(page.sourceByteCount),
                    rotationDegrees = page.rotationDegrees,
                    filterName = page.filterName,
                )
            },
        )
        val sourceAssets = if (includeSourceAsset) {
            val bytes = syntheticPdf(sourceDocumentIndex)
            val assetId = "$documentId-source"
            val relativePath = "$documentId/revisions/stress/$assetId.pdf"
            val file = File(rig.fileStore.root, relativePath)
            val parent = requireNotNull(file.parentFile)
            check(parent.isDirectory || parent.mkdirs())
            file.writeBytes(bytes)
            listOf(
                LibrarySourceAssetEntity(
                    assetId = assetId,
                    documentId = documentId,
                    role = "ORIGINAL_DOCUMENT",
                    relativePath = relativePath,
                    contentType = "application/pdf",
                    byteCount = bytes.size.toLong(),
                    sha256 = sha256(bytes),
                    sourceModifiedAtMillis = FIXTURE_EPOCH + sourceDocumentIndex,
                    createdAtMillis = FIXTURE_EPOCH + sourceDocumentIndex,
                    matchesCurrentRevision = sourceDocumentIndex % 2 == 0,
                ),
            )
        } else {
            emptyList()
        }
        val createdAt = FIXTURE_EPOCH + (sourceDocumentIndex * 10L)
        val modifiedAt = createdAt + 5L
        rig.dao.replaceDocument(
            document = LibraryDocumentEntity(
                documentId = documentId,
                title = title,
                createdAtMillis = createdAt,
                modifiedAtMillis = modifiedAt,
                pageCount = pages.size,
                folderId = folderId,
                thumbnailRelativePath = null,
                ocrStatus = LibraryOcrStatus.NOT_INDEXED.name,
                contentHashVersion = fingerprint.version,
                contentSha256 = fingerprint.sha256,
                contentByteCount = pages.sumOf { requireNotNull(it.sourceByteCount) },
                sourceModifiedAtMillis = sourceAssets.firstOrNull()?.sourceModifiedAtMillis,
                importedAtMillis = createdAt + 1L,
                contentRevision = sourceDocumentIndex.toLong() % 4L,
                ocrScriptPreference = OCR_SCRIPTS[sourceDocumentIndex % OCR_SCRIPTS.size],
            ),
            pages = pages,
            ocrText = pages.joinToString("\n\n") { page -> page.ocrText.orEmpty() },
            sourceAssets = sourceAssets,
        )
        seedVerifiedOcr(rig, sourceDocumentIndex, documentId, pages.first(), modifiedAt)
    }

    private suspend fun seedVerifiedOcr(
        rig: StressLibraryRig,
        sourceDocumentIndex: Int,
        documentId: String,
        page: LibraryPageEntity,
        modifiedAt: Long,
    ) {
        val expected = requireNotNull(rig.dao.ocrPageSnapshot(documentId, page.pageId))
        val lineCount = ocrLineCountForDocument(sourceDocumentIndex)
        val lines = List(lineCount) { ordinal ->
            positionedLine(
                ordinal = ordinal,
                text = rawOcrLine(sourceDocumentIndex, ordinal),
                top = ordinal.toDouble() / lineCount.toDouble(),
                bottom = (ordinal.toDouble() + 0.8) / lineCount.toDouble(),
            )
        }
        val actualScript = OCR_SCRIPTS[sourceDocumentIndex % OCR_SCRIPTS.size]
            .takeUnless { it == "AUTOMATIC" }
            ?: "LATIN"
        assertEquals(
            LibraryOcrCommitResult.APPLIED,
            rig.dao.commitOcrArtifact(
                expected = expected,
                draft = LibraryOcrArtifactDraft(
                    inputFingerprintVersion = 1,
                    inputFingerprint = "stress-input-${sourceDocumentIndex.toString().padStart(4, '0')}",
                    contentSha256 = requireNotNull(expected.contentSha256),
                    rotationDegrees = expected.rotationDegrees,
                    filterName = expected.filterName,
                    uprightWidth = PAGE_WIDTH,
                    uprightHeight = PAGE_HEIGHT,
                    coordinateSystemVersion = 1,
                    transformVersion = 1,
                    actualScript = actualScript,
                    recognizerId = if (actualScript == "LATIN") {
                        "ML_KIT_LATIN_BUNDLED"
                    } else {
                        "ML_KIT_${actualScript}_PLAY_SERVICES"
                    },
                    pipelineVersion = "phase2b-stress-v1",
                    clientVersion = "synthetic",
                    delivery = if (actualScript == "LATIN") "BUNDLED" else "PLAY_SERVICES",
                    recognizedAtMillis = modifiedAt + 1L,
                    rawText = lines.joinToString("\n", transform = LibraryOcrLineDraft::rawText),
                    lines = lines,
                ),
                modifiedAt = modifiedAt + 1L,
            ),
        )
        val correction = when {
            sourceDocumentIndex % LINE_ALIGNED_CORRECTION_INTERVAL == 0 -> {
                val correctedLines = List(lineCount) { line ->
                    correctedOcrLine(sourceDocumentIndex, line)
                }
                LibraryOcrCorrectionDraft(
                    correctedText = correctedLines.joinToString("\n"),
                    alignment = LibraryOcrCorrectionAlignment.LINE_ALIGNED,
                    correctedLines = correctedLines,
                )
            }
            sourceDocumentIndex % FREEFORM_CORRECTION_INTERVAL == 0 -> LibraryOcrCorrectionDraft(
                correctedText = freeformCorrection(sourceDocumentIndex),
                alignment = LibraryOcrCorrectionAlignment.FREEFORM,
            )
            else -> null
        }
        if (correction != null) {
            assertEquals(
                LibraryOcrCommitResult.APPLIED,
                rig.dao.saveOcrCorrection(
                    expected = requireNotNull(rig.dao.ocrPageSnapshot(documentId, page.pageId)),
                    correction = correction,
                    modifiedAt = modifiedAt + 2L,
                ),
            )
        }
    }

    private suspend fun assertSeededSource(rig: StressLibraryRig, profile: StressProfile) {
        val expected = expectedCounts(profile.documentCount)
        assertEquals(expected.documents.toLong(), rig.sqlCount(ACTIVE_DOCUMENT_COUNT_SQL))
        assertEquals(expected.pages.toLong(), rig.sqlCount(PAGE_COUNT_SQL))
        assertEquals(expected.sourceAssets.toLong(), rig.sqlCount(SOURCE_ASSET_COUNT_SQL))
        assertEquals(expected.ocrArtifacts.toLong(), rig.sqlCount(OCR_ARTIFACT_COUNT_SQL))
        assertEquals(expected.ocrCorrections.toLong(), rig.sqlCount(OCR_CORRECTION_COUNT_SQL))
        assertEquals(expected.ocrLines.toLong(), rig.sqlCount(OCR_LINE_COUNT_SQL))
        assertEquals(0L, rig.foreignKeyViolationCount())
        val sample = rig.dao.pages(sourceDocumentId(1))
        val expectedSamplePages = pageCountForDocument(1)
        assertEquals(expectedSamplePages, sample.size)
        assertEquals(expectedSamplePages, sample.map(LibraryPageEntity::sourceCategory).distinct().size)
        assertEquals(expectedSamplePages, sample.map(LibraryPageEntity::rotationDegrees).distinct().size)
        assertEquals(expectedSamplePages, sample.map(LibraryPageEntity::filterName).distinct().size)
    }

    private fun assertBackupManifest(
        artifact: VerifiedLibraryBackupArtifact,
        profile: StressProfile,
    ) {
        val expected = expectedCounts(profile.documentCount)
        val manifest = artifact.manifest
        assertEquals(expected.documents, manifest.summary.documentCount)
        assertEquals(expected.pages, manifest.summary.pageCount)
        assertEquals(FOLDER_COUNT, manifest.summary.folderCount)
        assertEquals(expected.sourceAssets, manifest.summary.sourceAssetCount)
        assertTrue(manifest.summary.contentByteLength > 0L)
        val ocr = requireNotNull(manifest.ocr)
        assertEquals(expected.documents, ocr.documentStateCount)
        assertEquals(expected.pages, ocr.pageStateCount)
        assertEquals(expected.ocrArtifacts, ocr.artifactCount)
        assertEquals(expected.ocrCorrections, ocr.correctionCount)
        assertEquals(expected.ocrLines, ocr.lineCount)
        assertTrue(ocr.lineByteLength > 0L)
        assertTrue(ocr.lineChunks.isNotEmpty())
        ocr.lineChunks.forEach { chunk ->
            assertTrue(chunk.recordCount in 1..FORMAT_LIMITS.maximumOcrLineChunkRecords)
            assertTrue(chunk.byteLength in 1L..FORMAT_LIMITS.maximumOcrLineChunkBytes.toLong())
        }
    }

    private fun assertRestorePreview(preview: RestorePreview, profile: StressProfile) {
        val expected = expectedCounts(profile.documentCount)
        assertEquals(backupId(profile), preview.backupId)
        assertEquals(expected.documents, preview.documentCount)
        assertEquals(expected.pages, preview.pageCount)
        assertEquals(FOLDER_COUNT, preview.folderCount)
        assertEquals(expected.sourceAssets, preview.sourceAssetCount)
        assertTrue(preview.contentByteLength > 0L)
        assertTrue("The preservation-equivalent duplicate was not detected", preview.exactDuplicateCount >= 1)
        assertTrue(preview.exactDuplicateCount + preview.possibleDuplicateCount <= profile.documentCount)
    }

    private suspend fun verifyRestoredLibrary(rig: StressLibraryRig, profile: StressProfile) {
        val expected = expectedCounts(profile.documentCount)
        assertEquals((expected.documents + 1).toLong(), rig.sqlCount(ACTIVE_DOCUMENT_COUNT_SQL))
        assertEquals(0L, rig.sqlCount(PENDING_DOCUMENT_COUNT_SQL))
        assertEquals(
            (expected.pages + pageCountForDocument(0)).toLong(),
            rig.sqlCount(PAGE_COUNT_SQL),
        )
        assertEquals(expected.sourceAssets.toLong(), rig.sqlCount(SOURCE_ASSET_COUNT_SQL))
        assertEquals((expected.ocrArtifacts + DUPLICATE_OCR_ARTIFACTS).toLong(), rig.sqlCount(OCR_ARTIFACT_COUNT_SQL))
        assertEquals((expected.ocrCorrections + 1).toLong(), rig.sqlCount(OCR_CORRECTION_COUNT_SQL))
        assertEquals(
            (expected.ocrLines + ocrLineCountForDocument(0)).toLong(),
            rig.sqlCount(OCR_LINE_COUNT_SQL),
        )
        assertEquals(0L, rig.foreignKeyViolationCount())
        assertEquals(FOLDER_COUNT.toLong(), rig.sqlCount(FOLDER_COUNT_SQL))

        val documents = rig.activeDocuments()
        val importedZero = documents.single { it.title == sourceDocumentTitle(0) }
        assertFalse(importedZero.documentId == sourceDocumentId(0))
        assertEquals(OCR_SCRIPTS[0], importedZero.ocrScriptPreference)
        val importedZeroPages = rig.dao.pages(importedZero.documentId)
        assertEquals(pageCountForDocument(0), importedZeroPages.size)
        assertTrue(importedZeroPages.none { page -> page.pageId.startsWith(sourceDocumentId(0)) })
        assertEquals(DocumentSourceCategory.LIBRARY.name, importedZeroPages.first().sourceCategory)
        assertEquals(ROTATIONS[0], importedZeroPages[0].rotationDegrees)
        assertEquals(FILTERS[0].name, importedZeroPages[0].filterName)
        val lineAligned = requireNotNull(
            rig.dao.effectiveOcrPage(importedZero.documentId, importedZeroPages[VERIFIED_OCR_PAGE].pageId),
        )
        assertEffectiveLineAligned(lineAligned, 0)

        if (profile.documentCount > FREEFORM_CORRECTION_INTERVAL) {
            val importedFreeform = documents.single { it.title == sourceDocumentTitle(FREEFORM_CORRECTION_INTERVAL) }
            val freeformPage = rig.dao.pages(importedFreeform.documentId)[VERIFIED_OCR_PAGE]
            val effective = requireNotNull(rig.dao.effectiveOcrPage(importedFreeform.documentId, freeformPage.pageId))
            assertEquals(freeformCorrection(FREEFORM_CORRECTION_INTERVAL), effective.effectiveText)
            assertEquals(LibraryOcrCorrectionAlignment.FREEFORM, effective.alignment)
            assertTrue(effective.lines.isEmpty())
        }

        val importedLegacy = documents.single { it.title == sourceDocumentTitle(2) }
        val legacyPage = rig.dao.pages(importedLegacy.documentId)[LEGACY_OCR_PAGE]
        val legacy = requireNotNull(rig.dao.effectiveOcrPage(importedLegacy.documentId, legacyPage.pageId))
        assertEquals(legacyOcrText(2), legacy.rawText)
        assertEquals("LEGACY_UNVERIFIED", legacy.verification.name)

        val importedFailure = documents.single { it.title == sourceDocumentTitle(OCR_FAILURE_INTERVAL) }
        val failurePage = rig.dao.pages(importedFailure.documentId)[FAILURE_PAGE]
        assertEquals(SAFE_OCR_ERROR, failurePage.ocrError)
        assertNull(rig.dao.effectiveOcrPage(importedFailure.documentId, failurePage.pageId))

        val importedUnindexed = documents.single { it.title == sourceDocumentTitle(1) }
        val unindexedPage = rig.dao.pages(importedUnindexed.documentId)[FAILURE_PAGE]
        assertNull(unindexedPage.ocrError)
        assertNull(rig.dao.effectiveOcrPage(importedUnindexed.documentId, unindexedPage.pageId))
        val searchableCorrections = rig.dao.pageSearchPage(
            ftsQuery = "corrected",
            beforeModifiedAt = Long.MAX_VALUE,
            afterPageId = "",
            limit = profile.documentCount + 1,
        )
        assertTrue(searchableCorrections.any { result -> result.title == sourceDocumentTitle(0) })
        assertTrue(rig.dao.sourceAssets(importedZero.documentId).isNotEmpty())
        rig.dao.sourceAssets(importedZero.documentId).forEach { source ->
            val file = requireNotNull(rig.fileStore.resolve(source.relativePath))
            assertTrue(file.isFile && file.length() == source.byteCount)
        }

        val folders = rig.dao.foldersPage(0, FOLDER_COUNT + 1)
        assertEquals(FOLDER_COUNT, folders.size)
        val folderById = folders.associateBy(LibraryFolderEntity::folderId)
        assertEquals(ROOT_FOLDER_COUNT, folders.count { it.parentFolderId == null })
        assertEquals(
            ROOT_FOLDER_COUNT * CHILDREN_PER_ROOT,
            folders.count { folder -> folder.parentFolderId?.let(folderById::containsKey) == true },
        )
    }

    private fun assertEffectiveLineAligned(page: LibraryEffectiveOcrPage, sourceDocumentIndex: Int) {
        assertEquals(
            List(ocrLineCountForDocument(sourceDocumentIndex)) { line ->
                correctedOcrLine(sourceDocumentIndex, line)
            }.joinToString("\n"),
            page.effectiveText,
        )
        assertEquals(LibraryOcrCorrectionAlignment.LINE_ALIGNED, page.alignment)
        assertEquals("CURRENT_VERIFIED", page.verification.name)
        assertEquals(ocrLineCountForDocument(sourceDocumentIndex), page.lines.size)
        assertEquals(correctedOcrLine(sourceDocumentIndex, 0), page.lines[0].text)
        assertEquals(0.0, page.lines[0].topLeftY, 0.0)
        assertEquals(0.90, page.lines[0].topRightX, 0.0)
        assertEquals("HORIZONTAL", page.lines[0].writingOrientation)
    }

    private fun assertBackupBounds(profile: StressProfile, metrics: PhaseMetrics) {
        val pageBatches = ceilDiv(profile.pageCount, BACKUP_OCR_PAGE_BATCH_SIZE)
        val documentBatches = ceilDiv(profile.documentCount, DATABASE_BATCH_SIZE)
        val upperBound = BACKUP_QUERY_BASE_ALLOWANCE +
            (pageBatches * BACKUP_QUERIES_PER_OCR_BATCH_ALLOWANCE) +
            (documentBatches * BACKUP_QUERIES_PER_DOCUMENT_BATCH_ALLOWANCE)
        assertTrue(
            "Backup issued ${metrics.queries.queryCount} queries; expected <= $upperBound",
            metrics.queries.queryCount <= upperBound,
        )
        assertTrue(
            "Backup used ${metrics.queries.maximumBindArgumentCount} bind arguments",
            metrics.queries.maximumBindArgumentCount <= BACKUP_MAX_BIND_ARGUMENTS,
        )
        assertMemoryBounded("backup", metrics.memoryGrowth)
    }

    private fun assertPrepareBounds(profile: StressProfile, metrics: PhaseMetrics) {
        val upperBound = PREPARE_QUERY_BASE_ALLOWANCE +
            (profile.documentCount.toLong() * PREPARE_QUERIES_PER_DOCUMENT_ALLOWANCE)
        assertTrue(
            "Restore preparation issued ${metrics.queries.queryCount} destination queries; " +
                "expected <= $upperBound",
            metrics.queries.queryCount <= upperBound,
        )
        assertTrue(metrics.queries.maximumBindArgumentCount <= DATABASE_BATCH_SIZE)
        assertMemoryBounded("restore preparation", metrics.memoryGrowth)
    }

    private fun assertActivationBounds(profile: StressProfile, metrics: PhaseMetrics) {
        val upperBound = RESTORE_QUERY_BASE_ALLOWANCE +
            (profile.documentCount.toLong() * RESTORE_QUERIES_PER_DOCUMENT_ALLOWANCE)
        assertTrue(
            "Restore activation issued ${metrics.queries.queryCount} queries; expected <= $upperBound",
            metrics.queries.queryCount <= upperBound,
        )
        assertTrue(metrics.queries.maximumBindArgumentCount <= RESTORE_MAX_BIND_ARGUMENTS)
        assertMemoryBounded("restore activation", metrics.memoryGrowth)
    }

    private fun assertMemoryBounded(phase: String, growth: StressMemoryPeak) {
        val managedLimit = minOf(MAX_MANAGED_HEAP_GROWTH_BYTES, Runtime.getRuntime().maxMemory() * 3L / 4L)
        assertTrue(
            "$phase managed-heap growth was ${growth.managedHeapBytes} bytes",
            growth.managedHeapBytes <= managedLimit,
        )
        assertTrue(
            "$phase native-heap growth was ${growth.nativeHeapBytes} bytes",
            growth.nativeHeapBytes <= MAX_NATIVE_HEAP_GROWTH_BYTES,
        )
        assertTrue(
            "$phase PSS growth was ${growth.processPssBytes} bytes",
            growth.processPssBytes <= MAX_PSS_GROWTH_BYTES,
        )
    }

    private fun assertManagedGrowthDoesNotTrackLibrarySize(
        phase: String,
        smaller: PhaseMetrics,
        larger: PhaseMetrics,
    ) {
        assertTrue(
            "$phase managed-heap growth tracked library size: " +
                "${smaller.memoryGrowth.managedHeapBytes} -> ${larger.memoryGrowth.managedHeapBytes}",
            larger.memoryGrowth.managedHeapBytes <=
                smaller.memoryGrowth.managedHeapBytes + MEMORY_SCALING_ALLOWANCE_BYTES,
        )
    }

    private suspend fun <T> measurePhase(
        queryCounter: StressQueryCounter,
        diskTargets: List<StressDiskTarget> = emptyList(),
        block: suspend () -> T,
    ): Measured<T> {
        queryCounter.reset()
        val probe = StressResourceProbe(
            sampleIntervalMillis = RESOURCE_SAMPLE_INTERVAL_MILLIS,
            diskTargets = diskTargets,
        )
        val startedAt = SystemClock.elapsedRealtimeNanos()
        val value = try {
            block()
        } finally {
            probe.close()
        }
        return Measured(
            value = value,
            metrics = PhaseMetrics(
                durationMillis = (SystemClock.elapsedRealtimeNanos() - startedAt) / 1_000_000L,
                memoryPeak = probe.peak(),
                memoryGrowth = probe.peakGrowth(),
                queries = queryCounter.snapshot(),
                diskPeaks = probe.diskPeaks(),
                fileCountPeaks = probe.fileCountPeaks(),
            ),
        )
    }

    private fun logReport(report: ProfileReport) {
        Log.i(
            LOG_TAG,
            "profile=${report.profile.name} docs=${report.profile.documentCount} " +
                "pages=${report.profile.pageCount} " +
                "backupMs=${report.backup.durationMillis} verifyMs=${report.verification.durationMillis} " +
                "prepareMs=${report.prepare.durationMillis} " +
                "activateMs=${report.activation.durationMillis} " +
                "backupQueries=${report.backup.queries.queryCount} " +
                "backupMaxBinds=${report.backup.queries.maximumBindArgumentCount} " +
                "prepareQueries=${report.prepare.queries.queryCount} " +
                "prepareMaxBinds=${report.prepare.queries.maximumBindArgumentCount} " +
                "activateQueries=${report.activation.queries.queryCount} " +
                "activateMaxBinds=${report.activation.queries.maximumBindArgumentCount} " +
                "backupManagedPeak=${report.backup.memoryPeak.managedHeapBytes} " +
                "backupNativePeak=${report.backup.memoryPeak.nativeHeapBytes} " +
                "backupPssPeak=${report.backup.memoryPeak.processPssBytes} " +
                "backupManagedGrowth=${report.backup.memoryGrowth.managedHeapBytes} " +
                "verifyManagedPeak=${report.verification.memoryPeak.managedHeapBytes} " +
                "verifyNativePeak=${report.verification.memoryPeak.nativeHeapBytes} " +
                "verifyPssPeak=${report.verification.memoryPeak.processPssBytes} " +
                "verifyManagedGrowth=${report.verification.memoryGrowth.managedHeapBytes} " +
                "prepareManagedPeak=${report.prepare.memoryPeak.managedHeapBytes} " +
                "prepareNativePeak=${report.prepare.memoryPeak.nativeHeapBytes} " +
                "preparePssPeak=${report.prepare.memoryPeak.processPssBytes} " +
                "prepareManagedGrowth=${report.prepare.memoryGrowth.managedHeapBytes} " +
                "activateManagedPeak=${report.activation.memoryPeak.managedHeapBytes} " +
                "activateNativePeak=${report.activation.memoryPeak.nativeHeapBytes} " +
                "activatePssPeak=${report.activation.memoryPeak.processPssBytes} " +
                "activateManagedGrowth=${report.activation.memoryGrowth.managedHeapBytes} " +
                "backupDbPeak=${report.backup.diskPeaks.getValue("backupSourceDb")} " +
                "backupWalPeak=${report.backup.diskPeaks.getValue("backupSourceWal")} " +
                "backupArchiveTempPeak=${report.backup.diskPeaks.getValue("backupArchiveTemp")} " +
                "backupSpoolPeak=${report.backup.diskPeaks.getValue("backupSnapshotSpool")} " +
                "verificationTempPeak=${report.verification.diskPeaks.getValue("verificationTemp")} " +
                "prepareWalPeak=${report.prepare.diskPeaks.getValue("prepareDestinationWal")} " +
                "prepareTempPeak=${report.prepare.diskPeaks.getValue("prepareRestoreTemp")} " +
                "activateWalPeak=${report.activation.diskPeaks.getValue("activateDestinationWal")} " +
                "activateTempPeak=${report.activation.diskPeaks.getValue("activateRestoreTemp")} " +
                "backupArchiveTempFiles=${report.backup.fileCountPeaks.getValue("backupArchiveTemp")} " +
                "backupSpoolFiles=${report.backup.fileCountPeaks.getValue("backupSnapshotSpool")} " +
                "verificationTempFiles=${report.verification.fileCountPeaks.getValue("verificationTemp")} " +
                "prepareTempFiles=${report.prepare.fileCountPeaks.getValue("prepareRestoreTemp")} " +
                "activateTempFiles=${report.activation.fileCountPeaks.getValue("activateRestoreTemp")} " +
                "restorePreflight=${formatPreflight(report.restorePreflight)} " +
                "sourceDb=${report.sourceDatabase.databaseBytes} " +
                "sourceWal=${report.sourceDatabase.walBytes} " +
                "sourceShm=${report.sourceDatabase.sharedMemoryBytes} " +
                "destinationDbBefore=${report.destinationDatabaseBefore.databaseBytes} " +
                "destinationWalBefore=${report.destinationDatabaseBefore.walBytes} " +
                "destinationDb=${report.destinationDatabase.databaseBytes} " +
                "destinationWal=${report.destinationDatabase.walBytes} " +
                "destinationShm=${report.destinationDatabase.sharedMemoryBytes} " +
                "archiveBytes=${report.archiveBytes} backupTempBytes=${report.backupTemporaryBytes} " +
                "verificationStageBytes=${report.verificationStagingBytes} " +
                "restoreStageBytes=${report.restoreStagingBytes} restoredAssetBytes=${report.restoredAssetBytes}",
        )
    }

    private fun positionedLine(
        ordinal: Int,
        text: String,
        top: Double,
        bottom: Double,
    ) = LibraryOcrLineDraft(
        lineOrdinal = ordinal,
        rawText = text,
        topLeftX = 0.10,
        topLeftY = top,
        topRightX = 0.90,
        topRightY = top,
        bottomRightX = 0.90,
        bottomRightY = bottom,
        bottomLeftX = 0.10,
        bottomLeftY = bottom,
        baselineStartX = 0.10,
        baselineStartY = bottom,
        baselineEndX = 0.90,
        baselineEndY = bottom,
        baselineAngleDegrees = 0.0,
        writingOrientation = "HORIZONTAL",
    )

    private fun syntheticPng(documentIndex: Int, pagePosition: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(PAGE_WIDTH, PAGE_HEIGHT, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(
                Color.rgb(
                    (37 + documentIndex * 17 + pagePosition * 43) and 0xff,
                    (83 + documentIndex * 29 + pagePosition * 31) and 0xff,
                    (149 + documentIndex * 41 + pagePosition * 19) and 0xff,
                ),
            )
            val identity = (documentIndex shl 2) or pagePosition
            repeat(Int.SIZE_BITS) { bit ->
                val color = if ((identity ushr bit) and 1 == 1) Color.WHITE else Color.BLACK
                bitmap.setPixel(bit % PAGE_WIDTH, bit / PAGE_WIDTH, color)
            }
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun syntheticPdf(documentIndex: Int): ByteArray {
        val pageCount = pageCountForDocument(documentIndex)
        val objectBodies = ArrayList<ByteArray>(2 + (pageCount * 2))
        objectBodies += "<< /Type /Catalog /Pages 2 0 R >>".toByteArray(Charsets.US_ASCII)
        val pageReferences = buildString {
            repeat(pageCount) { page ->
                if (page > 0) append(' ')
                append(3 + (page * 2)).append(" 0 R")
            }
        }
        objectBodies += (
            "<< /Type /Pages /Count $pageCount /Kids [$pageReferences] >>"
            ).toByteArray(Charsets.US_ASCII)
        repeat(pageCount) { page ->
            val pageObject = 3 + (page * 2)
            val contentObject = pageObject + 1
            objectBodies += (
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 32 32] /Contents $contentObject 0 R >>"
                ).toByteArray(Charsets.US_ASCII)
            val content = "% RME stress document $documentIndex page $page\n"
                .toByteArray(Charsets.US_ASCII)
            objectBodies += ByteArrayOutputStream().use { body ->
                body.write("<< /Length ${content.size} >>\nstream\n".toByteArray(Charsets.US_ASCII))
                body.write(content)
                body.write("endstream".toByteArray(Charsets.US_ASCII))
                body.toByteArray()
            }
        }

        return ByteArrayOutputStream().use { output ->
            output.write("%PDF-1.4\n% RME synthetic stress source $documentIndex\n".toByteArray(Charsets.US_ASCII))
            val offsets = IntArray(objectBodies.size + 1)
            objectBodies.forEachIndexed { index, body ->
                val objectNumber = index + 1
                offsets[objectNumber] = output.size()
                output.write("$objectNumber 0 obj\n".toByteArray(Charsets.US_ASCII))
                output.write(body)
                output.write("\nendobj\n".toByteArray(Charsets.US_ASCII))
            }
            val xrefOffset = output.size()
            output.write("xref\n0 ${offsets.size}\n".toByteArray(Charsets.US_ASCII))
            output.write("0000000000 65535 f \n".toByteArray(Charsets.US_ASCII))
            offsets.drop(1).forEach { offset ->
                output.write("${offset.toString().padStart(10, '0')} 00000 n \n".toByteArray(Charsets.US_ASCII))
            }
            output.write(
                (
                    "trailer\n<< /Size ${offsets.size} /Root 1 0 R >>\n" +
                        "startxref\n$xrefOffset\n%%EOF\n"
                    ).toByteArray(Charsets.US_ASCII),
            )
            output.toByteArray()
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }

    private fun expectedCounts(documentCount: Int): ExpectedCounts {
        val legacyArtifacts = (0 until documentCount).count { documentIndex ->
            documentIndex % 2 == 0 && pageCountForDocument(documentIndex) > LEGACY_OCR_PAGE
        }
        val alignedCorrections = ceilDiv(documentCount, LINE_ALIGNED_CORRECTION_INTERVAL).toInt()
        val freeformCorrections = ceilDiv(documentCount, FREEFORM_CORRECTION_INTERVAL).toInt() -
            ceilDiv(documentCount, lcm(LINE_ALIGNED_CORRECTION_INTERVAL, FREEFORM_CORRECTION_INTERVAL)).toInt()
        return ExpectedCounts(
            documents = documentCount,
            pages = (0 until documentCount).sumOf(::pageCountForDocument),
            sourceAssets = ceilDiv(documentCount, SOURCE_ASSET_INTERVAL).toInt(),
            ocrArtifacts = documentCount + legacyArtifacts,
            ocrCorrections = alignedCorrections + freeformCorrections,
            ocrLines = (0 until documentCount).sumOf(::ocrLineCountForDocument),
        )
    }

    private fun rawOcrLine(documentIndex: Int, line: Int): String =
        "Synthetic OCR document ${documentIndex.toString().padStart(4, '0')} line $line; " +
            "local multilingual sample Ελληνικά 日本語 हिन्दी 한국어; " +
            "geometry-preserving private test text block $documentIndex-$line."

    private fun correctedOcrLine(documentIndex: Int, line: Int): String =
        "Corrected synthetic document ${documentIndex.toString().padStart(4, '0')} line $line"

    private fun freeformCorrection(documentIndex: Int): String =
        "Freeform corrected synthetic document ${documentIndex.toString().padStart(4, '0')}\n" +
            "Second paragraph without line geometry"

    private fun legacyOcrText(documentIndex: Int): String =
        "Legacy OCR mirror for synthetic document ${documentIndex.toString().padStart(4, '0')} " +
            "with searchable local-only text"

    private fun sourceDocumentId(index: Int): String = "stress-doc-${index.toString().padStart(4, '0')}"

    private fun sourceDocumentTitle(index: Int): String =
        "Stress document ${index.toString().padStart(4, '0')}"

    private fun backupId(profile: StressProfile): String =
        "10000000-0000-4000-8000-${profile.documentCount.toString().padStart(12, '0')}"

    private fun rootFolderId(index: Int): String = "stress-root-${index.toString().padStart(2, '0')}"

    private fun childFolderId(rootIndex: Int, childIndex: Int): String =
        "stress-child-${rootIndex.toString().padStart(2, '0')}-${childIndex.toString().padStart(2, '0')}"

    private fun ceilDiv(value: Int, divisor: Int): Long =
        (value.toLong() + divisor.toLong() - 1L) / divisor.toLong()

    private fun lcm(first: Int, second: Int): Int {
        fun gcd(left: Int, right: Int): Int = if (right == 0) left else gcd(right, left % right)
        return first / gcd(first, second) * second
    }

    private data class StressProfile(
        val name: String,
        val documentCount: Int,
    ) {
        val pageCount: Int = (0 until documentCount).sumOf(::pageCountForDocument)
    }

    private data class ExpectedCounts(
        val documents: Int,
        val pages: Int,
        val sourceAssets: Int,
        val ocrArtifacts: Int,
        val ocrCorrections: Int,
        val ocrLines: Int,
    )

    private data class Measured<T>(
        val value: T,
        val metrics: PhaseMetrics,
    )

    private data class PhaseMetrics(
        val durationMillis: Long,
        val memoryPeak: StressMemoryPeak,
        val memoryGrowth: StressMemoryPeak,
        val queries: StressQueryStats,
        val diskPeaks: Map<String, Long>,
        val fileCountPeaks: Map<String, Long>,
    )

    private data class DatabaseFootprint(
        val databaseBytes: Long,
        val walBytes: Long,
        val sharedMemoryBytes: Long,
    )

    private data class ProfileReport(
        val profile: StressProfile,
        val backup: PhaseMetrics,
        val verification: PhaseMetrics,
        val prepare: PhaseMetrics,
        val activation: PhaseMetrics,
        val sourceDatabase: DatabaseFootprint,
        val destinationDatabaseBefore: DatabaseFootprint,
        val destinationDatabase: DatabaseFootprint,
        val archiveBytes: Long,
        val backupTemporaryBytes: Long,
        val verificationStagingBytes: Long,
        val restoreStagingBytes: Long,
        val restoredAssetBytes: Long,
        val restorePreflight: List<RestorePreflightObservation>,
    )

    private data class RestorePreflightObservation(
        val requiredBytes: Long,
        val availableBytes: Long?,
        val productionAccepted: Boolean,
        val testOverrideUsed: Boolean,
    )

    /** Records the production decision and overrides only rejection for synthetic semantic profiling. */
    private class ObservedProductionRestorePreflight : RestoreStoragePreflight {
        private val observations = mutableListOf<RestorePreflightObservation>()

        override fun hasCapacity(estimate: RestoreStorageEstimate): Boolean {
            val productionAccepted = DefaultRestoreStoragePreflight.hasCapacity(estimate)
            observations += RestorePreflightObservation(
                requiredBytes = estimate.requiredAdditionalBytes,
                availableBytes = estimate.availableBytes,
                productionAccepted = productionAccepted,
                testOverrideUsed = !productionAccepted,
            )
            return true
        }

        fun observations(): List<RestorePreflightObservation> = observations.toList()
    }

    private fun formatPreflight(observations: List<RestorePreflightObservation>): String =
        observations.joinToString(";") { observation ->
            "required:${observation.requiredBytes},available:${observation.availableBytes}," +
                "accepted:${observation.productionAccepted},override:${observation.testOverrideUsed}"
        }

    private class SequentialStressRestoreIdSource(start: Long) : RestoreIdSource {
        private var next = start

        override fun newId(): String =
            "00000000-0000-4000-8000-${(next++).toString().padStart(12, '0')}"
    }

    private class BlockingBackupWorkspace(
        private val delegate: LibraryBackupWorkspace,
    ) : LibraryBackupWorkspace by delegate {
        private val archiveCreated = CountDownLatch(1)
        private val releaseArchive = CountDownLatch(1)

        override fun createTemporaryArchive(backupId: String): File {
            val archive = delegate.createTemporaryArchive(backupId)
            archiveCreated.countDown()
            check(releaseArchive.await(CANCELLATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                "Timed out waiting to release the cancellation archive"
            }
            return archive
        }

        fun awaitArchiveCreation(timeoutSeconds: Long): Boolean =
            archiveCreated.await(timeoutSeconds, TimeUnit.SECONDS)

        fun releaseArchiveCreation() {
            releaseArchive.countDown()
        }
    }

    private class StressLibraryRig private constructor(
        private val context: Context,
        val fileStore: LibraryFileStore,
        private val databaseName: String,
        val queries: StressQueryCounter,
    ) {
        lateinit var database: LibraryDatabase
            private set

        val dao: LibraryDao
            get() = database.libraryDao()

        init {
            open()
        }

        fun reopen() {
            database.close()
            open()
        }

        fun closeAndDelete() {
            if (::database.isInitialized) database.close()
            context.deleteDatabase(databaseName)
            fileStore.root.deleteRecursively()
        }

        fun databaseFootprint(): DatabaseFootprint {
            val databaseFile = context.getDatabasePath(databaseName)
            return DatabaseFootprint(
                databaseBytes = databaseFile.lengthIfFile(),
                walBytes = File(databaseFile.path + "-wal").lengthIfFile(),
                sharedMemoryBytes = File(databaseFile.path + "-shm").lengthIfFile(),
            )
        }

        fun databaseDiskTargets(prefix: String): List<StressDiskTarget> {
            val databaseFile = context.getDatabasePath(databaseName)
            return listOf(
                StressDiskTarget("${prefix}Db", databaseFile),
                StressDiskTarget("${prefix}Wal", File(databaseFile.path + "-wal")),
                StressDiskTarget("${prefix}Shm", File(databaseFile.path + "-shm")),
            )
        }

        fun sqlCount(sql: String): Long = database.openHelper.readableDatabase.query(sql).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }

        fun foreignKeyViolationCount(): Long =
            database.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { cursor ->
                var count = 0L
                while (cursor.moveToNext()) count += 1L
                count
            }

        suspend fun activeDocuments(): List<LibraryDocumentEntity> {
            val result = ArrayList<LibraryDocumentEntity>()
            var afterRowId = -1L
            while (true) {
                val page = dao.activeDocumentsPage(afterRowId, DATABASE_BATCH_SIZE)
                if (page.isEmpty()) return result
                result += page
                afterRowId = page.last().rowId
                if (page.size < DATABASE_BATCH_SIZE) return result
            }
        }

        private fun open() {
            database = Room.databaseBuilder(context, LibraryDatabase::class.java, databaseName)
                .setQueryCallback(queries, DIRECT_EXECUTOR)
                .build()
        }

        companion object {
            fun create(
                context: Context,
                libraryRoot: File,
                databaseName: String,
            ): StressLibraryRig {
                context.deleteDatabase(databaseName)
                libraryRoot.deleteRecursively()
                return StressLibraryRig(
                    context = context,
                    fileStore = LibraryFileStore(context, libraryRoot),
                    databaseName = databaseName,
                    queries = StressQueryCounter(),
                )
            }

            private val DIRECT_EXECUTOR = Executor { command -> command.run() }
        }
    }

    private companion object {
        fun pageCountForDocument(documentIndex: Int): Int =
            when {
                documentIndex % LONG_DOCUMENT_INTERVAL == 0 -> LONG_DOCUMENT_PAGE_COUNT
                documentIndex % SINGLE_PAGE_DOCUMENT_INTERVAL == 0 -> 1
                else -> MINIMUM_PAGES_PER_DOCUMENT + (documentIndex % MIXED_PAGE_COUNT_SPAN)
            }

        fun ocrLineCountForDocument(documentIndex: Int): Int =
            if (documentIndex % OCR_HEAVY_DOCUMENT_INTERVAL == 0) {
                OCR_HEAVY_LINE_COUNT
            } else {
                BASE_OCR_LINES_PER_VERIFIED_PAGE
            }

        val PROFILES = listOf(
            StressProfile("documents-0100", 100),
            StressProfile("documents-0500", 500),
            StressProfile("documents-1000", 1_000),
        )
        val PRODUCER = BackupProducer(
            applicationId = "org.synapseworks.pageharbor.phase2b.stress",
            versionName = "phase2b-stress",
            versionCode = 1,
        )
        val FORMAT_LIMITS = BackupFormatLimits()
        val OCR_SCRIPTS = listOf("LATIN", "CHINESE", "JAPANESE", "KOREAN", "DEVANAGARI", "AUTOMATIC")
        val SOURCE_CATEGORIES = listOf(
            DocumentSourceCategory.SCAN,
            DocumentSourceCategory.SELECTED_IMAGE,
            DocumentSourceCategory.INBOUND_SHARE,
            DocumentSourceCategory.RENDERED_PDF_PAGE,
            DocumentSourceCategory.LIBRARY,
        )
        val FILTERS = listOf(
            DocumentFilter.ORIGINAL,
            DocumentFilter.GRAYSCALE,
            DocumentFilter.HIGH_CONTRAST,
            DocumentFilter.BLACK_AND_WHITE,
            DocumentFilter.AUTO_ENHANCE,
        )
        val ROTATIONS = listOf(0, 90, 180, 270)

        const val LOG_TAG = "Phase2bStress"
        const val MINIMUM_PAGES_PER_DOCUMENT = 3
        const val MIXED_PAGE_COUNT_SPAN = 5
        const val SINGLE_PAGE_DOCUMENT_INTERVAL = 10
        const val LONG_DOCUMENT_INTERVAL = 100
        const val LONG_DOCUMENT_PAGE_COUNT = 64
        const val VERIFIED_OCR_PAGE = 0
        const val LEGACY_OCR_PAGE = 1
        const val FAILURE_PAGE = 2
        const val BASE_OCR_LINES_PER_VERIFIED_PAGE = 2
        const val OCR_HEAVY_DOCUMENT_INTERVAL = 50
        const val OCR_HEAVY_LINE_COUNT = 512
        const val DUPLICATE_OCR_ARTIFACTS = 2
        const val ROOT_FOLDER_COUNT = 10
        const val CHILDREN_PER_ROOT = 2
        const val FOLDER_COUNT = ROOT_FOLDER_COUNT * (1 + CHILDREN_PER_ROOT)
        const val UNFILED_DOCUMENT_INTERVAL = 10
        const val SOURCE_ASSET_INTERVAL = 10
        const val OCR_FAILURE_INTERVAL = 7
        const val LINE_ALIGNED_CORRECTION_INTERVAL = 5
        const val FREEFORM_CORRECTION_INTERVAL = 11
        const val PAGE_WIDTH = 32
        const val PAGE_HEIGHT = 32
        const val SAFE_OCR_ERROR = "IMAGE_UNREADABLE"
        const val EXISTING_DUPLICATE_ID = "existing-phase2b-duplicate"
        const val EXISTING_DUPLICATE_TITLE = "Existing preservation-equivalent duplicate"
        const val FIXTURE_EPOCH = 1_800_000_000_000L
        const val BACKUP_CREATED_AT = FIXTURE_EPOCH + 100_000L
        const val RESTORED_AT = FIXTURE_EPOCH + 200_000L
        const val PRIVATE_BACKUP_SPOOL_DIRECTORY = ".portable-backup-spool"
        const val DATABASE_BATCH_SIZE = 256
        const val BACKUP_OCR_PAGE_BATCH_SIZE = 32
        const val BACKUP_MAX_BIND_ARGUMENTS = 8L
        const val BACKUP_QUERY_BASE_ALLOWANCE = 64L
        const val BACKUP_QUERIES_PER_OCR_BATCH_ALLOWANCE = 8L
        const val BACKUP_QUERIES_PER_DOCUMENT_BATCH_ALLOWANCE = 4L
        const val PREPARE_QUERY_BASE_ALLOWANCE = 128L
        const val PREPARE_QUERIES_PER_DOCUMENT_ALLOWANCE = 8L
        const val RESTORE_QUERY_BASE_ALLOWANCE = 2_000L
        const val RESTORE_QUERIES_PER_DOCUMENT_ALLOWANCE = 320L
        const val RESTORE_MAX_BIND_ARGUMENTS = 512L
        const val QUERY_SCALING_ALLOWANCE = 2_000L
        const val RESOURCE_SAMPLE_INTERVAL_MILLIS = 250L
        const val MAX_MANAGED_HEAP_GROWTH_BYTES = 256L * 1024L * 1024L
        const val MAX_NATIVE_HEAP_GROWTH_BYTES = 256L * 1024L * 1024L
        const val MAX_PSS_GROWTH_BYTES = 512L * 1024L * 1024L
        const val MEMORY_SCALING_ALLOWANCE_BYTES = 96L * 1024L * 1024L
        const val RESTORE_CANCEL_AFTER_DOCUMENTS = 100
        const val CANCELLATION_TIMEOUT_SECONDS = 60L
        const val CANCELLATION_BACKUP_ID = "20000000-0000-4000-8000-000000000500"

        const val ACTIVE_DOCUMENT_COUNT_SQL =
            "SELECT COUNT(*) FROM library_documents WHERE library_state = 'ACTIVE'"
        const val PENDING_DOCUMENT_COUNT_SQL =
            "SELECT COUNT(*) FROM library_documents WHERE library_state = 'PENDING'"
        const val PAGE_COUNT_SQL = "SELECT COUNT(*) FROM library_pages"
        const val SOURCE_ASSET_COUNT_SQL = "SELECT COUNT(*) FROM library_source_assets"
        const val FOLDER_COUNT_SQL = "SELECT COUNT(*) FROM library_folders"
        const val OCR_ARTIFACT_COUNT_SQL = "SELECT COUNT(*) FROM library_page_ocr_artifacts"
        const val OCR_CORRECTION_COUNT_SQL = "SELECT COUNT(*) FROM library_page_ocr_corrections"
        const val OCR_LINE_COUNT_SQL = "SELECT COUNT(*) FROM library_page_ocr_lines"
    }
}

private fun File.lengthIfFile(): Long = if (isFile) length() else 0L
