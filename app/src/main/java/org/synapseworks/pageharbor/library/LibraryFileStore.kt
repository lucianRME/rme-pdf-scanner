package org.synapseworks.pageharbor.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.FileVisitResult
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
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
import org.synapseworks.pageharbor.document.importing.MAX_PDF_IMPORT_BYTES
import org.synapseworks.pageharbor.image.ArgbImage
import org.synapseworks.pageharbor.image.DocumentFilter
import org.synapseworks.pageharbor.image.DocumentImageFilterEngine

internal data class LibraryPageSource(
    val persistentId: String?,
    val ocrCloneSourcePageId: String? = null,
    val contentType: String,
    val sourceCategory: String,
    val imageMetadata: DocumentImageMetadata,
    val rotation: DocumentPageRotation,
    val filter: DocumentFilter,
    val ocrText: String?,
    val ocrError: String?,
    val openStream: () -> InputStream,
)

internal data class LibrarySourceAssetSource(
    val role: String,
    val contentType: String,
    val sourceModifiedAtMillis: Long?,
    val matchesCurrentRevision: Boolean,
    val openStream: () -> InputStream,
)

internal data class PreparedLibraryPage(
    val pageId: String,
    val ocrCloneSourcePageId: String?,
    val position: Int,
    val relativePath: String,
    val contentType: String,
    val sourceCategory: String,
    val imageMetadata: DocumentImageMetadata,
    val rotation: DocumentPageRotation,
    val filter: DocumentFilter,
    val ocrText: String?,
    val ocrError: String?,
    val contentSha256: String,
)

internal data class PreparedLibrarySourceAsset(
    val assetId: String,
    val role: String,
    val relativePath: String,
    val contentType: String,
    val byteCount: Long,
    val sha256: String,
    val sourceModifiedAtMillis: Long?,
    val matchesCurrentRevision: Boolean,
)

internal data class PreparedLibraryRevision(
    val revisionDirectory: File,
    val pages: List<PreparedLibraryPage>,
    val sourceAssets: List<PreparedLibrarySourceAsset>,
    val thumbnailRelativePath: String?,
    val warning: LibraryWarning?,
)

internal data class PendingDocumentDeletion(
    val documentId: String,
    val directory: File,
)

internal sealed interface DocumentDeletionPreparation {
    data object NoPrivateFiles : DocumentDeletionPreparation
    data class Quarantined(val pending: PendingDocumentDeletion) : DocumentDeletionPreparation
    data class AlreadyQuarantined(val pending: PendingDocumentDeletion) : DocumentDeletionPreparation
    data object QuarantineFailed : DocumentDeletionPreparation
    data object StateUncertain : DocumentDeletionPreparation
}

internal sealed interface DocumentDirectoryScan<out T> {
    data class Available<T>(val entries: List<T>) : DocumentDirectoryScan<T>
    data object Unavailable : DocumentDirectoryScan<Nothing>
}

internal open class LibraryFileStore(
    context: Context,
    val root: File = File(context.applicationContext.filesDir, LIBRARY_DIRECTORY),
) {

    suspend fun prepareRevision(
        documentId: String,
        sources: List<LibraryPageSource>,
        sourceAssets: List<LibrarySourceAssetSource> = emptyList(),
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
                val copied = when (
                    val copy = copyBounded(
                        openSource = source.openStream,
                        destination = destination,
                        maxBytes = DEFAULT_MAX_IMAGE_SOURCE_BYTES,
                    )
                ) {
                    is CopyOutcome.Success -> copy
                    CopyOutcome.SourceTooLarge -> {
                        staging.deleteRecursivelySafely(root)
                        return@withContext LibraryResult.Failure(LibraryError.SOURCE_TOO_LARGE)
                    }
                    CopyOutcome.Failed -> {
                        staging.deleteRecursivelySafely(root)
                        return@withContext LibraryResult.Failure(LibraryError.SOURCE_MISSING)
                    }
                }
                prepared += PreparedLibraryPage(
                    pageId = pageId,
                    ocrCloneSourcePageId = source.ocrCloneSourcePageId,
                    position = index,
                    relativePath = relativePath(destination),
                    contentType = source.contentType,
                    sourceCategory = source.sourceCategory,
                    imageMetadata = source.imageMetadata.copy(sourceByteCount = copied.byteCount),
                    rotation = source.rotation,
                    filter = source.filter,
                    ocrText = source.ocrText,
                    ocrError = source.ocrError,
                    contentSha256 = copied.sha256,
                )
            }

            val preparedSourceAssets = sourceAssets.map { source ->
                currentCoroutineContext().ensureActive()
                val assetId = UUID.randomUUID().toString()
                val destination = File(staging, "source-$assetId.${sourceAssetExtension(source.contentType)}")
                val copied = when (
                    val copy = copyBounded(
                        openSource = source.openStream,
                        destination = destination,
                        maxBytes = MAX_PDF_IMPORT_BYTES,
                    )
                ) {
                    is CopyOutcome.Success -> copy
                    CopyOutcome.SourceTooLarge -> {
                        staging.deleteRecursivelySafely(root)
                        return@withContext LibraryResult.Failure(LibraryError.SOURCE_TOO_LARGE)
                    }
                    CopyOutcome.Failed -> {
                        staging.deleteRecursivelySafely(root)
                        return@withContext LibraryResult.Failure(LibraryError.SOURCE_MISSING)
                    }
                }
                PreparedLibrarySourceAsset(
                    assetId = assetId,
                    role = source.role,
                    relativePath = relativePath(destination),
                    contentType = source.contentType,
                    byteCount = copied.byteCount,
                    sha256 = copied.sha256,
                    sourceModifiedAtMillis = source.sourceModifiedAtMillis,
                    matchesCurrentRevision = source.matchesCurrentRevision,
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
            val completedSourceAssets = preparedSourceAssets.map { asset ->
                asset.copy(
                    relativePath = relativePath(File(completed, File(asset.relativePath).name)),
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
                    sourceAssets = completedSourceAssets,
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

    /** Best-effort cleanup for already-unreferenced staging and migration assets. */
    fun deleteDocument(documentId: String) {
        if (!isSafeIdentifier(documentId)) return
        File(root, documentId).deleteRecursivelySafely(root)
    }

    /**
     * Atomically removes a document directory from its live location before Room is changed.
     * The quarantine directory is also the durable pending-deletion record: recovery can either
     * restore it when the database row still exists or retry physical deletion after the row is
     * gone.
     */
    open fun quarantineDocument(documentId: String): DocumentDeletionPreparation {
        if (!isSafeIdentifier(documentId)) return DocumentDeletionPreparation.StateUncertain
        return try {
            val canonicalRoot = root.canonicalFile
            val source = File(canonicalRoot, documentId)
            val pendingRoot = File(canonicalRoot, PENDING_DELETIONS_DIRECTORY)
            if (pendingRoot.canonicalFile.parentFile != canonicalRoot) {
                return DocumentDeletionPreparation.StateUncertain
            }
            val pendingDirectory = File(pendingRoot, documentId)
            val sourceState = source.pathState()
            val pendingState = pendingDirectory.pathState()
            if (sourceState == FilePathState.UNKNOWN || pendingState == FilePathState.UNKNOWN) {
                return DocumentDeletionPreparation.StateUncertain
            }
            if (pendingState == FilePathState.PRESENT) {
                val pending = PendingDocumentDeletion(documentId, pendingDirectory)
                if (!isValidPendingDeletion(pending) ||
                    !Files.isDirectory(pendingDirectory.toPath(), LinkOption.NOFOLLOW_LINKS) ||
                    sourceState == FilePathState.PRESENT
                ) {
                    return DocumentDeletionPreparation.StateUncertain
                }
                return DocumentDeletionPreparation.AlreadyQuarantined(pending)
            }
            if (sourceState == FilePathState.ABSENT) {
                return DocumentDeletionPreparation.NoPrivateFiles
            }
            if (source.canonicalFile.parentFile != canonicalRoot ||
                !Files.isDirectory(source.toPath(), LinkOption.NOFOLLOW_LINKS)
            ) {
                return DocumentDeletionPreparation.StateUncertain
            }
            if ((!pendingRoot.isDirectory && !pendingRoot.mkdirs()) ||
                !Files.isDirectory(pendingRoot.toPath(), LinkOption.NOFOLLOW_LINKS)
            ) {
                return DocumentDeletionPreparation.QuarantineFailed
            }
            // Both paths are direct children of the same app-private filesystem root. Android's
            // File.renameTo delegates to a same-filesystem rename, so success is atomic.
            if (!source.renameTo(pendingDirectory)) {
                return DocumentDeletionPreparation.QuarantineFailed
            }
            DocumentDeletionPreparation.Quarantined(
                PendingDocumentDeletion(documentId, pendingDirectory),
            )
        } catch (_: IOException) {
            DocumentDeletionPreparation.StateUncertain
        } catch (_: SecurityException) {
            DocumentDeletionPreparation.StateUncertain
        }
    }

    open fun restoreQuarantinedDocument(pending: PendingDocumentDeletion): Boolean {
        if (!isValidPendingDeletion(pending)) return false
        return try {
            val destination = File(root.canonicalFile, pending.documentId)
            if (Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS)) return false
            if (!pending.directory.renameTo(destination)) return false
            Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                Files.notExists(pending.directory.toPath(), LinkOption.NOFOLLOW_LINKS)
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    open fun completeDocumentDeletion(pending: PendingDocumentDeletion): Boolean {
        if (!isValidPendingDeletion(pending)) return false
        return pending.directory.deleteRecursivelyChecked(root)
    }

    open fun pendingDocumentDeletions(): DocumentDirectoryScan<PendingDocumentDeletion> {
        return try {
            val canonicalRoot = root.canonicalFile
            val pendingRoot = File(canonicalRoot, PENDING_DELETIONS_DIRECTORY)
            if (pendingRoot.canonicalFile.parentFile != canonicalRoot) {
                return DocumentDirectoryScan.Unavailable
            }
            when (pendingRoot.pathState()) {
                FilePathState.ABSENT -> return DocumentDirectoryScan.Available(emptyList())
                FilePathState.UNKNOWN -> return DocumentDirectoryScan.Unavailable
                FilePathState.PRESENT -> Unit
            }
            if (!Files.isDirectory(pendingRoot.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                return DocumentDirectoryScan.Unavailable
            }
            val children = pendingRoot.listFiles() ?: return DocumentDirectoryScan.Unavailable
            DocumentDirectoryScan.Available(
                children
                .filter { candidate ->
                    isSafeIdentifier(candidate.name) &&
                        Files.isDirectory(candidate.toPath(), LinkOption.NOFOLLOW_LINKS)
                }
                .map { candidate -> PendingDocumentDeletion(candidate.name, candidate) },
            )
        } catch (_: IOException) {
            DocumentDirectoryScan.Unavailable
        } catch (_: SecurityException) {
            DocumentDirectoryScan.Unavailable
        }
    }

    /**
     * Returns only direct children with the established document/revisions shape. Ownership is
     * deliberately decided by the repository using Room and durable operation journals.
     */
    open fun legacyDocumentDirectoryCandidates(): DocumentDirectoryScan<String> {
        return try {
            val canonicalRoot = root.canonicalFile
            when (canonicalRoot.pathState()) {
                FilePathState.ABSENT -> return DocumentDirectoryScan.Available(emptyList())
                FilePathState.UNKNOWN -> return DocumentDirectoryScan.Unavailable
                FilePathState.PRESENT -> Unit
            }
            if (!Files.isDirectory(canonicalRoot.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                return DocumentDirectoryScan.Unavailable
            }
            val children = canonicalRoot.listFiles() ?: return DocumentDirectoryScan.Unavailable
            DocumentDirectoryScan.Available(
                children.filter { candidate ->
                    isSafeIdentifier(candidate.name) &&
                        Files.isDirectory(candidate.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                        Files.isDirectory(
                            File(candidate, REVISIONS_DIRECTORY).toPath(),
                            LinkOption.NOFOLLOW_LINKS,
                        )
                }.map(File::getName),
            )
        } catch (_: IOException) {
            DocumentDirectoryScan.Unavailable
        } catch (_: SecurityException) {
            DocumentDirectoryScan.Unavailable
        }
    }

    private fun isValidPendingDeletion(pending: PendingDocumentDeletion): Boolean {
        if (!isSafeIdentifier(pending.documentId)) return false
        return try {
            val canonicalRoot = root.canonicalFile
            val pendingRoot = File(canonicalRoot, PENDING_DELETIONS_DIRECTORY).canonicalFile
            pending.directory.canonicalFile.parentFile == pendingRoot &&
                pending.directory.name == pending.documentId
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private fun relativePath(file: File): String =
        root.canonicalFile.toPath().relativize(file.canonicalFile.toPath()).toString()

    private suspend fun copyBounded(
        openSource: () -> InputStream,
        destination: File,
        maxBytes: Long,
    ): CopyOutcome = try {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        openSource().use { source ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(COPY_BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = source.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > maxBytes) return CopyOutcome.SourceTooLarge
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                }
                output.flush()
            }
        }
        if (destination.isFile && destination.length() == total && total > 0L) {
            CopyOutcome.Success(total, digest.digest().toHexString())
        } else {
            CopyOutcome.Failed
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: IOException) {
        CopyOutcome.Failed
    } catch (_: SecurityException) {
        CopyOutcome.Failed
    } catch (_: IllegalArgumentException) {
        CopyOutcome.Failed
    }

    /** Creates the same bounded library thumbnail while a restore revision is still invisible. */
    fun createRestoreThumbnail(
        source: File,
        destination: File,
        rotationDegrees: Int,
        filterName: String,
    ): Boolean = createThumbnail(
        source = source,
        destination = destination,
        rotation = DocumentPageRotation.entries.single { it.degrees == rotationDegrees },
        filter = DocumentFilter.valueOf(filterName),
    )

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

    private sealed interface CopyOutcome {
        data class Success(val byteCount: Long, val sha256: String) : CopyOutcome
        data object SourceTooLarge : CopyOutcome
        data object Failed : CopyOutcome
    }

    companion object {
        const val LIBRARY_DIRECTORY = "document-library"
        private const val REVISIONS_DIRECTORY = "revisions"
        private const val PENDING_DELETIONS_DIRECTORY = ".pending-deletions"
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

private fun sourceAssetExtension(contentType: String): String = when (contentType.lowercase()) {
    "application/pdf" -> "pdf"
    else -> "bin"
}

private fun ByteArray.toHexString(): String = joinToString(separator = "") { byte ->
    "%02x".format(byte.toInt() and 0xff)
}

private fun isSafeIdentifier(value: String): Boolean =
    value.isNotBlank() && value.length <= 80 && value.all { it.isLetterOrDigit() || it == '-' }

private fun File.deleteRecursivelySafely(root: File) {
    deleteRecursivelyChecked(root)
}

private fun File.deleteRecursivelyChecked(root: File): Boolean = try {
    val canonicalRoot = root.canonicalFile
    val target = canonicalFile
    if (target == canonicalRoot || !target.toPath().startsWith(canonicalRoot.toPath())) {
        false
    } else {
        Files.walkFileTree(
            target.toPath(),
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(directory: Path, failure: IOException?): FileVisitResult {
                    if (failure != null) throw failure
                    Files.deleteIfExists(directory)
                    return FileVisitResult.CONTINUE
                }
            },
        )
        Files.notExists(target.toPath(), LinkOption.NOFOLLOW_LINKS)
    }
} catch (_: IOException) {
    false
} catch (_: SecurityException) {
    false
}

private enum class FilePathState {
    PRESENT,
    ABSENT,
    UNKNOWN,
}

private fun File.pathState(): FilePathState = when {
    Files.exists(toPath(), LinkOption.NOFOLLOW_LINKS) -> FilePathState.PRESENT
    Files.notExists(toPath(), LinkOption.NOFOLLOW_LINKS) -> FilePathState.ABSENT
    else -> FilePathState.UNKNOWN
}
