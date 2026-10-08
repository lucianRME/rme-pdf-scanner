package org.synapseworks.pageharbor.document.cardcopy

import android.content.Context
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import java.io.InputStream
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal sealed interface CardCopyDestinationResult {
    data class Success(val bytes: Long) : CardCopyDestinationResult
    data class Failure(val reason: CardCopyFailure) : CardCopyDestinationResult
}

internal class CardCopyDestinationWriter(private val context: Context) {
    suspend fun write(
        prepared: CardCopyPreparedPdf,
        destination: Uri,
    ): CardCopyDestinationResult = withContext(Dispatchers.IO) {
        if (!prepared.file.isFile || prepared.file.length() != prepared.bytes) {
            return@withContext CardCopyDestinationResult.Failure(CardCopyFailure.VALIDATION_FAILED)
        }
        val output = try {
            context.contentResolver.openOutputStream(destination, "wt")
        } catch (_: Exception) {
            null
        } ?: return@withContext CardCopyDestinationResult.Failure(
            CardCopyFailure.DESTINATION_UNAVAILABLE,
        )
        try {
            prepared.file.inputStream().use { input ->
                output.use { target ->
                    val buffer = ByteArray(32 * 1_024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        target.write(buffer, 0, count)
                    }
                    target.flush()
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return@withContext CardCopyDestinationResult.Failure(CardCopyFailure.WRITE_FAILED)
        }
        if (!verify(prepared, destination)) {
            return@withContext CardCopyDestinationResult.Failure(CardCopyFailure.VALIDATION_FAILED)
        }
        CardCopyDestinationResult.Success(prepared.bytes)
    }

    private suspend fun verify(prepared: CardCopyPreparedPdf, destination: Uri): Boolean {
        val sourceDigest = digest(prepared.file.inputStream()) ?: return false
        val destinationDigest = try {
            context.contentResolver.openInputStream(destination)?.let { digest(it) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        } ?: return false
        if (sourceDigest.first != destinationDigest.first ||
            !sourceDigest.second.contentEquals(destinationDigest.second)
        ) return false
        val descriptor = try {
            context.contentResolver.openFileDescriptor(destination, "r")
        } catch (_: Exception) {
            null
        } ?: return false
        return try {
            descriptor.use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    if (renderer.pageCount != 1) return false
                    renderer.openPage(0).use { page ->
                        val expected = prepared.geometry.pageSize
                        if (kotlin.math.abs(page.width - expected.widthPoints) > 2f ||
                            kotlin.math.abs(page.height - expected.heightPoints) > 2f
                        ) return false
                        val bitmap = createBitmap(96, 128)
                        try {
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun digest(input: InputStream): Pair<Long, ByteArray>? = try {
        input.use { source ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(32 * 1_024)
            var bytes = 0L
            while (true) {
                coroutineContext.ensureActive()
                val count = source.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
                bytes += count
            }
            bytes to digest.digest()
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }
}

fun cardCopyShareUri(context: Context, prepared: CardCopyPreparedPdf): Uri? = try {
    if (!prepared.file.isFile || prepared.file.length() != prepared.bytes) null else {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", prepared.file)
    }
} catch (_: RuntimeException) {
    null
}
