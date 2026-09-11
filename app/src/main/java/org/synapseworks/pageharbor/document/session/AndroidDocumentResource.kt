package org.synapseworks.pageharbor.document.session

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns

/** The only conversion between the platform-neutral session reference and Android URI handling. */
fun DocumentResource.toAndroidUri(): Uri = Uri.parse(reference)

fun Uri.toExternalDocumentResource(): AcquiredResource = AcquiredResource(
    reference = toString(),
    ownership = DocumentResourceOwnership.USER_OR_EXTERNAL,
)

/** Reads only provider-declared size metadata; unknown length stays null and is not rejected. */
fun ContentResolver.readDocumentImageMetadata(uri: Uri): DocumentImageMetadata =
    DocumentImageMetadata(
        sourceByteCount = firstKnownSourceByteCount(
            queryOpenableSize(uri),
            queryDescriptorLength(uri),
        ),
    )

internal fun firstKnownSourceByteCount(vararg candidates: Long?): Long? =
    candidates.firstOrNull { candidate -> candidate != null && candidate >= 0L }

private fun ContentResolver.queryOpenableSize(uri: Uri): Long? = try {
    query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        val column = cursor.getColumnIndex(OpenableColumns.SIZE)
        if (column >= 0 && cursor.moveToFirst() && !cursor.isNull(column)) {
            cursor.getLong(column)
        } else {
            null
        }
    }
} catch (_: RuntimeException) {
    null
}

private fun ContentResolver.queryDescriptorLength(uri: Uri): Long? = try {
    openAssetFileDescriptor(uri, "r")?.use { descriptor -> descriptor.length }
} catch (_: Exception) {
    null
}
