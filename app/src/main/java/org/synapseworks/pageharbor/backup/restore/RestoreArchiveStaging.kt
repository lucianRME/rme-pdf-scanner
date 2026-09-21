package org.synapseworks.pageharbor.backup.restore

import android.content.Context
import java.io.File
import java.io.IOException

internal interface RestoreArchiveWorkspace {
    fun create(): File

    fun delete(file: File): Boolean
}

internal class FileRestoreArchiveWorkspace(
    private val root: File,
) : RestoreArchiveWorkspace {
    override fun create(): File {
        if ((!root.exists() && !root.mkdirs()) || !root.isDirectory) {
            throw IOException("The private restore archive workspace is unavailable.")
        }
        makeOwnerOnly(root)
        return File.createTempFile(".rme-restore-", ".zip", root).also(::makeOwnerOnly)
    }

    override fun delete(file: File): Boolean {
        val safe = try {
            val canonicalRoot = root.canonicalFile.toPath()
            val canonicalFile = file.canonicalFile.toPath()
            canonicalFile.parent == canonicalRoot
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
        return safe && (!file.exists() || file.delete())
    }
}

internal class AndroidRestoreArchiveWorkspace(context: Context) : RestoreArchiveWorkspace by
    FileRestoreArchiveWorkspace(
        File(context.applicationContext.noBackupFilesDir, PRIVATE_ARCHIVE_DIRECTORY),
    ) {
    private companion object {
        const val PRIVATE_ARCHIVE_DIRECTORY = "restore-archive-staging"
    }
}

private fun makeOwnerOnly(file: File) {
    file.setReadable(false, false)
    file.setWritable(false, false)
    file.setExecutable(false, false)
    file.setReadable(true, true)
    file.setWritable(true, true)
    if (file.isDirectory) file.setExecutable(true, true)
}
