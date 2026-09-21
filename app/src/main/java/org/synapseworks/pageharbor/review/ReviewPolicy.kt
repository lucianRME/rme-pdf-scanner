package org.synapseworks.pageharbor.review

/**
 * A completed, local milestone which can make an automatic in-app review request appropriate.
 *
 * The enum deliberately contains no document identifiers or content. Major milestones represent a
 * result the user has verified; callers must not report them until verification has completed.
 */
enum class ReviewMilestone(
    val significance: ReviewMilestoneSignificance,
    internal val deduplicationBit: Int,
) {
    MIGRATION_COMPLETED(ReviewMilestoneSignificance.MAJOR, 1 shl 0),
    BACKUP_VERIFIED(ReviewMilestoneSignificance.MAJOR, 1 shl 1),
    RESTORE_COMPLETED(ReviewMilestoneSignificance.MAJOR, 1 shl 2),
    NEW_PHONE_TRANSFER_VERIFIED(ReviewMilestoneSignificance.MAJOR, 1 shl 3),
    SAVED_TO_RME(ReviewMilestoneSignificance.MINOR, 1 shl 0),
    PDF_EXPORTED(ReviewMilestoneSignificance.MINOR, 1 shl 1),
    SEARCHABLE_PDF_EXPORTED(ReviewMilestoneSignificance.MINOR, 1 shl 2),
}

enum class ReviewMilestoneSignificance {
    MAJOR,
    MINOR,
}

/** Only [SUCCESS] may advance an automatic-review milestone. */
enum class ReviewMilestoneOutcome {
    SUCCESS,
    FAILURE,
    CANCELLED,
    UNVERIFIED,
}

/** Foreground safety conditions supplied by the app immediately before an automatic attempt. */
data class AutomaticReviewEligibility(
    val isAppResumed: Boolean,
    val isAppUnlocked: Boolean,
    val hasActiveUserFlow: Boolean,
) {
    val permitsAttempt: Boolean
        get() = isAppResumed && isAppUnlocked && !hasActiveUserFlow

    companion object {
        /** Compatibility value for callers which already apply the same gates before launching. */
        val ALREADY_GUARDED = AutomaticReviewEligibility(
            isAppResumed = true,
            isAppUnlocked = true,
            hasActiveUserFlow = false,
        )
    }
}

data class ReviewMilestoneState(
    val firstUseTimestampMillis: Long = 0L,
    val sessionCount: Int = 0,
    /**
     * Retains the v1.4 field name and preference key for a compatible upgrade. In v1.5 it counts
     * distinct meaningful minor milestones, including existing successful Save to RME events.
     */
    val successfulDocumentSaveCount: Int = 0,
    val successfulMajorMilestoneCount: Int = 0,
    val countedMajorMilestoneMask: Int = 0,
    val countedMinorMilestoneMask: Int = 0,
    val countedMinorMilestoneSession: Int = -1,
    /** Automatic attempts only. The manual Rate RME action does not read or update this state. */
    val lastReviewAttemptVersion: String? = null,
    val lastAutomaticReviewAttemptTimestampMillis: Long = 0L,
) {
    val successfulMinorMilestoneCount: Int
        get() = successfulDocumentSaveCount
}

class ReviewPolicy(
    private val minimumMinorMilestones: Int = 3,
    private val minimumSessions: Int = 2,
    private val minimumAgeMillis: Long = MINIMUM_AGE_MILLIS,
    private val crossVersionCooldownMillis: Long = CROSS_VERSION_COOLDOWN_MILLIS,
) {
    /** Compatibility overload for a caller which performs foreground gates at the launch site. */
    fun isEligible(
        state: ReviewMilestoneState,
        currentVersion: String,
        nowMillis: Long,
    ): Boolean = isEligible(
        state = state,
        currentVersion = currentVersion,
        nowMillis = nowMillis,
        eligibility = AutomaticReviewEligibility.ALREADY_GUARDED,
    )

    fun isEligible(
        state: ReviewMilestoneState,
        currentVersion: String,
        nowMillis: Long,
        eligibility: AutomaticReviewEligibility,
    ): Boolean {
        if (!eligibility.permitsAttempt || state.firstUseTimestampMillis <= 0L) return false
        if (nowMillis - state.firstUseTimestampMillis < minimumAgeMillis) return false
        if (state.sessionCount < minimumSessions) return false

        val hasMeaningfulSuccess = state.successfulMajorMilestoneCount >= 1 ||
            state.successfulMinorMilestoneCount >= minimumMinorMilestones
        if (!hasMeaningfulSuccess) return false

        val previousVersion = state.lastReviewAttemptVersion
        if (previousVersion == currentVersion) return false
        if (previousVersion != null) {
            val lastAttempt = state.lastAutomaticReviewAttemptTimestampMillis
            if (lastAttempt <= 0L || nowMillis - lastAttempt < crossVersionCooldownMillis) {
                return false
            }
        }
        return true
    }

    companion object {
        const val MINIMUM_AGE_MILLIS = 48L * 60L * 60L * 1_000L
        const val CROSS_VERSION_COOLDOWN_MILLIS = 90L * 24L * 60L * 60L * 1_000L
    }
}
