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

    fun continueToLayout() {
        val current = state as? CardCopyWorkflowState.ReviewSides ?: return
        val front = current.front.readySource() ?: return
        val back = current.back.readySource() ?: return
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

    fun markSavePickerOpen() = updatePrepared {
        it.copy(saveStatus = CardCopySaveStatus.ChoosingDestination)
    }

    fun onSaveDestination(uri: Uri?) {
        val current = state as? CardCopyWorkflowState.Prepared ?: return
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

    fun preparedForShare(): CardCopyPreparedPdf? =
        (state as? CardCopyWorkflowState.Prepared)?.prepared

    fun markShareFailed() = updatePrepared { it.copy(shareFailed = true) }
    fun markShareInvoked() = updatePrepared { it.copy(shareFailed = false) }

    fun back() {
        when (val current = state) {
            CardCopyWorkflowState.Hidden -> Unit
            is CardCopyWorkflowState.AcquireSides -> close()
            is CardCopyWorkflowState.ReviewSides ->
                state = CardCopyWorkflowState.AcquireSides(current.front, current.back)
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

    private companion object {
        const val ACTIVE_KEY = "card_copy_workflow_active"
    }
}
