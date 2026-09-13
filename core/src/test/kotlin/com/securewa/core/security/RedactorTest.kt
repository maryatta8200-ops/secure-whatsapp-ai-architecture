package com.securewa.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactorTest {

    @Test
    fun `phone numbers are masked but stay recognisable`() {
        // Country calling code and the last two digits survive; everything
        // between them is replaced, which is what makes a log entry usable
        // without exposing the number.
        assertEquals("+923XXXXXXX67", Redactor.maskNumber("+923001234567"))
        assertTrue(Redactor.maskNumber("+923001234567").contains("XXXXXXX"))
    }

    @Test
    fun `short numbers are fully masked`() {
        assertEquals("+XXX", Redactor.maskNumber("+123"))
    }

    @Test
    fun `phone numbers inside text are masked`() {
        val redacted = Redactor.redactText("message from +923001234567 received")
        assertFalse("no full number may survive: $redacted", redacted.contains("923001234567"))
        assertTrue(redacted.contains("+92"))
    }

    @Test
    fun `provider api keys are removed from text`() {
        assertFalse(Redactor.redactText("key sk-abcdefghijklmnopqrstuvwxyz1234 sent").contains("sk-abcdef"))
        assertFalse(Redactor.redactText("key sk-ant-abcdefghijklmnopqrstuvwxyz1234 sent").contains("sk-ant-abcdef"))
        assertFalse(Redactor.redactText("key AIzaSyD-abcdefghijklmnopqrstuvwxyz1234 sent").contains("AIzaSyD-"))
        assertFalse(Redactor.redactText("Authorization: Bearer abcdefgh12345678").contains("abcdefgh12345678"))
    }

    @Test
    fun `email addresses are removed from text`() {
        assertFalse(Redactor.redactText("from doctor.clinic@example.com").contains("doctor.clinic@example.com"))
    }

    @Test
    fun `sensitive fields are redacted by name even when the value looks harmless`() {
        val redacted = Redactor.redactFields(
            mapOf(
                "AuthToken" to "unknown-value",
                "X-Twilio-Signature" to "abc",
                "api_key" to "abc",
                "Body" to "hello"
            )
        )
        assertEquals(Redactor.marker(), redacted["AuthToken"])
        assertEquals(Redactor.marker(), redacted["X-Twilio-Signature"])
        assertEquals(Redactor.marker(), redacted["api_key"])
        assertEquals("hello", redacted["Body"])
    }

    @Test
    fun `message identifiers survive redaction`() {
        // A Twilio SID is an identifier, not a secret, and it is the only way
        // to trace a delivery through the logs.
        val sid = "SM00000000000000000000000000000001"
        assertEquals(sid, Redactor.redactText(sid))
        assertEquals(sid, Redactor.redactFields(mapOf("MessageSid" to sid))["MessageSid"])
    }

    @Test
    fun `a bare thirty two character hex secret is redacted`() {
        val token = "0123456789abcdef0123456789abcdef"
        assertFalse(Redactor.redactText("token=$token").contains(token))
    }

    @Test
    fun `a log line built from webhook fields contains no usable secret`() {
        val line = Redactor.redactFields(
            mapOf(
                "MessageSid" to "SM00000000000000000000000000000001",
                "AuthToken" to "0123456789abcdef0123456789abcdef",
                "From" to "whatsapp:+923001234567"
            )
        ).entries.joinToString(" ") { (key, value) -> "$key=$value" }

        assertFalse(line.contains("0123456789abcdef0123456789abcdef"))
        assertFalse(line.contains("+923001234567"))
        assertTrue("message identifiers stay visible for troubleshooting: $line", line.contains("SM00000000000000000000000000000001"))
    }

    @Test
    fun `null input is handled`() {
        assertEquals("", Redactor.redactText(null))
    }
}
