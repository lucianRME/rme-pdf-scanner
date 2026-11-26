package org.synapseworks.pageharbor.office

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import java.io.File

/** Independent diagnosis controls frozen before correcting any regression.
 * Every control is expected to reopen as one unencrypted page. These do not
 * replace the original Office inputs/references or weaken their thresholds.
 */
class OfficePlatformPdfControlTest {
    @Test fun independentAndPlatformGeneratedUnencryptedPagesReopen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val root = File(app.cacheDir, "m35-platform-controls").apply { mkdirs() }
        val reference = File(root, "independent.pdf")
        reference.writeBytes(instrumentation.context.assets.open("m35-preview/pptx-portrait.pdf").use { it.readBytes() })
        assertEquals("21df12943355b8729938a65a08ceaf898d9bed5566f4d79569f2f373c85b7f42", officeDigest(reference) {})
        for (text in listOf(false, true)) {
            val pdf = PdfDocument()
            try {
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(612, 792, 1).create())
                page.canvas.drawColor(Color.WHITE)
                page.canvas.drawRect(40f, 40f, 80f, 80f, Paint().apply { color = Color.BLUE })
                if (text) page.canvas.drawText("RME independent platform control", 40f, 120f, Paint().apply { color = Color.BLACK; textSize = 18f })
                pdf.finishPage(page)
                File(root, if (text) "platform-text.pdf" else "platform-graphics.pdf").outputStream().use(pdf::writeTo)
            } finally { pdf.close() }
        }
        val rows = JSONArray()
        for (id in listOf("independent", "platform-graphics", "platform-text")) {
            val file = File(root, "$id.pdf")
            val row = JSONObject().put("id", id).put("expectedPages", 1).put("sha256", officeDigest(file) {})
            try {
                PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use { row.put("actualPages", it.pageCount).put("passed", it.pageCount == 1) }
            } catch (failure: Exception) { row.put("passed", false).put("exceptionClass", failure.javaClass.simpleName) }
            rows.put(row)
        }
        File(root, "results.json").writeText(JSONObject().put("api", android.os.Build.VERSION.SDK_INT)
            .put("bundleEnabled", org.synapseworks.pageharbor.BuildConfig.M35_OFFICE_ENABLED).put("expected", "Three unencrypted single-page PDFs reopen without a password")
            .put("records", rows).toString())
        assertTrue("Unchanged independent/platform controls must all reopen", (0 until rows.length()).all { rows.getJSONObject(it).getBoolean("passed") })
    }
}
