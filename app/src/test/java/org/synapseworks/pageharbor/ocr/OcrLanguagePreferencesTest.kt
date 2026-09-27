package org.synapseworks.pageharbor.ocr

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OcrLanguagePreferencesTest {
    @Test
    fun missingOrInvalidStoredValueDefaultsToAutomatic() {
        assertEquals(OcrScriptSelection.Automatic, OcrScriptSelection.fromStoredValue(null))
        assertEquals(OcrScriptSelection.Automatic, OcrScriptSelection.fromStoredValue("ARABIC"))
    }

    @Test
    fun stableIdsRoundTripEveryExposedSelection() {
        val selections = listOf(OcrScriptSelection.Automatic) +
            OcrScript.entries.map(OcrScriptSelection::Explicit)

        selections.forEach { selection ->
            assertEquals(selection, OcrScriptSelection.fromStoredValue(selection.stableId))
        }
    }

    @Test
    fun useDefaultResolvesAutomaticRecommendationWithoutChangingDefault() {
        val resolved = OcrOperationSelectionResolver.resolve(
            operationSelection = OcrOperationSelection.UseDefault,
            defaultSelection = OcrScriptSelection.Automatic,
            recommendation = OcrLocaleScriptMapper.recommend(Locale.JAPANESE),
            useAsDefault = false,
        )

        assertEquals(OcrScript.JAPANESE, resolved.script)
        assertNull(resolved.updatedDefault)
    }

    @Test
    fun oneOperationOverrideDoesNotChangeDefault() {
        val resolved = OcrOperationSelectionResolver.resolve(
            operationSelection = OcrOperationSelection.Override(OcrScript.KOREAN),
            defaultSelection = OcrScriptSelection.Automatic,
            recommendation = OcrLocaleScriptMapper.recommend(Locale.ENGLISH),
            useAsDefault = false,
        )

        assertEquals(OcrScript.KOREAN, resolved.script)
        assertNull(resolved.updatedDefault)
    }

    @Test
    fun explicitUseAsDefaultReturnsOnlyTheChosenScript() {
        val resolved = OcrOperationSelectionResolver.resolve(
            operationSelection = OcrOperationSelection.Override(OcrScript.DEVANAGARI),
            defaultSelection = OcrScriptSelection.Automatic,
            recommendation = OcrLocaleScriptMapper.recommend(Locale.ENGLISH),
            useAsDefault = true,
        )

        assertEquals(OcrScript.DEVANAGARI, resolved.script)
        assertEquals(
            OcrScriptSelection.Explicit(OcrScript.DEVANAGARI),
            resolved.updatedDefault,
        )
    }
}
