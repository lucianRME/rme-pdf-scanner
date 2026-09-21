package org.synapseworks.pageharbor.portability.export

import java.io.OutputStream
import java.util.Locale
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class PortableExportFolder(
    val id: String,
    val name: String,
    val parentId: String?,
)

data class PortableExportDocument(
    val id: String,
    val title: String,
    val folderId: String?,
)

data class PortableExportPlan(
    val folders: List<PortableExportFolder>,
    val documents: List<PortableExportDocument>,
)

fun interface PortableExportPdfSource {
    /** Writes the current edited representation as an ordinary PDF. */
    suspend fun writePdf(
        documentId: String,
        destination: OutputStream,
    ): PortableExportPdfWriteResult
}

enum class PortableExportPdfWriteResult {
    WRITTEN,
    SOURCE_UNAVAILABLE,
    WRITE_FAILED,
}

/** A provider-neutral directory writer. Handles may be content URIs or test-only identifiers. */
interface PortableExportDestination {
    fun createDirectory(parentHandle: String?, displayName: String): String?

    fun createFile(parentHandle: String, displayName: String, mimeType: String): String?

    fun openOutput(fileHandle: String): OutputStream?

    fun delete(handle: String)
}

data class PortableExportFailure(
    val documentId: String,
    val reason: PortableExportFailureReason,
)

enum class PortableExportFailureReason {
    DESTINATION_UNAVAILABLE,
    SOURCE_UNAVAILABLE,
    WRITE_FAILED,
}

data class PortableExportResult(
    val exportedDocumentCount: Int,
    val failures: List<PortableExportFailure>,
    val rootHandle: String,
)

fun interface PortableExportProgressListener {
    fun onProgress(completedDocuments: Int, totalDocuments: Int, currentTitle: String)
}

/**
 * Streams one PDF at a time into a user-selected tree. Existing provider content is never
 * overwritten: this engine creates a new RME Export directory and collision-safe child names.
 */
class PortableLibraryExportEngine(
    private val pdfSource: PortableExportPdfSource,
) {
    suspend fun export(
        plan: PortableExportPlan,
        destination: PortableExportDestination,
        progress: PortableExportProgressListener = PortableExportProgressListener { _, _, _ -> },
    ): PortableExportResult {
        validatePlan(plan)
        val rootHandle = destination.createDirectory(null, "RME Export")
            ?: throw PortableExportException("The export destination is unavailable.")
        return try {
            exportIntoRoot(plan, destination, rootHandle, progress)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            runCatching { destination.delete(rootHandle) }
            throw cancelled
        } catch (failure: Exception) {
            runCatching { destination.delete(rootHandle) }
            throw failure
        }
    }

    private suspend fun exportIntoRoot(
        plan: PortableExportPlan,
        destination: PortableExportDestination,
        rootHandle: String,
        progress: PortableExportProgressListener,
    ): PortableExportResult {
        val foldersById = plan.folders.associateBy(PortableExportFolder::id)
        val directoryHandles = mutableMapOf<String, String>()
        val usedDirectoryNames = mutableMapOf<String?, MutableSet<String>>()
        if (plan.documents.any { it.folderId == null }) {
            usedDirectoryNames.getOrPut(rootHandle) { mutableSetOf() }
                .add(UNFILED_DIRECTORY_NAME.lowercase(Locale.ROOT))
        }

        plan.folders.sortedWith(compareBy<PortableExportFolder> { folderDepth(it.id, foldersById) }
            .thenBy { it.name.lowercase(Locale.ROOT) }
            .thenBy(PortableExportFolder::id))
            .forEach { folder ->
                currentCoroutineContext().ensureActive()
                val parentHandle = if (folder.parentId == null) {
                    rootHandle
                } else {
                    directoryHandles[folder.parentId] ?: return@forEach
                }
                val usedNames = usedDirectoryNames.getOrPut(parentHandle) { mutableSetOf() }
                val displayName = PortableExportNaming.allocateFolderName(folder.name, usedNames)
                destination.createDirectory(parentHandle, displayName)?.let { handle ->
                    directoryHandles[folder.id] = handle
                }
            }

        var unfiledHandle: String? = null
        val usedFileNames = mutableMapOf<String, MutableSet<String>>()
        val failures = mutableListOf<PortableExportFailure>()
        var exported = 0
        val orderedDocuments = plan.documents.sortedWith(
            compareBy<PortableExportDocument> { it.folderId.orEmpty() }
                .thenBy { it.title.lowercase(Locale.ROOT) }
                .thenBy(PortableExportDocument::id),
        )
        orderedDocuments.forEachIndexed { index, document ->
            currentCoroutineContext().ensureActive()
            progress.onProgress(index, orderedDocuments.size, document.title)
            val parentHandle = if (document.folderId == null) {
                unfiledHandle ?: destination.createDirectory(rootHandle, UNFILED_DIRECTORY_NAME)
                    ?.also { unfiledHandle = it }
            } else {
                directoryHandles[document.folderId]
            }
            if (parentHandle == null) {
                failures += PortableExportFailure(
                    document.id,
                    PortableExportFailureReason.DESTINATION_UNAVAILABLE,
                )
                return@forEachIndexed
            }
            val fileName = PortableExportNaming.allocateFileName(
                title = document.title,
                extension = "pdf",
                usedNames = usedFileNames.getOrPut(parentHandle) { mutableSetOf() },
            )
            val fileHandle = destination.createFile(parentHandle, fileName, PDF_MIME_TYPE)
            if (fileHandle == null) {
                failures += PortableExportFailure(
                    document.id,
                    PortableExportFailureReason.DESTINATION_UNAVAILABLE,
                )
                return@forEachIndexed
            }
            val output = destination.openOutput(fileHandle)
            if (output == null) {
                destination.delete(fileHandle)
                failures += PortableExportFailure(
                    document.id,
                    PortableExportFailureReason.DESTINATION_UNAVAILABLE,
                )
                return@forEachIndexed
            }
            val writeResult = try {
                output.use { pdfSource.writePdf(document.id, it) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                destination.delete(fileHandle)
                throw cancelled
            } catch (_: Exception) {
                PortableExportPdfWriteResult.WRITE_FAILED
            }
            when (writeResult) {
                PortableExportPdfWriteResult.WRITTEN -> exported += 1
                PortableExportPdfWriteResult.SOURCE_UNAVAILABLE,
                PortableExportPdfWriteResult.WRITE_FAILED,
                -> {
                    destination.delete(fileHandle)
                    failures += PortableExportFailure(
                        document.id,
                        if (writeResult == PortableExportPdfWriteResult.SOURCE_UNAVAILABLE) {
                            PortableExportFailureReason.SOURCE_UNAVAILABLE
                        } else {
                            PortableExportFailureReason.WRITE_FAILED
                        },
                    )
                }
            }
            progress.onProgress(index + 1, orderedDocuments.size, document.title)
        }
        return PortableExportResult(exported, failures.toList(), rootHandle)
    }

    private fun validatePlan(plan: PortableExportPlan) {
        require(plan.folders.map(PortableExportFolder::id).distinct().size == plan.folders.size)
        require(plan.documents.map(PortableExportDocument::id).distinct().size == plan.documents.size)
        val foldersById = plan.folders.associateBy(PortableExportFolder::id)
        plan.folders.forEach { folder ->
            require(folder.id.isNotBlank())
            require(folder.parentId == null || folder.parentId in foldersById)
            require(folderDepth(folder.id, foldersById) < MAX_FOLDER_DEPTH)
        }
        plan.documents.forEach { document ->
            require(document.id.isNotBlank())
            require(document.folderId == null || document.folderId in foldersById)
        }
    }

    private fun folderDepth(
        folderId: String,
        foldersById: Map<String, PortableExportFolder>,
    ): Int {
        val visited = mutableSetOf<String>()
        var currentId: String? = folderId
        var depth = 0
        while (currentId != null) {
            require(visited.add(currentId)) { "Folder hierarchy contains a cycle." }
            currentId = foldersById.getValue(currentId).parentId
            depth += 1
            require(depth <= MAX_FOLDER_DEPTH) { "Folder hierarchy is too deep." }
        }
        return depth
    }
}

class PortableExportException(message: String) : Exception(message)

private const val PDF_MIME_TYPE = "application/pdf"
private const val MAX_FOLDER_DEPTH = 100
private const val UNFILED_DIRECTORY_NAME = "Unfiled"
