package org.synapseworks.pageharbor.reminder.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupReminderPolicyTest {
    private val now = 100L * DAY_MILLIS
    private val firstReminderState = BackupReminderState(
        firstSavedContentTimestampMillis = now - BackupReminderPolicy.FIRST_REMINDER_AGE_MILLIS,
    )

    @Test
    fun firstReminderNeedsAgeAndEitherDocumentOrPageThreshold() {
        val policy = BackupReminderPolicy()

        assertFalse(policy.isEligible(firstReminderState, snapshot(2, 9), now, false))
        assertTrue(policy.isEligible(firstReminderState, snapshot(3, 3), now, false))
        assertTrue(policy.isEligible(firstReminderState, snapshot(1, 10), now, false))
        assertFalse(
            policy.isEligible(
                firstReminderState.copy(
                    firstSavedContentTimestampMillis = now -
                        BackupReminderPolicy.FIRST_REMINDER_AGE_MILLIS + 1L,
                ),
                snapshot(3, 3),
                now,
                false,
            ),
        )
    }

    @Test
    fun verifiedBackupSuppressesTheFirstReminder() {
        val policy = BackupReminderPolicy()
        val protected = firstReminderState.copy(
            lastVerifiedBackupTimestampMillis = now - DAY_MILLIS,
            libraryRevisionAtLastVerifiedBackup = 4L,
        )

        assertFalse(policy.isEligible(protected, snapshot(4, 12, revision = 4L), now, false))
    }

    @Test
    fun laterReminderRequiresLibraryChangeAndThirtyDaysOrMutationThreshold() {
        val policy = BackupReminderPolicy()
        val baseline = firstReminderState.copy(
            lastReminderShownTimestampMillis = now -
                BackupReminderPolicy.REPEAT_REMINDER_INTERVAL_MILLIS,
            libraryRevisionAtLastReminder = 10L,
        )

        assertFalse(policy.isEligible(baseline, snapshot(3, 10, revision = 10L), now, false))
        assertTrue(policy.isEligible(baseline, snapshot(3, 11, revision = 11L), now, false))
        assertFalse(
            policy.isEligible(
                baseline.copy(lastReminderShownTimestampMillis = now - DAY_MILLIS),
                snapshot(3, 11, revision = 11L),
                now,
                false,
            ),
        )
        assertTrue(
            policy.isEligible(
                baseline.copy(
                    lastReminderShownTimestampMillis = now - DAY_MILLIS,
                    meaningfulMutationsSinceBaseline =
                        BackupReminderPolicy.MEANINGFUL_MUTATION_THRESHOLD,
                ),
                snapshot(3, 11, revision = 11L),
                now,
                false,
            ),
        )
    }

    @Test
    fun verifiedBackupBecomesTheLatestBaselineForLaterChanges() {
        val policy = BackupReminderPolicy()
        val state = firstReminderState.copy(
            lastReminderShownTimestampMillis = now - 60L * DAY_MILLIS,
            libraryRevisionAtLastReminder = 2L,
            lastVerifiedBackupTimestampMillis = now - 5L * DAY_MILLIS,
            libraryRevisionAtLastVerifiedBackup = 8L,
            meaningfulMutationsSinceBaseline = BackupReminderPolicy.MEANINGFUL_MUTATION_THRESHOLD,
        )

        assertFalse(policy.isEligible(state, snapshot(4, 14, revision = 8L), now, false))
        assertTrue(policy.isEligible(state, snapshot(4, 15, revision = 9L), now, false))
    }

    @Test
    fun notNowAndOncePerSessionAlwaysSuppressEligibility() {
        val policy = BackupReminderPolicy()

        assertFalse(
            policy.isEligible(
                firstReminderState.copy(
                    suppressUntilTimestampMillis = now + 1L,
                ),
                snapshot(3, 3),
                now,
                false,
            ),
        )
        assertTrue(
            policy.isEligible(
                firstReminderState.copy(suppressUntilTimestampMillis = now),
                snapshot(3, 3),
                now,
                false,
            ),
        )
        assertFalse(policy.isEligible(firstReminderState, snapshot(3, 3), now, true))
    }

    private fun snapshot(
        documents: Int,
        pages: Int,
        revision: Long = 1L,
    ) = BackupReminderLibrarySnapshot(documents, pages, revision)

    private companion object {
        const val DAY_MILLIS = 24L * 60L * 60L * 1_000L
    }
}
