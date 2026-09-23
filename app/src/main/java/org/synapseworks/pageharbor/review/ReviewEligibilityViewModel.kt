package org.synapseworks.pageharbor.review

import android.app.Application
import androidx.lifecycle.AndroidViewModel

class ReviewEligibilityViewModel(application: Application) : AndroidViewModel(application) {
    private val coordinator = ReviewMilestoneCoordinator(
        stateStore = SharedPreferencesReviewStateStore(application),
    )

    fun recordAppSession() {
        runCatching { coordinator.recordAppSession() }
    }

    fun recordSuccessfulDocumentSave(currentVersion: String): Boolean =
        coordinator.recordDocumentSave(successful = true, currentVersion = currentVersion)

    fun recordMilestone(
        milestone: ReviewMilestone,
        outcome: ReviewMilestoneOutcome,
        currentVersion: String,
        eligibility: AutomaticReviewEligibility,
    ): Boolean = coordinator.recordMilestone(
        milestone = milestone,
        outcome = outcome,
        currentVersion = currentVersion,
        eligibility = eligibility,
    )

    fun isEligible(
        currentVersion: String,
        eligibility: AutomaticReviewEligibility,
    ): Boolean = coordinator.isEligible(currentVersion, eligibility)

    fun markReviewAttempt(currentVersion: String) {
        coordinator.markReviewAttempt(currentVersion)
    }
}
