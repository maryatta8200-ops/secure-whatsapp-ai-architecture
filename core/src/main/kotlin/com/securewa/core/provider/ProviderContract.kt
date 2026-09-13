package com.securewa.core.provider

import com.securewa.core.routing.ProviderKind

/**
 * The provider-agnostic contract every AI adapter implements.
 *
 * Nothing here knows how a provider serialises a request. Keeping the contract
 * in the dependency-free domain module means the pipeline, the routing layer and
 * the fallback logic can be written and tested without any HTTP, and a new
 * provider is added by writing one adapter rather than editing the message path.
 */

enum class MessageRole {
    SYSTEM,
    USER,
    ASSISTANT
}

data class AiMessage(
    val role: MessageRole,
    val content: String
)

/**
 * One completion request.
 *
 * Temperature is carried in thousandths (`700` = 0.7) rather than as a float:
 * the value is persisted, compared in configuration digests and logged, and
 * integers avoid both rounding drift and locale-dependent formatting.
 */
data class CompletionRequest(
    val modelId: String,
    val messages: List<AiMessage>,
    val systemInstruction: String? = null,
    val temperatureMilli: Int? = null,
    val maxOutputTokens: Int? = null,
    val stopSequences: List<String> = emptyList()
) {
    /** Approximate size of the prompt, used by cost and size guards before a call is made. */
    fun characterCount(): Int =
        messages.sumOf { it.content.length } + (systemInstruction?.length ?: 0)
}

data class CompletionResponse(
    val text: String,
    val modelId: String? = null,
    val finishReason: String? = null,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null
)

/** How a provider call failed. These are the categories fallback policy reasons from. */
enum class ProviderFailureKind {
    AUTHENTICATION,
    RATE_LIMITED,
    INVALID_REQUEST,
    SERVER_ERROR,
    TIMEOUT,
    NETWORK,
    PARSE,
    EMPTY_RESPONSE,
    /**
     * The provider was never called: no credential in the slot, a locked vault,
     * a disabled provider, or a base URL no credential may be sent to. Distinct
     * from [NETWORK] because nothing left the device, and from [INVALID_REQUEST]
     * because the request was never shown to be wrong.
     */
    UNAVAILABLE
}

/**
 * One provider failure.
 *
 * [message] must already be redacted by the caller: provider error bodies can
 * echo the request, and the request contains message content and an API key
 * header.
 */
data class ProviderFailure(
    val kind: ProviderFailureKind,
    val statusCode: Int? = null,
    val message: String = "",
    val retryable: Boolean = false
)

sealed interface CompletionOutcome {
    data class Success(val response: CompletionResponse) : CompletionOutcome
    data class Failure(val failure: ProviderFailure) : CompletionOutcome
}

/**
 * Maps an HTTP status to a failure kind and to whether retrying could succeed.
 *
 * Retrying a 401 or a 400 can only burn quota and delay the user; retrying a
 * 429 or a 503 can genuinely succeed. This is decided once, here, and tested,
 * rather than being re-decided inside each adapter.
 */
object ProviderFailureClassifier {

    fun classify(statusCode: Int, redactedBody: String = ""): ProviderFailure = when (statusCode) {
        401, 403 -> ProviderFailure(
            kind = ProviderFailureKind.AUTHENTICATION,
            statusCode = statusCode,
            message = redactedBody.ifBlank { "the provider rejected the credential" },
            retryable = false
        )
        408, 409 -> ProviderFailure(
            kind = ProviderFailureKind.TIMEOUT,
            statusCode = statusCode,
            message = redactedBody.ifBlank { "the provider timed out" },
            retryable = true
        )
        429 -> ProviderFailure(
            kind = ProviderFailureKind.RATE_LIMITED,
            statusCode = statusCode,
            message = redactedBody.ifBlank { "the provider rate limited this credential" },
            retryable = true
        )
        in 400..499 -> ProviderFailure(
            kind = ProviderFailureKind.INVALID_REQUEST,
            statusCode = statusCode,
            message = redactedBody.ifBlank { "the provider rejected the request" },
            retryable = false
        )
        in 500..599 -> ProviderFailure(
            kind = ProviderFailureKind.SERVER_ERROR,
            statusCode = statusCode,
            message = redactedBody.ifBlank { "the provider returned a server error" },
            retryable = true
        )
        else -> ProviderFailure(
            kind = ProviderFailureKind.SERVER_ERROR,
            statusCode = statusCode,
            message = redactedBody.ifBlank { "unexpected provider status $statusCode" },
            retryable = false
        )
    }

    /** Which provider kinds are known to the routing layer. */
    fun supports(kind: ProviderKind): Boolean = kind in setOf(
        ProviderKind.GEMINI,
        ProviderKind.OPENAI,
        ProviderKind.ANTHROPIC,
        ProviderKind.OPENAI_COMPATIBLE
    )
}

/**
 * Validates a request before it is sent.
 *
 * Checking here means an invalid configuration produces a clear, local failure
 * instead of a paid round trip that returns an error the user has to interpret.
 */
object ProviderRequestValidator {

    const val MAX_CHARACTERS = 200_000
    const val MAX_OUTPUT_TOKENS = 32_000

    /** Returns the problems with [request], empty when it is acceptable. */
    fun validate(request: CompletionRequest): List<String> {
        val problems = mutableListOf<String>()
        if (request.modelId.isBlank()) problems += "a model must be selected"
        if (request.messages.isEmpty()) problems += "at least one message is required"
        if (request.messages.any { it.content.isBlank() }) problems += "a message is empty"
        if (request.systemInstruction != null && request.systemInstruction.isBlank()) {
            problems += "the system instruction is empty"
        }
        request.temperatureMilli?.let { temperature ->
            if (temperature !in 0..2_000) {
                problems += "temperature must be between 0 and 2.000"
            }
        }
        request.maxOutputTokens?.let { tokens ->
            if (tokens <= 0 || tokens > MAX_OUTPUT_TOKENS) {
                problems += "maximum output tokens must be between 1 and $MAX_OUTPUT_TOKENS"
            }
        }
        if (request.characterCount() > MAX_CHARACTERS) {
            problems += "the prompt is ${request.characterCount()} characters, limit is $MAX_CHARACTERS"
        }
        return problems
    }

    fun isValid(request: CompletionRequest): Boolean = validate(request).isEmpty()
}
