package org.synapseworks.pageharbor.ui.sharesafe

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.synapseworks.pageharbor.document.sharesafe.ShareSafeConfigureStep
import org.synapseworks.pageharbor.document.sharesafe.ShareSafeArtifactStage
import org.synapseworks.pageharbor.document.sharesafe.ShareSafeAttestedArtifact
import org.synapseworks.pageharbor.document.sharesafe.ShareSafeCopyWorkflowState
import org.synapseworks.pageharbor.document.sharesafe.ShareSafePerformance
import org.synapseworks.pageharbor.document.sharesafe.ShareSafePreparedPdf
import org.synapseworks.pageharbor.document.sharesafe.ShareSafeRecipe
import org.synapseworks.pageharbor.document.sharesafe.ShareSafeSearchableTextStatus
import org.synapseworks.pageharbor.document.sharesafe.ShareSafeSourceKind
import org.synapseworks.pageharbor.document.sharesafe.ShareSafeTargetSizeConfig
import org.synapseworks.pageharbor.document.sharesafe.ShareSafeWatermarkConfig
import org.synapseworks.pageharbor.document.redaction.RedactionBounds
import org.synapseworks.pageharbor.document.redaction.RedactionCoordinateSpace
import org.synapseworks.pageharbor.document.redaction.RedactionRegion
import org.synapseworks.pageharbor.document.redaction.RedactionRegionId
import org.synapseworks.pageharbor.document.redaction.RedactionTarget
import org.synapseworks.pageharbor.document.redaction.RedactionTargetKind
import org.synapseworks.pageharbor.document.redaction.RedactedPdfImageExpectation
import org.synapseworks.pageharbor.document.redaction.RedactedPdfPageExpectation
import org.synapseworks.pageharbor.document.redaction.RedactedPdfVerificationEvidence
import org.synapseworks.pageharbor.document.redaction.RedactedPdfVerificationPolicy
import org.synapseworks.pageharbor.ui.home.ZoomableDocumentPreview
import org.synapseworks.pageharbor.ui.theme.PageHarborTheme

class ShareSafeCopyScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun recipeSelectionKeepsIdAvailableWithoutDocumentAndExplainsEveryTask() {
        var selected: ShareSafeRecipe? = null
        composeRule.setContent {
            PageHarborTheme {
                screen(
                    state = ShareSafeCopyWorkflowState.ChooseRecipe(documentAvailable = false),
                    onRecipe = { selected = it },
                )
            }
        }

        composeRule.onNodeWithText("Upload to a portal").assertIsNotEnabled()
        composeRule.onNodeWithText("Share an ID copy").assertIsEnabled().performClick()
        composeRule.onNodeWithText("Email a document").assertIsNotEnabled()
        composeRule.onNodeWithText("Archive a document").assertIsNotEnabled()
        composeRule.runOnIdle { assertEquals(ShareSafeRecipe.SHARE_ID_COPY, selected) }
    }

    @Test
    fun purposeControlsRemainReachableAtTwoHundredPercentFontInDarkMode() {
        var purpose = ""
        var next = false
        var state by mutableStateOf(
            ShareSafeCopyWorkflowState.Configure(
                title = "Synthetic",
                pageCount = 1,
                sourceKind = ShareSafeSourceKind.DOCUMENT,
                recipe = ShareSafeRecipe.EMAIL_DOCUMENT,
                watermark = ShareSafeWatermarkConfig(enabled = true),
                targetSize = ShareSafeTargetSizeConfig(),
                step = ShareSafeConfigureStep.PURPOSE,
            ),
        )
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                PageHarborTheme(darkTheme = true) {
                    Box(Modifier.size(400.dp, 700.dp)) {
                        screen(
                            state = state,
                            onPurpose = {
                                purpose = it
                                state = state.copy(watermark = state.watermark.copy(purposeText = it))
                            },
                            onNext = { next = true },
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithText("Purpose").performTextInput("For school registration")
        composeRule.onNodeWithText("Repeated").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Strong").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Next").assertIsDisplayed().performClick()
        composeRule.runOnIdle {
            assertEquals("For school registration", purpose)
            assertTrue(next)
        }
    }

    @Test
    fun sizeStepUsesTheExistingValidatedCustomTargetParser() {
        var selectedBytes: Long? = null
        var next = false
        composeRule.setContent {
            PageHarborTheme {
                screen(
                    state = ShareSafeCopyWorkflowState.Configure(
                        title = "Synthetic",
                        pageCount = 1,
                        sourceKind = ShareSafeSourceKind.DOCUMENT,
                        recipe = ShareSafeRecipe.UPLOAD_TO_PORTAL,
                        watermark = ShareSafeWatermarkConfig(),
                        targetSize = ShareSafeTargetSizeConfig(enabled = true),
                        step = ShareSafeConfigureStep.SIZE,
                    ),
                    onTargetBytes = { selectedBytes = it },
                    onNext = { next = true },
                )
            }
        }

        composeRule.onNodeWithText("Custom").performClick()
        composeRule.onNodeWithText("KB").performClick()
        composeRule.onNodeWithText("Maximum size value").performTextInput("750")
        composeRule.onNodeWithText("Next").performClick()
        composeRule.runOnIdle {
            assertEquals(750_000L, selectedBytes)
            assertTrue(next)
        }
    }

    @Test
    fun sizePrimaryActionStaysVisibleAtThreeTwentyDpAndTwoHundredPercentFont() {
        var next = false
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                PageHarborTheme(darkTheme = true) {
                    Box(Modifier.size(320.dp, 600.dp)) {
                        screen(
                            state = ShareSafeCopyWorkflowState.Configure(
                                title = "Synthetic",
                                pageCount = 1,
                                sourceKind = ShareSafeSourceKind.DOCUMENT,
                                recipe = ShareSafeRecipe.UPLOAD_TO_PORTAL,
                                watermark = ShareSafeWatermarkConfig(),
                                targetSize = ShareSafeTargetSizeConfig(
                                    enabled = true,
                                    maximumBytes = 750_000L,
                                ),
                                step = ShareSafeConfigureStep.SIZE,
                            ),
                            onNext = { next = true },
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithText("Next").assertIsDisplayed().assertIsNotEnabled()
        composeRule.onNodeWithText("Maximum size value").performScrollTo().performTextInput("750")
        composeRule.onNodeWithText("Next").assertIsDisplayed().assertIsEnabled().performClick()
        composeRule.runOnIdle { assertTrue(next) }
    }

    @Test
    fun impossibleTargetExplainsReadabilityAndOffersChangeTarget() {
        composeRule.setContent {
            PageHarborTheme {
                screen(
                    state = ShareSafeCopyWorkflowState.Failed(
                        reason = org.synapseworks.pageharbor.document.sharesafe.ShareSafeFailure.TARGET_NOT_REACHABLE,
                        canRetry = true,
                    ),
                )
            }
        }

        composeRule.onNodeWithText("Can’t reach this size without reducing readability.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Change target").assertIsDisplayed()
    }

    @Test
    fun preparedSaveAndShareActionsStayVisibleAtThreeTwentyDpAndTwoHundredPercentFont() {
        val prepared = syntheticPreparedPdf()
        try {
            composeRule.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                    PageHarborTheme(darkTheme = true) {
                        Box(Modifier.size(320.dp, 600.dp)) {
                            screen(
                                state = ShareSafeCopyWorkflowState.Prepared(
                                    title = "Synthetic",
                                    sourceKind = ShareSafeSourceKind.DOCUMENT,
                                    prepared = prepared,
                                ),
                            )
                        }
                    }
                }
            }

            composeRule.onNodeWithText("Save PDF").assertIsDisplayed()
            composeRule.onNodeWithText("Share").assertIsDisplayed()
            composeRule.onNodeWithText("Change settings").assertIsDisplayed()
        } finally {
            prepared.artifact.ownedDirectory.deleteRecursively()
        }
    }

    @Test
    fun selectedRedactionCanBeDraggedDirectlyThenNudged() {
        val preview = syntheticPreview()
        val id = RedactionRegionId("direct-drag")
        val initial = RedactionBounds(0.2f, 0.3f, 0.5f, 0.5f)
        val region = RedactionRegion(
            id = id,
            coordinateSpace = RedactionCoordinateSpace.SOURCE_NORMALIZED,
            bounds = initial,
            target = RedactionTarget(RedactionTargetKind.DOCUMENT_PAGE, "synthetic-page"),
            confirmed = true,
            order = 0,
        )
        val updates = mutableListOf<RedactionBounds>()
        var state by mutableStateOf(
            ShareSafeCopyWorkflowState.Configure(
                title = "Synthetic",
                pageCount = 1,
                sourceKind = ShareSafeSourceKind.DOCUMENT,
                recipe = ShareSafeRecipe.EMAIL_DOCUMENT,
                watermark = ShareSafeWatermarkConfig(),
                targetSize = ShareSafeTargetSizeConfig(),
                step = ShareSafeConfigureStep.REDACT,
                selectedPagePreviewUri = Uri.fromFile(preview),
                redactions = listOf(region),
                selectedRedactionId = id,
            ),
        )
        try {
            composeRule.setContent {
                PageHarborTheme {
                    screen(
                        state = state,
                        onSelectRedaction = { selected ->
                            state = state.copy(selectedRedactionId = selected)
                        },
                        onUpdateRedaction = { bounds ->
                            updates += bounds
                            state = state.copy(
                                redactions = state.redactions.map { current ->
                                    if (current.id == id) current.copy(bounds = bounds) else current
                                },
                            )
                        },
                    )
                }
            }

            val previewNode = composeRule.onNodeWithContentDescription(
                "Redaction preview. Drag a blackout region to move it; pinch to zoom.",
            )
            composeRule.waitUntil(timeoutMillis = 5_000) {
                runCatching { previewNode.assertIsDisplayed() }.isSuccess
            }
            previewNode.performTouchInput {
                swipe(
                    start = Offset(width * 0.35f, height * 0.4f),
                    end = Offset(width * 0.45f, height * 0.4f),
                    durationMillis = 500,
                )
            }
            composeRule.runOnIdle {
                assertTrue(updates.isNotEmpty())
                assertTrue(state.redactions.single().bounds.left > initial.left)
                assertEquals(initial.width, state.redactions.single().bounds.width, 0.0001f)
                assertEquals(initial.height, state.redactions.single().bounds.height, 0.0001f)
            }

            val afterDrag = state.redactions.single().bounds
            composeRule.onNodeWithText("→").performScrollTo().performClick()
            composeRule.runOnIdle {
                assertTrue(state.redactions.single().bounds.left > afterDrag.left)
            }
        } finally {
            preview.delete()
        }
    }

    @Test
    fun editorBlackoutIsFullyOpaqueOverWhiteDocument() {
        val preview = syntheticPreview()
        val id = RedactionRegionId("opaque-editor-blackout")
        val region = RedactionRegion(
            id = id,
            coordinateSpace = RedactionCoordinateSpace.SOURCE_NORMALIZED,
            bounds = RedactionBounds(0.2f, 0.3f, 0.8f, 0.5f),
            target = RedactionTarget(RedactionTargetKind.DOCUMENT_PAGE, "synthetic-page"),
            confirmed = true,
            order = 0,
        )
        try {
            composeRule.setContent {
                PageHarborTheme {
                    screen(
                        state = ShareSafeCopyWorkflowState.Configure(
                            title = "Synthetic",
                            pageCount = 1,
                            sourceKind = ShareSafeSourceKind.DOCUMENT,
                            recipe = ShareSafeRecipe.EMAIL_DOCUMENT,
                            watermark = ShareSafeWatermarkConfig(),
                            targetSize = ShareSafeTargetSizeConfig(),
                            step = ShareSafeConfigureStep.REDACT,
                            selectedPagePreviewUri = Uri.fromFile(preview),
                            redactions = listOf(region),
                            selectedRedactionId = id,
                        ),
                    )
                }
            }

            val previewNode = composeRule.onNodeWithContentDescription(
                "Redaction preview. Drag a blackout region to move it; pinch to zoom.",
            )
            composeRule.waitUntil(timeoutMillis = 5_000) {
                runCatching { previewNode.assertIsDisplayed() }.isSuccess
            }
            val capture = previewNode.captureToImage()
            val fittedHeight = capture.width / 2f
            val fittedTop = (capture.height - fittedHeight) / 2f
            val pixel = capture.toPixelMap()[
                capture.width / 2,
                (fittedTop + fittedHeight * 0.4f).toInt(),
            ]

            assertTrue("red=${pixel.red} green=${pixel.green} blue=${pixel.blue}",
                pixel.red < 0.01f && pixel.green < 0.01f && pixel.blue < 0.01f)
            assertEquals(1f, pixel.alpha, 0.001f)
        } finally {
            preview.delete()
        }
    }

    @Test
    fun redactionDragRemainsMappedAfterZoomAndPan() {
        val preview = syntheticPreview()
        val id = RedactionRegionId("zoom-pan-drag")
        val initial = RedactionBounds(0.2f, 0.3f, 0.5f, 0.5f)
        val region = RedactionRegion(
            id = id,
            coordinateSpace = RedactionCoordinateSpace.SOURCE_NORMALIZED,
            bounds = initial,
            target = RedactionTarget(RedactionTargetKind.DOCUMENT_PAGE, "synthetic-page"),
            confirmed = true,
            order = 0,
        )
        var state by mutableStateOf(
            ShareSafeCopyWorkflowState.Configure(
                title = "Synthetic",
                pageCount = 1,
                sourceKind = ShareSafeSourceKind.DOCUMENT,
                recipe = ShareSafeRecipe.EMAIL_DOCUMENT,
                watermark = ShareSafeWatermarkConfig(),
                targetSize = ShareSafeTargetSizeConfig(),
                step = ShareSafeConfigureStep.REDACT,
                selectedPagePreviewUri = Uri.fromFile(preview),
                redactions = listOf(region),
                selectedRedactionId = id,
            ),
        )
        try {
            composeRule.setContent {
                PageHarborTheme {
                    screen(
                        state = state,
                        onSelectRedaction = { selected ->
                            state = state.copy(selectedRedactionId = selected)
                        },
                        onUpdateRedaction = { bounds ->
                            state = state.copy(
                                redactions = listOf(region.copy(bounds = bounds)),
                            )
                        },
                    )
                }
            }

            val previewNode = composeRule.onNodeWithContentDescription(
                "Redaction preview. Drag a blackout region to move it; pinch to zoom.",
            )
            composeRule.waitUntil(timeoutMillis = 5_000) {
                runCatching { previewNode.assertIsDisplayed() }.isSuccess
            }
            previewNode.performTouchInput {
                pinch(
                    start0 = Offset(width * 0.3f, height * 0.7f),
                    start1 = Offset(width * 0.7f, height * 0.7f),
                    end0 = Offset(width * 0.1f, height * 0.7f),
                    end1 = Offset(width * 0.9f, height * 0.7f),
                    durationMillis = 500,
                )
            }
            previewNode.performTouchInput {
                swipe(
                    start = Offset(width * 0.8f, height * 0.8f),
                    end = Offset(width * 0.9f, height * 0.8f),
                    durationMillis = 300,
                )
            }
            previewNode.performTouchInput {
                swipe(
                    start = Offset(width * 0.3f, height * 0.3f),
                    end = Offset(width * 0.4f, height * 0.3f),
                    durationMillis = 500,
                )
            }
            composeRule.runOnIdle {
                val moved = state.redactions.single().bounds
                assertTrue(moved.left > initial.left)
                assertEquals(initial.width, moved.width, 0.0001f)
                assertEquals(initial.height, moved.height, 0.0001f)
            }
        } finally {
            preview.delete()
        }
    }

    @Test
    fun verySmallDocumentPinchFromViewportIncreasesFitRelativeScale() {
        val bitmap = Bitmap.createBitmap(240, 24, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED)
        }
        try {
            composeRule.setContent {
                PageHarborTheme {
                    Box(Modifier.size(360.dp, 600.dp)) {
                        ZoomableDocumentPreview(
                            bitmap = bitmap.asImageBitmap(),
                            resetKey = "very-small-page",
                            description = "Very small page preview",
                            modifier = Modifier.fillMaxSize().testTag("very-small-preview"),
                        )
                    }
                }
            }

            val preview = composeRule.onNodeWithTag("very-small-preview")
            val before = preview.captureToImage().countRedPixels()
            preview.performTouchInput {
                pinch(
                    start0 = Offset(width * 0.47f, height * 0.47f),
                    start1 = Offset(width * 0.53f, height * 0.53f),
                    end0 = Offset(width * 0.1f, height * 0.1f),
                    end1 = Offset(width * 0.9f, height * 0.9f),
                    durationMillis = 600,
                )
            }
            composeRule.waitForIdle()
            val after = preview.captureToImage().countRedPixels()
            preview.performTouchInput {
                swipe(
                    start = Offset(width * 0.5f, height * 0.5f),
                    end = Offset(width * 0.5f, height * 0.85f),
                    durationMillis = 500,
                )
            }
            composeRule.waitForIdle()
            val pannedCapture = preview.captureToImage()
            val pannedCenter = pannedCapture.redPixelCentroidY()

            assertTrue("expected fit-relative pinch growth: before=$before after=$after", after > before * 3f)
            assertEquals(
                "small fitted axis must remain centered after pan",
                pannedCapture.height / 2f,
                pannedCenter,
                pannedCapture.height * 0.05f,
            )
        } finally {
            bitmap.recycle()
        }
    }

    @Composable
    private fun screen(
        state: ShareSafeCopyWorkflowState,
        onRecipe: (ShareSafeRecipe) -> Unit = {},
        onPurpose: (String) -> Unit = {},
        onTargetBytes: (Long) -> Unit = {},
        onNext: () -> Unit = {},
        onSelectRedaction: (RedactionRegionId) -> Unit = {},
        onUpdateRedaction: (RedactionBounds) -> Unit = {},
    ) = ShareSafeCopyScreen(
        state = state,
        onBack = {},
        onRecipe = onRecipe,
        onPage = {},
        onAddRedaction = {},
        onSelectRedaction = onSelectRedaction,
        onUpdateRedaction = onUpdateRedaction,
        onDeleteRedaction = {},
        onWatermarkEnabled = {},
        onPurpose = onPurpose,
        onDateEnabled = {},
        onDate = {},
        onPlacement = {},
        onOpacity = {},
        onTargetEnabled = {},
        onTargetBytes = onTargetBytes,
        onNext = onNext,
        onPrepare = {},
        onSave = {},
        onShare = {},
        onRetry = {},
    )

    private fun syntheticPreview(): File {
        val file = File(context.cacheDir, "share-safe-redaction-${System.nanoTime()}.jpg")
        val bitmap = Bitmap.createBitmap(1_000, 500, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(Color.WHITE)
            FileOutputStream(file).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output))
            }
            file
        } finally {
            bitmap.recycle()
        }
    }

    private fun syntheticPreparedPdf(): ShareSafePreparedPdf {
        val directory = File(context.cacheDir, "share-safe-prepared-ui-${System.nanoTime()}")
            .apply { mkdirs() }
        val file = File(directory, "prepared.pdf")
        val pdf = PdfDocument()
        try {
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(240, 320, 1).create())
            page.canvas.drawColor(Color.WHITE)
            pdf.finishPage(page)
            FileOutputStream(file).use { output -> pdf.writeTo(output) }
        } finally {
            pdf.close()
        }
        val placeholderHash = "0".repeat(64)
        val policy = RedactedPdfVerificationPolicy(
            pages = listOf(
                RedactedPdfPageExpectation(
                    widthPoints = 240f,
                    heightPoints = 320f,
                    expectedImages = listOf(
                        RedactedPdfImageExpectation(
                            leftPoints = 0f,
                            bottomPoints = 0f,
                            widthPoints = 240f,
                            heightPoints = 320f,
                            pixelWidth = 240,
                            pixelHeight = 320,
                            encodedSha256 = placeholderHash,
                        ),
                    ),
                ),
            ),
        )
        val evidence = RedactedPdfVerificationEvidence(
            sha256 = requireNotNull(org.synapseworks.pageharbor.document.sharesafe.sha256(file)),
            bytes = file.length(),
            pageCount = 1,
            imageObjectCount = 1,
            searchableTextPageCount = 0,
            policySha256 = placeholderHash,
        )
        return ShareSafePreparedPdf(
            artifact = ShareSafeAttestedArtifact(
                file = file,
                ownedDirectory = directory,
                policy = policy,
                evidence = evidence,
                sourceGeneration = 1L,
                stage = ShareSafeArtifactStage.FINAL,
            ),
            recipe = ShareSafeRecipe.ARCHIVE_DOCUMENT,
            watermark = ShareSafeWatermarkConfig(),
            targetSize = ShareSafeTargetSizeConfig(),
            searchableTextStatus = ShareSafeSearchableTextStatus.NOT_PRESENT,
            performance = ShareSafePerformance(0L, 0L, 0L, 0L, file.length(), 0L),
        )
    }

    private fun androidx.compose.ui.graphics.ImageBitmap.countRedPixels(): Int {
        val pixels = toPixelMap()
        var count = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = pixels[x, y]
                if (pixel.red > 0.8f && pixel.green < 0.2f && pixel.blue < 0.2f) count++
            }
        }
        return count
    }

    private fun androidx.compose.ui.graphics.ImageBitmap.redPixelCentroidY(): Float {
        val pixels = toPixelMap()
        var count = 0
        var totalY = 0L
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = pixels[x, y]
                if (pixel.red > 0.8f && pixel.green < 0.2f && pixel.blue < 0.2f) {
                    count++
                    totalY += y
                }
            }
        }
        check(count > 0)
        return totalY.toFloat() / count
    }
}
