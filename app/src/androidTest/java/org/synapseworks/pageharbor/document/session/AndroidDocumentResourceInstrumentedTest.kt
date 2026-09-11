package org.synapseworks.pageharbor.document.session

import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidDocumentResourceInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun contentResolverReportsKnownSizesForJpegPngAndWebpReferences() {
        val directory = File(context.cacheDir, "shared-pdfs").apply { mkdirs() }
        val fixtures = listOf("page.jpg", "page.png", "page.webp").mapIndexed { index, name ->
            File(directory, "metadata-$name").apply {
                writeBytes(ByteArray(index + 11) { 7 })
            }
        }

        try {
            fixtures.forEach { file ->
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )

                assertEquals(
                    DocumentImageMetadata(sourceByteCount = file.length()),
                    context.contentResolver.readDocumentImageMetadata(uri),
                )
            }
        } finally {
            fixtures.forEach(File::delete)
        }
    }
}
