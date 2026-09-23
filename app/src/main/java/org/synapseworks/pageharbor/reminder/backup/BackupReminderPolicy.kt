package org.synapseworks.pageharbor.reminder.backup

import kotlin.math.max

/** Aggregate library facts only; this model must never contain titles, paths, URIs or content. */
data class BackupReminderLibrarySnapshot(
    val documentCount: Int,
    val pageCount: Int,
    val revision: Long,
)

data class BackupReminderState(
    val firstSavedContentTimestampMillis: Long = 0L,
    val lastReminderShownTimestampMillis: Long = 0L,
    val suppressUntilTimestampMillis: Long = 0L,
    val lastVerifiedBackupTimestampMillis: Long = 0L,
    val libraryRevisionAtLastReminder: Long = NO_REVISION,
    val libraryRevisionAtLastVerifiedBackup: Long = NO_REVISION,
    val lastObservedLibraryRevision: Long = NO_REVISION,
    val meaningfulMutationsSinceBaseline: Int = 0,
) {
    companion object {
        const val NO_REVISION = -1L
    }
}

class BackupReminderPolicy(
    private val firstReminderAgeMillis: Long = FIRST_REMINDER_AGE_MILLIS,
    private val repeatReminderIntervalMillis: Long = REPEAT_REMINDER_INTERVAL_MILLIS,
    private val meaningfulMutationThreshold: Int = MEANINGFUL_MUTATION_THRESHOLD,
) {
    fun isEligible(
        state: BackupReminderState,
        library: BackupReminderLibrarySnapshot,
        nowMillis: Long,
        alreadyShownThisSession: Boolean,
    ): Boolean {
        if (alreadyShownThisSession || nowMillis < state.suppressUntilTimestampMillis) return false
        if (library.documentCount <= 0 || library.pageCount <= 0) return false
        if (state.firstSavedContentTimestampMillis <= 0L) return false
        if (nowMillis - state.firstSavedContentTimestampMillis < firstReminderAgeMillis) return false

        val hasReminderHistory = state.lastReminderShownTimestampMillis > 0L
        val hasVerifiedBackup = state.lastVerifiedBackupTimestampMillis > 0L
        if (!hasReminderHistory && !hasVerifiedBackup) {
            return library.documentCount >= FIRST_REMINDER_DOCUMENT_COUNT ||
                library.pageCount >= FIRST_REMINDER_PAGE_COUNT
        }

        val backupIsLatestBaseline = state.lastVerifiedBackupTimestampMillis >=
            state.lastReminderShownTimestampMillis
        val baselineTimestamp = max(
            state.lastReminderShownTimestampMillis,
            state.lastVerifiedBackupTimestampMillis,
        )
        val baselineRevision = if (backupIsLatestBaseline) {
            state.libraryRevisionAtLastVerifiedBackup
        } else {
            state.libraryRevisionAtLastReminder
        }
        val libraryChanged = baselineRevision != BackupReminderState.NO_REVISION &&
            library.revision != baselineRevision
        if (!libraryChanged) return false

        return nowMillis - baselineTimestamp >= repeatReminderIntervalMillis ||
            state.meaningfulMutationsSinceBaseline >= meaningfulMutationThreshold
    }

    companion object {
        const val FIRST_REMINDER_DOCUMENT_COUNT = 3
        const val FIRST_REMINDER_PAGE_COUNT = 10
        const val MEANINGFUL_MUTATION_THRESHOLD = 10
        const val FIRST_REMINDER_AGE_MILLIS = 7L * 24L * 60L * 60L * 1_000L
        const val REPEAT_REMINDER_INTERVAL_MILLIS = 30L * 24L * 60L * 60L * 1_000L
        const val NOT_NOW_SUPPRESSION_MILLIS = 14L * 24L * 60L * 60L * 1_000L
    }
}
