package org.synapseworks.pageharbor.backup.restore

import java.util.Locale
import org.synapseworks.pageharbor.backup.format.BackupFolderRecord
import org.synapseworks.pageharbor.library.MAX_FOLDER_NAME_LENGTH

internal data class RestoreFolderPlan(
    val foldersToCreate: List<RestoreFolderToCreate>,
    val targetIdByOriginalId: Map<String, String>,
)

internal object RestoreFolderPlanner {
    fun plan(
        backupFolders: List<BackupFolderRecord>,
        requiredFolderIds: Set<String>,
        existingFolders: List<RestoreExistingFolder>,
        idSource: RestoreIdSource,
    ): RestoreFolderPlan {
        if (requiredFolderIds.isEmpty()) return RestoreFolderPlan(emptyList(), emptyMap())
        val byId = backupFolders.associateBy(BackupFolderRecord::folderId)
        require(requiredFolderIds.all(byId::containsKey))
        val ordered = topologicalOrder(byId, requiredFolderIds)
        val usedNames = existingFolders.mapTo(linkedSetOf()) { normalize(it.name) }
        val targetIds = linkedMapOf<String, String>()
        val result = ordered.map { original ->
            val targetId = idSource.newId()
            targetIds[original.folderId] = targetId
            val uniqueName = uniqueName(original.name, usedNames)
            RestoreFolderToCreate(
                folderId = targetId,
                originalFolderId = original.folderId,
                name = uniqueName,
                normalizedName = normalize(uniqueName),
                parentFolderId = original.parentFolderId?.let(targetIds::get),
                createdAtEpochMillis = original.createdAtEpochMillis,
                modifiedAtEpochMillis = original.modifiedAtEpochMillis,
            )
        }
        return RestoreFolderPlan(result, targetIds)
    }

    private fun topologicalOrder(
        byId: Map<String, BackupFolderRecord>,
        required: Set<String>,
    ): List<BackupFolderRecord> {
        val result = ArrayList<BackupFolderRecord>(required.size)
        val added = HashSet<String>()
        fun add(folderId: String) {
            if (folderId in added) return
            val folder = requireNotNull(byId[folderId])
            folder.parentFolderId?.takeIf(required::contains)?.let(::add)
            added += folderId
            result += folder
        }
        required.sorted().forEach(::add)
        return result
    }

    private fun uniqueName(original: String, usedNames: MutableSet<String>): String {
        if (usedNames.add(normalize(original))) return original
        var suffixNumber = 1
        while (true) {
            val suffix = if (suffixNumber == 1) " (restored)" else " (restored $suffixNumber)"
            val prefix = original.take((MAX_FOLDER_NAME_LENGTH - suffix.length).coerceAtLeast(1)).trimEnd()
            val candidate = "$prefix$suffix"
            if (usedNames.add(normalize(candidate))) return candidate
            suffixNumber += 1
        }
    }

    private fun normalize(value: String): String = value.trim()
        .replace(Regex("\\s+"), " ")
        .lowercase(Locale.ROOT)
}
