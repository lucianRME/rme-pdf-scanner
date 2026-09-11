package org.synapseworks.pageharbor.document

sealed interface PdfShareState {
    data object Idle : PdfShareState
    data object Preparing : PdfShareState
    data class Error(val result: PdfShareError) : PdfShareState
}

enum class PdfShareError {
    NoPdfAvailable,
    SourceTooLarge,
    ShareTargetUnavailable,
    InvalidUri,
    UnexpectedFailure,
}
