package org.synapseworks.pageharbor.ui.home

import org.synapseworks.pageharbor.ui.theme.RmePrimaryButton
import org.synapseworks.pageharbor.ui.theme.RmeSecondaryButton
import org.synapseworks.pageharbor.ui.theme.RmeTextButton
import org.synapseworks.pageharbor.ui.theme.RmeTextField

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.roundToInt
import org.synapseworks.pageharbor.R
import org.synapseworks.pageharbor.document.batch.*
import org.synapseworks.pageharbor.document.session.toAndroidUri

@Composable
internal fun BatchEditingDialog(editor: SelectedPageBatchEditor) {
    val state = editor.state ?: return
    val progress by editor.progress.collectAsState()
    val selected = remember(state.pages, state.selectedIds) { state.pages.filter { it.id.value in state.selectedIds } }
    val enabled = !state.busy && state.outcome != BatchEditOutcome.STALE
    val canEdit = enabled && selected.isNotEmpty()
    var range by rememberSaveable(state.captureId) { mutableStateOf("") }
    var rangeError by rememberSaveable(state.captureId) { mutableStateOf(false) }
    val mixed = stringResource(R.string.batch_mixed)
    val initialFilter = selected.map { it.filter }.distinct().singleOrNull()
    val initialBrightness = selected.map { it.adjustments.brightness }.distinct().singleOrNull()
    val initialContrast = selected.map { it.adjustments.contrast }.distinct().singleOrNull()
    val initialRotation = selected.map { it.rotation.degrees }.distinct().singleOrNull()
    val leave = stringResource(R.string.batch_leave_unchanged)
    val filterName = state.edit.filter?.let { filter -> stringResource(filterSelectorOptions.first { it.filter == filter }.labelRes) } ?: leave
    val rotationLabels = listOf(stringResource(R.string.batch_leave_unchanged), stringResource(R.string.batch_clockwise),
        stringResource(R.string.batch_half_turn), stringResource(R.string.batch_counterclockwise))
    Dialog(onDismissRequest = editor::close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.safeDrawingPadding().imePadding().padding(12.dp).widthIn(max = 720.dp)
            .fillMaxWidth().fillMaxHeight(0.95f), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.batch_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.batch_count, selected.size, state.pages.size),
                    Modifier.testTag("batch-count").semantics { liveRegion = LiveRegionMode.Polite })
                RmePrimaryButton(onClick = editor::selectAll, enabled = enabled && state.pages.isNotEmpty(), modifier = Modifier.fillMaxWidth().testTag("batch-all")) { Text(stringResource(R.string.batch_select_all)) }
                RmeTextButton(onClick = { editor.select(emptySet()) }, enabled = canEdit, modifier = Modifier.fillMaxWidth().testTag("batch-clear")) { Text(stringResource(R.string.batch_clear)) }
                RmeTextField(range, { if (it.length <= 200) { range = it; rangeError = false } },
                    modifier = Modifier.fillMaxWidth().testTag("batch-range"), enabled = enabled, singleLine = true,
                    label = { Text(stringResource(R.string.batch_range)) }, isError = rangeError)
                if (rangeError) Text(stringResource(R.string.batch_range_error), color = MaterialTheme.colorScheme.error)
                RmeSecondaryButton(onClick = { rangeError = !editor.selectRange(range) }, enabled = enabled && range.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().testTag("batch-select-range")) { Text(stringResource(R.string.batch_select_range)) }
                LazyColumn(Modifier.fillMaxWidth().height(180.dp).testTag("batch-pages")) {
                    itemsIndexed(state.pages, key = { _, p -> p.id.value }) { index, page ->
                        val checked = page.id.value in state.selectedIds
                        Column {
                            Row(Modifier.fillMaxWidth().testTag("batch-page-${page.id.value}")
                                .toggleable(checked, enabled, Role.Checkbox) {
                                    editor.select(if (checked) state.selectedIds - page.id.value else state.selectedIds + page.id.value)
                                }.padding(4.dp)) {
                                Checkbox(checked, onCheckedChange = null, enabled = enabled)
                                Text(stringResource(R.string.library_page_number, index + 1), Modifier.weight(1f))
                            }
                            if (checked) RmeTextButton(onClick = { editor.preview(page.id.value) }, enabled = enabled,
                                modifier = Modifier.fillMaxWidth().testTag("batch-preview-${page.id.value}")) { Text(stringResource(R.string.batch_preview_page, index + 1)) }
                        }
                    }
                }
                val preview = state.pages.firstOrNull { it.id.value == state.previewId && it.id.value in state.selectedIds }
                if (preview != null && state.outcome != BatchEditOutcome.STALE) {
                    val rendered = state.edit.preview(preview)
                    Text(stringResource(R.string.batch_preview_page, state.pages.indexOf(preview) + 1))
                    FilteredDocumentPreview(FilteredPreviewRequest(rendered.id.value, rendered.source.reference,
                        rendered.filter, rendered.rotation, rendered.imageMetadata, rendered.adjustments),
                        rendered.source.toAndroidUri(), state.pages.indexOf(preview) + 1, state.pages.size,
                        minHeight = 100.dp, maxHeight = 160.dp)
                }
                Text(stringResource(R.string.batch_captured_values,
                    if (selected.isEmpty()) "—" else initialFilter?.let { stringResource(filterSelectorOptions.first { o -> o.filter == it }.labelRes) } ?: mixed,
                    if (selected.isEmpty()) "—" else initialBrightness?.toString() ?: mixed,
                    if (selected.isEmpty()) "—" else initialContrast?.toString() ?: mixed,
                    if (selected.isEmpty()) "—" else initialRotation?.let { "$it°" } ?: mixed), Modifier.testTag("batch-mixed"))
                Text(stringResource(R.string.batch_absolute_help))
                RmeSecondaryButton(onClick = { val index = supportedBatchFilters.indexOf(state.edit.filter)
                    editor.setEdit(state.edit.copy(filter = supportedBatchFilters.getOrNull(index + 1))) }, enabled = canEdit,
                    modifier = Modifier.fillMaxWidth().testTag("batch-filter")) { Text(stringResource(R.string.batch_filter, filterName)) }
                RmeSecondaryButton(onClick = { editor.setEdit(state.edit.copy(clockwiseQuarterTurns = (state.edit.clockwiseQuarterTurns + 1) % 4)) },
                    enabled = canEdit, modifier = Modifier.fillMaxWidth().testTag("batch-rotation")) { Text(rotationLabels[state.edit.clockwiseQuarterTurns]) }
                BatchAdjustment(R.string.manual_brightness, "brightness", state.edit.brightness, initialBrightness ?: 0, canEdit) {
                    editor.setEdit(state.edit.copy(brightness = it)) }
                BatchAdjustment(R.string.manual_contrast, "contrast", state.edit.contrast, initialContrast ?: 0, canEdit) {
                    editor.setEdit(state.edit.copy(contrast = it)) }
                if (state.busy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.batch_progress, progress.second, state.selectedIds.size), Modifier.testTag("batch-progress").semantics { liveRegion = LiveRegionMode.Polite })
                    RmeSecondaryButton(onClick = editor::cancel, modifier = Modifier.fillMaxWidth().testTag("batch-cancel")) { Text(stringResource(R.string.batch_cancel)) }
                }
                val status = when (state.outcome) {
                    BatchEditOutcome.DRAFT -> R.string.batch_draft
                    BatchEditOutcome.APPLIED -> null
                    BatchEditOutcome.NO_CHANGES -> R.string.batch_no_changes
                    BatchEditOutcome.CANCELLED -> R.string.batch_cancelled
                    BatchEditOutcome.FAILED -> R.string.batch_failed
                    BatchEditOutcome.STALE -> R.string.batch_stale
                    BatchEditOutcome.UNDONE -> R.string.batch_undone
                }
                Text(if (status == null) pluralStringResource(R.plurals.batch_applied, state.changedPages, state.changedPages) else stringResource(status), Modifier.testTag("batch-status").semantics { liveRegion = LiveRegionMode.Polite })
                RmePrimaryButton(onClick = editor::apply, enabled = canEdit, modifier = Modifier.fillMaxWidth().testTag("batch-apply")) { Text(stringResource(R.string.batch_apply)) }
                RmeSecondaryButton(onClick = editor::undo, enabled = enabled && editor.canUndoCommitted, modifier = Modifier.fillMaxWidth().testTag("batch-undo")) { Text(stringResource(R.string.batch_undo)) }
                RmeTextButton(onClick = editor::close, modifier = Modifier.fillMaxWidth().testTag("batch-close")) { Text(stringResource(R.string.about_close)) }
            }
        }
    }
}

@Composable
private fun BatchAdjustment(label: Int, name: String, value: Int?, initial: Int, enabled: Boolean, onChange: (Int?) -> Unit) {
    val title = stringResource(label)
    val setLabel = stringResource(R.string.batch_set_field, title)
    Row(Modifier.fillMaxWidth().testTag("batch-set-$name").toggleable(value != null, enabled, Role.Checkbox) { onChange(if (it) initial else null) }) {
        Checkbox(value != null, onCheckedChange = null, enabled = enabled)
        Text(setLabel, Modifier.weight(1f))
    }
    if (value != null) {
        Text(stringResource(R.string.manual_value, title, value))
        Slider(value.toFloat(), { onChange(it.roundToInt().coerceIn(-100,100)) }, enabled = enabled,
            valueRange = -100f..100f, steps = 199,
            modifier = Modifier.fillMaxWidth().testTag("batch-$name").semantics { contentDescription = title; stateDescription = "$value %" })
    }
}
