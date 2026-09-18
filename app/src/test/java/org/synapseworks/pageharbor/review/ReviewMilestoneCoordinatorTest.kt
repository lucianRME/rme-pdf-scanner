package org.synapseworks.pageharbor.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewMilestoneCoordinatorTest {
    @Test
    fun recordsOnlyOneSessionPerCoordinatorAndPreservesFirstUse() {
        val store = InMemoryReviewStateStore()
        val firstSession = ReviewMilestoneCoordinator(store, nowMillis = { 10_000L })

        firstSession.recordAppSession()
        firstSession.recordAppSession()
        ReviewMilestoneCoordinator(store, nowMillis = { 20_000L }).recordAppSession()

        assertEquals(10_000L, store.read().firstUseTimestampMillis)
        assertEquals(2, store.read().sessionCount)
    }

    @Test
    fun onlySuccessfulSavesAdvanceSaveMilestone() {
        val now = ReviewPolicy.MINIMUM_AGE_MILLIS + 10_000L
        val store = InMemoryReviewStateStore(
            ReviewMilestoneState(firstUseTimestampMillis = 1_000L, sessionCount = 2),
        )
        val coordinator = ReviewMilestoneCoordinator(store, nowMillis = { now })

        assertFalse(coordinator.recordDocumentSave(successful = false, currentVersion = "1.4.0"))
        assertEquals(0, store.read().successfulDocumentSaveCount)
        assertFalse(coordinator.recordDocumentSave(successful = true, currentVersion = "1.4.0"))
        assertFalse(coordinator.recordDocumentSave(successful = true, currentVersion = "1.4.0"))
        assertTrue(coordinator.recordDocumentSave(successful = true, currentVersion = "1.4.0"))
        assertEquals(3, store.read().successfulDocumentSaveCount)
    }

    @Test
    fun attemptIsMarkedBeforeLaunchAndLauncherFailureIsSilent() {
        val events = mutableListOf<String>()
        val runner = ReviewAttemptRunner(
            markAttempt = { events += "marked:$it" },
            launch = {
                events += "launch"
                error("Play unavailable")
            },
        )

        runner.run("1.4.0")

        assertEquals(listOf("marked:1.4.0", "launch"), events)
    }

    @Test
    fun stateFailureIsSilentAndPreventsAnUntrackedLaunch() {
        var launchCount = 0
        val runner = ReviewAttemptRunner(
            markAttempt = { error("Preferences unavailable") },
            launch = { launchCount += 1 },
        )

        runner.run("1.4.0")

        assertEquals(0, launchCount)
    }

    @Test
    fun markingAttemptPreventsAnotherAttemptInCurrentVersion() {
        val now = ReviewPolicy.MINIMUM_AGE_MILLIS + 10_000L
        val store = InMemoryReviewStateStore(
            ReviewMilestoneState(
                firstUseTimestampMillis = 1_000L,
                sessionCount = 2,
                successfulDocumentSaveCount = 2,
            ),
        )
        val coordinator = ReviewMilestoneCoordinator(store, nowMillis = { now })

        assertTrue(coordinator.recordDocumentSave(successful = true, currentVersion = "1.4.0"))
        coordinator.markReviewAttempt("1.4.0")
        assertFalse(coordinator.recordDocumentSave(successful = true, currentVersion = "1.4.0"))
    }

    private class InMemoryReviewStateStore(
        initial: ReviewMilestoneState = ReviewMilestoneState(),
    ) : ReviewStateStore {
        private var state = initial

        override fun read(): ReviewMilestoneState = state

        override fun update(
            transform: (ReviewMilestoneState) -> ReviewMilestoneState,
        ): ReviewMilestoneState {
            state = transform(state)
            return state
        }
    }
}
