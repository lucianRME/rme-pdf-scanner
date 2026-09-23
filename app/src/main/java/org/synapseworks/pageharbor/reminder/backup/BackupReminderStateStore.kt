package org.synapseworks.pageharbor.reminder.backup

import android.content.Context
import androidx.core.content.edit

interface BackupReminderStateStore {
    fun read(): BackupReminderState

    fun update(transform: (BackupReminderState) -> BackupReminderState): BackupReminderState
}

/** Local aggregate state only. No document name, path, URI, page data or content is stored. */
class SharedPreferencesBackupReminderStateStore(context: Context) : BackupReminderStateStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun read(): BackupReminderState = synchronized(preferences) {
        readLocked()
    }

    override fun update(
        transform: (BackupReminderState) -> BackupReminderState,
    ): BackupReminderState = synchronized(preferences) {
        val updated = transform(readLocked())
        preferences.edit {
            putLong(KEY_FIRST_SAVED_CONTENT_AT, updated.firstSavedContentTimestampMillis)
            putLong(KEY_LAST_REMINDER_AT, updated.lastReminderShownTimestampMillis)
            putLong(KEY_SUPPRESS_UNTIL, updated.suppressUntilTimestampMillis)
            putLong(KEY_LAST_VERIFIED_BACKUP_AT, updated.lastVerifiedBackupTimestampMillis)
            putLong(KEY_REVISION_AT_LAST_REMINDER, updated.libraryRevisionAtLastReminder)
            putLong(KEY_REVISION_AT_LAST_BACKUP, updated.libraryRevisionAtLastVerifiedBackup)
            putLong(KEY_LAST_OBSERVED_REVISION, updated.lastObservedLibraryRevision)
            putInt(KEY_MEANINGFUL_MUTATIONS, updated.meaningfulMutationsSinceBaseline)
        }
        updated
    }

    private fun readLocked(): BackupReminderState = BackupReminderState(
        firstSavedContentTimestampMillis = preferences.getLong(KEY_FIRST_SAVED_CONTENT_AT, 0L),
        lastReminderShownTimestampMillis = preferences.getLong(KEY_LAST_REMINDER_AT, 0L),
        suppressUntilTimestampMillis = preferences.getLong(KEY_SUPPRESS_UNTIL, 0L),
        lastVerifiedBackupTimestampMillis = preferences.getLong(KEY_LAST_VERIFIED_BACKUP_AT, 0L),
        libraryRevisionAtLastReminder = preferences.getLong(
            KEY_REVISION_AT_LAST_REMINDER,
            BackupReminderState.NO_REVISION,
        ),
        libraryRevisionAtLastVerifiedBackup = preferences.getLong(
            KEY_REVISION_AT_LAST_BACKUP,
            BackupReminderState.NO_REVISION,
        ),
        lastObservedLibraryRevision = preferences.getLong(
            KEY_LAST_OBSERVED_REVISION,
            BackupReminderState.NO_REVISION,
        ),
        meaningfulMutationsSinceBaseline = preferences.getInt(KEY_MEANINGFUL_MUTATIONS, 0)
            .coerceAtLeast(0),
    )

    private companion object {
        const val PREFERENCES_NAME = "rme_backup_reminder"
        const val KEY_FIRST_SAVED_CONTENT_AT = "first_saved_content_at"
        const val KEY_LAST_REMINDER_AT = "last_reminder_at"
        const val KEY_SUPPRESS_UNTIL = "suppress_until"
        const val KEY_LAST_VERIFIED_BACKUP_AT = "last_verified_backup_at"
        const val KEY_REVISION_AT_LAST_REMINDER = "revision_at_last_reminder"
        const val KEY_REVISION_AT_LAST_BACKUP = "revision_at_last_backup"
        const val KEY_LAST_OBSERVED_REVISION = "last_observed_revision"
        const val KEY_MEANINGFUL_MUTATIONS = "meaningful_mutations"
    }
}
