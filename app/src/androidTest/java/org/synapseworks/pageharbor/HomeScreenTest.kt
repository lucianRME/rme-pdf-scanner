package org.synapseworks.pageharbor

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.synapseworks.pageharbor.document.PageExportState
import org.synapseworks.pageharbor.document.PdfSaveState
import org.synapseworks.pageharbor.document.PdfShareState
import org.synapseworks.pageharbor.document.searchablepdf.SearchablePdfSaveError
import org.synapseworks.pageharbor.document.searchablepdf.SearchablePdfSaveState
import org.synapseworks.pageharbor.document.session.DocumentPage
import org.synapseworks.pageharbor.document.session.DocumentPageId
import org.synapseworks.pageharbor.document.session.DocumentResource
import org.synapseworks.pageharbor.document.session.DocumentResourceOwnership
import org.synapseworks.pageharbor.document.session.DocumentSourceCategory
import org.synapseworks.pageharbor.scanner.ScannerSpikeState
import org.synapseworks.pageharbor.ocr.OcrPageError
import org.synapseworks.pageharbor.ocr.OcrPageResult
import org.synapseworks.pageharbor.ocr.OcrResult
import org.synapseworks.pageharbor.ocr.OcrUiState
import org.synapseworks.pageharbor.ui.PageHarborApp

class HomeScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun productTitleIsDisplayed() {
        composeTestRule.setContent {
            PageHarborApp()
        }

        composeTestRule.onNodeWithText("RME PDF Scanner").assertIsDisplayed()
    }

    @Test
    fun scanDocumentButtonIsDisplayedAndEnabled() {
        composeTestRule.setContent {
            PageHarborApp()
        }

        composeTestRule.onNodeWithText("Scan document")
            .assertIsDisplayed()
            .assertIsEnabled()
    }

    @Test
    fun privacyAndAboutActionsAreDisplayed() {
        composeTestRule.setContent {
            PageHarborApp()
        }

        composeTestRule.onNodeWithText("How privacy works").assertIsDisplayed()
        composeTestRule.onNodeWithText("About RME PDF Scanner").assertIsDisplayed()
    }

    @Test
    fun debugBuildLabelIsDisplayed() {
        composeTestRule.setContent {
            PageHarborApp()
        }

        composeTestRule.onNodeWithText(
            "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · " +
                "${BuildConfig.BUILD_TYPE_LABEL} · ${BuildConfig.GIT_REVISION}",
        ).assertIsDisplayed()
        assertTrue(BuildConfig.GIT_REVISION.isNotBlank())
    }

    @Test
    fun clickingScanDocumentInvokesCallback() {
        var scanClickCount = 0

        composeTestRule.setContent {
            PageHarborApp(
                onScanDocument = {
                    scanClickCount += 1
                },
            )
        }

        composeTestRule.onNodeWithText("Scan document").performClick()

        assertEquals(1, scanClickCount)
    }

    @Test
    fun preparingStateDisablesScanActionAndShowsProgress() {
        composeTestRule.setContent {
            PageHarborApp(scannerSpikeState = ScannerSpikeState.Preparing)
        }

        composeTestRule.onNodeWithText("Scan document")
            .assertIsDisplayed()
            .assertIsNotEnabled()
        composeTestRule.onNodeWithText("Preparing scanner…")
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    @Test
    fun homeScanActionRemainsReachableInANarrowShortWindowAtTwoHundredPercentFont() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                Box(modifier = androidx.compose.ui.Modifier.size(width = 320.dp, height = 320.dp)) {
                    PageHarborApp()
                }
            }
        }

        composeTestRule.onNodeWithText("Scan document").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun currentScanRemainsASecondaryActionOnHome() {
        composeTestRule.setContent {
            PageHarborApp(
                autoNavigateToScanResult = false,
                scannerSpikeState = ScannerSpikeState.ResultSummary(
                    jpegPageCount = 1,
                    hasPdf = true,
                    pdfPageCount = 1,
                ),
            )
        }

        composeTestRule.onNodeWithText("Scan document")
            .assertIsDisplayed()
            .assertIsEnabled()
        composeTestRule.onNodeWithText("View current scan").assertIsDisplayed()
    }

    @Test
    fun successfulSummaryUsesProductFacingPageWordingWithoutDiagnostics() {
        composeTestRule.setContent {
            PageHarborApp(
                scannerSpikeState = ScannerSpikeState.ResultSummary(
                    jpegPageCount = 3,
                    hasPdf = true,
                    pdfPageCount = 3,
                ),
                documentPages = (1L..3L).map(::documentPage),
            )
        }

        composeTestRule.onNodeWithText("Scan complete").assertIsDisplayed()
        composeTestRule.onNodeWithText("3 pages ready").assertIsDisplayed()
        listOf("Save PDF", "Share PDF", "Export Pages", "Recognize Text").forEach { action ->
            composeTestRule.onNodeWithText(action)
                .assertNodeExists()
                .performScrollTo()
                .assertIsDisplayed()
        }
    }

    @Test
    fun savePdfButtonAppearsForPagesOnlySessionWhenScannerPdfIsMissing() {
        composeTestRule.setContent {
            PageHarborApp(
                scannerSpikeState = ScannerSpikeState.ResultSummary(
                    jpegPageCount = 1,
                    hasPdf = false,
                    pdfPageCount = null,
                ),
                documentPages = listOf(documentPage(1L)),
            )
        }

        composeTestRule.onNodeWithText("Save PDF").assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun sharePdfButtonAppearsForPagesOnlySessionWhenScannerPdfIsMissing() {
        composeTestRule.setContent {
            PageHarborApp(
                scannerSpikeState = ScannerSpikeState.ResultSummary(
                    jpegPageCount = 1,
                    hasPdf = false,
                    pdfPageCount = null,
                ),
                documentPages = listOf(documentPage(1L)),
            )
        }

        composeTestRule.onNodeWithText("Share PDF")
            .assertNodeExists()
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
    }

    @Test
    fun pdfActionsAppearWhenPdfExists() {
        composeTestRule.setContent {
            PageHarborApp(
                scannerSpikeState = ScannerSpikeState.ResultSummary(
                    jpegPageCount = 1,
                    hasPdf = true,
                    pdfPageCount = 1,
                ),
                documentPages = listOf(documentPage(1L)),
            )
        }

        composeTestRule.onNodeWithText("Save PDF")
            .assertNodeExists()
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
        composeTestRule.onNodeWithText("Share PDF")
            .assertNodeExists()
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
    }

    @Test
    fun pdfActionsDoNotAppearForAnEmptySessionEvenWhenLegacySummaryHasPdf() {
        composeTestRule.setContent {
            PageHarborApp(
                scannerSpikeState = ScannerSpikeState.ResultSummary(
                    jpegPageCount = 0,
                    hasPdf = true,
                    pdfPageCount = 1,
                ),
                documentPages = emptyList(),
            )
        }

        composeTestRule.onAllNodesWithText("Save PDF").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("Share PDF").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("Export Pages").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("Recognize Text").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("Save searchable PDF").assertCountEquals(0)
    }

    @Test
    fun exportPagesButtonDoesNotAppearWhenPagesAreMissing() {
        composeTestRule.setContent {
            PageHarborApp(
                scannerSpikeState = ScannerSpikeState.ResultSummary(
                    jpegPageCount = 0,
                    hasPdf = true,
                    pdfPageCount = 1,
                ),
            )
        }

        composeTestRule.onAllNodesWithText("Export Pages").assertCountEquals(0)
    }

    @Test
    fun exportPagesButtonAppearsWhenPagesExist() {
        composeTestRule.setContent {
            PageHarborApp(
                scannerSpikeState = ScannerSpikeState.ResultSummary(
                    jpegPageCount = 2,
                    hasPdf = false,
                    pdfPageCount = null,
                ),
                documentPages = listOf(documentPage(1L), documentPage(2L)),
            )
        }

        composeTestRule.onNodeWithText("Export Pages")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
    }

    @Test
    fun clickingSavePdfInvokesCallback() {
        var saveClickCount = 0

        composeTestRule.setContent {
            PageHarborApp(
                scannerSpikeState = ScannerSpikeState.ResultSummary(
                    jpegPageCount = 1,
                    hasPdf = true,
                    pdfPageCount = 1,
                ),
                documentPages = listOf(documentPage(1L)),
                onSavePdf = {
                    saveClickCount += 1
                },
            )
        }

        composeTestRule.onNodeWithText("Save PDF").performClick()

        assertEquals(1, saveClickCount)
    }

    @Test
    fun clickingSharePdfInvokesCallback() {
        var shareClickCount = 0

        composeTestRule.setContent {
            PageHarborApp(
                scannerSpikeState = ScannerSpikeState.ResultSummary(
                    jpegPageCount = 1,
                    hasPdf = true,
                    pdfPageCount = 1,
                ),
                documentPages = listOf(documentPage(1L)),
                onSharePdf = {
                    shareClickCount += 1
                },
            )
        }

        composeTestRule.onNodeWithText("Share PDF")
            .assertNodeExists()
            .performScrollTo()
            .performClick()

        assertEquals(1, shareClickCount)
    }

    @Test
    fun clickingExportPagesInvokesCallback() {
        var exportClickCount = 0

        composeTestRule.setContent {
            PageHarborApp(
                scannerSpikeState = ScannerSpikeState.ResultSummary(
                    jpegPageCount = 2,
                    hasPdf = true,
                    pdfPageCount = 2,
                ),
                documentPages = listOf(documentPage(1L), documentPage(2L)),
                onExportPages = {
                    exportClickCount += 1
                },
            )
        }

        composeTestRule.onNodeWithText("Export Pages").performScrollTo().performClick()

        assertEquals(1, exportClickCount)
    }

    @Test
    fun savingStateDisablesRepeatedClicksAndShowsProgress() {
        composeTestRule.setContent {
            PageHarborApp(
                scannerSpikeState = ScannerSpikeState.ResultSummary(
                    jpegPageCount = 1,
                    hasPdf = true,
                    pdfPageCount = 1,
                ),
                documentPages = listOf(documentPage(1L)),
                pdfSaveState = PdfSaveState.Saving,
            )
        }

        composeTestRule.onNodeWithText("Save PDF")
            .assertNodeExists()
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsNotEnabled()
        composeTestRule.onNodeWithText("Share PDF")
            .assertNodeExists()
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
        composeTestRule.onNodeWithText("Saving PDF…")
            .assertNodeExists()
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun destinationPickerStateDisablesRepeatedClicks() {
        composeTestRule.setContent {
            PageHarborApp(
                scannerSpikeState = ScannerSpikeState.ResultSummary(
                    jpegPageCount = 1,
                    hasPdf = true,
                    pdfPageCount = 1,
                ),
                documentPages = listOf(documentPage(1L)),
                pdfSaveState = PdfSaveState.ChoosingDestination,
            )
        }

        composeTestRule.onNodeWithText("Save PDF")
            .assertIsDisplayed()
            .assertIsNotEnabled()
    }

    @Test
    fun sharePreparingStateDisablesRepeatedShareClicksAndKeepsSaveEnabled() {
        composeTestRule.setContent {
            PageHarborApp(
                scannerSpikeState = ScannerSpikeState.ResultSummary(
                    jpegPageCount = 1,
                    hasPdf = true,
                    pdfPageCount = 1,
                ),
                documentPages = listOf(documentPage(1L)),
                pdfShareState = PdfShareState.Preparing,
            )
        }

        composeTestRule.onNodeWithText("Share PDF")
            .assertNodeExists()
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsNotEnabled()
        composeTestRule.onNodeWithText("Save PDF")
            .assertNodeExists()
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
        composeTestRule.onNodeWithText("Preparing share…")
            .assertNodeExists()
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun pageExportingStateDisablesRepeatedClicksAndShowsProgress() {
        composeTestRule.setContent {
            PageHarborApp(
                scannerSpikeState = ScannerSpikeState.ResultSummary(
                    jpegPageCount = 3,
                    hasPdf = true,
                    pdfPageCount = 3,
                ),
                documentPages = (1L..3L).map(::documentPage),
                pageExportState = PageExportState.Exporting(
                    pageNumber = 2,
                    pageCount = 3,
                ),
            )
        }

        composeTestRule.onNodeWithText("Export Pages")
            .assertNodeExists()
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsNotEnabled()
        composeTestRule.onNodeWithText("Exporting page 2 of 3…")
            .assertNodeExists()
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Save PDF").performScrollTo().assertIsEnabled()
        composeTestRule.onNodeWithText("Share PDF").performScrollTo().assertIsEnabled()
    }

    @Test
    fun pageDestinationPickerStateDisablesRepeatedClicksWithoutFakeProgress() {
        composeTestRule.setContent {
            PageHarborApp(
                scannerSpikeState = ScannerSpikeState.ResultSummary(
                    jpegPageCount = 2,
                    hasPdf = false,
                    pdfPageCount = null,
                ),
                documentPages = listOf(documentPage(1L), documentPage(2L)),
                pageExportState = PageExportState.ChoosingDestination(
                    pageNumber = 1,
                    pageCount = 2,
                ),
            )
        }

        composeTestRule.onNodeWithText("Export Pages")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsNotEnabled()
        composeTestRule.onAllNodesWithText("Exporting page 1 of 2…").assertCountEquals(0)
    }

    @Test
    fun successMessageAppearsAfterPdfSave() {
        composeTestRule.setContent {
            PageHarborApp(
                scannerSpikeState = ScannerSpikeState.ResultSummary(
                    jpegPageCount = 1,
                    hasPdf = true,
                    pdfPageCount = 1,
                ),
                documentPages = listOf(documentPage(1L)),
                pdfSaveState = PdfSaveState.Saved,
            )
        }

        composeTestRule.onNodeWithText("PDF saved").assertIsDisplayed()
        composeTestRule.onNodeWithText("Save PDF").assertIsEnabled()
    }

    @Test
    fun successMessageAppearsAfterPageExport() {
        composeTestRule.setContent {
            PageHarborApp(
                scannerSpikeState = ScannerSpikeState.ResultSummary(
                    jpegPageCount = 2,
                    hasPdf = false,
                    pdfPageCount = null,
                ),
                documentPages = listOf(documentPage(1L), documentPage(2L)),
                pageExportState = PageExportState.Completed(pageCount = 2),
            )
        }

        composeTestRule.onNodeWithText("Pages exported").assertIsDisplayed()
    }

    @Test
    fun cancellationMessageAppearsAndExportCanBeRetried() {
        composeTestRule.setContent {
            PageHarborApp(
                scannerSpikeState = ScannerSpikeState.ResultSummary(
                    jpegPageCount = 3,
                    hasPdf = false,
                    pdfPageCount = null,
                ),
                documentPages = (1L..3L).map(::documentPage),
                pageExportState = PageExportState.Cancelled(exportedPageCount = 1),
            )
        }

        composeTestRule.onNodeWithText("Page export cancelled.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Export Pages")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
        composeTestRule.onAllNodesWithText("Exporting page 2 of 3…").assertCountEquals(0)
    }

    @Test
    fun cancelledDestinationSelectionReturnsToSaveReadyState() {
        composeTestRule.setContent {
            PageHarborApp(
                scannerSpikeState = ScannerSpikeState.ResultSummary(
                    jpegPageCount = 1,
                    hasPdf = true,
                    pdfPageCount = 1,
                ),
                documentPages = listOf(documentPage(1L)),
                pdfSaveState = PdfSaveState.Idle,
            )
        }

        composeTestRule.onNodeWithText("Save PDF")
            .assertIsDisplayed()
            .assertIsEnabled()
        composeTestRule.onAllNodesWithText("Saving PDF…").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("PDF saved").assertCountEquals(0)
    }

    @Test
    fun clearScanResultInvokesCallback() {
        var clearClickCount = 0

        composeTestRule.setContent {
            PageHarborApp(
                scannerSpikeState = ScannerSpikeState.ResultSummary(
                    jpegPageCount = 1,
                    hasPdf = false,
                    pdfPageCount = null,
                ),
                onClearScanResult = {
                    clearClickCount += 1
                },
            )
        }

        composeTestRule.onNodeWithText("Discard").performClick()

        assertEquals(1, clearClickCount)
    }

    @Test
    fun cancellationFeedbackIsDisplayed() {
        composeTestRule.setContent {
            PageHarborApp(scannerSpikeState = ScannerSpikeState.Cancelled)
        }

        composeTestRule.onNodeWithText("Scan cancelled.").assertIsDisplayed()
    }

    @Test
    fun errorFeedbackIsDisplayed() {
        composeTestRule.setContent {
            PageHarborApp(scannerSpikeState = ScannerSpikeState.Error)
        }

        composeTestRule.onNodeWithText("Document scanner could not be opened.")
            .assertIsDisplayed()
    }

    @Test
    fun clickingPrivacyActionShowsPrivacyDialog() {
        composeTestRule.setContent {
            PageHarborApp()
        }

        composeTestRule.onNodeWithText("How privacy works").performClick()

        composeTestRule.onNodeWithText("Documents are intended to be processed locally.")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("RME PDF Scanner does not operate cloud storage.")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Users will choose where exported files are saved or shared.")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(
            "RME PDF Scanner does not operate advertising, tracking, or analytics.",
        )
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(
            "Google ML Kit may send encrypted technical diagnostics. It does not send your document images or recognized text.",
        )
            .assertIsDisplayed()
    }

    @Test
    fun privacyDialogCanBeDismissed() {
        composeTestRule.setContent {
            PageHarborApp()
        }

        composeTestRule.onNodeWithText("How privacy works").performClick()
        composeTestRule.onNodeWithText("OK").performClick()

        composeTestRule.onAllNodesWithText("Documents are intended to be processed locally.")
            .assertCountEquals(0)
    }

    @Test
    fun clickingAboutShowsAppAndAttributionInformation() {
        composeTestRule.setContent {
            PageHarborApp()
        }

        composeTestRule.onNodeWithText("About RME PDF Scanner").performClick()

        composeTestRule.onNodeWithText("Private document scanner for Android")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Version: ${BuildConfig.VERSION_NAME}")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Build: ${BuildConfig.VERSION_CODE}")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Build type: ${BuildConfig.BUILD_TYPE_LABEL}")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Git revision: ${BuildConfig.GIT_REVISION}")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Published under SynapseWorks")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Open source under Apache License 2.0")
            .assertIsDisplayed()
    }

    @Test
    fun aboutDialogCanBeDismissed() {
        composeTestRule.setContent {
            PageHarborApp()
        }

        composeTestRule.onNodeWithText("About RME PDF Scanner").performClick()
        composeTestRule.onNodeWithText("Close").performClick()

        composeTestRule.onAllNodesWithText("Private document scanner for Android")
            .assertCountEquals(0)
    }

    @Test
    fun viewSourceCodeInvokesCallback() {
        var viewSourceClickCount = 0

        composeTestRule.setContent {
            PageHarborApp(
                onViewSourceCode = {
                    viewSourceClickCount += 1
                },
            )
        }

        composeTestRule.onNodeWithText("About RME PDF Scanner").performClick()
        composeTestRule.onNodeWithText("View source code").performClick()

        assertEquals(1, viewSourceClickCount)
    }

    @Test
    fun recognizeTextDoesNotAppearWhenJpegPagesAreMissing() {
        composeTestRule.setContent {
            PageHarborApp(scannerSpikeState = scanSummary(jpegPageCount = 0))
        }
        composeTestRule.onAllNodesWithText("Recognize Text").assertCountEquals(0)
    }

    @Test
    fun recognizeTextAppearsWhenJpegPagesExist() {
        composeTestRule.setContent {
            PageHarborApp(
                scannerSpikeState = scanSummary(jpegPageCount = 1),
                documentPages = listOf(documentPage(1L)),
            )
        }
        composeTestRule.onNodeWithText("Recognize Text").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun recognizeTextInvokesCallback() {
        var callCount = 0
        composeTestRule.setContent {
            PageHarborApp(
                scannerSpikeState = scanSummary(jpegPageCount = 1),
                documentPages = listOf(documentPage(1L)),
                onRecognizeText = { callCount += 1 },
            )
        }

        composeTestRule.onNodeWithText("Recognize Text").performScrollTo().performClick()

        assertEquals(1, callCount)
    }

    @Test
    fun searchablePdfSaveAppearsOnlyWhenScannedPagesExist() {
        val pages = mutableStateOf(listOf(documentPage(1L)))
        composeTestRule.setContent {
            PageHarborApp(
                scannerSpikeState = scanSummary(jpegPageCount = pages.value.size),
                documentPages = pages.value,
            )
        }

        composeTestRule.onNodeWithText("Save searchable PDF")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()

        composeTestRule.runOnIdle { pages.value = emptyList() }

        composeTestRule.onAllNodesWithText("Save searchable PDF").assertCountEquals(0)
    }

    @Test
    fun searchablePdfSaveInvokesCallbackAndPreventsDuplicatesWhileActive() {
        var callCount = 0
        val state = mutableStateOf<SearchablePdfSaveState>(SearchablePdfSaveState.Idle)
        composeTestRule.setContent {
            PageHarborApp(
                scannerSpikeState = scanSummary(jpegPageCount = 1),
                documentPages = listOf(documentPage(1L)),
                searchablePdfSaveState = state.value,
                onSaveSearchablePdf = { callCount += 1 },
            )
        }

        composeTestRule.onNodeWithText("Save searchable PDF").performScrollTo().performClick()
        assertEquals(1, callCount)

        composeTestRule.runOnIdle { state.value = SearchablePdfSaveState.ChoosingDestination }

        composeTestRule.onNodeWithText("Save searchable PDF")
            .performScrollTo()
            .assertIsNotEnabled()
        composeTestRule.onAllNodesWithText("Preparing searchable PDF…").assertCountEquals(0)
    }

    @Test
    fun searchablePdfSaveShowsEachProductFacingProgressState() {
        val progressStates = listOf(
            SearchablePdfSaveState.Preparing to "Preparing searchable PDF…",
            SearchablePdfSaveState.Recognizing to "Recognizing text…",
            SearchablePdfSaveState.Generating to "Generating searchable PDF…",
            SearchablePdfSaveState.Saving to "Saving searchable PDF…",
        )

        val state = mutableStateOf(progressStates.first().first)
        composeTestRule.setContent {
            PageHarborApp(
                scannerSpikeState = scanSummary(jpegPageCount = 1),
                documentPages = listOf(documentPage(1L)),
                searchablePdfSaveState = state.value,
            )
        }

        progressStates.forEach { (progressState, message) ->
            composeTestRule.runOnIdle { state.value = progressState }
            composeTestRule.onNodeWithText("Save searchable PDF")
                .performScrollTo()
                .assertIsNotEnabled()
            composeTestRule.onNodeWithText(message).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun searchablePdfSaveShowsSuccessCancellationAndSafeFailure() {
        val state = mutableStateOf<SearchablePdfSaveState>(SearchablePdfSaveState.Saved)
        composeTestRule.setContent {
            PageHarborApp(
                scannerSpikeState = scanSummary(jpegPageCount = 1),
                documentPages = listOf(documentPage(1L)),
                searchablePdfSaveState = state.value,
            )
        }
        composeTestRule.onNodeWithText("Searchable PDF saved").assertIsDisplayed()
        composeTestRule.onNodeWithText("Save searchable PDF").assertIsEnabled()

        composeTestRule.runOnIdle { state.value = SearchablePdfSaveState.Cancelled }
        composeTestRule.onNodeWithText("Searchable PDF save cancelled.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Save searchable PDF").assertIsEnabled()

        composeTestRule.runOnIdle {
            state.value = SearchablePdfSaveState.Error(SearchablePdfSaveError.PREPARATION_FAILED)
        }
        composeTestRule.onNodeWithText("Searchable PDF could not be prepared. Try again.")
            .assertIsDisplayed()
    }

    @Test
    fun searchablePdfSaveDoesNotChangeExistingScanResultActions() {
        composeTestRule.setContent {
            PageHarborApp(
                scannerSpikeState = scanSummary(jpegPageCount = 1),
                documentPages = listOf(documentPage(1L)),
                searchablePdfSaveState = SearchablePdfSaveState.Saving,
            )
        }

        composeTestRule.onNodeWithText("Save PDF").assertIsEnabled()
        composeTestRule.onNodeWithText("Share PDF").assertIsEnabled()
        composeTestRule.onNodeWithText("Export Pages").assertIsEnabled()
        composeTestRule.onNodeWithText("Recognize Text").assertIsEnabled()
    }

    @Test
    fun scanResultPageControlsFiltersAndAddPagesRemainAccessible() {
        var addPagesCalls = 0
        composeTestRule.setContent {
            PageHarborApp(
                scannerSpikeState = scanSummary(jpegPageCount = 2),
                documentPages = listOf(
                    documentPage(1L),
                    documentPage(2L),
                ),
                onScanDocument = { addPagesCalls += 1 },
            )
        }

        composeTestRule.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
                .and(hasText("Page 1 of 2")),
        ).assertIsDisplayed()
        composeTestRule.onNodeWithText("Previous page").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Next page").performClick()
        composeTestRule.onNodeWithText("Page 2 of 2").assertIsDisplayed()
        composeTestRule.onNodeWithText("Next page").assertIsNotEnabled()
        composeTestRule.onNodeWithContentDescription("Original")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        composeTestRule.onNodeWithText("Add pages").performClick()

        assertEquals(1, addPagesCalls)
    }

    @Test
    fun twentyPageScanNavigatesToTheLastPageAndDisablesAddPages() {
        var addPagesCalls = 0
        composeTestRule.setContent {
            PageHarborApp(
                scannerSpikeState = scanSummary(jpegPageCount = MAX_DOCUMENT_PAGES),
                documentPages = (1L..MAX_DOCUMENT_PAGES.toLong()).map { id ->
                    documentPage(id)
                },
                onScanDocument = { addPagesCalls += 1 },
            )
        }

        repeat(MAX_DOCUMENT_PAGES - 1) {
            composeTestRule.onNodeWithText("Next page").performClick()
        }

        composeTestRule.onNodeWithText("Page 20 of 20").assertIsDisplayed()
        composeTestRule.onNodeWithText("Next page").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Add pages").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Maximum 20 pages per scan").assertIsDisplayed()
        assertEquals(0, addPagesCalls)
    }

    @Test
    fun scanResultEditorRetainsEveryDocumentTool() {
        composeTestRule.setContent {
            PageHarborApp(
                scannerSpikeState = scanSummary(jpegPageCount = 1),
                documentPages = listOf(documentPage(1L)),
            )
        }

        listOf("Original", "Enhance", "Grayscale", "B&W", "High Contrast").forEach { label ->
            composeTestRule.onAllNodesWithText(label).assertCountEquals(1)
        }
        listOf(
            "Add pages",
            "Recognize Text",
            "Save PDF",
            "Save searchable PDF",
            "Share PDF",
            "Export Pages",
            "Discard",
        ).forEach { action ->
            composeTestRule.onNodeWithText(action).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun scanResultControlsRemainReachableInANarrowShortWindow() {
        composeTestRule.setContent {
            Box(modifier = androidx.compose.ui.Modifier.size(width = 320.dp, height = 320.dp)) {
                PageHarborApp(
                    scannerSpikeState = scanSummary(jpegPageCount = 2),
                    documentPages = listOf(
                        documentPage(1L),
                        documentPage(2L),
                    ),
                )
            }
        }

        composeTestRule.onNodeWithText("Page 1 of 2").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Filter").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Save PDF").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun recognizingStateDisablesOnlyRepeatedOcrAndShowsProgress() {
        composeTestRule.setContent {
            PageHarborApp(
                scannerSpikeState = scanSummary(jpegPageCount = 1),
                documentPages = listOf(documentPage(1L)),
                ocrUiState = OcrUiState.Recognizing,
            )
        }

        composeTestRule.onNodeWithText("Recognize Text").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Recognizing text…").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Save PDF").assertIsEnabled()
        composeTestRule.onNodeWithText("Share PDF").assertIsEnabled()
        composeTestRule.onNodeWithText("Export Pages").assertIsEnabled()
    }

    @Test
    fun ocrSuccessShowsPageSpecificTextAndReturnsToScanResult() {
        composeTestRule.setContent {
            PageHarborApp(
                scannerSpikeState = scanSummary(jpegPageCount = 2),
                documentPages = listOf(documentPage(1L), documentPage(2L)),
                ocrUiState = OcrUiState.Success(
                    OcrResult(
                        listOf(
                            OcrPageResult(pageIndex = 0, text = "First page text"),
                            OcrPageResult(pageIndex = 1, text = "Second page text"),
                        ),
                    ),
                ),
            )
        }

        composeTestRule.onNodeWithText("View recognized text").performScrollTo().performClick()
        composeTestRule.onNodeWithText("Recognized text").assertIsDisplayed()
        composeTestRule.onNodeWithText("Text found on 2 of 2 pages").assertIsDisplayed()
        composeTestRule.onNodeWithText("Page 1 of 2").assertIsDisplayed()
        composeTestRule.onNodeWithText("First page text").assertIsDisplayed()
        composeTestRule.onNodeWithText("Back").performClick()
        composeTestRule.onNodeWithText("View recognized text").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun multipageOcrNavigationChangesTheSelectedPageTextAndPreviewSemantics() {
        val selectedPage = mutableStateOf(0)
        composeTestRule.setContent {
            PageHarborApp(
                scannerSpikeState = scanSummary(jpegPageCount = 2),
                documentPages = listOf(documentPage(1L), documentPage(2L)),
                ocrUiState = OcrUiState.Success(
                    OcrResult(
                        listOf(
                            OcrPageResult(pageIndex = 0, text = "First page text"),
                            OcrPageResult(pageIndex = 1, text = "Second page text"),
                        ),
                    ),
                ),
                ocrSelectedPageIndex = selectedPage.value,
                scannedPageUris = listOf(
                    Uri.parse("content://test/page-one"),
                    Uri.parse("content://test/page-two"),
                ),
                onOcrSelectedPageChange = { selectedPage.value = it },
            )
        }

        composeTestRule.onNodeWithText("View recognized text").performScrollTo().performClick()
        composeTestRule.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
                .and(hasText("Page 1 of 2")),
        ).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Scanned document preview, page 1 of 2")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Previous page").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Next page").performClick()

        composeTestRule.onNodeWithText("Page 2 of 2").assertIsDisplayed()
        composeTestRule.onNodeWithText("Second page text").assertIsDisplayed()
        composeTestRule.onNodeWithText("Next page").assertIsNotEnabled()
    }

    @Test
    fun formattedOcrTextAndActionsRemainReachableAtTwoHundredPercentFont() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                Box(modifier = androidx.compose.ui.Modifier.size(width = 320.dp, height = 600.dp)) {
                    PageHarborApp(
                        scannerSpikeState = scanSummary(jpegPageCount = 1),
                        documentPages = listOf(documentPage(1L)),
                        ocrUiState = OcrUiState.Success(
                            OcrResult(
                                listOf(
                                    OcrPageResult(
                                        pageIndex = 0,
                                        text = "Heading\n  Preserved indentation",
                                    ),
                                ),
                            ),
                        ),
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("View recognized text").performScrollTo().performClick()
        composeTestRule.onNodeWithText("Heading\n  Preserved indentation")
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Copy text").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Recognize Again").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Clear").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun emptyOcrSuccessDisplaysSafeEmptyMessage() {
        composeTestRule.setContent {
            PageHarborApp(
                scannerSpikeState = scanSummary(jpegPageCount = 1),
                documentPages = listOf(documentPage(1L)),
                ocrUiState = OcrUiState.Success(
                    OcrResult(listOf(OcrPageResult(pageIndex = 0, text = ""))),
                ),
            )
        }

        composeTestRule.onNodeWithText("View recognized text").performScrollTo().performClick()
        composeTestRule.onNodeWithText("No text was recognized in this scan.").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Copy text").assertCountEquals(0)
        composeTestRule.onNodeWithText("Recognize Again").assertIsDisplayed()
        composeTestRule.onNodeWithText("Clear").assertIsDisplayed()
    }

    @Test
    fun partialOcrSuccessKeepsTextAndShowsSafeWarning() {
        composeTestRule.setContent {
            PageHarborApp(
                scannerSpikeState = scanSummary(jpegPageCount = 2),
                documentPages = listOf(documentPage(1L), documentPage(2L)),
                ocrUiState = OcrUiState.Success(
                    OcrResult(
                        listOf(
                            OcrPageResult(pageIndex = 0, text = "Readable page"),
                            OcrPageResult(
                                pageIndex = 1,
                                text = "",
                                error = OcrPageError.IMAGE_UNREADABLE,
                            ),
                        ),
                    ),
                ),
            )
        }

        composeTestRule.onNodeWithText("View recognized text").performScrollTo().performClick()
        composeTestRule.onNodeWithText("Readable page", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Copy text").assertIsDisplayed()
        composeTestRule.onNodeWithText("Some pages could not be read.")
            .assertIsDisplayed()
    }

    @Test
    fun clearRecognizedTextPreservesExistingExportActions() {
        var clearCallCount = 0
        composeTestRule.setContent {
            PageHarborApp(
                scannerSpikeState = scanSummary(jpegPageCount = 1),
                documentPages = listOf(documentPage(1L)),
                ocrUiState = OcrUiState.Success(
                    OcrResult(listOf(OcrPageResult(pageIndex = 0, text = "Recognized"))),
                ),
                onClearRecognizedText = { clearCallCount += 1 },
            )
        }

        composeTestRule.onNodeWithText("View recognized text").performScrollTo().performClick()
        composeTestRule.onNodeWithText("Clear").performClick()

        assertEquals(1, clearCallCount)
        composeTestRule.onNodeWithText("Save PDF").assertIsEnabled()
        composeTestRule.onNodeWithText("Share PDF").assertIsEnabled()
        composeTestRule.onNodeWithText("Export Pages").assertIsEnabled()
    }

    @Test
    fun copyTextInvokesCallbackAndShowsSnackbar() {
        var copiedText: String? = null
        composeTestRule.setContent {
            PageHarborApp(
                scannerSpikeState = scanSummary(jpegPageCount = 1),
                documentPages = listOf(documentPage(1L)),
                ocrUiState = OcrUiState.Success(
                    OcrResult(listOf(OcrPageResult(pageIndex = 0, text = "Copy me"))),
                ),
                onCopyRecognizedText = { copiedText = it },
            )
        }

        composeTestRule.onNodeWithText("View recognized text").performScrollTo().performClick()
        composeTestRule.onNodeWithText("Copy text").performClick()

        assertEquals("Copy me", copiedText)
        composeTestRule.onNodeWithText("Text copied").assertIsDisplayed()
    }

    @Test
    fun screenTitlesAreExposedAsHeadings() {
        composeTestRule.setContent {
            PageHarborApp(scannerSpikeState = scanSummary(jpegPageCount = 1))
        }

        composeTestRule.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit)
                .and(hasText("Scanned document")),
        ).assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Scanned document").assertCountEquals(1)
        composeTestRule.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit)
                .and(hasText("Scan complete")),
        ).assertIsDisplayed()
    }

    @Test
    fun searchablePdfProgressIsAPoliteLiveAnnouncement() {
        composeTestRule.setContent {
            PageHarborApp(
                scannerSpikeState = scanSummary(jpegPageCount = 1),
                documentPages = listOf(documentPage(1L)),
                searchablePdfSaveState = SearchablePdfSaveState.Generating,
            )
        }

        composeTestRule.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
                .and(hasText("Generating searchable PDF…")),
        ).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun completionClearsProgressAndUsesConciseSuccessFeedback() {
        val state = mutableStateOf<PdfSaveState>(PdfSaveState.Saving)
        composeTestRule.setContent {
            PageHarborApp(
                scannerSpikeState = scanSummary(jpegPageCount = 1),
                documentPages = listOf(documentPage(1L)),
                pdfSaveState = state.value,
            )
        }

        composeTestRule.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
                .and(hasText("Saving PDF…")),
        ).assertNodeExists().performScrollTo().assertIsDisplayed()

        composeTestRule.runOnIdle { state.value = PdfSaveState.Saved }

        composeTestRule.onAllNodesWithText("Saving PDF…").assertCountEquals(0)
        composeTestRule.onNodeWithText("PDF saved")
            .assertNodeExists()
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Save PDF").performScrollTo().assertIsEnabled()
    }

    @Test
    fun pdfFailureUsesSafeUserFacingFeedback() {
        composeTestRule.setContent {
            PageHarborApp(
                scannerSpikeState = scanSummary(jpegPageCount = 1),
                documentPages = listOf(documentPage(1L)),
                pdfSaveState = PdfSaveState.Error(
                    org.synapseworks.pageharbor.document.PdfExportResult.WriteFailed,
                ),
            )
        }

        composeTestRule.onNodeWithText("PDF could not be saved. Try another destination.")
            .assertIsDisplayed()
        composeTestRule.onAllNodesWithText("FileNotFoundException").assertCountEquals(0)
        composeTestRule.onNodeWithText("Save PDF").assertIsEnabled()
    }

    @Test
    fun scanResultActionsRemainReachableAtLargeFontOnNarrowWidth() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                Box(modifier = androidx.compose.ui.Modifier.size(width = 320.dp, height = 600.dp)) {
                    PageHarborApp(
                        scannerSpikeState = scanSummary(jpegPageCount = 1),
                        documentPages = listOf(documentPage(1L)),
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("Save PDF")
            .assertNodeExists()
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Save searchable PDF").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Discard").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun ocrResultNavigationControlsRemainReachableAtTwoHundredPercentFont() {
        val selectedPage = mutableStateOf(0)
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                Box(modifier = androidx.compose.ui.Modifier.size(width = 320.dp, height = 600.dp)) {
                    PageHarborApp(
                        scannerSpikeState = scanSummary(jpegPageCount = 2),
                        documentPages = listOf(documentPage(1L), documentPage(2L)),
                        ocrUiState = OcrUiState.Success(
                            OcrResult(
                                listOf(
                                    OcrPageResult(pageIndex = 0, text = "First page"),
                                    OcrPageResult(pageIndex = 1, text = "Second page"),
                                ),
                            ),
                        ),
                        ocrSelectedPageIndex = selectedPage.value,
                        onOcrSelectedPageChange = { selectedPage.value = it },
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("View recognized text").performScrollTo().performClick()
        composeTestRule.onNodeWithText("Page 1 of 2").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Next page").performScrollTo().performClick()
        composeTestRule.onNodeWithText("Page 2 of 2").assertIsDisplayed()
    }

    @Test
    fun darkThemeCompositionKeepsScanActionsAvailable() {
        composeTestRule.setContent {
            PageHarborApp(
                darkTheme = true,
                scannerSpikeState = scanSummary(jpegPageCount = 1),
                documentPages = listOf(documentPage(1L)),
            )
        }

        listOf("Save PDF", "Save searchable PDF", "Recognize Text").forEach { action ->
            composeTestRule.onNodeWithText(action)
                .assertNodeExists()
                .performScrollTo()
                .assertIsDisplayed()
        }
    }

    private fun documentPage(id: Long) = DocumentPage(
        id = DocumentPageId(id),
        source = DocumentResource(
            reference = "content://org.synapseworks.pageharbor.test/page/$id",
            ownership = DocumentResourceOwnership.USER_OR_EXTERNAL,
        ),
        sourceCategory = DocumentSourceCategory.SCAN,
    )

    private fun scanSummary(jpegPageCount: Int) = ScannerSpikeState.ResultSummary(
        jpegPageCount = jpegPageCount,
        hasPdf = true,
        pdfPageCount = 1,
    )
}

private fun SemanticsNodeInteraction.assertNodeExists(): SemanticsNodeInteraction =
    assert(SemanticsMatcher("node exists") { true })
