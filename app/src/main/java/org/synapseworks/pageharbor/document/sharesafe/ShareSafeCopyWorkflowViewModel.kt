package org.synapseworks.pageharbor.document.sharesafe

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import java.time.LocalDate
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.synapseworks.pageharbor.R
import org.synapseworks.pageharbor.document.cardcopy.CardCopyPreparedPdf
import org.synapseworks.pageharbor.document.redaction.RedactionBounds
import org.synapseworks.pageharbor.document.redaction.RedactionCoordinateSpace
import org.synapseworks.pageharbor.document.redaction.RedactionRegion
import org.synapseworks.pageharbor.document.redaction.RedactionRegionId
import org.synapseworks.pageharbor.document.redaction.RedactionTarget
import org.synapseworks.pageharbor.document.redaction.RedactionTargetKind
import org.synapseworks.pageharbor.document.session.DocumentPageRotation

enum class ShareSafeSourceKind { DOCUMENT, CARD_COPY }
enum class ShareSafeConfigureStep { REDACT, PURPOSE, SIZE, REVIEW }

sealed interface ShareSafeCopyWorkflowState {
    data object Hidden : ShareSafeCopyWorkflowState

    data class ChooseRecipe(
        val documentAvailable: Boolean,
        val documentTitle: String? = null,
        val pageCount: Int = 0,
    ) : ShareSafeCopyWorkflowState

    /** M6 owns the visible acquisition/layout/redaction flow while this marker owns the handoff. */
    data object CardCopyHandoff : ShareSafeCopyWorkflowState

    data class Configure(
        val title: String,
        val pageCount: Int,
        val sourceKind: ShareSafeSourceKind,
        val recipe: ShareSafeRecipe,
        val watermark: ShareSafeWatermarkConfig,
        val targetSize: ShareSafeTargetSizeConfig,
        val step: ShareSafeConfigureStep,
        val selectedPageIndex: Int = 0,
        val selectedPagePreviewUri: Uri? = null,
        val selectedPageRotation: DocumentPageRotation = DocumentPageRotation.DEGREES_0,
        val redactions: List<RedactionRegion> = emptyList(),
        val selectedRedactionId: RedactionRegionId? = null,
    ) : ShareSafeCopyWorkflowState

    data class Preparing(
        val title: String,
        val recipe: ShareSafeRecipe,
        val stage: ShareSafePipelineStage,
    ) : ShareSafeCopyWorkflowState

    data class Prepared(
        val title: String,
        val sourceKind: ShareSafeSourceKind,
        val prepared: ShareSafePreparedPdf,
        val saveStatus: ShareSafeSaveStatus = ShareSafeSaveStatus.Idle,
        val shareFailed: Boolean = false,
    ) : ShareSafeCopyWorkflowState

    data class Failed(
        val reason: ShareSafeFailure,
        val canRetry: Boolean,
    ) : ShareSafeCopyWorkflowState
}

/** Activity-scoped local state. Only an interruption marker survives process death. */
class ShareSafeCopyWorkflowViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {
    private val engine = ShareSafeCopyEngine(application)
    private val destinationWriter = ShareSafeDestinationWriter(application)
    private var documentInput: ShareSafeDocumentInput? = null
    private var cardArtifact: ShareSafeAttestedArtifact? = null
    private var preparationJob: Job? = null
    private var saveJob: Job? = null
    private var generation = 0L
    private var nextRedactionOrdinal = 1
    private var lastConfiguration: ShareSafeCopyWorkflowState.Configure? = null

    var state: ShareSafeCopyWorkflowState by mutableStateOf(
        if (savedStateHandle.get<Boolean>(ACTIVE_KEY) == true) {
            ShareSafeCopyWorkflowState.Failed(
                reason = ShareSafeFailure.PROCESS_INTERRUPTED,
                canRetry = false,
            )
        } else {
            ShareSafeCopyWorkflowState.Hidden
        },
    )
        private set

    init {
        savedStateHandle[ACTIVE_KEY] = state != ShareSafeCopyWorkflowState.Hidden
    }

    internal fun open(input: ShareSafeDocumentInput?) {
        clearOwnedResult()
        generation++
        documentInput = input?.copy(sourceGeneration = generation)
        cardArtifact = null
        savedStateHandle[ACTIVE_KEY] = true
        state = ShareSafeCopyWorkflowState.ChooseRecipe(
            documentAvailable = input != null,
            documentTitle = input?.title,
            pageCount = input?.pages?.size ?: 0,
        )
    }

    fun selectRecipe(recipe: ShareSafeRecipe) {
        val current = state as? ShareSafeCopyWorkflowState.ChooseRecipe ?: return
        if (recipe == ShareSafeRecipe.SHARE_ID_COPY) {
            state = ShareSafeCopyWorkflowState.CardCopyHandoff
            return
        }
        val input = documentInput ?: return
        val preset = ShareSafeRecipes.preset(recipe)
        state = configureState(
            title = current.documentTitle ?: input.title,
            sourceKind = ShareSafeSourceKind.DOCUMENT,
            recipe = recipe,
            watermark = preset.watermark,
            targetSize = preset.targetSize,
        ).also { lastConfiguration = it }
    }

    internal fun acceptCardCopy(prepared: CardCopyPreparedPdf): Boolean {
        if (state != ShareSafeCopyWorkflowState.CardCopyHandoff) return false
        generation++
        val artifact = prepared.toShareSafeAttestation(generation) ?: return false
        documentInput = null
        cardArtifact = artifact
        val preset = ShareSafeRecipes.preset(ShareSafeRecipe.SHARE_ID_COPY)
        state = ShareSafeCopyWorkflowState.Configure(
            title = getApplication<Application>().getString(R.string.share_safe_id_copy_title),
            pageCount = prepared.geometry.let { 1 },
            sourceKind = ShareSafeSourceKind.CARD_COPY,
            recipe = preset.recipe,
            watermark = preset.watermark,
            targetSize = preset.targetSize,
            step = ShareSafeConfigureStep.PURPOSE,
        ).also { lastConfiguration = it }
        return true
    }

    fun selectPage(index: Int) = updateConfigure { current ->
        if (current.sourceKind != ShareSafeSourceKind.DOCUMENT) return@updateConfigure current
        val input = documentInput ?: return@updateConfigure current
        val safeIndex = index.coerceIn(0, input.pages.lastIndex)
        current.copy(
            selectedPageIndex = safeIndex,
            selectedPagePreviewUri = input.pages[safeIndex].previewUri,
            selectedPageRotation = input.pages[safeIndex].source.rotation,
            redactions = input.pages[safeIndex].redactions,
            selectedRedactionId = null,
        )
    }

    fun addRedaction(bounds: RedactionBounds = RedactionBounds(0.2f, 0.4f, 0.8f, 0.55f)) {
        val current = state as? ShareSafeCopyWorkflowState.Configure ?: return
        if (current.sourceKind != ShareSafeSourceKind.DOCUMENT) return
        val input = documentInput ?: return
        val page = input.pages.getOrNull(current.selectedPageIndex) ?: return
        val region = RedactionRegion(
            id = RedactionRegionId("share-safe-${generation}-${nextRedactionOrdinal++}"),
            coordinateSpace = RedactionCoordinateSpace.SOURCE_NORMALIZED,
            bounds = bounds,
            target = RedactionTarget(
                kind = RedactionTargetKind.DOCUMENT_PAGE,
                stableId = page.source.pageId,
            ),
            confirmed = true,
            order = page.redactions.size,
        )
        replacePageRedactions(current.selectedPageIndex, page.redactions + region, region.id)
    }

    fun selectRedaction(id: RedactionRegionId) = updateConfigure { current ->
        current.copy(selectedRedactionId = id.takeIf { selected ->
            current.redactions.any { it.id == selected }
        })
    }

    fun updateSelectedRedaction(bounds: RedactionBounds) {
        val current = state as? ShareSafeCopyWorkflowState.Configure ?: return
        val selected = current.selectedRedactionId ?: return
        replacePageRedactions(
            current.selectedPageIndex,
            current.redactions.map { region ->
                if (region.id == selected) region.copy(bounds = bounds) else region
            },
            selected,
        )
    }

    fun deleteSelectedRedaction() {
        val current = state as? ShareSafeCopyWorkflowState.Configure ?: return
        val selected = current.selectedRedactionId ?: return
        replacePageRedactions(
            current.selectedPageIndex,
            current.redactions.filterNot { it.id == selected },
            null,
        )
    }

    fun setWatermarkEnabled(enabled: Boolean) = updateOutputSetting { current ->
        current.copy(watermark = current.watermark.copy(enabled = enabled))
    }

    fun updatePurpose(text: String) = updateOutputSetting { current ->
        current.copy(watermark = current.watermark.copy(purposeText = text))
    }

    fun setDateEnabled(
        enabled: Boolean,
        today: LocalDate = LocalDate.now(),
        locale: Locale = Locale.getDefault(),
    ) = updateOutputSetting { current ->
        val watermark = if (enabled && !current.watermark.includeDate) {
            current.watermark.withSuggestedLocalDate(today, locale)
        } else {
            current.watermark.copy(includeDate = enabled)
        }
        current.copy(watermark = watermark)
    }

    fun updateDate(text: String) = updateOutputSetting { current ->
        current.copy(watermark = current.watermark.copy(dateText = text))
    }

    fun updatePlacement(placement: ShareSafeWatermarkPlacement) = updateOutputSetting { current ->
        current.copy(watermark = current.watermark.copy(placement = placement))
    }

    fun updateOpacity(opacity: ShareSafeWatermarkOpacity) = updateOutputSetting { current ->
        current.copy(watermark = current.watermark.copy(opacity = opacity))
    }

    fun setTargetSizeEnabled(enabled: Boolean) = updateOutputSetting { current ->
        current.copy(targetSize = current.targetSize.copy(enabled = enabled))
    }

    fun updateTargetBytes(bytes: Long) = updateOutputSetting { current ->
        current.copy(targetSize = ShareSafeTargetSizeConfig(enabled = true, maximumBytes = bytes))
    }

    fun nextStep() = updateConfigure { current ->
        val steps = stepsFor(current.sourceKind)
        val index = steps.indexOf(current.step)
        current.copy(step = steps[(index + 1).coerceAtMost(steps.lastIndex)])
    }

    fun previousStep(): Boolean {
        val current = state as? ShareSafeCopyWorkflowState.Configure ?: return false
        val steps = stepsFor(current.sourceKind)
        val index = steps.indexOf(current.step)
        if (index <= 0) return false
        state = current.copy(step = steps[index - 1])
        return true
    }

    fun prepare() {
        val current = state as? ShareSafeCopyWorkflowState.Configure ?: return
        if (!current.watermark.isValid) return
        lastConfiguration = current
        val operation = generation
        preparationJob?.cancel()
        saveJob?.cancel()
        state = ShareSafeCopyWorkflowState.Preparing(
            title = current.title,
            recipe = current.recipe,
            stage = ShareSafePipelineStage.SANITIZING,
        )
        preparationJob = viewModelScope.launch {
            try {
                val result = when (current.sourceKind) {
                    ShareSafeSourceKind.DOCUMENT -> {
                        val input = documentInput
                            ?: return@launch fail(ShareSafeFailure.INVALID_SOURCE, false)
                        engine.prepareDocument(
                            input = input,
                            recipe = current.recipe,
                            watermark = current.watermark,
                            targetSize = current.targetSize,
                            expectedGeneration = operation,
                            onProgress = { updateProgress(operation, it) },
                        )
                    }
                    ShareSafeSourceKind.CARD_COPY -> {
                        val artifact = cardArtifact
                            ?: return@launch fail(ShareSafeFailure.INVALID_SOURCE, false)
                        engine.prepareAttestedCardCopy(
                            initial = artifact,
                            recipe = current.recipe,
                            watermark = current.watermark,
                            targetSize = current.targetSize,
                            expectedGeneration = operation,
                            onProgress = { updateProgress(operation, it) },
                        )
                    }
                }
                if (generation != operation) {
                    if (result is ShareSafePrepareResult.Success) engine.discard(result.prepared)
                    return@launch
                }
                state = when (result) {
                    is ShareSafePrepareResult.Success -> {
                        cardArtifact = null
                        ShareSafeCopyWorkflowState.Prepared(
                            title = current.title,
                            sourceKind = current.sourceKind,
                            prepared = result.prepared,
                        )
                    }
                    is ShareSafePrepareResult.Failure -> {
                        cardArtifact = null
                        ShareSafeCopyWorkflowState.Failed(
                            reason = result.reason,
                            canRetry = current.sourceKind == ShareSafeSourceKind.DOCUMENT,
                        )
                    }
                }
            } catch (_: CancellationException) {
                // Replacement state owns the visible cancellation result.
            }
        }
    }

    fun markSavePickerOpen(): Boolean {
        val current = state as? ShareSafeCopyWorkflowState.Prepared ?: return false
        state = current.copy(saveStatus = ShareSafeSaveStatus.ChoosingDestination)
        return true
    }

    fun onSaveDestination(uri: Uri?) {
        val current = state as? ShareSafeCopyWorkflowState.Prepared ?: return
        if (uri == null) {
            state = current.copy(saveStatus = ShareSafeSaveStatus.Idle)
            return
        }
        val operation = generation
        val prepared = current.prepared
        state = current.copy(saveStatus = ShareSafeSaveStatus.Saving)
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            val result = destinationWriter.write(prepared, operation, uri)
            val latest = state as? ShareSafeCopyWorkflowState.Prepared ?: return@launch
            if (generation != operation || latest.prepared !== prepared) return@launch
            state = latest.copy(
                saveStatus = when (result) {
                    is ShareSafeDestinationResult.Success -> ShareSafeSaveStatus.Saved(result.bytes)
                    is ShareSafeDestinationResult.Failure -> ShareSafeSaveStatus.Failed(result.reason)
                },
            )
        }
    }

    internal suspend fun verifiedShareUri(): Uri? {
        val current = state as? ShareSafeCopyWorkflowState.Prepared ?: return null
        return shareSafeShareUri(getApplication(), current.prepared, generation)
    }

    internal fun generationForTest(): Long = generation

    fun markShareResult(success: Boolean) {
        val current = state as? ShareSafeCopyWorkflowState.Prepared ?: return
        state = current.copy(shareFailed = !success)
    }

    fun retry() {
        val failed = state as? ShareSafeCopyWorkflowState.Failed ?: return
        val configuration = lastConfiguration
        if (!failed.canRetry || configuration == null || documentInput == null) return
        state = configuration
    }

    fun back() {
        when (val current = state) {
            ShareSafeCopyWorkflowState.Hidden -> Unit
            is ShareSafeCopyWorkflowState.ChooseRecipe -> close()
            ShareSafeCopyWorkflowState.CardCopyHandoff -> {
                state = ShareSafeCopyWorkflowState.ChooseRecipe(
                    documentAvailable = documentInput != null,
                    documentTitle = documentInput?.title,
                    pageCount = documentInput?.pages?.size ?: 0,
                )
            }
            is ShareSafeCopyWorkflowState.Configure -> if (!previousStep()) {
                state = ShareSafeCopyWorkflowState.ChooseRecipe(
                    documentAvailable = documentInput != null,
                    documentTitle = documentInput?.title,
                    pageCount = documentInput?.pages?.size ?: 0,
                )
            }
            is ShareSafeCopyWorkflowState.Preparing -> {
                generation++
                preparationJob?.cancel()
                documentInput = documentInput?.copy(sourceGeneration = generation)
                cardArtifact = cardArtifact?.copy(sourceGeneration = generation)
                state = lastConfiguration ?: ShareSafeCopyWorkflowState.Failed(
                    ShareSafeFailure.CANCELED,
                    canRetry = false,
                )
            }
            is ShareSafeCopyWorkflowState.Prepared -> {
                engine.discard(current.prepared)
                if (current.sourceKind == ShareSafeSourceKind.DOCUMENT && lastConfiguration != null) {
                    invalidateGeneration()
                    state = requireNotNull(lastConfiguration)
                } else {
                    close()
                }
            }
            is ShareSafeCopyWorkflowState.Failed -> if (current.canRetry) retry() else close()
        }
    }

    fun close() {
        generation++
        preparationJob?.cancel()
        saveJob?.cancel()
        clearOwnedResult()
        documentInput = null
        cardArtifact?.ownedDirectory?.deleteRecursively()
        cardArtifact = null
        lastConfiguration = null
        savedStateHandle[ACTIVE_KEY] = false
        state = ShareSafeCopyWorkflowState.Hidden
    }

    override fun onCleared() {
        generation++
        clearOwnedResult()
        cardArtifact?.ownedDirectory?.deleteRecursively()
        super.onCleared()
    }

    private fun configureState(
        title: String,
        sourceKind: ShareSafeSourceKind,
        recipe: ShareSafeRecipe,
        watermark: ShareSafeWatermarkConfig,
        targetSize: ShareSafeTargetSizeConfig,
    ): ShareSafeCopyWorkflowState.Configure {
        val pages = documentInput?.pages.orEmpty()
        return ShareSafeCopyWorkflowState.Configure(
            title = title,
            pageCount = if (sourceKind == ShareSafeSourceKind.CARD_COPY) 1 else pages.size,
            sourceKind = sourceKind,
            recipe = recipe,
            watermark = watermark,
            targetSize = targetSize,
            step = if (sourceKind == ShareSafeSourceKind.DOCUMENT) {
                ShareSafeConfigureStep.REDACT
            } else {
                ShareSafeConfigureStep.PURPOSE
            },
            selectedPagePreviewUri = pages.firstOrNull()?.previewUri,
            selectedPageRotation = pages.firstOrNull()?.source?.rotation
                ?: DocumentPageRotation.DEGREES_0,
            redactions = pages.firstOrNull()?.redactions.orEmpty(),
        )
    }

    private fun replacePageRedactions(
        pageIndex: Int,
        regions: List<RedactionRegion>,
        selected: RedactionRegionId?,
    ) {
        val input = documentInput ?: return
        if (pageIndex !in input.pages.indices) return
        invalidateGeneration()
        documentInput = input.copy(
            sourceGeneration = generation,
            pages = input.pages.mapIndexed { index, page ->
                if (index == pageIndex) page.copy(redactions = regions) else page
            },
        )
        updateConfigure { current ->
            current.copy(redactions = regions, selectedRedactionId = selected)
        }
    }

    private fun updateOutputSetting(
        transform: (ShareSafeCopyWorkflowState.Configure) -> ShareSafeCopyWorkflowState.Configure,
    ) {
        val current = state as? ShareSafeCopyWorkflowState.Configure ?: return
        invalidateGeneration()
        state = transform(current).also { lastConfiguration = it }
    }

    private fun updateConfigure(
        transform: (ShareSafeCopyWorkflowState.Configure) -> ShareSafeCopyWorkflowState.Configure,
    ) {
        val current = state as? ShareSafeCopyWorkflowState.Configure ?: return
        state = transform(current).also { lastConfiguration = it }
    }

    private fun invalidateGeneration() {
        generation++
        documentInput = documentInput?.copy(sourceGeneration = generation)
        cardArtifact = cardArtifact?.copy(sourceGeneration = generation)
    }

    private fun updateProgress(operation: Long, stage: ShareSafePipelineStage) {
        val current = state as? ShareSafeCopyWorkflowState.Preparing ?: return
        if (generation == operation) state = current.copy(stage = stage)
    }

    private fun fail(reason: ShareSafeFailure, canRetry: Boolean) {
        state = ShareSafeCopyWorkflowState.Failed(reason, canRetry)
    }

    private fun clearOwnedResult() {
        (state as? ShareSafeCopyWorkflowState.Prepared)?.prepared?.let(engine::discard)
    }

    private fun stepsFor(sourceKind: ShareSafeSourceKind): List<ShareSafeConfigureStep> =
        if (sourceKind == ShareSafeSourceKind.DOCUMENT) {
            ShareSafeConfigureStep.entries
        } else {
            listOf(
                ShareSafeConfigureStep.PURPOSE,
                ShareSafeConfigureStep.SIZE,
                ShareSafeConfigureStep.REVIEW,
            )
        }

    private companion object {
        const val ACTIVE_KEY = "share_safe_workflow_active"
    }
}
