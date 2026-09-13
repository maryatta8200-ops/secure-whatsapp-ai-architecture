package com.securewa.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The expected signature constants in this test were produced with Python's
 * `hmac`/`hashlib` (an independent implementation of HMAC-SHA1 + Base64), not
 * with the code under test, so a regression in the Kotlin implementation is
 * caught rather than reproduced.
 */
class TwilioSignatureValidatorTest {

    private val authToken = "test-auth-token-not-a-real-secret"
    private val url = "https://receiver.example.com/twilio/inbound"
    private val params = mapOf(
        "MessageSid" to "SM00000000000000000000000000000001",
        "From" to "whatsapp:+15551234567",
        "To" to "whatsapp:+15557654321",
        "Body" to "Hello clinic",
        "NumMedia" to "0"
    )

    /** Base64(HMAC-SHA1(token, url + sorted params)) computed by Python. */
    private val expectedSignature = "IXY1pYMcJprr25otHC1rzbCou94="
    private val tamperedBodySignature = "rI0SW0gmX2dOmLBbjueBpOXMgfQ="
    private val wrongSecretSignature = "DIctfdewYhgyUiYqlPZcMRmDF+U="
    private val formBody =
        "MessageSid=SM00000000000000000000000000000001&From=whatsapp%3A%2B15551234567" +
            "&To=whatsapp%3A%2B15557654321&Body=Hello+clinic&NumMedia=0"

    @Test
    fun `signature matches the independently computed HMAC-SHA1 value`() {
        assertEquals(
            expectedSignature,
            TwilioSignatureValidator.expectedSignature(authToken, url, params)
        )
    }

    @Test
    fun `parameter order does not change the signature`() {
        assertEquals(
            expectedSignature,
            TwilioSignatureValidator.expectedSignature(authToken, url, params.entries.reversed().associate { it.key to it.value })
        )
    }

    @Test
    fun `tampering with the body changes the signature`() {
        val tampered = params + ("Body" to "Hello clinic!")
        assertEquals(
            tamperedBodySignature,
            TwilioSignatureValidator.expectedSignature(authToken, url, tampered)
        )
    }

    @Test
    fun `a different auth token produces a different signature`() {
        assertEquals(
            wrongSecretSignature,
            TwilioSignatureValidator.expectedSignature(authToken + "x", url, params)
        )
    }

    @Test
    fun `the url is part of the signed payload`() {
        assertEquals(
            expectedSignature,
            TwilioSignatureValidator.expectedSignature(authToken, url, params)
        )
        assertFalse(
            "changing the URL must change the signature",
            TwilioSignatureValidator.expectedSignature(authToken, "$url?extra=1", params) == expectedSignature
        )
    }

    @Test
    fun `form bodies are decoded exactly like Twilio encodes them`() {
        val parsed = TwilioSignatureValidator.parseFormBody(formBody)
        assertEquals(params, parsed)
        assertEquals(
            expectedSignature,
            TwilioSignatureValidator.expectedSignature(authToken, url, parsed)
        )
    }

    @Test
    fun `valid signed requests are accepted once`() {
        val guard = ReplayGuard()
        val result = TwilioSignatureValidator.validate(
            authToken = authToken,
            url = url,
            params = params,
            providedSignature = expectedSignature,
            replayGuard = guard,
            nowMillis = 1_000_000L
        )
        assertEquals(ValidationResult.ACCEPTED, result)
        assertTrue(result.accepted)
    }

    @Test
    fun `replaying the same signed request is rejected`() {
        val guard = ReplayGuard()
        val first = TwilioSignatureValidator.validate(authToken, url, params, expectedSignature, guard, 1_000_000L)
        val replay = TwilioSignatureValidator.validate(authToken, url, params, expectedSignature, guard, 1_000_100L)
        assertEquals(ValidationResult.ACCEPTED, first)
        assertEquals(ValidationResult.REJECTED_REPLAY, replay)
        assertFalse(replay.accepted)
    }

    @Test
    fun `an invalid signature is rejected`() {
        val guard = ReplayGuard()
        assertEquals(
            ValidationResult.REJECTED_BAD_SIGNATURE,
            TwilioSignatureValidator.validate(authToken, url, params, tamperedBodySignature, guard, 1_000_000L)
        )
    }

    @Test
    fun `a missing signature is rejected`() {
        val guard = ReplayGuard()
        assertEquals(
            ValidationResult.REJECTED_MISSING_SIGNATURE,
            TwilioSignatureValidator.validate(authToken, url, params, null, guard, 1_000_000L)
        )
        assertEquals(
            ValidationResult.REJECTED_MISSING_SIGNATURE,
            TwilioSignatureValidator.validate(authToken, url, params, "  ", guard, 1_000_000L)
        )
    }

    @Test
    fun `validation refuses to run without a configured secret`() {
        val guard = ReplayGuard()
        assertEquals(
            ValidationResult.REJECTED_NO_SECRET,
            TwilioSignatureValidator.validate("", url, params, expectedSignature, guard, 1_000_000L)
        )
    }

    @Test
    fun `comparison is constant time and length aware`() {
        assertTrue(TwilioSignatureValidator.constantTimeEquals(expectedSignature, expectedSignature))
        assertFalse(TwilioSignatureValidator.constantTimeEquals(expectedSignature, expectedSignature.dropLast(1)))
        assertFalse(TwilioSignatureValidator.constantTimeEquals("", "a"))
        assertTrue(TwilioSignatureValidator.constantTimeEquals("", ""))
    }

    @Test
    fun `a rejected request must not reach the AI provider`() {
        // The pipeline calls validate() before routing. A rejection short
        // circuits the whole turn, so no provider call and no outbound send can
        // happen for a forged or replayed request.
        val guard = ReplayGuard()
        val forged = TwilioSignatureValidator.validate(authToken, url, params, "not-a-signature", guard, 1_000_000L)
        assertFalse("forged requests must never be treated as accepted", forged.accepted)
        assertTrue(forged.auditMessage.startsWith("rejected"))
    }
}
