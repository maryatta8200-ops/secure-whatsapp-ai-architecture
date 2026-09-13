package com.securewa.data.twilio

import com.securewa.core.messaging.DeliveryState
import com.securewa.core.messaging.OutboundMessage
import com.securewa.core.messaging.OutboundRejection
import com.securewa.core.messaging.OutboundSendResult
import com.securewa.core.model.ConnectionState
import com.securewa.core.model.HealthEvidence
import com.securewa.core.routing.ChannelKind
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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
 * The Twilio client against a real socket.
 *
 * What is asserted is the request Twilio would receive — path, form fields and
 * the basic auth header — and what the client claims about the answer. An
 * unrecognised delivery status being reported as unknown rather than as success
 * is the sort of thing that only shows up if you actually run it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class TwilioMessagingClientTest {

    private val accountSid = "ACtestaccountsid0000000000000000000"
    private val authToken = "test-auth-token-not-a-real-credential"
    private val configurationId = "twilio-config-1"

    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient

    private val message = OutboundMessage(
        toE164 = "+923001234567",
        body = "Your appointment is at nine.",
        channel = ChannelKind.WHATSAPP_TWILIO,
        fromAddress = "whatsapp:+15557654321"
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

    private fun clientFor(resolution: TwilioCredentialResolution) = TwilioMessagingClient(
        http = http,
        credentials = FixedCredentials(resolution)
    )

    private fun ready(baseUrlOverride: String? = null) = TwilioCredentialResolution.Ready(
        configurationId = configurationId,
        accountSid = accountSid,
        authToken = authToken,
        apiBaseUrl = baseUrlOverride ?: server.url("/2010-04-01").toString().trimEnd('/'),
        statusCallbackUrl = null
    )

    private class FixedCredentials(
        private val resolution: TwilioCredentialResolution
    ) : TwilioCredentialResolver {
        override suspend fun resolve(configurationId: String): TwilioCredentialResolution = resolution
    }

    private fun json(status: Int, body: String) = MockResponse()
        .setResponseCode(status)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    // --- sending ---------------------------------------------------------------

    @Test
    fun `a message is sent to the account's messages endpoint with basic auth`() = runBlocking {
        server.enqueue(json(201, """{"sid":"SM00000000000000000000000000000001","status":"queued"}"""))

        val result = clientFor(ready()).send(configurationId, message)

        val accepted = result as OutboundSendResult.Accepted
        assertEquals("SM00000000000000000000000000000001", accepted.messageSid)
        assertEquals(DeliveryState.QUEUED, accepted.state)

        val sent = server.takeRequest()
        assertEquals("POST", sent.method)
        assertEquals("/2010-04-01/Accounts/$accountSid/Messages.json", sent.path)
        assertEquals(Credentials.basic(accountSid, authToken), sent.getHeader("Authorization"))

        val form = formOf(sent)
        assertEquals("whatsapp:+923001234567", form["To"])
        assertEquals("whatsapp:+15557654321", form["From"])
        assertEquals("Your appointment is at nine.", form["Body"])
    }

    @Test
    fun `the status callback url is passed when one is configured`() = runBlocking {
        server.enqueue(json(201, """{"sid":"SM1","status":"queued"}"""))

        val resolution = ready().copy(statusCallbackUrl = "https://receiver.example.com/twilio/status")
        clientFor(resolution).send(configurationId, message)

        val form = formOf(server.takeRequest())
        assertEquals("https://receiver.example.com/twilio/status", form["StatusCallback"])
    }

    @Test
    fun `a status this build does not know is reported as unknown, not as success`() = runBlocking {
        server.enqueue(json(201, """{"sid":"SM1","status":"something_new"}"""))

        val accepted = clientFor(ready()).send(configurationId, message) as OutboundSendResult.Accepted

        assertEquals(DeliveryState.UNKNOWN, accepted.state)
        assertEquals("something_new", accepted.rawStatus)
        assertFalse("an unknown status is not delivered", accepted.state.succeeded)
    }

    @Test
    fun `a destination that is not e164 is refused without contacting twilio`() = runBlocking {
        val result = clientFor(ready()).send(configurationId, message.copy(toE164 = "0300 1234567"))

        val rejected = result as OutboundSendResult.Rejected
        assertEquals(OutboundRejection.INVALID_MESSAGE, rejected.rejection)
        assertFalse(rejected.retryable)
        assertEquals("nothing may be sent to an address that is not E.164", 0, server.requestCount)
    }

    @Test
    fun `a body twilio would reject is refused before it is billed`() = runBlocking {
        val tooLong = "x".repeat(1601)

        val rejected = clientFor(ready()).send(configurationId, message.copy(body = tooLong))
            as OutboundSendResult.Rejected

        assertEquals(OutboundRejection.INVALID_MESSAGE, rejected.rejection)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `nothing is sent when the credentials cannot be used`() = runBlocking {
        val rejected = clientFor(TwilioCredentialResolution.VaultLocked(configurationId))
            .send(configurationId, message) as OutboundSendResult.Rejected

        assertEquals(OutboundRejection.NOT_CONFIGURED, rejected.rejection)
        assertTrue(rejected.detail.contains("vault is locked"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a refused credential is not retried`() = runBlocking {
        server.enqueue(json(401, """{"code":20003,"message":"Authenticate"}"""))

        val rejected = clientFor(ready()).send(configurationId, message) as OutboundSendResult.Rejected

        assertEquals(OutboundRejection.AUTHENTICATION, rejected.rejection)
        assertFalse(rejected.retryable)
        assertEquals(401, rejected.statusCode)
        assertEquals(20003, rejected.errorCode)
        assertEquals("Authenticate", rejected.detail)
    }

    @Test
    fun `being rate limited is worth another attempt later`() = runBlocking {
        server.enqueue(json(429, """{"code":20429,"message":"Too Many Requests"}"""))

        val rejected = clientFor(ready()).send(configurationId, message) as OutboundSendResult.Rejected

        assertEquals(OutboundRejection.RATE_LIMITED, rejected.rejection)
        assertTrue(rejected.retryable)
    }

    @Test
    fun `a message twilio refused on its merits is not retried unchanged`() = runBlocking {
        server.enqueue(json(400, """{"code":63016,"message":"WhatsApp cannot send a free-form message outside the window"}"""))

        val rejected = clientFor(ready()).send(configurationId, message) as OutboundSendResult.Rejected

        assertEquals(OutboundRejection.REJECTED_BY_TWILIO, rejected.rejection)
        assertFalse(rejected.retryable)
        assertEquals(63016, rejected.errorCode)
    }

    @Test
    fun `an answer with no message sid is not reported as accepted`() = runBlocking {
        server.enqueue(json(201, """{"status":"queued"}"""))

        val rejected = clientFor(ready()).send(configurationId, message) as OutboundSendResult.Rejected

        assertEquals(OutboundRejection.MALFORMED_RESPONSE, rejected.rejection)
    }

    @Test
    fun `twilio not answering is a retryable timeout`() = runBlocking {
        server.enqueue(json(201, """{"sid":"SM1"}""").setBodyDelay(2, TimeUnit.SECONDS))
        val impatient = OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(150, TimeUnit.MILLISECONDS)
            .build()

        val result = TwilioMessagingClient(impatient, FixedCredentials(ready()))
            .send(configurationId, message)

        val rejected = result as OutboundSendResult.Rejected
        assertEquals(OutboundRejection.TIMEOUT, rejected.rejection)
        assertTrue(rejected.retryable)
    }

    // --- connection checks -------------------------------------------------------

    @Test
    fun `an authenticated request twilio answers makes the account connected`() = runBlocking {
        server.enqueue(json(200, """{"sid":"$accountSid","friendly_name":"test"}"""))

        val health = clientFor(ready()).checkConnection(configurationId)

        assertEquals(ConnectionState.CONNECTED, health.state)
        assertEquals(HealthEvidence.AUTHENTICATED_CHECK, health.evidence)
        assertEquals("/2010-04-01/Accounts/$accountSid.json", server.takeRequest().path)
    }

    @Test
    fun `credentials twilio rejects leave the account disconnected`() = runBlocking {
        server.enqueue(json(401, """{"code":20003,"message":"Authenticate"}"""))

        val health = clientFor(ready()).checkConnection(configurationId)

        assertEquals(ConnectionState.DISCONNECTED, health.state)
        assertEquals("the evidence is still an authenticated check that failed",
            HealthEvidence.AUTHENTICATED_CHECK, health.evidence)
        assertEquals(1, health.consecutiveFailures)
    }

    @Test
    fun `a configuration that has never been exercised is not connected`() = runBlocking {
        val health = clientFor(TwilioCredentialResolution.ConfigurationMissing(configurationId))
            .checkConnection(configurationId)

        assertEquals(ConnectionState.UNCONFIGURED, health.state)
        assertEquals(HealthEvidence.NONE, health.evidence)
        assertEquals("no request may be made without credentials", 0, server.requestCount)
    }

    @Test
    fun `a device that cannot reach twilio is disconnected, not unknown`() = runBlocking {
        server.shutdown()

        val health = clientFor(ready()).checkConnection(configurationId)

        assertEquals(ConnectionState.DISCONNECTED, health.state)
        assertEquals(1, health.consecutiveFailures)
    }

    private fun formOf(request: RecordedRequest): Map<String, String> =
        request.body.readUtf8().split("&").mapNotNull { part ->
            val pieces = part.split("=", limit = 2)
            if (pieces.size == 2) {
                java.net.URLDecoder.decode(pieces[0], "UTF-8") to
                    java.net.URLDecoder.decode(pieces[1], "UTF-8")
            } else {
                null
            }
        }.toMap()
}
