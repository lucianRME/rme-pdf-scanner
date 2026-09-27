package org.synapseworks.pageharbor.ocr.batch

import android.app.Application
import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.synapseworks.pageharbor.library.LibraryDatabase
import org.synapseworks.pageharbor.library.LibraryDocumentEntity
import org.synapseworks.pageharbor.library.LibraryDocumentState
import org.synapseworks.pageharbor.library.LibraryFileStore
import org.synapseworks.pageharbor.library.LibraryOcrCommitResult
import org.synapseworks.pageharbor.library.LibraryOcrCorrectionAlignment
import org.synapseworks.pageharbor.library.LibraryOcrCorrectionDraft
import org.synapseworks.pageharbor.library.LibraryOcrStatus
import org.synapseworks.pageharbor.library.LibraryOperationGate
import org.synapseworks.pageharbor.library.LibraryPageEntity
import org.synapseworks.pageharbor.library.LibraryRepository
import org.synapseworks.pageharbor.library.OcrBatchJobEntity
import org.synapseworks.pageharbor.library.OcrBatchJobState
import org.synapseworks.pageharbor.library.OcrBatchTarget
import org.synapseworks.pageharbor.ocr.OcrModelDelivery
import org.synapseworks.pageharbor.ocr.OcrModelState
import org.synapseworks.pageharbor.ocr.OcrPageLayout
import org.synapseworks.pageharbor.ocr.OcrPageRecognitionEngine
import org.synapseworks.pageharbor.ocr.OcrPageRecognitionOutcome
import org.synapseworks.pageharbor.ocr.OcrRecognizerProvenance
import org.synapseworks.pageharbor.ocr.OcrScript
import org.synapseworks.pageharbor.ocr.OcrScriptSelection
import org.synapseworks.pageharbor.ocr.OcrTextBounds
import org.synapseworks.pageharbor.ocr.OcrTextLine

@RunWith(AndroidJUnit4::class)
class OcrBatchViewModelInstrumentedTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var database: LibraryDatabase
    private lateinit var repository: LibraryRepository
    private lateinit var root: File
    private val nextId = AtomicInteger()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(application, LibraryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        root = File(application.filesDir, LibraryFileStore.LIBRARY_DIRECTORY)
        root.deleteRecursively()
        root.mkdirs()
        repository = LibraryRepository(
            context = application,
            dao = database.libraryDao(),
            fileStore = LibraryFileStore(application, root),
            nowMillis = { 100L + nextId.get() },
            operationGate = LibraryOperationGate(),
        )
    }

    @After
    fun tearDown() {
        database.close()
        root.deleteRecursively()
    }

    @Test
    fun missingOnlySkipsCorrectedPagePersistsSuccessAndContainsFailure() = runBlocking {
        storeDocument("document-a", 2)
        storeDocument("document-b", 1)
        val dao = database.libraryDao()
        val correctedPage = "document-a-page-0"
        val initial = requireNotNull(dao.ocrPageSnapshot("document-a", correctedPage))
        assertEquals(
            LibraryOcrCommitResult.APPLIED,
            dao.commitOcrArtifact(initial, artifact(initial, "old raw"), 10),
        )
        val correctedSnapshot = requireNotNull(dao.ocrPageSnapshot("document-a", correctedPage))
        assertEquals(
            LibraryOcrCommitResult.APPLIED,
            dao.saveOcrCorrection(
                correctedSnapshot,
                LibraryOcrCorrectionDraft(
                    "user correction",
                    LibraryOcrCorrectionAlignment.FREEFORM,
                ),
                11,
            ),
        )
        val failedPage = "document-b-page-0"
        val engine = OcrPageRecognitionEngine { request ->
            if (request.descriptor.address.pageId == "document-b-page-0") {
                OcrPageRecognitionOutcome.Failure(
                    request.descriptor,
                    org.synapseworks.pageharbor.ocr.OcrFailureReason.IMAGE_UNREADABLE,
                )
            } else {
                success(request.descriptor, "batch searchable")
            }
        }
        val viewModel = viewModel(engine)
        viewModel.openSetup(listOf("document-a", "document-b"))
        viewModel.start(OcrScriptSelection.Explicit(OcrScript.LATIN), OcrScript.LATIN, OcrScript.LATIN)

        val finished = awaitFinished(viewModel)
        assertEquals(3, finished.summary.pageTotal)
        assertEquals(1, finished.summary.completedPages)
        assertEquals(1, finished.summary.skippedPages)
        assertEquals(1, finished.summary.failedPages)
        assertEquals("user correction", dao.ocrReviewPage("document-a", correctedPage)?.effectiveText)
        assertEquals(
            "document-a-page-1",
            dao.pageSearchPage("searchable*", Long.MAX_VALUE, "", 10).single().pageId,
        )
    }

    @Test
    fun failedRerunPreservesPriorValidSearchState() = runBlocking {
        storeDocument("document-failed-rerun", 1)
        val dao = database.libraryDao()
        val pageId = "document-failed-rerun-page-0"
        val snapshot = requireNotNull(dao.ocrPageSnapshot("document-failed-rerun", pageId))
        assertEquals(
            LibraryOcrCommitResult.APPLIED,
            dao.commitOcrArtifact(snapshot, artifact(snapshot, "prior valid searchable state"), 12),
        )
        val viewModel = viewModel(
            engine = OcrPageRecognitionEngine { request ->
                OcrPageRecognitionOutcome.Failure(
                    request.descriptor,
                    org.synapseworks.pageharbor.ocr.OcrFailureReason.IMAGE_UNREADABLE,
                )
            },
        )
        viewModel.openSetup(listOf("document-failed-rerun"))
        viewModel.setMode(OcrBatchMode.RERUN)
        viewModel.start(OcrScriptSelection.Explicit(OcrScript.LATIN), OcrScript.LATIN, OcrScript.LATIN)

        assertEquals(1, awaitFinished(viewModel).summary.failedPages)
        assertEquals(
            pageId,
            dao.pageSearchPage("prior*", Long.MAX_VALUE, "", 10).single().pageId,
        )
    }

    @Test
    fun rerunUsesExplicitOptionalScriptAndKeepsCorrectionEffective() = runBlocking {
        storeDocument("document-c", 1)
        val dao = database.libraryDao()
        val pageId = "document-c-page-0"
        val initial = requireNotNull(dao.ocrPageSnapshot("document-c", pageId))
        dao.commitOcrArtifact(initial, artifact(initial, "old"), 1)
        val corrected = requireNotNull(dao.ocrPageSnapshot("document-c", pageId))
        dao.saveOcrCorrection(
            corrected,
            LibraryOcrCorrectionDraft("kept", LibraryOcrCorrectionAlignment.FREEFORM),
            2,
        )
        val scripts = mutableListOf<OcrScript>()
        val viewModel = viewModel(
            engine = OcrPageRecognitionEngine { request ->
                scripts += request.descriptor.script
                success(request.descriptor, "new Japanese raw")
            },
            modelState = { OcrModelState.Installed },
        )
        viewModel.openSetup(listOf("document-c"))
        viewModel.setMode(OcrBatchMode.RERUN)
        viewModel.start(
            OcrScriptSelection.Explicit(OcrScript.JAPANESE),
            OcrScript.JAPANESE,
            OcrScript.LATIN,
        )

        assertEquals(1, awaitFinished(viewModel).summary.completedPages)
        assertEquals(listOf(OcrScript.JAPANESE), scripts)
        val review = requireNotNull(dao.ocrReviewPage("document-c", pageId))
        assertEquals("new Japanese raw", review.rawText)
        assertEquals("kept", review.effectiveText)
    }

    @Test
    fun unavailableOptionalModelCreatesNoDurableJob() = runBlocking {
        storeDocument("document-d", 1)
        val viewModel = viewModel(
            engine = OcrPageRecognitionEngine { error("must not recognize") },
            modelState = { OcrModelState.NotInstalled },
        )
        viewModel.openSetup(listOf("document-d"))
        viewModel.start(
            OcrScriptSelection.Explicit(OcrScript.KOREAN),
            OcrScript.KOREAN,
            OcrScript.LATIN,
        )
        val setup = withTimeout(5_000) {
            viewModel.state.filterIsInstance<OcrBatchUiState.Setup>()
                .first { it.error == OcrBatchSetupError.MODEL_UNAVAILABLE }
        }
        assertEquals(OcrBatchSetupError.MODEL_UNAVAILABLE, setup.error)
        assertTrue(database.libraryDao().latestRecoverableOcrBatchJob() == null)
    }

    @Test
    fun cancellationLeavesCompletedPagesAndTerminatesRemainingWork() = runBlocking {
        storeDocument("document-e", 2)
        var calls = 0
        val viewModel = viewModel(
            engine = OcrPageRecognitionEngine { request ->
                calls += 1
                if (calls == 1) success(request.descriptor, "first complete") else awaitCancellation()
            },
        )
        viewModel.openSetup(listOf("document-e"))
        viewModel.start(OcrScriptSelection.Explicit(OcrScript.LATIN), OcrScript.LATIN, OcrScript.LATIN)
        withTimeout(5_000) {
            viewModel.state.filterIsInstance<OcrBatchUiState.Running>()
                .first { it.progress.completedPages == 1 }
        }
        viewModel.cancel()
        val finished = awaitFinished(viewModel)
        assertTrue(finished.summary.cancelled)
        assertEquals(1, finished.summary.completedPages)
        assertEquals(1, finished.summary.skippedPages)

        var reconstructedExecutions = 0
        val reconstructed = viewModel(
            engine = OcrPageRecognitionEngine {
                reconstructedExecutions += 1
                error("cancelled work must not restart")
            },
        )
        delay(250)
        assertEquals(OcrBatchUiState.Hidden, reconstructed.state.value)
        assertEquals(0, reconstructedExecutions)
        assertEquals(
            OcrBatchJobState.CANCELLED.name,
            database.libraryDao().ocrBatchJob(finished.jobId)?.state,
        )
    }

    @Test
    fun repositoryReconstructionResumesDurableReadyJobWithoutDuplicateExecution() = runBlocking {
        storeDocument("document-f", 1)
        val dao = database.libraryDao()
        assertTrue(
            dao.freezeOcrBatchTargets(
                OcrBatchJobEntity(
                    jobId = "reconstructed-job",
                    selectionPolicy = OcrBatchMode.RERUN.name,
                    requestedScriptSelection = OcrScript.LATIN.stableId,
                    localeRecommendationSnapshot = OcrScript.LATIN.stableId,
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
                ),
                listOf(
                    OcrBatchTarget(
                        itemId = "reconstructed-item",
                        documentId = "document-f",
                        pageId = "document-f-page-0",
                        ordinal = 0,
                        requestedScriptSelection = OcrScript.LATIN.stableId,
                        resolvedScript = OcrScript.LATIN.stableId,
                    ),
                ),
            ),
        )
        requireNotNull(
            dao.claimOcrBatchItem(
                "reconstructed-job",
                "reconstructed-item",
                1,
                "interrupted-claim",
                2,
            ),
        )
        var executions = 0
        val reconstructed = viewModel(
            OcrPageRecognitionEngine { request ->
                executions += 1
                success(request.descriptor, "resumed")
            },
        )

        assertEquals(1, awaitFinished(reconstructed).summary.completedPages)
        assertEquals(1, executions)
        assertEquals(2, dao.ocrBatchItems("reconstructed-job").single().attemptNumber)
    }

    @Test
    fun reconstructedPartialJobRetriesOnlyFailureWithoutRerunningSuccesses() = runBlocking {
        storeDocument("document-g", 3)
        val dao = database.libraryDao()
        val jobId = "partial-reconstructed-job"
        assertTrue(
            dao.freezeOcrBatchTargets(
                OcrBatchJobEntity(
                    jobId = jobId,
                    selectionPolicy = OcrBatchMode.RERUN.name,
                    requestedScriptSelection = OcrScript.LATIN.stableId,
                    localeRecommendationSnapshot = OcrScript.LATIN.stableId,
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
                ),
                List(3) { index ->
                    OcrBatchTarget(
                        itemId = "partial-item-$index",
                        documentId = "document-g",
                        pageId = "document-g-page-$index",
                        ordinal = index,
                        requestedScriptSelection = OcrScript.LATIN.stableId,
                        resolvedScript = OcrScript.LATIN.stableId,
                    )
                },
            ),
        )
        val completed = requireNotNull(
            dao.claimOcrBatchItem(jobId, "partial-item-0", 1, "completed-claim", 2),
        )
        assertEquals(
            org.synapseworks.pageharbor.library.OcrBatchCompletionResult.APPLIED,
            dao.completeOcrBatchItem(completed, artifact(completed.expected, "already complete"), 3),
        )
        val failed = requireNotNull(
            dao.claimOcrBatchItem(jobId, "partial-item-1", 1, "failed-claim", 4),
        )
        assertEquals(
            org.synapseworks.pageharbor.library.OcrBatchCompletionResult.APPLIED,
            dao.failOcrBatchItem(failed, "RECOGNITION_FAILED", 5),
        )
        requireNotNull(
            dao.claimOcrBatchItem(jobId, "partial-item-2", 1, "interrupted-claim", 6),
        )

        val executions = mutableListOf<String?>()
        val reconstructed = viewModel(
            engine = OcrPageRecognitionEngine { request ->
                executions += request.descriptor.address.pageId
                success(request.descriptor, "reconstructed ${request.descriptor.address.pageId}")
            },
        )
        val resumed = awaitFinished(reconstructed)
        assertEquals(2, resumed.summary.completedPages)
        assertEquals(1, resumed.summary.failedPages)
        assertEquals(listOf("document-g-page-2"), executions)

        reconstructed.retryFailed()
        val retried = withTimeout(10_000) {
            reconstructed.state.filterIsInstance<OcrBatchUiState.Finished>()
                .first { it.summary.completedPages == 3 && it.summary.failedPages == 0 }
        }
        assertEquals(3, retried.summary.completedPages)
        assertEquals(0, retried.summary.failedPages)
        assertEquals(listOf("document-g-page-2", "document-g-page-1"), executions)
        assertEquals(1, dao.ocrBatchItems(jobId).first { it.ordinal == 0 }.attemptNumber)
    }

    private fun viewModel(
        engine: OcrPageRecognitionEngine,
        modelState: suspend (OcrScript) -> OcrModelState = { OcrModelState.Bundled },
    ) = OcrBatchViewModel(
        application = application,
        repository = repository,
        recognitionEngine = engine,
        modelState = modelState,
        nowMillis = { 1_000L + nextId.incrementAndGet() },
        newId = { "batch-id-${nextId.incrementAndGet()}" },
    )

    private suspend fun awaitFinished(viewModel: OcrBatchViewModel): OcrBatchUiState.Finished =
        withTimeout(10_000) {
            viewModel.state.filterIsInstance<OcrBatchUiState.Finished>().first()
        }

    private suspend fun storeDocument(documentId: String, pageCount: Int) {
        val pages = List(pageCount) { index ->
            val pageId = "$documentId-page-$index"
            writeImage("$pageId.jpg")
            LibraryPageEntity(
                pageId = pageId,
                documentId = documentId,
                position = index,
                relativePath = "$pageId.jpg",
                contentType = "image/jpeg",
                sourceCategory = "TEST",
                width = 20,
                height = 20,
                sourceByteCount = File(root, "$pageId.jpg").length(),
                rotationDegrees = 0,
                filterName = "ORIGINAL",
                ocrText = null,
                ocrError = null,
                contentSha256 = "${index + 1}".repeat(64).take(64),
            )
        }
        database.libraryDao().replaceDocument(
            LibraryDocumentEntity(
                documentId = documentId,
                title = "Title $documentId",
                createdAtMillis = 1,
                modifiedAtMillis = 1,
                pageCount = pageCount,
                folderId = null,
                thumbnailRelativePath = null,
                ocrStatus = LibraryOcrStatus.NOT_INDEXED.name,
                libraryState = LibraryDocumentState.ACTIVE.name,
            ),
            pages,
            "",
        )
    }

    private fun writeImage(name: String) {
        FileOutputStream(File(root, name)).use { output ->
            Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).run {
                eraseColor(0xffffffff.toInt())
                compress(Bitmap.CompressFormat.JPEG, 90, output)
                recycle()
            }
        }
    }

    private fun success(
        descriptor: org.synapseworks.pageharbor.ocr.OcrPageRecognitionDescriptor,
        text: String,
    ) = OcrPageRecognitionOutcome.Success(
        descriptor = descriptor,
        rawText = text,
        layout = OcrPageLayout(
            imageWidthPx = 20,
            imageHeightPx = 20,
            lines = listOf(OcrTextLine(text, OcrTextBounds(1f, 1f, 19f, 10f))),
        ),
        provenance = OcrRecognizerProvenance(
            actualScript = descriptor.script,
            recognizerId = "batch-test",
            pipelineVersion = "test-v1",
            delivery = if (descriptor.script == OcrScript.LATIN) {
                OcrModelDelivery.BUNDLED
            } else {
                OcrModelDelivery.PLAY_SERVICES
            },
        ),
    )

    private fun artifact(
        snapshot: org.synapseworks.pageharbor.library.LibraryOcrPageSnapshot,
        text: String,
    ) = org.synapseworks.pageharbor.library.LibraryOcrArtifactDraft(
        inputFingerprintVersion = 1,
        inputFingerprint = "test-${snapshot.pageId}-$text",
        contentSha256 = requireNotNull(snapshot.contentSha256),
        rotationDegrees = snapshot.rotationDegrees,
        filterName = snapshot.filterName,
        uprightWidth = 20,
        uprightHeight = 20,
        coordinateSystemVersion = 1,
        transformVersion = 1,
        actualScript = "LATIN",
        recognizerId = "test",
        pipelineVersion = "test",
        clientVersion = null,
        delivery = "BUNDLED",
        recognizedAtMillis = 1,
        rawText = text,
    )
}
