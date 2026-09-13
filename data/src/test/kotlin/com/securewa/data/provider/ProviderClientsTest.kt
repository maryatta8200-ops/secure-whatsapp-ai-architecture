package com.securewa.data.provider

import com.securewa.core.provider.AiMessage
import com.securewa.core.provider.CompletionOutcome
import com.securewa.core.provider.CompletionRequest
import com.securewa.core.provider.MessageRole
import com.securewa.core.provider.ProviderFailure
import com.securewa.core.provider.ProviderFailureKind
import com.securewa.core.routing.ProviderKind
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * HTTP level tests for the provider adapters.
 *
 * These run against a real socket with a real OkHttp client, so what is asserted
 * is actual behaviour: the headers that are sent, the status handling, the
 * parsing, the timeouts and the transport failures. A test that only built a
 * request object would not tell us whether any of it works.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ProviderClientsTest {

    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient

    private val apiKey = "test-key-not-a-real-credential"
    private val request = CompletionRequest(
        modelId = "test-model",
        messages = listOf(
            AiMessage(MessageRole.USER, "What are your hours?")
        ),
        systemInstruction = "You are a clinic assistant.",
        temperatureMilli = 700,
        maxOutputTokens = 512
    )

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        http = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun endpoint(kind: ProviderKind, path: String = "") = ProviderEndpoint(
        kind = kind,
        baseUrl = server.url(path).toString().trimEnd('/'),
        apiKey = apiKey
    )

    private fun jsonResponse(body: String, status: Int = 200) = MockResponse()
        .setResponseCode(status)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    // --- OpenAI compatible ----------------------------------------------------

    @Test
    fun `openai adapter parses a successful completion`() = runBlocking {
        server.enqueue(
            jsonResponse(
                """
                {
                  "model": "test-model",
                  "choices": [{"message": {"content": "We are open 09:00 to 17:00."}, "finish_reason": "stop"}],
                  "usage": {"prompt_tokens": 21, "completion_tokens": 9}
                }
                """.trimIndent()
            )
        )

        val outcome = AiProviderClients.create(ProviderKind.OPENAI, http).complete(endpoint(ProviderKind.OPENAI), request)

        assertTrue("$outcome", outcome is CompletionOutcome.Success)
        val response = (outcome as CompletionOutcome.Success).response
        assertEquals("We are open 09:00 to 17:00.", response.text)
        assertEquals("test-model", response.modelId)
        assertEquals("stop", response.finishReason)
        assertEquals(21, response.promptTokens)
        assertEquals(9, response.completionTokens)
    }

    @Test
    fun `openai adapter authenticates with a bearer header`() = runBlocking {
        server.enqueue(jsonResponse("""{"choices":[{"message":{"content":"ok"}}]}"""))

        AiProviderClients.create(ProviderKind.OPENAI, http).complete(endpoint(ProviderKind.OPENAI), request)

        val sent = server.takeRequest()
        assertEquals("Bearer $apiKey", sent.getHeader("Authorization"))
        assertEquals("POST", sent.method)
        assertEquals("/chat/completions", sent.path)
        val body = sent.body.readUtf8()
        assertTrue(body.contains("\"model\":\"test-model\""))
        assertTrue(body.contains("\"temperature\":0.7"))
        assertTrue(body.contains("\"max_tokens\":512"))
        assertTrue(body.contains("\"role\":\"system\""))
    }

    @Test
    fun `the credential never appears in the request url`() = runBlocking {
        server.enqueue(jsonResponse("""{"choices":[{"message":{"content":"ok"}}]}"""))
        AiProviderClients.create(ProviderKind.OPENAI, http).complete(endpoint(ProviderKind.OPENAI), request)
        assertFalse(
            "a key in the URL ends up in logs and proxies",
            server.takeRequest().path!!.contains(apiKey)
        )
    }

    @Test
    fun `an openai compatible base url is respected`() = runBlocking {
        server.enqueue(jsonResponse("""{"choices":[{"message":{"content":"ok"}}]}"""))

        AiProviderClients.create(ProviderKind.OPENAI_COMPATIBLE, http)
            .complete(endpoint(ProviderKind.OPENAI_COMPATIBLE, "/api/v1"), request)

        assertEquals("/api/v1/chat/completions", server.takeRequest().path)
    }

    @Test
    fun `a rejected credential is reported as authentication and is not retried`() = runBlocking {
        server.enqueue(jsonResponse("""{"error":"invalid api key"}""", status = 401))

        val outcome = AiProviderClients.create(ProviderKind.OPENAI, http).complete(endpoint(ProviderKind.OPENAI), request)

        assertTrue(outcome is CompletionOutcome.Failure)
        val failure = (outcome as CompletionOutcome.Failure).failure
        assertEquals(ProviderFailureKind.AUTHENTICATION, failure.kind)
        assertEquals(401, failure.statusCode)
        assertFalse("retrying a rejected key only delays the user", failure.retryable)
    }

    @Test
    fun `rate limiting is reported as retryable`() = runBlocking {
        server.enqueue(jsonResponse("""{"error":"rate limit"}""", status = 429))

        val failure = (AiProviderClients.create(ProviderKind.OPENAI, http)
            .complete(endpoint(ProviderKind.OPENAI), request) as CompletionOutcome.Failure).failure

        assertEquals(ProviderFailureKind.RATE_LIMITED, failure.kind)
        assertTrue(failure.retryable)
    }

    @Test
    fun `a server error is reported as retryable`() = runBlocking {
        server.enqueue(jsonResponse("""{"error":"upstream"}""", status = 503))

        val failure = (AiProviderClients.create(ProviderKind.OPENAI, http)
            .complete(endpoint(ProviderKind.OPENAI), request) as CompletionOutcome.Failure).failure

        assertEquals(ProviderFailureKind.SERVER_ERROR, failure.kind)
        assertTrue(failure.retryable)
    }

    @Test
    fun `a malformed body is reported as a parse failure`() = runBlocking {
        server.enqueue(jsonResponse("not json at all"))

        val failure = (AiProviderClients.create(ProviderKind.OPENAI, http)
            .complete(endpoint(ProviderKind.OPENAI), request) as CompletionOutcome.Failure).failure

        assertEquals(ProviderFailureKind.PARSE, failure.kind)
        assertFalse(failure.retryable)
    }

    @Test
    fun `an empty completion is not reported as success`() = runBlocking {
        server.enqueue(jsonResponse("""{"choices":[]}"""))

        val failure = (AiProviderClients.create(ProviderKind.OPENAI, http)
            .complete(endpoint(ProviderKind.OPENAI), request) as CompletionOutcome.Failure).failure

        assertEquals(ProviderFailureKind.EMPTY_RESPONSE, failure.kind)
    }

    @Test
    fun `an invalid request is rejected before any network call is made`() = runBlocking {
        val invalid = request.copy(modelId = " ")

        val outcome = AiProviderClients.create(ProviderKind.OPENAI, http).complete(endpoint(ProviderKind.OPENAI), invalid)

        assertTrue(outcome is CompletionOutcome.Failure)
        assertEquals(
            ProviderFailureKind.INVALID_REQUEST,
            (outcome as CompletionOutcome.Failure).failure.kind
        )
        assertEquals("no request may be sent for an invalid configuration", 0, server.requestCount)
    }

    // --- Gemini ---------------------------------------------------------------

    @Test
    fun `gemini adapter parses a completion and sends the key in a header`() = runBlocking {
        server.enqueue(
            jsonResponse(
                """
                {
                  "candidates": [{"content": {"parts": [{"text": "Open until five."}]}, "finishReason": "STOP"}],
                  "usageMetadata": {"promptTokenCount": 12, "candidatesTokenCount": 5}
                }
                """.trimIndent()
            )
        )

        val outcome = AiProviderClients.create(ProviderKind.GEMINI, http).complete(endpoint(ProviderKind.GEMINI), request)

        assertTrue("$outcome", outcome is CompletionOutcome.Success)
        val response = (outcome as CompletionOutcome.Success).response
        assertEquals("Open until five.", response.text)
        assertEquals("STOP", response.finishReason)
        assertEquals(12, response.promptTokens)
        assertEquals(5, response.completionTokens)

        val sent = server.takeRequest()
        assertEquals(apiKey, sent.getHeader("x-goog-api-key"))
        assertTrue(sent.path!!.contains("/models/test-model:generateContent"))
        assertFalse(sent.path!!.contains(apiKey))
        val body = sent.body.readUtf8()
        assertTrue(body.contains("systemInstruction"))
        assertTrue(body.contains("\"role\":\"user\""))
        assertTrue(body.contains("maxOutputTokens"))
    }

    @Test
    fun `gemini empty candidates are not reported as success`() = runBlocking {
        server.enqueue(jsonResponse("""{"candidates":[]}"""))

        val failure = (AiProviderClients.create(ProviderKind.GEMINI, http)
            .complete(endpoint(ProviderKind.GEMINI), request) as CompletionOutcome.Failure).failure

        assertEquals(ProviderFailureKind.EMPTY_RESPONSE, failure.kind)
    }

    // --- Anthropic ------------------------------------------------------------

    @Test
    fun `anthropic adapter parses a completion and sends the version header`() = runBlocking {
        server.enqueue(
            jsonResponse(
                """
                {
                  "model": "test-model",
                  "content": [{"type": "text", "text": "We close at five."}],
                  "stop_reason": "end_turn",
                  "usage": {"input_tokens": 15, "output_tokens": 6}
                }
                """.trimIndent()
            )
        )

        val outcome = AiProviderClients.create(ProviderKind.ANTHROPIC, http).complete(endpoint(ProviderKind.ANTHROPIC), request)

        assertTrue("$outcome", outcome is CompletionOutcome.Success)
        val response = (outcome as CompletionOutcome.Success).response
        assertEquals("We close at five.", response.text)
        assertEquals("end_turn", response.finishReason)
        assertEquals(15, response.promptTokens)
        assertEquals(6, response.completionTokens)

        val sent = server.takeRequest()
        assertEquals(apiKey, sent.getHeader("x-api-key"))
        assertEquals("2023-06-01", sent.getHeader("anthropic-version"))
        assertEquals("/v1/messages", sent.path)
    }

    @Test
    fun `anthropic receives an output budget even when none was configured`() = runBlocking {
        server.enqueue(jsonResponse("""{"content":[{"type":"text","text":"ok"}]}"""))

        AiProviderClients.create(ProviderKind.ANTHROPIC, http)
            .complete(endpoint(ProviderKind.ANTHROPIC), request.copy(maxOutputTokens = null))

        val body = server.takeRequest().body.readUtf8()
        assertTrue("Anthropic requires max_tokens", body.contains("\"max_tokens\":1024"))
    }

    // --- transport failures ----------------------------------------------------

    /**
     * The answer is read by the transport, not by the parser, so a connection
     * that dies while the answer is still arriving is a transport failure and
     * stays retryable. Reported as a bad body it would be neither true nor
     * retryable, which is how this was first written.
     */
    @Test
    fun `an answer that stops arriving is reported as a retryable timeout and not as a bad body`() =
        runBlocking {
            val impatient = OkHttpClient.Builder()
                .connectTimeout(1, TimeUnit.SECONDS)
                .readTimeout(150, TimeUnit.MILLISECONDS)
                .build()
            server.enqueue(jsonResponse("""{"choices":[{"message":{"content":"too late"}}]}""").setBodyDelay(2, TimeUnit.SECONDS))

            val failure = (AiProviderClients.create(ProviderKind.OPENAI, impatient)
                .complete(endpoint(ProviderKind.OPENAI), request) as CompletionOutcome.Failure).failure

            assertEquals("the whole failure was $failure", ProviderFailureKind.TIMEOUT, failure.kind)
            assertTrue(failure.retryable)
        }

    /**
     * The failures below are injected with an application interceptor, which
     * OkHttp runs before its own retry layer, so the exception reaches the
     * adapter exactly as the socket produced it. They say how each family of
     * transport failure is classified; the test above says that classification
     * is reached at all when the body is what fails.
     */
    private fun clientThatFailsWith(error: Throwable) = OkHttpClient.Builder()
        .addInterceptor(Interceptor { throw error })
        .build()

    private fun failureForTransport(error: Throwable): ProviderFailure = runBlocking {
        val client = AiProviderClients.create(ProviderKind.OPENAI, clientThatFailsWith(error))
        (client.complete(endpoint(ProviderKind.OPENAI), request) as CompletionOutcome.Failure).failure
    }

    @Test
    fun `a socket read timeout is reported as a retryable timeout`() {
        val failure = failureForTransport(SocketTimeoutException("timeout"))

        assertEquals(ProviderFailureKind.TIMEOUT, failure.kind)
        assertTrue(failure.retryable)
    }

    @Test
    fun `a deadline that passed without a socket is reported as a retryable timeout`() {
        val failure = failureForTransport(InterruptedIOException("timeout"))

        assertEquals(ProviderFailureKind.TIMEOUT, failure.kind)
        assertTrue(failure.retryable)
    }

    @Test
    fun `a connection that is reset is reported as a retryable network failure`() {
        val failure = failureForTransport(SocketException("Connection reset"))

        assertEquals(ProviderFailureKind.NETWORK, failure.kind)
        assertTrue(failure.retryable)
    }

    @Test
    fun `an unexpected failure while reading is not retried and names the exception`() {
        val failure = failureForTransport(IllegalStateException("closed"))

        assertEquals(ProviderFailureKind.PARSE, failure.kind)
        assertFalse(failure.retryable)
        assertTrue(
            "the failure has to name what went wrong so it can be diagnosed: $failure",
            failure.message.contains("IllegalStateException")
        )
    }

    @Test
    fun `an unreachable provider reports a retryable network failure`() = runBlocking {
        server.shutdown()

        val failure = (AiProviderClients.create(ProviderKind.OPENAI, http)
            .complete(endpoint(ProviderKind.OPENAI), request) as CompletionOutcome.Failure).failure

        assertEquals(ProviderFailureKind.NETWORK, failure.kind)
        assertTrue(failure.retryable)
    }
}
