package org.synapseworks.pageharbor.ui.home

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import org.synapseworks.pageharbor.R
import org.synapseworks.pageharbor.document.session.DEFAULT_DOCUMENT_INPUT_LIMITS
import org.synapseworks.pageharbor.document.session.DocumentPageRotation
import org.synapseworks.pageharbor.document.session.imageConstraintViolation
import org.synapseworks.pageharbor.image.ArgbImage
import org.synapseworks.pageharbor.image.DocumentFilter
import org.synapseworks.pageharbor.image.DocumentImageFilterEngine
import org.synapseworks.pageharbor.ui.theme.PageHarborLayout

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
    val previewState by rememberManagedDocumentPreview(
        requestKey = request,
        isCurrent = { request.isCurrentFor(currentRequest) },
        decode = { decodeDocumentPreview(contentResolver, pageUri, request) },
        transform = { decoded ->
            applyPreviewRotationAndFilter(decoded, request.rotation, request.filter)
        },
    )
    val description = stringResource(R.string.scan_preview_description, pageNumber, pageCount)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(
                min = minHeight,
                max = maxHeight,
            )
            .semantics { contentDescription = description },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
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

                is ManagedDocumentPreviewState.Ready -> Image(
                    modifier = Modifier.fillMaxSize(),
                    bitmap = state.owner.bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
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

internal fun applyPreviewFilterOrOriginal(source: Bitmap, filter: DocumentFilter): Bitmap =
    applyPreviewTransformationOrOriginal(source, filter) { bitmap, selectedFilter ->
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val filtered = DocumentImageFilterEngine.apply(
            ArgbImage(bitmap.width, bitmap.height, pixels),
            selectedFilter,
        )
        Bitmap.createBitmap(filtered.pixels, filtered.width, filtered.height, Bitmap.Config.ARGB_8888)
    }

internal fun applyPreviewRotationAndFilter(
    source: Bitmap,
    rotation: DocumentPageRotation,
    filter: DocumentFilter,
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
        val filtered = applyPreviewFilterOrOriginal(rotated, filter)
        if (filtered !== rotated && rotated !== source) recyclePreviewBitmap(rotated)
        intermediate = null
        filtered
    } catch (error: Throwable) {
        intermediate?.let(::recyclePreviewBitmap)
        throw error
    }
}

private fun decodeDocumentPreview(
    contentResolver: ContentResolver,
    pageUri: Uri,
    request: FilteredPreviewRequest,
): Bitmap? {
    if (DEFAULT_DOCUMENT_INPUT_LIMITS.imageConstraintViolation(request.imageMetadata) != null) {
        return null
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    runCatching {
        contentResolver.openInputStream(pageUri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        }
    }.getOrNull()
    if (
        DEFAULT_DOCUMENT_INPUT_LIMITS.imageConstraintViolation(
            request.imageMetadata.copy(width = bounds.outWidth, height = bounds.outHeight),
        ) != null
    ) {
        return null
    }
    val sampleSize = DocumentPreviewDecodePolicy.calculateInSampleSize(
        width = bounds.outWidth,
        height = bounds.outHeight,
    ) ?: return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return runCatching {
        contentResolver.openInputStream(pageUri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        }
    }.getOrNull()
}
