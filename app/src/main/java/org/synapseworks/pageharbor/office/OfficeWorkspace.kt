package org.synapseworks.pageharbor.office

import android.content.Context
import android.os.*
import android.provider.Settings
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import kotlinx.coroutines.*
import java.io.*
import java.util.UUID

private const val PREVIEW_MAGIC = 0x35355031
/** One parent-process owner for native work, previews and cleanup across both
 * the standalone Office activity and its reviewed workflow handoff. */
internal val officeProcessingMutex = kotlinx.coroutines.sync.Mutex()
private fun previewRoot(context: Context) = File(context.noBackupFilesDir, "office-preview")
private fun boot(context: Context) = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }.getOrDefault(-1)

internal fun officePreviewLease(context: Context): File {
    val root = previewRoot(context).apply { require(mkdirs() || isDirectory) }
    if (root.listFiles().orEmpty().size >= 4 || root.usableSpace < 64L * 1024 * 1024) throw OfficeFailure("PREVIEW_STORAGE_LIMIT")
    return File(root, "lease-${UUID.randomUUID()}").apply { require(mkdir()) }
}
internal fun recordOfficePreview(context: Context, lease: File, pid: Int, uid: Int) {
    require(pid > 1 && pid != Process.myPid() && uid % 100000 >= 90000)
    val file = AtomicFile(File(lease, "worker")); val out = file.startWrite()
    try {
        val data = DataOutputStream(out)
        data.writeInt(PREVIEW_MAGIC); data.writeInt(pid); data.writeInt(uid); data.writeInt(boot(context)); data.flush()
        file.finishWrite(out)
    } catch (failure: Throwable) { file.failWrite(out); throw failure }
}
internal fun previewGone(context: Context, lease: File): Boolean {
    val marker = File(lease, "worker")
    if (!marker.exists() && !File(lease, "worker.bak").exists()) return true // No pre-parse hello was acknowledged.
    val fields = AtomicFile(marker).openRead().use { input ->
        val data = DataInputStream(input); val values = IntArray(4) { data.readInt() }
        if (data.read() != -1) throw OfficeFailure("PREVIEW_RECOVERY_FAILED"); values
    }
    if (fields[0] != PREVIEW_MAGIC || fields[1] <= 1 || fields[1] == Process.myPid() || fields[2] % 100000 < 90000) throw OfficeFailure("PREVIEW_RECOVERY_FAILED")
    val currentBoot = boot(context)
    if (fields[3] >= 0 && currentBoot >= 0 && fields[3] != currentBoot) return true
    return try {
        Os.kill(fields[1], 0); false
    } catch (failure: ErrnoException) {
        if (failure.errno == OsConstants.ESRCH) true
        // EPERM is not death proof. A non-dumpable worker may expose root-owned
        // proc entries; an apparent UID mismatch cannot release its lease.
        // Unknown/reused live PIDs stay quarantined until ESRCH or a new boot.
        else false
    }
}
internal suspend fun recoverOfficePreviewLeases(context: Context) {
    val root = previewRoot(context); val entries = root.listFiles().orEmpty()
    if (entries.size > 4) throw OfficeFailure("PREVIEW_RECOVERY_FAILED")
    val until = SystemClock.elapsedRealtime() + 5000
    for (entry in entries) {
        currentCoroutineContext().ensureActive()
        if (!entry.isDirectory || java.nio.file.Files.isSymbolicLink(entry.toPath()) || !entry.name.startsWith("lease-")) throw OfficeFailure("PREVIEW_RECOVERY_FAILED")
        while (!previewGone(context, entry)) {
            currentCoroutineContext().ensureActive()
            if (SystemClock.elapsedRealtime() >= until) throw OfficeFailure("PREVIEW_CLEANUP_PENDING")
            delay(20)
        }
        if (!entry.deleteRecursively()) throw OfficeFailure("PREVIEW_RECOVERY_FAILED")
    }
}

/** A coordinator health barrier plus preview PID journals protect old UI copies
 * from deletion while a previous process can still own a source/output FD.
 */
internal suspend fun recoverOfficeWorkspace(context: Context) {
    officeCoordinatorIdle(context)
    recoverOfficePreviewLeases(context)
    val root = File(context.noBackupFilesDir, "office-ui")
    val entries = root.listFiles().orEmpty()
    if (entries.size > 16) throw OfficeFailure("CLEANUP_PENDING")
    for (entry in entries) {
        currentCoroutineContext().ensureActive()
        if (!entry.isDirectory || java.nio.file.Files.isSymbolicLink(entry.toPath()) || !entry.name.startsWith("session-")) throw OfficeFailure("CLEANUP_PENDING")
        if (!entry.deleteRecursively()) throw OfficeFailure("CLEANUP_PENDING")
    }
}
