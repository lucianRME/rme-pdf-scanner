package org.synapseworks.pageharbor.reminder.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupReminderCoordinatorTest {
    @Test
    fun onlySuccessfulNewRevisionsRecordSavedContentAndMutations() {
        var now = 10_000L
        val store = InMemoryBackupReminderStateStore()
        val coordinator = BackupReminderCoordinator(store, nowMillis = { now })
        val revisionOne = snapshot(revision = 1L)

        coordinator.recordLibraryMutation(BackupReminderMutationOutcome.FAILURE, revisionOne)
        coordinator.recordLibraryMutation(BackupReminderMutationOutcome.CANCELLED, revisionOne)
        assertEquals(BackupReminderState(), store.read())

        coordinator.recordLibraryMutation(BackupReminderMutationOutcome.SUCCESS, revisionOne)
        now += 1_000L
        coordinator.recordLibraryMutation(BackupReminderMutationOutcome.SUCCESS, revisionOne)
        coordinator.recordLibraryMutation(
            BackupReminderMutationOutcome.SUCCESS,
            snapshot(revision = 2L),
            meaningful = false,
        )

        assertEquals(10_000L, store.read().firstSavedContentTimestampMillis)
        assertEquals(2L, store.read().lastObservedLibraryRevision)
        assertEquals(1, store.read().meaningfulMutationsSinceBaseline)
    }

    @Test
    fun markingShownIsLimitedToOncePerSessionAndResetsMutationBaseline() {
        val store = InMemoryBackupReminderStateStore(
            eligibleFirstReminderState(mutations = 4),
        )
        val coordinator = BackupReminderCoordinator(store, nowMillis = { NOW })
        val library = snapshot(revision = 7L)

        assertTrue(coordinator.shouldShow(library))
        assertTrue(coordinator.markShown(library))
        assertFalse(coordinator.markShown(library))
        assertFalse(coordinator.shouldShow(library))
        assertEquals(NOW, store.read().lastReminderShownTimestampMillis)
        assertEquals(7L, store.read().libraryRevisionAtLastReminder)
        assertEquals(0, store.read().meaningfulMutationsSinceBaseline)
    }

    @Test
    fun presentationGatePersistsShownStateOnlyAfterUiAcknowledgement() {
        val store = InMemoryBackupReminderStateStore(eligibleFirstReminderState())
        val coordinator = BackupReminderCoordinator(store, nowMillis = { NOW })
        val gate = BackupReminderPresentationGate(coordinator)
        val library = snapshot(revision = 7L)

        assertEquals(BackupReminderPresentationDecision.SHOW, gate.evaluate(library))
        assertEquals(0L, store.read().lastReminderShownTimestampMillis)
        assertTrue(gate.markPresented())
        assertEquals(NOW, store.read().lastReminderShownTimestampMillis)
        assertEquals(7L, store.read().libraryRevisionAtLastReminder)
        assertEquals(BackupReminderPresentationDecision.KEEP, gate.evaluate(library))

        gate.dismiss()
        assertEquals(BackupReminderPresentationDecision.HIDE, gate.evaluate(library))
    }

    @Test
    fun unpresentedCandidateCanBeRevokedWithoutCreatingReminderHistory() {
        val store = InMemoryBackupReminderStateStore(eligibleFirstReminderState())
        val coordinator = BackupReminderCoordinator(store, nowMillis = { NOW })
        val gate = BackupReminderPresentationGate(coordinator)
        val library = snapshot(revision = 7L)

        assertEquals(BackupReminderPresentationDecision.SHOW, gate.evaluate(library))
        coordinator.recordVerifiedBackup(library)

        assertEquals(BackupReminderPresentationDecision.HIDE, gate.evaluate(library))
        assertFalse(gate.markPresented())
        assertEquals(0L, store.read().lastReminderShownTimestampMillis)
    }

    @Test
    fun refreshOrderRejectsOlderRequestsAndRevisionRollback() {
        val order = BackupReminderRefreshOrder()

        assertTrue(order.shouldStart(requestId = 2L))
        assertTrue(order.shouldApply(requestId = 2L, revision = 4L))
        assertFalse(order.shouldStart(requestId = 1L))
        assertFalse(order.shouldApply(requestId = 1L, revision = 5L))
        assertTrue(order.shouldStart(requestId = 3L))
        assertFalse(order.shouldApply(requestId = 3L, revision = 3L))
        assertTrue(order.shouldStart(requestId = 4L))
        assertTrue(order.shouldApply(requestId = 4L, revision = 5L))
        assertTrue(order.shouldStart(requestId = 6L))
        assertFalse(order.shouldStart(requestId = 5L))
    }

    @Test
    fun notNowSuppressesAndRequiresAChangedLibraryBeforeLaterReminder() {
        var now = NOW
        val store = InMemoryBackupReminderStateStore(eligibleFirstReminderState())
        val coordinator = BackupReminderCoordinator(store, nowMillis = { now })
        val library = snapshot()

        coordinator.markNotNow(library)

        assertEquals(NOW + BackupReminderPolicy.NOT_NOW_SUPPRESSION_MILLIS, store.read().suppressUntilTimestampMillis)
        assertFalse(coordinator.shouldShow(library))
        now += BackupReminderPolicy.NOT_NOW_SUPPRESSION_MILLIS
        assertFalse(BackupReminderCoordinator(store, nowMillis = { now }).shouldShow(library))
        now = NOW + BackupReminderPolicy.REPEAT_REMINDER_INTERVAL_MILLIS
        assertTrue(
            BackupReminderCoordinator(store, nowMillis = { now }).shouldShow(
                library.copy(revision = 2L),
            ),
        )
    }

    @Test
    fun verifiedBackupResetsMutationsAndUsesItsRevisionAsProtectionBaseline() {
        val store = InMemoryBackupReminderStateStore(eligibleFirstReminderState(mutations = 9))
        val coordinator = BackupReminderCoordinator(store, nowMillis = { NOW })

        coordinator.recordVerifiedBackup(snapshot(revision = 12L))

        assertEquals(NOW, store.read().lastVerifiedBackupTimestampMillis)
        assertEquals(12L, store.read().libraryRevisionAtLastVerifiedBackup)
        assertEquals(0, store.read().meaningfulMutationsSinceBaseline)
        assertFalse(coordinator.shouldShow(snapshot(revision = 12L)))
    }

    private fun eligibleFirstReminderState(mutations: Int = 0) = BackupReminderState(
        firstSavedContentTimestampMillis = NOW - BackupReminderPolicy.FIRST_REMINDER_AGE_MILLIS,
        meaningfulMutationsSinceBaseline = mutations,
    )

    private fun snapshot(
        documents: Int = 3,
        pages: Int = 10,
        revision: Long = 1L,
    ) = BackupReminderLibrarySnapshot(documents, pages, revision)

    private class InMemoryBackupReminderStateStore(
        initial: BackupReminderState = BackupReminderState(),
    ) : BackupReminderStateStore {
        private var state = initial

        override fun read(): BackupReminderState = state

        override fun update(
            transform: (BackupReminderState) -> BackupReminderState,
        ): BackupReminderState {
            state = transform(state)
            return state
        }
    }

    private companion object {
        const val NOW = 100L * 24L * 60L * 60L * 1_000L
    }
}
