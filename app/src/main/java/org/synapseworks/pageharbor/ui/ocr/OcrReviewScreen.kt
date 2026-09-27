package org.synapseworks.pageharbor.ui.ocr

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.synapseworks.pageharbor.R
import org.synapseworks.pageharbor.ocr.OcrScript
import org.synapseworks.pageharbor.ocr.review.OcrReviewLoadState
import org.synapseworks.pageharbor.ocr.review.OcrReviewNotice
import org.synapseworks.pageharbor.ocr.review.OcrReviewOperationState
import org.synapseworks.pageharbor.ocr.review.OcrReviewUiState

private enum class PendingReviewAction { BACK, PREVIOUS, NEXT, RERUN_PAGE, RERUN_DOCUMENT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OcrReviewScreen(
    state: OcrReviewUiState,
    onBack: () -> Unit,
    onBeginEdit: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSaveCorrection: () -> Unit,
    onCancelEdit: () -> Unit,
    onRevertCorrection: () -> Unit,
    onSelectPage: (String) -> Unit,
    onRerunPage: () -> Unit,
    onRerunDocument: () -> Unit,
    onCancelRerun: () -> Unit,
) {
    var pendingAction by rememberSaveable { mutableStateOf<PendingReviewAction?>(null) }

    fun perform(action: PendingReviewAction) {
        if (state.editing) onCancelEdit()
        when (action) {
            PendingReviewAction.BACK -> onBack()
            PendingReviewAction.PREVIOUS -> state.pageIds.getOrNull(state.selectedPageIndex - 1)
                ?.let(onSelectPage)
            PendingReviewAction.NEXT -> state.pageIds.getOrNull(state.selectedPageIndex + 1)
                ?.let(onSelectPage)
            PendingReviewAction.RERUN_PAGE -> onRerunPage()
            PendingReviewAction.RERUN_DOCUMENT -> onRerunDocument()
        }
    }

    fun request(action: PendingReviewAction) {
        if (state.hasUnsavedChanges) pendingAction = action else perform(action)
    }

    BackHandler {
        if (state.operationRunning) onCancelRerun()
        request(PendingReviewAction.BACK)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.ocr_review_title),
                            modifier = Modifier.semantics { heading() },
                        )
                        if (state.documentTitle.isNotBlank()) {
                            Text(
                                text = state.documentTitle,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { request(PendingReviewAction.BACK) }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.ocr_back_action),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (state.loadState) {
                OcrReviewLoadState.LOADING -> CircularProgressIndicator(
                    modifier = Modifier.testTag("ocr_review_loading"),
                )
                OcrReviewLoadState.DOCUMENT_MISSING -> ReviewMessage(
                    stringResource(R.string.ocr_review_document_missing),
                )
                OcrReviewLoadState.PAGE_MISSING -> ReviewMessage(
                    stringResource(R.string.ocr_review_page_missing),
                )
                OcrReviewLoadState.FAILED -> ReviewMessage(
                    stringResource(R.string.ocr_review_load_failed),
                )
                OcrReviewLoadState.IDLE,
                OcrReviewLoadState.READY,
                -> Unit
            }

            if (state.pageIds.isNotEmpty()) {
                PageNavigation(
                    current = state.selectedPageIndex + 1,
                    total = state.pageIds.size,
                    previousEnabled = state.selectedPageIndex > 0 && !state.operationRunning,
                    nextEnabled = state.selectedPageIndex < state.pageIds.lastIndex &&
                        !state.operationRunning,
                    onPrevious = { request(PendingReviewAction.PREVIOUS) },
                    onNext = { request(PendingReviewAction.NEXT) },
                )
            }

            val page = state.page
            state.notice?.let { notice ->
                Text(
                    text = noticeMessage(notice),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("ocr_review_notice"),
                )
            }
            if (page != null) {
                val script = page.actualScript?.let(OcrScript::fromStableId)
                Text(
                    text = if (script == null) {
                        stringResource(R.string.ocr_review_no_script)
                    } else {
                        stringResource(R.string.ocr_review_script, scriptDisplayName(script))
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (page.rawText == null) {
                    ReviewMessage(stringResource(R.string.ocr_review_empty))
                    Button(
                        enabled = !state.operationRunning,
                        onClick = { request(PendingReviewAction.RERUN_PAGE) },
                    ) {
                        Text(stringResource(R.string.ocr_review_rerun_page))
                    }
                } else if (state.editing) {
                    Text(
                        text = stringResource(R.string.ocr_review_edit_heading),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    OutlinedTextField(
                        value = state.draftText,
                        onValueChange = onDraftChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 240.dp)
                            .testTag("ocr_review_editor"),
                        label = { Text(stringResource(R.string.ocr_review_edited_text)) },
                    )
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = onSaveCorrection,
                        ) {
                            Text(stringResource(R.string.ocr_review_save))
                        }
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = onCancelEdit,
                        ) {
                            Text(stringResource(R.string.library_cancel))
                        }
                    }
                } else {
                    Text(
                        text = stringResource(
                            if (page.hasCorrection) {
                                R.string.ocr_review_edited_text
                            } else {
                                R.string.ocr_review_recognized_text
                            },
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.semantics { heading() },
                    )
                    if (page.hasCorrection) {
                        Text(
                            text = stringResource(R.string.ocr_review_correction_active),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.testTag("ocr_review_corrected_indicator"),
                        )
                    }
                    SelectionContainer {
                        Text(
                            text = page.effectiveText.orEmpty(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("ocr_review_effective_text"),
                        )
                    }
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = onBeginEdit,
                        ) {
                            Text(stringResource(R.string.ocr_review_edit))
                        }
                        if (page.hasCorrection) {
                            OutlinedButton(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = onRevertCorrection,
                            ) {
                                Text(stringResource(R.string.ocr_review_revert))
                            }
                        }
                    }
                    if (page.hasCorrection) {
                        HorizontalDivider()
                        Text(
                            text = stringResource(R.string.ocr_review_latest_recognized),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        SelectionContainer {
                            Text(
                                text = page.rawText.orEmpty(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("ocr_review_raw_text"),
                            )
                        }
                    }
                }

                if (!state.editing && page.rawText != null) {
                    HorizontalDivider()
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.operationRunning,
                            onClick = { request(PendingReviewAction.RERUN_PAGE) },
                        ) {
                            Text(stringResource(R.string.ocr_review_rerun_page))
                        }
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.operationRunning,
                            onClick = { request(PendingReviewAction.RERUN_DOCUMENT) },
                        ) {
                            Text(stringResource(R.string.ocr_review_rerun_document))
                        }
                    }
                }
            }

            val running = state.operation as? OcrReviewOperationState.Running
            if (running != null) {
                LinearProgressIndicator(
                    progress = {
                        running.progress.completedPages.toFloat() /
                            running.progress.totalPages.coerceAtLeast(1)
                    },
                    modifier = Modifier.fillMaxWidth().testTag("ocr_review_progress"),
                )
                Text(
                    stringResource(
                        R.string.ocr_review_progress,
                        running.progress.completedPages,
                        running.progress.totalPages,
                    ),
                )
                TextButton(onClick = onCancelRerun) {
                    Text(stringResource(R.string.ocr_review_cancel_rerun))
                }
            }
            Spacer(Modifier.padding(bottom = 12.dp))
        }
    }

    val pending = pendingAction
    if (pending != null) {
        AlertDialog(
            onDismissRequest = { pendingAction = null },
            title = { Text(stringResource(R.string.ocr_review_unsaved_title)) },
            text = { Text(stringResource(R.string.ocr_review_unsaved_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingAction = null
                        perform(pending)
                    },
                ) { Text(stringResource(R.string.ocr_review_discard_edit)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingAction = null }) {
                    Text(stringResource(R.string.ocr_review_keep_editing))
                }
            },
        )
    }
}

@Composable
private fun PageNavigation(
    current: Int,
    total: Int,
    previousEnabled: Boolean,
    nextEnabled: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.ocr_review_page_indicator, current, total),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() }.testTag("ocr_review_page_indicator"),
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = previousEnabled,
                onClick = onPrevious,
            ) {
                Text(stringResource(R.string.ocr_review_previous_page))
            }
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = nextEnabled,
                onClick = onNext,
            ) {
                Text(stringResource(R.string.ocr_review_next_page))
            }
        }
    }
}

@Composable
private fun ReviewMessage(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun scriptDisplayName(script: OcrScript): String = stringResource(
    when (script) {
        OcrScript.LATIN -> R.string.ocr_language_latin
        OcrScript.CHINESE -> R.string.ocr_language_chinese
        OcrScript.JAPANESE -> R.string.ocr_language_japanese
        OcrScript.KOREAN -> R.string.ocr_language_korean
        OcrScript.DEVANAGARI -> R.string.ocr_language_devanagari
    },
)

@Composable
private fun noticeMessage(notice: OcrReviewNotice): String = stringResource(
    when (notice) {
        OcrReviewNotice.CORRECTION_SAVED -> R.string.ocr_review_notice_saved
        OcrReviewNotice.CORRECTION_REVERTED -> R.string.ocr_review_notice_reverted
        OcrReviewNotice.RECOGNITION_REFRESHED -> R.string.ocr_review_notice_refreshed
        OcrReviewNotice.RECOGNITION_REFRESHED_CORRECTION_PRESERVED ->
            R.string.ocr_review_notice_refreshed_correction
        OcrReviewNotice.RECOGNITION_PARTIAL -> R.string.ocr_review_notice_partial
        OcrReviewNotice.RECOGNITION_FAILED -> R.string.ocr_review_notice_failed
        OcrReviewNotice.RECOGNITION_CANCELLED -> R.string.ocr_review_notice_cancelled
        OcrReviewNotice.STALE_PAGE -> R.string.ocr_review_notice_stale
    },
)
