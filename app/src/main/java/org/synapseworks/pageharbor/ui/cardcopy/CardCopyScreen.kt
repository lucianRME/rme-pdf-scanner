package org.synapseworks.pageharbor.ui.cardcopy

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import java.io.File
import kotlin.math.max
import org.synapseworks.pageharbor.R
import org.synapseworks.pageharbor.document.cardcopy.CardCopyAcquisition
import org.synapseworks.pageharbor.document.cardcopy.CardCopyAlignment
import org.synapseworks.pageharbor.document.cardcopy.CardCopyFailure
import org.synapseworks.pageharbor.document.cardcopy.CardCopyGeometry
import org.synapseworks.pageharbor.document.cardcopy.CardCopyGeometryEngine
import org.synapseworks.pageharbor.document.cardcopy.CardCopyLayout
import org.synapseworks.pageharbor.document.cardcopy.CardCopyPageSize
import org.synapseworks.pageharbor.document.cardcopy.CardCopyPreparedPdf
import org.synapseworks.pageharbor.document.cardcopy.CardCopySaveStatus
import org.synapseworks.pageharbor.document.cardcopy.CardCopySettings
import org.synapseworks.pageharbor.document.cardcopy.CardCopySide
import org.synapseworks.pageharbor.document.cardcopy.CardCopySideState
import org.synapseworks.pageharbor.document.cardcopy.CardCopySource
import org.synapseworks.pageharbor.document.cardcopy.CardCopySpacing
import org.synapseworks.pageharbor.document.cardcopy.CardCopyWorkflowState
import org.synapseworks.pageharbor.document.cardcopy.defaultRedactionBounds
import org.synapseworks.pageharbor.document.cardcopy.readySource
import org.synapseworks.pageharbor.document.cardcopy.selectedRegion
import org.synapseworks.pageharbor.document.readOrientation
import org.synapseworks.pageharbor.document.redaction.RedactionBounds
import org.synapseworks.pageharbor.document.redaction.RedactionCoordinateMapper
import org.synapseworks.pageharbor.document.redaction.RedactionCoordinateSpace
import org.synapseworks.pageharbor.document.redaction.RedactionRegion
import org.synapseworks.pageharbor.document.redaction.RedactionRegionId
import org.synapseworks.pageharbor.document.redaction.RedactionTargetKind
import org.synapseworks.pageharbor.document.redaction.confirmedSourceRegionsFor
import org.synapseworks.pageharbor.document.transform
import org.synapseworks.pageharbor.document.targetsize.formatDecimalBytes
import org.synapseworks.pageharbor.ui.home.ManagedDocumentPreviewState
import org.synapseworks.pageharbor.ui.home.ZoomableDocumentPreview
import org.synapseworks.pageharbor.ui.home.rememberManagedDocumentPreview
import org.synapseworks.pageharbor.ui.theme.PageHarborLayout
import org.synapseworks.pageharbor.ui.theme.PageHarborSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardCopyScreen(
    state: CardCopyWorkflowState,
    onBack: () -> Unit,
    onScan: (CardCopySide) -> Unit,
    onImport: (CardCopySide) -> Unit,
    onContinueReview: () -> Unit,
    onRotate: (CardCopySide, Boolean) -> Unit,
    onRemove: (CardCopySide) -> Unit,
    onSwap: () -> Unit,
    onContinueLayout: () -> Unit,
    onPageSize: (CardCopyPageSize) -> Unit,
    onLayout: (CardCopyLayout) -> Unit,
    onSpacing: (CardCopySpacing) -> Unit,
    onAlignment: (CardCopyAlignment) -> Unit,
    onShowPreview: () -> Unit,
    onPrepare: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onContinueShareSafe: () -> Unit = {},
    onRetry: () -> Unit,
    onOpenRedaction: (CardCopySide) -> Unit = {},
    onAddRedaction: (RedactionBounds) -> Unit = {},
    onSelectRedaction: (RedactionRegionId) -> Unit = {},
    onUpdateRedaction: (RedactionRegionId, RedactionBounds) -> Unit = { _, _ -> },
    onDeleteRedaction: () -> Unit = {},
    onUndoRedaction: () -> Unit = {},
    onRedoRedaction: () -> Unit = {},
    onApplyRedactions: () -> Unit = {},
) {
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.card_copy_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.card_copy_close),
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
                CardCopyWorkflowState.Hidden -> Unit
                is CardCopyWorkflowState.AcquireSides -> AcquireContent(
                    state.front, state.back, onScan, onImport, onContinueReview,
                )
                is CardCopyWorkflowState.ReviewSides -> ReviewContent(
                    state.front, state.back, onScan, onImport, onRotate, onRemove,
                    onSwap, onOpenRedaction, onContinueLayout,
                )
                is CardCopyWorkflowState.RedactSide -> RedactionEditorContent(
                    state = state,
                    onAdd = onAddRedaction,
                    onSelect = onSelectRedaction,
                    onUpdate = onUpdateRedaction,
                    onDelete = onDeleteRedaction,
                    onUndo = onUndoRedaction,
                    onRedo = onRedoRedaction,
                    onApply = onApplyRedactions,
                )
                is CardCopyWorkflowState.Layout -> LayoutContent(
                    state.front, state.back, state.settings, onPageSize, onLayout,
                    onSpacing, onAlignment, onShowPreview,
                )
                is CardCopyWorkflowState.Preview -> PreviewContent(
                    state.front, state.back, state.settings, onPrepare,
                )
                is CardCopyWorkflowState.Preparing -> PreparingContent()
                is CardCopyWorkflowState.Prepared -> PreparedContent(
                    state, onSave, onShare, onContinueShareSafe, onBack,
                )
                is CardCopyWorkflowState.Failed -> FailedContent(state.reason, onRetry)
            }
        }
    }
}

@Composable
private fun AcquireContent(
    front: CardCopySideState,
    back: CardCopySideState,
    onScan: (CardCopySide) -> Unit,
    onImport: (CardCopySide) -> Unit,
    onContinue: () -> Unit,
) {
    val frontReady = front.readySource() != null
    val backReady = back.readySource() != null
    Column(
        Modifier.widthIn(max = PageHarborLayout.homeContentMaxWidth).fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(PageHarborLayout.compactScreenHorizontalPadding),
        verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.medium),
    ) {
        Text(stringResource(R.string.card_copy_tool_description), style = MaterialTheme.typography.bodyLarge)
        SideCard(CardCopySide.FRONT, front, onScan, onImport, null, null, null)
        SideCard(CardCopySide.BACK, back, onScan, onImport, null, null, null)
        if (!frontReady) Notice(stringResource(R.string.card_copy_add_front))
        else if (!backReady) Notice(stringResource(R.string.card_copy_add_back))
        Text(stringResource(R.string.card_copy_processed_locally), color = MaterialTheme.colorScheme.primary)
        Button(
            onClick = onContinue,
            enabled = frontReady && backReady,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.card_copy_continue)) }
        Spacer(Modifier.height(PageHarborSpacing.large))
    }
}

@Composable
private fun ReviewContent(
    front: CardCopySideState,
    back: CardCopySideState,
    onScan: (CardCopySide) -> Unit,
    onImport: (CardCopySide) -> Unit,
    onRotate: (CardCopySide, Boolean) -> Unit,
    onRemove: (CardCopySide) -> Unit,
    onSwap: () -> Unit,
    onRedact: (CardCopySide) -> Unit,
    onContinue: () -> Unit,
) {
    Column(
        Modifier.widthIn(max = PageHarborLayout.homeContentMaxWidth).fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(PageHarborLayout.compactScreenHorizontalPadding),
        verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.medium),
    ) {
        Text(
            stringResource(R.string.card_copy_review_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        SideCard(CardCopySide.FRONT, front, onScan, onImport, onRotate, onRemove, onRedact)
        SideCard(CardCopySide.BACK, back, onScan, onImport, onRotate, onRemove, onRedact)
        OutlinedButton(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            onClick = onSwap,
        ) {
            Icon(Icons.Default.SwapVert, contentDescription = null)
            Text(stringResource(R.string.card_copy_swap))
        }
        Button(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            enabled = front.readySource() != null && back.readySource() != null,
            onClick = onContinue,
        ) { Text(stringResource(R.string.card_copy_continue)) }
        Spacer(Modifier.height(PageHarborSpacing.large))
    }
}

@Composable
private fun RedactionEditorContent(
    state: CardCopyWorkflowState.RedactSide,
    onAdd: (RedactionBounds) -> Unit,
    onSelect: (RedactionRegionId) -> Unit,
    onUpdate: (RedactionRegionId, RedactionBounds) -> Unit,
    onDelete: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onApply: () -> Unit,
) {
    val source = if (state.editor.side == CardCopySide.FRONT) state.front else state.back
    val selected = state.editor.selectedRegion()
    val selectedDisplay = selected?.let { region ->
        RedactionCoordinateMapper.rotate(region.bounds, source.rotation)
    }
    Column(
        Modifier.widthIn(max = PageHarborLayout.homeContentMaxWidth).fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(PageHarborLayout.compactScreenHorizontalPadding),
        verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.small),
    ) {
        Text(
            stringResource(
                if (state.editor.side == CardCopySide.FRONT) {
                    R.string.card_copy_redact_front
                } else {
                    R.string.card_copy_redact_back
                },
            ),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(R.string.card_copy_redaction_warning))
        RedactionEditorCanvas(
            source = source,
            regions = state.editor.regions,
            selectedId = state.editor.selectedId,
            onAdd = onAdd,
            onSelect = onSelect,
            onUpdate = onUpdate,
            modifier = Modifier.fillMaxWidth()
                .aspectRatio(source.orientedWidth.toFloat() / source.orientedHeight)
                .heightIn(min = 220.dp, max = 520.dp),
        )
        if (state.editor.regions.isEmpty()) {
            Notice(stringResource(R.string.card_copy_redaction_empty))
        } else {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(PageHarborSpacing.small),
            ) {
                state.editor.regions.sortedBy(RedactionRegion::order).forEachIndexed { index, region ->
                    FilterChip(
                        selected = region.id == state.editor.selectedId,
                        onClick = { onSelect(region.id) },
                        label = { Text(stringResource(R.string.card_copy_redaction_label, index + 1)) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(PageHarborSpacing.small),
        ) {
            OutlinedButton(
                modifier = Modifier.heightIn(min = 48.dp),
                onClick = { onAdd(defaultRedactionBounds()) },
            ) { Text(stringResource(R.string.card_copy_add_redaction)) }
            TextButton(
                modifier = Modifier.heightIn(min = 48.dp),
                enabled = state.editor.canUndo,
                onClick = onUndo,
            ) { Text(stringResource(R.string.card_copy_redaction_undo)) }
            TextButton(
                modifier = Modifier.heightIn(min = 48.dp),
                enabled = state.editor.canRedo,
                onClick = onRedo,
            ) { Text(stringResource(R.string.card_copy_redaction_redo)) }
            TextButton(
                modifier = Modifier.heightIn(min = 48.dp),
                enabled = selected != null,
                onClick = onDelete,
            ) { Text(stringResource(R.string.card_copy_redaction_delete)) }
        }
        if (selected != null && selectedDisplay != null) {
            Text(
                stringResource(
                    R.string.card_copy_redaction_selected,
                    state.editor.regions.sortedBy(RedactionRegion::order).indexOfFirst {
                        it.id == selected.id
                    } + 1,
                ),
                style = MaterialTheme.typography.labelLarge,
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(PageHarborSpacing.small),
            ) {
                RedactionAdjustButton(R.string.card_copy_move_left) {
                    moveRedaction(selectedDisplay, -REDACTION_NUDGE, 0f)?.let {
                        onUpdate(selected.id, it)
                    }
                }
                RedactionAdjustButton(R.string.card_copy_move_right) {
                    moveRedaction(selectedDisplay, REDACTION_NUDGE, 0f)?.let {
                        onUpdate(selected.id, it)
                    }
                }
                RedactionAdjustButton(R.string.card_copy_move_up) {
                    moveRedaction(selectedDisplay, 0f, -REDACTION_NUDGE)?.let {
                        onUpdate(selected.id, it)
                    }
                }
                RedactionAdjustButton(R.string.card_copy_move_down) {
                    moveRedaction(selectedDisplay, 0f, REDACTION_NUDGE)?.let {
                        onUpdate(selected.id, it)
                    }
                }
                RedactionAdjustButton(R.string.card_copy_make_smaller) {
                    resizeRedaction(selectedDisplay, -REDACTION_NUDGE)?.let {
                        onUpdate(selected.id, it)
                    }
                }
                RedactionAdjustButton(R.string.card_copy_make_larger) {
                    resizeRedaction(selectedDisplay, REDACTION_NUDGE)?.let {
                        onUpdate(selected.id, it)
                    }
                }
            }
        }
        Button(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            onClick = onApply,
        ) { Text(stringResource(R.string.card_copy_apply_redactions)) }
        Spacer(Modifier.height(PageHarborSpacing.large))
    }
}

@Composable
private fun RedactionAdjustButton(label: Int, onClick: () -> Unit) {
    OutlinedButton(
        modifier = Modifier.heightIn(min = 48.dp),
        onClick = onClick,
    ) { Text(stringResource(label)) }
}

private enum class RedactionDragMode {
    CREATE,
    MOVE,
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
}

@Composable
private fun RedactionEditorCanvas(
    source: CardCopySource,
    regions: List<RedactionRegion>,
    selectedId: RedactionRegionId?,
    onAdd: (RedactionBounds) -> Unit,
    onSelect: (RedactionRegionId) -> Unit,
    onUpdate: (RedactionRegionId, RedactionBounds) -> Unit,
    modifier: Modifier = Modifier,
) {
    val preview by rememberCardBitmap(source)
    val selectionColor = MaterialTheme.colorScheme.primary
    val handleColor = MaterialTheme.colorScheme.onPrimary
    val editorDescription = stringResource(R.string.card_copy_redaction_editor_description)
    var draftBounds by remember(source.sourceId, source.rotation, regions, selectedId) {
        mutableStateOf<RedactionBounds?>(null)
    }
    var dragId by remember { mutableStateOf<RedactionRegionId?>(null) }
    var dragMode by remember { mutableStateOf(RedactionDragMode.CREATE) }
    var dragStart by remember { mutableStateOf(Offset.Zero) }
    var dragDelta by remember { mutableStateOf(Offset.Zero) }
    var dragOrigin by remember { mutableStateOf<RedactionBounds?>(null) }

    Surface(
        modifier = modifier.semantics { contentDescription = editorDescription },
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Box(contentAlignment = Alignment.Center) {
            when (val state = preview) {
                ManagedDocumentPreviewState.Loading -> CircularProgressIndicator()
                ManagedDocumentPreviewState.Unavailable -> Text(stringResource(R.string.scan_preview_unavailable))
                is ManagedDocumentPreviewState.Ready -> Canvas(
                    Modifier.fillMaxSize()
                        .pointerInput(regions, selectedId, source.rotation) {
                            detectTapGestures { tap ->
                                val point = tap.toNormalizedOffset(size.width, size.height)
                                displayRegions(regions, source).lastOrNull { (_, bounds) ->
                                    bounds.contains(point)
                                }?.first?.let(onSelect)
                            }
                        }
                        .pointerInput(regions, selectedId, source.rotation) {
                            detectDragGestures(
                                onDragStart = { position ->
                                    dragStart = position.toNormalizedOffset(size.width, size.height)
                                    dragDelta = Offset.Zero
                                    val displays = displayRegions(regions, source)
                                    val selected = displays.firstOrNull { it.first == selectedId }
                                    val thresholdX = 24.dp.toPx() / size.width.coerceAtLeast(1)
                                    val thresholdY = 24.dp.toPx() / size.height.coerceAtLeast(1)
                                    val handle = selected?.second?.handleAt(
                                        dragStart,
                                        thresholdX,
                                        thresholdY,
                                    )
                                    val hit = selected?.takeIf { handle != null }
                                        ?: displays.lastOrNull { (_, bounds) -> bounds.contains(dragStart) }
                                    dragMode = handle ?: if (hit != null) {
                                        RedactionDragMode.MOVE
                                    } else {
                                        RedactionDragMode.CREATE
                                    }
                                    dragId = hit?.first
                                    dragOrigin = hit?.second
                                    draftBounds = hit?.second
                                },
                                onDragCancel = {
                                    draftBounds = null
                                    dragId = null
                                    dragOrigin = null
                                },
                                onDragEnd = {
                                    val completed = draftBounds
                                    val id = dragId
                                    if (completed != null) {
                                        if (dragMode == RedactionDragMode.CREATE) onAdd(completed)
                                        else if (id != null) onUpdate(id, completed)
                                    }
                                    draftBounds = null
                                    dragId = null
                                    dragOrigin = null
                                },
                            ) { change, amount ->
                                change.consume()
                                dragDelta += Offset(
                                    amount.x / size.width.coerceAtLeast(1),
                                    amount.y / size.height.coerceAtLeast(1),
                                )
                                draftBounds = dragBounds(
                                    dragMode,
                                    dragStart,
                                    dragDelta,
                                    dragOrigin,
                                ) ?: draftBounds
                            }
                        },
                ) {
                    drawImage(
                        state.owner.bitmap.asImageBitmap(),
                        dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()),
                    )
                    val displays = displayRegions(regions, source)
                    displays.forEach { (id, bounds) ->
                        val shown = if (id == dragId && draftBounds != null) draftBounds!! else bounds
                        drawRedactionRegion(
                            bounds = shown,
                            selected = id == selectedId || id == dragId,
                            selectionColor = selectionColor,
                            handleColor = handleColor,
                        )
                    }
                    if (dragMode == RedactionDragMode.CREATE && dragId == null) {
                        draftBounds?.let { bounds ->
                            drawRedactionRegion(bounds, true, selectionColor, handleColor)
                        }
                    }
                }
            }
        }
    }
}

private fun displayRegions(
    regions: List<RedactionRegion>,
    source: CardCopySource,
): List<Pair<RedactionRegionId, RedactionBounds>> = regions.mapNotNull { region ->
    if (region.coordinateSpace != RedactionCoordinateSpace.SOURCE_NORMALIZED ||
        region.target.kind != RedactionTargetKind.CARD_SOURCE ||
        region.target.stableId != source.sourceId
    ) null else region.id to RedactionCoordinateMapper.rotate(region.bounds, source.rotation)
}

private fun Offset.toNormalizedOffset(width: Int, height: Int): Offset = Offset(
    (x / width.coerceAtLeast(1)).coerceIn(0f, 1f),
    (y / height.coerceAtLeast(1)).coerceIn(0f, 1f),
)

private fun RedactionBounds.contains(point: Offset): Boolean =
    point.x in left..right && point.y in top..bottom

private fun RedactionBounds.handleAt(
    point: Offset,
    thresholdX: Float,
    thresholdY: Float,
): RedactionDragMode? {
    val handles = listOf(
        RedactionDragMode.TOP_LEFT to Offset(left, top),
        RedactionDragMode.TOP_RIGHT to Offset(right, top),
        RedactionDragMode.BOTTOM_LEFT to Offset(left, bottom),
        RedactionDragMode.BOTTOM_RIGHT to Offset(right, bottom),
    )
    return handles.firstOrNull { (_, handle) ->
        kotlin.math.abs(point.x - handle.x) <= thresholdX &&
            kotlin.math.abs(point.y - handle.y) <= thresholdY
    }?.first
}

private fun dragBounds(
    mode: RedactionDragMode,
    start: Offset,
    delta: Offset,
    origin: RedactionBounds?,
): RedactionBounds? = when (mode) {
    RedactionDragMode.CREATE -> RedactionBounds.clamped(
        start.x,
        start.y,
        start.x + delta.x,
        start.y + delta.y,
    )
    RedactionDragMode.MOVE -> origin?.let { moveRedaction(it, delta.x, delta.y) }
    RedactionDragMode.TOP_LEFT -> origin?.let {
        RedactionBounds.clamped(it.left + delta.x, it.top + delta.y, it.right, it.bottom)
    }
    RedactionDragMode.TOP_RIGHT -> origin?.let {
        RedactionBounds.clamped(it.left, it.top + delta.y, it.right + delta.x, it.bottom)
    }
    RedactionDragMode.BOTTOM_LEFT -> origin?.let {
        RedactionBounds.clamped(it.left + delta.x, it.top, it.right, it.bottom + delta.y)
    }
    RedactionDragMode.BOTTOM_RIGHT -> origin?.let {
        RedactionBounds.clamped(it.left, it.top, it.right + delta.x, it.bottom + delta.y)
    }
}

private fun moveRedaction(bounds: RedactionBounds, deltaX: Float, deltaY: Float): RedactionBounds? {
    val appliedX = deltaX.coerceIn(-bounds.left, 1f - bounds.right)
    val appliedY = deltaY.coerceIn(-bounds.top, 1f - bounds.bottom)
    return RedactionBounds.clamped(
        bounds.left + appliedX,
        bounds.top + appliedY,
        bounds.right + appliedX,
        bounds.bottom + appliedY,
    )
}

private fun resizeRedaction(bounds: RedactionBounds, delta: Float): RedactionBounds? =
    RedactionBounds.clamped(
        bounds.left - delta,
        bounds.top - delta,
        bounds.right + delta,
        bounds.bottom + delta,
    )

private fun DrawScope.drawRedactionRegion(
    bounds: RedactionBounds,
    selected: Boolean,
    selectionColor: androidx.compose.ui.graphics.Color,
    handleColor: androidx.compose.ui.graphics.Color,
) {
    val left = bounds.left * size.width
    val top = bounds.top * size.height
    val right = bounds.right * size.width
    val bottom = bounds.bottom * size.height
    drawRect(
        color = androidx.compose.ui.graphics.Color.Black,
        topLeft = Offset(left, top),
        size = Size(right - left, bottom - top),
    )
    if (!selected) return
    val stroke = 3.dp.toPx()
    drawRect(
        color = selectionColor,
        topLeft = Offset(left, top),
        size = Size(right - left, bottom - top),
        style = Stroke(stroke),
    )
    val handle = 12.dp.toPx()
    listOf(
        Offset(left, top), Offset(right, top), Offset(left, bottom), Offset(right, bottom),
    ).forEach { point ->
        drawRect(
            color = handleColor,
            topLeft = Offset(point.x - handle / 2f, point.y - handle / 2f),
            size = Size(handle, handle),
        )
        drawRect(
            color = selectionColor,
            topLeft = Offset(point.x - handle / 2f, point.y - handle / 2f),
            size = Size(handle, handle),
            style = Stroke(stroke.coerceAtMost(handle / 3f)),
        )
    }
}

private const val REDACTION_NUDGE = 0.02f

@Composable
private fun SideCard(
    side: CardCopySide,
    state: CardCopySideState,
    onScan: (CardCopySide) -> Unit,
    onImport: (CardCopySide) -> Unit,
    onRotate: ((CardCopySide, Boolean) -> Unit)?,
    onRemove: ((CardCopySide) -> Unit)?,
    onRedact: ((CardCopySide) -> Unit)?,
) {
    val source = state.readySource()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier.padding(PageHarborSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.small),
        ) {
            Text(
                stringResource(if (side == CardCopySide.FRONT) R.string.card_copy_front else R.string.card_copy_back),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            source?.let { CardSidePreview(it, Modifier.fillMaxWidth().heightIn(min = 150.dp, max = 240.dp)) }
            when (state) {
                is CardCopySideState.Acquiring -> Text(
                    stringResource(R.string.card_copy_acquiring, acquisitionName(state.acquisition)),
                )
                is CardCopySideState.Decoding -> Text(
                    stringResource(R.string.card_copy_decoding, acquisitionName(state.acquisition)),
                )
                is CardCopySideState.Failed -> Notice(stringResource(failureText(state.reason)))
                is CardCopySideState.Ready -> Text(
                    stringResource(
                        if (state.source.acquisition == CardCopyAcquisition.SCAN) {
                            R.string.card_copy_scan_source
                        } else R.string.card_copy_import_source,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CardCopySideState.Empty -> Unit
            }
            if (source != null) Text(
                stringResource(R.string.card_copy_replace),
                style = MaterialTheme.typography.labelLarge,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(PageHarborSpacing.small),
            ) {
                OutlinedButton(
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    onClick = { onScan(side) },
                ) { Text(stringResource(R.string.card_copy_scan)) }
                OutlinedButton(
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    onClick = { onImport(side) },
                ) { Text(stringResource(R.string.card_copy_import)) }
            }
            if (source != null && onRotate != null && onRemove != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    IconButton(
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                        onClick = { onRotate(side, false) },
                    ) { Icon(Icons.AutoMirrored.Filled.RotateLeft, stringResource(R.string.card_copy_rotate_left)) }
                    IconButton(
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                        onClick = { onRotate(side, true) },
                    ) { Icon(Icons.AutoMirrored.Filled.RotateRight, stringResource(R.string.card_copy_rotate_right)) }
                    IconButton(
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                        onClick = { onRemove(side) },
                    ) { Icon(Icons.Default.Delete, stringResource(R.string.card_copy_remove)) }
                }
            }
            if (source != null && onRedact != null) {
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    onClick = { onRedact(side) },
                ) {
                    Text(
                        if (source.redactions.isEmpty()) {
                            stringResource(R.string.card_copy_redact)
                        } else {
                            stringResource(
                                R.string.card_copy_redaction_count,
                                source.redactions.size,
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun LayoutContent(
    front: CardCopySource,
    back: CardCopySource,
    settings: CardCopySettings,
    onPageSize: (CardCopyPageSize) -> Unit,
    onLayout: (CardCopyLayout) -> Unit,
    onSpacing: (CardCopySpacing) -> Unit,
    onAlignment: (CardCopyAlignment) -> Unit,
    onContinue: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val expanded = maxWidth >= 600.dp
        val controls: @Composable (Modifier) -> Unit = { modifier ->
            Column(
                modifier,
                verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.medium),
            ) {
                Text(stringResource(R.string.card_copy_layout_title), style = MaterialTheme.typography.headlineSmall)
                ChoiceRow(stringResource(R.string.card_copy_page_size), CardCopyPageSize.entries, settings.pageSize,
                    { if (it == CardCopyPageSize.A4) R.string.card_copy_a4 else R.string.card_copy_letter }, onPageSize)
                ChoiceRow(stringResource(R.string.card_copy_layout), CardCopyLayout.entries, settings.layout,
                    { when (it) { CardCopyLayout.AUTO -> R.string.card_copy_auto; CardCopyLayout.VERTICAL -> R.string.card_copy_vertical; CardCopyLayout.HORIZONTAL -> R.string.card_copy_horizontal } }, onLayout)
                ChoiceRow(stringResource(R.string.card_copy_spacing), CardCopySpacing.entries, settings.spacing,
                    { when (it) { CardCopySpacing.COMPACT -> R.string.card_copy_compact; CardCopySpacing.NORMAL -> R.string.card_copy_normal; CardCopySpacing.WIDE -> R.string.card_copy_wide } }, onSpacing)
                ChoiceRow(stringResource(R.string.card_copy_alignment), CardCopyAlignment.entries, settings.alignment,
                    { when (it) { CardCopyAlignment.START -> R.string.card_copy_start_alignment; CardCopyAlignment.CENTER -> R.string.card_copy_center_alignment; CardCopyAlignment.END -> R.string.card_copy_end_alignment } }, onAlignment)
                Text(stringResource(R.string.card_copy_originals_unchanged), color = MaterialTheme.colorScheme.primary)
                Button(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    onClick = onContinue,
                ) { Text(stringResource(R.string.card_copy_preview)) }
                Spacer(Modifier.height(PageHarborSpacing.large))
            }
        }
        if (expanded) {
            Row(
                Modifier.fillMaxSize().padding(PageHarborLayout.mediumScreenHorizontalPadding),
                horizontalArrangement = Arrangement.spacedBy(PageHarborSpacing.large),
            ) {
                controls(Modifier.weight(0.8f).verticalScroll(rememberScrollState()))
                LivePagePreview(front, back, settings, Modifier.weight(1.2f).fillMaxSize())
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(PageHarborLayout.compactScreenHorizontalPadding),
                verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.medium),
            ) {
                LivePagePreview(front, back, settings, Modifier.fillMaxWidth().heightIn(min = 300.dp, max = 440.dp))
                controls(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun <T> ChoiceRow(
    title: String,
    values: List<T>,
    selected: T,
    label: (T) -> Int,
    onSelected: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.extraSmall)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(PageHarborSpacing.small),
        ) {
            values.forEach { value ->
                FilterChip(
                    selected = value == selected,
                    onClick = { onSelected(value) },
                    label = { Text(stringResource(label(value))) },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
    }
}

@Composable
private fun PreviewContent(
    front: CardCopySource,
    back: CardCopySource,
    settings: CardCopySettings,
    onPrepare: () -> Unit,
) {
    Column(
        Modifier.widthIn(max = PageHarborLayout.homeContentMaxWidth).fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(PageHarborLayout.compactScreenHorizontalPadding),
        verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.medium),
    ) {
        LivePagePreview(front, back, settings, Modifier.fillMaxWidth().heightIn(min = 420.dp, max = 620.dp))
        Text(stringResource(R.string.card_copy_processed_locally), color = MaterialTheme.colorScheme.primary)
        Button(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            onClick = onPrepare,
        ) { Text(stringResource(R.string.card_copy_prepare)) }
    }
}

@Composable
private fun PreparingContent() {
    Column(
        Modifier.padding(PageHarborSpacing.extraLarge).semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.medium),
    ) {
        CircularProgressIndicator()
        Text(stringResource(R.string.card_copy_preparing), style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun PreparedContent(
    state: CardCopyWorkflowState.Prepared,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onContinueShareSafe: () -> Unit,
    onChangeLayout: () -> Unit,
) {
    val saving = state.saveStatus == CardCopySaveStatus.Saving
    Column(
        Modifier.widthIn(max = PageHarborLayout.homeContentMaxWidth).fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(PageHarborLayout.compactScreenHorizontalPadding),
        verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.medium),
    ) {
        PreparedPdfPreview(state.prepared, Modifier.fillMaxWidth().heightIn(min = 420.dp, max = 620.dp))
        Text(stringResource(R.string.card_copy_ready), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.card_copy_result_size, formatDecimalBytes(state.prepared.bytes)))
        when (val save = state.saveStatus) {
            CardCopySaveStatus.Idle, CardCopySaveStatus.ChoosingDestination -> Unit
            CardCopySaveStatus.Saving -> Text(stringResource(R.string.card_copy_saving))
            is CardCopySaveStatus.Saved -> Text(stringResource(R.string.card_copy_saved, formatDecimalBytes(save.bytes)))
            is CardCopySaveStatus.Failed -> Notice(stringResource(failureText(save.reason)))
        }
        if (state.shareFailed) Notice(stringResource(R.string.card_copy_share_failed))
        if (state.continueToShareSafe) {
            Button(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                enabled = !saving,
                onClick = onContinueShareSafe,
            ) { Text(stringResource(R.string.share_safe_continue)) }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(PageHarborSpacing.small)) {
                Button(
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp), enabled = !saving, onClick = onSave,
                ) { Icon(Icons.Default.Save, null); Text(stringResource(R.string.card_copy_save)) }
                OutlinedButton(
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp), enabled = !saving, onClick = onShare,
                ) { Icon(Icons.Default.Share, null); Text(stringResource(R.string.card_copy_share)) }
            }
        }
        TextButton(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            enabled = !saving,
            onClick = onChangeLayout,
        ) { Text(stringResource(R.string.card_copy_change_layout)) }
    }
}

@Composable
private fun FailedContent(reason: CardCopyFailure, onRetry: () -> Unit) {
    Column(
        Modifier.widthIn(max = PageHarborLayout.homeContentMaxWidth).fillMaxWidth()
            .padding(PageHarborLayout.compactScreenHorizontalPadding),
        verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.medium),
    ) {
        Text(stringResource(R.string.card_copy_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(failureText(reason)))
        if (reason != CardCopyFailure.PROCESS_INTERRUPTED) Button(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), onClick = onRetry,
        ) { Text(stringResource(R.string.card_copy_try_again)) }
    }
}

@Composable
private fun CardSidePreview(source: CardCopySource, modifier: Modifier = Modifier) {
    val state by rememberCardBitmap(source)
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
        Box(contentAlignment = Alignment.Center) {
            when (val preview = state) {
                ManagedDocumentPreviewState.Loading -> CircularProgressIndicator()
                ManagedDocumentPreviewState.Unavailable -> Text(stringResource(R.string.scan_preview_unavailable))
                is ManagedDocumentPreviewState.Ready -> Image(
                    preview.owner.bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
                )
            }
        }
    }
}

@Composable
private fun LivePagePreview(
    front: CardCopySource,
    back: CardCopySource,
    settings: CardCopySettings,
    modifier: Modifier = Modifier,
) {
    val frontState by rememberCardBitmap(front)
    val backState by rememberCardBitmap(back)
    val geometry = remember(front.rotation, back.rotation, settings) {
        CardCopyGeometryEngine.calculate(
            requireNotNull(front.metadata.width), requireNotNull(front.metadata.height), front.rotation,
            requireNotNull(back.metadata.width), requireNotNull(back.metadata.height), back.rotation,
            settings,
        )
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    val previewDescription = stringResource(R.string.card_copy_preview_description)
    Box(modifier, contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.fillMaxWidth()
                .aspectRatio(settings.pageSize.widthPoints / settings.pageSize.heightPoints)
                .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offsetX, translationY = offsetY)
                .pointerInput(settings) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 4f)
                        offsetX = if (scale == 1f) 0f else offsetX + pan.x
                        offsetY = if (scale == 1f) 0f else offsetY + pan.y
                    }
                }
                .semantics { contentDescription = previewDescription },
            color = androidx.compose.ui.graphics.Color.White,
            shadowElevation = 4.dp,
        ) {
            val frontBitmap = (frontState as? ManagedDocumentPreviewState.Ready)?.owner?.bitmap
            val backBitmap = (backState as? ManagedDocumentPreviewState.Ready)?.owner?.bitmap
            if (frontBitmap == null || backBitmap == null) {
                Box(contentAlignment = Alignment.Center) { Text(stringResource(R.string.card_copy_preview_loading)) }
            } else Canvas(Modifier.fillMaxSize()) {
                drawCard(front, frontBitmap, geometry.front, geometry)
                drawCard(back, backBitmap, geometry.back, geometry)
            }
        }
    }
}

private fun DrawScope.drawCard(
    source: CardCopySource,
    bitmap: Bitmap,
    placement: org.synapseworks.pageharbor.document.cardcopy.CardCopyPlacement,
    geometry: CardCopyGeometry,
) {
    val rect = placement.bounds
    val sx = size.width / geometry.pageSize.widthPoints
    val sy = size.height / geometry.pageSize.heightPoints
    drawImage(
        bitmap.asImageBitmap(),
        dstOffset = androidx.compose.ui.unit.IntOffset((rect.left * sx).toInt(), (rect.top * sy).toInt()),
        dstSize = androidx.compose.ui.unit.IntSize((rect.width * sx).toInt(), (rect.height * sy).toInt()),
    )
    source.redactions.confirmedSourceRegionsFor(
        source.sourceId,
        RedactionTargetKind.CARD_SOURCE,
    )?.forEach { region ->
        val mapped = placement.mapSecureRasterCoverage(region.bounds) ?: return@forEach
        drawRect(
            color = androidx.compose.ui.graphics.Color.Black,
            topLeft = Offset(mapped.left * sx, mapped.top * sy),
            size = Size(
                (mapped.right - mapped.left) * sx,
                (mapped.bottom - mapped.top) * sy,
            ),
        )
    }
}

@Composable
private fun PreparedPdfPreview(prepared: CardCopyPreparedPdf, modifier: Modifier = Modifier) {
    val preview by rememberManagedDocumentPreview(
        requestKey = prepared.bytes,
        decode = { renderPdf(prepared.file) },
    )
    val previewDescription = stringResource(R.string.card_copy_prepared_preview_description)
    Surface(
        modifier = modifier.semantics { contentDescription = previewDescription },
        color = androidx.compose.ui.graphics.Color.White,
        shape = MaterialTheme.shapes.large,
    ) {
        Box(contentAlignment = Alignment.Center) {
            when (val state = preview) {
                ManagedDocumentPreviewState.Loading -> CircularProgressIndicator()
                ManagedDocumentPreviewState.Unavailable -> Text(stringResource(R.string.scan_preview_unavailable))
                is ManagedDocumentPreviewState.Ready -> ZoomableDocumentPreview(
                    modifier = Modifier.fillMaxSize(),
                    bitmap = state.owner.bitmap.asImageBitmap(),
                    resetKey = prepared.bytes,
                    description = previewDescription,
                    exposeDescription = false,
                )
            }
        }
    }
}

@Composable
private fun rememberCardBitmap(source: CardCopySource) = rememberManagedDocumentPreview(
    requestKey = Triple(source.file.path, source.file.lastModified(), source.rotation),
    decode = { decodeBounded(source.file) },
    transform = { bitmap ->
        transform(
            source = bitmap,
            orientation = readOrientation { source.file.inputStream() },
            rotation = source.rotation,
        )
    },
)

private fun decodeBounded(file: File): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (max(bounds.outWidth / sample, bounds.outHeight / sample) > 1_200) sample *= 2
    return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
}

private fun renderPdf(file: File): Bitmap? = try {
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            renderer.openPage(0).use { page ->
                val bitmap = createBitmap(page.width.coerceAtMost(1_200), page.height.coerceAtMost(1_600))
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            }
        }
    }
} catch (_: Exception) { null }

@Composable
private fun Notice(text: String) {
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
        Text(text, Modifier.padding(PageHarborSpacing.small))
    }
}

@Composable
private fun acquisitionName(acquisition: CardCopyAcquisition): String = stringResource(
    if (acquisition == CardCopyAcquisition.SCAN) R.string.card_copy_scan else R.string.card_copy_import,
)

private fun failureText(reason: CardCopyFailure): Int = when (reason) {
    CardCopyFailure.INVALID_SOURCE -> R.string.card_copy_source_invalid
    CardCopyFailure.SOURCE_TOO_LARGE -> R.string.card_copy_source_large
    CardCopyFailure.PREPARATION_FAILED -> R.string.card_copy_prepare_failed
    CardCopyFailure.VALIDATION_FAILED -> R.string.card_copy_validation_failed
    CardCopyFailure.DESTINATION_UNAVAILABLE -> R.string.card_copy_destination_failed
    CardCopyFailure.WRITE_FAILED -> R.string.card_copy_write_failed
    CardCopyFailure.PROCESS_INTERRUPTED -> R.string.card_copy_interrupted
    CardCopyFailure.INVALID_REDACTION -> R.string.card_copy_redaction_invalid
    CardCopyFailure.SOURCE_CHANGED -> R.string.card_copy_source_changed
    CardCopyFailure.SECURITY_VERIFICATION_FAILED -> R.string.card_copy_security_verification_failed
}
