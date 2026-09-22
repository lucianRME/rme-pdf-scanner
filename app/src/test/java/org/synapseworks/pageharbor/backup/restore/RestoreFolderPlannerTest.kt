package org.synapseworks.pageharbor.backup.restore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.synapseworks.pageharbor.backup.format.BackupFolderRecord

class RestoreFolderPlannerTest {
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
