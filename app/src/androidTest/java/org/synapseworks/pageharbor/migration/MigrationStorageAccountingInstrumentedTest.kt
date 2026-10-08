package org.synapseworks.pageharbor.migration

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.synapseworks.pageharbor.library.LibraryDatabase
import org.synapseworks.pageharbor.library.LibraryFileStore
import org.synapseworks.pageharbor.library.LibraryOperationGate
import org.synapseworks.pageharbor.library.duplicate.DuplicateKind

@RunWith(AndroidJUnit4::class)
class MigrationStorageAccountingInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun eightPageSevenMibPdfMeasuresPersistentAndTemporaryBytesSeparately() = runBlocking {
        val runId = UUID.randomUUID().toString()
        val root = File(context.cacheDir, "migration-storage-$runId")
        val sourceRoot = File(root, "external-source")
        val internalRoot = File(root, "rme-internal")
        val libraryRoot = File(internalRoot, "library")
        val executionStaging = File(internalRoot, "execution-staging")
        val previewStaging = File(internalRoot, "preview-staging")
        val databaseName = "migration-storage-$runId.db"
        assertTrue(sourceRoot.mkdirs())
        assertTrue(internalRoot.mkdirs())
        context.deleteDatabase(databaseName)

        val baselineDatabase = Room.databaseBuilder(
            context,
            LibraryDatabase::class.java,
            databaseName,
        ).build()
        baselineDatabase.openHelper.writableDatabase
            .query("PRAGMA wal_checkpoint(FULL)").use { cursor -> cursor.moveToFirst() }
        val databaseBytesBefore = databaseBytes(databaseName)
        baselineDatabase.close()

        val database = Room.databaseBuilder(
            context,
            LibraryDatabase::class.java,
            databaseName,
        ).build()
        val fileStore = LibraryFileStore(context, libraryRoot)
        val operationGate = LibraryOperationGate()
        val source = File(sourceRoot, "representative-eight-page.pdf")
        writeRepresentativePdf(source, pageCount = 8, targetBytes = REPRESENTATIVE_SOURCE_BYTES)
        val sourceHashBefore = source.sha256()
        val migrationSource = MigrationSource(
            id = source.absolutePath,
            displayName = "representative-eight-page.pdf",
            declaredContentType = MigrationContentType.PDF.mimeType,
            sizeBytes = source.length(),
            modifiedAtMillis = source.lastModified(),
            discoveryIdentity = "representative-pdf",
        )
        val document = MigrationDocumentPlan(
            id = "representative-document",
            suggestedTitle = "Representative document",
            relativeFolderPath = emptyList(),
            sources = listOf(
                InspectedMigrationSource(
                    source = migrationSource,
                    contentType = MigrationContentType.PDF,
                    pageCount = 8,
                ),
            ),
            grouping = MigrationDocumentGrouping.SINGLE_PDF,
            pages = List(8) { index ->
                MigrationPlannedPage(
                    id = "representative-page-$index",
                    sourceDiscoveryId = migrationSource.discoveryIdentity,
                    sourcePageIndex = index,
                    plannedIndex = index,
                    factualPageNumber = index + 1,
                    orderEvidence = MigrationEvidenceSource.PDF_PAGE_TREE,
                )
            },
        )
        val initialPreview = MigrationPreview(
            documents = listOf(document),
            rejectedSources = emptyList(),
            storageEstimate = MigrationStorageEstimate(
                knownSourceBytes = source.length(),
                unknownSizeSourceCount = 0,
            ),
            inspectedSourceCount = 1,
            requestedSourceCount = 1,
            wasCancelled = false,
            requestedSources = listOf(migrationSource),
        )
        val sourceAccess = MigrationSourceAccess { FileInputStream(source) }
        val preview = RoomMigrationDuplicateAnalyzer(
            storageCapacity = MigrationStorageCapacity { Long.MAX_VALUE },
            candidates = MigrationDuplicateCandidateSnapshot { emptyList() },
            stager = AndroidMigrationDocumentStager(previewStaging, sourceAccess),
        ).analyze(initialPreview, NeverCancelMigration, MigrationBatchProgressListener { })
        val accounting = migrationBatchStorageEstimate(preview)
        val measuredPreview = preview.duplicateDocuments.single()

        assertEquals(8, measuredPreview.analyzedPageCount)
        assertEquals(DuplicateKind.DIFFERENT, measuredPreview.duplicateKind)
        assertEquals(source.length(), measuredPreview.measuredOriginalAssetBytes)
        assertTrue(requireNotNull(measuredPreview.measuredPageBytes) > 0L)
        assertTrue(previewStaging.listFiles().isNullOrEmpty())

        var measuredExecutionWorkspaceBytes = 0L
        val delegateStager = AndroidMigrationDocumentStager(executionStaging, sourceAccess)
        val measuringStager = object : MigrationDocumentStager {
            override suspend fun stage(
                plan: MigrationDocumentPlan,
                context: MigrationPublicationContext,
            ): MigrationStagingResult = delegateStager.stage(plan, context).also { result ->
                if (result is MigrationStagingResult.Ready) {
                    measuredExecutionWorkspaceBytes = maxOf(
                        measuredExecutionWorkspaceBytes,
                        result.document.workspace.directoryBytes(),
                    )
                }
            }

            override fun cleanAbandonedWorkspaces() = delegateStager.cleanAbandonedWorkspaces()
        }
        val keepSampling = AtomicBoolean(true)
        var sampledPeakTemporaryBytes = 0L
        val sampler = thread(start = true, name = "migration-storage-sampler") {
            while (keepSampling.get()) {
                sampledPeakTemporaryBytes = maxOf(
                    sampledPeakTemporaryBytes,
                    internalRoot.transientBytes(),
                )
                Thread.sleep(2L)
            }
        }

        try {
            val executor = RoomVerifiedMigrationExecutor(
                context = context,
                sourceAccess = sourceAccess,
                sourceRevalidator = MigrationSourceRevalidator { inspected, _ ->
                    MigrationSourceRevalidation(
                        sourceDiscoveryId = inspected.source.discoveryIdentity,
                        status = MigrationSourceRevalidationStatus.UNCHANGED,
                        currentSizeBytes = source.length(),
                        currentModifiedAtMillis = source.lastModified(),
                        currentPageCount = inspected.pageCount,
                    )
                },
                journalMetadata = MigrationJournalMetadata(
                    sourceKind = MigrationBatchSourceKind.FILE_URIS.name,
                    sourceRootUri = null,
                    sourceGrantFlags = 0,
                ),
                dao = database.libraryDao(),
                fileStore = fileStore,
                storageCapacity = MigrationStorageCapacity { Long.MAX_VALUE },
                operationGate = operationGate,
                stager = measuringStager,
            )
            val report = executor.execute(
                plan = preview,
                decisions = MigrationExecutionDecisions(),
                cancellationSignal = NeverCancelMigration,
                progressListener = MigrationBatchProgressListener { },
            )
            assertEquals(1, report.publishedCount)
        } finally {
            keepSampling.set(false)
            sampler.join()
        }

        val dao = database.libraryDao()
        val saved = dao.activeDocumentsPage(-1L, 2).single()
        val originalPdfBytes = dao.sourceAssets(saved.documentId).single().byteCount
        val derivedPageBytes = dao.pages(saved.documentId).sumOf {
            requireNotNull(it.sourceByteCount)
        }
        val thumbnailBytes = requireNotNull(
            fileStore.resolve(requireNotNull(saved.thumbnailRelativePath)),
        ).length()
        database.openHelper.writableDatabase
            .query("PRAGMA wal_checkpoint(FULL)").use { cursor -> cursor.moveToFirst() }
        val databaseBytesAfter = databaseBytes(databaseName)
        val databaseContribution = (databaseBytesAfter - databaseBytesBefore).coerceAtLeast(0L)
        val fileStoreBytes = libraryRoot.directoryBytes()
        val persistentDelta = fileStoreBytes + databaseContribution
        val stagingResidueBytes = internalRoot.transientBytes()

        assertEquals(REPRESENTATIVE_SOURCE_BYTES, source.length())
        assertEquals(source.length(), originalPdfBytes)
        assertEquals(8, saved.pageCount)
        assertTrue(derivedPageBytes > 0L)
        assertTrue(thumbnailBytes in 1 until derivedPageBytes)
        assertTrue(persistentDelta >= originalPdfBytes + derivedPageBytes + thumbnailBytes)
        assertTrue(persistentDelta < 150L * 1024L * 1024L)
        assertTrue(accounting.estimatedPersistentBytes < 150L * 1024L * 1024L)
        assertTrue(measuredExecutionWorkspaceBytes >= originalPdfBytes + derivedPageBytes)
        assertTrue(sampledPeakTemporaryBytes >= measuredExecutionWorkspaceBytes)
        assertEquals(0L, stagingResidueBytes)
        assertEquals(sourceHashBefore, source.sha256())

        println(
            "MIGRATION_STORAGE source_bytes=${source.length()} pages=8 " +
                "estimated_persistent_bytes=${accounting.estimatedPersistentBytes} " +
                "required_free_bytes=${accounting.requiredFreeSpace.requiredBytesLowerBound} " +
                "safety_margin_bytes=${accounting.requiredFreeSpace.safetyMarginBytes} " +
                "original_pdf_bytes=$originalPdfBytes derived_page_bytes=$derivedPageBytes " +
                "thumbnail_bytes=$thumbnailBytes database_delta_bytes=$databaseContribution " +
                "persistent_delta_bytes=$persistentDelta " +
                "peak_workspace_bytes=$measuredExecutionWorkspaceBytes " +
                "sampled_peak_temporary_bytes=$sampledPeakTemporaryBytes staging_residue_bytes=0",
        )

        database.close()
        context.deleteDatabase(databaseName)
        root.deleteRecursively()
        Unit
    }

    private fun writeRepresentativePdf(destination: File, pageCount: Int, targetBytes: Long) {
        val document = PdfDocument()
        try {
            repeat(pageCount) { pageIndex ->
                val page = document.startPage(
                    PdfDocument.PageInfo.Builder(1_200, 1_600, pageIndex + 1).create(),
                )
                page.canvas.drawColor(Color.WHITE)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(25, 45, 75)
                    textSize = 42f
                }
                page.canvas.drawText("Synthetic migration page ${pageIndex + 1}", 72f, 100f, paint)
                paint.strokeWidth = 2f
                repeat(80) { line ->
                    paint.color = Color.rgb(
                        (40 + pageIndex * 13 + line) % 220,
                        (70 + line * 3) % 220,
                        (90 + line * 5) % 220,
                    )
                    val y = 150f + line * 16f
                    page.canvas.drawLine(72f, y, 1_128f, y + (line % 5), paint)
                }
                document.finishPage(page)
            }
            FileOutputStream(destination).use(document::writeTo)
        } finally {
            document.close()
        }
        padPdfBeforeStartXref(destination, targetBytes)
        assertEquals(targetBytes, destination.length())
    }

    private fun padPdfBeforeStartXref(file: File, targetBytes: Long) {
        val original = file.readBytes()
        require(targetBytes >= original.size)
        val marker = "startxref".encodeToByteArray()
        val markerIndex = original.lastIndexOf(marker)
        require(markerIndex > 0)
        val paddingBytes = targetBytes - original.size
        val temporary = File(file.parentFile, "${file.name}.padding")
        FileOutputStream(temporary).use { output ->
            output.write(original, 0, markerIndex)
            var remaining = paddingBytes
            val comment = ByteArray(100) { ' '.code.toByte() }.apply {
                this[0] = '%'.code.toByte()
                this[lastIndex] = '\n'.code.toByte()
            }
            while (remaining >= comment.size) {
                output.write(comment)
                remaining -= comment.size
            }
            if (remaining > 0L) output.write(ByteArray(remaining.toInt()) { '\n'.code.toByte() })
            output.write(original, markerIndex, original.size - markerIndex)
        }
        check(file.delete() && temporary.renameTo(file))
    }

    private fun ByteArray.lastIndexOf(needle: ByteArray): Int {
        for (index in size - needle.size downTo 0) {
            if (needle.indices.all { offset -> this[index + offset] == needle[offset] }) return index
        }
        return -1
    }

    private fun databaseBytes(name: String): Long {
        val database = context.getDatabasePath(name)
        return listOf(database, File("${database.path}-wal"), File("${database.path}-shm"))
            .sumOf { file -> if (file.isFile) file.length() else 0L }
    }

    private fun File.transientBytes(): Long = when {
        !exists() -> 0L
        isFile -> if (parentFile?.name?.contains("staging") == true) length() else 0L
        name.contains("staging") -> directoryBytes()
        else -> listFiles().orEmpty().sumOf { child -> child.transientBytes() }
    }

    private fun File.directoryBytes(): Long = when {
        isFile -> length()
        isDirectory -> listFiles().orEmpty().sumOf { child -> child.directoryBytes() }
        else -> 0L
    }

    private fun File.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(readBytes())
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private companion object {
        const val REPRESENTATIVE_SOURCE_BYTES = 7_507_804L
    }
}
