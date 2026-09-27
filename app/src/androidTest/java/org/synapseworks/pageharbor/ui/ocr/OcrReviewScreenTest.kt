package org.synapseworks.pageharbor.ui.ocr

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.synapseworks.pageharbor.library.LibraryOcrPageSnapshot
import org.synapseworks.pageharbor.library.LibraryOcrReviewPage
import org.synapseworks.pageharbor.ocr.review.OcrReviewLoadState
import org.synapseworks.pageharbor.ocr.review.OcrReviewUiState
import org.synapseworks.pageharbor.ui.theme.PageHarborTheme

@RunWith(AndroidJUnit4::class)
class OcrReviewScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun correctedPageShowsEffectiveAndLatestRecognizedTextWithPageIdentity() {
        setContent(state(corrected = true))

        composeRule.onNodeWithTag("ocr_review_page_indicator")
            .assertTextContains("Page 1 of 2")
        composeRule.onNodeWithTag("ocr_review_corrected_indicator").assertIsDisplayed()
        composeRule.onNodeWithTag("ocr_review_effective_text")
            .assertTextContains("Edited text")
        composeRule.onNodeWithTag("ocr_review_raw_text")
            .assertTextContains("Latest raw text")
        composeRule.onNodeWithText("OCR language: Japanese").assertIsDisplayed()
    }

    @Test
    fun editSaveAndCancelExposeOnlyExplicitCallbacks() {
        var current by mutableStateOf(
            state().copy(editing = true, draftText = "Recognized text"),
        )
        var saved = ""
        var cancelled = false
        composeRule.setContent {
            PageHarborTheme {
                OcrReviewScreen(
                    state = current,
                    onBack = {},
                    onBeginEdit = {},
                    onDraftChange = { current = current.copy(draftText = it) },
                    onSaveCorrection = { saved = current.draftText },
                    onCancelEdit = { cancelled = true },
                    onRevertCorrection = {},
                    onSelectPage = {},
                    onRerunPage = {},
                    onRerunDocument = {},
                    onCancelRerun = {},
                )
            }
        }

        composeRule.onNodeWithTag("ocr_review_editor")
            .performScrollTo()
            .performTextReplacement("User correction")
        composeRule.onNodeWithText("Save correction").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals("User correction", saved) }

        composeRule.onNodeWithText("Cancel").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(true, cancelled) }
    }

    @Test
    fun revertRequiresASeparateDeliberateAction() {
        var reverted = false
        setContent(state(corrected = true), onRevert = { reverted = true })

        composeRule.onNodeWithText("Revert to recognized text").performClick()

        composeRule.runOnIdle { assertEquals(true, reverted) }
    }

    @Test
    fun pageSwitchWithUnsavedTextRequiresConfirmation() {
        var selected: String? = null
        var cancelled = false
        setContent(
            state = state().copy(editing = true, draftText = "Unsaved correction"),
            onCancelEdit = { cancelled = true },
            onSelectPage = { selected = it },
        )

        composeRule.onNodeWithText("Next page").performClick()
        composeRule.onNodeWithText("Discard unsaved changes?").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(null, selected) }
        composeRule.onNodeWithText("Discard changes").performClick()
        composeRule.runOnIdle {
            assertEquals("page-2", selected)
            assertEquals(true, cancelled)
        }
    }

    @Test
    fun emptyPageOffersRerunWithoutInventingText() {
        var rerun = false
        setContent(
            state().copy(page = reviewPage(raw = null, corrected = null)),
            onRerunPage = { rerun = true },
        )

        composeRule.onNodeWithText("No recognized text is available for this page.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Re-run OCR").performClick()
        composeRule.runOnIdle { assertEquals(true, rerun) }
    }

    @Test
    fun longTextAtTwoHundredPercentRemainsScrollableAndActionsReachable() {
        val longText = (1..120).joinToString("\n") { index ->
            "Line $index https://example.com/a-very-long-unbroken-document-reference-$index"
        }
        setContent(
            state = state().copy(page = reviewPage(raw = longText, corrected = null)),
            fontScale = 2f,
        )

        composeRule.onNodeWithTag("ocr_review_effective_text").assertIsDisplayed()
        composeRule.onNodeWithText("Re-run all pages").performScrollTo().assertIsDisplayed()
    }

    private fun setContent(
        state: OcrReviewUiState,
        fontScale: Float = 1f,
        onBeginEdit: () -> Unit = {},
        onDraftChange: (String) -> Unit = {},
        onSave: () -> Unit = {},
        onCancelEdit: () -> Unit = {},
        onRevert: () -> Unit = {},
        onSelectPage: (String) -> Unit = {},
        onRerunPage: () -> Unit = {},
    ) {
        composeRule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                LocalDensity provides Density(density = 1f, fontScale = fontScale),
            ) {
                PageHarborTheme {
                    OcrReviewScreen(
                        state = state,
                        onBack = {},
                        onBeginEdit = onBeginEdit,
                        onDraftChange = onDraftChange,
                        onSaveCorrection = onSave,
                        onCancelEdit = onCancelEdit,
                        onRevertCorrection = onRevert,
                        onSelectPage = onSelectPage,
                        onRerunPage = onRerunPage,
                        onRerunDocument = {},
                        onCancelRerun = {},
                    )
                }
            }
        }
    }

    private fun state(corrected: Boolean = false) = OcrReviewUiState(
        active = true,
        documentId = "document",
        documentTitle = "Quarterly report",
        pageIds = listOf("page-1", "page-2"),
        selectedPageId = "page-1",
        selectedPageIndex = 0,
        page = reviewPage(
            raw = if (corrected) "Latest raw text" else "Recognized text",
            corrected = if (corrected) "Edited text" else null,
        ),
        loadState = OcrReviewLoadState.READY,
    )

    private fun reviewPage(
        raw: String?,
        corrected: String?,
    ) = LibraryOcrReviewPage(
        snapshot = LibraryOcrPageSnapshot(
            documentId = "document",
            pageId = "page-1",
            pagePosition = 0,
            documentContentRevision = 1,
            pageVisualRevision = 1,
            ocrStateRevision = 2,
            activeArtifactRevision = raw?.let { 2L },
            contentSha256 = "a".repeat(64),
            rotationDegrees = 0,
            filterName = "ORIGINAL",
        ),
        rawText = raw,
        effectiveText = corrected ?: raw,
        correctedText = corrected,
        actualScript = raw?.let { "JAPANESE" },
        recognizedAtMillis = raw?.let { 100L },
        correctionBaseArtifactRevision = corrected?.let { 1L },
        rawLines = raw?.lines().orEmpty(),
    )
}
