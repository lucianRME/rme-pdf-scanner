package org.synapseworks.pageharbor.migration

import java.io.ByteArrayInputStream
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationImportPlannerTest {
    @Test
    fun identicalEvidenceProducesIdenticalPlanAcrossProviderEnumerationOrders() {
        val sources = listOf(
            source("three", "zeta.pdf", folder = listOf("2025")),
            source("one", "alpha.pdf", folder = listOf("2024")),
            source("two", "beta.jpg", folder = listOf("2024")),
        )
        val access = accessFor(sources, pdfPages = mapOf("one" to 2, "three" to 1))
        val expected = BulkMigrationPlanner(access).preview(sources)

        repeat(25) { seed ->
            val actual = BulkMigrationPlanner(access).preview(sources.shuffled(Random(seed)))
            assertEquals(expected, actual)
        }
    }

    @Test
    fun explicitPageSequenceUsesStrongEvidenceAndNaturalPageOrder() {
        val sources = listOf(
            source("ten", "Invoice page 10.jpg"),
            source("two", "Invoice page 2.jpg"),
            source("one", "Invoice page 1.jpg"),
        ) + (3..9).map { page -> source("p$page", "Invoice page $page.jpg") }

        val plan = BulkMigrationPlanner(accessFor(sources)).preview(sources)

        val document = plan.documents.single()
        assertEquals(MigrationDocumentGrouping.HIGH_CONFIDENCE_IMAGE_SEQUENCE, document.grouping)
        assertEquals((1..10).toList(), document.pages.map(MigrationPlannedPage::factualPageNumber))
        assertTrue(document.pages.all {
            it.orderEvidence == MigrationEvidenceSource.EXPLICIT_PAGE_FILENAME
        })
        assertTrue(plan.ambiguities.isEmpty())
    }

    @Test
    fun explicitFilenameOrderOverridesConflictingSourceDeclaredOrder() {
        val pageOne = source("one", "Case page 1.jpg").copy(
            discoveryOrder = 1,
            discoveryOrderEvidence = MigrationDiscoveryOrderEvidence.SOURCE_DECLARED,
        )
        val pageTwo = source("two", "Case page 2.jpg").copy(
            discoveryOrder = 0,
            discoveryOrderEvidence = MigrationDiscoveryOrderEvidence.SOURCE_DECLARED,
        )

        val document = BulkMigrationPlanner(accessFor(listOf(pageOne, pageTwo)))
            .preview(listOf(pageTwo, pageOne))
            .documents
            .single()

        assertEquals(listOf("one", "two"), document.sources.map { it.source.id })
        assertEquals(
            listOf(MigrationEvidenceSource.EXPLICIT_PAGE_FILENAME, MigrationEvidenceSource.EXPLICIT_PAGE_FILENAME),
            document.pages.map(MigrationPlannedPage::orderEvidence),
        )
    }

    @Test
    fun sourceDeclaredOrderIsPreservedForIndependentSharedPdfs() {
        val zeta = source("zeta", "zeta.pdf").copy(
            discoveryOrder = 0,
            discoveryOrderEvidence = MigrationDiscoveryOrderEvidence.SOURCE_DECLARED,
        )
        val alpha = source("alpha", "alpha.pdf").copy(
            discoveryOrder = 1,
            discoveryOrderEvidence = MigrationDiscoveryOrderEvidence.SOURCE_DECLARED,
        )
        val sources = listOf(alpha, zeta)

        val plan = BulkMigrationPlanner(
            accessFor(sources, sources.associate { it.id to 1 }),
        ).preview(sources)

        assertEquals(listOf("zeta", "alpha"), plan.documents.map { it.sources.single().source.id })
        assertTrue(plan.documents.all { it.sources.size == 1 })
    }

    @Test
    fun weakOrIncompleteImageSequencesRemainSeparateAndExplicitlyAmbiguous() {
        val sources = listOf(
            source("page10", "page10.jpg", folder = listOf("plain")),
            source("page2", "page2.jpg", folder = listOf("plain")),
            source("page1", "page1.jpg", folder = listOf("plain")),
            source("scan10", "scan_010.jpg", folder = listOf("scanner")),
            source("scan2", "scan_002.jpg", folder = listOf("scanner")),
            source("scan1", "scan_001.jpg", folder = listOf("scanner")),
        )

        val plan = BulkMigrationPlanner(accessFor(sources)).preview(sources)

        assertEquals(6, plan.documents.size)
        assertTrue(plan.documents.all { it.grouping == MigrationDocumentGrouping.SINGLE_IMAGE })
        assertEquals(2, plan.ambiguities.count {
            it.kind == MigrationAmbiguityKind.POSSIBLE_IMAGE_SEQUENCE
        })
        assertEquals(
            listOf("page1", "page2", "page10"),
            plan.documents.filter { it.relativeFolderPath == listOf("plain") }
                .map { it.sources.single().source.id },
        )
        assertEquals(6, plan.sourceSummary.reviewRequiredCount)
        assertEquals(0, plan.sourceSummary.plannedCount)
    }

    @Test
    fun separatePdfsAndSameNamesInDifferentFoldersStaySeparate() {
        val sources = listOf(
            source("invoice", "invoice.pdf", folder = listOf("A")),
            source("invoice-copy", "invoice (1).pdf", folder = listOf("A")),
            source("other-folder", "invoice.pdf", folder = listOf("B")),
        )

        val plan = BulkMigrationPlanner(
            accessFor(sources, sources.associate { it.id to 1 }),
        ).preview(sources)

        assertEquals(3, plan.documents.size)
        assertTrue(plan.documents.all { it.grouping == MigrationDocumentGrouping.SINGLE_PDF })
        assertEquals(3, plan.documents.map(MigrationDocumentPlan::id).distinct().size)
        assertTrue(plan.duplicateCandidates.isEmpty())
    }

    @Test
    fun pdfPageTreeAndOriginalAssetArePlannedWithoutRasterization() {
        val pdf = source("pdf", "Archive.pdf", size = 4_096L)
        val access = CountingAccess(
            bytes = mapOf(pdf.id to pdfBytes()),
            pdfInspections = mapOf(pdf.id to MigrationPdfInspection.Readable(4)),
        )

        val document = BulkMigrationPlanner(access).preview(listOf(pdf)).documents.single()

        assertEquals(listOf(0, 1, 2, 3), document.pages.map(MigrationPlannedPage::sourcePageIndex))
        assertEquals(listOf(1, 2, 3, 4), document.pages.map(MigrationPlannedPage::factualPageNumber))
        assertEquals(MigrationOriginalAssetRole.ORIGINAL_PDF, document.originalAssets.single().role)
        assertTrue(document.originalAssets.single().preserveUnchanged)
        assertEquals(1, access.openCount.get())
        assertEquals(1, access.pdfInspectionCount.get())
    }

    @Test
    fun treeFolderProvenanceAndOriginalImagesRemainExplicit() {
        val sources = listOf(
            source("page-two", "Record page 2.jpg", folder = listOf("Clients", "2025")),
            source("page-one", "Record page 1.jpg", folder = listOf("Clients", "2025")),
        ).map { source -> source.copy(origin = MigrationSourceOrigin.DOCUMENT_TREE) }

        val plan = BulkMigrationPlanner(accessFor(sources)).preview(sources)

        val folder = plan.plannedFolders.single()
        assertEquals(listOf("Clients", "2025"), folder.relativePath)
        assertEquals(MigrationEvidenceSource.SAF_TREE_PATH, folder.evidence)
        val document = plan.documents.single()
        assertEquals(2, document.originalAssets.size)
        assertTrue(document.originalAssets.all {
            it.role == MigrationOriginalAssetRole.ORIGINAL_IMAGE && it.preserveUnchanged
        })
    }

    @Test
    fun factualTimestampsAndFilenameCandidateKeepDistinctProvenance() {
        val source = source(
            id = "dated",
            name = "Invoice 2025-07-09.pdf",
            created = 1_000L,
            modified = 2_000L,
        )

        val document = BulkMigrationPlanner(
            accessFor(listOf(source), mapOf(source.id to 1)),
        ).preview(listOf(source)).documents.single()

        assertEquals(3, document.dateCandidates.size)
        assertEquals(
            1_000L,
            document.dateCandidates.single {
                it.kind == MigrationDateCandidateKind.ORIGINAL_FILE_CREATED
            }.instantMillis,
        )
        assertEquals(
            2_000L,
            document.dateCandidates.single {
                it.kind == MigrationDateCandidateKind.ORIGINAL_FILE_MODIFIED
            }.instantMillis,
        )
        val filename = document.dateCandidates.single {
            it.kind == MigrationDateCandidateKind.SOURCE_DOCUMENT_DATE
        }
        assertEquals(LocalDate.of(2025, 7, 9).toEpochDay(), filename.epochDay)
        assertEquals(MigrationEvidenceSource.FILENAME_CANDIDATE, filename.evidence)
        assertFalse(filename.authoritativeForKind)
        assertTrue(MigrationAnalysisWarning.FILENAME_DATE_CANDIDATE_ONLY in document.warningCodes)
    }

    @Test
    fun missingAndAmbiguousFilenameDatesNeverBecomeDateCandidates() {
        val missing = source("missing", "Invoice.pdf")
        val ambiguous = source("ambiguous", "2024-01-02 to 2025-03-04.pdf")
        val sources = listOf(missing, ambiguous)

        val plan = BulkMigrationPlanner(
            accessFor(sources, sources.associate { it.id to 1 }),
        ).preview(sources)

        assertTrue(plan.documents.single { it.suggestedTitle == "Invoice" }.dateCandidates.isEmpty())
        val ambiguousDocument = plan.documents.single { it.suggestedTitle.startsWith("2024") }
        assertTrue(ambiguousDocument.dateCandidates.isEmpty())
        assertTrue(
            MigrationAnalysisWarning.AMBIGUOUS_FILENAME_DATE_IGNORED in
                ambiguousDocument.warningCodes,
        )
        assertEquals(1, plan.ambiguities.count {
            it.kind == MigrationAmbiguityKind.AMBIGUOUS_FILENAME_DATE
        })
    }

    @Test
    fun unsupportedMalformedZeroByteAndUnreadableSourcesAreFullyAccounted() {
        val unsupported = source("unsupported", "notes.txt")
        val malformed = source("malformed", "broken.pdf")
        val empty = source("empty", "empty.pdf", size = 0L)
        val unreadable = source("unreadable", "lost.pdf")
        val sources = listOf(unsupported, malformed, empty, unreadable)
        val access = CountingAccess(
            bytes = mapOf(
                unsupported.id to "plain text".encodeToByteArray(),
                malformed.id to pdfBytes(),
                empty.id to byteArrayOf(),
            ),
            pdfInspections = mapOf(malformed.id to MigrationPdfInspection.Malformed),
        )

        val plan = BulkMigrationPlanner(access).preview(sources)

        assertEquals(4, plan.sourceAccounting.size)
        assertEquals(1, plan.sourceSummary.unsupportedCount)
        assertEquals(3, plan.sourceSummary.analysisFailedCount)
        assertEquals(
            setOf(
                MigrationSourceFailureDetail.UNSUPPORTED_SIGNATURE,
                MigrationSourceFailureDetail.MALFORMED_PDF,
                MigrationSourceFailureDetail.ZERO_BYTE_SOURCE,
                MigrationSourceFailureDetail.STREAM_UNREADABLE,
            ),
            plan.rejectedSources.map(RejectedMigrationSource::failureDetail).toSet(),
        )
        assertEquals(4, plan.sourceSummary.requestedCount)
    }

    @Test
    fun duplicateReferencesRemainPlannedButAreMarkedPossibleNotExact() {
        val first = source("same-uri", "same.pdf", size = 100L)
        val second = first.copy()
        val access = accessFor(listOf(first), mapOf(first.id to 1))

        val plan = BulkMigrationPlanner(access).preview(listOf(first, second))

        assertEquals(2, plan.documents.size)
        assertNotEquals(plan.documents[0].id, plan.documents[1].id)
        assertEquals(1, plan.duplicateCandidates.size)
        assertEquals(MigrationPlanDuplicateStrength.POSSIBLE, plan.duplicateCandidates.single().strength)
        assertEquals(2, plan.sourceSummary.duplicateCandidateCount)
        assertEquals(0, plan.sourceSummary.plannedCount)
    }

    @Test
    fun matchingMetadataIsOnlyPossibleAndUnicodeNormalizationIsLocaleIndependent() {
        val composed = source("first", "Résumé.pdf", folder = listOf("Équipe"), size = 200L)
        val decomposed = source("second", "Résumé.pdf", folder = listOf("Équipe"), size = 200L)
        val sources = listOf(composed, decomposed)

        val plan = BulkMigrationPlanner(
            accessFor(sources, sources.associate { it.id to 1 }),
        ).preview(sources)

        assertEquals(composed.normalizedDisplayName, decomposed.normalizedDisplayName)
        assertEquals(MigrationPlanDuplicateStrength.POSSIBLE, plan.duplicateCandidates.single().strength)
        assertEquals(MigrationEvidenceSource.PROVIDER_METADATA, plan.duplicateCandidates.single().evidence)
    }

    @Test
    fun cancellationLeavesEveryUnopenedArtifactExplicitlyUnprocessed() {
        val sources = List(10) { index -> source("id-$index", "Artifact-$index-unique.jpg") }
        val access = accessFor(sources)
        var inspected = 0

        val plan = BulkMigrationPlanner(access).preview(
            sources = sources,
            cancellationSignal = MigrationCancellationSignal { inspected >= 3 },
            progressListener = MigrationPreviewProgressListener { completed, _ -> inspected = completed },
        )

        assertTrue(plan.wasCancelled)
        assertEquals(3, plan.inspectedSourceCount)
        assertEquals(3, plan.sourceSummary.plannedCount)
        assertEquals(7, plan.sourceSummary.cancelledUnprocessedCount)
        assertEquals(10, plan.sourceAccounting.size)
    }

    @Test
    fun sourceLimitBoundaryRejectsOverflowWithoutOpeningIt() {
        val sources = List(4) { index -> source("id-$index", "Artifact-$index-unique.jpg") }
        val access = accessFor(sources)

        val plan = BulkMigrationPlanner(access, maximumSources = 3).preview(sources)

        assertFalse(plan.wasCancelled)
        assertEquals(3, plan.inspectedSourceCount)
        assertEquals(3, plan.sourceSummary.plannedCount)
        assertEquals(1, plan.sourceSummary.unsupportedCount)
        assertEquals(MigrationSourceFailureDetail.SOURCE_LIMIT_EXCEEDED, plan.rejectedSources.single().failureDetail)
        assertTrue(plan.planWarnings.any {
            it.warning == MigrationAnalysisWarning.DISCOVERY_LIMIT_REACHED
        })
        assertEquals(3, access.openCount.get())
    }

    @Test
    fun plannerScalesToOneThousandArtifactsWithOneBoundedSignatureReadEach() {
        listOf(10, 100, 500, 1_000).forEach { count ->
            val sources = List(count) { index ->
                source("id-$index", "Artifact-$index-unique.jpg", size = 64L)
            }
            val access = accessFor(sources)
            requestGarbageCollection()
            val beforeHeap = usedHeapBytes()
            val started = System.nanoTime()

            val plan = BulkMigrationPlanner(access).preview(sources)

            val durationMillis = (System.nanoTime() - started) / 1_000_000L
            requestGarbageCollection()
            val heapDelta = (usedHeapBytes() - beforeHeap).coerceAtLeast(0L)
            println(
                "M1_PLANNER_PERF artifacts=$count durationMs=$durationMillis " +
                    "retainedHeapDeltaBytes=$heapDelta opened=${access.openCount.get()}",
            )
            assertEquals(count, plan.documents.size)
            assertEquals(count, plan.sourceAccounting.size)
            assertEquals(count, access.openCount.get())
            assertTrue("Planning $count artifacts took ${durationMillis}ms", durationMillis < 10_000L)
        }
    }

    private fun accessFor(
        sources: List<MigrationSource>,
        pdfPages: Map<String, Int> = emptyMap(),
    ) = CountingAccess(
        bytes = sources.associate { source ->
            source.id to if (source.displayName?.endsWith(".pdf", ignoreCase = true) == true) {
                pdfBytes()
            } else {
                jpegBytes()
            }
        },
        pdfInspections = pdfPages.mapValues { MigrationPdfInspection.Readable(it.value) },
    )

    private fun source(
        id: String,
        name: String,
        folder: List<String> = emptyList(),
        size: Long? = 64L,
        created: Long? = null,
        modified: Long? = null,
    ) = MigrationSource(
        id = id,
        displayName = name,
        relativeFolderPath = folder,
        sizeBytes = size,
        createdAtMillis = created,
        modifiedAtMillis = modified,
    )

    private class CountingAccess(
        private val bytes: Map<String, ByteArray>,
        private val pdfInspections: Map<String, MigrationPdfInspection> = emptyMap(),
    ) : MigrationSourceAccess, MigrationPdfInspector {
        val openCount = AtomicInteger()
        val pdfInspectionCount = AtomicInteger()

        override fun open(source: MigrationSource): ByteArrayInputStream {
            openCount.incrementAndGet()
            return ByteArrayInputStream(bytes[source.id] ?: throw IOException("missing"))
        }

        override fun inspectPdf(source: MigrationSource): MigrationPdfInspection {
            pdfInspectionCount.incrementAndGet()
            return pdfInspections[source.id] ?: MigrationPdfInspection.Unavailable
        }
    }

    private fun pdfBytes() = "%PDF-1.7\n".encodeToByteArray()
    private fun jpegBytes() = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0)

    private fun usedHeapBytes(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    private fun requestGarbageCollection() {
        System.gc()
        System.runFinalization()
    }
}
