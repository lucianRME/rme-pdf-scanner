package org.synapseworks.pageharbor.ui.home

import android.content.ContentResolver
import kotlinx.coroutines.ensureActive
import org.synapseworks.pageharbor.image.ManualImageAdjustments
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import org.synapseworks.pageharbor.image.bitmapDisplaySize
import org.synapseworks.pageharbor.image.decodeDisplayBitmap
import org.synapseworks.pageharbor.image.BitmapDisplaySize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import org.synapseworks.pageharbor.R
import org.synapseworks.pageharbor.document.session.DEFAULT_DOCUMENT_INPUT_LIMITS
import org.synapseworks.pageharbor.document.session.DocumentPageRotation
import org.synapseworks.pageharbor.document.session.documentWorkingSampleSize
import org.synapseworks.pageharbor.document.session.imageConstraintViolation
import org.synapseworks.pageharbor.image.ArgbImage
import org.synapseworks.pageharbor.image.DocumentFilter
import org.synapseworks.pageharbor.image.DocumentImageFilterEngine
import org.synapseworks.pageharbor.ui.theme.PageHarborLayout
import org.synapseworks.pageharbor.ui.theme.PageHarborSpacing
import org.synapseworks.pageharbor.ui.theme.rmeColors

/**
 * Displays a bounded, transient Scan Result preview. The source URI and scanner output are never
 * modified. A new page or filter cancels the prior producer; the request comparison also guards
 * against a stale decode completing after a rapid selection change.
 */
@Composable
internal fun FilteredDocumentPreview(
    request: FilteredPreviewRequest,
    pageUri: Uri,
    pageNumber: Int,
    pageCount: Int,
    modifier: Modifier = Modifier,
    minHeight: Dp = PageHarborLayout.documentPreviewMinHeight,
    maxHeight: Dp = PageHarborLayout.documentPreviewMaxHeight,
) {
    val contentResolver = LocalContext.current.contentResolver
    val currentRequest by rememberUpdatedState(request)
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val displayTarget = bitmapDisplaySize(viewport.width, viewport.height, inspectionScale = 4)
    val decodeTarget = displayTarget?.let {
        if (request.rotation == DocumentPageRotation.DEGREES_90 || request.rotation == DocumentPageRotation.DEGREES_270)
            BitmapDisplaySize(it.height, it.width) else it
    }
    val previewState by rememberManagedDocumentPreview(
        requestKey = request to decodeTarget,
        isCurrent = { request.isCurrentFor(currentRequest) },
        decode = { decodeTarget?.let { decodeDocumentPreview(contentResolver, pageUri, request, it) } },
        debounceMillis = if (request.adjustments.isNeutral) 0 else 80,
        transform = { decoded ->
            val job = kotlinx.coroutines.currentCoroutineContext()
            applyPreviewRotationAndFilter(decoded, request.rotation, request.filter, request.adjustments, request.annotations) {
                job.ensureActive()
            }
        },
    )
    val description = stringResource(R.string.scan_preview_description, pageNumber, pageCount)
    val dimensions = request.imageMetadata
    val readyBitmap=(previewState as?ManagedDocumentPreviewState.Ready)?.owner?.bitmap
    val aspectRatio = if(readyBitmap!=null)readyBitmap.width.toFloat()/readyBitmap.height else if ((dimensions.width ?: 0) > 0 && (dimensions.height ?: 0) > 0) {
        val ratio = dimensions.width!!.toFloat() / dimensions.height!!
        if (request.rotation == DocumentPageRotation.DEGREES_90 || request.rotation == DocumentPageRotation.DEGREES_270) 1f / ratio else ratio
    } else 0.72f
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val fittedHeight = (maxWidth / aspectRatio).coerceIn(minHeight, maxHeight)
        Column {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(fittedHeight)
                    .onSizeChanged { viewport = it }
                    .semantics { contentDescription = description },
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.rmeColors.previewBackground,
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    when (val state = previewState) {
                        ManagedDocumentPreviewState.Loading -> Text(
                            text = stringResource(R.string.scan_preview_loading),
                            style = MaterialTheme.typography.bodyMedium,
                        )

                        ManagedDocumentPreviewState.Unavailable -> Text(
                            text = stringResource(R.string.scan_preview_unavailable),
                            style = MaterialTheme.typography.bodyMedium,
                        )

                        is ManagedDocumentPreviewState.Ready -> ZoomableDocumentPreview(
                            modifier = Modifier.fillMaxSize(),
                            bitmap = state.owner.bitmap.asImageBitmap(),
                            resetKey = request,
                            description = description,
                            exposeDescription = false,
                        )
                    }
                }
            }
            val sample = documentWorkingSampleSize(dimensions.width ?: 0, dimensions.height ?: 0)
            if (sample != null && sample > 1) {
                Text(
                    modifier = Modifier.padding(top = PageHarborSpacing.small),
                    text = stringResource(R.string.document_large_image_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** ORIGINAL returns [source] directly; transformation failures preserve the source preview. */
internal fun <T> applyPreviewTransformationOrOriginal(
    source: T,
    filter: DocumentFilter,
    transform: (T, DocumentFilter) -> T,
): T {
    if (filter == DocumentFilter.ORIGINAL) return source
    return runCatching { transform(source, filter) }.getOrDefault(source)
}

internal fun applyPreviewFilterOrOriginal(source: Bitmap, filter: DocumentFilter, adjustments: ManualImageAdjustments = ManualImageAdjustments(), ensureActive: () -> Unit = {}): Bitmap {
    if (filter == DocumentFilter.ORIGINAL && adjustments.isNeutral) return source
    fun render(bitmap: Bitmap): Bitmap {
        val selectedFilter = filter
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val filtered = DocumentImageFilterEngine.apply(
            ArgbImage(bitmap.width, bitmap.height, pixels),
            selectedFilter, adjustments, ensureActive,
        )
        return Bitmap.createBitmap(filtered.pixels, filtered.width, filtered.height, Bitmap.Config.ARGB_8888)
    }
    // Never silently publish an unadjusted image after an adjustment/cancellation failure.
    return if (adjustments.isNeutral && !DocumentImageFilterEngine.requiresSpatialContext(filter)) applyPreviewTransformationOrOriginal(source, filter) { bitmap, _ -> render(bitmap) } else render(source)
}

internal fun applyPreviewRotationAndFilter(
    source: Bitmap,
    rotation: DocumentPageRotation,
    filter: DocumentFilter,
    adjustments: ManualImageAdjustments = ManualImageAdjustments(),
    annotations: org.synapseworks.pageharbor.document.annotation.AnnotationLayer = org.synapseworks.pageharbor.document.annotation.AnnotationLayer.EMPTY,
    ensureActive: () -> Unit = {},
): Bitmap {
    var intermediate: Bitmap? = null
    return try {
        val rotated = if (rotation == DocumentPageRotation.DEGREES_0) {
            source
        } else {
            Bitmap.createBitmap(
                source,
                0,
                0,
                source.width,
                source.height,
                Matrix().apply { setRotate(rotation.degrees.toFloat()) },
                true,
            ).also { intermediate = it }
        }
        val filtered = applyPreviewFilterOrOriginal(rotated, filter, adjustments, ensureActive)
        if (filtered !== rotated && rotated !== source) recyclePreviewBitmap(rotated)
        intermediate = null
        if (annotations.isEmpty) filtered else {
            val output=if(filtered.isMutable)filtered else filtered.copy(Bitmap.Config.ARGB_8888,true)
            try { org.synapseworks.pageharbor.document.annotation.AnnotationRenderer.draw(output,annotations,rotation,ensureActive) }
            catch(error:Throwable){if(output!==source)recyclePreviewBitmap(output);throw error}
            if(output!==filtered&&filtered!==source)recyclePreviewBitmap(filtered)
            output
        }
    } catch (error: Throwable) {
        intermediate?.let(::recyclePreviewBitmap)
        throw error
    }
}

internal fun decodeDocumentPreview(
    contentResolver: ContentResolver,
    pageUri: Uri,
    request: FilteredPreviewRequest,
    target: BitmapDisplaySize = BitmapDisplaySize(1600, 1600),
): Bitmap? {
    if (org.synapseworks.pageharbor.document.session.DEFAULT_DOCUMENT_SOURCE_LIMITS.imageConstraintViolation(request.imageMetadata) != null) return null
    val open = { contentResolver.openInputStream(pageUri) }
    val decoded = decodeDisplayBitmap(open, target) ?: return null
    return try {
        org.synapseworks.pageharbor.document.transform(decoded,org.synapseworks.pageharbor.document.readOrientation(open),DocumentPageRotation.DEGREES_0).also { if(it!==decoded)decoded.recycle() }
    } catch(error:Throwable) { decoded.recycle();throw error }
}
