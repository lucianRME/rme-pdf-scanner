package org.synapseworks.pageharbor.document.targetsize

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.synapseworks.pageharbor.document.session.DocumentImageMetadata
import org.synapseworks.pageharbor.document.session.DocumentSourceCategory
import org.synapseworks.pageharbor.image.DocumentFilter
import org.synapseworks.pageharbor.ui.targetsize.FitUploadLimitScreen
import org.synapseworks.pageharbor.ui.theme.PageHarborTheme

class FitUploadLimitScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val ownedFiles = mutableListOf<File>()

    @After
    fun tearDown() {
        ownedFiles.forEach(File::deleteRecursively)
    }

    @Test
    fun chooseLimitSupportsExactPresetsAndValidatedCustomInput() {
        var prepared = false
        var state by mutableStateOf(TargetSizeWorkflowState.ChooseLimit(input()))
        composeRule.setContent {
            PageHarborTheme {
                screen(
                    state = state,
                    onCustomSelected = {
                        state = state.copy(
                            selection = TargetSizeLimitSelection.Custom(
                                text = "",
                                unit = TargetSizeCustomUnit.KB,
                                validation = TargetSizeCustomValidation.Invalid(
                                    TargetSizeCustomError.EMPTY,
                                ),
                            ),
                        )
                    },
                    onCustomTextChanged = { text ->
                        state = state.copy(
                            selection = TargetSizeLimitSelection.Custom(
                                text = text,
                                unit = TargetSizeCustomUnit.KB,
                                validation = TargetSizeCustomLimitParser.parse(
                                    text,
                                    TargetSizeCustomUnit.KB,
                                ),
                            ),
                        )
                    },
                    onPrepare = { prepared = true },
                )
            }
        }

        listOf("200 KB", "500 KB", "1 MB", "2 MB", "Custom").forEach { label ->
            composeRule.onNodeWithText(label).assertIsDisplayed()
        }
        composeRule.onNodeWithText("Custom").performClick()
        composeRule.onNodeWithText("Maximum size value").performTextInput("750")
        composeRule.onNodeWithText("Prepare PDF").performClick()

        assertEquals(750_000L, state.selection.targetBytes)
        assertTrue(prepared)
    }

    @Test
    fun chooseLimitUsesSingularPageCopyForOnePageDocument() {
        composeRule.setContent {
            PageHarborTheme {
                screen(
                    state = TargetSizeWorkflowState.ChooseLimit(input(pageCount = 1)),
                )
            }
        }

        composeRule.onNodeWithText("1 page").assertIsDisplayed()
        composeRule.onAllNodesWithText("1 pages").assertCountEquals(0)
    }

    @Test
    fun preparingAndImpossibleStatesExposeCancellationAndExplicitAlternative() {
        var cancelled = false
        var accepted = false
        val alternative = preparedPdf(pageCount = 2)
        var state by mutableStateOf<TargetSizeWorkflowState>(
            TargetSizeWorkflowState.Preparing(
                input = input(),
                selection = TargetSizeLimitSelection.Preset(TargetSizePreset.KB_200),
                targetBytes = TargetSizeBytes.KB_200,
                progress = TargetSizeProgress.ENCODING,
            ),
        )
        composeRule.setContent {
            PageHarborTheme {
                screen(
                    state = state,
                    onCancel = { cancelled = true },
                    onUseAlternative = { accepted = true },
                )
            }
        }

        composeRule.onNodeWithText("Creating the smaller PDF…").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").performClick()
        assertTrue(cancelled)

        composeRule.runOnIdle {
            state = TargetSizeWorkflowState.ImpossibleTarget(
                input = input(),
                selection = TargetSizeLimitSelection.Preset(TargetSizePreset.KB_200),
                requestedTargetBytes = TargetSizeBytes.KB_200,
                smallestRecommendedBytes = 436_000L,
                smallestRecommended = alternative,
                bytesAreMeasured = true,
            )
        }

        composeRule.onNodeWithText("Choose a larger limit").assertIsDisplayed()
        composeRule.onNodeWithText("Use 436 KB").assertIsEnabled().performClick()
        assertTrue(accepted)
    }

    @Test
    fun impossibleEstimateWithoutPreparedFileNeverSilentlyAcceptsIt() {
        composeRule.setContent {
            PageHarborTheme {
                screen(
                    state = TargetSizeWorkflowState.ImpossibleTarget(
                        input = input(),
                        selection = TargetSizeLimitSelection.Preset(TargetSizePreset.KB_200),
                        requestedTargetBytes = TargetSizeBytes.KB_200,
                        smallestRecommendedBytes = 436_000L,
                        smallestRecommended = null,
                        bytesAreMeasured = false,
                    ),
                )
            }
        }

        composeRule.onNodeWithText("Estimated").assertIsDisplayed()
        composeRule.onNodeWithText("Use 436 KB").assertIsNotEnabled()
        composeRule.onNodeWithText("Choose another limit").assertIsEnabled()
    }

    @Test
    fun actualPreparedPdfPreviewNavigatesPagesAndOffersExactResultActions() {
        val prepared = preparedPdf(pageCount = 2)
        composeRule.setContent {
            PageHarborTheme {
                screen(
                    state = TargetSizeWorkflowState.Prepared(
                        input = input(),
                        selection = TargetSizeLimitSelection.Preset(TargetSizePreset.MB_1),
                        requestedTargetBytes = TargetSizeBytes.MB_1,
                        prepared = prepared,
                        reusedOriginalQuality = false,
                    ),
                )
            }
        }

        composeRule.onNodeWithContentDescription("Prepared PDF preview, page 1 of 2")
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Next prepared page").performClick()
        composeRule.onNodeWithContentDescription("Prepared PDF preview, page 2 of 2")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Save").performScrollTo().assertIsEnabled()
        composeRule.onNodeWithText("Share").assertIsEnabled()
        composeRule.onNodeWithText("Change limit").assertIsEnabled()
    }

    @Test
    fun darkThemeAndTwoHundredPercentTextKeepCriticalControlsReachable() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                Box(androidx.compose.ui.Modifier.size(width = 320.dp, height = 600.dp)) {
                    PageHarborTheme(darkTheme = true) {
                        screen(TargetSizeWorkflowState.ChooseLimit(input()))
                    }
                }
            }
        }

        composeRule.onNodeWithText("Fit upload limit").assertIsDisplayed()
        val statusLabel = composeRule.onNodeWithText("Support status").fetchSemanticsNode().boundsInRoot
        val statusValue = composeRule.onNodeWithText("Ready to check").fetchSemanticsNode().boundsInRoot
        assertTrue(statusLabel.bottom <= statusValue.top)
        composeRule.onNodeWithText("Prepare PDF").performScrollTo().assertIsEnabled()
    }

    @Test
    fun typedFailuresUsePlainUserMessages() {
        composeRule.setContent {
            PageHarborTheme {
                screen(
                    TargetSizeWorkflowState.Failed(
                        input = input(),
                        selection = TargetSizeLimitSelection.Preset(TargetSizePreset.MB_1),
                        targetBytes = TargetSizeBytes.MB_1,
                        reason = TargetSizePdfFailure.ENCRYPTED_PDF,
                    ),
                )
            }
        }

        composeRule.onNodeWithText(
            "This PDF is password-protected and can’t be prepared for a size limit.",
        ).assertIsDisplayed()
        composeRule.onNodeWithText("Try again").assertIsEnabled()
    }

    @Suppress("LongParameterList")
    @Composable
    private fun screen(
        state: TargetSizeWorkflowState,
        onCustomSelected: () -> Unit = {},
        onCustomTextChanged: (String) -> Unit = {},
        onPrepare: () -> Unit = {},
        onCancel: () -> Unit = {},
        onUseAlternative: () -> Unit = {},
    ) = FitUploadLimitScreen(
        state = state,
        onBack = {},
        onPresetSelected = {},
        onCustomSelected = onCustomSelected,
        onCustomTextChanged = onCustomTextChanged,
        onCustomUnitChanged = {},
        onPrepare = onPrepare,
        onCancel = onCancel,
        onUseAlternative = onUseAlternative,
        onChangeLimit = {},
        onSave = {},
        onShare = {},
        onRetry = {},
    )

    private fun input(pageCount: Int = 2): TargetSizeWorkflowInput {
        val pages = List(pageCount) { index ->
            TargetSizePdfPage(
                pageId = index.toLong(),
                sourceUri = Uri.EMPTY,
                sourceCategory = DocumentSourceCategory.SCAN,
                contentType = "image/jpeg",
                imageMetadata = DocumentImageMetadata(
                    sourceByteCount = 3_600_000L,
                    width = 1_000,
                    height = 1_400,
                ),
                filter = DocumentFilter.ORIGINAL,
            )
        }
        return TargetSizeWorkflowInput(
            summary = TargetSizeSourceSummary("Tax form", pageCount, 3_600_000L * pageCount),
            request = TargetSizePdfRequest(pages, targetBytes = TargetSizeBytes.MB_1),
        )
    }

    private fun preparedPdf(pageCount: Int): TargetSizePreparedPdf {
        val directory = File(context.cacheDir, "m5-ui-${System.nanoTime()}").apply { mkdirs() }
        ownedFiles += directory
        val file = File(directory, "prepared.pdf")
        val document = PdfDocument()
        try {
            repeat(pageCount) { index ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(600, 800, index + 1).create())
                page.canvas.drawColor(Color.WHITE)
                page.canvas.drawText(
                    "Prepared page ${index + 1}",
                    40f,
                    80f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.BLACK
                        textSize = 28f
                    },
                )
                document.finishPage(page)
            }
            file.outputStream().use(document::writeTo)
        } finally {
            document.close()
        }
        val pagePlans = List(pageCount) { index ->
            TargetSizePagePlan(
                pageId = index.toLong(),
                targetWidthPx = 1_000,
                targetHeightPx = 1_400,
                minimumWidthPx = 960,
                minimumHeightPx = 1_344,
                jpegQuality = 80,
                minimumJpegQuality = 55,
                allocatedBytes = 100_000L,
                textStrategy = TargetSizeTextStrategy.NONE,
            )
        }
        return TargetSizePreparedPdf(
            file = file,
            workingDirectory = directory,
            finalBytes = file.length(),
            pageCount = pageCount,
            textLayerPageCount = 0,
            plan = TargetSizePlan(
                targetBytes = TargetSizeBytes.MB_1,
                inputClass = TargetSizeInputClass.IMAGE_DOCUMENT_SUPPORTED,
                pages = pagePlans,
                mandatoryPdfAndTextOverheadBytes = 8_000L,
                estimatedFullDocumentPasses = 1,
                maximumFullDocumentPasses = 2,
                warnings = emptySet(),
                estimatedMinimumAcceptableBytes = 100_000L,
            ),
            performance = TargetSizePerformance(1, 1, 1, 1, 1, file.length()),
        )
    }
}
