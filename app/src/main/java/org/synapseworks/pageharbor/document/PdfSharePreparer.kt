package org.synapseworks.pageharbor.document

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

private const val SharedPdfDirectory = "shared-pdfs"
private const val SharedPdfMaxAgeMillis = 24L * 60L * 60L * 1000L

sealed interface PdfSharePreparationResult {
    @ConsistentCopyVisibility
    data class Ready internal constructor(
        val uri: Uri,
        internal val temporaryFile: PreparedPdfShareFile? = null,
    ) : PdfSharePreparationResult
    data object SourceMissing : PdfSharePreparationResult
    data object SourceTooLarge : PdfSharePreparationResult
    data object Failed : PdfSharePreparationResult
}

/** Idempotent ownership for a private share copy that must survive only a successful hand-off. */
internal class PreparedPdfShareFile(
    private val file: File,
    private val deleteFile: (File) -> Boolean = File::delete,
) {
    private val released = AtomicBoolean(false)

    fun release(): Boolean = released.compareAndSet(false, true) && try {
        deleteFile(file)
    } catch (_: SecurityException) {
        false
    }
}

internal fun discardPreparedPdfShare(result: PdfSharePreparationResult.Ready) {
    result.temporaryFile?.release()
}

fun preparePdfForSharing(
    context: Context,
    sourceUri: Uri?,
): PdfSharePreparationResult {
    if (sourceUri == null) return PdfSharePreparationResult.SourceMissing
    if (sourceUri.scheme == ContentResolver.SCHEME_CONTENT) {
        return PdfSharePreparationResult.Ready(sourceUri)
    }

    val shareDirectory = File(context.cacheDir, SharedPdfDirectory)
    if (!shareDirectory.exists() && !shareDirectory.mkdirs()) {
        return PdfSharePreparationResult.Failed
    }

    val temporaryPdf = try {
        File.createTempFile("shared-", ".pdf", shareDirectory)
    } catch (_: IOException) {
        return PdfSharePreparationResult.Failed
    } catch (_: SecurityException) {
        return PdfSharePreparationResult.Failed
    }

    val source = try {
        context.contentResolver.openInputStream(sourceUri)
    } catch (_: FileNotFoundException) {
        temporaryPdf.deleteSafely()
        return PdfSharePreparationResult.SourceMissing
    } catch (_: SecurityException) {
        temporaryPdf.deleteSafely()
        return PdfSharePreparationResult.SourceMissing
    }
    if (source == null) {
        temporaryPdf.deleteSafely()
        return PdfSharePreparationResult.SourceMissing
    }

    val destination = try {
        FileOutputStream(temporaryPdf)
    } catch (_: FileNotFoundException) {
        source.closeSafely()
        temporaryPdf.deleteSafely()
        return PdfSharePreparationResult.Failed
    } catch (_: SecurityException) {
        source.closeSafely()
        temporaryPdf.deleteSafely()
        return PdfSharePreparationResult.Failed
    }

    val copyResult = copyPdfToDestination(source, destination)
    if (copyResult != PdfExportResult.Success) {
        temporaryPdf.deleteSafely()
        return when (copyResult) {
            PdfExportResult.SourceMissing -> PdfSharePreparationResult.SourceMissing
            PdfExportResult.SourceTooLarge -> PdfSharePreparationResult.SourceTooLarge
            PdfExportResult.DestinationUnavailable,
            PdfExportResult.WriteFailed,
            -> PdfSharePreparationResult.Failed
            PdfExportResult.Success -> error("Handled above")
        }
    }

    return try {
        PdfSharePreparationResult.Ready(
            uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                temporaryPdf,
            ),
            temporaryFile = PreparedPdfShareFile(temporaryPdf),
        )
    } catch (_: IllegalArgumentException) {
        temporaryPdf.deleteSafely()
        PdfSharePreparationResult.Failed
    } catch (_: SecurityException) {
        temporaryPdf.deleteSafely()
        PdfSharePreparationResult.Failed
    }
}

private fun java.io.InputStream.closeSafely() {
    try {
        close()
    } catch (_: IOException) {
        // Cleanup is best-effort and must not expose document details.
    }
}

fun deleteStaleSharedPdfs(
    cacheDirectory: File,
    nowMillis: Long = System.currentTimeMillis(),
) {
    val shareDirectory = File(cacheDirectory, SharedPdfDirectory)
    shareDirectory.listFiles()?.forEach { file ->
        if (nowMillis - file.lastModified() >= SharedPdfMaxAgeMillis) {
            file.deleteSafely()
        }
    }
}

private fun File.deleteSafely(): Boolean {
    return try {
        delete()
    } catch (_: SecurityException) {
        // Cache cleanup is best-effort and must not expose document details.
        false
    }
}
