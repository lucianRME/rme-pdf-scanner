package org.synapseworks.pageharbor.document.targetsize

import android.content.Context
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

enum class TargetSizeDestinationError {
    PREPARED_RESULT_UNAVAILABLE,
    DESTINATION_UNAVAILABLE,
    WRITE_FAILED,
    VALIDATION_FAILED,
}

sealed interface TargetSizeDestinationResult {
    data class Success(val bytes: Long) : TargetSizeDestinationResult
    data class Failure(val reason: TargetSizeDestinationError) : TargetSizeDestinationResult
}

/** Streams one validated private result to SAF, then reads and renders the destination itself. */
internal class TargetSizeDestinationWriter(
    context: Context,
    private val openOutput: (Uri) -> OutputStream? = { uri ->
        context.contentResolver.openOutputStream(uri, "wt")
    },
    private val openInput: (Uri) -> InputStream? = context.contentResolver::openInputStream,
    private val openDescriptor: (Uri) -> ParcelFileDescriptor? = { uri ->
        context.contentResolver.openFileDescriptor(uri, "r")
    },
) {
    suspend fun write(
        prepared: TargetSizePreparedPdf,
        destination: Uri,
    ): TargetSizeDestinationResult = withContext(Dispatchers.IO) {
        if (!prepared.file.isFile || prepared.file.length() != prepared.finalBytes) {
            return@withContext TargetSizeDestinationResult.Failure(
                TargetSizeDestinationError.PREPARED_RESULT_UNAVAILABLE,
            )
        }
        val output = try {
            openOutput(destination)
        } catch (_: FileNotFoundException) {
            null
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } ?: return@withContext TargetSizeDestinationResult.Failure(
            TargetSizeDestinationError.DESTINATION_UNAVAILABLE,
        )

        try {
            prepared.file.inputStream().use { input ->
                output.use { destinationOutput ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        destinationOutput.write(buffer, 0, count)
                    }
                    destinationOutput.flush()
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: IOException) {
            return@withContext TargetSizeDestinationResult.Failure(
                TargetSizeDestinationError.WRITE_FAILED,
            )
        } catch (_: SecurityException) {
            return@withContext TargetSizeDestinationResult.Failure(
                TargetSizeDestinationError.WRITE_FAILED,
            )
        }

        if (!destinationMatchesPrepared(prepared, destination)) {
            return@withContext TargetSizeDestinationResult.Failure(
                TargetSizeDestinationError.VALIDATION_FAILED,
            )
        }
        TargetSizeDestinationResult.Success(prepared.finalBytes)
    }

    private suspend fun destinationMatchesPrepared(
        prepared: TargetSizePreparedPdf,
        destination: Uri,
    ): Boolean {
        val sourceDigest = digest(prepared.file.inputStream()) ?: return false
        val destinationInput = try {
            openInput(destination)
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } ?: return false
        val destinationDigest = digest(destinationInput) ?: return false
        if (sourceDigest.bytes != destinationDigest.bytes ||
            !sourceDigest.sha256.contentEquals(destinationDigest.sha256)
        ) {
            return false
        }
        val descriptor = try {
            openDescriptor(destination)
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } ?: return false
        return try {
            descriptor.use { fileDescriptor ->
                PdfRenderer(fileDescriptor).use { renderer ->
                    if (renderer.pageCount != prepared.pageCount) return false
                    repeat(renderer.pageCount) { index ->
                        coroutineContext.ensureActive()
                        renderer.openPage(index).use { page ->
                            val width = minOf(DESTINATION_VALIDATION_EDGE, page.width).coerceAtLeast(1)
                            val height = minOf(DESTINATION_VALIDATION_EDGE, page.height).coerceAtLeast(1)
                            val bitmap = createBitmap(width, height)
                            try {
                                bitmap.eraseColor(Color.WHITE)
                                page.render(
                                    bitmap,
                                    null,
                                    null,
                                    PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                                )
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
        } catch (_: RuntimeException) {
            false
        } catch (_: OutOfMemoryError) {
            false
        }
    }

    private suspend fun digest(input: InputStream): StreamDigest? = try {
        input.use { source ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            var bytes = 0L
            while (true) {
                coroutineContext.ensureActive()
                val count = source.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
                bytes += count
            }
            StreamDigest(bytes, digest.digest())
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private data class StreamDigest(val bytes: Long, val sha256: ByteArray)

    private companion object {
        const val COPY_BUFFER_BYTES = 32 * 1_024
        const val DESTINATION_VALIDATION_EDGE = 48
    }
}

fun targetSizeShareUri(context: Context, prepared: TargetSizePreparedPdf): Uri? {
    if (!prepared.file.isFile || prepared.file.length() != prepared.finalBytes) return null
    return try {
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            prepared.file,
        )
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: SecurityException) {
        null
    }
}
