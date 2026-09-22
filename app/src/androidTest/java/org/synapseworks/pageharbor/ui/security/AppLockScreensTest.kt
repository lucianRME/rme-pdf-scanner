package org.synapseworks.pageharbor.ui.security

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.synapseworks.pageharbor.security.AppLockAuthenticatedChangeResult
import org.synapseworks.pageharbor.security.AppLockBiometricAvailability
import org.synapseworks.pageharbor.security.AppLockConfig
import org.synapseworks.pageharbor.security.AppLockPhase
import org.synapseworks.pageharbor.security.AppLockSetupResult
import org.synapseworks.pageharbor.security.AppLockState
import org.synapseworks.pageharbor.security.AppLockUnlockResult
import org.synapseworks.pageharbor.security.AutoLockTimeout
import org.synapseworks.pageharbor.ui.theme.PageHarborTheme

class AppLockScreensTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun lockedStateReplacesProtectedContentUntilUnlock() {
        val state = mutableStateOf(lockedState())

        composeRule.setContent {
            if (state.value.shouldComposeProtectedContent) {
                androidx.compose.material3.Text(PROTECTED_SENTINEL)
            } else {
                AppLockScreen(
                    state = state.value,
                    biometricAvailability = AppLockBiometricAvailability.UNSUPPORTED,
                    onUnlockWithPin = { AppLockUnlockResult.REJECTED },
                    onUnlockWithBiometric = {},
                    onExit = {},
                )
            }
        }

        composeRule.onNodeWithText("RME is locked").assertIsDisplayed()
        composeRule.onAllNodesWithText(PROTECTED_SENTINEL).assertCountEquals(0)

        composeRule.runOnIdle { state.value = unlockedState() }

        composeRule.onNodeWithText(PROTECTED_SENTINEL).assertIsDisplayed()
        composeRule.onAllNodesWithText("RME is locked").assertCountEquals(0)
    }

    @Test
    fun lockPinIsPasswordMaskedFiltersNonDigitsAndSubmitsAtSixDigits() {
        var submittedPin: String? = null
        var callbackBuffer: CharArray? = null
        composeRule.setContent {
            AppLockScreen(
                state = lockedState(),
                biometricAvailability = AppLockBiometricAvailability.UNSUPPORTED,
                onUnlockWithPin = { pin ->
                    submittedPin = pin.concatToString()
                    callbackBuffer = pin
                    AppLockUnlockResult.ACCEPTED
                },
                onUnlockWithBiometric = {},
                onExit = {},
            )
        }

        val pinField = composeRule.onNode(hasSetTextAction())
        pinField.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        composeRule.onNodeWithText("Unlock").assertIsNotEnabled()

        pinField.performTextInput("12a34")
        composeRule.onNodeWithText("Unlock").assertIsNotEnabled()
        pinField.performTextInput("b56")
        composeRule.onNodeWithText("Unlock").assertIsEnabled().performClick()

        composeRule.runOnIdle {
            assertEquals("123456", submittedPin)
            assertTrue(requireNotNull(callbackBuffer).all { it == '\u0000' })
        }
        pinField.assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.EditableText,
                AnnotatedString(""),
            ),
        )
    }

    @Test
    fun setupDialogMasksBothPinsRequiresSixDigitsAndClearsCallbackBuffers() {
        var submittedPin: String? = null
        var submittedConfirmation: String? = null
        var submittedTimeout: AutoLockTimeout? = null
        var pinBuffer: CharArray? = null
        var confirmationBuffer: CharArray? = null
        composeRule.setContent {
            PageHarborTheme {
                AppLockSettingsScreen(
                    state = disabledState(),
                    biometricAvailability = AppLockBiometricAvailability.UNSUPPORTED,
                    onBack = {},
                    onSetup = { pin, confirmation, timeout ->
                        submittedPin = pin.concatToString()
                        submittedConfirmation = confirmation.concatToString()
                        submittedTimeout = timeout
                        pinBuffer = pin
                        confirmationBuffer = confirmation
                        AppLockSetupResult.Success(unlockedState())
                    },
                    onReplacePin = { _, _ -> unavailableChange() },
                    onTimeoutChange = { unavailableChange() },
                    onEnableBiometric = { unavailableChange() },
                    onDisableBiometric = { unavailableChange() },
                    onDisable = { unavailableChange() },
                    onLockNow = {},
                )
            }
        }

        composeRule.onNodeWithText("Set up app lock").performClick()
        val fields = composeRule.onAllNodes(hasSetTextAction())
        fields.assertCountEquals(2)
        fields[0].assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        fields[1].assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))

        fields[0].performTextInput("12345")
        fields[1].performTextInput("12345")
        composeRule.onNodeWithText("Save").assertIsNotEnabled()
        fields[0].performTextInput("6")
        fields[1].performTextInput("6")
        composeRule.onNodeWithText("Save").assertIsEnabled().performClick()

        composeRule.runOnIdle {
            assertEquals("123456", submittedPin)
            assertEquals("123456", submittedConfirmation)
            assertEquals(AutoLockTimeout.ONE_MINUTE, submittedTimeout)
            assertTrue(requireNotNull(pinBuffer).all { it == '\u0000' })
            assertTrue(requireNotNull(confirmationBuffer).all { it == '\u0000' })
        }
        composeRule.onAllNodesWithText("Create a PIN").assertCountEquals(0)
        composeRule.onNodeWithText("App lock settings updated.").assertIsDisplayed()
    }

    @Test
    fun settingsShowAccessAtRestAndRecoveryDisclosure() {
        composeRule.setContent {
            PageHarborTheme {
                AppLockSettingsScreen(
                    state = disabledState(),
                    biometricAvailability = AppLockBiometricAvailability.UNSUPPORTED,
                    onBack = {},
                    onSetup = { _, _, _ -> AppLockSetupResult.StorageUnavailable },
                    onReplacePin = { _, _ -> unavailableChange() },
                    onTimeoutChange = { unavailableChange() },
                    onEnableBiometric = { unavailableChange() },
                    onDisableBiometric = { unavailableChange() },
                    onDisable = { unavailableChange() },
                    onLockNow = {},
                )
            }
        }

        composeRule.onNodeWithText("App lock protects access through the RME app interface.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("It does not encrypt the local RME library at rest.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("RME cannot recover your PIN. Keep a verified backup.")
            .assertIsDisplayed()
    }

    @Test
    fun compactTwoHundredPercentLockedAndSettingsActionsRemainReachable() {
        val showSettings = mutableStateOf(false)
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                Box(modifier = Modifier.size(width = 320.dp, height = 480.dp)) {
                    if (showSettings.value) {
                        PageHarborTheme {
                            AppLockSettingsScreen(
                                state = disabledState(),
                                biometricAvailability = AppLockBiometricAvailability.UNSUPPORTED,
                                onBack = {},
                                onSetup = { _, _, _ -> AppLockSetupResult.StorageUnavailable },
                                onReplacePin = { _, _ -> unavailableChange() },
                                onTimeoutChange = { unavailableChange() },
                                onEnableBiometric = { unavailableChange() },
                                onDisableBiometric = { unavailableChange() },
                                onDisable = { unavailableChange() },
                                onLockNow = {},
                            )
                        }
                    } else {
                        AppLockScreen(
                            state = lockedState(),
                            biometricAvailability = AppLockBiometricAvailability.UNSUPPORTED,
                            onUnlockWithPin = { AppLockUnlockResult.REJECTED },
                            onUnlockWithBiometric = {},
                            onExit = {},
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithText("Unlock").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Close RME").performScrollTo().assertIsDisplayed()

        composeRule.runOnIdle { showSettings.value = true }

        composeRule.onNodeWithText("It does not encrypt the local RME library at rest.")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Set up app lock").performScrollTo().assertIsDisplayed()
    }

    private fun lockedState(): AppLockState = AppLockState(
        phase = AppLockPhase.LOCKED,
        config = AppLockConfig(
            enabled = true,
            autoLockTimeout = AutoLockTimeout.ONE_MINUTE,
            biometricEnabled = false,
        ),
    )

    private fun unlockedState(): AppLockState = AppLockState(
        phase = AppLockPhase.UNLOCKED,
        config = AppLockConfig(
            enabled = true,
            autoLockTimeout = AutoLockTimeout.ONE_MINUTE,
            biometricEnabled = false,
        ),
        hasAuthenticatedSession = true,
    )

    private fun disabledState(): AppLockState = AppLockState(
        phase = AppLockPhase.DISABLED,
        config = AppLockConfig(),
    )

    private fun unavailableChange(): AppLockAuthenticatedChangeResult =
        AppLockAuthenticatedChangeResult.STORAGE_UNAVAILABLE

    private companion object {
        const val PROTECTED_SENTINEL = "PRIVATE DOCUMENT TITLE SENTINEL"
    }
}
