package org.synapseworks.pageharbor.document.protectedpdf

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.LoadParams
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDSignatureField
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PasswordProtectedPdfInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private val password = "synthetic-open-password".toCharArray()

    @Before
    fun setUp() {
        PDFBoxResourceLoader.init(context)
        root = File(context.cacheDir, "password-pdf-test-${System.nanoTime()}")
        assertTrue(root.mkdirs())
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
        password.fill('\u0000')
    }

    @Test
    fun createsAes256CopyThatNeedsTheCorrectOpeningPasswordAndPreservesInput() = runBlocking {
        val source = source()
        val original = source.readBytes()
        val output = File(root, "protected.pdf")
        val result = PasswordProtectedPdf().create(source, output, password)

        assertEquals(PasswordPdfResult.Success(2), result)
        assertArrayEquals(original, source.readBytes())
        expectWrongPassword { PDDocument.load(output).close() }
        expectWrongPassword { PDDocument.load(output, "").close() }
        expectWrongPassword { PDDocument.load(output, "wrong-synthetic-password").close() }
        PDDocument.load(output, String(password)).use { document ->
            assertTrue(document.isEncrypted)
            assertEquals(2, document.numberOfPages)
            val encryption = requireNotNull(document.encryption)
            assertEquals(256, encryption.length)
            assertEquals(5, encryption.version)
            assertEquals(6, encryption.revision)
            val filters = encryption.cosObject.getDictionaryObject(COSName.getPDFName("CF")) as COSDictionary
            val standard = filters.getDictionaryObject(COSName.getPDFName("StdCF")) as COSDictionary
            assertEquals("AESV3", standard.getNameAsString(COSName.getPDFName("CFM")))
        }
        PDDocument.load(source).use { assertFalse(it.isEncrypted) }
        if (InstrumentationRegistry.getArguments().getString("m05IndependentFixture") == "true") {
            output.copyTo(File(context.cacheDir, "m05-independent-encrypted.pdf"), overwrite = true)
        }
        assertNoScratch()
    }

    @Test
    fun invalidPasswordsDoNotCreateFilesOrChangeInput() = runBlocking {
        val source = source()
        val before = source.readBytes()
        val invalid = listOf(
            charArrayOf(), "short".toCharArray(), "a".repeat(128).toCharArray(),
            "password\uD800".toCharArray(), "😀".repeat(8).toCharArray(), "password-₺".toCharArray(),
        )
        invalid.forEachIndexed { index, candidate ->
            val output = File(root, "invalid-$index.pdf")
            assertEquals(PasswordPdfResult.InvalidPassword, PasswordProtectedPdf().create(source, output, candidate))
            assertFalse(output.exists())
        }
        assertArrayEquals(before, source.readBytes())
        assertNoScratch()
    }

    @Test
    fun existingOutputAndSourceAreNeverOverwritten() = runBlocking {
        val source = source()
        val original = source.readBytes()
        val output = File(root, "existing.pdf").apply { writeBytes(byteArrayOf(8, 9, 10)) }
        assertEquals(PasswordPdfResult.InvalidSource, PasswordProtectedPdf().create(source, output, password))
        assertArrayEquals(byteArrayOf(8, 9, 10), output.readBytes())
        assertEquals(PasswordPdfResult.InvalidSource, PasswordProtectedPdf().create(source, source, password))
        assertArrayEquals(original, source.readBytes())
        assertNoScratch()
    }

    @Test
    fun malformedPdfDeletesOnlyItsClaimedOutputAndOwnedScratch() = runBlocking {
        val source = File(root, "invalid-source.pdf").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val output = File(root, "failed.pdf")
        val unrelated = File(root, "keep.bin").apply { writeBytes(byteArrayOf(5, 6)) }
        assertEquals(PasswordPdfResult.Failed, PasswordProtectedPdf().create(source, output, password))
        assertFalse(output.exists())
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), source.readBytes())
        assertArrayEquals(byteArrayOf(5, 6), unrelated.readBytes())
        assertNoScratch()
    }

    @Test
    fun alreadyCancelledCallCreatesNoOutput() = runBlocking {
        val source = source()
        val before = source.readBytes()
        val output = File(root, "cancelled-before.pdf")
        try {
            withContext(Job().apply { cancel() }) {
                PasswordProtectedPdf().create(source, output, password)
            }
            throw AssertionError("Expected cancellation")
        } catch (_: CancellationException) {
            // Expected: the caller never receives a PDF.
        }
        assertFalse(output.exists())
        assertArrayEquals(before, source.readBytes())
        assertNoScratch()
    }

    @Test(timeout = 30_000)
    fun cancellationAtIoResultDeliveryRemovesTheUndeliveredEncryptedCopy() {
        val source = source()
        val before = source.readBytes()
        val output = File(root, "cancelled-handoff.pdf")
        val caller = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + caller)
        val operation = scope.launch { PasswordProtectedPdf().create(source, output, password) }
        try {
            // Start the caller, then keep its continuation queued while IO finishes.
            caller.next().run()
            val completedIo = caller.next()
            assertTrue("The IO worker must have produced a protected copy before cancellation", output.isFile)
            operation.cancel()
            completedIo.run()
            while (!operation.isCompleted) caller.next().run()
            assertTrue(operation.isCancelled)
            assertFalse(output.exists())
            assertArrayEquals(before, source.readBytes())
            assertNoScratch()
        } finally {
            scope.cancel()
        }
    }

    @Test fun diskAndHeapAdmissionFailuresPreserveInputsAndCreateNoOutput() = runBlocking {
        val source = source()
        val before = source.readBytes()
        val output = File(root, "resource.pdf")
        assertEquals(PasswordPdfResult.StorageUnavailable,
            PasswordProtectedPdf(availableBytes = { 0 }).create(source, output, password))
        assertEquals(PasswordPdfResult.ResourceLimit,
            PasswordProtectedPdf(availableHeap = { 0 }).create(source, output, password))
        assertEquals(PasswordPdfResult.ResourceLimit,
            PasswordProtectedPdf(availableBytes = { throw OutOfMemoryError() }).create(source, output, password))
        assertFalse(output.exists())
        assertArrayEquals(before, source.readBytes())
        assertNoScratch()
    }

    @Test fun encryptedAndSignedInputsAreRejectedWithoutReplacingThem() = runBlocking {
        val source = source()
        val encrypted = File(root, "already.pdf")
        assertTrue(PasswordProtectedPdf().create(source, encrypted, password) is PasswordPdfResult.Success)
        val before = encrypted.readBytes()
        val output = File(root, "rejected.pdf")
        assertEquals(PasswordPdfResult.InvalidSource, PasswordProtectedPdf().create(encrypted, output, password))
        assertArrayEquals(before, encrypted.readBytes())
        val signed = File(root, "signed.pdf")
        PDDocument.load(source).use { document ->
            val form = PDAcroForm(document)
            document.documentCatalog.acroForm = form
            form.fields.add(PDSignatureField(form).apply { signature = PDSignature() })
            document.save(signed)
        }
        val signedBefore = signed.readBytes()
        assertEquals(PasswordPdfResult.InvalidSource, PasswordProtectedPdf().create(signed, output, password))
        assertArrayEquals(signedBefore, signed.readBytes())
        assertFalse(output.exists())
        assertNoScratch()
    }

    @Test fun supportedUnicodeAndBoundaryPasswordsOpenWithoutNormalizationOrTruncation() = runBlocking {
        val source = source()
        val before = source.readBytes()
        listOf("a".repeat(127), "ș".repeat(63), "\uD840\uDD00".repeat(8), "אבגדהוזחט", "  word pass  ").forEachIndexed { index, text ->
            val chars = text.toCharArray()
            val output = File(root, "boundary-$index.pdf")
            try {
                assertTrue(PasswordProtectedPdf().create(source, output, chars) is PasswordPdfResult.Success)
                expectWrongPassword { PDDocument.load(output, "wrong-synthetic-password").close() }
                PDDocument.load(output, text).use { assertEquals(2, it.numberOfPages) }
                if (InstrumentationRegistry.getArguments().getString("m05IndependentFixture") == "true") {
                    output.copyTo(File(context.cacheDir, "m05-independent-boundary-$index.pdf"), overwrite = true)
                }
            } finally { chars.fill('\u0000') }
        }
        assertArrayEquals(before, source.readBytes())
        assertNoScratch()
    }

    @Test @SdkSuppress(minSdkVersion = 35)
    fun nativeReaderRendersEveryProtectedPageIdenticallyIncludingCropAndRotation() = runBlocking {
        val input = source()
        val shaped = File(root, "shaped.pdf")
        PDDocument.load(input).use {
            it.getPage(0).cropBox = PDRectangle(10f, 10f, 400f, 500f)
            it.getPage(1).rotation = 90
            it.save(shaped)
        }
        val original = shaped.readBytes()
        val output = File(root, "native-encrypted.pdf")
        assertTrue(PasswordProtectedPdf().create(shaped, output, password) is PasswordPdfResult.Success)
        fun render(file: File, secret: String?, index: Int): Bitmap {
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = try {
                if (secret == null) PdfRenderer(descriptor)
                else PdfRenderer(descriptor, LoadParams.Builder().setPassword(secret).build())
            } catch (failure: Throwable) { descriptor.close(); throw failure }
            return renderer.use { pdf -> pdf.openPage(index).use { page ->
                val factor = 400f / maxOf(page.width, page.height)
                Bitmap.createBitmap(maxOf(1, (page.width * factor).toInt()), maxOf(1, (page.height * factor).toInt()), Bitmap.Config.ARGB_8888).also {
                    it.eraseColor(Color.WHITE)
                    page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            } }
        }
        repeat(2) { index ->
            val before = render(shaped, null, index)
            val after = render(output, String(password), index)
            try { assertTrue(before.sameAs(after)) }
            finally { before.recycle(); after.recycle() }
        }
        assertArrayEquals(original, shaped.readBytes())
        assertNoScratch()
    }

    private fun source(): File = File(root, "synthetic.pdf").also { file ->
        PDDocument().use { document ->
            repeat(2) { index ->
                val page = PDPage(PDRectangle.A4)
                document.addPage(page)
                PDPageContentStream(document, page).use { stream ->
                    stream.setLineWidth(2f)
                    stream.moveTo(40f, 40f + index * 20)
                    stream.lineTo(100f, 100f + index * 20)
                    stream.stroke()
                }
            }
            document.save(file)
        }
    }

    private fun assertNoScratch() {
        assertFalse(root.listFiles().orEmpty().any { it.name.startsWith("password-work-") })
    }

    private fun expectWrongPassword(open: () -> Unit) {
        try {
            open()
            throw AssertionError("Expected an opening password to be required")
        } catch (_: InvalidPasswordException) {
            // Expected: no document content is available with this password.
        }
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val tasks = LinkedBlockingQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.put(block) }
        fun next(): Runnable {
            val next = tasks.poll(15, TimeUnit.SECONDS)
            assertNotNull("Expected the coroutine continuation", next)
            return requireNotNull(next)
        }
    }
}
