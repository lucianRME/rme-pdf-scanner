package org.synapseworks.pageharbor.ui.home

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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.synapseworks.pageharbor.ui.theme.PageHarborSpacing
import org.synapseworks.pageharbor.ui.theme.rmeColors

internal const val DOCUMENT_PREVIEW_MIN_SCALE = 1f
internal const val DOCUMENT_PREVIEW_MAX_SCALE = 4f

internal data class DocumentPreviewTransform(
    val scale: Float = DOCUMENT_PREVIEW_MIN_SCALE,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
)

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
): DocumentPreviewTransform {
    val scale = (current.scale * zoomChange)
        .coerceIn(DOCUMENT_PREVIEW_MIN_SCALE, DOCUMENT_PREVIEW_MAX_SCALE)
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
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    var transform by remember(resetKey) { mutableStateOf(DocumentPreviewTransform()) }
    var viewport by remember(resetKey) { mutableStateOf(IntSize.Zero) }
    val previewPadding = PageHarborSpacing.small
    val totalPaddingPx = with(LocalDensity.current) { previewPadding.toPx() * 2f }
    LaunchedEffect(resetKey) { transform = DocumentPreviewTransform() }
    val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
        val content = fittedDocumentPreviewContentSize(
            viewportWidth = viewport.width,
            viewportHeight = viewport.height,
            imageWidth = bitmap.width,
            imageHeight = bitmap.height,
            totalPaddingPx = totalPaddingPx,
        )
        transform = updateDocumentPreviewTransform(
            current = transform,
            zoomChange = zoomChange,
            panX = panChange.x,
            panY = panChange.y,
            viewportWidth = viewport.width,
            viewportHeight = viewport.height,
            contentWidth = content.width,
            contentHeight = content.height,
        )
    }

    val previewModifier = if (exposeDescription) {
        modifier.semantics { contentDescription = description }
    } else {
        modifier
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
                .transformable(transformableState)
                .pointerInput(resetKey) {
                    detectTapGestures(onDoubleTap = {
                        transform = toggleDocumentPreviewZoom(transform)
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
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                )
                overlay()
            }
        }
    }
}
