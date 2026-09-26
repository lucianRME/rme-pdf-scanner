package org.synapseworks.pageharbor.backup.engine

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupScratchRecoveryTest {
    @Test
    fun workspaceSweepsRecognizedOrphansPreservesUnrelatedPathsAndSkipsActiveSession() =
        withRoot("rme-backup-session-recovery") { root ->
            val orphan = File(root, ".rme-backup-session-$BACKUP_ID-12345.tmp")
            val orphanScratch = File(orphan, "scratch/.rme-checksums-12345.tmp")
            assertTrue(orphanScratch.mkdirs())
            File(orphanScratch, "records.bin").writeText("orphan")
            File(orphan, "archive.zip").writeText("partial")
            val legacy = File(root, ".rme-backup-$BACKUP_ID-12345.zip").apply { writeText("partial") }
            val unrelatedFile = File(root, "keep.txt").apply { writeText("keep") }
            val unrelatedDirectory = File(root, ".rme-backup-session-not-owned.tmp").apply { mkdir() }

            val workspace = FileLibraryBackupWorkspace(root)

            assertFalse(orphan.exists())
            assertFalse(legacy.exists())
            assertTrue(unrelatedFile.isFile)
            assertTrue(unrelatedDirectory.isDirectory)

            val archive = workspace.createTemporaryArchive(BACKUP_ID)
            val scratch = workspace.scratchDirectoryFor(archive)
            File(scratch, ".rme-zip-central-12345.bin").writeText("active")

            FileLibraryBackupWorkspace(root)

            assertTrue(archive.isFile)
            assertTrue(scratch.isDirectory)
            assertTrue(workspace.deleteTemporaryArchive(archive))
            assertFalse(requireNotNull(archive.parentFile).exists())
            assertTrue(unrelatedFile.isFile)
            assertTrue(unrelatedDirectory.isDirectory)
        }

    @Test
    fun snapshotSpoolSweepRemovesOnlyRecognizedDirectChildrenAndSkipsActiveSpool() =
        withRoot("rme-snapshot-spool-recovery") { parent ->
            val orphan = File(parent, ".rme-backup-spool-12345.tmp")
            assertTrue(File(orphan, "entries").mkdirs())
            File(orphan, "entries/partial.jsonl").writeText("partial")
            val unrelated = File(parent, ".rme-backup-spool-user-data").apply { mkdir() }

            recoverStaleBackupSpools(parent)

            assertFalse(orphan.exists())
            assertTrue(unrelated.isDirectory)

            val active = createPrivateSpoolDirectory(parent)
            File(active, "active.bin").writeText("active")
            recoverStaleBackupSpools(parent)
            assertTrue(active.isDirectory)
            assertTrue(deleteSpoolDirectory(parent, active))
            assertFalse(active.exists())
        }

    @Test
    fun staleSweepNeverFollowsRecognizedSymlinkOutsideWorkspace() =
        withRoot("rme-backup-symlink-workspace") { root ->
            val outside = Files.createTempDirectory("rme-backup-outside").toFile()
            val sentinel = File(outside, "keep.txt").apply { writeText("keep") }
            val link = File(root, ".rme-backup-session-$BACKUP_ID-54321.tmp")
            try {
                try {
                    Files.createSymbolicLink(link.toPath(), outside.toPath())
                } catch (_: UnsupportedOperationException) {
                    return@withRoot
                } catch (_: SecurityException) {
                    return@withRoot
                }

                recoverStaleBackupSessions(root)

                assertTrue(Files.isSymbolicLink(link.toPath()))
                assertTrue(sentinel.isFile)
            } finally {
                runCatching { Files.deleteIfExists(link.toPath()) }
                outside.deleteRecursively()
            }
        }

    @Test
    fun captureCleanupFailureIsRetriedSurfacedAndPreservesCancellation() {
        var attempts = 0
        val original = LibraryBackupSnapshotException(
            LibraryBackupSnapshotFailure.INVALID_RECORD,
            "synthetic snapshot failure",
        )

        val wrapped = assertThrows(LibraryBackupSnapshotException::class.java) {
            throwAfterBackupCaptureCleanup(original) {
                attempts += 1
                false
            }
        }

        assertEquals(3, attempts)
        assertEquals(LibraryBackupSnapshotFailure.INVALID_RECORD, wrapped.failure)
        assertFalse(wrapped.cleanupSucceeded)
        assertSame(original, wrapped.cause)
        assertTrue(original.suppressed.any { it is IOException })

        val cancellation = CancellationException("synthetic cancellation")
        val propagated = assertThrows(CancellationException::class.java) {
            throwAfterBackupCaptureCleanup(
                failure = cancellation,
                initialCleanupFailure = IOException("synthetic close failure"),
                cleanup = { true },
            )
        }
        assertSame(cancellation, propagated)
        assertTrue(cancellation.suppressed.any { it is IOException })
    }

    private fun withRoot(prefix: String, block: (File) -> Unit) {
        val root = Files.createTempDirectory(prefix).toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private companion object {
        const val BACKUP_ID = "00000000-0000-4000-8000-000000000015"
    }
}
