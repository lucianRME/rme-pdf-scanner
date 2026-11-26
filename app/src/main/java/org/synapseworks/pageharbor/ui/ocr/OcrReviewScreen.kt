package org.synapseworks.pageharbor.ui.ocr

import org.synapseworks.pageharbor.ui.theme.RmeFilterChip
import org.synapseworks.pageharbor.ui.theme.RmeIconButton
import org.synapseworks.pageharbor.ui.theme.RmePrimaryButton
import org.synapseworks.pageharbor.ui.theme.RmeSecondaryButton
import org.synapseworks.pageharbor.ui.theme.RmeTextButton
import org.synapseworks.pageharbor.ui.theme.RmeTextField

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.key
import org.synapseworks.pageharbor.ui.home.OcrPageText
import org.synapseworks.pageharbor.ui.home.OcrSourcePassage
import org.synapseworks.pageharbor.ui.home.ScannedDocumentPreview
import org.synapseworks.pageharbor.ui.home.ocrReadoutBlocks
import org.synapseworks.pageharbor.ui.home.sourcePassages
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
    val pageText = state.page?.effectiveText.orEmpty()
    val blocks = remember(pageText) { ocrReadoutBlocks(pageText) }
    val links = remember(blocks, state.scan) { sourcePassages(blocks, state.scan?.layout) }
    var selected by remember(state.page?.snapshot) { mutableStateOf<OcrSourcePassage?>(null) }
    var showScan by rememberSaveable(state.selectedPageId) { mutableStateOf(false) }
    val position = remember(state.page?.snapshot) { org.synapseworks.pageharbor.ui.home.DocumentPreviewPosition() }
    val source = state.scan?.takeIf { state.selectedPageId == state.page?.snapshot?.pageId && state.loadState == OcrReviewLoadState.READY }
    var spellingOpen by remember(state.documentId, state.selectedPageId, state.editing, state.draftText) {
        mutableStateOf(false)
    }
    if (state.editing && spellingOpen) {
        OcrSpellingDialog(
            sourceText = state.draftText,
            script = OcrScript.fromStableId(state.page?.actualScript),
            confirmLabel = R.string.ocr_spelling_update_draft,
            onDismiss = { spellingOpen = false },
            onConfirm = { reviewed ->
                spellingOpen = false
                onDraftChange(reviewed)
            },
        )
    }

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
        if (showScan) { showScan = false; return@BackHandler }
        if (state.operationRunning) onCancelRerun()
        request(PendingReviewAction.BACK)
    }

    Scaffold(
        topBar = {
            Column {
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
                        RmeIconButton(onClick = { if (showScan) showScan = false else request(PendingReviewAction.BACK) }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.ocr_back_action),
                            )
                        }
                    },
                )
                if (source != null && !state.editing) {
                    androidx.compose.foundation.layout.Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RmeFilterChip(selected = !showScan, onClick = { showScan = false },
                            label = { Text(stringResource(R.string.ocr_text_section_heading)) })
                        RmeFilterChip(selected = showScan, onClick = { showScan = true },
                            label = { Text(stringResource(R.string.ocr_source_tab)) })
                    }
                }
            }
        },
    ) { padding ->
        if (showScan && source != null && !state.editing) {
            key(state.page?.snapshot) {
                ScannedDocumentPreview(source.uri, source.metadata, state.selectedPageIndex + 1, state.pageIds.size,
                    source.rotation, selected?.bounds, position, Modifier.padding(padding).fillMaxSize())
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .padding(padding).consumeWindowInsets(padding).imePadding()
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
                if (script != null) {
                    Text(stringResource(R.string.ocr_review_script, scriptDisplayName(script)),
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val needsText = page.rawText.isNullOrBlank() && page.effectiveText.isNullOrBlank() && !page.hasCorrection
                if (needsText && !state.editing) {
                    ReviewMessage(stringResource(R.string.ocr_review_empty))
                    RmePrimaryButton(
                        enabled = !state.operationRunning,
                        onClick = { request(PendingReviewAction.RERUN_PAGE) },
                    ) {
                        Text(stringResource(R.string.ocr_read_this_page))
                    }
                } else if (state.editing) {
                    Text(
                        text = stringResource(R.string.ocr_review_edit_heading),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (state.draftText.isNotBlank()) {
                        RmeTextButton(onClick = { spellingOpen = true }) {
                            Text(stringResource(R.string.ocr_spelling_review))
                        }
                    }
                    RmeTextField(
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
                        RmePrimaryButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = onSaveCorrection,
                        ) {
                            Text(stringResource(R.string.ocr_review_save))
                        }
                        RmeTextButton(
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
                    if (links.isNotEmpty()) {
                        Text(stringResource(R.string.ocr_tap_source), style = MaterialTheme.typography.bodySmall)
                        androidx.compose.foundation.layout.Box(Modifier.testTag("ocr_review_effective_text")) {
                            OcrPageText(pageText, "", null, blocks, links, selected) {
                                position.lastViewport = androidx.compose.ui.unit.IntSize.Zero
                                selected = it
                                showScan = true
                            }
                        }
                    } else SelectionContainer {
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
                        RmePrimaryButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = onBeginEdit,
                        ) {
                            Text(stringResource(R.string.ocr_review_edit))
                        }
                        if (page.hasCorrection) {
                            RmeSecondaryButton(
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

                if (!state.editing && !needsText) {
                    HorizontalDivider()
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RmeSecondaryButton(
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.operationRunning,
                            onClick = { request(PendingReviewAction.RERUN_PAGE) },
                        ) {
                            Text(stringResource(R.string.ocr_review_rerun_page))
                        }
                        RmeSecondaryButton(
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
                RmeTextButton(onClick = onCancelRerun) {
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
                RmeTextButton(
                    onClick = {
                        pendingAction = null
                        perform(pending)
                    },
                ) { Text(stringResource(R.string.ocr_review_discard_edit)) }
            },
            dismissButton = {
                RmeTextButton(onClick = { pendingAction = null }) {
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
            RmeSecondaryButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = previousEnabled,
                onClick = onPrevious,
            ) {
                Text(stringResource(R.string.ocr_review_previous_page))
            }
            RmeSecondaryButton(
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
