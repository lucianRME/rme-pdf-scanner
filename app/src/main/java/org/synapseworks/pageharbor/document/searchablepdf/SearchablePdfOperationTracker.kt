package org.synapseworks.pageharbor.document.searchablepdf

/**
 * In-memory ownership token for one Activity-owned searchable-PDF operation.
 *
 * A token has one terminal preparation completion. This makes duplicate callbacks and callbacks
 * from a superseded Activity operation harmless without retaining document data in this model.
 */
class SearchablePdfOperationTracker {
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

    /** Starts preparation that is valid only for [documentRevision]. */
    @Synchronized
    fun begin(documentRevision: Long): Long {
        currentToken++
        completedToken = null
        capturedDocumentRevision = documentRevision
        return currentToken
    }

    @Synchronized
    fun invalidate() {
        ++currentToken
        completedToken = null
        capturedDocumentRevision = null
    }

    @Synchronized
    fun isCurrent(token: Long): Boolean = token == currentToken

    @Synchronized
    fun isCurrent(token: Long, documentRevision: Long): Boolean =
        token == currentToken && capturedDocumentRevision == documentRevision

    /** True only while [token] owns an operation that has not completed preparation. */
    @Synchronized
    fun acceptsProgress(token: Long): Boolean = isCurrent(token) && completedToken != token

    /** Revision-bound form used by document mutation-aware callers. */
    @Synchronized
    fun acceptsProgress(token: Long, documentRevision: Long): Boolean =
        isCurrent(token, documentRevision) && completedToken != token

    /** Claims the one preparation completion that may publish UI state for [token]. */
    @Synchronized
    fun claimCompletion(token: Long): CompletionClaim = when {
        token != currentToken -> CompletionClaim.SUPERSEDED
        completedToken == token -> CompletionClaim.DUPLICATE
        else -> {
            completedToken = token
            CompletionClaim.CLAIMED
        }
    }

    /** Claims completion only if both the generation and captured document revision still match. */
    @Synchronized
    fun claimCompletion(token: Long, documentRevision: Long): CompletionClaim = when {
        !isCurrent(token, documentRevision) -> CompletionClaim.SUPERSEDED
        completedToken == token -> CompletionClaim.DUPLICATE
        else -> {
            completedToken = token
            CompletionClaim.CLAIMED
        }
    }

    /** Invalidates bound work after any mutation that advances the active document revision. */
    @Synchronized
    fun invalidateIfDocumentRevisionChanged(documentRevision: Long): Boolean {
        val capturedRevision = capturedDocumentRevision ?: return false
        if (capturedRevision == documentRevision) return false
        ++currentToken
        completedToken = null
        capturedDocumentRevision = null
        return true
    }

    enum class CompletionClaim {
        /** This is the one current completion permitted to publish prepared output. */
        CLAIMED,
        /** A repeated callback owns nothing and must leave the active prepared output untouched. */
        DUPLICATE,
        /** An old callback must discard only the output it produced. */
        SUPERSEDED,
    }
}
