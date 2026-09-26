package org.synapseworks.pageharbor.backup.restore

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.synapseworks.pageharbor.backup.format.BackupFolderRecord
import org.synapseworks.pageharbor.library.MAX_FOLDER_NAME_LENGTH
import kotlinx.coroutines.runBlocking

private data class RestoreExistingFolder(
    val folderId: String,
    val name: String,
    val parentFolderId: String?,
)

private data class RestoreFolderPlan(
    val foldersToCreate: List<RestoreFolderToCreate>,
    val targetIdByOriginalId: Map<String, String>,
)

/** Historical list planner retained only as a regression oracle for folder naming tests. */
private object RestoreFolderPlanner {
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
        val usedNamesByParent = mutableMapOf<String?, MutableSet<String>>()
        existingFolders.forEach { folder ->
            usedNamesByParent.getOrPut(folder.parentFolderId) { linkedSetOf() } += normalize(folder.name)
        }
        val targetIds = linkedMapOf<String, String>()
        val result = ordered.map { original ->
            val targetId = idSource.newId()
            targetIds[original.folderId] = targetId
            val targetParentId = original.parentFolderId?.let(targetIds::get)
            val siblingNames = usedNamesByParent.getOrPut(targetParentId) { linkedSetOf() }
            val uniqueName = uniqueName(original.name, siblingNames)
            RestoreFolderToCreate(
                folderId = targetId,
                originalFolderId = original.folderId,
                name = uniqueName,
                normalizedName = normalize(uniqueName),
                parentFolderId = targetParentId,
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

class RestoreFolderPlannerTest {
    @Test
    fun indexedPlannerKeepsDeepHierarchyParentLinkedAndReconstructsExactSearchPath() = runBlocking {
        val depth = 10_000
        val staging = TestIndexedRestoreArea("deep-folder-test") {}
        repeat(depth) { ordinal ->
            staging.acceptFolder(
                ordinal,
                folder(
                    id = "folder-$ordinal",
                    name = "x",
                    parentId = if (ordinal == 0) null else "folder-${ordinal - 1}",
                ),
            )
        }

        val plan = RestoreIndexedFolderPlanner.plan(
            staging = staging,
            expectedFolderCount = depth,
            store = FakeRestoreStore(),
            idSource = sequentialIds(),
            cancellationSignal = NeverCancelRestore,
        )

        assertEquals(depth, staging.plannedFolderCount())
        assertEquals(depth, plan.plannedFoldersPage(-1, depth + 1).size)
        val expectedPath = List(depth) { "x" }.joinToString("/")
        assertEquals(expectedPath, staging.targetFolderSearchPath("folder-${depth - 1}"))
    }

    @Test
    fun indexedPlannerRejectsAPathBeyondTheFixedSearchBound() {
        val depth = (MAX_RESTORE_FOLDER_SEARCH_PATH_CHARACTERS / 2) + 2
        val staging = TestIndexedRestoreArea("overlong-folder-test") {}
        repeat(depth) { ordinal ->
            staging.acceptFolder(
                ordinal,
                folder(
                    id = "folder-$ordinal",
                    name = "x",
                    parentId = if (ordinal == 0) null else "folder-${ordinal - 1}",
                ),
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                RestoreIndexedFolderPlanner.plan(
                    staging = staging,
                    expectedFolderCount = depth,
                    store = FakeRestoreStore(),
                    idSource = sequentialIds(),
                    cancellationSignal = NeverCancelRestore,
                )
            }
        }
        assertEquals(
            (MAX_RESTORE_FOLDER_SEARCH_PATH_CHARACTERS + 1) / 2,
            staging.plannedFolderCount(),
        )
    }

    @Test
    fun identicalNamesInDifferentBranchesArePreserved() {
        val plan = RestoreFolderPlanner.plan(
            backupFolders = listOf(
                folder("work", "Work"),
                folder("work-year", "2024", "work"),
                folder("personal", "Personal"),
                folder("personal-year", "2024", "personal"),
            ),
            requiredFolderIds = setOf("work", "work-year", "personal", "personal-year"),
            existingFolders = listOf(
                RestoreExistingFolder("archive", "Archive", null),
                RestoreExistingFolder("archive-year", "2024", "archive"),
            ),
            idSource = sequentialIds(),
        )

        val workYear = plan.foldersToCreate.single { it.originalFolderId == "work-year" }
        val personalYear = plan.foldersToCreate.single { it.originalFolderId == "personal-year" }
        assertEquals("2024", workYear.name)
        assertEquals("2024", personalYear.name)
        assertNotEquals(workYear.parentFolderId, personalYear.parentFolderId)
    }

    @Test
    fun collisionsAreRenamedOnlyWithinTheSameParent() {
        val plan = RestoreFolderPlanner.plan(
            backupFolders = listOf(
                folder("first", "Reports"),
                folder("second", "reports"),
            ),
            requiredFolderIds = setOf("first", "second"),
            existingFolders = listOf(RestoreExistingFolder("existing", "Reports", null)),
            idSource = sequentialIds(),
        )

        assertEquals(
            listOf("Reports (restored)", "reports (restored 2)"),
            plan.foldersToCreate.map { it.name },
        )
    }

    private fun folder(
        id: String,
        name: String,
        parentId: String? = null,
    ) = BackupFolderRecord(
        folderId = id,
        name = name,
        parentFolderId = parentId,
        createdAtEpochMillis = 1,
        modifiedAtEpochMillis = 2,
    )

    private fun sequentialIds(): RestoreIdSource {
        var next = 0
        return RestoreIdSource { "restored-${++next}" }
    }
}
