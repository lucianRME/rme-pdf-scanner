package org.synapseworks.pageharbor.ui.ocr

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import org.synapseworks.pageharbor.ocr.OcrLanguagePreferenceStore
import org.synapseworks.pageharbor.ocr.OcrModelFailure
import org.synapseworks.pageharbor.ocr.OcrModelState
import org.synapseworks.pageharbor.ocr.OcrOperationSelection
import org.synapseworks.pageharbor.ocr.OcrScript
import org.synapseworks.pageharbor.ocr.OcrScriptRecommendation
import org.synapseworks.pageharbor.ocr.OcrScriptSelection
import org.synapseworks.pageharbor.ocr.SharedPreferencesOcrLanguagePreferenceStore
import org.synapseworks.pageharbor.ui.theme.PageHarborTheme

@RunWith(AndroidJUnit4::class)
class OcrLanguageScreensTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val recommendation = OcrScriptRecommendation(
        OcrScript.JAPANESE,
        OcrScriptRecommendation.Basis.LOCALE_LANGUAGE,
    )

    @After
    fun clearPreference() {
        InstrumentationRegistry.getInstrumentation().targetContext
            .deleteSharedPreferences("rme_ocr_language_v1")
    }

    @Test
    fun automaticRecommendationAndAllSupportedScriptsAreVisible() {
        composeRule.setContent {
            PageHarborTheme {
                OcrLanguageSettingsScreen(
                    selection = OcrScriptSelection.Automatic,
                    recommendation = recommendation,
                    modelStates = modelStates(OcrModelState.NotInstalled),
                    onSelectionChanged = {},
                    onInstall = {},
                    onRefresh = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("Recommended: Japanese").assertIsDisplayed()
        listOf("Latin", "Chinese", "Japanese", "Korean", "Devanagari").forEach { label ->
            composeRule.onNodeWithText(label).assertExists()
        }
        composeRule.onNodeWithText("Built in · available offline").assertIsDisplayed()
    }

    @Test
    fun optionalMissingModelRequiresExplicitDownloadAndNeverOffersFallback() {
        var installRequested = false
        composeRule.setContent {
            PageHarborTheme {
                OcrOperationDialog(
                    defaultSelection = OcrScriptSelection.Explicit(OcrScript.JAPANESE),
                    recommendation = recommendation,
                    modelStates = modelStates(OcrModelState.NotInstalled),
                    onInstall = { installRequested = it == OcrScript.JAPANESE },
                    onRefresh = {},
                    onDismiss = {},
                    onConfirm = { _, _ -> error("Recognition must not start") },
                )
            }
        }

        composeRule.onNodeWithText("Download OCR support").assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(true, installRequested) }
        composeRule.onNodeWithText("Recognize text").assertDoesNotExist()
    }

    @Test
    fun installProgressDisablesDownloadAndInstalledStateEnablesRecognition() {
        var state by mutableStateOf<OcrModelState>(OcrModelState.Downloading())
        composeRule.setContent {
            PageHarborTheme {
                OcrOperationDialog(
                    defaultSelection = OcrScriptSelection.Explicit(OcrScript.JAPANESE),
                    recommendation = recommendation,
                    modelStates = modelStates(state),
                    onInstall = {},
                    onRefresh = {},
                    onDismiss = {},
                    onConfirm = { _, _ -> },
                )
            }
        }
        composeRule.onNodeWithText("Downloading…").assertIsDisplayed()
        composeRule.onNodeWithText("Download OCR support").assertIsNotEnabled()

        composeRule.runOnIdle { state = OcrModelState.Installed }
        composeRule.onNodeWithText("Recognize text").assertIsEnabled()
    }

    @Test
    fun failedInstallOffersRetry() {
        composeRule.setContent {
            PageHarborTheme {
                OcrOperationDialog(
                    defaultSelection = OcrScriptSelection.Explicit(OcrScript.JAPANESE),
                    recommendation = recommendation,
                    modelStates = modelStates(
                        OcrModelState.RetryableFailure(OcrModelFailure.INSTALLATION_FAILED),
                    ),
                    onInstall = {}, onRefresh = {}, onDismiss = {}, onConfirm = { _, _ -> },
                )
            }
        }

        composeRule.onNodeWithText("Couldn’t download OCR support").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").assertIsEnabled()
    }

    @Test
    fun perOperationOverrideDoesNotRequestDefaultChangeUnlessChecked() {
        var confirmed: Pair<OcrOperationSelection, Boolean>? = null
        composeRule.setContent {
            PageHarborTheme {
                OcrOperationDialog(
                    defaultSelection = OcrScriptSelection.Automatic,
                    recommendation = recommendation,
                    modelStates = modelStates(OcrModelState.Installed),
                    onInstall = {}, onRefresh = {}, onDismiss = {},
                    onConfirm = { selection, useAsDefault ->
                        confirmed = selection to useAsDefault
                    },
                )
            }
        }

        composeRule.onNodeWithText("Korean").performClick()
        composeRule.onNodeWithText("Recognize text").performClick()
        composeRule.runOnIdle {
            assertEquals(OcrOperationSelection.Override(OcrScript.KOREAN), confirmed?.first)
            assertFalse(confirmed?.second ?: true)
        }
    }

    @Test
    fun preferenceSurvivesStoreRecreationAndDefaultsToAutomatic() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val first: OcrLanguagePreferenceStore = SharedPreferencesOcrLanguagePreferenceStore(context)
        assertEquals(OcrScriptSelection.Automatic, first.read())
        first.write(OcrScriptSelection.Explicit(OcrScript.DEVANAGARI))

        val recreated: OcrLanguagePreferenceStore = SharedPreferencesOcrLanguagePreferenceStore(context)
        assertEquals(OcrScriptSelection.Explicit(OcrScript.DEVANAGARI), recreated.read())
    }

    @Test
    fun captureRepresentativeLanguageAndModelStates() {
        var preview by mutableStateOf(Preview.Settings)
        var fontScale by mutableStateOf(1f)
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                PageHarborTheme {
                    when (preview) {
                        Preview.Settings -> OcrLanguageSettingsScreen(
                            selection = OcrScriptSelection.Automatic,
                            recommendation = recommendation,
                            modelStates = modelStates(OcrModelState.NotInstalled),
                            onSelectionChanged = {}, onInstall = {}, onRefresh = {}, onBack = {},
                        )
                        Preview.Missing -> previewDialog(OcrModelState.NotInstalled)
                        Preview.Installing -> previewDialog(OcrModelState.Installing)
                        Preview.Installed -> previewDialog(OcrModelState.Installed)
                        Preview.Failed -> previewDialog(
                            OcrModelState.RetryableFailure(OcrModelFailure.INSTALLATION_FAILED),
                        )
                    }
                }
            }
        }

        capture("settings-automatic")
        composeRule.runOnIdle { preview = Preview.Missing }
        capture("optional-missing")
        composeRule.runOnIdle { preview = Preview.Installing }
        capture("optional-installing")
        composeRule.runOnIdle { preview = Preview.Installed }
        capture("optional-installed")
        composeRule.runOnIdle { preview = Preview.Failed }
        capture("optional-failed-retry")
        composeRule.runOnIdle { preview = Preview.Settings; fontScale = 2f }
        capture("settings-200-percent")
    }

    @Composable
    private fun previewDialog(state: OcrModelState) {
        OcrOperationDialog(
            defaultSelection = OcrScriptSelection.Explicit(OcrScript.JAPANESE),
            recommendation = recommendation,
            modelStates = modelStates(state),
            onInstall = {}, onRefresh = {}, onDismiss = {}, onConfirm = { _, _ -> },
        )
    }

    private fun capture(name: String) {
        composeRule.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        SystemClock.sleep(750L)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val width = context.resources.displayMetrics.widthPixels
        val height = context.resources.displayMetrics.heightPixels
        val directory = requireNotNull(context.getExternalFilesDir("phase3b-screenshots"))
        val file = File(directory, "$name-${width}x$height.png")
        FileOutputStream(file).use { output ->
            check(
                requireNotNull(
                    InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot(),
                ).compress(
                    Bitmap.CompressFormat.PNG,
                    100,
                    output,
                ),
            )
        }
    }

    private enum class Preview { Settings, Missing, Installing, Installed, Failed }

    private fun modelStates(optionalState: OcrModelState): Map<OcrScript, OcrModelState> =
        OcrScript.entries.associateWith { script ->
            if (script == OcrScript.LATIN) OcrModelState.Bundled else optionalState
        }
}
