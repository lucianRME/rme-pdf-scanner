package org.synapseworks.pageharbor.ui.sharesafe

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.synapseworks.pageharbor.document.sharesafe.ShareSafeConfigureStep
import org.synapseworks.pageharbor.document.sharesafe.ShareSafeCopyWorkflowState
import org.synapseworks.pageharbor.document.sharesafe.ShareSafeRecipe
import org.synapseworks.pageharbor.document.sharesafe.ShareSafeSourceKind
import org.synapseworks.pageharbor.document.sharesafe.ShareSafeTargetSizeConfig
import org.synapseworks.pageharbor.document.sharesafe.ShareSafeWatermarkConfig
import org.synapseworks.pageharbor.ui.theme.PageHarborTheme

class ShareSafeCopyScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

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
        composeRule.onNodeWithText("Next").performScrollTo().assertIsDisplayed().performClick()
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

    @Composable
    private fun screen(
        state: ShareSafeCopyWorkflowState,
        onRecipe: (ShareSafeRecipe) -> Unit = {},
        onPurpose: (String) -> Unit = {},
        onTargetBytes: (Long) -> Unit = {},
        onNext: () -> Unit = {},
    ) = ShareSafeCopyScreen(
        state = state,
        onBack = {},
        onRecipe = onRecipe,
        onPage = {},
        onAddRedaction = {},
        onSelectRedaction = {},
        onUpdateRedaction = {},
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
}
