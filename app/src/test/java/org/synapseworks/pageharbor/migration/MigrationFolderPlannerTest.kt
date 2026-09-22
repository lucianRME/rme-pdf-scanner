package org.synapseworks.pageharbor.migration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MigrationFolderPlannerTest {
    @Test
    fun createsTrueHierarchyInPathOrder() {
        var nextId = 0
        val plan = MigrationFolderPlanner.plan(
            relativePath = listOf("Clients", "Acme", "2026"),
            existingFolders = emptyList(),
            newId = { "folder-${nextId++}" },
            nowMillis = 10L,
        )

        assertEquals(listOf("Clients", "Acme", "2026"), plan.foldersToCreate.map { it.name })
        assertNull(plan.foldersToCreate[0].parentFolderId)
        assertEquals("folder-0", plan.foldersToCreate[1].parentFolderId)
        assertEquals("folder-1", plan.foldersToCreate[2].parentFolderId)
        assertEquals("folder-2", plan.targetFolderId)
    }

    @Test
    fun sameNameInAnotherBranchIsPreservedAndReusedOnRetry() {
        val existing = listOf(
            folder("work", "Work", null),
            folder("work-year", "2024", "work"),
            folder("personal", "Personal", null),
        )
        val first = MigrationFolderPlanner.plan(
            relativePath = listOf("Personal", "2024", "Receipts"),
            existingFolders = existing,
            newId = sequenceOf("personal-year", "new-receipts").iterator()::next,
            nowMillis = 20L,
        )

        assertEquals(listOf("2024", "Receipts"), first.foldersToCreate.map { it.name })
        assertEquals("personal", first.foldersToCreate.first().parentFolderId)
        assertEquals("personal-year", first.foldersToCreate.last().parentFolderId)
        assertNotEquals("work-year", first.targetFolderId)

        val retried = MigrationFolderPlanner.plan(
            relativePath = listOf("Personal", "2024", "Receipts"),
            existingFolders = existing + first.foldersToCreate.map {
                MigrationExistingFolder(it.folderId, it.name, it.normalizedName, it.parentFolderId)
            },
            newId = { error("retry must reuse the existing hierarchy") },
            nowMillis = 30L,
        )

        assertEquals(first.targetFolderId, retried.targetFolderId)
        assertEquals(emptyList<Any>(), retried.foldersToCreate)
    }

    @Test
    fun emptyPathKeepsDocumentUnfiled() {
        val plan = MigrationFolderPlanner.plan(emptyList(), emptyList(), { "unused" }, 1L)

        assertNull(plan.targetFolderId)
        assertEquals(emptyList<Any>(), plan.foldersToCreate)
    }

    private fun folder(id: String, name: String, parentId: String?) = MigrationExistingFolder(
        folderId = id,
        name = name,
        normalizedName = name.lowercase(),
        parentFolderId = parentId,
    )
}
