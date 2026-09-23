package org.synapseworks.pageharbor.portability.export

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortableLibraryExportEngineTest {
    @Test
    fun `exports nested folders unfiled documents and deterministic collisions`() = runBlocking {
        val destination = RecordingDestination()
        val engine = PortableLibraryExportEngine(
            PortableExportPdfSource { id, output ->
                output.write("pdf:$id".toByteArray())
                PortableExportPdfWriteResult.WRITTEN
            },
        )

        val result = engine.export(
            PortableExportPlan(
                folders = listOf(
                    PortableExportFolder("root", "Work", null),
                    PortableExportFolder("child", "2026", "root"),
                ),
                documents = listOf(
                    PortableExportDocument("a", "Contract", "child"),
                    PortableExportDocument("b", "contract", "child"),
                    PortableExportDocument("c", "Notes", null),
                ),
            ),
            destination,
        )

        assertEquals(3, result.exportedDocumentCount)
        assertTrue(result.failures.isEmpty())
        assertTrue(destination.paths.contains("RME Export/Work/2026/Contract.pdf"))
        assertTrue(destination.paths.contains("RME Export/Work/2026/contract (2).pdf"))
        assertTrue(destination.paths.contains("RME Export/Unfiled/Notes.pdf"))
    }

    @Test
    fun `failed document deletes its partial output and continues`() = runBlocking {
        val destination = RecordingDestination()
        val engine = PortableLibraryExportEngine(
            PortableExportPdfSource { id, output ->
                output.write(id.toByteArray())
                if (id == "bad") {
                    PortableExportPdfWriteResult.WRITE_FAILED
                } else {
                    PortableExportPdfWriteResult.WRITTEN
                }
            },
        )

        val result = engine.export(
            PortableExportPlan(
                folders = emptyList(),
                documents = listOf(
                    PortableExportDocument("bad", "Broken", null),
                    PortableExportDocument("good", "Good", null),
                ),
            ),
            destination,
        )

        assertEquals(1, result.exportedDocumentCount)
        assertEquals(listOf("bad"), result.failures.map(PortableExportFailure::documentId))
        assertFalse(destination.paths.contains("RME Export/Unfiled/Broken.pdf"))
        assertTrue(destination.paths.contains("RME Export/Unfiled/Good.pdf"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects cyclic hierarchy before creating destination`() {
        runBlocking {
            PortableLibraryExportEngine(
                PortableExportPdfSource { _, _ -> PortableExportPdfWriteResult.WRITTEN },
            ).export(
                PortableExportPlan(
                    folders = listOf(
                        PortableExportFolder("a", "A", "b"),
                        PortableExportFolder("b", "B", "a"),
                    ),
                    documents = emptyList(),
                ),
                RecordingDestination(),
            )
        }
    }

    @Test
    fun `failed nested directory never flattens its document into Unfiled`() = runBlocking {
        val destination = RecordingDestination(failedDirectoryName = "Blocked")
        val engine = PortableLibraryExportEngine(
            PortableExportPdfSource { _, _ -> PortableExportPdfWriteResult.WRITTEN },
        )

        val result = engine.export(
            PortableExportPlan(
                folders = listOf(PortableExportFolder("blocked", "Blocked", null)),
                documents = listOf(PortableExportDocument("document", "Private", "blocked")),
            ),
            destination,
        )

        assertEquals(0, result.exportedDocumentCount)
        assertEquals(
            PortableExportFailureReason.DESTINATION_UNAVAILABLE,
            result.failures.single().reason,
        )
        assertFalse(destination.paths.any { it.contains("Unfiled/Private.pdf") })
    }

    @Test
    fun `source failure deletes its partial file and is reported distinctly`() = runBlocking {
        val destination = RecordingDestination()
        val engine = PortableLibraryExportEngine(
            PortableExportPdfSource { _, output ->
                output.write("partial".toByteArray())
                PortableExportPdfWriteResult.SOURCE_UNAVAILABLE
            },
        )

        val result = engine.export(
            PortableExportPlan(
                folders = emptyList(),
                documents = listOf(PortableExportDocument("missing", "Missing", null)),
            ),
            destination,
        )

        assertEquals(PortableExportFailureReason.SOURCE_UNAVAILABLE, result.failures.single().reason)
        assertFalse(destination.paths.contains("RME Export/Unfiled/Missing.pdf"))
    }

    @Test
    fun `cancellation deletes the entire partial export tree`() = runBlocking {
        val destination = RecordingDestination()
        val engine = PortableLibraryExportEngine(
            PortableExportPdfSource { _, output ->
                output.write("partial".toByteArray())
                throw CancellationException("cancel")
            },
        )

        try {
            engine.export(
                PortableExportPlan(
                    folders = emptyList(),
                    documents = listOf(PortableExportDocument("cancelled", "Cancelled", null)),
                ),
                destination,
            )
            throw AssertionError("Expected cancellation")
        } catch (_: CancellationException) {
            // Expected.
        }

        assertTrue(destination.paths.isEmpty())
    }

    private class RecordingDestination(
        private val failedDirectoryName: String? = null,
    ) : PortableExportDestination {
        private val outputByHandle = mutableMapOf<String, ByteArrayOutputStream>()
        val paths = mutableSetOf<String>()

        override fun createDirectory(parentHandle: String?, displayName: String): String? {
            if (displayName == failedDirectoryName) return null
            val handle = listOfNotNull(parentHandle, displayName).joinToString("/")
            paths += handle
            return handle
        }

        override fun createFile(parentHandle: String, displayName: String, mimeType: String): String {
            val handle = "$parentHandle/$displayName"
            paths += handle
            outputByHandle[handle] = ByteArrayOutputStream()
            return handle
        }

        override fun openOutput(fileHandle: String): OutputStream? = outputByHandle[fileHandle]

        override fun delete(handle: String) {
            paths.removeAll { path -> path == handle || path.startsWith("$handle/") }
            outputByHandle.keys.removeAll { path -> path == handle || path.startsWith("$handle/") }
        }
    }
}
