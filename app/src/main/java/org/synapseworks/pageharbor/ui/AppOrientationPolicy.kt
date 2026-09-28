package org.synapseworks.pageharbor.ui

/** Activity orientation behavior for RME-owned surfaces; external scanner activities are unaffected. */
internal enum class AppOrientationPreference {
    PORTRAIT,
    SYSTEM,
}

internal object AppOrientationPolicy {
    const val LARGE_SCREEN_MIN_SMALLEST_WIDTH_DP = 600

    fun resolve(
        smallestScreenWidthDp: Int,
        screen: PageHarborScreen,
        portabilityVisible: Boolean,
        ocrReviewVisible: Boolean,
        appLocked: Boolean,
    ): AppOrientationPreference {
        if (smallestScreenWidthDp >= LARGE_SCREEN_MIN_SMALLEST_WIDTH_DP) {
            return AppOrientationPreference.SYSTEM
        }
        if (appLocked || portabilityVisible) return AppOrientationPreference.PORTRAIT
        if (ocrReviewVisible) return AppOrientationPreference.SYSTEM

        return when (screen) {
            PageHarborScreen.Home -> AppOrientationPreference.PORTRAIT
            PageHarborScreen.ScanResult,
            PageHarborScreen.OcrResult,
            -> AppOrientationPreference.SYSTEM
        }
    }
}
