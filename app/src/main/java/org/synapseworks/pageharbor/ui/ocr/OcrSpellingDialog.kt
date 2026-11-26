package org.synapseworks.pageharbor.ui.ocr

import org.synapseworks.pageharbor.ui.theme.RmePrimaryButton
import org.synapseworks.pageharbor.ui.theme.RmeSecondaryButton
import org.synapseworks.pageharbor.ui.theme.RmeTextButton

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import java.io.IOException
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.synapseworks.pageharbor.R
import org.synapseworks.pageharbor.ocr.OCR_SPELLING_PAGE_LIMIT
import org.synapseworks.pageharbor.ocr.OcrScript
import org.synapseworks.pageharbor.ocr.OcrSpellingLanguage
import org.synapseworks.pageharbor.ocr.OcrSpellingReview
import org.synapseworks.pageharbor.ocr.readSpellingWords
import org.synapseworks.pageharbor.ocr.reviewOcrSpelling

@Composable
internal fun OcrSpellingDialog(
    sourceText: String,
    script: OcrScript?,
    @StringRes confirmLabel: Int,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val assets = LocalContext.current.assets
    val options = remember(script) { OcrSpellingLanguage.options(script) }
    var language by remember(script) {
        mutableStateOf(OcrSpellingLanguage.default(script, Locale.getDefault()))
    }
    var choosingLanguage by remember { mutableStateOf(false) }
    var result by remember(sourceText, language) { mutableStateOf<OcrSpellingReview?>(null) }
    var failed by remember(sourceText, language) { mutableStateOf(false) }
    var selected by remember(sourceText, language) { mutableStateOf(setOf<Int>()) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { onDismiss() }

    val activeLanguage = language
    LaunchedEffect(sourceText, activeLanguage) {
        if (sourceText.length <= OCR_SPELLING_PAGE_LIMIT) {
            try {
                val words = withContext(Dispatchers.IO) {
                    assets.open(activeLanguage.asset).use { stream ->
                        val input = if (activeLanguage.asset.endsWith(".gzip")) GZIPInputStream(stream) else stream
                        readSpellingWords(input, activeLanguage) { ensureActive() }
                    }
                }
                result = withContext(Dispatchers.Default) {
                    reviewOcrSpelling(sourceText, activeLanguage, words) { ensureActive() }
                }
            } catch (_: IOException) {
                failed = true
            }
        }
    }

    val review = result
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ocr_spelling_title, language.label)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (options.size > 1) {
                    Box {
                        RmeSecondaryButton(onClick = { choosingLanguage = true }) {
                            Text(stringResource(R.string.ocr_spelling_choose_language))
                        }
                        DropdownMenu(expanded = choosingLanguage, onDismissRequest = { choosingLanguage = false }) {
                            for (option in options) {
                                DropdownMenuItem(
                                    text = { Text(option.label) },
                                    onClick = {
                                        choosingLanguage = false
                                        language = option
                                    },
                                )
                            }
                        }
                    }
                }
                Text(
                    text = stringResource(
                        when {
                            sourceText.length > OCR_SPELLING_PAGE_LIMIT -> R.string.ocr_spelling_page_limit
                            failed -> R.string.ocr_spelling_unavailable
                            review == null -> R.string.ocr_spelling_checking
                            review.suggestions.isEmpty() && !review.reachedReviewLimit -> R.string.ocr_spelling_no_suggestions
                            else -> R.string.ocr_spelling_instructions
                        },
                    ),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                if (review != null) {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                        items(review.suggestions, key = { it.start }) { suggestion ->
                            val checked = suggestion.start in selected
                            Row(
                                modifier = Modifier.fillMaxWidth().toggleable(
                                    value = checked,
                                    role = Role.Checkbox,
                                    onValueChange = { enabled ->
                                        selected = if (enabled) selected + suggestion.start else selected - suggestion.start
                                    },
                                ).padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Checkbox(checked = checked, onCheckedChange = null)
                                Column {
                                    Text(stringResource(R.string.ocr_spelling_original_word, suggestion.original))
                                    Text(
                                        stringResource(R.string.ocr_spelling_suggested_word, suggestion.replacement),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                    if (review.reachedReviewLimit) Text(stringResource(R.string.ocr_spelling_partial))
                }
            }
        },
        dismissButton = { RmeTextButton(onClick = onDismiss) { Text(stringResource(R.string.library_cancel)) } },
        confirmButton = {
            if (review?.suggestions?.isNotEmpty() == true) {
                RmePrimaryButton(
                    enabled = selected.isNotEmpty(),
                    onClick = { onConfirm(review.applyTo(sourceText, selected)) },
                ) { Text(stringResource(confirmLabel)) }
            }
        },
    )
}
