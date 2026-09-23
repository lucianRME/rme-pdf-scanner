package org.synapseworks.pageharbor.ui.security

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.synapseworks.pageharbor.security.AppLockAuthenticatedChangeResult
import org.synapseworks.pageharbor.security.AppLockAuthenticationAvailability
import org.synapseworks.pageharbor.security.AppLockConfig
import org.synapseworks.pageharbor.security.AppLockPhase
import org.synapseworks.pageharbor.security.AppLockSetupResult
import org.synapseworks.pageharbor.security.AppLockState
import org.synapseworks.pageharbor.ui.theme.PageHarborTheme

class AppLockScreensTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun lockedStateReplacesProtectedContentUntilDeviceAuthentication() {
        val state = mutableStateOf(lockedState())
        composeRule.setContent {
            if (state.value.shouldComposeProtectedContent) {
                androidx.compose.material3.Text(PROTECTED_SENTINEL)
            } else {
                AppLockScreen(
                    state = state.value,
                    authenticationAvailability = AppLockAuthenticationAvailability.AVAILABLE,
                    onUnlock = { state.value = unlockedState() },
                    onOpenDeviceSecuritySettings = {},
                    onExit = {},
                )
            }
        }

        composeRule.onNodeWithText("RME is locked").assertIsDisplayed()
        composeRule.onNodeWithText("Unlock with your device").assertIsEnabled().performClick()
        composeRule.onNodeWithText(PROTECTED_SENTINEL).assertIsDisplayed()
        composeRule.onAllNodesWithText("RME is locked").assertCountEquals(0)
    }

    @Test
    fun noSecureDeviceLockExplainsRequiredSystemSetup() {
        composeRule.setContent {
            AppLockScreen(
                state = lockedState(),
                authenticationAvailability = AppLockAuthenticationAvailability.NO_SECURE_DEVICE_LOCK,
                onUnlock = {},
                onOpenDeviceSecuritySettings = {},
                onExit = {},
            )
        }
        composeRule.onNodeWithText("Set up a device PIN, pattern or password in Android settings before enabling RME app lock.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Open device security settings").assertIsDisplayed()
    }

    @Test
    fun settingsUseDeviceAuthenticationAndDoNotOfferAnAppPin() {
        composeRule.setContent {
            PageHarborTheme {
                AppLockSettingsScreen(
                    state = disabledState(),
                    authenticationAvailability = AppLockAuthenticationAvailability.AVAILABLE,
                    onBack = {},
                    onSetup = { AppLockSetupResult.Success(unlockedState()) },
                    onTimeoutChange = { AppLockAuthenticatedChangeResult.APPLIED },
                    onDisable = { AppLockAuthenticatedChangeResult.APPLIED },
                    onLockNow = {},
                    onOpenDeviceSecuritySettings = {},
                )
            }
        }
        composeRule.onNodeWithText("RME uses your device authentication: fingerprint, face, PIN, pattern or password. Android manages credentials and biometric templates.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Lock RME").assertIsEnabled().performClick()
        composeRule.onNodeWithText("App lock settings updated.").assertIsDisplayed()
        composeRule.onAllNodesWithText("Create a PIN").assertCountEquals(0)
        composeRule.onAllNodesWithText("Change PIN").assertCountEquals(0)
    }

    @Test
    fun largeTextKeepsSystemAuthenticationActionsReachable() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                Box(modifier = Modifier.size(width = 320.dp, height = 480.dp)) {
                    PageHarborTheme {
                        AppLockSettingsScreen(
                            state = disabledState(),
                            authenticationAvailability = AppLockAuthenticationAvailability.NO_SECURE_DEVICE_LOCK,
                            onBack = {},
                            onSetup = { AppLockSetupResult.DeviceAuthenticationUnavailable },
                            onTimeoutChange = { AppLockAuthenticatedChangeResult.STORAGE_UNAVAILABLE },
                            onDisable = { AppLockAuthenticatedChangeResult.STORAGE_UNAVAILABLE },
                            onLockNow = {},
                            onOpenDeviceSecuritySettings = {},
                        )
                    }
                }
            }
        }
        composeRule.onNodeWithText("Open device security settings").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Lock RME").performScrollTo().assertIsDisplayed()
    }

    private fun lockedState() = AppLockState(
        phase = AppLockPhase.LOCKED,
        config = AppLockConfig(enabled = true),
    )

    private fun unlockedState() = AppLockState(
        phase = AppLockPhase.UNLOCKED,
        config = AppLockConfig(enabled = true),
        hasAuthenticatedSession = true,
    )

    private fun disabledState() = AppLockState(
        phase = AppLockPhase.DISABLED,
        config = AppLockConfig(),
    )

    private companion object {
        const val PROTECTED_SENTINEL = "PRIVATE DOCUMENT TITLE SENTINEL"
    }
}
