@file:android.annotation.SuppressLint("ExifInterface")

package org.synapseworks.pageharbor.document

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.media.ExifInterface
import androidx.core.graphics.scale
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.cancellation.CancellationException
import org.synapseworks.pageharbor.document.session.DEFAULT_DOCUMENT_INPUT_LIMITS
import org.synapseworks.pageharbor.document.session.DocumentImageConstraintViolation
import org.synapseworks.pageharbor.document.session.DocumentImageMetadata
import org.synapseworks.pageharbor.document.session.DocumentInputLimits
import org.synapseworks.pageharbor.document.session.DocumentPageRotation
import org.synapseworks.pageharbor.document.session.imageConstraintViolation
import org.synapseworks.pageharbor.image.ArgbImage
import org.synapseworks.pageharbor.image.DocumentFilter
import org.synapseworks.pageharbor.image.DocumentImageFilterEngine

private const val FilteredJpegQuality = 100

internal data class SizedJpegEncoding(
    val maximumWidthPx: Int,
    val maximumHeightPx: Int,
    val quality: Int,
) {
    init {
        require(maximumWidthPx > 0)
        require(maximumHeightPx > 0)
        require(quality in 1..100)
    }
}

/**
 * Validates source metadata and encoded bounds before allocating full-resolution bitmaps, then
 * applies one non-destructive filter and writes a new JPEG. EXIF and session rotation share one
 * transform, intermediates transfer ownership immediately, and filters use one scanline buffer.
 * The common 12.5 MP ceiling therefore retains at most two full-page ARGB bitmaps (~100 MiB), not
 * several full-page bitmaps and arrays. Inputs are never silently downsampled.
 * [openSource] must return a newly opened, readable stream on every invocation; this function
 * closes each stream independently after EXIF, bounds, or full-resolution decoding.
 */
fun writeFilteredJpegToDestination(
    openSource: () -> InputStream?,
    destination: OutputStream?,
    filter: DocumentFilter,
    rotation: DocumentPageRotation = DocumentPageRotation.DEGREES_0,
    imageMetadata: DocumentImageMetadata = DocumentImageMetadata(),
    limits: DocumentInputLimits = DEFAULT_DOCUMENT_INPUT_LIMITS,
): PageExportResult {
    if (destination == null) return PageExportResult.DestinationUnavailable
    transformedImagePreflight(imageMetadata, limits = limits)?.let { failure ->
        destination.closeSafely()
        return failure
    }

    var decoded: Bitmap? = null
    var working: Bitmap? = null
    var outputBitmap: Bitmap? = null
    return try {
        val orientation = readOrientation(openSource)
        val bounds = readImageBounds(openSource) ?: run {
            destination.closeSafely()
            return PageExportResult.SourceMissing
        }
        transformedImagePreflight(
            metadata = imageMetadata,
            decodedWidth = bounds.first,
            decodedHeight = bounds.second,
            limits = limits,
        )?.let { failure ->
            destination.closeSafely()
            return failure
        }
        decoded = decodeFullResolution(openSource) ?: run {
            destination.closeSafely()
            return PageExportResult.SourceMissing
        }
        transformedImagePreflight(
            metadata = imageMetadata,
            decodedWidth = decoded.width,
            decodedHeight = decoded.height,
            limits = limits,
        )?.let { failure ->
            destination.closeSafely()
            return failure
        }
        val decodedBitmap = decoded
        val transformed = transform(decodedBitmap, orientation, rotation)
        if (transformed !== decodedBitmap) decodedBitmap.recycle()
        decoded = null
        working = transformed
        val readyForEncoding = if (filter == DocumentFilter.ORIGINAL) {
            transformed
        } else {
            filterBitmap(transformed, filter)
        }
        if (readyForEncoding !== transformed) transformed.recycle()
        working = null
        outputBitmap = readyForEncoding
        destination.use { output ->
            if (readyForEncoding.compress(Bitmap.CompressFormat.JPEG, FilteredJpegQuality, output)) {
                output.flush()
                PageExportResult.Success
            } else {
                PageExportResult.WriteFailed
            }
        }
    } catch (_: IOException) {
        destination.closeSafely()
        PageExportResult.WriteFailed
    } catch (_: SecurityException) {
        destination.closeSafely()
        PageExportResult.WriteFailed
    } catch (_: IllegalArgumentException) {
        destination.closeSafely()
        PageExportResult.WriteFailed
    } catch (_: IllegalStateException) {
        destination.closeSafely()
        PageExportResult.WriteFailed
    } catch (_: OutOfMemoryError) {
        // Bounds validation is the primary guard; this is a final response to heap contention.
        destination.closeSafely()
        PageExportResult.WriteFailed
    } finally {
        outputBitmap?.recycle()
        working?.recycle()
        decoded?.recycle()
    }
}

/**
 * Target-size companion to [writeFilteredJpegToDestination]. It preserves the same source,
 * rotation, filter, ownership, and pre-allocation rules while allowing only bounded downsampling
 * and a caller-selected JPEG quality. It never upscales and retains at most two page bitmaps.
 */
internal fun writeSizedJpegToDestination(
    openSource: () -> InputStream?,
    destination: OutputStream?,
    filter: DocumentFilter,
    rotation: DocumentPageRotation,
    imageMetadata: DocumentImageMetadata,
    encoding: SizedJpegEncoding,
    limits: DocumentInputLimits = DEFAULT_DOCUMENT_INPUT_LIMITS,
    isCancellationRequested: () -> Boolean = { false },
    /** Optional final in-place pixel sanitizer, applied after transforms/filters and before encode. */
    pixelSanitizer: ((Bitmap) -> Boolean)? = null,
): PageExportResult {
    if (destination == null) return PageExportResult.DestinationUnavailable
    transformedImagePreflight(imageMetadata, limits = limits)?.let { failure ->
        destination.closeSafely()
        return failure
    }

    var decoded: Bitmap? = null
    var working: Bitmap? = null
    var outputBitmap: Bitmap? = null
    return try {
        ensureEncodingActive(isCancellationRequested)
        val orientation = readOrientation(openSource)
        val bounds = readImageBounds(openSource) ?: run {
            destination.closeSafely()
            return PageExportResult.SourceMissing
        }
        transformedImagePreflight(
            metadata = imageMetadata,
            decodedWidth = bounds.first,
            decodedHeight = bounds.second,
            limits = limits,
        )?.let { failure ->
            destination.closeSafely()
            return failure
        }
        val sampleSize = targetDecodeSampleSize(
            sourceWidth = bounds.first,
            sourceHeight = bounds.second,
            orientation = orientation,
            rotation = rotation,
            encoding = encoding,
        )
        decoded = decodeWithSample(openSource, sampleSize) ?: run {
            destination.closeSafely()
            return PageExportResult.SourceMissing
        }
        ensureEncodingActive(isCancellationRequested)
        val decodedBitmap = decoded
        val transformed = transform(decodedBitmap, orientation, rotation)
        if (transformed !== decodedBitmap) decodedBitmap.recycle()
        decoded = null
        working = transformed
        val scaled = scaleToFit(transformed, encoding.maximumWidthPx, encoding.maximumHeightPx)
        if (scaled !== transformed) transformed.recycle()
        working = null
        outputBitmap = scaled
        ensureEncodingActive(isCancellationRequested)
        var filtered = if (filter == DocumentFilter.ORIGINAL) {
            scaled
        } else {
            filterBitmap(scaled, filter, isCancellationRequested)
        }
        if (filtered !== scaled) scaled.recycle()
        outputBitmap = filtered
        ensureEncodingActive(isCancellationRequested)
        if (pixelSanitizer != null && !filtered.isMutable) {
            val mutable = filtered.copy(Bitmap.Config.ARGB_8888, true)
                ?: return PageExportResult.WriteFailed
            filtered.recycle()
            filtered = mutable
            outputBitmap = mutable
        }
        if (pixelSanitizer?.invoke(filtered) == false) {
            destination.closeSafely()
            return PageExportResult.WriteFailed
        }
        ensureEncodingActive(isCancellationRequested)
        destination.use { output ->
            if (filtered.compress(Bitmap.CompressFormat.JPEG, encoding.quality, output)) {
                output.flush()
                PageExportResult.Success
            } else {
                PageExportResult.WriteFailed
            }
        }
    } catch (error: CancellationException) {
        destination.closeSafely()
        throw error
    } catch (_: IOException) {
        destination.closeSafely()
        PageExportResult.WriteFailed
    } catch (_: SecurityException) {
        destination.closeSafely()
        PageExportResult.WriteFailed
    } catch (_: IllegalArgumentException) {
        destination.closeSafely()
        PageExportResult.WriteFailed
    } catch (_: IllegalStateException) {
        destination.closeSafely()
        PageExportResult.WriteFailed
    } catch (_: OutOfMemoryError) {
        destination.closeSafely()
        PageExportResult.WriteFailed
    } finally {
        outputBitmap?.recycle()
        working?.recycle()
        decoded?.recycle()
    }
}

/** Exposed for JVM tests to prove the export path delegates to the shared Slice 1 engine. */
internal fun applyExportFilter(source: ArgbImage, filter: DocumentFilter): ArgbImage =
    DocumentImageFilterEngine.apply(source, filter)

/** Returns null when allocation may proceed, otherwise a typed pre-allocation failure. */
internal fun transformedImagePreflight(
    metadata: DocumentImageMetadata,
    decodedWidth: Int? = null,
    decodedHeight: Int? = null,
    limits: DocumentInputLimits = DEFAULT_DOCUMENT_INPUT_LIMITS,
): PageExportResult? {
    val effectiveMetadata = if (decodedWidth != null || decodedHeight != null) {
        metadata.copy(width = decodedWidth, height = decodedHeight)
    } else {
        metadata
    }
    return when (limits.imageConstraintViolation(effectiveMetadata)) {
        null -> null
        DocumentImageConstraintViolation.INVALID_METADATA -> PageExportResult.SourceMissing
        DocumentImageConstraintViolation.SOURCE_BYTES_EXCEEDED,
        DocumentImageConstraintViolation.DIMENSIONS_EXCEEDED,
        -> PageExportResult.SourceTooLarge
    }
}

/** Applies the shared known-size/dimension policy without decoding or allocating a bitmap. */
internal fun pageSourcePreflight(
    metadata: DocumentImageMetadata,
    limits: DocumentInputLimits = DEFAULT_DOCUMENT_INPUT_LIMITS,
): PageExportResult? = transformedImagePreflight(metadata = metadata, limits = limits)

internal fun readOrientation(openSource: () -> InputStream?): Int = runCatching {
    openSource()?.use { source ->
        ExifInterface(source).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    } ?: ExifInterface.ORIENTATION_NORMAL
}.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

@Suppress("DEPRECATION")
internal fun decodeFullResolution(openSource: () -> InputStream?): Bitmap? =
    openSource()?.use { source ->
        val decoder = BitmapRegionDecoder.newInstance(source, false) ?: return@use null
        try {
            decoder.decodeRegion(
                Rect(0, 0, decoder.width, decoder.height),
                BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            )
        } finally {
            decoder.recycle()
        }
    }

@Suppress("DEPRECATION")
private fun decodeWithSample(openSource: () -> InputStream?, sampleSize: Int): Bitmap? =
    openSource()?.use { source ->
        val decoder = BitmapRegionDecoder.newInstance(source, false) ?: return@use null
        try {
            decoder.decodeRegion(
                Rect(0, 0, decoder.width, decoder.height),
                BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inMutable = true
                    inSampleSize = sampleSize
                },
            )
        } finally {
            decoder.recycle()
        }
    }

@Suppress("DEPRECATION")
internal fun readImageBounds(openSource: () -> InputStream?): Pair<Int, Int>? {
    openSource()?.use { source ->
        val decoder = BitmapRegionDecoder.newInstance(source, false) ?: return null
        try {
            return decoder.width to decoder.height
        } finally {
            decoder.recycle()
        }
    } ?: return null
}

internal fun transform(
    source: Bitmap,
    orientation: Int,
    rotation: DocumentPageRotation,
): Bitmap {
    val matrix = Matrix()
    val hasExifTransform = when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> true.also { matrix.setScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_180 -> true.also { matrix.setRotate(180f) }
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> true.also { matrix.setScale(1f, -1f) }
        ExifInterface.ORIENTATION_TRANSPOSE -> true.also {
            matrix.setRotate(90f)
            matrix.postScale(-1f, 1f)
        }

        ExifInterface.ORIENTATION_ROTATE_90 -> true.also { matrix.setRotate(90f) }
        ExifInterface.ORIENTATION_TRANSVERSE -> true.also {
            matrix.setRotate(-90f)
            matrix.postScale(-1f, 1f)
        }

        ExifInterface.ORIENTATION_ROTATE_270 -> true.also { matrix.setRotate(-90f) }
        else -> false
    }
    if (rotation != DocumentPageRotation.DEGREES_0) {
        matrix.postRotate(rotation.degrees.toFloat())
    }
    if (!hasExifTransform && rotation == DocumentPageRotation.DEGREES_0) return source
    return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
}

private fun filterBitmap(
    source: Bitmap,
    filter: DocumentFilter,
    isCancellationRequested: () -> Boolean = { false },
): Bitmap {
    val row = IntArray(source.width)
    val histogram = if (DocumentImageFilterEngine.requiresHistogram(filter)) {
        IntArray(256).also { counts ->
            repeat(source.height) { y ->
                ensureEncodingActive(isCancellationRequested)
                source.getPixels(row, 0, source.width, 0, y, source.width, 1)
                DocumentImageFilterEngine.accumulateLuminanceHistogram(row, counts)
            }
        }
    } else {
        null
    }
    val plan = DocumentImageFilterEngine.createRowFilterPlan(
        filter = filter,
        histogram = histogram,
        totalPixelCount = source.width * source.height,
    )
    val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
    var completed = false
    return try {
        repeat(source.height) { y ->
            ensureEncodingActive(isCancellationRequested)
            source.getPixels(row, 0, source.width, 0, y, source.width, 1)
            DocumentImageFilterEngine.applyRowFilterInPlace(row, plan)
            output.setPixels(row, 0, source.width, 0, y, source.width, 1)
        }
        completed = true
        output
    } finally {
        if (!completed) output.recycle()
    }
}

private fun targetDecodeSampleSize(
    sourceWidth: Int,
    sourceHeight: Int,
    orientation: Int,
    rotation: DocumentPageRotation,
    encoding: SizedJpegEncoding,
): Int {
    val exifSwapsAxes = exifOrientationSwapsAxes(orientation)
    val sessionSwapsAxes = rotation == DocumentPageRotation.DEGREES_90 ||
        rotation == DocumentPageRotation.DEGREES_270
    val swapsAxes = exifSwapsAxes.xor(sessionSwapsAxes)
    val targetRawWidth = if (swapsAxes) encoding.maximumHeightPx else encoding.maximumWidthPx
    val targetRawHeight = if (swapsAxes) encoding.maximumWidthPx else encoding.maximumHeightPx
    var sample = 1
    while (
        sourceWidth / (sample * 2) >= targetRawWidth &&
        sourceHeight / (sample * 2) >= targetRawHeight
    ) {
        sample *= 2
    }
    return sample
}

internal fun exifOrientationSwapsAxes(orientation: Int): Boolean = orientation in setOf(
    ExifInterface.ORIENTATION_TRANSPOSE,
    ExifInterface.ORIENTATION_ROTATE_90,
    ExifInterface.ORIENTATION_TRANSVERSE,
    ExifInterface.ORIENTATION_ROTATE_270,
)

internal fun exifOrientationIsIdentity(orientation: Int): Boolean = orientation in setOf(
    ExifInterface.ORIENTATION_UNDEFINED,
    ExifInterface.ORIENTATION_NORMAL,
)

private fun scaleToFit(source: Bitmap, maximumWidth: Int, maximumHeight: Int): Bitmap {
    if (source.width <= maximumWidth && source.height <= maximumHeight) return source
    val scale = minOf(
        maximumWidth.toDouble() / source.width.toDouble(),
        maximumHeight.toDouble() / source.height.toDouble(),
    )
    val width = (source.width * scale).toInt().coerceAtLeast(1)
    val height = (source.height * scale).toInt().coerceAtLeast(1)
    return source.scale(width, height)
}

private fun ensureEncodingActive(isCancellationRequested: () -> Boolean) {
    if (isCancellationRequested()) throw CancellationException("Target-size encoding cancelled")
}

internal data class TransformedImageWorkingSet(
    val maxConcurrentFullResolutionBitmaps: Int,
    val fullPageArgbArrays: Int,
    val scanlineBufferPixels: Int,
    val estimatedPeakArgbBytes: Long,
)

/** Deterministic upper bound for the bitmap/filter allocations controlled by this writer. */
internal fun transformedImageWorkingSet(width: Int, height: Int): TransformedImageWorkingSet {
    require(width > 0 && height > 0)
    val pixels = width.toLong() * height.toLong()
    val scanlineBytes = width.toLong() * Int.SIZE_BYTES
    return TransformedImageWorkingSet(
        maxConcurrentFullResolutionBitmaps = 2,
        fullPageArgbArrays = 0,
        scanlineBufferPixels = width,
        estimatedPeakArgbBytes = pixels * Int.SIZE_BYTES * 2L + scanlineBytes + 256L * Int.SIZE_BYTES,
    )
}

private fun OutputStream.closeSafely() {
    try {
        close()
    } catch (_: IOException) {
        // Nothing user-actionable, and document details must not be logged.
    }
}
