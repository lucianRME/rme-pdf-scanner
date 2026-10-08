package org.synapseworks.pageharbor.migration

import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidMigrationImportPlanningInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixtureRoot = File(context.cacheDir, "shared-pdfs").apply { mkdirs() }

    @Test
    fun androidAdapterReadsPdfPageTreeWithoutCreatingLibraryState() = runBlocking {
        val pdf = pdfFixture("planning-pages", pageCount = 3)
        try {
            val adapter = AndroidMigrationSourceAdapter(context)
            val source = adapter.describe(uri(pdf), origin = MigrationSourceOrigin.FILE_SELECTION)

            val plan = withContext(Dispatchers.IO) {
                BulkMigrationPlanner(adapter).preview(listOf(source))
            }

            val document = plan.documents.single()
            assertEquals(3, document.pages.size)
            assertEquals(listOf(0, 1, 2), document.pages.map(MigrationPlannedPage::sourcePageIndex))
            assertEquals(MigrationOriginalAssetRole.ORIGINAL_PDF, document.originalAssets.single().role)
            assertEquals(1, plan.sourceSummary.plannedCount)
        } finally {
            pdf.delete()
        }
    }

    @Test
    fun providerEnumerationOrderDoesNotChangeGenericFilePlan() = runBlocking {
        val alpha = pdfFixture("alpha", pageCount = 1)
        val zeta = pdfFixture("zeta", pageCount = 2)
        try {
            val adapter = AndroidMigrationSourceAdapter(context)
            val sources = listOf(alpha, zeta).map { file -> adapter.describe(uri(file)) }

            val forward = withContext(Dispatchers.IO) {
                BulkMigrationPlanner(adapter).preview(sources)
            }
            val reversed = withContext(Dispatchers.IO) {
                BulkMigrationPlanner(adapter).preview(sources.reversed())
            }

            assertEquals(forward, reversed)
        } finally {
            alpha.delete()
            zeta.delete()
        }
    }

    @Test
    fun malformedPdfAndLostPermissionAreAccountedWithoutStoppingValidSources() = runBlocking {
        val valid = pdfFixture("valid", pageCount = 1)
        val malformed = File(fixtureRoot, "planning-malformed.pdf").apply {
            writeBytes("%PDF-1.7\nnot-a-pdf".encodeToByteArray())
        }
        try {
            val adapter = AndroidMigrationSourceAdapter(context)
            val sources = listOf(
                adapter.describe(uri(valid)),
                adapter.describe(uri(malformed)),
                MigrationSource(
                    id = "content://missing.rme.test/no-permission",
                    displayName = "unreadable.pdf",
                    sizeBytes = null,
                    origin = MigrationSourceOrigin.FILE_SELECTION,
                ),
            )

            val plan = withContext(Dispatchers.IO) {
                BulkMigrationPlanner(adapter).preview(sources)
            }

            assertEquals(1, plan.documents.size)
            assertEquals(2, plan.rejectedSources.size)
            assertEquals(
                setOf(
                    MigrationSourceFailureDetail.MALFORMED_PDF,
                    MigrationSourceFailureDetail.STREAM_UNREADABLE,
                ),
                plan.rejectedSources.map(RejectedMigrationSource::failureDetail).toSet(),
            )
            assertEquals(3, plan.sourceAccounting.size)
        } finally {
            valid.delete()
            malformed.delete()
        }
    }

    @Test
    fun cancellationStopsAndroidSourceInspectionAndAccountsTheTail() = runBlocking {
        val files = List(5) { index -> pdfFixture("cancel-$index", pageCount = 1) }
        try {
            val adapter = AndroidMigrationSourceAdapter(context)
            val sources = files.map { file -> adapter.describe(uri(file)) }
            var inspected = 0

            val plan = withContext(Dispatchers.IO) {
                BulkMigrationPlanner(adapter).preview(
                    sources = sources,
                    cancellationSignal = MigrationCancellationSignal { inspected >= 2 },
                    progressListener = MigrationPreviewProgressListener { completed, _ ->
                        inspected = completed
                    },
                )
            }

            assertTrue(plan.wasCancelled)
            assertEquals(2, plan.inspectedSourceCount)
            assertEquals(3, plan.sourceSummary.cancelledUnprocessedCount)
        } finally {
            files.forEach(File::delete)
        }
    }

    private fun pdfFixture(name: String, pageCount: Int): File {
        val file = File(fixtureRoot, "m1-$name.pdf")
        val document = PdfDocument()
        try {
            repeat(pageCount) { index ->
                val info = PdfDocument.PageInfo.Builder(240, 320, index + 1).create()
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

    private fun uri(file: File): Uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )
}
