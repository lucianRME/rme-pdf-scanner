package org.synapseworks.pageharbor.ui

class DoubleBackExitController(
    private val nowMillis: () -> Long,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {
    private var armedAtMillis: Long? = null

    fun onBack(): Result {
        val now = nowMillis()
        val armedAt = armedAtMillis
        return if (armedAt != null && now >= armedAt && now - armedAt <= timeoutMillis) {
            armedAtMillis = null
            Result.Exit
        } else {
            armedAtMillis = now
            Result.ShowHint
        }
    }

    fun reset() {
        armedAtMillis = null
    }

    enum class Result {
        ShowHint,
        Exit,
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 2_000L
    }
}
