package org.synapseworks.pageharbor.document.importing

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.synapseworks.pageharbor.PageHarborSessionViewModel
import org.synapseworks.pageharbor.document.session.DocumentSourceCategory
import org.synapseworks.pageharbor.document.session.PendingResourceRegistrationResult

class DocumentImportProcessorInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixtureRoot = File(context.cacheDir, "shared-pdfs").apply { mkdirs() }
    private val importRoot = File(context.cacheDir, "document-imports").apply { mkdirs() }

    @Test
    fun oneAndMultipleImagesEnterTheUnifiedSessionInSelectionOrder() = runBlocking {
        val first = jpegFixture("first", Color.RED)
        val second = pngFixture("second", Color.BLUE)
        val session = PageHarborSessionViewModel()
        try {
            val result = prepare(session, listOf(uri(first), uri(second)))
            assertTrue(result is DocumentImportPreparationResult.Success)
            result as DocumentImportPreparationResult.Success
            session.completeImportRequest(result)

            assertEquals(2, session.documentPages.size)
            assertEquals(
                listOf("image/jpeg", "image/png"),
                session.documentPages.map { page -> page.contentType },
            )
            assertEquals(
                listOf(DocumentSourceCategory.SELECTED_IMAGE, DocumentSourceCategory.SELECTED_IMAGE),
                session.documentPages.map { page -> page.sourceCategory },
            )
        } finally {
            session.clearScan()
            first.delete()
            second.delete()
        }
    }

    @Test
    fun malformedImageUnreadableUriAndUnsupportedBytesHaveDistinctFailures() = runBlocking {
        val malformed = fixture(
            "malformed.jpg",
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0),
        )
        val unsupported = fixture("unsupported.bin", "plain text".encodeToByteArray())
        try {
            assertEquals(
                DocumentImportPreparationResult.Failure(DocumentImportError.INVALID_IMAGE),
                prepareFresh(listOf(uri(malformed))),
            )
            assertEquals(
                DocumentImportPreparationResult.Failure(DocumentImportError.UNSUPPORTED_TYPE),
                prepareFresh(listOf(uri(unsupported))),
            )
            assertEquals(
                DocumentImportPreparationResult.Failure(DocumentImportError.UNREADABLE_SOURCE),
                prepareFresh(listOf(Uri.parse("content://missing.rme.test/unreadable"))),
            )
        } finally {
            malformed.delete()
            unsupported.delete()
        }
    }

    @Test
    fun partialMultiFileFailureKeepsValidPagesAndReportsSkippedItems() = runBlocking {
        val invalid = fixture("partial-invalid.bin", byteArrayOf(1, 2, 3, 4))
        val valid = jpegFixture("partial-valid", Color.GREEN)
        val session = PageHarborSessionViewModel()
        try {
            val result = prepare(session, listOf(uri(invalid), uri(valid)))

            assertTrue(result is DocumentImportPreparationResult.Success)
            result as DocumentImportPreparationResult.Success
            assertEquals(1, result.skippedItems)
            assertEquals(1, result.input.pages.size)
            session.completeImportRequest(result)
            assertEquals(1, session.documentPages.size)
        } finally {
            session.clearScan()
            invalid.delete()
            valid.delete()
        }
    }

    @Test
    fun multiPagePdfRendersSequentialBoundedPagesAndCleansPrivateCopies() = runBlocking {
        val pdf = pdfFixture("multi", listOf(612 to 792, 20_000 to 10_000))
        val before = importTemporaryFiles()
        val session = PageHarborSessionViewModel()
        try {
            val result = prepare(session, listOf(uri(pdf)))
            assertTrue(result is DocumentImportPreparationResult.Success)
            result as DocumentImportPreparationResult.Success
            assertEquals(2, result.input.pages.size)
            assertTrue(result.input.directPdfSource != null)
            result.input.pages.forEach { page ->
                assertEquals(DocumentSourceCategory.RENDERED_PDF_PAGE, page.sourceCategory)
                assertEquals("image/jpeg", page.contentType)
                assertTrue(requireNotNull(page.imageMetadata.width) <= MAX_PDF_RENDER_DIMENSION)
                assertTrue(requireNotNull(page.imageMetadata.height) <= MAX_PDF_RENDER_DIMENSION)
                assertTrue(
                    requireNotNull(page.imageMetadata.width).toLong() *
                        requireNotNull(page.imageMetadata.height) <= MAX_PDF_RENDER_PIXELS,
                )
            }

            session.completeImportRequest(result)
            assertEquals(2, session.documentPages.size)
            assertEquals(1, newImportSourceFiles(before).size)
            assertEquals(2, newImportPageFiles(before).size)

            session.clearScan()
            assertTrue(newImportSourceFiles(before).isEmpty())
            assertTrue(newImportPageFiles(before).isEmpty())
        } finally {
            session.clearScan()
            (importTemporaryFiles() - before).forEach(File::delete)
            pdf.delete()
        }
    }

    @Test
    fun multiplePdfsRemainDurableSessionSourcesUntilTheSessionIsReleased() = runBlocking {
        val first = pdfFixture("first-original", listOf(300 to 400))
        val second = pdfFixture("second-original", listOf(300 to 400))
        val before = importTemporaryFiles()
        val session = PageHarborSessionViewModel()
        try {
            val result = prepare(session, listOf(uri(first), uri(second)))
            assertTrue(result is DocumentImportPreparationResult.Success)
            result as DocumentImportPreparationResult.Success
            assertEquals(2, result.input.originalPdfSources.size)
            assertEquals(null, result.input.directPdfSource)

            session.completeImportRequest(result)

            assertEquals(2, session.documentSession.originalPdfSources.size)
            assertEquals(2, newImportSourceFiles(before).size)
            session.clearScan()
            assertTrue(newImportSourceFiles(before).isEmpty())
        } finally {
            session.clearScan()
            (importTemporaryFiles() - before).forEach(File::delete)
            first.delete()
            second.delete()
        }
    }

    @Test
    fun invalidPdfAndPdfPageLimitFailWithoutLeakingTemporaryFiles() = runBlocking {
        val invalid = fixture("invalid.pdf", "%PDF-not-readable".encodeToByteArray())
        val tooMany = pdfFixture("too-many", List(3) { 200 to 300 })
        val before = importTemporaryFiles()
        try {
            assertEquals(
                DocumentImportPreparationResult.Failure(DocumentImportError.PDF_UNREADABLE),
                prepareFresh(listOf(uri(invalid))),
            )
            assertEquals(
                DocumentImportPreparationResult.Failure(DocumentImportError.PAGE_LIMIT_EXCEEDED),
                prepareFresh(listOf(uri(tooMany)), capacity = 2),
            )
            assertTrue(newImportSourceFiles(before).isEmpty())
            assertTrue(newImportPageFiles(before).isEmpty())
        } finally {
            (importTemporaryFiles() - before).forEach(File::delete)
            invalid.delete()
            tooMany.delete()
        }
    }

    @Test
    fun encryptedPdfUsesTheSafeUnreadablePdfOutcome() = runBlocking {
        val encrypted = encryptedPdfFixture("encrypted")
        val before = importTemporaryFiles()
        try {
            assertEquals(
                DocumentImportPreparationResult.Failure(DocumentImportError.PDF_UNREADABLE),
                prepareFresh(listOf(uri(encrypted))),
            )
            assertTrue(newImportSourceFiles(before).isEmpty())
            assertTrue(newImportPageFiles(before).isEmpty())
        } finally {
            (importTemporaryFiles() - before).forEach(File::delete)
            encrypted.delete()
        }
    }

    @Test
    fun coldSessionCleanupDeletesOnlyStaleImportFiles() {
        val now = 2L * 24L * 60L * 60L * 1_000L
        val stale = File(importRoot, "stale-import-test.jpg").apply {
            writeBytes(byteArrayOf(1))
            setLastModified(0L)
        }
        val recent = File(importRoot, "recent-import-test.jpg").apply {
            writeBytes(byteArrayOf(2))
            setLastModified(now)
        }
        val directory = File(importRoot, "stale-import-directory").apply {
            mkdirs()
            setLastModified(0L)
        }
        try {
            deleteStaleDocumentImports(context.cacheDir, nowMillis = now)

            assertFalse(stale.exists())
            assertTrue(recent.exists())
            assertTrue(directory.isDirectory)
        } finally {
            stale.delete()
            recent.delete()
            directory.delete()
        }
    }

    @Test
    fun cancellationAfterPreparationCleansEveryRegisteredPdfResource() = runBlocking {
        val pdf = pdfFixture("cancel", listOf(300 to 400))
        val before = importTemporaryFiles()
        val session = PageHarborSessionViewModel()
        session.beginImportRequest(DocumentImportOrigin.PICKER)
        val processor = processor(session)
        try {
            try {
                processor.prepare(
                    uris = listOf(uri(pdf)),
                    imageSourceCategory = DocumentSourceCategory.SELECTED_IMAGE,
                    pageCapacity = 20,
                    progressListener = DocumentImportProgressListener { _, _, _ ->
                        throw CancellationException("test cancellation")
                    },
                )
                throw AssertionError("Expected cancellation")
            } catch (_: CancellationException) {
                session.cancelImportRequest()
            }

            assertTrue(newImportSourceFiles(before).isEmpty())
            assertTrue(newImportPageFiles(before).isEmpty())
        } finally {
            session.clearScan()
            (importTemporaryFiles() - before).forEach(File::delete)
            pdf.delete()
        }
    }

    private suspend fun prepareFresh(
        uris: List<Uri>,
        capacity: Int = 20,
    ): DocumentImportPreparationResult {
        val session = PageHarborSessionViewModel()
        return try {
            val result = prepare(session, uris, capacity)
            if (result is DocumentImportPreparationResult.Failure) {
                session.failImportRequest(result.reason)
            }
            result
        } finally {
            session.clearScan()
        }
    }

    private suspend fun prepare(
        session: PageHarborSessionViewModel,
        uris: List<Uri>,
        capacity: Int = 20,
    ): DocumentImportPreparationResult {
        assertTrue(session.beginImportRequest(DocumentImportOrigin.PICKER))
        return processor(session).prepare(
            uris = uris,
            imageSourceCategory = DocumentSourceCategory.SELECTED_IMAGE,
            pageCapacity = capacity,
        )
    }

    private fun processor(session: PageHarborSessionViewModel) =
        DocumentImportProcessor(context) { resource ->
            session.registerPendingAcquisitionResources(listOf(resource)) ==
                PendingResourceRegistrationResult.Registered
        }

    private fun jpegFixture(label: String, color: Int): File =
        bitmapFixture("import-fixture-$label.jpg", color, Bitmap.CompressFormat.JPEG)

    private fun pngFixture(label: String, color: Int): File =
        bitmapFixture("import-fixture-$label.png", color, Bitmap.CompressFormat.PNG)

    private fun bitmapFixture(name: String, color: Int, format: Bitmap.CompressFormat): File {
        val file = File(fixtureRoot, name)
        val bitmap = Bitmap.createBitmap(160, 240, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(color)
            FileOutputStream(file).use { output ->
                assertTrue(bitmap.compress(format, 95, output))
            }
        } finally {
            bitmap.recycle()
        }
        return file
    }

    private fun pdfFixture(name: String, sizes: List<Pair<Int, Int>>): File {
        val file = File(fixtureRoot, "import-fixture-$name.pdf")
        val document = PdfDocument()
        try {
            sizes.forEachIndexed { index, (width, height) ->
                val info = PdfDocument.PageInfo.Builder(width, height, index + 1).create()
                val page = document.startPage(info)
                page.canvas.drawColor(if (index % 2 == 0) Color.WHITE else Color.LTGRAY)
                document.finishPage(page)
            }
            FileOutputStream(file).use(document::writeTo)
        } finally {
            document.close()
        }
        return file
    }

    private fun encryptedPdfFixture(name: String): File {
        val file = File(fixtureRoot, "import-fixture-$name.pdf")
        PDDocument().use { document ->
            document.addPage(PDPage())
            val policy = StandardProtectionPolicy(
                "fixture-owner-password",
                "fixture-user-password",
                AccessPermission(),
            ).apply {
                encryptionKeyLength = 128
            }
            document.protect(policy)
            document.save(file)
        }
        return file
    }

    private fun fixture(name: String, bytes: ByteArray): File = File(fixtureRoot, name).apply {
        writeBytes(bytes)
    }

    private fun uri(file: File): Uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )

    private fun importTemporaryFiles(): Set<File> = importRoot.listFiles().orEmpty()
        .filter { file -> file.name.startsWith("import-source-") || file.name.startsWith("import-page-") }
        .toSet()

    private fun newImportSourceFiles(before: Set<File>): Set<File> =
        (importTemporaryFiles() - before).filter { it.name.startsWith("import-source-") }.toSet()

    private fun newImportPageFiles(before: Set<File>): Set<File> =
        (importTemporaryFiles() - before).filter { it.name.startsWith("import-page-") }.toSet()
}
