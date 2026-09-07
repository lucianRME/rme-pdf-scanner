package org.synapseworks.pageharbor.document.searchablepdf

import java.io.File

private const val TemporaryPdfDirectory = "searchable-pdfs"
private const val TemporaryPdfPrefix = "searchable-"
private const val TemporaryVisualPrefix = "searchable-visual-"
private const val TemporaryPdfMaxAgeMillis = 24L * 60L * 60L * 1000L

/**
 * Removes only stale RME PDF Scanner-generated searchable-PDF cache files after a prior process ended.
 * It never traverses subdirectories, touches SAF destinations, or deletes a file outside the owned
 * name and location boundary. Active work is Activity-owned, so this runs only at a later startup.
 */
fun deleteStaleSearchablePdfs(
    cacheDirectory: File,
    nowMillis: Long = System.currentTimeMillis(),
) {
    val directory = File(cacheDirectory, TemporaryPdfDirectory)
    directory.listFiles()?.forEach { file ->
        if (
            file.isFile &&
            (
                (file.name.startsWith(TemporaryPdfPrefix) && file.name.endsWith(".pdf")) ||
                    (file.name.startsWith(TemporaryVisualPrefix) && file.name.endsWith(".jpg"))
            ) &&
            nowMillis - file.lastModified() >= TemporaryPdfMaxAgeMillis
        ) {
            deleteSafely(file)
        }
    }
}

private fun deleteSafely(file: File) {
    try {
        file.delete()
    } catch (_: SecurityException) {
        // Private-cache cleanup is best-effort and must not expose document details.
    }
}
