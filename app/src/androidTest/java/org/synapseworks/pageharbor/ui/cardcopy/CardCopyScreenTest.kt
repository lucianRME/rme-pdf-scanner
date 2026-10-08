package org.synapseworks.pageharbor.ui.cardcopy

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.synapseworks.pageharbor.document.cardcopy.CardCopyAcquisition
import org.synapseworks.pageharbor.document.cardcopy.CardCopyAlignment
import org.synapseworks.pageharbor.document.cardcopy.CardCopyGeometryEngine
import org.synapseworks.pageharbor.document.cardcopy.CardCopyLayout
import org.synapseworks.pageharbor.document.cardcopy.CardCopyPageSize
import org.synapseworks.pageharbor.document.cardcopy.CardCopyPreparedPdf
import org.synapseworks.pageharbor.document.cardcopy.CardCopySettings
import org.synapseworks.pageharbor.document.cardcopy.CardCopySide
import org.synapseworks.pageharbor.document.cardcopy.CardCopySideState
import org.synapseworks.pageharbor.document.cardcopy.CardCopySource
import org.synapseworks.pageharbor.document.cardcopy.CardCopySpacing
import org.synapseworks.pageharbor.document.cardcopy.CardCopyWorkflowState
import org.synapseworks.pageharbor.document.session.DocumentImageMetadata
import org.synapseworks.pageharbor.document.session.DocumentPageRotation
import org.synapseworks.pageharbor.ui.theme.PageHarborTheme

class CardCopyScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private lateinit var front: CardCopySource
    private lateinit var back: CardCopySource

    @Before
    fun setUp() {
        root = File(context.cacheDir, "card-copy-ui-${System.nanoTime()}").apply { mkdirs() }
        front = source(CardCopySide.FRONT, Color.RED)
        back = source(CardCopySide.BACK, Color.BLUE)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun acquisitionRequiresBothSidesAndExposesScanImportSemantics() {
        var scanned: CardCopySide? = null
        var imported: CardCopySide? = null
        composeRule.setContent {
            PageHarborTheme {
                CardCopyScreen(
                    state = CardCopyWorkflowState.AcquireSides(
                        front = CardCopySideState.Ready(front),
                    ),
                    onBack = {},
                    onScan = { scanned = it },
                    onImport = { imported = it },
                    onContinueReview = {},
                    onRotate = { _, _ -> },
                    onRemove = {},
                    onSwap = {},
                    onContinueLayout = {},
                    onPageSize = {},
                    onLayout = {},
                    onSpacing = {},
                    onAlignment = {},
                    onShowPreview = {},
                    onPrepare = {},
                    onSave = {},
                    onShare = {},
                    onRetry = {},
                )
            }
        }

        composeRule.onNodeWithText("Front").assertIsDisplayed()
        composeRule.onNodeWithText("Back").assertIsDisplayed()
        composeRule.onNodeWithText("Add the back of the card to continue.").assertIsDisplayed()
        composeRule.onNodeWithText("Continue").assertIsNotEnabled()
        composeRule.onAllNodesWithText("Scan")[1].performClick()
        composeRule.onAllNodesWithText("Import")[0].performClick()
        composeRule.runOnIdle {
            assertEquals(CardCopySide.BACK, scanned)
            assertEquals(CardCopySide.FRONT, imported)
        }
    }

    @Test
    fun reviewExposesReplaceRotateRemoveAndSwapAsClickableActions() {
        composeRule.setContent {
            PageHarborTheme {
                CardCopyScreen(
                    state = CardCopyWorkflowState.ReviewSides(
                        CardCopySideState.Ready(front),
                        CardCopySideState.Ready(back),
                    ),
                    onBack = {}, onScan = {}, onImport = {}, onContinueReview = {},
                    onRotate = { _, _ -> },
                    onRemove = {},
                    onSwap = {},
                    onContinueLayout = {}, onPageSize = {}, onLayout = {}, onSpacing = {},
                    onAlignment = {}, onShowPreview = {}, onPrepare = {}, onSave = {},
                    onShare = {}, onRetry = {},
                )
            }
        }

        assertEquals(2, composeRule.onAllNodesWithText("Replace").fetchSemanticsNodes().size)
        composeRule.onNode(hasText("Swap front and back") and hasClickAction())
            .assertExists()
        assertEquals(
            2,
            composeRule.onAllNodes(hasContentDescription("Rotate right") and hasClickAction())
                .fetchSemanticsNodes().size,
        )
        assertEquals(
            2,
            composeRule.onAllNodes(hasContentDescription("Remove") and hasClickAction())
                .fetchSemanticsNodes().size,
        )
    }

    @Test
    fun tabletDarkLargeFontLayoutKeepsExactPreviewAndPrimaryActionReachable() {
        var page = CardCopyPageSize.A4
        var layout = CardCopyLayout.AUTO
        var spacing = CardCopySpacing.NORMAL
        var alignment = CardCopyAlignment.CENTER
        var preview = 0
        composeRule.setContent {
            PageHarborTheme(darkTheme = true) {
                CompositionLocalProvider(LocalDensity provides Density(2f, fontScale = 2f)) {
                    CardCopyScreen(
                        state = CardCopyWorkflowState.Layout(front, back, CardCopySettings()),
                        onBack = {}, onScan = {}, onImport = {}, onContinueReview = {},
                        onRotate = { _, _ -> }, onRemove = {}, onSwap = {}, onContinueLayout = {},
                        onPageSize = { page = it }, onLayout = { layout = it },
                        onSpacing = { spacing = it }, onAlignment = { alignment = it },
                        onShowPreview = { preview++ }, onPrepare = {}, onSave = {},
                        onShare = {}, onRetry = {},
                    )
                }
            }
        }

        composeRule.onNodeWithContentDescription("Card copy page preview").assertIsDisplayed()
        composeRule.onNodeWithText("Letter").performClick()
        composeRule.onNodeWithText("Horizontal").performClick()
        composeRule.onNodeWithText("Wide").performClick()
        composeRule.onNodeWithText("Right / Bottom").performClick()
        composeRule.onNodeWithText("Preview").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        composeRule.runOnIdle {
            assertEquals(CardCopyPageSize.LETTER, page)
            assertEquals(CardCopyLayout.HORIZONTAL, layout)
            assertEquals(CardCopySpacing.WIDE, spacing)
            assertEquals(CardCopyAlignment.END, alignment)
            assertEquals(1, preview)
        }
    }

    @Test
    fun preparedStateExposesSaveShareAndChangeLayout() {
        var saves = 0
        var shares = 0
        var changes = 0
        val settings = CardCopySettings()
        val geometry = CardCopyGeometryEngine.calculate(
            320, 200, DocumentPageRotation.DEGREES_0,
            320, 200, DocumentPageRotation.DEGREES_0,
            settings,
        )
        val fakePdf = File(root, "prepared.pdf").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val prepared = CardCopyPreparedPdf(
            fakePdf, root, fakePdf.length(), geometry, 1L, fakePdf.length(), 1_024L,
        )
        composeRule.setContent {
            PageHarborTheme {
                CardCopyScreen(
                    state = CardCopyWorkflowState.Prepared(front, back, settings, prepared),
                    onBack = { changes++ }, onScan = {}, onImport = {}, onContinueReview = {},
                    onRotate = { _, _ -> }, onRemove = {}, onSwap = {}, onContinueLayout = {},
                    onPageSize = {}, onLayout = {}, onSpacing = {}, onAlignment = {},
                    onShowPreview = {}, onPrepare = {}, onSave = { saves++ },
                    onShare = { shares++ }, onRetry = {},
                )
            }
        }

        composeRule.onNodeWithText("Save PDF").performClick()
        composeRule.onNodeWithText("Share").performClick()
        composeRule.onNodeWithText("Change layout").performClick()
        composeRule.runOnIdle {
            assertEquals(1, saves)
            assertEquals(1, shares)
            assertEquals(1, changes)
        }
    }

    private fun source(side: CardCopySide, color: Int): CardCopySource {
        val directory = File(root, side.name.lowercase()).apply { mkdirs() }
        val file = File(directory, "source.jpg")
        val bitmap = Bitmap.createBitmap(320, 200, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(color)
            FileOutputStream(file).use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
        } finally {
            bitmap.recycle()
        }
        return CardCopySource(
            side, CardCopyAcquisition.IMPORT, file, directory,
            DocumentImageMetadata(file.length(), 320, 200),
        )
    }
}
