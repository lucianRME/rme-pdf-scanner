package org.synapseworks.pageharbor.ui.sharesafe

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import androidx.core.graphics.createBitmap
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt
import org.synapseworks.pageharbor.R
import org.synapseworks.pageharbor.document.redaction.RedactionBounds
import org.synapseworks.pageharbor.document.redaction.RedactionRegion
import org.synapseworks.pageharbor.document.redaction.RedactionRegionId
import org.synapseworks.pageharbor.document.sharesafe.ShareSafeConfigureStep
import org.synapseworks.pageharbor.document.sharesafe.ShareSafeCopyWorkflowState
import org.synapseworks.pageharbor.document.sharesafe.ShareSafeFailure
import org.synapseworks.pageharbor.document.sharesafe.ShareSafeDateValidator
import org.synapseworks.pageharbor.document.sharesafe.ShareSafePipelineStage
import org.synapseworks.pageharbor.document.sharesafe.ShareSafePreparedPdf
import org.synapseworks.pageharbor.document.sharesafe.ShareSafePurposeValidation
import org.synapseworks.pageharbor.document.sharesafe.ShareSafePurposeError
import org.synapseworks.pageharbor.document.sharesafe.ShareSafePurposeValidator
import org.synapseworks.pageharbor.document.sharesafe.ShareSafeRecipe
import org.synapseworks.pageharbor.document.sharesafe.ShareSafeSaveStatus
import org.synapseworks.pageharbor.document.sharesafe.ShareSafeSourceKind
import org.synapseworks.pageharbor.document.sharesafe.ShareSafeWatermarkConfig
import org.synapseworks.pageharbor.document.sharesafe.ShareSafeWatermarkGeometry
import org.synapseworks.pageharbor.document.sharesafe.ShareSafeWatermarkOpacity
import org.synapseworks.pageharbor.document.sharesafe.ShareSafeWatermarkPlacement
import org.synapseworks.pageharbor.document.targetsize.TargetSizeBytes
import org.synapseworks.pageharbor.document.targetsize.TargetSizeCustomLimitParser
import org.synapseworks.pageharbor.document.targetsize.TargetSizeCustomUnit
import org.synapseworks.pageharbor.document.targetsize.TargetSizeCustomValidation
import org.synapseworks.pageharbor.document.targetsize.formatDecimalBytes
import org.synapseworks.pageharbor.document.targetsize.formatTargetSize
import org.synapseworks.pageharbor.ui.home.ManagedDocumentPreviewState
import org.synapseworks.pageharbor.ui.home.ZoomableDocumentPreview
import org.synapseworks.pageharbor.ui.home.rememberManagedDocumentPreview
import org.synapseworks.pageharbor.ui.theme.PageHarborLayout
import org.synapseworks.pageharbor.ui.theme.RmeSecondaryButton
import org.synapseworks.pageharbor.ui.theme.RmePrimaryButton
import org.synapseworks.pageharbor.ui.theme.RmeTonalChoice
import org.synapseworks.pageharbor.ui.theme.rmeColors
import org.synapseworks.pageharbor.ui.theme.PageHarborSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareSafeCopyScreen(
    state: ShareSafeCopyWorkflowState,
    onBack: () -> Unit,
    onRecipe: (ShareSafeRecipe) -> Unit,
    onPage: (Int) -> Unit,
    onAddRedaction: () -> Unit,
    onSelectRedaction: (RedactionRegionId) -> Unit,
    onUpdateRedaction: (RedactionBounds) -> Unit,
    onDeleteRedaction: () -> Unit,
    onWatermarkEnabled: (Boolean) -> Unit,
    onPurpose: (String) -> Unit,
    onDateEnabled: (Boolean) -> Unit,
    onDate: (String) -> Unit,
    onPlacement: (ShareSafeWatermarkPlacement) -> Unit,
    onOpacity: (ShareSafeWatermarkOpacity) -> Unit,
    onTargetEnabled: (Boolean) -> Unit,
    onTargetBytes: (Long) -> Unit,
    onNext: () -> Unit,
    onPrepare: () -> Unit,
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
                        stringResource(R.string.share_safe_title),
                        Modifier.semantics { heading() },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(R.string.share_safe_close),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(
            Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            when (state) {
                ShareSafeCopyWorkflowState.Hidden,
                ShareSafeCopyWorkflowState.CardCopyHandoff,
                -> Unit
                is ShareSafeCopyWorkflowState.ChooseRecipe -> RecipeContent(state, onRecipe)
                is ShareSafeCopyWorkflowState.Configure -> ConfigureContent(
                    state = state,
                    onPage = onPage,
                    onAddRedaction = onAddRedaction,
                    onSelectRedaction = onSelectRedaction,
                    onUpdateRedaction = onUpdateRedaction,
                    onDeleteRedaction = onDeleteRedaction,
                    onWatermarkEnabled = onWatermarkEnabled,
                    onPurpose = onPurpose,
                    onDateEnabled = onDateEnabled,
                    onDate = onDate,
                    onPlacement = onPlacement,
                    onOpacity = onOpacity,
                    onTargetEnabled = onTargetEnabled,
                    onTargetBytes = onTargetBytes,
                    onNext = onNext,
                    onPrepare = onPrepare,
                )
                is ShareSafeCopyWorkflowState.Preparing -> PreparingContent(state.stage)
                is ShareSafeCopyWorkflowState.Prepared -> PreparedContent(
                    state,
                    onSave,
                    onShare,
                    onBack,
                )
                is ShareSafeCopyWorkflowState.Failed -> FailedContent(state, onRetry)
            }
        }
    }
}

@Composable
private fun RecipeContent(
    state: ShareSafeCopyWorkflowState.ChooseRecipe,
    onRecipe: (ShareSafeRecipe) -> Unit,
) {
    Column(
        Modifier.widthIn(max = PageHarborLayout.homeContentMaxWidth).fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(PageHarborLayout.compactScreenHorizontalPadding),
        verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.medium),
    ) {
        Text(stringResource(R.string.share_safe_description), style = MaterialTheme.typography.bodyLarge)
        Text(
            stringResource(R.string.share_safe_choose_recipe),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        RecipeCard(
            R.string.share_safe_portal,
            R.string.share_safe_portal_description,
            state.documentAvailable,
        ) { onRecipe(ShareSafeRecipe.UPLOAD_TO_PORTAL) }
        RecipeCard(R.string.share_safe_id, R.string.share_safe_id_description, true) {
            onRecipe(ShareSafeRecipe.SHARE_ID_COPY)
        }
        RecipeCard(
            R.string.share_safe_email,
            R.string.share_safe_email_description,
            state.documentAvailable,
        ) { onRecipe(ShareSafeRecipe.EMAIL_DOCUMENT) }
        RecipeCard(
            R.string.share_safe_archive,
            R.string.share_safe_archive_description,
            state.documentAvailable,
        ) { onRecipe(ShareSafeRecipe.ARCHIVE_DOCUMENT) }
        if (!state.documentAvailable) {
            Text(
                stringResource(R.string.share_safe_tool_unavailable),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RecipeCard(title: Int, description: Int, enabled: Boolean, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), enabled = enabled, onClick = onClick) {
        Column(
            Modifier.padding(PageHarborSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.extraSmall),
        ) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(description), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ConfigureContent(
    state: ShareSafeCopyWorkflowState.Configure,
    onPage: (Int) -> Unit,
    onAddRedaction: () -> Unit,
    onSelectRedaction: (RedactionRegionId) -> Unit,
    onUpdateRedaction: (RedactionBounds) -> Unit,
    onDeleteRedaction: () -> Unit,
    onWatermarkEnabled: (Boolean) -> Unit,
    onPurpose: (String) -> Unit,
    onDateEnabled: (Boolean) -> Unit,
    onDate: (String) -> Unit,
    onPlacement: (ShareSafeWatermarkPlacement) -> Unit,
    onOpacity: (ShareSafeWatermarkOpacity) -> Unit,
    onTargetEnabled: (Boolean) -> Unit,
    onTargetBytes: (Long) -> Unit,
    onNext: () -> Unit,
    onPrepare: () -> Unit,
) {
    Column(
        Modifier
            .widthIn(max = PageHarborLayout.homeContentMaxWidth)
            .fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(PageHarborLayout.compactScreenHorizontalPadding),
            verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.medium),
        ) {
            when (state.step) {
                ShareSafeConfigureStep.REDACT -> RedactionStep(
                    state,
                    onPage,
                    onAddRedaction,
                    onSelectRedaction,
                    onUpdateRedaction,
                    onDeleteRedaction,
                )
                ShareSafeConfigureStep.PURPOSE -> PurposeStep(
                    state = state,
                    onEnabled = onWatermarkEnabled,
                    onPurpose = onPurpose,
                    onDateEnabled = onDateEnabled,
                    onDate = onDate,
                    onPlacement = onPlacement,
                    onOpacity = onOpacity,
                )
                ShareSafeConfigureStep.SIZE -> SizeStep(
                    state,
                    onTargetEnabled,
                    onTargetBytes,
                    onNext,
                )
                ShareSafeConfigureStep.REVIEW -> ReviewStep(state)
            }
        }
        if (state.step != ShareSafeConfigureStep.SIZE) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 3.dp,
            ) {
                RmePrimaryButton(
                    onClick = if (state.step == ShareSafeConfigureStep.REVIEW) onPrepare else onNext,
                    modifier = Modifier
                        .padding(PageHarborSpacing.medium)
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    enabled = state.step != ShareSafeConfigureStep.PURPOSE || state.watermark.isValid,
                ) {
                    Text(
                        stringResource(
                            if (state.step == ShareSafeConfigureStep.REVIEW) {
                                R.string.share_safe_prepare
                            } else {
                                R.string.share_safe_next
                            },
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun RedactionStep(
    state: ShareSafeCopyWorkflowState.Configure,
    onPage: (Int) -> Unit,
    onAdd: () -> Unit,
    onSelect: (RedactionRegionId) -> Unit,
    onUpdate: (RedactionBounds) -> Unit,
    onDelete: () -> Unit,
) {
    Text(
        stringResource(R.string.share_safe_redact_heading),
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.semantics { heading() },
    )
    Text(stringResource(R.string.share_safe_redact_description))
    state.selectedPagePreviewUri?.let { uri ->
        RedactionPagePreview(uri, state.redactions, state.selectedRedactionId)
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(enabled = state.selectedPageIndex > 0, onClick = {
            onPage(state.selectedPageIndex - 1)
        }) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                stringResource(R.string.share_safe_previous_page),
            )
        }
        Text(stringResource(R.string.share_safe_page_indicator, state.selectedPageIndex + 1, state.pageCount))
        IconButton(enabled = state.selectedPageIndex < state.pageCount - 1, onClick = {
            onPage(state.selectedPageIndex + 1)
        }) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                stringResource(R.string.share_safe_next_page),
            )
        }
    }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(PageHarborSpacing.small),
    ) {
        state.redactions.forEachIndexed { index, region ->
            RmeTonalChoice(
                selected = region.id == state.selectedRedactionId,
                onClick = { onSelect(region.id) },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("${index + 1}") }
        }
    }
    RmePrimaryButton(onClick = onAdd, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Icon(Icons.Default.Add, null)
        Text(stringResource(R.string.share_safe_add_redaction))
    }
    val selected = state.redactions.firstOrNull { it.id == state.selectedRedactionId }
    if (selected != null) {
        RedactionAdjustmentControls(selected.bounds, onUpdate)
        RmeSecondaryButton(
            onClick = onDelete,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Icon(Icons.Default.Delete, null)
            Text(stringResource(R.string.share_safe_delete_redaction))
        }
    }
}

@Composable
private fun RedactionAdjustmentControls(bounds: RedactionBounds, onUpdate: (RedactionBounds) -> Unit) {
    val movements = listOf(
        "←" to (-0.025f to 0f),
        "↑" to (0f to -0.025f),
        "↓" to (0f to 0.025f),
        "→" to (0.025f to 0f),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        movements.forEach { (label, delta) ->
            RmeSecondaryButton(
                onClick = { shifted(bounds, delta.first, delta.second)?.let(onUpdate) },
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
            ) { Text(label) }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        RmeSecondaryButton(
            onClick = { resized(bounds, -0.025f)?.let(onUpdate) },
            modifier = Modifier.heightIn(min = 48.dp),
        ) { Text("−") }
        RmeSecondaryButton(
            onClick = { resized(bounds, 0.025f)?.let(onUpdate) },
            modifier = Modifier.heightIn(min = 48.dp),
        ) { Text("+") }
    }
}

@Composable
private fun RedactionPagePreview(
    uri: Uri,
    redactions: List<RedactionRegion>,
    selected: RedactionRegionId?,
) {
    val resolver = LocalContext.current.contentResolver
    val preview by rememberManagedDocumentPreview(
        requestKey = uri,
        decode = { decodeBoundedPreview(resolver, uri) },
    )
    Surface(
        Modifier.fillMaxWidth().heightIn(min = 260.dp, max = 520.dp),
        color = MaterialTheme.rmeColors.previewBackground,
        shape = MaterialTheme.shapes.large,
    ) {
        Box(contentAlignment = Alignment.Center) {
            when (val current = preview) {
                ManagedDocumentPreviewState.Loading -> CircularProgressIndicator()
                ManagedDocumentPreviewState.Unavailable -> Text(
                    stringResource(R.string.scan_preview_unavailable),
                )
                is ManagedDocumentPreviewState.Ready -> ZoomableDocumentPreview(
                    bitmap = current.owner.bitmap.asImageBitmap(),
                    resetKey = uri,
                    description = stringResource(R.string.share_safe_watermark_preview),
                    modifier = Modifier.fillMaxSize().aspectRatio(
                        current.owner.bitmap.width.toFloat() / current.owner.bitmap.height,
                        matchHeightConstraintsFirst = true,
                    ),
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        redactions.forEach { region ->
                            drawRect(
                                color = if (region.id == selected) {
                                    androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.9f)
                                } else {
                                    androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.72f)
                                },
                                topLeft = Offset(region.bounds.left * size.width, region.bounds.top * size.height),
                                size = Size(region.bounds.width * size.width, region.bounds.height * size.height),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PurposeStep(
    state: ShareSafeCopyWorkflowState.Configure,
    onEnabled: (Boolean) -> Unit,
    onPurpose: (String) -> Unit,
    onDateEnabled: (Boolean) -> Unit,
    onDate: (String) -> Unit,
    onPlacement: (ShareSafeWatermarkPlacement) -> Unit,
    onOpacity: (ShareSafeWatermarkOpacity) -> Unit,
) {
    val config = state.watermark
    Text(
        stringResource(R.string.share_safe_purpose_heading),
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.semantics { heading() },
    )
    ToggleRow(stringResource(R.string.share_safe_watermark_toggle), config.enabled, onEnabled)
    if (!config.enabled) return
    val validation = ShareSafePurposeValidator.validate(config.purposeText)
    OutlinedTextField(
        value = config.purposeText,
        onValueChange = onPurpose,
        label = { Text(stringResource(R.string.share_safe_purpose_label)) },
        supportingText = {
            Text(
                if (validation is ShareSafePurposeValidation.Invalid && config.purposeText.isNotEmpty()) {
                    stringResource(
                        when (validation.reason) {
                            ShareSafePurposeError.EMPTY -> R.string.share_safe_purpose_empty
                            ShareSafePurposeError.TOO_LONG -> R.string.share_safe_purpose_too_long
                            ShareSafePurposeError.TOO_MANY_LINES -> R.string.share_safe_purpose_too_many_lines
                            ShareSafePurposeError.CONTROL_CHARACTER -> R.string.share_safe_purpose_invalid_character
                        },
                    )
                } else {
                    stringResource(R.string.share_safe_purpose_support)
                },
            )
        },
        isError = validation !is ShareSafePurposeValidation.Valid && config.purposeText.isNotEmpty(),
        modifier = Modifier.fillMaxWidth(),
        minLines = 1,
        maxLines = 3,
    )
    ToggleRow(stringResource(R.string.share_safe_date_toggle), config.includeDate, onDateEnabled)
    if (config.includeDate) {
        OutlinedTextField(
            value = config.dateText,
            onValueChange = onDate,
            label = { Text(stringResource(R.string.share_safe_date_label)) },
            supportingText = {
                Text(stringResource(
                    if (ShareSafeDateValidator.isValid(config.dateText)) {
                        R.string.share_safe_date_support
                    } else {
                        R.string.share_safe_date_invalid
                    },
                ))
            },
            isError = !ShareSafeDateValidator.isValid(config.dateText),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
    }
    ChoiceRow(
        stringResource(R.string.share_safe_placement),
        ShareSafeWatermarkPlacement.entries,
        config.placement,
        onPlacement,
    ) { placementLabel(it) }
    ChoiceRow(
        stringResource(R.string.share_safe_opacity),
        ShareSafeWatermarkOpacity.entries,
        config.opacity,
        onOpacity,
    ) { opacityLabel(it) }
    if (config.renderedText != null) {
        WatermarkPreview(state.selectedPagePreviewUri, config)
    }
}

@Composable
private fun SizeStep(
    state: ShareSafeCopyWorkflowState.Configure,
    onEnabled: (Boolean) -> Unit,
    onBytes: (Long) -> Unit,
    onNext: () -> Unit,
) {
    val presetBytes = listOf(
        TargetSizeBytes.KB_200,
        TargetSizeBytes.KB_500,
        TargetSizeBytes.MB_1,
        TargetSizeBytes.MB_2,
    )
    var customSelected by rememberSaveable(state.recipe) {
        mutableStateOf(state.targetSize.maximumBytes !in presetBytes)
    }
    var customText by rememberSaveable(state.recipe) { mutableStateOf("") }
    var customUnit by rememberSaveable(state.recipe) { mutableStateOf(TargetSizeCustomUnit.MB) }
    val customValidation = TargetSizeCustomLimitParser.parse(customText, customUnit)
    Text(
        stringResource(R.string.share_safe_size_heading),
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.semantics { heading() },
    )
    ToggleRow(stringResource(R.string.share_safe_size_toggle), state.targetSize.enabled, onEnabled)
    Text(stringResource(R.string.share_safe_size_support), color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (state.targetSize.enabled) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(PageHarborSpacing.small),
        ) {
            presetBytes.forEach { bytes ->
                RmeTonalChoice(
                    selected = !customSelected && state.targetSize.maximumBytes == bytes,
                    onClick = { customSelected = false; onBytes(bytes) },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(formatTargetSize(bytes)) }
            }
            RmeTonalChoice(
                selected = customSelected,
                onClick = { customSelected = true },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.fit_limit_custom)) }
        }
        if (customSelected) {
            OutlinedTextField(
                value = customText,
                onValueChange = { value ->
                    customText = value
                    (TargetSizeCustomLimitParser.parse(value, customUnit)
                        as? TargetSizeCustomValidation.Valid)?.let { onBytes(it.bytes) }
                },
                label = { Text(stringResource(R.string.fit_limit_custom_value)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                isError = customText.isNotEmpty() &&
                    customValidation !is TargetSizeCustomValidation.Valid,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(PageHarborSpacing.small),
            ) {
                TargetSizeCustomUnit.entries.forEach { unit ->
                    RmeTonalChoice(
                        selected = unit == customUnit,
                        onClick = {
                            customUnit = unit
                            (TargetSizeCustomLimitParser.parse(customText, unit)
                                as? TargetSizeCustomValidation.Valid)?.let { onBytes(it.bytes) }
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(unit.name) }
                }
            }
        }
    }
    RmePrimaryButton(
        onClick = onNext,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        enabled = !state.targetSize.enabled || !customSelected ||
            customValidation is TargetSizeCustomValidation.Valid,
    ) { Text(stringResource(R.string.share_safe_next)) }
}

@Composable
private fun ReviewStep(state: ShareSafeCopyWorkflowState.Configure) {
    Text(
        stringResource(R.string.share_safe_review_heading),
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.semantics { heading() },
    )
    Text(state.title, style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.share_safe_redaction_summary, state.redactions.size))
    Text(
        state.watermark.renderedText ?: stringResource(R.string.share_safe_no_watermark),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        if (state.targetSize.enabled) formatTargetSize(state.targetSize.maximumBytes)
        else stringResource(R.string.share_safe_no_size_limit),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PreparingContent(stage: ShareSafePipelineStage) {
    val message = when (stage) {
        ShareSafePipelineStage.SANITIZING -> R.string.share_safe_preparing_sanitizing
        ShareSafePipelineStage.WATERMARKING -> R.string.share_safe_preparing_watermarking
        ShareSafePipelineStage.COMPRESSING -> R.string.share_safe_preparing_compressing
        ShareSafePipelineStage.FINAL_VERIFYING -> R.string.share_safe_preparing_verifying
    }
    Column(
        Modifier.padding(PageHarborSpacing.extraLarge).semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.medium),
    ) {
        CircularProgressIndicator()
        Text(stringResource(message), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun PreparedContent(
    state: ShareSafeCopyWorkflowState.Prepared,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onChange: () -> Unit,
) {
    val saving = state.saveStatus == ShareSafeSaveStatus.Saving
    Column(
        Modifier.widthIn(max = PageHarborLayout.homeContentMaxWidth).fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(PageHarborLayout.compactScreenHorizontalPadding),
        verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.medium),
    ) {
        FinalPdfPreview(state.prepared)
        Text(stringResource(R.string.share_safe_ready), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(
            R.string.share_safe_result_size,
            formatDecimalBytes(state.prepared.bytes),
            state.prepared.pageCount,
        ))
        when (val save = state.saveStatus) {
            ShareSafeSaveStatus.Idle, ShareSafeSaveStatus.ChoosingDestination -> Unit
            ShareSafeSaveStatus.Saving -> Text(stringResource(R.string.share_safe_saving))
            is ShareSafeSaveStatus.Saved -> Text(
                stringResource(R.string.share_safe_saved, formatDecimalBytes(save.bytes)),
            )
            is ShareSafeSaveStatus.Failed -> Text(
                stringResource(R.string.share_safe_failed),
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (state.shareFailed) Text(
            stringResource(R.string.share_safe_share_failed),
            color = MaterialTheme.colorScheme.error,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(PageHarborSpacing.small)) {
            RmePrimaryButton(
                onClick = onSave,
                enabled = !saving,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) { Icon(Icons.Default.Save, null); Text(stringResource(R.string.share_safe_save)) }
            RmeSecondaryButton(
                onClick = onShare,
                enabled = !saving,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) { Icon(Icons.Default.Share, null); Text(stringResource(R.string.share_safe_share)) }
        }
        TextButton(
            onClick = onChange,
            enabled = !saving,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.share_safe_change_settings)) }
    }
}

@Composable
private fun FailedContent(state: ShareSafeCopyWorkflowState.Failed, onRetry: () -> Unit) {
    Column(
        Modifier.widthIn(max = PageHarborLayout.homeContentMaxWidth).fillMaxWidth()
            .padding(PageHarborLayout.compactScreenHorizontalPadding),
        verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.medium),
    ) {
        Text(
            stringResource(
                if (state.reason == ShareSafeFailure.PROCESS_INTERRUPTED) {
                    R.string.share_safe_interrupted
                } else {
                    R.string.share_safe_failed
                },
            ),
            style = MaterialTheme.typography.titleMedium,
        )
        if (state.canRetry) RmePrimaryButton(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.share_safe_retry)) }
    }
}

@Composable
private fun <T> ChoiceRow(
    title: String,
    values: List<T>,
    selected: T,
    onSelected: (T) -> Unit,
    label: @Composable (T) -> String,
) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(PageHarborSpacing.small),
    ) {
        values.forEach { value ->
            RmeTonalChoice(
                selected = value == selected,
                onClick = { onSelected(value) },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(label(value)) }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun WatermarkPreview(uri: Uri?, config: ShareSafeWatermarkConfig) {
    val text = config.renderedText ?: return
    val description = stringResource(R.string.share_safe_watermark_preview)
    val resolver = LocalContext.current.contentResolver
    val preview by rememberManagedDocumentPreview(
        requestKey = uri,
        decode = { uri?.let { decodeBoundedPreview(resolver, it) } },
    )
    Surface(
        Modifier.fillMaxWidth().heightIn(min = 260.dp, max = 420.dp)
            .semantics { contentDescription = description },
        color = MaterialTheme.rmeColors.previewBackground,
        shape = MaterialTheme.shapes.large,
    ) {
        Box(contentAlignment = Alignment.Center) {
            when (val current = preview) {
                ManagedDocumentPreviewState.Loading -> CircularProgressIndicator()
                ManagedDocumentPreviewState.Unavailable -> Text(
                    stringResource(R.string.scan_preview_unavailable),
                )
                is ManagedDocumentPreviewState.Ready -> ZoomableDocumentPreview(
                    bitmap = current.owner.bitmap.asImageBitmap(),
                    resetKey = uri to config,
                    description = description,
                    exposeDescription = false,
                    modifier = Modifier.fillMaxSize().aspectRatio(
                        current.owner.bitmap.width.toFloat() / current.owner.bitmap.height,
                        matchHeightConstraintsFirst = true,
                    ),
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        val lines = text.lines()
                        ShareSafeWatermarkGeometry.stamps(config.placement).forEach { stamp ->
                            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                color = Color.BLACK
                                alpha = config.opacity.alpha
                                textAlign = Paint.Align.CENTER
                                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                                textSize = minOf(size.width, size.height) * stamp.textSizeFraction
                            }
                            val maximumWidth = size.width * stamp.maximumWidthFraction
                            val widest = lines.maxOf(paint::measureText).coerceAtLeast(1f)
                            if (widest > maximumWidth) paint.textSize *= maximumWidth / widest
                            val metrics = paint.fontMetrics
                            val lineHeight = (metrics.descent - metrics.ascent) * 1.08f
                            val firstBaseline = size.height * stamp.centerY -
                                lineHeight * lines.size / 2f - metrics.ascent
                            withTransform({
                                rotate(
                                    stamp.rotationDegrees,
                                    Offset(size.width * stamp.centerX, size.height * stamp.centerY),
                                )
                            }) {
                                lines.forEachIndexed { index, line ->
                                    drawContext.canvas.nativeCanvas.drawText(
                                        line,
                                        size.width * stamp.centerX,
                                        firstBaseline + index * lineHeight,
                                        paint,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FinalPdfPreview(prepared: ShareSafePreparedPdf) {
    var pageIndex by rememberSaveable(prepared.sha256) { mutableIntStateOf(0) }
    LaunchedEffect(prepared.pageCount) { pageIndex = pageIndex.coerceIn(0, prepared.pageCount - 1) }
    val preview by rememberManagedDocumentPreview(
        requestKey = prepared.sha256 to pageIndex,
        decode = { renderPdfPage(prepared.artifact.file, pageIndex) },
    )
    val description = stringResource(
        R.string.share_safe_preview_description,
        pageIndex + 1,
        prepared.pageCount,
    )
    Column(verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.small)) {
        Surface(
            Modifier.fillMaxWidth().heightIn(min = 320.dp, max = 560.dp)
                .semantics { contentDescription = description },
            color = MaterialTheme.rmeColors.previewBackground,
            shape = MaterialTheme.shapes.large,
        ) {
            Box(contentAlignment = Alignment.Center) {
                when (val current = preview) {
                    ManagedDocumentPreviewState.Loading -> Text(
                        stringResource(R.string.share_safe_preview_loading),
                    )
                    ManagedDocumentPreviewState.Unavailable -> Text(
                        stringResource(R.string.share_safe_preview_unavailable),
                    )
                    is ManagedDocumentPreviewState.Ready -> ZoomableDocumentPreview(
                        bitmap = current.owner.bitmap.asImageBitmap(),
                        resetKey = prepared.sha256 to pageIndex,
                        description = description,
                        exposeDescription = false,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(enabled = pageIndex > 0, onClick = { pageIndex-- }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.share_safe_previous_page))
            }
            Text(stringResource(R.string.share_safe_page_indicator, pageIndex + 1, prepared.pageCount))
            IconButton(enabled = pageIndex < prepared.pageCount - 1, onClick = { pageIndex++ }) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, stringResource(R.string.share_safe_next_page))
            }
        }
    }
}

@Composable
private fun placementLabel(value: ShareSafeWatermarkPlacement): String = stringResource(
    when (value) {
        ShareSafeWatermarkPlacement.DIAGONAL -> R.string.share_safe_placement_diagonal
        ShareSafeWatermarkPlacement.FOOTER -> R.string.share_safe_placement_footer
        ShareSafeWatermarkPlacement.REPEATED -> R.string.share_safe_placement_repeated
    },
)

@Composable
private fun opacityLabel(value: ShareSafeWatermarkOpacity): String = stringResource(
    when (value) {
        ShareSafeWatermarkOpacity.LIGHT -> R.string.share_safe_opacity_light
        ShareSafeWatermarkOpacity.NORMAL -> R.string.share_safe_opacity_normal
        ShareSafeWatermarkOpacity.STRONG -> R.string.share_safe_opacity_strong
    },
)

private fun shifted(bounds: RedactionBounds, dx: Float, dy: Float): RedactionBounds? {
    val safeDx = dx.coerceIn(-bounds.left, 1f - bounds.right)
    val safeDy = dy.coerceIn(-bounds.top, 1f - bounds.bottom)
    return RedactionBounds.clamped(
        bounds.left + safeDx,
        bounds.top + safeDy,
        bounds.right + safeDx,
        bounds.bottom + safeDy,
    )
}

private fun resized(bounds: RedactionBounds, delta: Float): RedactionBounds? =
    RedactionBounds.clamped(
        bounds.left - delta,
        bounds.top - delta,
        bounds.right + delta,
        bounds.bottom + delta,
    )

internal fun decodeBoundedPreview(
    contentResolver: ContentResolver,
    uri: Uri,
): Bitmap? = try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, info, _ ->
            var sample = 1
            while (max(info.size.width / sample, info.size.height / sample) > 1_400) sample *= 2
            decoder.setTargetSampleSize(sample)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } else {
        decodeBoundedLegacy(contentResolver, uri)
    }
} catch (_: Exception) {
    null
} catch (_: OutOfMemoryError) {
    null
}

private fun decodeBoundedLegacy(contentResolver: ContentResolver, uri: Uri): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (max(bounds.outWidth / sample, bounds.outHeight / sample) > 1_400) sample *= 2
    return contentResolver.openInputStream(uri)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}

private fun renderPdfPage(file: File, pageIndex: Int): Bitmap? = try {
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            if (pageIndex !in 0 until renderer.pageCount) return null
            renderer.openPage(pageIndex).use { page ->
                val scale = minOf(1_600f / max(page.width, page.height), 1f)
                createBitmap(
                    (page.width * scale).roundToInt().coerceAtLeast(1),
                    (page.height * scale).roundToInt().coerceAtLeast(1),
                ).also { bitmap ->
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
