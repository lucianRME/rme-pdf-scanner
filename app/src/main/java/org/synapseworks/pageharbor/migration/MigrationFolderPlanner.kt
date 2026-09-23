package org.synapseworks.pageharbor.migration

import java.util.Locale
import org.synapseworks.pageharbor.library.LibraryFolderEntity
import org.synapseworks.pageharbor.library.MAX_FOLDER_NAME_LENGTH
import org.synapseworks.pageharbor.library.normalizeFolderName

internal data class MigrationExistingFolder(
    val folderId: String,
    val name: String,
    val normalizedName: String,
    val parentFolderId: String?,
)

internal data class MigrationFolderPlan(
    val targetFolderId: String?,
    val foldersToCreate: List<LibraryFolderEntity>,
)

/** Resolves and creates a true hierarchy; normalized-name uniqueness is scoped to siblings. */
internal object MigrationFolderPlanner {
    fun plan(
        relativePath: List<String>,
        existingFolders: List<MigrationExistingFolder>,
        newId: () -> String,
        nowMillis: Long,
    ): MigrationFolderPlan {
        if (relativePath.isEmpty()) return MigrationFolderPlan(null, emptyList())
        require(relativePath.none(::isUnsafePathSegment))

        val available = existingFolders.toMutableList()
        val reservedNormalizedNamesByParent = available.groupBy(MigrationExistingFolder::parentFolderId)
            .mapValuesTo(mutableMapOf()) { (_, siblings) ->
                siblings.mapTo(linkedSetOf()) { it.normalizedName.lowercase(Locale.ROOT) }
            }
        val created = mutableListOf<LibraryFolderEntity>()
        var parentId: String? = null

        relativePath.forEach { rawSegment ->
            val requestedName = normalizeFolderName(rawSegment).ifBlank { "Imported folder" }
            val requestedNormalized = requestedName.lowercase(Locale.ROOT)
            available.firstOrNull { folder ->
                folder.parentFolderId == parentId &&
                    folder.normalizedName.lowercase(Locale.ROOT) == requestedNormalized
            }?.let { matching ->
                parentId = matching.folderId
                return@forEach
            }

            var suffix = 1
            var selectedName: String
            var selectedNormalized: String
            while (true) {
                selectedName = collisionSafeName(requestedName, suffix)
                selectedNormalized = selectedName.lowercase(Locale.ROOT)
                val existingAtTarget = available.firstOrNull { folder ->
                    folder.parentFolderId == parentId &&
                        folder.normalizedName.lowercase(Locale.ROOT) == selectedNormalized
                }
                if (existingAtTarget != null) {
                    parentId = existingAtTarget.folderId
                    return@forEach
                }
                if (
                    selectedNormalized !in
                    reservedNormalizedNamesByParent[parentId].orEmpty()
                ) {
                    break
                }
                suffix += 1
            }

            val folderId = newId()
            require(folderId.isNotBlank())
            val entity = LibraryFolderEntity(
                folderId = folderId,
                name = selectedName,
                normalizedName = selectedNormalized,
                createdAtMillis = nowMillis,
                modifiedAtMillis = nowMillis,
                parentFolderId = parentId,
            )
            created += entity
            available += MigrationExistingFolder(
                folderId = entity.folderId,
                name = entity.name,
                normalizedName = entity.normalizedName,
                parentFolderId = entity.parentFolderId,
            )
            reservedNormalizedNamesByParent.getOrPut(entity.parentFolderId, ::linkedSetOf) +=
                selectedNormalized
            parentId = folderId
        }
        return MigrationFolderPlan(parentId, created)
    }

    private fun collisionSafeName(requestedName: String, suffixIndex: Int): String {
        if (suffixIndex == 1) return requestedName.take(MAX_FOLDER_NAME_LENGTH)
        val suffix = " ($suffixIndex)"
        val baseLength = (MAX_FOLDER_NAME_LENGTH - suffix.length).coerceAtLeast(1)
        return requestedName.take(baseLength).trimEnd() + suffix
    }
}
