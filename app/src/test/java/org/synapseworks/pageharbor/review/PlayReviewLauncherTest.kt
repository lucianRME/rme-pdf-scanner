package org.synapseworks.pageharbor.review

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayReviewLauncherTest {
    @Test
    fun launchRequiresActivityAndCurrentFullSafetyGate() {
        assertTrue(reviewLaunchIsSafe(isActivityResumedAndUsable = true) { true })
        assertFalse(reviewLaunchIsSafe(isActivityResumedAndUsable = false) { true })
        assertFalse(reviewLaunchIsSafe(isActivityResumedAndUsable = true) { false })
    }

    @Test
    fun safetyCheckFailurePreventsLaunch() {
        assertFalse(
            reviewLaunchIsSafe(isActivityResumedAndUsable = true) {
                error("eligibility unavailable")
            },
        )
    }
}
