package org.synapseworks.pageharbor.review

class ReviewMilestoneCoordinator(
    private val stateStore: ReviewStateStore,
    private val policy: ReviewPolicy = ReviewPolicy(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private var sessionRecorded = false

    fun recordAppSession() {
        if (sessionRecorded) return
        sessionRecorded = true
        val now = nowMillis()
        stateStore.update { state ->
            state.copy(
                firstUseTimestampMillis = state.firstUseTimestampMillis.takeIf { it > 0L } ?: now,
                sessionCount = state.sessionCount.saturatedIncrement(),
            )
        }
    }

    /**
     * Records one verified successful milestone and reports whether an automatic review attempt is
     * now appropriate. Failed, cancelled and unverified work never changes milestone state.
     *
     * Repeated instances of the same minor milestone in one app session count once. Major milestone
     * kinds count once for the lifetime of this local installation. No document identifier is needed
     * or retained to provide that repeat suppression.
     */
    fun recordMilestone(
        milestone: ReviewMilestone,
        outcome: ReviewMilestoneOutcome,
        currentVersion: String,
        eligibility: AutomaticReviewEligibility = AutomaticReviewEligibility.ALREADY_GUARDED,
    ): Boolean {
        if (outcome != ReviewMilestoneOutcome.SUCCESS) return false

        var counted = false
        val updated = stateStore.update { state ->
            when (milestone.significance) {
                ReviewMilestoneSignificance.MAJOR -> {
                    val alreadyCounted = state.countedMajorMilestoneMask and
                        milestone.deduplicationBit != 0
                    if (alreadyCounted) {
                        state
                    } else {
                        counted = true
                        state.copy(
                            successfulMajorMilestoneCount =
                                state.successfulMajorMilestoneCount.saturatedIncrement(),
                            countedMajorMilestoneMask = state.countedMajorMilestoneMask or
                                milestone.deduplicationBit,
                        )
                    }
                }

                ReviewMilestoneSignificance.MINOR -> {
                    val activeSessionMask = if (state.countedMinorMilestoneSession == state.sessionCount) {
                        state.countedMinorMilestoneMask
                    } else {
                        0
                    }
                    val alreadyCounted = activeSessionMask and milestone.deduplicationBit != 0
                    if (alreadyCounted) {
                        state
                    } else {
                        counted = true
                        state.copy(
                            successfulDocumentSaveCount =
                                state.successfulDocumentSaveCount.saturatedIncrement(),
                            countedMinorMilestoneMask = activeSessionMask or
                                milestone.deduplicationBit,
                            countedMinorMilestoneSession = state.sessionCount,
                        )
                    }
                }
            }
        }
        return counted && policy.isEligible(updated, currentVersion, nowMillis(), eligibility)
    }

    fun isEligible(
        currentVersion: String,
        eligibility: AutomaticReviewEligibility,
    ): Boolean = policy.isEligible(stateStore.read(), currentVersion, nowMillis(), eligibility)

    /** Compatibility entry point for the existing Save to RME success callback. */
    fun recordDocumentSave(successful: Boolean, currentVersion: String): Boolean =
        recordMilestone(
            milestone = ReviewMilestone.SAVED_TO_RME,
            outcome = if (successful) {
                ReviewMilestoneOutcome.SUCCESS
            } else {
                ReviewMilestoneOutcome.FAILURE
            },
            currentVersion = currentVersion,
        )

    fun markAutomaticReviewAttempt(currentVersion: String) {
        val now = nowMillis()
        stateStore.update { state ->
            state.copy(
                lastReviewAttemptVersion = currentVersion,
                lastAutomaticReviewAttemptTimestampMillis = now,
            )
        }
    }

    /** Compatibility alias. This remains the automatic in-app review attempt marker. */
    fun markReviewAttempt(currentVersion: String) = markAutomaticReviewAttempt(currentVersion)

    private fun Int.saturatedIncrement(): Int = if (this == Int.MAX_VALUE) this else this + 1
}

class ReviewAttemptRunner(
    private val markAttempt: (String) -> Unit,
    private val launch: () -> Unit,
) {
    fun run(currentVersion: String) {
        if (runCatching { markAttempt(currentVersion) }.isFailure) return
        runCatching(launch)
    }
}
