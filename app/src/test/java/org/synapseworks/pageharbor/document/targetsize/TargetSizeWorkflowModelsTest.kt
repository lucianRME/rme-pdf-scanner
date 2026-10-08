package org.synapseworks.pageharbor.document.targetsize

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetSizeWorkflowModelsTest {
    @Test
    fun presetsRetainExactM4DecimalTargets() {
        assertEquals(
            listOf(200_000L, 500_000L, 1_000_000L, 2_000_000L),
            TargetSizePreset.entries.map(TargetSizePreset::bytes),
        )
    }

    @Test
    fun customKilobytesCoverBoundaryAndTypicalValues() {
        listOf(199L, 200L, 201L, 750L).forEach { value ->
            assertEquals(
                TargetSizeCustomValidation.Valid(value * 1_000L),
                TargetSizeCustomLimitParser.parse(value.toString(), TargetSizeCustomUnit.KB, Locale.US),
            )
        }
    }

    @Test
    fun customMegabytesParseLocaleDecimalSeparatorsSafely() {
        assertEquals(
            TargetSizeCustomValidation.Valid(1_500_000L),
            TargetSizeCustomLimitParser.parse("1.5", TargetSizeCustomUnit.MB, Locale.US),
        )
        assertEquals(
            TargetSizeCustomValidation.Valid(1_500_000L),
            TargetSizeCustomLimitParser.parse("1,5", TargetSizeCustomUnit.MB, Locale.GERMANY),
        )
    }

    @Test
    fun customInputRejectsInvalidZeroNegativeTinyAndHugeValues() {
        assertEquals(
            TargetSizeCustomError.INVALID_NUMBER,
            invalidReason("words", TargetSizeCustomUnit.KB),
        )
        assertEquals(TargetSizeCustomError.INVALID_NUMBER, invalidReason("0", TargetSizeCustomUnit.KB))
        assertEquals(TargetSizeCustomError.INVALID_NUMBER, invalidReason("-1", TargetSizeCustomUnit.KB))
        assertEquals(TargetSizeCustomError.TOO_SMALL, invalidReason("99", TargetSizeCustomUnit.KB))
        assertEquals(TargetSizeCustomError.TOO_LARGE, invalidReason("1001", TargetSizeCustomUnit.MB))
    }

    @Test
    fun customInputRejectsFractionalBytesAndTrailingText() {
        assertEquals(
            TargetSizeCustomError.INVALID_NUMBER,
            invalidReason("100.0001", TargetSizeCustomUnit.KB),
        )
        assertEquals(
            TargetSizeCustomError.INVALID_NUMBER,
            invalidReason("750 KB", TargetSizeCustomUnit.KB),
        )
    }

    @Test
    fun resultFormattingUsesDecimalUnitsAndNaturalPrecision() {
        assertEquals("200 KB", formatTargetSize(200_000L))
        assertEquals("842 KB", formatDecimalBytes(842_000L, Locale.US))
        assertEquals("1.5 MB", formatDecimalBytes(1_500_000L, Locale.US))
    }

    @Test
    fun reductionIsFactualAndNeverClaimsGrowthAsReduction() {
        assertEquals(88, reductionPercent(7_200_000L, 842_000L))
        assertNull(reductionPercent(600_000L, 600_000L))
        assertNull(reductionPercent(600_000L, 700_000L))
        assertNull(reductionPercent(null, 500_000L))
    }

    @Test
    fun alreadyFitsRequiresSafeOriginalAndExactKnownBytes() {
        assertTrue(shouldReuseOriginalPdf(true, 600_000L, 2_000_000L))
        assertTrue(shouldReuseOriginalPdf(true, 2_000_000L, 2_000_000L))
        assertFalse(shouldReuseOriginalPdf(false, 600_000L, 2_000_000L))
        assertFalse(shouldReuseOriginalPdf(true, null, 2_000_000L))
        assertFalse(shouldReuseOriginalPdf(true, 2_000_001L, 2_000_000L))
    }

    @Test
    fun unrealisticWarningIsPageCountAware() {
        assertTrue(isObviouslyUnrealisticTarget(200_000L, 100))
        assertFalse(isObviouslyUnrealisticTarget(2_000_000L, 20))
    }

    @Test
    fun searchableTextStatusNeverOverclaimsUnpositionedText() {
        assertEquals(
            TargetSizeSearchableTextStatus.PRESERVED,
            searchableTextStatus(2, 2, listOf(TargetSizeTextStrategy.PRESERVE_POSITIONED_UNICODE)),
        )
        assertEquals(
            TargetSizeSearchableTextStatus.PARTIALLY_PRESERVED,
            searchableTextStatus(1, 2, emptyList()),
        )
        assertEquals(
            TargetSizeSearchableTextStatus.NOT_PRESERVED,
            searchableTextStatus(
                0,
                2,
                listOf(TargetSizeTextStrategy.OMIT_UNPOSITIONED_WITH_WARNING),
            ),
        )
        assertEquals(
            TargetSizeSearchableTextStatus.NOT_PRESENT,
            searchableTextStatus(0, 2, listOf(TargetSizeTextStrategy.NONE)),
        )
    }

    @Test
    fun filenameIsBoundedSafeAndNonDestructive() {
        assertEquals("Tax form-smaller.pdf", targetSizeFilename("Tax form.pdf"))
        assertEquals("Document-smaller.pdf", targetSizeFilename("***"))
        assertTrue(targetSizeFilename("x".repeat(200)).length <= 92)
    }

    private fun invalidReason(text: String, unit: TargetSizeCustomUnit): TargetSizeCustomError =
        (TargetSizeCustomLimitParser.parse(text, unit, Locale.US) as TargetSizeCustomValidation.Invalid)
            .reason
}
