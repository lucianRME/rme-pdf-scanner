package org.synapseworks.pageharbor

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.synapseworks.pageharbor.document.session.DocumentPage
import org.synapseworks.pageharbor.document.session.DocumentPageId
import org.synapseworks.pageharbor.document.session.DocumentResource
import org.synapseworks.pageharbor.document.session.DocumentResourceOwnership
import org.synapseworks.pageharbor.document.session.DocumentSourceCategory
import org.synapseworks.pageharbor.library.LibraryDocumentSummary
import org.synapseworks.pageharbor.library.LibraryOcrStatus
import org.synapseworks.pageharbor.library.LibraryUiState
import org.synapseworks.pageharbor.scanner.ScannerSpikeState
import org.synapseworks.pageharbor.ui.PageHarborApp
import org.synapseworks.pageharbor.ui.PageHarborScreen
import org.synapseworks.pageharbor.ui.portability.MigrationPreviewUiModel
import org.synapseworks.pageharbor.ui.portability.MigrationProgressUiModel
import org.synapseworks.pageharbor.ui.portability.PortabilityCallbacks
import org.synapseworks.pageharbor.ui.portability.PortabilityWorkflowState
import org.synapseworks.pageharbor.ui.portability.ScannerMigrationSource

class BackBehaviorTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun topLevelFirstBackShowsHintAndSecondBackWithinWindowRequestsExit() {
        var now = 1_000L
        var exitCount = 0
        composeTestRule.setContent {
            PageHarborApp(
                exitBackClock = { now },
                onExitApp = { exitCount += 1 },
            )
        }

        pressBack()
        composeTestRule.onNodeWithText("Press back again to exit").assertIsDisplayed()
        assertEquals(0, exitCount)

        now += 1_000L
        pressBack()
        composeTestRule.runOnIdle { assertEquals(1, exitCount) }
    }

    @Test
    fun topLevelBackAfterTimeoutShowsHintAgainWithoutExit() {
        var now = 1_000L
        var exitCount = 0
        composeTestRule.setContent {
            PageHarborApp(
                exitBackClock = { now },
                onExitApp = { exitCount += 1 },
            )
        }

        pressBack()
        now += 2_001L
        pressBack()

        composeTestRule.onNodeWithText("Press back again to exit").assertIsDisplayed()
        composeTestRule.runOnIdle { assertEquals(0, exitCount) }
    }

    @Test
    fun toolsSearchConsumesBackBeforeTopLevelExitHandling() {
        var exitCount = 0
        composeTestRule.setContent {
            PageHarborApp(onExitApp = { exitCount += 1 })
        }

        composeTestRule.onNodeWithText("Tools").performClick()
        composeTestRule.onNodeWithContentDescription("Search tools").performTextInput("OCR")
        pressBack()

        composeTestRule.onNodeWithContentDescription(
            "Extract text. Unavailable. Requires a saved document. OCR.",
        ).assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Press back again to exit").assertCountEquals(0)
        pressBack()

        composeTestRule.onNodeWithContentDescription("Import files").assertIsDisplayed()
        composeTestRule.runOnIdle { assertEquals(0, exitCount) }
    }

    @Test
    fun visibleDialogConsumesBackBeforeTopLevelExitHandling() {
        var exitCount = 0
        composeTestRule.setContent {
            PageHarborApp(onExitApp = { exitCount += 1 })
        }

        composeTestRule.onNodeWithText("More").performClick()
        composeTestRule.onNodeWithText("About RME PDF Scanner").performScrollTo().performClick()
        composeTestRule.onNodeWithText("Open source under Apache License 2.0").assertIsDisplayed()
        pressBack()

        composeTestRule.onAllNodesWithText("Open source under Apache License 2.0")
            .assertCountEquals(0)
        composeTestRule.onAllNodesWithText("Press back again to exit").assertCountEquals(0)
        composeTestRule.runOnIdle { assertEquals(0, exitCount) }
    }

    @Test
    fun migrationBackAndCancelStayInMigrationAndReturnToMore() {
        var workflow by mutableStateOf<PortabilityWorkflowState>(PortabilityWorkflowState.Hidden)
        composeTestRule.setContent {
            PageHarborApp(
                portabilityState = workflow,
                portabilityCallbacks = PortabilityCallbacks(
                    onBack = {
                        workflow = when {
                            workflow is PortabilityWorkflowState.MigrationPreview ||
                                workflow is PortabilityWorkflowState.MigrationProgress ->
                                PortabilityWorkflowState.MigrationSource()
                            else -> PortabilityWorkflowState.Hidden
                        }
                    },
                    onCancelMigration = {
                        workflow = PortabilityWorkflowState.MigrationSource()
                    },
                ),
            )
        }

        composeTestRule.onNodeWithText("More").performClick()
        composeTestRule.runOnIdle { workflow = migrationPreview() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Cancel").performScrollTo().performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            workflow is PortabilityWorkflowState.MigrationSource
        }
        composeTestRule.onNodeWithText("Choose your scanner").assertIsDisplayed()

        pressBack()
        composeTestRule.onNodeWithText("Move from another scanner").assertIsDisplayed()
        composeTestRule.onNodeWithText("Data & backup").assertIsDisplayed()
        composeTestRule.onNodeWithText("RME PDF Scanner").assertIsDisplayed()
    }

    @Test
    fun migrationPreviewBackReturnsToSourceAndActiveCancelReturnsToSource() {
        var workflow by mutableStateOf<PortabilityWorkflowState>(PortabilityWorkflowState.Hidden)
        composeTestRule.setContent {
            PageHarborApp(
                portabilityState = workflow,
                portabilityCallbacks = PortabilityCallbacks(
                    onBack = {
                        workflow = when {
                            workflow is PortabilityWorkflowState.MigrationPreview ||
                                workflow is PortabilityWorkflowState.MigrationProgress ->
                                PortabilityWorkflowState.MigrationSource()
                            else -> PortabilityWorkflowState.Hidden
                        }
                    },
                    onCancelMigration = {
                        workflow = PortabilityWorkflowState.MigrationSource()
                    },
                ),
            )
        }

        composeTestRule.onNodeWithText("More").performClick()
        composeTestRule.runOnIdle { workflow = migrationPreview() }
        composeTestRule.waitForIdle()
        pressBack()
        composeTestRule.onNodeWithText("Choose your scanner").assertIsDisplayed()

        composeTestRule.runOnIdle {
            workflow = MigrationProgressUiModel(
                completedDocuments = 0,
                totalDocuments = 1,
                currentDocument = null,
                currentStage = "Preparing",
                skippedItems = 0,
                failedItems = 0,
            ).let(PortabilityWorkflowState::MigrationProgress)
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Cancel safely").performScrollTo().performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            workflow is PortabilityWorkflowState.MigrationSource
        }
        composeTestRule.onNodeWithText("Choose your scanner").assertIsDisplayed()
    }

    @Test
    fun visibleDocumentMenuConsumesBackBeforeTopLevelExitHandling() {
        var exitCount = 0
        val saved = savedDocument()
        composeTestRule.setContent {
            PageHarborApp(
                libraryUiState = LibraryUiState(
                    documents = listOf(saved),
                    recentDocuments = listOf(saved),
                ),
                onExitApp = { exitCount += 1 },
            )
        }

        composeTestRule.onNodeWithContentDescription("More actions for Saved document")
            .performClick()
        composeTestRule.onNodeWithText("Rename").assertIsDisplayed()
        pressBack()

        composeTestRule.onAllNodesWithText("Rename").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("Press back again to exit").assertCountEquals(0)
        composeTestRule.runOnIdle { assertEquals(0, exitCount) }
    }

    @Test
    fun documentWorkspaceBackReturnsHomeWithoutExitHint() {
        var exitCount = 0
        composeTestRule.setContent {
            PageHarborApp(
                scannerSpikeState = ScannerSpikeState.ResultSummary(1, false, null),
                documentPages = listOf(page(1L)),
                onExitApp = { exitCount += 1 },
            )
        }

        composeTestRule.onNodeWithText("Document").assertIsDisplayed()
        pressBack()

        composeTestRule.onNodeWithText("RME PDF Scanner").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Press back again to exit").assertCountEquals(0)
        composeTestRule.runOnIdle { assertEquals(0, exitCount) }
    }

    @Test
    fun documentEditorConsumesBackBeforeWorkspaceNavigation() {
        var observedScreen: PageHarborScreen? = null
        composeTestRule.setContent {
            PageHarborApp(
                scannerSpikeState = ScannerSpikeState.ResultSummary(1, false, null),
                documentPages = listOf(page(1L)),
                onScreenChange = { observedScreen = it },
            )
        }

        composeTestRule.onNodeWithText("Edit").performClick()
        composeTestRule.onNodeWithText("Edit page").assertIsDisplayed()
        composeTestRule.runOnIdle { observedScreen = null }
        pressBack()

        composeTestRule.onAllNodesWithText("Edit page").assertCountEquals(0)
        composeTestRule.onNodeWithText("Document").assertIsDisplayed()
        composeTestRule.runOnIdle { assertEquals(null, observedScreen) }
    }

    @Test
    fun documentSheetConsumesBackBeforeWorkspaceNavigation() {
        var observedScreen: PageHarborScreen? = null
        composeTestRule.setContent {
            PageHarborApp(
                scannerSpikeState = ScannerSpikeState.ResultSummary(1, false, null),
                documentPages = listOf(page(1L)),
                onScreenChange = { observedScreen = it },
            )
        }

        composeTestRule.onNodeWithText("Add").performClick()
        composeTestRule.onNodeWithText("Add to document").assertIsDisplayed()
        composeTestRule.runOnIdle { observedScreen = null }
        pressBack()

        composeTestRule.onAllNodesWithText("Add to document").assertCountEquals(0)
        composeTestRule.onNodeWithText("Document").assertIsDisplayed()
        composeTestRule.runOnIdle { assertEquals(null, observedScreen) }
    }

    private fun pressBack() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        composeTestRule.waitForIdle()
    }

    private fun page(id: Long) = DocumentPage(
        id = DocumentPageId(id),
        source = DocumentResource(
            reference = "content://org.synapseworks.pageharbor.test/page/$id",
            ownership = DocumentResourceOwnership.USER_OR_EXTERNAL,
        ),
        sourceCategory = DocumentSourceCategory.SCAN,
    )

    private fun savedDocument() = LibraryDocumentSummary(
        id = "saved-1",
        title = "Saved document",
        createdAtMillis = 1L,
        modifiedAtMillis = 1L,
        pageCount = 1,
        folderId = null,
        folderName = null,
        thumbnailRelativePath = null,
        ocrStatus = LibraryOcrStatus.NOT_INDEXED,
    )

    private fun migrationPreview() = PortabilityWorkflowState.MigrationPreview(
        preview = MigrationPreviewUiModel(
            source = ScannerMigrationSource.CAMSCANNER,
            documentCount = 1,
            pageCount = 1,
            folderCount = 0,
            exactDuplicateCount = 0,
            possibleDuplicateCount = 0,
            unsupportedFileCount = 0,
            estimatedStorage = "1 KiB",
        ),
    )
}
