package org.synapseworks.pageharbor.ui.ocr

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.synapseworks.pageharbor.R
import org.synapseworks.pageharbor.ocr.batch.OcrBatchMode
import org.synapseworks.pageharbor.ocr.batch.OcrBatchSetupError
import org.synapseworks.pageharbor.ocr.batch.OcrBatchUiState

@Composable
fun OcrBatchSetupDialog(
    state: OcrBatchUiState.Setup,
    onModeChange: (OcrBatchMode) -> Unit,
    onChooseLanguage: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ocr_batch_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.ocr_batch_selected_documents, state.documentIds.size))
                BatchModeRow(
                    title = stringResource(R.string.ocr_batch_missing_title),
                    detail = stringResource(R.string.ocr_batch_missing_detail),
                    selected = state.mode == OcrBatchMode.MISSING_ONLY,
                    onClick = { onModeChange(OcrBatchMode.MISSING_ONLY) },
                )
                BatchModeRow(
                    title = stringResource(R.string.ocr_batch_rerun_title),
                    detail = stringResource(R.string.ocr_batch_rerun_detail),
                    selected = state.mode == OcrBatchMode.RERUN,
                    onClick = { onModeChange(OcrBatchMode.RERUN) },
                )
                state.error?.let { error ->
                    Text(
                        text = stringResource(
                            when (error) {
                                OcrBatchSetupError.MODEL_UNAVAILABLE ->
                                    R.string.ocr_batch_model_unavailable
                                OcrBatchSetupError.EMPTY_SELECTION ->
                                    R.string.ocr_batch_empty_selection
                                OcrBatchSetupError.PLANNING_FAILED ->
                                    R.string.ocr_batch_planning_failed
                            },
                        ),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(enabled = state.documentIds.isNotEmpty(), onClick = onChooseLanguage) {
                Text(stringResource(R.string.ocr_batch_choose_language))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.library_cancel)) }
        },
    )
}

@Composable
fun OcrBatchStatusDialog(
    state: OcrBatchUiState,
    onCancel: () -> Unit,
    onRetryFailed: () -> Unit,
    onDone: () -> Unit,
) {
    when (state) {
        is OcrBatchUiState.Planning -> AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.ocr_batch_preparing)) },
            text = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.ocr_batch_selected_documents, state.documentTotal))
                }
            },
            confirmButton = {},
        )
        is OcrBatchUiState.Running -> {
            val progress = state.progress
            val settled = progress.completedPages + progress.skippedPages + progress.failedPages
            AlertDialog(
                onDismissRequest = {},
                title = { Text(stringResource(R.string.ocr_batch_running)) },
                text = {
                    Column(
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LinearProgressIndicator(
                            progress = {
                                if (progress.pageTotal == 0) 0f
                                else settled.toFloat() / progress.pageTotal.toFloat()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            stringResource(
                                R.string.ocr_batch_document_progress,
                                progress.completedDocuments,
                                progress.documentTotal,
                            ),
                        )
                        Text(
                            stringResource(
                                R.string.ocr_batch_page_progress,
                                settled,
                                progress.pageTotal,
                            ),
                        )
                        progress.currentDocumentTitle?.let {
                            Text(stringResource(R.string.ocr_batch_current_document, it))
                        }
                        Text(
                            stringResource(
                                R.string.ocr_batch_counts,
                                progress.completedPages,
                                progress.skippedPages,
                                progress.failedPages,
                            ),
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = onCancel) {
                        Text(stringResource(R.string.ocr_batch_cancel))
                    }
                },
            )
        }
        is OcrBatchUiState.Finished -> {
            val summary = state.summary
            AlertDialog(
                onDismissRequest = onDone,
                title = {
                    Text(
                        stringResource(
                            if (summary.cancelled) R.string.ocr_batch_cancelled
                            else R.string.ocr_batch_complete,
                        ),
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(
                                R.string.ocr_batch_summary,
                                summary.documentTotal,
                                summary.pageTotal,
                            ),
                        )
                        Text(
                            stringResource(
                                R.string.ocr_batch_counts,
                                summary.completedPages,
                                summary.skippedPages,
                                summary.failedPages,
                            ),
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = onDone) { Text(stringResource(R.string.ocr_batch_done)) }
                },
                dismissButton = {
                    if (!summary.cancelled && summary.hasRetryableWork) {
                        TextButton(onClick = onRetryFailed) {
                            Text(stringResource(R.string.ocr_batch_retry_failed))
                        }
                    }
                },
            )
        }
        OcrBatchUiState.Hidden,
        is OcrBatchUiState.Setup,
        -> Unit
    }
}

@Composable
private fun BatchModeRow(
    title: String,
    detail: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}
