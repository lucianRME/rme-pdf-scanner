package org.synapseworks.pageharbor.backup.restore

import android.os.Debug
import androidx.room.RoomDatabase
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

internal data class StressMemoryPeak(
    val managedHeapBytes: Long,
    val nativeHeapBytes: Long,
    val processPssBytes: Long,
)

internal data class StressDiskTarget(
    val name: String,
    val file: File,
)

internal class StressResourceProbe(
    private val sampleIntervalMillis: Long = 20L,
    private val diskTargets: List<StressDiskTarget> = emptyList(),
) : Closeable {
    val baseline: StressMemoryPeak = StressMemoryPeak(
        managedHeapBytes = currentManagedHeapBytes(),
        nativeHeapBytes = Debug.getNativeHeapAllocatedSize(),
        processPssBytes = Debug.getPss().toLong() * 1_024L,
    )
    private val running = AtomicBoolean(true)
    private val maximumManaged = AtomicLong(baseline.managedHeapBytes)
    private val maximumNative = AtomicLong(baseline.nativeHeapBytes)
    private val maximumPss = AtomicLong(baseline.processPssBytes)
    private val maximumDiskBytes = diskTargets.associate { target ->
        target.name to AtomicLong(diskBytes(target.file))
    }
    private val maximumFileCounts = diskTargets.associate { target ->
        target.name to AtomicLong(fileCount(target.file))
    }

    init {
        require(sampleIntervalMillis > 0L)
        require(diskTargets.map(StressDiskTarget::name).distinct().size == diskTargets.size)
    }

    private val sampler = thread(
        start = true,
        isDaemon = true,
        name = "rme-phase2b-resource-probe",
    ) {
        while (running.get()) {
            sampleNow()
            try {
                Thread.sleep(sampleIntervalMillis)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
    }

    fun peak(): StressMemoryPeak = StressMemoryPeak(
        managedHeapBytes = maximumManaged.get(),
        nativeHeapBytes = maximumNative.get(),
        processPssBytes = maximumPss.get(),
    )

    fun peakGrowth(): StressMemoryPeak = peak().let { peak ->
        StressMemoryPeak(
            managedHeapBytes = maxOf(0L, peak.managedHeapBytes - baseline.managedHeapBytes),
            nativeHeapBytes = maxOf(0L, peak.nativeHeapBytes - baseline.nativeHeapBytes),
            processPssBytes = maxOf(0L, peak.processPssBytes - baseline.processPssBytes),
        )
    }

    fun diskPeaks(): Map<String, Long> = maximumDiskBytes.mapValues { (_, maximum) -> maximum.get() }

    fun fileCountPeaks(): Map<String, Long> = maximumFileCounts.mapValues { (_, maximum) -> maximum.get() }

    override fun close() {
        if (running.compareAndSet(true, false)) {
            sampleNow()
            sampler.interrupt()
            sampler.join(2_000L)
        }
    }

    private fun sampleNow() {
        maximumManaged.accumulateAndGet(currentManagedHeapBytes()) { left, right -> maxOf(left, right) }
        maximumNative.accumulateAndGet(Debug.getNativeHeapAllocatedSize()) { left, right -> maxOf(left, right) }
        maximumPss.accumulateAndGet(Debug.getPss().toLong() * 1_024L) { left, right -> maxOf(left, right) }
        diskTargets.forEach { target ->
            maximumDiskBytes.getValue(target.name).accumulateAndGet(diskBytes(target.file)) { left, right ->
                maxOf(left, right)
            }
            maximumFileCounts.getValue(target.name).accumulateAndGet(fileCount(target.file)) { left, right ->
                maxOf(left, right)
            }
        }
    }
}

internal fun diskBytes(root: File): Long {
    if (!root.exists()) return 0L
    if (root.isFile) return root.length()
    var total = 0L
    val pending = ArrayDeque<File>()
    pending += root
    while (pending.isNotEmpty()) {
        val current = pending.removeFirst()
        current.listFiles().orEmpty().forEach { child ->
            if (child.isDirectory) pending += child else total = saturatingAdd(total, child.length())
        }
    }
    return total
}

internal fun fileCount(root: File): Long {
    if (!root.exists()) return 0L
    if (root.isFile) return 1L
    var total = 0L
    val pending = ArrayDeque<File>()
    pending += root
    while (pending.isNotEmpty()) {
        pending.removeFirst().listFiles().orEmpty().forEach { child ->
            if (child.isDirectory) pending += child else total += 1L
        }
    }
    return total
}

internal class StressQueryCounter : RoomDatabase.QueryCallback {
    private val count = AtomicLong(0L)
    private val maximumBindArgumentCount = AtomicLong(0L)

    override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
        count.incrementAndGet()
        maximumBindArgumentCount.accumulateAndGet(bindArgs.size.toLong()) { left, right ->
            maxOf(left, right)
        }
    }

    fun reset() {
        count.set(0L)
        maximumBindArgumentCount.set(0L)
    }

    fun value(): Long = count.get()

    fun snapshot(): StressQueryStats = StressQueryStats(
        queryCount = count.get(),
        maximumBindArgumentCount = maximumBindArgumentCount.get(),
    )
}

internal data class StressQueryStats(
    val queryCount: Long,
    val maximumBindArgumentCount: Long,
)

private fun currentManagedHeapBytes(): Long =
    Runtime.getRuntime().let { runtime -> runtime.totalMemory() - runtime.freeMemory() }

private fun saturatingAdd(left: Long, right: Long): Long =
    if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
