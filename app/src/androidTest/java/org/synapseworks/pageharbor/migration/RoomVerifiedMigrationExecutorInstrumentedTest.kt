package org.synapseworks.pageharbor.migration

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.os.SystemClock
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.synapseworks.pageharbor.library.LibraryDatabase
import org.synapseworks.pageharbor.library.LibraryFileTimeProvenance
import org.synapseworks.pageharbor.library.LibraryFileStore
import org.synapseworks.pageharbor.library.LibraryOcrCommitResult
import org.synapseworks.pageharbor.library.LibraryOcrCorrectionAlignment
import org.synapseworks.pageharbor.library.LibraryOcrCorrectionDraft
import org.synapseworks.pageharbor.library.LibraryOperationGate
import org.synapseworks.pageharbor.library.LibraryDocumentState
import org.synapseworks.pageharbor.library.LibraryRepository
import org.synapseworks.pageharbor.library.LibrarySourceProvenanceKind
import org.synapseworks.pageharbor.library.LibraryTextSourceKind
import org.synapseworks.pageharbor.library.duplicate.DuplicateKind
import org.synapseworks.pageharbor.backup.engine.FileLibraryBackupWorkspace
import org.synapseworks.pageharbor.backup.engine.LibraryBackupClock
import org.synapseworks.pageharbor.backup.engine.LibraryBackupCreationResult
import org.synapseworks.pageharbor.backup.engine.LibraryBackupEngine
import org.synapseworks.pageharbor.backup.engine.LibraryBackupIdSource
import org.synapseworks.pageharbor.backup.engine.LibraryBackupStoragePreflight
import org.synapseworks.pageharbor.backup.engine.RoomLibraryBackupSnapshotSource
import org.synapseworks.pageharbor.backup.format.BackupProducer
import org.synapseworks.pageharbor.backup.format.RME_BACKUP_FORMAT_VERSION_V3
import org.synapseworks.pageharbor.backup.restore.FileRestoreStagingWorkspace
import org.synapseworks.pageharbor.backup.restore.LibraryRestoreEngine
import org.synapseworks.pageharbor.backup.restore.RestoreArchiveSource
import org.synapseworks.pageharbor.backup.restore.RestorePreparationResult
import org.synapseworks.pageharbor.backup.restore.RestoreMergePolicy
import org.synapseworks.pageharbor.backup.restore.RestoreResult
import org.synapseworks.pageharbor.backup.restore.RestoreStoragePreflight
import org.synapseworks.pageharbor.backup.restore.RoomRestoreLibraryStore
import org.synapseworks.pageharbor.searchprep.AndroidSearchPreparationCoordinator
import org.synapseworks.pageharbor.searchprep.ExtractedPdfPage
import org.synapseworks.pageharbor.searchprep.PdfEmbeddedTextExtraction
import org.synapseworks.pageharbor.searchprep.PdfEmbeddedTextExtractor
import org.synapseworks.pageharbor.searchprep.SearchPreparationOutcome
import org.synapseworks.pageharbor.searchprep.SearchPreparationProgressListener
import org.synapseworks.pageharbor.ocr.OcrModelDelivery
import org.synapseworks.pageharbor.ocr.OcrPageLayout
import org.synapseworks.pageharbor.ocr.OcrPageRecognitionOutcome
import org.synapseworks.pageharbor.ocr.OcrRecognizerProvenance
import org.synapseworks.pageharbor.ocr.OcrScript
import org.synapseworks.pageharbor.ocr.OcrTextBounds
import org.synapseworks.pageharbor.ocr.OcrTextLine
import org.synapseworks.pageharbor.ocr.persistence.ScriptedOcrSessionRunner

@RunWith(AndroidJUnit4::class)
class RoomVerifiedMigrationExecutorInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun recoveryAtEveryCommitBoundaryIsAtomicAndIdempotent() = runBlocking {
        val checkpoints = listOf(
            MigrationExecutionCheckpoint.AFTER_JOURNAL_CREATED,
            MigrationExecutionCheckpoint.AFTER_DOCUMENT_STAGED,
            MigrationExecutionCheckpoint.BEFORE_ATOMIC_ACTIVATION,
            MigrationExecutionCheckpoint.AFTER_ATOMIC_ACTIVATION,
            MigrationExecutionCheckpoint.DURING_POST_ACTIVATION_CLEANUP,
        )

        checkpoints.forEach { checkpoint ->
            withRig("checkpoint-${checkpoint.name.lowercase()}") { rig ->
                val plan = rig.plan(documentCount = 2)
                val sourceHashes = rig.sources.associate { it.name to it.sha256() }
                val fault = OneShotFault(checkpoint)
                val first = runCatching {
                    rig.executor(fault).execute(
                        plan = plan,
                        decisions = plan.importAllDuplicateCandidates(),
                        cancellationSignal = NeverCancelMigration,
                        progressListener = MigrationBatchProgressListener { },
                    )
                }
                val operationId = fault.operationId
                    ?: first.getOrNull()?.operationId
                    ?: error("The operation ID was not recorded")
                val activeBeforeRecovery = rig.dao.activeDocumentsPage(-1L, 10)
                assertTrue(
                    checkpoint.name,
                    activeBeforeRecovery.size == 0 || activeBeforeRecovery.size == 2,
                )
                assertFalse(
                    checkpoint.name,
                    activeBeforeRecovery.size == 1,
                )

                assertEquals(checkpoint.name, 1, rig.executor().recoverInterruptedOperations())
                assertEquals(
                    "$checkpoint ${rig.diagnostic(operationId)}",
                    0,
                    rig.executor().recoverInterruptedOperations(),
                )

                val active = rig.dao.activeDocumentsPage(-1L, 10)
                assertEquals(checkpoint.name, 2, active.size)
                assertTrue(active.all { it.libraryState == LibraryDocumentState.ACTIVE.name })
                assertTrue(active.all { it.importedAtMillis != null })
                assertTrue(active.all { it.documentDateEpochDay == null })
                assertTrue(active.all { it.documentDateProvenance == null })
                assertTrue(active.all { it.originalCreatedAtMillis == null })
                assertTrue(active.all { it.sourceModifiedAtProvenance == "FILE_METADATA" })
                assertTrue(active.all { it.sourceProvenanceKind == "GENERIC_IMAGE" })
                assertEquals(2, active.sumOf { it.pageCount })
                assertEquals(2, rig.dao.foldersPage(0, 10).size)
                assertTrue(rig.dao.pendingDocuments(operationId).isEmpty())
                val report = rig.executor().completionReport(operationId)
                assertTrue(report.isTerminal)
                assertTrue(report.reconciles)
                assertEquals(2, report.importedDocumentCount)
                assertEquals(2, report.importedPageCount)
                assertEquals(2, report.reconstructedFolderCount)
                assertTrue(
                    rig.dao.operationItems(operationId)
                        .flatMap { rig.dao.operationSources(it.itemId) }
                        .all { source ->
                            source.sourceUri == null &&
                                source.relativeSourcePath == null &&
                                source.displayName == null &&
                                source.sourceModifiedAtMillis == null
                        },
                )
                assertEquals(sourceHashes, rig.sources.associate { it.name to it.sha256() })
                assertEquals(2, active.map { it.documentId }.distinct().size)
            }
        }
    }

    @Test
    fun cancellationKeepsPreparedDocumentsInvisibleAndRetryPublishesOnce() = runBlocking {
        withRig("cancel-retry") { rig ->
            val cancelled = AtomicBoolean(false)
            val plan = rig.plan(documentCount = 2)
            val first = rig.executor().execute(
                plan = plan,
                decisions = plan.importAllDuplicateCandidates(),
                cancellationSignal = MigrationCancellationSignal(cancelled::get),
                progressListener = MigrationBatchProgressListener { progress ->
                    if (progress.completedDocuments == 1) cancelled.set(true)
                },
            )

            assertTrue(first.wasCancelled)
            assertTrue(rig.dao.activeDocumentsPage(-1L, 10).isEmpty())
            assertEquals(1, rig.dao.pendingDocuments(requireNotNull(first.operationId)).size)

            val retried = rig.executor().resume(
                operationId = requireNotNull(first.operationId),
                decisions = plan.importAllDuplicateCandidates(),
                cancellationSignal = NeverCancelMigration,
                progressListener = MigrationBatchProgressListener { },
            )

            assertFalse(retried.wasCancelled)
            assertEquals(
                rig.diagnostic(requireNotNull(first.operationId)) + " reports=${retried.documents}",
                2,
                retried.publishedCount,
            )
            assertEquals(2, rig.dao.activeDocumentsPage(-1L, 10).size)
            assertTrue(rig.dao.pendingDocuments(requireNotNull(first.operationId)).isEmpty())
            assertEquals(0, rig.executor().recoverInterruptedOperations())
        }
    }

    @Test
    fun changedAndUnavailableSourcesFailClosedWithoutPartialVisibility() = runBlocking {
        withRig("source-revalidation") { rig ->
            val plan = rig.plan(documentCount = 2)
            val changedId = plan.documents.first().sources.single().source.discoveryIdentity
            val unavailableId = plan.documents.last().sources.single().source.discoveryIdentity
            val executor = rig.executor(
                revalidator = MigrationSourceRevalidator { source, _ ->
                    MigrationSourceRevalidation(
                        sourceDiscoveryId = source.source.discoveryIdentity,
                        status = if (source.source.discoveryIdentity == changedId) {
                            MigrationSourceRevalidationStatus.CHANGED_REQUIRES_REANALYSIS
                        } else {
                            MigrationSourceRevalidationStatus.UNAVAILABLE
                        },
                    )
                },
            )

            val report = executor.execute(
                plan,
                MigrationExecutionDecisions(),
                NeverCancelMigration,
                MigrationBatchProgressListener { },
            )

            assertTrue(rig.dao.activeDocumentsPage(-1L, 10).isEmpty())
            assertEquals(2, report.failedCount)
            val outcomes = requireNotNull(report.durableCompletion).sources
                .associate { it.accountingId.substringAfterLast('|') to it.outcome }
            assertEquals(MigrationTerminalSourceOutcome.SOURCE_CHANGED, outcomes[changedId])
            assertEquals(MigrationTerminalSourceOutcome.UNAVAILABLE, outcomes[unavailableId])
            assertTrue(requireNotNull(report.durableCompletion).reconciles)
        }
    }

    @Test
    fun exactDuplicateDefaultsToSkipAndExplicitImportPublishesOneMoreDocument() = runBlocking {
        withRig("exact-duplicate") { rig ->
            val plan = rig.plan(documentCount = 1)
            val first = rig.executor().execute(
                plan,
                plan.importAllDuplicateCandidates(),
                NeverCancelMigration,
                MigrationBatchProgressListener { },
            )
            assertEquals(1, first.publishedCount)

            val skipped = rig.executor().execute(
                plan,
                MigrationExecutionDecisions(),
                NeverCancelMigration,
                MigrationBatchProgressListener { },
            )
            assertEquals(0, skipped.publishedCount)
            assertEquals(1, skipped.duplicateCount)
            assertEquals(
                MigrationTerminalSourceOutcome.EXACT_DUPLICATE_SKIPPED,
                requireNotNull(skipped.durableCompletion).sources.single().outcome,
            )
            assertEquals(1, rig.dao.activeDocumentsPage(-1L, 10).size)

            val importedAnyway = rig.executor().execute(
                plan,
                plan.importAllDuplicateCandidates(),
                NeverCancelMigration,
                MigrationBatchProgressListener { },
            )
            assertEquals(1, importedAnyway.publishedCount)
            assertEquals(
                MigrationTerminalSourceOutcome.IMPORTED_DESPITE_DUPLICATE,
                requireNotNull(importedAnyway.durableCompletion).sources.single().outcome,
            )
            assertEquals(2, rig.dao.activeDocumentsPage(-1L, 10).size)
        }
    }

    @Test
    fun insufficientStorageCopyFailureAndReopenFailureRemainInvisible() = runBlocking {
        withRig("insufficient-storage") { rig ->
            val plan = rig.plan(documentCount = 1)
            val report = rig.executor(
                storageCapacity = MigrationStorageCapacity { 0L },
            ).execute(
                plan,
                plan.importAllDuplicateCandidates(),
                NeverCancelMigration,
                MigrationBatchProgressListener { },
            )
            assertEquals(MigrationPublicationFailure.INSUFFICIENT_STORAGE, report.documents.single().failure)
            assertTrue(rig.dao.activeDocumentsPage(-1L, 10).isEmpty())
        }
        withRig("copy-failure") { rig ->
            val plan = rig.plan(documentCount = 1)
            assertTrue(rig.sources.single().delete())
            val report = rig.executor().execute(
                plan,
                plan.importAllDuplicateCandidates(),
                NeverCancelMigration,
                MigrationBatchProgressListener { },
            )
            assertEquals(MigrationPublicationFailure.SOURCE_UNAVAILABLE, report.documents.single().failure)
            assertTrue(rig.dao.activeDocumentsPage(-1L, 10).isEmpty())
        }
        withRig("reopen-failure") { rig ->
            val plan = rig.plan(documentCount = 1)
            val report = rig.executor(
                stager = object : MigrationDocumentStager {
                    override suspend fun stage(
                        plan: MigrationDocumentPlan,
                        context: MigrationPublicationContext,
                    ): MigrationStagingResult {
                        val workspace = File(rig.root, "synthetic-invalid-stage").apply { mkdirs() }
                        return MigrationStagingResult.Ready(
                            StagedMigrationDocument(
                                workspace = workspace,
                                pages = listOf(
                                    StagedMigrationPage(
                                        file = File(workspace, "missing.jpg"),
                                        contentType = MigrationContentType.JPEG.mimeType,
                                        width = 10,
                                        height = 10,
                                        byteCount = 1L,
                                        sha256 = "0".repeat(64),
                                    ),
                                ),
                                originalPdf = null,
                                copiedSourceBytes = 1L,
                            ),
                        )
                    }

                    override fun cleanAbandonedWorkspaces() = Unit
                },
            ).execute(
                plan,
                plan.importAllDuplicateCandidates(),
                NeverCancelMigration,
                MigrationBatchProgressListener { },
            )
            assertEquals(MigrationPublicationFailure.VALIDATION_FAILED, report.documents.single().failure)
            assertTrue(rig.dao.activeDocumentsPage(-1L, 10).isEmpty())
        }
    }

    @Test
    fun migratedPdfAndImageRoundTripThroughCurrentBackupRestore() = runBlocking {
        withRig("backup-round-trip") { sourceRig ->
            val plan = sourceRig.mixedPdfAndImagePlan()
            val migration = sourceRig.executor().execute(
                plan,
                plan.importAllDuplicateCandidates(),
                NeverCancelMigration,
                MigrationBatchProgressListener { },
            )
            assertEquals(2, migration.publishedCount)
            val sourceDocuments = sourceRig.dao.activeDocumentsPage(-1L, 10)
            assertEquals(2, sourceDocuments.size)
            assertEquals(3, sourceDocuments.sumOf { it.pageCount })
            assertEquals(1, sourceDocuments.sumOf { sourceRig.dao.sourceAssets(it.documentId).size })
            val migratedPdf = sourceDocuments.single { it.title == "Migrated PDF" }
            val repository = LibraryRepository(
                context = context,
                dao = sourceRig.dao,
                fileStore = sourceRig.fileStore,
                operationGate = sourceRig.operationGate,
            )
            val preparation = AndroidSearchPreparationCoordinator(
                context = context,
                dao = sourceRig.dao,
                repository = repository,
                fileStore = sourceRig.fileStore,
                operationGate = sourceRig.operationGate,
                runner = ScriptedOcrSessionRunner { request ->
                    OcrPageRecognitionOutcome.Success(
                        descriptor = request.descriptor,
                        rawText = "Raster OCR page two",
                        layout = OcrPageLayout(
                            imageWidthPx = 600,
                            imageHeightPx = 800,
                            lines = listOf(
                                OcrTextLine(
                                    text = "Raster OCR page two",
                                    bounds = OcrTextBounds(32f, 80f, 430f, 130f),
                                ),
                            ),
                        ),
                        provenance = OcrRecognizerProvenance(
                            actualScript = OcrScript.LATIN,
                            recognizerId = "M3_TEST_OCR",
                            pipelineVersion = "M3_TEST_V1",
                            delivery = OcrModelDelivery.BUNDLED,
                        ),
                    )
                },
                defaultOcrSelection = {
                    org.synapseworks.pageharbor.ocr.OcrScriptSelection.Explicit(
                        org.synapseworks.pageharbor.ocr.OcrScript.LATIN,
                    )
                },
            ).prepare(
                documentIds = listOf(migratedPdf.documentId),
                cancellationSignal = NeverCancelMigration,
                progressListener = SearchPreparationProgressListener { },
            )
            val preparationResult = preparation.documents.single()
            val preparationDiagnostics = sourceRig.dao.pages(migratedPdf.documentId).joinToString {
                "position=${it.position},artifact=${it.activeOcrArtifactRevision},error=${it.ocrError}"
            }
            assertEquals(
                "report=$preparationResult pages=[$preparationDiagnostics]",
                SearchPreparationOutcome.SEARCH_READY,
                preparationResult.outcome,
            )
            assertEquals(1, preparation.documents.single().embeddedTextPages)
            assertEquals(1, preparation.documents.single().ocrPages)
            val importedHit = repository.searchHits("migration embedded", limit = 10).single()
            assertEquals(migratedPdf.documentId, importedHit.documentId)
            assertEquals(0, importedHit.currentPagePosition)
            val firstArtifact = requireNotNull(
                sourceRig.dao.activeTextArtifact(
                    sourceRig.dao.pages(migratedPdf.documentId).first().pageId,
                ),
            )
            assertEquals(LibraryTextSourceKind.PDF_EMBEDDED_TEXT.name, firstArtifact.textSourceKind)
            val correctedPage = sourceRig.dao.pages(migratedPdf.documentId).first()
            assertEquals(
                LibraryOcrCommitResult.APPLIED,
                repository.saveOcrCorrection(
                    requireNotNull(
                        repository.captureOcrPageSnapshot(
                            migratedPdf.documentId,
                            correctedPage.pageId,
                        ),
                    ),
                    LibraryOcrCorrectionDraft(
                        correctedText = "Corrected migration compass",
                        alignment = LibraryOcrCorrectionAlignment.FREEFORM,
                    ),
                ),
            )
            assertTrue(repository.searchHits("migration embedded", limit = 10).isEmpty())
            val correctedHit = repository.searchHits("corrected migration compass", limit = 10).single()
            assertEquals(migratedPdf.documentId, correctedHit.documentId)
            assertEquals(0, correctedHit.currentPagePosition)

            val backupResult = LibraryBackupEngine(
                snapshotSource = RoomLibraryBackupSnapshotSource(sourceRig.dao, sourceRig.fileStore),
                workspace = FileLibraryBackupWorkspace(File(sourceRig.root, "backup-workspace")),
                operationGate = sourceRig.operationGate,
                storagePreflight = LibraryBackupStoragePreflight { true },
                clock = LibraryBackupClock { 1_760_000_000_000L },
                idSource = LibraryBackupIdSource { UUID.randomUUID().toString() },
            ).create(
                BackupProducer(
                    applicationId = context.packageName,
                    versionName = "1.6.0",
                    versionCode = 18,
                ),
            ) as LibraryBackupCreationResult.Verified
            val backup = backupResult.artifact
            try {
                assertEquals(RME_BACKUP_FORMAT_VERSION_V3, backup.manifest.formatVersion)
                assertEquals(2, backup.manifest.summary.documentCount)
                assertEquals(3, backup.manifest.summary.pageCount)
                assertEquals(2, backup.manifest.summary.folderCount)
                assertEquals(1, backup.manifest.summary.sourceAssetCount)

                val restoreDatabase = Room.inMemoryDatabaseBuilder(
                    context,
                    LibraryDatabase::class.java,
                ).build()
                val restoreStore = LibraryFileStore(context, File(sourceRig.root, "restored-library"))
                try {
                    val restoreDao = restoreDatabase.libraryDao()
                    val restoreEngine = LibraryRestoreEngine(
                        store = RoomRestoreLibraryStore(context, restoreDao, restoreStore),
                        stagingWorkspace = FileRestoreStagingWorkspace(
                            File(sourceRig.root, "restore-staging"),
                        ),
                        operationGate = LibraryOperationGate(),
                        storagePreflight = RestoreStoragePreflight { true },
                    )
                    val preparation = restoreEngine.prepare(
                        RestoreArchiveSource(backup.sizeBytes) { FileInputStream(backup.file) },
                    ) as RestorePreparationResult.Ready
                    val restored = restoreEngine.restore(
                        preparation.prepared,
                        RestoreMergePolicy.MERGE_IMPORT_ANYWAY,
                    ) as RestoreResult.Completed

                    assertEquals(2, restored.importedDocumentCount)
                    assertEquals(0, restored.skippedExactDocumentCount)
                    val restoredDocuments = restoreDao.activeDocumentsPage(-1L, 10)
                    assertEquals(2, restoredDocuments.size)
                    assertEquals(
                        sourceDocuments.map { it.title }.sorted(),
                        restoredDocuments.map { it.title }.sorted(),
                    )
                    assertEquals(3, restoredDocuments.sumOf { it.pageCount })
                    assertEquals(2, restoreDao.foldersPage(0, 10).size)
                    assertEquals(
                        1,
                        restoredDocuments.sumOf { restoreDao.sourceAssets(it.documentId).size },
                    )
                    val restoredPdf = restoredDocuments.single { it.title == "Migrated PDF" }
                    assertEquals(null, restoredPdf.documentDateEpochDay)
                    assertEquals(null, restoredPdf.originalCreatedAtMillis)
                    assertEquals(migratedPdf.sourceModifiedAtMillis, restoredPdf.sourceModifiedAtMillis)
                    assertEquals(migratedPdf.importedAtMillis, restoredPdf.importedAtMillis)
                    assertEquals(
                        LibraryFileTimeProvenance.FILE_METADATA.name,
                        restoredPdf.sourceModifiedAtProvenance,
                    )
                    assertEquals(
                        LibrarySourceProvenanceKind.GENERIC_PDF.name,
                        restoredPdf.sourceProvenanceKind,
                    )
                    val restoredRepository = LibraryRepository(
                        context = context,
                        dao = restoreDao,
                        fileStore = restoreStore,
                        operationGate = LibraryOperationGate(),
                    )
                    val restoredHit = restoredRepository.searchHits(
                        "corrected migration compass",
                        limit = 10,
                    ).single()
                    assertEquals(restoredPdf.documentId, restoredHit.documentId)
                    assertEquals(0, restoredHit.currentPagePosition)
                    val restoredFirstPage = restoreDao.pages(restoredPdf.documentId).first()
                    val restoredEffective = requireNotNull(
                        restoreDao.effectiveOcrPage(
                            restoredPdf.documentId,
                            restoredFirstPage.pageId,
                        ),
                    )
                    assertEquals("Migration embedded page one", restoredEffective.rawText.trim())
                    assertEquals("Corrected migration compass", restoredEffective.effectiveText)
                    assertEquals(
                        LibraryTextSourceKind.PDF_EMBEDDED_TEXT,
                        restoredEffective.textSourceKind,
                    )
                    restoredDocuments.forEach { document ->
                        val pages = restoreDao.pages(document.documentId)
                        assertEquals(pages.indices.toList(), pages.map { it.position })
                        pages.forEach { page ->
                            val file = requireNotNull(restoreStore.resolve(page.relativePath))
                            assertTrue(file.isFile && FileInputStream(file).use { it.read() >= 0 })
                        }
                        restoreDao.sourceAssets(document.documentId).forEach { asset ->
                            val file = requireNotNull(restoreStore.resolve(asset.relativePath))
                            assertTrue(file.isFile && FileInputStream(file).use { it.read() >= 0 })
                        }
                    }
                } finally {
                    restoreDatabase.close()
                }
            } finally {
                backup.close()
            }
        }
    }

    @Test
    fun searchPreparationRecoversProcessDeathAndRetriesCancellationIdempotently() = runBlocking {
        withRig("search-preparation-recovery") { rig ->
            val plan = rig.mixedPdfAndImagePlan()
            val migration = rig.executor().execute(
                plan,
                plan.importAllDuplicateCandidates(),
                NeverCancelMigration,
                MigrationBatchProgressListener { },
            )
            assertEquals(2, migration.publishedCount)
            val document = rig.dao.activeDocumentsPage(-1L, 10)
                .single { it.title == "Migrated PDF" }
            val repository = LibraryRepository(
                context = context,
                dao = rig.dao,
                fileStore = rig.fileStore,
                operationGate = rig.operationGate,
            )
            val crash = AndroidSearchPreparationCoordinator(
                context = context,
                dao = rig.dao,
                repository = repository,
                fileStore = rig.fileStore,
                extractor = PdfEmbeddedTextExtractor { _, _, _, _ ->
                    throw SimulatedProcessDeath()
                },
                operationGate = rig.operationGate,
            )
            val interrupted = runCatching {
                crash.prepare(
                    listOf(document.documentId),
                    NeverCancelMigration,
                    SearchPreparationProgressListener { },
                )
            }.exceptionOrNull()
            assertTrue(interrupted is SimulatedProcessDeath)

            val deterministicExtractor = PdfEmbeddedTextExtractor {
                    _, expectedPageCount, isCancelled, onPage ->
                repeat(expectedPageCount) { pageIndex ->
                    if (isCancelled()) throw kotlinx.coroutines.CancellationException()
                    onPage(
                        ExtractedPdfPage(
                            pageIndex,
                            "Recovered local text for page ${pageIndex + 1}",
                        ),
                    )
                }
                PdfEmbeddedTextExtraction.Completed(expectedPageCount)
            }
            val recovered = AndroidSearchPreparationCoordinator(
                context = context,
                dao = rig.dao,
                repository = repository,
                fileStore = rig.fileStore,
                extractor = deterministicExtractor,
                operationGate = rig.operationGate,
            )
            assertEquals(1, recovered.recoverInterruptedOperations())
            assertEquals(0, recovered.recoverInterruptedOperations())
            assertTrue(
                rig.dao.pages(document.documentId).all { page ->
                    rig.dao.activeTextArtifact(page.pageId)?.textSourceKind ==
                        LibraryTextSourceKind.PDF_EMBEDDED_TEXT.name
                },
            )

            val cancelled = recovered.prepare(
                listOf(document.documentId),
                MigrationCancellationSignal { true },
                SearchPreparationProgressListener { },
            )
            assertTrue(cancelled.cancelled)
            assertEquals(SearchPreparationOutcome.CANCELLED, cancelled.documents.single().outcome)
            val retried = recovered.retry(
                cancelled.operationId,
                NeverCancelMigration,
                SearchPreparationProgressListener { },
            )
            assertEquals(SearchPreparationOutcome.SEARCH_READY, retried.documents.single().outcome)
            assertEquals(2, retried.documents.single().embeddedTextPages)
        }
    }

    @Test
    fun migrationExecutionScalesThroughFiveHundredDocumentsWithoutStaleStaging() = runBlocking {
        listOf(10, 100, 500).forEach { documentCount ->
            withRig("stress-$documentCount") { rig ->
                val plan = rig.plan(documentCount)
                val runtime = Runtime.getRuntime()
                runtime.gc()
                val memoryBefore = runtime.usedBytes()
                var sampledPeak = memoryBefore
                val startedAt = SystemClock.elapsedRealtime()

                val report = rig.executor().execute(
                    plan,
                    plan.importAllDuplicateCandidates(),
                    NeverCancelMigration,
                    MigrationBatchProgressListener {
                        sampledPeak = maxOf(sampledPeak, runtime.usedBytes())
                    },
                )

                val elapsedMillis = SystemClock.elapsedRealtime() - startedAt
                val sourceBytes = rig.sources.sumOf(File::length)
                val finalBytes = rig.fileStore.root.directoryBytes()
                val operationId = requireNotNull(report.operationId)
                val items = rig.dao.operationItems(operationId)
                val sourceRows = items.sumOf { rig.dao.operationSources(it.itemId).size }
                assertEquals(documentCount, report.publishedCount)
                assertEquals(documentCount, rig.dao.activeDocumentsPage(-1L, documentCount + 1).size)
                assertEquals(documentCount, items.size)
                assertEquals(documentCount, sourceRows)
                assertTrue(requireNotNull(report.durableCompletion).reconciles)
                assertTrue(File(rig.root, "staging").listFiles().isNullOrEmpty())
                assertEquals(0, rig.executor().recoverInterruptedOperations())
                println(
                    "M2_STRESS documents=$documentCount sources=$sourceRows " +
                        "elapsed_ms=$elapsedMillis source_bytes=$sourceBytes " +
                        "final_bytes=$finalBytes retained_heap_delta_bytes=" +
                        "${(runtime.usedBytes() - memoryBefore).coerceAtLeast(0L)} " +
                        "sampled_heap_growth_bytes=${(sampledPeak - memoryBefore).coerceAtLeast(0L)} " +
                        "staging_bytes=0",
                )
            }
        }
    }

    private suspend fun withRig(name: String, block: suspend (Rig) -> Unit) {
        val runId = UUID.randomUUID().toString()
        val root = File(context.cacheDir, "verified-migration-test-$name-$runId")
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        val rig = Rig(
            root = root,
            database = database,
            fileStore = LibraryFileStore(context, File(root, "library")),
            operationGate = LibraryOperationGate(),
        )
        try {
            assertTrue(root.mkdirs())
            block(rig)
        } finally {
            database.close()
            root.deleteRecursively()
        }
    }

    private inner class Rig(
        val root: File,
        val database: LibraryDatabase,
        val fileStore: LibraryFileStore,
        val operationGate: LibraryOperationGate,
    ) {
        val dao = database.libraryDao()
        val sources = mutableListOf<File>()
        private val sourceAccess = MigrationSourceAccess { source ->
            FileInputStream(File(source.id))
        }
        private val unchangedRevalidator = MigrationSourceRevalidator { source, _ ->
            MigrationSourceRevalidation(
                sourceDiscoveryId = source.source.discoveryIdentity,
                status = MigrationSourceRevalidationStatus.UNCHANGED,
                currentSizeBytes = source.source.sizeBytes,
                currentModifiedAtMillis = source.source.modifiedAtMillis,
                currentPageCount = source.pageCount,
            )
        }

        fun plan(documentCount: Int): MigrationImportPlan {
            val documents = List(documentCount) { index ->
                val sourceFile = File(root, "source-$index.jpg")
                writeJpeg(sourceFile, Color.rgb(40 + index * 100, 80, 120 + index * 80))
                sources += sourceFile
                val source = MigrationSource(
                    id = sourceFile.absolutePath,
                    displayName = "source-$index.jpg",
                    relativeFolderPath = listOf("Archive", "2026"),
                    declaredContentType = MigrationContentType.JPEG.mimeType,
                    sizeBytes = sourceFile.length(),
                    modifiedAtMillis = sourceFile.lastModified(),
                    discoveryIdentity = "source-$index",
                )
                val inspected = InspectedMigrationSource(
                    source = source,
                    contentType = MigrationContentType.JPEG,
                    pageCount = 1,
                )
                MigrationDocumentPlan(
                    id = "document-$index",
                    suggestedTitle = "Document $index",
                    relativeFolderPath = source.relativeFolderPath,
                    sources = listOf(inspected),
                    grouping = MigrationDocumentGrouping.SINGLE_IMAGE,
                    pages = listOf(
                        MigrationPlannedPage(
                            id = "document-$index-page-0",
                            sourceDiscoveryId = source.discoveryIdentity,
                            sourcePageIndex = 0,
                            plannedIndex = 0,
                            factualPageNumber = null,
                            orderEvidence = MigrationEvidenceSource.USER_SELECTED_SOURCE,
                        ),
                    ),
                )
            }
            return MigrationPreview(
                documents = documents,
                rejectedSources = emptyList(),
                storageEstimate = MigrationStorageEstimate(
                    knownSourceBytes = sources.sumOf(File::length),
                    unknownSizeSourceCount = 0,
                ),
                inspectedSourceCount = documentCount,
                requestedSourceCount = documentCount,
                wasCancelled = false,
                duplicateDocuments = documents.map { document ->
                    MigrationDuplicatePreview(
                        documentId = document.id,
                        duplicateKind = DuplicateKind.DIFFERENT,
                        analyzedPageCount = 1,
                    )
                },
                duplicateAnalysisComplete = true,
                requestedSources = documents.flatMap { it.sources }
                    .map(InspectedMigrationSource::source),
            )
        }

        fun mixedPdfAndImagePlan(): MigrationImportPlan {
            val pdf = File(root, "source.pdf")
            writePdf(pdf, 2)
            val image = File(root, "source.jpg")
            writeJpeg(image, Color.rgb(20, 180, 70))
            sources += listOf(pdf, image)
            val pdfSource = migrationSource(pdf, "pdf-source", MigrationContentType.PDF)
            val imageSource = migrationSource(image, "image-source", MigrationContentType.JPEG)
            val documents = listOf(
                MigrationDocumentPlan(
                    id = "pdf-document",
                    suggestedTitle = "Migrated PDF",
                    relativeFolderPath = listOf("Archive", "2026"),
                    sources = listOf(
                        InspectedMigrationSource(pdfSource, MigrationContentType.PDF, pageCount = 2),
                    ),
                    grouping = MigrationDocumentGrouping.SINGLE_PDF,
                    pages = List(2) { index ->
                        MigrationPlannedPage(
                            id = "pdf-page-$index",
                            sourceDiscoveryId = pdfSource.discoveryIdentity,
                            sourcePageIndex = index,
                            plannedIndex = index,
                            factualPageNumber = index + 1,
                            orderEvidence = MigrationEvidenceSource.PDF_PAGE_TREE,
                        )
                    },
                ),
                MigrationDocumentPlan(
                    id = "image-document",
                    suggestedTitle = "Migrated Image",
                    relativeFolderPath = listOf("Archive", "2026"),
                    sources = listOf(
                        InspectedMigrationSource(imageSource, MigrationContentType.JPEG, pageCount = 1),
                    ),
                    grouping = MigrationDocumentGrouping.SINGLE_IMAGE,
                    pages = listOf(
                        MigrationPlannedPage(
                            id = "image-page-0",
                            sourceDiscoveryId = imageSource.discoveryIdentity,
                            sourcePageIndex = 0,
                            plannedIndex = 0,
                            factualPageNumber = null,
                            orderEvidence = MigrationEvidenceSource.USER_SELECTED_SOURCE,
                        ),
                    ),
                ),
            )
            return MigrationPreview(
                documents = documents,
                rejectedSources = emptyList(),
                storageEstimate = MigrationStorageEstimate(
                    knownSourceBytes = pdf.length() + image.length(),
                    unknownSizeSourceCount = 0,
                ),
                inspectedSourceCount = 2,
                requestedSourceCount = 2,
                wasCancelled = false,
                duplicateDocuments = documents.map { document ->
                    MigrationDuplicatePreview(
                        documentId = document.id,
                        duplicateKind = DuplicateKind.DIFFERENT,
                        analyzedPageCount = document.pages.size,
                    )
                },
                duplicateAnalysisComplete = true,
                requestedSources = listOf(pdfSource, imageSource),
            )
        }

        private fun migrationSource(
            file: File,
            identity: String,
            type: MigrationContentType,
        ) = MigrationSource(
            id = file.absolutePath,
            displayName = file.name,
            relativeFolderPath = listOf("Archive", "2026"),
            declaredContentType = type.mimeType,
            sizeBytes = file.length(),
            modifiedAtMillis = file.lastModified(),
            discoveryIdentity = identity,
        )

        fun executor(
            faultInjector: MigrationExecutionFaultInjector = NoMigrationExecutionFaults,
            revalidator: MigrationSourceRevalidator = unchangedRevalidator,
            storageCapacity: MigrationStorageCapacity = MigrationStorageCapacity { Long.MAX_VALUE },
            stager: MigrationDocumentStager = AndroidMigrationDocumentStager(
                workspaceRoot = File(root, "staging"),
                sourceAccess = sourceAccess,
            ),
        ) = RoomVerifiedMigrationExecutor(
            context = context,
            sourceAccess = sourceAccess,
            sourceRevalidator = revalidator,
            journalMetadata = MigrationJournalMetadata(
                sourceKind = MigrationBatchSourceKind.FILE_URIS.name,
                sourceRootUri = null,
                sourceGrantFlags = 0,
            ),
            dao = dao,
            fileStore = fileStore,
            storageCapacity = storageCapacity,
            operationGate = operationGate,
            idSource = { UUID.randomUUID().toString() },
            faultInjector = faultInjector,
            stager = stager,
        )

        suspend fun diagnostic(operationId: String): String =
            "phase=${dao.operation(operationId)?.phase} " +
                "items=${dao.operationItems(operationId).map { it.itemState to it.failureCode }} " +
                "pending=${dao.pendingDocuments(operationId).size} " +
                "active=${dao.activeDocumentsPage(-1L, 20).size}"
    }

    private class OneShotFault(
        private val target: MigrationExecutionCheckpoint,
    ) : MigrationExecutionFaultInjector {
        var operationId: String? = null
            private set
        private var fired = false

        override suspend fun checkpoint(
            checkpoint: MigrationExecutionCheckpoint,
            operationId: String,
            itemId: String?,
        ) {
            this.operationId = operationId
            if (!fired && checkpoint == target) {
                fired = true
                if (target == MigrationExecutionCheckpoint.DURING_POST_ACTIVATION_CLEANUP) {
                    throw IllegalStateException("synthetic cleanup interruption")
                }
                throw SimulatedProcessDeath()
            }
        }
    }

    private class SimulatedProcessDeath : Error("synthetic process death")

    private fun writeJpeg(file: File, color: Int) {
        file.parentFile?.mkdirs()
        val bitmap = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(color)
            FileOutputStream(file).use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
            }
        } finally {
            bitmap.recycle()
        }
        assertTrue(file.isFile && file.length() > 0L)
    }

    private fun writePdf(file: File, pageCount: Int) {
        file.parentFile?.mkdirs()
        val document = PdfDocument()
        try {
            repeat(pageCount) { index ->
                val page = document.startPage(
                    PdfDocument.PageInfo.Builder(600, 800, index + 1).create(),
                )
                if (index == 0) {
                    page.canvas.drawText(
                        "Migration embedded page one",
                        32f,
                        72f,
                        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                            color = Color.BLACK
                            textSize = 32f
                        },
                    )
                } else {
                    val rasterText = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888)
                    try {
                        val rasterCanvas = android.graphics.Canvas(rasterText)
                        rasterCanvas.drawColor(Color.WHITE)
                        rasterCanvas.drawText(
                            "Raster OCR page two",
                            32f,
                            120f,
                            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                color = Color.BLACK
                                textSize = 42f
                            },
                        )
                        page.canvas.drawBitmap(rasterText, 0f, 0f, null)
                    } finally {
                        rasterText.recycle()
                    }
                }
                document.finishPage(page)
            }
            FileOutputStream(file).use(document::writeTo)
        } finally {
            document.close()
        }
        assertTrue(file.isFile && file.length() > 0L)
    }

    private fun File.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(readBytes())
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun File.directoryBytes(): Long = when {
        isFile -> length()
        isDirectory -> listFiles().orEmpty().fold(0L) { total, child ->
            val childBytes = child.directoryBytes()
            if (Long.MAX_VALUE - total < childBytes) Long.MAX_VALUE else total + childBytes
        }
        else -> 0L
    }

    private fun Runtime.usedBytes(): Long = totalMemory() - freeMemory()

    private fun MigrationImportPlan.importAllDuplicateCandidates() = MigrationExecutionDecisions(
        duplicateDecisions = documents.associate { document ->
            document.id to MigrationDuplicateExecutionDecision.IMPORT_ANYWAY
        },
    )
}
