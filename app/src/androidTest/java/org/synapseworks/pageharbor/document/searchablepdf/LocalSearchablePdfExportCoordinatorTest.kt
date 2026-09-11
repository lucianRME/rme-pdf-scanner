package org.synapseworks.pageharbor.document.searchablepdf

import android.content.Context
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.synapseworks.pageharbor.ocr.OcrEngine
import org.synapseworks.pageharbor.ocr.OcrPage
import org.synapseworks.pageharbor.ocr.OcrPageLayout
import org.synapseworks.pageharbor.ocr.OcrPageResult
import org.synapseworks.pageharbor.ocr.OcrResult
import org.synapseworks.pageharbor.ocr.OcrTextBounds
import org.synapseworks.pageharbor.ocr.OcrTextLine
import org.synapseworks.pageharbor.image.DocumentFilter
import org.synapseworks.pageharbor.document.session.DEFAULT_MAX_IMAGE_SOURCE_BYTES
import org.synapseworks.pageharbor.document.session.DocumentImageMetadata

class LocalSearchablePdfExportCoordinatorTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun filteredVisualJpegDoesNotReplaceTheOriginalOcrSourceOrLayout() = runBlocking {
        val source = sharedFile("filtered-visual-source.jpg")
        writeJpeg(source)
        val originalBytes = source.readBytes()
        val ocrEngine = SourceRecordingOcrEngine(pageResult())
        val generator = VisualRecordingGenerator()
        val coordinator = LocalSearchablePdfExportCoordinator(
            context = context,
            ocrEngine = ocrEngine,
            generator = generator,
        )

        try {
            val prepared = coordinator.prepare(
                SearchablePdfExportRequest(
                    pageUris = listOf(fileUri(source)),
                    visualPages = listOf(
                        SearchablePdfVisualPage(
                            pageId = 42L,
                            originalUri = fileUri(source),
                            filter = DocumentFilter.GRAYSCALE,
                        ),
                    ),
                ),
            )

            assertTrue(prepared is SearchablePdfPreparedExport.Ready)
            prepared as SearchablePdfPreparedExport.Ready
            assertArrayEquals(originalBytes, ocrEngine.receivedSourceBytes)
            assertEquals(pageResult().layout, generator.receivedOcrResult?.layout)
            assertTrue(generator.visualPixelIsGrayscale)
            assertArrayEquals(originalBytes, source.readBytes())
            coordinator.discardPreparedExport(prepared)
        } finally {
            source.delete()
        }
    }

    @Test
    fun knownOversizedVisualFailsBeforeOcrOrGenerationWithTypedReason() = runBlocking {
        val generator = RecordingGenerator()
        val coordinator = LocalSearchablePdfExportCoordinator(
            context = context,
            ocrEngine = FailingOcrEngine,
            generator = generator,
        )
        val uri = Uri.parse("content://test.provider/oversized.jpg")

        val result = coordinator.prepare(
            SearchablePdfExportRequest(
                pageUris = listOf(uri),
                visualPages = listOf(
                    SearchablePdfVisualPage(
                        pageId = 1L,
                        originalUri = uri,
                        imageMetadata = DocumentImageMetadata(
                            sourceByteCount = DEFAULT_MAX_IMAGE_SOURCE_BYTES + 1L,
                        ),
                    ),
                ),
            ),
        )

        assertEquals(
            SearchablePdfPreparedExport.Failure(SearchablePdfPreparationError.SOURCE_TOO_LARGE),
            result,
        )
        assertEquals(0, generator.requestCount)
    }

    @Test
    fun runsOcrGeneratesPdfWritesContentDestinationAndDeletesTemporaryFile() = runBlocking {
        val source = sharedFile("source.jpg")
        val destination = sharedFile("destination.pdf")
        val progress = mutableListOf<SearchablePdfExportProgress>()
        writeJpeg(source)
        val originalImageBytes = source.readBytes()
        val ocrEngine = RecordingOcrEngine(pageResult())
        val coordinator = LocalSearchablePdfExportCoordinator(
            context = context,
            ocrEngine = ocrEngine,
        )

        try {
            val prepared = coordinator.prepare(
                SearchablePdfExportRequest(
                    pageUris = listOf(fileUri(source)),
                    progressListener = SearchablePdfExportProgressListener(progress::add),
                ),
            )

            assertTrue(prepared is SearchablePdfPreparedExport.Ready)
            prepared as SearchablePdfPreparedExport.Ready
            assertEquals(1, ocrEngine.requestCount)
            assertTrue(prepared.temporaryFile.exists())
            assertEquals(
                listOf(
                    SearchablePdfExportProgress.Recognizing,
                    SearchablePdfExportProgress.Generating,
                ),
                progress,
            )

            val result = coordinator.writePreparedExport(prepared, fileUri(destination))

            assertEquals(SearchablePdfExportResult.Success, result)
            assertFalse(prepared.temporaryFile.exists())
            assertArrayEquals(originalImageBytes, source.readBytes())
            assertEquals(
                listOf(
                    SearchablePdfExportProgress.Recognizing,
                    SearchablePdfExportProgress.Generating,
                    SearchablePdfExportProgress.Writing,
                ),
                progress,
            )
            PDDocument.load(destination).use { document ->
                assertEquals(1, document.numberOfPages)
                assertEquals("English: searchable text", PDFTextStripper().getText(document).trim())
            }
        } finally {
            source.delete()
            destination.delete()
        }
    }

    @Test
    fun usesSuppliedOcrAndDiscardDeletesUnusedPreparedPdf() = runBlocking {
        val source = sharedFile("supplied-source.jpg").apply { writeText("jpeg") }
        val generator = RecordingGenerator()
        val coordinator = LocalSearchablePdfExportCoordinator(
            context = context,
            ocrEngine = FailingOcrEngine,
            generator = generator,
        )

        try {
            val prepared = coordinator.prepare(
                SearchablePdfExportRequest(
                    pageUris = listOf(fileUri(source)),
                    ocrResult = OcrResult(listOf(pageResult())),
                ),
            )

            assertTrue(prepared is SearchablePdfPreparedExport.Ready)
            prepared as SearchablePdfPreparedExport.Ready
            assertEquals(1, generator.requestCount)
            assertTrue(prepared.temporaryFile.exists())

            coordinator.discardPreparedExport(prepared)

            assertFalse(prepared.temporaryFile.exists())
        } finally {
            source.delete()
        }
    }

    @Test
    fun preparesCategoryBasedFilenameSuggestions() = runBlocking {
        val source = sharedFile("filename-source.jpg").apply { writeText("jpeg") }

        try {
            assertPreparedFilename(source, "Invoice\nVAT", "invoice.pdf")
            assertPreparedFilename(source, "Receipt\nSubtotal", "receipt.pdf")
            assertPreparedFilename(source, "Dear reader\nRegards", "letter.pdf")
            assertPreparedFilename(source, "Application form\nPlease complete", "form.pdf")
            assertPreparedFilename(source, "Unrelated scan text", "document.pdf")
            assertPreparedFilename(source, "", "document.pdf")
        } finally {
            source.delete()
        }
    }

    @Test
    fun createDocumentReceivesSuggestionAndReturnsUserEditedDestinationUri() {
        val contract = ActivityResultContracts.CreateDocument("application/pdf")
        val intent = contract.createIntent(context, "invoice.pdf")
        val userEditedDestination = Uri.parse(
            "content://org.synapseworks.pageharbor.test/user-edited-name.pdf",
        )

        assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
        assertEquals("invoice.pdf", intent.getStringExtra(Intent.EXTRA_TITLE))
        assertEquals(
            userEditedDestination,
            contract.parseResult(Activity.RESULT_OK, Intent().setData(userEditedDestination)),
        )
    }

    @Test
    fun deletesTemporaryPdfWhenDestinationCannotBeOpened() = runBlocking {
        val source = sharedFile("destination-failure-source.jpg").apply { writeText("jpeg") }
        val generator = RecordingGenerator()
        val coordinator = LocalSearchablePdfExportCoordinator(
            context = context,
            ocrEngine = FailingOcrEngine,
            generator = generator,
        )

        try {
            val prepared = coordinator.prepare(
                SearchablePdfExportRequest(
                    pageUris = listOf(fileUri(source)),
                    ocrResult = OcrResult(listOf(pageResult())),
                ),
            ) as SearchablePdfPreparedExport.Ready

            val result = coordinator.writePreparedExport(
                prepared,
                android.net.Uri.parse("content://missing.destination/searchable.pdf"),
            )

            assertEquals(
                SearchablePdfExportResult.Failure(
                    SearchablePdfExportError.DESTINATION_UNAVAILABLE,
                ),
                result,
            )
            assertFalse(prepared.temporaryFile.exists())
        } finally {
            source.delete()
        }
    }

    @Test
    fun unavailablePrivateCacheReturnsSafePreparationFailureWithoutGenerating() = runBlocking {
        val source = sharedFile("private-cache-source.jpg").apply { writeText("jpeg") }
        val generator = RecordingGenerator()
        val coordinator = LocalSearchablePdfExportCoordinator(
            context = context,
            ocrEngine = FailingOcrEngine,
            generator = generator,
            createTemporaryPdfFile = { null },
        )

        try {
            assertEquals(
                SearchablePdfPreparedExport.Failure(
                    SearchablePdfPreparationError.TEMPORARY_STORAGE_UNAVAILABLE,
                ),
                coordinator.prepare(
                    SearchablePdfExportRequest(
                        pageUris = listOf(fileUri(source)),
                        ocrResult = OcrResult(listOf(pageResult())),
                    ),
                ),
            )
            assertEquals(0, generator.requestCount)
            assertTrue(source.exists())
        } finally {
            source.delete()
        }
    }

    @Test
    fun unavailableSourceStreamReturnsSafePreparationFailureAndCleansPartialOutput() = runBlocking {
        val source = sharedFile("source-open-failure.jpg").apply { writeText("jpeg") }
        val generator = SourceReadingGenerator()
        val coordinator = LocalSearchablePdfExportCoordinator(
            context = context,
            ocrEngine = FailingOcrEngine,
            generator = generator,
            openSourceInputStream = { null },
        )

        try {
            assertEquals(
                SearchablePdfPreparedExport.Failure(
                    SearchablePdfPreparationError.GENERATION_FAILED,
                ),
                coordinator.prepare(
                    SearchablePdfExportRequest(
                        pageUris = listOf(fileUri(source)),
                        ocrResult = OcrResult(listOf(pageResult())),
                    ),
                ),
            )
            assertTrue(generator.outputFile != null)
            assertFalse(generator.outputFile!!.exists())
            assertTrue(source.exists())
        } finally {
            source.delete()
        }
    }

    @Test
    fun deletesTemporaryPdfWhenGenerationIsCancelled() = runBlocking {
        val source = sharedFile("cancelled-source.jpg").apply { writeText("jpeg") }
        val generator = CancellingGenerator()
        val coordinator = LocalSearchablePdfExportCoordinator(
            context = context,
            ocrEngine = FailingOcrEngine,
            generator = generator,
        )

        try {
            val failure = runCatching {
                coordinator.prepare(
                    SearchablePdfExportRequest(
                        pageUris = listOf(fileUri(source)),
                        ocrResult = OcrResult(listOf(pageResult())),
                    ),
                )
            }.exceptionOrNull()

            assertTrue(failure is CancellationException)
            assertTrue(generator.outputFile != null)
            assertFalse(generator.outputFile!!.exists())
        } finally {
            source.delete()
        }
    }

    @Test
    fun providerOpenFailuresAreSafeAndDeletePreparedOutput() = runBlocking {
        val source = sharedFile("provider-open-source.jpg").apply { writeText("jpeg") }
        val coordinator = LocalSearchablePdfExportCoordinator(
            context = context,
            ocrEngine = FailingOcrEngine,
            generator = RecordingGenerator(),
            openDestinationOutputStream = { throw IllegalStateException("provider unavailable") },
        )

        try {
            val prepared = prepare(coordinator, source)

            assertEquals(
                SearchablePdfExportResult.Failure(
                    SearchablePdfExportError.DESTINATION_UNAVAILABLE,
                ),
                coordinator.writePreparedExport(prepared, Uri.parse("content://test.provider/document.pdf")),
            )
            assertFalse(prepared.temporaryFile.exists())
            assertTrue(source.exists())
        } finally {
            source.delete()
        }
    }

    @Test
    fun nullProviderOutputStreamIsSafeAndDeletesPreparedOutput() = runBlocking {
        val source = sharedFile("null-provider-source.jpg").apply { writeText("jpeg") }
        val coordinator = LocalSearchablePdfExportCoordinator(
            context = context,
            ocrEngine = FailingOcrEngine,
            generator = RecordingGenerator(),
            openDestinationOutputStream = { null },
        )

        try {
            val prepared = prepare(coordinator, source)

            assertEquals(
                SearchablePdfExportResult.Failure(
                    SearchablePdfExportError.DESTINATION_UNAVAILABLE,
                ),
                coordinator.writePreparedExport(prepared, Uri.parse("content://test.provider/null.pdf")),
            )
            assertFalse(prepared.temporaryFile.exists())
            assertTrue(source.exists())
        } finally {
            source.delete()
        }
    }

    @Test
    fun providerWriteFlushAndCloseFailuresAreSafeAndDeletePreparedOutput() = runBlocking {
        val source = sharedFile("provider-write-source.jpg").apply { writeText("jpeg") }
        val failureModes = listOf(
            FailingOutputStream.Mode.IMMEDIATE_WRITE,
            FailingOutputStream.Mode.MID_COPY,
            FailingOutputStream.Mode.FLUSH,
            FailingOutputStream.Mode.CLOSE,
        )

        try {
            failureModes.forEach { mode ->
                val coordinator = LocalSearchablePdfExportCoordinator(
                    context = context,
                    ocrEngine = FailingOcrEngine,
                    generator = LargeRecordingGenerator(),
                    openDestinationOutputStream = { FailingOutputStream(mode) },
                )
                val prepared = prepare(coordinator, source)

                assertEquals(
                    SearchablePdfExportResult.Failure(SearchablePdfExportError.WRITE_FAILED),
                    coordinator.writePreparedExport(
                        prepared,
                        Uri.parse("content://test.provider/$mode.pdf"),
                    ),
                )
                assertFalse(prepared.temporaryFile.exists())
                assertTrue(source.exists())
            }
        } finally {
            source.delete()
        }
    }

    @Test
    fun cancellationDuringCopyDeletesPreparedOutputAndRemainsDistinctFromFailure() = runBlocking {
        val source = sharedFile("copy-cancellation-source.jpg").apply { writeText("jpeg") }
        val coordinator = LocalSearchablePdfExportCoordinator(
            context = context,
            ocrEngine = FailingOcrEngine,
            generator = LargeRecordingGenerator(),
            openDestinationOutputStream = { FailingOutputStream(FailingOutputStream.Mode.CANCELLATION) },
        )

        try {
            val prepared = prepare(coordinator, source)
            val cancellation = runCatching {
                coordinator.writePreparedExport(
                    prepared,
                    Uri.parse("content://test.provider/cancelled.pdf"),
                )
            }.exceptionOrNull()

            assertTrue(cancellation is CancellationException)
            assertFalse(prepared.temporaryFile.exists())
            assertTrue(source.exists())
        } finally {
            source.delete()
        }
    }

    @Test
    fun missingPreparedOutputAndRepeatedDiscardAreHarmless() = runBlocking {
        val source = sharedFile("missing-prepared-source.jpg").apply { writeText("jpeg") }
        val coordinator = LocalSearchablePdfExportCoordinator(
            context = context,
            ocrEngine = FailingOcrEngine,
            generator = RecordingGenerator(),
        )

        try {
            val prepared = prepare(coordinator, source)
            assertTrue(prepared.temporaryFile.delete())

            assertEquals(
                SearchablePdfExportResult.Failure(
                    SearchablePdfExportError.PREPARED_EXPORT_UNAVAILABLE,
                ),
                coordinator.writePreparedExport(
                    prepared,
                    Uri.parse("content://test.provider/missing.pdf"),
                ),
            )
            coordinator.discardPreparedExport(prepared)
            coordinator.discardPreparedExport(prepared)
            assertTrue(source.exists())
        } finally {
            source.delete()
        }
    }

    @Test
    fun deletionFailureDoesNotCrashOrExposeSuccess() = runBlocking {
        val source = sharedFile("deletion-failure-source.jpg").apply { writeText("jpeg") }
        val coordinator = LocalSearchablePdfExportCoordinator(
            context = context,
            ocrEngine = FailingOcrEngine,
            generator = RecordingGenerator(),
            openDestinationOutputStream = { null },
            deleteTemporaryFile = { false },
        )

        try {
            val prepared = prepare(coordinator, source)

            assertEquals(
                SearchablePdfExportResult.Failure(
                    SearchablePdfExportError.DESTINATION_UNAVAILABLE,
                ),
                coordinator.writePreparedExport(prepared, Uri.parse("content://test.provider/delete.pdf")),
            )
            assertTrue(prepared.temporaryFile.exists())
            assertTrue(source.exists())
            assertTrue(prepared.temporaryFile.delete())
        } finally {
            source.delete()
        }
    }

    @Test
    fun generatorFailureAfterTemporaryOutputCreationDeletesPartialOutput() = runBlocking {
        val source = sharedFile("partial-generation-source.jpg").apply { writeText("jpeg") }
        val generator = PartialFailingGenerator()
        val coordinator = LocalSearchablePdfExportCoordinator(
            context = context,
            ocrEngine = FailingOcrEngine,
            generator = generator,
        )

        try {
            assertEquals(
                SearchablePdfPreparedExport.Failure(
                    SearchablePdfPreparationError.GENERATION_FAILED,
                ),
                coordinator.prepare(
                    SearchablePdfExportRequest(
                        pageUris = listOf(fileUri(source)),
                        ocrResult = OcrResult(listOf(pageResult())),
                    ),
                ),
            )
            assertTrue(generator.outputFile != null)
            assertFalse(generator.outputFile!!.exists())
            assertTrue(source.exists())
        } finally {
            source.delete()
        }
    }

    private suspend fun assertPreparedFilename(source: File, text: String, expectedFilename: String) {
        val coordinator = LocalSearchablePdfExportCoordinator(
            context = context,
            ocrEngine = FailingOcrEngine,
            generator = RecordingGenerator(),
        )
        val prepared = coordinator.prepare(
            SearchablePdfExportRequest(
                pageUris = listOf(fileUri(source)),
                ocrResult = OcrResult(listOf(pageResult(text = text))),
            ),
        ) as SearchablePdfPreparedExport.Ready

        try {
            assertEquals(expectedFilename, prepared.filenameSuggestion.filename)
        } finally {
            coordinator.discardPreparedExport(prepared)
        }
    }

    private suspend fun prepare(
        coordinator: LocalSearchablePdfExportCoordinator,
        source: File,
    ): SearchablePdfPreparedExport.Ready = coordinator.prepare(
        SearchablePdfExportRequest(
            pageUris = listOf(fileUri(source)),
            ocrResult = OcrResult(listOf(pageResult())),
        ),
    ) as SearchablePdfPreparedExport.Ready

    private fun pageResult(text: String = "English: searchable text"): OcrPageResult = OcrPageResult(
        pageIndex = 0,
        text = text,
        layout = OcrPageLayout(
            imageWidthPx = 240,
            imageHeightPx = 320,
            lines = listOf(
                OcrTextLine(
                    text = text,
                    bounds = OcrTextBounds(left = 16f, top = 20f, right = 224f, bottom = 48f),
                ),
            ),
        ),
    )

    private fun writeJpeg(file: File) {
        val bitmap = Bitmap.createBitmap(240, 320, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.rgb(220, 235, 250))
            FileOutputStream(file).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun sharedFile(name: String): File {
        val directory = File(context.cacheDir, "shared-pdfs").apply { mkdirs() }
        return File(directory, "searchable-coordinator-$name")
    }

    private fun fileUri(file: File) = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )

    private class RecordingOcrEngine(private val result: OcrPageResult) : OcrEngine {
        var requestCount = 0

        override fun recognize(pages: List<OcrPage>): OcrResult {
            requestCount++
            return OcrResult(listOf(result))
        }
    }

    private class SourceRecordingOcrEngine(private val result: OcrPageResult) : OcrEngine {
        var receivedSourceBytes: ByteArray = byteArrayOf()
            private set

        override fun recognize(pages: List<OcrPage>): OcrResult {
            receivedSourceBytes = pages.single().openJpegStream().use { it.readBytes() }
            return OcrResult(listOf(result))
        }
    }

    private class VisualRecordingGenerator : SearchablePdfGenerator {
        var receivedOcrResult: OcrPageResult? = null
            private set
        var visualPixelIsGrayscale: Boolean = false
            private set

        override suspend fun generate(request: SearchablePdfRequest): SearchablePdfGenerationResult {
            val page = request.pages.single()
            val bitmap = page.openJpegStream().use { input -> BitmapFactory.decodeStream(input) }
            try {
                val pixel = checkNotNull(bitmap).getPixel(120, 160)
                visualPixelIsGrayscale =
                    kotlin.math.abs(Color.red(pixel) - Color.green(pixel)) <= 1 &&
                        kotlin.math.abs(Color.green(pixel) - Color.blue(pixel)) <= 1
            } finally {
                bitmap?.recycle()
            }
            receivedOcrResult = page.ocrResult
            request.outputFile.writeText("prepared searchable pdf")
            return SearchablePdfGenerationResult.Success(pageCount = 1, textLayerPageCount = 1)
        }
    }

    private class RecordingGenerator : SearchablePdfGenerator {
        var requestCount = 0

        override suspend fun generate(request: SearchablePdfRequest): SearchablePdfGenerationResult {
            requestCount++
            request.outputFile.writeText("prepared searchable pdf")
            return SearchablePdfGenerationResult.Success(pageCount = 1, textLayerPageCount = 1)
        }
    }

    private class LargeRecordingGenerator : SearchablePdfGenerator {
        override suspend fun generate(request: SearchablePdfRequest): SearchablePdfGenerationResult {
            request.outputFile.outputStream().use { output ->
                output.write(ByteArray(16 * 1024) { 1 })
            }
            return SearchablePdfGenerationResult.Success(pageCount = 1, textLayerPageCount = 1)
        }
    }

    private class PartialFailingGenerator : SearchablePdfGenerator {
        var outputFile: File? = null

        override suspend fun generate(request: SearchablePdfRequest): SearchablePdfGenerationResult {
            outputFile = request.outputFile
            request.outputFile.writeText("partial")
            return SearchablePdfGenerationResult.Failure(
                SearchablePdfGenerationError.GENERATION_FAILED,
            )
        }
    }

    private class SourceReadingGenerator : SearchablePdfGenerator {
        var outputFile: File? = null

        override suspend fun generate(request: SearchablePdfRequest): SearchablePdfGenerationResult {
            outputFile = request.outputFile
            request.outputFile.writeText("partial")
            request.pages.single().openJpegStream().use { input -> input.read() }
            return SearchablePdfGenerationResult.Success(pageCount = 1, textLayerPageCount = 0)
        }
    }

    private class FailingOutputStream(private val mode: Mode) : OutputStream() {
        enum class Mode {
            IMMEDIATE_WRITE,
            MID_COPY,
            FLUSH,
            CLOSE,
            CANCELLATION,
        }

        private var writeCalls = 0

        override fun write(b: Int) = write(byteArrayOf(b.toByte()))

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            writeCalls++
            when (mode) {
                Mode.IMMEDIATE_WRITE -> throw IOException("write failed")
                Mode.MID_COPY -> if (writeCalls > 1) throw IOException("copy failed")
                Mode.CANCELLATION -> throw CancellationException()
                Mode.FLUSH,
                Mode.CLOSE,
                -> Unit
            }
        }

        override fun flush() {
            if (mode == Mode.FLUSH) throw IOException("flush failed")
        }

        override fun close() {
            if (mode == Mode.CLOSE) throw IOException("close failed")
        }
    }

    private class CancellingGenerator : SearchablePdfGenerator {
        var outputFile: File? = null

        override suspend fun generate(request: SearchablePdfRequest): SearchablePdfGenerationResult {
            outputFile = request.outputFile
            request.outputFile.writeText("partial")
            throw CancellationException()
        }
    }

    private data object FailingOcrEngine : OcrEngine {
        override fun recognize(pages: List<OcrPage>): OcrResult = error("OCR must not run")
    }
}
