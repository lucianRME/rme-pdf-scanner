package org.synapseworks.pageharbor.migration

import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BulkMigrationPlannerTest {
    @Test
    fun onePdfBecomesOneDocumentAndSignatureOverridesProviderMetadata() {
        val pdf = source(
            id = "pdf",
            name = "Report.pdf",
            folder = listOf("Clients", "2024"),
            declaredType = "text/plain",
            size = null,
            modified = null,
        )
        val mislabeledImage = source(
            id = "image",
            name = "Receipt.pdf",
            folder = listOf("Clients", "2024"),
            declaredType = "application/pdf",
            size = 12L,
        )
        val preview = planner(
            mapOf(
                pdf.id to pdfBytes(),
                mislabeledImage.id to jpegBytes(),
            ),
        ).preview(listOf(pdf, mislabeledImage))

        assertEquals(2, preview.documents.size)
        assertEquals(
            MigrationDocumentGrouping.SINGLE_PDF,
            preview.documents.single { it.suggestedTitle == "Report" }.grouping,
        )
        assertEquals(
            MigrationContentType.JPEG,
            preview.documents.single { it.suggestedTitle == "Receipt" }.sources.single().contentType,
        )
        assertEquals(listOf("Clients", "2024"), preview.documents.first().relativeFolderPath)
        assertNull(
            preview.documents.single { it.suggestedTitle == "Report" }
                .sources.single().source.modifiedAtMillis,
        )
        assertNull(preview.storageEstimate.estimatedRequiredBytes)
        assertTrue(preview.storageEstimate.estimateIsIncomplete)
        assertEquals(1, preview.storageEstimate.unknownSizeSourceCount)
    }

    @Test
    fun explicitConsecutivePageNamesGroupInNaturalOrderButAmbiguousNamesStaySeparate() {
        val folder = listOf("Exports", "Invoices")
        val sources = listOf(
            source("p3", "Invoice page 3.jpg", folder),
            source("photo2", "photo2.jpg", folder),
            source("p1", "Invoice page 1.jpg", folder),
            source("photo1", "photo1.jpg", folder),
            source("p2", "Invoice page 2.jpg", folder),
        )
        val preview = planner(sources.associate { it.id to jpegBytes() }).preview(sources)

        val sequence = preview.documents.single {
            it.grouping == MigrationDocumentGrouping.HIGH_CONFIDENCE_IMAGE_SEQUENCE
        }
        assertEquals("Invoice", sequence.suggestedTitle)
        assertEquals(listOf("p1", "p2", "p3"), sequence.sources.map { it.source.id })
        assertEquals(folder, sequence.relativeFolderPath)
        assertEquals(
            listOf("photo1", "photo2"),
            preview.documents
                .filter { it.grouping == MigrationDocumentGrouping.SINGLE_IMAGE }
                .map { it.suggestedTitle },
        )
    }

    @Test
    fun pageSequenceRequiresStartingAtOneWithNoGaps() {
        val sources = listOf(
            source("one", "Case page 1.png"),
            source("three", "Case page 3.png"),
        )
        val preview = planner(sources.associate { it.id to pngBytes() }).preview(sources)

        assertEquals(2, preview.documents.size)
        assertTrue(preview.documents.all { it.grouping == MigrationDocumentGrouping.SINGLE_IMAGE })
    }

    @Test
    fun bulkImageDocumentIsNotSubjectToInteractiveTwentyPageLimit() {
        val sources = (25 downTo 1).map { page ->
            source("page-$page", "Archive page $page.jpg")
        }
        val preview = planner(sources.associate { it.id to jpegBytes() }).preview(sources)

        assertEquals(1, preview.documents.size)
        assertEquals(25, preview.documents.single().sources.size)
        assertEquals(
            (1..25).map { "page-$it" },
            preview.documents.single().sources.map { it.source.id },
        )
    }

    @Test
    fun unreadableAndUnsupportedSourcesAreIsolatedFromTheRestOfTheBatch() {
        val good = source("good", "Good.pdf")
        val unsupported = source("unsupported", "Notes.txt")
        val unreadable = source("unreadable", "Missing.pdf")
        val access = MigrationSourceAccess { source ->
            when (source.id) {
                good.id -> ByteArrayInputStream(pdfBytes())
                unsupported.id -> ByteArrayInputStream("plain text".encodeToByteArray())
                else -> throw IOException("missing")
            }
        }

        val preview = BulkMigrationPlanner(access).preview(listOf(unreadable, good, unsupported))

        assertEquals(1, preview.documents.size)
        assertEquals(
            setOf(MigrationSourceIssue.UNREADABLE, MigrationSourceIssue.UNSUPPORTED_CONTENT),
            preview.rejectedSources.map { it.issue }.toSet(),
        )
        assertFalse(preview.wasCancelled)
        assertEquals(3, preview.inspectedSourceCount)
    }

    @Test
    fun previewCancellationStopsBeforeOpeningRemainingSources() {
        val sources = List(100) { index -> source("id-$index", "File $index.pdf") }
        var opened = 0
        val planner = BulkMigrationPlanner(
            MigrationSourceAccess {
                opened += 1
                ByteArrayInputStream(pdfBytes())
            },
        )
        val preview = planner.preview(
            sources = sources,
            cancellationSignal = MigrationCancellationSignal { opened >= 7 },
        )

        assertTrue(preview.wasCancelled)
        assertEquals(7, preview.inspectedSourceCount)
        assertEquals(7, preview.documents.size)
        assertEquals(7, opened)
    }

    @Test
    fun providerTitleIsBoundedAndCannotBecomeAPath() {
        val unsafe = source(
            id = "unsafe",
            name = "../A\\B\u0000" + "x".repeat(300) + ".pdf",
        )

        val title = planner(mapOf(unsafe.id to pdfBytes()))
            .preview(listOf(unsafe)).documents.single().suggestedTitle

        assertFalse('/' in title)
        assertFalse('\\' in title)
        assertFalse(title.any(Char::isISOControl))
        assertTrue(title.length <= 255)
    }

    private fun planner(bytes: Map<String, ByteArray>) = BulkMigrationPlanner(
        MigrationSourceAccess { source ->
            ByteArrayInputStream(bytes[source.id] ?: throw IOException("missing"))
        },
    )

    private fun source(
        id: String,
        name: String,
        folder: List<String> = emptyList(),
        declaredType: String? = null,
        size: Long? = 10L,
        modified: Long? = 20L,
    ) = MigrationSource(
        id = id,
        displayName = name,
        relativeFolderPath = folder,
        declaredContentType = declaredType,
        sizeBytes = size,
        modifiedAtMillis = modified,
    )

    private fun pdfBytes() = "%PDF-1.7\n".encodeToByteArray()
    private fun jpegBytes() = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0)
    private fun pngBytes() = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )
}
