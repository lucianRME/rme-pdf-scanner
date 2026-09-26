package org.synapseworks.pageharbor.backup.format

data class BackupOcrDocumentStateRecord(
    val documentId: String,
    val contentRevision: Long,
    val scriptPreference: String?,
)

data class BackupOcrPageStateRecord(
    val pageId: String,
    val visualRevision: Long,
    val ocrStateRevision: Long,
    val activeArtifactRevision: Long?,
)

enum class BackupOcrVerificationState {
    CURRENT_VERIFIED,
    LEGACY_UNVERIFIED,
}

data class BackupOcrInputFingerprint(
    val version: Int,
    val value: String,
    val contentSha256: String,
    val visualRevision: Long,
    val rotationDegrees: Int,
    val filterName: String,
    val uprightWidth: Int,
    val uprightHeight: Int,
    val coordinateSystemVersion: Int,
    val transformVersion: Int,
)

data class BackupOcrArtifactRecord(
    val pageId: String,
    val artifactRevision: Long,
    val capturedPageVisualRevision: Long?,
    val capturedDocumentContentRevision: Long?,
    val verificationState: BackupOcrVerificationState,
    val inputFingerprint: BackupOcrInputFingerprint?,
    val actualScript: String?,
    val recognizerId: String?,
    val pipelineVersion: String?,
    val clientVersion: String?,
    val delivery: String?,
    val recognizedAtEpochMillis: Long?,
    val rawText: String,
    val lineCount: Int,
)

enum class BackupOcrCorrectionAlignment {
    LINE_ALIGNED,
    FREEFORM,
}

data class BackupOcrLineCorrectionRecord(
    val lineOrdinal: Int,
    val correctedText: String,
)

data class BackupOcrCorrectionRecord(
    val pageId: String,
    val baseArtifactRevision: Long,
    val correctedText: String,
    val correctedAtEpochMillis: Long,
    val alignment: BackupOcrCorrectionAlignment,
    val lineCorrections: List<BackupOcrLineCorrectionRecord>,
)

data class BackupOcrPoint(
    val x: Double,
    val y: Double,
)

data class BackupOcrLineRecord(
    val pageId: String,
    val artifactRevision: Long,
    val lineOrdinal: Int,
    val rawText: String,
    val cornerPoints: List<BackupOcrPoint>,
    val baselineStart: BackupOcrPoint,
    val baselineEnd: BackupOcrPoint,
    val baselineAngleDegrees: Double,
    val writingOrientation: String?,
)
