package org.synapseworks.pageharbor.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderNavigationTest {
    private val folders = listOf(
        LibraryFolder("work", "Work"),
        LibraryFolder("receipts", "Receipts", parentFolderId = "work"),
        LibraryFolder("private", "Personal"),
        LibraryFolder("personal-receipts", "Receipts", parentFolderId = "private"),
    )

    @Test fun emptySearchKeepsTheSelectedParentScope() {
        assertEquals(listOf("receipts"), folderNavigationItems(folders, "work", " ").map { it.folder.id })
        assertEquals(listOf("work", "private"), folderNavigationItems(folders, null, "").map { it.folder.id })
    }

    @Test fun searchFindsNestedFoldersAndDisambiguatesDuplicateNames() {
        assertEquals(listOf("Personal / Receipts", "Work / Receipts"), folderNavigationItems(folders, null, "RECEIPTS").map { it.path })
        assertEquals(listOf("work", "receipts"), folderNavigationItems(folders, "private", "work").map { it.folder.id })
        assertTrue(folderNavigationItems(folders, null, "missing").isEmpty())
    }

    @Test fun inconsistentCyclesTerminateWithoutMutatingTheInput() {
        val cycle = listOf(LibraryFolder("a", "A", parentFolderId = "b"), LibraryFolder("b", "B", parentFolderId = "a"))
        assertEquals(2, folderNavigationItems(cycle, null, "a").size)
        assertEquals("b", cycle.first().parentFolderId)
    }
}
