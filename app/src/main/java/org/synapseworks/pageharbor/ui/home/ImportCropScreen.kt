package org.synapseworks.pageharbor.ui.home

import org.synapseworks.pageharbor.ui.theme.RmeFilterChip
import org.synapseworks.pageharbor.ui.theme.RmePrimaryButton
import org.synapseworks.pageharbor.ui.theme.RmeSecondaryButton
import org.synapseworks.pageharbor.ui.theme.RmeTextButton

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import org.synapseworks.pageharbor.R
import org.synapseworks.pageharbor.document.booklayout.BookPreviewImage
import org.synapseworks.pageharbor.document.crop.ImportCropViewModel
import org.synapseworks.pageharbor.document.crop.PageCrop
import org.synapseworks.pageharbor.document.session.DocumentPageRotation
import org.synapseworks.pageharbor.image.DocumentFilter
import org.synapseworks.pageharbor.image.ManualImageAdjustments
import kotlin.math.roundToInt

@Composable
internal fun ImportCropScreen(model: ImportCropViewModel, onApply: () -> Unit) {
    val review = model.state ?: return
    var showOriginal by remember { mutableStateOf(false) }
    var adjusting by remember { mutableStateOf(false) }
    var corners by remember(review.crop) { mutableStateOf(review.crop) }
    val source = review.source
    BackHandler {
        if (adjusting) {
            corners = review.crop
            adjusting = false
        } else {
            model.close()
        }
    }

    Surface(modifier = Modifier.fillMaxSize().testTag("import-crop")) {
        Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth()
                    .verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.import_crop_title), style = MaterialTheme.typography.headlineSmall)
                if (review.busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        stringResource(if (source == null) R.string.import_crop_finding else R.string.import_crop_preparing),
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                if (review.failed) {
                    Text(stringResource(R.string.import_crop_failed), color = MaterialTheme.colorScheme.error)
                }
                if (source != null && !review.busy) {
                    val originalVisible = adjusting || showOriginal || review.preview == null
                    val image = if (originalVisible) source.original else requireNotNull(review.preview)
                    Text(
                        stringResource(when {
                            adjusting -> R.string.import_crop_drag
                            originalVisible -> R.string.import_crop_original
                            else -> R.string.import_crop_proposal
                        }),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    CropImage(
                        image = image,
                        pageId = review.page.id.value,
                        filter = if (originalVisible) review.page.filter else review.filter,
                        adjustments = review.page.adjustments,
                        corners = if (adjusting) corners else null,
                        onMove = { index, dx, dy -> corners = corners.moveCorner(index, dx, dy) },
                    )
                    if (adjusting) {
                        RmePrimaryButton(
                            onClick = {
                                model.updateCorners(corners)
                                showOriginal = false
                                adjusting = false
                            },
                            modifier = Modifier.fillMaxWidth().testTag("import-crop-preview-adjusted"),
                        ) { Text(stringResource(R.string.import_crop_preview)) }
                        if (source.proposal != null) {
                            RmeTextButton(onClick = { corners = source.proposal }) {
                                Text(stringResource(R.string.import_crop_auto))
                            }
                        }
                    } else {
                        if (review.preview != null) {
                            RmeSecondaryButton(
                                onClick = { showOriginal = !showOriginal },
                                modifier = Modifier.fillMaxWidth().testTag("import-crop-compare"),
                            ) {
                                Text(stringResource(if (showOriginal) R.string.import_crop_show else R.string.import_crop_compare))
                            }
                        }
                        RmeSecondaryButton(
                            onClick = { adjusting = true; corners = review.crop },
                            modifier = Modifier.fillMaxWidth().testTag("import-crop-adjust"),
                        ) { Text(stringResource(R.string.import_crop_adjust)) }
                        if (review.preview != null && review.page.filter == DocumentFilter.ORIGINAL) {
                            RmeFilterChip(
                                selected = review.enhance,
                                onClick = model::toggleEnhancement,
                                label = { Text(stringResource(R.string.import_crop_enhance)) },
                            )
                        }
                    }
                }
                if (!adjusting) {
                    RmePrimaryButton(
                        onClick = onApply,
                        enabled = review.canApply && !showOriginal,
                        modifier = Modifier.fillMaxWidth().testTag("import-crop-use"),
                    ) { Text(stringResource(R.string.import_crop_use)) }
                }
                RmeTextButton(
                    onClick = model::close,
                    modifier = Modifier.fillMaxWidth().testTag("import-crop-keep"),
                ) { Text(stringResource(R.string.import_crop_keep)) }
            }
        }
    }
}

@Composable
internal fun CropImage(
    image: BookPreviewImage,
    pageId: Long,
    filter: DocumentFilter,
    adjustments: ManualImageAdjustments,
    corners: PageCrop?,
    onMove: (Int, Float, Float) -> Unit,
    cornerTag: String = "import-crop",
) {
    val aspect = requireNotNull(image.metadata.width).toFloat() / requireNotNull(image.metadata.height)
    BoxWithConstraints(
        Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        val margin = if (corners == null) 0.dp else 24.dp
        val width = minOf((maxWidth - margin * 2).coerceAtLeast(1.dp), 400.dp * aspect)
        val height = width / aspect
        val uri = Uri.fromFile(image.file)
        Box(Modifier.size(width + margin * 2, height + margin * 2)) {
            FilteredDocumentPreview(
                request = FilteredPreviewRequest(
                    pageId = pageId,
                    sourceKey = uri.toString(),
                    imageMetadata = image.metadata,
                    filter = filter,
                    rotation = DocumentPageRotation.DEGREES_0,
                    adjustments = adjustments,
                ),
                pageUri = uri,
                pageNumber = 1,
                pageCount = 1,
                modifier = Modifier.align(Alignment.Center).size(width, height),
                minHeight = height,
                maxHeight = height,
            )
            if (corners != null) {
                val border = MaterialTheme.colorScheme.primary
                Canvas(Modifier.align(Alignment.Center).size(width, height)) {
                    val points = corners.corners.map { Offset(it.x * size.width, it.y * size.height) }
                    points.forEachIndexed { i, start ->
                        val end = points[(i + 1) % points.size]
                        drawLine(Color.Black, start, end, strokeWidth = 5.dp.toPx())
                        drawLine(border, start, end, strokeWidth = 2.dp.toPx())
                    }
                }
                val move by rememberUpdatedState(onMove)
                val widthPx = with(LocalDensity.current) { width.toPx() }
                val heightPx = with(LocalDensity.current) { height.toPx() }
                val names = listOf(
                    R.string.import_crop_top_left, R.string.import_crop_top_right,
                    R.string.import_crop_bottom_right, R.string.import_crop_bottom_left,
                )
                val left = stringResource(R.string.import_crop_move_left)
                val right = stringResource(R.string.import_crop_move_right)
                val up = stringResource(R.string.import_crop_move_up)
                val down = stringResource(R.string.import_crop_move_down)
                corners.corners.forEachIndexed { index, point ->
                    val name = stringResource(names[index])
                    val position = stringResource(
                        R.string.import_crop_position, (point.x * 100).roundToInt(), (point.y * 100).roundToInt(),
                    )
                    Box(
                        Modifier.offset(x = width * point.x, y = height * point.y)
                            .size(48.dp)
                            .testTag("$cornerTag-corner-$index")
                            .pointerInput(index, widthPx, heightPx) {
                                detectDragGestures { change, delta ->
                                    change.consume()
                                    move(index, delta.x / widthPx, delta.y / heightPx)
                                }
                            }
                            .onKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                                when (event.key) {
                                    Key.DirectionLeft -> move(index, -.01f, 0f)
                                    Key.DirectionRight -> move(index, .01f, 0f)
                                    Key.DirectionUp -> move(index, 0f, -.01f)
                                    Key.DirectionDown -> move(index, 0f, .01f)
                                    else -> return@onKeyEvent false
                                }
                                true
                            }
                            .semantics {
                                contentDescription = name
                                stateDescription = position
                                customActions = listOf(
                                    CustomAccessibilityAction(left) { move(index, -.01f, 0f); true },
                                    CustomAccessibilityAction(right) { move(index, .01f, 0f); true },
                                    CustomAccessibilityAction(up) { move(index, 0f, -.01f); true },
                                    CustomAccessibilityAction(down) { move(index, 0f, .01f); true },
                                )
                            }.focusable(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.size(18.dp).border(2.dp, Color.Black, CircleShape).background(border, CircleShape))
                    }
                }
            }
        }
    }
}
