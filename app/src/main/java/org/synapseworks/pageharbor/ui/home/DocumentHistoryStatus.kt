package org.synapseworks.pageharbor.ui.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import org.synapseworks.pageharbor.R
import org.synapseworks.pageharbor.document.history.*

@Composable internal fun historyActionLabel(action:HistoryAction?):String=stringResource(when(action){
    HistoryAction.ROTATE->R.string.history_rotate;HistoryAction.FILTER->R.string.history_filter;HistoryAction.ADJUST->R.string.history_adjust
    HistoryAction.BATCH->R.string.history_batch;HistoryAction.REORDER->R.string.history_reorder;HistoryAction.DELETE->R.string.history_delete
    HistoryAction.APPEND->R.string.history_append;HistoryAction.BOOK_SPLIT->R.string.history_split;HistoryAction.CURVATURE->R.string.history_curvature
    HistoryAction.OBJECT_REMOVAL->R.string.removal_title
    HistoryAction.FINGER_REMOVAL->R.string.finger_removal_title
    HistoryAction.CROP->R.string.import_crop_title
    HistoryAction.COLLAGE->R.string.history_collage
    HistoryAction.ANNOTATE->R.string.annotation_title
    null->R.string.history_edit
})
@Composable internal fun DocumentHistoryStatus(state:HistoryUiState, compact: Boolean = false){
    val status=when{state.busy->stringResource(R.string.history_preparing);state.unavailable->stringResource(R.string.history_unavailable)
        state.limited->stringResource(R.string.history_limit,state.undoCount,state.redoCount)
        else->stringResource(R.string.history_depth,state.undoCount,state.redoCount)}
    if (state.busy || state.unavailable || state.limited || (!compact && (state.undoCount != 0 || state.redoCount != 0))) {
        Text(status,Modifier.testTag("history-status").semantics{liveRegion=LiveRegionMode.Polite},style=MaterialTheme.typography.bodySmall)
    }
    if(state.dirty)Text(stringResource(if (compact) R.string.document_unsaved_short else R.string.history_dirty),Modifier.testTag("history-dirty"),style=MaterialTheme.typography.bodySmall)
}
