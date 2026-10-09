package org.synapseworks.pageharbor.document.cardcopy

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.synapseworks.pageharbor.document.redaction.RedactionBounds
import org.synapseworks.pageharbor.document.redaction.RedactionCoordinateMapper
import org.synapseworks.pageharbor.document.redaction.RedactionCoordinateSpace
import org.synapseworks.pageharbor.document.redaction.RedactionRegion
import org.synapseworks.pageharbor.document.redaction.RedactionRegionId
import org.synapseworks.pageharbor.document.redaction.RedactionTarget
import org.synapseworks.pageharbor.document.redaction.RedactionTargetKind
import org.synapseworks.pageharbor.document.session.DocumentPageRotation

class CardCopyWorkflowViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {
    private val stager = CardCopySourceStager(application)
    private val composer = CardCopyPdfComposer(application)
    private val destinationWriter = CardCopyDestinationWriter(application)
    private var acquisitionJob: Job? = null
    private var preparationJob: Job? = null
    private var saveJob: Job? = null
    private var generation = 0L

    var state: CardCopyWorkflowState by mutableStateOf(
        if (savedStateHandle.get<Boolean>(ACTIVE_KEY) == true) {
            CardCopyWorkflowState.Failed(reason = CardCopyFailure.PROCESS_INTERRUPTED)
        } else CardCopyWorkflowState.Hidden,
    )
        private set

    init {
        savedStateHandle[ACTIVE_KEY] = state != CardCopyWorkflowState.Hidden
    }

    fun open() {
        if (state != CardCopyWorkflowState.Hidden) return
        savedStateHandle[ACTIVE_KEY] = true
        state = CardCopyWorkflowState.AcquireSides()
    }

    fun beginAcquisition(side: CardCopySide, acquisition: CardCopyAcquisition): Boolean {
        val sides = currentSideStates() ?: return false
        if (sides.first is CardCopySideState.Acquiring || sides.second is CardCopySideState.Acquiring ||
            sides.first is CardCopySideState.Decoding || sides.second is CardCopySideState.Decoding
        ) return false
        val current = if (side == CardCopySide.FRONT) sides.first else sides.second
        val acquiring = CardCopySideState.Acquiring(acquisition, current.readySource())
        updateSideState(side, acquiring)
        return true
    }

    fun onAcquisitionResult(uri: Uri?) {
        val pending = pendingAcquisition() ?: return
        if (uri == null) {
            updateSideState(pending.first, pending.second.previous?.let(CardCopySideState::Ready)
                ?: CardCopySideState.Empty)
            return
        }
        val side = pending.first
        val acquisition = pending.second.acquisition
        val previous = pending.second.previous
        updateSideState(side, CardCopySideState.Decoding(acquisition, previous))
        val operation = ++generation
        acquisitionJob?.cancel()
        acquisitionJob = viewModelScope.launch {
            val result = stager.stage(uri, side, acquisition)
            if (generation != operation) {
                if (result is CardCopyStageResult.Success) discardCardCopySource(result.source)
                return@launch
            }
            when (result) {
                is CardCopyStageResult.Success -> {
                    previous?.let(::discardCardCopySource)
                    updateSideState(side, CardCopySideState.Ready(result.source))
                }
                is CardCopyStageResult.Failure -> updateSideState(
                    side,
                    CardCopySideState.Failed(acquisition, result.reason, previous),
                )
            }
        }
    }

    fun onAcquisitionFailure(reason: CardCopyFailure = CardCopyFailure.INVALID_SOURCE) {
        val pending = pendingAcquisition() ?: return
        updateSideState(
            pending.first,
            CardCopySideState.Failed(
                pending.second.acquisition,
                reason,
                pending.second.previous,
            ),
        )
    }

    fun continueToReview() {
        val current = state as? CardCopyWorkflowState.AcquireSides ?: return
        if (current.front.readySource() == null || current.back.readySource() == null) return
        state = CardCopyWorkflowState.ReviewSides(current.front, current.back)
    }

    fun rotate(side: CardCopySide, clockwise: Boolean) {
        val current = currentSideStates() ?: return
        val sideState = if (side == CardCopySide.FRONT) current.first else current.second
        val source = sideState.readySource() ?: return
        val rotation = if (clockwise) source.rotation.clockwise() else source.rotation.counterClockwise()
        updateSideState(side, CardCopySideState.Ready(source.copy(rotation = rotation)))
    }

    fun remove(side: CardCopySide) {
        val current = currentSideStates() ?: return
        val source = if (side == CardCopySide.FRONT) current.first.readySource()
        else current.second.readySource()
        source?.let(::discardCardCopySource)
        val front = if (side == CardCopySide.FRONT) CardCopySideState.Empty else current.first
        val back = if (side == CardCopySide.BACK) CardCopySideState.Empty else current.second
        state = CardCopyWorkflowState.AcquireSides(front, back)
    }

    fun swap() {
        val current = state as? CardCopyWorkflowState.ReviewSides ?: return
        val front = current.front.readySource() ?: return
        val back = current.back.readySource() ?: return
        state = CardCopyWorkflowState.ReviewSides(
            front = CardCopySideState.Ready(back.copy(side = CardCopySide.FRONT)),
            back = CardCopySideState.Ready(front.copy(side = CardCopySide.BACK)),
        )
    }

    /**
     * Opens the deliberately narrow card-side redaction editor. Drafts are detached from the
     * applied source until [applyRedactions] so system Back is a true cancel operation.
     */
    fun openRedactionEditor(side: CardCopySide): Boolean {
        val current = state as? CardCopyWorkflowState.ReviewSides ?: return false
        val front = current.front.readySource() ?: return false
        val back = current.back.readySource() ?: return false
        val source = if (side == CardCopySide.FRONT) front else back
        if (!source.hasValidAppliedRedactions()) return false
        val drafts = source.redactions.map { it.copy(confirmed = false) }
        generation++
        state = CardCopyWorkflowState.RedactSide(
            front = front,
            back = back,
            editor = CardCopyRedactionEditor(
                side = side,
                history = listOf(drafts),
                historyIndex = 0,
                selectedId = drafts.firstOrNull()?.id,
                nextOrdinal = (drafts.maxOfOrNull(RedactionRegion::order) ?: -1) + 2,
            ),
        )
        return true
    }

    /** [displayBounds] belongs to the currently rotated editor image, never the screen itself. */
    fun addRedactionFromDisplay(displayBounds: RedactionBounds = defaultRedactionBounds()) {
        val current = state as? CardCopyWorkflowState.RedactSide ?: return
        val source = current.editorSource()
        val bounds = runCatching {
            RedactionCoordinateMapper.inverseRotate(displayBounds, source.rotation)
        }.getOrNull() ?: return
        val ordinal = current.editor.nextOrdinal
        val id = RedactionRegionId("${source.sourceId}-redaction-$ordinal")
        val region = RedactionRegion(
            id = id,
            coordinateSpace = RedactionCoordinateSpace.SOURCE_NORMALIZED,
            bounds = bounds,
            target = RedactionTarget(RedactionTargetKind.CARD_SOURCE, source.sourceId),
            confirmed = false,
            order = ordinal - 1,
        )
        generation++
        state = current.copy(
            editor = current.editor.withEdit(
                regions = current.editor.regions + region,
                selectedId = id,
                nextOrdinal = ordinal + 1,
            ),
        )
    }

    fun selectRedaction(id: RedactionRegionId) {
        val current = state as? CardCopyWorkflowState.RedactSide ?: return
        if (current.editor.regions.none { it.id == id }) return
        state = current.copy(editor = current.editor.copy(selectedId = id))
    }

    /** Updates a draft from rotated editor coordinates while persisting source-normalized bounds. */
    fun updateRedactionFromDisplay(id: RedactionRegionId, displayBounds: RedactionBounds) {
        val current = state as? CardCopyWorkflowState.RedactSide ?: return
        val source = current.editorSource()
        val existing = current.editor.regions.firstOrNull { it.id == id } ?: return
        if (existing.target.kind != RedactionTargetKind.CARD_SOURCE ||
            existing.target.stableId != source.sourceId
        ) return
        val bounds = runCatching {
            RedactionCoordinateMapper.inverseRotate(displayBounds, source.rotation)
        }.getOrNull() ?: return
        if (bounds == existing.bounds) {
            selectRedaction(id)
            return
        }
        generation++
        state = current.copy(
            editor = current.editor.withEdit(
                regions = current.editor.regions.map { region ->
                    if (region.id == id) region.copy(bounds = bounds, confirmed = false) else region
                },
                selectedId = id,
            ),
        )
    }

    fun deleteSelectedRedaction() {
        val current = state as? CardCopyWorkflowState.RedactSide ?: return
        val selected = current.editor.selectedId ?: return
        if (current.editor.regions.none { it.id == selected }) return
        generation++
        state = current.copy(
            editor = current.editor.withEdit(
                regions = current.editor.regions.filterNot { it.id == selected },
                selectedId = null,
            ),
        )
    }

    fun undoRedactionEdit() {
        val current = state as? CardCopyWorkflowState.RedactSide ?: return
        if (!current.editor.canUndo) return
        generation++
        val editor = current.editor.copy(historyIndex = current.editor.historyIndex - 1)
        state = current.copy(
            editor = editor.copy(
                selectedId = editor.selectedId?.takeIf { selected ->
                    editor.regions.any { it.id == selected }
                },
            ),
        )
    }

    fun redoRedactionEdit() {
        val current = state as? CardCopyWorkflowState.RedactSide ?: return
        if (!current.editor.canRedo) return
        generation++
        val editor = current.editor.copy(historyIndex = current.editor.historyIndex + 1)
        state = current.copy(
            editor = editor.copy(
                selectedId = editor.selectedId?.takeIf { selected ->
                    editor.regions.any { it.id == selected }
                },
            ),
        )
    }

    /** Explicit confirmation is the only operation that attaches draft regions to a source. */
    fun applyRedactions() {
        val current = state as? CardCopyWorkflowState.RedactSide ?: return
        val source = current.editorSource()
        val confirmed = current.editor.regions.map { region ->
            if (region.coordinateSpace != RedactionCoordinateSpace.SOURCE_NORMALIZED ||
                region.target.kind != RedactionTargetKind.CARD_SOURCE ||
                region.target.stableId != source.sourceId
            ) return
            region.copy(confirmed = true)
        }
        val updated = source.copy(redactions = confirmed)
        generation++
        state = CardCopyWorkflowState.ReviewSides(
            front = CardCopySideState.Ready(
                if (current.editor.side == CardCopySide.FRONT) updated else current.front,
            ),
            back = CardCopySideState.Ready(
                if (current.editor.side == CardCopySide.BACK) updated else current.back,
            ),
        )
    }

    fun continueToLayout() {
        val current = state as? CardCopyWorkflowState.ReviewSides ?: return
        val front = current.front.readySource() ?: return
        val back = current.back.readySource() ?: return
        if (!front.hasValidAppliedRedactions() || !back.hasValidAppliedRedactions()) return
        state = CardCopyWorkflowState.Layout(front, back)
    }

    fun updatePageSize(value: CardCopyPageSize) = updateLayout { it.copy(pageSize = value) }
    fun updateLayout(value: CardCopyLayout) = updateLayout { it.copy(layout = value) }
    fun updateAlignment(value: CardCopyAlignment) = updateLayout { it.copy(alignment = value) }
    fun updateSpacing(value: CardCopySpacing) = updateLayout { it.copy(spacing = value) }

    fun showPreview() {
        val current = state as? CardCopyWorkflowState.Layout ?: return
        state = CardCopyWorkflowState.Preview(current.front, current.back, current.settings)
    }

    fun prepare() {
        val current = state as? CardCopyWorkflowState.Preview ?: return
        if (!current.front.hasValidAppliedRedactions() ||
            !current.back.hasValidAppliedRedactions()
        ) return
        val operation = ++generation
        preparationJob?.cancel()
        state = CardCopyWorkflowState.Preparing(current.front, current.back, current.settings)
        preparationJob = viewModelScope.launch {
            try {
                val result = composer.prepare(current.front, current.back, current.settings)
                if (generation != operation) {
                    if (result is CardCopyPrepareResult.Success) composer.discard(result.prepared)
                    return@launch
                }
                state = when (result) {
                    is CardCopyPrepareResult.Success -> CardCopyWorkflowState.Prepared(
                        current.front, current.back, current.settings, result.prepared,
                    )
                    is CardCopyPrepareResult.Failure -> CardCopyWorkflowState.Failed(
                        current.front, current.back, current.settings, result.reason,
                    )
                }
            } catch (_: CancellationException) {
                // Explicit back/cancel establishes the replacement state and composer cleans output.
            }
        }
    }

    fun markSavePickerOpen(): Boolean {
        val current = state as? CardCopyWorkflowState.Prepared ?: return false
        if (!current.isSecurelyCurrent()) {
            state = current.copy(
                saveStatus = CardCopySaveStatus.Failed(
                    CardCopyFailure.SECURITY_VERIFICATION_FAILED,
                ),
            )
            return false
        }
        state = current.copy(saveStatus = CardCopySaveStatus.ChoosingDestination)
        return true
    }

    fun onSaveDestination(uri: Uri?) {
        val current = state as? CardCopyWorkflowState.Prepared ?: return
        if (!current.isSecurelyCurrent()) {
            state = current.copy(
                saveStatus = CardCopySaveStatus.Failed(
                    CardCopyFailure.SECURITY_VERIFICATION_FAILED,
                ),
            )
            return
        }
        if (uri == null) {
            state = current.copy(saveStatus = CardCopySaveStatus.Idle)
            return
        }
        val operation = generation
        val prepared = current.prepared
        state = current.copy(saveStatus = CardCopySaveStatus.Saving)
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            val result = destinationWriter.write(prepared, uri)
            val latest = state as? CardCopyWorkflowState.Prepared ?: return@launch
            if (generation != operation || latest.prepared !== prepared) return@launch
            state = latest.copy(
                saveStatus = when (result) {
                    is CardCopyDestinationResult.Success -> CardCopySaveStatus.Saved(result.bytes)
                    is CardCopyDestinationResult.Failure -> CardCopySaveStatus.Failed(result.reason)
                },
            )
        }
    }

    fun preparedForShare(): CardCopyPreparedPdf? {
        val current = state as? CardCopyWorkflowState.Prepared ?: return null
        return current.prepared.takeIf { current.isSecurelyCurrent() }
    }

    fun markShareFailed() = updatePrepared { it.copy(shareFailed = true) }
    fun markShareInvoked() = updatePrepared { it.copy(shareFailed = false) }

    fun back() {
        when (val current = state) {
            CardCopyWorkflowState.Hidden -> Unit
            is CardCopyWorkflowState.AcquireSides -> close()
            is CardCopyWorkflowState.ReviewSides ->
                state = CardCopyWorkflowState.AcquireSides(current.front, current.back)
            is CardCopyWorkflowState.RedactSide -> state = CardCopyWorkflowState.ReviewSides(
                CardCopySideState.Ready(current.front), CardCopySideState.Ready(current.back),
            )
            is CardCopyWorkflowState.Layout -> state = CardCopyWorkflowState.ReviewSides(
                CardCopySideState.Ready(current.front), CardCopySideState.Ready(current.back),
            )
            is CardCopyWorkflowState.Preview -> state = CardCopyWorkflowState.Layout(
                current.front, current.back, current.settings,
            )
            is CardCopyWorkflowState.Preparing -> {
                generation++
                preparationJob?.cancel()
                state = CardCopyWorkflowState.Preview(current.front, current.back, current.settings)
            }
            is CardCopyWorkflowState.Prepared -> {
                generation++
                saveJob?.cancel()
                composer.discard(current.prepared)
                state = CardCopyWorkflowState.Layout(current.front, current.back, current.settings)
            }
            is CardCopyWorkflowState.Failed -> {
                if (current.front != null && current.back != null && current.settings != null) {
                    state = CardCopyWorkflowState.Preview(
                        current.front, current.back, current.settings,
                    )
                } else close()
            }
        }
    }

    fun retryPreparation() {
        val current = state as? CardCopyWorkflowState.Failed ?: return
        val front = current.front ?: return
        val back = current.back ?: return
        val settings = current.settings ?: return
        state = CardCopyWorkflowState.Preview(front, back, settings)
        prepare()
    }

    fun close() {
        generation++
        acquisitionJob?.cancel()
        preparationJob?.cancel()
        saveJob?.cancel()
        ownedPrepared()?.let(composer::discard)
        ownedSources().distinctBy { it.ownedDirectory.path }.forEach(::discardCardCopySource)
        savedStateHandle[ACTIVE_KEY] = false
        state = CardCopyWorkflowState.Hidden
    }

    override fun onCleared() {
        generation++
        ownedPrepared()?.let(composer::discard)
        ownedSources().distinctBy { it.ownedDirectory.path }.forEach(::discardCardCopySource)
        super.onCleared()
    }

    private fun updateLayout(transform: (CardCopySettings) -> CardCopySettings) {
        when (val current = state) {
            is CardCopyWorkflowState.Layout -> state = current.copy(settings = transform(current.settings))
            else -> Unit
        }
    }

    private fun updateSideState(side: CardCopySide, sideState: CardCopySideState) {
        state = when (val current = state) {
            is CardCopyWorkflowState.AcquireSides -> if (side == CardCopySide.FRONT) {
                current.copy(front = sideState)
            } else current.copy(back = sideState)
            is CardCopyWorkflowState.ReviewSides -> if (side == CardCopySide.FRONT) {
                current.copy(front = sideState)
            } else current.copy(back = sideState)
            else -> current
        }
    }

    private fun currentSideStates(): Pair<CardCopySideState, CardCopySideState>? = when (val current = state) {
        is CardCopyWorkflowState.AcquireSides -> current.front to current.back
        is CardCopyWorkflowState.ReviewSides -> current.front to current.back
        else -> null
    }

    private fun pendingAcquisition(): Pair<CardCopySide, CardCopySideState.Acquiring>? {
        val sides = currentSideStates() ?: return null
        (sides.first as? CardCopySideState.Acquiring)?.let { return CardCopySide.FRONT to it }
        (sides.second as? CardCopySideState.Acquiring)?.let { return CardCopySide.BACK to it }
        return null
    }

    private fun ownedSources(): List<CardCopySource> = when (val current = state) {
        is CardCopyWorkflowState.AcquireSides -> listOfNotNull(
            current.front.readySource(), current.back.readySource(),
        )
        is CardCopyWorkflowState.ReviewSides -> listOfNotNull(
            current.front.readySource(), current.back.readySource(),
        )
        is CardCopyWorkflowState.RedactSide -> listOf(current.front, current.back)
        is CardCopyWorkflowState.Layout -> listOf(current.front, current.back)
        is CardCopyWorkflowState.Preview -> listOf(current.front, current.back)
        is CardCopyWorkflowState.Preparing -> listOf(current.front, current.back)
        is CardCopyWorkflowState.Prepared -> listOf(current.front, current.back)
        is CardCopyWorkflowState.Failed -> listOfNotNull(current.front, current.back)
        CardCopyWorkflowState.Hidden -> emptyList()
    }

    private fun ownedPrepared(): CardCopyPreparedPdf? =
        (state as? CardCopyWorkflowState.Prepared)?.prepared

    private fun updatePrepared(
        transform: (CardCopyWorkflowState.Prepared) -> CardCopyWorkflowState.Prepared,
    ) {
        val current = state as? CardCopyWorkflowState.Prepared ?: return
        state = transform(current)
    }

    private fun DocumentPageRotation.counterClockwise(): DocumentPageRotation =
        DocumentPageRotation.entries[(ordinal + DocumentPageRotation.entries.size - 1) %
            DocumentPageRotation.entries.size]

    private fun CardCopyWorkflowState.RedactSide.editorSource(): CardCopySource =
        if (editor.side == CardCopySide.FRONT) front else back

    private fun CardCopySource.hasValidAppliedRedactions(): Boolean = redactions.all { region ->
        region.confirmed &&
            region.coordinateSpace == RedactionCoordinateSpace.SOURCE_NORMALIZED &&
            region.target.kind == RedactionTargetKind.CARD_SOURCE &&
            region.target.stableId == sourceId
    }

    private fun CardCopyWorkflowState.Prepared.isSecurelyCurrent(): Boolean =
        prepared.securelyVerified &&
            prepared.verificationEvidence?.let { evidence ->
                prepared.file.isFile && prepared.file.length() == evidence.bytes &&
                    cardCopySha256(prepared.file) == evidence.sha256
            } == true &&
            prepared.redactionCount == front.redactions.size + back.redactions.size &&
            front.hasValidAppliedRedactions() && back.hasValidAppliedRedactions()

    private companion object {
        const val ACTIVE_KEY = "card_copy_workflow_active"
    }
}
