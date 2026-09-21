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
    fun failuresCancellationsAndUnverifiedResultsNeverAdvanceMilestones() {
        val store = eligibleBaseStore()
        val coordinator = ReviewMilestoneCoordinator(store, nowMillis = { NOW })

        listOf(
            ReviewMilestoneOutcome.FAILURE,
            ReviewMilestoneOutcome.CANCELLED,
            ReviewMilestoneOutcome.UNVERIFIED,
        ).forEach { outcome ->
            assertFalse(
                coordinator.recordMilestone(
                    ReviewMilestone.BACKUP_VERIFIED,
                    outcome,
                    "1.5.0",
                ),
            )
        }

        assertEquals(0, store.read().successfulMajorMilestoneCount)
        assertEquals(0, store.read().successfulMinorMilestoneCount)
    }

    @Test
    fun oneVerifiedMajorSuccessBecomesEligibleAndSameKindIsDeduplicated() {
        val store = eligibleBaseStore()
        val coordinator = ReviewMilestoneCoordinator(store, nowMillis = { NOW })

        assertTrue(
            coordinator.recordMilestone(
                ReviewMilestone.MIGRATION_COMPLETED,
                ReviewMilestoneOutcome.SUCCESS,
                "1.5.0",
            ),
        )
        assertFalse(
            coordinator.recordMilestone(
                ReviewMilestone.MIGRATION_COMPLETED,
                ReviewMilestoneOutcome.SUCCESS,
                "1.5.0",
            ),
        )
        assertEquals(1, store.read().successfulMajorMilestoneCount)
    }

    @Test
    fun sameMinorKindCountsOncePerSessionButDistinctKindsCountImmediately() {
        val store = eligibleBaseStore()
        val firstSession = ReviewMilestoneCoordinator(store, nowMillis = { NOW })

        assertFalse(firstSession.recordDocumentSave(successful = true, currentVersion = "1.5.0"))
        assertFalse(firstSession.recordDocumentSave(successful = true, currentVersion = "1.5.0"))
        assertFalse(
            firstSession.recordMilestone(
                ReviewMilestone.PDF_EXPORTED,
                ReviewMilestoneOutcome.SUCCESS,
                "1.5.0",
            ),
        )
        assertTrue(
            firstSession.recordMilestone(
                ReviewMilestone.SEARCHABLE_PDF_EXPORTED,
                ReviewMilestoneOutcome.SUCCESS,
                "1.5.0",
            ),
        )
        assertEquals(3, store.read().successfulMinorMilestoneCount)

        ReviewMilestoneCoordinator(store, nowMillis = { NOW + 1L }).apply {
            recordAppSession()
            recordDocumentSave(successful = true, currentVersion = "1.5.0")
        }
        assertEquals(4, store.read().successfulMinorMilestoneCount)
    }

    @Test
    fun unsafeContextStillRecordsSuccessButDoesNotOfferAutomaticAttempt() {
        val store = eligibleBaseStore(
            ReviewMilestoneState(
                firstUseTimestampMillis = NOW - ReviewPolicy.MINIMUM_AGE_MILLIS,
                sessionCount = 2,
                successfulDocumentSaveCount = 2,
            ),
        )
        val coordinator = ReviewMilestoneCoordinator(store, nowMillis = { NOW })
        val locked = AutomaticReviewEligibility.ALREADY_GUARDED.copy(isAppUnlocked = false)

        assertFalse(
            coordinator.recordMilestone(
                ReviewMilestone.PDF_EXPORTED,
                ReviewMilestoneOutcome.SUCCESS,
                "1.5.0",
                locked,
            ),
        )
        assertEquals(3, store.read().successfulMinorMilestoneCount)
        assertTrue(
            coordinator.isEligible(
                "1.5.0",
                AutomaticReviewEligibility.ALREADY_GUARDED,
            ),
        )
    }

    @Test
    fun automaticAttemptStoresVersionAndTimeBeforeLaunch() {
        val store = eligibleBaseStore()
        val coordinator = ReviewMilestoneCoordinator(store, nowMillis = { NOW })
        val events = mutableListOf<String>()
        val runner = ReviewAttemptRunner(
            markAttempt = {
                coordinator.markAutomaticReviewAttempt(it)
                events += "marked:$it"
            },
            launch = {
                events += "launch"
                error("Play unavailable")
            },
        )

        runner.run("1.5.0")

        assertEquals(listOf("marked:1.5.0", "launch"), events)
        assertEquals("1.5.0", store.read().lastReviewAttemptVersion)
        assertEquals(NOW, store.read().lastAutomaticReviewAttemptTimestampMillis)
    }

    @Test
    fun stateFailureIsSilentAndPreventsAnUntrackedLaunch() {
        var launchCount = 0
        val runner = ReviewAttemptRunner(
            markAttempt = { error("Preferences unavailable") },
            launch = { launchCount += 1 },
        )

        runner.run("1.5.0")

        assertEquals(0, launchCount)
    }

    private fun eligibleBaseStore(
        initial: ReviewMilestoneState = ReviewMilestoneState(
            firstUseTimestampMillis = NOW - ReviewPolicy.MINIMUM_AGE_MILLIS,
            sessionCount = 2,
        ),
    ) = InMemoryReviewStateStore(initial)

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

    private companion object {
        const val NOW = 200L * 24L * 60L * 60L * 1_000L
    }
}
