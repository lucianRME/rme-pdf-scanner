package org.synapseworks.pageharbor.portability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StoragePreflightTest {
    @Test
    fun completeEstimateIncludesWorkingCopiesAndSafetyMargin() {
        val estimate = StoragePreflight.estimate(
            sourceSizes = listOf(100L, 200L),
            workingCopyCount = 2,
            fixedOverheadBytes = 50L,
            minimumSafetyMarginBytes = 0L,
            safetyPercent = 10,
        )

        assertEquals(715L, estimate.requiredBytesLowerBound)
        assertEquals(65L, estimate.safetyMarginBytes)
        assertTrue(estimate.isComplete)
        assertTrue(StoragePreflight.evaluate(estimate, 715L) is StoragePreflightResult.Sufficient)
        assertTrue(StoragePreflight.evaluate(estimate, 714L) is StoragePreflightResult.Insufficient)
    }

    @Test
    fun providerMissingSizeRemainsUnknown() {
        val estimate = StoragePreflight.estimate(
            sourceSizes = listOf(10L, null),
            workingCopyCount = 1,
            minimumSafetyMarginBytes = 5L,
            safetyPercent = 0,
        )

        assertEquals(15L, estimate.requiredBytesLowerBound)
        assertEquals(1, estimate.unknownSourceCount)
        assertTrue(StoragePreflight.evaluate(estimate, 1_000L) is StoragePreflightResult.Unknown)
        assertTrue(StoragePreflight.evaluate(estimate, 14L) is StoragePreflightResult.Insufficient)
    }

    @Test
    fun arithmeticSaturatesInsteadOfWrapping() {
        val estimate = StoragePreflight.estimate(
            sourceSizes = listOf(Long.MAX_VALUE),
            workingCopyCount = 2,
            minimumSafetyMarginBytes = Long.MAX_VALUE,
            safetyPercent = 100,
        )

        assertEquals(Long.MAX_VALUE, estimate.requiredBytesLowerBound)
    }
}
