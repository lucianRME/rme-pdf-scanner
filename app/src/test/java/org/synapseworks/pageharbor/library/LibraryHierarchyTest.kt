package org.synapseworks.pageharbor.library

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryHierarchyTest {
    @Test
    fun breadcrumbPreservesRootToLeafOrder() {
        val folders = listOf(
            LibraryFolder(id = "leaf", name = "April", parentFolderId = "child"),
            LibraryFolder(id = "root", name = "Projects"),
            LibraryFolder(id = "child", name = "Receipts", parentFolderId = "root"),
        )

        assertEquals(
            listOf("root", "child", "leaf"),
            folders.breadcrumbTo("leaf").map(LibraryFolder::id),
        )
    }

    @Test
    fun breadcrumbStopsSafelyAtCorruptCycle() {
        val folders = listOf(
            LibraryFolder(id = "first", name = "First", parentFolderId = "second"),
            LibraryFolder(id = "second", name = "Second", parentFolderId = "first"),
        )

        assertEquals(
            listOf("second", "first"),
            folders.breadcrumbTo("first").map(LibraryFolder::id),
        )
    }
}
