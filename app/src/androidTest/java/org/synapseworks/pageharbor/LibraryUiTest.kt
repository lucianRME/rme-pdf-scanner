package org.synapseworks.pageharbor

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
import org.synapseworks.pageharbor.library.LibrarySearchHit
import org.synapseworks.pageharbor.library.LibrarySearchMatch
import org.synapseworks.pageharbor.library.LibrarySearchState
import org.synapseworks.pageharbor.library.LibraryUiState
import org.synapseworks.pageharbor.library.SmartNameUiState
import org.synapseworks.pageharbor.library.smartnaming.SmartDocumentType
import org.synapseworks.pageharbor.library.smartnaming.SmartNameConfidence
import org.synapseworks.pageharbor.library.smartnaming.SmartNameSuggestion
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
                onOpenLibraryDocument = { documentId, _ -> openedId = documentId },
            )
        }

        composeTestRule.onNodeWithText("Site notes").assertIsDisplayed()
        composeTestRule.onNodeWithText("3 pages", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Site notes").performClick()
        assertEquals("document-1", openedId)
    }

    @Test
    fun searchShowsTitleAndEachMatchingPageAndOpensStablePage() {
        var opened: Pair<String, String?>? = null
        val hits = listOf(
            LibrarySearchHit(
                documentId = "invoice",
                documentTitle = "Vodafone Invoice",
                pageId = null,
                currentPagePosition = null,
                matchType = LibrarySearchMatch.TITLE,
                snippet = null,
            ),
            LibrarySearchHit(
                documentId = "invoice",
                documentTitle = "Vodafone Invoice",
                pageId = "page-three",
                currentPagePosition = 2,
                matchType = LibrarySearchMatch.OCR,
                snippet = "amount due €42.16 by 28 September",
            ),
            LibrarySearchHit(
                documentId = "invoice",
                documentTitle = "Vodafone Invoice",
                pageId = "page-seven",
                currentPagePosition = 6,
                matchType = LibrarySearchMatch.OCR,
                snippet = "Vodafone contract number 1234",
            ),
            LibrarySearchHit(
                documentId = "tax-archive",
                documentTitle = "Archive",
                pageId = null,
                currentPagePosition = null,
                matchType = LibrarySearchMatch.FOLDER,
                snippet = null,
            ),
        )
        composeTestRule.setContent {
            PageHarborApp(
                libraryUiState = LibraryUiState(
                    query = "Vodafone",
                    searchState = LibrarySearchState.Results(
                        query = "Vodafone",
                        hits = hits,
                        canLoadMore = false,
                    ),
                ),
                onOpenLibraryDocument = { documentId, pageId ->
                    opened = documentId to pageId
                },
            )
        }

        composeTestRule.onNodeWithText("Title match").assertIsDisplayed()
        composeTestRule.onNodeWithText("Page 3 · 2 matching pages").assertIsDisplayed()
        composeTestRule.onNodeWithText("Page 7 · 2 matching pages").assertIsDisplayed()
        composeTestRule.onNodeWithText("Document details match").assertIsDisplayed()
        composeTestRule.onNodeWithText("amount due €42.16", substring = true).performClick()
        assertEquals("invoice" to "page-three", opened)
    }

    @Test
    fun documentsDestinationUsesTheSamePageAwareSearchResult() {
        var opened: Pair<String, String?>? = null
        composeTestRule.setContent {
            PageHarborApp(
                documentsDestinationRequestId = 1L,
                libraryUiState = LibraryUiState(
                    query = "contract",
                    searchState = LibrarySearchState.Results(
                        query = "contract",
                        hits = listOf(
                            LibrarySearchHit(
                                documentId = "document",
                                documentTitle = "Client file",
                                pageId = "stable-page",
                                currentPagePosition = 4,
                                matchType = LibrarySearchMatch.OCR,
                                snippet = "signed contract number 1234",
                            ),
                        ),
                        canLoadMore = false,
                    ),
                ),
                onOpenLibraryDocument = { documentId, pageId ->
                    opened = documentId to pageId
                },
            )
        }

        composeTestRule.onNodeWithText("Page 5").assertIsDisplayed()
        composeTestRule.onNodeWithText("signed contract number 1234").performClick()
        assertEquals("document" to "stable-page", opened)
    }

    @Test
    fun stablePageOpenRequestSelectsCurrentMatchingPage() {
        composeTestRule.setContent {
            PageHarborApp(
                scannerSpikeState = ScannerSpikeState.ResultSummary(3, false, null),
                documentPages = listOf(
                    page(10, "page-three"),
                    page(11, "page-one"),
                    page(12, "page-two"),
                ),
                libraryDocument = LibraryDocumentReference("invoice", "Vodafone Invoice"),
                openedPageRequestId = 1L,
                openedPagePersistentId = "page-two",
            )
        }

        composeTestRule.onNodeWithText("Page 3 of 3").assertIsDisplayed()
    }

    @Test
    fun multilingualResultRemainsAccessibleAtTwoHundredPercentAcrossWidths() {
        val width = mutableStateOf(320.dp)
        val longTitle = "बहुत लंबा बीमा दस्तावेज 中文发票 日本語請求書 한국어 청구서"
        val hit = LibrarySearchHit(
            documentId = "multilingual",
            documentTitle = longTitle,
            pageId = "page-cjk",
            currentPagePosition = 3,
            matchType = LibrarySearchMatch.OCR,
            snippet = "中文测试发票金额 €42.16 हिन्दी परीक्षण चालान",
        )
        composeTestRule.setContent {
            val deviceDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(deviceDensity.density, fontScale = 2f),
            ) {
                Box(
                    modifier = androidx.compose.ui.Modifier
                        .width(width.value)
                        .fillMaxHeight(),
                ) {
                    PageHarborApp(
                        libraryUiState = LibraryUiState(
                            query = "发票",
                            searchState = LibrarySearchState.Results(
                                query = "发票",
                                hits = listOf(hit),
                                canLoadMore = false,
                            ),
                        ),
                    )
                }
            }
        }

        listOf(320.dp, 600.dp, 840.dp).forEach { targetWidth ->
            composeTestRule.runOnUiThread { width.value = targetWidth }
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithContentDescription(
                "中文测试发票金额",
                substring = true,
            ).assertIsDisplayed()
        }
    }

    @Test
    fun searchUsesNaturalLoadingShortAndEmptyStates() {
        val state = mutableStateOf(
            LibraryUiState(
                query = "invoice",
                searchState = LibrarySearchState.Loading("invoice"),
            ),
        )
        composeTestRule.setContent { PageHarborApp(libraryUiState = state.value) }

        composeTestRule.onNodeWithText("Searching this device…").assertIsDisplayed()
        composeTestRule.runOnUiThread {
            state.value = LibraryUiState(
                query = "invoice",
                searchState = LibrarySearchState.Results("invoice", emptyList(), false),
            )
        }
        composeTestRule.onNodeWithText("No matching documents").assertIsDisplayed()
        composeTestRule.runOnUiThread {
            state.value = LibraryUiState(
                query = "i",
                searchState = LibrarySearchState.TooShort("i"),
            )
        }
        composeTestRule.onNodeWithText("Type at least two letters or numbers.").assertIsDisplayed()
    }

    @Test
    fun searchOffersExpansionAndExplainsTheTwoHundredResultBound() {
        var loadMoreRequests = 0
        val state = mutableStateOf(
            LibraryUiState(
                query = "common",
                searchState = LibrarySearchState.Results(
                    query = "common",
                    hits = listOf(searchHit(0)),
                    canLoadMore = true,
                    hasMoreResults = true,
                ),
            ),
        )
        composeTestRule.setContent {
            PageHarborApp(
                libraryUiState = state.value,
                onLoadMoreLibrarySearchResults = { loadMoreRequests += 1 },
            )
        }

        composeTestRule.onNodeWithText("Show more results").performClick()
        assertEquals(1, loadMoreRequests)

        composeTestRule.runOnUiThread {
            state.value = LibraryUiState(
                query = "common",
                searchState = LibrarySearchState.Results(
                    query = "common",
                    hits = List(200, ::searchHit),
                    canLoadMore = false,
                    hasMoreResults = true,
                ),
            )
        }
        composeTestRule.onAllNodes(hasScrollAction()).onFirst().performScrollToIndex(202)
        composeTestRule.onNodeWithText(
            "Showing the first 200 results. Refine your search to narrow the list.",
        ).assertIsDisplayed()
    }

    @Test
    fun documentSecondaryActionsUseOneAccessibleMenuAndNameDeleteTarget() {
        var deletedId: String? = null
        composeTestRule.setContent {
            PageHarborApp(
                libraryUiState = LibraryUiState(
                    documents = listOf(summary("document-1", "Electricity bill", pageCount = 2)),
                ),
                onDeleteLibraryDocument = { deletedId = it },
            )
        }

        composeTestRule.onAllNodesWithText("Rename").assertCountEquals(0)
        composeTestRule.onNodeWithContentDescription("More actions for Electricity bill")
            .performClick()
        composeTestRule.onNodeWithText("Delete").performClick()
        composeTestRule.onNodeWithText("Delete “Electricity bill” from RME?")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Delete").performClick()

        assertEquals("document-1", deletedId)
    }

    @Test
    fun renameFlowRequestsAndUsesSmartNameOnlyAfterUserConfirmation() {
        var requestedId: String? = null
        var renamed: Pair<String, String>? = null
        val suggestion = "Vodafone Invoice — Sep 2026"
        composeTestRule.setContent {
            PageHarborApp(
                libraryUiState = LibraryUiState(
                    documents = listOf(summary("document-1", "Document 12", pageCount = 1)),
                    smartNameStates = mapOf(
                        "document-1" to SmartNameUiState.Available(
                            SmartNameSuggestion(
                                name = suggestion,
                                confidence = SmartNameConfidence.HIGH,
                                organization = "Vodafone",
                                documentType = SmartDocumentType.INVOICE,
                                date = "Sep 2026",
                            ),
                        ),
                    ),
                ),
                onRequestSmartName = { requestedId = it },
                onRenameLibraryDocument = { id, name -> renamed = id to name },
            )
        }

        composeTestRule.onNodeWithContentDescription("More actions for Document 12").performClick()
        composeTestRule.onNodeWithText("Rename").performClick()
        composeTestRule.waitForIdle()
        assertEquals("document-1", requestedId)
        composeTestRule.onNodeWithText("Suggested name: $suggestion").assertIsDisplayed()
        composeTestRule.onNodeWithText("Use suggestion").performClick()
        composeTestRule.onNodeWithText("Rename").performClick()
        assertEquals("document-1" to suggestion, renamed)
    }

    @Test
    fun smartNameCanBeEditedAndKeepCurrentDeclinesWithoutMutation() {
        var renamed: String? = null
        val suggestion = "Tesco Receipt — 25 Sep 2026"
        composeTestRule.setContent {
            PageHarborApp(
                libraryUiState = LibraryUiState(
                    documents = listOf(summary("document-2", "Scan 2026-09-27", pageCount = 1)),
                    smartNameStates = mapOf(
                        "document-2" to SmartNameUiState.Available(
                            SmartNameSuggestion(
                                name = suggestion,
                                confidence = SmartNameConfidence.HIGH,
                                organization = "Tesco",
                                documentType = SmartDocumentType.RECEIPT,
                                date = "25 Sep 2026",
                            ),
                        ),
                    ),
                ),
                onRenameLibraryDocument = { _, name -> renamed = name },
            )
        }

        composeTestRule.onNodeWithContentDescription("More actions for Scan 2026-09-27").performClick()
        composeTestRule.onNodeWithText("Rename").performClick()
        composeTestRule.onNodeWithText("Keep current name").performClick()
        assertEquals(null, renamed)

        composeTestRule.onNodeWithContentDescription("More actions for Scan 2026-09-27").performClick()
        composeTestRule.onNodeWithText("Rename").performClick()
        composeTestRule.onNodeWithText("Use suggestion").performClick()
        val suggestedNameField = composeTestRule.onNode(
            hasSetTextAction() and hasAnyAncestor(isDialog()),
        )
        suggestedNameField.performTextClearance()
        suggestedNameField.performTextInput("Edited receipt")
        composeTestRule.onNodeWithText("Rename").performClick()
        assertEquals("Edited receipt", renamed)
    }

    @Test
    fun unavailableSmartNameLeavesOrdinaryRenameFlowIntact() {
        var renamed: String? = null
        composeTestRule.setContent {
            PageHarborApp(
                libraryUiState = LibraryUiState(
                    documents = listOf(summary("document-3", "Document 3", pageCount = 1)),
                    smartNameStates = mapOf("document-3" to SmartNameUiState.Unavailable),
                ),
                onRenameLibraryDocument = { _, name -> renamed = name },
            )
        }

        composeTestRule.onNodeWithContentDescription("More actions for Document 3").performClick()
        composeTestRule.onNodeWithText("Rename").performClick()
        composeTestRule.onNodeWithText("Use suggestion").assertDoesNotExist()
        val manualNameField = composeTestRule.onNode(
            hasSetTextAction() and hasAnyAncestor(isDialog()),
        )
        manualNameField.performTextClearance()
        manualNameField.performTextInput("Manual title")
        composeTestRule.onNodeWithText("Rename").performClick()
        assertEquals("Manual title", renamed)
    }

    @Test
    fun smartNameRenameDialogRemainsUsableAtTwoHundredPercentAcrossWidths() {
        val width = mutableStateOf(320.dp)
        val suggestion = "Vodafone Invoice — Sep 2026"
        composeTestRule.setContent {
            val deviceDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(deviceDensity.density, fontScale = 2f),
            ) {
                Box(
                    modifier = androidx.compose.ui.Modifier
                        .width(width.value)
                        .fillMaxHeight(),
                ) {
                    PageHarborApp(
                        libraryUiState = LibraryUiState(
                            documents = listOf(summary("responsive", "Document 4", pageCount = 1)),
                            smartNameStates = mapOf(
                                "responsive" to SmartNameUiState.Available(
                                    SmartNameSuggestion(
                                        suggestion,
                                        SmartNameConfidence.HIGH,
                                        "Vodafone",
                                        SmartDocumentType.INVOICE,
                                        "Sep 2026",
                                    ),
                                ),
                            ),
                        ),
                    )
                }
            }
        }

        composeTestRule.onNodeWithContentDescription("More actions for Document 4").performClick()
        composeTestRule.onNodeWithText("Rename").performClick()
        listOf(320.dp, 600.dp, 840.dp).forEach { targetWidth ->
            composeTestRule.runOnUiThread { width.value = targetWidth }
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithText("Use suggestion").performScrollTo().assertIsDisplayed()
            composeTestRule.onNodeWithText("Keep current name").assertIsDisplayed()
        }
    }

    @Test
    fun activeSavedDocumentRenameUsesTheSamePassiveSmartName() {
        var requestedId: String? = null
        var renamed: Pair<String, String>? = null
        val suggestion = "Tesco Receipt — 25 Sep 2026"
        composeTestRule.setContent {
            PageHarborApp(
                scannerSpikeState = ScannerSpikeState.ResultSummary(1, false, null),
                documentPages = listOf(page(1, "saved-page")),
                libraryDocument = LibraryDocumentReference("saved-document", "Document 5"),
                libraryUiState = LibraryUiState(
                    smartNameStates = mapOf(
                        "saved-document" to SmartNameUiState.Available(
                            SmartNameSuggestion(
                                suggestion,
                                SmartNameConfidence.HIGH,
                                "Tesco",
                                SmartDocumentType.RECEIPT,
                                "25 Sep 2026",
                            ),
                        ),
                    ),
                ),
                onRequestSmartName = { requestedId = it },
                onRenameLibraryDocument = { id, title -> renamed = id to title },
            )
        }

        composeTestRule.onNodeWithText("Actions").performClick()
        composeTestRule.onNodeWithText("Rename").performClick()
        composeTestRule.waitForIdle()
        assertEquals("saved-document", requestedId)
        composeTestRule.onNodeWithText("Use suggestion").performClick()
        composeTestRule.onNodeWithText("Rename").performClick()
        assertEquals("saved-document" to suggestion, renamed)
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

        composeTestRule.onNodeWithText("Actions").performClick()
        composeTestRule.onNodeWithText("Export PDF")
            .assertIsDisplayed()
            .assertIsEnabled()
        composeTestRule.onNodeWithText("Save to RME").performClick()
        composeTestRule.onNodeWithText("Save editable local document").assertIsDisplayed()
        composeTestRule.onNodeWithText("Save to RME").performClick()
        assertEquals("Document", savedTitle)
        composeTestRule.onNodeWithText("Edit").performClick()
        composeTestRule.onNodeWithText("Remove page").assertIsNotEnabled()
        composeTestRule.onNodeWithText("A document must keep at least one page.")
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

        composeTestRule.onNodeWithText("Actions").performClick()
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

    private fun searchHit(index: Int) = LibrarySearchHit(
        documentId = "document-$index",
        documentTitle = "Document $index",
        pageId = "page-$index",
        currentPagePosition = index,
        matchType = LibrarySearchMatch.OCR,
        snippet = "common searchable text $index",
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
