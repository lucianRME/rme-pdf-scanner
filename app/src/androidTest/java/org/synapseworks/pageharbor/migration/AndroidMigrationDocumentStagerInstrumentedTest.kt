package org.synapseworks.pageharbor.migration

import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidMigrationDocumentStagerInstrumentedTest {
    @Test
    fun bulkPdfPathStagesTwentyFivePagesAndPreservesOriginal() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val testRoot = File(context.cacheDir, "migration-stager-test-${System.nanoTime()}")
        val source = File(testRoot, "source.pdf")
        val workspaces = File(testRoot, "workspaces")
        assertTrue(testRoot.mkdirs())
        writePdf(source, pageCount = 25)
        val plan = MigrationDocumentPlan(
            id = "twenty-five-page-pdf",
            suggestedTitle = "Large PDF",
            relativeFolderPath = emptyList(),
            sources = listOf(
                InspectedMigrationSource(
                    source = MigrationSource(
                        id = "test-source",
                        displayName = "large.pdf",
                        declaredContentType = MigrationContentType.PDF.mimeType,
                        sizeBytes = source.length(),
                    ),
                    contentType = MigrationContentType.PDF,
                ),
            ),
            grouping = MigrationDocumentGrouping.SINGLE_PDF,
        )
        val stager = AndroidMigrationDocumentStager(
            workspaceRoot = workspaces,
            sourceAccess = MigrationSourceAccess { FileInputStream(source) },
        )

        try {
            val result = stager.stage(
                plan,
                MigrationPublicationContext(NeverCancelMigration) { _, _ -> },
            )

            assertTrue(result is MigrationStagingResult.Ready)
            val staged = (result as MigrationStagingResult.Ready).document
            staged.use {
                assertEquals(25, staged.pages.size)
                assertNotNull(staged.originalPdf)
                assertTrue(staged.pages.all { it.byteCount > 0L && it.file.isFile })
            }
            assertFalse(workspaces.listFiles().orEmpty().any())
        } finally {
            testRoot.deleteRecursively()
        }
    }

    private fun writePdf(destination: File, pageCount: Int) {
        val document = PdfDocument()
        try {
            repeat(pageCount) { index ->
                val page = document.startPage(
                    PdfDocument.PageInfo.Builder(100, 140, index + 1).create(),
                )
                document.finishPage(page)
            }
            FileOutputStream(destination).use(document::writeTo)
        } finally {
            document.close()
        }
        assertTrue(destination.isFile && destination.length() > 0L)
    }
}
