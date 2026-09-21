package org.synapseworks.pageharbor.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinRetryThrottleTest {
    private val policy = PinRetryThrottlePolicy()

    @Test
    fun firstFourFailuresHaveNoDelayThenDelayProgressesToFiveMinuteCap() {
        val expected = listOf(0L, 0L, 0L, 0L, 30_000L, 60_000L, 120_000L, 240_000L, 300_000L)
        var state = PinRetryThrottleState()

        expected.forEachIndexed { index, delay ->
            val result = policy.recordFailure(state, nowWallTimeMillis = 1_000L + index)
            assertEquals(delay, result.retryAfterMillis)
            state = result.state
        }

        repeat(100) {
            state = policy.recordFailure(state, 2_000L + it).state
        }
        assertEquals(300_000L, policy.delayForFailureCount(state.failedAttemptCount))
    }

    @Test
    fun persistedDeadlineSurvivesProcessRestart() {
        val fifthFailure = policy.recordFailure(
            PinRetryThrottleState(failedAttemptCount = 4),
            nowWallTimeMillis = 10_000L,
        )

        val restarted = policy.evaluate(fifthFailure.state, nowWallTimeMillis = 20_000L)
        assertFalse(restarted.canAttempt)
        assertEquals(20_000L, restarted.retryAfterMillis)

        val expired = policy.evaluate(restarted.state, nowWallTimeMillis = 40_000L)
        assertTrue(expired.canAttempt)
    }

    @Test
    fun wallClockRollbackImposesConservativeCappedDelay() {
        val throttled = PinRetryThrottleState(
            failedAttemptCount = 7,
            retryNotBeforeWallTimeMillis = 120_000L,
            lastObservedWallTimeMillis = 100_000L,
        )

        val rollback = policy.evaluate(throttled, nowWallTimeMillis = 50_000L)

        assertFalse(rollback.canAttempt)
        assertEquals(PinRetryThrottlePolicy.DEFAULT_MAXIMUM_DELAY_MILLIS, rollback.retryAfterMillis)
        assertEquals(350_000L, rollback.state.retryNotBeforeWallTimeMillis)
        assertTrue(policy.evaluate(rollback.state, 350_000L).canAttempt)
    }

    @Test
    fun wallClockRollbackBeforeThrottleThresholdDoesNotInventFailures() {
        val state = PinRetryThrottleState(
            failedAttemptCount = 2,
            lastObservedWallTimeMillis = 100_000L,
        )

        val result = policy.evaluate(state, nowWallTimeMillis = 50_000L)

        assertTrue(result.canAttempt)
        assertEquals(2, result.state.failedAttemptCount)
    }

    @Test
    fun successfulPinClearsFailuresAndDeadline() {
        assertEquals(PinRetryThrottleState(), policy.recordSuccess())
    }
}
