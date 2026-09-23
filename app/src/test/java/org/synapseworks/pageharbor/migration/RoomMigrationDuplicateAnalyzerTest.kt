package org.synapseworks.pageharbor.migration

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.synapseworks.pageharbor.library.duplicate.DuplicateCandidate
import org.synapseworks.pageharbor.library.duplicate.DuplicateKind

class RoomMigrationDuplicateAnalyzerTest {
    @Test
    fun readOnlyPreviewCountsMoreThanTwentyPdfPagesAndClassifiesExact() = runBlocking {
        val root = Files.createTempDirectory("migration-preview-test").toFile()
        try {
            val plan = pdfPlan("large-pdf")
            val stagedTemplate = stagedDocument(File(root, "template"), plan.id, 25)
            val fingerprint = stagedTemplate.fingerprintForMigration()
            val candidate = DuplicateCandidate(
                documentId = "existing",
                contentHashVersion = fingerprint.version,
                contentSha256 = fingerprint.sha256,
                pageCount = 25,
                contentByteLength = stagedTemplate.pages.sumOf { it.byteCount },
                orderedMimeTypes = stagedTemplate.pages.map { it.contentType },
            )
            val stager = FakeStager(root, pageCounts = mapOf(plan.id to 25))
            val analyzer = RoomMigrationDuplicateAnalyzer(
                storageCapacity = MigrationStorageCapacity { Long.MAX_VALUE },
                candidates = MigrationDuplicateCandidateSnapshot { listOf(candidate) },
                stager = stager,
            )

            val analyzed = analyzer.analyze(
                preview(plan),
                NeverCancelMigration,
                MigrationBatchProgressListener { },
            )

            assertTrue(analyzed.duplicateAnalysisComplete)
            assertEquals(25L, analyzed.pageCount)
            assertEquals(25, analyzed.duplicateDocuments.single().analyzedPageCount)
            assertEquals(DuplicateKind.EXACT, analyzed.duplicateDocuments.single().duplicateKind)
            assertEquals(1, analyzed.exactDuplicateCount)
            assertEquals(1, stager.stageCalls)
            assertFalse(File(root, plan.id).exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun stagingFailureLeavesPreviewExplicitlyIncomplete() = runBlocking {
        val plan = pdfPlan("unreadable")
        val analyzer = RoomMigrationDuplicateAnalyzer(
            storageCapacity = MigrationStorageCapacity { Long.MAX_VALUE },
            candidates = MigrationDuplicateCandidateSnapshot { emptyList() },
            stager = object : MigrationDocumentStager {
                override suspend fun stage(
                    plan: MigrationDocumentPlan,
                    context: MigrationPublicationContext,
                ) = MigrationStagingResult.Failed(
                    MigrationPublicationFailure.SOURCE_UNAVAILABLE,
                    retryable = true,
                )

                override fun cleanAbandonedWorkspaces() = Unit
            },
        )

        val analyzed = analyzer.analyze(
            preview(plan),
            NeverCancelMigration,
            MigrationBatchProgressListener { },
        )

        assertFalse(analyzed.duplicateAnalysisComplete)
        assertNull(analyzed.pageCount)
        assertEquals(setOf(plan.id), analyzed.unclassifiedDocumentIds)
        assertEquals(
            MigrationPublicationFailure.SOURCE_UNAVAILABLE,
            analyzed.duplicateDocuments.single().analysisFailure,
        )
    }

    private inner class FakeStager(
        private val root: File,
        private val pageCounts: Map<String, Int>,
    ) : MigrationDocumentStager {
        var stageCalls = 0

        override suspend fun stage(
            plan: MigrationDocumentPlan,
            context: MigrationPublicationContext,
        ): MigrationStagingResult {
            stageCalls += 1
            return MigrationStagingResult.Ready(
                stagedDocument(
                    workspace = File(root, plan.id).apply { mkdirs() },
                    planId = plan.id,
                    pageCount = pageCounts.getValue(plan.id),
                ),
            )
        }

        override fun cleanAbandonedWorkspaces() = Unit
    }

    private fun stagedDocument(
        workspace: File,
        planId: String,
        pageCount: Int,
    ) = StagedMigrationDocument(
        workspace = workspace,
        pages = List(pageCount) { index ->
            StagedMigrationPage(
                file = File(workspace, "page-$index.jpg"),
                contentType = MigrationContentType.JPEG.mimeType,
                width = 100,
                height = 200,
                byteCount = 100L + index,
                sha256 = sha256("$planId-$index"),
            )
        },
        originalPdf = null,
        copiedSourceBytes = 100L,
    )

    private fun pdfPlan(id: String): MigrationDocumentPlan {
        val source = MigrationSource(
            id = "content://test/$id",
            displayName = "$id.pdf",
            declaredContentType = MigrationContentType.PDF.mimeType,
            sizeBytes = 100L,
        )
        return MigrationDocumentPlan(
            id = id,
            suggestedTitle = id,
            relativeFolderPath = emptyList(),
            sources = listOf(InspectedMigrationSource(source, MigrationContentType.PDF)),
            grouping = MigrationDocumentGrouping.SINGLE_PDF,
        )
    }

    private fun preview(plan: MigrationDocumentPlan) = MigrationPreview(
        documents = listOf(plan),
        rejectedSources = emptyList(),
        storageEstimate = MigrationStorageEstimate(
            knownSourceBytes = 100L,
            unknownSizeSourceCount = 0,
            estimatedRequiredBytes = 1_000L,
            estimateIsIncomplete = false,
        ),
        inspectedSourceCount = 1,
        requestedSourceCount = 1,
        wasCancelled = false,
    )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray())
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
