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

    fun markReviewAttempt(currentVersion: String) {
        coordinator.markReviewAttempt(currentVersion)
    }
}
