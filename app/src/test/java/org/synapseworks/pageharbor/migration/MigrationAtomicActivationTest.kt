package org.synapseworks.pageharbor.migration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class MigrationAtomicActivationTest {
    @Test
    fun cancellationObservedAfterMigrationCommitIsResolvedAsCommitted() = runBlocking {
        var phase = "PREPARING"
        var rollbackReached = false

        try {
            val result = completeMigrationActivation(
                activate = {
                    phase = "COMPLETED"
                    throw CancellationException("synthetic post-commit cancellation")
                },
                isCommitted = { phase == "COMPLETED" },
            )
            assertEquals(MigrationPublicationResult.Published, result)
        } catch (_: CancellationException) {
            rollbackReached = true
        }

        assertEquals("COMPLETED", phase)
        assertFalse(rollbackReached)
    }

    @Test
    fun cancellationBeforeMigrationCommitStillPropagatesForRollback() {
        assertThrows(CancellationException::class.java) {
            runBlocking {
                completeMigrationActivation(
                    activate = { throw CancellationException("synthetic pre-commit cancellation") },
                    isCommitted = { false },
                )
            }
        }
    }
}
