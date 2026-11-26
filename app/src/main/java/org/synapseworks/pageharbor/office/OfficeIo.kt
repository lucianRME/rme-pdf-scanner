package org.synapseworks.pageharbor.office

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.system.Os
import android.system.OsConstants
import org.synapseworks.pageharbor.forms.FormDestination
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import org.synapseworks.m35.workerprobe.OfficeAdmission

internal const val OFFICE_INPUT = 20L * 1024 * 1024
internal const val OFFICE_OUTPUT = 32L * 1024 * 1024
internal val OFFICE_MIME = linkedMapOf("docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation")
internal data class OfficeIdentity(val device: Long, val inode: Long)
internal fun officeIdentity(fd: ParcelFileDescriptor): OfficeIdentity {
    val stat = Os.fstat(fd.fileDescriptor)
    if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_ino <= 0) throw OfficeFailure("SEEKABLE_LOCAL_SOURCE_REQUIRED")
    Os.lseek(fd.fileDescriptor, 0, OsConstants.SEEK_CUR)
    return OfficeIdentity(stat.st_dev, stat.st_ino)
}
internal fun officeDigest(fd: ParcelFileDescriptor, limit: Long, check: () -> Unit): String {
    val size = Os.fstat(fd.fileDescriptor).st_size
    if (size !in 1..limit) throw OfficeFailure("SIZE_LIMIT")
    val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(16384); var offset = 0L
    while (offset < size) {
        check(); val count = Os.pread(fd.fileDescriptor, buffer, 0, minOf(buffer.size.toLong(), size - offset).toInt(), offset)
        if (count <= 0) throw OfficeFailure("SOURCE_CHANGED")
        digest.update(buffer, 0, count); offset += count
    }
    check(); if (Os.fstat(fd.fileDescriptor).st_size != size) throw OfficeFailure("SOURCE_CHANGED")
    return digest.digest().joinToString("") { "%02x".format(it) }
}
internal fun officeDigest(file: File, check: () -> Unit): String = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { officeDigest(it, OFFICE_OUTPUT, check) }

/** Provider FD retained; coordinator owns the only private source-byte snapshot. */
internal class OfficeSource private constructor(val uri: Uri, val kind: String, val descriptor: ParcelFileDescriptor,
    val identity: OfficeIdentity, val digest: String, private val context: Context) : AutoCloseable {
    fun rewind() { Os.lseek(descriptor.fileDescriptor, 0, OsConstants.SEEK_SET) }
    fun unchanged(check: () -> Unit) {
        check()
        if (officeIdentity(descriptor) != identity || officeDigest(descriptor, OFFICE_INPUT, check) != digest) throw OfficeFailure("SOURCE_CHANGED")
        val current = context.contentResolver.openFileDescriptor(uri, "r") ?: throw OfficeFailure("SOURCE_CHANGED")
        current.use { if (officeIdentity(it) != identity || officeDigest(it, OFFICE_INPUT, check) != digest) throw OfficeFailure("SOURCE_CHANGED") }
    }
    override fun close() { descriptor.close() }
    override fun toString() = "OfficeSource(<redacted>)"
    companion object {
        fun open(context: Context, uri: Uri, kind: String, check: () -> Unit): OfficeSource {
            if (kind !in OFFICE_MIME || uri.scheme != "content") throw OfficeFailure("SOURCE_KIND")
            val fd = context.contentResolver.openFileDescriptor(uri, "r") ?: throw OfficeFailure("SOURCE_UNAVAILABLE")
            try {
                if ((org.synapseworks.m35.workerprobe.OfficeFd.flags(fd) and OsConstants.O_ACCMODE) != OsConstants.O_RDONLY) throw OfficeFailure("SOURCE_MODE")
                val identity = officeIdentity(fd); val digest = officeDigest(fd, OFFICE_INPUT, check)
                return OfficeSource(uri, kind, fd, identity, digest, context.applicationContext)
            } catch (failure: Throwable) { fd.close(); throw failure }
        }
    }
}
internal class OfficeOutput(val file: File, val digest: String, val pages: Int, val owner: String) {
    override fun toString() = "OfficeOutput(<redacted>)"
    fun verify(check: () -> Unit) { if (pages !in 1..10 || officeDigest(file, check) != digest) throw OfficeFailure("OUTPUT_CHANGED") }
}
internal fun officeSessionDirectory(context: Context): File {
    val root = File(context.noBackupFilesDir, "office-ui").apply { check(mkdirs() || isDirectory) }
    if (root.usableSpace < 320L * 1024 * 1024) throw OfficeFailure("STORAGE_LOW")
    return File(root, "session-${UUID.randomUUID()}").apply { check(mkdir()) }
}
/** Temporary pre-review snapshot is deleted before the coordinator starts.
 * This prevents a mutable provider from changing XML between admission and review.
 */
internal fun inspectOfficeSource(context: Context, source: OfficeSource, check: () -> Unit): List<String> {
    val directory = officeSessionDirectory(context)
    val snapshot = File(directory, "review.${source.kind}")
    try {
        val size = Os.fstat(source.descriptor.fileDescriptor).st_size
        if (size !in 1..OFFICE_INPUT) throw OfficeFailure("SIZE_LIMIT")
        FileOutputStream(snapshot).use { out ->
            val buffer = ByteArray(16384); var offset = 0L
            while (offset < size) {
                check(); val count = Os.pread(source.descriptor.fileDescriptor, buffer, 0, minOf(buffer.size.toLong(), size - offset).toInt(), offset)
                if (count <= 0) throw OfficeFailure("SOURCE_CHANGED")
                out.write(buffer, 0, count); offset += count
            }
        }
        if (!snapshot.setReadOnly() || officeDigest(snapshot, check) != source.digest) throw OfficeFailure("SOURCE_CHANGED")
        val fonts = OfficeAdmission.inspect(snapshot, source.kind).toList()
        source.unchanged(check); return fonts
    } finally { directory.deleteRecursively() }
}
internal fun officeSourceAlias(source: OfficeSource, uri: Uri): Boolean = uri == source.uri ||
    (uri.authority == source.uri.authority && runCatching {
        DocumentsContract.getDocumentId(uri) == DocumentsContract.getDocumentId(source.uri)
    }.getOrDefault(false))

/** Separate trusted-local Android destination. Never truncate or delete a user's file. */
internal fun saveOffice(context: Context, source: OfficeSource, output: OfficeOutput, uri: Uri, check: () -> Unit,
    openTarget: () -> ParcelFileDescriptor? = { context.contentResolver.openFileDescriptor(uri, "rw") },
    supported: Boolean = FormDestination.supports(context, uri),
    openCurrent: () -> ParcelFileDescriptor? = { context.contentResolver.openFileDescriptor(uri, "r") }) {
    check(); if (!supported || officeSourceAlias(source, uri)) throw OfficeFailure("DESTINATION_REFUSED")
    source.unchanged(check); output.verify(check)
    val target = openTarget() ?: throw OfficeFailure("DESTINATION_REFUSED")
    target.use {
        check()
        val targetIdentity = officeIdentity(target)
        if (targetIdentity == source.identity || Os.fstat(target.fileDescriptor).st_size != 0L ||
            (org.synapseworks.m35.workerprobe.OfficeFd.flags(target) and OsConstants.O_ACCMODE) != OsConstants.O_RDWR) throw OfficeFailure("DESTINATION_REFUSED")
        source.unchanged(check); output.verify(check)
        ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.dup(target.fileDescriptor)).use { out ->
            output.file.inputStream().use { input ->
                val buffer = ByteArray(16384); var total = 0L
                while (true) {
                    check(); val count = input.read(buffer); if (count < 0) break
                    total += count; if (total > OFFICE_OUTPUT) throw OfficeFailure("OUTPUT_LIMIT")
                    out.write(buffer, 0, count)
                }
                if (total != output.file.length()) throw OfficeFailure("OUTPUT_CHANGED")
                check(); out.flush(); out.fd.sync()
            }
        }
        check(); source.unchanged(check); output.verify(check)
        if (officeIdentity(target) != targetIdentity || officeDigest(target, OFFICE_OUTPUT, check) != output.digest) throw OfficeFailure("DESTINATION_CHANGED")
        val current = openCurrent() ?: throw OfficeFailure("DESTINATION_CHANGED")
        current.use { if (officeIdentity(it) != targetIdentity || officeDigest(it, OFFICE_OUTPUT, check) != output.digest) throw OfficeFailure("DESTINATION_CHANGED") }
    }
}

/** User-selected sharing gets an independent bounded copy and existing FileProvider scope. */
internal fun shareOfficeCopy(context: Context, source: OfficeSource, output: OfficeOutput, check: () -> Unit): File {
    source.unchanged(check); output.verify(check)
    val root = File(context.cacheDir, "shared-pdfs").apply { require(mkdirs() || isDirectory) }
    root.listFiles().orEmpty().filter { it.name.startsWith("office-") && it.lastModified() < System.currentTimeMillis() - 86400000 }.forEach { it.delete() }
    if (root.listFiles().orEmpty().filter { it.name.startsWith("office-") }.sumOf { it.length() } + output.file.length() > 64L * 1024 * 1024) throw OfficeFailure("SHARE_STORAGE_LIMIT")
    val file = File(root, "office-${UUID.randomUUID()}.pdf")
    try {
        output.file.inputStream().use { input -> file.outputStream().use { out ->
            val buffer = ByteArray(16384); var total = 0L
            while (true) { check(); val n = input.read(buffer); if (n < 0) break; total += n; if (total > OFFICE_OUTPUT) throw OfficeFailure("OUTPUT_LIMIT"); out.write(buffer, 0, n) }
        } }
        if (officeDigest(file, check) != output.digest) throw OfficeFailure("OUTPUT_CHANGED")
        source.unchanged(check); check(); return file
    } catch (failure: Throwable) { file.delete(); throw failure }
}

/** Explicit user control; never delete other features' shared copies. Existing
 * recipient-held descriptors/copies cannot be recalled by revoking a URI grant.
 */
internal fun clearOfficeShares(context: Context, check: () -> Unit) {
    val root = File(context.cacheDir, "shared-pdfs")
    if (java.nio.file.Files.isSymbolicLink(root.toPath())) throw OfficeFailure("SHARE_STORAGE_LIMIT")
    val owned = root.listFiles().orEmpty().filter { it.name.startsWith("office-") }
    if (owned.size > 256) throw OfficeFailure("SHARE_STORAGE_LIMIT")
    for (file in owned) {
        check()
        if (!file.name.matches(Regex("office-[a-f0-9-]{36}\\.pdf")) || !file.isFile || java.nio.file.Files.isSymbolicLink(file.toPath())) throw OfficeFailure("SHARE_STORAGE_LIMIT")
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.revokeUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (!file.delete()) throw OfficeFailure("SHARE_CLEANUP_FAILED")
    }
}
