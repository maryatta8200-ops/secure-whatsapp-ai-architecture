# Architecture

## Layering

```
User Types → Registered Numbers → Agents → Providers → Messaging Channels
```

The implementation follows the same direction as the data model:

```
                    ┌──────────────────────────────────────────┐
   WhatsApp user ──►│ Twilio WhatsApp integration               │
                    └───────────────┬──────────────────────────┘
                                    │ inbound webhook (HTTPS, public)
                    ┌───────────────▼──────────────────────────┐
                    │ Documented receiver (Twilio Functions +  │
                    │ Sync queue), authenticated and observable│
                    └───────────────┬──────────────────────────┘
                                    │ pull / acknowledge / health (authenticated)
   ┌────────────────────────────────▼───────────────────────────────────────┐
   │ Android application                                                    │
   │  inbound validation → idempotency → NumberRouter → agent snapshot      │
   │  → provider adapter → output rules → outbound Twilio send → audit      │
   └────────────────────────────────────────────────────────────────────────┘
```

## Modules

| Module | Kind | Contents |
| --- | --- | --- |
| `:core` | Kotlin/JVM, **no Android and no third-party dependencies** | Deterministic domain: user types, E.164, `NumberRouter`, Twilio signature validation, replay guard, idempotency keys, redaction, rate limiting, retry policy, health state model, capability registry. |
| `:app` | Android application | Compose UI, Android-specific wiring, WorkManager scheduling (added with the features that need it). |
| `:data` | Android library (milestone 2) | Room database, repositories, Android Keystore-backed encryption, HTTP adapters, receiver client. |

`:core` being dependency-free is a deliberate constraint: it is the part that
must be provably correct, and it is the part that can be compiled and executed
in an environment without network access (see
[LOCAL_VERIFICATION.md](LOCAL_VERIFICATION.md)).

## User types

`Doctor`, `Patient` and `Common user` are an enum persisted by **storage key**
(`doctor`, `patient`, `common_user`), never by ordinal and never by free text.
`UserType.fromStorageKey` returns `null` for anything unknown, so an invalid
value is rejected instead of silently mapping to a default. Each type carries a
retention default and a data-sensitivity class used by the safety and retention
layers.

## Number resolution and routing

`NumberRouter` is a pure decision component. Given a `RoutingRequest` and a
`RoutingCandidates` snapshot (one registered number, its agents, its rules) it
returns one of:

- `RoutedDecision` — exactly one agent, its provider reference, the ordered
  fallback chain, the matched rule, the selection reason, a correlation id and
  an ordered explanation;
- `ControlledResponseDecision` — a configured static message, no AI call;
- `RejectedDecision` — no response, with a reason.

It performs no network calls, reads no credentials, generates no AI response and
mutates nothing. Ordering is `(priority asc, id asc)`; equal priorities are
broken by rule id and the tie-break is recorded in the explanation. Only agents
belonging to the resolved number **and** matching its user type are eligible.
A rule whose target agent is missing or disabled is skipped: the router never
substitutes another agent.

## Agents and isolation

Each number owns one or more agents. An agent is addressed by a stable id and is
loaded for a turn as an **immutable snapshot** (`AgentRouteConfig`) carrying a
revision and a prompt digest, so an in-flight message can never combine settings
from two agent revisions. Agents may share a provider, a model or a credential
slot; sharing a slot means sharing a *reference* to an encrypted credential, not
sharing prompts, conversation history, permissions or configuration.

## Providers

`ProviderKind` enumerates Gemini, OpenAI, Anthropic and
`OPENAI_COMPATIBLE` (any OpenAI-compatible endpoint: OpenRouter, Groq, Ollama,
vLLM). Adapters implement one interface and are selected by the agent's
`ProviderRef`, never by a hard-coded branch in the message path.

Fallback is explicit and disabled by default. `ProviderFallbackPolicy` names the
secondary provider and the failure reasons that permit falling back, because
switching provider changes cost and changes where message content is processed.

## Messaging and the inbound receiver

Outbound: the app calls the Twilio REST API directly with Basic auth over HTTPS.

Inbound: an Android application is **not** a reliable public HTTPS endpoint. It
cannot serve a webhook while it is closed, suspended or offline, and the OS may
kill the process at any time. The app therefore integrates with an externally
hosted, documented receiver and pulls from it. See
[RECEIVER_REQUIREMENTS.md](RECEIVER_REQUIREMENTS.md). The app displays the
receiver's real health and its own processing backlog; it never reports a
delivery it has not confirmed.

## Security model

See [SECURITY.md](SECURITY.md). Summary: credentials live in encrypted slots,
never in plain database columns; inbound requests are signature-validated and
replay-protected; idempotency keys are derived from provider message
identifiers; anything written to a log, notification, crash report or audit
explanation is passed through `Redactor` first.

## Resource usage

Event-driven by design: inbound work is pulled and acknowledged rather than
polled on a fixed schedule, retry delays come from `RetryPolicy` with
exponential backoff, provider and send rates are bounded by `RateLimiter`, and
both the replay guard and the rate limiter are bounded structures so a traffic
burst cannot exhaust memory. No `Application.onCreate` work is started before
the feature that needs it exists.
