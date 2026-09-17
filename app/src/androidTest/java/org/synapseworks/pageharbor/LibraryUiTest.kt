package org.synapseworks.pageharbor

import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.synapseworks.pageharbor.document.session.DocumentPage
import org.synapseworks.pageharbor.document.session.DocumentPageId
import org.synapseworks.pageharbor.document.session.DocumentResource
import org.synapseworks.pageharbor.document.session.DocumentResourceOwnership
import org.synapseworks.pageharbor.document.session.DocumentSourceCategory
import org.synapseworks.pageharbor.document.session.LibraryDocumentReference
import org.synapseworks.pageharbor.library.LibraryDocumentSummary
import org.synapseworks.pageharbor.library.LibraryOcrStatus
import org.synapseworks.pageharbor.library.LibraryUiState
import org.synapseworks.pageharbor.scanner.ScannerSpikeState
import org.synapseworks.pageharbor.ui.PageHarborApp

class LibraryUiTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun savedDocumentCardShowsMetadataAndOpensRequestedDocument() {
        var openedId: String? = null
        composeTestRule.setContent {
            PageHarborApp(
                libraryUiState = LibraryUiState(
                    documents = listOf(summary("document-1", "Site notes", pageCount = 3)),
                ),
                onOpenLibraryDocument = { openedId = it },
            )
        }

        composeTestRule.onNodeWithText("Site notes").assertIsDisplayed()
        composeTestRule.onNodeWithText("3 pages", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Open").performClick()
        assertEquals("document-1", openedId)
    }

    @Test
    fun documentReviewOffersExplicitLibrarySaveAndProtectsFinalPage() {
        var savedTitle: String? = null
        composeTestRule.setContent {
            PageHarborApp(
                scannerSpikeState = ScannerSpikeState.ResultSummary(1, false, null),
                documentPages = listOf(page(1)),
                onSaveToLibrary = { savedTitle = it },
            )
        }

        composeTestRule.onNodeWithText("Save PDF").assertIsDisplayed().assertIsEnabled()
        composeTestRule.onNodeWithText("Save to RME").performScrollTo().performClick()
        composeTestRule.onNodeWithText("Save editable local document").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Save to RME")[1].performClick()
        assertEquals("Document", savedTitle)
        composeTestRule.onNodeWithText("Remove page").performScrollTo().assertIsNotEnabled()
        composeTestRule.onNodeWithText("A document must keep at least one page.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun savedDocumentPageToolsExtractStablePageIds() {
        var extracted: Triple<Set<String>, String, Boolean>? = null
        composeTestRule.setContent {
            PageHarborApp(
                scannerSpikeState = ScannerSpikeState.ResultSummary(2, false, null),
                documentPages = listOf(
                    page(1, "page-one"),
                    page(2, "page-two"),
                ),
                libraryDocument = LibraryDocumentReference("saved-1", "Saved document"),
                onExtractLibraryPages = { pageIds, title, remove ->
                    extracted = Triple(pageIds, title, remove)
                },
            )
        }

        composeTestRule.onNodeWithText("Extract or split pages").performScrollTo().performClick()
        composeTestRule.onNodeWithText("Page 2").performClick()
        composeTestRule.onNodeWithText("Extract as a new document").performClick()
        assertEquals(Triple(setOf("page-two"), "Extracted pages", false), extracted)
    }

    private fun summary(id: String, title: String, pageCount: Int) = LibraryDocumentSummary(
        id = id,
        title = title,
        createdAtMillis = 0L,
        modifiedAtMillis = 0L,
        pageCount = pageCount,
        folderId = null,
        folderName = null,
        thumbnailRelativePath = null,
        ocrStatus = LibraryOcrStatus.NOT_INDEXED,
    )

    private fun page(id: Long, persistentId: String? = null) = DocumentPage(
        id = DocumentPageId(id),
        source = DocumentResource(
            reference = Uri.parse("content://test/page-$id").toString(),
            ownership = DocumentResourceOwnership.USER_OR_EXTERNAL,
        ),
        sourceCategory = DocumentSourceCategory.SELECTED_IMAGE,
        persistentId = persistentId,
    )
}
