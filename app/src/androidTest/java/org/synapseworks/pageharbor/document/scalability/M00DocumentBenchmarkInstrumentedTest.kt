package org.synapseworks.pageharbor.document.scalability

import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import androidx.core.content.FileProvider
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.synapseworks.pageharbor.PageHarborSessionViewModel
import org.synapseworks.pageharbor.backup.engine.*
import org.synapseworks.pageharbor.backup.format.BackupProducer
import org.synapseworks.pageharbor.backup.restore.*
import org.synapseworks.pageharbor.document.*
import org.synapseworks.pageharbor.document.importing.*
import org.synapseworks.pageharbor.document.searchablepdf.*
import org.synapseworks.pageharbor.document.session.*
import org.synapseworks.pageharbor.image.DocumentFilter
import org.synapseworks.pageharbor.library.*
import org.synapseworks.pageharbor.ocr.*

/** SYNTHETIC ONLY. Opt-in physical benchmark in an application ID separate from real libraries. */
class M00DocumentBenchmarkInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val reports = JSONArray()

    @Test fun requiredDocumentSizesThroughRealLocalPipelines() = runBlocking {
        assertTrue("Use the isolated M00 package", context.packageName.endsWith(".m00benchmark"))
        PDFBoxResourceLoader.init(context)
        val sizes = InstrumentationRegistry.getArguments().getString("m00Sizes")
            ?.split(',')?.map(String::toInt) ?: listOf(1, 10, 20, 21, 50, 100, 200, 500, 1000)
        for (count in sizes) {
            instrumentation.sendStatus(0, Bundle().apply { putString("m00_start_pages", count.toString()) })
            val report = benchmark(count)
            reports.put(report)
            File(context.filesDir, "m00-benchmark-results.json").writeText(reports.toString(2))
            instrumentation.sendStatus(0, Bundle().apply { putString("m00_result", report.toString()) })
        }
    }

    private suspend fun benchmark(count: Int): JSONObject {
        val token = UUID.randomUUID().toString()
        val root = File(context.cacheDir, "shared-pdfs/m00-$token").apply { mkdirs() }
        val source = File(root, "synthetic.pdf")
        val store = LibraryFileStore(context, File(context.filesDir, "document-library/m00-$token"))
        val restoredStore = LibraryFileStore(context, File(context.filesDir, "document-library/m00-restored-$token"))
        val databaseName = "m00-$token.db"
        val restoredName = "m00-restored-$token.db"
        var database = Room.databaseBuilder(context, LibraryDatabase::class.java, databaseName).build()
        val restoredDatabase = Room.databaseBuilder(context, LibraryDatabase::class.java, restoredName).build()
        var repository = LibraryRepository(context, database.libraryDao(), store)
        val restoredRepository = LibraryRepository(context, restoredDatabase.libraryDao(), restoredStore)
        val session = PageHarborSessionViewModel()
        val stages = JSONObject()
        val sampler = Sampler().apply { start() }
        val started = SystemClock.elapsedRealtime()
        var normal: File? = null
        var searchable: SearchablePdfPreparedExport.Ready? = null
        var coordinator: LocalSearchablePdfExportCoordinator? = null
        var artifact: VerifiedLibraryBackupArtifact? = null
        try {
            fixture(source, count)
            val originalDigest = digest(source)
            val sourceUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", source)
            var phase = SystemClock.elapsedRealtime()
            assertTrue(session.beginImportRequest(DocumentImportOrigin.PICKER))
            val importer = DocumentImportProcessor(context) { resource ->
                session.registerPendingAcquisitionResources(listOf(resource)) == PendingResourceRegistrationResult.Registered
            }
            val prepared = importer.prepare(listOf(sourceUri), DocumentSourceCategory.SELECTED_IMAGE, session.remainingImportPageCapacity())
            assertTrue("Import $count", prepared is DocumentImportPreparationResult.Success)
            session.completeImportRequest(prepared as DocumentImportPreparationResult.Success)
            assertEquals(count, session.documentPages.size)
            assertTrue(session.documentSession.canUseDirectPdf)
            stages.put("import_ms", SystemClock.elapsedRealtime() - phase)
            val stagedBytes = session.documentPages.sumOf { it.imageMetadata.sourceByteCount ?: 0L } + source.length()

            // Endpoint navigation/model edits; the separate Compose test verifies the actual jump UI.
            assertEquals(count, session.documentPages.map { it.id }.distinct().size)
            assertNotNull(session.documentPages[count - 1])
            assertTrue(session.setPageFilter(session.documentPages.first().id.value, DocumentFilter.GRAYSCALE))
            assertTrue(session.rotatePageClockwise(session.documentPages.last().id.value))
            assertFalse(session.documentSession.canUseDirectPdf)

            phase = SystemClock.elapsedRealtime()
            val saved = repository.saveSession(session.documentSession, "M00 synthetic $count")
            assertTrue(saved is LibraryResult.Success)
            val documentId = (saved as LibraryResult.Success).value.id
            database.close()
            database = Room.databaseBuilder(context, LibraryDatabase::class.java, databaseName).build()
            repository = LibraryRepository(context, database.libraryDao(), store)
            val reopened = repository.openDocument(documentId)
            assertTrue(reopened is LibraryResult.Success)
            val pages = (reopened as LibraryResult.Success).value.session.pages
            assertEquals(count, pages.size)
            assertEquals(DocumentFilter.GRAYSCALE, pages.first().filter)
            stages.put("save_close_reopen_ms", SystemClock.elapsedRealtime() - phase)

            phase = SystemClock.elapsedRealtime()
            val ocr = withContext(Dispatchers.Default) {
                MlKitOcrEngine().recognize(pages.map { page ->
                    OcrPage(rotationDegrees = page.rotation.degrees, imageMetadata = page.imageMetadata) {
                        requireNotNull(context.contentResolver.openInputStream(page.source.toAndroidUri()))
                    }
                })
            }
            assertEquals(count, ocr.pages.size)
            assertTrue("Real bundled OCR must produce successful pages", ocr.pages.all { it.error == null })
            stages.put("ocr_ms", SystemClock.elapsedRealtime() - phase)
            val recognized = ocr.pages.count { it.text.contains("synthetic", ignoreCase = true) }
            assertTrue("Synthetic keyword OCR", recognized > 0)
            assertTrue(repository.indexOcr(documentId, pages.map { it.persistentId }, ocr) is LibraryResult.Success)
            assertTrue(repository.searchHits("synthetic").isNotEmpty())

            phase = SystemClock.elapsedRealtime()
            val plan = normalPdfExportPlan((reopened as LibraryResult.Success).value.session)
            assertTrue(plan is NormalPdfExportPlan.RecomposeFromPages)
            val normalResult = recomposeNormalPdf(context, (plan as NormalPdfExportPlan.RecomposeFromPages).pages)
            assertTrue(normalResult is NormalPdfRecompositionResult.Ready)
            normal = (normalResult as NormalPdfRecompositionResult.Ready).file
            assertEquals(count, pdfCount(normal))
            stages.put("normal_pdf_ms", SystemClock.elapsedRealtime() - phase)

            phase = SystemClock.elapsedRealtime()
            coordinator = LocalSearchablePdfExportCoordinator(context, MlKitOcrEngine())
            val export = coordinator.prepare(SearchablePdfExportRequest(
                pageUris = pages.map { it.source.toAndroidUri() },
                visualPages = pages.map { page -> SearchablePdfVisualPage(
                    pageId = page.id.value, originalUri = page.source.toAndroidUri(),
                    filter = page.filter, rotation = page.rotation, imageMetadata = page.imageMetadata,
                ) },
                ocrResult = ocr,
            ))
            assertTrue("Searchable export $count", export is SearchablePdfPreparedExport.Ready)
            searchable = export as SearchablePdfPreparedExport.Ready
            assertEquals(count, searchable.pageCount)
            assertEquals(count, pdfCount(searchable.temporaryFile))
            PDDocument.load(searchable.temporaryFile, memory(root)).use { pdf ->
                val stripper = PDFTextStripper().apply { startPage = 1; endPage = 1 }
                assertTrue(stripper.getText(pdf).isNotBlank())
            }
            stages.put("searchable_pdf_ms", SystemClock.elapsedRealtime() - phase)

            phase = SystemClock.elapsedRealtime()
            val backup = LibraryBackupEngine(
                RoomLibraryBackupSnapshotSource(database.libraryDao(), store),
                FileLibraryBackupWorkspace(File(root, "backup")),
            ).create(BackupProducer(context.packageName, "1.7.2", 21))
            assertTrue("Backup $count", backup is LibraryBackupCreationResult.Verified)
            artifact = (backup as LibraryBackupCreationResult.Verified).artifact
            assertEquals(count, artifact.manifest.summary.pageCount)
            val engine = LibraryRestoreEngine(
                RoomRestoreLibraryStore(context, restoredDatabase.libraryDao(), restoredStore),
                FileRestoreStagingWorkspace(File(root, "restore")),
            )
            val restorePreparation = engine.prepare(RestoreArchiveSource(artifact.file.length()) { artifact.file.inputStream() })
            assertTrue("Restore prepare $count", restorePreparation is RestorePreparationResult.Ready)
            val restored = engine.restore((restorePreparation as RestorePreparationResult.Ready).prepared, RestoreMergePolicy.MERGE_IMPORT_ANYWAY)
            assertTrue("Restore $count", restored is RestoreResult.Completed)
            val restoredDocuments = restoredDatabase.libraryDao().activeDocumentsPage(-1L, 10)
            assertEquals(1, restoredDocuments.size)
            val restoredId = restoredDocuments.single().documentId
            val restoredOpen = restoredRepository.openDocument(restoredId) as LibraryResult.Success
            assertEquals(count, restoredOpen.value.session.pages.size)
            assertEquals(pages.map { it.filter }, restoredOpen.value.session.pages.map { it.filter })
            assertEquals(pages.map { it.rotation }, restoredOpen.value.session.pages.map { it.rotation })
            assertTrue(restoredRepository.searchHits("synthetic").isNotEmpty())
            stages.put("backup_restore_ms", SystemClock.elapsedRealtime() - phase)

            phase = SystemClock.elapsedRealtime()
            val selectedIds = pages.filterIndexed { index, _ -> index % 2 == 0 }.map { requireNotNull(it.persistentId) }.toSet()
            val extracted = repository.extractPages(documentId, selectedIds, "M00 synthetic extract", false)
            assertTrue(extracted is LibraryResult.Success)
            val extractedId = (extracted as LibraryResult.Success).value.id
            assertEquals(selectedIds.size, (repository.openDocument(extractedId) as LibraryResult.Success).value.session.pages.size)
            val merged = repository.mergeDocuments(listOf(documentId, extractedId), "M00 synthetic merge")
            assertTrue(merged is LibraryResult.Success)
            assertEquals(count + selectedIds.size, (repository.openDocument((merged as LibraryResult.Success).value.id) as LibraryResult.Success).value.session.pages.size)
            if (count > 1) {
                val split = repository.extractPages(documentId, setOf(requireNotNull(pages.last().persistentId)), "M00 synthetic split", true)
                assertTrue(split is LibraryResult.Success)
                assertEquals(count - 1, (repository.openDocument(documentId) as LibraryResult.Success).value.session.pages.size)
            }
            stages.put("merge_extract_split_ms", SystemClock.elapsedRealtime() - phase)

            // Rejected append must retain the original active session and release every new staged file.
            val before = session.documentPages
            val importRoot = File(context.cacheDir, "document-imports")
            val filesBefore = importRoot.listFiles().orEmpty().map { it.name }.toSet()
            assertTrue(session.beginImportRequest(DocumentImportOrigin.PICKER))
            val lowSpace = DocumentImportProcessor(context, availableStorageBytes = { 0L }) { resource ->
                session.registerPendingAcquisitionResources(listOf(resource)) == PendingResourceRegistrationResult.Registered
            }.prepare(listOf(sourceUri), DocumentSourceCategory.SELECTED_IMAGE, session.remainingImportPageCapacity())
            assertEquals(DocumentImportPreparationResult.Failure(DocumentImportError.INSUFFICIENT_STORAGE), lowSpace)
            session.failImportRequest(DocumentImportError.INSUFFICIENT_STORAGE)
            assertEquals(before, session.documentPages)
            assertEquals(filesBefore, importRoot.listFiles().orEmpty().map { it.name }.toSet())

            assertTrue(session.beginImportRequest(DocumentImportOrigin.PICKER))
            try {
                importer.prepare(listOf(sourceUri), DocumentSourceCategory.SELECTED_IMAGE, session.remainingImportPageCapacity(),
                    DocumentImportProgressListener { _, _, preparedPages -> if (preparedPages >= 1) throw CancellationException("Synthetic interruption") })
                fail("Cancellation was not propagated")
            } catch (_: CancellationException) {
                session.cancelImportRequest()
            }
            assertEquals(before, session.documentPages)
            assertEquals(filesBefore, importRoot.listFiles().orEmpty().map { it.name }.toSet())
            assertArrayEquals(originalDigest, digest(source))

            return JSONObject().put("pages", count).put("status", "PASS")
                .put("elapsed_ms", SystemClock.elapsedRealtime() - started).put("stages", stages)
                .put("sampled_peak_java_bytes", sampler.javaPeak.get()).put("sampled_peak_native_bytes", sampler.nativePeak.get())
                .put("sampled_peak_pss_kb", sampler.pssPeak.get()).put("sample_interval_ms", 100)
                .put("staged_input_bytes", stagedBytes).put("normal_pdf_bytes", normal.length())
                .put("searchable_pdf_bytes", searchable.temporaryFile.length()).put("backup_bytes", artifact.file.length())
                .put("ocr_pages_with_synthetic_keyword", recognized)
                .put("ocr_successful_pages", ocr.pages.count { it.error == null })
        } finally {
            sampler.stop()
            session.clearScan()
            normal?.let(::deleteNormalPdfRecomposition)
            searchable?.let { coordinator?.discardPreparedExport(it) }
            artifact?.close()
            database.close(); restoredDatabase.close()
            context.deleteDatabase(databaseName); context.deleteDatabase(restoredName)
            store.root.deleteRecursively(); restoredStore.root.deleteRecursively(); root.deleteRecursively()
        }
    }

    private fun fixture(file: File, pages: Int) {
        PDDocument(memory(file.parentFile!!)).use { pdf ->
            repeat(pages) { index ->
                val page = PDPage(PDRectangle(200f, 260f)); pdf.addPage(page)
                PDPageContentStream(pdf, page).use { content ->
                    content.beginText(); content.setFont(PDType1Font.HELVETICA, 12f)
                    content.newLineAtOffset(15f, 220f); content.showText("RME synthetic page ${index + 1}")
                    content.newLineAtOffset(0f, -25f); content.showText("Local M00 validation only")
                    content.endText()
                }
            }
            pdf.save(file)
        }
    }
    private fun memory(root: File) = MemoryUsageSetting.setupMixed(8L * 1024 * 1024).setTempDir(root)
    private fun pdfCount(file: File) = PDDocument.load(file, memory(file.parentFile!!)).use { it.numberOfPages }
    private fun digest(file: File): ByteArray = file.inputStream().use { input ->
        val hash = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(32 * 1024)
        while (true) { val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }; hash.digest()
    }
    private class Sampler {
        val javaPeak = AtomicLong(); val nativePeak = AtomicLong(); val pssPeak = AtomicLong()
        private val running = AtomicBoolean(true)
        private val thread = Thread {
            while (running.get()) {
                val r = Runtime.getRuntime(); javaPeak.accumulateAndGet(r.totalMemory() - r.freeMemory(), ::maxOf)
                nativePeak.accumulateAndGet(Debug.getNativeHeapAllocatedSize(), ::maxOf)
                pssPeak.accumulateAndGet(Debug.getPss(), ::maxOf)
                try { Thread.sleep(100) } catch (_: InterruptedException) { break }
            }
        }
        fun start() = thread.start()
        fun stop() { running.set(false); thread.interrupt(); thread.join(1000) }
    }
}
