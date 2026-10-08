package org.synapseworks.pageharbor.migration

import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidMigrationSourceRevalidatorInstrumentedTest {
    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    private val uri by lazy {
        Uri.parse("content://${targetContext.packageName}.migrationtestprovider/source")
    }
    private val adapter by lazy { AndroidMigrationSourceAdapter(targetContext) }
    private val revalidator by lazy { AndroidMigrationSourceRevalidator(adapter) }
    private var sourceFile: File? = null

    @Before
    fun setUp() {
        val root = File(targetContext.cacheDir, "migration-revalidation").apply { mkdirs() }
        sourceFile = File(root, "source-${System.nanoTime()}.pdf")
        writePdf(requireNotNull(sourceFile), 1)
        RevocableMigrationTestProvider.sourceFile = sourceFile
        RevocableMigrationTestProvider.denyReads = false
    }

    @After
    fun tearDown() {
        RevocableMigrationTestProvider.denyReads = false
        RevocableMigrationTestProvider.sourceFile = null
        sourceFile?.delete()
        sourceFile = null
    }

    @Test
    fun unchangedSourceRetainsProviderAndPdfEvidence() = runBlocking {
        val source = adapter.describe(uri, declaredContentType = MigrationContentType.PDF.mimeType)
        val inspected = InspectedMigrationSource(
            source = source,
            contentType = MigrationContentType.PDF,
            pageCount = 1,
        )

        val result = revalidator.revalidate(inspected, NeverCancelMigration)

        assertEquals(MigrationSourceRevalidationStatus.UNCHANGED, result.status)
        assertTrue(MigrationSourceRevalidationEvidence.STREAM_SIGNATURE in result.evidence)
        assertTrue(MigrationSourceRevalidationEvidence.PROVIDER_SIZE in result.evidence)
        assertTrue(MigrationSourceRevalidationEvidence.PDF_PAGE_COUNT in result.evidence)
    }

    @Test
    fun permissionLossIsUnavailableAndDoesNotReuseThePlan() = runBlocking {
        val source = adapter.describe(uri, declaredContentType = MigrationContentType.PDF.mimeType)
        RevocableMigrationTestProvider.denyReads = true

        val result = revalidator.revalidate(
            InspectedMigrationSource(source, MigrationContentType.PDF, pageCount = 1),
            NeverCancelMigration,
        )

        assertEquals(MigrationSourceRevalidationStatus.UNAVAILABLE, result.status)
    }

    @Test
    fun changedPdfPageCountRequiresReanalysisEvenWithoutMetadata() = runBlocking {
        writePdf(requireNotNull(sourceFile), 2)
        val source = MigrationSource(
            id = uri.toString(),
            displayName = "source.pdf",
            declaredContentType = MigrationContentType.PDF.mimeType,
            sizeBytes = null,
            modifiedAtMillis = null,
            discoveryIdentity = "source",
        )

        val result = revalidator.revalidate(
            InspectedMigrationSource(source, MigrationContentType.PDF, pageCount = 1),
            NeverCancelMigration,
        )

        assertEquals(MigrationSourceRevalidationStatus.CHANGED_REQUIRES_REANALYSIS, result.status)
        assertEquals(2, result.currentPageCount)
        assertTrue(MigrationSourceRevalidationEvidence.PDF_PAGE_COUNT in result.evidence)
    }

    @Test
    fun establishedHashMismatchRequiresReanalysis() = runBlocking {
        val source = adapter.describe(uri, declaredContentType = MigrationContentType.PDF.mimeType)
        val result = revalidator.revalidate(
            InspectedMigrationSource(
                source = source,
                contentType = MigrationContentType.PDF,
                pageCount = 1,
                contentSha256 = "0".repeat(64),
            ),
            NeverCancelMigration,
        )

        assertEquals(MigrationSourceRevalidationStatus.CHANGED_REQUIRES_REANALYSIS, result.status)
        assertTrue(MigrationSourceRevalidationEvidence.PREVIOUS_SHA256 in result.evidence)
    }

    private fun writePdf(destination: File, pageCount: Int) {
        val document = PdfDocument()
        try {
            repeat(pageCount) { index ->
                val page = document.startPage(
                    PdfDocument.PageInfo.Builder(80, 120, index + 1).create(),
                )
                document.finishPage(page)
            }
            FileOutputStream(destination, false).use(document::writeTo)
        } finally {
            document.close()
        }
        destination.setLastModified(System.currentTimeMillis())
    }

}
