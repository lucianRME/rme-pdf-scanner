package org.synapseworks.pageharbor.document.targetsize

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.core.graphics.createBitmap
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.synapseworks.pageharbor.document.PageExportResult
import org.synapseworks.pageharbor.document.SizedJpegEncoding
import org.synapseworks.pageharbor.document.exifOrientationSwapsAxes
import org.synapseworks.pageharbor.document.pageSourcePreflight
import org.synapseworks.pageharbor.document.readImageBounds
import org.synapseworks.pageharbor.document.readOrientation
import org.synapseworks.pageharbor.document.searchablepdf.PdfBoxSearchablePdfGenerator
import org.synapseworks.pageharbor.document.searchablepdf.SearchablePdfGenerationResult
import org.synapseworks.pageharbor.document.searchablepdf.SearchablePdfGenerator
import org.synapseworks.pageharbor.document.searchablepdf.SearchablePdfPage
import org.synapseworks.pageharbor.document.searchablepdf.SearchablePdfRequest
import org.synapseworks.pageharbor.document.session.DocumentPageRotation
import org.synapseworks.pageharbor.document.session.DocumentSourceCategory
import org.synapseworks.pageharbor.document.writeSizedJpegToDestination

interface TargetSizePdfEngine {
    suspend fun prepare(
        request: TargetSizePdfRequest,
        progressListener: TargetSizeProgressListener = TargetSizeProgressListener {},
    ): TargetSizePdfResult

    suspend fun prepareExistingPdfCopy(
        request: TargetSizeExistingPdfCopyRequest,
        progressListener: TargetSizeProgressListener = TargetSizeProgressListener {},
    ): TargetSizePdfResult

    fun discard(preparedPdf: TargetSizePreparedPdf)
}

/** Local-only engine. It prepares and validates a private result; M5 owns destination UI/commit. */
@android.annotation.SuppressLint("UsableSpace")
internal class LocalTargetSizePdfEngine(
    context: Context,
    private val planner: TargetSizePdfPlanner = TargetSizePdfPlanner(),
    private val generator: SearchablePdfGenerator = PdfBoxSearchablePdfGenerator(context),
    private val pdfInspector: TargetSizePdfInputInspector = TargetSizePdfInputInspector(context),
    private val openPageInputStream: (android.net.Uri) -> InputStream? =
        context.contentResolver::openInputStream,
    private val availableTemporaryBytes: () -> Long? = {
        context.cacheDir.usableSpace.takeIf { it > 0L }
    },
) : TargetSizePdfEngine {
    private val applicationContext = context.applicationContext ?: context
    private val rootDirectory = File(applicationContext.cacheDir, TARGET_SIZE_DIRECTORY_NAME)

    override suspend fun prepare(
        request: TargetSizePdfRequest,
        progressListener: TargetSizeProgressListener,
    ): TargetSizePdfResult = withContext(Dispatchers.IO) {
        val workingDirectory = createWorkingDirectory()
            ?: return@withContext TargetSizePdfResult.Failure(
                TargetSizePdfFailure.INSUFFICIENT_TEMPORARY_STORAGE,
            )
        try {
            if (isCanceled(request)) return@withContext canceled(workingDirectory)
            report(progressListener, TargetSizeProgress.PLANNING)
            val planningStarted = SystemClock.elapsedRealtime()
            val profiles = inspectPages(request)
                ?: return@withContext failed(workingDirectory, TargetSizePdfFailure.INVALID_SOURCE)
            val pdfFacts = mutableListOf<TargetSizePdfSourceFacts>()
            request.originalPdfUris.forEach { uri ->
                if (isCanceled(request)) return@withContext canceled(workingDirectory)
                when (val inspection = pdfInspector.inspect(uri)) {
                    is TargetSizePdfInspectionResult.Success -> pdfFacts += inspection.facts
                    TargetSizePdfInspectionResult.Encrypted ->
                        return@withContext failed(workingDirectory, TargetSizePdfFailure.ENCRYPTED_PDF)
                    TargetSizePdfInspectionResult.Malformed ->
                        return@withContext failed(workingDirectory, TargetSizePdfFailure.MALFORMED_SOURCE)
                    TargetSizePdfInspectionResult.Unreadable ->
                        return@withContext failed(workingDirectory, TargetSizePdfFailure.SOURCE_READ_FAILED)
                }
            }
            val classification = TargetSizeInputClassifier.classify(
                TargetSizeInputFacts(
                    pageCount = profiles.size,
                    originalPdfSources = pdfFacts,
                    containsNonPdfPages = containsNonPdfPages(request),
                    reconstructionComplete = profiles.size == request.pages.size,
                ),
            )
            when (classification.inputClass) {
                TargetSizeInputClass.ENCRYPTED_PDF ->
                    return@withContext failed(workingDirectory, TargetSizePdfFailure.ENCRYPTED_PDF)
                TargetSizeInputClass.INVALID_SOURCE ->
                    return@withContext failed(workingDirectory, TargetSizePdfFailure.INVALID_SOURCE)
                TargetSizeInputClass.UNSUPPORTED_COMPLEX_PDF ->
                    return@withContext failed(workingDirectory, TargetSizePdfFailure.UNSUPPORTED_INPUT)
                else -> Unit
            }
            val plan = planner.plan(
                targetBytes = request.targetBytes,
                classification = classification,
                pages = profiles,
                qualityFloor = request.qualityFloor,
            )
            val planningMillis = elapsedSince(planningStarted)
            if (!plan.isFeasibleAtQualityFloor) {
                cleanupWorkingDirectory(workingDirectory)
                return@withContext TargetSizePdfResult.TargetNotReachableAtAcceptableQuality(
                    smallestAcceptable = null,
                    smallestAcceptableBytes = plan.estimatedMinimumAcceptableBytes,
                    bytesAreMeasured = false,
                    reason = TargetSizeNotReachableReason.MATHEMATICALLY_UNREALISTIC_FOR_PAGE_COUNT,
                    plan = plan,
                )
            }
            val storageEstimate = TargetSizeStoragePreflight.estimate(
                sourceSizes = request.pages.map { it.imageMetadata.sourceByteCount } +
                    request.originalPdfUris.map(::sourceLength),
                targetBytes = request.targetBytes,
                candidateUpperBoundBytes = candidateUpperBoundBytes(plan),
            )
            if (TargetSizeStoragePreflight.isSufficient(
                    storageEstimate,
                    availableTemporaryBytes(),
                ) == false
            ) {
                return@withContext failed(
                    workingDirectory,
                    TargetSizePdfFailure.INSUFFICIENT_TEMPORARY_STORAGE,
                )
            }

            if (isCanceled(request)) return@withContext canceled(workingDirectory)
            report(progressListener, TargetSizeProgress.CALIBRATING)
            val calibrationStarted = SystemClock.elapsedRealtime()
            val calibration = calibrate(request, profiles, plan, workingDirectory)
                ?: return@withContext if (isCanceled(request)) {
                    canceled(workingDirectory)
                } else {
                    failed(workingDirectory, TargetSizePdfFailure.ENCODE_FAILED)
                }
            val calibrationMillis = elapsedSince(calibrationStarted)

            if (isCanceled(request)) return@withContext canceled(workingDirectory)
            report(progressListener, TargetSizeProgress.ENCODING)
            val encodingStarted = SystemClock.elapsedRealtime()
            var peakTemporaryBytes = directoryBytes(workingDirectory)
            var fullPasses = 1
            val first = buildCandidate(
                request = request,
                pagePlans = calibration.pagePlans,
                outputFile = File(workingDirectory, "candidate-1.pdf"),
                workingDirectory = workingDirectory,
                onTemporaryBytes = { bytes -> peakTemporaryBytes = maxOf(peakTemporaryBytes, bytes) },
            ) ?: return@withContext if (isCanceled(request)) {
                canceled(workingDirectory)
            } else {
                failed(workingDirectory, TargetSizePdfFailure.ENCODE_FAILED)
            }
            peakTemporaryBytes = maxOf(peakTemporaryBytes, directoryBytes(workingDirectory))
            if (isCanceled(request)) return@withContext canceled(workingDirectory)
            report(progressListener, TargetSizeProgress.VALIDATING)
            if (!validateCandidate(first.file, request.pages.size, request)) {
                return@withContext failed(
                    workingDirectory,
                    TargetSizePdfFailure.OUTPUT_VALIDATION_FAILED,
                )
            }
            val firstBytes = first.file.length()
            val encodingMillisAfterFirst = elapsedSince(encodingStarted)
            if (isTargetSizeReached(firstBytes, request.targetBytes)) {
                return@withContext reached(
                    workingDirectory = workingDirectory,
                    candidate = first,
                    plan = plan.copy(pages = calibration.pagePlans),
                    planningMillis = planningMillis,
                    calibrationMillis = calibrationMillis,
                    encodingMillis = encodingMillisAfterFirst,
                    calibrationTrials = calibration.trialCount,
                    fullPasses = fullPasses,
                    peakTemporaryBytes = peakTemporaryBytes,
                )
            }

            val refinedPlans = TargetSizeQualitySearch.refineDocument(
                pages = calibration.pagePlans,
                actualBytes = firstBytes,
                targetBytes = request.targetBytes,
                mandatoryOverheadBytes = plan.mandatoryPdfAndTextOverheadBytes,
            )
            if (refinedPlans == calibration.pagePlans) {
                return@withContext notReachable(
                    workingDirectory,
                    first,
                    plan.copy(pages = calibration.pagePlans),
                    planningMillis,
                    calibrationMillis,
                    encodingMillisAfterFirst,
                    calibration.trialCount,
                    fullPasses,
                    peakTemporaryBytes,
                )
            }

            if (isCanceled(request)) return@withContext canceled(workingDirectory)
            report(progressListener, TargetSizeProgress.ENCODING)
            fullPasses++
            val second = buildCandidate(
                request = request,
                pagePlans = refinedPlans,
                outputFile = File(workingDirectory, "candidate-2.pdf"),
                workingDirectory = workingDirectory,
                onTemporaryBytes = { bytes -> peakTemporaryBytes = maxOf(peakTemporaryBytes, bytes) },
            ) ?: return@withContext if (isCanceled(request)) {
                canceled(workingDirectory)
            } else {
                failed(workingDirectory, TargetSizePdfFailure.ENCODE_FAILED)
            }
            peakTemporaryBytes = maxOf(peakTemporaryBytes, directoryBytes(workingDirectory))
            if (isCanceled(request)) return@withContext canceled(workingDirectory)
            report(progressListener, TargetSizeProgress.VALIDATING)
            if (!validateCandidate(second.file, request.pages.size, request)) {
                return@withContext failed(
                    workingDirectory,
                    TargetSizePdfFailure.OUTPUT_VALIDATION_FAILED,
                )
            }
            val selected = if (second.file.length() <= first.file.length()) second else first
            val selectedPlans = if (selected === second) refinedPlans else calibration.pagePlans
            (if (selected === second) first else second).file.deleteSafely()
            val finalPlan = plan.copy(pages = selectedPlans)
            val encodingMillis = elapsedSince(encodingStarted)
            if (isTargetSizeReached(selected.file.length(), request.targetBytes)) {
                reached(
                    workingDirectory,
                    selected,
                    finalPlan,
                    planningMillis,
                    calibrationMillis,
                    encodingMillis,
                    calibration.trialCount,
                    fullPasses,
                    peakTemporaryBytes,
                )
            } else {
                notReachable(
                    workingDirectory,
                    selected,
                    finalPlan,
                    planningMillis,
                    calibrationMillis,
                    encodingMillis,
                    calibration.trialCount,
                    fullPasses,
                    peakTemporaryBytes,
                )
            }
        } catch (error: CancellationException) {
            cleanupWorkingDirectory(workingDirectory)
            if (isCanceled(request)) {
                TargetSizePdfResult.Failure(TargetSizePdfFailure.CANCELED)
            } else {
                throw error
            }
        } catch (error: TargetSizeEngineFailure) {
            failed(workingDirectory, error.reason)
        } catch (_: IOException) {
            failed(workingDirectory, TargetSizePdfFailure.ENCODE_FAILED)
        } catch (_: SecurityException) {
            failed(workingDirectory, TargetSizePdfFailure.SOURCE_READ_FAILED)
        } catch (_: RuntimeException) {
            failed(workingDirectory, TargetSizePdfFailure.ENCODE_FAILED)
        }
    }

    override suspend fun prepareExistingPdfCopy(
        request: TargetSizeExistingPdfCopyRequest,
        progressListener: TargetSizeProgressListener,
    ): TargetSizePdfResult = withContext(Dispatchers.IO) {
        val documentRequest = request.documentRequest
        val workingDirectory = createWorkingDirectory()
            ?: return@withContext TargetSizePdfResult.Failure(
                TargetSizePdfFailure.INSUFFICIENT_TEMPORARY_STORAGE,
            )
        try {
            if (isCanceled(documentRequest)) return@withContext canceled(workingDirectory)
            report(progressListener, TargetSizeProgress.PLANNING)
            val planningStarted = SystemClock.elapsedRealtime()
            val profiles = inspectPages(documentRequest)
                ?: return@withContext failed(workingDirectory, TargetSizePdfFailure.INVALID_SOURCE)
            val facts = when (val inspection = pdfInspector.inspect(request.sourcePdfUri)) {
                is TargetSizePdfInspectionResult.Success -> inspection.facts
                TargetSizePdfInspectionResult.Encrypted ->
                    return@withContext failed(workingDirectory, TargetSizePdfFailure.ENCRYPTED_PDF)
                TargetSizePdfInspectionResult.Malformed ->
                    return@withContext failed(workingDirectory, TargetSizePdfFailure.MALFORMED_SOURCE)
                TargetSizePdfInspectionResult.Unreadable ->
                    return@withContext failed(workingDirectory, TargetSizePdfFailure.SOURCE_READ_FAILED)
            }
            val classification = TargetSizeInputClassifier.classify(
                TargetSizeInputFacts(
                    pageCount = profiles.size,
                    originalPdfSources = listOf(facts),
                    containsNonPdfPages = false,
                    reconstructionComplete = profiles.size == facts.pageCount,
                ),
            )
            if (classification.inputClass !in setOf(
                    TargetSizeInputClass.IMAGE_BASED_PDF_SUPPORTED,
                    TargetSizeInputClass.MIXED_PDF_SUPPORTED_WITH_RECONSTRUCTION,
                )
            ) {
                val reason = when (classification.inputClass) {
                    TargetSizeInputClass.ENCRYPTED_PDF -> TargetSizePdfFailure.ENCRYPTED_PDF
                    TargetSizeInputClass.INVALID_SOURCE -> TargetSizePdfFailure.INVALID_SOURCE
                    else -> TargetSizePdfFailure.UNSUPPORTED_INPUT
                }
                return@withContext failed(workingDirectory, reason)
            }
            val plan = planner.plan(
                targetBytes = documentRequest.targetBytes,
                classification = classification,
                pages = profiles,
                qualityFloor = documentRequest.qualityFloor,
            )
            val planningMillis = elapsedSince(planningStarted)
            val storageEstimate = TargetSizeStoragePreflight.estimateExistingPdfCopy(
                sourceBytes = sourceLength(request.sourcePdfUri),
                targetBytes = documentRequest.targetBytes,
            )
            if (TargetSizeStoragePreflight.isSufficient(
                    storageEstimate,
                    availableTemporaryBytes(),
                ) == false
            ) {
                return@withContext failed(
                    workingDirectory,
                    TargetSizePdfFailure.INSUFFICIENT_TEMPORARY_STORAGE,
                )
            }
            report(progressListener, TargetSizeProgress.ENCODING)
            val encodingStarted = SystemClock.elapsedRealtime()
            val output = File(workingDirectory, "candidate-original.pdf")
            val source = try {
                openPageInputStream(request.sourcePdfUri)
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            } ?: return@withContext failed(
                workingDirectory,
                TargetSizePdfFailure.SOURCE_READ_FAILED,
            )
            try {
                source.use { input ->
                    FileOutputStream(output).use { destination ->
                        val buffer = ByteArray(EXISTING_PDF_COPY_BUFFER_BYTES)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            if (isCanceled(documentRequest)) {
                                throw TargetSizeEngineFailure(TargetSizePdfFailure.CANCELED)
                            }
                            val count = input.read(buffer)
                            if (count < 0) break
                            destination.write(buffer, 0, count)
                            if (destination.channel.position() > documentRequest.targetBytes) {
                                throw TargetSizeEngineFailure(
                                    TargetSizePdfFailure.OUTPUT_VALIDATION_FAILED,
                                )
                            }
                        }
                        destination.flush()
                        destination.fd.sync()
                    }
                }
            } catch (error: TargetSizeEngineFailure) {
                throw error
            } catch (_: IOException) {
                return@withContext failed(workingDirectory, TargetSizePdfFailure.ENCODE_FAILED)
            }
            if (!isTargetSizeReached(output.length(), documentRequest.targetBytes)) {
                return@withContext failed(
                    workingDirectory,
                    TargetSizePdfFailure.OUTPUT_VALIDATION_FAILED,
                )
            }
            report(progressListener, TargetSizeProgress.VALIDATING)
            if (!validateCandidate(output, profiles.size, documentRequest)) {
                return@withContext failed(
                    workingDirectory,
                    TargetSizePdfFailure.OUTPUT_VALIDATION_FAILED,
                )
            }
            reached(
                workingDirectory = workingDirectory,
                candidate = Candidate(output, textLayerPageCount = 0),
                plan = plan.copy(
                    warnings = plan.warnings - TargetSizeWarning.TARGET_REQUIRES_QUALITY_FLOOR,
                ),
                planningMillis = planningMillis,
                calibrationMillis = 0L,
                encodingMillis = elapsedSince(encodingStarted),
                calibrationTrials = 0,
                fullPasses = 0,
                peakTemporaryBytes = output.length(),
            )
        } catch (error: CancellationException) {
            cleanupWorkingDirectory(workingDirectory)
            if (isCanceled(documentRequest)) {
                TargetSizePdfResult.Failure(TargetSizePdfFailure.CANCELED)
            } else {
                throw error
            }
        } catch (error: TargetSizeEngineFailure) {
            failed(workingDirectory, error.reason)
        } catch (_: IOException) {
            failed(workingDirectory, TargetSizePdfFailure.ENCODE_FAILED)
        } catch (_: SecurityException) {
            failed(workingDirectory, TargetSizePdfFailure.SOURCE_READ_FAILED)
        } catch (_: RuntimeException) {
            failed(workingDirectory, TargetSizePdfFailure.ENCODE_FAILED)
        }
    }

    override fun discard(preparedPdf: TargetSizePreparedPdf) {
        cleanupWorkingDirectory(preparedPdf.workingDirectory)
    }

    private suspend fun inspectPages(request: TargetSizePdfRequest): List<TargetSizePageProfile>? {
        if (request.pages.isEmpty()) return null
        return request.pages.map { page ->
            currentCoroutineContext().ensureActive()
            if (isCanceled(request)) throw TargetSizeEngineFailure(TargetSizePdfFailure.CANCELED)
            if (page.contentType !in SUPPORTED_IMAGE_TYPES) {
                throw TargetSizeEngineFailure(TargetSizePdfFailure.UNSUPPORTED_INPUT)
            }
            when (pageSourcePreflight(page.imageMetadata)) {
                PageExportResult.SourceTooLarge ->
                    throw TargetSizeEngineFailure(TargetSizePdfFailure.INVALID_SOURCE)
                PageExportResult.SourceMissing ->
                    throw TargetSizeEngineFailure(TargetSizePdfFailure.SOURCE_READ_FAILED)
                else -> Unit
            }
            val bounds = try {
                readImageBounds { openPageInputStream(page.sourceUri) }
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            } ?: throw TargetSizeEngineFailure(TargetSizePdfFailure.SOURCE_READ_FAILED)
            val orientation = readOrientation { openPageInputStream(page.sourceUri) }
            val dimensions = effectiveDimensions(bounds, orientation, page.rotation)
            val textStrategy = searchableTextStrategy(page)
            TargetSizePageProfile(
                pageId = page.pageId,
                widthPx = dimensions.first,
                heightPx = dimensions.second,
                sourceBytes = page.imageMetadata.sourceByteCount,
                contentProfile = page.contentProfile,
                textStrategy = textStrategy,
                searchableCharacterCount = if (
                    textStrategy == TargetSizeTextStrategy.PRESERVE_POSITIONED_UNICODE
                ) {
                    page.effectiveOcr?.text?.let { text ->
                        text.codePointCount(0, text.length)
                    } ?: 0
                } else {
                    0
                },
            )
        }
    }

    private fun searchableTextStrategy(page: TargetSizePdfPage): TargetSizeTextStrategy {
        val ocr = page.effectiveOcr ?: return TargetSizeTextStrategy.NONE
        if (ocr.text.isBlank()) return TargetSizeTextStrategy.NONE
        val layout = ocr.layout ?: return TargetSizeTextStrategy.OMIT_UNPOSITIONED_WITH_WARNING
        if (ocr.error != null || layout.rotationDegrees != 0) {
            return TargetSizeTextStrategy.OMIT_UNPOSITIONED_WITH_WARNING
        }
        val meaningfulLines = layout.lines.filter { it.text.isNotBlank() }
        return if (meaningfulLines.isNotEmpty() && meaningfulLines.all { it.bounds != null }) {
            TargetSizeTextStrategy.PRESERVE_POSITIONED_UNICODE
        } else {
            TargetSizeTextStrategy.OMIT_UNPOSITIONED_WITH_WARNING
        }
    }

    private suspend fun calibrate(
        request: TargetSizePdfRequest,
        profiles: List<TargetSizePageProfile>,
        plan: TargetSizePlan,
        workingDirectory: File,
    ): CalibrationResult? {
        val indexes = TargetSizeQualitySearch.representativePageIndices(profiles)
        if (indexes.isEmpty()) return CalibrationResult(plan.pages, 0)
        var worstRatio = 1.0
        var sharedDimensionScale = 1.0
        var sharedQualityReduction = 0
        var trials = 0
        indexes.forEach { index ->
            val original = plan.pages[index]
            var current = original
            var finalRatio = 1.0
            for (trialIndex in 0 until TargetSizeQualitySearch.MAXIMUM_TRIALS_PER_CALIBRATION_PAGE) {
                if (isCanceled(request)) return null
                val trial = File(workingDirectory, "calibration-$index-$trialIndex.jpg")
                encodePage(request.pages[index], current, trial, request)
                trials++
                val actualBytes = trial.length().coerceAtLeast(1L)
                val budget = current.allocatedBytes.coerceAtLeast(1L)
                finalRatio = actualBytes.toDouble() / budget.toDouble()
                trial.deleteSafely()
                val next = TargetSizeQualitySearch.calibrated(current, actualBytes)
                if (actualBytes <= current.allocatedBytes || next == current) break
                current = next
            }
            worstRatio = maxOf(worstRatio, finalRatio)
            sharedDimensionScale = minOf(
                sharedDimensionScale,
                current.targetWidthPx.toDouble() / original.targetWidthPx.toDouble(),
                current.targetHeightPx.toDouble() / original.targetHeightPx.toDouble(),
            )
            sharedQualityReduction = maxOf(
                sharedQualityReduction,
                original.jpegQuality - current.jpegQuality,
            )
        }
        val adjusted = plan.pages.map { pagePlan ->
            val calibrated = pagePlan.copy(
                targetWidthPx = (pagePlan.targetWidthPx * sharedDimensionScale)
                    .roundToInt()
                    .coerceIn(pagePlan.minimumWidthPx, pagePlan.targetWidthPx),
                targetHeightPx = (pagePlan.targetHeightPx * sharedDimensionScale)
                    .roundToInt()
                    .coerceIn(pagePlan.minimumHeightPx, pagePlan.targetHeightPx),
                jpegQuality = (pagePlan.jpegQuality - sharedQualityReduction)
                    .coerceAtLeast(pagePlan.minimumJpegQuality),
            )
            if (worstRatio <= 1.0) {
                calibrated
            } else {
                val syntheticActual = (pagePlan.allocatedBytes * worstRatio)
                    .toLong()
                    .coerceAtLeast(1L)
                TargetSizeQualitySearch.calibrated(calibrated, syntheticActual)
            }
        }
        return CalibrationResult(adjusted, trials)
    }

    private suspend fun buildCandidate(
        request: TargetSizePdfRequest,
        pagePlans: List<TargetSizePagePlan>,
        outputFile: File,
        workingDirectory: File,
        onTemporaryBytes: (Long) -> Unit,
    ): Candidate? {
        val pageFiles = mutableListOf<File>()
        return try {
            request.pages.forEachIndexed { index, page ->
                currentCoroutineContext().ensureActive()
                if (isCanceled(request)) return null
                val pageFile = File(workingDirectory, "page-$index.jpg")
                pageFile.deleteSafely()
                encodePage(page, pagePlans[index], pageFile, request)
                pageFiles += pageFile
                onTemporaryBytes(directoryBytes(workingDirectory))
            }
            val generated = generator.generate(
                SearchablePdfRequest(
                    pages = request.pages.mapIndexed { index, page ->
                        SearchablePdfPage(
                            openJpegStream = { pageFiles[index].inputStream() },
                            ocrResult = if (
                                pagePlans[index].textStrategy ==
                                TargetSizeTextStrategy.PRESERVE_POSITIONED_UNICODE
                            ) {
                                page.effectiveOcr
                            } else {
                                null
                            },
                        )
                    },
                    outputFile = outputFile,
                    scratchDirectory = workingDirectory,
                ),
            )
            onTemporaryBytes(directoryBytes(workingDirectory))
            when (generated) {
                is SearchablePdfGenerationResult.Success -> Candidate(
                    file = outputFile,
                    textLayerPageCount = generated.textLayerPageCount,
                )
                is SearchablePdfGenerationResult.Failure -> null
            }
        } finally {
            pageFiles.forEach(File::deleteSafely)
        }
    }

    private fun encodePage(
        page: TargetSizePdfPage,
        plan: TargetSizePagePlan,
        outputFile: File,
        request: TargetSizePdfRequest,
    ) {
        val output = try {
            outputFile.outputStream()
        } catch (_: IOException) {
            throw TargetSizeEngineFailure(TargetSizePdfFailure.ENCODE_FAILED)
        } catch (_: SecurityException) {
            throw TargetSizeEngineFailure(TargetSizePdfFailure.ENCODE_FAILED)
        }
        val result = writeSizedJpegToDestination(
            openSource = { openPageInputStream(page.sourceUri) },
            destination = output,
            filter = page.filter,
            rotation = page.rotation,
            imageMetadata = page.imageMetadata,
            encoding = SizedJpegEncoding(
                maximumWidthPx = plan.targetWidthPx,
                maximumHeightPx = plan.targetHeightPx,
                quality = plan.jpegQuality,
            ),
            isCancellationRequested = { request.cancellationSignal.isCancellationRequested() },
        )
        when (result) {
            PageExportResult.Success -> if (!outputFile.isFile || outputFile.length() <= 0L) {
                throw TargetSizeEngineFailure(TargetSizePdfFailure.ENCODE_FAILED)
            }
            PageExportResult.SourceMissing ->
                throw TargetSizeEngineFailure(TargetSizePdfFailure.SOURCE_READ_FAILED)
            PageExportResult.SourceTooLarge ->
                throw TargetSizeEngineFailure(TargetSizePdfFailure.INVALID_SOURCE)
            PageExportResult.DestinationUnavailable,
            PageExportResult.WriteFailed,
            -> throw TargetSizeEngineFailure(TargetSizePdfFailure.ENCODE_FAILED)
        }
    }

    private suspend fun validateCandidate(
        outputFile: File,
        expectedPages: Int,
        request: TargetSizePdfRequest,
    ): Boolean {
        if (!outputFile.isFile || outputFile.length() <= 0L) return false
        return try {
            ParcelFileDescriptor.open(outputFile, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    if (renderer.pageCount != expectedPages) return false
                    repeat(renderer.pageCount) { pageIndex ->
                        currentCoroutineContext().ensureActive()
                        if (isCanceled(request)) return false
                        renderer.openPage(pageIndex).use { page ->
                            val width = minOf(VALIDATION_EDGE_PX, page.width).coerceAtLeast(1)
                            val height = minOf(VALIDATION_EDGE_PX, page.height).coerceAtLeast(1)
                            val bitmap = createBitmap(width, height)
                            try {
                                bitmap.eraseColor(Color.WHITE)
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            } finally {
                                bitmap.recycle()
                            }
                        }
                    }
                }
            }
            true
        } catch (error: CancellationException) {
            throw error
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        } catch (_: IllegalStateException) {
            false
        } catch (_: OutOfMemoryError) {
            false
        }
    }

    private fun reached(
        workingDirectory: File,
        candidate: Candidate,
        plan: TargetSizePlan,
        planningMillis: Long,
        calibrationMillis: Long,
        encodingMillis: Long,
        calibrationTrials: Int,
        fullPasses: Int,
        peakTemporaryBytes: Long,
    ): TargetSizePdfResult.TargetReached = TargetSizePdfResult.TargetReached(
        prepared = prepared(
            workingDirectory,
            candidate,
            plan,
            planningMillis,
            calibrationMillis,
            encodingMillis,
            calibrationTrials,
            fullPasses,
            peakTemporaryBytes,
        ),
    )

    private fun notReachable(
        workingDirectory: File,
        candidate: Candidate,
        plan: TargetSizePlan,
        planningMillis: Long,
        calibrationMillis: Long,
        encodingMillis: Long,
        calibrationTrials: Int,
        fullPasses: Int,
        peakTemporaryBytes: Long,
    ): TargetSizePdfResult.TargetNotReachableAtAcceptableQuality {
        val prepared = prepared(
            workingDirectory,
            candidate,
            plan,
            planningMillis,
            calibrationMillis,
            encodingMillis,
            calibrationTrials,
            fullPasses,
            peakTemporaryBytes,
        )
        return TargetSizePdfResult.TargetNotReachableAtAcceptableQuality(
            smallestAcceptable = prepared,
            smallestAcceptableBytes = prepared.finalBytes,
            bytesAreMeasured = true,
            reason = TargetSizeNotReachableReason.QUALITY_FLOOR_REACHED,
            plan = plan,
        )
    }

    private fun prepared(
        workingDirectory: File,
        candidate: Candidate,
        plan: TargetSizePlan,
        planningMillis: Long,
        calibrationMillis: Long,
        encodingMillis: Long,
        calibrationTrials: Int,
        fullPasses: Int,
        peakTemporaryBytes: Long,
    ): TargetSizePreparedPdf {
        workingDirectory.listFiles()?.forEach { file ->
            if (file != candidate.file) file.deleteSafely()
        }
        return TargetSizePreparedPdf(
            file = candidate.file,
            workingDirectory = workingDirectory,
            finalBytes = candidate.file.length(),
            pageCount = plan.pages.size,
            textLayerPageCount = candidate.textLayerPageCount,
            plan = plan,
            performance = TargetSizePerformance(
                planningMillis = planningMillis,
                calibrationMillis = calibrationMillis,
                encodingMillis = encodingMillis,
                calibrationTrialCount = calibrationTrials,
                fullDocumentPassCount = fullPasses,
                peakTemporaryBytes = peakTemporaryBytes,
            ),
        )
    }

    private fun containsNonPdfPages(request: TargetSizePdfRequest): Boolean {
        if (request.originalPdfUris.isEmpty()) return true
        return request.pages.any { page ->
            page.sourceCategory in setOf(
                DocumentSourceCategory.SCAN,
                DocumentSourceCategory.SELECTED_IMAGE,
                DocumentSourceCategory.INBOUND_SHARE,
            )
        }
    }

    private fun sourceLength(uri: android.net.Uri): Long? = try {
        applicationContext.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
            descriptor.length.takeIf { it >= 0L }
        }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun createWorkingDirectory(): File? {
        return try {
            if (!rootDirectory.exists() && !rootDirectory.mkdirs()) return null
            val marker = File.createTempFile(TARGET_SIZE_PREFIX, ".work", rootDirectory)
            if (!marker.delete() || !marker.mkdir()) return null
            marker
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private fun failed(directory: File, reason: TargetSizePdfFailure): TargetSizePdfResult.Failure {
        cleanupWorkingDirectory(directory)
        return TargetSizePdfResult.Failure(reason)
    }

    private fun canceled(directory: File): TargetSizePdfResult.Failure =
        failed(directory, TargetSizePdfFailure.CANCELED)

    private fun isCanceled(request: TargetSizePdfRequest): Boolean =
        request.cancellationSignal.isCancellationRequested()

    private fun report(listener: TargetSizeProgressListener, progress: TargetSizeProgress) {
        runCatching { listener.onProgress(progress) }
    }

    private data class CalibrationResult(
        val pagePlans: List<TargetSizePagePlan>,
        val trialCount: Int,
    )

    private data class Candidate(
        val file: File,
        val textLayerPageCount: Int,
    )

    private class TargetSizeEngineFailure(val reason: TargetSizePdfFailure) : RuntimeException()

    private companion object {
        const val EXISTING_PDF_COPY_BUFFER_BYTES = 32 * 1_024
        const val TARGET_SIZE_PREFIX = "target-size-"
        const val VALIDATION_EDGE_PX = 64
        val SUPPORTED_IMAGE_TYPES = setOf("image/jpeg", "image/png", "image/webp")
    }
}

private fun effectiveDimensions(
    bounds: Pair<Int, Int>,
    orientation: Int,
    rotation: DocumentPageRotation,
): Pair<Int, Int> {
    val exifSwapsAxes = exifOrientationSwapsAxes(orientation)
    val sessionSwapsAxes = rotation == DocumentPageRotation.DEGREES_90 ||
        rotation == DocumentPageRotation.DEGREES_270
    return if (exifSwapsAxes.xor(sessionSwapsAxes)) {
        bounds.second to bounds.first
    } else {
        bounds
    }
}

private fun elapsedSince(startMillis: Long): Long =
    (SystemClock.elapsedRealtime() - startMillis).coerceAtLeast(0L)

private fun directoryBytes(directory: File): Long =
    directory.listFiles()?.filter(File::isFile)?.fold(0L) { total, file ->
        saturatedAdd(total, file.length())
    } ?: 0L

private fun candidateUpperBoundBytes(plan: TargetSizePlan): Long {
    val visualBytes = plan.pages.fold(0L) { total, page ->
        val pixels = saturatedMultiply(page.targetWidthPx.toLong(), page.targetHeightPx.toLong())
        val rawRgbAndHeader = saturatedAdd(saturatedMultiply(pixels, 3L), 4_096L)
        saturatedAdd(total, rawRgbAndHeader)
    }
    return saturatedAdd(plan.mandatoryPdfAndTextOverheadBytes, visualBytes).coerceAtLeast(1L)
}

private fun cleanupWorkingDirectory(directory: File) {
    try {
        directory.listFiles()?.forEach { file ->
            if (file.isFile) file.delete()
        }
        directory.delete()
    } catch (_: SecurityException) {
        // Private cleanup is best-effort and never logs document names or paths.
    }
}

private fun File.deleteSafely() {
    try {
        if (isFile) delete()
    } catch (_: SecurityException) {
        // Private cleanup is best-effort and never logs document names or paths.
    }
}

/** Cold-start cleanup for process-death residue. Only M4's private cache directory is touched. */
fun deleteStaleTargetSizePdfs(
    cacheDirectory: File,
    nowMillis: Long = System.currentTimeMillis(),
    minimumAgeMillis: Long = TARGET_SIZE_MAX_STALE_AGE_MILLIS,
) {
    require(minimumAgeMillis >= 0L)
    File(cacheDirectory, TARGET_SIZE_DIRECTORY_NAME)
        .listFiles()
        ?.filter { it.isDirectory && it.name.startsWith("target-size-") }
        ?.forEach { directory ->
            if (nowMillis - directory.lastModified() >= minimumAgeMillis) {
                cleanupWorkingDirectory(directory)
            }
        }
}

internal const val TARGET_SIZE_MAX_STALE_AGE_MILLIS = 24L * 60L * 60L * 1_000L
internal const val TARGET_SIZE_DIRECTORY_NAME = "target-size-pdfs"
