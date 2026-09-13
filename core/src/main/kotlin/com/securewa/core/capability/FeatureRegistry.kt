package com.securewa.core.capability

/**
 * Single source of truth for "is this feature actually implemented?".
 *
 * Every screen renders its unavailable capabilities from this registry instead
 * of showing a plausible-looking empty state. A capability is only reported as
 * available from the milestone in which it was implemented and verified, so the
 * app can never present an unfinished feature as working.
 */
enum class Capability(
    val displayName: String,
    val availableFromMilestone: Int,
    val summary: String
) {
    DOMAIN_CORE("Domain core (user types, E.164, routing, security)", 1,
        "User types, E.164 validation, deterministic routing, Twilio signature validation, idempotency and redaction."),
    APP_SHELL("Application shell", 1,
        "Application entry point and capability status screen."),
    APP_LOCK("Application password lock", 3,
        "Passphrase derived key that protects the local database and the credential vault."),
    LOCAL_PERSISTENCE("Encrypted local persistence", 2,
        "Room database holding numbers, agents, providers, conversations and messages."),
    CREDENTIAL_VAULT("Encrypted credential vault", 3,
        "Credential slots encrypted with Android Keystore-backed keys."),
    AI_PROVIDERS("AI provider adapters", 4,
        "Gemini, OpenAI, Anthropic and OpenAI-compatible provider adapters."),
    TWILIO_ADAPTER("Twilio messaging adapter", 5,
        "Authenticated Twilio REST calls for outbound WhatsApp messages."),
    INBOUND_RECEIVER("Inbound receiver integration", 6,
        "Pull, acknowledgement and health of the documented Twilio receiver."),
    MESSAGE_PIPELINE("Inbound/outbound message pipeline", 6,
        "Durable processing from inbound webhook to outbound Twilio message."),
    NUMBER_MANAGEMENT_UI("Number management UI", 7,
        "Register, categorise, test and disable numbers."),
    AGENT_MANAGEMENT_UI("Agent management UI", 7,
        "Create, configure, test, enable and disable agents."),
    CONVERSATIONS_UI("Messages and logs UI", 8,
        "Conversation history, delivery state, audit and application logs."),
    BACKUP_RESTORE("Encrypted backup and restore", 9,
        "Export and import of configuration without recoverable secrets."),
    RELEASE_HARDENING("Release hardening", 9,
        "R8 rules, release signing configuration and release security checks.")
}

/** Availability status rendered by the UI. */
data class FeatureStatus(
    val capability: Capability,
    val available: Boolean,
    val availableFromMilestone: Int
)

object FeatureRegistry {
    /**
     * The milestone this build implements. Bumped only when the corresponding
     * verification (local and CI) has passed for the milestone commit.
     */
    const val CURRENT_MILESTONE: Int = 3

    fun isAvailable(capability: Capability): Boolean =
        capability.availableFromMilestone <= CURRENT_MILESTONE

    fun status(): List<FeatureStatus> = Capability.entries
        .sortedWith(compareBy(Capability::availableFromMilestone).thenBy { it.displayName })
        .map { FeatureStatus(it, isAvailable(it), it.availableFromMilestone) }
}
