package org.synapseworks.pageharbor.document.cardcopy

import java.io.File
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
) {
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
)

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
