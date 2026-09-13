package com.securewa.core.messaging

import com.securewa.core.phone.PhoneNumberNormalizer
import com.securewa.core.routing.ChannelKind

/**
 * The delivery state of one outbound message, in this app's own terms.
 *
 * Twilio reports more states than this app needs and adds more over time, so a
 * status this build does not recognise maps to [UNKNOWN], which is neither
 * success nor failure. Reporting an unrecognised status as delivered would be
 * inventing an outcome, which is the one thing this app must never do.
 */
enum class DeliveryState(
    /** True when no further update will arrive for this message. */
    val isFinal: Boolean,
    /** True when the message reached the recipient. */
    val succeeded: Boolean
) {
    ACCEPTED(isFinal = false, succeeded = true),
    QUEUED(isFinal = false, succeeded = true),
    SENDING(isFinal = false, succeeded = true),
    SENT(isFinal = true, succeeded = true),
    DELIVERED(isFinal = true, succeeded = true),
    /** Some parts of a multi-part message arrived; not all of them. */
    PARTIALLY_DELIVERED(isFinal = true, succeeded = true),
    READ(isFinal = true, succeeded = true),
    FAILED(isFinal = true, succeeded = false),
    UNDELIVERED(isFinal = true, succeeded = false),
    CANCELED(isFinal = true, succeeded = false),
    UNKNOWN(isFinal = false, succeeded = false)
}

/** Translates what Twilio reports into what this app says. */
object TwilioStatusMapper {

    fun stateFor(status: String?): DeliveryState = when (status?.trim()?.lowercase()) {
        "accepted" -> DeliveryState.ACCEPTED
        "queued", "scheduled" -> DeliveryState.QUEUED
        "sending" -> DeliveryState.SENDING
        "sent", "received" -> DeliveryState.SENT
        "delivered" -> DeliveryState.DELIVERED
        "partially_delivered", "partially delivered" -> DeliveryState.PARTIALLY_DELIVERED
        "read" -> DeliveryState.READ
        "failed" -> DeliveryState.FAILED
        "undelivered" -> DeliveryState.UNDELIVERED
        "canceled", "cancelled" -> DeliveryState.CANCELED
        else -> DeliveryState.UNKNOWN
    }
}

/** One outbound message, before any channel-specific formatting. */
data class OutboundMessage(
    val toE164: String,
    val body: String,
    val channel: ChannelKind,
    /** The sender exactly as Twilio knows it, for example `whatsapp:+15557654321`. */
    val fromAddress: String,
    /** Public HTTPS URL Twilio posts delivery updates to. Optional but recommended. */
    val statusCallbackUrl: String? = null
)

/**
 * Refuses a message this app knows Twilio will refuse.
 *
 * Checking here costs nothing, and it keeps a malformed destination from being
 * billed or from producing an audit record with no obvious cause.
 */
object OutboundMessageValidator {

    /**
     * Twilio's Programmable Messaging limit for a single message body, on every
     * channel including WhatsApp and SMS. Longer bodies fail with error 21617.
     */
    const val MAX_BODY_CHARS = 1600

    /**
     * What Twilio recommends for SMS rather than what it allows: longer
     * messages are segmented, cost more and deliver less reliably. Advisory, so
     * it is not enforced here.
     */
    const val RECOMMENDED_SMS_BODY_CHARS = 320

    /** Empty when the message may be sent. */
    fun validate(message: OutboundMessage): List<String> {
        val problems = mutableListOf<String>()
        if (message.toE164.isBlank() || !PhoneNumberNormalizer.isE164(message.toE164)) {
            problems += "the destination \"${message.toE164}\" is not an E.164 number"
        }
        if (message.body.isBlank()) {
            problems += "the message body is empty"
        } else if (message.body.length > MAX_BODY_CHARS) {
            problems += "the body is ${message.body.length} characters, which is more than the $MAX_BODY_CHARS Twilio accepts"
        }
        if (message.fromAddress.isBlank()) {
            problems += "no sender is configured for this channel"
        }
        return problems
    }
}

/** Why an outbound message did not go out. */
enum class OutboundRejection {
    /** Refused by this app before it was sent: not E.164, empty, or too long. */
    INVALID_MESSAGE,

    /** No usable credentials, so nothing was sent. */
    NOT_CONFIGURED,

    AUTHENTICATION,
    RATE_LIMITED,
    TIMEOUT,
    NETWORK,
    SERVER_ERROR,

    /** Twilio refused the message on its merits, for example an unsupported destination. */
    REJECTED_BY_TWILIO,

    /** Twilio answered with something this app cannot read. */
    MALFORMED_RESPONSE
}

/**
 * The result of one send attempt.
 *
 * [OutboundSendResult.Rejected.detail] is already redacted by the caller: a
 * Twilio error body echoes the message body, and the body is conversation
 * content.
 */
sealed interface OutboundSendResult {

    /**
     * Twilio took the message. [state] is what it said at that moment, which is
     * a promise to try, not a delivery receipt.
     */
    data class Accepted(
        val messageSid: String,
        val state: DeliveryState,
        val rawStatus: String?
    ) : OutboundSendResult

    data class Rejected(
        val rejection: OutboundRejection,
        val detail: String,
        val retryable: Boolean,
        val statusCode: Int? = null,
        /** Twilio's own error code, when it supplied one. */
        val errorCode: Int? = null
    ) : OutboundSendResult
}

/**
 * One Twilio status callback.
 *
 * These arrive as a form-encoded POST from Twilio to the public receiver, and
 * they are the only honest source of delivery state: the response to a send
 * says Twilio accepted the message, not that the phone received it.
 */
data class TwilioStatusCallback(
    val messageSid: String?,
    val state: DeliveryState,
    /** What Twilio actually said, kept so an unrecognised status is never lost. */
    val rawStatus: String?,
    val errorCode: Int?,
    val toE164: String?,
    val fromAddress: String?
) {

    companion object {
        fun fromForm(params: Map<String, String>): TwilioStatusCallback = TwilioStatusCallback(
            messageSid = params["MessageSid"]?.trim()?.takeIf { it.isNotEmpty() },
            state = TwilioStatusMapper.stateFor(params["MessageStatus"]),
            rawStatus = params["MessageStatus"]?.trim()?.takeIf { it.isNotEmpty() },
            errorCode = params["ErrorCode"]?.trim()?.toIntOrNull(),
            toE164 = params["To"]?.trim()?.takeIf { it.isNotEmpty() }?.withoutChannelPrefix(),
            fromAddress = params["From"]?.trim()?.takeIf { it.isNotEmpty() }
        )
    }
}

/** `whatsapp:+15557654321` -> `+15557654321`; a bare number is left alone. */
fun String.withoutChannelPrefix(): String {
    val afterPrefix = substringAfter(':')
    return if (afterPrefix.isBlank()) this else afterPrefix
}

/** The Twilio address form for a channel: `whatsapp:+15557654321` or `+15557654321`. */
fun ChannelKind.addressFor(e164: String): String = when (this) {
    ChannelKind.WHATSAPP_TWILIO -> "whatsapp:$e164"
    ChannelKind.SMS_TWILIO -> e164
}
