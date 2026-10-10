package org.synapseworks.pageharbor.document.sharesafe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.core.graphics.createBitmap
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
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
import org.synapseworks.pageharbor.document.session.DocumentImageMetadata
import org.synapseworks.pageharbor.document.session.DocumentSourceCategory
import org.synapseworks.pageharbor.document.targetsize.LocalTargetSizePdfEngine
import org.synapseworks.pageharbor.document.targetsize.TargetSizeCancellationSignal
import org.synapseworks.pageharbor.document.targetsize.TargetSizePageContentProfile
import org.synapseworks.pageharbor.document.targetsize.TargetSizePdfPage
import org.synapseworks.pageharbor.document.targetsize.TargetSizePdfRequest
import org.synapseworks.pageharbor.document.targetsize.TargetSizePdfResult

internal sealed interface ShareSafeTargetSizeResult {
    enum class Outcome {
        ALREADY_FITS,
        COMPRESSED_TO_TARGET,
    }

    data class Success(
        val artifact: ShareSafeAttestedArtifact,
        val outcome: Outcome,
        val elapsedMillis: Long,
        val peakTemporaryBytes: Long,
        val estimatedPeakBitmapBytes: Long,
    ) : ShareSafeTargetSizeResult

    data class Failure(val reason: ShareSafeFailure) : ShareSafeTargetSizeResult
}

/** Typed M7-to-M4 bridge. It renders only an attested sanitized artifact into M4 page inputs. */
internal class ShareSafeTargetSizeAdapter(context: Context) {
    private val applicationContext = context.applicationContext ?: context
    private val verifier = RedactedPdfVerifier(applicationContext)
    private val targetEngine = LocalTargetSizePdfEngine(
        context = applicationContext,
        openPageInputStream = { uri ->
            if (uri.scheme != "file") null else runCatching {
                File(requireNotNull(uri.path)).inputStream()
            }.getOrNull()
        },
    )
    private val root = File(applicationContext.cacheDir, SHARE_SAFE_WORK_DIRECTORY)

    init {
        PDFBoxResourceLoader.init(applicationContext)
    }

    suspend fun compress(
        source: ShareSafeAttestedArtifact,
        targetBytes: Long,
        expectedGeneration: Long,
        cancellationSignal: TargetSizeCancellationSignal = TargetSizeCancellationSignal { false },
    ): ShareSafeTargetSizeResult = withContext(Dispatchers.IO) {
        if (!source.revalidate(verifier, expectedGeneration)) {
            return@withContext ShareSafeTargetSizeResult.Failure(
                ShareSafeFailure.ATTESTATION_FAILED,
            )
        }
        if (source.evidence.bytes <= targetBytes) {
            return@withContext ShareSafeTargetSizeResult.Success(
                artifact = source.copy(targetBytes = targetBytes),
                outcome = ShareSafeTargetSizeResult.Outcome.ALREADY_FITS,
                elapsedMillis = 0L,
                peakTemporaryBytes = source.evidence.bytes,
                estimatedPeakBitmapBytes = 0L,
            )
        }
        val working = createWorkingDirectory()
            ?: return@withContext ShareSafeTargetSizeResult.Failure(
                ShareSafeFailure.PREPARATION_FAILED,
            )
        val started = SystemClock.elapsedRealtime()
        var peakTemporaryBytes = 0L
        var peakBitmapBytes = 0L
        try {
            val pages = renderAttestedPages(source, working, cancellationSignal) { bitmapBytes ->
                peakBitmapBytes = max(peakBitmapBytes, bitmapBytes)
                peakTemporaryBytes = max(peakTemporaryBytes, working.directoryBytes())
            } ?: return@withContext failed(working, ShareSafeFailure.PREPARATION_FAILED)
            if (!source.revalidate(verifier, expectedGeneration)) {
                return@withContext failed(working, ShareSafeFailure.ATTESTATION_FAILED)
            }
            val result = targetEngine.prepare(
                TargetSizePdfRequest(
                    pages = pages.mapIndexed { index, page ->
                        TargetSizePdfPage(
                            pageId = index.toLong() + 1L,
                            sourceUri = Uri.fromFile(page.file),
                            sourceCategory = DocumentSourceCategory.SELECTED_IMAGE,
                            contentType = "image/jpeg",
                            imageMetadata = DocumentImageMetadata(
                                sourceByteCount = page.file.length(),
                                width = page.width,
                                height = page.height,
                            ),
                            effectiveOcr = null,
                            contentProfile = TargetSizePageContentProfile.MIXED_TEXT_PHOTO,
                        )
                    },
                    targetBytes = targetBytes,
                    cancellationSignal = cancellationSignal,
                ),
            )
            pages.forEach { it.file.delete() }
            working.deleteRecursively()
            val prepared = when (result) {
                is TargetSizePdfResult.TargetReached -> result.prepared
                is TargetSizePdfResult.TargetNotReachableAtAcceptableQuality -> {
                    result.smallestAcceptable?.let(targetEngine::discard)
                    return@withContext ShareSafeTargetSizeResult.Failure(
                        ShareSafeFailure.TARGET_NOT_REACHABLE,
                    )
                }
                is TargetSizePdfResult.Failure -> return@withContext ShareSafeTargetSizeResult.Failure(
                    if (result.reason.name == "CANCELED") ShareSafeFailure.CANCELED
                    else ShareSafeFailure.PREPARATION_FAILED,
                )
            }
            if (prepared.finalBytes > targetBytes ||
                !source.revalidate(verifier, expectedGeneration)
            ) {
                targetEngine.discard(prepared)
                return@withContext ShareSafeTargetSizeResult.Failure(
                    ShareSafeFailure.ATTESTATION_FAILED,
                )
            }
            val policy = policyForM4Output(prepared.file, source.policy)
            if (policy == null) {
                targetEngine.discard(prepared)
                return@withContext ShareSafeTargetSizeResult.Failure(
                    ShareSafeFailure.FINAL_VERIFICATION_FAILED,
                )
            }
            val verified = verifier.verify(prepared.file, policy)
                as? RedactedPdfVerificationResult.Verified
            if (verified == null || verified.evidence.bytes > targetBytes) {
                targetEngine.discard(prepared)
                return@withContext ShareSafeTargetSizeResult.Failure(
                    ShareSafeFailure.FINAL_VERIFICATION_FAILED,
                )
            }
            ShareSafeTargetSizeResult.Success(
                artifact = ShareSafeAttestedArtifact(
                    file = prepared.file,
                    ownedDirectory = prepared.workingDirectory,
                    policy = policy,
                    evidence = verified.evidence,
                    sourceGeneration = expectedGeneration,
                    stage = ShareSafeArtifactStage.TARGET_SIZED,
                    watermarkConfigDigest = source.watermarkConfigDigest,
                    targetBytes = targetBytes,
                ),
                outcome = ShareSafeTargetSizeResult.Outcome.COMPRESSED_TO_TARGET,
                elapsedMillis = SystemClock.elapsedRealtime() - started,
                peakTemporaryBytes = max(
                    peakTemporaryBytes,
                    prepared.performance.peakTemporaryBytes,
                ),
                estimatedPeakBitmapBytes = peakBitmapBytes,
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

    private suspend fun renderAttestedPages(
        source: ShareSafeAttestedArtifact,
        working: File,
        cancellationSignal: TargetSizeCancellationSignal,
        onPageComplete: (Long) -> Unit,
    ): List<RenderedPage>? {
        val descriptor = runCatching {
            ParcelFileDescriptor.open(source.file, ParcelFileDescriptor.MODE_READ_ONLY)
        }.getOrNull() ?: return null
        val output = mutableListOf<RenderedPage>()
        descriptor.use { fileDescriptor ->
            PdfRenderer(fileDescriptor).use { renderer ->
                if (renderer.pageCount != source.evidence.pageCount) return null
                repeat(renderer.pageCount) { pageIndex ->
                    coroutineContext.ensureActive()
                    if (cancellationSignal.isCancellationRequested()) return null
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
                            val file = File(working, "m4-input-$pageIndex.jpg")
                            FileOutputStream(file).use { stream ->
                                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 96, stream)) return null
                                stream.fd.sync()
                            }
                            output += RenderedPage(file, width, height)
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

    /** Builds expectations from M4's own fresh one-image-per-page output, never from source input. */
    private fun policyForM4Output(
        file: File,
        inherited: RedactedPdfVerificationPolicy,
    ): RedactedPdfVerificationPolicy? = try {
        val scratch = file.parentFile ?: return null
        PDDocument.load(
            file,
            MemoryUsageSetting.setupMixed(8L * 1_024L * 1_024L).setTempDir(scratch),
        ).use { document ->
            if (document.numberOfPages != inherited.pages.size) return null
            val pages = document.pages.mapIndexed { index, page ->
                val imageNames = page.resources.xObjectNames.filter { name ->
                    page.resources.getXObject(name) is PDImageXObject
                }
                if (imageNames.size != 1) return null
                val image = page.resources.getXObject(imageNames.single()) as PDImageXObject
                val rawHash = image.cosObject.createRawInputStream().use(::sha256Stream)
                val box = page.mediaBox
                RedactedPdfPageExpectation(
                    widthPoints = box.width,
                    heightPoints = box.height,
                    expectedImages = listOf(
                        RedactedPdfImageExpectation(
                            leftPoints = 0f,
                            bottomPoints = 0f,
                            widthPoints = box.width,
                            heightPoints = box.height,
                            pixelWidth = image.width,
                            pixelHeight = image.height,
                            encodedSha256 = rawHash,
                        ),
                    ),
                    requiredOpaqueBlackRegions = inherited.pages[index]
                        .requiredOpaqueBlackRegions,
                )
            }
            RedactedPdfVerificationPolicy(pages, inherited.forbiddenSentinels)
        }
    } catch (_: Exception) {
        null
    }

    private fun sha256Stream(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(32 * 1_024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }

    private fun createWorkingDirectory(): File? {
        return try {
            if (!root.exists() && !root.mkdirs()) return null
            File.createTempFile("share-safe-m4-input-", ".work", root).also { marker ->
                if (!marker.delete() || !marker.mkdir()) return null
            }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private fun failed(directory: File, reason: ShareSafeFailure): ShareSafeTargetSizeResult.Failure {
        directory.deleteRecursively()
        return ShareSafeTargetSizeResult.Failure(reason)
    }

    private data class RenderedPage(val file: File, val width: Int, val height: Int)

    private companion object {
        const val DEFAULT_RENDER_SCALE = 2.5f
        const val MAX_RENDER_LONG_EDGE_PX = 2_400
    }
}
