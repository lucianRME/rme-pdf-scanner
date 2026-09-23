package org.synapseworks.pageharbor.portability.export

import java.io.OutputStream
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LargeLibraryPortableExportTest {
    @Test
    fun defaultPlanSourceReadsThousandDocumentsInBoundedDatabasePages() = runBlocking {
        val records = LargePagedRecords(
            folders = List(300) { index ->
                PortableExportFolder(
                    id = "folder-$index",
                    name = "Folder $index",
                    parentId = null,
                )
            },
            documents = List(1_000) { index ->
                PortableExportDocumentRow(
                    rowId = index + 1L,
                    documentId = "document-${index.toString().padStart(4, '0')}",
                    title = "Document $index",
                    folderId = "folder-${index % 300}",
                )
            },
        )

        val plan = PagedPortableExportPlanSource(records).capturePlan()

        assertEquals(300, plan.folders.size)
        assertEquals(1_000, plan.documents.size)
        assertEquals(listOf(256, 44), records.folderBatchSizes)
        assertEquals(listOf(256, 256, 256, 232), records.documentBatchSizes)
        assertTrue(records.requestedPageSizes.all { it == 256 })
        assertEquals(listOf(-1L, 256L, 512L, 768L), records.documentCursors)
        assertEquals(2, records.revisionCalls)
        assertEquals("document-0000", plan.documents.first().id)
        assertEquals("document-0999", plan.documents.last().id)
    }

    @Test
    fun thousandCollidingPortableNamesExportOnePdfStreamAtATime() = runBlocking {
        val plan = PortableExportPlan(
            folders = emptyList(),
            documents = List(1_000) { index ->
                PortableExportDocument(
                    id = "document-${index.toString().padStart(4, '0')}",
                    title = "CON",
                    folderId = null,
                )
            },
        )
        val destination = CountingExportDestination()
        var activeSources = 0
        var maximumConcurrentSources = 0
        var sourceCalls = 0
        val pdfSource = PortableExportPdfSource { _, output ->
            sourceCalls += 1
            activeSources += 1
            maximumConcurrentSources = maxOf(maximumConcurrentSources, activeSources)
            try {
                output.write(PDF_HEADER)
                output.write(PDF_BODY)
                PortableExportPdfWriteResult.WRITTEN
            } finally {
                activeSources -= 1
            }
        }
        var progressCalls = 0
        var lastCompleted = -1
        val progress = PortableExportProgressListener { completed, total, _ ->
            assertEquals(1_000, total)
            assertTrue(completed >= lastCompleted)
            lastCompleted = completed
            progressCalls += 1
        }

        val result = PortableLibraryExportEngine(pdfSource).export(plan, destination, progress)

        assertEquals(1_000, result.exportedDocumentCount)
        assertTrue(result.failures.isEmpty())
        assertEquals(1_000, sourceCalls)
        assertEquals(0, activeSources)
        assertEquals(1, maximumConcurrentSources)
        assertEquals(1_000, destination.openCount)
        assertEquals(0, destination.activeOutputs)
        assertEquals(1, destination.maximumConcurrentOutputs)
        assertTrue(destination.maximumWriteRequestBytes <= PDF_HEADER.size)
        assertEquals(1_000L * (PDF_HEADER.size + PDF_BODY.size), destination.totalBytes)
        assertEquals(2_000, progressCalls)
        assertEquals(1_000, lastCompleted)
        assertTrue(destination.deletedHandles.isEmpty())

        val names = destination.fileHandles.map { it.substringAfterLast('/') }
        assertEquals(1_000, names.size)
        assertEquals(1_000, names.map { it.lowercase(Locale.ROOT) }.distinct().size)
        assertTrue("_CON.pdf" in names)
        assertTrue("_CON (1000).pdf" in names)
        assertFalse(names.any { UNSAFE_FILENAME_CHARACTERS.containsMatchIn(it) })
    }
}

private class LargePagedRecords(
    private val folders: List<PortableExportFolder>,
    private val documents: List<PortableExportDocumentRow>,
) : PortableExportRecordSource {
    val folderBatchSizes = mutableListOf<Int>()
    val documentBatchSizes = mutableListOf<Int>()
    val requestedPageSizes = mutableListOf<Int>()
    val documentCursors = mutableListOf<Long>()
    var revisionCalls: Int = 0
        private set

    override suspend fun libraryRevision(): Long {
        revisionCalls += 1
        return 42L
    }

    override suspend fun foldersPage(offset: Int, limit: Int): List<PortableExportFolder> {
        requestedPageSizes += limit
        return folders.drop(offset).take(limit).also { folderBatchSizes += it.size }
    }

    override suspend fun documentsPage(
        afterRowId: Long,
        limit: Int,
    ): List<PortableExportDocumentRow> {
        requestedPageSizes += limit
        documentCursors += afterRowId
        return documents.asSequence()
            .dropWhile { it.rowId <= afterRowId }
            .take(limit)
            .toList()
            .also { documentBatchSizes += it.size }
    }
}

private class CountingExportDestination : PortableExportDestination {
    val fileHandles = mutableListOf<String>()
    val deletedHandles = mutableListOf<String>()
    var openCount: Int = 0
        private set
    var activeOutputs: Int = 0
        private set
    var maximumConcurrentOutputs: Int = 0
        private set
    var maximumWriteRequestBytes: Int = 0
        private set
    var totalBytes: Long = 0L
        private set

    override fun createDirectory(parentHandle: String?, displayName: String): String =
        listOfNotNull(parentHandle, displayName).joinToString("/")

    override fun createFile(parentHandle: String, displayName: String, mimeType: String): String =
        "$parentHandle/$displayName".also(fileHandles::add)

    override fun openOutput(fileHandle: String): OutputStream {
        check(fileHandle in fileHandles)
        openCount += 1
        activeOutputs += 1
        maximumConcurrentOutputs = maxOf(maximumConcurrentOutputs, activeOutputs)
        return object : OutputStream() {
            private var closed = false

            override fun write(value: Int) {
                check(!closed)
                maximumWriteRequestBytes = maxOf(maximumWriteRequestBytes, 1)
                totalBytes += 1
            }

            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                check(!closed)
                maximumWriteRequestBytes = maxOf(maximumWriteRequestBytes, length)
                totalBytes += length
            }

            override fun close() {
                if (!closed) {
                    closed = true
                    activeOutputs -= 1
                }
            }
        }
    }

    override fun delete(handle: String) {
        deletedHandles += handle
    }
}

private val PDF_HEADER = "%PDF-1.7\n".toByteArray()
private val PDF_BODY = "%%EOF\n".toByteArray()
private val UNSAFE_FILENAME_CHARACTERS = Regex("[\\\\/:*?\"<>|]")
