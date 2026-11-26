package org.synapseworks.pageharbor.office

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Independent frozen PDFs and full-page oracle; test-only oracle buffers are
 * separate from production's sequential worker/display bitmap accounting.
 */
class OfficePreviewInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    private fun pixels(bitmap: Bitmap): String {
        val digest = MessageDigest.getInstance("SHA-256"); val row = IntArray(bitmap.width); val bytes = ByteArray(bitmap.width * 4)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            row.forEachIndexed { index, value -> for (component in 0..3) bytes[index * 4 + component] = (value ushr (24 - component * 8)).toByte() }
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    @Test fun transferredPixelsMatchFrozenFullPageOracleExactly() = runBlocking {
        Assume.assumeTrue("Qualified local bundle required", org.synapseworks.pageharbor.BuildConfig.M35_OFFICE_ENABLED)
        val manifest = JSONObject(instrumentation.context.assets.open("m35-preview/manifest.json").bufferedReader().use { it.readText() }).getJSONArray("cases")
        for (index in 0 until manifest.length()) {
            val case = manifest.getJSONObject(index)
            val file = File(app.cacheDir, "m35-preview-${java.util.UUID.randomUUID()}.pdf")
            try {
                file.writeBytes(instrumentation.context.assets.open("m35-preview/" + case.getString("file")).use { it.readBytes() })
                assertEquals(case.getString("sha256"), officeDigest(file) {})
                PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use { pdf ->
                    for (page in 0 until pdf.pageCount) {
                        val actual = previewOffice(app, file, page, pdf.pageCount)
                        try {
                            pdf.openPage(page).use { source ->
                                val scale = 1280.0 / maxOf(source.width, source.height)
                                val expected = Bitmap.createBitmap(maxOf(1, (source.width * scale).toInt()), maxOf(1, (source.height * scale).toInt()), Bitmap.Config.ARGB_8888)
                                try {
                                    expected.eraseColor(Color.WHITE); source.render(expected, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    assertEquals(expected.width, actual.width); assertEquals(expected.height, actual.height)
                                    assertEquals("Unchanged full-page pixel gate: " + case.getString("id") + ":" + page, pixels(expected), pixels(actual))
                                } finally { expected.recycle() }
                            }
                        } finally { actual.recycle() }
                    }
                }
            } finally { file.delete() }
        }
    }
}
