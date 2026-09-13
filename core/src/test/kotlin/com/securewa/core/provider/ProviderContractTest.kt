package com.securewa.core.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderContractTest {

    private fun request(
        modelId: String = "gemini-1.5-pro",
        messages: List<AiMessage> = listOf(AiMessage(MessageRole.USER, "hello")),
        systemInstruction: String? = null,
        temperatureMilli: Int? = 700,
        maxOutputTokens: Int? = 1024
    ) = CompletionRequest(modelId, messages, systemInstruction, temperatureMilli, maxOutputTokens)

    // --- validation ----------------------------------------------------------

    @Test
    fun `a well formed request is valid`() {
        assertTrue(ProviderRequestValidator.validate(request()).isEmpty())
        assertTrue(ProviderRequestValidator.isValid(request()))
    }

    @Test
    fun `a request without a model is rejected`() {
        val problems = ProviderRequestValidator.validate(request(modelId = " "))
        assertTrue(problems.any { it.contains("model") })
    }

    @Test
    fun `a request without messages is rejected`() {
        assertTrue(ProviderRequestValidator.validate(request(messages = emptyList())).isNotEmpty())
    }

    @Test
    fun `an empty message is rejected`() {
        val problems = ProviderRequestValidator.validate(
            request(messages = listOf(AiMessage(MessageRole.USER, "  ")))
        )
        assertTrue(problems.any { it.contains("empty") })
    }

    @Test
    fun `temperature is bounded`() {
        assertTrue(ProviderRequestValidator.isValid(request(temperatureMilli = 0)))
        assertTrue(ProviderRequestValidator.isValid(request(temperatureMilli = 2_000)))
        assertFalse(ProviderRequestValidator.isValid(request(temperatureMilli = 2_001)))
        assertFalse(ProviderRequestValidator.isValid(request(temperatureMilli = -1)))
    }

    @Test
    fun `output tokens are bounded`() {
        assertTrue(ProviderRequestValidator.isValid(request(maxOutputTokens = 1)))
        assertTrue(ProviderRequestValidator.isValid(request(maxOutputTokens = 32_000)))
        assertFalse(ProviderRequestValidator.isValid(request(maxOutputTokens = 0)))
        assertFalse(ProviderRequestValidator.isValid(request(maxOutputTokens = 32_001)))
    }

    @Test
    fun `an oversized prompt is rejected`() {
        val huge = AiMessage(MessageRole.USER, "x".repeat(ProviderRequestValidator.MAX_CHARACTERS + 1))
        val problems = ProviderRequestValidator.validate(request(messages = listOf(huge)))
        assertTrue(problems.any { it.contains("characters") })
    }

    @Test
    fun `character count covers the system instruction too`() {
        val request = request(systemInstruction = "abcd", messages = listOf(AiMessage(MessageRole.USER, "12345")))
        assertEquals(9, request.characterCount())
    }

    // --- failure classification ---------------------------------------------

    @Test
    fun `authentication failures are not retryable`() {
        listOf(401, 403).forEach { status ->
            val failure = ProviderFailureClassifier.classify(status)
            assertEquals(ProviderFailureKind.AUTHENTICATION, failure.kind)
            assertFalse("retrying a rejected credential only burns quota", failure.retryable)
        }
    }

    @Test
    fun `rate limiting is retryable`() {
        val failure = ProviderFailureClassifier.classify(429)
        assertEquals(ProviderFailureKind.RATE_LIMITED, failure.kind)
        assertTrue(failure.retryable)
    }

    @Test
    fun `server errors are retryable`() {
        listOf(500, 502, 503, 504).forEach { status ->
            assertTrue(
                "a $status can succeed on a later attempt",
                ProviderFailureClassifier.classify(status).retryable
            )
        }
    }

    @Test
    fun `client errors other than auth and rate limits are not retryable`() {
        val failure = ProviderFailureClassifier.classify(400)
        assertEquals(ProviderFailureKind.INVALID_REQUEST, failure.kind)
        assertFalse(failure.retryable)
    }

    @Test
    fun `timeouts are retryable`() {
        assertEquals(ProviderFailureKind.TIMEOUT, ProviderFailureClassifier.classify(408).kind)
        assertTrue(ProviderFailureClassifier.classify(408).retryable)
    }

    @Test
    fun `an unusual client status is still classified as a client error`() {
        // 418 is a 4xx: the request is the problem, so retrying will not help.
        val failure = ProviderFailureClassifier.classify(418)
        assertEquals(ProviderFailureKind.INVALID_REQUEST, failure.kind)
        assertEquals(418, failure.statusCode)
    }

    @Test
    fun `a status outside the http families is reported with the status it was`() {
        val failure = ProviderFailureClassifier.classify(799)
        assertEquals(799, failure.statusCode)
        assertTrue(failure.message.contains("799"))
        assertFalse("an unclassifiable status must not be retried blindly", failure.retryable)
    }

    @Test
    fun `a redacted body is carried through for the log`() {
        val failure = ProviderFailureClassifier.classify(429, "quota exceeded for slot 3")
        assertEquals("quota exceeded for slot 3", failure.message)
    }

    @Test
    fun `every provider kind the router can select is supported`() {
        assertTrue(ProviderFailureClassifier.supports(com.securewa.core.routing.ProviderKind.GEMINI))
        assertTrue(ProviderFailureClassifier.supports(com.securewa.core.routing.ProviderKind.OPENAI))
        assertTrue(ProviderFailureClassifier.supports(com.securewa.core.routing.ProviderKind.ANTHROPIC))
        assertTrue(ProviderFailureClassifier.supports(com.securewa.core.routing.ProviderKind.OPENAI_COMPATIBLE))
    }
}
