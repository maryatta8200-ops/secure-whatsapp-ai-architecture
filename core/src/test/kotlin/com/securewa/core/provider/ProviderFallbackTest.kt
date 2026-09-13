package com.securewa.core.provider

import com.securewa.core.routing.FallbackReason
import com.securewa.core.routing.ProviderFallbackPolicy
import com.securewa.core.routing.ProviderKind
import com.securewa.core.routing.ProviderRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fallback is the one place where this app can send a conversation to a provider
 * the agent was not primarily configured with, so the rules are asserted rather
 * than assumed.
 */
class ProviderFallbackTest {

    private val primary = ProviderRef(ProviderKind.OPENAI, "gpt-4o-mini", "slot-openai", 3)
    private val secondary = ProviderRef(ProviderKind.GEMINI, "gemini-2.0-flash", "slot-gemini", 1)

    private fun policy(
        enabled: Boolean = true,
        secondary: ProviderRef? = this.secondary,
        allowed: Set<FallbackReason> = FallbackReason.entries.toSet()
    ) = ProviderFallbackPolicy(enabled = enabled, secondary = secondary, allowedReasons = allowed)

    private fun failure(kind: ProviderFailureKind, retryable: Boolean = true) =
        ProviderFailure(kind = kind, message = "test failure", retryable = retryable)

    // --- what a failure means -------------------------------------------------

    @Test
    fun `a timeout is a provider timeout`() {
        assertEquals(FallbackReason.PROVIDER_TIMEOUT, ProviderFallback.reasonFor(ProviderFailureKind.TIMEOUT))
    }

    @Test
    fun `being rate limited is a provider rate limit`() {
        assertEquals(FallbackReason.PROVIDER_RATE_LIMITED, ProviderFallback.reasonFor(ProviderFailureKind.RATE_LIMITED))
    }

    @Test
    fun `a server error is a provider server error`() {
        assertEquals(FallbackReason.PROVIDER_SERVER_ERROR, ProviderFallback.reasonFor(ProviderFailureKind.SERVER_ERROR))
    }

    @Test
    fun `a rejected credential is a provider authentication failure`() {
        assertEquals(FallbackReason.PROVIDER_AUTH_FAILED, ProviderFallback.reasonFor(ProviderFailureKind.AUTHENTICATION))
    }

    @Test
    fun `an answer that cannot be used is a validation failure`() {
        assertEquals(FallbackReason.RESPONSE_VALIDATION_FAILED, ProviderFallback.reasonFor(ProviderFailureKind.PARSE))
        assertEquals(FallbackReason.RESPONSE_VALIDATION_FAILED, ProviderFallback.reasonFor(ProviderFailureKind.EMPTY_RESPONSE))
        assertEquals(FallbackReason.RESPONSE_VALIDATION_FAILED, ProviderFallback.reasonFor(ProviderFailureKind.INVALID_REQUEST))
    }

    @Test
    fun `not reaching a provider is not a reason to hand the conversation to another one`() {
        assertNull(ProviderFallback.reasonFor(ProviderFailureKind.NETWORK))
    }

    @Test
    fun `a provider that could not be called at all is not a reason either`() {
        assertNull(ProviderFallback.reasonFor(ProviderFailureKind.UNAVAILABLE))
    }

    // --- whether the turn moves ------------------------------------------------

    @Test
    fun `a turn moves to the secondary when the agent allowed the reason that occurred`() {
        val policy = policy(allowed = setOf(FallbackReason.PROVIDER_TIMEOUT))

        assertEquals(secondary, ProviderFallback.nextAfter(policy, primary, failure(ProviderFailureKind.TIMEOUT)))
    }

    @Test
    fun `a turn does not move when the agent did not allow the reason that occurred`() {
        val policy = policy(allowed = setOf(FallbackReason.PROVIDER_TIMEOUT))

        assertNull(ProviderFallback.nextAfter(policy, primary, failure(ProviderFailureKind.RATE_LIMITED)))
    }

    @Test
    fun `a turn does not move when fallback is switched off`() {
        val policy = policy(enabled = false, allowed = setOf(FallbackReason.PROVIDER_TIMEOUT))

        assertNull(ProviderFallback.nextAfter(policy, primary, failure(ProviderFailureKind.TIMEOUT)))
    }

    @Test
    fun `a turn does not move when no secondary is configured`() {
        val policy = policy(secondary = null, allowed = setOf(FallbackReason.PROVIDER_TIMEOUT))

        assertNull(ProviderFallback.nextAfter(policy, primary, failure(ProviderFailureKind.TIMEOUT)))
    }

    @Test
    fun `an offline device never moves the conversation to another organisation`() {
        val policy = policy(allowed = FallbackReason.entries.toSet())

        assertNull(ProviderFallback.nextAfter(policy, primary, failure(ProviderFailureKind.NETWORK)))
    }

    @Test
    fun `the secondary is never tried twice`() {
        val policy = policy(allowed = setOf(FallbackReason.PROVIDER_TIMEOUT))

        assertNull(ProviderFallback.nextAfter(policy, secondary, failure(ProviderFailureKind.TIMEOUT)))
    }

    @Test
    fun `a credential the provider rejected can move the turn even though it cannot be retried`() {
        val rejected = failure(ProviderFailureKind.AUTHENTICATION, retryable = false)
        val policy = policy(allowed = setOf(FallbackReason.PROVIDER_AUTH_FAILED))

        assertFalse("the same provider must not be retried", rejected.retryable)
        assertEquals(secondary, ProviderFallback.nextAfter(policy, primary, rejected))
    }

    @Test
    fun `an attempt record names the provider, the model and the credential slot`() {
        val record = ProviderAttemptRecord(
            provider = primary,
            failure = failure(ProviderFailureKind.TIMEOUT),
            fallbackReason = FallbackReason.PROVIDER_TIMEOUT
        )

        assertEquals(ProviderKind.OPENAI, record.provider.providerKind)
        assertEquals("gpt-4o-mini", record.provider.modelId)
        assertEquals("slot-openai", record.provider.credentialSlotId)
        assertTrue(record.notCalledReason == null)
    }
}
