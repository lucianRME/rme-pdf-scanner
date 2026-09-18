package org.synapseworks.pageharbor.review

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewPolicyTest {
    private val now = ReviewPolicy.MINIMUM_AGE_MILLIS + 1_000L
    private val eligibleState = ReviewMilestoneState(
        firstUseTimestampMillis = 1_000L,
        sessionCount = 2,
        successfulDocumentSaveCount = 3,
    )

    @Test
    fun allThresholdsMustBeMet() {
        val policy = ReviewPolicy()

        assertTrue(policy.isEligible(eligibleState, "1.4.0", now))
        (0..1).forEach { sessions ->
            assertFalse(policy.isEligible(eligibleState.copy(sessionCount = sessions), "1.4.0", now))
        }
        (0..2).forEach { saves ->
            assertFalse(
                policy.isEligible(
                    eligibleState.copy(successfulDocumentSaveCount = saves),
                    "1.4.0",
                    now,
                ),
            )
        }
        assertTrue(
            policy.isEligible(
                eligibleState.copy(sessionCount = 3, successfulDocumentSaveCount = 4),
                "1.4.0",
                now,
            ),
        )
        assertFalse(
            policy.isEligible(
                eligibleState.copy(firstUseTimestampMillis = now - ReviewPolicy.MINIMUM_AGE_MILLIS + 1L),
                "1.4.0",
                now,
            ),
        )
        assertFalse(policy.isEligible(eligibleState.copy(firstUseTimestampMillis = 0L), "1.4.0", now))
        assertTrue(
            policy.isEligible(
                eligibleState.copy(firstUseTimestampMillis = now - ReviewPolicy.MINIMUM_AGE_MILLIS),
                "1.4.0",
                now,
            ),
        )
    }

    @Test
    fun currentVersionAttemptSuppressesEligibilityOnlyForThatVersion() {
        val policy = ReviewPolicy()
        val attempted = eligibleState.copy(lastReviewAttemptVersion = "1.4.0")

        assertFalse(policy.isEligible(attempted, "1.4.0", now))
        assertTrue(policy.isEligible(attempted, "1.5.0", now))
    }
}
