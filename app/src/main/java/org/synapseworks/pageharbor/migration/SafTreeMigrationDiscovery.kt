package org.synapseworks.pageharbor.migration

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class MigrationTreeDiscoveryResult(
    val sources: List<MigrationSource>,
    val visitedDirectoryCount: Int,
    val unreadableDirectoryCount: Int,
    val skippedEntryCount: Int,
    val wasCancelled: Boolean,
    val wasTruncated: Boolean,
)

fun interface MigrationTreeDiscoveryProgressListener {
    fun onProgress(discoveredFiles: Int, visitedDirectories: Int)
}

internal data class MigrationTreeEntry(
    val documentId: String,
    val resourceReference: String,
    val displayName: String?,
    val declaredContentType: String?,
    val isDirectory: Boolean,
    val sizeBytes: Long?,
    val modifiedAtMillis: Long?,
)

internal interface MigrationTreeBackend {
    fun rootDocumentId(treeReference: String): String
    fun children(treeReference: String, directoryDocumentId: String): List<MigrationTreeEntry>
}

/**
 * Provider-neutral traversal used by the Android DocumentsContract adapter. Traversal is iterative,
 * cycle-safe, and bounded so a broken provider cannot recurse forever or exhaust app memory.
 */
internal class RecursiveMigrationTreeEnumerator(
    private val backend: MigrationTreeBackend,
    private val maximumEntries: Int = DEFAULT_MAX_TREE_ENTRIES,
    private val maximumDepth: Int = DEFAULT_MAX_TREE_DEPTH,
) {
    init {
        require(maximumEntries > 0)
        require(maximumDepth > 0)
    }

    suspend fun discover(
        treeReference: String,
        platformAccessFlags: Int,
        cancellationSignal: MigrationCancellationSignal = NeverCancelMigration,
        progressListener: MigrationTreeDiscoveryProgressListener =
            MigrationTreeDiscoveryProgressListener { _, _ -> },
    ): MigrationTreeDiscoveryResult {
        if (treeReference.isBlank()) return emptyFailureResult()
        val rootId = try {
            backend.rootDocumentId(treeReference)
        } catch (_: RuntimeException) {
            return emptyFailureResult()
        }.takeIf(String::isNotBlank) ?: return emptyFailureResult()

        data class PendingDirectory(
            val documentId: String,
            val relativePath: List<String>,
            val depth: Int,
        )

        val queue = ArrayDeque<PendingDirectory>()
        queue.add(PendingDirectory(rootId, emptyList(), 0))
        val visitedDirectoryIds = linkedSetOf<String>()
        val emittedResourceReferences = linkedSetOf<String>()
        val sources = mutableListOf<MigrationSource>()
        var unreadableDirectories = 0
        var skippedEntries = 0
        var enumeratedEntries = 0
        var truncated = false

        while (queue.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            if (cancellationSignal.isCancellationRequested()) break
            val directory = queue.removeFirst()
            if (!visitedDirectoryIds.add(directory.documentId)) continue
            val children = try {
                backend.children(treeReference, directory.documentId)
            } catch (_: RuntimeException) {
                unreadableDirectories += 1
                progressListener.onProgress(sources.size, visitedDirectoryIds.size)
                continue
            }.sortedWith { left, right ->
                val nameComparison = NaturalFilenameComparator.compare(
                    left.displayName ?: left.documentId,
                    right.displayName ?: right.documentId,
                )
                if (nameComparison != 0) nameComparison
                else left.documentId.compareTo(right.documentId)
            }

            for (entry in children) {
                currentCoroutineContext().ensureActive()
                if (cancellationSignal.isCancellationRequested()) break
                if (enumeratedEntries >= maximumEntries) {
                    truncated = true
                    break
                }
                enumeratedEntries += 1
                if (entry.documentId.isBlank() || entry.resourceReference.isBlank()) {
                    skippedEntries += 1
                    continue
                }
                if (entry.isDirectory) {
                    if (directory.depth >= maximumDepth) {
                        skippedEntries += 1
                        truncated = true
                        continue
                    }
                    val segment = safeTreeFolderSegment(entry.displayName, entry.documentId)
                    queue.add(
                        PendingDirectory(
                            documentId = entry.documentId,
                            relativePath = directory.relativePath + segment,
                            depth = directory.depth + 1,
                        ),
                    )
                } else if (emittedResourceReferences.add(entry.resourceReference)) {
                    sources += MigrationSource(
                        id = entry.resourceReference,
                        displayName = entry.displayName,
                        relativeFolderPath = directory.relativePath,
                        declaredContentType = entry.declaredContentType,
                        platformAccessFlags = platformAccessFlags,
                        sizeBytes = entry.sizeBytes,
                        modifiedAtMillis = entry.modifiedAtMillis,
                    )
                    progressListener.onProgress(sources.size, visitedDirectoryIds.size)
                }
            }
            progressListener.onProgress(sources.size, visitedDirectoryIds.size)
            if (truncated || cancellationSignal.isCancellationRequested()) break
        }

        return MigrationTreeDiscoveryResult(
            sources = sources,
            visitedDirectoryCount = visitedDirectoryIds.size,
            unreadableDirectoryCount = unreadableDirectories,
            skippedEntryCount = skippedEntries,
            wasCancelled = cancellationSignal.isCancellationRequested(),
            wasTruncated = truncated,
        )
    }

    private fun emptyFailureResult() = MigrationTreeDiscoveryResult(
        sources = emptyList(),
        visitedDirectoryCount = 0,
        unreadableDirectoryCount = 1,
        skippedEntryCount = 0,
        wasCancelled = false,
        wasTruncated = false,
    )
}

/** Recursively enumerates the user-selected SAF tree without assuming provider metadata exists. */
class AndroidSafTreeMigrationDiscovery(context: Context) {
    private val resolver: ContentResolver = context.applicationContext.contentResolver

    suspend fun discover(
        treeUri: Uri,
        platformAccessFlags: Int,
        cancellationSignal: MigrationCancellationSignal = NeverCancelMigration,
        progressListener: MigrationTreeDiscoveryProgressListener =
            MigrationTreeDiscoveryProgressListener { _, _ -> },
    ): MigrationTreeDiscoveryResult = withContext(Dispatchers.IO) {
        RecursiveMigrationTreeEnumerator(DocumentsContractTreeBackend(resolver)).discover(
            treeReference = treeUri.toString(),
            platformAccessFlags = platformAccessFlags,
            cancellationSignal = cancellationSignal,
            progressListener = progressListener,
        )
    }
}

private class DocumentsContractTreeBackend(
    private val resolver: ContentResolver,
) : MigrationTreeBackend {
    override fun rootDocumentId(treeReference: String): String =
        DocumentsContract.getTreeDocumentId(Uri.parse(treeReference))

    override fun children(
        treeReference: String,
        directoryDocumentId: String,
    ): List<MigrationTreeEntry> {
        val treeUri = Uri.parse(treeReference)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            directoryDocumentId,
        )
        val entries = mutableListOf<MigrationTreeEntry>()
        resolver.query(childrenUri, TREE_COLUMNS, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val documentId = cursor.safeString(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    ?: continue
                val contentType = cursor.safeString(DocumentsContract.Document.COLUMN_MIME_TYPE)
                entries += MigrationTreeEntry(
                    documentId = documentId,
                    resourceReference = DocumentsContract.buildDocumentUriUsingTree(
                        treeUri,
                        documentId,
                    ).toString(),
                    displayName = cursor.safeString(
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    ),
                    declaredContentType = contentType,
                    isDirectory = contentType == DocumentsContract.Document.MIME_TYPE_DIR,
                    sizeBytes = cursor.safeNonNegativeLong(
                        DocumentsContract.Document.COLUMN_SIZE,
                    ),
                    modifiedAtMillis = cursor.safePositiveLong(
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    ),
                )
            }
        } ?: throw IllegalStateException("Document provider returned no cursor")
        return entries
    }
}

private fun Cursor.safeString(column: String): String? {
    val index = getColumnIndex(column)
    if (index < 0 || isNull(index)) return null
    return try {
        getString(index)?.takeIf(String::isNotBlank)
    } catch (_: RuntimeException) {
        null
    }
}

private fun Cursor.safeNonNegativeLong(column: String): Long? {
    val index = getColumnIndex(column)
    if (index < 0 || isNull(index)) return null
    return try {
        getLong(index).takeIf { it >= 0L }
    } catch (_: RuntimeException) {
        null
    }
}

private fun Cursor.safePositiveLong(column: String): Long? =
    safeNonNegativeLong(column)?.takeIf { it > 0L }

internal fun safeTreeFolderSegment(displayName: String?, documentId: String): String {
    val cleaned = displayName.orEmpty().asSequence()
        .map { character ->
            if (
                character.isISOControl() || character == '/' || character == '\\' ||
                character == ':'
            ) {
                '_'
            } else {
                character
            }
        }
        .joinToString(separator = "")
        .trim()
        .trim('.')
        .take(MAX_TREE_FOLDER_SEGMENT_LENGTH)
    if (cleaned.isNotBlank() && cleaned != "." && cleaned != "..") return cleaned
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(documentId.encodeToByteArray())
        .take(4)
        .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte) }
    return "Folder-$digest"
}

private val TREE_COLUMNS = arrayOf(
    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
    DocumentsContract.Document.COLUMN_MIME_TYPE,
    DocumentsContract.Document.COLUMN_SIZE,
    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
)

private const val DEFAULT_MAX_TREE_ENTRIES = 10_000
private const val DEFAULT_MAX_TREE_DEPTH = 64
private const val MAX_TREE_FOLDER_SEGMENT_LENGTH = 80
