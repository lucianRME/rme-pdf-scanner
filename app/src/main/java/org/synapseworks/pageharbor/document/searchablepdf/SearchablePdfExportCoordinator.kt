package org.synapseworks.pageharbor.document.searchablepdf

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileNotFoundException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.synapseworks.pageharbor.document.PageExportResult
import org.synapseworks.pageharbor.document.PageExportFailureException
import org.synapseworks.pageharbor.document.classification.DocumentClassifier
import org.synapseworks.pageharbor.document.pageSourcePreflight
import org.synapseworks.pageharbor.document.writeFilteredJpegToDestination
import org.synapseworks.pageharbor.document.filename.FilenameSuggestion
import org.synapseworks.pageharbor.document.filename.FilenameSuggestionEngine
import org.synapseworks.pageharbor.ocr.OcrEngine
import org.synapseworks.pageharbor.ocr.OcrPage
import org.synapseworks.pageharbor.ocr.OcrResult

/** Coordinates local OCR, searchable-PDF preparation, and a caller-selected SAF destination. */
interface SearchablePdfExportCoordinator {
    suspend fun prepare(request: SearchablePdfExportRequest): SearchablePdfPreparedExport

    suspend fun writePreparedExport(
        preparedExport: SearchablePdfPreparedExport,
        destinationUri: Uri,
    ): SearchablePdfExportResult

    /** Deletes an unused prepared PDF, for example when the user cancels destination selection. */
    fun discardPreparedExport(preparedExport: SearchablePdfPreparedExport)
}

/** Ordered scanner JPEG sources, optional existing OCR, and UI-agnostic progress notifications. */
data class SearchablePdfExportRequest(
    val pageUris: List<Uri>,
    val visualPages: List<SearchablePdfVisualPage> = pageUris.mapIndexed { index, uri ->
        SearchablePdfVisualPage(pageId = index.toLong(), originalUri = uri)
    },
    val ocrResult: OcrResult? = null,
    val progressListener: SearchablePdfExportProgressListener = SearchablePdfExportProgressListener {},
) {
    init {
        require(visualPages.map(SearchablePdfVisualPage::originalUri) == pageUris) {
            "Visual pages must retain the ordered original OCR sources."
        }
    }
}

fun interface SearchablePdfExportProgressListener {
    fun onProgress(progress: SearchablePdfExportProgress)
}

sealed interface SearchablePdfExportProgress {
    data object Recognizing : SearchablePdfExportProgress
    data object Generating : SearchablePdfExportProgress
    data object Writing : SearchablePdfExportProgress
}

sealed interface SearchablePdfPreparedExport {
    class Ready internal constructor(
        internal val temporaryFile: File,
        internal val progressListener: SearchablePdfExportProgressListener,
        val pageCount: Int,
        val textLayerPageCount: Int,
        /** Safe, category-only title for the immediately following user-controlled SAF save. */
        val filenameSuggestion: FilenameSuggestion,
    ) : SearchablePdfPreparedExport

    data class Failure(val reason: SearchablePdfPreparationError) : SearchablePdfPreparedExport
}

enum class SearchablePdfPreparationError {
    NO_PAGES,
    OCR_FAILED,
    OCR_RESULT_MISMATCH,
    TEMPORARY_STORAGE_UNAVAILABLE,
    SOURCE_TOO_LARGE,
    GENERATION_FAILED,
}

sealed interface SearchablePdfExportResult {
    data object Success : SearchablePdfExportResult
    data class Failure(val reason: SearchablePdfExportError) : SearchablePdfExportResult
}

enum class SearchablePdfExportError {
    PREPARED_EXPORT_UNAVAILABLE,
    DESTINATION_UNAVAILABLE,
    WRITE_FAILED,
}

/**
 * Local implementation. It keeps generated PDFs in private cache only until they are copied to a
 * destination selected through SAF, discarded, or cancelled.
 */
class LocalSearchablePdfExportCoordinator(
    context: Context,
    private val ocrEngine: OcrEngine,
    private val generator: SearchablePdfGenerator = PdfBoxSearchablePdfGenerator(context),
    private val documentClassifier: DocumentClassifier = DocumentClassifier(),
    private val filenameSuggestionEngine: FilenameSuggestionEngine = FilenameSuggestionEngine(),
    /** Kept injectable only for deterministic source-provider failure tests; production uses SAF. */
    private val openSourceInputStream: (Uri) -> InputStream? = { uri ->
        context.contentResolver.openInputStream(uri)
    },
    /** Kept injectable only for deterministic provider-boundary tests; production uses SAF. */
    private val openDestinationOutputStream: (Uri) -> OutputStream? = { uri ->
        context.contentResolver.openOutputStream(uri)
    },
    /** Kept injectable only for deterministic private-cache availability tests. */
    private val createTemporaryPdfFile: () -> File? = {
        createPrivateTemporaryPdf(context.cacheDir)
    },
    /** Kept injectable only for deterministic private-cache cleanup tests. */
    private val deleteTemporaryFile: (File) -> Boolean = File::delete,
) : SearchablePdfExportCoordinator {
    private val cacheDirectory: File = context.cacheDir

    override suspend fun prepare(request: SearchablePdfExportRequest): SearchablePdfPreparedExport {
        if (request.visualPages.isEmpty()) {
            return SearchablePdfPreparedExport.Failure(SearchablePdfPreparationError.NO_PAGES)
        }
        request.visualPages.forEach { page ->
            if (pageSourcePreflight(page.imageMetadata) == PageExportResult.SourceTooLarge) {
                return SearchablePdfPreparedExport.Failure(
                    SearchablePdfPreparationError.SOURCE_TOO_LARGE,
                )
            }
        }

        val ocrResult = request.ocrResult ?: recognize(request)
            ?: return SearchablePdfPreparedExport.Failure(SearchablePdfPreparationError.OCR_FAILED)
        val orderedOcrPages = orderOcrPages(ocrResult, request.visualPages.size)
            ?: return SearchablePdfPreparedExport.Failure(SearchablePdfPreparationError.OCR_RESULT_MISMATCH)
        val filenameSuggestion = filenameSuggestionEngine.suggest(
            documentClassifier.classify(ocrResult.plainText).category,
        )
        val temporaryFile = createTemporaryPdf()
            ?: return SearchablePdfPreparedExport.Failure(
                SearchablePdfPreparationError.TEMPORARY_STORAGE_UNAVAILABLE,
            )

        return try {
            coroutineContext.ensureActive()
            reportProgress(request.progressListener, SearchablePdfExportProgress.Generating)
            when (
                val generated = generator.generate(
                    SearchablePdfRequest(
                        pages = request.visualPages.mapIndexed { index, visualPage ->
                            SearchablePdfPage(
                                openJpegStream = { openVisualJpegStream(visualPage) },
                                ocrResult = orderedOcrPages[index],
                            )
                        },
                        outputFile = temporaryFile,
                    ),
                )
            ) {
                is SearchablePdfGenerationResult.Success -> {
                    if (!temporaryFile.isFile || temporaryFile.length() == 0L) {
                        deleteTemporaryPdf(temporaryFile)
                        SearchablePdfPreparedExport.Failure(
                            SearchablePdfPreparationError.GENERATION_FAILED,
                        )
                    } else {
                        SearchablePdfPreparedExport.Ready(
                            temporaryFile = temporaryFile,
                            progressListener = request.progressListener,
                            pageCount = generated.pageCount,
                            textLayerPageCount = generated.textLayerPageCount,
                            filenameSuggestion = filenameSuggestion,
                        )
                    }
                }

                is SearchablePdfGenerationResult.Failure -> {
                    deleteTemporaryPdf(temporaryFile)
                    SearchablePdfPreparedExport.Failure(
                        searchablePreparationErrorForGenerationFailure(generated.reason),
                    )
                }
            }
        } catch (error: CancellationException) {
            deleteTemporaryPdf(temporaryFile)
            throw error
        } catch (_: Exception) {
            deleteTemporaryPdf(temporaryFile)
            SearchablePdfPreparedExport.Failure(SearchablePdfPreparationError.GENERATION_FAILED)
        }
    }

    override suspend fun writePreparedExport(
        preparedExport: SearchablePdfPreparedExport,
        destinationUri: Uri,
    ): SearchablePdfExportResult = withContext(Dispatchers.IO) {
        val ready = preparedExport as? SearchablePdfPreparedExport.Ready
            ?: return@withContext SearchablePdfExportResult.Failure(
                SearchablePdfExportError.PREPARED_EXPORT_UNAVAILABLE,
            )
        if (!ready.temporaryFile.isFile) {
            deleteTemporaryPdf(ready.temporaryFile)
            return@withContext SearchablePdfExportResult.Failure(
                SearchablePdfExportError.PREPARED_EXPORT_UNAVAILABLE,
            )
        }

        try {
            coroutineContext.ensureActive()
            reportProgress(ready.progressListener, SearchablePdfExportProgress.Writing)
            val destination = try {
                openDestinationOutputStream(destinationUri)
            } catch (_: FileNotFoundException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: SecurityException) {
                null
            } catch (_: IOException) {
                null
            } catch (_: RuntimeException) {
                null
            }
            if (destination == null) {
                return@withContext SearchablePdfExportResult.Failure(
                    SearchablePdfExportError.DESTINATION_UNAVAILABLE,
                )
            }

            ready.temporaryFile.inputStream().use { input ->
                destination.use { output ->
                    val buffer = ByteArray(CopyBufferSize)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.flush()
                }
            }
            SearchablePdfExportResult.Success
        } catch (error: CancellationException) {
            throw error
        } catch (_: IOException) {
            SearchablePdfExportResult.Failure(SearchablePdfExportError.WRITE_FAILED)
        } catch (_: SecurityException) {
            SearchablePdfExportResult.Failure(SearchablePdfExportError.WRITE_FAILED)
        } catch (_: RuntimeException) {
            SearchablePdfExportResult.Failure(SearchablePdfExportError.WRITE_FAILED)
        } finally {
            deleteTemporaryPdf(ready.temporaryFile)
        }
    }

    override fun discardPreparedExport(preparedExport: SearchablePdfPreparedExport) {
        (preparedExport as? SearchablePdfPreparedExport.Ready)?.let { ready ->
            deleteTemporaryPdf(ready.temporaryFile)
        }
    }

    private suspend fun recognize(request: SearchablePdfExportRequest): OcrResult? {
        reportProgress(request.progressListener, SearchablePdfExportProgress.Recognizing)
        return try {
            withContext(Dispatchers.IO) {
                coroutineContext.ensureActive()
                ocrEngine.recognize(
                    request.visualPages.map { page ->
                        OcrPage(
                            rotationDegrees = page.rotation.degrees,
                            imageMetadata = page.imageMetadata,
                        ) {
                            openSourceInputStream(page.originalUri)
                                ?: throw FileNotFoundException()
                        }
                    },
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }

    private fun orderOcrPages(ocrResult: OcrResult, pageCount: Int) =
        ocrResult.pages
            .takeIf { pages -> pages.size == pageCount }
            ?.sortedBy { page -> page.pageIndex }
            ?.takeIf { pages -> pages.indices.all { index -> pages[index].pageIndex == index } }

    /**
     * The generator invokes this one page at a time. OCR never uses this stream: it always uses
     * [SearchablePdfExportRequest.pageUris], the unfiltered original sources, in [recognize].
     */
    private fun openVisualJpegStream(page: SearchablePdfVisualPage): InputStream =
        when (val plan = searchablePdfVisualPlan(page)) {
            is SearchablePdfVisualPlan.Original -> {
                pageSourcePreflight(page.imageMetadata)?.let {
                    throw PageExportFailureException(it)
                }
                openSourceInputStream(page.originalUri) ?: throw FileNotFoundException()
            }

            is SearchablePdfVisualPlan.Filtered -> openFilteredVisualJpegStream(page, plan.filter)
        }

    private fun openFilteredVisualJpegStream(
        page: SearchablePdfVisualPage,
        filter: org.synapseworks.pageharbor.image.DocumentFilter,
    ): InputStream {
        val temporaryImage = createTemporaryVisualJpeg()
            ?: throw FileNotFoundException()
        val destination = try {
            temporaryImage.outputStream()
        } catch (_: IOException) {
            deleteTemporaryVisualJpeg(temporaryImage)
            throw FileNotFoundException()
        } catch (_: SecurityException) {
            deleteTemporaryVisualJpeg(temporaryImage)
            throw FileNotFoundException()
        }
        val result = writeFilteredJpegToDestination(
            openSource = { openSourceInputStream(page.originalUri) },
            destination = destination,
            filter = filter,
            rotation = page.rotation,
            imageMetadata = page.imageMetadata,
        )
        when (result) {
            PageExportResult.Success -> Unit
            PageExportResult.SourceTooLarge -> {
                deleteTemporaryVisualJpeg(temporaryImage)
                throw PageExportFailureException(result)
            }

            PageExportResult.SourceMissing -> {
                deleteTemporaryVisualJpeg(temporaryImage)
                throw FileNotFoundException()
            }

            PageExportResult.DestinationUnavailable,
            PageExportResult.WriteFailed,
            -> {
                deleteTemporaryVisualJpeg(temporaryImage)
                throw IOException()
            }
        }
        return try {
            DeleteOnCloseInputStream(temporaryImage.inputStream(), temporaryImage)
        } catch (_: IOException) {
            deleteTemporaryVisualJpeg(temporaryImage)
            throw FileNotFoundException()
        }
    }

    private fun createTemporaryPdf(): File? {
        return createTemporaryPdfFile()
    }

    private fun reportProgress(
        listener: SearchablePdfExportProgressListener,
        progress: SearchablePdfExportProgress,
    ) {
        try {
            listener.onProgress(progress)
        } catch (_: RuntimeException) {
            // Progress observation cannot change document handling or expose sensitive details.
        }
    }

    private fun deleteTemporaryPdf(file: File) {
        if (file.isFile) {
            try {
                deleteTemporaryFile(file)
            } catch (_: SecurityException) {
                // Private-cache cleanup is best-effort and must not expose document details.
            }
        }
    }

    private fun createTemporaryVisualJpeg(): File? {
        val directory = File(cacheDirectory, TemporaryPdfDirectory)
        return try {
            if (!directory.exists() && !directory.mkdirs()) return null
            File.createTempFile(TemporaryVisualPrefix, ".jpg", directory)
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private fun deleteTemporaryVisualJpeg(file: File) {
        if (file.isFile) {
            try {
                deleteTemporaryFile(file)
            } catch (_: SecurityException) {
                // Private-cache cleanup is best-effort and must not expose document details.
            }
        }
    }

    private inner class DeleteOnCloseInputStream(
        input: InputStream,
        private val temporaryImage: File,
    ) : FilterInputStream(input) {
        override fun close() {
            try {
                super.close()
            } finally {
                deleteTemporaryVisualJpeg(temporaryImage)
            }
        }
    }

    private companion object {
        const val TemporaryPdfDirectory = "searchable-pdfs"
        const val TemporaryPdfPrefix = "searchable-"
        const val TemporaryVisualPrefix = "searchable-visual-"
        const val CopyBufferSize = 8 * 1024

        fun createPrivateTemporaryPdf(cacheDirectory: File): File? {
            val directory = File(cacheDirectory, TemporaryPdfDirectory)
            return try {
                if (!directory.exists() && !directory.mkdirs()) return null
                File.createTempFile(TemporaryPdfPrefix, ".pdf", directory)
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            }
        }
    }
}

internal fun searchablePreparationErrorForGenerationFailure(
    error: SearchablePdfGenerationError,
): SearchablePdfPreparationError = if (
    error == SearchablePdfGenerationError.PAGE_IMAGE_TOO_LARGE
) {
    SearchablePdfPreparationError.SOURCE_TOO_LARGE
} else {
    SearchablePdfPreparationError.GENERATION_FAILED
}
