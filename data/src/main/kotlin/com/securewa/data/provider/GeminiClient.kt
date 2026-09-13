package com.securewa.data.provider

import com.securewa.core.provider.CompletionOutcome
import com.securewa.core.provider.CompletionRequest
import com.securewa.core.provider.CompletionResponse
import com.securewa.core.provider.MessageRole
import com.securewa.core.provider.ProviderFailureKind
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Gemini generateContent wire format.
 *
 * The API key travels in the `x-goog-api-key` header rather than in a query
 * parameter: the project's rule is that no credential ever appears in a URL,
 * because URLs end up in logs, proxies and crash reports.
 */
class GeminiClient(http: OkHttpClient) : HttpProviderClient(http) {

    override suspend fun call(
        endpoint: ProviderEndpoint,
        request: CompletionRequest
    ): CompletionOutcome {
        val systemText = buildList {
            request.systemInstruction?.takeIf { it.isNotBlank() }?.let { add(it) }
            request.messages.filter { it.role == MessageRole.SYSTEM }.forEach { add(it.content) }
        }.joinToString("\n\n")

        val contents = JSONArray()
        request.messages.filter { it.role != MessageRole.SYSTEM }.forEach { message ->
            contents.put(
                JSONObject()
                    .put("role", if (message.role == MessageRole.ASSISTANT) "model" else "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", message.content)))
            )
        }

        val body = JSONObject()
            .put("contents", contents)
            .apply {
                if (systemText.isNotBlank()) {
                    put(
                        "systemInstruction",
                        JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemText)))
                    )
                }
                val config = JSONObject()
                request.temperatureMilli?.let { config.put("temperature", it / 1000.0) }
                request.maxOutputTokens?.let { config.put("maxOutputTokens", it) }
                if (request.stopSequences.isNotEmpty()) {
                    config.put("stopSequences", JSONArray(request.stopSequences))
                }
                if (config.length() > 0) put("generationConfig", config)
            }
            .toString()

        val call = jsonPost(
            url = endpoint.baseUrl.trimEnd('/') + "/models/${request.modelId}:generateContent",
            apiKeyHeader = "x-goog-api-key" to endpoint.apiKey,
            body = body
        )

        return perform(call, ::parse)
    }

    private fun parse(body: String): CompletionOutcome {
        val payload = runCatching { JSONObject(body) }
            .getOrNull() ?: return parseFailure(ProviderFailureKind.PARSE, "the provider returned a body that is not JSON")

        val candidate = payload.optJSONArray("candidates")?.optJSONObject(0)
            ?: return parseFailure(ProviderFailureKind.EMPTY_RESPONSE, "the provider returned no candidates")

        val text = candidate.optJSONObject("content")?.optJSONArray("parts")?.let { parts ->
            (0 until parts.length()).joinToString("") { index -> parts.optJSONObject(index)?.optString("text").orEmpty() }
        }?.takeIf { it.isNotBlank() }
            ?: return parseFailure(ProviderFailureKind.EMPTY_RESPONSE, "the provider returned an empty candidate")

        val usage = payload.optJSONObject("usageMetadata")
        return CompletionOutcome.Success(
            CompletionResponse(
                text = text,
                modelId = payload.optString("modelVersion").takeIf { it.isNotBlank() },
                finishReason = candidate.optString("finishReason").takeIf { it.isNotBlank() },
                promptTokens = usage?.optIntOrNull("promptTokenCount"),
                completionTokens = usage?.optIntOrNull("candidatesTokenCount")
            )
        )
    }

    private fun JSONObject.optIntOrNull(name: String): Int? =
        if (has(name) && !isNull(name)) optInt(name) else null
}
