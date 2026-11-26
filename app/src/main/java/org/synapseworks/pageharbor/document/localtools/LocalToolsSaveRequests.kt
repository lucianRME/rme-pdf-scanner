package org.synapseworks.pageharbor.document.localtools

import java.io.File
import java.util.UUID

/** A picker result is bound to one output object and workflow lifetime, never to "latest output". */
internal data class LocalToolsSaveRequest(
    val id: String,
    val output: File,
    val generation: Long,
)

internal sealed interface LocalToolsSaveResolution {
    /** No matching pending request. In particular, leave any newer request untouched. */
    data object Ignored : LocalToolsSaveResolution
    data class Ready(val request: LocalToolsSaveRequest) : LocalToolsSaveResolution
    data class Cancelled(val request: LocalToolsSaveRequest) : LocalToolsSaveResolution
    data class Invalidated(val request: LocalToolsSaveRequest) : LocalToolsSaveResolution
}

/** Pure, one-shot request tracking; invalidate does not recycle request identities. */
internal class LocalToolsSaveRequests {
    private val identity = UUID.randomUUID().toString()
    private var nextRequest = 0L
    private var pending: LocalToolsSaveRequest? = null

    @Synchronized
    fun create(output: File, generation: Long): LocalToolsSaveRequest? {
        if (pending != null || nextRequest == Long.MAX_VALUE) return null
        return LocalToolsSaveRequest("$identity-${++nextRequest}", output, generation).also { pending = it }
    }

    @Synchronized
    fun consume(
        requestId: String,
        generation: Long,
        currentOutput: File?,
        cancelled: Boolean,
    ): LocalToolsSaveResolution {
        val request = pending ?: return LocalToolsSaveResolution.Ignored
        if (request.id != requestId) return LocalToolsSaveResolution.Ignored
        // Matching results are consumed even if validation or chooser cancellation rejects saving.
        pending = null
        return when {
            request.generation != generation || request.output !== currentOutput ->
                LocalToolsSaveResolution.Invalidated(request)
            cancelled -> LocalToolsSaveResolution.Cancelled(request)
            else -> LocalToolsSaveResolution.Ready(request)
        }
    }

    @Synchronized
    fun invalidate() {
        pending = null
    }
}
