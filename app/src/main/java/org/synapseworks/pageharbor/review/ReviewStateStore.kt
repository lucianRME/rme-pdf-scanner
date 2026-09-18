package org.synapseworks.pageharbor.review

import android.content.Context
import androidx.core.content.edit

interface ReviewStateStore {
    fun read(): ReviewMilestoneState

    fun update(transform: (ReviewMilestoneState) -> ReviewMilestoneState): ReviewMilestoneState
}

class SharedPreferencesReviewStateStore(context: Context) : ReviewStateStore {
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
            if (updated.lastReviewAttemptVersion == null) {
                remove(KEY_LAST_REVIEW_ATTEMPT_VERSION)
            } else {
                putString(KEY_LAST_REVIEW_ATTEMPT_VERSION, updated.lastReviewAttemptVersion)
            }
        }
        updated
    }

    private fun readLocked(): ReviewMilestoneState = ReviewMilestoneState(
        firstUseTimestampMillis = preferences.getLong(KEY_FIRST_USE_TIMESTAMP, 0L),
        sessionCount = preferences.getInt(KEY_SESSION_COUNT, 0).coerceAtLeast(0),
        successfulDocumentSaveCount = preferences.getInt(KEY_SUCCESSFUL_SAVE_COUNT, 0)
            .coerceAtLeast(0),
        lastReviewAttemptVersion = preferences.getString(
            KEY_LAST_REVIEW_ATTEMPT_VERSION,
            null,
        ),
    )

    private companion object {
        const val PREFERENCES_NAME = "rme_review_milestones"
        const val KEY_FIRST_USE_TIMESTAMP = "first_use_timestamp"
        const val KEY_SESSION_COUNT = "session_count"
        const val KEY_SUCCESSFUL_SAVE_COUNT = "successful_document_save_count"
        const val KEY_LAST_REVIEW_ATTEMPT_VERSION = "last_review_attempt_version"
    }
}
