package org.synapseworks.pageharbor.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class OcrScriptTest {
    @Test
    fun stableIdentifiersRoundTripWithoutSdkTypesOrOrdinals() {
        OcrScript.entries.forEach { script ->
            assertEquals(script, OcrScript.fromStableId(script.stableId))
        }

        assertNull(OcrScript.fromStableId("ARABIC"))
        assertNull(OcrScript.fromStableId("latin"))
    }

    @Test
    fun selectionRoundTripsAndInvalidStorageFallsBackToAutomatic() {
        assertEquals(
            OcrScriptSelection.Automatic,
            OcrScriptSelection.fromStableId(OcrScriptSelection.AUTOMATIC_STABLE_ID),
        )
        OcrScript.entries.forEach { script ->
            assertEquals(
                OcrScriptSelection.Explicit(script),
                OcrScriptSelection.fromStableId(script.stableId),
            )
        }
        assertEquals(OcrScriptSelection.Automatic, OcrScriptSelection.fromStoredValue(null))
        assertEquals(OcrScriptSelection.Automatic, OcrScriptSelection.fromStoredValue("UNKNOWN"))
    }

    @Test
    fun explicitSelectionOverridesAutomaticRecommendation() {
        val recommendation = OcrScriptRecommendation(
            script = OcrScript.JAPANESE,
            basis = OcrScriptRecommendation.Basis.LOCALE_LANGUAGE,
        )

        assertEquals(OcrScript.JAPANESE, OcrScriptSelection.Automatic.resolve(recommendation))
        assertEquals(
            OcrScript.KOREAN,
            OcrScriptSelection.Explicit(OcrScript.KOREAN).resolve(recommendation),
        )
    }

    @Test
    fun downloadProgressIsDeterminateOnlyWithValidRealByteValues() {
        assertEquals(null, OcrModelState.Downloading().fraction)
        assertEquals(0.25, OcrModelState.Downloading(25L, 100L).fraction!!, 0.0)
        assertThrows(IllegalArgumentException::class.java) {
            OcrModelState.Downloading(downloadedBytes = 1L, totalBytes = null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            OcrModelState.Downloading(downloadedBytes = 101L, totalBytes = 100L)
        }
    }
}
