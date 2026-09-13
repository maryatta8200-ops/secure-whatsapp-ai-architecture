package com.securewa.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayGuardTest {

    @Test
    fun `a fresh nonce is accepted exactly once`() {
        val guard = ReplayGuard()
        assertTrue(guard.checkAndRecord("sig-1", 1_000L))
        assertFalse(guard.checkAndRecord("sig-1", 1_001L))
    }

    @Test
    fun `distinct nonces do not collide`() {
        val guard = ReplayGuard()
        assertTrue(guard.checkAndRecord("sig-a", 1_000L))
        assertTrue(guard.checkAndRecord("sig-b", 1_000L))
        assertEquals(2, guard.size())
    }

    @Test
    fun `entries expire after the ttl`() {
        val guard = ReplayGuard(ttlMillis = 5_000L)
        assertTrue(guard.checkAndRecord("sig-1", 0L))
        assertFalse("still inside the TTL", guard.checkAndRecord("sig-1", 4_999L))
        assertTrue("outside the TTL", guard.checkAndRecord("sig-1", 5_000L))
    }

    @Test
    fun `the number of tracked entries stays bounded`() {
        val guard = ReplayGuard(maxEntries = 2, ttlMillis = 60_000L)
        assertTrue(guard.checkAndRecord("sig-1", 0L))
        assertTrue(guard.checkAndRecord("sig-2", 0L))
        assertTrue(guard.checkAndRecord("sig-3", 0L))
        assertEquals("oldest entry must be evicted", 2, guard.size())
        assertTrue("evicted entries are forgotten", guard.checkAndRecord("sig-1", 1L))
    }

    @Test
    fun `purging removes expired entries`() {
        val guard = ReplayGuard(ttlMillis = 1_000L)
        guard.checkAndRecord("sig-1", 0L)
        guard.checkAndRecord("sig-2", 0L)
        assertEquals(2, guard.size())
        guard.purge(1_500L)
        assertEquals(0, guard.size())
    }
}
