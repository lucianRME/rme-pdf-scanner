package org.synapseworks.pageharbor.ocr

/**
 * In-memory ownership token for one Activity-owned OCR operation.
 *
 * OCR engines can be backed by services that do not stop immediately when their caller is
 * cancelled. A token makes a late completion harmless after Discard, a replacement scan, or an
 * Activity recreation without retaining any document data or progress across process death.
 */
class OcrOperationTracker {
    private var currentToken = 0L
    private var completedToken: Long? = null
    private var capturedDocumentRevision: Long? = null

    @Synchronized
    fun begin(): Long {
        currentToken++
        completedToken = null
        capturedDocumentRevision = null
        return currentToken
    }

    /** Starts an operation that is valid only for [documentRevision]. */
    @Synchronized
    fun begin(documentRevision: Long): Long {
        currentToken++
        completedToken = null
        capturedDocumentRevision = documentRevision
        return currentToken
    }

    @Synchronized
    fun invalidate() {
        currentToken++
        completedToken = null
        capturedDocumentRevision = null
    }

    /** Invalidates bound work after any mutation that advances the active document revision. */
    @Synchronized
    fun invalidateIfDocumentRevisionChanged(documentRevision: Long): Boolean {
        val capturedRevision = capturedDocumentRevision ?: return false
        if (capturedRevision == documentRevision) return false
        currentToken++
        completedToken = null
        capturedDocumentRevision = null
        return true
    }

    @Synchronized
    fun isCurrent(token: Long, documentRevision: Long): Boolean =
        token == currentToken && capturedDocumentRevision == documentRevision

    /** Claims the sole completion that is allowed to publish OCR UI state. */
    @Synchronized
    fun claimCompletion(token: Long): CompletionClaim = when {
        token != currentToken -> CompletionClaim.SUPERSEDED
        completedToken == token -> CompletionClaim.DUPLICATE
        else -> {
            completedToken = token
            CompletionClaim.CLAIMED
        }
    }

    /** Claims completion only if both the operation generation and document revision still match. */
    @Synchronized
    fun claimCompletion(token: Long, documentRevision: Long): CompletionClaim = when {
        !isCurrent(token, documentRevision) -> CompletionClaim.SUPERSEDED
        completedToken == token -> CompletionClaim.DUPLICATE
        else -> {
            completedToken = token
            CompletionClaim.CLAIMED
        }
    }

    enum class CompletionClaim {
        CLAIMED,
        DUPLICATE,
        SUPERSEDED,
    }
}
