package org.synapseworks.pageharbor.library

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.coroutineContext

/**
 * Coordinates library mutations with operations that require a stable view of both Room and the
 * private asset store. The gate deliberately does not know about Android schedulers: callers may
 * run backup, restore, migration, or ordinary edits from any coroutine owner.
 *
 * A snapshot operation holds the gate while it reads and streams the selected revision assets.
 * This favors correctness over concurrent editing: revisions cannot be deleted or replaced while
 * a backup is being assembled. Cancellation releases the mutex through [Mutex.withLock].
 */
class LibraryOperationGate internal constructor(
    private val mutex: Mutex = Mutex(),
) {
    suspend fun <T> withMutation(block: suspend () -> T): T = withExclusiveAccess(block)

    suspend fun <T> withStableSnapshot(block: suspend () -> T): T = withExclusiveAccess(block)

    val isOperationActive: Boolean
        get() = mutex.isLocked

    private suspend fun <T> withExclusiveAccess(block: suspend () -> T): T {
        if (coroutineContext[GateContext]?.gate === this) return block()
        return mutex.withLock {
            withContext(GateContext(this@LibraryOperationGate)) { block() }
        }
    }

    private class GateContext(
        val gate: LibraryOperationGate,
    ) : AbstractCoroutineContextElement(GateContext) {
        companion object Key : kotlin.coroutines.CoroutineContext.Key<GateContext>
    }
}

/** Process-local gate shared by repositories and portability engines. */
object LibraryOperationCoordinator {
    val gate: LibraryOperationGate = LibraryOperationGate()
}

/**
 * Resolves the narrow cancellation/error window after an atomic database activation. Room may have
 * committed the transaction before a suspending caller observes a failure. Once the durable journal
 * says COMPLETED, that commit is authoritative and callers must continue as success rather than
 * deleting assets now referenced by ACTIVE rows.
 */
internal suspend fun completeAtomicActivation(
    activate: suspend () -> Unit,
    isCommitted: suspend () -> Boolean,
) {
    try {
        activate()
    } catch (failure: Exception) {
        val committed = withContext(NonCancellable) {
            try {
                isCommitted()
            } catch (_: Exception) {
                false
            }
        }
        if (!committed) throw failure
    }
}
