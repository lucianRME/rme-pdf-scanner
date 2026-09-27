package org.synapseworks.pageharbor.library

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
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
    private val queryCount = AtomicInteger()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryCallback({ _, _ -> queryCount.incrementAndGet() }, { command -> command.run() })
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

        val review = requireNotNull(dao.ocrReviewPage(DOCUMENT_ID, stored.pageId))
        assertEquals("raw two", review.rawText)
        assertEquals("corrected one", review.effectiveText)
        assertEquals("LATIN", review.actualScript)
        assertTrue(review.hasCorrection)
        assertEquals(
            stored.pageId,
            dao.pageSearchPage("corrected*", Long.MAX_VALUE, "", 10).single().pageId,
        )
        assertTrue(dao.pageSearchPage("two*", Long.MAX_VALUE, "", 10).isEmpty())

        val beforeRevert = requireNotNull(dao.ocrPageSnapshot(DOCUMENT_ID, stored.pageId))
        assertEquals(
            LibraryOcrCommitResult.APPLIED,
            dao.revertOcrCorrection(beforeRevert, modifiedAt = 50),
        )
        assertEquals("raw two", dao.ocrReviewPage(DOCUMENT_ID, stored.pageId)?.effectiveText)
        assertTrue(dao.pageSearchPage("corrected*", Long.MAX_VALUE, "", 10).isEmpty())
        assertEquals(
            stored.pageId,
            dao.pageSearchPage("two*", Long.MAX_VALUE, "", 10).single().pageId,
        )
    }

    @Test
    fun correctionSurvivesDatabaseCloseAndReopen() = runBlocking {
        val name = "phase4-ocr-review-${System.nanoTime()}.db"
        var persistent = Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()
        try {
            val firstDao = persistent.libraryDao()
            val stored = page("page-reopen", 0, "hash-reopen", null)
            firstDao.replaceDocument(document(), listOf(stored), "")
            val initial = requireNotNull(firstDao.ocrPageSnapshot(DOCUMENT_ID, stored.pageId))
            assertEquals(
                LibraryOcrCommitResult.APPLIED,
                firstDao.commitOcrArtifact(initial, draft(initial, "raw before restart"), 20),
            )
            val beforeCorrection = requireNotNull(
                firstDao.ocrPageSnapshot(DOCUMENT_ID, stored.pageId),
            )
            assertEquals(
                LibraryOcrCommitResult.APPLIED,
                firstDao.saveOcrCorrection(
                    beforeCorrection,
                    LibraryOcrCorrectionDraft(
                        "edited after restart",
                        LibraryOcrCorrectionAlignment.FREEFORM,
                    ),
                    30,
                ),
            )
            persistent.close()

            persistent = Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()
            val reopened = requireNotNull(
                persistent.libraryDao().ocrReviewPage(DOCUMENT_ID, stored.pageId),
            )
            assertEquals("raw before restart", reopened.rawText)
            assertEquals("edited after restart", reopened.effectiveText)
            assertTrue(reopened.hasCorrection)
        } finally {
            if (persistent.isOpen) persistent.close()
            context.deleteDatabase(name)
        }
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
    fun batchSkipAndRetryRefreshAffectOnlyEligibleItems() = runBlocking {
        val stored = page("page-1", 0, "hash-1", null)
        dao.replaceDocument(document(), listOf(stored), "")
        assertTrue(dao.freezeOcrBatchTargets(job("job-skip"), listOf(target("item-skip"))))
        val claim = requireNotNull(dao.claimOcrBatchItem("job-skip", "item-skip", 1, "claim", 10))
        assertEquals(
            OcrBatchCompletionResult.APPLIED,
            dao.skipOcrBatchItem(claim, "CURRENT_OCR", 11),
        )
        assertEquals(OcrBatchJobState.COMPLETED.name, dao.ocrBatchJob("job-skip")?.state)
        assertEquals(0, dao.ocrBatchProgress("job-skip").retryableItems)
        assertTrue(dao.retryableOcrBatchItems("job-skip", 100).isEmpty())

        assertTrue(dao.freezeOcrBatchTargets(job("job-stale"), listOf(target("item-stale"))))
        val staleClaim = requireNotNull(
            dao.claimOcrBatchItem("job-stale", "item-stale", 1, "stale-claim", 20),
        )
        dao.replaceDocument(document(modifiedAt = 21), listOf(stored.copy(rotationDegrees = 90)), "")
        assertEquals(
            OcrBatchCompletionResult.STALE_INPUT,
            dao.completeOcrBatchItem(staleClaim, draft(staleClaim.expected, "obsolete"), 22),
        )
        assertEquals(1, dao.ocrBatchProgress("job-stale").retryableItems)
        assertTrue(dao.refreshOcrBatchItemForRetry("job-stale", "item-stale", 23))
        val refreshedJob = requireNotNull(dao.ocrBatchJob("job-stale"))
        val refreshed = requireNotNull(
            dao.claimOcrBatchItem(
                "job-stale",
                "item-stale",
                refreshedJob.runGeneration,
                "fresh-claim",
                24,
            ),
        )
        assertEquals(1L, refreshed.expected.pageVisualRevision)
        assertEquals(
            OcrBatchCompletionResult.APPLIED,
            dao.completeOcrBatchItem(refreshed, draft(refreshed.expected, "fresh searchable"), 25),
        )
        assertEquals(
            stored.pageId,
            dao.pageSearchPage("searchable*", Long.MAX_VALUE, "", 10).single().pageId,
        )
    }

    @Test
    fun rerunCommitKeepsCorrectionEffectiveAndUpdatesOnlyRawArtifact() = runBlocking {
        val stored = page("page-1", 0, "hash-1", null)
        dao.replaceDocument(document(), listOf(stored), "")
        val initial = requireNotNull(dao.ocrPageSnapshot(DOCUMENT_ID, stored.pageId))
        assertEquals(
            LibraryOcrCommitResult.APPLIED,
            dao.commitOcrArtifact(initial, draft(initial, "old raw"), 10),
        )
        val correctedSnapshot = requireNotNull(dao.ocrPageSnapshot(DOCUMENT_ID, stored.pageId))
        assertEquals(
            LibraryOcrCommitResult.APPLIED,
            dao.saveOcrCorrection(
                correctedSnapshot,
                LibraryOcrCorrectionDraft(
                    correctedText = "kept correction",
                    alignment = LibraryOcrCorrectionAlignment.FREEFORM,
                ),
                11,
            ),
        )
        assertTrue(dao.freezeOcrBatchTargets(job("job-rerun"), listOf(target("item-rerun"))))
        val claim = requireNotNull(
            dao.claimOcrBatchItem("job-rerun", "item-rerun", 1, "claim-rerun", 12),
        )
        assertEquals(
            OcrBatchCompletionResult.APPLIED,
            dao.completeOcrBatchItem(claim, draft(claim.expected, "new raw"), 13),
        )
        val review = requireNotNull(dao.ocrReviewPage(DOCUMENT_ID, stored.pageId))
        assertEquals("new raw", review.rawText)
        assertEquals("kept correction", review.effectiveText)
        assertEquals("kept correction", dao.effectiveOcrPage(DOCUMENT_ID, stored.pageId)?.effectiveText)
        assertTrue(dao.pageSearchPage("new*", Long.MAX_VALUE, "", 10).isEmpty())
        assertEquals(
            stored.pageId,
            dao.pageSearchPage("kept*", Long.MAX_VALUE, "", 10).single().pageId,
        )
    }

    @Test
    fun batchPopulationScalesInBoundedChunksForFiveFiftyAndOneHundredDocuments() = runBlocking {
        val runtime = Runtime.getRuntime()
        val heapBefore = runtime.totalMemory() - runtime.freeMemory()
        repeat(100) { documentIndex ->
            val documentId = "scale-document-$documentIndex"
            val pages = List(2) { pageIndex ->
                page(
                    id = "scale-page-$documentIndex-$pageIndex",
                    position = pageIndex,
                    hash = "scale-hash-$documentIndex-$pageIndex",
                    raw = null,
                ).copy(documentId = documentId)
            }
            dao.replaceDocument(
                document(pageCount = 2).copy(documentId = documentId, title = "Scale $documentIndex"),
                pages,
                "",
            )
        }
        listOf(5, 50, 100).forEach { documentCount ->
            val started = android.os.SystemClock.elapsedRealtime()
            val queriesBefore = queryCount.get()
            val targets = ArrayList<OcrBatchTarget>(documentCount * 2)
            repeat(documentCount) { documentIndex ->
                repeat(2) { pageIndex ->
                    val ordinal = targets.size
                    targets += OcrBatchTarget(
                        itemId = "scale-item-$documentCount-$ordinal",
                        documentId = "scale-document-$documentIndex",
                        pageId = "scale-page-$documentIndex-$pageIndex",
                        ordinal = ordinal,
                        requestedScriptSelection = "LATIN",
                        resolvedScript = "LATIN",
                    )
                }
            }
            val scaleJob = job("scale-job-$documentCount").copy(selectionPolicy = "MISSING_ONLY")
            assertTrue(dao.freezeOcrBatchTargets(scaleJob, targets))
            val planningElapsed = android.os.SystemClock.elapsedRealtime() - started
            assertEquals(documentCount * 2, dao.ocrBatchJob(scaleJob.jobId)?.totalItemCount)
            assertEquals(documentCount, dao.ocrBatchProgress(scaleJob.jobId).documentTotal)
            val processingStarted = android.os.SystemClock.elapsedRealtime()
            var processed = 0
            while (true) {
                val next = dao.nextOcrBatchItem(scaleJob.jobId) ?: break
                val claim = requireNotNull(
                    dao.claimOcrBatchItem(
                        scaleJob.jobId,
                        next.itemId,
                        1,
                        "scale-claim-$documentCount-$processed",
                        200L + processed,
                    ),
                )
                assertEquals(
                    OcrBatchCompletionResult.APPLIED,
                    dao.skipOcrBatchItem(claim, "CURRENT_OCR", 200L + processed),
                )
                processed += 1
            }
            val processingElapsed = android.os.SystemClock.elapsedRealtime() - processingStarted
            val queryDelta = queryCount.get() - queriesBefore
            assertEquals(targets.size, processed)
            assertEquals(OcrBatchJobState.COMPLETED.name, dao.ocrBatchJob(scaleJob.jobId)?.state)
            println(
                "OCR_BATCH_SCALE documents=$documentCount pages=${targets.size} " +
                    "planningMs=$planningElapsed processingMs=$processingElapsed queries=$queryDelta",
            )
        }
        repeat(3) {
            System.gc()
            System.runFinalization()
            delay(100)
        }
        val heapAfter = runtime.totalMemory() - runtime.freeMemory()
        val cursor = database.openHelper.readableDatabase.query("PRAGMA page_count")
        val pageCount = cursor.use { if (it.moveToFirst()) it.getLong(0) else -1L }
        println("OCR_BATCH_SCALE heapDeltaBytes=${heapAfter - heapBefore} dbPages=$pageCount")
        assertTrue(pageCount > 0)
    }

    @Test
    fun terminalBatchMetadataFootprintRemainsBoundedThroughOneThousandTargets() = runBlocking {
        repeat(500) { documentIndex ->
            val documentId = "footprint-document-$documentIndex"
            val pages = List(2) { pageIndex ->
                page(
                    id = "footprint-page-$documentIndex-$pageIndex",
                    position = pageIndex,
                    hash = "footprint-hash-$documentIndex-$pageIndex",
                    raw = null,
                ).copy(documentId = documentId)
            }
            dao.replaceDocument(
                document(pageCount = 2).copy(
                    documentId = documentId,
                    title = "Footprint $documentIndex",
                ),
                pages,
                "",
            )
        }

        val readableDatabase = database.openHelper.readableDatabase
        val pageSize = readableDatabase.query("PRAGMA page_size").use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else error("Missing SQLite page size")
        }
        listOf(20, 200, 1_000).forEach { targetCount ->
            val pageCountBefore = readableDatabase.query("PRAGMA page_count").use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else error("Missing SQLite page count")
            }
            val targets = List(targetCount) { ordinal ->
                val documentIndex = ordinal / 2
                val pageIndex = ordinal % 2
                OcrBatchTarget(
                    itemId = "footprint-item-$targetCount-$ordinal",
                    documentId = "footprint-document-$documentIndex",
                    pageId = "footprint-page-$documentIndex-$pageIndex",
                    ordinal = ordinal,
                    requestedScriptSelection = "LATIN",
                    resolvedScript = "LATIN",
                )
            }
            val jobId = "footprint-job-$targetCount"
            assertTrue(dao.freezeOcrBatchTargets(job(jobId), targets))
            assertTrue(dao.cancelOcrBatchJob(jobId, 1_000L + targetCount))
            assertEquals(OcrBatchJobState.CANCELLED.name, dao.ocrBatchJob(jobId)?.state)
            assertEquals(targetCount, dao.ocrBatchItems(jobId).size)
            val pageCountAfter = readableDatabase.query("PRAGMA page_count").use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else error("Missing SQLite page count")
            }
            println(
                "OCR_BATCH_METADATA targets=$targetCount " +
                    "allocatedBytes=${(pageCountAfter - pageCountBefore) * pageSize}",
            )
        }
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
