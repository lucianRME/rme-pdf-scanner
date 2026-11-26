package org.synapseworks.pageharbor.document.scalability

import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.synapseworks.pageharbor.PageHarborSessionViewModel
import org.synapseworks.pageharbor.document.importing.*
import org.synapseworks.pageharbor.document.session.*

class M00ResourceFailureInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun storageExhaustionAfterFirstRenderedPageReleasesAllNewStaging() = runBlocking {
        PDFBoxResourceLoader.init(context)
        val source = File(context.cacheDir, "shared-pdfs/m00-space.pdf")
        source.parentFile!!.mkdirs()
        PDDocument().use { it.addPage(PDPage()); it.addPage(PDPage()); it.save(source) }
        val original = source.readBytes()
        val session = PageHarborSessionViewModel()
        val root = File(context.cacheDir, "document-imports")
        val before = root.listFiles().orEmpty().map { it.name }.toSet()
        var probes = 0
        var rendered = 0
        try {
            assertTrue(session.beginImportRequest(DocumentImportOrigin.PICKER))
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", source)
            val result = DocumentImportProcessor(context, availableStorageBytes = {
                probes += 1
                if (probes <= 2) Long.MAX_VALUE else 0L
            }) { resource ->
                session.registerPendingAcquisitionResources(listOf(resource)) == PendingResourceRegistrationResult.Registered
            }.prepare(listOf(uri), DocumentSourceCategory.SELECTED_IMAGE, session.remainingImportPageCapacity(),
                DocumentImportProgressListener { _, _, count -> rendered = maxOf(rendered, count) })
            assertEquals(1, rendered)
            assertEquals(DocumentImportPreparationResult.Failure(DocumentImportError.INSUFFICIENT_STORAGE), result)
            session.failImportRequest(DocumentImportError.INSUFFICIENT_STORAGE)
            assertEquals(before, root.listFiles().orEmpty().map { it.name }.toSet())
            assertArrayEquals(original, source.readBytes())
        } finally { session.clearScan(); source.delete() }
    }

    @Test fun lowMetadataMemoryPreservesCurrentSessionAndCleansStagedPdf() = runBlocking {
        PDFBoxResourceLoader.init(context)
        val source = File(context.cacheDir, "shared-pdfs/m00-memory.pdf")
        source.parentFile!!.mkdirs()
        PDDocument().use { it.addPage(PDPage()); it.save(source) }
        val session = PageHarborSessionViewModel()
        val root = File(context.cacheDir, "document-imports")
        val before = root.listFiles().orEmpty().map { it.name }.toSet()
        try {
            assertTrue(session.beginImportRequest(DocumentImportOrigin.PICKER))
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", source)
            val result = DocumentImportProcessor(context, availableHeapBytes = { 0L }) { resource ->
                session.registerPendingAcquisitionResources(listOf(resource)) == PendingResourceRegistrationResult.Registered
            }.prepare(listOf(uri), DocumentSourceCategory.SELECTED_IMAGE, session.remainingImportPageCapacity())
            assertEquals(DocumentImportPreparationResult.Failure(DocumentImportError.INSUFFICIENT_MEMORY), result)
            session.failImportRequest(DocumentImportError.INSUFFICIENT_MEMORY)
            assertTrue(session.documentPages.isEmpty())
            assertEquals(before, root.listFiles().orEmpty().map { it.name }.toSet())
            assertTrue(source.isFile)
        } finally { session.clearScan(); source.delete() }
    }
}
