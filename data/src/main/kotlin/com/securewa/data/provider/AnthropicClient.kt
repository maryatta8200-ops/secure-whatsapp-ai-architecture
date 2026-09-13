package com.securewa.data.provider

import com.securewa.core.provider.CompletionOutcome
import com.securewa.core.provider.CompletionRequest
import com.securewa.core.provider.CompletionResponse
import com.securewa.core.provider.MessageRole
import com.securewa.core.provider.ProviderFailureKind
import okhttp3.OkHttpClient
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Anthropic messages wire format.
 *
 * Two provider-specific details are handled here so the rest of the app does not
 * have to know them: the API version is a required header, and system text is a
 * separate field rather than a message role.
 */
class AnthropicClient(http: OkHttpClient) : HttpProviderClient(http) {

    override suspend fun call(
        endpoint: ProviderEndpoint,
        request: CompletionRequest
    ): CompletionOutcome {
        val systemText = buildList {
            request.systemInstruction?.takeIf { it.isNotBlank() }?.let { add(it) }
            request.messages.filter { it.role == MessageRole.SYSTEM }.forEach { add(it.content) }
        }.joinToString("\n\n")

        val messages = JSONArray()
        request.messages.filter { it.role != MessageRole.SYSTEM }.forEach { message ->
            messages.put(
                JSONObject()
                    .put("role", if (message.role == MessageRole.ASSISTANT) "assistant" else "user")
                    .put("content", message.content)
            )
        }

        val body = JSONObject()
            .put("model", request.modelId)
            // Anthropic requires an output budget; the agent default applies when
            // the user did not set one.
            .put("max_tokens", request.maxOutputTokens ?: DEFAULT_MAX_OUTPUT_TOKENS)
            .put("messages", messages)
            .apply {
                if (systemText.isNotBlank()) put("system", systemText)
                request.temperatureMilli?.let { put("temperature", it / 1000.0) }
                if (request.stopSequences.isNotEmpty()) {
                    put("stop_sequences", JSONArray(request.stopSequences))
                }
            }
            .toString()

        val call = jsonPost(
            url = endpoint.baseUrl.trimEnd('/') + "/v1/messages",
            apiKeyHeader = "x-api-key" to endpoint.apiKey,
            body = body,
            extraHeaders = mapOf("anthropic-version" to ANTHROPIC_VERSION)
        )

        return perform(call, ::parse)
    }

    private fun parse(response: Response): CompletionOutcome {
        val failure = failureFor(response)
        if (failure != null) return CompletionOutcome.Failure(failure)

        val payload = runCatching {
            JSONObject(response.body?.string().orEmpty())
        }.getOrNull() ?: return parseFailure(ProviderFailureKind.PARSE, "the provider returned a body that is not JSON")

        val content = payload.optJSONArray("content")
        val text = content?.let { array ->
            (0 until array.length())
                .mapNotNull { index -> array.optJSONObject(index) }
                .filter { it.optString("type", "text") == "text" }
                .joinToString("") { it.optString("text") }
        }?.takeIf { it.isNotBlank() }
            ?: return parseFailure(ProviderFailureKind.EMPTY_RESPONSE, "the provider returned no text content")

        val usage = payload.optJSONObject("usage")
        return CompletionOutcome.Success(
            CompletionResponse(
                text = text,
                modelId = payload.optString("model").takeIf { it.isNotBlank() },
                finishReason = payload.optString("stop_reason").takeIf { it.isNotBlank() },
                promptTokens = usage?.optIntOrNull("input_tokens"),
                completionTokens = usage?.optIntOrNull("output_tokens")
            )
        )
    }

    private fun JSONObject.optIntOrNull(name: String): Int? =
        if (has(name) && !isNull(name)) optInt(name) else null

    private companion object {
        const val ANTHROPIC_VERSION = "2023-06-01"
    }
}
