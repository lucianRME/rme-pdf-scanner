package org.synapseworks.pageharbor.document

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import org.synapseworks.pageharbor.document.session.DEFAULT_DOCUMENT_INPUT_LIMITS
import org.synapseworks.pageharbor.document.session.DocumentImageMetadata
import org.synapseworks.pageharbor.document.session.DocumentInputLimits

private const val PageCopyBufferSize = 8 * 1024

sealed interface PageExportResult {
    data object Success : PageExportResult
    data object SourceMissing : PageExportResult
    data object SourceTooLarge : PageExportResult
    data object DestinationUnavailable : PageExportResult
    data object WriteFailed : PageExportResult
}

/** Carries a safe page failure through stream-based PDF generator boundaries. */
internal class PageExportFailureException(val result: PageExportResult) : IOException()

fun copyPageToDestination(
    source: InputStream?,
    destination: OutputStream?,
    imageMetadata: DocumentImageMetadata = DocumentImageMetadata(),
    limits: DocumentInputLimits = DEFAULT_DOCUMENT_INPUT_LIMITS,
): PageExportResult {
    pageSourcePreflight(imageMetadata, limits)?.let { failure ->
        source?.closeSafely()
        destination?.closeSafely()
        return failure
    }
    if (source == null) {
        destination?.closeSafely()
        return PageExportResult.SourceMissing
    }
    if (destination == null) {
        source.closeSafely()
        return PageExportResult.DestinationUnavailable
    }

    return try {
        source.use { input ->
            destination.use { output ->
                input.copyTo(output, bufferSize = PageCopyBufferSize)
                output.flush()
            }
        }
        PageExportResult.Success
    } catch (_: IOException) {
        PageExportResult.WriteFailed
    } catch (_: SecurityException) {
        PageExportResult.WriteFailed
    }
}

private fun InputStream.closeSafely() {
    try {
        close()
    } catch (_: IOException) {
        // Nothing user-actionable, and document details must not be logged.
    }
}

private fun OutputStream.closeSafely() {
    try {
        close()
    } catch (_: IOException) {
        // Nothing user-actionable, and document details must not be logged.
    }
}
