package org.synapseworks.pageharbor.ui

import android.os.Build
import android.os.SystemClock
import android.view.WindowManager
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.synapseworks.pageharbor.document.sharesafe.ShareSafeConfigureStep
import org.synapseworks.pageharbor.document.sharesafe.ShareSafeCopyWorkflowState
import org.synapseworks.pageharbor.document.sharesafe.ShareSafeRecipe
import org.synapseworks.pageharbor.document.sharesafe.ShareSafeSourceKind
import org.synapseworks.pageharbor.document.sharesafe.ShareSafeTargetSizeConfig
import org.synapseworks.pageharbor.document.sharesafe.ShareSafeWatermarkConfig
import org.synapseworks.pageharbor.ui.sharesafe.ShareSafeCopyScreen
import org.synapseworks.pageharbor.document.session.DocumentPage
import org.synapseworks.pageharbor.document.session.DocumentPageId
import org.synapseworks.pageharbor.document.session.DocumentResource
import org.synapseworks.pageharbor.document.session.DocumentResourceOwnership
import org.synapseworks.pageharbor.document.session.DocumentSourceCategory
import org.synapseworks.pageharbor.library.LibraryOcrPageSnapshot
import org.synapseworks.pageharbor.library.LibraryOcrReviewPage
import org.synapseworks.pageharbor.ocr.OcrModelState
import org.synapseworks.pageharbor.ocr.OcrScript
import org.synapseworks.pageharbor.ocr.OcrScriptRecommendation
import org.synapseworks.pageharbor.ocr.OcrScriptSelection
import org.synapseworks.pageharbor.ocr.review.OcrReviewLoadState
import org.synapseworks.pageharbor.ocr.review.OcrReviewUiState
import org.synapseworks.pageharbor.scanner.ScannerSpikeState
import org.synapseworks.pageharbor.ui.ocr.OcrLanguageSettingsScreen
import org.synapseworks.pageharbor.ui.ocr.OcrReviewScreen
import org.synapseworks.pageharbor.ui.theme.PageHarborTheme

/**
 * Edge-to-edge activity tests with deterministic cutout/IME geometry and separate real-keyboard
 * Share-Safe cases. Real IME assertions use native window insets and preserve device density;
 * device navigation and theme checks remain separate.
 */
@RunWith(AndroidJUnit4::class)
class M02WindowInsetsInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var composeHost: View

    @Before
    fun enableEdgeToEdgeWindow() {
        // Native WindowInsets.Builder preserves independently supplied inset types on API 30+.
        assumeTrue("Deterministic independent inset delivery requires API 30+", Build.VERSION.SDK_INT >= 30)
        compose.activityRule.scenario.onActivity {
            it.enableEdgeToEdge()
            it.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    @Test
    fun documentActionAndEditBarsAvoidHorizontalCutouts() {
        setContent {
            PageHarborApp(
                screen = PageHarborScreen.ScanResult,
                scannerSpikeState = ScannerSpikeState.ResultSummary(1, false, null),
                documentPages = listOf(syntheticPage()),
            )
        }
        val cutout = insetPixels(48)
        val bottom = insetPixels(24)
        deliverInsets(leftCutout = cutout, rightCutout = cutout, bottom = bottom)

        listOf("Add", "Edit", "OCR", "Share", "More").forEach { action ->
            val node = compose.onNodeWithContentDescription(action).assertIsDisplayed()
            assertHorizontallySafe(node.fetchSemanticsNode().boundsInRoot, cutout)
            assertAboveBottom(node.fetchSemanticsNode().boundsInRoot, bottom)
        }

        compose.onNodeWithContentDescription("Edit").performClick()
        // Insets are delivered again after the edit panel subscribes to WindowInsets.
        deliverInsets(leftCutout = cutout, rightCutout = cutout, bottom = bottom)
        val done = compose.onNodeWithText("Done").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertHorizontallySafe(done, cutout)
        assertAboveBottom(done, bottom)
    }

    @Test
    fun documentNavigationSpaceIsAppliedOnce() {
        setContent {
            PageHarborApp(
                screen = PageHarborScreen.ScanResult,
                scannerSpikeState = ScannerSpikeState.ResultSummary(1, false, null),
                documentPages = listOf(syntheticPage()),
            )
        }
        val firstBottom = insetPixels(16)
        val secondBottom = insetPixels(48)
        deliverInsets(bottom = firstBottom)
        val before = compose.onNodeWithContentDescription("Add").fetchSemanticsNode().boundsInRoot
        deliverInsets(bottom = secondBottom)
        val after = compose.onNodeWithContentDescription("Add").fetchSemanticsNode().boundsInRoot

        assertEquals(
            "A navigation inset increase must move the action by that amount exactly once",
            (secondBottom - firstBottom).toFloat(),
            before.bottom - after.bottom,
            2f,
        )
        assertEquals("Changing system insets must preserve the action height", before.height, after.height, 2f)
    }

    @Test
    fun ocrLanguageHeaderBackActionAvoidsHorizontalCutout() {
        setContent {
            OcrLanguageSettingsScreen(
                selection = OcrScriptSelection.Automatic,
                recommendation = OcrScriptRecommendation(
                    OcrScript.LATIN,
                    OcrScriptRecommendation.Basis.LATIN_FALLBACK,
                ),
                modelStates = mapOf(OcrScript.LATIN to OcrModelState.Bundled),
                onSelectionChanged = {},
                onInstall = {},
                onRefresh = {},
                onBack = {},
            )
        }
        val cutout = insetPixels(48)
        val top = insetPixels(24)
        deliverInsets(leftCutout = cutout, rightCutout = cutout, top = top)

        val back = compose.onNodeWithContentDescription("Back").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertHorizontallySafe(back, cutout)
        assertTrue("Back must remain below the status-bar area", back.top >= top - 2f)
    }

    @Test
    fun ocrCorrectionSaveRemainsReachableAboveImeAndRecoversAfterItCloses() {
        var saves = 0
        setContent {
            OcrReviewScreen(
                state = reviewState(),
                onBack = {},
                onBeginEdit = {},
                onDraftChange = {},
                onSaveCorrection = { saves += 1 },
                onCancelEdit = {},
                onRevertCorrection = {},
                onSelectPage = {},
                onRerunPage = {},
                onRerunDocument = {},
                onCancelRerun = {},
            )
        }
        val bottom = insetPixels(24)
        deliverInsets(bottom = bottom)
        val closedViewport = compose.onNode(hasScrollAction()).fetchSemanticsNode().boundsInRoot
        val ime = compose.runOnIdle { (composeHost.height * 0.4f).toInt() }
        deliverInsets(bottom = bottom, ime = ime)

        val openViewport = compose.onNode(hasScrollAction()).fetchSemanticsNode().boundsInRoot
        assertAboveBottom(openViewport, ime)
        assertTrue("The scrollable viewport must shrink when the keyboard covers it", openViewport.height < closedViewport.height)
        val editor = compose.onNodeWithTag("ocr_review_editor").performScrollTo().assertIsDisplayed()
        assertAboveBottom(editor.fetchSemanticsNode().boundsInRoot, ime)

        val save = compose.onNodeWithText("Save correction").performScrollTo().assertIsDisplayed()
        assertAboveBottom(save.fetchSemanticsNode().boundsInRoot, ime)
        save.performClick()
        compose.runOnIdle { assertEquals(1, saves) }

        deliverInsets(bottom = bottom)
        val restoredViewport = compose.onNode(hasScrollAction()).fetchSemanticsNode().boundsInRoot
        assertEquals("Closing the keyboard restores the viewport", closedViewport.bottom, restoredViewport.bottom, 2f)
        val afterClose = compose.onNodeWithText("Save correction").performScrollTo().assertIsDisplayed()
        assertAboveBottom(afterClose.fetchSemanticsNode().boundsInRoot, bottom)
        afterClose.performClick()
        compose.runOnIdle { assertEquals(2, saves) }
    }

    @Test
    fun shareSafePurposeAndNextRemainAboveRealImeAtTwoHundredPercentFont() {
        assertShareSafeAboveRealIme(ShareSafeConfigureStep.PURPOSE)
    }

    @Test
    fun shareSafeSizeAndNextRemainAboveRealImeAtTwoHundredPercentFont() {
        assertShareSafeAboveRealIme(ShareSafeConfigureStep.SIZE)
    }

    private fun assertShareSafeAboveRealIme(step: ShareSafeConfigureStep) {
        var next = 0
        var state by mutableStateOf(
            ShareSafeCopyWorkflowState.Configure(
                title = "Synthetic IME fixture",
                pageCount = 1,
                sourceKind = ShareSafeSourceKind.DOCUMENT,
                recipe = if (step == ShareSafeConfigureStep.PURPOSE) {
                    ShareSafeRecipe.EMAIL_DOCUMENT
                } else {
                    ShareSafeRecipe.UPLOAD_TO_PORTAL
                },
                watermark = ShareSafeWatermarkConfig(enabled = true),
                targetSize = ShareSafeTargetSizeConfig(enabled = true, maximumBytes = 750_000L),
                step = step,
            ),
        )
        setContent {
            val nativeDensity = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(nativeDensity, fontScale = 2f)) {
                PageHarborTheme(darkTheme = true) {
                    shareSafeScreen(
                        state = state,
                        onPurpose = { state = state.copy(watermark = state.watermark.copy(purposeText = it)) },
                        onTargetBytes = { state = state.copy(targetSize = state.targetSize.copy(maximumBytes = it)) },
                        onNext = { next += 1 },
                    )
                }
            }
        }
        compose.runOnIdle {
            assertEquals("The fixture must fill the real window", composeHost.rootView.height, composeHost.height)
        }
        val field = if (step == ShareSafeConfigureStep.PURPOSE) "Purpose" else "Maximum size value"
        compose.onNodeWithText(field).performScrollTo().performClick().performTextInput(
            if (step == ShareSafeConfigureStep.PURPOSE) "Synthetic purpose" else "750",
        )
        val imeBottom = waitForRealIme(visible = true)
        val imeTop = compose.runOnIdle { composeHost.rootView.height - imeBottom }
        val editor = compose.onNodeWithText(field).performScrollTo().assertIsDisplayed()
        assertTrue("The input must be above the real IME", editor.fetchSemanticsNode().boundsInRoot.bottom <= imeTop + 2f)
        if (step == ShareSafeConfigureStep.PURPOSE) {
            compose.onNodeWithText("Repeated").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Strong").performScrollTo().assertIsDisplayed()
        }
        val button = compose.onNodeWithText("Next").assertIsDisplayed().assertIsEnabled()
        val openBottom = button.fetchSemanticsNode().boundsInRoot.bottom
        assertTrue("Next must be above the real IME", openBottom <= imeTop + 2f)
        button.performClick()
        compose.runOnIdle { assertEquals(1, next) }

        compose.activityRule.scenario.onActivity {
            WindowInsetsControllerCompat(it.window, it.window.decorView).hide(WindowInsetsCompat.Type.ime())
        }
        waitForRealIme(visible = false)
        val restored = compose.onNodeWithText("Next").assertIsDisplayed().assertIsEnabled()
        assertTrue("Closing IME restores the full viewport", restored.fetchSemanticsNode().boundsInRoot.bottom > openBottom)
        restored.performClick()
        compose.runOnIdle { assertEquals(2, next) }
    }

    private fun waitForRealIme(visible: Boolean): Int {
        var previous = -1
        var stableSince = SystemClock.uptimeMillis()
        var bottom = 0
        compose.waitUntil(timeoutMillis = 10_000) {
            bottom = compose.runOnIdle {
                requireNotNull(ViewCompat.getRootWindowInsets(composeHost))
                    .getInsets(WindowInsetsCompat.Type.ime()).bottom
            }
            if (bottom != previous) {
                previous = bottom
                stableSince = SystemClock.uptimeMillis()
            }
            (bottom > 0) == visible && SystemClock.uptimeMillis() - stableSince >= 200
        }
        compose.waitForIdle()
        return bottom
    }

    @Composable
    private fun shareSafeScreen(
        state: ShareSafeCopyWorkflowState,
        onPurpose: (String) -> Unit,
        onTargetBytes: (Long) -> Unit,
        onNext: () -> Unit,
    ) = ShareSafeCopyScreen(
        state = state, onBack = {}, onRecipe = {}, onPage = {}, onAddRedaction = {},
        onSelectRedaction = {}, onUpdateRedaction = {}, onDeleteRedaction = {},
        onWatermarkEnabled = {}, onPurpose = onPurpose, onDateEnabled = {}, onDate = {},
        onPlacement = {}, onOpacity = {}, onTargetEnabled = {}, onTargetBytes = onTargetBytes,
        onNext = onNext, onPrepare = {}, onSave = {}, onShare = {}, onRetry = {},
    )

    private fun setContent(content: @Composable () -> Unit) {
        compose.setContent {
            composeHost = LocalView.current
            PageHarborTheme(content = content)
        }
        compose.waitForIdle()
    }

    private fun insetPixels(dp: Int): Int = compose.runOnIdle {
        (dp * composeHost.resources.displayMetrics.density).toInt()
    }

    private fun deliverInsets(
        leftCutout: Int = 0,
        rightCutout: Int = 0,
        top: Int = 0,
        bottom: Int = 0,
        ime: Int = 0,
    ) {
        compose.runOnIdle {
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, top, 0, 0))
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, bottom))
                .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(leftCutout, 0, rightCutout, 0))
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, ime))
                .setVisible(WindowInsetsCompat.Type.statusBars(), top > 0)
                .setVisible(WindowInsetsCompat.Type.navigationBars(), bottom > 0)
                .setVisible(WindowInsetsCompat.Type.ime(), ime > 0)
                .build()
            ViewCompat.dispatchApplyWindowInsets(composeHost, insets)
        }
        compose.waitForIdle()
    }

    private fun assertHorizontallySafe(bounds: Rect, inset: Int) {
        val width = compose.runOnIdle { composeHost.width }
        assertTrue("Control must remain right of the left cutout", bounds.left >= inset - 2f)
        assertTrue("Control must remain left of the right cutout", bounds.right <= width - inset + 2f)
    }

    private fun assertAboveBottom(bounds: Rect, inset: Int) {
        val height = compose.runOnIdle { composeHost.height }
        assertTrue("Control must remain above the obscured bottom area", bounds.bottom <= height - inset + 2f)
    }

    private fun syntheticPage() = DocumentPage(
        id = DocumentPageId(1L),
        source = DocumentResource(
            reference = "content://org.synapseworks.pageharbor.test/m02/page",
            ownership = DocumentResourceOwnership.USER_OR_EXTERNAL,
        ),
        sourceCategory = DocumentSourceCategory.SCAN,
    )

    private fun reviewState(): OcrReviewUiState {
        val text = "Synthetic correction text"
        val page = LibraryOcrReviewPage(
            snapshot = LibraryOcrPageSnapshot(
                documentId = "m02-synthetic-document",
                pageId = "m02-synthetic-page",
                pagePosition = 0,
                documentContentRevision = 1,
                pageVisualRevision = 1,
                ocrStateRevision = 1,
                activeArtifactRevision = 1,
                contentSha256 = "a".repeat(64),
                rotationDegrees = 0,
                filterName = "ORIGINAL",
            ),
            rawText = text,
            effectiveText = text,
            correctedText = null,
            actualScript = "LATIN",
            recognizedAtMillis = 1,
            correctionBaseArtifactRevision = null,
            rawLines = listOf(text),
        )
        return OcrReviewUiState(
            active = true,
            documentId = page.snapshot.documentId,
            documentTitle = "Synthetic OCR fixture",
            pageIds = listOf(page.snapshot.pageId),
            selectedPageId = page.snapshot.pageId,
            selectedPageIndex = 0,
            page = page,
            loadState = OcrReviewLoadState.READY,
            editing = true,
            draftText = text,
        )
    }
}
