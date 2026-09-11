package org.synapseworks.pageharbor.document

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

private const val PdfCopyBufferSize = 8 * 1024

sealed interface PdfExportResult {
    data object Success : PdfExportResult
    data object SourceMissing : PdfExportResult
    data object SourceTooLarge : PdfExportResult
    data object DestinationUnavailable : PdfExportResult
    data object WriteFailed : PdfExportResult
}

fun copyPdfToDestination(
    source: InputStream?,
    destination: OutputStream?,
): PdfExportResult {
    if (source == null) {
        destination?.closeSafely()
        return PdfExportResult.SourceMissing
    }
    if (destination == null) {
        source.closeSafely()
        return PdfExportResult.DestinationUnavailable
    }

    return try {
        source.use { input ->
            destination.use { output ->
                input.copyTo(output, bufferSize = PdfCopyBufferSize)
                output.flush()
            }
        }
        PdfExportResult.Success
    } catch (_: IOException) {
        PdfExportResult.WriteFailed
    } catch (_: SecurityException) {
        PdfExportResult.WriteFailed
    }
}

private fun InputStream.closeSafely() {
    try {
        close()
    } catch (_: IOException) {
        // Nothing user-actionable, and paths or document details must not be logged.
    }
}

private fun OutputStream.closeSafely() {
    try {
        close()
    } catch (_: IOException) {
        // Nothing user-actionable, and paths or document details must not be logged.
    }
}
