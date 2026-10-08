package org.synapseworks.pageharbor.document.targetsize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetSizeBytesTest {
    @Test
    fun presetsAndConversionsUseExactDecimalBytes() {
        assertEquals(200_000L, TargetSizeBytes.KB_200)
        assertEquals(500_000L, TargetSizeBytes.KB_500)
        assertEquals(1_000_000L, TargetSizeBytes.MB_1)
        assertEquals(2_000_000L, TargetSizeBytes.MB_2)
        assertEquals(37_000L, TargetSizeBytes.kilobytes(37))
        assertEquals(3_000_000L, TargetSizeBytes.megabytes(3))
    }

    @Test
    fun exactFinalSizeRuleHasZeroOverTargetTolerance() {
        assertTrue(isTargetSizeReached(499_999L, TargetSizeBytes.KB_500))
        assertTrue(isTargetSizeReached(500_000L, TargetSizeBytes.KB_500))
        assertFalse(isTargetSizeReached(500_001L, TargetSizeBytes.KB_500))
    }
}
