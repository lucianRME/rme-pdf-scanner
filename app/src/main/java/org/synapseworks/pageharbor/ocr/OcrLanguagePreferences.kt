package org.synapseworks.pageharbor.ocr

import android.content.Context

interface OcrLanguagePreferenceStore {
    fun read(): OcrScriptSelection
    fun write(selection: OcrScriptSelection)
}

class SharedPreferencesOcrLanguagePreferenceStore(context: Context) :
    OcrLanguagePreferenceStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun read(): OcrScriptSelection = OcrScriptSelection.fromStoredValue(
        preferences.getString(DEFAULT_SELECTION, null),
    )

    override fun write(selection: OcrScriptSelection) {
        check(preferences.edit().putString(DEFAULT_SELECTION, selection.stableId).commit()) {
            "Unable to persist OCR language preference"
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "rme_ocr_language_v1"
        const val DEFAULT_SELECTION = "default_selection"
    }
}

sealed interface OcrOperationSelection {
    data object UseDefault : OcrOperationSelection
    data class Override(val script: OcrScript) : OcrOperationSelection
}

data class ResolvedOcrOperation(
    val script: OcrScript,
    val updatedDefault: OcrScriptSelection?,
)

object OcrOperationSelectionResolver {
    fun resolve(
        operationSelection: OcrOperationSelection,
        defaultSelection: OcrScriptSelection,
        recommendation: OcrScriptRecommendation,
        useAsDefault: Boolean,
    ): ResolvedOcrOperation {
        val script = when (operationSelection) {
            OcrOperationSelection.UseDefault -> defaultSelection.resolve(recommendation)
            is OcrOperationSelection.Override -> operationSelection.script
        }
        val updatedDefault = if (useAsDefault) {
            when (operationSelection) {
                OcrOperationSelection.UseDefault -> defaultSelection
                is OcrOperationSelection.Override ->
                    OcrScriptSelection.Explicit(operationSelection.script)
            }
        } else {
            null
        }
        return ResolvedOcrOperation(script, updatedDefault)
    }
}
