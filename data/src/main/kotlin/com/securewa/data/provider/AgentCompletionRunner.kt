package com.securewa.data.provider

import com.securewa.core.provider.CompletionOutcome
import com.securewa.core.provider.CompletionRequest
import com.securewa.core.provider.CompletionResponse
import com.securewa.core.provider.ProviderAttemptRecord
import com.securewa.core.provider.ProviderFallback
import com.securewa.core.provider.ProviderFailure
import com.securewa.core.provider.ProviderFailureKind
import com.securewa.core.routing.AgentRouteConfig
import com.securewa.core.routing.ProviderFallbackPolicy
import com.securewa.core.routing.ProviderKind
import com.securewa.core.routing.ProviderRef
import com.securewa.core.security.Redactor

/** Supplies the adapter for a provider family. */
fun interface ProviderClientSource {
    fun clientFor(kind: ProviderKind): AiProviderClient
}

/**
 * What one turn produced, together with every attempt it took to get there.
 *
 * The attempts are the audit trail: which provider, model and credential slot
 * took part, what went wrong, and why a second provider was allowed to see the
 * conversation at all.
 */
sealed interface CompletionRun {

    val attempts: List<ProviderAttemptRecord>

    data class Answered(
        val response: CompletionResponse,
        override val attempts: List<ProviderAttemptRecord>
    ) : CompletionRun

    /** The turn failed. [attempts] always holds at least one entry. */
    data class Failed(
        val failure: ProviderFailure,
        override val attempts: List<ProviderAttemptRecord>
    ) : CompletionRun
}

/**
 * Runs one completion for one agent, honouring that agent's fallback policy.
 *
 * The chain is at most two providers long: the configured primary and, when the
 * agent explicitly allows it for the reason that occurred, the configured
 * secondary. Anything longer would make the cost and the privacy of a reply
 * impossible to reason about, which is the thing the policy exists to protect.
 *
 * Every attempt is recorded, including the ones that never reached a provider,
 * so a turn is never quietly unaccounted for.
 */
class AgentCompletionRunner(
    private val endpoints: EndpointResolver,
    private val clients: ProviderClientSource
) {

    suspend fun run(agent: AgentRouteConfig, request: CompletionRequest): CompletionRun =
        run(agent.provider, agent.fallback, request)

    suspend fun run(
        primary: ProviderRef,
        fallback: ProviderFallbackPolicy,
        request: CompletionRequest
    ): CompletionRun {
        val attempts = mutableListOf<ProviderAttemptRecord>()
        var current = primary
        var lastFailure: ProviderFailure? = null

        while (true) {
            val resolution = endpoints.resolve(current)

            if (resolution !is EndpointResolution.Ready) {
                val detail = Redactor.redactText(resolution.detail)
                attempts += ProviderAttemptRecord(
                    provider = current,
                    isFallbackAttempt = current != primary,
                    notCalledReason = detail
                )
                return CompletionRun.Failed(
                    failure = lastFailure
                        ?: ProviderFailure(kind = ProviderFailureKind.UNAVAILABLE, message = detail, retryable = false),
                    attempts = attempts
                )
            }

            val outcome = clients.clientFor(current.providerKind).complete(resolution.endpoint, request)
            if (outcome is CompletionOutcome.Success) {
                attempts += ProviderAttemptRecord(provider = current, isFallbackAttempt = current != primary)
                return CompletionRun.Answered(outcome.response, attempts)
            }

            val failure = (outcome as CompletionOutcome.Failure).failure
            lastFailure = failure

            val next = ProviderFallback.nextAfter(fallback, current, failure)
            attempts += ProviderAttemptRecord(
                provider = current,
                isFallbackAttempt = current != primary,
                failure = failure,
                fallbackReason = if (next == null) null else ProviderFallback.reasonFor(failure.kind)
            )

            if (next == null) return CompletionRun.Failed(failure, attempts)
            current = next
        }
    }
}
