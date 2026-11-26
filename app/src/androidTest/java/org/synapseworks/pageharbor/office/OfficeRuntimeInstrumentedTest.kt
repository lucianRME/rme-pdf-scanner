package org.synapseworks.pageharbor.office

import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Original frozen fifteen inputs through the production coordinator, not probes. */
class OfficeRuntimeInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    private lateinit var root: File
    @Before fun prepare() { Assume.assumeTrue("Qualified local bundle required", org.synapseworks.pageharbor.BuildConfig.M35_OFFICE_ENABLED); root = File(app.cacheDir, "shared-pdfs/m35-runtime-${UUID.randomUUID()}").apply { mkdirs() } }
    @After fun cleanup() { if (::root.isInitialized) root.deleteRecursively() }
    private fun uri(file: File) = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
    @Test fun originalCorpusProductionImportConversionAndEveryPagePreview() = runBlocking {
        val manifest = JSONObject(instrumentation.context.assets.open("m35/manifest.json").bufferedReader().use { it.readText() }).getJSONArray("cases")
        for (index in 0 until manifest.length()) {
            val case = manifest.getJSONObject(index); val id = case.getString("id"); val kind = case.getString("kind")
            val input = File(root, "$id.$kind").apply { writeBytes(instrumentation.context.assets.open("m35/$id.$kind").use { it.readBytes() }) }
            val before = input.readBytes()
            OfficeSource.open(app, uri(input), kind) { }.use { source ->
                assertEquals(case.getString("sha256"), source.digest)
                val expected = case.getString("expectedProduct")
                if (expected != "ADMITTED") {
                    try { inspectOfficeSource(app, source) {}; fail("Original refusal unexpectedly admitted: $id") }
                    catch (failure: Exception) { assertEquals(expected, org.synapseworks.m35.workerprobe.OfficeAdmission.failureCode(failure)) }
                } else {
                    inspectOfficeSource(app, source) {}
                    val output = File(root, "$id.pdf")
                    val result = withTimeout(75000) { convertOffice(app, source, output) }
                    assertTrue(result.pages in 1..10)
                    assertTrue(result.metrics.withinLimits())
                    File(app.cacheDir, "m35-output").apply { mkdirs() }.let { folder ->
                        val metrics = result.metrics
                        File(folder, "$id-metrics.json").writeText(JSONObject().apply {
                            put("initializationMs", metrics.initializationMs); put("conversionMs", metrics.conversionMs)
                            put("javaBytes", metrics.javaBytes); put("nativeBytes", metrics.nativeBytes); put("aggregatePssBytes", metrics.pssBytes)
                            put("temporaryBytes", metrics.temporaryBytes); put("outputBytes", metrics.copiedBytes)
                            put("sourceSha256", source.digest); put("outputSha256", officeDigest(output) {})
                        }.toString())
                    }
                    File(app.cacheDir, "m35-output").apply { mkdirs() }.let { output.copyTo(File(it, "$id.pdf"), overwrite = true) }
                    for (page in 0 until result.pages) {
                        val bitmap = previewOffice(app, output, page, result.pages)
                        assertTrue(bitmap.width in 1..1280 && bitmap.height in 1..1280); bitmap.recycle()
                    }
                    val reviewed = OfficeOutput(output, officeDigest(output) {}, result.pages, "synthetic-$id")
                    val separate = File(root, "$id-saved.pdf").apply { writeBytes(byteArrayOf()) }
                    saveOffice(app, source, reviewed, Uri.parse("content://synthetic.separate/$id"), {},
                        { ParcelFileDescriptor.open(separate, ParcelFileDescriptor.MODE_READ_WRITE) }, true,
                        { ParcelFileDescriptor.open(separate, ParcelFileDescriptor.MODE_READ_ONLY) })
                    assertArrayEquals(output.readBytes(), separate.readBytes())
                    // Preserve synthetic PDFs for independent host inspection in debuggable
                    // builds. Optimized checks still inspect bytes in-process and actual SAF.
                    File(app.cacheDir, "m35-output").apply { mkdirs() }.let { output.copyTo(File(it, "$id.pdf"), overwrite = true) }
                }
                assertArrayEquals(before, input.readBytes())
            }
        }
    }
    @Test fun aliasesSourceReplacementCancellationAndFrozenOutputAreRejected() = runBlocking {
        val original = File(root, "source.docx").apply { writeBytes(instrumentation.context.assets.open("m35/docx-columns.docx").use { it.readBytes() }) }
        val originalBytes = original.readBytes()
        OfficeSource.open(app, uri(original), "docx") {}.use { source ->
            val file = File(root, "copy.pdf").apply { writeText("SYNTHETIC REVIEWED BYTES") }
            val output = OfficeOutput(file, officeDigest(file) {}, 1, "synthetic-owner")
            var opens = 0
            fun target() = ParcelFileDescriptor.open(original, ParcelFileDescriptor.MODE_READ_WRITE).also { opens++ }
            try { saveOffice(app, source, output, source.uri, {}, ::target, true); fail() } catch (failure: OfficeFailure) { assertEquals("DESTINATION_REFUSED", failure.code) }
            assertEquals(0, opens)
            try { saveOffice(app, source, output, Uri.parse("content://synthetic.alias/1"), {}, ::target, true); fail() } catch (failure: OfficeFailure) { assertEquals("DESTINATION_REFUSED", failure.code) }
            assertArrayEquals(originalBytes, original.readBytes())
            try { saveOffice(app, source, output, Uri.parse("content://synthetic.cancel/1"), { throw CancellationException() }, ::target, true); fail() } catch (_: CancellationException) { }
            assertEquals(1, opens)
            file.appendText("CHANGED")
            try { saveOffice(app, source, output, Uri.parse("content://synthetic.changed/1"), {}, ::target, true); fail() } catch (failure: OfficeFailure) { assertEquals("OUTPUT_CHANGED", failure.code) }
            assertEquals(1, opens)
            val retired = File(root, "retired.docx"); assertTrue(original.renameTo(retired)); original.writeText("SYNTHETIC REPLACEMENT")
            try { source.unchanged {}; fail() } catch (failure: OfficeFailure) { assertEquals("SOURCE_CHANGED", failure.code) }
            assertArrayEquals(originalBytes, retired.readBytes())
        }
    }
    @Test fun partialCancellationAndDestinationReplacementPreserveUserOwnership() {
        val original = File(root, "source.docx").apply { writeBytes(instrumentation.context.assets.open("m35/docx-columns.docx").use { it.readBytes() }) }
        val before = original.readBytes()
        OfficeSource.open(app, uri(original), "docx") {}.use { source ->
            val file = File(root, "reviewed.pdf").apply { writeBytes(ByteArray(256 * 1024) { (it % 251).toByte() }) }
            val output = OfficeOutput(file, officeDigest(file) {}, 1, "synthetic-owner")
            val target = File(root, "partial.pdf").apply { createNewFile() }
            try {
                saveOffice(app, source, output, Uri.parse("content://synthetic.partial/1"),
                    { if (target.length() > 0) throw CancellationException() },
                    { ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_WRITE) }, true,
                    { ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_ONLY) })
                fail("Cancellation must interrupt a real write")
            } catch (_: CancellationException) {}
            assertTrue(target.exists()); assertTrue(target.length() in 1 until file.length())
            val destination = File(root, "destination.pdf").apply { createNewFile() }
            val replacement = File(root, "replacement.pdf").apply { writeText("SYNTHETIC PROVIDER REPLACEMENT") }
            try {
                saveOffice(app, source, output, Uri.parse("content://synthetic.replaced/1"), {},
                    { ParcelFileDescriptor.open(destination, ParcelFileDescriptor.MODE_READ_WRITE) }, true,
                    { ParcelFileDescriptor.open(replacement, ParcelFileDescriptor.MODE_READ_ONLY) })
                fail("A replaced provider identity must not report success")
            } catch (failure: OfficeFailure) { assertEquals("DESTINATION_CHANGED", failure.code) }
            assertArrayEquals(file.readBytes(), destination.readBytes())
            assertEquals("SYNTHETIC PROVIDER REPLACEMENT", replacement.readText())
            assertArrayEquals(before, original.readBytes())
            assertTrue(file.delete())
            try { output.verify {}; fail("Missing output must not remain reviewed") } catch (_: java.io.FileNotFoundException) {}
            assertArrayEquals(before, original.readBytes())
        }
    }
}
