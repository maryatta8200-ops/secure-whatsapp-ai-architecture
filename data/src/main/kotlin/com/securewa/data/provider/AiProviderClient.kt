package com.securewa.data.provider

import com.securewa.core.provider.CompletionOutcome
import com.securewa.core.provider.CompletionRequest
import com.securewa.core.provider.ProviderFailure
import com.securewa.core.provider.ProviderFailureKind
import com.securewa.core.provider.ProviderRequestValidator
import com.securewa.core.routing.ProviderKind
import com.securewa.core.security.Redactor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException

/**
 * One configured AI endpoint. The key is held in memory only; it comes from the
 * credential vault and is never persisted here.
 */
data class ProviderEndpoint(
    val kind: ProviderKind,
    val baseUrl: String,
    val apiKey: String
)

/** Every AI adapter implements this. The pipeline only ever sees this interface. */
interface AiProviderClient {
    suspend fun complete(endpoint: ProviderEndpoint, request: CompletionRequest): CompletionOutcome
}

/** Selects the adapter for a provider kind. Adding a provider means adding one branch here. */
object AiProviderClients {
    fun create(kind: ProviderKind, http: OkHttpClient): AiProviderClient = when (kind) {
        ProviderKind.OPENAI,
        ProviderKind.OPENAI_COMPATIBLE -> OpenAiCompatibleClient(http)
        ProviderKind.GEMINI -> GeminiClient(http)
        ProviderKind.ANTHROPIC -> AnthropicClient(http)
    }
}

/**
 * Shared behaviour for the HTTP adapters: validate first, classify failures once,
 * never let an exception escape.
 *
 * Validation happens before the network call so an invalid configuration cannot
 * cost a round trip, and every failure path returns a [ProviderFailure] rather
 * than throwing, because the caller has to be able to record why a turn failed
 * and decide about fallback.
 */
abstract class HttpProviderClient(protected val http: OkHttpClient) : AiProviderClient {

    override suspend fun complete(
        endpoint: ProviderEndpoint,
        request: CompletionRequest
    ): CompletionOutcome {
        val problems = ProviderRequestValidator.validate(request)
        if (problems.isNotEmpty()) {
            return CompletionOutcome.Failure(
                ProviderFailure(
                    kind = ProviderFailureKind.INVALID_REQUEST,
                    message = problems.joinToString("; "),
                    retryable = false
                )
            )
        }
        return withContext(Dispatchers.IO) { call(endpoint, request) }
    }

    protected abstract suspend fun call(
        endpoint: ProviderEndpoint,
        request: CompletionRequest
    ): CompletionOutcome

    protected fun jsonPost(url: String, apiKeyHeader: Pair<String, String>?, body: String, extraHeaders: Map<String, String> = emptyMap()): Request =
        Request.Builder()
            .url(url)
            .apply {
                apiKeyHeader?.let { (name, value) -> addHeader(name, value) }
                extraHeaders.forEach { (name, value) -> addHeader(name, value) }
            }
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

    /**
     * Runs a call and turns transport problems into failures, handing the parsed
     * [parse] the answer as text.
     *
     * The whole exchange happens here, reading the body included. Reading the
     * body inside the parser would let the parser's own error handling swallow a
     * connection that died half way through the answer and report it as a body
     * the provider never sent: untrue, and not retryable when the truth is that
     * a retry could well succeed.
     *
     * The answer is bounded. A provider that starts streaming far more than an
     * agent can use is stopped rather than read to the end.
     *
     * A deadline that passed is retryable, a rejected credential is not: that
     * distinction is what keeps the retry policy from hammering an endpoint that
     * will keep refusing.
     */
    protected fun perform(execRequest: Request, parse: (String) -> CompletionOutcome): CompletionOutcome =
        try {
            http.newCall(execRequest).execute().use { response ->
                val failure = failureFor(response)
                if (failure != null) return CompletionOutcome.Failure(failure)

                val body = response.body
                    ?: return parseFailure(ProviderFailureKind.EMPTY_RESPONSE, "the provider sent no body")

                val declaredBytes = body.contentLength()
                if (declaredBytes > MAX_RESPONSE_BYTES) {
                    return parseFailure(
                        ProviderFailureKind.PARSE,
                        "the provider declared a body of $declaredBytes bytes, more than the $MAX_RESPONSE_BYTES bytes this app will read"
                    )
                }

                // A chunked answer declares no length, so the size is checked
                // after the read for those.
                val text = body.string()
                if (text.length.toLong() > MAX_RESPONSE_BYTES) {
                    return parseFailure(
                        ProviderFailureKind.PARSE,
                        "the provider sent ${text.length} characters, more than the $MAX_RESPONSE_BYTES characters this app will read"
                    )
                }

                parse(text)
            }
        } catch (timeout: InterruptedIOException) {
            // A deadline that passed is how okio reports every timeout, the
            // socket read timeout among them.
            CompletionOutcome.Failure(
                ProviderFailure(
                    kind = ProviderFailureKind.TIMEOUT,
                    message = "the provider did not answer in time",
                    retryable = true
                )
            )
        } catch (network: IOException) {
            CompletionOutcome.Failure(
                ProviderFailure(
                    kind = ProviderFailureKind.NETWORK,
                    message = Redactor.redactText(network.message),
                    retryable = true
                )
            )
        } catch (unexpected: RuntimeException) {
            CompletionOutcome.Failure(
                ProviderFailure(
                    kind = ProviderFailureKind.PARSE,
                    message = "${unexpected.javaClass.simpleName}: ${Redactor.redactText(unexpected.message)}",
                    retryable = false
                )
            )
        }

    /** Returns the failure for a non-2xx response, or `null` when it succeeded. */
    protected fun failureFor(response: Response): ProviderFailure? {
        if (response.isSuccessful) return null
        val body = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
        return com.securewa.core.provider.ProviderFailureClassifier.classify(
            statusCode = response.code,
            redactedBody = Redactor.redactText(body.take(MAX_ERROR_BODY_CHARS))
        )
    }

    protected fun parseFailure(kind: ProviderFailureKind, detail: String): CompletionOutcome =
        CompletionOutcome.Failure(ProviderFailure(kind = kind, message = Redactor.redactText(detail), retryable = false))

    protected companion object {
        const val MAX_ERROR_BODY_CHARS = 500
        const val DEFAULT_MAX_OUTPUT_TOKENS = 1024

        /** 1 MiB. Enough for any answer an agent can use, small enough to bound memory. */
        const val MAX_RESPONSE_BYTES = 1_048_576L
    }
}
