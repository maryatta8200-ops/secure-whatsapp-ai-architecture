package com.securewa.core.policy

import kotlin.math.min
import kotlin.math.pow

/**
 * Exponential backoff with deterministic jitter.
 *
 * The delay is a pure function of the attempt number and a caller-supplied
 * value in [0, 1), so retry behaviour is reproducible in tests and in audit
 * logs: given the same attempt and the same jitter seed, the same delay is
 * produced. Wall-clock monotonicity comes from WorkManager, which owns the
 * actual scheduling.
 */
data class RetryPolicy(
    val maxAttempts: Int = 5,
    val initialDelayMillis: Long = 10_000L,
    val maxDelayMillis: Long = 900_000L,
    val multiplier: Double = 2.0,
    val jitterRatio: Double = 0.2
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1" }
        require(initialDelayMillis >= 0) { "initialDelayMillis must not be negative" }
        require(maxDelayMillis >= initialDelayMillis) { "maxDelayMillis must be >= initialDelayMillis" }
        require(jitterRatio in 0.0..1.0) { "jitterRatio must be within 0.0..1.0" }
    }

    /** Whether another attempt is permitted after [attempt] (1-based). */
    fun shouldRetry(attempt: Int): Boolean = attempt < maxAttempts

    /**
     * Delay before the attempt that follows [attempt] (1-based).
     *
     * @param jitterSeed value in [0, 1); `0.5` yields the un-jittered delay for
     *                   deterministic tests.
     */
    fun delayBeforeAttempt(attempt: Int, jitterSeed: Double = 0.5): Long {
        require(attempt >= 1) { "attempt is 1-based" }
        require(jitterSeed in 0.0..1.0) { "jitterSeed must be within 0.0..1.0" }
        val uncapped = initialDelayMillis * multiplier.pow((attempt - 1).toDouble())
        val capped = min(uncapped, maxDelayMillis.toDouble())
        val jitter = capped * jitterRatio * ((jitterSeed * 2.0) - 1.0)
        return (capped + jitter).toLong().coerceAtLeast(0L)
    }

    companion object {
        /** Backoff used for AI provider calls: bounded, short, few attempts. */
        val PROVIDER_CALL = RetryPolicy(
            maxAttempts = 4,
            initialDelayMillis = 5_000L,
            maxDelayMillis = 120_000L,
            multiplier = 2.0,
            jitterRatio = 0.2
        )

        /** Backoff used for outbound Twilio sends. */
        val OUTBOUND_SEND = RetryPolicy(
            maxAttempts = 6,
            initialDelayMillis = 15_000L,
            maxDelayMillis = 900_000L,
            multiplier = 2.0,
            jitterRatio = 0.2
        )

        /** Backoff used for pulling the inbound receiver queue. */
        val RECEIVER_PULL = RetryPolicy(
            maxAttempts = 8,
            initialDelayMillis = 30_000L,
            maxDelayMillis = 900_000L,
            multiplier = 1.8,
            jitterRatio = 0.25
        )
    }
}
