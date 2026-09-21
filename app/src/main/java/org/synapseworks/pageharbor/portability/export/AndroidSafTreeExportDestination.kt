package org.synapseworks.pageharbor.portability.export

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.OutputStream

/** Writes ordinary export folders/files only through the user-selected Storage Access Framework tree. */
class AndroidSafTreeExportDestination(
    private val contentResolver: ContentResolver,
    treeUri: Uri,
) : PortableExportDestination {
    private val rootDocumentUri: Uri = DocumentsContract.buildDocumentUriUsingTree(
        treeUri,
        DocumentsContract.getTreeDocumentId(treeUri),
    )

    override fun createDirectory(parentHandle: String?, displayName: String): String? = try {
        DocumentsContract.createDocument(
            contentResolver,
            parentHandle?.let(Uri::parse) ?: rootDocumentUri,
            DocumentsContract.Document.MIME_TYPE_DIR,
            displayName,
        )?.toString()
    } catch (_: Exception) {
        null
    }

    override fun createFile(
        parentHandle: String,
        displayName: String,
        mimeType: String,
    ): String? = try {
        DocumentsContract.createDocument(
            contentResolver,
            Uri.parse(parentHandle),
            mimeType,
            displayName,
        )?.toString()
    } catch (_: Exception) {
        null
    }

    override fun openOutput(fileHandle: String): OutputStream? = try {
        contentResolver.openOutputStream(Uri.parse(fileHandle), "w")
    } catch (_: Exception) {
        null
    }

    override fun delete(handle: String) {
        runCatching { DocumentsContract.deleteDocument(contentResolver, Uri.parse(handle)) }
    }
}
