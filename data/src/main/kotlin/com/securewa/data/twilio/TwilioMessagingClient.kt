package com.securewa.data.twilio

import com.securewa.core.http.HttpStatusClassifier
import com.securewa.core.http.HttpStatusOutcome
import com.securewa.core.messaging.OutboundMessage
import com.securewa.core.messaging.OutboundMessageValidator
import com.securewa.core.messaging.OutboundRejection
import com.securewa.core.messaging.OutboundSendResult
import com.securewa.core.messaging.TwilioStatusMapper
import com.securewa.core.messaging.addressFor
import com.securewa.core.model.ConnectionState
import com.securewa.core.model.HealthEvidence
import com.securewa.core.model.HealthSnapshot
import com.securewa.core.security.Redactor
import java.io.IOException
import java.io.InterruptedIOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** What the app asks of a messaging channel, whichever one is behind it. */
interface OutboundMessagingClient {
    suspend fun send(configurationId: String, message: OutboundMessage): OutboundSendResult

    /** An authenticated request that proves the credentials work, not a guess. */
    suspend fun checkConnection(configurationId: String): HealthSnapshot
}

/**
 * Sends WhatsApp and SMS messages through Twilio's REST API.
 *
 * Two properties matter here:
 *
 * 1. **Nothing is claimed that Twilio did not say.** Accepting a message is not
 *    delivering it. The result carries what Twilio reported at that moment, and
 *    an unrecognised status is reported as unknown rather than as success.
 * 2. **A request is validated before it is paid for.** A destination that is not
 *    E.164, an empty body or an over-long body never reaches the network.
 *
 * The retry decision for an HTTP status is not made here: it comes from
 * [HttpStatusClassifier], which is shared with the AI adapters, so the same
 * status means the same thing everywhere.
 */
class TwilioMessagingClient(
    private val http: OkHttpClient,
    private val credentials: TwilioCredentialResolver
) : OutboundMessagingClient {

    override suspend fun send(configurationId: String, message: OutboundMessage): OutboundSendResult {
        val problems = OutboundMessageValidator.validate(message)
        if (problems.isNotEmpty()) {
            return OutboundSendResult.Rejected(
                rejection = OutboundRejection.INVALID_MESSAGE,
                detail = Redactor.redactText(problems.joinToString("; ")),
                retryable = false
            )
        }

        val resolution = credentials.resolve(configurationId)
        if (resolution !is TwilioCredentialResolution.Ready) {
            return OutboundSendResult.Rejected(
                rejection = OutboundRejection.NOT_CONFIGURED,
                detail = Redactor.redactText(resolution.detail),
                retryable = false
            )
        }

        return post(resolution, message)
    }

    override suspend fun checkConnection(configurationId: String): HealthSnapshot {
        val checkedAt = System.currentTimeMillis()
        val resolution = credentials.resolve(configurationId)
        if (resolution !is TwilioCredentialResolution.Ready) {
            return HealthSnapshot(
                state = if (resolution is TwilioCredentialResolution.ConfigurationMissing) {
                    ConnectionState.UNCONFIGURED
                } else {
                    ConnectionState.DISCONNECTED
                },
                evidence = HealthEvidence.NONE,
                checkedAtEpochMillis = checkedAt,
                detail = Redactor.redactText(resolution.detail),
                consecutiveFailures = if (resolution is TwilioCredentialResolution.ConfigurationMissing) 0 else 1
            )
        }
        return fetchAccount(resolution, checkedAt)
    }

    private suspend fun post(
        resolution: TwilioCredentialResolution.Ready,
        message: OutboundMessage
    ): OutboundSendResult = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("To", message.channel.addressFor(message.toE164))
            .add("From", message.fromAddress)
            .add("Body", message.body)
            .apply {
                val callback = message.statusCallbackUrl ?: resolution.statusCallbackUrl
                if (!callback.isNullOrBlank()) add("StatusCallback", callback)
            }
            .build()

        val request = Request.Builder()
            .url(messagesUrl(resolution))
            .addHeader("Authorization", Credentials.basic(resolution.accountSid, resolution.authToken))
            .post(form)
            .build()

        try {
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()

                if (!response.isSuccessful) {
                    val verdict = HttpStatusClassifier.verdictFor(response.code)
                    val error = runCatching { JSONObject(body) }.getOrNull()
                    return@withContext OutboundSendResult.Rejected(
                        rejection = when (verdict.outcome) {
                            HttpStatusOutcome.AUTHENTICATION -> OutboundRejection.AUTHENTICATION
                            HttpStatusOutcome.RATE_LIMITED -> OutboundRejection.RATE_LIMITED
                            HttpStatusOutcome.TIMEOUT -> OutboundRejection.TIMEOUT
                            HttpStatusOutcome.SERVER_ERROR -> OutboundRejection.SERVER_ERROR
                            HttpStatusOutcome.INVALID_REQUEST,
                            HttpStatusOutcome.UNEXPECTED_STATUS -> OutboundRejection.REJECTED_BY_TWILIO
                        },
                        detail = Redactor.redactText(
                            error?.optString("message")?.takeIf { it.isNotBlank() }
                                ?: "twilio answered ${response.code}"
                        ),
                        retryable = verdict.retryable,
                        statusCode = response.code,
                        errorCode = error?.opt("code") as? Int
                    )
                }

                val payload = runCatching { JSONObject(body) }.getOrNull()
                val sid = payload?.optString("sid")?.takeIf { it.isNotBlank() }
                val status = payload?.optString("status")?.takeIf { it.isNotBlank() }
                if (sid == null) {
                    return@withContext OutboundSendResult.Rejected(
                        rejection = OutboundRejection.MALFORMED_RESPONSE,
                        detail = "twilio accepted the message but did not return a message sid",
                        retryable = false,
                        statusCode = response.code
                    )
                }

                OutboundSendResult.Accepted(
                    messageSid = sid,
                    state = TwilioStatusMapper.stateFor(status),
                    rawStatus = status
                )
            }
        } catch (timeout: InterruptedIOException) {
            OutboundSendResult.Rejected(
                rejection = OutboundRejection.TIMEOUT,
                detail = "twilio did not answer in time",
                retryable = true
            )
        } catch (network: IOException) {
            OutboundSendResult.Rejected(
                rejection = OutboundRejection.NETWORK,
                detail = Redactor.redactText(network.message),
                retryable = true
            )
        }
    }

    /**
     * An authenticated read of the account resource. This is what makes
     * "connected" true: a configuration that has never made a request that
     * Twilio answered successfully is not connected, however complete it looks.
     */
    private suspend fun fetchAccount(
        resolution: TwilioCredentialResolution.Ready,
        checkedAt: Long
    ): HealthSnapshot = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${resolution.apiBaseUrl}/Accounts/${resolution.accountSid}.json")
            .addHeader("Authorization", Credentials.basic(resolution.accountSid, resolution.authToken))
            .get()
            .build()

        try {
            http.newCall(request).execute().use { response ->
                HealthSnapshot(
                    state = if (response.isSuccessful) ConnectionState.CONNECTED else ConnectionState.DISCONNECTED,
                    evidence = HealthEvidence.AUTHENTICATED_CHECK,
                    checkedAtEpochMillis = checkedAt,
                    detail = if (response.isSuccessful) {
                        "twilio answered an authenticated request for this account"
                    } else {
                        "twilio answered ${response.code} to an authenticated request"
                    },
                    consecutiveFailures = if (response.isSuccessful) 0 else 1
                )
            }
        } catch (network: IOException) {
            HealthSnapshot(
                state = ConnectionState.DISCONNECTED,
                evidence = HealthEvidence.AUTHENTICATED_CHECK,
                checkedAtEpochMillis = checkedAt,
                detail = Redactor.redactText(network.message).ifBlank { "twilio could not be reached" },
                consecutiveFailures = 1
            )
        }
    }

    private fun messagesUrl(resolution: TwilioCredentialResolution.Ready) =
        "${resolution.apiBaseUrl}/Accounts/${resolution.accountSid}/Messages.json"
}
