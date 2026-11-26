package org.synapseworks.pageharbor.office

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.*
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.io.RandomAccessFile
import java.io.DataInputStream
import org.synapseworks.m35.workerprobe.OfficePreviewService
import org.synapseworks.m35.workerprobe.ProductionCoordinator

internal class OfficeFailure(val code: String) : Exception("Office operation declined: " + if (code.matches(Regex("[A-Z_]{1,32}"))) code else "UNKNOWN")
internal data class OfficeMetrics(val initializationMs: Long, val conversionMs: Long, val javaBytes: Long,
    val nativeBytes: Long, val pssBytes: Long, val copiedBytes: Long, val temporaryBytes: Long) {
    fun withinLimits() = initializationMs in 0..10000 && conversionMs in 0..15000 && javaBytes in 0..96L * 1024 * 1024 &&
        nativeBytes >= 0 && pssBytes in 0..512L * 1024 * 1024 && copiedBytes in 1..OFFICE_OUTPUT && temporaryBytes in 0..256L * 1024 * 1024
}
internal data class OfficeResult(val pages: Int, val metrics: OfficeMetrics)

internal suspend fun officeCoordinatorIdle(context: Context) = withContext(Dispatchers.IO) {
    val connected = CompletableDeferred<IBinder>()
    val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) { connected.complete(service) }
        override fun onServiceDisconnected(name: ComponentName) { if (!connected.isCompleted) connected.completeExceptionally(OfficeFailure("RECOVERY_FAILED")) }
    }
    val bound = context.bindService(Intent(context, ProductionCoordinator::class.java), connection, Context.BIND_AUTO_CREATE)
    if (!bound) throw OfficeFailure("RECOVERY_FAILED")
    try {
        withTimeout(10000) {
            val service = connected.await(); val until = SystemClock.elapsedRealtime() + 5000
            while (true) {
                currentCoroutineContext().ensureActive()
                val request = Parcel.obtain(); val reply = Parcel.obtain()
                val idle = try {
                    request.writeInterfaceToken("M35_PROBE_V3")
                    if (!service.transact(5, request, reply, 0)) throw OfficeFailure("RECOVERY_FAILED")
                    val ready = reply.readInt(); val active = reply.readInt(); val bytes = reply.readLong(); val outcome = reply.readString()
                    if (ready != 1 || outcome == null || !outcome.matches(Regex("[A-Z_]{1,32}")) || reply.dataAvail() != 0) throw OfficeFailure("RECOVERY_FAILED")
                    active == 0 && bytes == 0L
                } finally { request.recycle(); reply.recycle() }
                if (idle) break
                if (SystemClock.elapsedRealtime() >= until) throw OfficeFailure("CLEANUP_PENDING")
                delay(50)
            }
        }
    } catch (_: TimeoutCancellationException) { throw OfficeFailure("RECOVERY_FAILED") }
    finally { context.unbindService(connection) }
}

/** One app-private coordinator request. No caller-supplied test commands. */
internal suspend fun convertOffice(context: Context, source: OfficeSource, output: File): OfficeResult = withContext(Dispatchers.IO) {
    val operationContext = currentCoroutineContext()
    val connected = CompletableDeferred<IBinder>()
    val complete = CompletableDeferred<Triple<String, String, Int>>()
    var binder: IBinder? = null
    var nonce: String? = null
    var resources: OfficeMetrics? = null
    val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) { binder = service; connected.complete(service) }
        override fun onServiceDisconnected(name: ComponentName) { complete.completeExceptionally(OfficeFailure("ENGINE_DIED")) }
    }
    val callback = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (Binder.getCallingUid() != Process.myUid() || data.dataSize() > 4096) return false
            if (code == 2 || code == 3) return true
            if (code != 1) return false
            return try {
                data.enforceInterface("M35_RESULT_V8")
                val token = data.readString() ?: return false
                val status = data.readString() ?: return false
                val pages = data.readInt()
                val initialization = data.readLong(); val conversion = data.readLong(); val javaBytes = data.readLong()
                val nativeBytes = data.readLong(); val pss = data.readLong(); val copied = data.readLong()
                val clean = data.readInt(); val temporary = data.readLong()
                val measured = OfficeMetrics(initialization, conversion, javaBytes, nativeBytes, pss, copied, temporary)
                val metrics = IntArray(15) { data.readInt() }
                val trace = data.readString()
                if (data.dataAvail() != 0 || !token.matches(Regex("[a-f0-9]{32}")) || !status.matches(Regex("[A-Z_]{1,32}")) || trace == null || trace.length > 1024) return false
                val isolated = metrics[0] % 100000 >= 90000 && metrics[2] % 100000 >= 90000 && metrics[0] != metrics[2]
                val success = measured.withinLimits() && clean == 1 && pages in 1..10 && isolated && metrics[4] == 2 && metrics[5] == 131071 && metrics[7] == 511 && metrics[8] == 2047 && metrics[9] == 3 && metrics[10] in 1..2
                resources = measured
                complete.complete(Triple(token, if (status == "OK" && !success) "ISOLATION_FAILED" else if (clean != 1) "CLEANUP_FAILED" else status, pages)); true
            } catch (_: Exception) { false }
        }
    }
    val bound = context.bindService(Intent(context, ProductionCoordinator::class.java), connection, Context.BIND_AUTO_CREATE)
    if (!bound) throw OfficeFailure("ENGINE_UNAVAILABLE")
    try {
        val service = withTimeout(10000) { connected.await() }
        source.unchanged { operationContext.ensureActive() }
        ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE).use { destination ->
            source.rewind()
            val request = Parcel.obtain(); val response = Parcel.obtain()
            try {
                request.writeInterfaceToken("M35_PROBE_V3"); request.writeString(source.kind); request.writeString(source.digest); request.writeInt(0)
                request.writeFileDescriptor(source.descriptor.fileDescriptor); request.writeFileDescriptor(destination.fileDescriptor); request.writeStrongBinder(callback)
                if (!service.transact(1, request, response, 0)) throw OfficeFailure("ENGINE_UNAVAILABLE")
                nonce = response.readString()
                if (nonce?.matches(Regex("[a-f0-9]{32}")) != true) throw OfficeFailure("ENGINE_BUSY_OR_UNAVAILABLE")
            } finally { request.recycle(); response.recycle() }
            val result = withTimeout(70000) { complete.await() }
            if (result.first != nonce || result.second != "OK") throw OfficeFailure(if (result.first == nonce) result.second else "STALE_RESULT")
            currentCoroutineContext().ensureActive()
            source.unchanged { operationContext.ensureActive() }
            if (output.length() !in 1..OFFICE_OUTPUT || !output.setReadOnly()) throw OfficeFailure("OUTPUT_LIMIT")
            OfficeResult(result.third, checkNotNull(resources))
        }
    } catch (_: TimeoutCancellationException) { throw OfficeFailure("ENGINE_TIMEOUT")
    } finally {
        // Cancellation reaches the native worker before the coordinator is unbound.
        if (!complete.isCompleted && nonce != null && binder != null) {
            val request = Parcel.obtain(); val response = Parcel.obtain()
            try { request.writeInterfaceToken("M35_PROBE_V3"); request.writeString(nonce); binder!!.transact(2, request, response, 0) } catch (_: Exception) { } finally { request.recycle(); response.recycle() }
            withContext(NonCancellable) { runCatching { withTimeoutOrNull(7000) { complete.await() } } }
        }
        context.unbindService(connection)
    }
}

/** Raw pixel protocol: exact nonce, isolated identity, row order and size bounds.
 * Publish pixels only after the isolated process dies. No parser in this process.
 */
internal suspend fun previewOffice(context: Context, pdf: File, index: Int, pages: Int): Bitmap {
    val crossing = AtomicReference<Bitmap?>()
    try {
        val delivered = withContext(Dispatchers.IO) { previewOfficeInIo(context, pdf, index, pages).also(crossing::set) }
        crossing.compareAndSet(delivered, null)
        return delivered
    } finally { crossing.getAndSet(null)?.recycle() }
}
private suspend fun previewOfficeInIo(context: Context, pdf: File, index: Int, pages: Int): Bitmap {
    val lease = officePreviewLease(context)
    val raw = File(lease, "pixels")
    var spool: RandomAccessFile? = null
    val connected = CompletableDeferred<IBinder>(); val complete = CompletableDeferred<Unit>()
    val dead = CompletableDeferred<Unit>()
    val token = UUID.randomUUID().toString().replace("-", "")
    val monitor = Any()
    var service: IBinder? = null; var uid = 0; var pid = 0; var row = 0; var width = 0; var height = 0
    var accepted = false; var stopped = false; var rowPixels: IntArray? = null; var rowBytes: ByteArray? = null
    val callback = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = synchronized(monitor) {
            if (reply == null || complete.isCompleted || data.dataSize() > 49152) return@synchronized false
            try {
                data.enforceInterface(OfficePreviewService.RESULT)
                if (data.readString() != token) return@synchronized false
                val caller = Binder.getCallingUid(); val process = Binder.getCallingPid()
                if (caller % 100000 < 90000 || caller == Process.myUid() || process <= 1) return@synchronized false
                when (code) {
                    0 -> {
                        if (uid != 0 || data.readInt() != caller || data.readInt() != process) return@synchronized false
                        recordOfficePreview(context, lease, process, caller)
                        uid = caller; pid = process
                    }
                    1 -> {
                        if (caller != uid || process != pid || width != 0) return@synchronized false
                        width = data.readInt(); height = data.readInt()
                        if (width !in 1..1280 || height !in 1..1280 || width.toLong() * height * 4 + 1024 * 1024 > 8L * 1024 * 1024) return@synchronized false
                        spool = RandomAccessFile(raw, "rw").also { it.setLength(width.toLong() * height * 4) }
                    }
                    2 -> {
                        if (caller != uid || process != pid) return@synchronized false
                        val target = spool ?: return@synchronized false
                        if (data.readInt() != row) return@synchronized false
                        val count = data.readInt()
                        if (count !in 1..8 || row + count > height) return@synchronized false
                        // Enforce declared array length before allocating untrusted IPC data.
                        val position = data.dataPosition(); val length = data.readInt(); data.setDataPosition(position)
                        if (length != count * width) return@synchronized false
                        val pixels = rowPixels?.takeIf { it.size == length } ?: IntArray(length).also { rowPixels = it }
                        data.readIntArray(pixels)
                        if (data.dataAvail() != 0 || target.filePointer != row.toLong() * width * 4) return@synchronized false
                        val bytes = rowBytes?.takeIf { it.size == length * 4 } ?: ByteArray(length * 4).also { rowBytes = it }
                        pixels.forEachIndexed { index, value -> for (component in 0..3) bytes[index * 4 + component] = (value ushr (24 - component * 8)).toByte() }
                        target.write(bytes); row += count
                    }
                    3 -> {
                        if (caller != uid || process != pid || data.readString() != "OK" || width == 0 || spool == null || row != height) {
                            complete.completeExceptionally(OfficeFailure("PREVIEW_FAILED")); return@synchronized false
                        }
                        spool!!.fd.sync(); spool!!.close(); spool = null
                        if (raw.length() != width.toLong() * height * 4 || !raw.setReadOnly()) return@synchronized false
                    }
                    else -> return@synchronized false
                }
                if (data.dataAvail() != 0) return@synchronized false
                reply.writeInt(1)
                if (code == 3) complete.complete(Unit)
                true
            } catch (_: Throwable) { complete.completeExceptionally(OfficeFailure("PREVIEW_FAILED")); false }
        }
    }
    val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = binder
            try {
                // isBinderAlive may retain a cached true value without a death
                // subscription. Require the kernel's death notification.
                binder.linkToDeath({ dead.complete(Unit); if (!complete.isCompleted) complete.completeExceptionally(OfficeFailure("PREVIEW_DIED")) }, 0)
                connected.complete(binder)
            } catch (_: Exception) { dead.complete(Unit); connected.completeExceptionally(OfficeFailure("PREVIEW_DIED")) }
        }
        override fun onServiceDisconnected(name: ComponentName) { if (!complete.isCompleted) complete.completeExceptionally(OfficeFailure("PREVIEW_DIED")) }
    }
    val bound = try { context.bindService(Intent(context, OfficePreviewService::class.java), connection, Context.BIND_AUTO_CREATE) }
        catch (_: Exception) { lease.deleteRecursively(); throw OfficeFailure("PREVIEW_UNAVAILABLE") }
    if (!bound) { lease.deleteRecursively(); throw OfficeFailure("PREVIEW_UNAVAILABLE") }
    try {
        withTimeout(15000) {
            val binder = connected.await()
            ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { source ->
                val request = Parcel.obtain(); val reply = Parcel.obtain()
                try {
                    request.writeInterfaceToken(OfficePreviewService.API); request.writeString(token); request.writeInt(index); request.writeInt(pages)
                    request.writeFileDescriptor(source.fileDescriptor); request.writeStrongBinder(callback)
                    if (!binder.transact(1, request, reply, 0) || reply.readInt() != 1) throw OfficeFailure("PREVIEW_REFUSED")
                } finally { request.recycle(); reply.recycle() }
                complete.await()
            }
        }
        accepted = true
    } catch (_: TimeoutCancellationException) { throw OfficeFailure("PREVIEW_TIMEOUT")
    } finally {
        context.unbindService(connection)
        // As with the qualified worker, unbinding terminates an isolated service.
        stopped = service == null || withContext(NonCancellable) { withTimeoutOrNull(5000) { dead.await(); true } == true }
        synchronized(monitor) { runCatching { spool?.close() }; spool = null }
        if (!accepted || !stopped) {
            if (stopped) lease.deleteRecursively()
            if (accepted) throw OfficeFailure("PREVIEW_STOP_FAILED")
        }
    }
    var bitmap: Bitmap? = null
    try {
        currentCoroutineContext().ensureActive()
        if (!stopped || raw.length() != width.toLong() * height * 4) throw OfficeFailure("PREVIEW_FAILED")
        val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888); bitmap = image
        val pixels = IntArray(width); val bytes = ByteArray(width * 4)
        DataInputStream(raw.inputStream()).use { input ->
            for (y in 0 until height) {
                currentCoroutineContext().ensureActive(); input.readFully(bytes)
                for (x in 0 until width) pixels[x] = ((bytes[x * 4].toInt() and 255) shl 24) or ((bytes[x * 4 + 1].toInt() and 255) shl 16) or ((bytes[x * 4 + 2].toInt() and 255) shl 8) or (bytes[x * 4 + 3].toInt() and 255)
                image.setPixels(pixels, 0, width, 0, y, width, 1)
            }
            if (input.read() != -1) throw OfficeFailure("PREVIEW_FAILED")
        }
        return image.also { bitmap = null }
    } finally { bitmap?.recycle(); lease.deleteRecursively() }
}
