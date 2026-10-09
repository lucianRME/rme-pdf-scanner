package org.synapseworks.pageharbor.document.sharesafe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.core.graphics.createBitmap
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.BreakIterator
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.synapseworks.pageharbor.document.redaction.RedactedPdfImageExpectation
import org.synapseworks.pageharbor.document.redaction.RedactedPdfPageExpectation
import org.synapseworks.pageharbor.document.redaction.RedactedPdfVerificationPolicy
import org.synapseworks.pageharbor.document.redaction.RedactedPdfVerificationResult
import org.synapseworks.pageharbor.document.redaction.RedactedPdfVerifier
import org.synapseworks.pageharbor.document.searchablepdf.PdfBoxSearchablePdfGenerator
import org.synapseworks.pageharbor.document.searchablepdf.SearchablePdfGenerationResult
import org.synapseworks.pageharbor.document.searchablepdf.SearchablePdfPage
import org.synapseworks.pageharbor.document.searchablepdf.SearchablePdfRequest

internal sealed interface ShareSafeWatermarkResult {
    data class Success(
        val artifact: ShareSafeAttestedArtifact,
        val elapsedMillis: Long,
        val peakTemporaryBytes: Long,
        val estimatedPeakBitmapBytes: Long,
    ) : ShareSafeWatermarkResult

    data class Failure(val reason: ShareSafeFailure) : ShareSafeWatermarkResult
}

/** Raster-flattens a purpose onto already-sanitized pages and rebuilds a simple image-only PDF. */
internal class ShareSafeWatermarkEngine(context: Context) {
    private val applicationContext = context.applicationContext ?: context
    private val verifier = RedactedPdfVerifier(applicationContext)
    private val generator = PdfBoxSearchablePdfGenerator(applicationContext)
    private val root = File(applicationContext.cacheDir, SHARE_SAFE_WORK_DIRECTORY)

    suspend fun apply(
        source: ShareSafeAttestedArtifact,
        config: ShareSafeWatermarkConfig,
        expectedGeneration: Long,
    ): ShareSafeWatermarkResult = withContext(Dispatchers.IO) {
        if (!config.enabled || !config.isValid) {
            return@withContext ShareSafeWatermarkResult.Failure(ShareSafeFailure.INVALID_WATERMARK)
        }
        val text = config.renderedText
            ?: return@withContext ShareSafeWatermarkResult.Failure(ShareSafeFailure.INVALID_WATERMARK)
        if (!supportsEveryGlyph(text)) {
            return@withContext ShareSafeWatermarkResult.Failure(
                ShareSafeFailure.UNSUPPORTED_WATERMARK_GLYPH,
            )
        }
        if (!source.revalidate(verifier, expectedGeneration)) {
            return@withContext ShareSafeWatermarkResult.Failure(ShareSafeFailure.ATTESTATION_FAILED)
        }
        val working = createWorkingDirectory()
            ?: return@withContext ShareSafeWatermarkResult.Failure(
                ShareSafeFailure.PREPARATION_FAILED,
            )
        val started = SystemClock.elapsedRealtime()
        var peakTemporaryBytes = 0L
        var estimatedPeakBitmapBytes = 0L
        try {
            val pages = renderWatermarkedPages(source, text, config, working) { bitmapBytes ->
                estimatedPeakBitmapBytes = max(estimatedPeakBitmapBytes, bitmapBytes)
                peakTemporaryBytes = max(peakTemporaryBytes, working.directoryBytes())
            } ?: return@withContext failed(working, ShareSafeFailure.PREPARATION_FAILED)
            coroutineContext.ensureActive()
            val output = File(working, "watermarked.pdf")
            val generated = generator.generate(
                SearchablePdfRequest(
                    pages = pages.map { page -> SearchablePdfPage(page.file::inputStream) },
                    outputFile = output,
                    scratchDirectory = working,
                ),
            ) as? SearchablePdfGenerationResult.Success
                ?: return@withContext failed(working, ShareSafeFailure.PREPARATION_FAILED)
            if (generated.pageCount != source.evidence.pageCount) {
                return@withContext failed(working, ShareSafeFailure.FINAL_VERIFICATION_FAILED)
            }
            val policy = RedactedPdfVerificationPolicy(
                pages = pages.mapIndexed { index, page ->
                    RedactedPdfPageExpectation(
                        widthPoints = page.width.toFloat(),
                        heightPoints = page.height.toFloat(),
                        expectedImages = listOf(
                            RedactedPdfImageExpectation(
                                leftPoints = 0f,
                                bottomPoints = 0f,
                                widthPoints = page.width.toFloat(),
                                heightPoints = page.height.toFloat(),
                                pixelWidth = page.width,
                                pixelHeight = page.height,
                                encodedSha256 = requireNotNull(sha256(page.file)),
                            ),
                        ),
                        requiredOpaqueBlackRegions = source.policy.pages[index]
                            .requiredOpaqueBlackRegions,
                    )
                },
                forbiddenSentinels = source.policy.forbiddenSentinels,
            )
            pages.forEach { it.file.delete() }
            peakTemporaryBytes = max(peakTemporaryBytes, working.directoryBytes())
            val verification = verifier.verify(output, policy)
                as? RedactedPdfVerificationResult.Verified
                ?: return@withContext failed(working, ShareSafeFailure.FINAL_VERIFICATION_FAILED)
            if (!source.revalidate(verifier, expectedGeneration)) {
                return@withContext failed(working, ShareSafeFailure.ATTESTATION_FAILED)
            }
            working.listFiles()?.forEach { file -> if (file != output) file.deleteRecursively() }
            ShareSafeWatermarkResult.Success(
                artifact = ShareSafeAttestedArtifact(
                    file = output,
                    ownedDirectory = working,
                    policy = policy,
                    evidence = verification.evidence,
                    sourceGeneration = expectedGeneration,
                    stage = ShareSafeArtifactStage.WATERMARKED,
                    watermarkConfigDigest = config.digest(),
                ),
                elapsedMillis = SystemClock.elapsedRealtime() - started,
                peakTemporaryBytes = peakTemporaryBytes.coerceAtLeast(output.length()),
                estimatedPeakBitmapBytes = estimatedPeakBitmapBytes,
            )
        } catch (error: CancellationException) {
            working.deleteRecursively()
            throw error
        } catch (_: Exception) {
            failed(working, ShareSafeFailure.PREPARATION_FAILED)
        } catch (_: OutOfMemoryError) {
            failed(working, ShareSafeFailure.PREPARATION_FAILED)
        }
    }

    private suspend fun renderWatermarkedPages(
        source: ShareSafeAttestedArtifact,
        text: String,
        config: ShareSafeWatermarkConfig,
        working: File,
        onPageComplete: (Long) -> Unit,
    ): List<WatermarkedPage>? {
        val descriptor = try {
            ParcelFileDescriptor.open(source.file, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (_: Exception) {
            null
        } ?: return null
        val output = mutableListOf<WatermarkedPage>()
        descriptor.use { fileDescriptor ->
            PdfRenderer(fileDescriptor).use { renderer ->
                if (renderer.pageCount != source.evidence.pageCount) return null
                repeat(renderer.pageCount) { pageIndex ->
                    coroutineContext.ensureActive()
                    renderer.openPage(pageIndex).use { page ->
                        val scale = min(
                            MAX_RENDER_LONG_EDGE_PX.toFloat() / max(page.width, page.height),
                            DEFAULT_RENDER_SCALE,
                        ).coerceAtLeast(1f)
                        val width = (page.width * scale).roundToInt().coerceAtLeast(1)
                        val height = (page.height * scale).roundToInt().coerceAtLeast(1)
                        val bitmap = createBitmap(width, height)
                        try {
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            drawWatermark(bitmap, text, config)
                            val file = File(working, "watermarked-page-$pageIndex.jpg")
                            FileOutputStream(file).use { stream ->
                                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, WATERMARK_JPEG_QUALITY, stream)) {
                                    return null
                                }
                                stream.fd.sync()
                            }
                            output += WatermarkedPage(file, width, height)
                            onPageComplete(width.toLong() * height.toLong() * Int.SIZE_BYTES)
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
            }
        }
        return output
    }

    private fun drawWatermark(
        bitmap: Bitmap,
        text: String,
        config: ShareSafeWatermarkConfig,
    ) {
        val canvas = Canvas(bitmap)
        val lines = text.lines()
        ShareSafeWatermarkGeometry.stamps(config.placement).forEach { stamp ->
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                alpha = config.opacity.alpha
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textSize = min(bitmap.width, bitmap.height) * stamp.textSizeFraction
            }
            val maximumWidth = bitmap.width * stamp.maximumWidthFraction
            val widest = lines.maxOf(paint::measureText).coerceAtLeast(1f)
            if (widest > maximumWidth) paint.textSize *= maximumWidth / widest
            val metrics = paint.fontMetrics
            val lineHeight = (metrics.descent - metrics.ascent) * 1.08f
            val blockHeight = lineHeight * lines.size
            canvas.save()
            canvas.rotate(
                stamp.rotationDegrees,
                bitmap.width * stamp.centerX,
                bitmap.height * stamp.centerY,
            )
            val firstBaseline = bitmap.height * stamp.centerY - blockHeight / 2f - metrics.ascent
            lines.forEachIndexed { index, line ->
                canvas.drawText(
                    line,
                    bitmap.width * stamp.centerX,
                    firstBaseline + index * lineHeight,
                    paint,
                )
            }
            canvas.restore()
        }
    }

    private fun supportsEveryGlyph(text: String): Boolean {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT }
        return text.lines().all { line ->
            if (line.isEmpty()) return@all false
            val iterator = BreakIterator.getCharacterInstance().apply { setText(line) }
            var start = iterator.first()
            var end = iterator.next()
            while (end != BreakIterator.DONE) {
                val cluster = line.substring(start, end)
                if (!cluster.all(Char::isWhitespace) && !paint.hasGlyph(cluster)) return@all false
                start = end
                end = iterator.next()
            }
            true
        }
    }

    private fun createWorkingDirectory(): File? {
        return try {
            if (!root.exists() && !root.mkdirs()) return null
            File.createTempFile("share-safe-watermark-", ".work", root).also { marker ->
                if (!marker.delete() || !marker.mkdir()) return null
            }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private fun failed(directory: File, reason: ShareSafeFailure): ShareSafeWatermarkResult.Failure {
        directory.deleteRecursively()
        return ShareSafeWatermarkResult.Failure(reason)
    }

    private data class WatermarkedPage(val file: File, val width: Int, val height: Int)

    private companion object {
        const val DEFAULT_RENDER_SCALE = 2.5f
        const val MAX_RENDER_LONG_EDGE_PX = 2_400
        const val WATERMARK_JPEG_QUALITY = 94
    }
}

internal fun File.directoryBytes(): Long =
    walkTopDown().filter(File::isFile).sumOf(File::length)

internal const val SHARE_SAFE_WORK_DIRECTORY = "share-safe-work"
