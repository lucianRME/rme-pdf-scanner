package org.synapseworks.pageharbor.workflow

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.io.FileNotFoundException

/** Private no-backup source exclusions, never PDF metadata or diagnostics. */
internal data class WorkflowSourceGuard(val uri:String,val device:Long,val inode:Long) {
    init { require(uri.length in 1..4096 && Uri.parse(uri).scheme in setOf("file","content") && inode>0) }
    override fun toString()="WorkflowSourceGuard(<redacted>)"
}
internal fun workflowSourceGuard(context:Context,uri:Uri):WorkflowSourceGuard =
    requireNotNull(context.contentResolver.openFileDescriptor(uri,"r")).use { fd ->
        val stat=Os.fstat(fd.fileDescriptor);require(OsConstants.S_ISREG(stat.st_mode) && stat.st_size>0 && stat.st_ino>0)
        WorkflowSourceGuard(uri.toString(),stat.st_dev,stat.st_ino)
    }

/** Test the actual non-truncating destination descriptor, not only provider URI spelling.
 * Reopen surviving source URIs to exclude replacement inodes as well. Missing originals do not
 * invalidate an independent frozen job; retained captured inode exclusions still apply.
 */
internal fun workflowDifferentSources(context:Context,fd:ParcelFileDescriptor,target:Uri,guards:List<WorkflowSourceGuard>):Boolean = runCatching {
    require(guards.isNotEmpty() && guards.size<=40)
    val destination=Os.fstat(fd.fileDescriptor);require(OsConstants.S_ISREG(destination.st_mode))
    guards.forEach { guard ->
        require(target.toString()!=guard.uri && !(destination.st_dev==guard.device && destination.st_ino==guard.inode))
        val source=Uri.parse(guard.uri)
        val current=try { requireNotNull(context.contentResolver.openFileDescriptor(source,"r")) }
        catch(_:FileNotFoundException) {
            // Purged app-owned originals are expected. An unverifiable external source is not.
            val ownedProvider=source.scheme=="content" && source.authority=="${context.packageName}.fileprovider"
            val path=if(source.scheme=="file")java.io.File(requireNotNull(source.path)).canonicalFile else null
            val ownedFile=path!=null && listOf(context.filesDir,context.cacheDir,context.noBackupFilesDir).any { path.toPath().startsWith(it.canonicalFile.toPath()) } && !path.exists()
            require(ownedProvider || ownedFile);null
        }
        current?.use { original -> val stat=Os.fstat(original.fileDescriptor);require(destination.st_dev!=stat.st_dev || destination.st_ino!=stat.st_ino) }
    }
    true
}.getOrDefault(false)
