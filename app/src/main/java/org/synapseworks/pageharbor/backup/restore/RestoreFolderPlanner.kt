package org.synapseworks.pageharbor.backup.restore

import java.util.Locale
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.synapseworks.pageharbor.backup.format.BackupFolderRecord
import org.synapseworks.pageharbor.library.MAX_FOLDER_NAME_LENGTH

/** Plans the indexed restore hierarchy without retaining either folder tree in memory. */
internal object RestoreIndexedFolderPlanner {
    suspend fun plan(
        staging: IndexedRestoreStagingArea,
        expectedFolderCount: Int,
        store: RestoreLibraryStore,
        idSource: RestoreIdSource,
        cancellationSignal: RestoreCancellationSignal,
    ): RestoreFolderPlanSource {
        var plannedFolderCount = staging.plannedFolderCount()
        while (plannedFolderCount < expectedFolderCount) {
            currentCoroutineContext().ensureActive()
            if (cancellationSignal.isCancellationRequested()) throw RestoreFolderPlanningCancelled
            val ready = staging.unplannedReadyFoldersPage(INDEXED_FOLDER_BATCH_SIZE)
            check(ready.isNotEmpty()) { "The verified folder hierarchy cannot be planned" }
            ready.forEach { original ->
                currentCoroutineContext().ensureActive()
                if (cancellationSignal.isCancellationRequested()) throw RestoreFolderPlanningCancelled
                val targetParent = original.parentFolderId?.let { parentId ->
                    requireNotNull(staging.targetFolderId(parentId))
                }
                val targetId = idSource.newId()
                val uniqueName = uniqueIndexedName(
                    original.name,
                    targetParent,
                    targetId,
                    staging,
                    store,
                )
                val planned = RestoreFolderToCreate(
                        folderId = targetId,
                        originalFolderId = original.folderId,
                        name = uniqueName,
                        normalizedName = normalizeIndexedFolderName(uniqueName),
                        parentFolderId = targetParent,
                        createdAtEpochMillis = original.createdAtEpochMillis,
                        modifiedAtEpochMillis = original.modifiedAtEpochMillis,
                    )
                staging.recordPlannedFolder(planned)
                plannedFolderCount += 1
            }
        }
        check(plannedFolderCount == expectedFolderCount)
        return staging
    }

    private suspend fun uniqueIndexedName(
        original: String,
        targetParent: String?,
        targetId: String,
        staging: IndexedRestoreStagingArea,
        store: RestoreLibraryStore,
    ): String {
        val collisionToken = targetId.filter(Char::isLetterOrDigit).takeLast(8)
        require(collisionToken.isNotEmpty())
        var suffixNumber = -1
        while (true) {
            val suffix = when (suffixNumber) {
                -1 -> ""
                0 -> " (restored $collisionToken)"
                else -> " (restored $collisionToken-$suffixNumber)"
            }
            val prefix = original.take((MAX_FOLDER_NAME_LENGTH - suffix.length).coerceAtLeast(1)).trimEnd()
            val candidate = "$prefix$suffix"
            val normalized = normalizeIndexedFolderName(candidate)
            if (!staging.plannedFolderNameExists(targetParent, normalized) &&
                !store.folderNameExists(targetParent, normalized)
            ) {
                return candidate
            }
            suffixNumber += 1
        }
    }

    private fun normalizeIndexedFolderName(value: String): String = value.trim()
        .replace(Regex("\\s+"), " ")
        .lowercase(Locale.ROOT)

    private const val INDEXED_FOLDER_BATCH_SIZE = 256
}

internal object RestoreFolderPlanningCancelled : RuntimeException(null, null, false, false)

/**
 * Search content keeps the exact canonical folder path, but one adversarial hierarchy must not
 * create an unbounded String or an unbounded FTS row. Folder planning stores only this scalar size;
 * the path itself is reconstructed once for each document that actually needs it.
 */
internal fun restoreFolderSearchPathCharacters(parentCharacters: Int?, name: String): Int {
    require(name.isNotBlank())
    val separatorCharacters = if (parentCharacters == null) 0 else 1
    val characters = Math.addExact(
        parentCharacters ?: 0,
        Math.addExact(separatorCharacters, name.length),
    )
    require(characters <= MAX_RESTORE_FOLDER_SEARCH_PATH_CHARACTERS) {
        "A restore folder search path exceeds the supported length."
    }
    return characters
}

internal class RestoreFolderSearchPathBuilder {
    private val segments = ArrayList<Segment>()
    private var observedCharacters = 0

    fun add(
        targetId: String,
        targetParentId: String?,
        name: String,
        depthFromLeaf: Int,
    ) {
        require(depthFromLeaf >= 0)
        observedCharacters = restoreFolderSearchPathCharacters(
            observedCharacters.takeIf { segments.isNotEmpty() },
            name,
        )
        segments += Segment(targetId, targetParentId, name, depthFromLeaf)
    }

    fun build(expectedCharacters: Int): String {
        require(segments.isNotEmpty())
        require(expectedCharacters == observedCharacters)
        segments.sortByDescending(Segment::depthFromLeaf)
        val path = StringBuilder(expectedCharacters)
        var previousTargetId: String? = null
        segments.forEach { segment ->
            if (previousTargetId == null) {
                check(segment.targetParentId == null) { "A planned restore folder has a missing ancestor" }
            } else {
                check(segment.targetParentId == previousTargetId) {
                    "A planned restore folder hierarchy is inconsistent"
                }
                path.append('/')
            }
            path.append(segment.name)
            previousTargetId = segment.targetId
        }
        check(path.length == expectedCharacters)
        return path.toString()
    }

    private data class Segment(
        val targetId: String,
        val targetParentId: String?,
        val name: String,
        val depthFromLeaf: Int,
    )
}

internal const val MAX_RESTORE_FOLDER_SEARCH_PATH_CHARACTERS = 32 * 1024
