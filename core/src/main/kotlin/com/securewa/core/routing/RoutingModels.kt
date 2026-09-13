package com.securewa.core.routing

import com.securewa.core.model.UserType
import java.time.Instant

/** Messaging channels supported by the routing layer. */
enum class ChannelKind {
    WHATSAPP_TWILIO,
    SMS_TWILIO
}

/** AI provider families. Adapters are selected by this enum, never by string. */
enum class ProviderKind {
    GEMINI,
    OPENAI,
    ANTHROPIC,
    /** Any OpenAI-compatible HTTP endpoint (OpenRouter, Groq, Ollama, vLLM, ...). */
    OPENAI_COMPATIBLE
}

/** Why a provider call failed, used to decide whether fallback is permitted. */
enum class FallbackReason {
    PROVIDER_TIMEOUT,
    PROVIDER_RATE_LIMITED,
    PROVIDER_SERVER_ERROR,
    PROVIDER_AUTH_FAILED,
    RESPONSE_VALIDATION_FAILED
}

/**
 * Points at a credential slot and a model without carrying any secret.
 * The routing layer never sees key material.
 */
data class ProviderRef(
    val providerKind: ProviderKind,
    val modelId: String,
    val credentialSlotId: String,
    val configurationRevision: Long
)

/**
 * Explicit, opt-in fallback. Fallback is disabled by default because switching
 * providers can change both cost and where message content is processed.
 */
data class ProviderFallbackPolicy(
    val enabled: Boolean = false,
    val secondary: ProviderRef? = null,
    val allowedReasons: Set<FallbackReason> = emptySet()
) {
    fun permits(reason: FallbackReason): Boolean =
        enabled && secondary != null && allowedReasons.contains(reason)
}

/** Immutable snapshot of an agent configuration used for one message turn. */
data class AgentRouteConfig(
    val agentId: String,
    val agentName: String,
    val registeredNumberId: String,
    val agentRevision: Long,
    val enabled: Boolean,
    val userType: UserType,
    val provider: ProviderRef,
    val fallback: ProviderFallbackPolicy = ProviderFallbackPolicy(),
    /** Digest of the system prompt, not the prompt itself: routing must not need content. */
    val systemPromptDigest: String = ""
)

/** What a number does when no routing rule matches. */
enum class NoRuleMatchBehavior {
    USE_DEFAULT_AGENT,
    SEND_CONTROLLED_MESSAGE,
    DECLINE_TO_RESPOND
}

/** Immutable snapshot of a registered number used for one message turn. */
data class RegisteredNumberRouteConfig(
    val id: String,
    val e164: String,
    val userType: UserType,
    val enabled: Boolean,
    val defaultAgentId: String? = null,
    val onNoRuleMatch: NoRuleMatchBehavior = NoRuleMatchBehavior.SEND_CONTROLLED_MESSAGE,
    /** Static text used only when [NoRuleMatchBehavior.SEND_CONTROLLED_MESSAGE] is selected. */
    val controlledMessage: String? = null,
    val policyVersion: Long = 1L
)

/** A user-defined routing rule. Rules are ordered by [priority] then [id]. */
data class RoutingRule(
    val id: String,
    val registeredNumberId: String,
    val targetAgentId: String,
    /** Lower value wins. Identical priorities are broken by [id] ascending. */
    val priority: Int,
    val enabled: Boolean = true,
    /** Empty means "any user type". */
    val userTypeFilter: Set<UserType> = emptySet(),
    /** Empty means "keyword is not considered". */
    val keywordAny: Set<String> = emptySet(),
    /** Empty means "any sender". */
    val senderE164Exact: Set<String> = emptySet(),
    val conversationTagExact: String? = null,
    /** Schedule bounds in minutes after midnight in the configured business timezone. */
    val activeFromMinuteOfDay: Int? = null,
    val activeUntilMinuteOfDay: Int? = null,
    val description: String = "",
    val policyVersion: Long = 1L
)

/**
 * Everything the router is allowed to consider. The router performs no network
 * calls, reads no credentials and mutates nothing.
 */
data class RoutingCandidates(
    val registeredNumber: RegisteredNumberRouteConfig,
    val agents: List<AgentRouteConfig>,
    val rules: List<RoutingRule>
)

/**
 * One inbound message as presented to the router.
 *
 * [localMinuteOfDay] is supplied by the caller so the router stays free of
 * timezone and clock concerns: without it, schedule rules cannot be evaluated
 * and are treated as non-matching rather than guessed.
 */
data class RoutingRequest(
    val destinationNumberE164: String,
    val senderE164: String,
    val channelKind: ChannelKind,
    val channelId: String,
    val externalMessageId: String,
    val receivedAt: Instant,
    val body: String? = null,
    val conversationTag: String? = null,
    val localMinuteOfDay: Int? = null
)

/** Why an agent was chosen. Recorded with every decision. */
enum class SelectionReason {
    RULE_MATCH,
    DEFAULT_AGENT
}

/** Why no AI response may be produced. Recorded with every decision. */
enum class RejectionReason {
    INVALID_REQUEST,
    DESTINATION_NOT_FOUND,
    NUMBER_DISABLED,
    NO_AGENT_CONFIGURED,
    AGENT_UNAVAILABLE,
    NO_RULE_MATCHED,
    DECLINED_BY_POLICY,
    CONFIGURATION_CONFLICT
}

/**
 * Result of routing.
 *
 * [explanation] is a human readable audit trail describing exactly which rules
 * were considered, which matched and why the final selection was made. It never
 * contains message content or credentials.
 */
sealed interface RoutingDecision {
    val correlationId: String
    val policyVersion: Long
    val explanation: List<String>
}

/** An agent was selected; the caller may now call the provider. */
data class RoutedDecision(
    val registeredNumberId: String,
    val userType: UserType,
    val agent: AgentRouteConfig,
    val provider: ProviderRef,
    val fallbackChain: List<ProviderRef>,
    val matchedRule: RoutingRule?,
    val selectionReason: SelectionReason,
    val tieBreakApplied: Boolean,
    override val correlationId: String,
    override val policyVersion: Long,
    override val explanation: List<String>
) : RoutingDecision

/**
 * No AI response is produced, but a controlled static message is sent back.
 * This is a real outcome, not a simulated provider response.
 */
data class ControlledResponseDecision(
    val registeredNumberId: String,
    val userType: UserType,
    val messageText: String,
    val reason: RejectionReason,
    override val correlationId: String,
    override val policyVersion: Long,
    override val explanation: List<String>
) : RoutingDecision

/** No response of any kind is produced. */
data class RejectedDecision(
    val registeredNumberId: String?,
    val reason: RejectionReason,
    override val correlationId: String,
    override val policyVersion: Long,
    override val explanation: List<String>
) : RoutingDecision
