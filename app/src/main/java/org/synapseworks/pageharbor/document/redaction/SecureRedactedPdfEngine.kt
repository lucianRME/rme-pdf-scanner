package org.synapseworks.pageharbor.document.redaction

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.synapseworks.pageharbor.document.PageExportResult
import org.synapseworks.pageharbor.document.SizedJpegEncoding
import org.synapseworks.pageharbor.document.exifOrientationSwapsAxes
import org.synapseworks.pageharbor.document.exifOrientationIsIdentity
import org.synapseworks.pageharbor.document.readImageBounds
import org.synapseworks.pageharbor.document.readOrientation
import org.synapseworks.pageharbor.document.searchablepdf.PdfBoxSearchablePdfGenerator
import org.synapseworks.pageharbor.document.searchablepdf.SearchablePdfGenerationResult
import org.synapseworks.pageharbor.document.searchablepdf.SearchablePdfGenerator
import org.synapseworks.pageharbor.document.searchablepdf.SearchablePdfPage
import org.synapseworks.pageharbor.document.searchablepdf.SearchablePdfRequest
import org.synapseworks.pageharbor.document.session.DocumentImageMetadata
import org.synapseworks.pageharbor.document.session.DocumentPageRotation
import org.synapseworks.pageharbor.document.writeSizedJpegToDestination
import org.synapseworks.pageharbor.image.DocumentFilter
import org.synapseworks.pageharbor.ocr.OcrPageResult

/** One immutable effective visual page plus workflow-local, source-normalized redactions. */
internal data class SecureRedactedPdfPage(
    val pageId: String,
    val openSource: () -> InputStream?,
    val imageMetadata: DocumentImageMetadata,
    val expectedSourceSha256: String,
    val regions: List<RedactionRegion>,
    val filter: DocumentFilter = DocumentFilter.ORIGINAL,
    val rotation: DocumentPageRotation = DocumentPageRotation.DEGREES_0,
    /** Null means no effective text. A nonnull result with null layout is unpositioned text. */
    val effectiveOcr: OcrPageResult? = null,
    /** Captured with the OCR artifact; absent or mismatched provenance removes page text. */
    val ocrProvenance: SecureRedactedOcrProvenance? = null,
) {
    init {
        require(pageId.isNotBlank())
        require(expectedSourceSha256.matches(LOWERCASE_SHA_256))
    }
}

internal data class SecureRedactedOcrProvenance(
    val recognizedSourceSha256: String,
    val effectiveWidthPx: Int,
    val effectiveHeightPx: Int,
    val rotation: DocumentPageRotation,
) {
    init {
        require(recognizedSourceSha256.matches(LOWERCASE_SHA_256))
        require(effectiveWidthPx > 0 && effectiveHeightPx > 0)
    }
}

internal data class SecureRedactedPdfRequest(val pages: List<SecureRedactedPdfPage>) {
    init {
        require(pages.map(SecureRedactedPdfPage::pageId).distinct().size == pages.size)
    }
}

internal class SecureRedactedPreparedPdf internal constructor(
    internal val file: File,
    internal val workingDirectory: File,
    val pageCount: Int,
    val textLayerPageCount: Int,
    val omittedUnsafeTextPageCount: Int,
    val redactionCount: Int,
    val sourceHashesVerified: Boolean,
    val securelyVerified: Boolean,
    val verificationEvidence: RedactedPdfVerificationEvidence,
    val performance: SecureRedactionPerformance,
)

internal data class SecureRedactionPerformance(
    val sourceRenderAndRedactionMillis: Long,
    val pdfRebuildMillis: Long,
    val verificationMillis: Long,
    val peakTemporaryBytes: Long,
    val estimatedPeakBitmapBytes: Long,
) {
    init {
        require(sourceRenderAndRedactionMillis >= 0L)
        require(pdfRebuildMillis >= 0L)
        require(verificationMillis >= 0L)
        require(peakTemporaryBytes >= 0L)
        require(estimatedPeakBitmapBytes >= 0L)
    }
}

internal sealed interface SecureRedactedPdfResult {
    data class Success(val prepared: SecureRedactedPreparedPdf) : SecureRedactedPdfResult
    data class Failure(val reason: SecureRedactedPdfFailure) : SecureRedactedPdfResult
}

internal enum class SecureRedactedPdfFailure {
    EMPTY_REQUEST,
    INVALID_REDACTION,
    SOURCE_MISSING,
    SOURCE_TOO_LARGE,
    SOURCE_CHANGED,
    RASTERIZATION_FAILED,
    TEXT_SANITIZATION_FAILED,
    TEMPORARY_STORAGE_UNAVAILABLE,
    GENERATION_FAILED,
    SECURITY_VERIFICATION_FAILED,
}

internal data class SecureRedactedPdfVerificationPage(
    val pageId: String,
    val width: Int,
    val height: Int,
    val regions: List<RedactionRegion>,
    val rotation: DocumentPageRotation,
    val safeOcr: OcrPageResult?,
    val sanitizedJpegSha256: String,
)

/**
 * Rebuilds a PDF only from destructively sanitized raster pages and sanitized OCR. Original PDF
 * objects, source JPEG streams, annotations, attachments, metadata, and unpositioned text never
 * cross the generator boundary. Processing remains one decoded page at a time.
 */
internal class SecureRedactedPdfEngine(
    context: Context,
    private val generator: SearchablePdfGenerator = PdfBoxSearchablePdfGenerator(context),
    private val verifier: RedactedPdfVerifier = RedactedPdfVerifier(context),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val applicationContext = context.applicationContext ?: context
    private val rootDirectory = File(applicationContext.cacheDir, SECURE_REDACTED_PDF_DIRECTORY)

    suspend fun prepare(request: SecureRedactedPdfRequest): SecureRedactedPdfResult {
        val ownedDirectories = linkedSetOf<File>()
        var ownershipTransferred = false
        try {
            val result = withContext(ioDispatcher) {
                val operationContext = coroutineContext
                if (request.pages.isEmpty()) {
                    return@withContext SecureRedactedPdfResult.Failure(
                        SecureRedactedPdfFailure.EMPTY_REQUEST,
                    )
                }
                if (request.pages.sumOf { it.regions.size } == 0) {
                    return@withContext SecureRedactedPdfResult.Failure(
                        SecureRedactedPdfFailure.INVALID_REDACTION,
                    )
                }
                val workingDirectory = createWorkingDirectory()
                    ?: return@withContext SecureRedactedPdfResult.Failure(
                        SecureRedactedPdfFailure.TEMPORARY_STORAGE_UNAVAILABLE,
                    )
                ownedDirectories += workingDirectory
                val outputFile = File(workingDirectory, SECURE_REDACTED_PDF_NAME)
                val sanitizedPageFiles = mutableListOf<File>()
                val verificationPages = mutableListOf<SecureRedactedPdfVerificationPage>()
                var omittedUnsafeTextPages = 0
                var renderAndRedactionMillis = 0L
                var pdfRebuildMillis = 0L
                var verificationMillis = 0L
                var peakTemporaryBytes = 0L
                var estimatedPeakBitmapBytes = 0L
                try {
                    request.pages.forEachIndexed { index, page ->
                        coroutineContext.ensureActive()
                        if (!page.hasValidRedactionContract()) {
                            return@withContext failed(
                                workingDirectory,
                                SecureRedactedPdfFailure.INVALID_REDACTION,
                            )
                        }
                        val hashBefore = sha256(page.openSource)
                            ?: return@withContext failed(
                                workingDirectory,
                                SecureRedactedPdfFailure.SOURCE_MISSING,
                            )
                        if (hashBefore != page.expectedSourceSha256) {
                            return@withContext failed(
                                workingDirectory,
                                SecureRedactedPdfFailure.SOURCE_CHANGED,
                            )
                        }
                        val bounds = readImageBounds(page.openSource)
                            ?: return@withContext failed(
                                workingDirectory,
                                SecureRedactedPdfFailure.SOURCE_MISSING,
                            )
                        val exifOrientation = readOrientation(page.openSource)
                        val effectiveDimensions = effectiveDimensions(
                            bounds = bounds,
                            exifOrientation = exifOrientation,
                            rotation = page.rotation,
                        )
                        estimatedPeakBitmapBytes = maxOf(
                            estimatedPeakBitmapBytes,
                            effectiveDimensions.first.toLong() * effectiveDimensions.second *
                                Int.SIZE_BYTES * 2L,
                        )
                        val sanitizedFile = File(workingDirectory, "sanitized-page-$index.jpg")
                        var rasterResult: RedactionRasterResult? = null
                        val rasterStarted = SystemClock.elapsedRealtime()
                        val writeResult = try {
                            writeSizedJpegToDestination(
                                openSource = page.openSource,
                                destination = FileOutputStream(sanitizedFile),
                                filter = page.filter,
                                rotation = page.rotation,
                                imageMetadata = page.imageMetadata,
                                encoding = SizedJpegEncoding(
                                    maximumWidthPx = effectiveDimensions.first,
                                    maximumHeightPx = effectiveDimensions.second,
                                    quality = SECURE_JPEG_QUALITY,
                                ),
                                isCancellationRequested = { !operationContext.isActive },
                                pixelSanitizer = { bitmap ->
                                    RedactionRasterizer.applyOpaqueBlack(
                                        bitmap = bitmap,
                                        regions = page.regions,
                                        sourceTargetId = page.pageId,
                                        rotation = page.rotation,
                                        targetKind = RedactionTargetKind.DOCUMENT_PAGE,
                                        isCancellationRequested = { !operationContext.isActive },
                                    ).also { result -> rasterResult = result } is RedactionRasterResult.Success
                                },
                            )
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            PageExportResult.WriteFailed
                        }
                        renderAndRedactionMillis += SystemClock.elapsedRealtime() - rasterStarted
                        when (writeResult) {
                            PageExportResult.Success -> Unit
                            PageExportResult.SourceTooLarge -> return@withContext failed(
                                workingDirectory,
                                SecureRedactedPdfFailure.SOURCE_TOO_LARGE,
                            )
                            PageExportResult.SourceMissing -> return@withContext failed(
                                workingDirectory,
                                SecureRedactedPdfFailure.SOURCE_MISSING,
                            )
                            PageExportResult.DestinationUnavailable,
                            PageExportResult.WriteFailed,
                            -> return@withContext failed(
                                workingDirectory,
                                SecureRedactedPdfFailure.RASTERIZATION_FAILED,
                            )
                        }
                        val expectedRegions = page.regions.size
                        if (
                            (rasterResult as? RedactionRasterResult.Success)?.appliedRegions !=
                            expectedRegions || !sanitizedFile.isFile || sanitizedFile.length() <= 0L
                        ) {
                            return@withContext failed(
                                workingDirectory,
                                SecureRedactedPdfFailure.RASTERIZATION_FAILED,
                            )
                        }
                        val sanitizedJpegSha256 = sha256(sanitizedFile::inputStream)
                            ?: return@withContext failed(
                                workingDirectory,
                                SecureRedactedPdfFailure.RASTERIZATION_FAILED,
                            )
                        coroutineContext.ensureActive()
                        val hashAfter = sha256(page.openSource)
                        if (hashAfter == null || hashAfter != hashBefore) {
                            return@withContext failed(
                                workingDirectory,
                                SecureRedactedPdfFailure.SOURCE_CHANGED,
                            )
                        }
                        val textResult = RedactionTextSanitizer.sanitize(
                            page = page.effectiveOcr,
                            regions = page.regions,
                            sourceTargetId = page.pageId,
                            rotation = page.rotation,
                            // Existing OCR artifacts do not record EXIF orientation. Never assert that
                            // such geometry matches an EXIF-normalized export. An absent orientation
                            // tag is represented as either UNDEFINED or NORMAL and applies no transform.
                            geometryMatchesEffectiveSource = page.ocrProvenance?.let { provenance ->
                                provenance.recognizedSourceSha256 == page.expectedSourceSha256 &&
                                    provenance.effectiveWidthPx == effectiveDimensions.first &&
                                    provenance.effectiveHeightPx == effectiveDimensions.second &&
                                    provenance.rotation == page.rotation &&
                                    page.effectiveOcr?.layout?.let { layout ->
                                        layout.imageWidthPx == provenance.effectiveWidthPx &&
                                            layout.imageHeightPx == provenance.effectiveHeightPx
                                    } == true &&
                                exifOrientationIsIdentity(exifOrientation)
                            } == true,
                            rasterWidthPx = effectiveDimensions.first,
                            rasterHeightPx = effectiveDimensions.second,
                        )
                        val safeOcr = when (textResult) {
                            is RedactionTextResult.Preserved -> textResult.page
                            is RedactionTextResult.Sanitized -> textResult.page
                            RedactionTextResult.OmittedUnsafeGeometry -> {
                                omittedUnsafeTextPages++
                                null
                            }
                            RedactionTextResult.InvalidMapping -> return@withContext failed(
                                workingDirectory,
                                SecureRedactedPdfFailure.TEXT_SANITIZATION_FAILED,
                            )
                        }
                        sanitizedPageFiles += sanitizedFile
                        peakTemporaryBytes = maxOf(
                            peakTemporaryBytes,
                            workingDirectory.walkTopDown().filter(File::isFile).sumOf(File::length),
                        )
                        verificationPages += SecureRedactedPdfVerificationPage(
                            pageId = page.pageId,
                            width = effectiveDimensions.first,
                            height = effectiveDimensions.second,
                            regions = page.regions,
                            rotation = page.rotation,
                            safeOcr = safeOcr,
                            sanitizedJpegSha256 = sanitizedJpegSha256,
                        )
                    }

                    coroutineContext.ensureActive()
                    if (request.pages.any { page ->
                            sha256(page.openSource) != page.expectedSourceSha256
                        }
                    ) {
                        return@withContext failed(
                            workingDirectory,
                            SecureRedactedPdfFailure.SOURCE_CHANGED,
                        )
                    }
                    val rebuildStarted = SystemClock.elapsedRealtime()
                    val generated = generator.generate(
                        SearchablePdfRequest(
                            pages = sanitizedPageFiles.mapIndexed { index, file ->
                                SearchablePdfPage(
                                    openJpegStream = file::inputStream,
                                    ocrResult = verificationPages[index].safeOcr,
                                )
                            },
                            outputFile = outputFile,
                            scratchDirectory = workingDirectory,
                        ),
                    )
                    val generation = generated as? SearchablePdfGenerationResult.Success
                        ?: return@withContext failed(
                            workingDirectory,
                            SecureRedactedPdfFailure.GENERATION_FAILED,
                    )
                    pdfRebuildMillis = SystemClock.elapsedRealtime() - rebuildStarted
                    peakTemporaryBytes = maxOf(
                        peakTemporaryBytes,
                        workingDirectory.walkTopDown().filter(File::isFile).sumOf(File::length),
                    )
                    sanitizedPageFiles.forEach(File::deleteSafely)
                    coroutineContext.ensureActive()
                    val verificationStarted = SystemClock.elapsedRealtime()
                    val verification = verifier.verify(
                        outputFile,
                        RedactedPdfVerificationPolicy(
                            pages = verificationPages.map { page ->
                                val regions = page.regions.confirmedSourceRegionsFor(
                                    page.pageId,
                                    RedactionTargetKind.DOCUMENT_PAGE,
                                ) ?: return@withContext failed(
                                    workingDirectory,
                                    SecureRedactedPdfFailure.SECURITY_VERIFICATION_FAILED,
                                )
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
                                            encodedSha256 = page.sanitizedJpegSha256,
                                        ),
                                    ),
                                    expectedExtractedText = page.safeOcr?.layout?.lines.orEmpty()
                                        .joinToString("\n") { line -> line.text },
                                    expectedFontTexts = page.safeOcr?.layout?.lines.orEmpty()
                                        .map { line -> line.text },
                                    requiredOpaqueBlackRegions = regions.map { region ->
                                        RedactionCoordinateMapper.rotate(
                                            region.bounds,
                                            page.rotation,
                                        )
                                    },
                                )
                            },
                        ),
                    )
                    val evidence = (verification as? RedactedPdfVerificationResult.Verified)?.evidence
                    verificationMillis = SystemClock.elapsedRealtime() - verificationStarted
                    if (evidence == null) {
                        return@withContext failed(
                            workingDirectory,
                            SecureRedactedPdfFailure.SECURITY_VERIFICATION_FAILED,
                        )
                    }
                    if (request.pages.any { page ->
                            sha256(page.openSource) != page.expectedSourceSha256
                        }
                    ) {
                        return@withContext failed(
                            workingDirectory,
                            SecureRedactedPdfFailure.SOURCE_CHANGED,
                        )
                    }
                    cleanupExcept(workingDirectory, outputFile)
                    SecureRedactedPdfResult.Success(
                        SecureRedactedPreparedPdf(
                            file = outputFile,
                            workingDirectory = workingDirectory,
                            pageCount = generation.pageCount,
                            textLayerPageCount = generation.textLayerPageCount,
                            omittedUnsafeTextPageCount = omittedUnsafeTextPages,
                            redactionCount = request.pages.sumOf { it.regions.size },
                            sourceHashesVerified = true,
                            securelyVerified = true,
                            verificationEvidence = evidence,
                            performance = SecureRedactionPerformance(
                                sourceRenderAndRedactionMillis = renderAndRedactionMillis,
                                pdfRebuildMillis = pdfRebuildMillis,
                                verificationMillis = verificationMillis,
                                peakTemporaryBytes = peakTemporaryBytes.coerceAtLeast(evidence.bytes),
                                estimatedPeakBitmapBytes = estimatedPeakBitmapBytes,
                            ),
                        ),
                    )
                } catch (error: CancellationException) {
                    workingDirectory.deleteRecursivelySafely()
                    throw error
                } catch (_: IOException) {
                    failed(workingDirectory, SecureRedactedPdfFailure.GENERATION_FAILED)
                } catch (_: SecurityException) {
                    failed(workingDirectory, SecureRedactedPdfFailure.GENERATION_FAILED)
                } catch (_: RuntimeException) {
                    failed(workingDirectory, SecureRedactedPdfFailure.GENERATION_FAILED)
                } catch (_: OutOfMemoryError) {
                    failed(workingDirectory, SecureRedactedPdfFailure.GENERATION_FAILED)
                }
            }
            ownershipTransferred = result is SecureRedactedPdfResult.Success
            return result
        } finally {
            if (!ownershipTransferred) {
                withContext(NonCancellable + ioDispatcher) {
                    ownedDirectories.forEach(File::deleteRecursivelySafely)
                }
            }
        }
    }

    fun discard(prepared: SecureRedactedPreparedPdf) {
        prepared.workingDirectory.deleteRecursivelySafely()
    }

    private fun SecureRedactedPdfPage.hasValidRedactionContract(): Boolean =
        regions.all { region ->
            region.confirmed &&
                region.coordinateSpace == RedactionCoordinateSpace.SOURCE_NORMALIZED &&
                region.target.kind == RedactionTargetKind.DOCUMENT_PAGE &&
                region.target.stableId == pageId
        }

    private fun createWorkingDirectory(): File? {
        return try {
            if (!rootDirectory.exists() && !rootDirectory.mkdirs()) return null
            File.createTempFile(SECURE_REDACTED_PDF_PREFIX, ".work", rootDirectory).also { marker ->
                if (!marker.delete() || !marker.mkdir()) return null
            }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private fun failed(
        directory: File,
        reason: SecureRedactedPdfFailure,
    ): SecureRedactedPdfResult.Failure {
        directory.deleteRecursivelySafely()
        return SecureRedactedPdfResult.Failure(reason)
    }
}

internal fun secureRedactionSha256(openSource: () -> InputStream?): String? = sha256(openSource)

internal fun deleteStaleSecureRedactedPdfs(
    cacheDirectory: File,
    nowMillis: Long = System.currentTimeMillis(),
    minimumAgeMillis: Long = SECURE_REDACTED_PDF_MAX_AGE_MILLIS,
) {
    require(minimumAgeMillis >= 0L)
    File(cacheDirectory, SECURE_REDACTED_PDF_DIRECTORY).listFiles()?.forEach { file ->
        if (file.lastModified() <= nowMillis &&
            nowMillis - file.lastModified() >= minimumAgeMillis
        ) {
            file.deleteRecursivelySafely()
        }
    }
}

private fun effectiveDimensions(
    bounds: Pair<Int, Int>,
    exifOrientation: Int,
    rotation: DocumentPageRotation,
): Pair<Int, Int> {
    val sessionSwaps = rotation == DocumentPageRotation.DEGREES_90 ||
        rotation == DocumentPageRotation.DEGREES_270
    return if (exifOrientationSwapsAxes(exifOrientation).xor(sessionSwaps)) {
        bounds.second to bounds.first
    } else {
        bounds
    }
}

private fun sha256(openSource: () -> InputStream?): String? {
    return try {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = openSource() ?: return null
        input.use { stream ->
            val buffer = ByteArray(32 * 1_024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    } catch (_: RuntimeException) {
        null
    }
}

private fun cleanupExcept(directory: File, retained: File) {
    directory.listFiles()?.forEach { file ->
        if (file != retained) {
            if (file.isDirectory) file.deleteRecursivelySafely() else file.deleteSafely()
        }
    }
}

private fun File.deleteSafely() {
    try {
        if (isFile) delete()
    } catch (_: SecurityException) {
        // Private cleanup is best effort and never logs sensitive names or paths.
    }
}

private fun File.deleteRecursivelySafely() {
    try {
        if (exists()) deleteRecursively()
    } catch (_: SecurityException) {
        // Private cleanup is best effort and never logs sensitive names or paths.
    }
}

private val LOWERCASE_SHA_256 = Regex("[0-9a-f]{64}")
private const val SECURE_REDACTED_PDF_DIRECTORY = "secure-redacted-pdfs"
private const val SECURE_REDACTED_PDF_PREFIX = "secure-redacted-"
private const val SECURE_REDACTED_PDF_NAME = "redacted.pdf"
private const val SECURE_REDACTED_PDF_MAX_AGE_MILLIS = 24L * 60L * 60L * 1_000L
private const val SECURE_JPEG_QUALITY = 100
