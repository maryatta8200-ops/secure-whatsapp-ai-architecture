package com.securewa.core.routing

import com.securewa.core.model.UserType
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NumberRouterTest {

    private val router = NumberRouter()
    private val doctorNumber = "+15551000001"
    private val patientNumber = "+15551000002"
    private val sender = "+15557654321"
    private val receivedAt = Instant.parse("2026-09-13T09:15:00Z")

    private fun number(
        id: String = "num-doctor",
        e164: String = doctorNumber,
        userType: UserType = UserType.DOCTOR,
        enabled: Boolean = true,
        defaultAgentId: String? = "agent-a1",
        onNoRuleMatch: NoRuleMatchBehavior = NoRuleMatchBehavior.DECLINE_TO_RESPOND,
        controlledMessage: String? = null
    ) = RegisteredNumberRouteConfig(
        id = id,
        e164 = e164,
        userType = userType,
        enabled = enabled,
        defaultAgentId = defaultAgentId,
        onNoRuleMatch = onNoRuleMatch,
        controlledMessage = controlledMessage
    )

    private fun agent(
        id: String,
        numberId: String = "num-doctor",
        userType: UserType = UserType.DOCTOR,
        enabled: Boolean = true,
        providerKind: ProviderKind = ProviderKind.GEMINI,
        modelId: String = "gemini-1.5-pro",
        credentialSlotId: String = "slot-a"
    ) = AgentRouteConfig(
        agentId = id,
        agentName = "Agent $id",
        registeredNumberId = numberId,
        agentRevision = 3L,
        enabled = enabled,
        userType = userType,
        provider = ProviderRef(providerKind, modelId, credentialSlotId, configurationRevision = 7L)
    )

    private fun request(
        destination: String = doctorNumber,
        body: String? = "hello",
        externalMessageId: String = "SM00000000000000000000000000000001",
        sender: String = this.sender,
        conversationTag: String? = null,
        localMinuteOfDay: Int? = null
    ) = RoutingRequest(
        destinationNumberE164 = destination,
        senderE164 = sender,
        channelKind = ChannelKind.WHATSAPP_TWILIO,
        channelId = "whatsapp",
        externalMessageId = externalMessageId,
        receivedAt = receivedAt,
        body = body,
        conversationTag = conversationTag,
        localMinuteOfDay = localMinuteOfDay
    )

    private fun rule(
        id: String,
        targetAgentId: String,
        priority: Int,
        numberId: String = "num-doctor",
        enabled: Boolean = true,
        keywords: Set<String> = emptySet(),
        senders: Set<String> = emptySet(),
        userTypes: Set<UserType> = emptySet(),
        conversationTag: String? = null,
        fromMinute: Int? = null,
        untilMinute: Int? = null
    ) = RoutingRule(
        id = id,
        registeredNumberId = numberId,
        targetAgentId = targetAgentId,
        priority = priority,
        enabled = enabled,
        userTypeFilter = userTypes,
        keywordAny = keywords,
        senderE164Exact = senders,
        conversationTagExact = conversationTag,
        activeFromMinuteOfDay = fromMinute,
        activeUntilMinuteOfDay = untilMinute,
        description = "rule $id"
    )

    @Test
    fun `keyword rule selects the matching subagent`() {
        val decision = router.route(
            request(body = "I need an appointment"),
            RoutingCandidates(
                registeredNumber = number(),
                agents = listOf(agent("agent-a1"), agent("agent-a2", providerKind = ProviderKind.OPENAI, modelId = "gpt-4o", credentialSlotId = "slot-b")),
                rules = listOf(
                    rule("rule-appointment", "agent-a2", priority = 10, keywords = setOf("appointment")),
                    rule("rule-catch-all", "agent-a1", priority = 100)
                )
            )
        )
        assertTrue("expected a routed decision but got $decision", decision is RoutedDecision)
        val routed = decision as RoutedDecision
        assertEquals("agent-a2", routed.agent.agentId)
        assertEquals(ProviderKind.OPENAI, routed.provider.providerKind)
        assertEquals("rule-appointment", routed.matchedRule?.id)
        assertEquals(SelectionReason.RULE_MATCH, routed.selectionReason)
        assertEquals(UserType.DOCTOR, routed.userType)
    }

    @Test
    fun `catch all rule handles messages with no keyword`() {
        val decision = router.route(
            request(body = "good morning"),
            RoutingCandidates(
                registeredNumber = number(),
                agents = listOf(agent("agent-a1"), agent("agent-a2")),
                rules = listOf(
                    rule("rule-appointment", "agent-a2", priority = 10, keywords = setOf("appointment")),
                    rule("rule-catch-all", "agent-a1", priority = 100)
                )
            )
        )
        val routed = decision as RoutedDecision
        assertEquals("agent-a1", routed.agent.agentId)
        assertEquals("rule-catch-all", routed.matchedRule?.id)
    }

    @Test
    fun `two agents on one number keep separate conversations because only one is selected`() {
        val agents = listOf(agent("agent-a1"), agent("agent-a2"))
        val candidates = RoutingCandidates(
            registeredNumber = number(defaultAgentId = null),
            agents = agents,
            rules = listOf(
                rule("rule-billing", "agent-a2", priority = 5, keywords = setOf("invoice")),
                rule("rule-triage", "agent-a1", priority = 5, keywords = setOf("pain"))
            )
        )
        val billing = router.route(request(body = "Where is my invoice?"), candidates) as RoutedDecision
        val triage = router.route(request(body = "I have chest pain"), candidates) as RoutedDecision

        assertEquals("agent-a2", billing.agent.agentId)
        assertEquals("agent-a1", triage.agent.agentId)
        assertTrue(
            "each decision must name exactly one agent",
            billing.agent.agentId != triage.agent.agentId
        )
    }

    @Test
    fun `equal priority rules are broken deterministically by rule id`() {
        val candidates = RoutingCandidates(
            registeredNumber = number(defaultAgentId = null),
            agents = listOf(agent("agent-a1"), agent("agent-a2")),
            rules = listOf(
                rule("rule-zzz", "agent-a2", priority = 5),
                rule("rule-aaa", "agent-a1", priority = 5)
            )
        )
        val first = router.route(request(), candidates) as RoutedDecision
        val second = router.route(request(), candidates) as RoutedDecision

        assertEquals("rule-aaa", first.matchedRule?.id)
        assertEquals(first.matchedRule?.id, second.matchedRule?.id)
        assertTrue(first.tieBreakApplied)
    }

    @Test
    fun `lower priority value wins regardless of list order`() {
        val candidates = RoutingCandidates(
            registeredNumber = number(defaultAgentId = null),
            agents = listOf(agent("agent-a1"), agent("agent-a2")),
            rules = listOf(
                rule("rule-low-priority-number", "agent-a2", priority = 9),
                rule("rule-high-priority-number", "agent-a1", priority = 1)
            )
        )
        val decision = router.route(request(), candidates) as RoutedDecision
        assertEquals("agent-a1", decision.agent.agentId)
        assertTrue(!decision.tieBreakApplied)
    }

    @Test
    fun `a number with no agent produces no AI response`() {
        val decision = router.route(
            request(),
            RoutingCandidates(registeredNumber = number(defaultAgentId = null), agents = emptyList(), rules = emptyList())
        )
        assertTrue("expected a rejection but got $decision", decision is RejectedDecision)
        assertEquals(RejectionReason.NO_AGENT_CONFIGURED, (decision as RejectedDecision).reason)
    }

    @Test
    fun `a disabled number is rejected before any agent is considered`() {
        val decision = router.route(
            request(),
            RoutingCandidates(registeredNumber = number(enabled = false), agents = listOf(agent("agent-a1")), rules = emptyList())
        )
        assertEquals(RejectionReason.NUMBER_DISABLED, (decision as RejectedDecision).reason)
    }

    @Test
    fun `agents belonging to another number can never be selected`() {
        val decision = router.route(
            request(),
            RoutingCandidates(
                registeredNumber = number(),
                agents = listOf(
                    agent("agent-other-number", numberId = "num-patient", userType = UserType.PATIENT),
                    agent("agent-a1")
                ),
                rules = emptyList()
            )
        ) as RoutedDecision
        assertEquals("agent-a1", decision.agent.agentId)
        assertTrue(
            "the explanation must record that a foreign agent was ignored",
            decision.explanation.any { it.contains("different registered number") }
        )
    }

    @Test
    fun `an agent whose user type no longer matches the number is not used`() {
        val decision = router.route(
            request(),
            RoutingCandidates(
                registeredNumber = number(userType = UserType.DOCTOR),
                agents = listOf(agent("agent-a1", userType = UserType.PATIENT)),
                rules = emptyList()
            )
        )
        assertTrue(decision is RejectedDecision)
        assertEquals(RejectionReason.CONFIGURATION_CONFLICT, (decision as RejectedDecision).reason)
    }

    @Test
    fun `routing is user type aware across two numbers`() {
        val doctorDecision = router.route(
            request(destination = doctorNumber),
            RoutingCandidates(
                registeredNumber = number(id = "num-doctor", e164 = doctorNumber, userType = UserType.DOCTOR, defaultAgentId = "agent-doctor"),
                agents = listOf(agent("agent-doctor", numberId = "num-doctor", userType = UserType.DOCTOR)),
                rules = emptyList()
            )
        ) as RoutedDecision
        val patientDecision = router.route(
            request(destination = patientNumber),
            RoutingCandidates(
                registeredNumber = number(id = "num-patient", e164 = patientNumber, userType = UserType.PATIENT, defaultAgentId = "agent-patient"),
                agents = listOf(agent("agent-patient", numberId = "num-patient", userType = UserType.PATIENT)),
                rules = emptyList()
            )
        ) as RoutedDecision

        assertEquals(UserType.DOCTOR, doctorDecision.userType)
        assertEquals(UserType.PATIENT, patientDecision.userType)
        assertEquals("num-doctor", doctorDecision.registeredNumberId)
        assertEquals("num-patient", patientDecision.registeredNumberId)
    }

    @Test
    fun `a rule targeting an unavailable agent never causes a substitution`() {
        // A rule whose agent was deleted or disabled cannot be satisfied. The
        // router must not silently pick a different agent: it falls through to
        // the configured no-match behaviour, which here is to decline.
        val decision = router.route(
            request(),
            RoutingCandidates(
                registeredNumber = number(defaultAgentId = null, onNoRuleMatch = NoRuleMatchBehavior.DECLINE_TO_RESPOND),
                agents = listOf(agent("agent-a1")),
                rules = listOf(rule("rule-missing-agent", "agent-deleted", priority = 1))
            )
        )
        assertTrue("no AI response may be produced: $decision", decision is RejectedDecision)
        val rejected = decision as RejectedDecision
        assertEquals(RejectionReason.DECLINED_BY_POLICY, rejected.reason)
        assertTrue(
            "the audit trail must record why the rule could not be used",
            rejected.explanation.any { it.contains("agent-deleted") && it.contains("not eligible") }
        )
        assertTrue(
            "no other agent may be named in the decision",
            rejected.explanation.none { it.contains("Selected agent") }
        )
    }

    @Test
    fun `a rule targeting a disabled agent does not fall back to that agent`() {
        val decision = router.route(
            request(),
            RoutingCandidates(
                registeredNumber = number(defaultAgentId = null),
                agents = listOf(agent("agent-a1", enabled = false), agent("agent-a2")),
                rules = listOf(rule("rule-disabled-agent", "agent-a1", priority = 1))
            )
        )
        assertTrue(decision is RejectedDecision)
        assertTrue((decision as RejectedDecision).explanation.none { it.contains("Selected agent") })
    }

    @Test
    fun `disabled rules are ignored`() {
        val decision = router.route(
            request(),
            RoutingCandidates(
                registeredNumber = number(defaultAgentId = null),
                agents = listOf(agent("agent-a1"), agent("agent-a2")),
                rules = listOf(
                    rule("rule-disabled", "agent-a2", priority = 1, enabled = false),
                    rule("rule-enabled", "agent-a1", priority = 50)
                )
            )
        ) as RoutedDecision
        assertEquals("agent-a1", decision.agent.agentId)
    }

    @Test
    fun `sender allow lists are enforced`() {
        val candidates = RoutingCandidates(
            registeredNumber = number(defaultAgentId = null),
            agents = listOf(agent("agent-a1"), agent("agent-a2")),
            rules = listOf(
                rule("rule-vip", "agent-a2", priority = 1, senders = setOf("+15559999999")),
                rule("rule-catch-all", "agent-a1", priority = 100)
            )
        )
        assertEquals("agent-a1", (router.route(request(), candidates) as RoutedDecision).agent.agentId)
        assertEquals(
            "agent-a2",
            (router.route(request(sender = "+15559999999"), candidates) as RoutedDecision).agent.agentId
        )
    }

    @Test
    fun `conversation tag rules are enforced`() {
        val candidates = RoutingCandidates(
            registeredNumber = number(defaultAgentId = null),
            agents = listOf(agent("agent-a1"), agent("agent-a2")),
            rules = listOf(
                rule("rule-followup", "agent-a2", priority = 1, conversationTag = "follow-up"),
                rule("rule-catch-all", "agent-a1", priority = 100)
            )
        )
        assertEquals(
            "agent-a2",
            (router.route(request(conversationTag = "follow-up"), candidates) as RoutedDecision).agent.agentId
        )
        assertEquals("agent-a1", (router.route(request(), candidates) as RoutedDecision).agent.agentId)
    }

    @Test
    fun `scheduled rules are skipped when the caller supplies no local time`() {
        val decision = router.route(
            request(),
            RoutingCandidates(
                registeredNumber = number(defaultAgentId = null),
                agents = listOf(agent("agent-a1"), agent("agent-a2")),
                rules = listOf(
                    rule("rule-night", "agent-a2", priority = 1, fromMinute = 18 * 60, untilMinute = 8 * 60),
                    rule("rule-catch-all", "agent-a1", priority = 100)
                )
            )
        ) as RoutedDecision
        assertEquals("agent-a1", decision.agent.agentId)
        assertTrue(decision.explanation.any { it.contains("no local time was supplied") })
    }

    @Test
    fun `scheduled rules match inside the window and wrap around midnight`() {
        val candidates = RoutingCandidates(
            registeredNumber = number(defaultAgentId = null),
            agents = listOf(agent("agent-a1"), agent("agent-a2")),
            rules = listOf(
                rule("rule-night", "agent-a2", priority = 1, fromMinute = 18 * 60, untilMinute = 8 * 60),
                rule("rule-catch-all", "agent-a1", priority = 100)
            )
        )
        assertEquals(
            "agent-a2",
            (router.route(request(localMinuteOfDay = 22 * 60), candidates) as RoutedDecision).agent.agentId
        )
        assertEquals(
            "agent-a2",
            (router.route(request(localMinuteOfDay = 30), candidates) as RoutedDecision).agent.agentId
        )
        assertEquals(
            "agent-a1",
            (router.route(request(localMinuteOfDay = 12 * 60), candidates) as RoutedDecision).agent.agentId
        )
    }

    @Test
    fun `no matching rule without a default agent declines to respond`() {
        val decision = router.route(
            request(),
            RoutingCandidates(
                registeredNumber = number(defaultAgentId = null, onNoRuleMatch = NoRuleMatchBehavior.DECLINE_TO_RESPOND),
                agents = listOf(agent("agent-a1")),
                rules = listOf(rule("rule-keyword", "agent-a1", priority = 1, keywords = setOf("zzz")))
            )
        )
        assertTrue(decision is RejectedDecision)
        assertEquals(RejectionReason.DECLINED_BY_POLICY, (decision as RejectedDecision).reason)
    }

    @Test
    fun `no matching rule can send a controlled message instead of an AI response`() {
        val decision = router.route(
            request(),
            RoutingCandidates(
                registeredNumber = number(
                    defaultAgentId = null,
                    onNoRuleMatch = NoRuleMatchBehavior.SEND_CONTROLLED_MESSAGE,
                    controlledMessage = "Our clinic is closed. Reply during working hours."
                ),
                agents = listOf(agent("agent-a1")),
                rules = listOf(rule("rule-keyword", "agent-a1", priority = 1, keywords = setOf("zzz")))
            )
        )
        assertTrue("expected a controlled response but got $decision", decision is ControlledResponseDecision)
        assertEquals(
            "Our clinic is closed. Reply during working hours.",
            (decision as ControlledResponseDecision).messageText
        )
    }

    @Test
    fun `the default agent is used when no rules are configured`() {
        val decision = router.route(
            request(),
            RoutingCandidates(
                registeredNumber = number(defaultAgentId = "agent-a1"),
                agents = listOf(agent("agent-a1"), agent("agent-a2")),
                rules = emptyList()
            )
        ) as RoutedDecision
        assertEquals("agent-a1", decision.agent.agentId)
        assertNull(decision.matchedRule)
        assertEquals(SelectionReason.DEFAULT_AGENT, decision.selectionReason)
    }

    @Test
    fun `fallback is disabled by default`() {
        val decision = router.route(
            request(),
            RoutingCandidates(
                registeredNumber = number(),
                agents = listOf(agent("agent-a1")),
                rules = emptyList()
            )
        ) as RoutedDecision
        assertEquals(1, decision.fallbackChain.size)
        assertTrue(decision.explanation.any { it.contains("Fallback disabled") })
    }

    @Test
    fun `fallback is only included when explicitly enabled`() {
        val secondary = ProviderRef(ProviderKind.ANTHROPIC, "claude-3-5-sonnet", "slot-b", 2L)
        val agentWithFallback = agent("agent-a1").copy(
            fallback = ProviderFallbackPolicy(
                enabled = true,
                secondary = secondary,
                allowedReasons = setOf(FallbackReason.PROVIDER_TIMEOUT)
            )
        )
        val decision = router.route(
            request(),
            RoutingCandidates(registeredNumber = number(), agents = listOf(agentWithFallback), rules = emptyList())
        ) as RoutedDecision

        assertEquals(2, decision.fallbackChain.size)
        assertEquals(ProviderKind.GEMINI, decision.fallbackChain[0].providerKind)
        assertEquals(ProviderKind.ANTHROPIC, decision.fallbackChain[1].providerKind)
        assertTrue(
            agentWithFallback.fallback.permits(FallbackReason.PROVIDER_TIMEOUT)
        )
        assertTrue(
            "fallback must not trigger for an unlisted reason",
            !agentWithFallback.fallback.permits(FallbackReason.PROVIDER_AUTH_FAILED)
        )
    }

    @Test
    fun `the correlation id is stable for the same message and differs across messages`() {
        val candidates = RoutingCandidates(
            registeredNumber = number(),
            agents = listOf(agent("agent-a1")),
            rules = emptyList()
        )
        val first = router.route(request(), candidates)
        val again = router.route(request(), candidates)
        val other = router.route(request(externalMessageId = "SM00000000000000000000000000000002"), candidates)

        assertEquals(first.correlationId, again.correlationId)
        assertTrue(first.correlationId != other.correlationId)
        assertEquals(64, first.correlationId.length)
    }

    @Test
    fun `a request without an external message id is rejected`() {
        val decision = router.route(
            request(externalMessageId = " "),
            RoutingCandidates(registeredNumber = number(), agents = listOf(agent("agent-a1")), rules = emptyList())
        )
        assertEquals(RejectionReason.INVALID_REQUEST, (decision as RejectedDecision).reason)
    }

    @Test
    fun `a destination that does not match the supplied number is rejected`() {
        val decision = router.route(
            request(destination = patientNumber),
            RoutingCandidates(registeredNumber = number(), agents = listOf(agent("agent-a1")), rules = emptyList())
        )
        assertEquals(RejectionReason.DESTINATION_NOT_FOUND, (decision as RejectedDecision).reason)
    }

    @Test
    fun `every decision carries an explanation and a policy version`() {
        val decision = router.route(
            request(),
            RoutingCandidates(registeredNumber = number(), agents = listOf(agent("agent-a1")), rules = emptyList())
        )
        assertTrue(decision.explanation.isNotEmpty())
        assertEquals(1L, decision.policyVersion)
    }
}
