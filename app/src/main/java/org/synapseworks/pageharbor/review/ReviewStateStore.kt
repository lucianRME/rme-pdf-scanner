package org.synapseworks.pageharbor.review

import android.content.Context
import androidx.core.content.edit

interface ReviewStateStore {
    fun read(): ReviewMilestoneState

    fun update(transform: (ReviewMilestoneState) -> ReviewMilestoneState): ReviewMilestoneState
}

class SharedPreferencesReviewStateStore(
    context: Context,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : ReviewStateStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun read(): ReviewMilestoneState = synchronized(preferences) {
        readLocked()
    }

    override fun update(
        transform: (ReviewMilestoneState) -> ReviewMilestoneState,
    ): ReviewMilestoneState = synchronized(preferences) {
        val updated = transform(readLocked())
        preferences.edit {
            putLong(KEY_FIRST_USE_TIMESTAMP, updated.firstUseTimestampMillis)
            putInt(KEY_SESSION_COUNT, updated.sessionCount)
            putInt(KEY_SUCCESSFUL_SAVE_COUNT, updated.successfulDocumentSaveCount)
            putInt(KEY_SUCCESSFUL_MAJOR_COUNT, updated.successfulMajorMilestoneCount)
            putInt(KEY_COUNTED_MAJOR_MASK, updated.countedMajorMilestoneMask)
            putInt(KEY_COUNTED_MINOR_MASK, updated.countedMinorMilestoneMask)
            putInt(KEY_COUNTED_MINOR_SESSION, updated.countedMinorMilestoneSession)
            if (updated.lastReviewAttemptVersion == null) {
                remove(KEY_LAST_REVIEW_ATTEMPT_VERSION)
            } else {
                putString(KEY_LAST_REVIEW_ATTEMPT_VERSION, updated.lastReviewAttemptVersion)
            }
            putLong(
                KEY_LAST_AUTOMATIC_ATTEMPT_TIMESTAMP,
                updated.lastAutomaticReviewAttemptTimestampMillis,
            )
        }
        updated
    }

    private fun readLocked(): ReviewMilestoneState {
        val lastAttemptVersion = preferences.getString(KEY_LAST_REVIEW_ATTEMPT_VERSION, null)
        val storedAttemptTimestamp = preferences.getLong(KEY_LAST_AUTOMATIC_ATTEMPT_TIMESTAMP, 0L)
        // v1.4 stored only the attempted version. Start a conservative cooldown at the v1.5
        // migration read instead of either prompting immediately or suppressing forever.
        val migratedAttemptTimestamp = if (lastAttemptVersion != null && storedAttemptTimestamp <= 0L) {
            nowMillis()
        } else {
            storedAttemptTimestamp
        }
        return ReviewMilestoneState(
            firstUseTimestampMillis = preferences.getLong(KEY_FIRST_USE_TIMESTAMP, 0L),
            sessionCount = preferences.getInt(KEY_SESSION_COUNT, 0).coerceAtLeast(0),
            successfulDocumentSaveCount = preferences.getInt(KEY_SUCCESSFUL_SAVE_COUNT, 0)
                .coerceAtLeast(0),
            successfulMajorMilestoneCount = preferences.getInt(KEY_SUCCESSFUL_MAJOR_COUNT, 0)
                .coerceAtLeast(0),
            countedMajorMilestoneMask = preferences.getInt(KEY_COUNTED_MAJOR_MASK, 0)
                .coerceAtLeast(0),
            countedMinorMilestoneMask = preferences.getInt(KEY_COUNTED_MINOR_MASK, 0)
                .coerceAtLeast(0),
            countedMinorMilestoneSession = preferences.getInt(KEY_COUNTED_MINOR_SESSION, -1),
            lastReviewAttemptVersion = lastAttemptVersion,
            lastAutomaticReviewAttemptTimestampMillis = migratedAttemptTimestamp,
        )
    }

    private companion object {
        const val PREFERENCES_NAME = "rme_review_milestones"
        const val KEY_FIRST_USE_TIMESTAMP = "first_use_timestamp"
        const val KEY_SESSION_COUNT = "session_count"
        const val KEY_SUCCESSFUL_SAVE_COUNT = "successful_document_save_count"
        const val KEY_SUCCESSFUL_MAJOR_COUNT = "successful_major_milestone_count"
        const val KEY_COUNTED_MAJOR_MASK = "counted_major_milestone_mask"
        const val KEY_COUNTED_MINOR_MASK = "counted_minor_milestone_mask"
        const val KEY_COUNTED_MINOR_SESSION = "counted_minor_milestone_session"
        const val KEY_LAST_REVIEW_ATTEMPT_VERSION = "last_review_attempt_version"
        const val KEY_LAST_AUTOMATIC_ATTEMPT_TIMESTAMP = "last_automatic_attempt_timestamp"
    }
}
