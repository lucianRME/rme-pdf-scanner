package org.synapseworks.pageharbor.migration

import java.io.ByteArrayInputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.synapseworks.pageharbor.library.duplicate.DocumentFingerprint
import org.synapseworks.pageharbor.library.duplicate.DuplicateCandidate
import org.synapseworks.pageharbor.library.duplicate.DuplicateKind
import org.synapseworks.pageharbor.library.duplicate.IncomingDocumentIdentity

class VerifiedMigrationModelsTest {
    @Test
    fun duplicateReviewChoicesAreExplicitAndSafeByDefault() {
        val defaults = MigrationExecutionDecisions()

        assertEquals(
            MigrationDuplicateExecutionDecision.SKIP_EXACT,
            defaults.duplicateDecision("exact", DuplicateKind.EXACT),
        )
        assertEquals(
            MigrationDuplicateExecutionDecision.DEFER,
            defaults.duplicateDecision("possible", DuplicateKind.POSSIBLE),
        )
        assertEquals(
            MigrationDuplicateExecutionDecision.IMPORT_ANYWAY,
            defaults.duplicateDecision("different", DuplicateKind.DIFFERENT),
        )

        val reviewed = MigrationExecutionDecisions(
            duplicateDecisions = mapOf(
                "possible-import" to MigrationDuplicateExecutionDecision.IMPORT_ANYWAY,
                "possible-exclude" to MigrationDuplicateExecutionDecision.EXCLUDE,
                "exact-import" to MigrationDuplicateExecutionDecision.IMPORT_ANYWAY,
            ),
        )
        assertEquals(
            MigrationDuplicateExecutionDecision.IMPORT_ANYWAY,
            reviewed.duplicateDecision("possible-import", DuplicateKind.POSSIBLE),
        )
        assertEquals(
            MigrationDuplicateExecutionDecision.EXCLUDE,
            reviewed.duplicateDecision("possible-exclude", DuplicateKind.POSSIBLE),
        )
        assertEquals(
            MigrationDuplicateExecutionDecision.IMPORT_ANYWAY,
            reviewed.duplicateDecision("exact-import", DuplicateKind.EXACT),
        )
    }

    @Test
    fun ambiguousGroupingDefersUntilAcceptedAndCanSplitDeterministically() {
        val first = inspected("first", "scan-1.jpg")
        val second = inspected("second", "scan-2.jpg")
        val document = document(
            id = "group",
            sources = listOf(first, second),
            grouping = MigrationDocumentGrouping.HIGH_CONFIDENCE_IMAGE_SEQUENCE,
            ambiguityIds = listOf("ambiguity"),
        )
        val plan = preview(
            listOf(document),
            ambiguities = listOf(
                MigrationPlanAmbiguity(
                    id = "ambiguity",
                    kind = MigrationAmbiguityKind.POSSIBLE_IMAGE_SEQUENCE,
                    sourceDiscoveryIds = listOf("first", "second"),
                    plannedDocumentIds = listOf(document.id),
                ),
            ),
        )

        assertEquals(
            MigrationResolvedDocumentAction.DEFER,
            plan.resolveExecutionDecisions(MigrationExecutionDecisions()).single().action,
        )
        assertEquals(
            MigrationResolvedDocumentAction.IMPORT,
            plan.resolveExecutionDecisions(
                MigrationExecutionDecisions(
                    ambiguityDecisions = mapOf(
                        "ambiguity" to MigrationAmbiguityExecutionDecision.ACCEPT_PROPOSED,
                    ),
                ),
            ).single().action,
        )
        val split = plan.resolveExecutionDecisions(
            MigrationExecutionDecisions(
                ambiguityDecisions = mapOf(
                    "ambiguity" to MigrationAmbiguityExecutionDecision.SPLIT_INDEPENDENT,
                ),
            ),
        )
        assertEquals(listOf("group:split:0", "group:split:1"), split.map { it.plan.id })
        assertTrue(split.all { it.action == MigrationResolvedDocumentAction.IMPORT })
        assertTrue(split.all { it.plan.sources.size == 1 && it.plan.ambiguityIds.isEmpty() })
    }

    @Test
    fun documentExclusionWinsBeforeExecution() {
        val plan = preview(listOf(document("document", listOf(inspected("source", "one.jpg")))))

        val resolved = plan.resolveExecutionDecisions(
            MigrationExecutionDecisions(
                documentDecisions = mapOf(
                    "document" to MigrationPlannedDocumentDecision.EXCLUDE,
                ),
            ),
        ).single()

        assertEquals(MigrationResolvedDocumentAction.EXCLUDE, resolved.action)
        assertEquals(DECISION_EXCLUDE_DOCUMENT, resolved.recordedDecision)
    }

    @Test
    fun boundedSha256UsesBytesNotNamesOrSizes() = runBlocking {
        val bytes = "identical-content".encodeToByteArray()
        val access = MapSourceAccess(
            mapOf(
                "first" to bytes,
                "renamed" to bytes,
                "same-name-a" to "abcd".encodeToByteArray(),
                "same-name-b" to "wxyz".encodeToByteArray(),
            ),
        )

        val first = access.sha256OrNull(source("first", "first.pdf"), 1_024L, NeverCancelMigration)
        val renamed = access.sha256OrNull(
            source("renamed", "renamed.pdf"),
            1_024L,
            NeverCancelMigration,
        )
        val sameNameA = access.sha256OrNull(
            source("same-name-a", "same.pdf"),
            1_024L,
            NeverCancelMigration,
        )
        val sameNameB = access.sha256OrNull(
            source("same-name-b", "same.pdf"),
            1_024L,
            NeverCancelMigration,
        )

        assertEquals(first, renamed)
        assertNotEquals(sameNameA, sameNameB)
        assertEquals(4, access.opened)
    }

    @Test
    fun boundedSha256FailsClosedAndChecksCancellation() = runBlocking {
        val failed = MigrationSourceAccess { throw IOException("unavailable") }
        assertEquals(
            null,
            failed.sha256OrNull(source("missing", "missing.pdf"), 1_024L, NeverCancelMigration),
        )

        val access = MapSourceAccess(mapOf("large" to ByteArray(128 * 1024) { 7 }))
        var cancellationObserved = false
        try {
            access.sha256OrNull(
                source("large", "large.pdf"),
                256 * 1024L,
                MigrationCancellationSignal { true },
            )
        } catch (_: CancellationException) {
            cancellationObserved = true
        }
        assertTrue(cancellationObserved)
        assertEquals(
            null,
            access.sha256OrNull(
                source("large", "large.pdf"),
                maximumBytes = 1_024L,
                cancellationSignal = NeverCancelMigration,
            ),
        )
    }

    @Test
    fun equalPageFingerprintIsExactWithoutIncomingOcrState() {
        val fingerprint = DocumentFingerprint(1, "a".repeat(64))
        val incoming = IncomingDocumentIdentity(
            fingerprint = fingerprint,
            pageCount = 1,
            contentByteLength = 10L,
            orderedMimeTypes = listOf("image/jpeg"),
        )
        val candidate = DuplicateCandidate(
            documentId = "existing",
            contentHashVersion = fingerprint.version,
            contentSha256 = fingerprint.sha256,
            pageCount = 1,
            contentByteLength = 10L,
            orderedMimeTypes = listOf("image/jpeg"),
        )

        assertEquals(
            DuplicateKind.EXACT,
            classifyMigrationDuplicate(incoming, listOf(candidate)).kind,
        )
    }

    @Test
    fun renderedContentHashIsExactAcrossContainersButSourceHashAloneIsOnlyPossible() {
        val renderedFingerprint = DocumentFingerprint(1, "b".repeat(64))
        val sameRenderedContent = IncomingDocumentIdentity(
            fingerprint = renderedFingerprint,
            sourceSha256 = setOf("c".repeat(64)),
            pageCount = 1,
            contentByteLength = 100L,
            orderedMimeTypes = listOf("image/jpeg"),
        )
        val existingPdf = DuplicateCandidate(
            documentId = "existing-pdf",
            contentHashVersion = renderedFingerprint.version,
            contentSha256 = renderedFingerprint.sha256,
            sourceSha256 = setOf("d".repeat(64)),
            pageCount = 1,
            contentByteLength = 100L,
            orderedMimeTypes = listOf("image/jpeg"),
        )
        assertEquals(
            DuplicateKind.EXACT,
            classifyMigrationDuplicate(sameRenderedContent, listOf(existingPdf)).kind,
        )

        val sameSourceOnly = sameRenderedContent.copy(
            fingerprint = DocumentFingerprint(1, "e".repeat(64)),
            sourceSha256 = existingPdf.sourceSha256,
        )
        assertEquals(
            DuplicateKind.POSSIBLE,
            classifyMigrationDuplicate(sameSourceOnly, listOf(existingPdf)).kind,
        )
    }

    @Test
    fun completionAccountingRequiresEveryRequestedSourceExactlyOnce() {
        val complete = MigrationDurableCompletionReport(
            operationId = "operation",
            requestedSourceCount = 2,
            sources = listOf(
                MigrationSourceCompletion(
                    accountingId = "source-1",
                    outcome = MigrationTerminalSourceOutcome.IMPORTED,
                    documentId = "document-1",
                ),
                MigrationSourceCompletion(
                    accountingId = "source-2",
                    outcome = MigrationTerminalSourceOutcome.UNSUPPORTED,
                    documentId = null,
                ),
            ),
            importedDocumentCount = 1,
            importedPageCount = 1,
            reconstructedFolderCount = 0,
            preservedSourcePdfCount = 0,
            isTerminal = true,
        )
        val incomplete = complete.copy(requestedSourceCount = 3)

        assertTrue(complete.reconciles)
        assertFalse(incomplete.reconciles)
    }

    private fun preview(
        documents: List<MigrationDocumentPlan>,
        ambiguities: List<MigrationPlanAmbiguity> = emptyList(),
    ) = MigrationPreview(
        documents = documents,
        rejectedSources = emptyList(),
        storageEstimate = MigrationStorageEstimate(0L, 0),
        inspectedSourceCount = documents.sumOf { it.sources.size },
        requestedSourceCount = documents.sumOf { it.sources.size },
        wasCancelled = false,
        duplicateDocuments = documents.map { plan ->
            MigrationDuplicatePreview(plan.id, DuplicateKind.DIFFERENT, plan.pages.size)
        },
        duplicateAnalysisComplete = true,
        requestedSources = documents.flatMap { it.sources }.map(InspectedMigrationSource::source),
        ambiguities = ambiguities,
    )

    private fun document(
        id: String,
        sources: List<InspectedMigrationSource>,
        grouping: MigrationDocumentGrouping = MigrationDocumentGrouping.SINGLE_IMAGE,
        ambiguityIds: List<String> = emptyList(),
    ) = MigrationDocumentPlan(
        id = id,
        suggestedTitle = id,
        relativeFolderPath = emptyList(),
        sources = sources,
        grouping = grouping,
        pages = sources.mapIndexed { index, inspected ->
            MigrationPlannedPage(
                id = "$id-page-$index",
                sourceDiscoveryId = inspected.source.discoveryIdentity,
                sourcePageIndex = 0,
                plannedIndex = index,
                factualPageNumber = null,
                orderEvidence = MigrationEvidenceSource.USER_SELECTED_SOURCE,
            )
        },
        ambiguityIds = ambiguityIds,
    )

    private fun inspected(id: String, name: String) = InspectedMigrationSource(
        source = source(id, name),
        contentType = MigrationContentType.JPEG,
        pageCount = 1,
    )

    private fun source(id: String, name: String) = MigrationSource(
        id = id,
        displayName = name,
        declaredContentType = MigrationContentType.JPEG.mimeType,
        sizeBytes = 16L,
        discoveryIdentity = id,
    )

    private class MapSourceAccess(
        private val bytes: Map<String, ByteArray>,
    ) : MigrationSourceAccess {
        var opened: Int = 0
            private set

        override fun open(source: MigrationSource) = ByteArrayInputStream(
            bytes.getValue(source.id),
        ).also { opened += 1 }
    }
}
