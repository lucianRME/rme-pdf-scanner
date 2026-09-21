package org.synapseworks.pageharbor.library

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryOperationGateTest {
    @Test
    fun snapshotAndMutationNeverOverlap() {
        val gate = LibraryOperationGate()
        val snapshotStarted = CountDownLatch(1)
        val allowSnapshotToFinish = CountDownLatch(1)
        val mutationEntered = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val snapshot = executor.submit {
                runBlocking {
                    gate.withStableSnapshot {
                        snapshotStarted.countDown()
                        assertTrue(allowSnapshotToFinish.await(5, TimeUnit.SECONDS))
                    }
                }
            }
            assertTrue(snapshotStarted.await(5, TimeUnit.SECONDS))

            val mutation = executor.submit {
                runBlocking {
                    gate.withMutation { mutationEntered.countDown() }
                }
            }
            assertFalse(mutationEntered.await(100, TimeUnit.MILLISECONDS))
            allowSnapshotToFinish.countDown()
            snapshot.get(5, TimeUnit.SECONDS)
            assertTrue(mutationEntered.await(5, TimeUnit.SECONDS))
            mutation.get(5, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun cancellationReleasesGate() = runBlocking {
        val gate = LibraryOperationGate()

        try {
            gate.withStableSnapshot<Unit> { throw CancellationException("cancel") }
        } catch (_: CancellationException) {
            // Expected.
        }

        var mutations = 0
        gate.withMutation { mutations += 1 }
        assertEquals(1, mutations)
        assertFalse(gate.isOperationActive)
    }

    @Test
    fun sameCoroutineCanEnterMutationFromSnapshotWithoutDeadlock() = runBlocking {
        val gate = LibraryOperationGate()
        var calls = 0

        gate.withStableSnapshot {
            calls += 1
            gate.withMutation { calls += 1 }
        }

        assertEquals(2, calls)
        assertFalse(gate.isOperationActive)
    }
}
