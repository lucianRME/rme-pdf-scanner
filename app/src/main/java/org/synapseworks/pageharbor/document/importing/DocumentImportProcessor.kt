package org.synapseworks.pageharbor.document.importing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.synapseworks.pageharbor.document.readImageBounds
import org.synapseworks.pageharbor.document.session.AcquiredDocumentPage
import org.synapseworks.pageharbor.document.session.AcquiredResource
import org.synapseworks.pageharbor.document.session.DEFAULT_DOCUMENT_INPUT_LIMITS
import org.synapseworks.pageharbor.document.session.DocumentAcquisitionInput
import org.synapseworks.pageharbor.document.session.DocumentImageMetadata
import org.synapseworks.pageharbor.document.session.DocumentImageConstraintViolation
import org.synapseworks.pageharbor.document.session.DocumentResourceOwnership
import org.synapseworks.pageharbor.document.session.DocumentSourceCategory
import org.synapseworks.pageharbor.document.session.OwnedTemporaryFile
import org.synapseworks.pageharbor.document.session.imageConstraintViolation
import org.synapseworks.pageharbor.document.session.readDocumentImageMetadata

fun interface DocumentImportProgressListener {
    fun onProgress(completedItems: Int, totalItems: Int, preparedPages: Int)
}

sealed interface DocumentImportPreparationResult {
    data class Success(
        val input: DocumentAcquisitionInput,
        val skippedItems: Int,
    ) : DocumentImportPreparationResult

    data class Failure(val reason: DocumentImportError) : DocumentImportPreparationResult
}

/** Android adapter for selected/shared images and bounded, sequential PdfRenderer page import. */
class DocumentImportProcessor(
    context: Context,
    private val registerOwnedResource: (AcquiredResource) -> Boolean,
) {
    private val applicationContext = context.applicationContext
    private val resolver = applicationContext.contentResolver
    private val privateRoot = File(applicationContext.cacheDir, DOCUMENT_IMPORT_DIRECTORY)

    suspend fun prepare(
        uris: List<Uri>,
        imageSourceCategory: DocumentSourceCategory,
        pageCapacity: Int,
        progressListener: DocumentImportProgressListener = DocumentImportProgressListener { _, _, _ -> },
    ): DocumentImportPreparationResult = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) return@withContext DocumentImportPreparationResult.Failure(
            DocumentImportError.EMPTY_INPUT,
        )
        if (pageCapacity <= 0 || uris.size > pageCapacity) {
            return@withContext DocumentImportPreparationResult.Failure(
                DocumentImportError.PAGE_LIMIT_EXCEEDED,
            )
        }
        privateRoot.mkdirs()
        if (!privateRoot.isDirectory) {
            return@withContext DocumentImportPreparationResult.Failure(
                DocumentImportError.TEMPORARY_FILE_FAILED,
            )
        }

        val preparedPages = mutableListOf<AcquiredDocumentPage>()
        val preparedPdfSources = mutableListOf<AcquiredResource>()
        var skippedItems = 0
        var firstFailure: DocumentImportError? = null
        uris.forEachIndexed { index, uri ->
            coroutineContext.ensureActive()
            val remainingCapacity = pageCapacity - preparedPages.size
            if (remainingCapacity <= 0) {
                return@withContext DocumentImportPreparationResult.Failure(
                    DocumentImportError.PAGE_LIMIT_EXCEEDED,
                )
            }
            when (
                val item = prepareOne(
                    uri = uri,
                    imageSourceCategory = imageSourceCategory,
                    remainingCapacity = remainingCapacity,
                    onPreparedPages = { itemPageCount ->
                        progressListener.onProgress(
                            index,
                            uris.size,
                            preparedPages.size + itemPageCount,
                        )
                    },
                )
            ) {
                is PreparedItem.Success -> {
                    preparedPages += item.pages
                    item.directPdfSource?.let(preparedPdfSources::add)
                }
                is PreparedItem.Failure -> {
                    if (item.reason == DocumentImportError.PAGE_LIMIT_EXCEEDED) {
                        return@withContext DocumentImportPreparationResult.Failure(item.reason)
                    }
                    skippedItems += 1
                    if (firstFailure == null) firstFailure = item.reason
                }
            }
            progressListener.onProgress(index + 1, uris.size, preparedPages.size)
        }
        if (preparedPages.isEmpty()) {
            DocumentImportPreparationResult.Failure(
                firstFailure ?: DocumentImportError.UNREADABLE_SOURCE,
            )
        } else {
            val directPdfSource = preparedPdfSources.singleOrNull()
                ?.takeIf { uris.size == 1 && skippedItems == 0 }
            DocumentImportPreparationResult.Success(
                input = DocumentAcquisitionInput(
                    pages = preparedPages,
                    directPdfSource = directPdfSource,
                    originalPdfSources = preparedPdfSources,
                ),
                skippedItems = skippedItems,
            )
        }
    }

    private suspend fun prepareOne(
        uri: Uri,
        imageSourceCategory: DocumentSourceCategory,
        remainingCapacity: Int,
        onPreparedPages: (Int) -> Unit,
    ): PreparedItem {
        val inspectedType = try {
            val input = resolver.openInputStream(uri)
                ?: return PreparedItem.Failure(DocumentImportError.UNREADABLE_SOURCE)
            input.use(::sniffSupportedContentType)
        } catch (_: IOException) {
            return PreparedItem.Failure(DocumentImportError.UNREADABLE_SOURCE)
        } catch (_: SecurityException) {
            return PreparedItem.Failure(DocumentImportError.UNREADABLE_SOURCE)
        } catch (_: IllegalArgumentException) {
            return PreparedItem.Failure(DocumentImportError.UNREADABLE_SOURCE)
        }
        val contentType = inspectedType
            ?: return PreparedItem.Failure(DocumentImportError.UNSUPPORTED_TYPE)

        return when (contentType) {
            "application/pdf" -> preparePdf(uri, remainingCapacity, onPreparedPages)
            "image/jpeg", "image/png", "image/webp" ->
                prepareImage(uri, imageSourceCategory, contentType)
            else -> PreparedItem.Failure(DocumentImportError.UNSUPPORTED_TYPE)
        }
    }

    private fun prepareImage(
        uri: Uri,
        sourceCategory: DocumentSourceCategory,
        contentType: String,
    ): PreparedItem {
        val knownMetadata = resolver.readDocumentImageMetadata(uri)
        if (knownMetadata.sourceByteCount?.let { it > DEFAULT_DOCUMENT_INPUT_LIMITS.maxSourceBytes } == true) {
            return PreparedItem.Failure(DocumentImportError.SOURCE_TOO_LARGE)
        }
        val bounds = try {
            readImageBounds { resolver.openInputStream(uri) }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } ?: return PreparedItem.Failure(DocumentImportError.INVALID_IMAGE)
        val metadata = knownMetadata.copy(width = bounds.first, height = bounds.second)
        when (DEFAULT_DOCUMENT_INPUT_LIMITS.imageConstraintViolation(metadata)) {
            DocumentImageConstraintViolation.INVALID_METADATA ->
                return PreparedItem.Failure(DocumentImportError.INVALID_IMAGE)
            DocumentImageConstraintViolation.SOURCE_BYTES_EXCEEDED,
            DocumentImageConstraintViolation.DIMENSIONS_EXCEEDED,
            -> return PreparedItem.Failure(DocumentImportError.SOURCE_TOO_LARGE)
            null -> Unit
        }
        return PreparedItem.Success(
            listOf(
                AcquiredDocumentPage(
                    resource = AcquiredResource(reference = uri.toString()),
                    sourceCategory = sourceCategory,
                    contentType = contentType,
                    imageMetadata = metadata,
                ),
            ),
        )
    }

    private suspend fun preparePdf(
        uri: Uri,
        remainingCapacity: Int,
        onPreparedPages: (Int) -> Unit,
    ): PreparedItem {
        val pdfFile = createOwnedTemporaryFile("import-source-", ".pdf")
            ?: return PreparedItem.Failure(DocumentImportError.TEMPORARY_FILE_FAILED)
        val pdfResource = ownedResource(pdfFile) ?: run {
            pdfFile.deleteSafely()
            return PreparedItem.Failure(DocumentImportError.TEMPORARY_FILE_FAILED)
        }
        if (!registerOwnedResource(pdfResource)) {
            return PreparedItem.Failure(DocumentImportError.INTERRUPTED)
        }
        when (copyPdfBounded(uri, pdfFile)) {
            CopyResult.Success -> Unit
            CopyResult.SourceTooLarge -> return PreparedItem.Failure(DocumentImportError.SOURCE_TOO_LARGE)
            CopyResult.Failed -> return PreparedItem.Failure(DocumentImportError.PDF_UNREADABLE)
        }
        coroutineContext.ensureActive()
        return try {
            ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    if (renderer.pageCount <= 0) {
                        return PreparedItem.Failure(DocumentImportError.PDF_UNREADABLE)
                    }
                    if (renderer.pageCount > remainingCapacity) {
                        return PreparedItem.Failure(DocumentImportError.PAGE_LIMIT_EXCEEDED)
                    }
                    val pages = mutableListOf<AcquiredDocumentPage>()
                    repeat(renderer.pageCount) { pageIndex ->
                        coroutineContext.ensureActive()
                        val rendered = renderPage(renderer, pageIndex)
                            ?: return PreparedItem.Failure(DocumentImportError.PDF_UNREADABLE)
                        pages += rendered
                        onPreparedPages(pages.size)
                    }
                    PreparedItem.Success(
                        pages = pages,
                        directPdfSource = pdfResource,
                    )
                }
            }
        } catch (_: CancellationException) {
            throw CancellationException()
        } catch (_: IOException) {
            PreparedItem.Failure(DocumentImportError.PDF_UNREADABLE)
        } catch (_: SecurityException) {
            PreparedItem.Failure(DocumentImportError.PDF_UNREADABLE)
        } catch (_: IllegalArgumentException) {
            PreparedItem.Failure(DocumentImportError.PDF_UNREADABLE)
        } catch (_: IllegalStateException) {
            PreparedItem.Failure(DocumentImportError.PDF_UNREADABLE)
        }
    }

    private fun renderPage(renderer: PdfRenderer, pageIndex: Int): AcquiredDocumentPage? {
        val outputFile = createOwnedTemporaryFile("import-page-", ".jpg") ?: return null
        val resource = ownedResource(outputFile) ?: run {
            outputFile.deleteSafely()
            return null
        }
        if (!registerOwnedResource(resource)) return null
        return renderer.openPage(pageIndex).use { page ->
            val size = calculatePdfRenderSize(page.width, page.height) ?: return null
            val bitmap = try {
                Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
            } catch (_: OutOfMemoryError) {
                return null
            }
            try {
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                val written = try {
                    FileOutputStream(outputFile).use { output ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output).also { success ->
                            if (success) output.flush()
                        }
                    }
                } catch (_: IOException) {
                    false
                }
                if (!written || !outputFile.isFile || outputFile.length() <= 0L) return null
                AcquiredDocumentPage(
                    resource = resource,
                    sourceCategory = DocumentSourceCategory.RENDERED_PDF_PAGE,
                    contentType = "image/jpeg",
                    imageMetadata = DocumentImageMetadata(
                        sourceByteCount = outputFile.length(),
                        width = size.width,
                        height = size.height,
                    ),
                )
            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun copyPdfBounded(uri: Uri, destination: File): CopyResult = try {
        val input = resolver.openInputStream(uri) ?: return CopyResult.Failed
        input.use { source ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(32 * 1024)
                var total = 0L
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_PDF_IMPORT_BYTES) return CopyResult.SourceTooLarge
                    output.write(buffer, 0, count)
                }
                output.flush()
            }
        }
        if (destination.length() <= 0L) CopyResult.Failed else CopyResult.Success
    } catch (_: IOException) {
        CopyResult.Failed
    } catch (_: SecurityException) {
        CopyResult.Failed
    } catch (_: IllegalArgumentException) {
        CopyResult.Failed
    }

    private fun createOwnedTemporaryFile(prefix: String, suffix: String): File? = try {
        File.createTempFile(prefix, suffix, privateRoot)
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun ownedResource(file: File): AcquiredResource? = try {
        AcquiredResource(
            reference = FileProvider.getUriForFile(
                applicationContext,
                "${applicationContext.packageName}.fileprovider",
                file,
            ).toString(),
            ownership = DocumentResourceOwnership.RME_OWNED_TEMPORARY,
            ownedTemporaryFile = OwnedTemporaryFile(
                path = file.path,
                rootPath = privateRoot.path,
            ),
        )
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun File.deleteSafely() {
        try {
            if (isFile) delete()
        } catch (_: SecurityException) {
            // Private cleanup is best-effort and never logs document names or paths.
        }
    }

    private sealed interface PreparedItem {
        data class Success(
            val pages: List<AcquiredDocumentPage>,
            val directPdfSource: AcquiredResource? = null,
        ) : PreparedItem
        data class Failure(val reason: DocumentImportError) : PreparedItem
    }

    private enum class CopyResult {
        Success,
        SourceTooLarge,
        Failed,
    }
}

private const val DOCUMENT_IMPORT_DIRECTORY = "document-imports"
private const val DOCUMENT_IMPORT_MAX_STALE_AGE_MILLIS = 24L * 60L * 60L * 1_000L

/** Cold-session recovery for private import files orphaned by process death. */
fun deleteStaleDocumentImports(
    cacheDirectory: File,
    nowMillis: Long = System.currentTimeMillis(),
) {
    File(cacheDirectory, DOCUMENT_IMPORT_DIRECTORY).listFiles()?.forEach { file ->
        if (nowMillis - file.lastModified() >= DOCUMENT_IMPORT_MAX_STALE_AGE_MILLIS) {
            try {
                if (file.isFile) file.delete()
            } catch (_: SecurityException) {
                // Cleanup is best-effort and never logs document names or paths.
            }
        }
    }
}
