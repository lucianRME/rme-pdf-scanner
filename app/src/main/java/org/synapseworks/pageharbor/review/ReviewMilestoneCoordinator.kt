package org.synapseworks.pageharbor.review

class ReviewMilestoneCoordinator(
    private val stateStore: ReviewStateStore,
    private val policy: ReviewPolicy = ReviewPolicy(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private var sessionRecorded = false

    fun recordAppSession() {
        if (sessionRecorded) return
        sessionRecorded = true
        val now = nowMillis()
        stateStore.update { state ->
            state.copy(
                firstUseTimestampMillis = state.firstUseTimestampMillis.takeIf { it > 0L } ?: now,
                sessionCount = state.sessionCount.saturatedIncrement(),
            )
        }
    }

    fun recordDocumentSave(successful: Boolean, currentVersion: String): Boolean {
        if (!successful) return false
        val updated = stateStore.update { state ->
            state.copy(
                successfulDocumentSaveCount = state.successfulDocumentSaveCount.saturatedIncrement(),
            )
        }
        return policy.isEligible(updated, currentVersion, nowMillis())
    }

    fun markReviewAttempt(currentVersion: String) {
        stateStore.update { state ->
            state.copy(lastReviewAttemptVersion = currentVersion)
        }
    }

    private fun Int.saturatedIncrement(): Int = if (this == Int.MAX_VALUE) this else this + 1
}

class ReviewAttemptRunner(
    private val markAttempt: (String) -> Unit,
    private val launch: () -> Unit,
) {
    fun run(currentVersion: String) {
        if (runCatching { markAttempt(currentVersion) }.isFailure) return
        runCatching(launch)
    }
}
