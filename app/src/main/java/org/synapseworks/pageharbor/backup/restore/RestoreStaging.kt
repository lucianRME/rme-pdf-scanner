package org.synapseworks.pageharbor.backup.restore

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import org.synapseworks.pageharbor.backup.format.BackupPathValidator
import org.synapseworks.pageharbor.backup.format.BackupStagingSink

internal interface RestoreStagedAssetSource {
    fun openAsset(relativePath: String): InputStream
}

internal interface RestoreStagingArea : BackupStagingSink, RestoreStagedAssetSource {
    val operationId: String

    fun discard(): Boolean
}

internal interface RestoreStagingWorkspace {
    fun availableBytes(): Long?

    fun create(operationId: String): RestoreStagingArea

    fun discard(operationId: String): Boolean
}

internal class FileRestoreStagingWorkspace(
    private val root: File,
) : RestoreStagingWorkspace {
    override fun availableBytes(): Long? {
        val storageRoot = when {
            root.exists() -> root
            root.parentFile != null -> root.parentFile
            else -> return null
        }
        return storageRoot.usableSpace.takeIf { it > 0L }
    }

    override fun create(operationId: String): RestoreStagingArea {
        require(SAFE_OPERATION_ID.matches(operationId)) { "Invalid restore operation ID" }
        if ((!root.exists() && !root.mkdirs()) || !root.isDirectory) {
            throw IOException("The private restore workspace is unavailable.")
        }
        val operationRoot = File(root, operationId)
        if (operationRoot.exists() || !operationRoot.mkdirs()) {
            throw IOException("The private restore staging area is unavailable.")
        }
        makeOwnerOnly(operationRoot)
        return FileRestoreStagingArea(operationId, root, operationRoot)
    }

    override fun discard(operationId: String): Boolean {
        if (!SAFE_OPERATION_ID.matches(operationId)) return false
        return deleteTreeSafely(root, File(root, operationId))
    }

    private companion object {
        val SAFE_OPERATION_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}

internal class AndroidRestoreStagingWorkspace(context: Context) : RestoreStagingWorkspace by
    FileRestoreStagingWorkspace(
        File(context.applicationContext.noBackupFilesDir, PRIVATE_RESTORE_DIRECTORY),
    ) {
    private companion object {
        const val PRIVATE_RESTORE_DIRECTORY = "verified-restore-staging"
    }
}

private class FileRestoreStagingArea(
    override val operationId: String,
    private val workspaceRoot: File,
    private val operationRoot: File,
) : RestoreStagingArea {
    private val verified = AtomicBoolean(false)
    private val discarded = AtomicBoolean(false)

    override fun open(relativePath: String): OutputStream {
        check(!discarded.get()) { "Restore staging was discarded" }
        check(!verified.get()) { "Verified restore staging is immutable" }
        BackupPathValidator.requireValidArchivePath(relativePath)
        check(BackupPathValidator.isAssetPath(relativePath)) { "Only backup assets may be staged" }
        val destination = resolve(relativePath)
        val parent = destination.parentFile ?: throw IOException("Invalid staging destination")
        if ((!parent.exists() && !parent.mkdirs()) || !parent.isDirectory) {
            throw IOException("Unable to create a private restore staging directory")
        }
        makeOwnerOnly(parent)
        if (destination.exists()) throw IOException("A staged asset already exists")
        return FileOutputStream(destination).also { makeOwnerOnly(destination) }
    }

    override fun openAsset(relativePath: String): InputStream {
        check(verified.get() && !discarded.get()) { "Restore staging is not verified" }
        BackupPathValidator.requireValidArchivePath(relativePath)
        check(BackupPathValidator.isAssetPath(relativePath)) { "Only backup assets may be opened" }
        val source = resolve(relativePath)
        if (!source.isFile) throw IOException("A verified staged asset is unavailable")
        return FileInputStream(source)
    }

    override fun verified() {
        check(!discarded.get())
        verified.set(true)
    }

    override fun abort() {
        discard()
    }

    override fun discard(): Boolean {
        if (discarded.get()) return true
        val deleted = deleteTreeSafely(workspaceRoot, operationRoot)
        if (deleted) discarded.set(true)
        return deleted
    }

    private fun resolve(relativePath: String): File {
        val canonicalRoot = operationRoot.canonicalFile
        val candidate = File(canonicalRoot, relativePath).canonicalFile
        if (candidate == canonicalRoot || !candidate.toPath().startsWith(canonicalRoot.toPath())) {
            throw IOException("Unsafe restore staging path")
        }
        return candidate
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

private fun deleteTreeSafely(root: File, target: File): Boolean = try {
    val canonicalRoot = root.canonicalFile
    val canonicalTarget = target.canonicalFile
    if (canonicalTarget == canonicalRoot || !canonicalTarget.toPath().startsWith(canonicalRoot.toPath())) {
        false
    } else {
        !canonicalTarget.exists() || canonicalTarget.deleteRecursively()
    }
} catch (_: IOException) {
    false
} catch (_: SecurityException) {
    false
}
