package org.synapseworks.pageharbor.security

data class PinRetryThrottleState(
    val failedAttemptCount: Int = 0,
    val retryNotBeforeWallTimeMillis: Long = 0L,
    val lastObservedWallTimeMillis: Long = 0L,
)

data class PinRetryGate(
    val state: PinRetryThrottleState,
    val retryAfterMillis: Long,
) {
    val canAttempt: Boolean
        get() = retryAfterMillis == 0L
}

class PinRetryThrottlePolicy(
    private val failuresBeforeDelay: Int = DEFAULT_FAILURES_BEFORE_DELAY,
    private val initialDelayMillis: Long = DEFAULT_INITIAL_DELAY_MILLIS,
    private val maximumDelayMillis: Long = DEFAULT_MAXIMUM_DELAY_MILLIS,
) {
    init {
        require(failuresBeforeDelay > 0)
        require(initialDelayMillis > 0L)
        require(maximumDelayMillis >= initialDelayMillis)
    }

    /**
     * Evaluates the persisted wall-clock deadline. If the wall clock moved backwards, a fresh
     * capped delay is imposed once throttling has started. Callers must persist [PinRetryGate.state]
     * even when no PIN attempt is made.
     */
    fun evaluate(state: PinRetryThrottleState, nowWallTimeMillis: Long): PinRetryGate {
        val now = nowWallTimeMillis.coerceAtLeast(0L)
        val normalized = state.normalized()
        val clockRolledBack = normalized.lastObservedWallTimeMillis > 0L &&
            now < normalized.lastObservedWallTimeMillis

        if (clockRolledBack && normalized.failedAttemptCount >= failuresBeforeDelay) {
            val delay = maximumDelayMillis
            return PinRetryGate(
                state = normalized.copy(
                    retryNotBeforeWallTimeMillis = saturatingAdd(now, delay),
                    lastObservedWallTimeMillis = now,
                ),
                retryAfterMillis = delay,
            )
        }

        val remaining = (normalized.retryNotBeforeWallTimeMillis - now)
            .coerceIn(0L, maximumDelayMillis)
        return PinRetryGate(
            state = normalized.copy(lastObservedWallTimeMillis = now),
            retryAfterMillis = remaining,
        )
    }

    /** Records a rejected PIN after [evaluate] has allowed an attempt. */
    fun recordFailure(
        state: PinRetryThrottleState,
        nowWallTimeMillis: Long,
    ): PinRetryGate {
        val now = nowWallTimeMillis.coerceAtLeast(0L)
        val nextFailures = if (state.failedAttemptCount == Int.MAX_VALUE) {
            Int.MAX_VALUE
        } else {
            state.failedAttemptCount.coerceAtLeast(0) + 1
        }
        val delay = delayForFailureCount(nextFailures)
        val deadline = if (delay == 0L) 0L else saturatingAdd(now, delay)
        return PinRetryGate(
            state = PinRetryThrottleState(
                failedAttemptCount = nextFailures,
                retryNotBeforeWallTimeMillis = deadline,
                lastObservedWallTimeMillis = now,
            ),
            retryAfterMillis = delay,
        )
    }

    fun recordSuccess(): PinRetryThrottleState = PinRetryThrottleState()

    fun delayForFailureCount(failedAttemptCount: Int): Long {
        if (failedAttemptCount < failuresBeforeDelay) return 0L
        val exponent = (failedAttemptCount - failuresBeforeDelay).coerceAtMost(62)
        var delay = initialDelayMillis
        repeat(exponent) {
            if (delay >= maximumDelayMillis) return maximumDelayMillis
            delay = (delay * 2L).coerceAtMost(maximumDelayMillis)
        }
        return delay.coerceAtMost(maximumDelayMillis)
    }

    private fun PinRetryThrottleState.normalized(): PinRetryThrottleState = copy(
        failedAttemptCount = failedAttemptCount.coerceAtLeast(0),
        retryNotBeforeWallTimeMillis = retryNotBeforeWallTimeMillis.coerceAtLeast(0L),
        lastObservedWallTimeMillis = lastObservedWallTimeMillis.coerceAtLeast(0L),
    )

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    companion object {
        const val DEFAULT_FAILURES_BEFORE_DELAY = 5
        const val DEFAULT_INITIAL_DELAY_MILLIS = 30_000L
        const val DEFAULT_MAXIMUM_DELAY_MILLIS = 5L * 60_000L
    }
}

data class AppLockPersistentState(
    val config: AppLockConfig = AppLockConfig(),
    val credential: PinCredentialRecord? = null,
    val throttle: PinRetryThrottleState = PinRetryThrottleState(),
)

sealed interface PinAttemptResult {
    val state: AppLockPersistentState

    data class Accepted(
        override val state: AppLockPersistentState,
    ) : PinAttemptResult

    data class Rejected(
        override val state: AppLockPersistentState,
        val retryAfterMillis: Long,
    ) : PinAttemptResult

    data class Throttled(
        override val state: AppLockPersistentState,
        val retryAfterMillis: Long,
    ) : PinAttemptResult

    data class Unavailable(
        override val state: AppLockPersistentState,
    ) : PinAttemptResult
}

/** Pure coordinator: persistence and clocks remain explicit at the Android boundary. */
fun interface AppLockPinAttemptAuthenticator {
    fun attempt(
        pin: CharArray,
        state: AppLockPersistentState,
        nowWallTimeMillis: Long,
    ): PinAttemptResult
}

class AppLockPinAuthenticator(
    private val verifier: PinCredentialService,
    private val throttlePolicy: PinRetryThrottlePolicy = PinRetryThrottlePolicy(),
) : AppLockPinAttemptAuthenticator {
    override fun attempt(
        pin: CharArray,
        state: AppLockPersistentState,
        nowWallTimeMillis: Long,
    ): PinAttemptResult {
        val gate = throttlePolicy.evaluate(state.throttle, nowWallTimeMillis)
        val evaluatedState = state.copy(throttle = gate.state)
        if (!gate.canAttempt) {
            return PinAttemptResult.Throttled(evaluatedState, gate.retryAfterMillis)
        }

        val credential = state.credential ?: return PinAttemptResult.Unavailable(evaluatedState)
        val verified = try {
            verifier.verify(pin, credential)
        } catch (_: AppLockPepperUnavailableException) {
            return PinAttemptResult.Unavailable(evaluatedState)
        }
        return if (verified) {
            PinAttemptResult.Accepted(
                evaluatedState.copy(throttle = throttlePolicy.recordSuccess()),
            )
        } else {
            val failed = throttlePolicy.recordFailure(gate.state, nowWallTimeMillis)
            PinAttemptResult.Rejected(
                state = evaluatedState.copy(throttle = failed.state),
                retryAfterMillis = failed.retryAfterMillis,
            )
        }
    }
}
