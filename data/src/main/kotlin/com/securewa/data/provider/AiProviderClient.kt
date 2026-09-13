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
import java.net.SocketTimeoutException

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
     * Runs a call and turns transport problems into failures.
     *
     * A timeout is retryable, a rejected credential is not: the distinction is
     * what keeps the retry policy from hammering an endpoint that will keep
     * refusing.
     */
    protected fun perform(execRequest: Request, parse: (Response) -> CompletionOutcome): CompletionOutcome =
        try {
            http.newCall(execRequest).execute().use { response -> parse(response) }
        } catch (timeout: SocketTimeoutException) {
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
                    message = Redactor.redactText(unexpected.message),
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
    }
}
