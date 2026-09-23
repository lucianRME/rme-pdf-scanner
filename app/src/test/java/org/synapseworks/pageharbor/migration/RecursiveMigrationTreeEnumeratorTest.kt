package org.synapseworks.pageharbor.migration

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecursiveMigrationTreeEnumeratorTest {
    @Test
    fun recursivelyPreservesRelativeFoldersNaturalOrderAndUnknownMetadata() = runBlocking {
        val backend = FakeTreeBackend(
            mapOf(
                "root" to listOf(
                    directory("receipts", "Receipts"),
                    file("root-file", "z.pdf", size = null, modified = null),
                ),
                "receipts" to listOf(
                    directory("year", "2024"),
                    file("ten", "page 10.jpg", size = 10L, modified = 20L),
                    file("two", "page 2.jpg", size = 2L, modified = 3L),
                ),
                "year" to listOf(file("nested", "report.pdf", size = null, modified = null)),
            ),
        )

        val result = RecursiveMigrationTreeEnumerator(backend).discover("tree", 7)

        assertEquals(
            listOf("root-file", "two", "ten", "nested"),
            result.sources.map { it.id.removePrefix("content://fake/") },
        )
        assertEquals(emptyList<String>(), result.sources[0].relativeFolderPath)
        assertEquals(listOf("Receipts"), result.sources[1].relativeFolderPath)
        assertEquals(listOf("Receipts", "2024"), result.sources[3].relativeFolderPath)
        assertNull(result.sources[0].sizeBytes)
        assertNull(result.sources[3].modifiedAtMillis)
        assertEquals(7, result.sources.single { it.id.endsWith("two") }.platformAccessFlags)
        assertEquals(3, result.visitedDirectoryCount)
        assertFalse(result.wasCancelled)
        assertFalse(result.wasTruncated)
    }

    @Test
    fun providerFailureAndDirectoryCycleAreIsolated() = runBlocking {
        val backend = FakeTreeBackend(
            entries = mapOf(
                "root" to listOf(
                    directory("broken", "Broken"),
                    directory("loop", "Loop"),
                    file("good", "good.pdf"),
                ),
                "loop" to listOf(directory("root", "Back to root")),
            ),
            failedDirectories = setOf("broken"),
        )

        val result = RecursiveMigrationTreeEnumerator(backend).discover("tree", 1)

        assertEquals(listOf("content://fake/good"), result.sources.map(MigrationSource::id))
        assertEquals(1, result.unreadableDirectoryCount)
        assertEquals(3, result.visitedDirectoryCount)
        assertFalse(result.wasTruncated)
    }

    @Test
    fun cancellationAndBoundsStopTraversalDeterministically() = runBlocking {
        val entries = (1..20).map { file("file-$it", "File $it.pdf") }
        var progressFiles = 0
        val cancelled = RecursiveMigrationTreeEnumerator(FakeTreeBackend(mapOf("root" to entries)))
            .discover(
                treeReference = "tree",
                platformAccessFlags = 0,
                cancellationSignal = MigrationCancellationSignal { progressFiles >= 4 },
                progressListener = MigrationTreeDiscoveryProgressListener { files, _ ->
                    progressFiles = files
                },
            )
        val truncated = RecursiveMigrationTreeEnumerator(
            FakeTreeBackend(mapOf("root" to entries)),
            maximumEntries = 3,
        ).discover("tree", 0)

        assertEquals(4, cancelled.sources.size)
        assertTrue(cancelled.wasCancelled)
        assertEquals(3, truncated.sources.size)
        assertTrue(truncated.wasTruncated)
    }

    @Test
    fun unsafeOrMissingFolderNamesBecomeSafeStableSegments() {
        val unsafe = safeTreeFolderSegment(" ../A/B\\C\u0000 ", "folder-id")
        val missingOne = safeTreeFolderSegment(null, "folder-id")
        val missingTwo = safeTreeFolderSegment(null, "folder-id")

        assertFalse('/' in unsafe)
        assertFalse('\\' in unsafe)
        assertFalse(unsafe.any(Char::isISOControl))
        assertEquals(missingOne, missingTwo)
        assertTrue(missingOne.startsWith("Folder-"))
    }

    private class FakeTreeBackend(
        private val entries: Map<String, List<MigrationTreeEntry>>,
        private val failedDirectories: Set<String> = emptySet(),
    ) : MigrationTreeBackend {
        override fun rootDocumentId(treeReference: String): String = "root"

        override fun children(
            treeReference: String,
            directoryDocumentId: String,
        ): List<MigrationTreeEntry> {
            if (directoryDocumentId in failedDirectories) error("provider failure")
            return entries[directoryDocumentId].orEmpty()
        }
    }

    private fun directory(id: String, name: String) = MigrationTreeEntry(
        documentId = id,
        resourceReference = "content://fake/$id",
        displayName = name,
        declaredContentType = "vnd.android.document/directory",
        isDirectory = true,
        sizeBytes = null,
        modifiedAtMillis = null,
    )

    private fun file(
        id: String,
        name: String,
        size: Long? = 10L,
        modified: Long? = 20L,
    ) = MigrationTreeEntry(
        documentId = id,
        resourceReference = "content://fake/$id",
        displayName = name,
        declaredContentType = if (name.endsWith(".pdf")) "application/pdf" else "image/jpeg",
        isDirectory = false,
        sizeBytes = size,
        modifiedAtMillis = modified,
    )
}
