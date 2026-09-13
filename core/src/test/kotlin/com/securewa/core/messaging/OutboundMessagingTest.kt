package com.securewa.core.messaging

import com.securewa.core.routing.ChannelKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutboundMessagingTest {

    private fun message(
        to: String = "+923001234567",
        body: String = "Your appointment is confirmed.",
        channel: ChannelKind = ChannelKind.WHATSAPP_TWILIO,
        from: String = "whatsapp:+15557654321"
    ) = OutboundMessage(toE164 = to, body = body, channel = channel, fromAddress = from)

    // --- validation ------------------------------------------------------------

    @Test
    fun `a well formed message has no problems`() {
        assertTrue(OutboundMessageValidator.validate(message()).isEmpty())
    }

    @Test
    fun `a destination that is not e164 is refused`() {
        val problems = OutboundMessageValidator.validate(message(to = "0300 1234567"))

        assertEquals(1, problems.size)
        assertTrue(problems.single().contains("E.164"))
    }

    @Test
    fun `an empty body is refused`() {
        val problems = OutboundMessageValidator.validate(message(body = "   "))

        assertTrue(problems.any { it.contains("empty") })
    }

    @Test
    fun `a body longer than twilio accepts is refused`() {
        val tooLong = "x".repeat(OutboundMessageValidator.MAX_BODY_CHARS + 1)

        val problems = OutboundMessageValidator.validate(message(body = tooLong))

        assertTrue(problems.single().contains("1600"))
    }

    @Test
    fun `the longest body twilio accepts is allowed`() {
        val longest = "x".repeat(OutboundMessageValidator.MAX_BODY_CHARS)

        assertTrue(OutboundMessageValidator.validate(message(body = longest)).isEmpty())
    }

    @Test
    fun `a channel with no sender is refused`() {
        assertTrue(OutboundMessageValidator.validate(message(from = "")).isNotEmpty())
    }

    // --- status mapping ----------------------------------------------------------

    @Test
    fun `twilio's delivery states are understood`() {
        assertEquals(DeliveryState.QUEUED, TwilioStatusMapper.stateFor("queued"))
        assertEquals(DeliveryState.SENT, TwilioStatusMapper.stateFor("sent"))
        assertEquals(DeliveryState.DELIVERED, TwilioStatusMapper.stateFor("delivered"))
        assertEquals(DeliveryState.READ, TwilioStatusMapper.stateFor("read"))
        assertEquals(DeliveryState.UNDELIVERED, TwilioStatusMapper.stateFor("undelivered"))
        assertEquals(DeliveryState.FAILED, TwilioStatusMapper.stateFor("failed"))
    }

    @Test
    fun `a status this build does not know is neither success nor failure`() {
        val state = TwilioStatusMapper.stateFor("something_new")

        assertEquals(DeliveryState.UNKNOWN, state)
        assertFalse("an unknown status must not be reported as delivered", state.succeeded)
        assertFalse("an unknown status must not end the message's life", state.isFinal)
    }

    @Test
    fun `a missing status is not invented`() {
        assertEquals(DeliveryState.UNKNOWN, TwilioStatusMapper.stateFor(null))
        assertEquals(DeliveryState.UNKNOWN, TwilioStatusMapper.stateFor(""))
    }

    @Test
    fun `mapping is not case or space sensitive`() {
        assertEquals(DeliveryState.DELIVERED, TwilioStatusMapper.stateFor("  Delivered "))
    }

    // --- status callbacks --------------------------------------------------------

    @Test
    fun `a status callback is read from twilio's form fields`() {
        val callback = TwilioStatusCallback.fromForm(
            mapOf(
                "MessageSid" to "SM00000000000000000000000000000001",
                "MessageStatus" to "delivered",
                "To" to "whatsapp:+923001234567",
                "From" to "whatsapp:+15557654321"
            )
        )

        assertEquals("SM00000000000000000000000000000001", callback.messageSid)
        assertEquals(DeliveryState.DELIVERED, callback.state)
        assertEquals("+923001234567", callback.toE164)
        assertEquals("whatsapp:+15557654321", callback.fromAddress)
    }

    @Test
    fun `a callback carrying an error code keeps it`() {
        val callback = TwilioStatusCallback.fromForm(
            mapOf("MessageSid" to "SM1", "MessageStatus" to "failed", "ErrorCode" to "63016")
        )

        assertEquals(DeliveryState.FAILED, callback.state)
        assertEquals(63016, callback.errorCode)
    }

    @Test
    fun `an unknown status in a callback is kept alongside the raw value`() {
        val callback = TwilioStatusCallback.fromForm(
            mapOf("MessageSid" to "SM1", "MessageStatus" to "scheduled_by_meta")
        )

        assertEquals(DeliveryState.UNKNOWN, callback.state)
        assertEquals("scheduled_by_meta", callback.rawStatus)
    }

    // --- addressing ----------------------------------------------------------------

    @Test
    fun `whatsapp addresses carry their channel prefix`() {
        assertEquals("whatsapp:+923001234567", ChannelKind.WHATSAPP_TWILIO.addressFor("+923001234567"))
        assertEquals("+923001234567", ChannelKind.SMS_TWILIO.addressFor("+923001234567"))
    }

    @Test
    fun `the channel prefix can be taken back off`() {
        assertEquals("+923001234567", "whatsapp:+923001234567".withoutChannelPrefix())
        assertEquals("+923001234567", "+923001234567".withoutChannelPrefix())
    }
}
