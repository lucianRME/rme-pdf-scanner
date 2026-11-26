package org.synapseworks.pageharbor.document

import org.synapseworks.pageharbor.document.session.imageConstraintViolation
import android.content.Context
import org.synapseworks.pageharbor.image.ManualImageAdjustments
import android.net.Uri
import java.io.File
import java.io.FileNotFoundException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.withContext
import org.synapseworks.pageharbor.document.searchablepdf.PdfBoxSearchablePdfGenerator
import org.synapseworks.pageharbor.document.searchablepdf.SearchablePdfGenerationResult
import org.synapseworks.pageharbor.document.searchablepdf.SearchablePdfGenerationError
import org.synapseworks.pageharbor.document.searchablepdf.SearchablePdfPage
import org.synapseworks.pageharbor.document.searchablepdf.SearchablePdfRequest
import org.synapseworks.pageharbor.document.session.toAndroidUri
import org.synapseworks.pageharbor.image.DocumentFilter

private const val NormalPdfDirectory = "normal-pdfs"
private const val NormalPdfPrefix = "normal-"
private const val NormalPdfVisualPrefix = "normal-visual-"
private const val NormalPdfMaxAgeMillis = 24L * 60L * 60L * 1000L

sealed interface NormalPdfRecompositionResult {
    data class Ready(val file: File) : NormalPdfRecompositionResult
    data object SourceMissing : NormalPdfRecompositionResult
    data object SourceTooLarge : NormalPdfRecompositionResult
    data object Failed : NormalPdfRecompositionResult
}

/**
 * Produces one private, image-only PDF from the current effective page visuals. Every source is
 * handled sequentially; filtered temporary JPEGs delete themselves when PdfBox closes each stream.
 */
suspend fun recomposeNormalPdf(
    context: Context,
    pages: List<NormalPdfPage>,
): NormalPdfRecompositionResult = withContext(Dispatchers.IO) {
    if (pages.isEmpty()) {
        return@withContext NormalPdfRecompositionResult.SourceMissing
    }
    val outputFile = createTemporaryNormalPdf(context.cacheDir)
        ?: return@withContext NormalPdfRecompositionResult.Failed
    val job=coroutineContext
    val streamProvider = NormalPdfVisualStreamProvider(context,isCancellationRequested={ !job.isActive })

    try {
        when (
            val generated = PdfBoxSearchablePdfGenerator(context).generate(
                SearchablePdfRequest(
                    pages = pages.map { page ->
                        SearchablePdfPage(
                            openJpegStream = { streamProvider.open(page) },
                            // This is deliberately a normal PDF, with no OCR/searchable layer.
                            ocrResult = null,
                            paperSize = physicalPdfPageSize(page.paperSize,page.rotation),
                        )
                    },
                    outputFile = outputFile,
                ),
            )
        ) {
            is SearchablePdfGenerationResult.Success -> {
                if (outputFile.isFile && outputFile.length() > 0L) {
                    NormalPdfRecompositionResult.Ready(outputFile)
                } else {
                    outputFile.deleteSafely()
                    NormalPdfRecompositionResult.Failed
                }
            }

            is SearchablePdfGenerationResult.Failure -> {
                outputFile.deleteSafely()
                normalPdfResultForGenerationFailure(generated.reason)
            }
        }
    } catch (error: CancellationException) {
        outputFile.deleteSafely()
        throw error
    } catch (_: Exception) {
        outputFile.deleteSafely()
        NormalPdfRecompositionResult.Failed
    }
}

internal fun normalPdfResultForGenerationFailure(
    error: SearchablePdfGenerationError,
): NormalPdfRecompositionResult = if (
    error == SearchablePdfGenerationError.PAGE_IMAGE_TOO_LARGE
) {
    NormalPdfRecompositionResult.SourceTooLarge
} else {
    NormalPdfRecompositionResult.Failed
}

fun deleteNormalPdfRecomposition(file: File) {
    file.deleteSafely()
}

fun deleteStaleNormalPdfs(
    cacheDirectory: File,
    nowMillis: Long = System.currentTimeMillis(),
) {
    File(cacheDirectory, NormalPdfDirectory).listFiles()?.forEach { file ->
        if (
            file.isFile &&
            (
                (file.name.startsWith(NormalPdfPrefix) && file.name.endsWith(".pdf")) ||
                    (file.name.startsWith(NormalPdfVisualPrefix) && file.name.endsWith(".jpg"))
            ) &&
            nowMillis - file.lastModified() >= NormalPdfMaxAgeMillis
        ) {
            file.deleteSafely()
        }
    }
}

internal class NormalPdfVisualStreamProvider(
    private val context: Context,
    private val workspaceDirectory: File? = null,
    private val flattenToSrgb: Boolean = false,
    private val isCancellationRequested:()->Boolean = { false },
) {
    fun open(page: NormalPdfPage): InputStream = when (page.filter) {
        DocumentFilter.ORIGINAL -> if (
            !flattenToSrgb && org.synapseworks.pageharbor.document.session.DEFAULT_DOCUMENT_INPUT_LIMITS.imageConstraintViolation(page.imageMetadata)==null && readOrientation { context.contentResolver.openInputStream(page.source.toAndroidUri()) } == androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL && page.annotations.isEmpty && page.adjustments.isNeutral && page.rotation.degrees == 0 && page.contentType == "image/jpeg"
        ) {
            pageSourcePreflight(page.imageMetadata)?.let { throw PageExportFailureException(it) }
            openOriginal(page.source.toAndroidUri())
        } else {
            openFiltered(
                page.source.toAndroidUri(),
                page.filter,
                page.rotation,
                page.imageMetadata,
                page.adjustments,
                page.annotations,
            )
        }
        else -> openFiltered(
            page.source.toAndroidUri(),
            page.filter,
            page.rotation,
            page.imageMetadata,
            page.adjustments,
            page.annotations,
        )
    }

    private fun openOriginal(sourceUri: Uri): InputStream =
        context.contentResolver.openInputStream(sourceUri) ?: throw FileNotFoundException()

    private fun openFiltered(
        sourceUri: Uri,
        filter: DocumentFilter,
        rotation: org.synapseworks.pageharbor.document.session.DocumentPageRotation,
        imageMetadata: org.synapseworks.pageharbor.document.session.DocumentImageMetadata,
        adjustments: ManualImageAdjustments,
        annotations: org.synapseworks.pageharbor.document.annotation.AnnotationLayer,
    ): InputStream {
        val temporaryImage = if (workspaceDirectory == null) {
            createTemporaryNormalVisual(context.cacheDir) ?: throw FileNotFoundException()
        } else {
            File.createTempFile(NormalPdfVisualPrefix, ".jpg", workspaceDirectory)
        }
        val destination = try {
            temporaryImage.outputStream()
        } catch (_: IOException) {
            temporaryImage.deleteSafely()
            throw FileNotFoundException()
        } catch (_: SecurityException) {
            temporaryImage.deleteSafely()
            throw FileNotFoundException()
        }
        val result = try { writeFilteredJpegToDestination(
            openSource = { context.contentResolver.openInputStream(sourceUri) },
            destination = destination,
            filter = filter,
            rotation = rotation,
            imageMetadata = imageMetadata,
            adjustments = adjustments,
            annotations = annotations,
            isCancellationRequested = isCancellationRequested,
            flattenToSrgb = flattenToSrgb,
        ) }catch(error:Throwable){temporaryImage.deleteSafely();throw error}
        when (result) {
            PageExportResult.Success -> Unit
            PageExportResult.SourceTooLarge -> {
                temporaryImage.deleteSafely()
                throw PageExportFailureException(result)
            }

            PageExportResult.SourceMissing -> {
                temporaryImage.deleteSafely()
                throw FileNotFoundException()
            }

            PageExportResult.DestinationUnavailable,
            PageExportResult.WriteFailed,
            -> {
                temporaryImage.deleteSafely()
                throw IOException()
            }
        }
        return try {
            DeleteOnCloseInputStream(temporaryImage.inputStream(), temporaryImage)
        } catch (_: IOException) {
            temporaryImage.deleteSafely()
            throw FileNotFoundException()
        }
    }
}

private class DeleteOnCloseInputStream(
    input: InputStream,
    private val temporaryImage: File,
) : FilterInputStream(input) {
    override fun close() {
        try {
            super.close()
        } finally {
            temporaryImage.deleteSafely()
        }
    }
}

private fun createTemporaryNormalPdf(cacheDirectory: File): File? =
    createTemporaryFile(cacheDirectory, NormalPdfPrefix, ".pdf")

private fun createTemporaryNormalVisual(cacheDirectory: File): File? =
    createTemporaryFile(cacheDirectory, NormalPdfVisualPrefix, ".jpg")

private fun createTemporaryFile(cacheDirectory: File, prefix: String, suffix: String): File? {
    val directory = File(cacheDirectory, NormalPdfDirectory)
    return try {
        if (!directory.exists() && !directory.mkdirs()) return null
        File.createTempFile(prefix, suffix, directory)
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }
}

private fun File.deleteSafely() {
    try {
        delete()
    } catch (_: SecurityException) {
        // Private-cache cleanup is best-effort and must not expose document details.
    }
}
