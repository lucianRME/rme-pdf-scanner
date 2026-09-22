package org.synapseworks.pageharbor.reminder.backup

enum class BackupReminderMutationOutcome {
    SUCCESS,
    FAILURE,
    CANCELLED,
}

/**
 * Session-scoped coordinator. Construct one per app session so [shownThisSession] enforces the
 * maximum of one reminder in that session without persisting a user or document identifier.
 */
class BackupReminderCoordinator(
    private val stateStore: BackupReminderStateStore,
    private val policy: BackupReminderPolicy = BackupReminderPolicy(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private var shownThisSession = false

    fun recordLibraryMutation(
        outcome: BackupReminderMutationOutcome,
        library: BackupReminderLibrarySnapshot,
        meaningful: Boolean = true,
    ) {
        if (outcome != BackupReminderMutationOutcome.SUCCESS) return
        val now = nowMillis()
        stateStore.update { state ->
            val containsSavedContent = library.documentCount > 0 && library.pageCount > 0
            val isNewRevision = library.revision != state.lastObservedLibraryRevision
            state.copy(
                firstSavedContentTimestampMillis = if (
                    containsSavedContent && state.firstSavedContentTimestampMillis <= 0L
                ) {
                    now
                } else {
                    state.firstSavedContentTimestampMillis
                },
                lastObservedLibraryRevision = library.revision,
                meaningfulMutationsSinceBaseline = if (meaningful && isNewRevision) {
                    state.meaningfulMutationsSinceBaseline.saturatedIncrement()
                } else {
                    state.meaningfulMutationsSinceBaseline
                },
            )
        }
    }

    fun shouldShow(library: BackupReminderLibrarySnapshot): Boolean = policy.isEligible(
        state = stateStore.read(),
        library = library,
        nowMillis = nowMillis(),
        alreadyShownThisSession = shownThisSession,
    )

    /** Returns false when another reminder was already recorded in this app session. */
    fun markShown(library: BackupReminderLibrarySnapshot): Boolean {
        if (shownThisSession) return false
        shownThisSession = true
        val now = nowMillis()
        stateStore.update { state ->
            state.copy(
                lastReminderShownTimestampMillis = now,
                libraryRevisionAtLastReminder = library.revision,
                lastObservedLibraryRevision = library.revision,
                meaningfulMutationsSinceBaseline = 0,
            )
        }
        return true
    }

    fun markNotNow(library: BackupReminderLibrarySnapshot) {
        shownThisSession = true
        val now = nowMillis()
        val suppressUntil = now.saturatedAdd(BackupReminderPolicy.NOT_NOW_SUPPRESSION_MILLIS)
        stateStore.update { state ->
            state.copy(
                lastReminderShownTimestampMillis = now,
                suppressUntilTimestampMillis = maxOf(
                    state.suppressUntilTimestampMillis,
                    suppressUntil,
                ),
                libraryRevisionAtLastReminder = library.revision,
                lastObservedLibraryRevision = library.revision,
                meaningfulMutationsSinceBaseline = 0,
            )
        }
    }

    /** Call only after the new archive has passed the backup verification step. */
    fun recordVerifiedBackup(library: BackupReminderLibrarySnapshot) {
        val now = nowMillis()
        stateStore.update { state ->
            state.copy(
                lastVerifiedBackupTimestampMillis = now,
                libraryRevisionAtLastVerifiedBackup = library.revision,
                lastObservedLibraryRevision = library.revision,
                meaningfulMutationsSinceBaseline = 0,
            )
        }
    }

    private fun Int.saturatedIncrement(): Int = if (this == Int.MAX_VALUE) this else this + 1

    private fun Long.saturatedAdd(increment: Long): Long =
        if (this > Long.MAX_VALUE - increment) Long.MAX_VALUE else this + increment
}

internal enum class BackupReminderPresentationDecision {
    SHOW,
    KEEP,
    HIDE,
}

/**
 * Keeps eligibility evaluation separate from recording an actual presentation. A reminder only
 * becomes the persisted repeat baseline after the UI acknowledges that it entered composition.
 */
internal class BackupReminderPresentationGate(
    private val coordinator: BackupReminderCoordinator,
) {
    private var pendingSnapshot: BackupReminderLibrarySnapshot? = null
    private var presented = false

    fun evaluate(snapshot: BackupReminderLibrarySnapshot): BackupReminderPresentationDecision {
        if (presented) return BackupReminderPresentationDecision.KEEP
        return if (coordinator.shouldShow(snapshot)) {
            pendingSnapshot = snapshot
            BackupReminderPresentationDecision.SHOW
        } else {
            pendingSnapshot = null
            BackupReminderPresentationDecision.HIDE
        }
    }

    fun markPresented(): Boolean {
        if (presented) return true
        val snapshot = pendingSnapshot ?: return false
        if (!coordinator.markShown(snapshot)) {
            pendingSnapshot = null
            return false
        }
        pendingSnapshot = null
        presented = true
        return true
    }

    fun dismiss() {
        pendingSnapshot = null
        presented = false
    }
}
