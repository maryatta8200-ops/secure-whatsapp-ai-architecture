package com.securewa.core.messaging

import com.securewa.core.routing.ChannelKind
import com.securewa.core.routing.ProviderKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdempotencyKeysTest {

    private val key1 = IdempotencyKeys.inbound(
        ChannelKind.WHATSAPP_TWILIO,
        "+15557654321",
        "SM00000000000000000000000000000001"
    )

    @Test
    fun `the same message always produces the same key`() {
        val again = IdempotencyKeys.inbound(
            ChannelKind.WHATSAPP_TWILIO,
            "+15557654321",
            "SM00000000000000000000000000000001"
        )
        assertEquals(key1, again)
        assertEquals(64, key1.length)
    }

    @Test
    fun `different messages produce different keys`() {
        val otherMessage = IdempotencyKeys.inbound(
            ChannelKind.WHATSAPP_TWILIO,
            "+15557654321",
            "SM00000000000000000000000000000002"
        )
        val otherDestination = IdempotencyKeys.inbound(
            ChannelKind.WHATSAPP_TWILIO,
            "+15551000001",
            "SM00000000000000000000000000000001"
        )
        val otherChannel = IdempotencyKeys.inbound(
            ChannelKind.SMS_TWILIO,
            "+15557654321",
            "SM00000000000000000000000000000001"
        )
        assertTrue(key1 != otherMessage)
        assertTrue(key1 != otherDestination)
        assertTrue(key1 != otherChannel)
    }

    @Test
    fun `a retry of the same provider call reuses one key`() {
        val first = IdempotencyKeys.outbound(ProviderKind.GEMINI, "gemini-1.5-pro", "corr-1", attempt = 1)
        val repeated = IdempotencyKeys.outbound(ProviderKind.GEMINI, "gemini-1.5-pro", "corr-1", attempt = 1)
        val nextAttempt = IdempotencyKeys.outbound(ProviderKind.GEMINI, "gemini-1.5-pro", "corr-1", attempt = 2)
        assertEquals(first, repeated)
        assertTrue(first != nextAttempt)
    }

    @Test
    fun `outbound send keys are scoped to the reply they carry`() {
        val a = IdempotencyKeys.twilioSend("corr-1", "digest-a")
        val b = IdempotencyKeys.twilioSend("corr-1", "digest-b")
        assertTrue(a != b)
        assertEquals(a, IdempotencyKeys.twilioSend("corr-1", "digest-a"))
    }
}
