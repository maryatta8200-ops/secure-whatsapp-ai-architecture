package com.securewa.core.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryPolicyTest {

    private val deterministic = RetryPolicy(
        maxAttempts = 5,
        initialDelayMillis = 1_000L,
        maxDelayMillis = 10_000L,
        multiplier = 2.0,
        jitterRatio = 0.0
    )

    @Test
    fun `delays grow exponentially`() {
        assertEquals(1_000L, deterministic.delayBeforeAttempt(1))
        assertEquals(2_000L, deterministic.delayBeforeAttempt(2))
        assertEquals(4_000L, deterministic.delayBeforeAttempt(3))
        assertEquals(8_000L, deterministic.delayBeforeAttempt(4))
    }

    @Test
    fun `delays are capped`() {
        assertEquals(10_000L, deterministic.delayBeforeAttempt(10))
    }

    @Test
    fun `retry stops at maxAttempts`() {
        assertTrue(deterministic.shouldRetry(1))
        assertTrue(deterministic.shouldRetry(4))
        assertFalse(deterministic.shouldRetry(5))
    }

    @Test
    fun `jitter is bounded and reproducible`() {
        val policy = deterministic.copy(jitterRatio = 0.2)
        val low = policy.delayBeforeAttempt(3, jitterSeed = 0.0)
        val mid = policy.delayBeforeAttempt(3, jitterSeed = 0.5)
        val high = policy.delayBeforeAttempt(3, jitterSeed = 1.0)
        assertEquals(4_000L, mid)
        assertTrue("low jitter must shorten the delay: $low", low < mid)
        assertTrue("high jitter must lengthen the delay: $high", high > mid)
        assertEquals(3_200L, low)
        assertEquals(4_800L, high)
        assertEquals("same seed produces the same delay", low, policy.delayBeforeAttempt(3, jitterSeed = 0.0))
    }

    @Test
    fun `delays are never negative`() {
        val aggressive = RetryPolicy(initialDelayMillis = 1_000L, jitterRatio = 1.0)
        for (attempt in 1..8) {
            assertTrue(aggressive.delayBeforeAttempt(attempt, jitterSeed = 0.0) >= 0L)
        }
    }

    @Test
    fun `invalid configuration is rejected at construction`() {
        assertThrows<IllegalArgumentException> { RetryPolicy(maxAttempts = 0) }
        assertThrows<IllegalArgumentException> { RetryPolicy(initialDelayMillis = -1L) }
        assertThrows<IllegalArgumentException> { RetryPolicy(initialDelayMillis = 5_000L, maxDelayMillis = 1_000L) }
        assertThrows<IllegalArgumentException> { RetryPolicy(jitterRatio = 1.5) }
    }

    @Test
    fun `the preset policies are bounded`() {
        listOf(RetryPolicy.PROVIDER_CALL, RetryPolicy.OUTBOUND_SEND, RetryPolicy.RECEIVER_PULL).forEach { policy ->
            assertTrue(policy.maxAttempts in 1..10)
            assertTrue(policy.maxDelayMillis <= 900_000L)
            assertTrue(policy.delayBeforeAttempt(policy.maxAttempts) <= policy.maxDelayMillis)
        }
    }

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit) {
        var thrown = false
        try {
            block()
        } catch (error: Throwable) {
            thrown = error is T
        }
        assertTrue("expected ${T::class.java.simpleName} to be thrown", thrown)
    }
}
