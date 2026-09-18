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
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
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
import org.synapseworks.pageharbor.document.importing.DocumentImportUiState
import org.synapseworks.pageharbor.document.PdfSaveState
import org.synapseworks.pageharbor.document.PdfShareState
import org.synapseworks.pageharbor.document.searchablepdf.SearchablePdfSaveError
import org.synapseworks.pageharbor.document.searchablepdf.SearchablePdfSaveState
import org.synapseworks.pageharbor.document.session.DocumentPage
import org.synapseworks.pageharbor.document.session.DocumentPageId
import org.synapseworks.pageharbor.document.session.DocumentResource
import org.synapseworks.pageharbor.document.session.DocumentResourceOwnership
import org.synapseworks.pageharbor.document.session.DocumentSourceCategory
import org.synapseworks.pageharbor.image.DocumentFilter
import org.synapseworks.pageharbor.library.LibraryDocumentSummary
import org.synapseworks.pageharbor.library.LibraryOcrStatus
import org.synapseworks.pageharbor.library.LibraryUiState
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
    fun bottomNavigationAndScanFabKeepPrimaryDestinationsOneTapAway() {
        composeTestRule.setContent {
            PageHarborApp(libraryUiState = savedLibraryState(2))
        }

        composeTestRule.onNodeWithText("Home").assertIsDisplayed()
        composeTestRule.onNodeWithText("Documents").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithContentDescription("Create folder").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Scan document").assertIsDisplayed()

        composeTestRule.onNodeWithText("Tools").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithContentDescription("Search tools").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Import files").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Merge documents").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Split or extract pages").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Extract text with OCR").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Scan document").assertIsDisplayed()

        composeTestRule.onNodeWithText("More").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithText("Rate RME").assertIsDisplayed()
        composeTestRule.onNodeWithText("Suggest a feature").assertIsDisplayed()
        composeTestRule.onNodeWithText("Share RME").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Scan document").assertIsDisplayed()
    }

    @Test
    fun secondaryAppInformationDoesNotClutterHome() {
        composeTestRule.setContent {
            PageHarborApp()
        }

        composeTestRule.onAllNodesWithText("No account · No ads · Local document handling")
            .assertCountEquals(0)
        composeTestRule.onAllNodesWithText("How privacy works").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("About RME PDF Scanner").assertCountEquals(0)
    }

    @Test
    fun scanDocumentButtonIsDisplayedAndEnabled() {
        composeTestRule.setContent {
            PageHarborApp()
        }

        composeTestRule.onNodeWithContentDescription("Scan document")
            .assertIsDisplayed()
            .assertIsEnabled()
    }

    @Test
    fun privacyAndAboutActionsAreDisplayed() {
        composeTestRule.setContent {
            PageHarborApp()
        }

        composeTestRule.onNodeWithText("More").performClick()
        composeTestRule.onNodeWithText("How privacy works").assertIsDisplayed()
        composeTestRule.onNodeWithText("About RME PDF Scanner").assertIsDisplayed()
    }

    @Test
    fun moreActionsInvokeOnlyUserInitiatedExternalFlows() {
        val calls = mutableListOf<String>()
        composeTestRule.setContent {
            PageHarborApp(
                onRateRme = { calls += "rate" },
                onSuggestFeature = { calls += "suggest" },
                onShareRme = { calls += "share" },
            )
        }

        composeTestRule.onNodeWithText("More").performClick()
        composeTestRule.onNodeWithText("Rate RME").performClick()
        composeTestRule.onNodeWithText("Suggest a feature").performClick()
        composeTestRule.onNodeWithText("Share RME").performClick()

        assertEquals(listOf("rate", "suggest", "share"), calls)
    }

    @Test
    fun debugBuildLabelIsDisplayed() {
        composeTestRule.setContent {
            PageHarborApp()
        }

        composeTestRule.onAllNodesWithText(
            "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · " +
                "${BuildConfig.BUILD_TYPE_LABEL} · ${BuildConfig.GIT_REVISION}",
        ).assertCountEquals(0)
        composeTestRule.onNodeWithText("More").performClick()
        composeTestRule.onNodeWithText("About RME PDF Scanner").performClick()
        composeTestRule.onNodeWithText("Git revision: ${BuildConfig.GIT_REVISION}")
            .assertIsDisplayed()
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

        composeTestRule.onNodeWithContentDescription("Scan document").performClick()

        assertEquals(1, scanClickCount)
    }

    @Test
    fun importFilesIsAnImmediatelyAvailableOneTapAction() {
        var importClickCount = 0
        composeTestRule.setContent {
            PageHarborApp(onImportFiles = { importClickCount += 1 })
        }

        composeTestRule.onNodeWithText("Import files")
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()

        assertEquals(1, importClickCount)
    }

    @Test
    fun toolsSearchFiltersImmediatelyUsingOcrAndCombineAliases() {
        composeTestRule.setContent {
            PageHarborApp(libraryUiState = savedLibraryState(2))
        }

        composeTestRule.onNodeWithText("Tools").performClick()
        composeTestRule.onNodeWithContentDescription("Search tools").performTextInput("OCR")
        composeTestRule.onNodeWithContentDescription("Extract text with OCR").assertIsDisplayed()
        composeTestRule.onAllNodesWithContentDescription("Import files").assertCountEquals(0)
        composeTestRule.onAllNodesWithContentDescription("Merge documents").assertCountEquals(0)
        composeTestRule.onAllNodesWithContentDescription("Split or extract pages").assertCountEquals(0)

        composeTestRule.onNodeWithContentDescription("Clear tool search").performClick()
        composeTestRule.onNodeWithContentDescription("Search tools").performTextInput("combine")
        composeTestRule.onNodeWithContentDescription("Merge documents").assertIsDisplayed()
        composeTestRule.onAllNodesWithContentDescription("Import files").assertCountEquals(0)
        composeTestRule.onAllNodesWithContentDescription("Split or extract pages").assertCountEquals(0)
        composeTestRule.onAllNodesWithContentDescription("Extract text with OCR").assertCountEquals(0)
    }

    @Test
    fun unmatchedToolsSearchShowsEmptyStateAndClearRestoresAllLaunchers() {
        composeTestRule.setContent {
            PageHarborApp(libraryUiState = savedLibraryState(2))
        }

        composeTestRule.onNodeWithText("Tools").performClick()
        composeTestRule.onNodeWithContentDescription("Search tools").performTextInput("cloud sync")
        composeTestRule.onNodeWithText("No tools found").assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription("Clear tool search").performClick()
        composeTestRule.onAllNodesWithText("No tools found").assertCountEquals(0)
        listOf(
            "Import files",
            "Merge documents",
            "Split or extract pages",
            "Extract text with OCR",
        ).forEach { label ->
            composeTestRule.onNodeWithContentDescription(label).assertIsDisplayed()
        }
    }

    @Test
    fun importAndMergeLaunchersKeepExistingActions() {
        var importClickCount = 0
        composeTestRule.setContent {
            PageHarborApp(
                libraryUiState = savedLibraryState(2),
                onImportFiles = { importClickCount += 1 },
            )
        }

        composeTestRule.onNodeWithText("Tools").performClick()
        composeTestRule.onNodeWithContentDescription("Import files").performClick()
        assertEquals(1, importClickCount)

        composeTestRule.onNodeWithContentDescription("Merge documents").performClick()
        composeTestRule.onNodeWithContentDescription("Create folder").assertIsDisplayed()
    }

    @Test
    fun splitLauncherKeepsExistingDocumentNavigationAction() {
        composeTestRule.setContent {
            PageHarborApp(libraryUiState = savedLibraryState(1))
        }

        composeTestRule.onNodeWithText("Tools").performClick()
        composeTestRule.onNodeWithContentDescription("Split or extract pages").performClick()
        composeTestRule.onNodeWithContentDescription("Create folder").assertIsDisplayed()
    }

    @Test
    fun ocrLauncherKeepsExistingDocumentNavigationAction() {
        composeTestRule.setContent {
            PageHarborApp(libraryUiState = savedLibraryState(1))
        }

        composeTestRule.onNodeWithText("Tools").performClick()
        composeTestRule.onNodeWithContentDescription("Extract text with OCR").performClick()
        composeTestRule.onNodeWithContentDescription("Create folder").assertIsDisplayed()
    }

    @Test
    fun toolsSearchAndLaunchersRemainUsableAtTwoHundredPercentFont() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                Box(modifier = androidx.compose.ui.Modifier.size(width = 320.dp, height = 600.dp)) {
                    PageHarborApp(libraryUiState = savedLibraryState(1))
                }
            }
        }

        composeTestRule.onNodeWithText("Tools").performClick()
        composeTestRule.onNodeWithContentDescription("Search tools")
            .assertIsDisplayed()
            .performTextInput("extract text")
        composeTestRule.onNodeWithContentDescription("Extract text with OCR")
            .assertIsDisplayed()
            .performClick()
        composeTestRule.onNodeWithContentDescription("Create folder").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Scan document").assertIsDisplayed()
    }

    @Test
    fun toolsWithNoSavedDocumentsKeepImportEnabledAndOtherLaunchersUnavailable() {
        var importClickCount = 0
        composeTestRule.setContent {
            PageHarborApp(onImportFiles = { importClickCount += 1 })
        }

        composeTestRule.onNodeWithText("Tools").performClick()
        composeTestRule.onNodeWithContentDescription("Import files")
            .assertIsEnabled()
            .performClick()
        composeTestRule.onNodeWithContentDescription(
            "Merge. Unavailable. Requires at least two saved documents.",
        ).assertIsNotEnabled()
        composeTestRule.onNodeWithContentDescription(
            "Split or Extract. Unavailable. Requires a saved document.",
        ).assertIsNotEnabled().performClick()
        composeTestRule.onNodeWithContentDescription(
            "Extract text. Unavailable. Requires a saved document. OCR.",
        ).assertIsNotEnabled()
        composeTestRule.onAllNodesWithContentDescription("Create folder").assertCountEquals(0)
        assertEquals(1, importClickCount)
    }

    @Test
    fun toolsWithOneSavedDocumentEnableSingleDocumentToolsOnly() {
        composeTestRule.setContent {
            PageHarborApp(libraryUiState = savedLibraryState(1))
        }

        composeTestRule.onNodeWithText("Tools").performClick()
        composeTestRule.onNodeWithContentDescription(
            "Merge. Unavailable. Requires at least two saved documents.",
        ).assertIsNotEnabled()
        composeTestRule.onNodeWithContentDescription("Split or extract pages").assertIsEnabled()
        composeTestRule.onNodeWithContentDescription("Extract text with OCR").assertIsEnabled()
    }

    @Test
    fun toolsWithTwoSavedDocumentsEnableEveryLauncher() {
        composeTestRule.setContent {
            PageHarborApp(libraryUiState = savedLibraryState(2))
        }

        composeTestRule.onNodeWithText("Tools").performClick()
        listOf(
            "Import files",
            "Merge documents",
            "Split or extract pages",
            "Extract text with OCR",
        ).forEach { description ->
            composeTestRule.onNodeWithContentDescription(description).assertIsEnabled()
        }
    }

    @Test
    fun toolsAvailabilityUpdatesWhenSavedDocumentCountChanges() {
        val libraryState = mutableStateOf(savedLibraryState(2))
        composeTestRule.setContent {
            PageHarborApp(libraryUiState = libraryState.value)
        }

        composeTestRule.onNodeWithText("Tools").performClick()
        composeTestRule.onNodeWithContentDescription("Merge documents").assertIsEnabled()
        composeTestRule.runOnIdle { libraryState.value = savedLibraryState(1) }
        composeTestRule.onNodeWithContentDescription(
            "Merge. Unavailable. Requires at least two saved documents.",
        ).assertIsNotEnabled()
        composeTestRule.onNodeWithContentDescription("Split or extract pages").assertIsEnabled()
    }

    @Test
    fun toolsSearchPreservesDisabledStateAndOcrAlias() {
        composeTestRule.setContent {
            PageHarborApp()
        }

        composeTestRule.onNodeWithText("Tools").performClick()
        composeTestRule.onNodeWithContentDescription("Search tools").performTextInput("OCR")
        composeTestRule.onNodeWithContentDescription(
            "Extract text. Unavailable. Requires a saved document. OCR.",
        ).assertIsDisplayed().assertIsNotEnabled()
        composeTestRule.onAllNodesWithContentDescription("Import files").assertCountEquals(0)
    }

    @Test
    fun toolsDisabledStatesRemainVisibleInDarkTheme() {
        composeTestRule.setContent {
            PageHarborApp(darkTheme = true)
        }

        composeTestRule.onNodeWithText("Tools").performClick()
        composeTestRule.onNodeWithContentDescription("Import files").assertIsEnabled()
        composeTestRule.onNodeWithContentDescription(
            "Merge. Unavailable. Requires at least two saved documents.",
        ).assertIsDisplayed().assertIsNotEnabled()
        composeTestRule.onNodeWithContentDescription(
            "Extract text. Unavailable. Requires a saved document. OCR.",
        ).assertIsDisplayed().assertIsNotEnabled()
    }

    @Test
    fun toolsRemainReachableAtTabletWidthAndTwoHundredPercentText() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                Box(modifier = androidx.compose.ui.Modifier.size(width = 900.dp, height = 800.dp)) {
                    PageHarborApp(libraryUiState = savedLibraryState(2))
                }
            }
        }

        composeTestRule.onNodeWithText("Tools").performClick()
        listOf(
            "Import files",
            "Merge documents",
            "Split or extract pages",
            "Extract text with OCR",
        ).forEach { description ->
            composeTestRule.onNodeWithContentDescription(description)
                .assertIsDisplayed()
                .assertIsEnabled()
        }
    }

    @Test
    fun preparingStateDisablesScanActionAndShowsProgress() {
        composeTestRule.setContent {
            PageHarborApp(scannerSpikeState = ScannerSpikeState.Preparing)
        }

        composeTestRule.onNodeWithContentDescription("Scan document")
            .assertIsDisplayed()
            .assertIsNotEnabled()
        composeTestRule.onNodeWithText("Preparing scanner…")
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    @Test
    fun homePrimaryScanActionRemainsReachableInANarrowShortWindowAtTwoHundredPercentFont() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                Box(modifier = androidx.compose.ui.Modifier.size(width = 320.dp, height = 320.dp)) {
                    PageHarborApp()
                }
            }
        }

        composeTestRule.onNodeWithContentDescription("Scan document").assertIsDisplayed()
        composeTestRule.onNodeWithText("Home").assertIsDisplayed()
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
                documentPages = listOf(documentPage(1L)),
            )
        }

        composeTestRule.onNodeWithContentDescription("Scan document")
            .assertIsDisplayed()
            .assertIsEnabled()
        composeTestRule.onNodeWithText("Resume document").assertIsDisplayed()
    }

    @Test
    fun resumeIsHiddenWhenNoUsefulActiveSessionExists() {
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

        composeTestRule.onAllNodesWithText("Resume document").assertCountEquals(0)
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

        composeTestRule.onNodeWithText("Document").assertIsDisplayed()
        composeTestRule.onNodeWithText("Page 1 of 3").assertIsDisplayed()
        listOf("Add", "Edit", "OCR", "Share", "More").forEach { action ->
            composeTestRule.onNodeWithText(action).assertIsDisplayed()
        }
        openDocumentMore()
        listOf("Export PDF", "Save searchable PDF", "Export Pages").forEach { action ->
            composeTestRule.onNodeWithText(action).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun documentWorkspaceReplacesGlobalNavigationWithContextualActions() {
        composeTestRule.setContent {
            PageHarborApp(
                scannerSpikeState = scanSummary(jpegPageCount = 1),
                documentPages = listOf(documentPage(1L)),
            )
        }

        composeTestRule.onAllNodesWithText("Home").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("Documents").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("Tools").assertCountEquals(0)
        listOf("Add", "Edit", "OCR", "Share", "More").forEach { action ->
            composeTestRule.onNodeWithText(action).assertIsDisplayed()
        }
    }

    @Test
    fun filterChangesKeepTheDocumentPreviewVisible() {
        val selectedFilter = mutableStateOf(DocumentFilter.ORIGINAL)
        composeTestRule.setContent {
            PageHarborApp(
                scannerSpikeState = scanSummary(jpegPageCount = 1),
                documentPages = listOf(documentPage(1L).copy(filter = selectedFilter.value)),
                onPageFilterChange = { _, filter -> selectedFilter.value = filter },
            )
        }

        openDocumentEdit()
        composeTestRule.onNodeWithText("Filter").performClick()
        composeTestRule.onNodeWithText("Grayscale").performClick()

        composeTestRule.runOnIdle {
            assertEquals(DocumentFilter.GRAYSCALE, selectedFilter.value)
        }
        composeTestRule.onNodeWithContentDescription("Document preview, page 1 of 1")
            .assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Grayscale")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
    }

    @Test
    fun importProgressCanBeCancelledWithoutLeavingTheWorkflow() {
        var cancelCount = 0
        composeTestRule.setContent {
            PageHarborApp(
                importUiState = DocumentImportUiState.Processing(1, 3, 1),
                onCancelImport = { cancelCount += 1 },
            )
        }

        composeTestRule.onNodeWithText("Importing file 1 of 3 · 1 pages ready…").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cancel import").performClick()
        assertEquals(1, cancelCount)
    }

    @Test
    fun pageReviewExposesReorderRotateRemoveAndAddActions() {
        val calls = mutableListOf<String>()
        composeTestRule.setContent {
            PageHarborApp(
                scannerSpikeState = scanSummary(jpegPageCount = 2),
                documentPages = listOf(documentPage(1L), documentPage(2L)),
                onPageRotate = { calls += "rotate:$it" },
                onPageMove = { pageId, offset -> calls += "move:$pageId:$offset" },
                onPageRemove = { calls += "remove:$it" },
                onImportFiles = { calls += "import" },
            )
        }

        openDocumentEdit()
        composeTestRule.onNodeWithText("Rotate clockwise").performClick()
        composeTestRule.onNodeWithText("Move later").performScrollTo().performClick()
        composeTestRule.onNodeWithText("Remove page").performScrollTo().performClick()
        composeTestRule.onNodeWithText("Done").performClick()
        openDocumentAdd()
        composeTestRule.onNodeWithText("Add files").performClick()

        assertEquals(listOf("rotate:1", "move:1:1", "remove:1", "import"), calls)
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

        openDocumentMore()
        composeTestRule.onNodeWithText("Export PDF")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
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

        composeTestRule.onNodeWithText("Share")
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

        composeTestRule.onNodeWithText("Share")
            .assertIsDisplayed()
            .assertIsEnabled()
        openDocumentMore()
        composeTestRule.onNodeWithText("Export PDF")
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

        composeTestRule.onNodeWithText("Share").assertIsNotEnabled()
        composeTestRule.onNodeWithText("OCR").assertIsNotEnabled()
        openDocumentMore()
        composeTestRule.onNodeWithText("Export PDF").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Export Pages").performScrollTo().assertIsNotEnabled()
        composeTestRule.onNodeWithText("Save searchable PDF").performScrollTo().assertIsNotEnabled()
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

        openDocumentMore()
        composeTestRule.onNodeWithText("Export Pages").performScrollTo().assertIsNotEnabled()
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

        openDocumentMore()
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

        openDocumentMore()
        composeTestRule.onNodeWithText("Export PDF").performClick()

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

        composeTestRule.onNodeWithText("Share").performClick()

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

        openDocumentMore()
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

        openDocumentMore()
        composeTestRule.onNodeWithText("Export PDF")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsNotEnabled()
        composeTestRule.onNodeWithText("Saving PDF…")
            .assertNodeExists()
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

        openDocumentMore()
        composeTestRule.onNodeWithText("Export PDF")
            .performScrollTo()
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

        composeTestRule.onNodeWithText("Share").assertIsNotEnabled()
        openDocumentMore()
        composeTestRule.onNodeWithText("Export PDF")
            .assertIsDisplayed()
            .assertIsEnabled()
        composeTestRule.onNodeWithText("Preparing share…")
            .assertNodeExists()
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

        openDocumentMore()
        composeTestRule.onNodeWithText("Export Pages")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsNotEnabled()
        composeTestRule.onNodeWithText("Exporting page 2 of 3…")
            .assertNodeExists()
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Export PDF").performScrollTo().assertIsEnabled()
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

        openDocumentMore()
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
        openDocumentMore()
        composeTestRule.onNodeWithText("Export PDF").assertIsEnabled()
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
        openDocumentMore()
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

        openDocumentMore()
        composeTestRule.onNodeWithText("Export PDF")
            .performScrollTo()
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

        openDocumentMore()
        composeTestRule.onNodeWithText("Discard").performScrollTo().performClick()

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

        composeTestRule.onNodeWithText("More").performClick()
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

        composeTestRule.onNodeWithText("More").performClick()
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

        composeTestRule.onNodeWithText("More").performClick()
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

        composeTestRule.onNodeWithText("More").performClick()
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

        composeTestRule.onNodeWithText("More").performClick()
        composeTestRule.onNodeWithText("About RME PDF Scanner").performClick()
        composeTestRule.onNodeWithText("View source code").performClick()

        assertEquals(1, viewSourceClickCount)
    }

    @Test
    fun recognizeTextDoesNotAppearWhenJpegPagesAreMissing() {
        composeTestRule.setContent {
            PageHarborApp(scannerSpikeState = scanSummary(jpegPageCount = 0))
        }
        composeTestRule.onNodeWithText("OCR").assertIsNotEnabled()
    }

    @Test
    fun recognizeTextAppearsWhenJpegPagesExist() {
        composeTestRule.setContent {
            PageHarborApp(
                scannerSpikeState = scanSummary(jpegPageCount = 1),
                documentPages = listOf(documentPage(1L)),
            )
        }
        composeTestRule.onNodeWithText("OCR").assertIsDisplayed().assertIsEnabled()
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

        composeTestRule.onNodeWithText("OCR").performClick()

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

        openDocumentMore()
        composeTestRule.onNodeWithText("Save searchable PDF")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()

        composeTestRule.runOnIdle { pages.value = emptyList() }

        composeTestRule.onNodeWithText("Save searchable PDF").assertIsNotEnabled()
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

        openDocumentMore()
        composeTestRule.onNodeWithText("Save searchable PDF").performScrollTo().performClick()
        assertEquals(1, callCount)

        composeTestRule.runOnIdle { state.value = SearchablePdfSaveState.ChoosingDestination }

        openDocumentMore()
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
            composeTestRule.onNodeWithText(message).assertIsDisplayed()
            openDocumentMore()
            composeTestRule.onNodeWithText("Save searchable PDF")
                .performScrollTo()
                .assertIsNotEnabled()
            composeTestRule.onNodeWithContentDescription("Close sheet").performClick()
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
        openDocumentMore()
        composeTestRule.onNodeWithText("Save searchable PDF").assertIsEnabled()
        composeTestRule.onNodeWithContentDescription("Close sheet").performClick()

        composeTestRule.runOnIdle { state.value = SearchablePdfSaveState.Cancelled }
        composeTestRule.onNodeWithText("Searchable PDF save cancelled.").assertIsDisplayed()
        openDocumentMore()
        composeTestRule.onNodeWithText("Save searchable PDF").assertIsEnabled()
        composeTestRule.onNodeWithContentDescription("Close sheet").performClick()

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

        composeTestRule.onNodeWithText("Share").assertIsEnabled()
        composeTestRule.onNodeWithText("OCR").assertIsEnabled()
        openDocumentMore()
        composeTestRule.onNodeWithText("Export PDF").assertIsEnabled()
        composeTestRule.onNodeWithText("Export Pages").performScrollTo().assertIsEnabled()
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
        composeTestRule.onNodeWithContentDescription("Previous page").assertIsNotEnabled()
        composeTestRule.onNodeWithContentDescription("Next page").performClick()
        composeTestRule.onNodeWithText("Page 2 of 2").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Next page").assertIsNotEnabled()
        openDocumentEdit()
        composeTestRule.onNodeWithText("Filter").performClick()
        composeTestRule.onNodeWithContentDescription("Original")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        composeTestRule.onNodeWithText("Done").performClick()
        openDocumentAdd()
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
            composeTestRule.onNodeWithContentDescription("Next page").performClick()
        }

        composeTestRule.onNodeWithText("Page 20 of 20").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Next page").assertIsNotEnabled()
        openDocumentAdd()
        composeTestRule.onNodeWithText("Add pages").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Maximum 20 pages per document").assertIsDisplayed()
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

        listOf("Add", "Edit", "OCR", "Share", "More").forEach { action ->
            composeTestRule.onNodeWithText(action).assertIsDisplayed()
        }
        openDocumentEdit()
        composeTestRule.onNodeWithText("Filter").performClick()
        listOf("Original", "Enhance", "Grayscale", "B&W").forEach { label ->
            composeTestRule.onAllNodesWithText(label).assertCountEquals(1)
        }
        composeTestRule.onNodeWithText("Done").performClick()
        openDocumentMore()
        listOf("Export PDF", "Save searchable PDF", "Export Pages", "Discard").forEach { action ->
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

        composeTestRule.onNodeWithText("Page 1 of 2").assertIsDisplayed()
        composeTestRule.onNodeWithText("Edit").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithText("Filter").assertIsDisplayed()
        composeTestRule.onNodeWithText("Done").assertIsDisplayed()
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

        composeTestRule.onNodeWithText("OCR").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Recognizing text…").assertIsDisplayed()
        composeTestRule.onNodeWithText("Share").assertIsEnabled()
        openDocumentMore()
        composeTestRule.onNodeWithText("Export PDF").assertIsEnabled()
        composeTestRule.onNodeWithText("Export Pages").performScrollTo().assertIsEnabled()
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

        composeTestRule.onNodeWithText("OCR").performClick()
        composeTestRule.onNodeWithText("Recognized text").assertIsDisplayed()
        composeTestRule.onNodeWithText("Text found on 2 of 2 pages").assertIsDisplayed()
        composeTestRule.onNodeWithText("Page 1 of 2").assertIsDisplayed()
        composeTestRule.onNodeWithText("First page text").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Back").performClick()
        composeTestRule.onNodeWithText("OCR").assertIsDisplayed()
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

        composeTestRule.onNodeWithText("OCR").performClick()
        composeTestRule.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
                .and(hasText("Page 1 of 2")),
        ).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Document preview, page 1 of 2")
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

        composeTestRule.onNodeWithText("OCR").performClick()
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

        composeTestRule.onNodeWithText("OCR").performClick()
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

        composeTestRule.onNodeWithText("OCR").performClick()
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

        composeTestRule.onNodeWithText("OCR").performClick()
        composeTestRule.onNodeWithText("Clear").performClick()

        assertEquals(1, clearCallCount)
        composeTestRule.onNodeWithText("Share").assertIsEnabled()
        openDocumentMore()
        composeTestRule.onNodeWithText("Export PDF").assertIsEnabled()
        composeTestRule.onNodeWithText("Export Pages").performScrollTo().assertIsEnabled()
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

        composeTestRule.onNodeWithText("OCR").performClick()
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
                .and(hasText("Document")),
        ).assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Document").assertCountEquals(1)
        composeTestRule.onNodeWithText("More").assertIsDisplayed()
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
        ).assertIsDisplayed()
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
        ).assertNodeExists().assertIsDisplayed()

        composeTestRule.runOnIdle { state.value = PdfSaveState.Saved }

        composeTestRule.onAllNodesWithText("Saving PDF…").assertCountEquals(0)
        composeTestRule.onNodeWithText("PDF saved")
            .assertNodeExists()
            .assertIsDisplayed()
        openDocumentMore()
        composeTestRule.onNodeWithText("Export PDF").assertIsEnabled()
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
        openDocumentMore()
        composeTestRule.onNodeWithText("Export PDF").assertIsEnabled()
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

        composeTestRule.onNodeWithText("Edit").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithText("Filter").assertIsDisplayed()
        composeTestRule.onNodeWithText("Done").performClick()
        openDocumentMore()
        composeTestRule.onNodeWithText("Export PDF").performScrollTo().assertIsDisplayed()
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

        composeTestRule.onNodeWithText("OCR").performClick()
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

        composeTestRule.onNodeWithText("OCR").assertIsDisplayed()
        composeTestRule.onNodeWithText("Share").assertIsDisplayed()
        openDocumentMore()
        listOf("Export PDF", "Save searchable PDF").forEach { action ->
            composeTestRule.onNodeWithText(action).performScrollTo().assertIsDisplayed()
        }
    }

    private fun openDocumentMore() {
        composeTestRule.onNodeWithText("More").performClick()
        composeTestRule.onNodeWithText("Document actions").assertIsDisplayed()
    }

    private fun openDocumentAdd() {
        composeTestRule.onNodeWithText("Add").performClick()
        composeTestRule.onNodeWithText("Add to document").assertIsDisplayed()
    }

    private fun openDocumentEdit() {
        composeTestRule.onNodeWithText("Edit").performClick()
        composeTestRule.onNodeWithText("Edit page").assertIsDisplayed()
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

    private fun savedLibraryState(count: Int): LibraryUiState {
        val documents = (1..count).map { index ->
            LibraryDocumentSummary(
                id = "saved-$index",
                title = "Saved document $index",
                createdAtMillis = index.toLong(),
                modifiedAtMillis = index.toLong(),
                pageCount = 1,
                folderId = null,
                folderName = null,
                thumbnailRelativePath = null,
                ocrStatus = LibraryOcrStatus.NOT_INDEXED,
            )
        }
        return LibraryUiState(documents = documents, recentDocuments = documents)
    }
}

private fun SemanticsNodeInteraction.assertNodeExists(): SemanticsNodeInteraction =
    assert(SemanticsMatcher("node exists") { true })
