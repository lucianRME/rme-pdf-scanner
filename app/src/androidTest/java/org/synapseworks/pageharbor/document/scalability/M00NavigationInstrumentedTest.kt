package org.synapseworks.pageharbor.document.scalability

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.os.Bundle
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.synapseworks.pageharbor.R
import org.synapseworks.pageharbor.document.session.*
import org.synapseworks.pageharbor.scanner.ScannerSpikeState
import org.synapseworks.pageharbor.ui.PageHarborApp

class M00NavigationInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val source = File(context.cacheDir, "shared-pdfs/m00-navigation.jpg")
    private fun pages(): List<DocumentPage> {
        source.parentFile!!.mkdirs()
        val bitmap = Bitmap.createBitmap(320, 480, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            Canvas(bitmap).drawText("Synthetic M00", 20f, 100f, Paint().apply { color = Color.BLACK; textSize = 24f })
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        } finally { bitmap.recycle() }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", source)
        return (1L..1000L).map { n -> DocumentPage(
            id = DocumentPageId(n),
            source = DocumentResource(uri.toString(), DocumentResourceOwnership.USER_OR_EXTERNAL),
            sourceCategory = DocumentSourceCategory.SELECTED_IMAGE,
            imageMetadata = DocumentImageMetadata(source.length(), 320, 480), persistentId = "m00-page-$n",
        ) }
    }
    @After fun cleanup() { source.delete() }

    @Test fun thousandPageDocumentCanJumpDirectlyAndRejectOutOfRangeInput() {
        val pages = pages()
        compose.setContent { PageHarborApp(
            scannerSpikeState = ScannerSpikeState.ResultSummary(1000, false, null), documentPages = pages,
        ) }
        compose.onNodeWithText("Page 1 of 1000").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("1001")
        compose.onNodeWithText("Go").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextReplacement("1000")
        val jumpStarted = SystemClock.elapsedRealtimeNanos()
        compose.onNodeWithText("Go").performClick()
        compose.onNodeWithText("Page 1000 of 1000").assertIsDisplayed()
        compose.waitForIdle()
        val jumpMillis = (SystemClock.elapsedRealtimeNanos() - jumpStarted) / 1_000_000L
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("m00_navigation", "{\"pages\":1000,\"jump_to_render_ms\":$jumpMillis,\"includes_test_synchronization\":true}")
        })
        compose.onNodeWithContentDescription("Next page").assertIsNotEnabled()
        compose.onNodeWithText("Add").performClick()
        compose.onNodeWithText("Scan pages").assertIsEnabled()
    }

    @Test fun lastPageCanBeSelectedWithoutComposingOneThousandSelectionRows() {
        val pages = pages()
        var selected: Set<String> = emptySet()
        compose.setContent { PageHarborApp(
            scannerSpikeState = ScannerSpikeState.ResultSummary(1000, false, null), documentPages = pages,
            libraryDocument = LibraryDocumentReference("m00-synthetic", "M00 synthetic"),
            onExtractLibraryPages = { ids, _, _ -> selected = ids },
        ) }
        compose.onNodeWithText("More").performClick()
        compose.onNodeWithText(context.getString(R.string.library_page_tools_action)).performScrollTo().performClick()
        compose.onNodeWithText("Page 1000").assertDoesNotExist()
        compose.onNodeWithTag("library-page-selection").performScrollToIndex(999)
        compose.onNodeWithText("Page 1000").performClick()
        compose.onNodeWithText(context.getString(R.string.library_extract_copy_action)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(setOf("m00-page-1000"), selected) }
    }
}
