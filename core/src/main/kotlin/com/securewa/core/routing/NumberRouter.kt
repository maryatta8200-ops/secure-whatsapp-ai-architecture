package com.securewa.core.routing

import com.securewa.core.model.UserType
import com.securewa.core.security.Digest

/**
 * Decides how one inbound message is handled.
 *
 * Contract (enforced by tests in `NumberRouterTest`):
 *  - pure: no network calls, no database access, no credential access,
 *    no mutation of unrelated records, no AI generation;
 *  - deterministic: the same request and the same candidate set always produce
 *    the same decision, including when several rules match;
 *  - isolated: only agents belonging to the resolved registered number and
 *    matching its user type are eligible;
 *  - auditable: every decision carries a correlation id, the policy version and
 *    an ordered explanation.
 */
class NumberRouter {

    /**
     * @param request    the inbound message.
     * @param candidates the number, its agents and its rules, already scoped to
     *                   the destination number by the caller.
     */
    fun route(request: RoutingRequest, candidates: RoutingCandidates): RoutingDecision {
        val number = candidates.registeredNumber
        val explanation = mutableListOf<String>()

        if (request.externalMessageId.isBlank()) {
            return RejectedDecision(
                registeredNumberId = number.id,
                reason = RejectionReason.INVALID_REQUEST,
                correlationId = "invalid-request",
                policyVersion = number.policyVersion,
                explanation = listOf("Rejected: inbound message has no external message identifier.")
            )
        }
        if (request.destinationNumberE164.isBlank() || request.senderE164.isBlank()) {
            return RejectedDecision(
                registeredNumberId = number.id,
                reason = RejectionReason.INVALID_REQUEST,
                correlationId = correlationId(number.id, request),
                policyVersion = number.policyVersion,
                explanation = listOf("Rejected: destination or sender number is missing.")
            )
        }

        val correlation = correlationId(number.id, request)
        explanation += "Destination ${request.destinationNumberE164} resolved to registered number ${number.id} (${number.userType.storageKey})."

        if (request.destinationNumberE164 != number.e164) {
            explanation += "Supplied number ${number.e164} does not match destination ${request.destinationNumberE164}."
            return RejectedDecision(
                registeredNumberId = number.id,
                reason = RejectionReason.DESTINATION_NOT_FOUND,
                correlationId = correlation,
                policyVersion = number.policyVersion,
                explanation = explanation
            )
        }
        if (!number.enabled) {
            explanation += "Registered number is disabled."
            return RejectedDecision(
                registeredNumberId = number.id,
                reason = RejectionReason.NUMBER_DISABLED,
                correlationId = correlation,
                policyVersion = number.policyVersion,
                explanation = explanation
            )
        }

        val eligibleAgents = candidates.agents.filter { it.registeredNumberId == number.id }
        val crossTenantAgents = candidates.agents.size - eligibleAgents.size
        if (crossTenantAgents > 0) {
            explanation += "Ignored $crossTenantAgents agent(s) belonging to a different registered number."
        }
        val mismatchedType = eligibleAgents.count { it.userType != number.userType }
        if (mismatchedType > 0) {
            explanation += "Ignored $mismatchedType agent(s) whose user type differs from ${number.userType.storageKey}."
        }

        val usableAgents = eligibleAgents.filter { it.enabled && it.userType == number.userType }
        if (usableAgents.isEmpty()) {
            explanation += "No enabled agent matches user type ${number.userType.storageKey}."
            val reason = if (eligibleAgents.isEmpty()) {
                RejectionReason.NO_AGENT_CONFIGURED
            } else {
                RejectionReason.CONFIGURATION_CONFLICT
            }
            return RejectedDecision(
                registeredNumberId = number.id,
                reason = reason,
                correlationId = correlation,
                policyVersion = number.policyVersion,
                explanation = explanation
            )
        }

        val agentById = usableAgents.associateBy { it.agentId }
        val rules = candidates.rules.filter { it.enabled && it.registeredNumberId == number.id }
            .sortedWith(compareBy(RoutingRule::priority).thenBy(RoutingRule::id))

        if (rules.isEmpty()) {
            explanation += "No routing rules configured for this number."
            return selectDefaultAgent(number, agentById, correlation, explanation)
                ?: noMatchOutcome(number, correlation, explanation, RejectionReason.NO_RULE_MATCHED)
        }

        val matched = rules.mapNotNull { rule ->
            val (matches, why) = evaluate(rule, request, number, agentById)
            explanation += why
            if (matches) rule else null
        }

        if (matched.isEmpty()) {
            explanation += "No rule matched."
            return selectDefaultAgent(number, agentById, correlation, explanation)
                ?: noMatchOutcome(number, correlation, explanation, RejectionReason.NO_RULE_MATCHED)
        }

        val best = matched.first()
        val tieBreakApplied = matched.size > 1 && matched[1].priority == best.priority
        if (tieBreakApplied) {
            explanation += "Tie-break: rules ${matched.joinToString(", ") { "${it.id}(priority ${it.priority})" }} " +
                "matched with equal priority; selected ${best.id} by ascending rule id."
        }

        val agent = agentById[best.targetAgentId]
        if (agent == null) {
            explanation += "Rule ${best.id} targets agent ${best.targetAgentId}, which is not enabled for this number and user type. " +
                "Refusing to substitute another agent."
            return RejectedDecision(
                registeredNumberId = number.id,
                reason = RejectionReason.AGENT_UNAVAILABLE,
                correlationId = correlation,
                policyVersion = number.policyVersion,
                explanation = explanation
            )
        }

        explanation += "Selected agent ${agent.agentName} (${agent.agentId}, revision ${agent.agentRevision}) " +
            "via rule ${best.id}; provider ${agent.provider.providerKind}/${agent.provider.modelId}."

        return RoutedDecision(
            registeredNumberId = number.id,
            userType = number.userType,
            agent = agent,
            provider = agent.provider,
            fallbackChain = buildFallbackChain(agent, explanation),
            matchedRule = best,
            selectionReason = SelectionReason.RULE_MATCH,
            tieBreakApplied = tieBreakApplied,
            correlationId = correlation,
            policyVersion = number.policyVersion,
            explanation = explanation.toList()
        )
    }

    /** Ordered provider chain: primary first, secondary only when explicitly permitted. */
    private fun buildFallbackChain(agent: AgentRouteConfig, explanation: MutableList<String>): List<ProviderRef> {
        val chain = mutableListOf(agent.provider)
        val secondary = agent.fallback.secondary
        if (agent.fallback.enabled && secondary != null) {
            chain += secondary
            explanation += "Fallback enabled to ${secondary.providerKind}/${secondary.modelId} for " +
                "${agent.fallback.allowedReasons.joinToString(", ") { it.name }} only."
        } else {
            explanation += "Fallback disabled."
        }
        return chain
    }

    private fun selectDefaultAgent(
        number: RegisteredNumberRouteConfig,
        agentById: Map<String, AgentRouteConfig>,
        correlation: String,
        explanation: MutableList<String>
    ): RoutingDecision? {
        val defaultId = number.defaultAgentId ?: run {
            explanation += "No default agent configured."
            return null
        }
        val agent = agentById[defaultId] ?: run {
            explanation += "Default agent $defaultId is not enabled for this number and user type. " +
                "Refusing to substitute another agent."
            return RejectedDecision(
                registeredNumberId = number.id,
                reason = RejectionReason.AGENT_UNAVAILABLE,
                correlationId = correlation,
                policyVersion = number.policyVersion,
                explanation = explanation
            )
        }
        explanation += "Selected default agent ${agent.agentName} (${agent.agentId}, revision ${agent.agentRevision})."
        return RoutedDecision(
            registeredNumberId = number.id,
            userType = number.userType,
            agent = agent,
            provider = agent.provider,
            fallbackChain = buildFallbackChain(agent, explanation),
            matchedRule = null,
            selectionReason = SelectionReason.DEFAULT_AGENT,
            tieBreakApplied = false,
            correlationId = correlation,
            policyVersion = number.policyVersion,
            explanation = explanation.toList()
        )
    }

    private fun noMatchOutcome(
        number: RegisteredNumberRouteConfig,
        correlation: String,
        explanation: MutableList<String>,
        reason: RejectionReason
    ): RoutingDecision {
        return when (number.onNoRuleMatch) {
            NoRuleMatchBehavior.SEND_CONTROLLED_MESSAGE -> {
                val text = number.controlledMessage
                if (text.isNullOrBlank()) {
                    explanation += "Configured behaviour is a controlled message, but no message text is configured."
                    RejectedDecision(number.id, RejectionReason.DECLINED_BY_POLICY, correlation, number.policyVersion, explanation)
                } else {
                    explanation += "No agent selected; sending the configured controlled message."
                    ControlledResponseDecision(
                        registeredNumberId = number.id,
                        userType = number.userType,
                        messageText = text,
                        reason = reason,
                        correlationId = correlation,
                        policyVersion = number.policyVersion,
                        explanation = explanation
                    )
                }
            }
            NoRuleMatchBehavior.DECLINE_TO_RESPOND -> {
                explanation += "No agent selected; configured behaviour is to decline to respond."
                RejectedDecision(number.id, RejectionReason.DECLINED_BY_POLICY, correlation, number.policyVersion, explanation)
            }
            NoRuleMatchBehavior.USE_DEFAULT_AGENT -> {
                // selectDefaultAgent already returned null, so no default is usable.
                explanation += "No default agent is usable; declining to respond."
                RejectedDecision(number.id, RejectionReason.DECLINED_BY_POLICY, correlation, number.policyVersion, explanation.toList())
            }
        }
    }

    /**
     * Evaluates one rule. Returns whether it matched together with an audit line
     * explaining the evaluation.
     */
    private fun evaluate(
        rule: RoutingRule,
        request: RoutingRequest,
        number: RegisteredNumberRouteConfig,
        agentById: Map<String, AgentRouteConfig>
    ): Pair<Boolean, String> {
        val checks = mutableListOf<String>()

        if (!agentById.containsKey(rule.targetAgentId)) {
            checks += "target agent ${rule.targetAgentId} not eligible"
            return false to "Rule ${rule.id} (priority ${rule.priority}) skipped: ${checks.joinToString("; ")}."
        }
        if (rule.userTypeFilter.isNotEmpty() && !rule.userTypeFilter.contains(number.userType)) {
            checks += "user type ${number.userType.storageKey} not in ${rule.userTypeFilter.map { it.storageKey }}"
            return false to "Rule ${rule.id} (priority ${rule.priority}) skipped: ${checks.joinToString("; ")}."
        }
        if (rule.senderE164Exact.isNotEmpty() && !rule.senderE164Exact.contains(request.senderE164)) {
            checks += "sender not in allow-list"
            return false to "Rule ${rule.id} (priority ${rule.priority}) skipped: ${checks.joinToString("; ")}."
        }
        if (rule.conversationTagExact != null && rule.conversationTagExact != request.conversationTag) {
            checks += "conversation tag '${request.conversationTag}' != '${rule.conversationTagExact}'"
            return false to "Rule ${rule.id} (priority ${rule.priority}) skipped: ${checks.joinToString("; ")}."
        }
        if (rule.keywordAny.isNotEmpty()) {
            val body = request.body
            if (body == null) {
                checks += "rule requires a keyword but the message has no text body"
                return false to "Rule ${rule.id} (priority ${rule.priority}) skipped: ${checks.joinToString("; ")}."
            }
            val lowered = body.lowercase()
            if (rule.keywordAny.none { keyword -> lowered.contains(keyword.lowercase()) }) {
                checks += "no keyword of ${rule.keywordAny.sorted()} present"
                return false to "Rule ${rule.id} (priority ${rule.priority}) skipped: ${checks.joinToString("; ")}."
            }
            checks += "keyword matched"
        }
        val from = rule.activeFromMinuteOfDay
        val until = rule.activeUntilMinuteOfDay
        if (from != null && until != null) {
            val minute = request.localMinuteOfDay
            if (minute == null) {
                checks += "rule is scheduled but no local time was supplied"
                return false to "Rule ${rule.id} (priority ${rule.priority}) skipped: ${checks.joinToString("; ")}."
            }
            val inside = if (from <= until) minute in from until until else minute >= from || minute < until
            if (!inside) {
                checks += "local minute $minute outside ${formatWindow(from, until)}"
                return false to "Rule ${rule.id} (priority ${rule.priority}) skipped: ${checks.joinToString("; ")}."
            }
            checks += "inside schedule"
        }
        if (checks.isEmpty()) checks += "all constraints satisfied"
        return true to "Rule ${rule.id} (priority ${rule.priority}) matched: ${checks.joinToString("; ")}."
    }

    private fun formatWindow(from: Int, until: Int): String {
        fun hhmm(minute: Int) = String.format("%02d:%02d", minute / 60, minute % 60)
        return "${hhmm(from)}-${hhmm(until)}"
    }

    /** Stable identifier for one routing decision, derived from the inbound identity. */
    fun correlationId(registeredNumberId: String, request: RoutingRequest): String =
        Digest.sha256Hex("route|$registeredNumberId|${request.channelKind}|${request.externalMessageId}")
}

/** Convenience helper for tests and fixtures. */
fun userTypeOf(storageKey: String): UserType = UserType.require(storageKey)
