package org.synapseworks.pageharbor.workflow

import java.io.File

/** Real filesystem fault for both legacy .bak and modern .new AtomicFile schemes. */
internal inline fun <T> withWorkflowMetadataBlocked(root: File, action: () -> T): T {
    val base = File(root, "queue.json")
    val held = File(root, "queue-test-held")
    check(base.isFile && !held.exists() && base.renameTo(held))
    val blocked = listOf(base, File(root, "queue.json.bak"), File(root, "queue.json.new"))
    try {
        blocked.forEach { path ->
            check(!path.exists() && path.mkdir())
            File(path, "block").writeBytes(byteArrayOf(1))
        }
        return action()
    } finally {
        blocked.forEach { check(it.deleteRecursively()) }
        check(held.renameTo(base))
    }
}
