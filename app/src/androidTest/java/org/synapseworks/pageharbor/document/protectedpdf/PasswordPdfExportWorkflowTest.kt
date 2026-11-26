package org.synapseworks.pageharbor.document.protectedpdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.filters.SdkSuppress
import android.app.Application
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.synapseworks.pageharbor.R
import org.synapseworks.pageharbor.document.NormalPdfPage
import org.synapseworks.pageharbor.image.DocumentFilter
import org.synapseworks.pageharbor.document.session.DocumentPageRotation
import org.synapseworks.pageharbor.document.session.DocumentImageMetadata
import org.synapseworks.pageharbor.document.NormalPdfExportPlan
import org.synapseworks.pageharbor.document.session.DocumentResource
import org.synapseworks.pageharbor.document.session.DocumentResourceOwnership

class PasswordPdfExportWorkflowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application
    private lateinit var root: File
    private lateinit var source: File
    private lateinit var model: PasswordPdfExportViewModel
    private val password: String get() = "synthetic-open-password"

    @Before fun setup() {
        PDFBoxResourceLoader.init(app)
        root = File(app.cacheDir, "shared-pdfs/m05-workflow-${UUID.randomUUID()}").apply { check(mkdirs()) }
        source = File(root, "source.pdf")
        PDDocument().use { it.addPage(PDPage()); it.save(source) }
        instrumentation.runOnMainSync { model = PasswordPdfExportViewModel(app) }
    }
    @After fun cleanup() = runBlocking {
        withContext(Dispatchers.Main) { model.close() }
        delay(100)
        root.deleteRecursively()
        Unit
    }
    private suspend fun open() = withContext(Dispatchers.Main) {
        model.open(NormalPdfExportPlan.DirectScannerPdf(DocumentResource(
            Uri.fromFile(source).toString(), DocumentResourceOwnership.USER_OR_EXTERNAL,
        )))
    }
    private suspend fun ready() {
        open()
        val input = password.toCharArray()
        withContext(Dispatchers.Main) { model.prepare(input) }
        assertTrue(input.all { it == '\u0000' })
        withTimeout(30_000) { while (model.state.busy) delay(10) }
        assertTrue("Workflow message resource: ${model.state.message}", model.state.output?.isFile == true)
    }
    private fun destination(file: File): Uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)

    @Test fun capturesAnIndependentSourceAndNeverRetargetsTheSessionInput() = runBlocking {
        val before = source.readBytes()
        open()
        val captured = requireNotNull(model.state.source)
        assertTrue(captured != source)
        assertArrayEquals(before, captured.readBytes())
        // A later external/session source change must not retarget the captured workflow.
        source.writeBytes(byteArrayOf(1, 2, 3))
        val input = password.toCharArray()
        withContext(Dispatchers.Main) { model.prepare(input) }
        withTimeout(30_000) { while (model.state.busy) delay(10) }
        PDDocument.load(requireNotNull(model.state.output), password).use { assertEquals(1, it.numberOfPages) }
        assertArrayEquals(byteArrayOf(1, 2, 3), source.readBytes())
        assertArrayEquals(before, captured.readBytes())
    }

    @Test fun saveCancellationDuplicateAndStaleResultsCannotPublishANewerOutput() = runBlocking {
        ready()
        val original = source.readBytes()
        val destination = File(root, "destination.pdf").apply { writeBytes(byteArrayOf(7, 8)) }
        val uri = destination(destination)
        val a = withContext(Dispatchers.Main) { requireNotNull(model.createSaveRequest()) }
        withContext(Dispatchers.Main) { model.save(null, a) { true } }
        assertFalse(model.state.busy)
        val b = withContext(Dispatchers.Main) { requireNotNull(model.createSaveRequest()) }
        withContext(Dispatchers.Main) { model.save(uri, a) { true } }
        assertTrue(model.state.busy)
        assertArrayEquals(byteArrayOf(7, 8), destination.readBytes())
        withContext(Dispatchers.Main) { model.save(uri, b) { true } }
        withTimeout(30_000) { while (model.state.busy) delay(10) }
        val saved = destination.readBytes()
        assertArrayEquals(requireNotNull(model.state.output).readBytes(), saved)
        withContext(Dispatchers.Main) { model.save(uri, b) { true } }
        assertArrayEquals(saved, destination.readBytes())
        assertArrayEquals(original, source.readBytes())
    }

    @Test fun lockedOrLostOutputNeverTruncatesTheDestination() = runBlocking {
        ready()
        val target = File(root, "untouched.pdf").apply { writeBytes(byteArrayOf(8, 9)) }
        val uri = destination(target)
        val request = withContext(Dispatchers.Main) { requireNotNull(model.createSaveRequest()) }
        withContext(Dispatchers.Main) { model.save(uri, request) { false } }
        withTimeout(30_000) { while (model.state.busy) delay(10) }
        assertArrayEquals(byteArrayOf(8, 9), target.readBytes())
        assertEquals(R.string.protect_save_failure, model.state.message)
        val next = withContext(Dispatchers.Main) { requireNotNull(model.createSaveRequest()) }
        requireNotNull(model.state.output).delete()
        withContext(Dispatchers.Main) { model.save(uri, next) { true } }
        withTimeout(30_000) { while (model.state.busy) delay(10) }
        assertArrayEquals(byteArrayOf(8, 9), target.readBytes())
    }

    @Test fun closingInvalidatesPendingSaveAndShareTicketsAndCleansOwnedFiles() = runBlocking {
        ready()
        val owned = requireNotNull(model.state.source).parentFile!!
        val share = withContext(Dispatchers.Main) { requireNotNull(model.beginHandoff()) }
        assertTrue(model.isCurrent(share))
        withContext(Dispatchers.Main) { model.finishHandoff(share) }
        val save = withContext(Dispatchers.Main) { requireNotNull(model.createSaveRequest()) }
        withContext(Dispatchers.Main) { model.close() }
        val target = File(root, "never-written.pdf").apply { writeBytes(byteArrayOf(4)) }
        withContext(Dispatchers.Main) { model.save(destination(target), save) { true } }
        assertFalse(model.isCurrent(share))
        assertFalse(model.state.visible)
        withTimeout(30_000) { while (owned.exists()) delay(10) }
        assertArrayEquals(byteArrayOf(4), target.readBytes())
        assertTrue(source.isFile)
    }

    @Test fun cancellationWipesOwnedPasswordAndWaitsForWorkerBeforeDeletingWorkspace() = runBlocking {
        open()
        val ownedDirectory = requireNotNull(model.state.source).parentFile!!
        val entered = CompletableDeferred<Unit>()
        val buffer = AtomicReference<CharArray>()
        withContext(Dispatchers.Main) {
            model.encryptForTest = { _, output, owned ->
                buffer.set(owned)
                output.writeBytes(byteArrayOf(1))
                entered.complete(Unit)
                awaitCancellation()
            }
            model.prepare(password.toCharArray())
        }
        withTimeout(30_000) { entered.await() }
        withContext(Dispatchers.Main) { model.close() }
        withTimeout(30_000) { while (ownedDirectory.exists()) delay(10) }
        assertTrue(buffer.get().all { it == '\u0000' })
        assertTrue(source.isFile)
        assertFalse(model.state.visible)
    }

    @Test fun changingOptionsDiscardsPreparedOutputAndRejectsOldHandoff() = runBlocking {
        ready()
        val old = requireNotNull(model.state.output)
        val ticket = withContext(Dispatchers.Main) { requireNotNull(model.beginHandoff()) }
        withContext(Dispatchers.Main) { model.finishHandoff(ticket); model.discardOutput() }
        assertFalse(model.isCurrent(ticket))
        assertNull(model.state.output)
        withTimeout(30_000) { while (old.exists()) delay(10) }
        assertTrue(model.state.source?.isFile == true)
    }
    @SdkSuppress(minSdkVersion = 35)
    @Test fun recomposedPagesPreserveOrderRotationPixelsAndOwnAllIntermediates() = runBlocking {
        fun image(name: String, color: Int): File {
            val file = File(root, name)
            val bitmap = Bitmap.createBitmap(80, 120, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(color)
                file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
            } finally { bitmap.recycle() }
            return file
        }
        val a = image("a.jpg", Color.RED)
        val b = image("b.jpg", Color.BLUE)
        val beforeA = a.readBytes()
        val beforeB = b.readBytes()
        fun page(file: File, id: Long, rotation: DocumentPageRotation) = NormalPdfPage(
            id, DocumentResource(Uri.fromFile(file).toString(), DocumentResourceOwnership.USER_OR_EXTERNAL),
            DocumentFilter.ORIGINAL, rotation, imageMetadata = DocumentImageMetadata(file.length(), 80, 120),
        )
        withContext(Dispatchers.Main) { model.open(NormalPdfExportPlan.RecomposeFromPages(listOf(
            page(b, 2, DocumentPageRotation.DEGREES_90), page(a, 1, DocumentPageRotation.DEGREES_0),
        ))) }
        val captured = requireNotNull(model.state.source)
        val owned = requireNotNull(captured.parentFile)
        assertEquals(listOf("input.pdf"), owned.listFiles().orEmpty().map { it.name })
        PDDocument.load(captured).use {
            assertEquals(2, it.numberOfPages)
            assertTrue(it.getPage(0).mediaBox.width > it.getPage(0).mediaBox.height)
            assertTrue(it.getPage(1).mediaBox.width < it.getPage(1).mediaBox.height)
        }
        withContext(Dispatchers.Main) { model.prepare(password.toCharArray()) }
        withTimeout(30_000) { while (model.state.busy) delay(10) }
        val output = requireNotNull(model.state.output)
        fun rendered(file: File, secret: String?, index: Int): IntArray {
            val params = android.graphics.pdf.LoadParams.Builder().apply { if (secret != null) setPassword(secret) }.build()
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            return PdfRenderer(descriptor, params).use { renderer -> renderer.openPage(index).use { page ->
                val bitmap = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888)
                try {
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    IntArray(120 * 120).also { bitmap.getPixels(it, 0, 120, 0, 0, 120, 120) }
                } finally { bitmap.recycle() }
            } }
        }
        repeat(2) { assertArrayEquals(rendered(captured, null, it), rendered(output, password, it)) }
        val first = rendered(captured, null, 0)[60 * 120 + 60]
        val second = rendered(captured, null, 1)[60 * 120 + 60]
        assertTrue(Color.blue(first) > Color.red(first))
        assertTrue(Color.red(second) > Color.blue(second))
        assertArrayEquals(beforeA, a.readBytes())
        assertArrayEquals(beforeB, b.readBytes())
        withContext(Dispatchers.Main) { model.close() }
        withTimeout(30_000) { while (owned.exists()) delay(10) }
    }

    @Test fun malformedRecompositionReleasesItsWorkspaceAndKeepsOriginalInput() = runBlocking {
        val bad = File(root, "invalid.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val ownerRoot = File(app.cacheDir, "protected-pdf-export")
        val before = ownerRoot.listFiles().orEmpty().map { it.name }.toSet()
        withContext(Dispatchers.Main) { model.open(NormalPdfExportPlan.RecomposeFromPages(listOf(NormalPdfPage(
            1, DocumentResource(Uri.fromFile(bad).toString(), DocumentResourceOwnership.USER_OR_EXTERNAL),
            DocumentFilter.ORIGINAL, DocumentPageRotation.DEGREES_90,
            imageMetadata = DocumentImageMetadata(bad.length(), 80, 120),
        )))) }
        assertNull(model.state.source)
        assertEquals(R.string.protect_source_failure, model.state.message)
        assertEquals(before, ownerRoot.listFiles().orEmpty().map { it.name }.toSet())
        assertArrayEquals(byteArrayOf(1, 2, 3), bad.readBytes())
    }

    @Test fun destinationStorageFailureKeepsPreparedOutputAndOriginalSource() = runBlocking {
        ready()
        val before = source.readBytes()
        val output = requireNotNull(model.state.output)
        val ciphertext = output.readBytes()
        val authority = "${app.packageName}.m05documents"
        val target = requireNotNull(android.provider.DocumentsContract.createDocument(
            app.contentResolver, android.provider.DocumentsContract.buildDocumentUri(authority, "root"),
            "application/pdf", "Synthetic encrypted PDF",
        ))
        org.synapseworks.pageharbor.protectedpdf.M05DocumentsProvider.denyWrites = true
        try {
            val request = withContext(Dispatchers.Main) { requireNotNull(model.createSaveRequest()) }
            withContext(Dispatchers.Main) { model.save(target, request) { true } }
            withTimeout(30_000) { while (model.state.busy) delay(10) }
            assertEquals(R.string.protect_save_failure, model.state.message)
            assertArrayEquals(ciphertext, output.readBytes())
            assertArrayEquals(before, source.readBytes())
        } finally {
            org.synapseworks.pageharbor.protectedpdf.M05DocumentsProvider.denyWrites = false
            android.provider.DocumentsContract.deleteDocument(app.contentResolver, target)
        }
    }

    @Test fun aNewHostRemovesAbandonedPlaintextButPreservesLiveWorkspaceOwnership() = runBlocking {
        open()
        val captured = requireNotNull(model.state.source)
        val abandoned = File(app.cacheDir, "protected-pdf-export/protect-${UUID.randomUUID()}").apply { check(mkdirs()) }
        File(abandoned, "input.pdf").writeBytes(byteArrayOf(1, 2, 3))
        lateinit var another: PasswordPdfExportViewModel
        withContext(Dispatchers.Main) { another = PasswordPdfExportViewModel(app) }
        try {
            withTimeout(30_000) { while (abandoned.exists()) delay(10) }
            assertTrue(captured.isFile)
            assertArrayEquals(source.readBytes(), captured.readBytes())
        } finally { withContext(Dispatchers.Main) { another.close() } }
    }

}
