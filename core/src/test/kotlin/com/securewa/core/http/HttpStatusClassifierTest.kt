package com.securewa.core.http

import com.securewa.core.provider.ProviderFailureKind
import com.securewa.core.provider.ProviderFailureClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One retry decision for every API this app calls.
 */
class HttpStatusClassifierTest {

    @Test
    fun `a refused credential is never retried`() {
        assertEquals(HttpStatusOutcome.AUTHENTICATION, HttpStatusClassifier.verdictFor(401).outcome)
        assertEquals(HttpStatusOutcome.AUTHENTICATION, HttpStatusClassifier.verdictFor(403).outcome)
        assertFalse(HttpStatusClassifier.verdictFor(401).retryable)
    }

    @Test
    fun `being rate limited is worth another attempt later`() {
        val verdict = HttpStatusClassifier.verdictFor(429)

        assertEquals(HttpStatusOutcome.RATE_LIMITED, verdict.outcome)
        assertTrue(verdict.retryable)
    }

    @Test
    fun `a timeout is worth another attempt`() {
        assertTrue(HttpStatusClassifier.verdictFor(408).retryable)
        assertEquals(HttpStatusOutcome.TIMEOUT, HttpStatusClassifier.verdictFor(409).outcome)
    }

    @Test
    fun `a request the other side refused on its merits is not retried`() {
        assertFalse(HttpStatusClassifier.verdictFor(400).retryable)
        assertEquals(HttpStatusOutcome.INVALID_REQUEST, HttpStatusClassifier.verdictFor(400).outcome)
    }

    @Test
    fun `a server error is worth another attempt`() {
        val verdict = HttpStatusClassifier.verdictFor(503)

        assertEquals(HttpStatusOutcome.SERVER_ERROR, verdict.outcome)
        assertTrue(verdict.retryable)
    }

    @Test
    fun `a status this app does not know is not retried`() {
        val verdict = HttpStatusClassifier.verdictFor(799)

        assertEquals(HttpStatusOutcome.UNEXPECTED_STATUS, verdict.outcome)
        assertFalse(verdict.retryable)
    }

    @Test
    fun `the provider classifier names the same decision in its own terms`() {
        assertEquals(ProviderFailureKind.AUTHENTICATION, ProviderFailureClassifier.classify(401).kind)
        assertEquals(ProviderFailureKind.RATE_LIMITED, ProviderFailureClassifier.classify(429).kind)
        assertEquals(ProviderFailureKind.TIMEOUT, ProviderFailureClassifier.classify(408).kind)
        assertEquals(ProviderFailureKind.INVALID_REQUEST, ProviderFailureClassifier.classify(418).kind)
        assertEquals(ProviderFailureKind.SERVER_ERROR, ProviderFailureClassifier.classify(500).kind)
        assertEquals(ProviderFailureKind.SERVER_ERROR, ProviderFailureClassifier.classify(799).kind)

        assertTrue(ProviderFailureClassifier.classify(429).retryable)
        assertFalse(ProviderFailureClassifier.classify(401).retryable)
        assertFalse(ProviderFailureClassifier.classify(799).retryable)
    }
}
