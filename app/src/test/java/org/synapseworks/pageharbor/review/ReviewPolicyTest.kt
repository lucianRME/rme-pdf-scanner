package org.synapseworks.pageharbor.review

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewPolicyTest {
    private val now = 200L * DAY_MILLIS
    private val eligibleMinorState = ReviewMilestoneState(
        firstUseTimestampMillis = now - ReviewPolicy.MINIMUM_AGE_MILLIS,
        sessionCount = 2,
        successfulDocumentSaveCount = 3,
    )

    @Test
    fun oneMajorOrThreeMinorSuccessesMeetTheMilestoneGate() {
        val policy = ReviewPolicy()

        assertTrue(policy.isEligible(eligibleMinorState, "1.5.0", now))
        assertFalse(
            policy.isEligible(
                eligibleMinorState.copy(successfulDocumentSaveCount = 2),
                "1.5.0",
                now,
            ),
        )
        assertTrue(
            policy.isEligible(
                eligibleMinorState.copy(
                    successfulDocumentSaveCount = 0,
                    successfulMajorMilestoneCount = 1,
                ),
                "1.5.0",
                now,
            ),
        )
    }

    @Test
    fun requiresTwoSessionsAndFortyEightHoursSinceFirstUse() {
        val policy = ReviewPolicy()

        assertFalse(policy.isEligible(eligibleMinorState.copy(sessionCount = 1), "1.5.0", now))
        assertFalse(
            policy.isEligible(
                eligibleMinorState.copy(
                    firstUseTimestampMillis = now - ReviewPolicy.MINIMUM_AGE_MILLIS + 1L,
                ),
                "1.5.0",
                now,
            ),
        )
        assertFalse(policy.isEligible(eligibleMinorState.copy(firstUseTimestampMillis = 0L), "1.5.0", now))
        assertTrue(policy.isEligible(eligibleMinorState, "1.5.0", now))
    }

    @Test
    fun automaticAttemptRequiresResumedUnlockedAppWithNoActiveFlow() {
        val policy = ReviewPolicy()
        val allowed = AutomaticReviewEligibility.ALREADY_GUARDED

        assertTrue(policy.isEligible(eligibleMinorState, "1.5.0", now, allowed))
        assertFalse(
            policy.isEligible(
                eligibleMinorState,
                "1.5.0",
                now,
                allowed.copy(isAppResumed = false),
            ),
        )
        assertFalse(
            policy.isEligible(
                eligibleMinorState,
                "1.5.0",
                now,
                allowed.copy(isAppUnlocked = false),
            ),
        )
        assertFalse(
            policy.isEligible(
                eligibleMinorState,
                "1.5.0",
                now,
                allowed.copy(hasActiveUserFlow = true),
            ),
        )
    }

    @Test
    fun currentVersionAttemptIsBlockedAndCrossVersionAttemptWaitsNinetyDays() {
        val policy = ReviewPolicy()
        val previousAttempt = eligibleMinorState.copy(
            lastReviewAttemptVersion = "1.4.0",
            lastAutomaticReviewAttemptTimestampMillis = now -
                ReviewPolicy.CROSS_VERSION_COOLDOWN_MILLIS,
        )

        assertFalse(policy.isEligible(previousAttempt, "1.4.0", now))
        assertTrue(policy.isEligible(previousAttempt, "1.5.0", now))
        assertFalse(
            policy.isEligible(
                previousAttempt.copy(lastAutomaticReviewAttemptTimestampMillis = now -
                    ReviewPolicy.CROSS_VERSION_COOLDOWN_MILLIS + 1L),
                "1.5.0",
                now,
            ),
        )
        assertFalse(
            policy.isEligible(
                previousAttempt.copy(lastAutomaticReviewAttemptTimestampMillis = 0L),
                "1.5.0",
                now,
            ),
        )
    }

    private companion object {
        const val DAY_MILLIS = 24L * 60L * 60L * 1_000L
    }
}
