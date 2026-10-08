package org.synapseworks.pageharbor.document.targetsize

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Activity-scoped M5 state. Configuration changes retain it; process death fails cleanly. */
class TargetSizeWorkflowViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {
    private val engine: TargetSizePdfEngine = LocalTargetSizePdfEngine(application)
    private val destinationWriter = TargetSizeDestinationWriter(application)
    private var preparationJob: Job? = null
    private var saveJob: Job? = null
    private var cancellation = AtomicBoolean(false)
    private var generation = 0L

    var state: TargetSizeWorkflowState by mutableStateOf(
        if (savedStateHandle.get<Boolean>(ACTIVE_WORKFLOW_KEY) == true) {
            TargetSizeWorkflowState.Failed(
                input = null,
                selection = null,
                targetBytes = null,
                reason = TargetSizePdfFailure.PROCESS_INTERRUPTED,
            )
        } else {
            TargetSizeWorkflowState.Hidden
        },
    )
        private set

    init {
        // Persist only a small interruption marker, never a URI, PDF, or document content.
        savedStateHandle[ACTIVE_WORKFLOW_KEY] = state != TargetSizeWorkflowState.Hidden
    }

    fun beginOpening() {
        if (state == TargetSizeWorkflowState.Hidden) {
            savedStateHandle[ACTIVE_WORKFLOW_KEY] = true
            state = TargetSizeWorkflowState.Opening
        }
    }

    fun open(input: TargetSizeWorkflowInput) {
        preparationJob?.cancel()
        saveJob?.cancel()
        discardOwnedResult()
        generation++
        savedStateHandle[ACTIVE_WORKFLOW_KEY] = true
        state = TargetSizeWorkflowState.ChooseLimit(input)
    }

    fun failOpening() {
        savedStateHandle[ACTIVE_WORKFLOW_KEY] = true
        state = TargetSizeWorkflowState.Failed(
            input = null,
            selection = null,
            targetBytes = null,
            reason = TargetSizePdfFailure.INVALID_SOURCE,
        )
    }

    fun selectPreset(preset: TargetSizePreset) {
        val current = state as? TargetSizeWorkflowState.ChooseLimit ?: return
        state = current.copy(selection = TargetSizeLimitSelection.Preset(preset))
    }

    fun selectCustom(
        text: String = "",
        unit: TargetSizeCustomUnit = TargetSizeCustomUnit.KB,
        locale: Locale = Locale.getDefault(),
    ) {
        val current = state as? TargetSizeWorkflowState.ChooseLimit ?: return
        state = current.copy(
            selection = TargetSizeLimitSelection.Custom(
                text = text,
                unit = unit,
                validation = TargetSizeCustomLimitParser.parse(text, unit, locale),
            ),
        )
    }

    fun updateCustomText(text: String, locale: Locale = Locale.getDefault()) {
        val current = state as? TargetSizeWorkflowState.ChooseLimit ?: return
        val custom = current.selection as? TargetSizeLimitSelection.Custom
            ?: TargetSizeLimitSelection.Custom(
                text = "",
                unit = TargetSizeCustomUnit.KB,
                validation = TargetSizeCustomValidation.Invalid(TargetSizeCustomError.EMPTY),
            )
        state = current.copy(
            selection = custom.copy(
                text = text.take(MAXIMUM_CUSTOM_CHARACTERS),
                validation = TargetSizeCustomLimitParser.parse(
                    text.take(MAXIMUM_CUSTOM_CHARACTERS),
                    custom.unit,
                    locale,
                ),
            ),
        )
    }

    fun updateCustomUnit(unit: TargetSizeCustomUnit, locale: Locale = Locale.getDefault()) {
        val current = state as? TargetSizeWorkflowState.ChooseLimit ?: return
        val custom = current.selection as? TargetSizeLimitSelection.Custom ?: return
        state = current.copy(
            selection = custom.copy(
                unit = unit,
                validation = TargetSizeCustomLimitParser.parse(custom.text, unit, locale),
            ),
        )
    }

    fun prepare() {
        val current = state as? TargetSizeWorkflowState.ChooseLimit ?: return
        val targetBytes = current.selection.targetBytes ?: return
        preparationJob?.cancel()
        saveJob?.cancel()
        cancellation.set(true)
        cancellation = AtomicBoolean(false)
        val signal = cancellation
        val operation = ++generation
        val request = current.input.request.copy(
            targetBytes = targetBytes,
            cancellationSignal = TargetSizeCancellationSignal(signal::get),
        )
        state = TargetSizeWorkflowState.Preparing(
            input = current.input,
            selection = current.selection,
            targetBytes = targetBytes,
            progress = TargetSizeProgress.PLANNING,
        )
        preparationJob = viewModelScope.launch {
            try {
                val canReuseOriginal = shouldReuseOriginalPdf(
                    safeOriginalAvailable = current.input.safeOriginalPdfUri != null,
                    sourceBytes = current.input.safeOriginalPdfBytes,
                    targetBytes = targetBytes,
                )
                val result = if (canReuseOriginal) {
                    engine.prepareExistingPdfCopy(
                        TargetSizeExistingPdfCopyRequest(
                            sourcePdfUri = requireNotNull(current.input.safeOriginalPdfUri),
                            documentRequest = request,
                        ),
                        progressListener(operation),
                    )
                } else {
                    engine.prepare(request, progressListener(operation))
                }
                if (generation != operation) {
                    discardResult(result)
                    return@launch
                }
                state = when (result) {
                    is TargetSizePdfResult.TargetReached -> TargetSizeWorkflowState.Prepared(
                        input = current.input,
                        selection = current.selection,
                        requestedTargetBytes = targetBytes,
                        prepared = result.prepared,
                        reusedOriginalQuality = canReuseOriginal,
                    )
                    is TargetSizePdfResult.TargetNotReachableAtAcceptableQuality ->
                        TargetSizeWorkflowState.ImpossibleTarget(
                            input = current.input,
                            selection = current.selection,
                            requestedTargetBytes = targetBytes,
                            smallestRecommendedBytes = result.smallestAcceptableBytes,
                            smallestRecommended = result.smallestAcceptable,
                            bytesAreMeasured = result.bytesAreMeasured,
                        )
                    is TargetSizePdfResult.Failure -> if (
                        result.reason == TargetSizePdfFailure.CANCELED
                    ) {
                        TargetSizeWorkflowState.ChooseLimit(current.input, current.selection)
                    } else {
                        TargetSizeWorkflowState.Failed(
                            current.input,
                            current.selection,
                            targetBytes,
                            result.reason,
                        )
                    }
                }
            } catch (_: CancellationException) {
                // Cancellation immediately restores editable settings; the engine owns cleanup.
            }
        }
    }

    fun cancelPreparation() {
        val current = state as? TargetSizeWorkflowState.Preparing ?: return
        cancellation.set(true)
        generation++
        preparationJob?.cancel()
        preparationJob = null
        state = TargetSizeWorkflowState.ChooseLimit(current.input, current.selection)
    }

    fun acceptSmallestRecommended() {
        val current = state as? TargetSizeWorkflowState.ImpossibleTarget ?: return
        val prepared = current.smallestRecommended ?: return
        state = TargetSizeWorkflowState.Prepared(
            input = current.input,
            selection = current.selection,
            requestedTargetBytes = current.smallestRecommendedBytes,
            prepared = prepared,
            reusedOriginalQuality = false,
            acceptedAlternative = true,
        )
    }

    fun changeLimit() {
        val current = state
        val input = when (current) {
            is TargetSizeWorkflowState.Prepared -> current.input
            is TargetSizeWorkflowState.ImpossibleTarget -> current.input
            is TargetSizeWorkflowState.Failed -> current.input
            else -> null
        } ?: return
        val selection = when (current) {
            is TargetSizeWorkflowState.Prepared -> current.selection
            is TargetSizeWorkflowState.ImpossibleTarget -> current.selection
            is TargetSizeWorkflowState.Failed -> current.selection
            else -> null
        } ?: TargetSizeLimitSelection.Preset(TargetSizePreset.MB_1)
        discardOwnedResult()
        generation++
        state = TargetSizeWorkflowState.ChooseLimit(input, selection)
    }

    fun retry() {
        val current = state as? TargetSizeWorkflowState.Failed ?: return
        val input = current.input ?: return
        state = TargetSizeWorkflowState.ChooseLimit(
            input,
            current.selection ?: TargetSizeLimitSelection.Preset(TargetSizePreset.MB_1),
        )
        prepare()
    }

    fun markSavePickerOpen() {
        updatePrepared { it.copy(saveStatus = TargetSizeSaveStatus.ChoosingDestination) }
    }

    fun onSaveDestination(destination: Uri?) {
        val current = state as? TargetSizeWorkflowState.Prepared ?: return
        if (destination == null) {
            state = current.copy(saveStatus = TargetSizeSaveStatus.Idle)
            return
        }
        val prepared = current.prepared
        val operation = generation
        state = current.copy(saveStatus = TargetSizeSaveStatus.Saving)
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            val result = try {
                destinationWriter.write(prepared, destination)
            } catch (_: CancellationException) {
                return@launch
            }
            val latest = state as? TargetSizeWorkflowState.Prepared ?: return@launch
            if (generation != operation || latest.prepared !== prepared) return@launch
            state = latest.copy(
                saveStatus = when (result) {
                    is TargetSizeDestinationResult.Success ->
                        TargetSizeSaveStatus.Saved(result.bytes)
                    is TargetSizeDestinationResult.Failure ->
                        TargetSizeSaveStatus.Failed(result.reason)
                },
            )
        }
    }

    fun preparedForShare(): TargetSizePreparedPdf? =
        (state as? TargetSizeWorkflowState.Prepared)?.prepared

    fun markShareInvoked() {
        updatePrepared { it.copy(shareInvoked = true, shareFailed = false) }
    }

    fun markShareFailed() {
        updatePrepared { it.copy(shareInvoked = false, shareFailed = true) }
    }

    fun close() {
        cancellation.set(true)
        generation++
        preparationJob?.cancel()
        saveJob?.cancel()
        preparationJob = null
        saveJob = null
        discardOwnedResult()
        savedStateHandle[ACTIVE_WORKFLOW_KEY] = false
        state = TargetSizeWorkflowState.Hidden
    }

    override fun onCleared() {
        cancellation.set(true)
        discardOwnedResult()
        super.onCleared()
    }

    private fun progressListener(operation: Long) = TargetSizeProgressListener { progress ->
        val current = state as? TargetSizeWorkflowState.Preparing
        if (generation == operation && current != null) state = current.copy(progress = progress)
    }

    private fun discardOwnedResult() {
        when (val current = state) {
            is TargetSizeWorkflowState.Prepared -> engine.discard(current.prepared)
            is TargetSizeWorkflowState.ImpossibleTarget ->
                current.smallestRecommended?.let(engine::discard)
            else -> Unit
        }
    }

    private fun discardResult(result: TargetSizePdfResult) {
        when (result) {
            is TargetSizePdfResult.TargetReached -> engine.discard(result.prepared)
            is TargetSizePdfResult.TargetNotReachableAtAcceptableQuality ->
                result.smallestAcceptable?.let(engine::discard)
            is TargetSizePdfResult.Failure -> Unit
        }
    }

    private fun updatePrepared(
        transform: (TargetSizeWorkflowState.Prepared) -> TargetSizeWorkflowState.Prepared,
    ) {
        val current = state as? TargetSizeWorkflowState.Prepared ?: return
        state = transform(current)
    }

    private companion object {
        const val ACTIVE_WORKFLOW_KEY = "target_size_workflow_active"
        const val MAXIMUM_CUSTOM_CHARACTERS = 24
    }
}
