package com.securewa.core.provider

import com.securewa.core.routing.FallbackReason
import com.securewa.core.routing.ProviderFallbackPolicy
import com.securewa.core.routing.ProviderRef

/**
 * Decides whether a failed attempt may be retried on another provider.
 *
 * Fallback is never automatic. Sending a conversation to a second organisation
 * changes what the turn costs and where the content is processed, so it happens
 * only when the agent was explicitly configured with a secondary provider *and*
 * with the reason that actually occurred. Anything else stops the turn and lets
 * the normal retry policy deal with the same provider.
 *
 * This is pure: no network, no credentials, no mutation. It can be tested
 * exhaustively, which matters because a fallback that fires too easily leaks
 * content to a provider the user never chose for this conversation.
 */
object ProviderFallback {

    /**
     * Why this failure may justify moving the turn to another provider, or
     * `null` when the failure says nothing another provider could fix.
     *
     * A network failure is deliberately excluded. Not reaching a provider is a
     * fact about the connection, not a verdict about the provider: acting on it
     * would hand the conversation to another organisation when the real problem
     * is that the device is offline.
     */
    fun reasonFor(kind: ProviderFailureKind): FallbackReason? = when (kind) {
        ProviderFailureKind.TIMEOUT -> FallbackReason.PROVIDER_TIMEOUT
        ProviderFailureKind.RATE_LIMITED -> FallbackReason.PROVIDER_RATE_LIMITED
        ProviderFailureKind.SERVER_ERROR -> FallbackReason.PROVIDER_SERVER_ERROR
        ProviderFailureKind.AUTHENTICATION -> FallbackReason.PROVIDER_AUTH_FAILED
        ProviderFailureKind.INVALID_REQUEST,
        ProviderFailureKind.PARSE,
        ProviderFailureKind.EMPTY_RESPONSE -> FallbackReason.RESPONSE_VALIDATION_FAILED
        // Reachable only when everything that can fail had no provider to blame.
        ProviderFailureKind.NETWORK -> null
        ProviderFailureKind.UNAVAILABLE -> null
    }

    /**
     * The provider to try next, or `null` when the turn must stop.
     *
     * [attempted] is what just failed. Passing the secondary back in returns
     * `null`, so a chain can never alternate between two providers.
     */
    fun nextAfter(
        policy: ProviderFallbackPolicy,
        attempted: ProviderRef,
        failure: ProviderFailure
    ): ProviderRef? {
        val secondary = policy.secondary ?: return null
        if (attempted == secondary) return null
        val reason = reasonFor(failure.kind) ?: return null
        return if (policy.permits(reason)) secondary else null
    }
}

/**
 * What one attempt did, whether it worked or not.
 *
 * Every attempt is recorded, including the ones that never reached a provider,
 * because the audit trail has to be able to answer "which provider, which model
 * and which credential slot took part in this turn". It carries no message
 * content and no key material, so it is safe to persist and to log.
 */
data class ProviderAttemptRecord(
    val provider: ProviderRef,
    /** True when this attempt ran on the agent's configured secondary provider. */
    val isFallbackAttempt: Boolean = false,
    /** Null when this attempt answered the turn. */
    val failure: ProviderFailure? = null,
    /** Why this failure was allowed to move the turn to another provider. */
    val fallbackReason: FallbackReason? = null,
    /**
     * Set instead of [failure] when the provider was never called at all, for
     * example because its credential slot is empty or the vault is locked.
     */
    val notCalledReason: String? = null
)
