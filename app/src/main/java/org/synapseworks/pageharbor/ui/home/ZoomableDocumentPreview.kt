package org.synapseworks.pageharbor.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.synapseworks.pageharbor.R
import org.synapseworks.pageharbor.ui.theme.PageHarborSpacing
import org.synapseworks.pageharbor.ui.theme.rmeColors

internal const val DOCUMENT_PREVIEW_MIN_SCALE = 1f
internal const val DOCUMENT_PREVIEW_MAX_SCALE = 4f

internal data class DocumentPreviewTransform(
    val scale: Float = DOCUMENT_PREVIEW_MIN_SCALE,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
)

internal class DocumentPreviewPosition {
    var transform by mutableStateOf(DocumentPreviewTransform())
    var lastViewport = IntSize.Zero
    var lastPassage: Rect? = null
}

internal data class DocumentPreviewContentSize(
    val width: Float,
    val height: Float,
)

internal fun fittedDocumentPreviewContentSize(
    viewportWidth: Int,
    viewportHeight: Int,
    imageWidth: Int,
    imageHeight: Int,
    totalPaddingPx: Float = 0f,
): DocumentPreviewContentSize {
    if (viewportWidth <= 0 || viewportHeight <= 0 || imageWidth <= 0 || imageHeight <= 0) {
        return DocumentPreviewContentSize(0f, 0f)
    }
    val availableWidth = (viewportWidth - totalPaddingPx).coerceAtLeast(1f)
    val availableHeight = (viewportHeight - totalPaddingPx).coerceAtLeast(1f)
    val fitScale = minOf(
        availableWidth / imageWidth.toFloat(),
        availableHeight / imageHeight.toFloat(),
    )
    return DocumentPreviewContentSize(
        width = imageWidth * fitScale,
        height = imageHeight * fitScale,
    )
}

internal fun updateDocumentPreviewTransform(
    current: DocumentPreviewTransform,
    zoomChange: Float,
    panX: Float,
    panY: Float,
    viewportWidth: Int,
    viewportHeight: Int,
    contentWidth: Float = viewportWidth.toFloat(),
    contentHeight: Float = viewportHeight.toFloat(),
    maxScale: Float = DOCUMENT_PREVIEW_MAX_SCALE,
): DocumentPreviewTransform {
    val scale = (current.scale * zoomChange)
        .coerceIn(DOCUMENT_PREVIEW_MIN_SCALE, maxScale)
    if (scale == DOCUMENT_PREVIEW_MIN_SCALE) return DocumentPreviewTransform()
    val maximumX = ((contentWidth.coerceAtLeast(0f) * scale - viewportWidth) / 2f)
        .coerceAtLeast(0f)
    val maximumY = ((contentHeight.coerceAtLeast(0f) * scale - viewportHeight) / 2f)
        .coerceAtLeast(0f)
    return DocumentPreviewTransform(
        scale = scale,
        offsetX = (current.offsetX + panX).coerceIn(-maximumX, maximumX),
        offsetY = (current.offsetY + panY).coerceIn(-maximumY, maximumY),
    )
}

internal fun toggleDocumentPreviewZoom(
    current: DocumentPreviewTransform,
): DocumentPreviewTransform = if (current.scale > DOCUMENT_PREVIEW_MIN_SCALE) {
    DocumentPreviewTransform()
} else {
    DocumentPreviewTransform(scale = 2f)
}

/** Retained layers may re-record a painter after its owner has released the bitmap's pixels. */
private class PreviewBitmapPainter(private val bitmap: ImageBitmap) : Painter() {
    private val delegate = BitmapPainter(bitmap)
    override val intrinsicSize = delegate.intrinsicSize

    override fun DrawScope.onDraw() {
        if (!bitmap.asAndroidBitmap().isRecycled) {
            with(delegate) { draw(size) }
        }
    }
}

/**
 * Shared inspection-only document preview interaction. Transform state is deliberately local and
 * resets when [resetKey] changes; it never affects source or exported document geometry.
 */
@Composable
internal fun ZoomableDocumentPreview(
    bitmap: ImageBitmap,
    resetKey: Any?,
    description: String,
    modifier: Modifier = Modifier,
    exposeDescription: Boolean = true,
    fitPortraitWidth: Boolean = false,
    passage: Rect? = null,
    position: DocumentPreviewPosition = remember(resetKey) { DocumentPreviewPosition() },
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val painter = remember(bitmap) { PreviewBitmapPainter(bitmap) }
    val transform = position.transform
    var viewport by remember(resetKey) { mutableStateOf(IntSize.Zero) }
    val previewPadding = if (fitPortraitWidth) 0.dp else PageHarborSpacing.small
    val totalPaddingPx = with(LocalDensity.current) { previewPadding.toPx() * 2f }
    val content = fittedDocumentPreviewContentSize(
        viewport.width, viewport.height, bitmap.width, bitmap.height, totalPaddingPx,
    )
    val widthScale = if (fitPortraitWidth && viewport.height > viewport.width && content.width > 0f)
        (viewport.width / content.width).coerceAtLeast(1f) else 1f
    val maxScale = DOCUMENT_PREVIEW_MAX_SCALE * widthScale
    LaunchedEffect(position, viewport, passage) {
        if (viewport == IntSize.Zero || (position.lastViewport == viewport && position.lastPassage == passage)) return@LaunchedEffect
        position.lastViewport = viewport
        position.lastPassage = passage
        val x = passage?.center?.x ?: .5f
        val y = passage?.center?.y ?: 0f
        position.transform = updateDocumentPreviewTransform(DocumentPreviewTransform(), widthScale,
            (.5f - x) * content.width * widthScale, (.5f - y) * content.height * widthScale,
            viewport.width, viewport.height, content.width, content.height, maxScale)
    }
    fun moved(zoom: Float = 1f, dx: Float = 0f, dy: Float = 0f) =
        updateDocumentPreviewTransform(
            position.transform, zoom, dx, dy, viewport.width, viewport.height, content.width, content.height, maxScale,
        )

    val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
        position.transform = moved(zoomChange, panChange.x, panChange.y)
    }
    val actions = mutableListOf<CustomAccessibilityAction>()
    fun addMovement(label: String, next: DocumentPreviewTransform) {
        if (next != transform) {
            actions += CustomAccessibilityAction(label) { position.transform = next; true }
        }
    }
    addMovement(stringResource(R.string.preview_zoom_in), moved(zoom = 2f))
    addMovement(stringResource(R.string.preview_zoom_out), moved(zoom = .5f))
    if (transform.scale > 2f) {
        addMovement(stringResource(R.string.preview_fit_page), DocumentPreviewTransform())
    }
    addMovement(stringResource(R.string.preview_view_left), moved(dx = viewport.width / 2f))
    addMovement(stringResource(R.string.preview_view_right), moved(dx = -viewport.width / 2f))
    addMovement(stringResource(R.string.preview_view_up), moved(dy = viewport.height / 2f))
    addMovement(stringResource(R.string.preview_view_down), moved(dy = -viewport.height / 2f))
    val accessibleName = if (exposeDescription) description else stringResource(R.string.preview_zoom_control)
    val zoomState = stringResource(R.string.preview_zoom_percent, (transform.scale * 100).roundToInt())
    val previewModifier = modifier.semantics {
        contentDescription = accessibleName
        stateDescription = zoomState
        customActions = actions
    }
    Surface(
        modifier = previewModifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.rmeColors.previewBackground,
        tonalElevation = 0.dp,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { viewport = it }
                .clipToBounds()
                .transformable(transformableState, canPan = { transform.scale > 1f })
                .pointerInput(resetKey) {
                    detectTapGestures(onDoubleTap = {
                        position.transform = toggleDocumentPreviewZoom(position.transform)
                    })
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = transform.scale
                        scaleY = transform.scale
                        translationX = transform.offsetX
                        translationY = transform.offsetY
                    }
                    .padding(previewPadding),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    modifier = Modifier.fillMaxSize(),
                    painter = painter,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                )
                if (passage != null) {
                    val color = MaterialTheme.colorScheme.primary
                    Canvas(Modifier.fillMaxSize()) {
                        val left = (size.width - content.width) / 2f + passage.left * content.width
                        val top = (size.height - content.height) / 2f + passage.top * content.height
                        val origin = Offset(left, top)
                        val box = Size(passage.width * content.width, passage.height * content.height)
                        drawRect(color.copy(alpha = .23f), origin, box)
                        drawRect(androidx.compose.ui.graphics.Color.White, origin, box, style = Stroke(3.dp.toPx()))
                        drawRect(color, origin, box, style = Stroke(1.5.dp.toPx()))
                    }
                }
                overlay()
            }
        }
    }
}
