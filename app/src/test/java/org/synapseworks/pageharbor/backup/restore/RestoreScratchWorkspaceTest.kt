package org.synapseworks.pageharbor.backup.restore

import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreScratchWorkspaceTest {
    @Test
    fun verifiedStagingSweepDeletesOnlyUnjournaledManagedOperations() {
        val parent = Files.createTempDirectory("rme-restore-staging-sweep").toFile()
        try {
            val root = parent.resolve("verified-restore-staging").apply { check(mkdirs()) }
            val retainedId = "11111111-1111-4111-8111-111111111111"
            val orphanId = "22222222-2222-4222-8222-222222222222"
            val retained = root.resolve(retainedId).apply { check(mkdirs()) }
            val orphan = root.resolve(orphanId).apply { check(mkdirs()) }
            val unmanaged = root.resolve("owner-note").apply { check(mkdirs()) }
            retained.resolve("asset.bin").writeBytes(byteArrayOf(1))
            orphan.resolve("plaintext.bin").writeBytes(byteArrayOf(2))
            unmanaged.resolve("keep.bin").writeBytes(byteArrayOf(3))

            assertTrue(FileRestoreStagingWorkspace(root).discardOrphans(setOf(retainedId)))

            assertTrue(retained.isDirectory)
            assertFalse(orphan.exists())
            assertTrue(unmanaged.isDirectory)
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun archiveSweepDeletesOnlyStrictlyNamedPrivateCopies() {
        val parent = Files.createTempDirectory("rme-restore-archive-sweep").toFile()
        try {
            val root = parent.resolve("restore-archive-staging")
            val workspace = FileRestoreArchiveWorkspace(root)
            val first = workspace.create().apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val second = workspace.create().apply { writeBytes(byteArrayOf(4, 5, 6)) }
            val unmanaged = root.resolve("do-not-delete.zip").apply { writeBytes(byteArrayOf(7)) }

            assertTrue(workspace.discardOrphans())

            assertFalse(first.exists())
            assertFalse(second.exists())
            assertTrue(unmanaged.isFile)
        } finally {
            parent.deleteRecursively()
        }
    }
}
