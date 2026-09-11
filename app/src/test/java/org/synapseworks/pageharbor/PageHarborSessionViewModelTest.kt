package org.synapseworks.pageharbor

import androidx.lifecycle.ViewModelStore
import org.junit.Assert.assertEquals
import org.junit.Test
import org.synapseworks.pageharbor.document.searchablepdf.SearchablePdfSaveState
import org.synapseworks.pageharbor.document.session.AcquiredResource
import org.synapseworks.pageharbor.document.session.DocumentAcquisitionCoordinator
import org.synapseworks.pageharbor.document.session.DocumentAcquisitionError
import org.synapseworks.pageharbor.document.session.DocumentImageMetadata
import org.synapseworks.pageharbor.document.session.DocumentPage
import org.synapseworks.pageharbor.document.session.DocumentPageId
import org.synapseworks.pageharbor.document.session.DocumentPageRotation
import org.synapseworks.pageharbor.document.session.DocumentResource
import org.synapseworks.pageharbor.document.session.DocumentResourceCleaner
import org.synapseworks.pageharbor.document.session.DocumentResourceOwnership
import org.synapseworks.pageharbor.document.session.DocumentSourceCategory
import org.synapseworks.pageharbor.document.session.DocumentSession
import org.synapseworks.pageharbor.document.session.OwnedTemporaryFile
import org.synapseworks.pageharbor.document.session.PendingResourceRegistrationResult
import org.synapseworks.pageharbor.image.DocumentFilter
import org.synapseworks.pageharbor.ocr.OcrPageResult
import org.synapseworks.pageharbor.ocr.OcrResult
import org.synapseworks.pageharbor.ocr.OcrUiState
import org.synapseworks.pageharbor.scanner.ScannerSpikeState
import org.synapseworks.pageharbor.ui.PageHarborScreen

class PageHarborSessionViewModelTest {
    @Test
    fun effectiveDocumentMutationsAdvanceRevisionAndNoOpsDoNot() {
        val session = completedSession("first", "second")
        val initialRevision = session.documentRevision
        val first = session.documentPages.first()
        val originalOrder = session.documentPages.map { it.id.value }

        assertEquals(false, session.setPageFilter(first.id.value, DocumentFilter.ORIGINAL))
        assertEquals(false, session.setPageFilter(Long.MAX_VALUE, DocumentFilter.GRAYSCALE))
        assertEquals(false, session.rotatePageClockwise(Long.MAX_VALUE))
        assertEquals(false, session.reorderPages(originalOrder))
        assertEquals(false, session.reorderPages(emptyList()))
        assertEquals(initialRevision, session.documentRevision)

        assertEquals(true, session.setPageFilter(first.id.value, DocumentFilter.GRAYSCALE))
        assertEquals(initialRevision + 1L, session.documentRevision)
        assertEquals(true, session.rotatePageClockwise(first.id.value))
        assertEquals(initialRevision + 2L, session.documentRevision)
        assertEquals(true, session.reorderPages(originalOrder.reversed()))
        assertEquals(initialRevision + 3L, session.documentRevision)
    }

    @Test
    fun successfulAcquisitionAndClearAdvanceDocumentRevision() {
        val session = completedSession("first")
        val initialRevision = session.documentRevision

        assertEquals(ScannerRequestMode.ADD_PAGES, session.beginScannerRequest())
        session.completeScannerReferencesForTest(
            scanSummary(pageCount = 1),
            scannedPageReferences = listOf("second"),
        )
        assertEquals(initialRevision + 1L, session.documentRevision)

        session.clearScan()
        assertEquals(initialRevision + 2L, session.documentRevision)
    }

    @Test
    fun discardDefersOwnedResourceCleanupUntilTheLastOperationLeaseEnds() {
        val cleaner = RecordingCleaner()
        val viewModel = PageHarborSessionViewModel(
            DocumentAcquisitionCoordinator(resourceCleaner = cleaner),
        )
        viewModel.installDocumentSessionForTest(ownedSession("leased"))
        val first = requireNotNull(viewModel.acquireDocumentSessionLease())
        val second = requireNotNull(viewModel.acquireDocumentSessionLease())

        viewModel.clearScan()
        assertEquals(emptyList<String>(), cleaner.deletedReferences)

        viewModel.releaseDocumentSessionLease(first)
        assertEquals(emptyList<String>(), cleaner.deletedReferences)

        viewModel.releaseDocumentSessionLease(second)
        viewModel.releaseDocumentSessionLease(second)
        assertEquals(listOf("leased"), cleaner.deletedReferences)
    }

    @Test
    fun viewModelClearDefersOwnedResourceCleanupUntilBlockingOperationReleasesItsLease() {
        val cleaner = RecordingCleaner()
        val viewModel = PageHarborSessionViewModel(
            DocumentAcquisitionCoordinator(resourceCleaner = cleaner),
        )
        viewModel.installDocumentSessionForTest(ownedSession("final"))
        val lease = requireNotNull(viewModel.acquireDocumentSessionLease())
        val store = ViewModelStore().apply { put("session", viewModel) }

        store.clear()
        assertEquals(emptyList<String>(), cleaner.deletedReferences)

        viewModel.releaseDocumentSessionLease(lease)
        assertEquals(listOf("final"), cleaner.deletedReferences)
    }

    @Test
    fun replacementDefersOwnedResourceCleanupUntilTheCapturedSessionLeaseEnds() {
        val cleaner = RecordingCleaner()
        val viewModel = PageHarborSessionViewModel(
            DocumentAcquisitionCoordinator(resourceCleaner = cleaner),
        )
        viewModel.installDocumentSessionForTest(ownedSession("replaced"))
        val lease = requireNotNull(viewModel.acquireDocumentSessionLease())

        viewModel.replaceScannerReferencesForTest(
            scannerState = scanSummary(pageCount = 1),
            scannedPageReferences = listOf("replacement"),
        )
        assertEquals(emptyList<String>(), cleaner.deletedReferences)

        viewModel.releaseDocumentSessionLease(lease)
        assertEquals(listOf("replaced"), cleaner.deletedReferences)
    }

    @Test
    fun viewModelClearCleansRegisteredPendingAcquisitionResources() {
        val cleaner = RecordingCleaner()
        val session = PageHarborSessionViewModel(
            DocumentAcquisitionCoordinator(resourceCleaner = cleaner),
        )
        assertEquals(ScannerRequestMode.INITIAL_SCAN, session.beginScannerRequest())
        assertEquals(
            PendingResourceRegistrationResult.Registered,
            session.registerPendingAcquisitionResources(
                listOf(
                    AcquiredResource(
                        reference = "pending",
                        ownership = DocumentResourceOwnership.RME_OWNED_TEMPORARY,
                        ownedTemporaryFile = OwnedTemporaryFile(
                            path = "/private/rme/pending",
                            rootPath = "/private/rme",
                        ),
                    ),
                ),
            ),
        )
        val store = ViewModelStore()
        store.put("session", session)

        store.clear()

        assertEquals(listOf("pending"), cleaner.deletedReferences)
    }

    @Test
    fun newPagesDefaultToOriginalAndChangingOneDoesNotAffectAnother() {
        val session = completedSession("first", "second")
        val first = session.documentPages[0]
        val second = session.documentPages[1]

        assertEquals(DocumentFilter.ORIGINAL, first.filter)
        assertEquals(DocumentFilter.ORIGINAL, second.filter)
        assertEquals(true, session.setPageFilter(first.id.value, DocumentFilter.GRAYSCALE))
        assertEquals(DocumentFilter.GRAYSCALE, session.documentPages[0].filter)
        assertEquals(DocumentFilter.ORIGINAL, session.documentPages[1].filter)
    }

    @Test
    fun scannerSourceByteMetadataReachesTheSharedSessionAndEnforcesTheLimit() {
        val accepted = PageHarborSessionViewModel()
        accepted.beginScannerRequest()
        accepted.completeScannerReferencesForTest(
            scannerState = scanSummary(pageCount = 1),
            scannedPageReferences = listOf("scanner-page"),
            scannedPageMetadata = listOf(DocumentImageMetadata(sourceByteCount = 1024L)),
        )

        assertEquals(1024L, accepted.documentPages.single().imageMetadata.sourceByteCount)

        val rejected = PageHarborSessionViewModel()
        rejected.beginScannerRequest()
        rejected.completeScannerReferencesForTest(
            scannerState = scanSummary(pageCount = 1),
            scannedPageReferences = listOf("oversized-scanner-page"),
            scannedPageMetadata = listOf(DocumentImageMetadata(sourceByteCount = 64L * 1024L * 1024L + 1L)),
        )

        assertEquals(emptyList<DocumentPage>(), rejected.documentPages)
        assertEquals(DocumentAcquisitionError.SOURCE_TOO_LARGE, rejected.lastAcquisitionError)
    }

    @Test
    fun reorderKeepsTheFilterWithItsStablePageIdentity() {
        val session = completedSession("first", "second", "third")
        val first = session.documentPages[0]
        val second = session.documentPages[1]
        val third = session.documentPages[2]
        session.setPageFilter(second.id.value, DocumentFilter.BLACK_AND_WHITE)

        assertEquals(true, session.reorderPages(listOf(third.id.value, second.id.value, first.id.value)))
        assertEquals(listOf(third.id, second.id, first.id), session.documentPages.map(DocumentPage::id))
        assertEquals(DocumentFilter.BLACK_AND_WHITE, session.documentPages[1].filter)
    }

    @Test
    fun addedPagesDefaultToOriginalWithoutChangingExistingSelections() {
        val session = completedSession("first")
        val existing = session.documentPages.single()
        session.setPageFilter(existing.id.value, DocumentFilter.HIGH_CONTRAST)
        session.beginScannerRequest()
        session.completeScannerReferencesForTest(
            scanSummary(pageCount = 1),
            scannedPageReferences = listOf("second"),
        )

        assertEquals(listOf(DocumentFilter.HIGH_CONTRAST, DocumentFilter.ORIGINAL),
            session.documentPages.map(DocumentPage::filter))
    }

    @Test
    fun revertingAndInvalidPageIdentifiersAreSafe() {
        val session = completedSession("first")
        val page = session.documentPages.single()
        session.setPageFilter(page.id.value, DocumentFilter.AUTO_ENHANCE)

        assertEquals(true, session.setPageFilter(page.id.value, DocumentFilter.ORIGINAL))
        assertEquals(DocumentFilter.ORIGINAL, session.documentPages.single().filter)
        assertEquals(false, session.setPageFilter(Long.MAX_VALUE, DocumentFilter.GRAYSCALE))
        assertEquals(false, session.reorderPages(emptyList()))
    }

    @Test
    fun recreationRetainsActivePageFilters() {
        val session = completedSession("first")
        val page = session.documentPages.single()
        session.setPageFilter(page.id.value, DocumentFilter.BLACK_AND_WHITE)

        session.resetTransientStateForRecreation()

        assertEquals(DocumentFilter.BLACK_AND_WHITE, session.documentPages.single().filter)
    }

    @Test
    fun scannerPagesEnterTheSharedSessionAndRotationKeepsIdentity() {
        val session = completedSession("first")
        val page = session.documentPages.single()
        session.ocrUiState = OcrUiState.Success(
            OcrResult(listOf(OcrPageResult(pageIndex = 0, text = "Old orientation"))),
        )

        assertEquals(true, session.rotatePageClockwise(page.id.value))

        assertEquals(page.id, session.documentPages.single().id)
        assertEquals(DocumentSourceCategory.SCAN, session.documentPages.single().sourceCategory)
        assertEquals(DocumentPageRotation.DEGREES_90, session.documentPages.single().rotation)
        assertEquals(OcrUiState.Idle, session.ocrUiState)
    }

    @Test
    fun returningFromOcrKeepsTheActiveScanAndItsOrderedPages() {
        val session = completedSession("first", "second")
        session.ocrUiState = OcrUiState.Success(
            OcrResult(listOf(OcrPageResult(pageIndex = 0, text = "Recognized"))),
        )
        session.screen = PageHarborScreen.OcrResult

        session.returnToScanResult()

        assertEquals(PageHarborScreen.ScanResult, session.screen)
        assertEquals(scanSummary(pageCount = 2), session.scannerState)
        assertEquals(listOf("first", "second"), session.pageReferences())
    }

    @Test
    fun cancelledAddPagesRequestKeepsTheActiveScanUnchanged() {
        val session = completedSession("first", "second")
        session.screen = PageHarborScreen.Home

        assertEquals(ScannerRequestMode.ADD_PAGES, session.beginScannerRequest())
        session.cancelScannerRequest()

        assertEquals(PageHarborScreen.ScanResult, session.screen)
        assertEquals(scanSummary(pageCount = 2), session.scannerState)
        assertEquals(listOf("first", "second"), session.pageReferences())
    }

    @Test
    fun successfulAddPagesRequestAppendsPagesInOrder() {
        val session = completedSession("first", "second")

        assertEquals(ScannerRequestMode.ADD_PAGES, session.beginScannerRequest())
        session.completeScannerReferencesForTest(
            scannerState = scanSummary(pageCount = 2),
            scannedPageReferences = listOf("third", "fourth"),
        )

        assertEquals(PageHarborScreen.ScanResult, session.screen)
        assertEquals(scanSummary(pageCount = 4, pdfPageCount = 2), session.scannerState)
        assertEquals(listOf("first", "second", "third", "fourth"), session.pageReferences())
    }

    @Test
    fun twentyPageScanRetainsOrderFiltersAndConfigurationState() {
        val tokens = (1..MAX_DOCUMENT_PAGES).map { "page-$it" }
        val session = completedSession(*tokens.toTypedArray())
        val first = session.documentPages.first()
        val twentieth = session.documentPages.last()

        assertEquals(MAX_DOCUMENT_PAGES, session.documentPages.size)
        assertEquals((0L until MAX_DOCUMENT_PAGES).toList(), session.documentPages.map { it.id.value })
        assertEquals(true, session.setPageFilter(first.id.value, DocumentFilter.GRAYSCALE))
        assertEquals(true, session.setPageFilter(twentieth.id.value, DocumentFilter.HIGH_CONTRAST))
        assertEquals(true, session.reorderPages(session.documentPages.map { it.id.value }.reversed()))

        session.resetTransientStateForRecreation()

        assertEquals(twentieth.id, session.documentPages.first().id)
        assertEquals(DocumentFilter.HIGH_CONTRAST, session.documentPages.first().filter)
        assertEquals(first.id, session.documentPages.last().id)
        assertEquals(DocumentFilter.GRAYSCALE, session.documentPages.last().filter)
        assertEquals(0, session.remainingPageCapacity())
    }

    @Test
    fun addPagesUsesOnlyTheRemainingSessionCapacity() {
        listOf(10 to 10, 15 to 5, 19 to 1).forEach { (existingCount, addedCount) ->
            val session = completedSession(*pageTokens(existingCount).toTypedArray())

            assertEquals(MAX_DOCUMENT_PAGES - existingCount, session.remainingPageCapacity())
            assertEquals(ScannerRequestMode.ADD_PAGES, session.beginScannerRequest())
            session.completeScannerReferencesForTest(
                scannerState = scanSummary(pageCount = addedCount),
                scannedPageReferences = pageTokens(addedCount, startAt = existingCount + 1),
            )

            assertEquals(MAX_DOCUMENT_PAGES, session.documentPages.size)
            assertEquals(MAX_DOCUMENT_PAGES, (session.scannerState as ScannerSpikeState.ResultSummary).jpegPageCount)
        }
    }

    @Test
    fun addPagesRejectsAnExternalResultThatExceedsTheSessionMaximum() {
        val session = completedSession(*pageTokens(19).toTypedArray())
        val before = session.documentPages

        assertEquals(ScannerRequestMode.ADD_PAGES, session.beginScannerRequest())
        session.completeScannerReferencesForTest(
            scannerState = scanSummary(pageCount = 2),
            scannedPageReferences = listOf("page-20", "page-21"),
        )

        assertEquals(before, session.documentPages)
        assertEquals(19, (session.scannerState as ScannerSpikeState.ResultSummary).jpegPageCount)
        assertEquals(DocumentAcquisitionError.PAGE_LIMIT_EXCEEDED, session.lastAcquisitionError)
    }

    @Test
    fun addPagesIsBlockedWhenTheSessionAlreadyHasTwentyPages() {
        val session = completedSession(*pageTokens(MAX_DOCUMENT_PAGES).toTypedArray())
        val pagesBefore = session.documentPages

        assertEquals(null, session.beginScannerRequest())
        assertEquals(pagesBefore, session.documentPages)
        assertEquals(0, session.remainingPageCapacity())
    }

    @Test
    fun anUnexpectedOversizedInitialResultIsRejectedWithATypedError() {
        val session = PageHarborSessionViewModel()

        session.replaceScannerReferencesForTest(
            scannerState = scanSummary(pageCount = MAX_DOCUMENT_PAGES + 1),
            scannedPageReferences = pageTokens(MAX_DOCUMENT_PAGES + 1),
        )

        assertEquals(emptyList<DocumentPage>(), session.documentPages)
        assertEquals(ScannerSpikeState.Error, session.scannerState)
        assertEquals(DocumentAcquisitionError.PAGE_LIMIT_EXCEEDED, session.lastAcquisitionError)
        assertEquals(null, session.scannedPdfUri)
    }

    @Test
    fun cancelledInitialScanDoesNotCreateAnEmptyScanResult() {
        val session = PageHarborSessionViewModel()

        assertEquals(ScannerRequestMode.INITIAL_SCAN, session.beginScannerRequest())
        session.cancelScannerRequest()

        assertEquals(PageHarborScreen.Home, session.screen)
        assertEquals(ScannerSpikeState.Cancelled, session.scannerState)
        assertEquals(emptyList<DocumentPage>(), session.documentPages)
    }

    @Test
    fun repeatedAddPagesCancellationNeverMutatesTheActiveScan() {
        val session = completedSession("first", "second")

        repeat(2) {
            assertEquals(ScannerRequestMode.ADD_PAGES, session.beginScannerRequest())
            session.cancelScannerRequest()
        }

        assertEquals(scanSummary(pageCount = 2), session.scannerState)
        assertEquals(listOf("first", "second"), session.pageReferences())
    }

    @Test
    fun addPagesRequestWithoutScannerContentKeepsTheActiveScanUnchanged() {
        val session = completedSession("first", "second")

        assertEquals(ScannerRequestMode.ADD_PAGES, session.beginScannerRequest())
        session.completeScannerRequestWithoutResult()

        assertEquals(PageHarborScreen.ScanResult, session.screen)
        assertEquals(scanSummary(pageCount = 2), session.scannerState)
        assertEquals(listOf("first", "second"), session.pageReferences())
    }

    @Test
    fun returningFromOcrLeavesTheScanUsableForAnotherOcrResult() {
        val session = completedSession("first")
        session.ocrUiState = OcrUiState.Success(
            OcrResult(listOf(OcrPageResult(pageIndex = 0, text = "Recognized"))),
        )
        session.screen = PageHarborScreen.OcrResult

        session.returnToScanResult()
        session.screen = PageHarborScreen.OcrResult

        assertEquals(PageHarborScreen.OcrResult, session.screen)
        assertEquals(listOf("first"), session.pageReferences())
        assertEquals(scanSummary(pageCount = 1), session.scannerState)
    }

    @Test
    fun addPagesErrorDoesNotDestroyTheActiveScan() {
        val session = completedSession("first", "second")

        assertEquals(ScannerRequestMode.ADD_PAGES, session.beginScannerRequest())
        session.failScannerRequest()

        assertEquals(PageHarborScreen.ScanResult, session.screen)
        assertEquals(scanSummary(pageCount = 2), session.scannerState)
        assertEquals(listOf("first", "second"), session.pageReferences())
    }

    @Test
    fun completedScanIsRetainedOnTheScanResultScreen() {
        val session = PageHarborSessionViewModel()
        val summary = scanSummary(pageCount = 2)

        session.replaceScannerReferencesForTest(summary, scannedPageReferences = pageTokens(2))

        assertEquals(PageHarborScreen.ScanResult, session.screen)
        assertEquals(summary, session.scannerState)
    }

    @Test
    fun completedOcrResultRemainsAvailableAfterRecreationReset() {
        val session = PageHarborSessionViewModel()
        val result = OcrResult(listOf(OcrPageResult(pageIndex = 0, text = "Retained test text")))
        session.replaceScannerReferencesForTest(
            scanSummary(pageCount = 1),
            scannedPageReferences = pageTokens(1),
        )
        session.ocrUiState = OcrUiState.Success(result)
        session.screen = PageHarborScreen.OcrResult

        session.resetTransientStateForRecreation()

        assertEquals(PageHarborScreen.OcrResult, session.screen)
        assertEquals(OcrUiState.Success(result), session.ocrUiState)
    }

    @Test
    fun newScanReplacesOldOcrStateAndRestoresScanResult() {
        val session = PageHarborSessionViewModel()
        session.replaceScannerReferencesForTest(
            scanSummary(pageCount = 1),
            scannedPageReferences = pageTokens(1),
        )
        session.ocrUiState = OcrUiState.Success(
            OcrResult(listOf(OcrPageResult(pageIndex = 0, text = "Old text"))),
        )
        val replacement = scanSummary(pageCount = 3)

        session.replaceScannerReferencesForTest(replacement, scannedPageReferences = pageTokens(3))

        assertEquals(PageHarborScreen.ScanResult, session.screen)
        assertEquals(replacement, session.scannerState)
        assertEquals(OcrUiState.Idle, session.ocrUiState)
        assertEquals(0, session.ocrSelectedPageIndex)
    }

    @Test
    fun discardClearsTheInMemorySession() {
        val session = PageHarborSessionViewModel()
        session.replaceScannerReferencesForTest(
            scanSummary(pageCount = 1),
            scannedPageReferences = pageTokens(1),
        )
        session.ocrUiState = OcrUiState.Success(
            OcrResult(listOf(OcrPageResult(pageIndex = 0, text = "Discarded test text"))),
        )

        session.clearScan()

        assertEquals(PageHarborScreen.Home, session.screen)
        assertEquals(ScannerSpikeState.Idle, session.scannerState)
        assertEquals(emptyList<DocumentPage>(), session.documentPages)
        assertEquals(null, session.scannedPdfUri)
        assertEquals(OcrUiState.Idle, session.ocrUiState)
        assertEquals(0, session.ocrSelectedPageIndex)
    }

    @Test
    fun activeOperationStateResetsWithoutDiscardingTheCompletedScan() {
        val session = PageHarborSessionViewModel()
        val summary = scanSummary(pageCount = 2)
        session.replaceScannerReferencesForTest(summary, scannedPageReferences = pageTokens(2))
        session.ocrUiState = OcrUiState.Recognizing
        session.searchablePdfSaveState = SearchablePdfSaveState.Generating

        session.resetTransientStateForRecreation()

        assertEquals(PageHarborScreen.ScanResult, session.screen)
        assertEquals(summary, session.scannerState)
        assertEquals(OcrUiState.Idle, session.ocrUiState)
        assertEquals(SearchablePdfSaveState.Idle, session.searchablePdfSaveState)
    }

    @Test
    fun selectedOcrPageSurvivesRecreationButNewScanResetsIt() {
        val session = PageHarborSessionViewModel()
        session.replaceScannerReferencesForTest(
            scanSummary(pageCount = 3),
            scannedPageReferences = pageTokens(3),
        )
        session.ocrUiState = OcrUiState.Success(
            OcrResult(
                listOf(
                    OcrPageResult(pageIndex = 0, text = "First"),
                    OcrPageResult(pageIndex = 1, text = "Second"),
                    OcrPageResult(pageIndex = 2, text = "Third"),
                ),
            ),
        )
        session.screen = PageHarborScreen.OcrResult
        session.ocrSelectedPageIndex = 2

        session.resetTransientStateForRecreation()

        assertEquals(PageHarborScreen.OcrResult, session.screen)
        assertEquals(2, session.ocrSelectedPageIndex)

        session.replaceScannerReferencesForTest(
            scanSummary(pageCount = 1),
            scannedPageReferences = pageTokens(1),
        )

        assertEquals(0, session.ocrSelectedPageIndex)
    }

    private fun scanSummary(
        pageCount: Int,
        pdfPageCount: Int? = pageCount,
    ) = ScannerSpikeState.ResultSummary(
        jpegPageCount = pageCount,
        hasPdf = pdfPageCount != null,
        pdfPageCount = pdfPageCount,
    )

    private fun completedSession(vararg pageTokens: String): PageHarborSessionViewModel =
        PageHarborSessionViewModel().also { session ->
            session.replaceScannerReferencesForTest(
                scannerState = scanSummary(pageTokens.size),
                scannedPageReferences = pageTokens.toList(),
            )
        }

    private fun ownedSession(reference: String): DocumentSession = DocumentSession(
        pages = listOf(
            DocumentPage(
                id = DocumentPageId(1L),
                source = DocumentResource(
                    reference = reference,
                    ownership = DocumentResourceOwnership.RME_OWNED_TEMPORARY,
                    ownedTemporaryFile = OwnedTemporaryFile(
                        path = "/private/rme/$reference",
                        rootPath = "/private/rme",
                    ),
                ),
                sourceCategory = DocumentSourceCategory.RENDERED_PDF_PAGE,
            ),
        ),
    )

    private fun pageTokens(count: Int, startAt: Int = 1): List<String> =
        (startAt until startAt + count).map { "page-$it" }

    private fun PageHarborSessionViewModel.pageReferences(): List<String> =
        documentPages.map { page -> page.source.reference }

    private class RecordingCleaner : DocumentResourceCleaner {
        val deletedReferences = mutableListOf<String>()

        override fun delete(resource: DocumentResource) {
            deletedReferences += resource.reference
        }
    }
}
