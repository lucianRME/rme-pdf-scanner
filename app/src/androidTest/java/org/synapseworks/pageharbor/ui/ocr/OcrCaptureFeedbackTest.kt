package org.synapseworks.pageharbor.ui.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.synapseworks.pageharbor.document.session.DocumentPage
import org.synapseworks.pageharbor.document.session.DocumentPageId
import org.synapseworks.pageharbor.document.session.DocumentResource
import org.synapseworks.pageharbor.document.session.DocumentResourceOwnership
import org.synapseworks.pageharbor.document.session.DocumentSourceCategory
import org.synapseworks.pageharbor.library.LibraryOcrPageSnapshot
import org.synapseworks.pageharbor.library.LibraryOcrReviewPage
import org.synapseworks.pageharbor.ocr.OcrPageResult
import org.synapseworks.pageharbor.ocr.OcrResult
import org.synapseworks.pageharbor.ocr.OcrScript
import org.synapseworks.pageharbor.ocr.OcrUiState
import org.synapseworks.pageharbor.ocr.review.OcrReviewLoadState
import org.synapseworks.pageharbor.ocr.review.OcrReviewUiState
import org.synapseworks.pageharbor.scanner.ScannerSpikeState
import org.synapseworks.pageharbor.ui.PageHarborApp
import org.synapseworks.pageharbor.ui.theme.PageHarborTheme

class OcrCaptureFeedbackTest {
    @get:Rule
    val compose = createComposeRule()
    private val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
    private val files = mutableListOf<File>()

    @After
    fun removeFixtures() {
        files.forEach { it.delete() }
    }

    @Test
    fun dimPageWarnsBeforeOcrAndMovingToBrightPageClearsIt() {
        val pages = listOf(
            page(1, 0xff805937.toInt()),
            page(2, 0xfffaf8f1.toInt()),
        )
        val calls = mutableStateOf(0)
        compose.setContent {
            PageHarborApp(
                scannerSpikeState = ScannerSpikeState.ResultSummary(2, false, 0),
                documentPages = pages,
                onRecognizeTextWithLanguage = { _, _ -> calls.value++ },
            )
        }

        waitForText("Page looks dark. Add light and retake it for clearer text.")
        compose.onNodeWithTag("ocr-lighting-warning").assertIsDisplayed()
        compose.onNodeWithText("OCR").performClick()
        compose.onNodeWithText("Recognize text").performClick()
        compose.runOnIdle { assertEquals(1, calls.value) }
        compose.onNodeWithContentDescription("Next page").performScrollTo().performClick()
        compose.onNodeWithTag("ocr-lighting-warning").assertDoesNotExist()
    }

    @Test
    fun spellingRequiresSelectionAndCopyLeavesRecognizedTextIntact() {
        val raw = "Check the documemt, then recieve the page."
        val copied = mutableListOf<String>()
        val pages = listOf(page(3, 0xfffaf8f1.toInt()))
        compose.setContent {
            PageHarborApp(
                scannerSpikeState = ScannerSpikeState.ResultSummary(1, false, 0),
                documentPages = pages,
                ocrUiState = OcrUiState.Success(OcrResult(listOf(OcrPageResult(0, raw, actualScript = OcrScript.LATIN)))),
                onCopyRecognizedText = { copied += it },
            )
        }
        compose.onNodeWithText("OCR").performClick()
        compose.onNodeWithText("Review spelling").performScrollTo().performClick()
        waitForText("Use document")
        compose.onNodeWithText("Copy reviewed page").assertIsNotEnabled()
        compose.onNodeWithText("Use document").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(emptyList<String>(), copied) }

        compose.onNodeWithText("Review spelling").performScrollTo().performClick()
        waitForText("Use document")
        compose.onNodeWithText("Use document").performClick()
        compose.onNodeWithText("Copy reviewed page").performClick()
        compose.runOnIdle { assertEquals(listOf(raw.replace("documemt", "document")), copied) }
        compose.onNodeWithText(raw).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun savedOcrSpellingUpdatesOnlyTheDraftUntilExplicitSave() {
        val raw = "Read the documemt."
        val page = LibraryOcrReviewPage(
            snapshot = LibraryOcrPageSnapshot(
                documentId = "document",
                pageId = "page",
                pagePosition = 0,
                documentContentRevision = 1,
                pageVisualRevision = 1,
                ocrStateRevision = 2,
                activeArtifactRevision = 2,
                contentSha256 = "a".repeat(64),
                rotationDegrees = 0,
                filterName = "ORIGINAL",
            ),
            rawText = raw,
            effectiveText = raw,
            correctedText = null,
            actualScript = OcrScript.LATIN.stableId,
            recognizedAtMillis = 100L,
            correctionBaseArtifactRevision = null,
            rawLines = raw.lines(),
        )
        val state = mutableStateOf(
            OcrReviewUiState(
                active = true,
                documentId = "document",
                pageIds = listOf("page"),
                selectedPageId = "page",
                page = page,
                loadState = OcrReviewLoadState.READY,
                editing = true,
                draftText = raw,
            ),
        )
        var saved: String? = null
        compose.setContent {
            PageHarborTheme {
                OcrReviewScreen(
                    state = state.value,
                    onBack = {},
                    onBeginEdit = {},
                    onDraftChange = { state.value = state.value.copy(draftText = it) },
                    onSaveCorrection = { saved = state.value.draftText },
                    onCancelEdit = {},
                    onRevertCorrection = {},
                    onSelectPage = {},
                    onRerunPage = {},
                    onRerunDocument = {},
                    onCancelRerun = {},
                )
            }
        }
        compose.onNodeWithText("Review spelling").performScrollTo().performClick()
        waitForText("Use document")
        compose.onNodeWithText("Use document").performClick()
        compose.onNodeWithText("Update draft").performClick()
        compose.runOnIdle {
            assertEquals(null, saved)
            assertEquals(raw, state.value.page?.rawText)
            assertEquals("Read the document.", state.value.draftText)
        }
        compose.onNodeWithText("Save correction").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("Read the document.", saved) }
    }

    private fun waitForText(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun page(id: Long, paper: Int): DocumentPage {
        val file = File.createTempFile("ocr-lighting-", ".jpg", cache)
        files += file
        val bitmap = Bitmap.createBitmap(540, 720, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(paper)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xff151515.toInt()
                textSize = 28f
                typeface = Typeface.create("serif", Typeface.ITALIC)
            }
            val canvas = Canvas(bitmap)
            repeat(8) { canvas.drawText("A page set in italic type.", 68f, 120f + it * 56f, paint) }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 94, it) }
        } finally {
            bitmap.recycle()
        }
        return DocumentPage(
            id = DocumentPageId(id),
            source = DocumentResource(Uri.fromFile(file).toString(), DocumentResourceOwnership.USER_OR_EXTERNAL),
            sourceCategory = DocumentSourceCategory.SELECTED_IMAGE,
        )
    }
}
