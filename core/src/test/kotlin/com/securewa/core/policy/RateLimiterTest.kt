package com.securewa.core.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RateLimiterTest {

    private val limiter = RateLimiter(capacity = 3, refillTokensPerMinute = 60.0)

    @Test
    fun `requests are allowed until the bucket is empty`() {
        assertTrue(limiter.tryConsume("number-a", 0L))
        assertTrue(limiter.tryConsume("number-a", 0L))
        assertTrue(limiter.tryConsume("number-a", 0L))
        assertFalse("the fourth call in the same minute must be refused", limiter.tryConsume("number-a", 0L))
    }

    @Test
    fun `the bucket refills over time`() {
        repeat(3) { limiter.tryConsume("number-b", 0L) }
        assertFalse(limiter.tryConsume("number-b", 0L))
        assertTrue("one token refills after one second", limiter.tryConsume("number-b", 1_000L))
    }

    @Test
    fun `buckets are isolated per key`() {
        repeat(3) { limiter.tryConsume("number-c", 0L) }
        assertFalse(limiter.tryConsume("number-c", 0L))
        assertTrue("a different number has its own bucket", limiter.tryConsume("number-d", 0L))
    }

    @Test
    fun `time does not move backwards`() {
        assertTrue(limiter.tryConsume("number-e", 10_000L))
        repeat(3) { limiter.tryConsume("number-e", 10_000L) }
        assertFalse(limiter.tryConsume("number-e", 5_000L))
    }

    @Test
    fun `idle buckets are dropped so memory stays bounded`() {
        val bounded = RateLimiter(capacity = 1, refillTokensPerMinute = 1.0)
        bounded.tryConsume("old", 0L)
        bounded.tryConsume("recent", 60_000L)
        assertEquals(2, bounded.trackedKeys())
        bounded.trimIdle(nowMillis = 120_000L, maxIdleMillis = 90_000L)
        assertEquals(1, bounded.trackedKeys())
    }

    @Test
    fun `the number of tracked keys is bounded`() {
        val bounded = RateLimiter(capacity = 1, refillTokensPerMinute = 1.0, maxTrackedKeys = 4)
        for (i in 0 until 50) bounded.tryConsume("key-$i", 0L)
        assertTrue("tracked keys must stay bounded: ${bounded.trackedKeys()}", bounded.trackedKeys() <= 4)
    }
}
