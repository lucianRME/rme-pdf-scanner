package org.synapseworks.pageharbor.backup.format

/** Test-fixture OCR validator retained for compatibility and rejection coverage. */
internal object BackupOcrFormatValidator {
    fun analyze(
        records: BackupRecordSource,
        index: BackupBaseRecordIndex,
        limits: BackupFormatLimits,
    ): BackupOcrManifest {
        val validation = begin(records, index, limits)
        records.ocrLines().forEach(validation::accept)
        return validation.finish()
    }

    fun begin(
        records: BackupRecordSource,
        index: BackupBaseRecordIndex,
        limits: BackupFormatLimits,
    ): OcrLineValidationSession {
        val documentStates = HashSet<String>()
        var documentStateCount = 0
        records.ocrDocumentStates().forEach { state ->
            documentStateCount = incrementBounded(documentStateCount, limits.maximumDocumentCount)
            requireStableId(state.documentId, "documentId")
            if (state.documentId !in index.documentIds) relationshipInvalid("An OCR document state is orphaned.")
            if (!documentStates.add(state.documentId)) duplicate("OCR document state")
            if (state.contentRevision < 0L) invalidMetadata("An OCR document revision is invalid.")
            state.scriptPreference?.let { preference ->
                if (preference !in SCRIPT_PREFERENCES) {
                    invalidMetadata("An OCR script preference is unsupported.")
                }
            }
        }
        if (documentStates != index.documentIds) {
            relationshipInvalid("V2 OCR document state must cover every document exactly once.")
        }

        val pageStates = HashMap<String, BackupOcrPageStateRecord>()
        records.ocrPageStates().forEach { state ->
            if (pageStates.size >= limits.maximumPageCount) limitExceeded("The OCR page-state count exceeds its limit.")
            requireStableId(state.pageId, "pageId")
            if (state.pageId !in index.pageIds) relationshipInvalid("An OCR page state is orphaned.")
            if (state.visualRevision < 0L || state.ocrStateRevision < 0L ||
                (state.activeArtifactRevision != null && state.activeArtifactRevision <= 0L)
            ) {
                invalidMetadata("An OCR page state revision is invalid.")
            }
            if (pageStates.put(state.pageId, state) != null) duplicate("OCR page state")
        }
        if (pageStates.keys != index.pageIds) {
            relationshipInvalid("V2 OCR page state must cover every page exactly once.")
        }

        val artifacts = HashMap<ArtifactKey, ArtifactSummary>()
        var artifactCount = 0
        records.ocrArtifacts().forEach { artifact ->
            artifactCount = incrementBounded(artifactCount, limits.maximumOcrArtifactCount)
            validateArtifact(artifact, index, limits)
            val key = ArtifactKey(artifact.pageId, artifact.artifactRevision)
            if (artifacts.put(key, ArtifactSummary(artifact.lineCount, artifact.rawText, artifact)) != null) {
                duplicate("OCR artifact")
            }
        }
        pageStates.values.forEach { state ->
            val basePage = requireNotNull(index.pagesById[state.pageId])
            val active = state.activeArtifactRevision?.let { revision ->
                artifacts[ArtifactKey(state.pageId, revision)] ?: run {
                    relationshipInvalid("An active OCR artifact pointer is invalid.")
                }
            }
            if (active == null) {
                if (basePage.ocrText != null) {
                    relationshipInvalid("A page OCR mirror exists without an active artifact.")
                }
            } else {
                if (state.ocrStateRevision <= 0L || basePage.ocrText != active.rawText) {
                    relationshipInvalid("An active OCR artifact and its page mirror are inconsistent.")
                }
                validateActiveArtifactCurrentness(active.record, state, basePage)
            }
        }

        val correctionPages = HashSet<String>()
        var correctionCount = 0
        records.ocrCorrections().forEach { correction ->
            correctionCount = incrementBounded(correctionCount, limits.maximumOcrCorrectionCount)
            requireStableId(correction.pageId, "pageId")
            if (correction.pageId !in index.pageIds) relationshipInvalid("An OCR correction is orphaned.")
            if (!correctionPages.add(correction.pageId)) duplicate("OCR correction page")
            val artifact = artifacts[ArtifactKey(correction.pageId, correction.baseArtifactRevision)]
                ?: relationshipInvalid("An OCR correction references a missing artifact.")
            if (requireNotNull(pageStates[correction.pageId]).ocrStateRevision <= 0L) {
                relationshipInvalid("An OCR correction has an invalid page-state revision.")
            }
            if (correction.correctedAtEpochMillis < 0L) invalidMetadata("An OCR correction timestamp is invalid.")
            requireText(correction.correctedText, limits)
            when (correction.alignment) {
                BackupOcrCorrectionAlignment.FREEFORM -> if (correction.lineCorrections.isNotEmpty()) {
                    relationshipInvalid("A freeform OCR correction cannot contain positioned line corrections.")
                }

                BackupOcrCorrectionAlignment.LINE_ALIGNED -> {
                    if (correction.lineCorrections.size != artifact.lineCount ||
                        correction.lineCorrections.map(BackupOcrLineCorrectionRecord::lineOrdinal) !=
                        correction.lineCorrections.indices.toList()
                    ) {
                        relationshipInvalid("A line-aligned OCR correction does not cover its artifact lines.")
                    }
                    correction.lineCorrections.forEach { requireText(it.correctedText, limits) }
                    if (correction.lineCorrections.joinToString("\n") { it.correctedText } != correction.correctedText) {
                        relationshipInvalid("A line-aligned OCR correction does not reproduce its page text.")
                    }
                }
            }
        }

        return OcrLineValidationSession(
            artifacts = artifacts,
            documentStateCount = documentStateCount,
            pageStateCount = pageStates.size,
            artifactCount = artifactCount,
            correctionCount = correctionCount,
            pageOrder = index.pageOrder,
            limits = limits,
        )
    }

    internal class OcrLineValidationSession(
        private val artifacts: Map<ArtifactKey, ArtifactSummary>,
        private val documentStateCount: Int,
        private val pageStateCount: Int,
        private val artifactCount: Int,
        private val correctionCount: Int,
        private val pageOrder: Map<String, BackupPageOrder>,
        private val limits: BackupFormatLimits,
    ) {
        private val chunkPlanner = OcrLineChunkPlanner(limits)
        private val actualLineCounts = HashMap<ArtifactKey, Int>()
        private var previousOrder: OcrLineArtifactOrder? = null
        private var expectedOrdinal = 0

        fun accept(line: BackupOcrLineRecord) {
            if (chunkPlanner.lineCount >= limits.maximumOcrLineCount) {
                limitExceeded("The OCR line count exceeds its limit.")
            }
            val key = ArtifactKey(line.pageId, line.artifactRevision)
            val artifact = artifacts[key] ?: relationshipInvalid("An OCR line references a missing artifact.")
            val order = OcrLineArtifactOrder(
                page = pageOrder[line.pageId]
                    ?: relationshipInvalid("An OCR line references a page without a stable order."),
                artifactRevision = line.artifactRevision,
            )
            val previous = previousOrder
            if (previous == null || order != previous) {
                if (previous != null && order <= previous) {
                    relationshipInvalid("OCR lines must follow document, page, artifact, and ordinal order.")
                }
                previousOrder = order
                expectedOrdinal = 0
            }
            if (line.lineOrdinal != expectedOrdinal || line.lineOrdinal >= artifact.lineCount) {
                relationshipInvalid("An OCR line ordinal is invalid.")
            }
            expectedOrdinal += 1
            validateLine(line, limits)
            actualLineCounts[key] = expectedOrdinal
            chunkPlanner.add(BackupOcrJsonCodec.encodeLineBytes(line))
        }

        fun finish(): BackupOcrManifest {
            artifacts.forEach { (key, artifact) ->
                if ((actualLineCounts[key] ?: 0) != artifact.lineCount) {
                    relationshipInvalid("An OCR artifact line count does not match its line metadata.")
                }
            }
            val chunks = chunkPlanner.finish()
            return BackupOcrManifest(
                documentStatesPath = RME_BACKUP_OCR_DOCUMENT_STATES_PATH,
                pageStatesPath = RME_BACKUP_OCR_PAGE_STATES_PATH,
                artifactsPath = RME_BACKUP_OCR_ARTIFACTS_PATH,
                correctionsPath = RME_BACKUP_OCR_CORRECTIONS_PATH,
                documentStateCount = documentStateCount,
                pageStateCount = pageStateCount,
                artifactCount = artifactCount,
                correctionCount = correctionCount,
                lineCount = chunkPlanner.lineCount,
                lineByteLength = chunkPlanner.totalBytes,
                lineChunks = chunks,
            )
        }
    }

    private fun validateArtifact(
        artifact: BackupOcrArtifactRecord,
        index: BackupBaseRecordIndex,
        limits: BackupFormatLimits,
    ) {
        requireStableId(artifact.pageId, "pageId")
        if (artifact.pageId !in index.pageIds) relationshipInvalid("An OCR artifact is orphaned.")
        if (artifact.artifactRevision <= 0L || artifact.lineCount !in 0..limits.maximumOcrLinesPerArtifact) {
            invalidMetadata("An OCR artifact revision or line count is invalid.")
        }
        requireText(artifact.rawText, limits)
        val actualScript = requireNotNullOrInvalid(artifact.actualScript, "An OCR actual script is missing.")
        if (actualScript !in ACTUAL_SCRIPTS) invalidMetadata("An OCR actual script is unsupported.")
        requireToken(requireNotNullOrInvalid(artifact.recognizerId, "An OCR recognizer ID is missing."), "OCR recognizer ID")
        artifact.clientVersion?.let { requireBoundedText(it, 128, "An OCR client version is invalid.") }
        val fingerprint = artifact.inputFingerprint
        if (artifact.verificationState == BackupOcrVerificationState.CURRENT_VERIFIED) {
            if (artifact.capturedPageVisualRevision == null || artifact.capturedDocumentContentRevision == null ||
                fingerprint == null || artifact.pipelineVersion == null || artifact.delivery == null ||
                artifact.recognizedAtEpochMillis == null
            ) {
                invalidMetadata("A current OCR artifact is missing required provenance.")
            }
        }
        artifact.capturedPageVisualRevision?.let { if (it < 0L) invalidMetadata("An OCR page revision is invalid.") }
        artifact.capturedDocumentContentRevision?.let {
            if (it < 0L) invalidMetadata("An OCR document revision is invalid.")
        }
        artifact.pipelineVersion?.let { requireBoundedText(it, 128, "An OCR pipeline version is invalid.") }
        artifact.delivery?.let { requireToken(it, "OCR delivery") }
        artifact.recognizedAtEpochMillis?.let { if (it < 0L) invalidMetadata("An OCR timestamp is invalid.") }
        fingerprint?.let { value ->
            if (value.version <= 0 || value.visualRevision < 0L || value.uprightWidth <= 0 ||
                value.uprightHeight <= 0 || value.coordinateSystemVersion <= 0 || value.transformVersion <= 0 ||
                value.rotationDegrees !in VALID_ROTATIONS
            ) {
                invalidMetadata("An OCR input fingerprint is invalid.")
            }
            requireBoundedText(value.value, 256, "An OCR input fingerprint value is invalid.")
            requireSha256(value.contentSha256)
            if (artifact.capturedPageVisualRevision != null &&
                artifact.capturedPageVisualRevision != value.visualRevision
            ) {
                relationshipInvalid("An OCR artifact fingerprint has a mismatched page revision.")
            }
            requireToken(value.filterName, "OCR filter")
        }
    }

    private fun validateActiveArtifactCurrentness(
        artifact: BackupOcrArtifactRecord,
        state: BackupOcrPageStateRecord,
        page: BackupPageRecord,
    ) {
        if (artifact.verificationState != BackupOcrVerificationState.CURRENT_VERIFIED) return
        val fingerprint = requireNotNull(artifact.inputFingerprint)
        if (artifact.capturedPageVisualRevision != state.visualRevision ||
            fingerprint.visualRevision != state.visualRevision ||
            fingerprint.contentSha256 != page.sha256 ||
            fingerprint.rotationDegrees != page.rotationDegrees ||
            fingerprint.filterName != page.filterName
        ) {
            relationshipInvalid("An active verified OCR artifact does not match its current page input.")
        }
    }

    private fun validateLine(line: BackupOcrLineRecord, limits: BackupFormatLimits) {
        requireStableId(line.pageId, "pageId")
        requireText(line.rawText, limits)
        if (line.cornerPoints.size != 4) invalidMetadata("An OCR line must contain four corner points.")
        (line.cornerPoints + line.baselineStart + line.baselineEnd).forEach { point ->
            if (!point.x.isFinite() || !point.y.isFinite() || point.x !in 0.0..1.0 || point.y !in 0.0..1.0) {
                invalidMetadata("An OCR line coordinate is invalid.")
            }
        }
        if (!line.baselineAngleDegrees.isFinite() || line.baselineAngleDegrees !in -180.0..180.0) {
            invalidMetadata("An OCR baseline angle is invalid.")
        }
        line.writingOrientation?.let { requireToken(it, "OCR writing orientation") }
    }

    private fun requireStableId(value: String, field: String) = BackupPathValidator.requireStableId(value, field)

    private fun requireText(value: String, limits: BackupFormatLimits) {
        if (value.length > limits.maximumStringCharacters || value.any { it == '\u0000' }) {
            limitExceeded("An OCR text value exceeds configured limits.")
        }
    }

    private fun requireToken(value: String, field: String) {
        requireBoundedText(value, 128, "A $field value is invalid.")
    }

    private fun requireBoundedText(value: String, maximum: Int, message: String) {
        if (value.isBlank() || value.length > maximum || value.any { it == '\u0000' }) invalidMetadata(message)
    }

    private fun requireSha256(value: String) {
        if (!SHA256.matches(value)) invalidMetadata("An OCR content SHA-256 is invalid.")
    }

    private fun <T : Any> requireNotNullOrInvalid(value: T?, message: String): T = value ?: invalidMetadata(message)

    private fun incrementBounded(current: Int, maximum: Int): Int {
        if (current >= maximum) limitExceeded("An OCR metadata record count exceeds its limit.")
        return current + 1
    }

    private fun duplicate(type: String): Nothing = relationshipInvalid("The backup contains a duplicate $type.")

    private fun relationshipInvalid(message: String): Nothing = throw backupFailure(
        BackupFormatFailure.RELATIONSHIP_INVALID,
        message,
    )

    private fun invalidMetadata(message: String): Nothing = throw backupFailure(
        BackupFormatFailure.INVALID_METADATA,
        message,
    )

    private val SHA256 = Regex("[0-9a-f]{64}")
    private val VALID_ROTATIONS = setOf(0, 90, 180, 270)
    private val ACTUAL_SCRIPTS = setOf("LATIN", "CHINESE", "JAPANESE", "KOREAN", "DEVANAGARI")
    private val SCRIPT_PREFERENCES = ACTUAL_SCRIPTS + "AUTOMATIC"
}

internal data class ArtifactKey(
    val pageId: String,
    val revision: Long,
) : Comparable<ArtifactKey> {
    override fun compareTo(other: ArtifactKey): Int {
        val pageComparison = pageId.compareTo(other.pageId)
        return if (pageComparison != 0) pageComparison else revision.compareTo(other.revision)
    }
}

private data class OcrLineArtifactOrder(
    val page: BackupPageOrder,
    val artifactRevision: Long,
) : Comparable<OcrLineArtifactOrder> {
    override fun compareTo(other: OcrLineArtifactOrder): Int {
        val pageComparison = page.compareTo(other.page)
        return if (pageComparison != 0) pageComparison else artifactRevision.compareTo(other.artifactRevision)
    }
}

internal data class ArtifactSummary(
    val lineCount: Int,
    val rawText: String,
    val record: BackupOcrArtifactRecord,
)

internal class OcrLineChunkPlanner(
    private val limits: BackupFormatLimits,
) {
    private val chunks = ArrayList<BackupOcrLineChunkDescriptor>()
    private var currentRecords = 0
    private var currentBytes = 0L
    var lineCount: Int = 0
        private set
    var totalBytes: Long = 0L
        private set

    fun add(encodedLine: ByteArray) {
        if (encodedLine.size > limits.maximumJsonLineBytes || encodedLine.size > limits.maximumOcrLineChunkBytes) {
            limitExceeded("An OCR line record is too large.")
        }
        if (currentRecords > 0 &&
            (currentRecords >= limits.maximumOcrLineChunkRecords ||
                currentBytes + encodedLine.size > limits.maximumOcrLineChunkBytes)
        ) {
            finishCurrentChunk()
        }
        currentRecords += 1
        currentBytes = checkedAdd(currentBytes, encodedLine.size.toLong())
        lineCount += 1
        totalBytes = checkedAdd(totalBytes, encodedLine.size.toLong())
    }

    fun finish(): List<BackupOcrLineChunkDescriptor> {
        if (currentRecords > 0) finishCurrentChunk()
        return chunks.toList()
    }

    private fun finishCurrentChunk() {
        chunks += BackupOcrLineChunkDescriptor(
            path = "$RME_BACKUP_OCR_LINES_DIRECTORY/${chunks.size.toString().padStart(6, '0')}.jsonl",
            recordCount = currentRecords,
            byteLength = currentBytes,
        )
        currentRecords = 0
        currentBytes = 0L
    }
}
