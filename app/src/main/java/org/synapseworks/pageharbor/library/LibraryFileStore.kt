package org.synapseworks.pageharbor.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.synapseworks.pageharbor.document.session.DEFAULT_MAX_IMAGE_SOURCE_BYTES
import org.synapseworks.pageharbor.document.session.DocumentImageMetadata
import org.synapseworks.pageharbor.document.session.DocumentPageRotation
import org.synapseworks.pageharbor.image.ArgbImage
import org.synapseworks.pageharbor.image.DocumentFilter
import org.synapseworks.pageharbor.image.DocumentImageFilterEngine

internal data class LibraryPageSource(
    val persistentId: String?,
    val contentType: String,
    val sourceCategory: String,
    val imageMetadata: DocumentImageMetadata,
    val rotation: DocumentPageRotation,
    val filter: DocumentFilter,
    val ocrText: String?,
    val ocrError: String?,
    val openStream: () -> InputStream,
)

internal data class PreparedLibraryPage(
    val pageId: String,
    val position: Int,
    val relativePath: String,
    val contentType: String,
    val sourceCategory: String,
    val imageMetadata: DocumentImageMetadata,
    val rotation: DocumentPageRotation,
    val filter: DocumentFilter,
    val ocrText: String?,
    val ocrError: String?,
)

internal data class PreparedLibraryRevision(
    val revisionDirectory: File,
    val pages: List<PreparedLibraryPage>,
    val thumbnailRelativePath: String?,
    val warning: LibraryWarning?,
)

internal class LibraryFileStore(
    context: Context,
    val root: File = File(context.applicationContext.filesDir, LIBRARY_DIRECTORY),
) {

    suspend fun prepareRevision(
        documentId: String,
        sources: List<LibraryPageSource>,
    ): LibraryResult<PreparedLibraryRevision> = withContext(Dispatchers.IO) {
        if (!isSafeIdentifier(documentId) || sources.isEmpty()) {
            return@withContext LibraryResult.Failure(LibraryError.CORRUPTED_RECORD)
        }
        val revisionsRoot = File(File(root, documentId), REVISIONS_DIRECTORY)
        val revisionId = UUID.randomUUID().toString()
        val staging = File(revisionsRoot, ".$revisionId-staging")
        val completed = File(revisionsRoot, revisionId)
        if (!staging.mkdirs() || !staging.isDirectory) {
            return@withContext LibraryResult.Failure(LibraryError.STORAGE_UNAVAILABLE)
        }

        val prepared = mutableListOf<PreparedLibraryPage>()
        try {
            sources.forEachIndexed { index, source ->
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val pageId = source.persistentId?.takeIf(::isSafeIdentifier)
                    ?: UUID.randomUUID().toString()
                val destination = File(staging, "$pageId.${extensionFor(source.contentType)}")
                when (copyBounded(source.openStream, destination)) {
                    CopyOutcome.SUCCESS -> Unit
                    CopyOutcome.SOURCE_TOO_LARGE -> {
                        staging.deleteRecursivelySafely(root)
                        return@withContext LibraryResult.Failure(LibraryError.SOURCE_TOO_LARGE)
                    }
                    CopyOutcome.FAILED -> {
                        staging.deleteRecursivelySafely(root)
                        return@withContext LibraryResult.Failure(LibraryError.SOURCE_MISSING)
                    }
                }
                prepared += PreparedLibraryPage(
                    pageId = pageId,
                    position = index,
                    relativePath = relativePath(destination),
                    contentType = source.contentType,
                    sourceCategory = source.sourceCategory,
                    imageMetadata = source.imageMetadata.copy(sourceByteCount = destination.length()),
                    rotation = source.rotation,
                    filter = source.filter,
                    ocrText = source.ocrText,
                    ocrError = source.ocrError,
                )
            }

            val thumbnail = File(staging, THUMBNAIL_FILENAME)
            val thumbnailCreated = createThumbnail(
                source = resolve(prepared.first().relativePath) ?: File(staging, "missing"),
                destination = thumbnail,
                rotation = prepared.first().rotation,
                filter = prepared.first().filter,
            )
            if (!staging.renameTo(completed)) {
                staging.deleteRecursivelySafely(root)
                return@withContext LibraryResult.Failure(LibraryError.STORAGE_UNAVAILABLE)
            }
            val completedPages = prepared.map { page ->
                page.copy(
                    relativePath = relativePath(File(completed, File(page.relativePath).name)),
                )
            }
            val thumbnailPath = if (thumbnailCreated) {
                relativePath(File(completed, THUMBNAIL_FILENAME))
            } else {
                null
            }
            LibraryResult.Success(
                PreparedLibraryRevision(
                    revisionDirectory = completed,
                    pages = completedPages,
                    thumbnailRelativePath = thumbnailPath,
                    warning = if (thumbnailCreated) null else LibraryWarning.THUMBNAIL_UNAVAILABLE,
                ),
                warning = if (thumbnailCreated) null else LibraryWarning.THUMBNAIL_UNAVAILABLE,
            )
        } catch (error: kotlinx.coroutines.CancellationException) {
            staging.deleteRecursivelySafely(root)
            throw error
        } catch (_: Exception) {
            staging.deleteRecursivelySafely(root)
            LibraryResult.Failure(LibraryError.STORAGE_UNAVAILABLE)
        }
    }

    fun resolve(relativePath: String): File? {
        if (relativePath.isBlank()) return null
        return try {
            val canonicalRoot = root.canonicalFile
            val file = File(canonicalRoot, relativePath).canonicalFile
            if (file == canonicalRoot || !file.toPath().startsWith(canonicalRoot.toPath())) null else file
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    fun cleanupOtherRevisions(documentId: String, keepDirectory: File) {
        val revisions = File(File(root, documentId), REVISIONS_DIRECTORY)
        val canonicalKeep = try {
            keepDirectory.canonicalFile
        } catch (_: IOException) {
            return
        } catch (_: SecurityException) {
            return
        }
        revisions.listFiles()?.forEach { candidate ->
            val isKeptRevision = try {
                candidate.canonicalFile == canonicalKeep
            } catch (_: IOException) {
                true
            } catch (_: SecurityException) {
                true
            }
            if (!isKeptRevision) candidate.deleteRecursivelySafely(root)
        }
    }

    fun discardRevision(revisionDirectory: File) {
        revisionDirectory.deleteRecursivelySafely(root)
    }

    fun deleteDocument(documentId: String) {
        if (!isSafeIdentifier(documentId)) return
        File(root, documentId).deleteRecursivelySafely(root)
    }

    private fun relativePath(file: File): String =
        root.canonicalFile.toPath().relativize(file.canonicalFile.toPath()).toString()

    private suspend fun copyBounded(
        openSource: () -> InputStream,
        destination: File,
    ): CopyOutcome = try {
        openSource().use { source ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(COPY_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = source.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > DEFAULT_MAX_IMAGE_SOURCE_BYTES) return CopyOutcome.SOURCE_TOO_LARGE
                    output.write(buffer, 0, count)
                }
                output.flush()
            }
        }
        if (destination.isFile && destination.length() > 0L) CopyOutcome.SUCCESS else CopyOutcome.FAILED
    } catch (error: CancellationException) {
        throw error
    } catch (_: IOException) {
        CopyOutcome.FAILED
    } catch (_: SecurityException) {
        CopyOutcome.FAILED
    } catch (_: IllegalArgumentException) {
        CopyOutcome.FAILED
    }

    private fun createThumbnail(
        source: File,
        destination: File,
        rotation: DocumentPageRotation,
        filter: DocumentFilter,
    ): Boolean {
        if (!source.isFile) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        var sample = 1
        while (max(bounds.outWidth / sample, bounds.outHeight / sample) > THUMBNAIL_MAX_EDGE * 2) {
            sample *= 2
        }
        val decoded = BitmapFactory.decodeFile(
            source.path,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: return false
        var transformed: Bitmap = decoded
        return try {
            if (rotation != DocumentPageRotation.DEGREES_0) {
                transformed = Bitmap.createBitmap(
                    transformed,
                    0,
                    0,
                    transformed.width,
                    transformed.height,
                    Matrix().apply { setRotate(rotation.degrees.toFloat()) },
                    true,
                ).also { if (transformed !== decoded) transformed.recycle() }
            }
            if (filter != DocumentFilter.ORIGINAL) {
                val pixels = IntArray(transformed.width * transformed.height)
                transformed.getPixels(
                    pixels,
                    0,
                    transformed.width,
                    0,
                    0,
                    transformed.width,
                    transformed.height,
                )
                val filtered = DocumentImageFilterEngine.apply(
                    ArgbImage(transformed.width, transformed.height, pixels),
                    filter,
                )
                val replacement = Bitmap.createBitmap(
                    filtered.pixels,
                    filtered.width,
                    filtered.height,
                    Bitmap.Config.ARGB_8888,
                )
                if (transformed !== decoded) transformed.recycle()
                transformed = replacement
            }
            val scale = minOf(
                1f,
                THUMBNAIL_MAX_EDGE.toFloat() / max(transformed.width, transformed.height),
            )
            val finalBitmap = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    transformed,
                    (transformed.width * scale).toInt().coerceAtLeast(1),
                    (transformed.height * scale).toInt().coerceAtLeast(1),
                    true,
                )
            } else {
                transformed
            }
            try {
                FileOutputStream(destination).use { output ->
                    finalBitmap.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_JPEG_QUALITY, output)
                        .also { if (it) output.flush() }
                }
            } finally {
                if (finalBitmap !== transformed) finalBitmap.recycle()
            }
        } catch (_: Exception) {
            false
        } finally {
            if (transformed !== decoded && !transformed.isRecycled) transformed.recycle()
            if (!decoded.isRecycled) decoded.recycle()
            if (!destination.isFile || destination.length() <= 0L) destination.delete()
        }
    }

    private enum class CopyOutcome {
        SUCCESS,
        SOURCE_TOO_LARGE,
        FAILED,
    }

    companion object {
        const val LIBRARY_DIRECTORY = "document-library"
        private const val REVISIONS_DIRECTORY = "revisions"
        private const val THUMBNAIL_FILENAME = "thumbnail.jpg"
        private const val THUMBNAIL_MAX_EDGE = 480
        private const val THUMBNAIL_JPEG_QUALITY = 84
        private const val COPY_BUFFER_SIZE = 32 * 1024
    }
}

private fun extensionFor(contentType: String): String = when (contentType.lowercase()) {
    "image/png" -> "png"
    "image/webp" -> "webp"
    else -> "jpg"
}

private fun isSafeIdentifier(value: String): Boolean =
    value.isNotBlank() && value.length <= 80 && value.all { it.isLetterOrDigit() || it == '-' }

private fun File.deleteRecursivelySafely(root: File) {
    try {
        val canonicalRoot = root.canonicalFile
        val target = canonicalFile
        if (target == canonicalRoot || !target.toPath().startsWith(canonicalRoot.toPath())) return
        deleteRecursively()
    } catch (_: IOException) {
        // Private library cleanup is best-effort and never logs document paths.
    } catch (_: SecurityException) {
        // Private library cleanup is best-effort and never logs document paths.
    }
}
