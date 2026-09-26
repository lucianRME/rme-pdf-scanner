package org.synapseworks.pageharbor.library

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryOcrPersistenceInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: LibraryDatabase
    private lateinit var dao: LibraryDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.libraryDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun retainedPageReorderPreservesArtifactsAndCorrectionWhileDeleteCascadesRemovedPage() = runBlocking {
        val first = page("page-1", 0, "hash-1", "raw first")
        val second = page("page-2", 1, "hash-2", "raw second")
        dao.replaceDocument(document(pageCount = 2), listOf(first, second), "")
        val before = requireNotNull(dao.ocrPageSnapshot(DOCUMENT_ID, first.pageId))
        assertEquals(
            LibraryOcrCommitResult.APPLIED,
            dao.saveOcrCorrection(
                before,
                LibraryOcrCorrectionDraft(
                    correctedText = "edited first",
                    alignment = LibraryOcrCorrectionAlignment.FREEFORM,
                ),
                modifiedAt = 20,
            ),
        )
        val artifactRevision = requireNotNull(before.activeArtifactRevision)

        dao.replaceDocument(
            document(pageCount = 2, modifiedAt = 30),
            listOf(second.copy(position = 0), first.copy(position = 1)),
            "",
        )

        val reordered = dao.pages(DOCUMENT_ID)
        assertEquals(listOf("page-2", "page-1"), reordered.map(LibraryPageEntity::pageId))
        assertEquals("raw first", dao.ocrArtifact(first.pageId, artifactRevision)?.rawText)
        assertEquals("edited first", dao.ocrCorrection(first.pageId)?.correctedText)
        assertEquals("edited first", dao.effectiveOcrPage(DOCUMENT_ID, first.pageId)?.effectiveText)

        dao.replaceDocument(
            document(pageCount = 1, modifiedAt = 40),
            listOf(first.copy(position = 0)),
            "",
        )
        assertNull(dao.pageAnyState(second.pageId))
        assertTrue(dao.ocrArtifacts(second.pageId).isEmpty())
        assertTrue(dao.pageSearchPage("second*", Long.MAX_VALUE, "", 10).isEmpty())
        assertEquals("edited first", dao.ocrCorrection(first.pageId)?.correctedText)
    }

    @Test
    fun effectiveSearchTracksLineCorrectionRevertAndDocumentDeletion() = runBlocking {
        val stored = page("page-1", 0, "hash-1", null)
        dao.replaceDocument(document(), listOf(stored), "")
        val initial = requireNotNull(dao.ocrPageSnapshot(DOCUMENT_ID, stored.pageId))
        assertEquals(
            LibraryOcrCommitResult.APPLIED,
            dao.commitOcrArtifact(
                initial,
                draft(initial, "raw amber signal\nsecond line").copy(
                    lines = listOf(
                        line(0, "raw amber signal", 0.10, 0.25),
                        line(1, "second line", 0.35, 0.50),
                    ),
                ),
                modifiedAt = 20,
            ),
        )

        assertEquals(stored.pageId, dao.pageSearchPage("amber*", Long.MAX_VALUE, "", 10).single().pageId)
        val raw = requireNotNull(dao.effectiveOcrPage(DOCUMENT_ID, stored.pageId))
        assertEquals("raw amber signal\nsecond line", raw.rawText)
        assertEquals(raw.rawText, raw.effectiveText)
        assertEquals(listOf("raw amber signal", "second line"), raw.lines.map { it.text })

        val beforeCorrection = requireNotNull(dao.ocrPageSnapshot(DOCUMENT_ID, stored.pageId))
        assertEquals(
            LibraryOcrCommitResult.APPLIED,
            dao.saveOcrCorrection(
                beforeCorrection,
                LibraryOcrCorrectionDraft(
                    correctedText = "edited cobalt signal\nrevised line",
                    alignment = LibraryOcrCorrectionAlignment.LINE_ALIGNED,
                    correctedLines = listOf("edited cobalt signal", "revised line"),
                ),
                modifiedAt = 30,
            ),
        )

        assertTrue(dao.pageSearchPage("amber*", Long.MAX_VALUE, "", 10).isEmpty())
        assertEquals(stored.pageId, dao.pageSearchPage("cobalt*", Long.MAX_VALUE, "", 10).single().pageId)
        val corrected = requireNotNull(dao.effectiveOcrPage(DOCUMENT_ID, stored.pageId))
        assertEquals("raw amber signal\nsecond line", corrected.rawText)
        assertEquals("edited cobalt signal\nrevised line", corrected.effectiveText)
        assertEquals(listOf("edited cobalt signal", "revised line"), corrected.lines.map { it.text })

        val beforeRevert = requireNotNull(dao.ocrPageSnapshot(DOCUMENT_ID, stored.pageId))
        assertEquals(
            LibraryOcrCommitResult.APPLIED,
            dao.revertOcrCorrection(beforeRevert, modifiedAt = 40),
        )
        assertNull(dao.ocrCorrection(stored.pageId))
        assertTrue(dao.ocrCorrectionLines(stored.pageId).isEmpty())
        assertTrue(dao.pageSearchPage("cobalt*", Long.MAX_VALUE, "", 10).isEmpty())
        assertEquals(stored.pageId, dao.pageSearchPage("amber*", Long.MAX_VALUE, "", 10).single().pageId)

        assertNotNull(dao.deleteDocument(DOCUMENT_ID))
        assertTrue(dao.pageSearchPage("amber*", Long.MAX_VALUE, "", 10).isEmpty())
        assertTrue(dao.documentSearchPage("document*", "Document", Long.MAX_VALUE, "", 10).isEmpty())
    }

    @Test
    fun visualEditMakesCapturedRecognitionStaleBeforeAnyArtifactInsert() = runBlocking {
        val stored = page("page-1", 0, "hash-1", "old searchable text")
        dao.replaceDocument(document(), listOf(stored), "")
        val expected = requireNotNull(dao.ocrPageSnapshot(DOCUMENT_ID, stored.pageId))
        assertEquals(1, dao.ocrArtifacts(stored.pageId).size)

        dao.replaceDocument(
            document(modifiedAt = 30),
            listOf(stored.copy(rotationDegrees = 90)),
            "",
        )

        assertEquals(
            LibraryOcrCommitResult.STALE,
            dao.commitOcrArtifact(expected, draft(expected, "verified raw"), modifiedAt = 40),
        )
        assertEquals(1, dao.ocrArtifacts(stored.pageId).size)
        val invalidated = requireNotNull(dao.ocrPageSnapshot(DOCUMENT_ID, stored.pageId))
        assertNull(invalidated.activeArtifactRevision)
        assertEquals(expected.pageVisualRevision + 1, invalidated.pageVisualRevision)
        assertEquals(expected.ocrStateRevision + 1, invalidated.ocrStateRevision)
        assertNull(dao.effectiveOcrPage(DOCUMENT_ID, stored.pageId))
        assertTrue(dao.pageSearchPage("old*", Long.MAX_VALUE, "", 10).isEmpty())
    }

    @Test
    fun visualReplacementDropsPriorCorrectionBeforeNewArtifactBecomesEffective() = runBlocking {
        val stored = page("page-1", 0, "hash-1", null)
        dao.replaceDocument(document(), listOf(stored), "")
        val initial = requireNotNull(dao.ocrPageSnapshot(DOCUMENT_ID, stored.pageId))
        assertEquals(
            LibraryOcrCommitResult.APPLIED,
            dao.commitOcrArtifact(initial, draft(initial, "old raw amber"), modifiedAt = 20),
        )
        val beforeCorrection = requireNotNull(dao.ocrPageSnapshot(DOCUMENT_ID, stored.pageId))
        assertEquals(
            LibraryOcrCommitResult.APPLIED,
            dao.saveOcrCorrection(
                beforeCorrection,
                LibraryOcrCorrectionDraft(
                    correctedText = "old corrected cobalt",
                    alignment = LibraryOcrCorrectionAlignment.FREEFORM,
                ),
                modifiedAt = 30,
            ),
        )
        assertEquals(
            stored.pageId,
            dao.pageSearchPage("cobalt*", Long.MAX_VALUE, "", 10).single().pageId,
        )

        dao.replaceDocument(
            document(modifiedAt = 40),
            listOf(stored.copy(contentSha256 = "hash-2")),
            "",
        )

        assertNull(dao.ocrCorrection(stored.pageId))
        assertTrue(dao.ocrCorrectionLines(stored.pageId).isEmpty())
        assertNull(dao.effectiveOcrPage(DOCUMENT_ID, stored.pageId))
        assertTrue(dao.pageSearchPage("cobalt*", Long.MAX_VALUE, "", 10).isEmpty())

        val replacement = requireNotNull(dao.ocrPageSnapshot(DOCUMENT_ID, stored.pageId))
        assertEquals(
            LibraryOcrCommitResult.APPLIED,
            dao.commitOcrArtifact(
                replacement,
                draft(replacement, "replacement raw emerald"),
                modifiedAt = 50,
            ),
        )

        val effective = requireNotNull(dao.effectiveOcrPage(DOCUMENT_ID, stored.pageId))
        assertEquals("replacement raw emerald", effective.rawText)
        assertEquals("replacement raw emerald", effective.effectiveText)
        assertNull(effective.correctedText)
        assertNull(effective.correctionBaseArtifactRevision)
        assertTrue(dao.pageSearchPage("cobalt*", Long.MAX_VALUE, "", 10).isEmpty())
        assertEquals(
            stored.pageId,
            dao.pageSearchPage("emerald*", Long.MAX_VALUE, "", 10).single().pageId,
        )
    }

    @Test
    fun correctionRemainsPinnedToItsBaseWhenNewRawArtifactBecomesActive() = runBlocking {
        val stored = page("page-1", 0, "hash-1", null)
        dao.replaceDocument(document(), listOf(stored), "")
        val initial = requireNotNull(dao.ocrPageSnapshot(DOCUMENT_ID, stored.pageId))
        assertEquals(
            LibraryOcrCommitResult.APPLIED,
            dao.commitOcrArtifact(initial, draft(initial, "raw one"), modifiedAt = 20),
        )
        val firstCommit = requireNotNull(dao.ocrPageSnapshot(DOCUMENT_ID, stored.pageId))
        assertEquals(
            LibraryOcrCommitResult.APPLIED,
            dao.saveOcrCorrection(
                firstCommit,
                LibraryOcrCorrectionDraft("corrected one", LibraryOcrCorrectionAlignment.FREEFORM),
                modifiedAt = 30,
            ),
        )
        val correctedSnapshot = requireNotNull(dao.ocrPageSnapshot(DOCUMENT_ID, stored.pageId))
        assertEquals(
            LibraryOcrCommitResult.APPLIED,
            dao.commitOcrArtifact(correctedSnapshot, draft(correctedSnapshot, "raw two"), modifiedAt = 40),
        )

        val effective = requireNotNull(dao.effectiveOcrPage(DOCUMENT_ID, stored.pageId))
        assertEquals("corrected one", effective.effectiveText)
        assertEquals("raw one", effective.rawText)
        assertEquals(firstCommit.activeArtifactRevision, effective.correctionBaseArtifactRevision)
        assertTrue(effective.activeArtifactRevision != effective.artifactRevision)
        assertEquals(LibraryOcrCorrectionAlignment.FREEFORM, effective.alignment)
        assertTrue(effective.lines.isEmpty())
    }

    @Test
    fun batchTargetsPopulateInBoundedChunksAndRemainUnclaimableUntilFinished() = runBlocking {
        val pages = listOf(
            page("page-1", 0, "hash-1", null),
            page("page-2", 1, "hash-2", null),
            page("page-3", 2, "hash-3", null),
        )
        dao.replaceDocument(document(pageCount = pages.size), pages, "")
        val job = job("job-streamed")
        assertTrue(dao.beginOcrBatchTargetPopulation(job))
        val oversizedFailure = runCatching {
            dao.appendOcrBatchTargets(
                job.jobId,
                List(MAX_OCR_BATCH_TARGET_CHUNK_SIZE + 1) { index ->
                    target("oversized-$index", pageId = "oversized-page-$index", ordinal = index)
                },
                updatedAt = 19,
            )
        }.exceptionOrNull()
        assertTrue(oversizedFailure is IllegalArgumentException)
        assertTrue(dao.ocrBatchItems(job.jobId).isEmpty())
        assertTrue(
            dao.appendOcrBatchTargets(
                job.jobId,
                listOf(
                    target("item-1", pageId = "page-1", ordinal = 0),
                    target("item-2", pageId = "page-2", ordinal = 1),
                ),
                updatedAt = 20,
            ),
        )
        assertEquals(2, dao.ocrBatchJob(job.jobId)?.totalItemCount)
        assertEquals(false, dao.ocrBatchJob(job.jobId)?.targetPopulationComplete)
        assertNull(dao.claimOcrBatchItem(job.jobId, "item-1", 1, "early-claim", 21))

        assertEquals(
            false,
            dao.appendOcrBatchTargets(
                job.jobId,
                listOf(target("missing", pageId = "missing-page", ordinal = 2)),
                updatedAt = 22,
            ),
        )
        assertEquals(2, dao.ocrBatchItems(job.jobId).size)
        assertTrue(
            dao.appendOcrBatchTargets(
                job.jobId,
                listOf(target("item-3", pageId = "page-3", ordinal = 2)),
                updatedAt = 23,
            ),
        )
        assertTrue(dao.finishOcrBatchTargetPopulation(job.jobId, updatedAt = 24))
        val frozen = requireNotNull(dao.ocrBatchJob(job.jobId))
        assertTrue(frozen.targetPopulationComplete)
        assertEquals(3, frozen.totalItemCount)
        assertNotNull(dao.claimOcrBatchItem(job.jobId, "item-1", 1, "claim-1", 25))

        val empty = job("job-empty")
        assertTrue(dao.beginOcrBatchTargetPopulation(empty))
        assertTrue(dao.finishOcrBatchTargetPopulation(empty.jobId, updatedAt = 30))
        assertEquals(OcrBatchJobState.COMPLETED.name, dao.ocrBatchJob(empty.jobId)?.state)
    }

    @Test
    fun staleOrDeletedTargetSettlesAsSkippedAtClaimTime() = runBlocking {
        val stored = page("page-1", 0, "hash-1", null)
        dao.replaceDocument(document(), listOf(stored), "")

        val staleJob = job("job-stale-target")
        assertTrue(dao.freezeOcrBatchTargets(staleJob, listOf(target("stale-item"))))
        dao.replaceDocument(
            document(modifiedAt = 20),
            listOf(stored.copy(rotationDegrees = 90)),
            "",
        )
        assertNull(dao.claimOcrBatchItem(staleJob.jobId, "stale-item", 1, "stale-claim", 21))
        assertSkippedAndCompleted(staleJob.jobId)

        val deletedJob = job("job-deleted-target")
        assertTrue(dao.freezeOcrBatchTargets(deletedJob, listOf(target("deleted-item"))))
        assertNotNull(dao.deleteDocument(DOCUMENT_ID))
        assertNull(dao.claimOcrBatchItem(deletedJob.jobId, "deleted-item", 1, "deleted-claim", 31))
        assertSkippedAndCompleted(deletedJob.jobId)
    }

    @Test
    fun batchClaimsAreGenerationGuardedAndInterruptedWorkCanBeRetried() = runBlocking {
        val stored = page("page-1", 0, "hash-1", null)
        dao.replaceDocument(document(), listOf(stored), "")
        val job = job("job-1")
        assertTrue(dao.freezeOcrBatchTargets(job, listOf(target("item-1"))))
        assertNull(dao.claimOcrBatchItem("job-1", "item-1", 0, "wrong", 20))
        val cancelledClaim = requireNotNull(
            dao.claimOcrBatchItem("job-1", "item-1", 1, "claim-1", 21),
        )
        assertTrue(dao.cancelOcrBatchJob("job-1", 22))
        assertEquals(2L, dao.ocrBatchJob("job-1")?.runGeneration)
        assertEquals(
            OcrBatchCompletionResult.STALE_CLAIM,
            dao.completeOcrBatchItem(cancelledClaim, draft(cancelledClaim.expected, "late"), 23),
        )
        assertTrue(dao.ocrArtifacts(stored.pageId).isEmpty())

        assertTrue(dao.freezeOcrBatchTargets(job("job-2"), listOf(target("item-2"))))
        val interruptedClaim = requireNotNull(
            dao.claimOcrBatchItem("job-2", "item-2", 1, "claim-2", 30),
        )
        assertEquals(1, dao.markInterruptedOcrBatchJobs(31))
        val recovered = requireNotNull(dao.recoverInterruptedOcrBatchJob("job-2", 32))
        assertEquals(2L, recovered.runGeneration)
        assertEquals(OcrBatchItemState.RETRY_PENDING.name, dao.ocrBatchItems("job-2").single().state)
        assertEquals(
            OcrBatchCompletionResult.STALE_CLAIM,
            dao.completeOcrBatchItem(interruptedClaim, draft(interruptedClaim.expected, "late"), 33),
        )

        val retryClaim = requireNotNull(
            dao.claimOcrBatchItem("job-2", "item-2", 2, "claim-3", 34),
        )
        assertEquals(
            OcrBatchCompletionResult.APPLIED,
            dao.waitForOcrModel(retryClaim, "MODEL_UNAVAILABLE", 35),
        )
        assertEquals(OcrBatchItemState.WAITING_FOR_MODEL.name, dao.ocrBatchItems("job-2").single().state)
        assertTrue(dao.requestOcrBatchItemRetry("job-2", "item-2", 36))
        assertEquals(OcrBatchItemState.RETRY_PENDING.name, dao.ocrBatchItems("job-2").single().state)
    }

    @Test
    fun invalidOcrRestoreStateRollsBackPendingDocumentAtomically() = runBlocking {
        val operation = restoreOperation()
        val item = restoreItem(operation.operationId)
        dao.insertRestoreOperation(operation, listOf(item))
        val targetPage = page("restored-page", 0, "hash-restore", null)
        val failure = runCatching {
            dao.insertPendingRestoreDocument(
                document = document().copy(
                    libraryState = LibraryDocumentState.PENDING.name,
                    pendingOperationId = operation.operationId,
                ),
                pages = listOf(targetPage),
                sourceAssets = emptyList(),
                updatedItem = item.copy(itemState = "PREPARED"),
                updatedOperation = operation.copy(preparedDocumentCount = 1),
                ocrPageStates = listOf(
                    LibraryOcrRestorePageState(
                        backupPageId = "backup-page",
                        ocrStateRevision = -1,
                        activeArtifactRevision = null,
                        ocrError = null,
                        artifacts = emptyList(),
                        lines = emptyList(),
                        correction = null,
                        correctionLines = emptyList(),
                    ),
                ),
                pageIdRemap = mapOf("backup-page" to targetPage.pageId),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertNull(dao.documentAnyState(DOCUMENT_ID))
        assertNull(dao.pageAnyState(targetPage.pageId))
        assertEquals(0, dao.operation(operation.operationId)?.preparedDocumentCount)
        assertEquals("PLANNED", dao.operationItems(operation.operationId).single().itemState)
    }

    private fun document(pageCount: Int = 1, modifiedAt: Long = 10) = LibraryDocumentEntity(
        documentId = DOCUMENT_ID,
        title = "Document",
        createdAtMillis = 1,
        modifiedAtMillis = modifiedAt,
        pageCount = pageCount,
        folderId = null,
        thumbnailRelativePath = null,
        ocrStatus = LibraryOcrStatus.NOT_INDEXED.name,
    )

    private fun page(id: String, position: Int, hash: String, raw: String?) = LibraryPageEntity(
        pageId = id,
        documentId = DOCUMENT_ID,
        position = position,
        relativePath = "$id.jpg",
        contentType = "image/jpeg",
        sourceCategory = "TEST",
        width = 100,
        height = 200,
        sourceByteCount = 10,
        rotationDegrees = 0,
        filterName = "ORIGINAL",
        ocrText = raw,
        ocrError = null,
        contentSha256 = hash,
    )

    private fun draft(expected: LibraryOcrPageSnapshot, text: String) = LibraryOcrArtifactDraft(
        inputFingerprintVersion = 1,
        inputFingerprint = "fingerprint-${expected.pageVisualRevision}-$text",
        contentSha256 = requireNotNull(expected.contentSha256),
        rotationDegrees = expected.rotationDegrees,
        filterName = expected.filterName,
        uprightWidth = 100,
        uprightHeight = 200,
        coordinateSystemVersion = 1,
        transformVersion = 1,
        actualScript = "LATIN",
        recognizerId = "TEST_RECOGNIZER",
        pipelineVersion = "test-v1",
        clientVersion = null,
        delivery = "BUNDLED",
        recognizedAtMillis = 1,
        rawText = text,
    )

    private fun line(
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

    private fun job(id: String) = OcrBatchJobEntity(
        jobId = id,
        selectionPolicy = "DOCUMENT",
        requestedScriptSelection = "AUTOMATIC",
        localeRecommendationSnapshot = "LATIN",
        state = OcrBatchJobState.READY.name,
        totalItemCount = 0,
        completedItemCount = 0,
        failedItemCount = 0,
        skippedItemCount = 0,
        createdAtMillis = 1,
        updatedAtMillis = 1,
        runGeneration = 1,
        cancelRequested = false,
        targetPopulationComplete = false,
        terminalErrorCode = null,
    )

    private suspend fun assertSkippedAndCompleted(jobId: String) {
        val item = dao.ocrBatchItems(jobId).single()
        assertEquals(OcrBatchItemState.SKIPPED.name, item.state)
        assertEquals("STALE_INPUT", item.safeErrorCode)
        val settled = requireNotNull(dao.ocrBatchJob(jobId))
        assertEquals(OcrBatchJobState.COMPLETED.name, settled.state)
        assertEquals(1, settled.skippedItemCount)
        assertEquals(settled.totalItemCount, settled.skippedItemCount)
    }

    private fun target(
        id: String,
        pageId: String = "page-1",
        ordinal: Int = 0,
    ) = OcrBatchTarget(
        itemId = id,
        documentId = DOCUMENT_ID,
        pageId = pageId,
        ordinal = ordinal,
        requestedScriptSelection = "AUTOMATIC",
        resolvedScript = "LATIN",
    )

    private fun restoreOperation() = LibraryDataOperationEntity(
        operationId = "restore-operation",
        operationType = "RESTORE",
        phase = "PREPARING",
        sourceKind = "RME_BACKUP",
        sourceRootUri = null,
        sourceGrantFlags = 0,
        createdAtMillis = 1,
        updatedAtMillis = 1,
        discoveredItemCount = 1,
        plannedDocumentCount = 1,
        preparedDocumentCount = 0,
        importedDocumentCount = 0,
        skippedDuplicateCount = 0,
        failedItemCount = 0,
        totalSourceBytes = 10,
        processedSourceBytes = 0,
        cancelRequested = false,
        terminalErrorCode = null,
    )

    private fun restoreItem(operationId: String) = LibraryDataOperationItemEntity(
        itemId = "restore-item",
        operationId = operationId,
        ordinal = 0,
        itemState = "PLANNED",
        targetDocumentId = DOCUMENT_ID,
        targetRevisionId = null,
        proposedTitle = "Document",
        proposedFolderPath = null,
        sourceModifiedAtMillis = null,
        sourceByteCount = 10,
        sourcePageCount = 1,
        logicalHashVersion = 1,
        logicalSha256 = "logical-hash",
        duplicateKind = "NONE",
        duplicateDocumentId = null,
        duplicateDecision = "IMPORT",
        failureCode = null,
    )

    private companion object {
        const val DOCUMENT_ID = "document-1"
    }
}
