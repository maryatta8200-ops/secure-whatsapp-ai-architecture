package com.securewa.data.provider

import com.securewa.core.provider.AiMessage
import com.securewa.core.provider.CompletionRequest
import com.securewa.core.provider.MessageRole
import com.securewa.core.provider.ProviderFailureKind
import com.securewa.core.routing.FallbackReason
import com.securewa.core.routing.ProviderFallbackPolicy
import com.securewa.core.routing.ProviderKind
import com.securewa.core.routing.ProviderRef
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * How one turn behaves against real sockets: what gets called, in what order,
 * and what is recorded about it.
 *
 * The adapters are the real ones and MockWebServer is a real server, so a
 * fallback really does send the conversation to a second endpoint. That is the
 * behaviour being decided here, so it is not worth testing against a fake.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class AgentCompletionRunnerTest {

    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient

    private val primary = ProviderRef(ProviderKind.OPENAI, "primary-model", "slot-primary", 1)
    private val secondary = ProviderRef(ProviderKind.OPENAI, "secondary-model", "slot-secondary", 1)

    private val request = CompletionRequest(
        modelId = "ignored-the-agent-decides",
        messages = listOf(AiMessage(MessageRole.USER, "Are you open tomorrow?")),
        temperatureMilli = 300
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

    private fun runnerWhere(vararg callable: ProviderRef) = AgentCompletionRunner(
        endpoints = FixedResolver(callable.associateWith { ref ->
            EndpointResolution.Ready(
                ref,
                ProviderEndpoint(
                    kind = ref.providerKind,
                    baseUrl = server.url("/${ref.credentialSlotId}").toString().trimEnd('/'),
                    apiKey = "test-key-not-a-real-credential"
                )
            )
        }),
        clients = ProviderClientSource { kind -> AiProviderClients.create(kind, http) }
    )

    private class FixedResolver(
        private val ready: Map<ProviderRef, EndpointResolution>
    ) : EndpointResolver {
        override suspend fun resolve(ref: ProviderRef): EndpointResolution =
            ready[ref] ?: EndpointResolution.SlotMissing(ref)
    }

    private fun jsonAnswer(text: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody("""{"choices":[{"message":{"content":"$text"},"finish_reason":"stop"}],"model":"m"}""")

    private fun status(code: Int) = MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json")
        .setBody("""{"error":{"message":"provider said no"}}""")

    private fun allowFallback(vararg reasons: FallbackReason) = ProviderFallbackPolicy(
        enabled = true,
        secondary = secondary,
        allowedReasons = reasons.toSet()
    )

    // --- one provider ---------------------------------------------------------

    @Test
    fun `the primary provider answers and one attempt is recorded`() = runBlocking {
        server.enqueue(jsonAnswer("We open at nine."))

        val run = runnerWhere(primary).run(primary, ProviderFallbackPolicy(), request)

        val answered = run as CompletionRun.Answered
        assertEquals("We open at nine.", answered.response.text)
        assertEquals(1, answered.attempts.size)
        assertEquals(primary, answered.attempts.single().provider)
        assertFalse(answered.attempts.single().isFallbackAttempt)
        assertEquals("/slot-primary/chat/completions", server.takeRequest().path)
    }

    // --- fallback --------------------------------------------------------------

    @Test
    fun `a rate limited primary falls back when the agent allowed it, and says why`() = runBlocking {
        server.enqueue(status(429))
        server.enqueue(jsonAnswer("We open at nine."))

        val run = runnerWhere(primary, secondary)
            .run(primary, allowFallback(FallbackReason.PROVIDER_RATE_LIMITED), request)

        val answered = run as CompletionRun.Answered
        assertEquals("We open at nine.", answered.response.text)
        assertEquals(2, answered.attempts.size)

        val first = answered.attempts[0]
        assertEquals(primary, first.provider)
        assertEquals(ProviderFailureKind.RATE_LIMITED, first.failure?.kind)
        assertEquals(FallbackReason.PROVIDER_RATE_LIMITED, first.fallbackReason)

        val second = answered.attempts[1]
        assertEquals(secondary, second.provider)
        assertTrue(second.isFallbackAttempt)
        assertEquals(null, second.failure)

        assertEquals("/slot-primary/chat/completions", server.takeRequest().path)
        assertEquals("/slot-secondary/chat/completions", server.takeRequest().path)
    }

    @Test
    fun `the conversation stays with the primary when the agent did not allow that reason`() = runBlocking {
        server.enqueue(status(429))

        val run = runnerWhere(primary, secondary)
            .run(primary, allowFallback(FallbackReason.PROVIDER_TIMEOUT), request)

        val failed = run as CompletionRun.Failed
        assertEquals(ProviderFailureKind.RATE_LIMITED, failed.failure.kind)
        assertEquals(1, failed.attempts.size)
        assertEquals(null, failed.attempts.single().fallbackReason)
        assertEquals("only the primary may have been called", 1, server.requestCount)
    }

    @Test
    fun `a device that cannot reach a provider does not move the conversation elsewhere`() = runBlocking {
        server.shutdown()

        val run = runnerWhere(primary, secondary)
            .run(primary, allowFallback(*FallbackReason.entries.toTypedArray()), request)

        val failed = run as CompletionRun.Failed
        assertEquals(ProviderFailureKind.NETWORK, failed.failure.kind)
        assertEquals(1, failed.attempts.size)
    }

    // --- providers that are never called ----------------------------------------

    @Test
    fun `a provider with no credential is reported without contacting anyone`() = runBlocking {
        val run = runnerWhere().run(primary, ProviderFallbackPolicy(), request)

        val failed = run as CompletionRun.Failed
        assertEquals(ProviderFailureKind.UNAVAILABLE, failed.failure.kind)
        assertEquals(1, failed.attempts.size)
        assertNotNull(failed.attempts.single().notCalledReason)
        assertEquals("nothing may be sent when there is no credential", 0, server.requestCount)
    }

    @Test
    fun `a fallback that cannot be called keeps the original failure and records both attempts`() =
        runBlocking {
            server.enqueue(status(429))

            val run = runnerWhere(primary)
                .run(primary, allowFallback(FallbackReason.PROVIDER_RATE_LIMITED), request)

            val failed = run as CompletionRun.Failed
            assertEquals("the failure the user saw is the one from the provider", ProviderFailureKind.RATE_LIMITED, failed.failure.kind)
            assertEquals(2, failed.attempts.size)
            assertEquals(FallbackReason.PROVIDER_RATE_LIMITED, failed.attempts[0].fallbackReason)
            assertTrue(failed.attempts[1].isFallbackAttempt)
            assertNotNull("why the secondary was not called must be recorded", failed.attempts[1].notCalledReason)
            assertEquals("the secondary was never called", 1, server.requestCount)
        }
}
