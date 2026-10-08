package org.synapseworks.pageharbor.document.cardcopy

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.synapseworks.pageharbor.document.exifOrientationSwapsAxes
import org.synapseworks.pageharbor.document.readImageBounds
import org.synapseworks.pageharbor.document.readOrientation
import org.synapseworks.pageharbor.document.session.DEFAULT_MAX_IMAGE_SOURCE_BYTES
import org.synapseworks.pageharbor.document.session.DocumentImageMetadata

internal sealed interface CardCopyStageResult {
    data class Success(val source: CardCopySource) : CardCopyStageResult
    data class Failure(val reason: CardCopyFailure) : CardCopyStageResult
}

/** Copies one user-initiated image into a bounded private workflow reference. */
internal class CardCopySourceStager(private val context: Context) {
    suspend fun stage(
        uri: Uri,
        side: CardCopySide,
        acquisition: CardCopyAcquisition,
    ): CardCopyStageResult = withContext(Dispatchers.IO) {
        val root = File(context.cacheDir, CARD_COPY_SOURCE_DIRECTORY).apply { mkdirs() }
        val directory = try {
            File.createTempFile("card-source-", ".work", root).also { marker ->
                if (!marker.delete() || !marker.mkdir()) return@withContext failure(null, CardCopyFailure.PREPARATION_FAILED)
            }
        } catch (_: IOException) {
            return@withContext CardCopyStageResult.Failure(CardCopyFailure.PREPARATION_FAILED)
        }
        val file = File(directory, "source-image")
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: return@withContext failure(directory, CardCopyFailure.INVALID_SOURCE)
            var bytes = 0L
            input.use { source ->
                FileOutputStream(file).use { output ->
                    val buffer = ByteArray(32 * 1_024)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        bytes += count
                        if (bytes > DEFAULT_MAX_IMAGE_SOURCE_BYTES) {
                            return@withContext failure(directory, CardCopyFailure.SOURCE_TOO_LARGE)
                        }
                        output.write(buffer, 0, count)
                    }
                    output.flush()
                    output.fd.sync()
                }
            }
            val dimensions = readImageBounds { file.inputStream() }
                ?: return@withContext failure(directory, CardCopyFailure.INVALID_SOURCE)
            if (dimensions.first <= 0 || dimensions.second <= 0 ||
                dimensions.first > 6_000 || dimensions.second > 6_000 ||
                dimensions.first.toLong() * dimensions.second > 12_500_000L
            ) {
                return@withContext failure(directory, CardCopyFailure.SOURCE_TOO_LARGE)
            }
            val orientation = readOrientation { file.inputStream() }
            val displayDimensions = if (exifOrientationSwapsAxes(orientation)) {
                dimensions.second to dimensions.first
            } else {
                dimensions
            }
            CardCopyStageResult.Success(
                CardCopySource(
                    side = side,
                    acquisition = acquisition,
                    file = file,
                    ownedDirectory = directory,
                    metadata = DocumentImageMetadata(
                        bytes,
                        displayDimensions.first,
                        displayDimensions.second,
                    ),
                ),
            )
        } catch (_: IOException) {
            failure(directory, CardCopyFailure.INVALID_SOURCE)
        } catch (_: SecurityException) {
            failure(directory, CardCopyFailure.INVALID_SOURCE)
        } catch (_: IllegalArgumentException) {
            failure(directory, CardCopyFailure.INVALID_SOURCE)
        }
    }

    private fun failure(directory: File?, reason: CardCopyFailure): CardCopyStageResult.Failure {
        directory?.deleteRecursively()
        return CardCopyStageResult.Failure(reason)
    }
}

internal const val CARD_COPY_SOURCE_DIRECTORY = "card-copy-sources"
internal const val CARD_COPY_OUTPUT_DIRECTORY = "card-copy-pdfs"
internal const val CARD_COPY_STALE_AGE_MILLIS = 24L * 60L * 60L * 1_000L

internal fun discardCardCopySource(source: CardCopySource) {
    source.ownedDirectory.deleteRecursively()
}

fun deleteStaleCardCopyFiles(cacheDirectory: File, nowMillis: Long = System.currentTimeMillis()) {
    listOf(CARD_COPY_SOURCE_DIRECTORY, CARD_COPY_OUTPUT_DIRECTORY).forEach { rootName ->
        File(cacheDirectory, rootName).listFiles()?.forEach { directory ->
            if (directory.isDirectory && nowMillis - directory.lastModified() >= CARD_COPY_STALE_AGE_MILLIS) {
                directory.deleteRecursively()
            }
        }
    }
}
