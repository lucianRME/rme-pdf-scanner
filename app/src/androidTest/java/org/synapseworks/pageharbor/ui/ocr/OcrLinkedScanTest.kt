package org.synapseworks.pageharbor.ui.ocr

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.*
import org.junit.Assert.*
import org.synapseworks.pageharbor.document.session.DocumentImageMetadata
import org.synapseworks.pageharbor.ocr.*
import org.synapseworks.pageharbor.ui.home.OcrResultScreen
import org.synapseworks.pageharbor.ui.theme.PageHarborTheme

class OcrLinkedScanTest {
    @get:Rule val compose = createComposeRule()

    @Test fun passageOpensSourceZoomSurvivesTabSwitchAndCopyStaysOnText() {
        val file = File.createTempFile("linked-scan-", ".png", InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        val bitmap = Bitmap.createBitmap(500, 700, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.WHITE)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        try {
            val lines = listOf(OcrTextLine("First passage", OcrTextBounds(25f, 50f, 205f, 75f)),
                OcrTextLine("Second passage", OcrTextBounds(35f, 525f, 260f, 565f)))
            val result = OcrResult(listOf(OcrPageResult(0, "First passage\nSecond passage", layout = OcrPageLayout(500, 700, lines = lines))))
            var copied = ""
            compose.setContent { PageHarborTheme { OcrResultScreen(result, listOf(Uri.fromFile(file)), listOf(DocumentImageMetadata()),
                0, {}, SnackbarHostState(), {}, {}, {}, { copied = it }) } }
            val text = compose.onNodeWithText("First passage\nSecond passage")
            val layout = mutableListOf<TextLayoutResult>()
            text.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { assertTrue(it(layout)) }
            val pos = layout.single().getBoundingBox(17).center
            text.performTouchInput { click(pos) }
            val name = "Document preview, page 1 of 1"
            compose.waitUntil(15000) { compose.onAllNodesWithContentDescription(name).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Copy text").assertDoesNotExist()
            val page = compose.onNodeWithContentDescription(name)
            val img = page.captureToImage()
            val pixels = img.toPixelMap()
            // The second passage is in the bottom-left of the paper. Its center must be tinted.
            val fit = minOf(img.width / 500f, img.height / 700f)
            val x = ((img.width - 500 * fit) / 2 + 145 * fit).toInt()
            val y = ((img.height - 700 * fit) / 2 + 545 * fit).toInt()
            assertNotEquals(androidx.compose.ui.graphics.Color.White, pixels[x,y])
            val firstY = ((img.height - 700 * fit) / 2 + 60 * fit).toInt()
            assertEquals(androidx.compose.ui.graphics.Color.White, pixels[x,firstY])
            page.performTouchInput { doubleClick(center) }
            compose.waitForIdle()
            val zoom = page.fetchSemanticsNode().config[SemanticsProperties.StateDescription]
            assertTrue(zoom.contains("200"))
            compose.onNodeWithText("Text").performClick()
            compose.onNodeWithText("Copy text").performClick()
            assertEquals(result.plainText, copied)
            compose.onNodeWithText("Scan").performClick()
            compose.waitUntil(10000) {
                compose.onAllNodesWithContentDescription(name).fetchSemanticsNodes().singleOrNull()
                    ?.config?.getOrElse(SemanticsProperties.StateDescription) { "" } == zoom
            }
        } finally { file.delete() }
    }
}
