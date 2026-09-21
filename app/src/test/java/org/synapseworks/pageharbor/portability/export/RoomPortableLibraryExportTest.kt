package org.synapseworks.pageharbor.portability.export

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.synapseworks.pageharbor.library.LibraryOperationGate

class RoomPortableLibraryExportTest {
    @Test
    fun `paged plan preserves hierarchy and unfiled documents`() = runBlocking {
        val records = FakeRecords(
            folders = listOf(
                PortableExportFolder("parent", "Parent", null),
                PortableExportFolder("child", "Child", "parent"),
                PortableExportFolder("empty", "Empty", null),
            ),
            documents = listOf(
                PortableExportDocumentRow(1, "nested", "Nested", "child"),
                PortableExportDocumentRow(2, "unfiled", "Loose", null),
            ),
        )

        val plan = PagedPortableExportPlanSource(records, pageSize = 1).capturePlan()

        assertEquals(records.folders, plan.folders)
        assertEquals(listOf("nested", "unfiled"), plan.documents.map { it.id })
        assertEquals("child", plan.documents.first().folderId)
        assertEquals(null, plan.documents.last().folderId)
        assertTrue(records.folderPageCalls > 1)
        assertTrue(records.documentPageCalls > 1)
    }

    @Test(expected = PortableExportException::class)
    fun `revision change rejects inconsistent plan`() {
        runBlocking {
            PagedPortableExportPlanSource(
                FakeRecords(
                    folders = emptyList(),
                    documents = emptyList(),
                    revisions = ArrayDeque(listOf(10L, 11L)),
                ),
            ).capturePlan()
        }
    }

    @Test
    fun `stable coordinator holds operation gate through planning and streaming`() = runBlocking {
        val gate = LibraryOperationGate()
        val destination = RecordingDestination()
        var plannedUnderGate = false
        var streamedUnderGate = false
        val coordinator = StablePortableLibraryExportCoordinator(
            planSource = PortableExportPlanSource {
                plannedUnderGate = gate.isOperationActive
                PortableExportPlan(
                    folders = emptyList(),
                    documents = listOf(PortableExportDocument("document", "Document", null)),
                )
            },
            pdfSource = PortableExportPdfSource { _, output ->
                streamedUnderGate = gate.isOperationActive
                output.write("%PDF-test".encodeToByteArray())
                PortableExportPdfWriteResult.WRITTEN
            },
            operationGate = gate,
        )

        val result = coordinator.export(destination)

        assertTrue(plannedUnderGate)
        assertTrue(streamedUnderGate)
        assertEquals(1, result.exportedDocumentCount)
        assertTrue(destination.paths.contains("RME Export/Unfiled/Document.pdf"))
        assertTrue(!gate.isOperationActive)
    }

    private class FakeRecords(
        val folders: List<PortableExportFolder>,
        private val documents: List<PortableExportDocumentRow>,
        private val revisions: ArrayDeque<Long> = ArrayDeque(listOf(7L, 7L)),
    ) : PortableExportRecordSource {
        var folderPageCalls = 0
        var documentPageCalls = 0

        override suspend fun libraryRevision(): Long = if (revisions.size > 1) {
            revisions.removeFirst()
        } else {
            revisions.first()
        }

        override suspend fun foldersPage(offset: Int, limit: Int): List<PortableExportFolder> {
            folderPageCalls += 1
            return folders.drop(offset).take(limit)
        }

        override suspend fun documentsPage(
            afterRowId: Long,
            limit: Int,
        ): List<PortableExportDocumentRow> {
            documentPageCalls += 1
            return documents.filter { it.rowId > afterRowId }.take(limit)
        }
    }

    private class RecordingDestination : PortableExportDestination {
        val paths = mutableSetOf<String>()

        override fun createDirectory(parentHandle: String?, displayName: String): String {
            return listOfNotNull(parentHandle, displayName).joinToString("/").also(paths::add)
        }

        override fun createFile(parentHandle: String, displayName: String, mimeType: String): String {
            return "$parentHandle/$displayName".also(paths::add)
        }

        override fun openOutput(fileHandle: String): OutputStream = ByteArrayOutputStream()

        override fun delete(handle: String) {
            paths.removeAll { path -> path == handle || path.startsWith("$handle/") }
        }
    }
}
