package org.synapseworks.pageharbor.document.cardcopy

import android.content.Context
import android.os.SystemClock
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.synapseworks.pageharbor.document.PageExportResult
import org.synapseworks.pageharbor.document.SizedJpegEncoding
import org.synapseworks.pageharbor.document.readImageBounds
import org.synapseworks.pageharbor.document.writeSizedJpegToDestination
import org.synapseworks.pageharbor.image.DocumentFilter
import org.synapseworks.pageharbor.document.redaction.RedactionRasterResult
import org.synapseworks.pageharbor.document.redaction.RedactionRasterizer
import org.synapseworks.pageharbor.document.redaction.RedactedPdfPageExpectation
import org.synapseworks.pageharbor.document.redaction.RedactedPdfImageExpectation
import org.synapseworks.pageharbor.document.redaction.RedactedPdfVerificationPolicy
import org.synapseworks.pageharbor.document.redaction.RedactedPdfVerificationResult
import org.synapseworks.pageharbor.document.redaction.RedactedPdfVerifier
import org.synapseworks.pageharbor.document.redaction.RedactionBounds
import org.synapseworks.pageharbor.document.redaction.RedactionTargetKind
import org.synapseworks.pageharbor.document.redaction.confirmedSourceRegionsFor

internal sealed interface CardCopyPrepareResult {
    data class Success(val prepared: CardCopyPreparedPdf) : CardCopyPrepareResult
    data class Failure(val reason: CardCopyFailure) : CardCopyPrepareResult
}

/**
 * Produces a one-page flattened PDF. Geometry is expressed in physical PDF points while each side
 * is decoded sequentially to at most 180 pixels/inch at its final placement size. This preserves
 * fine card text without retaining two full-resolution source bitmaps or claiming printer DPI.
 */
internal class CardCopyPdfComposer(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val redactedPdfVerifier = RedactedPdfVerifier(context)

    suspend fun prepare(
        front: CardCopySource,
        back: CardCopySource,
        settings: CardCopySettings,
    ): CardCopyPrepareResult {
        val ownedDirectories = linkedSetOf<File>()
        var ownershipTransferred = false
        try {
            val result = withContext(ioDispatcher) {
                val operationContext = coroutineContext
                val sources = listOf(front, back)
                if (sources.any { source ->
                        cardCopySha256(source.file) != source.sourceSha256 ||
                            source.redactions.confirmedSourceRegionsFor(
                                source.sourceId,
                                RedactionTargetKind.CARD_SOURCE,
                            ) == null ||
                            source.redactions.any { region ->
                                region.target.kind != RedactionTargetKind.CARD_SOURCE ||
                                    region.target.stableId != source.sourceId
                            }
                    }
                ) {
                    return@withContext CardCopyPrepareResult.Failure(
                        if (sources.any { cardCopySha256(it.file) != it.sourceSha256 }) {
                            CardCopyFailure.SOURCE_CHANGED
                        } else {
                            CardCopyFailure.INVALID_REDACTION
                        },
                    )
                }
                val geometry = CardCopyGeometryEngine.calculate(
                    requireNotNull(front.metadata.width), requireNotNull(front.metadata.height), front.rotation,
                    requireNotNull(back.metadata.width), requireNotNull(back.metadata.height), back.rotation,
                    settings,
                )
                val root = File(context.cacheDir, CARD_COPY_WORK_DIRECTORY).apply { mkdirs() }
                val working = try {
                    File.createTempFile("card-copy-", ".work", root).also { marker ->
                        if (!marker.delete() || !marker.mkdir()) {
                            return@withContext CardCopyPrepareResult.Failure(CardCopyFailure.PREPARATION_FAILED)
                        }
                        ownedDirectories += marker
                    }
                } catch (_: IOException) {
                    return@withContext CardCopyPrepareResult.Failure(CardCopyFailure.PREPARATION_FAILED)
                }
                val output = File(working, "card-copy.pdf")
                val started = SystemClock.elapsedRealtime()
                var peakTemporaryBytes = 0L
                var estimatedPeakBitmapBytes = 0L
                var verifiedDirectory: File? = null
                val sanitizedImageExpectations = mutableListOf<RedactedPdfImageExpectation>()
                try {
                    PDFBoxResourceLoader.init(context)
                    val memoryUsage = MemoryUsageSetting.setupMixed(8L * 1_024L * 1_024L)
                        .setTempDir(working)
                    PDDocument(memoryUsage).use { document ->
                        val page = PDPage(
                            PDRectangle(settings.pageSize.widthPoints, settings.pageSize.heightPoints),
                        )
                        document.addPage(page)
                        PDPageContentStream(document, page).use { content ->
                            content.setNonStrokingColor(255, 255, 255)
                            content.addRect(0f, 0f, settings.pageSize.widthPoints, settings.pageSize.heightPoints)
                            content.fill()
                            listOf(front to geometry.front, back to geometry.back).forEachIndexed { index, pair ->
                                coroutineContext.ensureActive()
                                val encoded = File(working, "side-$index.jpg")
                                val bounds = pair.second.bounds
                                val widthPx = pointsToPixels(bounds.width)
                                val heightPx = pointsToPixels(bounds.height)
                                estimatedPeakBitmapBytes = maxOf(
                                    estimatedPeakBitmapBytes,
                                    widthPx.toLong() * heightPx.toLong() * 4L * 2L,
                                )
                                val result = writeSizedJpegToDestination(
                                    openSource = { pair.first.file.inputStream() },
                                    destination = FileOutputStream(encoded),
                                    filter = DocumentFilter.ORIGINAL,
                                    rotation = pair.first.rotation,
                                    imageMetadata = pair.first.metadata,
                                    encoding = SizedJpegEncoding(widthPx, heightPx, COMPOSITION_JPEG_QUALITY),
                                    isCancellationRequested = { !operationContext.isActive },
                                    pixelSanitizer = { bitmap ->
                                        RedactionRasterizer.applyOpaqueBlack(
                                            bitmap = bitmap,
                                            regions = pair.first.redactions,
                                            sourceTargetId = pair.first.sourceId,
                                            rotation = pair.first.rotation,
                                            isCancellationRequested = { !operationContext.isActive },
                                        ) is RedactionRasterResult.Success
                                    },
                                )
                                if (result != PageExportResult.Success) {
                                    throw CardCopyCompositionException(
                                        if (result == PageExportResult.SourceTooLarge) {
                                            CardCopyFailure.SOURCE_TOO_LARGE
                                        } else {
                                            CardCopyFailure.PREPARATION_FAILED
                                        },
                                    )
                                }
                                val encodedSha256 = cardCopySha256(encoded)
                                    ?: throw CardCopyCompositionException(
                                        CardCopyFailure.PREPARATION_FAILED,
                                    )
                                val encodedDimensions = readImageBounds(encoded::inputStream)
                                    ?: throw CardCopyCompositionException(
                                        CardCopyFailure.PREPARATION_FAILED,
                                    )
                                sanitizedImageExpectations += RedactedPdfImageExpectation(
                                    leftPoints = bounds.left,
                                    bottomPoints = settings.pageSize.heightPoints - bounds.bottom,
                                    widthPoints = bounds.width,
                                    heightPoints = bounds.height,
                                    pixelWidth = encodedDimensions.first,
                                    pixelHeight = encodedDimensions.second,
                                    encodedSha256 = encodedSha256,
                                )
                                peakTemporaryBytes = maxOf(
                                    peakTemporaryBytes,
                                    working.walkTopDown().filter(File::isFile).sumOf(File::length),
                                )
                                val image = encoded.inputStream().use { JPEGFactory.createFromStream(document, it) }
                                content.drawImage(
                                    image,
                                    bounds.left,
                                    settings.pageSize.heightPoints - bounds.bottom,
                                    bounds.width,
                                    bounds.height,
                                )
                                encoded.delete()
                            }
                        }
                        document.save(output)
                    }
                    if (sources.any { cardCopySha256(it.file) != it.sourceSha256 }) {
                        working.deleteRecursively()
                        return@withContext CardCopyPrepareResult.Failure(CardCopyFailure.SOURCE_CHANGED)
                    }
                    val verificationPolicy = verificationPolicy(
                        geometry,
                        sources,
                        sanitizedImageExpectations,
                    )
                    val verification = redactedPdfVerifier.verify(output, verificationPolicy)
                    val evidence = (verification as? RedactedPdfVerificationResult.Verified)?.evidence
                        ?: run {
                            working.deleteRecursively()
                            return@withContext CardCopyPrepareResult.Failure(
                                CardCopyFailure.SECURITY_VERIFICATION_FAILED,
                            )
                        }
                    if (sources.any { cardCopySha256(it.file) != it.sourceSha256 }) {
                        working.deleteRecursively()
                        return@withContext CardCopyPrepareResult.Failure(CardCopyFailure.SOURCE_CHANGED)
                    }
                    val published = publishVerified(output) ?: run {
                        working.deleteRecursively()
                        return@withContext CardCopyPrepareResult.Failure(CardCopyFailure.PREPARATION_FAILED)
                    }
                    verifiedDirectory = published.parentFile
                    ownedDirectories += requireNotNull(verifiedDirectory)
                    working.deleteRecursively()
                    if (!redactedPdfVerifier.stillMatches(published, evidence)) {
                        verifiedDirectory?.deleteRecursively()
                        return@withContext CardCopyPrepareResult.Failure(
                            CardCopyFailure.SECURITY_VERIFICATION_FAILED,
                        )
                    }
                    CardCopyPrepareResult.Success(
                        CardCopyPreparedPdf(
                            file = published,
                            workingDirectory = requireNotNull(verifiedDirectory),
                            bytes = evidence.bytes,
                            geometry = geometry,
                            preparationMillis = SystemClock.elapsedRealtime() - started,
                            peakTemporaryBytes = peakTemporaryBytes.coerceAtLeast(evidence.bytes),
                            estimatedPeakBitmapBytes = estimatedPeakBitmapBytes,
                            redactionCount = sources.sumOf { it.redactions.size },
                            securelyVerified = true,
                            verificationPolicy = verificationPolicy,
                            verificationEvidence = evidence,
                        ),
                    )
                } catch (error: CancellationException) {
                    working.deleteRecursively()
                    verifiedDirectory?.deleteRecursively()
                    throw error
                } catch (error: CardCopyCompositionException) {
                    working.deleteRecursively()
                    verifiedDirectory?.deleteRecursively()
                    CardCopyPrepareResult.Failure(error.reason)
                } catch (_: IOException) {
                    working.deleteRecursively()
                    verifiedDirectory?.deleteRecursively()
                    CardCopyPrepareResult.Failure(CardCopyFailure.PREPARATION_FAILED)
                } catch (_: RuntimeException) {
                    working.deleteRecursively()
                    verifiedDirectory?.deleteRecursively()
                    CardCopyPrepareResult.Failure(CardCopyFailure.PREPARATION_FAILED)
                } catch (_: OutOfMemoryError) {
                    working.deleteRecursively()
                    verifiedDirectory?.deleteRecursively()
                    CardCopyPrepareResult.Failure(CardCopyFailure.PREPARATION_FAILED)
                }
            }
            ownershipTransferred = result is CardCopyPrepareResult.Success
            return result
        } finally {
            if (!ownershipTransferred) {
                withContext(NonCancellable + ioDispatcher) {
                    ownedDirectories.forEach(File::deleteRecursively)
                }
            }
        }
    }

    fun discard(prepared: CardCopyPreparedPdf) {
        prepared.workingDirectory.deleteRecursively()
    }

    private fun verificationPolicy(
        geometry: CardCopyGeometry,
        sources: List<CardCopySource>,
        sanitizedImages: List<RedactedPdfImageExpectation>,
    ): RedactedPdfVerificationPolicy {
        val opaqueRegions = listOf(
            sources[0] to geometry.front,
            sources[1] to geometry.back,
        ).flatMap { (source, placement) ->
            source.redactions.map { region ->
                val mapped = placement.mapSourceRegion(region.bounds)
                RedactionBounds(
                    left = mapped.left / geometry.pageSize.widthPoints,
                    top = mapped.top / geometry.pageSize.heightPoints,
                    right = mapped.right / geometry.pageSize.widthPoints,
                    bottom = mapped.bottom / geometry.pageSize.heightPoints,
                )
            }
        }
        return RedactedPdfVerificationPolicy(
            pages = listOf(
                RedactedPdfPageExpectation(
                    widthPoints = geometry.pageSize.widthPoints,
                    heightPoints = geometry.pageSize.heightPoints,
                    expectedImages = sanitizedImages,
                    requiredOpaqueBlackRegions = opaqueRegions,
                ),
            ),
        )
    }

    private fun publishVerified(source: File): File? {
        return try {
            val root = File(context.cacheDir, CARD_COPY_OUTPUT_DIRECTORY)
            if (!root.exists() && !root.mkdirs()) return null
            val directory = File.createTempFile(
                "verified-card-copy-",
                ".work",
                root,
            ).also { marker ->
                if (!marker.delete() || !marker.mkdir()) return null
            }
            val destination = File(directory, "share-safe-card-copy.pdf")
            if (!source.renameTo(destination)) {
                directory.deleteRecursively()
                return null
            }
            destination
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private fun pointsToPixels(points: Float): Int = pointsToCardCopyPixels(points)

    private class CardCopyCompositionException(val reason: CardCopyFailure) : RuntimeException()

    private companion object {
        const val COMPOSITION_JPEG_QUALITY = 94
    }
}
