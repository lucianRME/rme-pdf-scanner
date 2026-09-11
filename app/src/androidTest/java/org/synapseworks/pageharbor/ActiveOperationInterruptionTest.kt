package org.synapseworks.pageharbor

import android.net.Uri
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.synapseworks.pageharbor.document.NormalPdfExportPlan
import org.synapseworks.pageharbor.document.PageExportResult
import org.synapseworks.pageharbor.document.PageExportState
import org.synapseworks.pageharbor.document.PdfExportResult
import org.synapseworks.pageharbor.document.PdfSaveState
import org.synapseworks.pageharbor.document.PdfSharePreparationResult
import org.synapseworks.pageharbor.document.PdfShareState
import org.synapseworks.pageharbor.document.PreparedPdfShareFile
import org.synapseworks.pageharbor.document.searchablepdf.LocalSearchablePdfExportCoordinator
import org.synapseworks.pageharbor.document.searchablepdf.SearchablePdfExportCoordinator
import org.synapseworks.pageharbor.document.searchablepdf.SearchablePdfExportRequest
import org.synapseworks.pageharbor.document.searchablepdf.SearchablePdfExportResult
import org.synapseworks.pageharbor.document.searchablepdf.SearchablePdfGenerationResult
import org.synapseworks.pageharbor.document.searchablepdf.SearchablePdfGenerator
import org.synapseworks.pageharbor.document.searchablepdf.SearchablePdfPreparationError
import org.synapseworks.pageharbor.document.searchablepdf.SearchablePdfPreparedExport
import org.synapseworks.pageharbor.document.searchablepdf.SearchablePdfRequest
import org.synapseworks.pageharbor.document.searchablepdf.SearchablePdfSaveState
import org.synapseworks.pageharbor.document.session.DocumentPage
import org.synapseworks.pageharbor.document.session.DocumentPageId
import org.synapseworks.pageharbor.document.session.DocumentResource
import org.synapseworks.pageharbor.document.session.DocumentResourceOwnership
import org.synapseworks.pageharbor.document.session.DocumentSession
import org.synapseworks.pageharbor.document.session.DocumentSourceCategory
import org.synapseworks.pageharbor.document.session.OwnedTemporaryFile
import org.synapseworks.pageharbor.image.DocumentFilter
import org.synapseworks.pageharbor.ocr.OcrEngine
import org.synapseworks.pageharbor.ocr.OcrPage
import org.synapseworks.pageharbor.ocr.OcrPageResult
import org.synapseworks.pageharbor.ocr.OcrResult
import org.synapseworks.pageharbor.ocr.OcrUiState
import org.synapseworks.pageharbor.scanner.ScannerSpikeState
import org.synapseworks.pageharbor.ui.PageHarborScreen

/**
 * Exercises real Activity ownership with fakes paused at an explicit test-only gate. The gate
 * lives in androidTest, receives no document data, and has bounded waits only in test code.
 */
class ActiveOperationInterruptionTest {
    @Test
    fun pausedOcrCompletesOnceAfterBackgroundAndForeground() {
        val gate = ControllableOperationGate()
        val terminalState = CountDownLatch(1)
        val result = ocrResult("ocr-background-token")

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.replaceOperationsForTest(
                    ocrEngine = GatedOcrEngine(gate, result),
                    onOcrTerminalState = terminalState::countDown,
                )
                activity.restoreCompletedSessionForTest(
                    summary = scanSummary(),
                    pageUris = listOf(testPageUri()),
                )
                activity.recognizeTextForTest()
            }

            gate.awaitReached()
            scenario.moveToState(Lifecycle.State.STARTED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            gate.release()

            assertTrue("OCR completion was not delivered", terminalState.await(10, TimeUnit.SECONDS))
            scenario.onActivity { activity ->
                assertEquals(OcrUiState.Success(result), activity.ocrStateForTest())
                assertEquals(PageHarborScreen.ScanResult, activity.sessionScreenForTest())
            }
        }
    }

    @Test
    fun pausedOcrIsIgnoredAfterDiscardAndGateRelease() {
        val gate = ControllableOperationGate()
        val terminalStateCount = AtomicInteger(0)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.replaceOperationsForTest(
                    ocrEngine = GatedOcrEngine(gate, ocrResult("stale-ocr-token")),
                    onOcrTerminalState = { terminalStateCount.incrementAndGet() },
                )
                activity.restoreCompletedSessionForTest(scanSummary(), pageUris = listOf(testPageUri()))
                activity.recognizeTextForTest()
            }

            gate.awaitReached()
            scenario.onActivity { it.discardForTest() }
            gate.release()
            gate.awaitExited()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            scenario.onActivity { activity ->
                assertEquals(PageHarborScreen.Home, activity.sessionScreenForTest())
                assertEquals(OcrUiState.Idle, activity.ocrStateForTest())
            }
            assertEquals(0, terminalStateCount.get())
        }
    }

    @Test
    fun pausedOcrRecreationResetsToScanResultAndIgnoresLateCompletion() {
        val gate = ControllableOperationGate()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.replaceOperationsForTest(ocrEngine = GatedOcrEngine(gate, ocrResult("rotation")))
                activity.restoreCompletedSessionForTest(scanSummary(), pageUris = listOf(testPageUri()))
                activity.recognizeTextForTest()
            }

            gate.awaitReached()
            scenario.recreate()
            gate.release()
            gate.awaitExited()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            scenario.onActivity { activity ->
                assertEquals(PageHarborScreen.ScanResult, activity.sessionScreenForTest())
                assertEquals(OcrUiState.Idle, activity.ocrStateForTest())
            }
        }
    }

    @Test
    fun pausedSearchablePdfCompletesWithOneDestinationRequestAfterBackgroundAndForeground() {
        runBlocking {
        val gate = ControllableOperationGate()
        val destinationRequests = CountDownLatch(1)
        var requestCount = 0

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.replaceOperationsForTest(
                    searchablePdfExportCoordinator = gatedCoordinator(gate),
                    onSearchablePdfDestinationRequested = {
                        requestCount++
                        destinationRequests.countDown()
                    },
                )
                activity.restoreCompletedSessionForTest(
                    summary = scanSummary(),
                    ocrResult = ocrResult("searchable-background-token"),
                    pageUris = listOf(testPageUri()),
                )
                activity.saveSearchablePdfForTest()
            }

            gate.awaitReached()
            scenario.moveToState(Lifecycle.State.STARTED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            gate.release()

            assertTrue("Destination picker was not requested", destinationRequests.await(10, TimeUnit.SECONDS))
            scenario.onActivity { activity ->
                assertEquals(1, requestCount)
                assertEquals(SearchablePdfSaveState.ChoosingDestination, activity.searchablePdfStateForTest())
            }
        }
    }
    }

    @Test
    fun pausedSearchablePdfDiscardCleansPreparedOutputAndDoesNotRequestDestination() {
        runBlocking {
        val gate = ControllableOperationGate()
        val deleted = CountDownLatch(1)
        val destinationRequestCount = AtomicInteger(0)
        var generatedFile: File? = null

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.replaceOperationsForTest(
                    searchablePdfExportCoordinator = gatedCoordinator(
                        gate = gate,
                        onCreated = { generatedFile = it },
                        onDeleted = deleted::countDown,
                    ),
                    onSearchablePdfDestinationRequested = { destinationRequestCount.incrementAndGet() },
                )
                activity.restoreCompletedSessionForTest(
                    summary = scanSummary(),
                    ocrResult = ocrResult("searchable-stale-token"),
                    pageUris = listOf(testPageUri()),
                )
                activity.saveSearchablePdfForTest()
            }

            gate.awaitReached()
            scenario.onActivity { it.discardForTest() }
            gate.release()

            assertTrue("Prepared temporary output was not cleaned", deleted.await(10, TimeUnit.SECONDS))
            assertFalse(generatedFile?.exists() ?: true)
            assertEquals(0, destinationRequestCount.get())
            scenario.onActivity { activity ->
                assertEquals(PageHarborScreen.Home, activity.sessionScreenForTest())
                assertEquals(SearchablePdfSaveState.Idle, activity.searchablePdfStateForTest())
            }
        }
    }
    }

    @Test
    fun pausedNormalPdfSaveCannotPublishAfterDiscard() {
        val gate = ControllableOperationGate()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.replaceNormalOperationsForTest(
                    writeNormalPdf = { _: NormalPdfExportPlan, _: Uri ->
                        gate.await()
                        PdfExportResult.Success
                    },
                    onNormalPdfDestinationRequested = {},
                )
                activity.restoreCompletedSessionForTest(scanSummary(), pageUris = listOf(testPageUri()))
                activity.chooseNormalPdfDestinationForTest()
                activity.deliverNormalPdfDestinationForTest(testDestinationUri("normal.pdf"))
            }

            gate.awaitReached()
            scenario.onActivity { it.discardForTest() }
            gate.release()
            gate.awaitExited()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            scenario.onActivity { activity ->
                assertEquals(PageHarborScreen.Home, activity.sessionScreenForTest())
                assertEquals(PdfSaveState.Idle, activity.normalPdfStateForTest())
            }
        }
    }

    @Test
    fun pausedNormalPdfSaveRecreationResetsStateAndIgnoresLateCompletion() {
        val gate = ControllableOperationGate()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.replaceNormalOperationsForTest(
                    writeNormalPdf = { _, _ ->
                        gate.await()
                        PdfExportResult.Success
                    },
                    onNormalPdfDestinationRequested = {},
                )
                activity.restoreCompletedSessionForTest(scanSummary(), pageUris = listOf(testPageUri()))
                activity.chooseNormalPdfDestinationForTest()
                activity.deliverNormalPdfDestinationForTest(testDestinationUri("recreated.pdf"))
            }

            gate.awaitReached()
            scenario.recreate()
            scenario.onActivity { activity ->
                assertEquals(PageHarborScreen.ScanResult, activity.sessionScreenForTest())
                assertEquals(PdfSaveState.Idle, activity.normalPdfStateForTest())
            }

            gate.release()
            gate.awaitExited()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            scenario.onActivity { activity ->
                assertEquals(PageHarborScreen.ScanResult, activity.sessionScreenForTest())
                assertEquals(PdfSaveState.Idle, activity.normalPdfStateForTest())
            }
        }
    }

    @Test
    fun pausedShareDiscardPreventsChooserAndDeletesPreparedFileExactlyOnce() {
        val gate = ControllableOperationGate()
        val chooserCount = AtomicInteger(0)
        val deleteCount = AtomicInteger(0)
        val deletionFinished = CountDownLatch(1)
        val temporary = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "stale-share-${System.nanoTime()}.pdf",
        ).apply { writeBytes(byteArrayOf(0x50, 0x44, 0x46)) }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.replaceNormalOperationsForTest(
                    preparePdfShare = {
                        gate.await()
                        PdfSharePreparationResult.Ready(
                            uri = testDestinationUri("share.pdf"),
                            temporaryFile = PreparedPdfShareFile(temporary) { file ->
                                deleteCount.incrementAndGet()
                                file.delete().also { deletionFinished.countDown() }
                            },
                        )
                    },
                    onPdfShareRequested = { chooserCount.incrementAndGet() },
                )
                activity.restoreCompletedSessionForTest(scanSummary(), pageUris = listOf(testPageUri()))
                activity.sharePdfForTest()
            }

            gate.awaitReached()
            scenario.onActivity { it.discardForTest() }
            gate.release()
            gate.awaitExited()
            assertTrue(deletionFinished.await(10, TimeUnit.SECONDS))
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            assertEquals(0, chooserCount.get())
            assertEquals(1, deleteCount.get())
            assertFalse(temporary.exists())
            scenario.onActivity { activity ->
                assertEquals(PdfShareState.Idle, activity.pdfShareStateForTest())
            }
        }
    }

    @Test
    fun pausedPageExportDiscardDoesNotLaunchTheNextDestination() {
        val gate = ControllableOperationGate()
        val destinationRequests = AtomicInteger(0)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.replaceNormalOperationsForTest(
                    exportPage = { _, _ ->
                        gate.await()
                        PageExportResult.Success
                    },
                    onPageDestinationRequested = { destinationRequests.incrementAndGet() },
                )
                activity.restoreCompletedSessionForTest(
                    scanSummary(pageCount = 2),
                    pageUris = listOf(testPageUri(), testPageUri("page-2")),
                )
                activity.exportPagesForTest()
                activity.deliverPageDestinationForTest(testDestinationUri("page-1.jpg"))
            }

            gate.awaitReached()
            scenario.onActivity { it.discardForTest() }
            gate.release()
            gate.awaitExited()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            assertEquals(1, destinationRequests.get())
            scenario.onActivity { activity ->
                assertEquals(PageExportState.Idle, activity.pageExportStateForTest())
            }
        }
    }

    @Test
    fun staleDestinationCallbacksAfterDiscardAreIgnored() {
        val writeCount = AtomicInteger(0)
        val exportCount = AtomicInteger(0)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.replaceNormalOperationsForTest(
                    writeNormalPdf = { _, _ ->
                        writeCount.incrementAndGet()
                        PdfExportResult.Success
                    },
                    exportPage = { _, _ ->
                        exportCount.incrementAndGet()
                        PageExportResult.Success
                    },
                    onNormalPdfDestinationRequested = {},
                    onPageDestinationRequested = {},
                )
                activity.restoreCompletedSessionForTest(scanSummary(), pageUris = listOf(testPageUri()))
                activity.chooseNormalPdfDestinationForTest()
                activity.discardForTest()
                activity.deliverNormalPdfDestinationForTest(testDestinationUri("stale.pdf"))

                activity.restoreCompletedSessionForTest(scanSummary(), pageUris = listOf(testPageUri()))
                activity.exportPagesForTest()
                activity.discardForTest()
                activity.deliverPageDestinationForTest(testDestinationUri("stale.jpg"))
            }

            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertEquals(0, writeCount.get())
            assertEquals(0, exportCount.get())
        }
    }

    @Test
    fun oldNormalPdfCompletionCannotOverwriteANewSession() {
        val gate = ControllableOperationGate()
        val replacement = scanSummary(pageCount = 2)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.replaceNormalOperationsForTest(
                    writeNormalPdf = { _, _ ->
                        gate.await()
                        PdfExportResult.Success
                    },
                    onNormalPdfDestinationRequested = {},
                )
                activity.restoreCompletedSessionForTest(scanSummary(), pageUris = listOf(testPageUri()))
                activity.chooseNormalPdfDestinationForTest()
                activity.deliverNormalPdfDestinationForTest(testDestinationUri("old.pdf"))
            }

            gate.awaitReached()
            scenario.onActivity { activity ->
                activity.discardForTest()
                activity.restoreCompletedSessionForTest(
                    replacement,
                    pageUris = listOf(testPageUri("new-1"), testPageUri("new-2")),
                )
            }
            gate.release()
            gate.awaitExited()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            scenario.onActivity { activity ->
                assertEquals(replacement, activity.sessionSummaryForTest())
                assertEquals(PdfSaveState.Idle, activity.normalPdfStateForTest())
            }
        }
    }

    @Test
    fun filterMutationWhileNormalPdfSaveIsPausedInvalidatesItsCompletion() {
        val gate = ControllableOperationGate()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.replaceNormalOperationsForTest(
                    writeNormalPdf = { _, _ ->
                        gate.await()
                        PdfExportResult.Success
                    },
                    onNormalPdfDestinationRequested = {},
                )
                activity.restoreCompletedSessionForTest(scanSummary(), pageUris = listOf(testPageUri()))
                activity.chooseNormalPdfDestinationForTest()
                activity.deliverNormalPdfDestinationForTest(testDestinationUri("mutated-save.pdf"))
            }

            gate.awaitReached()
            scenario.onActivity { activity ->
                assertTrue(activity.setFirstPageFilterForTest(DocumentFilter.GRAYSCALE))
            }
            gate.release()
            gate.awaitExited()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            scenario.onActivity { activity ->
                assertEquals(PdfSaveState.Idle, activity.normalPdfStateForTest())
            }
        }
    }

    @Test
    fun filterMutationWhileSharePreparationIsPausedPreventsChooserAndCleansOutput() {
        val gate = ControllableOperationGate()
        val chooserCount = AtomicInteger(0)
        val deleteCount = AtomicInteger(0)
        val deleted = CountDownLatch(1)
        val temporary = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "mutated-share-${System.nanoTime()}.pdf",
        ).apply { writeBytes(byteArrayOf(0x50, 0x44, 0x46)) }

        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.replaceNormalOperationsForTest(
                        preparePdfShare = {
                            gate.await()
                            PdfSharePreparationResult.Ready(
                                uri = testDestinationUri("mutated-share.pdf"),
                                temporaryFile = PreparedPdfShareFile(temporary) { file ->
                                    deleteCount.incrementAndGet()
                                    file.delete().also { deleted.countDown() }
                                },
                            )
                        },
                        onPdfShareRequested = { chooserCount.incrementAndGet() },
                    )
                    activity.restoreCompletedSessionForTest(scanSummary(), pageUris = listOf(testPageUri()))
                    activity.sharePdfForTest()
                }

                gate.awaitReached()
                scenario.onActivity { activity ->
                    assertTrue(activity.setFirstPageFilterForTest(DocumentFilter.HIGH_CONTRAST))
                }
                gate.release()
                gate.awaitExited()
                assertTrue("Stale share output was not deleted", deleted.await(10, TimeUnit.SECONDS))
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()

                assertEquals(0, chooserCount.get())
                assertEquals(1, deleteCount.get())
                assertFalse(temporary.exists())
                scenario.onActivity { activity ->
                    assertEquals(PdfShareState.Idle, activity.pdfShareStateForTest())
                }
            }
        } finally {
            temporary.delete()
        }
    }

    @Test
    fun filterMutationBetweenPageDestinationCallbacksMakesTheSecondCallbackStale() {
        val destinationRequests = AtomicInteger(0)
        val secondDestinationRequested = CountDownLatch(1)
        val exportCount = AtomicInteger(0)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.replaceNormalOperationsForTest(
                    exportPage = { _, _ ->
                        exportCount.incrementAndGet()
                        PageExportResult.Success
                    },
                    onPageDestinationRequested = {
                        if (destinationRequests.incrementAndGet() == 2) {
                            secondDestinationRequested.countDown()
                        }
                    },
                )
                activity.restoreCompletedSessionForTest(
                    scanSummary(pageCount = 2),
                    pageUris = listOf(testPageUri("first"), testPageUri("second")),
                )
                activity.exportPagesForTest()
                activity.deliverPageDestinationForTest(testDestinationUri("first.jpg"))
            }

            assertTrue(
                "Second destination was not requested",
                secondDestinationRequested.await(10, TimeUnit.SECONDS),
            )
            scenario.onActivity { activity ->
                assertTrue(activity.setFirstPageFilterForTest(DocumentFilter.AUTO_ENHANCE))
                activity.deliverPageDestinationForTest(testDestinationUri("stale-second.jpg"))
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            assertEquals(2, destinationRequests.get())
            assertEquals(1, exportCount.get())
            scenario.onActivity { activity ->
                assertEquals(PageExportState.Idle, activity.pageExportStateForTest())
            }
        }
    }

    @Test
    fun ocrDiscardDefersOwnedSourceDeletionUntilTheReaderExits() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.cacheDir, "owned-ocr-${System.nanoTime()}.jpg")
            .apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val gate = ControllableOperationGate()
        val leaseReleased = CountDownLatch(1)
        val releaseCount = AtomicInteger(0)

        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.observeDocumentSessionLeaseReleaseForTest {
                        releaseCount.incrementAndGet()
                        leaseReleased.countDown()
                    }
                    activity.replaceOperationsForTest(
                        ocrEngine = object : OcrEngine {
                            override fun recognize(pages: List<OcrPage>): OcrResult =
                                pages.single().openJpegStream().use {
                                    gate.await()
                                    ocrResult("owned-ocr")
                                }
                        },
                    )
                    activity.installDocumentSessionForTest(ownedSession(source))
                    activity.recognizeTextForTest()
                }

                gate.awaitReached()
                scenario.onActivity { it.discardForTest() }
                assertTrue("Owned OCR source was deleted while still leased", source.exists())

                gate.release()
                gate.awaitExited()
                assertTrue("OCR lease was not released", leaseReleased.await(10, TimeUnit.SECONDS))
                assertEquals(1, releaseCount.get())
                assertFalse("Owned OCR source was not deleted after lease release", source.exists())
            }
        } finally {
            source.delete()
        }
    }

    @Test
    fun searchablePdfDiscardDefersOwnedSourceDeletionUntilTheReaderExits() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.cacheDir, "owned-searchable-${System.nanoTime()}.jpg")
            .apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val gate = ControllableOperationGate()
        val leaseReleased = CountDownLatch(1)
        val releaseCount = AtomicInteger(0)
        val coordinator = object : SearchablePdfExportCoordinator {
            override suspend fun prepare(
                request: SearchablePdfExportRequest,
            ): SearchablePdfPreparedExport = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(request.pageUris.single())!!.use {
                    gate.await()
                    SearchablePdfPreparedExport.Failure(SearchablePdfPreparationError.GENERATION_FAILED)
                }
            }

            override suspend fun writePreparedExport(
                preparedExport: SearchablePdfPreparedExport,
                destinationUri: Uri,
            ): SearchablePdfExportResult = error("No prepared export is expected")

            override fun discardPreparedExport(preparedExport: SearchablePdfPreparedExport) = Unit
        }

        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.observeDocumentSessionLeaseReleaseForTest {
                        releaseCount.incrementAndGet()
                        leaseReleased.countDown()
                    }
                    activity.replaceOperationsForTest(searchablePdfExportCoordinator = coordinator)
                    activity.installDocumentSessionForTest(ownedSession(source))
                    activity.saveSearchablePdfForTest()
                }

                gate.awaitReached()
                scenario.onActivity { it.discardForTest() }
                assertTrue("Owned searchable-PDF source was deleted while still leased", source.exists())

                gate.release()
                gate.awaitExited()
                assertTrue("Searchable-PDF lease was not released", leaseReleased.await(10, TimeUnit.SECONDS))
                assertEquals(1, releaseCount.get())
                assertFalse("Owned searchable-PDF source was not deleted after lease release", source.exists())
            }
        } finally {
            source.delete()
        }
    }

    @Test
    fun ocrExceptionReleasesOwnedSourceLease() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.cacheDir, "owned-ocr-error-${System.nanoTime()}.jpg")
            .apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val terminal = CountDownLatch(1)
        val leaseReleased = CountDownLatch(1)

        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.observeDocumentSessionLeaseReleaseForTest(leaseReleased::countDown)
                    activity.replaceOperationsForTest(
                        ocrEngine = object : OcrEngine {
                            override fun recognize(pages: List<OcrPage>): OcrResult =
                                pages.single().openJpegStream().use {
                                    error("deterministic OCR failure")
                                }
                        },
                        onOcrTerminalState = terminal::countDown,
                    )
                    activity.installDocumentSessionForTest(ownedSession(source))
                    activity.recognizeTextForTest()
                }

                assertTrue("OCR failure was not delivered", terminal.await(10, TimeUnit.SECONDS))
                assertTrue("OCR exception did not release its lease", leaseReleased.await(10, TimeUnit.SECONDS))
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity { it.discardForTest() }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                assertFalse("OCR exception stranded the source lease", source.exists())
            }
        } finally {
            source.delete()
        }
    }

    @Test
    fun searchablePdfExceptionReleasesOwnedSourceLease() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.cacheDir, "owned-searchable-error-${System.nanoTime()}.jpg")
            .apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val failed = CountDownLatch(1)
        val leaseReleased = CountDownLatch(1)
        val coordinator = object : SearchablePdfExportCoordinator {
            override suspend fun prepare(
                request: SearchablePdfExportRequest,
            ): SearchablePdfPreparedExport = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(request.pageUris.single())!!.use {
                    failed.countDown()
                    error("deterministic searchable PDF failure")
                }
            }

            override suspend fun writePreparedExport(
                preparedExport: SearchablePdfPreparedExport,
                destinationUri: Uri,
            ): SearchablePdfExportResult = error("No prepared export is expected")

            override fun discardPreparedExport(preparedExport: SearchablePdfPreparedExport) = Unit
        }

        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.observeDocumentSessionLeaseReleaseForTest(leaseReleased::countDown)
                    activity.replaceOperationsForTest(searchablePdfExportCoordinator = coordinator)
                    activity.installDocumentSessionForTest(ownedSession(source))
                    activity.saveSearchablePdfForTest()
                }

                assertTrue("Searchable-PDF failure did not run", failed.await(10, TimeUnit.SECONDS))
                assertTrue(
                    "Searchable-PDF exception did not release its lease",
                    leaseReleased.await(10, TimeUnit.SECONDS),
                )
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity { activity ->
                    assertTrue(activity.searchablePdfStateForTest() is SearchablePdfSaveState.Error)
                    activity.discardForTest()
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                assertFalse("Searchable-PDF exception stranded the source lease", source.exists())
            }
        } finally {
            source.delete()
        }
    }

    @Test
    fun discardNeverDeletesAnExternalSource() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.cacheDir, "external-source-${System.nanoTime()}.jpg")
            .apply { writeBytes(byteArrayOf(1, 2, 3)) }

        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.restoreCompletedSessionForTest(
                        scanSummary(),
                        pageUris = listOf(Uri.fromFile(source)),
                    )
                    activity.discardForTest()
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                assertTrue("External source was deleted", source.exists())
            }
        } finally {
            source.delete()
        }
    }

    @Test
    fun normalDocumentOperationsStillCompleteSuccessfully() {
        val saveCompleted = CountDownLatch(1)
        val shareLaunched = CountDownLatch(1)
        val pageCompleted = CountDownLatch(1)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.replaceNormalOperationsForTest(
                    writeNormalPdf = { _, _ ->
                        saveCompleted.countDown()
                        PdfExportResult.Success
                    },
                    preparePdfShare = {
                        PdfSharePreparationResult.Ready(testDestinationUri("ready.pdf"))
                    },
                    exportPage = { _, _ ->
                        pageCompleted.countDown()
                        PageExportResult.Success
                    },
                    onNormalPdfDestinationRequested = {},
                    onPageDestinationRequested = {},
                    onPdfShareRequested = { shareLaunched.countDown() },
                )
                activity.restoreCompletedSessionForTest(scanSummary(), pageUris = listOf(testPageUri()))
                activity.chooseNormalPdfDestinationForTest()
                activity.deliverNormalPdfDestinationForTest(testDestinationUri("saved.pdf"))
            }

            assertTrue(saveCompleted.await(10, TimeUnit.SECONDS))
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                assertEquals(PdfSaveState.Saved, activity.normalPdfStateForTest())
                activity.sharePdfForTest()
            }
            assertTrue(shareLaunched.await(10, TimeUnit.SECONDS))
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                assertEquals(PdfShareState.Idle, activity.pdfShareStateForTest())
                activity.exportPagesForTest()
                activity.deliverPageDestinationForTest(testDestinationUri("page.jpg"))
            }
            assertTrue(pageCompleted.await(10, TimeUnit.SECONDS))
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                assertTrue(activity.pageExportStateForTest() is PageExportState.Completed)
            }
        }
    }

    private fun gatedCoordinator(
        gate: ControllableOperationGate,
        onCreated: (File) -> Unit = {},
        onDeleted: () -> Unit = {},
    ) = LocalSearchablePdfExportCoordinator(
        context = InstrumentationRegistry.getInstrumentation().targetContext,
        ocrEngine = object : OcrEngine {
            override fun recognize(pages: List<OcrPage>): OcrResult =
                error("Existing deterministic OCR must be supplied")
        },
        generator = GatedGenerator(gate, onCreated),
        deleteTemporaryFile = { file ->
            file.delete().also { onDeleted() }
        },
    )

    private fun scanSummary(pageCount: Int = 1) = ScannerSpikeState.ResultSummary(
        jpegPageCount = pageCount,
        hasPdf = true,
        pdfPageCount = pageCount,
    )

    private fun testPageUri(path: String = "page"): Uri =
        Uri.parse("content://org.synapseworks.pageharbor.test/$path")

    private fun testDestinationUri(path: String): Uri =
        Uri.parse("content://org.synapseworks.pageharbor.test/destination/$path")

    private fun ownedSession(file: File): DocumentSession = DocumentSession(
        pages = listOf(
            DocumentPage(
                id = DocumentPageId(1L),
                source = DocumentResource(
                    reference = Uri.fromFile(file).toString(),
                    ownership = DocumentResourceOwnership.RME_OWNED_TEMPORARY,
                    ownedTemporaryFile = OwnedTemporaryFile(
                        path = file.absolutePath,
                        rootPath = file.parentFile!!.absolutePath,
                    ),
                ),
                sourceCategory = DocumentSourceCategory.RENDERED_PDF_PAGE,
            ),
        ),
    )

    private fun ocrResult(token: String) = OcrResult(listOf(OcrPageResult(pageIndex = 0, text = token)))

    private class GatedOcrEngine(
        private val gate: ControllableOperationGate,
        private val result: OcrResult,
    ) : OcrEngine {
        override fun recognize(pages: List<OcrPage>): OcrResult {
            gate.await()
            return result
        }
    }

    private class GatedGenerator(
        private val gate: ControllableOperationGate,
        private val onCreated: (File) -> Unit,
    ) : SearchablePdfGenerator {
        override suspend fun generate(request: SearchablePdfRequest): SearchablePdfGenerationResult =
            withContext(Dispatchers.IO) {
            request.outputFile.writeBytes(byteArrayOf(0x50, 0x44, 0x46))
            onCreated(request.outputFile)
            gate.await()
            SearchablePdfGenerationResult.Success(
                pageCount = request.pages.size,
                textLayerPageCount = 1,
            )
        }
    }
}

private class ControllableOperationGate {
    private val reached = CountDownLatch(1)
    private val released = CountDownLatch(1)
    private val exited = CountDownLatch(1)

    fun await() {
        reached.countDown()
        try {
            check(released.await(10, TimeUnit.SECONDS)) { "Test gate release timed out" }
        } finally {
            exited.countDown()
        }
    }

    fun awaitReached() {
        assertTrue("Operation did not reach the test gate", reached.await(10, TimeUnit.SECONDS))
    }

    fun release() = released.countDown()

    fun awaitExited() {
        assertTrue("Operation did not leave the test gate", exited.await(10, TimeUnit.SECONDS))
    }
}
