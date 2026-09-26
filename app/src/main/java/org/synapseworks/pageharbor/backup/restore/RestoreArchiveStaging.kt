package org.synapseworks.pageharbor.backup.restore

import android.content.Context
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID

internal interface RestoreArchiveWorkspace {
    fun availableBytes(): Long? = null

    fun create(): File

    fun delete(file: File): Boolean

    /** Removes only private archive copies created by this workspace. */
    fun discardOrphans(): Boolean = true
}

internal class FileRestoreArchiveWorkspace(
    private val root: File,
) : RestoreArchiveWorkspace {
    override fun availableBytes(): Long? {
        val storageRoot = when {
            root.exists() -> root
            root.parentFile != null -> root.parentFile
            else -> return null
        }
        return storageRoot.usableSpace
    }

    override fun create(): File {
        if ((!root.exists() && !root.mkdirs()) || !root.isDirectory) {
            throw IOException("The private restore archive workspace is unavailable.")
        }
        makeOwnerOnly(root)
        val file = File(root, "$ARCHIVE_PREFIX${UUID.randomUUID()}$ARCHIVE_SUFFIX")
        if (!file.createNewFile()) throw IOException("The private restore archive could not be created.")
        return file.also(::makeOwnerOnly)
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

    override fun discardOrphans(): Boolean {
        if (!root.exists()) return true
        if (!root.isDirectory) return false
        var cleaned = true
        return try {
            Files.newDirectoryStream(root.toPath()).use { entries ->
                entries.forEach { entry ->
                    val name = entry.fileName.toString()
                    if (MANAGED_ARCHIVE_NAME.matches(name)) {
                        cleaned = delete(entry.toFile()) && cleaned
                    }
                }
            }
            cleaned
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private companion object {
        const val ARCHIVE_PREFIX = ".rme-restore-"
        const val ARCHIVE_SUFFIX = ".zip"
        val MANAGED_ARCHIVE_NAME = Regex(
            "\\.rme-restore-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.zip",
        )
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
