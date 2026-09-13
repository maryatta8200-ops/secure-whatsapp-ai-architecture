package com.securewa.core.messaging

import com.securewa.core.routing.ChannelKind
import com.securewa.core.routing.ProviderKind
import com.securewa.core.security.Digest

/**
 * Deterministic idempotency keys.
 *
 * Every inbound message is deduplicated on a key derived from the channel, the
 * provider-assigned message identifier and the destination number, so that a
 * delivery retry or a replayed webhook is processed exactly once. Every
 * outbound attempt is keyed on the provider, the model and a digest of the
 * request, so that a retry after a timeout cannot produce a second WhatsApp
 * message for the same turn.
 */
object IdempotencyKeys {

    /**
     * Key for an inbound message. Deliberately computed *before* number
     * resolution, because a duplicate arrives on the same destination before any
     * routing has happened.
     */
    fun inbound(
        channelKind: ChannelKind,
        destinationNumberE164: String,
        externalMessageId: String
    ): String = Digest.sha256Hex(
        listOf("inbound", channelKind.name, destinationNumberE164, externalMessageId).joinToString("|")
    )

    /** Key for one outbound provider request within one inbound turn. */
    fun outbound(
        providerKind: ProviderKind,
        modelId: String,
        correlationId: String,
        attempt: Int
    ): String = Digest.sha256Hex(
        listOf("outbound", providerKind.name, modelId, correlationId, attempt.toString()).joinToString("|")
    )

    /** Key for the Twilio send of a generated reply. */
    fun twilioSend(correlationId: String, replyDigest: String): String =
        Digest.sha256Hex(listOf("twilio-send", correlationId, replyDigest).joinToString("|"))
}
