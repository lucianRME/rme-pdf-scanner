package org.synapseworks.pageharbor.ui.targetsize

import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import java.io.File
import kotlin.math.roundToInt
import org.synapseworks.pageharbor.R
import org.synapseworks.pageharbor.document.targetsize.TargetSizeCustomError
import org.synapseworks.pageharbor.document.targetsize.TargetSizeCustomUnit
import org.synapseworks.pageharbor.document.targetsize.TargetSizeCustomValidation
import org.synapseworks.pageharbor.document.targetsize.TargetSizeDestinationError
import org.synapseworks.pageharbor.document.targetsize.TargetSizeLimitSelection
import org.synapseworks.pageharbor.document.targetsize.TargetSizePdfFailure
import org.synapseworks.pageharbor.document.targetsize.TargetSizePreparedPdf
import org.synapseworks.pageharbor.document.targetsize.TargetSizePreset
import org.synapseworks.pageharbor.document.targetsize.TargetSizeProgress
import org.synapseworks.pageharbor.document.targetsize.TargetSizeQualitySearch
import org.synapseworks.pageharbor.document.targetsize.TargetSizeSaveStatus
import org.synapseworks.pageharbor.document.targetsize.TargetSizeSearchableTextStatus
import org.synapseworks.pageharbor.document.targetsize.TargetSizeWorkflowState
import org.synapseworks.pageharbor.document.targetsize.formatDecimalBytes
import org.synapseworks.pageharbor.document.targetsize.formatTargetSize
import org.synapseworks.pageharbor.document.targetsize.isObviouslyUnrealisticTarget
import org.synapseworks.pageharbor.document.targetsize.reductionPercent
import org.synapseworks.pageharbor.ui.home.ManagedDocumentPreviewState
import org.synapseworks.pageharbor.ui.home.rememberManagedDocumentPreview
import org.synapseworks.pageharbor.ui.theme.PageHarborLayout
import org.synapseworks.pageharbor.ui.theme.PageHarborSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FitUploadLimitScreen(
    state: TargetSizeWorkflowState,
    onBack: () -> Unit,
    onPresetSelected: (TargetSizePreset) -> Unit,
    onCustomSelected: () -> Unit,
    onCustomTextChanged: (String) -> Unit,
    onCustomUnitChanged: (TargetSizeCustomUnit) -> Unit,
    onPrepare: () -> Unit,
    onCancel: () -> Unit,
    onUseAlternative: () -> Unit,
    onChangeLimit: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onRetry: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        modifier = Modifier.semantics { heading() },
                        text = stringResource(R.string.fit_limit_title),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.fit_limit_close),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            when (state) {
                TargetSizeWorkflowState.Hidden,
                TargetSizeWorkflowState.Opening,
                -> LoadingContent()
                is TargetSizeWorkflowState.ChooseLimit -> ChooseLimitContent(
                    state,
                    onPresetSelected,
                    onCustomSelected,
                    onCustomTextChanged,
                    onCustomUnitChanged,
                    onPrepare,
                )
                is TargetSizeWorkflowState.Preparing -> PreparingContent(state, onCancel)
                is TargetSizeWorkflowState.Prepared -> PreparedContent(
                    state,
                    onSave,
                    onShare,
                    onChangeLimit,
                )
                is TargetSizeWorkflowState.ImpossibleTarget -> ImpossibleContent(
                    state,
                    onUseAlternative,
                    onChangeLimit,
                )
                is TargetSizeWorkflowState.Failed -> FailedContent(state, onRetry, onChangeLimit)
            }
        }
    }
}

@Composable
private fun LoadingContent() {
    Column(
        modifier = Modifier.padding(PageHarborSpacing.extraLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.medium),
    ) {
        CircularProgressIndicator()
        Text(stringResource(R.string.fit_limit_opening))
    }
}

@Composable
private fun ChooseLimitContent(
    state: TargetSizeWorkflowState.ChooseLimit,
    onPresetSelected: (TargetSizePreset) -> Unit,
    onCustomSelected: () -> Unit,
    onCustomTextChanged: (String) -> Unit,
    onCustomUnitChanged: (TargetSizeCustomUnit) -> Unit,
    onPrepare: () -> Unit,
) {
    val selection = state.selection
    val target = selection.targetBytes
    Column(
        modifier = Modifier
            .widthIn(max = PageHarborLayout.homeContentMaxWidth)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(PageHarborLayout.compactScreenHorizontalPadding),
        verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.medium),
    ) {
        Text(
            text = stringResource(R.string.fit_limit_workspace_description),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SourceSummary(state.input.summary)
        Text(
            modifier = Modifier.semantics { heading() },
            text = stringResource(R.string.fit_limit_maximum_heading),
            style = MaterialTheme.typography.titleMedium,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(PageHarborSpacing.small),
        ) {
            TargetSizePreset.entries.forEach { preset ->
                FilterChip(
                    selected = selection == TargetSizeLimitSelection.Preset(preset),
                    onClick = { onPresetSelected(preset) },
                    label = { Text(formatTargetSize(preset.bytes)) },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
            FilterChip(
                selected = selection is TargetSizeLimitSelection.Custom,
                onClick = onCustomSelected,
                label = { Text(stringResource(R.string.fit_limit_custom)) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
        if (selection is TargetSizeLimitSelection.Custom) {
            CustomLimitInput(selection, onCustomTextChanged, onCustomUnitChanged)
        }
        if (target != null && isObviouslyUnrealisticTarget(target, state.input.summary.pageCount)) {
            NeutralNotice(
                stringResource(
                    R.string.fit_limit_unrealistic_warning,
                    state.input.summary.pageCount,
                ),
            )
        }
        Text(
            text = stringResource(R.string.fit_limit_automatic_quality),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.fit_limit_original_unchanged),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Button(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            enabled = target != null,
            onClick = onPrepare,
        ) {
            Text(stringResource(R.string.fit_limit_prepare))
        }
        Spacer(Modifier.height(PageHarborSpacing.large))
    }
}

@Composable
private fun CustomLimitInput(
    selection: TargetSizeLimitSelection.Custom,
    onTextChanged: (String) -> Unit,
    onUnitChanged: (TargetSizeCustomUnit) -> Unit,
) {
    val error = (selection.validation as? TargetSizeCustomValidation.Invalid)?.reason
    Column(verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.small)) {
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = selection.text,
            onValueChange = onTextChanged,
            label = { Text(stringResource(R.string.fit_limit_custom_value)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            isError = error != null && error != TargetSizeCustomError.EMPTY,
            supportingText = error?.let { reason ->
                { Text(stringResource(customErrorResource(reason))) }
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(PageHarborSpacing.small)) {
            TargetSizeCustomUnit.entries.forEach { unit ->
                FilterChip(
                    selected = selection.unit == unit,
                    onClick = { onUnitChanged(unit) },
                    label = { Text(unit.name) },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
    }
}

@Composable
private fun SourceSummary(summary: org.synapseworks.pageharbor.document.targetsize.TargetSizeSourceSummary) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(PageHarborSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.extraSmall),
        ) {
            Text(
                modifier = Modifier.semantics { heading() },
                text = stringResource(R.string.fit_limit_document_heading),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(summary.title, style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.fit_limit_pages, summary.pageCount))
            MetricRow(
                stringResource(R.string.fit_limit_source_size),
                summary.sourceBytes?.let(::formatDecimalBytes)
                    ?: stringResource(R.string.fit_limit_source_size_unknown),
            )
            MetricRow(
                stringResource(R.string.fit_limit_support_status),
                stringResource(R.string.fit_limit_ready_to_check),
            )
        }
    }
}

@Composable
private fun PreparingContent(state: TargetSizeWorkflowState.Preparing, onCancel: () -> Unit) {
    Column(
        modifier = Modifier
            .widthIn(max = PageHarborLayout.homeContentMaxWidth)
            .fillMaxWidth()
            .padding(PageHarborLayout.compactScreenHorizontalPadding)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.medium),
    ) {
        CircularProgressIndicator()
        Text(
            modifier = Modifier.semantics { heading() },
            text = stringResource(R.string.fit_limit_preparing),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(stringResource(progressText(state.progress)))
        Text(formatTargetSize(state.targetBytes), style = MaterialTheme.typography.titleMedium)
        OutlinedButton(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            onClick = onCancel,
        ) {
            Text(stringResource(R.string.fit_limit_cancel))
        }
    }
}

@Composable
private fun PreparedContent(
    state: TargetSizeWorkflowState.Prepared,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onChangeLimit: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val expanded = maxWidth >= 600.dp
        if (expanded) {
            Row(
                modifier = Modifier
                    .widthIn(max = PageHarborLayout.expandedContentMaxWidth)
                    .fillMaxSize()
                    .padding(PageHarborLayout.mediumScreenHorizontalPadding),
                horizontalArrangement = Arrangement.spacedBy(PageHarborSpacing.large),
            ) {
                PreparedPdfPreview(
                    state.prepared,
                    Modifier.weight(1.2f).fillMaxSize(),
                )
                PreparedDetails(
                    state,
                    onSave,
                    onShare,
                    onChangeLimit,
                    Modifier.weight(0.8f).verticalScroll(rememberScrollState()),
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(PageHarborLayout.compactScreenHorizontalPadding),
                verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.medium),
            ) {
                PreparedPdfPreview(
                    state.prepared,
                    Modifier.fillMaxWidth().heightIn(min = 280.dp, max = 420.dp),
                )
                PreparedDetails(state, onSave, onShare, onChangeLimit)
            }
        }
    }
}

@Composable
private fun PreparedDetails(
    state: TargetSizeWorkflowState.Prepared,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onChangeLimit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val source = state.input.summary.sourceBytes
    val result = state.prepared.finalBytes
    val reduction = reductionPercent(source, result)
    val saving = state.saveStatus == TargetSizeSaveStatus.Saving
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.medium)) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(
                modifier = Modifier.padding(PageHarborSpacing.medium),
                verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.small),
            ) {
                MetricRow(
                    stringResource(R.string.fit_limit_target),
                    formatTargetSize(state.requestedTargetBytes),
                )
                MetricRow(stringResource(R.string.fit_limit_result), formatDecimalBytes(result))
                if (source != null) {
                    MetricRow(
                        stringResource(R.string.fit_limit_reduction),
                        stringResource(
                            R.string.fit_limit_reduction_value,
                            formatDecimalBytes(source),
                            formatDecimalBytes(result),
                        ),
                    )
                }
                reduction?.let {
                    Text(
                        stringResource(R.string.fit_limit_percent_smaller, it),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                MetricRow(
                    stringResource(R.string.fit_limit_pages_label),
                    state.prepared.pageCount.toString(),
                )
                MetricRow(
                    stringResource(R.string.fit_limit_status),
                    stringResource(R.string.fit_limit_ready_to_save),
                )
            }
        }
        if (state.reusedOriginalQuality) {
            NeutralNotice(
                stringResource(
                    R.string.fit_limit_already_under,
                    formatTargetSize(state.requestedTargetBytes),
                ),
            )
        } else if (state.prepared.plan.pages.any(TargetSizeQualitySearch::isAtFloor)) {
            NeutralNotice(stringResource(R.string.fit_limit_quality_warning))
        }
        when (state.searchableTextStatus) {
            TargetSizeSearchableTextStatus.PRESERVED ->
                Text(stringResource(R.string.fit_limit_searchable_preserved))
            TargetSizeSearchableTextStatus.PARTIALLY_PRESERVED ->
                NeutralNotice(stringResource(R.string.fit_limit_searchable_partial))
            TargetSizeSearchableTextStatus.NOT_PRESERVED ->
                NeutralNotice(stringResource(R.string.fit_limit_searchable_lost))
            TargetSizeSearchableTextStatus.NOT_PRESENT -> Unit
        }
        SaveStatus(state.saveStatus)
        if (state.shareFailed) NeutralNotice(stringResource(R.string.fit_limit_share_unavailable))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(PageHarborSpacing.small),
        ) {
            Button(
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                enabled = !saving,
                onClick = onSave,
            ) {
                Icon(Icons.Default.Save, contentDescription = null)
                Text(stringResource(R.string.fit_limit_save))
            }
            OutlinedButton(
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                enabled = !saving,
                onClick = onShare,
            ) {
                Icon(Icons.Default.Share, contentDescription = null)
                Text(stringResource(R.string.fit_limit_share))
            }
        }
        TextButton(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            enabled = !saving,
            onClick = onChangeLimit,
        ) {
            Text(stringResource(R.string.fit_limit_change))
        }
        Spacer(Modifier.height(PageHarborSpacing.large))
    }
}

@Composable
private fun SaveStatus(status: TargetSizeSaveStatus) {
    when (status) {
        TargetSizeSaveStatus.Idle,
        TargetSizeSaveStatus.ChoosingDestination,
        -> Unit
        TargetSizeSaveStatus.Saving -> Text(
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            text = stringResource(R.string.fit_limit_saving),
        )
        is TargetSizeSaveStatus.Saved -> Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = MaterialTheme.shapes.medium,
        ) {
            Column(Modifier.padding(PageHarborSpacing.small)) {
                Text(stringResource(R.string.fit_limit_saved), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.fit_limit_saved_size, formatDecimalBytes(status.bytes)))
            }
        }
        is TargetSizeSaveStatus.Failed -> NeutralNotice(
            stringResource(
                when (status.reason) {
                    TargetSizeDestinationError.PREPARED_RESULT_UNAVAILABLE,
                    TargetSizeDestinationError.WRITE_FAILED,
                    -> R.string.fit_limit_save_failed
                    TargetSizeDestinationError.DESTINATION_UNAVAILABLE ->
                        R.string.fit_limit_save_destination_unavailable
                    TargetSizeDestinationError.VALIDATION_FAILED ->
                        R.string.fit_limit_save_validation_failed
                },
            ),
        )
    }
}

@Composable
private fun ImpossibleContent(
    state: TargetSizeWorkflowState.ImpossibleTarget,
    onUseAlternative: () -> Unit,
    onChangeLimit: () -> Unit,
) {
    Column(
        modifier = Modifier
            .widthIn(max = PageHarborLayout.homeContentMaxWidth)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(PageHarborLayout.compactScreenHorizontalPadding),
        verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.medium),
    ) {
        Text(
            modifier = Modifier.semantics { heading() },
            text = stringResource(R.string.fit_limit_impossible_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            stringResource(
                R.string.fit_limit_impossible_message,
                formatTargetSize(state.requestedTargetBytes),
            ),
        )
        MetricRow(
            stringResource(R.string.fit_limit_smallest_recommended),
            formatDecimalBytes(state.smallestRecommendedBytes),
        )
        if (!state.bytesAreMeasured) Text(
            stringResource(R.string.fit_limit_estimated),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            enabled = state.smallestRecommended != null,
            onClick = onUseAlternative,
        ) {
            Text(
                stringResource(
                    R.string.fit_limit_use_size,
                    formatDecimalBytes(state.smallestRecommendedBytes),
                ),
            )
        }
        OutlinedButton(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            onClick = onChangeLimit,
        ) {
            Text(stringResource(R.string.fit_limit_choose_another))
        }
    }
}

@Composable
private fun FailedContent(
    state: TargetSizeWorkflowState.Failed,
    onRetry: () -> Unit,
    onChangeLimit: () -> Unit,
) {
    Column(
        modifier = Modifier
            .widthIn(max = PageHarborLayout.homeContentMaxWidth)
            .fillMaxWidth()
            .padding(PageHarborLayout.compactScreenHorizontalPadding),
        verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.medium),
    ) {
        Text(
            modifier = Modifier.semantics { heading() },
            text = stringResource(R.string.fit_limit_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(stringResource(failureText(state.reason)))
        if (state.input != null) {
            Button(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                onClick = onRetry,
            ) { Text(stringResource(R.string.fit_limit_retry)) }
            TextButton(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                onClick = onChangeLimit,
            ) { Text(stringResource(R.string.fit_limit_choose_another)) }
        }
    }
}

@Composable
private fun PreparedPdfPreview(prepared: TargetSizePreparedPdf, modifier: Modifier = Modifier) {
    var pageIndex by rememberSaveable(prepared.finalBytes, prepared.pageCount) { mutableIntStateOf(0) }
    var scale by remember(prepared.finalBytes, pageIndex) { mutableFloatStateOf(1f) }
    var offsetX by remember(prepared.finalBytes, pageIndex) { mutableFloatStateOf(0f) }
    var offsetY by remember(prepared.finalBytes, pageIndex) { mutableFloatStateOf(0f) }
    LaunchedEffect(prepared.pageCount) {
        pageIndex = pageIndex.coerceIn(0, prepared.pageCount - 1)
    }
    val preview by rememberManagedDocumentPreview(
        requestKey = Triple(prepared.finalBytes, prepared.pageCount, pageIndex),
        decode = { renderPreparedPage(prepared.file, pageIndex) },
    )
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.small)) {
        val description = stringResource(
            R.string.fit_limit_preview_description,
            pageIndex + 1,
            prepared.pageCount,
        )
        Surface(
            modifier = Modifier
                .weight(1f, fill = false)
                .fillMaxWidth()
                .heightIn(min = 260.dp, max = 560.dp)
                .semantics { contentDescription = description },
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.large,
        ) {
            Box(contentAlignment = Alignment.Center) {
                when (val current = preview) {
                    ManagedDocumentPreviewState.Loading ->
                        Text(stringResource(R.string.fit_limit_preview_loading))
                    ManagedDocumentPreviewState.Unavailable ->
                        Text(stringResource(R.string.fit_limit_preview_unavailable))
                    is ManagedDocumentPreviewState.Ready -> Image(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(prepared.finalBytes, pageIndex) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    scale = (scale * zoom).coerceIn(1f, 4f)
                                    offsetX = if (scale == 1f) 0f else offsetX + pan.x
                                    offsetY = if (scale == 1f) 0f else offsetY + pan.y
                                }
                            }
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offsetX,
                                translationY = offsetY,
                            ),
                        bitmap = current.owner.bitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                enabled = pageIndex > 0,
                onClick = { pageIndex-- },
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.fit_limit_previous_page),
                )
            }
            Text(stringResource(R.string.fit_limit_page_indicator, pageIndex + 1, prepared.pageCount))
            IconButton(
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                enabled = pageIndex < prepared.pageCount - 1,
                onClick = { pageIndex++ },
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = stringResource(R.string.fit_limit_next_page),
                )
            }
        }
    }
}

private fun renderPreparedPage(file: File, pageIndex: Int): android.graphics.Bitmap? = try {
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            if (pageIndex !in 0 until renderer.pageCount) return null
            renderer.openPage(pageIndex).use { page ->
                val scale = minOf(
                    PREVIEW_MAX_EDGE.toFloat() / maxOf(page.width, page.height).toFloat(),
                    1f,
                )
                val width = (page.width * scale).roundToInt().coerceAtLeast(1)
                val height = (page.height * scale).roundToInt().coerceAtLeast(1)
                createBitmap(width, height).also { bitmap ->
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        }
    }
} catch (_: Exception) {
    null
} catch (_: OutOfMemoryError) {
    null
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun NeutralNotice(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Text(text, Modifier.padding(PageHarborSpacing.small))
    }
}

private fun customErrorResource(error: TargetSizeCustomError): Int = when (error) {
    TargetSizeCustomError.EMPTY -> R.string.fit_limit_custom_empty
    TargetSizeCustomError.INVALID_NUMBER -> R.string.fit_limit_custom_invalid
    TargetSizeCustomError.TOO_SMALL -> R.string.fit_limit_custom_too_small
    TargetSizeCustomError.TOO_LARGE -> R.string.fit_limit_custom_too_large
}

private fun progressText(progress: TargetSizeProgress): Int = when (progress) {
    TargetSizeProgress.PLANNING -> R.string.fit_limit_progress_planning
    TargetSizeProgress.CALIBRATING -> R.string.fit_limit_progress_calibrating
    TargetSizeProgress.ENCODING -> R.string.fit_limit_progress_encoding
    TargetSizeProgress.VALIDATING -> R.string.fit_limit_progress_validating
}

private fun failureText(failure: TargetSizePdfFailure): Int = when (failure) {
    TargetSizePdfFailure.ENCRYPTED_PDF -> R.string.fit_limit_error_encrypted
    TargetSizePdfFailure.UNSUPPORTED_INPUT -> R.string.fit_limit_error_complex
    TargetSizePdfFailure.MALFORMED_SOURCE,
    TargetSizePdfFailure.INVALID_SOURCE,
    TargetSizePdfFailure.SOURCE_READ_FAILED,
    -> R.string.fit_limit_error_read
    TargetSizePdfFailure.INSUFFICIENT_TEMPORARY_STORAGE -> R.string.fit_limit_error_storage
    TargetSizePdfFailure.OUTPUT_VALIDATION_FAILED -> R.string.fit_limit_error_validation
    TargetSizePdfFailure.ENCODE_FAILED,
    TargetSizePdfFailure.CANCELED,
    -> R.string.fit_limit_error_encode
    TargetSizePdfFailure.PROCESS_INTERRUPTED -> R.string.fit_limit_error_process_interrupted
}

private const val PREVIEW_MAX_EDGE = 1_600
