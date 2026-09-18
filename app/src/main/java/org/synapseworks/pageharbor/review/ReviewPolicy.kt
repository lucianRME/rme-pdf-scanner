package org.synapseworks.pageharbor.review

data class ReviewMilestoneState(
    val firstUseTimestampMillis: Long = 0L,
    val sessionCount: Int = 0,
    val successfulDocumentSaveCount: Int = 0,
    val lastReviewAttemptVersion: String? = null,
)

class ReviewPolicy(
    private val minimumSuccessfulSaves: Int = 3,
    private val minimumSessions: Int = 2,
    private val minimumAgeMillis: Long = MINIMUM_AGE_MILLIS,
) {
    fun isEligible(
        state: ReviewMilestoneState,
        currentVersion: String,
        nowMillis: Long,
    ): Boolean {
        val ageMillis = nowMillis - state.firstUseTimestampMillis
        return state.firstUseTimestampMillis > 0L &&
            ageMillis >= minimumAgeMillis &&
            state.successfulDocumentSaveCount >= minimumSuccessfulSaves &&
            state.sessionCount >= minimumSessions &&
            state.lastReviewAttemptVersion != currentVersion
    }

    companion object {
        const val MINIMUM_AGE_MILLIS = 24L * 60L * 60L * 1_000L
    }
}
