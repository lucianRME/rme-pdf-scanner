package org.synapseworks.pageharbor

import android.content.res.Configuration
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.synapseworks.pageharbor.ocr.OcrPageResult
import org.synapseworks.pageharbor.ocr.OcrResult
import org.synapseworks.pageharbor.scanner.ScannerSpikeState
import org.synapseworks.pageharbor.ui.PageHarborScreen

/** Native display changes, deliberately opt-in on a disposable emulator only. */
class M03WindowResizeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun clearPreviousSyntheticSession() {
        // The opt-in resize case creates a recoverable session in this isolated test package.
        compose.activityRule.scenario.onActivity { it.discardForTest() }
    }

    @Test
    fun toolsDestinationAndActionsSurviveActivityRecreation() {
        compose.onNode(hasText("Tools") or hasContentDescription("Tools")).assertIsDisplayed().performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("Extract text with OCR"))
        compose.onNodeWithContentDescription("Extract text with OCR").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("Extract text with OCR"))
        compose.onNodeWithContentDescription("Extract text with OCR").assertIsDisplayed().performClick()
        compose.onNodeWithText("Choose a document").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Scan").assertIsDisplayed()
    }

    @Test
    fun completedDocumentAndOcrSelectionSurviveResizeAndRecreation() {
        assumeTrue(
            "Requires an explicitly owned disposable emulator",
            InstrumentationRegistry.getArguments().getString("m03AllowDisplayResize") == "true" &&
                android.os.Build.MODEL.startsWith("sdk_gphone"),
        )
        val originalSize = shell("wm size")
        val override = Regex("Override size: (\\d+x\\d+)").find(originalSize)?.groupValues?.get(1)
        val summary = ScannerSpikeState.ResultSummary(2, true, 2)
        compose.activityRule.scenario.onActivity {
            it.restoreCompletedSessionForTest(
                summary = summary,
                ocrResult = OcrResult(listOf(
                    OcrPageResult(0, "First fixture page"),
                    OcrPageResult(1, "Selected fixture page"),
                )),
                screen = PageHarborScreen.OcrResult,
                selectedOcrPageIndex = 1,
            )
        }
        try {
            listOf(1080 to 2400, 2560 to 1600, 840 to 1575, 1600 to 2560).forEach { (width, height) ->
                shell("wm size ${width}x$height")
                compose.waitUntil(10_000) {
                    var matched = false
                    compose.activityRule.scenario.onActivity {
                        matched = it.window.decorView.width == width &&
                            it.window.decorView.height == height &&
                            it.resources.configuration.orientation == if (width > height) {
                                Configuration.ORIENTATION_LANDSCAPE
                            } else Configuration.ORIENTATION_PORTRAIT
                    }
                    matched
                }
                assertRetained(summary)
                compose.activityRule.scenario.recreate()
                assertRetained(summary)
                compose.onNodeWithText("Selected fixture page").performScrollTo().assertIsDisplayed()
            }
        } finally {
            shell(if (override == null) "wm size reset" else "wm size $override")
        }
    }

    private fun assertRetained(summary: ScannerSpikeState.ResultSummary) {
        compose.activityRule.scenario.onActivity {
            assertEquals(PageHarborScreen.OcrResult, it.sessionScreenForTest())
            assertEquals(summary, it.sessionSummaryForTest())
            assertEquals(1, it.selectedOcrPageForTest())
            assertTrue(!it.isFinishing)
        }
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
    ).bufferedReader().use { it.readText() }
}
