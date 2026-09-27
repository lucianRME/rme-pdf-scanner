package org.synapseworks.pageharbor.ui.ocr

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.synapseworks.pageharbor.ocr.batch.OcrBatchMode
import org.synapseworks.pageharbor.ocr.batch.OcrBatchProgress
import org.synapseworks.pageharbor.ocr.batch.OcrBatchSummary
import org.synapseworks.pageharbor.ocr.batch.OcrBatchUiState
import org.synapseworks.pageharbor.library.LibraryDocumentSummary
import org.synapseworks.pageharbor.library.LibraryOcrStatus
import org.synapseworks.pageharbor.library.LibraryUiState
import org.synapseworks.pageharbor.ui.PageHarborApp
import org.synapseworks.pageharbor.ui.theme.PageHarborTheme

@RunWith(AndroidJUnit4::class)
class OcrBatchDialogsTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun documentMultiSelectEntryStartsRecognizeTextForStableIds() {
        var selectedIds = emptyList<String>()
        composeRule.setContent {
            PageHarborApp(
                libraryUiState = LibraryUiState(
                    documents = listOf(document("document-one", "Document one")),
                    recentDocuments = listOf(document("document-one", "Document one")),
                ),
                onOpenOcrBatch = { selectedIds = it },
            )
        }
        composeRule.onNodeWithContentDescription("Select for actions")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithContentDescription("Recognize text")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.runOnIdle { assertEquals(listOf("document-one"), selectedIds) }
    }

    @Test
    fun setupOffersMissingAndRerunModesThenLanguageStep() {
        var selected = OcrBatchMode.MISSING_ONLY
        var choseLanguage = false
        composeRule.setContent {
            PageHarborTheme {
                OcrBatchSetupDialog(
                    state = OcrBatchUiState.Setup(listOf("one", "two"), selected),
                    onModeChange = { selected = it },
                    onChooseLanguage = { choseLanguage = true },
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText("Selected documents: 2").assertIsDisplayed()
        composeRule.onNodeWithText("Re-run OCR").performClick()
        composeRule.runOnIdle { assertEquals(OcrBatchMode.RERUN, selected) }
        composeRule.onNodeWithText("Choose language").performClick()
        composeRule.runOnIdle { assertTrue(choseLanguage) }
    }

    @Test
    fun runningShowsDocumentPageAndOutcomeProgressAndCanCancel() {
        var cancelled = false
        composeRule.setContent {
            PageHarborTheme {
                OcrBatchStatusDialog(
                    state = OcrBatchUiState.Running(
                        jobId = "job",
                        progress = OcrBatchProgress(
                            documentTotal = 5,
                            completedDocuments = 2,
                            pageTotal = 12,
                            completedPages = 4,
                            skippedPages = 1,
                            failedPages = 1,
                            currentDocumentTitle = "Local receipt",
                        ),
                    ),
                    onCancel = { cancelled = true },
                    onRetryFailed = {},
                    onDone = {},
                )
            }
        }

        composeRule.onNodeWithText("Document progress: 2/5").assertIsDisplayed()
        composeRule.onNodeWithText("Page progress: 6/12").assertIsDisplayed()
        composeRule.onNodeWithText("Current: Local receipt").assertIsDisplayed()
        composeRule.onNodeWithText("Completed: 4 · Skipped: 1 · Failed: 1").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel safely").performClick()
        composeRule.runOnIdle { assertTrue(cancelled) }
    }

    @Test
    fun partialCompletionOffersRetryAndDone() {
        var retried = false
        var done = false
        composeRule.setContent {
            PageHarborTheme {
                OcrBatchStatusDialog(
                    state = finished(retryable = 2),
                    onCancel = {},
                    onRetryFailed = { retried = true },
                    onDone = { done = true },
                )
            }
        }
        composeRule.onNodeWithText("Retry failed").performClick()
        composeRule.onNodeWithText("Done").performClick()
        composeRule.runOnIdle {
            assertTrue(retried)
            assertTrue(done)
        }
    }

    @Test
    fun successfulCompletionDoesNotOfferRetry() {
        composeRule.setContent {
            PageHarborTheme {
                OcrBatchStatusDialog(
                    state = finished(retryable = 0),
                    onCancel = {},
                    onRetryFailed = {},
                    onDone = {},
                )
            }
        }
        composeRule.onNodeWithText("Batch complete").assertIsDisplayed()
        composeRule.onNodeWithText("Retry failed").assertDoesNotExist()
    }

    @Test
    fun cancelledCompletionIsExplicitAndDoesNotOfferRetry() {
        composeRule.setContent {
            PageHarborTheme {
                OcrBatchStatusDialog(
                    state = finished(retryable = 1, cancelled = true),
                    onCancel = {},
                    onRetryFailed = {},
                    onDone = {},
                )
            }
        }
        composeRule.onNodeWithText("Batch cancelled").assertIsDisplayed()
        composeRule.onNodeWithText("Retry failed").assertDoesNotExist()
    }

    private fun finished(retryable: Int, cancelled: Boolean = false) = OcrBatchUiState.Finished(
        jobId = "job",
        summary = OcrBatchSummary(
            documentTotal = 3,
            pageTotal = 8,
            completedPages = 5,
            skippedPages = 1,
            failedPages = 2,
            cancelled = cancelled,
            retryableItems = retryable,
        ),
    )

    private fun document(id: String, title: String) = LibraryDocumentSummary(
        id = id,
        title = title,
        createdAtMillis = 1,
        modifiedAtMillis = 1,
        pageCount = 1,
        folderId = null,
        folderName = null,
        thumbnailRelativePath = null,
        ocrStatus = LibraryOcrStatus.NOT_INDEXED,
    )
}
