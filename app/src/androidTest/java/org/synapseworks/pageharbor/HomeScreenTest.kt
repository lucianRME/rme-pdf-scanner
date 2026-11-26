package org.synapseworks.pageharbor

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
import org.synapseworks.pageharbor.document.session.LibraryDocumentReference
import org.synapseworks.pageharbor.image.DocumentFilter
import org.synapseworks.pageharbor.library.LibraryDocumentSummary
import org.synapseworks.pageharbor.library.LibraryOcrStatus
import org.synapseworks.pageharbor.library.LibraryUiState
import org.synapseworks.pageharbor.scanner.ScannerSpikeState
import org.synapseworks.pageharbor.ocr.OcrPageError
import org.synapseworks.pageharbor.ocr.OcrOperationSelection
import org.synapseworks.pageharbor.ocr.OcrPageResult
import org.synapseworks.pageharbor.ocr.OcrResult
import org.synapseworks.pageharbor.ocr.OcrScript
import org.synapseworks.pageharbor.ocr.OcrScriptSelection
import org.synapseworks.pageharbor.ocr.OcrUiState
import org.synapseworks.pageharbor.ui.PageHarborApp
import org.synapseworks.pageharbor.ui.portability.BackupVerificationStatusUiModel
import org.synapseworks.pageharbor.ui.portability.PortabilityCallbacks
import org.synapseworks.pageharbor.ui.portability.PortabilityWorkflowState
import org.synapseworks.pageharbor.ui.portability.ScannerMigrationSource

private const val LEGACY_UI_PAGE_COUNT = 20

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
    fun emptyLibraryDisablesBackupCreationButKeepsRestoreEnabled() {
        composeTestRule.setContent {
            PageHarborApp(
                libraryHasDocuments = false,
                portabilityState = PortabilityWorkflowState.BackupRestore(
                    BackupVerificationStatusUiModel.NeverBackedUp,
                ),
            )
        }

        composeTestRule.onNodeWithText("No documents to back up").assertIsDisplayed()
        composeTestRule.onNodeWithText("Back up now").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Restore backup").assertIsEnabled()
    }

    @Test
    fun nonEmptyLibraryEnablesBackupCreation() {
        composeTestRule.setContent {
            PageHarborApp(
                libraryHasDocuments = true,
                portabilityState = PortabilityWorkflowState.BackupRestore(
                    BackupVerificationStatusUiModel.NeverBackedUp,
                ),
            )
        }

        composeTestRule.onNodeWithText("Back up now").assertIsEnabled()
        composeTestRule.onNodeWithText("Restore backup").assertIsEnabled()
    }

    @Test
    fun emptyLibraryDisablesMoveToNewPhoneButLeavesReceivingRestoreAvailable() {
        composeTestRule.setContent {
            PageHarborApp(
                libraryHasDocuments = false,
                portabilityState = PortabilityWorkflowState.NewPhone(),
            )
        }

        composeTestRule.onNodeWithText("No documents to move yet.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Create backup").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Restore backup").assertIsEnabled()
    }

    @Test
    fun nonEmptyLibraryEnablesMoveToNewPhone() {
        composeTestRule.setContent {
            PageHarborApp(
                libraryHasDocuments = true,
                portabilityState = PortabilityWorkflowState.NewPhone(),
            )
        }

        composeTestRule.onNodeWithText("Create backup").assertIsEnabled()
        composeTestRule.onNodeWithText("Restore backup").assertIsEnabled()
    }

    @Test
    fun emptyLibraryDisablesMoreDestinationMoveActionWithExplanation() {
        composeTestRule.setContent {
            PageHarborApp(libraryHasDocuments = false)
        }

        composeTestRule.onNodeWithText("More").performClick()
        composeTestRule.onNodeWithText("Move to a new phone").assertIsNotEnabled()
        composeTestRule.onNodeWithText(
            "Available when documents are saved.",
        ).assertIsDisplayed()
        composeTestRule.onNodeWithText("Backup & restore").assertIsEnabled()
    }

    @Test
    fun microsoftLensUsesTheGenericMigrationActions() {
        var selectedSource by mutableStateOf(ScannerMigrationSource.OTHER)
        var fileSelections = 0
        var folderSelections = 0
        composeTestRule.setContent {
            PageHarborApp(
                portabilityState = PortabilityWorkflowState.MigrationSource(selectedSource),
                portabilityCallbacks = PortabilityCallbacks(
                    onMigrationSourceSelected = { selectedSource = it },
                    onSelectMigrationFiles = { fileSelections += 1 },
                    onSelectMigrationFolder = { folderSelections += 1 },
                ),
            )
        }

        listOf(
            "CamScanner",
            "Adobe Scan / Acrobat",
            "Genius Scan",
            "Microsoft Lens",
            "Other scanner",
        ).forEach { source -> composeTestRule.onNodeWithText(source).assertIsDisplayed() }
        composeTestRule.onNodeWithText("Microsoft Lens").performClick()
        composeTestRule.onNodeWithText(
            "Export or share your existing scans from Microsoft Lens as PDF, then choose RME. " +
                "Choose exported PDFs using Android's file picker.",
        ).assertIsDisplayed()

        composeTestRule.onNodeWithText("Select files").performClick()
        composeTestRule.onNodeWithText("Select folder").performClick()
        composeTestRule.runOnIdle {
            assertEquals(ScannerMigrationSource.MICROSOFT_LENS, selectedSource)
            assertEquals(1, fileSelections)
            assertEquals(1, folderSelections)
        }
    }

    @Test
    fun captureAndPrimaryDestinationsAreReachableFromPopulatedHome() {
        composeTestRule.setContent {
            PageHarborApp(libraryUiState = savedLibraryState(2))
        }

        composeTestRule.onNodeWithText("Home").assertIsDisplayed()
        composeTestRule.onNodeWithTag("library-scan-fab").assertIsDisplayed().assertIsEnabled()
        composeTestRule.onNodeWithText("Import").assertIsEnabled()
        composeTestRule.onNodeWithText("Documents").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithContentDescription("Create folder").assertIsDisplayed()
        composeTestRule.onNodeWithTag("library-scan-fab").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("1 page", substring = true).assertCountEquals(2)

        composeTestRule.onNodeWithText("Tools").assertIsDisplayed().performClick()
        composeTestRule.onAllNodesWithContentDescription("Search tools").assertCountEquals(0)
        listOf("Import & capture", "Extract text & data", "PDF tools").forEach { heading ->
            composeTestRule.onNodeWithTag("tools-grid").performScrollToNode(hasText(heading))
            composeTestRule.onNodeWithText(heading).assertIsDisplayed()
        }
        tool("Import files").assertIsDisplayed()
        tool("Merge documents").assertIsDisplayed()
        tool("Split or extract pages").assertIsDisplayed()
        tool("Fit upload limit. Create a PDF under a chosen file-size limit.").assertIsDisplayed()
        tool("ID / card copy. Front and back on one PDF page.").assertIsDisplayed().assertIsEnabled()
        tool("Extract text with OCR").assertIsDisplayed()
        composeTestRule.onNodeWithTag("library-scan-fab").assertIsDisplayed()

        composeTestRule.onNodeWithText("More").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithText("Rate RME").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Suggest a feature").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Share RME").performScrollTo().assertIsDisplayed()
        composeTestRule.onAllNodesWithContentDescription("Scan").assertCountEquals(0)
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

        composeTestRule.onNodeWithContentDescription("Scan")
            .assertIsDisplayed()
            .assertIsEnabled()
    }

    @Test
    fun privacyAndAboutActionsAreDisplayed() {
        composeTestRule.setContent {
            PageHarborApp()
        }

        composeTestRule.onNodeWithText("More").performClick()
        composeTestRule.onNodeWithText("How privacy works").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("About RME PDF Scanner").performScrollTo().assertIsDisplayed()
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
        composeTestRule.onNodeWithText("Rate RME").performScrollTo().performClick()
        composeTestRule.onNodeWithText("Suggest a feature").performScrollTo().performClick()
        composeTestRule.onNodeWithText("Share RME").performScrollTo().performClick()

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
        composeTestRule.onNodeWithText("About RME PDF Scanner").performScrollTo().performClick()
        composeTestRule.onNodeWithText("Git revision: ${BuildConfig.GIT_REVISION}")
            .assertIsDisplayed()
        assertTrue(BuildConfig.GIT_REVISION.isNotBlank())
    }

    @Test
    fun cameraImportCardAndGoogleScanReachTheirOwnEntryPoints() {
        val actions = mutableListOf<String>()
        composeTestRule.setContent {
            PageHarborApp(
                onManualScan = { actions += "camera" },
                onImportFiles = { actions += "import" },
                onOpenCardCopy = { actions += "card" },
                onScanDocument = { actions += "google" },
            )
        }

        composeTestRule.onNodeWithContentDescription("Scan").performClick()
        composeTestRule.onAllNodesWithText("New scan").assertCountEquals(0)
        composeTestRule.onNodeWithText("Import").performClick()
        composeTestRule.onNodeWithText("Documents").performClick()
        composeTestRule.onNodeWithText("Import").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithText("Tools").performClick()
        tool("ID / card copy. Front and back on one PDF page.").performClick()
        tool("Google auto scan. Uses Google’s camera controls.").performClick()
        assertEquals(listOf("camera", "import", "import", "card", "google"), actions)
    }

    @Test
    fun cancelledHomeImportReturnsToTheSameLibrary() {
        var importState by mutableStateOf<DocumentImportUiState>(DocumentImportUiState.Idle)
        composeTestRule.setContent {
            PageHarborApp(
                libraryUiState = savedLibraryState(1),
                importUiState = importState,
                onImportFiles = { importState = DocumentImportUiState.Selecting },
            )
        }
        composeTestRule.onNodeWithText("Import").performClick()
        composeTestRule.onNodeWithText("Import").assertIsNotEnabled()
        composeTestRule.runOnIdle { importState = DocumentImportUiState.Cancelled }
        composeTestRule.onNodeWithText("Import").assertIsDisplayed().assertIsEnabled()
        composeTestRule.onNodeWithTag("library-scan-fab").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("New scan").assertCountEquals(0)
    }

    @Test
    fun cancellingDirectToolsImportReturnsToToolsWithoutOpeningNewScan() {
        var importState by mutableStateOf<DocumentImportUiState>(DocumentImportUiState.Idle)
        composeTestRule.setContent {
            PageHarborApp(
                importUiState = importState,
                onImportFiles = { importState = DocumentImportUiState.Selecting },
            )
        }

        composeTestRule.onNodeWithText("Tools").performClick()
        tool("Import files").performClick()
        composeTestRule.runOnIdle { importState = DocumentImportUiState.Cancelled }

        composeTestRule.onAllNodesWithText("Tools").assertCountEquals(2)
        tool("Import files").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("New scan").assertCountEquals(0)
    }

    @Test
    fun cancellingImportFromEmptyToolPickerRestoresThatPicker() {
        var importState by mutableStateOf<DocumentImportUiState>(DocumentImportUiState.Idle)
        composeTestRule.setContent {
            PageHarborApp(
                importUiState = importState,
                onImportFiles = { importState = DocumentImportUiState.Selecting },
            )
        }

        composeTestRule.onNodeWithText("Tools").performClick()
        tool("Extract text with OCR").performClick()
        composeTestRule.onNode(hasText("Import files") and hasAnyAncestor(isDialog())).performClick()
        composeTestRule.runOnIdle { importState = DocumentImportUiState.Cancelled }

        composeTestRule.onNodeWithText("No saved documents yet").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    fun importFilesIsAnImmediatelyAvailableOneTapAction() {
        var importClickCount = 0
        composeTestRule.setContent {
            PageHarborApp(onImportFiles = { importClickCount += 1 })
        }

        composeTestRule.onNodeWithText("Import")
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()

        assertEquals(1, importClickCount)
    }

    @Test
    fun homeScanFabOpensCameraImmediately() {
        var opens = 0
        composeTestRule.setContent { PageHarborApp(onManualScan = { opens++ }) }

        composeTestRule.onNodeWithTag("library-scan-fab")
            .assertIsDisplayed()
            .performClick()

        assertEquals(1, opens)
        composeTestRule.onAllNodesWithText("New scan").assertCountEquals(0)
    }

    @Test
    fun cancellingToolPickerReturnsToToolsWithoutInvokingDocument() {
        var openedDocumentId: String? = null
        composeTestRule.setContent {
            PageHarborApp(
                libraryUiState = savedLibraryState(1),
                onOpenLibraryDocument = { id, _ -> openedDocumentId = id },
            )
        }

        composeTestRule.onNodeWithText("Tools").performClick()
        tool("Extract text with OCR").performClick()
        composeTestRule.onNodeWithText("Choose a document").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cancel").performClick()

        composeTestRule.onAllNodesWithText("Tools").assertCountEquals(2)
        composeTestRule.runOnIdle { assertEquals(null, openedDocumentId) }
    }

    @Test
    fun oneSelectedDocumentMergeAsksForAnotherAndKeepsSelectionLocal() {
        var mergedIds: List<String> = emptyList()
        composeTestRule.setContent {
            PageHarborApp(
                libraryUiState = savedLibraryState(2),
                onMergeLibraryDocuments = { ids, _ -> mergedIds = ids },
            )
        }

        composeTestRule.onNodeWithText("Documents").performClick()
        composeTestRule.onAllNodesWithContentDescription("Select for actions")[0].performClick()
        composeTestRule.onAllNodesWithText("1 selected")[0]
            .assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("More").performClick()
        composeTestRule.onNodeWithText("Merge").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Choose documents to merge").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("1 selected")[0].assertIsDisplayed()
        composeTestRule.onNodeWithText("Choose at least one more document").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Saved document 2")[1].performClick()
        composeTestRule.onNodeWithContentDescription("Continue: Merge").performClick()
        composeTestRule.onNodeWithText("Name merged document").assertIsDisplayed()
        composeTestRule.onNodeWithText("Name").performTextInput("Merged")
        composeTestRule.onNodeWithContentDescription("Confirm Merge").performClick()

        composeTestRule.runOnIdle {
            assertEquals(listOf("saved-1", "saved-2"), mergedIds)
        }
        composeTestRule.onAllNodesWithText("1 selected").assertCountEquals(0)
    }

    @Test
    fun documentSelectionBarStaysVisibleForOneAndMultipleSelections() {
        composeTestRule.setContent {
            PageHarborApp(libraryUiState = savedLibraryState(2))
        }

        composeTestRule.onNodeWithText("Documents").performClick()
        composeTestRule.onAllNodesWithContentDescription("Select for actions")[0].performClick()
        composeTestRule.onNodeWithText("1 selected").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Recognize text").assertIsDisplayed()

        composeTestRule.onAllNodesWithContentDescription("Select for actions")[0].performClick()
        composeTestRule.onNodeWithText("2 selected").assertIsDisplayed()
        composeTestRule.onNodeWithText("Merge").assertIsDisplayed()
    }

    @Test
    fun selectedDocumentExposesExplicitSelectionSemantics() {
        composeTestRule.setContent {
            PageHarborApp(libraryUiState = savedLibraryState(2))
        }

        composeTestRule.onNodeWithText("Documents").performClick()
        composeTestRule.onAllNodesWithContentDescription("Select for actions")[0].performClick()

        composeTestRule.onAllNodes(
            SemanticsMatcher.expectValue(SemanticsProperties.Selected, true) and
                hasText("Saved document 1"),
        ).assertCountEquals(1)
    }

    @Test
    fun cancelledMergeSelectionDoesNotLeakIntoExtractText() {
        composeTestRule.setContent {
            PageHarborApp(libraryUiState = savedLibraryState(2))
        }

        composeTestRule.onNodeWithText("Tools").performClick()
        tool("Merge documents").performClick()
        composeTestRule.onNodeWithText("Saved document 1").performClick()
        composeTestRule.onNodeWithText("Cancel").performClick()
        tool("Extract text with OCR").performClick()

        composeTestRule.onNodeWithText("Choose a document").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Continue: Extract text").assertIsNotEnabled()
        composeTestRule.onAllNodesWithText("1 selected").assertCountEquals(0)
    }

    @Test
    fun toolsSurfaceOmitsSearchAndKeepsAllGroupedLaunchers() {
        composeTestRule.setContent {
            PageHarborApp(libraryUiState = savedLibraryState(2))
        }

        composeTestRule.onNodeWithText("Tools").performClick()
        composeTestRule.onAllNodesWithContentDescription("Search tools").assertCountEquals(0)
        listOf(
            "Import files",
            "Merge documents",
            "Split or extract pages",
            "Extract text with OCR",
        ).forEach { label ->
            tool(label).assertIsDisplayed()
        }
    }

    @Test
    fun importAndMergeLaunchersUseDirectActionAndFocusedPicker() {
        var importClickCount = 0
        var mergedIds: List<String> = emptyList()
        composeTestRule.setContent {
            PageHarborApp(
                libraryUiState = savedLibraryState(2),
                onImportFiles = { importClickCount += 1 },
                onMergeLibraryDocuments = { ids, _ -> mergedIds = ids },
            )
        }

        composeTestRule.onNodeWithText("Tools").performClick()
        tool("Import files").performClick()
        assertEquals(1, importClickCount)

        tool("Merge documents").performClick()
        composeTestRule.onNodeWithText("Choose documents to merge").assertIsDisplayed()
        composeTestRule.onNodeWithText("Saved document 1").performClick()
        composeTestRule.onNodeWithText("Saved document 2").performClick()
        composeTestRule.onNodeWithContentDescription("Continue: Merge").performClick()
        composeTestRule.onNodeWithText("Name merged document").assertIsDisplayed()
        composeTestRule.onNodeWithText("Name").performTextInput("Merged")
        composeTestRule.onNodeWithContentDescription("Confirm Merge").performClick()
        composeTestRule.runOnIdle {
            assertEquals(listOf("saved-1", "saved-2"), mergedIds)
        }
    }

    @Test
    fun splitLauncherOpensFocusedDocumentPicker() {
        composeTestRule.setContent {
            PageHarborApp(libraryUiState = savedLibraryState(1))
        }

        composeTestRule.onNodeWithText("Tools").performClick()
        tool("Split or extract pages").performClick()
        composeTestRule.onNodeWithText("Choose a document").assertIsDisplayed()
        composeTestRule.onNodeWithText("Saved document 1").assertIsDisplayed()
    }

    @Test
    fun ocrLauncherOpensFocusedDocumentPicker() {
        composeTestRule.setContent {
            PageHarborApp(libraryUiState = savedLibraryState(1))
        }

        composeTestRule.onNodeWithText("Tools").performClick()
        tool("Extract text with OCR").performClick()
        composeTestRule.onNodeWithText("Choose a document").assertIsDisplayed()
        composeTestRule.onNodeWithText("Saved document 1").assertIsDisplayed()
    }

    @Test
    fun toolsExtractTextRoutesTheChosenDocumentToExistingOcrAction() {
        var recognizeCount = 0
        var screen by mutableStateOf(org.synapseworks.pageharbor.ui.PageHarborScreen.Home)
        var libraryDocument by mutableStateOf<LibraryDocumentReference?>(null)
        composeTestRule.setContent {
            PageHarborApp(
                screen = screen,
                autoNavigateToScanResult = false,
                scannerSpikeState = scanSummary(jpegPageCount = 1),
                documentPages = listOf(documentPage(1L)),
                libraryDocument = libraryDocument,
                libraryUiState = savedLibraryState(1),
                onOpenLibraryDocument = { id, _ ->
                    libraryDocument = LibraryDocumentReference(id, "Saved document 1")
                    screen = org.synapseworks.pageharbor.ui.PageHarborScreen.ScanResult
                },
                onRecognizeText = { recognizeCount += 1 },
            )
        }

        composeTestRule.onNodeWithText("Tools").performClick()
        tool("Extract text with OCR").performClick()
        composeTestRule.onNodeWithText("Saved document 1").performClick()
        composeTestRule.onNodeWithContentDescription("Continue: Extract text").performClick()

        composeTestRule.waitForIdle()
        assertEquals(1, recognizeCount)
    }

    @Test
    fun toolsSplitRoutesTheChosenDocumentToExistingPageTools() {
        var screen by mutableStateOf(org.synapseworks.pageharbor.ui.PageHarborScreen.Home)
        var libraryDocument by mutableStateOf<LibraryDocumentReference?>(null)
        composeTestRule.setContent {
            PageHarborApp(
                screen = screen,
                autoNavigateToScanResult = false,
                scannerSpikeState = scanSummary(jpegPageCount = 2),
                documentPages = listOf(documentPage(1L), documentPage(2L)),
                libraryDocument = libraryDocument,
                libraryUiState = savedLibraryState(1),
                onOpenLibraryDocument = { id, _ ->
                    libraryDocument = LibraryDocumentReference(id, "Saved document 1")
                    screen = org.synapseworks.pageharbor.ui.PageHarborScreen.ScanResult
                },
            )
        }

        composeTestRule.onNodeWithText("Tools").performClick()
        tool("Split or extract pages").performClick()
        composeTestRule.onNodeWithText("Saved document 1").performClick()
        composeTestRule.onNodeWithContentDescription("Continue: Split / Extract").performClick()

        composeTestRule.onNodeWithText("Page tools").assertIsDisplayed()
    }

    @Test
    fun fitUploadLimitToolChoosesExactDocumentBeforeOpeningSharedWorkflow() {
        var openCount = 0
        var screen by mutableStateOf(org.synapseworks.pageharbor.ui.PageHarborScreen.Home)
        var libraryDocument by mutableStateOf<LibraryDocumentReference?>(null)
        composeTestRule.setContent {
            PageHarborApp(
                screen = screen,
                autoNavigateToScanResult = false,
                scannerSpikeState = scanSummary(jpegPageCount = 1),
                documentPages = listOf(documentPage(1L)),
                libraryDocument = libraryDocument,
                libraryUiState = savedLibraryState(1),
                onOpenLibraryDocument = { id, _ ->
                    libraryDocument = LibraryDocumentReference(id, "Saved document 1")
                    screen = org.synapseworks.pageharbor.ui.PageHarborScreen.ScanResult
                },
                onOpenFitUploadLimit = { openCount += 1 },
            )
        }

        composeTestRule.onNodeWithText("Tools").performClick()
        tool("Fit upload limit. Create a PDF under a chosen file-size limit.").performClick()
        composeTestRule.onNodeWithText("Saved document 1").performClick()
        composeTestRule.onNodeWithContentDescription("Continue: Fit upload limit").performClick()

        composeTestRule.waitForIdle()
        assertEquals(1, openCount)
    }

    @Test
    fun shareSafeToolChoosesExactDocumentBeforeOpeningSharedWorkflow() {
        var openCount = 0
        var screen by mutableStateOf(org.synapseworks.pageharbor.ui.PageHarborScreen.Home)
        var libraryDocument by mutableStateOf<LibraryDocumentReference?>(null)
        composeTestRule.setContent {
            PageHarborApp(
                screen = screen,
                autoNavigateToScanResult = false,
                scannerSpikeState = scanSummary(jpegPageCount = 1),
                documentPages = listOf(documentPage(1L)),
                libraryDocument = libraryDocument,
                libraryUiState = savedLibraryState(1),
                onOpenLibraryDocument = { id, _ ->
                    libraryDocument = LibraryDocumentReference(id, "Saved document 1")
                    screen = org.synapseworks.pageharbor.ui.PageHarborScreen.ScanResult
                },
                onOpenShareSafeCopy = { openCount += 1 },
            )
        }

        composeTestRule.onNodeWithText("Tools").performClick()
        tool("Share-safe copy. Create a local copy with optional redaction, purpose watermark and file-size limit.").performClick()
        composeTestRule.onNodeWithText("Saved document 1").performClick()
        composeTestRule.onNodeWithContentDescription("Continue: Share-safe copy").performClick()

        composeTestRule.waitForIdle()
        assertEquals(1, openCount)
    }

    @Test
    fun toolsShareSafeImportContinuesDirectlyIntoShareSafe() {
        var openCount by mutableStateOf(0)
        var importState by mutableStateOf<DocumentImportUiState>(DocumentImportUiState.Idle)
        var pages by mutableStateOf(emptyList<DocumentPage>())
        var screen by mutableStateOf(org.synapseworks.pageharbor.ui.PageHarborScreen.Home)
        composeTestRule.setContent {
            PageHarborApp(
                screen = screen,
                autoNavigateToScanResult = false,
                importUiState = importState,
                documentPages = pages,
                onScreenChange = { screen = it },
                onImportFiles = { importState = DocumentImportUiState.Selecting },
                onOpenShareSafeCopy = { openCount += 1 },
            )
        }

        composeTestRule.onNodeWithText("Tools").performClick()
        tool("Share-safe copy. Create a local copy with optional redaction, purpose watermark and file-size limit.").performClick()
        composeTestRule.onNodeWithText("Choose a document").assertIsDisplayed()
        composeTestRule.onNode(hasText("Import files") and hasAnyAncestor(isDialog())).performClick()
        composeTestRule.runOnIdle {
            assertEquals(DocumentImportUiState.Selecting, importState)
            pages = listOf(documentPage(1L))
            importState = DocumentImportUiState.Completed(1, 0, appended = false)
        }

        composeTestRule.waitUntil(timeoutMillis = 5_000) { openCount == 1 }
        composeTestRule.runOnIdle {
            assertEquals(org.synapseworks.pageharbor.ui.PageHarborScreen.Home, screen)
        }
    }

    @Test
    fun cancellingShareSafeImportReturnsToPickerWithoutStartingTool() {
        var openCount by mutableStateOf(0)
        var importState by mutableStateOf<DocumentImportUiState>(DocumentImportUiState.Idle)
        composeTestRule.setContent {
            PageHarborApp(
                importUiState = importState,
                onImportFiles = { importState = DocumentImportUiState.Selecting },
                onOpenShareSafeCopy = { openCount += 1 },
            )
        }

        composeTestRule.onNodeWithText("Tools").performClick()
        tool("Share-safe copy. Create a local copy with optional redaction, purpose watermark and file-size limit.").performClick()
        composeTestRule.onNode(hasText("Import files") and hasAnyAncestor(isDialog())).performClick()
        composeTestRule.runOnIdle { importState = DocumentImportUiState.Cancelled }

        composeTestRule.onNodeWithText("Choose a document").assertIsDisplayed()
        composeTestRule.runOnIdle { assertEquals(0, openCount) }
    }

    @Test
    fun documentActionsExposeFitUploadLimitAsTheSameWorkflowEntryPoint() {
        var openCount = 0
        composeTestRule.setContent {
            PageHarborApp(
                scannerSpikeState = scanSummary(jpegPageCount = 1),
                documentPages = listOf(documentPage(1L)),
                onOpenFitUploadLimit = { openCount += 1 },
            )
        }

        openDocumentMore()
        composeTestRule.onNodeWithText("Fit upload limit").performScrollTo().performClick()

        assertEquals(1, openCount)
    }

    @Test
    fun documentActionsExposeShareSafeAsTheSameWorkflowEntryPoint() {
        var openCount = 0
        composeTestRule.setContent {
            PageHarborApp(
                scannerSpikeState = scanSummary(jpegPageCount = 1),
                documentPages = listOf(documentPage(1L)),
                onOpenShareSafeCopy = { openCount += 1 },
            )
        }

        openDocumentMore()
        composeTestRule.onNodeWithText("Share-safe copy").performScrollTo().performClick()

        assertEquals(1, openCount)
    }

    @Test
    fun toolsUseAdaptiveColumnsAndRemainUsableAtTwoHundredPercentFont() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                Box(modifier = androidx.compose.ui.Modifier.size(width = 320.dp, height = 600.dp)) {
                    PageHarborApp(libraryUiState = savedLibraryState(1))
                }
            }
        }

        composeTestRule.onNodeWithText("Tools").performClick()
        val extractText = hasContentDescription("Extract text with OCR")
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(extractText)
        composeTestRule.onNode(extractText).assertIsDisplayed().performClick()
        composeTestRule.onNodeWithText("Choose a document").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Scan").assertIsDisplayed()
    }

    @Test
    fun internalPickerSearchAndActionsRemainReachableAtTwoHundredPercentFont() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                Box(modifier = androidx.compose.ui.Modifier.size(width = 320.dp, height = 600.dp)) {
                    PageHarborApp(libraryUiState = savedLibraryState(2))
                }
            }
        }

        composeTestRule.onNodeWithText("Tools").performClick()
        val mergeDocuments = hasContentDescription("Merge documents")
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(mergeDocuments)
        composeTestRule.onNode(mergeDocuments).performClick()
        composeTestRule.onNode(hasSetTextAction()).performTextInput("Saved document 2")
        composeTestRule.onAllNodesWithText("Saved document 2")[1]
            .assertIsDisplayed()
            .performClick()
        composeTestRule.onNodeWithContentDescription("Continue: Merge").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    fun moreGroupedRowsRemainReachableAtTwoHundredPercentFont() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                Box(modifier = androidx.compose.ui.Modifier.size(width = 320.dp, height = 600.dp)) {
                    PageHarborApp(libraryHasDocuments = true)
                }
            }
        }

        composeTestRule.onNodeWithText("More").performClick()
        composeTestRule.onNodeWithText("Data & backup").assertIsDisplayed()
        composeTestRule.onNodeWithText("About RME PDF Scanner").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun toolsWithNoSavedDocumentsOpenActionableEmptyPicker() {
        var importClickCount = 0
        composeTestRule.setContent {
            PageHarborApp(onImportFiles = { importClickCount += 1 })
        }

        composeTestRule.onNodeWithText("Tools").performClick()
        tool("Import files")
            .assertIsEnabled()
            .performClick()
        assertEquals(1, importClickCount)

        tool("Extract text with OCR")
            .assertIsEnabled()
            .performClick()
        composeTestRule.onNodeWithText("No saved documents yet").assertIsDisplayed()
        composeTestRule.onNode(hasText("Import files") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        composeTestRule.onNodeWithText("Scan").assertIsEnabled()
        composeTestRule.onAllNodesWithContentDescription("Continue: Extract text")
            .assertCountEquals(0)
    }

    @Test
    fun toolsWithOneSavedDocumentKeepMergeActionable() {
        composeTestRule.setContent {
            PageHarborApp(libraryUiState = savedLibraryState(1))
        }

        composeTestRule.onNodeWithText("Tools").performClick()
        tool("Merge documents").assertIsEnabled()
        tool("Split or extract pages").assertIsEnabled()
        tool("Extract text with OCR").assertIsEnabled()
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
            tool(description).assertIsEnabled()
        }
    }

    @Test
    fun toolsRemainActionableWhenSavedDocumentCountChanges() {
        val libraryState = mutableStateOf(savedLibraryState(2))
        composeTestRule.setContent {
            PageHarborApp(libraryUiState = libraryState.value)
        }

        composeTestRule.onNodeWithText("Tools").performClick()
        tool("Merge documents").assertIsEnabled()
        composeTestRule.runOnIdle { libraryState.value = savedLibraryState(1) }
        tool("Merge documents").assertIsEnabled()
        tool("Split or extract pages").assertIsEnabled()
    }

    @Test
    fun toolsExposeActionableOcrWithoutSearchChrome() {
        composeTestRule.setContent {
            PageHarborApp()
        }

        composeTestRule.onNodeWithText("Tools").performClick()
        composeTestRule.onAllNodesWithContentDescription("Search tools").assertCountEquals(0)
        tool("Extract text with OCR")
            .assertIsDisplayed().assertIsEnabled()
        tool("Import files").assertIsDisplayed()
    }

    @Test
    fun toolsActionableStatesRemainVisibleInDarkTheme() {
        composeTestRule.setContent {
            PageHarborApp(darkTheme = true)
        }

        composeTestRule.onNodeWithText("Tools").performClick()
        tool("Import files").assertIsEnabled()
        tool("Merge documents")
            .assertIsDisplayed().assertIsEnabled()
        tool("Extract text with OCR")
            .assertIsDisplayed().assertIsEnabled()
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
            val tool = hasContentDescription(description)
            composeTestRule.onNode(hasScrollAction()).performScrollToNode(tool)
            composeTestRule.onNode(tool)
                .assertIsDisplayed()
                .assertIsEnabled()
        }
    }

    @Test
    fun preparingStateDisablesScanActionAndShowsProgress() {
        composeTestRule.setContent {
            PageHarborApp(scannerSpikeState = ScannerSpikeState.Preparing)
        }

        composeTestRule.onNodeWithContentDescription("Scan")
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

        composeTestRule.onNodeWithContentDescription("Scan").assertIsDisplayed()
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

        composeTestRule.onNodeWithContentDescription("Scan")
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
            documentMoreAction(action).performScrollTo().assertIsDisplayed()
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
        composeTestRule.onNodeWithText("Filter").performScrollTo().performClick()
        composeTestRule.onNodeWithText("Grayscale").performClick()

        composeTestRule.runOnIdle {
            assertEquals(DocumentFilter.GRAYSCALE, selectedFilter.value)
        }
        composeTestRule.onNodeWithContentDescription("Document preview, page 1 of 1")
            .assertIsDisplayed()
        openDocumentEdit()
        composeTestRule.onNodeWithText("Filter").performScrollTo().performClick()
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

        composeTestRule.onNodeWithText("Importing file 1 of 3 · 1 page ready…").assertIsDisplayed()
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
        openDocumentEdit()
        composeTestRule.onNodeWithText("Move later").performScrollTo().performClick()
        openDocumentEdit()
        composeTestRule.onNodeWithText("Remove page").performScrollTo().performClick()
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
        documentMoreAction("Export PDF")
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
        documentMoreAction("Export PDF")
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
        documentMoreAction("Export PDF").assertIsNotEnabled()
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

        composeTestRule.onNodeWithTag("document-export-pdf").performClick()

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
        documentMoreAction("Export PDF")
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
        documentMoreAction("Export PDF")
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
        documentMoreAction("Export PDF")
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
        documentMoreAction("Export PDF").performScrollTo().assertIsEnabled()
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
        documentMoreAction("Export PDF").assertIsEnabled()
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
        documentMoreAction("Export PDF")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
        composeTestRule.onAllNodesWithText("Saving PDF…").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("PDF saved").assertCountEquals(0)
    }

    @Test
    fun discardCanBeCancelledAndRequiresConfirmation() {
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
        documentMoreAction("Discard").performScrollTo().performClick()
        composeTestRule.onNodeWithText("Discard this working copy?").assertIsDisplayed()
        assertEquals(0, clearClickCount)
        composeTestRule.onNodeWithText("Cancel").performClick()
        composeTestRule.onNodeWithText("Discard this working copy?").assertDoesNotExist()
        assertEquals(0, clearClickCount)

        openDocumentMore()
        documentMoreAction("Discard").performScrollTo().performClick()
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

        composeTestRule.onNodeWithText("More").performClick()
        composeTestRule.onNodeWithText("How privacy works").performScrollTo().performClick()

        val disclosures = listOf(
            "RME processes your documents on this device.",
            "Save to RME keeps a private library copy. Deleted documents stay in the Recycle bin until you permanently delete them or clear app data.",
            "Document scan uses the Google scanner. RME asks for camera access when you choose direct capture for continuous scans, QR codes, cards, receipts or tables. You can also import a file.",
            "RME PDF Scanner does not operate cloud storage.",
            "You choose where to save or share copies. The destination may have its own cloud storage.",
            "RME PDF Scanner does not operate advertising, tracking, or analytics.",
            "Google ML Kit may send encrypted diagnostics and usage metrics. Your images and recognized text stay on the device during recognition.",
        )
        disclosures.forEach { disclosure ->
            composeTestRule.onNodeWithText(disclosure).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun privacyDialogCanBeDismissed() {
        composeTestRule.setContent {
            PageHarborApp()
        }

        composeTestRule.onNodeWithText("More").performClick()
        composeTestRule.onNodeWithText("How privacy works").performScrollTo().performClick()
        composeTestRule.onNodeWithText("OK").performClick()

        composeTestRule.onAllNodesWithText("RME processes your documents on this device.")
            .assertCountEquals(0)
    }

    @Test
    fun clickingAboutShowsAppAndAttributionInformation() {
        composeTestRule.setContent {
            PageHarborApp()
        }

        composeTestRule.onNodeWithText("More").performClick()
        composeTestRule.onNodeWithText("About RME PDF Scanner").performScrollTo().performClick()

        composeTestRule.onNodeWithText("About RME: Private PDF Scanner").assertIsDisplayed()
        composeTestRule.onNodeWithText("Private document scanner for Android")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Version: ${BuildConfig.VERSION_NAME}")
            .assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Version: 1.5.0").assertCountEquals(0)
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
        composeTestRule.onNodeWithText("About RME PDF Scanner").performScrollTo().performClick()
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
        composeTestRule.onNodeWithText("About RME PDF Scanner").performScrollTo().performClick()
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

        composeTestRule.onNodeWithContentDescription("OCR").performClick()

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
    fun searchablePdfLanguageCancelAndRepeatedLatinConfirmationAreOneShot() {
        val confirmations = mutableListOf<OcrOperationSelection>()
        composeTestRule.setContent {
            PageHarborApp(
                scannerSpikeState = scanSummary(jpegPageCount = 1),
                documentPages = listOf(documentPage(1L)),
                ocrDefaultSelection = OcrScriptSelection.Explicit(OcrScript.LATIN),
                onSaveSearchablePdfWithLanguage = { selection, _ ->
                    confirmations += selection
                },
            )
        }

        openDocumentMore()
        composeTestRule.onNodeWithText("Save searchable PDF").performScrollTo().performClick()
        composeTestRule.onNodeWithText("Cancel").performClick()
        composeTestRule.runOnIdle { assertTrue(confirmations.isEmpty()) }

        openDocumentMore()
        composeTestRule.onNodeWithText("Save searchable PDF").performScrollTo().performClick()
        composeTestRule.onNodeWithText("Recognize text").performClick()
        composeTestRule.runOnIdle {
            assertEquals(listOf(OcrOperationSelection.UseDefault), confirmations)
        }

        openDocumentMore()
        composeTestRule.onNodeWithText("Save searchable PDF").performScrollTo().performClick()
        composeTestRule.onNodeWithText("Latin").performClick()
        composeTestRule.onNodeWithText("Recognize text").performClick()
        composeTestRule.runOnIdle {
            assertEquals(
                listOf(
                    OcrOperationSelection.UseDefault,
                    OcrOperationSelection.Override(OcrScript.LATIN),
                ),
                confirmations,
            )
        }
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
        documentMoreAction("Export PDF").assertIsEnabled()
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
                onManualScan = { addPagesCalls += 1 },
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
        composeTestRule.onNodeWithText("Filter").performScrollTo().performClick()
        composeTestRule.onNodeWithContentDescription("Original")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        composeTestRule.onNodeWithText("Done").performClick()
        openDocumentAdd()
        composeTestRule.onNodeWithText("Scan pages").performClick()

        assertEquals(1, addPagesCalls)
    }

    @Test
    fun twentyPageScanNavigatesToTheLastPageAndAllowsAnotherScan() {
        var addPagesCalls = 0
        composeTestRule.setContent {
            PageHarborApp(
                scannerSpikeState = scanSummary(jpegPageCount = LEGACY_UI_PAGE_COUNT),
                documentPages = (1L..LEGACY_UI_PAGE_COUNT.toLong()).map { id ->
                    documentPage(id)
                },
                onManualScan = { addPagesCalls += 1 },
            )
        }

        repeat(LEGACY_UI_PAGE_COUNT - 1) {
            composeTestRule.onNodeWithContentDescription("Next page").performClick()
        }

        composeTestRule.onNodeWithText("Page 20 of 20").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Next page").assertIsNotEnabled()
        openDocumentAdd()
        composeTestRule.onNodeWithText("Scan pages").assertIsEnabled().performClick()
        assertEquals(1, addPagesCalls)
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
        composeTestRule.onNodeWithText("Filter").performScrollTo().performClick()
        listOf("Original", "Enhance", "Grayscale", "B&W").forEach { label ->
            composeTestRule.onAllNodesWithText(label).assertCountEquals(1)
        }
        composeTestRule.onNodeWithText("Done").performClick()
        openDocumentMore()
        listOf("Export PDF", "Save searchable PDF", "Export Pages", "Discard").forEach { action ->
            documentMoreAction(action).performScrollTo().assertIsDisplayed()
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
        composeTestRule.onNodeWithContentDescription("Edit").assertIsDisplayed().performClick()
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
        documentMoreAction("Export PDF").assertIsEnabled()
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
        composeTestRule.onNodeWithText("Scan").performClick()
        composeTestRule.onNodeWithContentDescription("Document preview, page 1 of 2")
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Previous page").performScrollTo().assertIsNotEnabled()
        composeTestRule.onNodeWithText("Next page").performClick()
        composeTestRule.onNodeWithText("Text").performScrollTo().performClick()

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

        composeTestRule.onNodeWithContentDescription("OCR").performClick()
        composeTestRule.onNodeWithText("Heading\n  Preserved indentation")
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Copy text").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Text options").performClick()
        composeTestRule.onNodeWithText("Recognize Again").assertIsDisplayed()
        composeTestRule.onNodeWithText("Clear").assertIsDisplayed()
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
        composeTestRule.onNodeWithContentDescription("Text options").performClick()
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
        composeTestRule.onNodeWithText("Copy page").assertIsDisplayed()
        composeTestRule.onNodeWithText("Copy all").assertIsDisplayed()
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
        composeTestRule.onNodeWithContentDescription("Text options").performClick()
        composeTestRule.onNodeWithText("Clear").performClick()
        composeTestRule.onNodeWithText("Clear recognized text?").assertIsDisplayed()
        composeTestRule.onNodeWithText("Clear").performClick()

        assertEquals(1, clearCallCount)
        composeTestRule.onNodeWithText("Share").assertIsEnabled()
        openDocumentMore()
        documentMoreAction("Export PDF").assertIsEnabled()
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
    fun tableReadoutFindAndScopedCopyKeepCanonicalTextAndLeadingZeros() {
        val table = "CODE\tITEM\tQTY\n0012\tBLUE BOOK\t2\n0100\tCOFFEE\t10\n0200\tPAPER\t5"
        var copied: String? = null
        composeTestRule.setContent {
            PageHarborApp(
                scannerSpikeState = scanSummary(jpegPageCount = 2),
                documentPages = listOf(documentPage(1L), documentPage(2L)),
                ocrUiState = OcrUiState.Success(
                    OcrResult(listOf(OcrPageResult(0, table), OcrPageResult(1, "Second page"))),
                ),
                onCopyRecognizedText = { copied = it },
            )
        }
        composeTestRule.onNodeWithText("OCR").performClick()
        composeTestRule.onNodeWithContentDescription("Find text").performClick()
        composeTestRule.onNodeWithText("Find in document").performTextInput("book")
        composeTestRule.onNodeWithText("1 match").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Close search").performClick()
        composeTestRule.onNodeWithText("Copy page").performClick()
        assertEquals(table, copied)
        composeTestRule.onNodeWithText("Copy all").performClick()
        assertTrue(requireNotNull(copied).contains(table))
        assertTrue(requireNotNull(copied).contains("Second page"))
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
        documentMoreAction("Export PDF").assertIsEnabled()
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
        documentMoreAction("Export PDF").assertIsEnabled()
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

        composeTestRule.onNodeWithContentDescription("Edit").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithText("Filter").assertIsDisplayed()
        composeTestRule.onNodeWithText("Done").performClick()
        openDocumentMore()
        documentMoreAction("Export PDF").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Save searchable PDF").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Discard").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun phase7dPortabilitySurfacesRemainReachableAcrossResponsiveWidthsAtLargeFont() {
        val width = mutableStateOf(320.dp)
        var workflow by mutableStateOf<PortabilityWorkflowState>(
            PortabilityWorkflowState.MigrationSource(ScannerMigrationSource.MICROSOFT_LENS),
        )
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                Box(modifier = androidx.compose.ui.Modifier.size(width = width.value, height = 900.dp)) {
                    PageHarborApp(
                        libraryHasDocuments = false,
                        portabilityState = workflow,
                    )
                }
            }
        }

        listOf(320.dp, 600.dp, 840.dp).forEach { targetWidth ->
            composeTestRule.runOnIdle { width.value = targetWidth }
            composeTestRule.onNodeWithText("Microsoft Lens").performScrollTo().assertIsDisplayed()
            composeTestRule.onNodeWithText("Select files").performScrollTo().assertIsDisplayed()
            composeTestRule.onNodeWithText("Select folder").performScrollTo().assertIsDisplayed()
        }

        composeTestRule.runOnIdle {
            workflow = PortabilityWorkflowState.BackupRestore(
                BackupVerificationStatusUiModel.NeverBackedUp,
            )
        }
        listOf(320.dp, 600.dp, 840.dp).forEach { targetWidth ->
            composeTestRule.runOnIdle { width.value = targetWidth }
            composeTestRule.onNodeWithText("No documents to back up")
                .performScrollTo()
                .assertIsDisplayed()
            composeTestRule.onNodeWithText("Restore backup").performScrollTo().assertIsDisplayed()
        }

        composeTestRule.runOnIdle { workflow = PortabilityWorkflowState.NewPhone() }
        listOf(320.dp, 600.dp, 840.dp).forEach { targetWidth ->
            composeTestRule.runOnIdle { width.value = targetWidth }
            composeTestRule.onNodeWithText("No documents to move yet.")
                .performScrollTo()
                .assertIsDisplayed()
            composeTestRule.onNodeWithText("Restore backup").performScrollTo().assertIsDisplayed()
        }
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

        composeTestRule.onNodeWithContentDescription("OCR").performClick()
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
            documentMoreAction(action).performScrollTo().assertIsDisplayed()
        }
    }

    private fun tool(label: String): SemanticsNodeInteraction {
        val tile = hasContentDescription(label)
        composeTestRule.onNodeWithTag("tools-grid").performScrollToNode(tile)
        return composeTestRule.onNode(tile)
    }

    private fun documentMoreAction(label: String): SemanticsNodeInteraction =
        composeTestRule.onNode(hasText(label) and hasAnyAncestor(hasTestTag("document-more-sheet")))

    private fun openDocumentMore() {
        composeTestRule.onNodeWithContentDescription("More").performClick()
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

    @Test
    fun fullPreviewReturnsToTheSameDocumentPage() {
        composeTestRule.setContent {
            PageHarborApp(scannerSpikeState = scanSummary(jpegPageCount = 1), documentPages = listOf(documentPage(1L)))
        }
        composeTestRule.onNodeWithContentDescription("Full-screen preview").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithTag("document-full-preview-dialog").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Close full-screen preview").performClick()
        composeTestRule.onNodeWithText("Export PDF").assertIsDisplayed()
    }

    @Test
    fun documentSearchJumpsBetweenPagesAndLanguageChooserKeepsResultsOnCancel() {
        val selected = mutableStateOf(0)
        var recognitionCount = 0
        composeTestRule.setContent {
            PageHarborApp(
                scannerSpikeState = scanSummary(jpegPageCount = 2),
                documentPages = listOf(documentPage(1L), documentPage(2L)),
                ocrUiState = OcrUiState.Success(OcrResult(listOf(
                    OcrPageResult(0, "Book one", actualScript = org.synapseworks.pageharbor.ocr.OcrScript.LATIN),
                    OcrPageResult(1, "Second book", actualScript = org.synapseworks.pageharbor.ocr.OcrScript.LATIN),
                ))),
                ocrSelectedPageIndex = selected.value,
                onOcrSelectedPageChange = { selected.value = it },
                onRecognizeTextWithLanguage = { _, _ -> recognitionCount++ },
            )
        }
        composeTestRule.onNodeWithText("OCR").performClick()
        composeTestRule.onNodeWithText("OCR: Latin").assertIsDisplayed()
        composeTestRule.onNodeWithText("Change OCR language").performClick()
        composeTestRule.onNodeWithText("Text recognition language").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cancel").performClick()
        assertEquals(0, recognitionCount)
        composeTestRule.onNodeWithText("Book one").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Find text").performClick()
        composeTestRule.onNodeWithText("Find in document").performTextInput("book")
        composeTestRule.waitUntil(5_000) { composeTestRule.onAllNodesWithText("2 matches").fetchSemanticsNodes().isNotEmpty() }
        composeTestRule.onNodeWithContentDescription("Next match").performClick()
        composeTestRule.onNodeWithContentDescription("Next match").performClick()
        assertEquals(1, selected.value)
        composeTestRule.onNodeWithText("Match 2 of 2").assertIsDisplayed()
        composeTestRule.onNodeWithText("Second book").assertIsDisplayed()
    }
}

private fun SemanticsNodeInteraction.assertNodeExists(): SemanticsNodeInteraction =
    assert(SemanticsMatcher("node exists") { true })
