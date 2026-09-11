package org.synapseworks.pageharbor.document.searchablepdf

/** UI-safe state for one user-requested searchable-PDF save. */
sealed interface SearchablePdfSaveState {
    data object Idle : SearchablePdfSaveState
    data object Preparing : SearchablePdfSaveState
    data object Recognizing : SearchablePdfSaveState
    data object Generating : SearchablePdfSaveState
    data object ChoosingDestination : SearchablePdfSaveState
    data object Saving : SearchablePdfSaveState
    data object Saved : SearchablePdfSaveState
    data object Cancelled : SearchablePdfSaveState
    data class Error(val reason: SearchablePdfSaveError) : SearchablePdfSaveState
}

/** Safe error categories that deliberately exclude document and destination details. */
enum class SearchablePdfSaveError {
    NO_PAGES,
    SOURCE_TOO_LARGE,
    PREPARATION_FAILED,
    DESTINATION_UNAVAILABLE,
    WRITE_FAILED,
}

fun SearchablePdfSaveState.isInProgress(): Boolean = when (this) {
    SearchablePdfSaveState.Preparing,
    SearchablePdfSaveState.Recognizing,
    SearchablePdfSaveState.Generating,
    SearchablePdfSaveState.ChoosingDestination,
    SearchablePdfSaveState.Saving,
    -> true

    SearchablePdfSaveState.Idle,
    SearchablePdfSaveState.Saved,
    SearchablePdfSaveState.Cancelled,
    is SearchablePdfSaveState.Error,
    -> false
}

fun searchablePdfSaveStateForProgress(progress: SearchablePdfExportProgress): SearchablePdfSaveState =
    when (progress) {
        SearchablePdfExportProgress.Recognizing -> SearchablePdfSaveState.Recognizing
        SearchablePdfExportProgress.Generating -> SearchablePdfSaveState.Generating
        SearchablePdfExportProgress.Writing -> SearchablePdfSaveState.Saving
    }
