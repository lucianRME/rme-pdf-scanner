package org.synapseworks.pageharbor.library.duplicate

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.synapseworks.pageharbor.backup.format.BackupOcrArtifactRecord
import org.synapseworks.pageharbor.backup.format.BackupOcrCorrectionRecord
import org.synapseworks.pageharbor.backup.format.BackupOcrLineRecord
import org.synapseworks.pageharbor.backup.format.BackupOcrPageStateRecord
import org.synapseworks.pageharbor.backup.format.BackupOcrVerificationState
import org.synapseworks.pageharbor.library.LEGACY_OCR_RECOGNIZER_ID

data class OcrStateDigest(
    val version: Int,
    val sha256: String,
)

/**
 * The preservation-sensitive OCR state for one page, in document page order.
 *
 * Backup-local page IDs and artifact revision numbers are association keys, not digest input. The
 * digest instead marks the active/base artifact in canonical artifact order. Timestamps and mutable
 * revision counters are deliberately excluded. Text is hashed as exact UTF-8: no Unicode,
 * whitespace, or case normalization is applied, so null, empty, and differently encoded state stay
 * distinguishable.
 */
data class OcrDigestPage(
    val legacyText: String?,
    val legacyError: String?,
    val pageState: BackupOcrPageStateRecord?,
    val artifacts: List<BackupOcrArtifactRecord>,
    val correction: BackupOcrCorrectionRecord?,
    val lines: List<BackupOcrLineRecord>,
)

/** Canonical, ID- and timestamp-independent digest for restorable OCR state. */
object OcrStateDigestV1 {
    const val VERSION: Int = 1

    /** Canonical state produced when a Backup Format v1 OCR mirror is restored into Room v3. */
    fun legacyV1Page(
        pageId: String,
        legacyText: String?,
        legacyError: String?,
    ): OcrDigestPage {
        val artifact = legacyText?.let { text ->
            BackupOcrArtifactRecord(
                pageId = pageId,
                artifactRevision = 1L,
                capturedPageVisualRevision = null,
                capturedDocumentContentRevision = null,
                verificationState = BackupOcrVerificationState.LEGACY_UNVERIFIED,
                inputFingerprint = null,
                actualScript = "LATIN",
                recognizerId = LEGACY_OCR_RECOGNIZER_ID,
                pipelineVersion = null,
                clientVersion = null,
                delivery = null,
                recognizedAtEpochMillis = null,
                rawText = text,
                lineCount = 0,
            )
        }
        return OcrDigestPage(
            legacyText = legacyText,
            legacyError = legacyError,
            pageState = BackupOcrPageStateRecord(
                pageId = pageId,
                visualRevision = 0L,
                ocrStateRevision = if (artifact == null) 0L else 1L,
                activeArtifactRevision = artifact?.artifactRevision,
            ),
            artifacts = listOfNotNull(artifact),
            correction = null,
            lines = emptyList(),
        )
    }

    fun calculate(
        scriptPreference: String?,
        pages: List<OcrDigestPage>,
    ): OcrStateDigest = Builder(scriptPreference, pages.size).apply {
        pages.forEach(::addPage)
    }.finish()

    class Builder(
        scriptPreference: String?,
        private val expectedPageCount: Int,
    ) {
        private val digest = MessageDigest.getInstance("SHA-256")
        private var pageCount = 0
        private var finished = false

        init {
            require(expectedPageCount >= 0)
            digest.update(DOMAIN)
            digest.updateInt(expectedPageCount)
            digest.updateNullableText(scriptPreference)
        }

        fun addPage(page: OcrDigestPage) {
            val artifacts = page.artifacts.sortedBy(BackupOcrArtifactRecord::artifactRevision)
            require(artifacts.map(BackupOcrArtifactRecord::artifactRevision).distinct().size == artifacts.size) {
                "OCR artifact revisions must be unique within a page."
            }
            val linesByRevision = page.lines.groupBy(BackupOcrLineRecord::artifactRevision)
            require(linesByRevision.keys.all { revision -> artifacts.any { it.artifactRevision == revision } }) {
                "An OCR line references a missing artifact."
            }
            val pageBuilder = beginPage(
                legacyText = page.legacyText,
                legacyError = page.legacyError,
                pageState = page.pageState,
                correction = page.correction,
                artifactCount = artifacts.size,
            )
            artifacts.forEach { artifact ->
                val lines = linesByRevision[artifact.artifactRevision].orEmpty()
                    .sortedBy(BackupOcrLineRecord::lineOrdinal)
                pageBuilder.addArtifact(artifact, lines.asSequence())
            }
            pageBuilder.finish()
        }

        /** Starts a page whose artifacts and lines will be supplied in canonical keyset order. */
        fun beginPage(
            legacyText: String?,
            legacyError: String?,
            pageState: BackupOcrPageStateRecord?,
            correction: BackupOcrCorrectionRecord?,
            artifactCount: Int,
        ): PageBuilder {
            check(!finished) { "The OCR digest is already complete." }
            check(pageCount < expectedPageCount) { "Too many OCR digest pages." }
            require(artifactCount >= 0)
            digest.updateInt(pageCount)
            digest.updateNullableText(legacyText)
            digest.updateNullableText(legacyError)
            digest.updateInt(artifactCount)
            return PageBuilder(pageState, correction, artifactCount)
        }

        inner class PageBuilder internal constructor(
            private val pageState: BackupOcrPageStateRecord?,
            private val correction: BackupOcrCorrectionRecord?,
            private val expectedArtifactCount: Int,
        ) {
            private var artifactCount = 0
            private var previousArtifactRevision: Long? = null
            private var activeFound = false
            private var correctionBaseFound = false
            private var artifactOpen = false
            private var pageFinished = false

            fun addArtifact(
                artifact: BackupOcrArtifactRecord,
                lines: Sequence<BackupOcrLineRecord>,
            ) {
                val artifactBuilder = beginArtifact(artifact)
                lines.forEach(artifactBuilder::addLine)
                artifactBuilder.finish()
            }

            /** Starts an artifact whose lines will be supplied in bounded keyset batches. */
            fun beginArtifact(artifact: BackupOcrArtifactRecord): ArtifactBuilder {
                check(!pageFinished) { "The OCR digest page is already complete." }
                check(!artifactOpen) { "The previous OCR digest artifact is incomplete." }
                check(artifactCount < expectedArtifactCount) { "Too many OCR artifacts." }
                previousArtifactRevision?.let { previous ->
                    require(artifact.artifactRevision > previous) {
                        "OCR artifacts must be unique and in revision order."
                    }
                }
                previousArtifactRevision = artifact.artifactRevision
                val isActive = pageState?.activeArtifactRevision == artifact.artifactRevision
                val isCorrectionBase = correction?.baseArtifactRevision == artifact.artifactRevision
                activeFound = activeFound || isActive
                correctionBaseFound = correctionBaseFound || isCorrectionBase
                digest.updateBoolean(isActive)
                digest.updateBoolean(isCorrectionBase)
                digest.updateText(artifact.verificationState.name)
                digest.updateFingerprint(artifact)
                digest.updateNullableText(artifact.actualScript)
                digest.updateNullableText(artifact.recognizerId)
                digest.updateNullableText(artifact.pipelineVersion)
                digest.updateNullableText(artifact.clientVersion)
                digest.updateNullableText(artifact.delivery)
                digest.updateText(artifact.rawText)
                digest.updateInt(artifact.lineCount)
                artifactOpen = true
                return ArtifactBuilder(artifact)
            }

            inner class ArtifactBuilder internal constructor(
                private val artifact: BackupOcrArtifactRecord,
            ) {
                private var lineCount = 0
                private var artifactFinished = false

                fun addLine(line: BackupOcrLineRecord) {
                    check(!artifactFinished) { "The OCR digest artifact is already complete." }
                    require(line.artifactRevision == artifact.artifactRevision) {
                        "An OCR line references the wrong artifact."
                    }
                    require(line.lineOrdinal == lineCount) { "OCR line ordinals must be contiguous." }
                    digest.updateLine(line)
                    lineCount += 1
                }

                fun finish() {
                    check(!artifactFinished) { "The OCR digest artifact is already complete." }
                    require(lineCount == artifact.lineCount) { "The OCR line count is inconsistent." }
                    artifactFinished = true
                    artifactOpen = false
                    artifactCount += 1
                }
            }

            fun finish() {
                check(!pageFinished) { "The OCR digest page is already complete." }
                check(!artifactOpen) { "An OCR digest artifact is incomplete." }
                require(artifactCount == expectedArtifactCount) { "The OCR artifact count is incomplete." }
                require(pageState?.activeArtifactRevision == null || activeFound) {
                    "The active OCR artifact is missing."
                }
                require(correction == null || correctionBaseFound) {
                    "The OCR correction base artifact is missing."
                }
                digest.updateBoolean(correction != null)
                if (correction != null) {
                    digest.updateText(correction.correctedText)
                    digest.updateText(correction.alignment.name)
                    val lineCorrections = correction.lineCorrections.sortedBy { it.lineOrdinal }
                    require(lineCorrections.map { it.lineOrdinal }.distinct().size == lineCorrections.size) {
                        "OCR correction line ordinals must be unique."
                    }
                    digest.updateInt(lineCorrections.size)
                    lineCorrections.forEach { line ->
                        digest.updateInt(line.lineOrdinal)
                        digest.updateText(line.correctedText)
                    }
                }
                pageFinished = true
                pageCount += 1
            }
        }

        fun finish(): OcrStateDigest {
            check(!finished) { "The OCR digest is already complete." }
            check(pageCount == expectedPageCount) { "The OCR digest page count is incomplete." }
            finished = true
            return OcrStateDigest(VERSION, digest.digest().toHex())
        }
    }

    private fun MessageDigest.updateFingerprint(artifact: BackupOcrArtifactRecord) {
        val fingerprint = artifact.inputFingerprint
        updateBoolean(fingerprint != null)
        if (fingerprint != null) {
            updateInt(fingerprint.version)
            // The opaque value embeds visual revision counters. Hash the explicit semantic input
            // fields below so equivalent OCR survives restore-time ID/revision remapping.
            updateText(fingerprint.contentSha256)
            updateInt(fingerprint.rotationDegrees)
            updateText(fingerprint.filterName)
            updateInt(fingerprint.uprightWidth)
            updateInt(fingerprint.uprightHeight)
            updateInt(fingerprint.coordinateSystemVersion)
            updateInt(fingerprint.transformVersion)
        }
    }

    private fun MessageDigest.updateLine(line: BackupOcrLineRecord) {
        updateText(line.rawText)
        updateInt(line.cornerPoints.size)
        line.cornerPoints.forEach { point ->
            updateDouble(point.x)
            updateDouble(point.y)
        }
        updateDouble(line.baselineStart.x)
        updateDouble(line.baselineStart.y)
        updateDouble(line.baselineEnd.x)
        updateDouble(line.baselineEnd.y)
        updateDouble(line.baselineAngleDegrees)
        updateNullableText(line.writingOrientation)
    }

    private val DOMAIN = "RME-OCR-STATE-DIGEST-V1\u0000".toByteArray(StandardCharsets.US_ASCII)
}

private fun MessageDigest.updateBoolean(value: Boolean) = update((if (value) 1 else 0).toByte())

private fun MessageDigest.updateNullableInt(value: Int?) {
    updateBoolean(value != null)
    if (value != null) updateInt(value)
}

private fun MessageDigest.updateNullableText(value: String?) {
    updateBoolean(value != null)
    if (value != null) updateText(value)
}

private fun MessageDigest.updateText(value: String) {
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    updateInt(bytes.size)
    update(bytes)
}

private fun MessageDigest.updateInt(value: Int) {
    update(ByteBuffer.allocate(Int.SIZE_BYTES).order(ByteOrder.BIG_ENDIAN).putInt(value).array())
}

private fun MessageDigest.updateDouble(value: Double) {
    require(value.isFinite()) { "OCR geometry must be finite." }
    update(ByteBuffer.allocate(Long.SIZE_BYTES).order(ByteOrder.BIG_ENDIAN).putLong(value.toBits()).array())
}

private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}
