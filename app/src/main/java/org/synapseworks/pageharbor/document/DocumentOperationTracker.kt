package org.synapseworks.pageharbor.document

/** One Activity operation bound to the exact effective document revision it captured. */
internal data class DocumentOperationToken(
    val generation: Long,
    val documentRevision: Long,
)

/** Activity-local generation token that makes late document-operation callbacks harmless. */
internal class DocumentOperationTracker {
    private var nextToken = 0L
    private var activeToken: DocumentOperationToken? = null

    @Synchronized
    fun begin(documentRevision: Long): DocumentOperationToken =
        DocumentOperationToken(++nextToken, documentRevision).also { activeToken = it }

    @Synchronized
    fun isCurrent(token: DocumentOperationToken, documentRevision: Long): Boolean =
        activeToken == token && token.documentRevision == documentRevision

    /** Completes the current generation exactly once. */
    @Synchronized
    fun finish(token: DocumentOperationToken, documentRevision: Long): Boolean {
        if (!isCurrent(token, documentRevision)) return false
        activeToken = null
        return true
    }

    /** Invalidates only work that captured an older document, preserving newly started work. */
    @Synchronized
    fun invalidateIfDocumentRevisionChanged(documentRevision: Long): Boolean {
        val token = activeToken ?: return false
        if (token.documentRevision == documentRevision) return false
        nextToken++
        activeToken = null
        return true
    }

    @Synchronized
    fun invalidate() {
        nextToken++
        activeToken = null
    }
}
