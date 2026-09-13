package com.securewa.data.provider

import com.securewa.core.provider.AiMessage
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
 * The OpenAI chat completions wire format.
 *
 * OpenAI, OpenRouter, Groq, Ollama, vLLM and anything else that speaks this
 * shape share this adapter; only the base URL differs, which is why the provider
 * kind is OPENAI_COMPATIBLE rather than one class per vendor.
 */
class OpenAiCompatibleClient(http: OkHttpClient) : HttpProviderClient(http) {

    override suspend fun call(
        endpoint: ProviderEndpoint,
        request: CompletionRequest
    ): CompletionOutcome {
        val messages = JSONArray()
        request.systemInstruction?.takeIf { it.isNotBlank() }?.let { system ->
            messages.put(JSONObject().put("role", "system").put("content", system))
        }
        request.messages.forEach { messages.put(it.toJson()) }

        val body = JSONObject()
            .put("model", request.modelId)
            .put("messages", messages)
            .apply {
                request.temperatureMilli?.let { put("temperature", it / 1000.0) }
                request.maxOutputTokens?.let { put("max_tokens", it) }
                if (request.stopSequences.isNotEmpty()) {
                    put("stop", JSONArray(request.stopSequences))
                }
            }
            .toString()

        val call = jsonPost(
            url = endpoint.baseUrl.trimEnd('/') + "/chat/completions",
            apiKeyHeader = "Authorization" to "Bearer ${endpoint.apiKey}",
            body = body
        )

        return perform(call, ::parse)
    }

    private fun parse(response: Response): CompletionOutcome {
        val failure = failureFor(response)
        if (failure != null) return CompletionOutcome.Failure(failure)

        val payload = runCatching {
            JSONObject(response.body?.string().orEmpty())
        }.getOrNull() ?: return parseFailure(ProviderFailureKind.PARSE, "the provider returned a body that is not JSON")

        val choice = payload.optJSONArray("choices")?.optJSONObject(0)
            ?: return parseFailure(ProviderFailureKind.EMPTY_RESPONSE, "the provider returned no choices")

        val text = choice.optJSONObject("message")?.optString("content")
            ?.takeIf { it.isNotBlank() }
            ?: return parseFailure(ProviderFailureKind.EMPTY_RESPONSE, "the provider returned an empty message")

        val usage = payload.optJSONObject("usage")
        return CompletionOutcome.Success(
            CompletionResponse(
                text = text,
                modelId = payload.optString("model").takeIf { it.isNotBlank() },
                finishReason = choice.optString("finish_reason").takeIf { it.isNotBlank() },
                promptTokens = usage?.opt("prompt_tokens") as? Int,
                completionTokens = usage?.opt("completion_tokens") as? Int
            )
        )
    }

    private fun AiMessage.toJson(): JSONObject = JSONObject()
        .put("role", when (role) {
            MessageRole.SYSTEM -> "system"
            MessageRole.USER -> "user"
            MessageRole.ASSISTANT -> "assistant"
        })
        .put("content", content)
}
