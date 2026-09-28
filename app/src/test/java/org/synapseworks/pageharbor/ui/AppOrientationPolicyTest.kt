package org.synapseworks.pageharbor.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AppOrientationPolicyTest {
    @Test
    fun compactNavigationAndUtilitySurfacesPreferPortrait() {
        val utilityCases = listOf(
            policy(screen = PageHarborScreen.Home),
            policy(screen = PageHarborScreen.Home, portabilityVisible = true),
            policy(screen = PageHarborScreen.Home, appLocked = true),
            policy(screen = PageHarborScreen.ScanResult, portabilityVisible = true),
        )

        utilityCases.forEach { preference ->
            assertEquals(AppOrientationPreference.PORTRAIT, preference)
        }
    }

    @Test
    fun compactDocumentAndOcrSurfacesRemainSystemManaged() {
        assertEquals(
            AppOrientationPreference.SYSTEM,
            policy(screen = PageHarborScreen.ScanResult),
        )
        assertEquals(
            AppOrientationPreference.SYSTEM,
            policy(screen = PageHarborScreen.OcrResult),
        )
        assertEquals(
            AppOrientationPreference.SYSTEM,
            policy(screen = PageHarborScreen.Home, ocrReviewVisible = true),
        )
    }

    @Test
    fun sevenAndTenInchClassesRemainSystemManagedForEverySurface() {
        listOf(600, 800).forEach { smallestWidth ->
            PageHarborScreen.entries.forEach { screen ->
                assertEquals(
                    AppOrientationPreference.SYSTEM,
                    policy(
                        smallestScreenWidthDp = smallestWidth,
                        screen = screen,
                        portabilityVisible = true,
                        appLocked = true,
                    ),
                )
            }
        }
    }

    private fun policy(
        smallestScreenWidthDp: Int = 411,
        screen: PageHarborScreen,
        portabilityVisible: Boolean = false,
        ocrReviewVisible: Boolean = false,
        appLocked: Boolean = false,
    ) = AppOrientationPolicy.resolve(
        smallestScreenWidthDp = smallestScreenWidthDp,
        screen = screen,
        portabilityVisible = portabilityVisible,
        ocrReviewVisible = ocrReviewVisible,
        appLocked = appLocked,
    )
}
