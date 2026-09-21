package org.synapseworks.pageharbor.backup.publish

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.InputStream
import java.io.OutputStream

class AndroidSafBackupDestination(
    private val contentResolver: ContentResolver,
    private val destinationUri: Uri,
) : BackupPublicationDestination {
    override fun openOutput(): OutputStream? = try {
        contentResolver.openOutputStream(destinationUri, "w")
    } catch (_: Exception) {
        null
    }

    override fun openInput(): InputStream? = try {
        contentResolver.openInputStream(destinationUri)
    } catch (_: Exception) {
        null
    }

    override fun delete(): Boolean = try {
        DocumentsContract.deleteDocument(contentResolver, destinationUri)
    } catch (_: Exception) {
        false
    }
}
