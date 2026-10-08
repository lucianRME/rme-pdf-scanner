package org.synapseworks.pageharbor.document.targetsize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetSizeStoragePreflightTest {
    @Test
    fun sourceOutputWorkspaceAndReserveRemainSeparate() {
        val estimate = TargetSizeStoragePreflight.estimate(
            sourceSizes = listOf(120_000L, 80_000L, null),
            targetBytes = 500_000L,
            candidateUpperBoundBytes = 700_000L,
            safetyReserveBytes = 1_000_000L,
        )

        assertEquals(200_000L, estimate.sourceBytes)
        assertEquals(1, estimate.unknownSourceCount)
        assertEquals(500_000L, estimate.estimatedOutputBytes)
        assertEquals(2_800_000L, estimate.temporaryWorkspaceBytes)
        assertEquals(1_000_000L, estimate.safetyReserveBytes)
        assertEquals(3_800_000L, estimate.requiredAvailableBytes)
        assertTrue(TargetSizeStoragePreflight.isSufficient(estimate, 3_800_000L)!!)
        assertFalse(TargetSizeStoragePreflight.isSufficient(estimate, 3_799_999L)!!)
        assertNull(TargetSizeStoragePreflight.isSufficient(estimate, null))
    }
}
