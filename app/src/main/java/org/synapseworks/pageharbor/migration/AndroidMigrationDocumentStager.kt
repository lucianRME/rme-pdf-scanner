package org.synapseworks.pageharbor.migration

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.synapseworks.pageharbor.document.importing.MAX_PDF_IMPORT_BYTES
import org.synapseworks.pageharbor.document.importing.calculatePdfRenderSize
import org.synapseworks.pageharbor.document.importing.sniffSupportedContentType
import org.synapseworks.pageharbor.document.session.DEFAULT_DOCUMENT_INPUT_LIMITS
import org.synapseworks.pageharbor.document.session.DEFAULT_MAX_IMAGE_SOURCE_BYTES
import org.synapseworks.pageharbor.document.session.DEFAULT_MAX_IMPORTED_DOCUMENT_PAGES
import org.synapseworks.pageharbor.document.session.DocumentImageConstraintViolation
import org.synapseworks.pageharbor.document.session.DocumentImageMetadata
import org.synapseworks.pageharbor.document.session.imageConstraintViolation

internal data class StagedMigrationPage(
    val file: File,
    val contentType: String,
    val width: Int,
    val height: Int,
    val byteCount: Long,
    val sha256: String,
)

internal data class StagedMigrationOriginalPdf(
    val file: File,
    val byteCount: Long,
    val sha256: String,
    val sourceModifiedAtMillis: Long?,
)

internal class StagedMigrationDocument(
    val workspace: File,
    val pages: List<StagedMigrationPage>,
    val originalPdf: StagedMigrationOriginalPdf?,
    val copiedSourceBytes: Long,
) : AutoCloseable {
    override fun close() {
        workspace.deleteMigrationWorkspace()
    }
}

internal sealed interface MigrationStagingResult {
    data class Ready(val document: StagedMigrationDocument) : MigrationStagingResult
    data object Cancelled : MigrationStagingResult
    data class Failed(
        val reason: MigrationPublicationFailure,
        val retryable: Boolean,
    ) : MigrationStagingResult
}

internal interface MigrationDocumentStager {
    suspend fun stage(
        plan: MigrationDocumentPlan,
        context: MigrationPublicationContext,
    ): MigrationStagingResult

    fun cleanAbandonedWorkspaces()
}

/** Owns a private, one-document-at-a-time working set; it never enters the UI session pipeline. */
internal class AndroidMigrationDocumentStager(
    private val workspaceRoot: File,
    private val sourceAccess: MigrationSourceAccess,
) : MigrationDocumentStager {
    override suspend fun stage(
        plan: MigrationDocumentPlan,
        context: MigrationPublicationContext,
    ): MigrationStagingResult = withContext(Dispatchers.IO) {
        if (context.cancellationSignal.isCancellationRequested()) {
            return@withContext MigrationStagingResult.Cancelled
        }
        val workspace = File(workspaceRoot, UUID.randomUUID().toString())
        if (!workspace.mkdirs() || !workspace.isDirectory) {
            return@withContext MigrationStagingResult.Failed(
                MigrationPublicationFailure.WRITE_FAILED,
                retryable = true,
            )
        }
        try {
            val result = when (plan.grouping) {
                MigrationDocumentGrouping.SINGLE_PDF -> stagePdf(plan, workspace, context)
                MigrationDocumentGrouping.SINGLE_IMAGE,
                MigrationDocumentGrouping.HIGH_CONFIDENCE_IMAGE_SEQUENCE,
                -> stageImages(plan, workspace, context)
            }
            if (result !is MigrationStagingResult.Ready) workspace.deleteMigrationWorkspace()
            result
        } catch (cancelled: CancellationException) {
            workspace.deleteMigrationWorkspace()
            if (context.cancellationSignal.isCancellationRequested()) {
                MigrationStagingResult.Cancelled
            } else {
                throw cancelled
            }
        } catch (_: IOException) {
            workspace.deleteMigrationWorkspace()
            MigrationStagingResult.Failed(
                MigrationPublicationFailure.SOURCE_UNAVAILABLE,
                retryable = true,
            )
        } catch (_: SecurityException) {
            workspace.deleteMigrationWorkspace()
            MigrationStagingResult.Failed(
                MigrationPublicationFailure.SOURCE_UNAVAILABLE,
                retryable = true,
            )
        } catch (_: RuntimeException) {
            workspace.deleteMigrationWorkspace()
            MigrationStagingResult.Failed(
                MigrationPublicationFailure.INVALID_DOCUMENT,
                retryable = false,
            )
        }
    }

    override fun cleanAbandonedWorkspaces() {
        val root = workspaceRoot.canonicalOrNull() ?: return
        root.listFiles()?.forEach { candidate ->
            val canonical = candidate.canonicalOrNull() ?: return@forEach
            if (canonical.parentFile == root && canonical.isDirectory) {
                canonical.deleteRecursively()
            }
        }
    }

    private suspend fun stageImages(
        plan: MigrationDocumentPlan,
        workspace: File,
        context: MigrationPublicationContext,
    ): MigrationStagingResult {
        val totalBytes = plan.sources.knownTotalBytesOrNull()
        var copiedBytes = 0L
        val pages = mutableListOf<StagedMigrationPage>()
        plan.sources.forEachIndexed { index, inspected ->
            currentCoroutineContext().ensureActive()
            if (context.cancellationSignal.isCancellationRequested()) {
                return MigrationStagingResult.Cancelled
            }
            val destination = File(workspace, "page-$index.${inspected.contentType.extension}")
            val copy = copySource(
                inspected = inspected,
                destination = destination,
                maximumBytes = DEFAULT_MAX_IMAGE_SOURCE_BYTES,
                alreadyCopied = copiedBytes,
                totalBytes = totalBytes,
                context = context,
            )
            when (copy) {
                CopyResult.Cancelled -> return MigrationStagingResult.Cancelled
                CopyResult.SourceTooLarge -> return MigrationStagingResult.Failed(
                    MigrationPublicationFailure.SOURCE_TOO_LARGE,
                    retryable = false,
                )
                CopyResult.Failed -> return MigrationStagingResult.Failed(
                    MigrationPublicationFailure.SOURCE_UNAVAILABLE,
                    retryable = true,
                )
                is CopyResult.Success -> {
                    copiedBytes = saturatingAdd(copiedBytes, copy.byteCount)
                    if (!destination.hasSignature(inspected.contentType)) {
                        return MigrationStagingResult.Failed(
                            MigrationPublicationFailure.INVALID_DOCUMENT,
                            retryable = false,
                        )
                    }
                    val bounds = destination.imageBoundsOrNull()
                        ?: return MigrationStagingResult.Failed(
                            MigrationPublicationFailure.INVALID_DOCUMENT,
                            retryable = false,
                        )
                    val metadata = DocumentImageMetadata(copy.byteCount, bounds.first, bounds.second)
                    when (DEFAULT_DOCUMENT_INPUT_LIMITS.imageConstraintViolation(metadata)) {
                        null -> Unit
                        DocumentImageConstraintViolation.SOURCE_BYTES_EXCEEDED,
                        DocumentImageConstraintViolation.DIMENSIONS_EXCEEDED,
                        -> return MigrationStagingResult.Failed(
                            MigrationPublicationFailure.SOURCE_TOO_LARGE,
                            retryable = false,
                        )
                        DocumentImageConstraintViolation.INVALID_METADATA ->
                            return MigrationStagingResult.Failed(
                                MigrationPublicationFailure.INVALID_DOCUMENT,
                                retryable = false,
                            )
                    }
                    pages += StagedMigrationPage(
                        file = destination,
                        contentType = inspected.contentType.mimeType,
                        width = bounds.first,
                        height = bounds.second,
                        byteCount = copy.byteCount,
                        sha256 = copy.sha256,
                    )
                }
            }
        }
        return MigrationStagingResult.Ready(
            StagedMigrationDocument(workspace, pages, null, copiedBytes),
        )
    }

    private suspend fun stagePdf(
        plan: MigrationDocumentPlan,
        workspace: File,
        context: MigrationPublicationContext,
    ): MigrationStagingResult {
        val inspected = plan.sources.single()
        val pdfFile = File(workspace, "original.pdf")
        val copy = copySource(
            inspected = inspected,
            destination = pdfFile,
            maximumBytes = MAX_PDF_IMPORT_BYTES,
            alreadyCopied = 0L,
            totalBytes = inspected.source.sizeBytes,
            context = context,
        )
        val copied = when (copy) {
            CopyResult.Cancelled -> return MigrationStagingResult.Cancelled
            CopyResult.SourceTooLarge -> return MigrationStagingResult.Failed(
                MigrationPublicationFailure.SOURCE_TOO_LARGE,
                retryable = false,
            )
            CopyResult.Failed -> return MigrationStagingResult.Failed(
                MigrationPublicationFailure.SOURCE_UNAVAILABLE,
                retryable = true,
            )
            is CopyResult.Success -> copy
        }
        if (!pdfFile.hasSignature(MigrationContentType.PDF)) {
            return MigrationStagingResult.Failed(
                MigrationPublicationFailure.INVALID_DOCUMENT,
                retryable = false,
            )
        }
        val pages = renderPdf(pdfFile, workspace, context)
            ?: return if (context.cancellationSignal.isCancellationRequested()) {
                MigrationStagingResult.Cancelled
            } else {
                MigrationStagingResult.Failed(
                    MigrationPublicationFailure.INVALID_DOCUMENT,
                    retryable = false,
                )
            }
        return MigrationStagingResult.Ready(
            StagedMigrationDocument(
                workspace = workspace,
                pages = pages,
                originalPdf = StagedMigrationOriginalPdf(
                    file = pdfFile,
                    byteCount = copied.byteCount,
                    sha256 = copied.sha256,
                    sourceModifiedAtMillis = inspected.source.modifiedAtMillis,
                ),
                copiedSourceBytes = copied.byteCount,
            ),
        )
    }

    private suspend fun renderPdf(
        pdfFile: File,
        workspace: File,
        context: MigrationPublicationContext,
    ): List<StagedMigrationPage>? = try {
        ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                if (
                    renderer.pageCount <= 0 ||
                    renderer.pageCount > DEFAULT_MAX_IMPORTED_DOCUMENT_PAGES
                ) {
                    return null
                }
                val pages = ArrayList<StagedMigrationPage>(renderer.pageCount)
                repeat(renderer.pageCount) { pageIndex ->
                    currentCoroutineContext().ensureActive()
                    if (context.cancellationSignal.isCancellationRequested()) return null
                    val output = File(workspace, "rendered-$pageIndex.jpg")
                    val rendered = renderer.openPage(pageIndex).use { page ->
                        val size = calculatePdfRenderSize(page.width, page.height) ?: return null
                        val bitmap = try {
                            Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
                        } catch (_: OutOfMemoryError) {
                            return null
                        }
                        try {
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val written = FileOutputStream(output).use { stream ->
                                bitmap.compress(Bitmap.CompressFormat.JPEG, PDF_JPEG_QUALITY, stream)
                                    .also { success -> if (success) stream.flush() }
                            }
                            if (!written || !output.isFile || output.length() <= 0L) return null
                            val sha256 = output.sha256OrNull() ?: return null
                            StagedMigrationPage(
                                file = output,
                                contentType = MigrationContentType.JPEG.mimeType,
                                width = size.width,
                                height = size.height,
                                byteCount = output.length(),
                                sha256 = sha256,
                            )
                        } finally {
                            bitmap.recycle()
                        }
                    }
                    pages += rendered
                }
                pages
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: IllegalStateException) {
        null
    }

    private suspend fun copySource(
        inspected: InspectedMigrationSource,
        destination: File,
        maximumBytes: Long,
        alreadyCopied: Long,
        totalBytes: Long?,
        context: MigrationPublicationContext,
    ): CopyResult = try {
        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        sourceAccess.open(inspected.source).use { source ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(COPY_BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    if (context.cancellationSignal.isCancellationRequested()) {
                        return CopyResult.Cancelled
                    }
                    val count = source.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    copied = saturatingAdd(copied, count.toLong())
                    if (copied > maximumBytes) return CopyResult.SourceTooLarge
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                    context.onBytesCopied(saturatingAdd(alreadyCopied, copied), totalBytes)
                }
                output.flush()
            }
        }
        if (copied <= 0L || !destination.isFile || destination.length() != copied) {
            CopyResult.Failed
        } else {
            CopyResult.Success(copied, digest.digest().toLowerHex())
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: IOException) {
        CopyResult.Failed
    } catch (_: SecurityException) {
        CopyResult.Failed
    } catch (_: IllegalArgumentException) {
        CopyResult.Failed
    }

    private sealed interface CopyResult {
        data class Success(val byteCount: Long, val sha256: String) : CopyResult
        data object SourceTooLarge : CopyResult
        data object Cancelled : CopyResult
        data object Failed : CopyResult
    }
}

private fun List<InspectedMigrationSource>.knownTotalBytesOrNull(): Long? {
    if (any { it.source.sizeBytes == null }) return null
    return fold(0L) { total, source -> saturatingAdd(total, requireNotNull(source.source.sizeBytes)) }
}

private val MigrationContentType.extension: String
    get() = when (this) {
        MigrationContentType.PDF -> "pdf"
        MigrationContentType.JPEG -> "jpg"
        MigrationContentType.PNG -> "png"
        MigrationContentType.WEBP -> "webp"
    }

private fun File.hasSignature(expected: MigrationContentType): Boolean = try {
    FileInputStream(this).use { input -> sniffSupportedContentType(input) == expected.mimeType }
} catch (_: IOException) {
    false
} catch (_: SecurityException) {
    false
}

private fun File.imageBoundsOrNull(): Pair<Int, Int>? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, options)
    return if (options.outWidth > 0 && options.outHeight > 0) {
        options.outWidth to options.outHeight
    } else {
        null
    }
}

private fun File.sha256OrNull(): String? = try {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(this).use { input ->
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
    }
    digest.digest().toLowerHex()
} catch (_: IOException) {
    null
} catch (_: SecurityException) {
    null
}

private fun ByteArray.toLowerHex(): String = joinToString("") { byte ->
    "%02x".format(Locale.ROOT, byte.toInt() and 0xff)
}

private fun File.canonicalOrNull(): File? = try {
    canonicalFile
} catch (_: IOException) {
    null
} catch (_: SecurityException) {
    null
}

private fun File.deleteMigrationWorkspace() {
    val canonical = canonicalOrNull() ?: return
    val parent = canonical.parentFile ?: return
    if (canonical == parent || canonical.name.isBlank()) return
    try {
        canonical.deleteRecursively()
    } catch (_: SecurityException) {
        // Private-cache cleanup is best effort and never logs provider or document details.
    }
}

private fun saturatingAdd(left: Long, right: Long): Long =
    if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

private const val COPY_BUFFER_SIZE = 32 * 1024
private const val PDF_JPEG_QUALITY = 95
