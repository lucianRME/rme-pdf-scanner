package org.synapseworks.pageharbor.document.cardcopy

import java.io.File
import org.synapseworks.pageharbor.document.redaction.RedactionBounds
import org.synapseworks.pageharbor.document.redaction.RedactionRegion
import org.synapseworks.pageharbor.document.redaction.RedactionRegionId
import org.synapseworks.pageharbor.document.redaction.RedactedPdfVerificationEvidence
import org.synapseworks.pageharbor.document.redaction.RedactedPdfVerificationPolicy
import org.synapseworks.pageharbor.document.session.DocumentImageMetadata
import org.synapseworks.pageharbor.document.session.DocumentPageRotation

enum class CardCopySide { FRONT, BACK }

enum class CardCopyAcquisition { SCAN, IMPORT }

enum class CardCopyPageSize(
    val widthPoints: Float,
    val heightPoints: Float,
) {
    A4(595.2756f, 841.8898f),
    LETTER(612f, 792f),
}

enum class CardCopyLayout { AUTO, VERTICAL, HORIZONTAL }

enum class CardCopyAlignment { START, CENTER, END }

enum class CardCopySpacing(val points: Float) {
    COMPACT(12f),
    NORMAL(24f),
    WIDE(36f),
}

data class CardCopySettings(
    val pageSize: CardCopyPageSize = CardCopyPageSize.A4,
    val layout: CardCopyLayout = CardCopyLayout.AUTO,
    val alignment: CardCopyAlignment = CardCopyAlignment.CENTER,
    val spacing: CardCopySpacing = CardCopySpacing.NORMAL,
)

data class CardCopySource(
    val side: CardCopySide,
    val acquisition: CardCopyAcquisition,
    internal val file: File,
    internal val ownedDirectory: File,
    val metadata: DocumentImageMetadata,
    val rotation: DocumentPageRotation = DocumentPageRotation.DEGREES_0,
    val sourceId: String = ownedDirectory.name,
    val sourceSha256: String = cardCopySha256(file).orEmpty(),
    val redactions: List<RedactionRegion> = emptyList(),
) {
    init {
        require(sourceId.isNotBlank())
        require(sourceSha256.matches(Regex("[0-9a-f]{64}")))
    }

    val orientedWidth: Int
        get() = if (rotation.degrees % 180 == 0) requireNotNull(metadata.width)
        else requireNotNull(metadata.height)
    val orientedHeight: Int
        get() = if (rotation.degrees % 180 == 0) requireNotNull(metadata.height)
        else requireNotNull(metadata.width)
}

sealed interface CardCopySideState {
    data object Empty : CardCopySideState
    data class Acquiring(
        val acquisition: CardCopyAcquisition,
        val previous: CardCopySource? = null,
    ) : CardCopySideState
    data class Decoding(
        val acquisition: CardCopyAcquisition,
        val previous: CardCopySource? = null,
    ) : CardCopySideState
    data class Ready(val source: CardCopySource) : CardCopySideState
    data class Failed(
        val acquisition: CardCopyAcquisition,
        val reason: CardCopyFailure,
        val previous: CardCopySource? = null,
    ) : CardCopySideState
}

enum class CardCopyFailure {
    INVALID_SOURCE,
    SOURCE_TOO_LARGE,
    PREPARATION_FAILED,
    VALIDATION_FAILED,
    DESTINATION_UNAVAILABLE,
    WRITE_FAILED,
    PROCESS_INTERRUPTED,
    INVALID_REDACTION,
    SOURCE_CHANGED,
    SECURITY_VERIFICATION_FAILED,
}

sealed interface CardCopySaveStatus {
    data object Idle : CardCopySaveStatus
    data object ChoosingDestination : CardCopySaveStatus
    data object Saving : CardCopySaveStatus
    data class Saved(val bytes: Long) : CardCopySaveStatus
    data class Failed(val reason: CardCopyFailure) : CardCopySaveStatus
}

class CardCopyPreparedPdf internal constructor(
    internal val file: File,
    internal val workingDirectory: File,
    val bytes: Long,
    val geometry: CardCopyGeometry,
    val preparationMillis: Long,
    val peakTemporaryBytes: Long,
    /** Conservative peak for one sampled source plus one transformed/scaled ARGB bitmap. */
    val estimatedPeakBitmapBytes: Long,
    val redactionCount: Int = 0,
    val securelyVerified: Boolean = false,
    internal val verificationPolicy: RedactedPdfVerificationPolicy? = null,
    internal val verificationEvidence: RedactedPdfVerificationEvidence? = null,
) {
    init {
        require(!securelyVerified || (
            verificationPolicy != null && verificationEvidence != null &&
                verificationEvidence.bytes == bytes
            ))
    }

    internal val outputSha256: String get() = verificationEvidence?.sha256.orEmpty()
}

data class CardCopyRedactionEditor(
    val side: CardCopySide,
    val history: List<List<RedactionRegion>>,
    val historyIndex: Int,
    val selectedId: RedactionRegionId? = null,
    val nextOrdinal: Int = 1,
) {
    init {
        require(history.isNotEmpty())
        require(historyIndex in history.indices)
        require(nextOrdinal > 0)
    }

    val regions: List<RedactionRegion> get() = history[historyIndex]
    val canUndo: Boolean get() = historyIndex > 0
    val canRedo: Boolean get() = historyIndex < history.lastIndex
}

sealed interface CardCopyWorkflowState {
    data object Hidden : CardCopyWorkflowState

    data class AcquireSides(
        val front: CardCopySideState = CardCopySideState.Empty,
        val back: CardCopySideState = CardCopySideState.Empty,
    ) : CardCopyWorkflowState

    data class ReviewSides(
        val front: CardCopySideState,
        val back: CardCopySideState,
    ) : CardCopyWorkflowState

    data class RedactSide(
        val front: CardCopySource,
        val back: CardCopySource,
        val editor: CardCopyRedactionEditor,
    ) : CardCopyWorkflowState

    data class Layout(
        val front: CardCopySource,
        val back: CardCopySource,
        val settings: CardCopySettings = CardCopySettings(),
    ) : CardCopyWorkflowState

    data class Preview(
        val front: CardCopySource,
        val back: CardCopySource,
        val settings: CardCopySettings,
    ) : CardCopyWorkflowState

    data class Preparing(
        val front: CardCopySource,
        val back: CardCopySource,
        val settings: CardCopySettings,
    ) : CardCopyWorkflowState

    data class Prepared(
        val front: CardCopySource,
        val back: CardCopySource,
        val settings: CardCopySettings,
        val prepared: CardCopyPreparedPdf,
        val saveStatus: CardCopySaveStatus = CardCopySaveStatus.Idle,
        val shareFailed: Boolean = false,
        val continueToShareSafe: Boolean = false,
    ) : CardCopyWorkflowState

    data class Failed(
        val front: CardCopySource? = null,
        val back: CardCopySource? = null,
        val settings: CardCopySettings? = null,
        val reason: CardCopyFailure,
    ) : CardCopyWorkflowState
}

fun cardCopyFilename(title: String? = null): String {
    val stem = title.orEmpty()
        .removeSuffix(".pdf")
        .replace(Regex("[^A-Za-z0-9._ -]"), "_")
        .trim(' ', '.', '_')
        .take(72)
    return if (stem.isBlank()) "card-copy.pdf" else "$stem-front-back.pdf"
}

fun CardCopySideState.readySource(): CardCopySource? = when (this) {
    is CardCopySideState.Ready -> source
    is CardCopySideState.Acquiring -> previous
    is CardCopySideState.Decoding -> previous
    is CardCopySideState.Failed -> previous
    CardCopySideState.Empty -> null
}

fun CardCopyRedactionEditor.selectedRegion(): RedactionRegion? =
    selectedId?.let { selected -> regions.firstOrNull { it.id == selected } }

fun CardCopyRedactionEditor.withEdit(
    regions: List<RedactionRegion>,
    selectedId: RedactionRegionId? = this.selectedId,
    nextOrdinal: Int = this.nextOrdinal,
): CardCopyRedactionEditor {
    val truncated = history.take(historyIndex + 1)
    return copy(
        history = truncated + listOf(regions),
        historyIndex = truncated.size,
        selectedId = selectedId,
        nextOrdinal = nextOrdinal,
    )
}

internal fun defaultRedactionBounds(): RedactionBounds =
    RedactionBounds(0.2f, 0.4f, 0.8f, 0.55f)
