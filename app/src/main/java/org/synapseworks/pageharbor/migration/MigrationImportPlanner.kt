package org.synapseworks.pageharbor.migration

import java.io.IOException
import java.security.MessageDigest
import java.time.DateTimeException
import java.time.LocalDate
import java.util.Locale
import org.synapseworks.pageharbor.document.importing.MAX_PDF_IMPORT_BYTES
import org.synapseworks.pageharbor.document.importing.sniffSupportedContentType
import org.synapseworks.pageharbor.document.session.DEFAULT_MAX_IMAGE_SOURCE_BYTES
import org.synapseworks.pageharbor.document.session.DEFAULT_MAX_IMPORTED_DOCUMENT_PAGES

private const val ESTIMATED_DOCUMENT_OVERHEAD_BYTES = 512L * 1024L
private const val DEFAULT_MAX_PLANNING_SOURCES = 10_000
private const val MAX_SUGGESTED_TITLE_LENGTH = 255

/**
 * Signature-validates and groups a bounded source set into the existing migration preview model.
 * Planning is read-only: it never stages, publishes, indexes, or mutates a library.
 */
class BulkMigrationPlanner(
    private val sourceAccess: MigrationSourceAccess,
    private val pdfInspector: MigrationPdfInspector? = sourceAccess as? MigrationPdfInspector,
    private val maximumSources: Int = DEFAULT_MAX_PLANNING_SOURCES,
) {
    init {
        require(maximumSources > 0)
    }

    fun preview(
        sources: List<MigrationSource>,
        cancellationSignal: MigrationCancellationSignal = NeverCancelMigration,
        progressListener: MigrationPreviewProgressListener = MigrationPreviewProgressListener { _, _ -> },
    ): MigrationImportPlan {
        val requestedSources = canonicalizeSources(sources)
        val inspected = mutableListOf<InspectedMigrationSource>()
        val rejected = mutableListOf<RejectedMigrationSource>()
        var inspectedCount = 0

        for (source in requestedSources.take(maximumSources)) {
            if (cancellationSignal.isCancellationRequested()) break
            when (val inspection = inspect(source)) {
                is SourceInspection.Supported -> inspected += InspectedMigrationSource(
                    source = source,
                    contentType = inspection.contentType,
                    pageCount = inspection.pageCount,
                    warnings = inspection.warnings,
                )
                is SourceInspection.Rejected -> rejected += RejectedMigrationSource(
                    source = source,
                    issue = inspection.issue,
                    failureDetail = inspection.failureDetail,
                    warnings = inspection.warnings,
                )
            }
            inspectedCount += 1
            progressListener.onProgress(inspectedCount, requestedSources.size)
        }

        val limitExceeded = if (!cancellationSignal.isCancellationRequested()) {
            requestedSources.drop(maximumSources)
        } else {
            emptyList()
        }
        rejected += limitExceeded.map { source ->
            RejectedMigrationSource(
                source = source,
                issue = MigrationSourceIssue.UNSUPPORTED_CONTENT,
                failureDetail = MigrationSourceFailureDetail.SOURCE_LIMIT_EXCEEDED,
                warnings = listOf(MigrationAnalysisWarning.DISCOVERY_LIMIT_REACHED),
            )
        }

        val grouping = groupDocuments(inspected)
        val duplicateCandidates = duplicateCandidates(inspected)
        val duplicateSourceIds = duplicateCandidates.flatMapTo(linkedSetOf()) {
            it.sourceDiscoveryIds
        }
        val documents = grouping.documents.map { document ->
            document.copy(
                warningCodes = (
                    document.warningCodes + document.sources.mapNotNull { source ->
                        MigrationAnalysisWarning.DUPLICATE_SOURCE_REFERENCE.takeIf {
                            source.source.discoveryIdentity in duplicateSourceIds
                        }
                    }
                    ).distinct(),
            )
        }
        val planWarnings = buildList {
            if (limitExceeded.isNotEmpty()) {
                add(
                    MigrationPlanWarning(
                        warning = MigrationAnalysisWarning.DISCOVERY_LIMIT_REACHED,
                        affectedSourceDiscoveryIds = limitExceeded.map(MigrationSource::discoveryIdentity),
                        occurrenceCount = limitExceeded.size,
                    ),
                )
            }
            duplicateCandidates.forEach { candidate ->
                add(
                    MigrationPlanWarning(
                        warning = MigrationAnalysisWarning.DUPLICATE_SOURCE_REFERENCE,
                        affectedSourceDiscoveryIds = candidate.sourceDiscoveryIds,
                        occurrenceCount = candidate.sourceDiscoveryIds.size,
                    ),
                )
            }
        }

        return MigrationPreview(
            documents = documents,
            rejectedSources = rejected,
            storageEstimate = estimateStorage(inspected, documents.size),
            inspectedSourceCount = inspectedCount,
            requestedSourceCount = requestedSources.size,
            wasCancelled = cancellationSignal.isCancellationRequested(),
            requestedSources = requestedSources,
            plannedFolders = plannedFolders(documents),
            planWarnings = planWarnings,
            ambiguities = grouping.ambiguities,
            duplicateCandidates = duplicateCandidates,
        )
    }

    private fun inspect(source: MigrationSource): SourceInspection {
        if (source.sizeBytes == 0L) {
            return SourceInspection.Rejected(
                issue = MigrationSourceIssue.UNREADABLE,
                failureDetail = MigrationSourceFailureDetail.ZERO_BYTE_SOURCE,
            )
        }
        return try {
            sourceAccess.open(source).use { input ->
                when (sniffSupportedContentType(input)) {
                    MigrationContentType.PDF.mimeType -> inspectPdf(source)
                    MigrationContentType.JPEG.mimeType ->
                        inspectImage(source, MigrationContentType.JPEG)
                    MigrationContentType.PNG.mimeType ->
                        inspectImage(source, MigrationContentType.PNG)
                    MigrationContentType.WEBP.mimeType ->
                        inspectImage(source, MigrationContentType.WEBP)
                    else -> SourceInspection.Rejected(
                        issue = MigrationSourceIssue.UNSUPPORTED_CONTENT,
                        failureDetail = MigrationSourceFailureDetail.UNSUPPORTED_SIGNATURE,
                    )
                }
            }
        } catch (_: IOException) {
            unreadableInspection()
        } catch (_: SecurityException) {
            unreadableInspection()
        } catch (_: IllegalArgumentException) {
            unreadableInspection()
        } catch (_: IllegalStateException) {
            unreadableInspection()
        }
    }

    private fun inspectPdf(source: MigrationSource): SourceInspection {
        if (source.sizeBytes != null && source.sizeBytes > MAX_PDF_IMPORT_BYTES) {
            return SourceInspection.Rejected(
                MigrationSourceIssue.UNSUPPORTED_CONTENT,
                MigrationSourceFailureDetail.SOURCE_LIMIT_EXCEEDED,
            )
        }
        val mismatchWarnings = mimeMismatchWarnings(source, MigrationContentType.PDF)
        return when (val inspection = pdfInspector?.inspectPdf(source)) {
            is MigrationPdfInspection.Readable -> {
                if (inspection.pageCount > DEFAULT_MAX_IMPORTED_DOCUMENT_PAGES) {
                    SourceInspection.Rejected(
                        MigrationSourceIssue.UNSUPPORTED_CONTENT,
                        MigrationSourceFailureDetail.PDF_PAGE_LIMIT_EXCEEDED,
                        mismatchWarnings,
                    )
                } else {
                    SourceInspection.Supported(
                        MigrationContentType.PDF,
                        inspection.pageCount,
                        mismatchWarnings,
                    )
                }
            }
            MigrationPdfInspection.Malformed -> SourceInspection.Rejected(
                MigrationSourceIssue.UNREADABLE,
                MigrationSourceFailureDetail.MALFORMED_PDF,
                mismatchWarnings,
            )
            MigrationPdfInspection.PageLimitExceeded -> SourceInspection.Rejected(
                MigrationSourceIssue.UNSUPPORTED_CONTENT,
                MigrationSourceFailureDetail.PDF_PAGE_LIMIT_EXCEEDED,
                mismatchWarnings,
            )
            MigrationPdfInspection.Unavailable,
            null,
            -> SourceInspection.Supported(
                MigrationContentType.PDF,
                pageCount = null,
                warnings = mismatchWarnings + MigrationAnalysisWarning.PDF_PAGE_COUNT_UNAVAILABLE,
            )
        }
    }

    private fun inspectImage(
        source: MigrationSource,
        contentType: MigrationContentType,
    ): SourceInspection {
        if (source.sizeBytes != null && source.sizeBytes > DEFAULT_MAX_IMAGE_SOURCE_BYTES) {
            return SourceInspection.Rejected(
                MigrationSourceIssue.UNSUPPORTED_CONTENT,
                MigrationSourceFailureDetail.SOURCE_LIMIT_EXCEEDED,
            )
        }
        return SourceInspection.Supported(
            contentType = contentType,
            pageCount = 1,
            warnings = mimeMismatchWarnings(source, contentType),
        )
    }

    private fun groupDocuments(inspected: List<InspectedMigrationSource>): GroupingResult {
        val ordered = inspected.sortedWith { left, right ->
            compareSources(left.source, right.source)
        }
        val plans = mutableListOf<MigrationDocumentPlan>()
        val groupedImageDiscoveryIds = mutableSetOf<String>()
        val imageSequences = ordered.asSequence()
            .filter { it.contentType.isImage }
            .mapNotNull { inspectedSource ->
                inspectedSource.explicitPageSequence()?.let { match -> match to inspectedSource }
            }
            .groupBy({ it.first.groupKey }, { it.second })
            .values
            .filter(::isHighConfidenceSequence)
            .sortedWith { left, right ->
                compareSources(left.first().source, right.first().source)
            }

        imageSequences.forEach { sequence ->
            val naturallyOrdered = sequence.sortedWith { left, right ->
                NaturalFilenameComparator.compare(left.source.sortName(), right.source.sortName())
            }
            groupedImageDiscoveryIds += naturallyOrdered.map { it.source.discoveryIdentity }
            val sequenceName = requireNotNull(naturallyOrdered.first().explicitPageSequence()).baseName
            plans += documentPlan(
                id = stablePlanId(
                    "images",
                    naturallyOrdered.map { it.source.discoveryIdentity },
                ),
                suggestedTitle = sequenceName.toSuggestedTitle(),
                relativeFolderPath = naturallyOrdered.first().source.relativeFolderPath,
                sources = naturallyOrdered,
                grouping = MigrationDocumentGrouping.HIGH_CONFIDENCE_IMAGE_SEQUENCE,
            )
        }

        ordered.forEach { inspectedSource ->
            if (inspectedSource.source.discoveryIdentity in groupedImageDiscoveryIds) return@forEach
            val isPdf = inspectedSource.contentType == MigrationContentType.PDF
            plans += documentPlan(
                id = stablePlanId(
                    if (isPdf) "pdf" else "image",
                    listOf(inspectedSource.source.discoveryIdentity),
                ),
                suggestedTitle = inspectedSource.source.baseFilename().toSuggestedTitle(),
                relativeFolderPath = inspectedSource.source.relativeFolderPath,
                sources = listOf(inspectedSource),
                grouping = if (isPdf) {
                    MigrationDocumentGrouping.SINGLE_PDF
                } else {
                    MigrationDocumentGrouping.SINGLE_IMAGE
                },
            )
        }

        val sortedPlans = plans.sortedWith { left, right ->
            val folderComparison = comparePaths(left.relativeFolderPath, right.relativeFolderPath)
            if (folderComparison != 0) {
                folderComparison
            } else {
                val orderComparison = compareDeclaredSourceOrder(
                    left.sources.first().source,
                    right.sources.first().source,
                )
                if (orderComparison != 0) {
                    orderComparison
                } else {
                    val titleComparison = NaturalFilenameComparator.compare(
                        left.suggestedTitle,
                        right.suggestedTitle,
                    )
                    if (titleComparison != 0) titleComparison else left.id.compareTo(right.id)
                }
            }
        }
        val planIdBySource = sortedPlans.flatMap { plan ->
            plan.sources.map { it.source.discoveryIdentity to plan.id }
        }.toMap()
        val ambiguities = ambiguousImageSequences(
            inspected = ordered,
            groupedImageDiscoveryIds = groupedImageDiscoveryIds,
            planIdBySource = planIdBySource,
        ) + ambiguousFilenameDates(sortedPlans)
        val ambiguityIdsBySource = ambiguities.flatMap { ambiguity ->
            ambiguity.sourceDiscoveryIds.map { it to ambiguity.id }
        }.groupBy({ it.first }, { it.second })

        return GroupingResult(
            documents = sortedPlans.map { document ->
                val ambiguityIds = document.sources.flatMap { source ->
                    ambiguityIdsBySource[source.source.discoveryIdentity].orEmpty()
                }.distinct()
                document.copy(
                    ambiguityIds = ambiguityIds,
                    warningCodes = (
                        document.warningCodes + if (ambiguityIds.isNotEmpty()) {
                            listOf(MigrationAnalysisWarning.POSSIBLE_IMAGE_SEQUENCE)
                        } else {
                            emptyList()
                        }
                        ).distinct(),
                )
            },
            ambiguities = ambiguities,
        )
    }

    private fun documentPlan(
        id: String,
        suggestedTitle: String,
        relativeFolderPath: List<String>,
        sources: List<InspectedMigrationSource>,
        grouping: MigrationDocumentGrouping,
    ): MigrationDocumentPlan {
        val dateAnalysis = sources.map(::dateCandidates)
        val pages = when (grouping) {
            MigrationDocumentGrouping.SINGLE_PDF -> {
                val source = sources.single()
                List(source.pageCount ?: 0) { pageIndex ->
                    MigrationPlannedPage(
                        id = stablePlanId("page", listOf(id, pageIndex.toString())),
                        sourceDiscoveryId = source.source.discoveryIdentity,
                        sourcePageIndex = pageIndex,
                        plannedIndex = pageIndex,
                        factualPageNumber = pageIndex + 1,
                        orderEvidence = MigrationEvidenceSource.PDF_PAGE_TREE,
                    )
                }
            }
            MigrationDocumentGrouping.SINGLE_IMAGE -> listOf(
                MigrationPlannedPage(
                    id = stablePlanId("page", listOf(id, "0")),
                    sourceDiscoveryId = sources.single().source.discoveryIdentity,
                    sourcePageIndex = 0,
                    plannedIndex = 0,
                    factualPageNumber = null,
                    orderEvidence = MigrationEvidenceSource.USER_SELECTED_SOURCE,
                ),
            )
            MigrationDocumentGrouping.HIGH_CONFIDENCE_IMAGE_SEQUENCE -> sources.mapIndexed {
                    pageIndex, source ->
                MigrationPlannedPage(
                    id = stablePlanId("page", listOf(id, pageIndex.toString())),
                    sourceDiscoveryId = source.source.discoveryIdentity,
                    sourcePageIndex = 0,
                    plannedIndex = pageIndex,
                    factualPageNumber = source.explicitPageSequence()?.pageNumber,
                    orderEvidence = MigrationEvidenceSource.EXPLICIT_PAGE_FILENAME,
                )
            }
        }
        return MigrationDocumentPlan(
            id = id,
            suggestedTitle = suggestedTitle,
            relativeFolderPath = relativeFolderPath,
            sources = sources,
            grouping = grouping,
            pages = pages,
            originalAssets = sources.map { source ->
                MigrationOriginalAssetPlan(
                    sourceDiscoveryId = source.source.discoveryIdentity,
                    role = if (source.contentType == MigrationContentType.PDF) {
                        MigrationOriginalAssetRole.ORIGINAL_PDF
                    } else {
                        MigrationOriginalAssetRole.ORIGINAL_IMAGE
                    },
                    contentType = source.contentType,
                )
            },
            dateCandidates = dateAnalysis.flatMap(DateAnalysis::candidates).distinct(),
            evidence = buildSet {
                add(MigrationEvidenceSource.STREAM_SIGNATURE)
                if (
                    relativeFolderPath.isNotEmpty() &&
                    sources.all { it.source.origin == MigrationSourceOrigin.DOCUMENT_TREE }
                ) {
                    add(MigrationEvidenceSource.SAF_TREE_PATH)
                }
                if (
                    grouping == MigrationDocumentGrouping.SINGLE_PDF &&
                    sources.single().pageCount != null
                ) {
                    add(MigrationEvidenceSource.PDF_PAGE_TREE)
                }
                if (grouping == MigrationDocumentGrouping.HIGH_CONFIDENCE_IMAGE_SEQUENCE) {
                    add(MigrationEvidenceSource.EXPLICIT_PAGE_FILENAME)
                }
            },
            warningCodes = (
                sources.flatMap(InspectedMigrationSource::warnings) +
                    dateAnalysis.flatMap(DateAnalysis::warnings)
                ).distinct(),
        )
    }

    private fun dateCandidates(source: InspectedMigrationSource): DateAnalysis {
        val candidates = buildList {
            source.source.createdAtMillis?.let { instant ->
                add(
                    MigrationDateCandidate(
                        kind = MigrationDateCandidateKind.ORIGINAL_FILE_CREATED,
                        instantMillis = instant,
                        evidence = MigrationEvidenceSource.PROVIDER_METADATA,
                        sourceDiscoveryId = source.source.discoveryIdentity,
                        authoritativeForKind = true,
                    ),
                )
            }
            source.source.modifiedAtMillis?.let { instant ->
                add(
                    MigrationDateCandidate(
                        kind = MigrationDateCandidateKind.ORIGINAL_FILE_MODIFIED,
                        instantMillis = instant,
                        evidence = MigrationEvidenceSource.PROVIDER_METADATA,
                        sourceDiscoveryId = source.source.discoveryIdentity,
                        authoritativeForKind = true,
                    ),
                )
            }
        }.toMutableList()
        val filenameMatches = FILENAME_DATE.findAll(source.source.baseFilename()).toList()
        val validDates = filenameMatches.mapNotNull { match ->
            try {
                LocalDate.of(
                    match.groupValues[1].toInt(),
                    match.groupValues[2].toInt(),
                    match.groupValues[3].toInt(),
                )
            } catch (_: DateTimeException) {
                null
            }
        }.distinct()
        val warnings = mutableListOf<MigrationAnalysisWarning>()
        if (filenameMatches.size == 1 && validDates.size == 1) {
            candidates += MigrationDateCandidate(
                kind = MigrationDateCandidateKind.SOURCE_DOCUMENT_DATE,
                epochDay = validDates.single().toEpochDay(),
                evidence = MigrationEvidenceSource.FILENAME_CANDIDATE,
                sourceDiscoveryId = source.source.discoveryIdentity,
                authoritativeForKind = false,
            )
            warnings += MigrationAnalysisWarning.FILENAME_DATE_CANDIDATE_ONLY
        } else if (filenameMatches.isNotEmpty()) {
            warnings += MigrationAnalysisWarning.AMBIGUOUS_FILENAME_DATE_IGNORED
        }
        return DateAnalysis(candidates, warnings)
    }

    private fun estimateStorage(
        inspected: List<InspectedMigrationSource>,
        documentCount: Int,
    ): MigrationStorageEstimate {
        var knownSourceBytes = 0L
        var estimatedBytes = documentCount.toLong().saturatingMultiply(
            ESTIMATED_DOCUMENT_OVERHEAD_BYTES,
        )
        var unknown = 0
        inspected.forEach { inspectedSource ->
            val sourceBytes = inspectedSource.source.sizeBytes
            if (sourceBytes == null) {
                unknown += 1
            } else {
                knownSourceBytes = knownSourceBytes.saturatingAdd(sourceBytes)
                val multiplier = if (inspectedSource.contentType == MigrationContentType.PDF) 3L else 2L
                estimatedBytes = estimatedBytes.saturatingAdd(
                    sourceBytes.saturatingMultiply(multiplier),
                )
            }
        }
        return MigrationStorageEstimate(
            knownSourceBytes = knownSourceBytes,
            unknownSizeSourceCount = unknown,
            estimatedRequiredBytes = estimatedBytes.takeIf { unknown == 0 },
            estimateIsIncomplete = unknown != 0,
        )
    }

    private fun mimeMismatchWarnings(
        source: MigrationSource,
        detected: MigrationContentType,
    ): List<MigrationAnalysisWarning> {
        val declared = source.declaredContentType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotEmpty() && it != "*/*" && !it.endsWith("/*") }
            ?: return emptyList()
        return if (declared == detected.mimeType) {
            emptyList()
        } else {
            listOf(MigrationAnalysisWarning.DECLARED_MIME_MISMATCH)
        }
    }
}

private sealed interface SourceInspection {
    data class Supported(
        val contentType: MigrationContentType,
        val pageCount: Int?,
        val warnings: List<MigrationAnalysisWarning>,
    ) : SourceInspection

    data class Rejected(
        val issue: MigrationSourceIssue,
        val failureDetail: MigrationSourceFailureDetail,
        val warnings: List<MigrationAnalysisWarning> = emptyList(),
    ) : SourceInspection
}

private data class GroupingResult(
    val documents: List<MigrationDocumentPlan>,
    val ambiguities: List<MigrationPlanAmbiguity>,
)

private data class DateAnalysis(
    val candidates: List<MigrationDateCandidate>,
    val warnings: List<MigrationAnalysisWarning>,
)

private data class PageSequenceGroupKey(
    val relativeFolderPath: List<String>,
    val normalizedBaseName: String,
)

private data class PageSequenceMatch(
    val groupKey: PageSequenceGroupKey,
    val baseName: String,
    val pageNumber: Int,
)

private data class WeakSequenceMatch(
    val groupKey: PageSequenceGroupKey,
    val pageNumber: Int,
)

private fun unreadableInspection() = SourceInspection.Rejected(
    MigrationSourceIssue.UNREADABLE,
    MigrationSourceFailureDetail.STREAM_UNREADABLE,
)

private fun canonicalizeSources(sources: List<MigrationSource>): List<MigrationSource> {
    val ordered = sources.sortedWith { left, right ->
        val declaredOrder = compareDeclaredSourceOrder(left, right)
        if (declaredOrder != 0) declaredOrder else compareSources(left, right)
    }
    val occurrences = mutableMapOf<String, Int>()
    return ordered.mapIndexed { index, source ->
        val identityParts = buildList {
            add(source.origin.name)
            add(source.id)
            addAll(source.relativeFolderPath)
            add(source.displayName.orEmpty())
            add(source.sizeBytes?.toString().orEmpty())
            add(source.modifiedAtMillis?.toString().orEmpty())
            add(source.createdAtMillis?.toString().orEmpty())
        }
        val baseIdentity = stablePlanId("source", identityParts)
        val occurrence = occurrences.getOrDefault(baseIdentity, 0) + 1
        occurrences[baseIdentity] = occurrence
        source.copy(
            discoveryIdentity = "$baseIdentity:$occurrence",
            discoveryOrder = index,
            discoveryOrderEvidence = if (
                source.discoveryOrderEvidence == MigrationDiscoveryOrderEvidence.SOURCE_DECLARED &&
                source.discoveryOrder != null
            ) {
                MigrationDiscoveryOrderEvidence.SOURCE_DECLARED
            } else {
                MigrationDiscoveryOrderEvidence.CANONICAL_PROVIDER_INDEPENDENT
            },
        )
    }
}

private fun duplicateCandidates(
    inspected: List<InspectedMigrationSource>,
): List<MigrationPlanDuplicateCandidate> {
    val exact = inspected.asSequence()
        .filter { it.contentSha256 != null }
        .groupBy { requireNotNull(it.contentSha256) }
        .values
        .filter { it.size > 1 }
        .map { matches ->
            MigrationPlanDuplicateCandidate(
                sourceDiscoveryIds = matches.map { it.source.discoveryIdentity }.sorted(),
                strength = MigrationPlanDuplicateStrength.EXACT,
                evidence = MigrationEvidenceSource.CRYPTOGRAPHIC_HASH,
            )
        }
    val classifiedIds = exact.flatMapTo(hashSetOf()) { it.sourceDiscoveryIds }
    val sameReference = inspected.asSequence()
        .filterNot { it.source.discoveryIdentity in classifiedIds }
        .groupBy { source ->
            listOf(
                "reference",
                source.source.id,
                source.source.relativeFolderPath.joinToString("/"),
                source.source.sizeBytes?.toString().orEmpty(),
            ).joinToString("\u0000")
        }
        .values
        .filter { it.size > 1 }
        .map { matches ->
            MigrationPlanDuplicateCandidate(
                sourceDiscoveryIds = matches.map { it.source.discoveryIdentity }.sorted(),
                strength = MigrationPlanDuplicateStrength.POSSIBLE,
                evidence = MigrationEvidenceSource.USER_SELECTED_SOURCE,
            )
        }
        .toList()
    classifiedIds += sameReference.flatMap { it.sourceDiscoveryIds }
    val sameMetadata = inspected.asSequence()
        .filterNot { it.source.discoveryIdentity in classifiedIds }
        .filter { it.source.sizeBytes != null && it.source.normalizedDisplayName != null }
        .groupBy { source ->
            listOf(
                "metadata",
                source.source.relativeFolderPath.joinToString("/"),
                requireNotNull(source.source.normalizedDisplayName),
                requireNotNull(source.source.sizeBytes).toString(),
                source.contentType.name,
            ).joinToString("\u0000")
        }
        .values
        .filter { it.size > 1 }
        .map { matches ->
            MigrationPlanDuplicateCandidate(
                sourceDiscoveryIds = matches.map { it.source.discoveryIdentity }.sorted(),
                strength = MigrationPlanDuplicateStrength.POSSIBLE,
                evidence = MigrationEvidenceSource.PROVIDER_METADATA,
            )
        }
        .toList()
    return (exact + sameReference + sameMetadata).sortedBy { it.sourceDiscoveryIds.first() }
}

private fun plannedFolders(documents: List<MigrationDocumentPlan>): List<MigrationFolderCandidate> =
    documents.asSequence()
        .filter { it.relativeFolderPath.isNotEmpty() }
        .groupBy(MigrationDocumentPlan::relativeFolderPath)
        .map { (path, plans) ->
            MigrationFolderCandidate(
                relativePath = path,
                evidence = if (
                    plans.flatMap(MigrationDocumentPlan::sources)
                        .all { it.source.origin == MigrationSourceOrigin.DOCUMENT_TREE }
                ) {
                    MigrationEvidenceSource.SAF_TREE_PATH
                } else {
                    MigrationEvidenceSource.USER_SELECTED_SOURCE
                },
                sourceDiscoveryIds = plans.flatMap(MigrationDocumentPlan::sources)
                    .map { it.source.discoveryIdentity }
                    .distinct()
                    .sorted(),
            )
        }
        .sortedWith { left, right -> comparePaths(left.relativePath, right.relativePath) }

private fun ambiguousImageSequences(
    inspected: List<InspectedMigrationSource>,
    groupedImageDiscoveryIds: Set<String>,
    planIdBySource: Map<String, String>,
): List<MigrationPlanAmbiguity> = inspected.asSequence()
    .filter { it.contentType.isImage }
    .filterNot { it.source.discoveryIdentity in groupedImageDiscoveryIds }
    .mapNotNull { source -> source.weakSequence()?.let { it to source } }
    .groupBy({ it.first.groupKey }, { it.second })
    .values
    .filter { it.size >= 2 }
    .map { sources ->
        val sourceIds = sources.map { it.source.discoveryIdentity }.sorted()
        MigrationPlanAmbiguity(
            id = stablePlanId("ambiguity-images", sourceIds),
            kind = MigrationAmbiguityKind.POSSIBLE_IMAGE_SEQUENCE,
            sourceDiscoveryIds = sourceIds,
            plannedDocumentIds = sourceIds.mapNotNull(planIdBySource::get).distinct().sorted(),
        )
    }
    .sortedBy(MigrationPlanAmbiguity::id)

private fun ambiguousFilenameDates(
    documents: List<MigrationDocumentPlan>,
): List<MigrationPlanAmbiguity> = documents.mapNotNull { document ->
    val sourceIds = document.sources
        .filter {
            MigrationAnalysisWarning.AMBIGUOUS_FILENAME_DATE_IGNORED in
                dateWarningsForFilename(it.source.baseFilename())
        }
        .map { it.source.discoveryIdentity }
    if (sourceIds.isEmpty()) {
        null
    } else {
        MigrationPlanAmbiguity(
            id = stablePlanId("ambiguity-date", sourceIds),
            kind = MigrationAmbiguityKind.AMBIGUOUS_FILENAME_DATE,
            sourceDiscoveryIds = sourceIds,
            plannedDocumentIds = listOf(document.id),
        )
    }
}

private fun dateWarningsForFilename(filename: String): List<MigrationAnalysisWarning> {
    val matches = FILENAME_DATE.findAll(filename).toList()
    if (matches.isEmpty()) return emptyList()
    val valid = matches.mapNotNull { match ->
        try {
            LocalDate.of(
                match.groupValues[1].toInt(),
                match.groupValues[2].toInt(),
                match.groupValues[3].toInt(),
            )
        } catch (_: DateTimeException) {
            null
        }
    }.distinct()
    return if (matches.size == 1 && valid.size == 1) {
        listOf(MigrationAnalysisWarning.FILENAME_DATE_CANDIDATE_ONLY)
    } else {
        listOf(MigrationAnalysisWarning.AMBIGUOUS_FILENAME_DATE_IGNORED)
    }
}

private fun InspectedMigrationSource.explicitPageSequence(): PageSequenceMatch? {
    val baseName = source.baseFilename()
    val match = EXPLICIT_PAGE_SEQUENCE.matchEntire(baseName) ?: return null
    val prefix = match.groupValues[1].trimEnd(' ', '.', '_', '-')
    if (prefix.isBlank()) return null
    val number = match.groupValues[2].toIntOrNull() ?: return null
    return PageSequenceMatch(
        groupKey = PageSequenceGroupKey(
            relativeFolderPath = source.relativeFolderPath,
            normalizedBaseName = prefix.lowercase(Locale.ROOT),
        ),
        baseName = prefix,
        pageNumber = number,
    )
}

private fun InspectedMigrationSource.weakSequence(): WeakSequenceMatch? {
    explicitPageSequence()?.let { explicit ->
        return WeakSequenceMatch(explicit.groupKey, explicit.pageNumber)
    }
    val match = WEAK_TRAILING_SEQUENCE.matchEntire(source.baseFilename()) ?: return null
    val prefix = match.groupValues[1].trimEnd(' ', '.', '_', '-')
    if (prefix.isBlank()) return null
    val number = match.groupValues[2].toIntOrNull() ?: return null
    return WeakSequenceMatch(
        PageSequenceGroupKey(
            relativeFolderPath = source.relativeFolderPath,
            normalizedBaseName = prefix.lowercase(Locale.ROOT),
        ),
        number,
    )
}

private fun isHighConfidenceSequence(sequence: List<InspectedMigrationSource>): Boolean {
    if (sequence.size < 2) return false
    val numbers = sequence.mapNotNull { it.explicitPageSequence()?.pageNumber }.sorted()
    if (numbers.size != sequence.size || numbers.firstOrNull() != 1) return false
    return numbers.withIndex().all { (index, number) -> number == index + 1 }
}

private fun MigrationSource.sortName(): String = displayName?.takeIf(String::isNotBlank) ?: id

private fun MigrationSource.baseFilename(): String {
    val name = displayName?.takeIf(String::isNotBlank) ?: "Imported document"
    val lastDot = name.lastIndexOf('.')
    return if (lastDot > 0) name.substring(0, lastDot) else name
}

private fun String.toSuggestedTitle(): String = asSequence()
    .map { character ->
        if (character.isISOControl() || character == '/' || character == '\\') '_' else character
    }
    .joinToString(separator = "")
    .trim()
    .take(MAX_SUGGESTED_TITLE_LENGTH)
    .ifEmpty { "Imported document" }

private fun comparePaths(left: List<String>, right: List<String>): Int =
    NaturalFilenameComparator.compare(left.joinToString("/"), right.joinToString("/"))

private fun compareDeclaredSourceOrder(left: MigrationSource, right: MigrationSource): Int {
    if (
        left.discoveryOrderEvidence != MigrationDiscoveryOrderEvidence.SOURCE_DECLARED ||
        right.discoveryOrderEvidence != MigrationDiscoveryOrderEvidence.SOURCE_DECLARED
    ) {
        return 0
    }
    return when {
        left.discoveryOrder == null || right.discoveryOrder == null -> 0
        else -> left.discoveryOrder.compareTo(right.discoveryOrder)
    }
}

private fun compareSources(left: MigrationSource, right: MigrationSource): Int {
    val folderComparison = comparePaths(left.relativeFolderPath, right.relativeFolderPath)
    if (folderComparison != 0) return folderComparison
    val declaredOrder = compareDeclaredSourceOrder(left, right)
    if (declaredOrder != 0) return declaredOrder
    val nameComparison = NaturalFilenameComparator.compare(left.sortName(), right.sortName())
    if (nameComparison != 0) return nameComparison
    return left.id.compareTo(right.id)
}

private fun stablePlanId(prefix: String, sourceIds: List<String>): String =
    "$prefix:${MessageDigest.getInstance("SHA-256").run {
        update(prefix.encodeToByteArray())
        sourceIds.forEach { sourceId ->
            update(0.toByte())
            update(sourceId.length.toString().encodeToByteArray())
            update(0.toByte())
            update(sourceId.encodeToByteArray())
        }
        digest().joinToString("") { byte -> "%02x".format(Locale.ROOT, byte) }
    }}"

private fun Long.saturatingAdd(other: Long): Long =
    if (this > Long.MAX_VALUE - other) Long.MAX_VALUE else this + other

private fun Long.saturatingMultiply(other: Long): Long = when {
    this == 0L || other == 0L -> 0L
    this > Long.MAX_VALUE / other -> Long.MAX_VALUE
    else -> this * other
}

private val EXPLICIT_PAGE_SEQUENCE = Regex(
    pattern = "^(.*?)[\\s._-]+(?:page|pg)[\\s._-]*(\\d+)$",
    option = RegexOption.IGNORE_CASE,
)
private val WEAK_TRAILING_SEQUENCE = Regex("^(.*?)[\\s._-]*(\\d+)$")
private val FILENAME_DATE = Regex("(?<!\\d)(\\d{4})[-_.](\\d{2})[-_.](\\d{2})(?!\\d)")
