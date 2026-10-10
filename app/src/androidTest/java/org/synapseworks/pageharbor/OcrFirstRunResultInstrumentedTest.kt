package org.synapseworks.pageharbor

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.synapseworks.pageharbor.document.session.DocumentImageMetadata
import org.synapseworks.pageharbor.document.session.DocumentPage
import org.synapseworks.pageharbor.document.session.DocumentPageId
import org.synapseworks.pageharbor.document.session.DocumentResource
import org.synapseworks.pageharbor.document.session.DocumentResourceOwnership
import org.synapseworks.pageharbor.document.session.DocumentSession
import org.synapseworks.pageharbor.document.session.DocumentSourceCategory
import org.synapseworks.pageharbor.library.LibraryRepository
import org.synapseworks.pageharbor.library.LibraryResult
import org.synapseworks.pageharbor.ocr.OcrEngine
import org.synapseworks.pageharbor.ocr.OcrPage
import org.synapseworks.pageharbor.ocr.OcrPageError
import org.synapseworks.pageharbor.ocr.OcrPageLayout
import org.synapseworks.pageharbor.ocr.OcrPageResult
import org.synapseworks.pageharbor.ocr.OcrResult
import org.synapseworks.pageharbor.ocr.OcrScript
import org.synapseworks.pageharbor.ocr.OcrTextBounds
import org.synapseworks.pageharbor.ocr.OcrTextLine
import org.synapseworks.pageharbor.ocr.OcrUiError
import org.synapseworks.pageharbor.ocr.OcrUiState
import org.synapseworks.pageharbor.ocr.review.OcrReviewLoadState
import org.synapseworks.pageharbor.ui.PageHarborScreen

class OcrFirstRunResultInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun importedImageFirstRecognitionPersistsAndImmediatelyOpensReview() {
        verifySavedDocumentFirstRun(DocumentSourceCategory.SELECTED_IMAGE)
    }

    @Test
    fun importedPdfPageFirstRecognitionPersistsAndImmediatelyOpensReview() {
        verifySavedDocumentFirstRun(DocumentSourceCategory.RENDERED_PDF_PAGE)
    }

    @Test
    fun importedImageLanguageChoiceShowsFirstResultWithoutSecondOcrTap() {
        val source = createSyntheticJpeg("unsaved-import")
        val recognizedText = "visible after the first recognition"
        val recognitionCount = AtomicInteger(0)
        val terminal = CountDownLatch(1)

        try {
            composeRule.runOnUiThread {
                composeRule.activity.replaceOperationsForTest(
                    ocrEngine = object : OcrEngine {
                        override fun recognize(pages: List<OcrPage>): OcrResult {
                            recognitionCount.incrementAndGet()
                            return OcrResult(
                                listOf(OcrPageResult(pageIndex = 0, text = recognizedText)),
                            )
                        }
                    },
                    onOcrTerminalState = terminal::countDown,
                )
                composeRule.activity.installDocumentSessionForTest(
                    unsavedSession(listOf(source), DocumentSourceCategory.SELECTED_IMAGE),
                )
            }

            composeRule.onNodeWithContentDescription("OCR").performClick()
            composeRule.onNodeWithText("Text recognition language").assertIsDisplayed()
            composeRule.onNodeWithText("Latin").performClick()
            composeRule.onNodeWithText("Recognize text").performClick()

            assertTrue("OCR completion was not delivered", terminal.await(10, TimeUnit.SECONDS))
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.activity.sessionScreenForTest() ==
                    org.synapseworks.pageharbor.ui.PageHarborScreen.OcrResult
            }
            composeRule.onNodeWithText("Recognized text").assertIsDisplayed()
            composeRule.onNodeWithText(recognizedText).assertIsDisplayed()
            assertEquals(1, recognitionCount.get())

            composeRule.onNodeWithContentDescription("Back").performClick()
            composeRule.onNodeWithContentDescription("OCR").assertIsDisplayed()
        } finally {
            composeRule.runOnUiThread { composeRule.activity.discardForTest() }
            source.delete()
        }
    }

    @Test
    fun scannedMultiPageFirstRecognitionImmediatelyShowsBothPages() {
        val sources = listOf(createSyntheticJpeg("scan-page-1"), createSyntheticJpeg("scan-page-2"))
        val recognitionCount = AtomicInteger(0)
        val terminal = CountDownLatch(1)
        val firstText = "first scanned page"
        val secondText = "second scanned page"

        try {
            composeRule.runOnUiThread {
                composeRule.activity.replaceOperationsForTest(
                    ocrEngine = object : OcrEngine {
                        override fun recognize(pages: List<OcrPage>): OcrResult {
                            recognitionCount.incrementAndGet()
                            return OcrResult(
                                listOf(
                                    OcrPageResult(pageIndex = 0, text = firstText),
                                    OcrPageResult(pageIndex = 1, text = secondText),
                                ),
                            )
                        }
                    },
                    onOcrTerminalState = terminal::countDown,
                )
                composeRule.activity.installDocumentSessionForTest(
                    unsavedSession(sources, DocumentSourceCategory.SCAN),
                )
                composeRule.activity.recognizeTextForTest(OcrScript.LATIN)
            }

            assertTrue("OCR completion was not delivered", terminal.await(10, TimeUnit.SECONDS))
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.activity.sessionScreenForTest() == PageHarborScreen.OcrResult
            }
            composeRule.onNodeWithText(firstText).performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithText("Next page").performScrollTo().performClick()
            composeRule.onNodeWithText(secondText).performScrollTo().assertIsDisplayed()
            assertEquals(1, recognitionCount.get())
        } finally {
            composeRule.runOnUiThread { composeRule.activity.discardForTest() }
            sources.forEach(File::delete)
        }
    }

    @Test
    fun emptyFirstRecognitionImmediatelyShowsAcceptedEmptyResult() {
        val source = createSyntheticJpeg("empty")
        val terminal = CountDownLatch(1)

        try {
            composeRule.runOnUiThread {
                composeRule.activity.replaceOperationsForTest(
                    ocrEngine = object : OcrEngine {
                        override fun recognize(pages: List<OcrPage>) =
                            OcrResult(listOf(OcrPageResult(pageIndex = 0, text = "")))
                    },
                    onOcrTerminalState = terminal::countDown,
                )
                composeRule.activity.installDocumentSessionForTest(
                    unsavedSession(listOf(source), DocumentSourceCategory.SELECTED_IMAGE),
                )
                composeRule.activity.recognizeTextForTest(OcrScript.LATIN)
            }

            assertTrue("OCR completion was not delivered", terminal.await(10, TimeUnit.SECONDS))
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.activity.sessionScreenForTest() == PageHarborScreen.OcrResult
            }
            composeRule.onNodeWithText("No text was recognized in this scan.").assertIsDisplayed()
        } finally {
            composeRule.runOnUiThread { composeRule.activity.discardForTest() }
            source.delete()
        }
    }

    @Test
    fun failedFirstRecognitionStaysInWorkspaceAndShowsRetryableError() {
        val source = createSyntheticJpeg("failure")
        val terminal = CountDownLatch(1)

        try {
            composeRule.runOnUiThread {
                composeRule.activity.replaceOperationsForTest(
                    ocrEngine = object : OcrEngine {
                        override fun recognize(pages: List<OcrPage>) =
                            OcrResult(
                                listOf(
                                    OcrPageResult(
                                        pageIndex = 0,
                                        text = "",
                                        error = OcrPageError.RECOGNITION_FAILED,
                                    ),
                                ),
                            )
                    },
                    onOcrTerminalState = terminal::countDown,
                )
                composeRule.activity.installDocumentSessionForTest(
                    unsavedSession(listOf(source), DocumentSourceCategory.SELECTED_IMAGE),
                )
                composeRule.activity.recognizeTextForTest(OcrScript.LATIN)
            }

            assertTrue("OCR completion was not delivered", terminal.await(10, TimeUnit.SECONDS))
            composeRule.runOnIdle {
                assertEquals(PageHarborScreen.ScanResult, composeRule.activity.sessionScreenForTest())
                assertEquals(
                    OcrUiState.Error(OcrUiError.ALL_PAGES_FAILED),
                    composeRule.activity.ocrStateForTest(),
                )
            }
            composeRule.onNodeWithText("Text could not be recognized from this scan.")
                .assertIsDisplayed()
        } finally {
            composeRule.runOnUiThread { composeRule.activity.discardForTest() }
            source.delete()
        }
    }

    private fun verifySavedDocumentFirstRun(sourceCategory: DocumentSourceCategory) {
        val source = createSyntheticJpeg(sourceCategory.name.lowercase())
        val repository = LibraryRepository(context)
        val title = "OCR first run ${System.nanoTime()}"
        val recognizedText = "first run orchard ${System.nanoTime()}"
        val saved = runBlocking {
            repository.saveSession(
                session = DocumentSession(
                    pages = listOf(
                        DocumentPage(
                            id = DocumentPageId(1L),
                            source = DocumentResource(
                                reference = Uri.fromFile(source).toString(),
                                ownership = DocumentResourceOwnership.USER_OR_EXTERNAL,
                            ),
                            sourceCategory = sourceCategory,
                            imageMetadata = DocumentImageMetadata(
                                sourceByteCount = source.length(),
                                width = 96,
                                height = 128,
                            ),
                        ),
                    ),
                ),
                title = title,
            ).successValue()
        }
        val opened = runBlocking { repository.openDocument(saved.id).successValue() }
        val recognitionCount = AtomicInteger(0)
        val terminal = CountDownLatch(1)

        try {
            composeRule.runOnUiThread {
                composeRule.activity.replaceOperationsForTest(
                    ocrEngine = object : OcrEngine {
                        override fun recognize(pages: List<OcrPage>): OcrResult {
                            recognitionCount.incrementAndGet()
                            return OcrResult(
                                listOf(
                                    OcrPageResult(
                                        pageIndex = 0,
                                        text = recognizedText,
                                        layout = OcrPageLayout(
                                            imageWidthPx = 96,
                                            imageHeightPx = 128,
                                            lines = listOf(
                                                OcrTextLine(
                                                    text = recognizedText,
                                                    bounds = OcrTextBounds(
                                                        left = 8f,
                                                        top = 12f,
                                                        right = 88f,
                                                        bottom = 36f,
                                                    ),
                                                ),
                                            ),
                                        ),
                                    ),
                                ),
                            )
                        }
                    },
                    onOcrTerminalState = terminal::countDown,
                )
                composeRule.activity.installDocumentSessionForTest(opened.session)
                composeRule.activity.recognizeTextForTest(OcrScript.LATIN)
            }

            assertTrue("OCR completion was not delivered", terminal.await(10, TimeUnit.SECONDS))
            composeRule.runOnIdle {
                val review = composeRule.activity.ocrReviewStateForTest()
                assertTrue(
                    "OCR review was not opened; OCR=${composeRule.activity.ocrStateForTest()}, review=$review",
                    review.active,
                )
            }
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.activity.ocrReviewStateForTest().loadState == OcrReviewLoadState.READY
            }
            composeRule.onNodeWithText("Review text").assertIsDisplayed()
            composeRule.onNodeWithText(recognizedText).assertIsDisplayed()
            assertEquals(1, recognitionCount.get())

            val persisted = runBlocking {
                repository.ocrReviewPage(
                    documentId = saved.id,
                    pageId = requireNotNull(opened.session.pages.single().persistentId),
                )
            }
            assertEquals(recognizedText, persisted?.effectiveText)
            assertTrue(
                runBlocking { repository.searchHits("orchard") }
                    .any { hit -> hit.documentId == saved.id },
            )

            composeRule.onNodeWithContentDescription("Back").performClick()
            composeRule.onNodeWithContentDescription("OCR").performClick()
            composeRule.onNodeWithText("Review text").assertIsDisplayed()
            composeRule.onNodeWithText(recognizedText).assertIsDisplayed()
            assertEquals("Existing OCR must not run recognition again", 1, recognitionCount.get())
        } finally {
            composeRule.runOnUiThread {
                composeRule.activity.closeOcrReviewForTest()
                composeRule.activity.discardForTest()
            }
            runBlocking { repository.deleteDocument(saved.id) }
            source.delete()
        }
    }

    private fun createSyntheticJpeg(stem: String): File {
        val source = File(context.cacheDir, "ocr-first-run-$stem-${System.nanoTime()}.jpg")
        val bitmap = Bitmap.createBitmap(96, 128, Bitmap.Config.ARGB_8888).apply {
            eraseColor(0xffffffff.toInt())
        }
        FileOutputStream(source).use { output ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output))
        }
        bitmap.recycle()
        return source
    }

    private fun unsavedSession(
        sources: List<File>,
        sourceCategory: DocumentSourceCategory,
    ): DocumentSession = DocumentSession(
        pages = sources.mapIndexed { index, source ->
            DocumentPage(
                id = DocumentPageId(index + 1L),
                source = DocumentResource(
                    reference = Uri.fromFile(source).toString(),
                    ownership = DocumentResourceOwnership.USER_OR_EXTERNAL,
                ),
                sourceCategory = sourceCategory,
                imageMetadata = DocumentImageMetadata(source.length(), 96, 128),
            )
        },
    )

    private fun <T> LibraryResult<T>.successValue(): T {
        assertTrue("Expected success but was $this", this is LibraryResult.Success)
        return (this as LibraryResult.Success).value
    }
}
